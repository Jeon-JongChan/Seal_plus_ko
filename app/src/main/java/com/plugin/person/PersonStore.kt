package com.plugin.person

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

/** 원본 이미지 대신 재디코딩에 필요한 좌표·시각과 모델 벡터만 저장합니다. */
@Serializable
data class PersonStoredFace(val vector: FloatArray, val issues: List<String> = emptyList(),
    val landmarks: FloatArray = floatArrayOf(), val bounds: FloatArray = floatArrayOf(),
    val trackId: Int? = null, val intervals: List<PersonInterval> = emptyList(), val frames: Int = 1,
    val representativeMs: Long = 0)

/** 모델 해시·전처리 버전을 포함한 한 원본의 재사용 가능한 분석 결과입니다. */
@Serializable
data class PersonRecord(val fingerprint: String, val video: Boolean, val faces: List<PersonStoredFace>,
    val version: Int = 1, val modelId: String = "", val modelVersion: String = "",
    val preprocessing: String = "sface-five-landmark-similarity-v1", val dimensions: Int = 128)

/** 파일 식별과 분석 결과를 연결하며 URI·크기·변경 시각을 저장합니다. */
@Serializable
data class PersonSource(val uri: String, val content: String, val bytes: Long, val modified: Long,
    val video: Boolean)

/** 프로세스 종료 후에도 재개할 수 있는 단일 파일 분석 요청입니다. */
data class PersonPending(val id: Long, val uri: String, val model: String, val state: String, val error: String)

/** 재추론을 피하는 로컬 분석 캐시이며 이전 암호화 자료도 읽을 수 있습니다. */
class PersonStore(private val context: Context, name: String = "analysis.db") : SQLiteOpenHelper(context,
    File(context.noBackupFilesDir,"person-ai/$name").apply { parentFile?.mkdirs() }.path,null,3) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val alias = context.packageName+".person-ai.aes-v1"
    // 행마다 Keystore 메타데이터를 다시 조회하지 않고 비추출 키 참조만 재사용합니다.
    private val encryptionKey by lazy { key() }
    private val keyFile = android.util.AtomicFile(File(context.noBackupFilesDir,"person-ai/$name.key"))
    private val storageId = File(context.noBackupFilesDir,"person-ai/$name").absolutePath
    private var epoch = lock.withLock { epochs.getOrPut(storageId) { 0L } }
    private val dataKey by lazy { dataKey() }

    companion object {
        private val lock = ReentrantLock()
        private val epochs = mutableMapOf<String,Long>()
        private const val INDEX_CHUNK = 1_000

        /** 경로 자체는 DB의 평문 인덱스에 기록하지 않습니다. */
        fun sourceKey(uri: String): String = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** 파일·모델별 캐시와 요청 큐는 원본 다운로드 DB와 분리합니다. */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE analysis(id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, model TEXT NOT NULL, data BLOB NOT NULL, UNIQUE(content,model))")
        db.execSQL("CREATE INDEX analysis_model ON analysis(model)")
        db.execSQL("CREATE TABLE faces(id INTEGER PRIMARY KEY AUTOINCREMENT, owner INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE, position INTEGER NOT NULL, data BLOB NOT NULL, UNIQUE(owner,position))")
        db.execSQL("CREATE TABLE sources(id TEXT PRIMARY KEY NOT NULL, content TEXT NOT NULL, data BLOB NOT NULL)")
        db.execSQL("CREATE INDEX sources_content ON sources(content)")
        db.execSQL("CREATE TABLE queue(id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, model TEXT NOT NULL, data BLOB NOT NULL, state TEXT NOT NULL, error TEXT NOT NULL DEFAULT '', priority INTEGER NOT NULL DEFAULT 0, UNIQUE(source,model))")
        db.execSQL("CREATE TABLE search_index(model TEXT NOT NULL, part INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(model,part))")
        db.execSQL("CREATE TABLE index_ready(model TEXT PRIMARY KEY NOT NULL, entries INTEGER NOT NULL)")
        createCheckpoints(db)
    }

    /** 원본 분석 행 삭제 시 하위 얼굴도 같은 트랜잭션에서 정리합니다. */
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    /** 기존 특징·검색 자료를 유지하면서 재개 테이블과 변경 번호를 이관합니다. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion == 3) { "지원하지 않는 얼굴 분석 DB 버전입니다." }
        if (oldVersion == 1) createCheckpoints(db)
        else db.execSQL("ALTER TABLE video_checkpoint_tracks ADD COLUMN revision INTEGER NOT NULL DEFAULT -1")
    }

    /** 트랙별 행으로 나누어 긴 영상도 단일 CursorWindow 크기를 넘지 않습니다. */
    private fun createCheckpoints(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE video_checkpoint(id INTEGER PRIMARY KEY, source TEXT NOT NULL, model TEXT NOT NULL, data BLOB NOT NULL, UNIQUE(source,model))")
        db.execSQL("CREATE TABLE video_checkpoint_tracks(owner INTEGER NOT NULL REFERENCES video_checkpoint(id) ON DELETE CASCADE, position INTEGER NOT NULL, data BLOB NOT NULL, revision INTEGER NOT NULL DEFAULT -1, PRIMARY KEY(owner,position))")
    }

    /** 앱별 비추출 AES 키는 Android Keystore에서 생성·재사용합니다. */
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    /** 비추출 Keystore 키로 감싼 데이터 키를 사용해 대량 행마다 보안 하드웨어를 호출하지 않습니다. */
    private fun dataKey(): SecretKey {
        val aad = "data-key:${keyFile.baseFile.name}"
        if (keyFile.baseFile.exists() || File(keyFile.baseFile.path+".bak").exists()) {
            val wrapped = keyFile.openRead().use { input ->
                require(input.channel.size() == 62L) { "얼굴 저장 암호키 크기가 다릅니다." }
                input.readBytes()
            }
            require(wrapped.isNotEmpty() && wrapped[0] == 1.toByte()) { "얼굴 저장 암호키가 손상되었습니다." }
            val bytes = unseal(wrapped,aad,encryptionKey)
            try { require(bytes.size == 32); return SecretKeySpec(bytes,"AES") } finally { bytes.fill(0) }
        }
        // V2 데이터가 있는데 키만 사라졌다면 새 키로 덮어써 복구 가능성을 없애지 않습니다.
        if (File(storageId).isFile) {
            for (table in listOf("analysis","faces","sources","queue","search_index","video_checkpoint","video_checkpoint_tracks")) {
                readableDatabase.rawQuery("SELECT 1 FROM $table WHERE substr(data,1,1)=X'02' LIMIT 1",null).use {
                    check(!it.moveToFirst()) { "암호키 파일이 없습니다. 기존 얼굴 데이터는 자동으로 덮어쓰지 않습니다." }
                }
            }
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val wrapped = seal(bytes,aad,encryptionKey,1)
            val output = keyFile.startWrite()
            try { output.write(wrapped); keyFile.finishWrite(output) }
            catch (failure: Throwable) { keyFile.failWrite(output); throw failure }
            return SecretKeySpec(bytes,"AES")
        } finally { bytes.fill(0) }
    }

    /** 신규 캐시는 암호화 없이 저장하고 형식 번호로 이전 암호화 행과 구분합니다. */
    @Suppress("UNUSED_PARAMETER")
    private fun encodeData(data: ByteArray, aad: String): ByteArray = byteArrayOf(0)+data

    /** 키 래핑과 데이터 암호화에 같은 무작위 IV·인증 규칙을 적용합니다. */
    private fun seal(data: ByteArray, aad: String, key: SecretKey, version: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key); cipher.updateAAD(aad.toByteArray())
        check(cipher.iv.size == 12)
        return byteArrayOf(version.toByte(),12)+cipher.iv+cipher.doFinal(data)
    }

    /** 인증 실패·형식 손상을 정상 캐시 적중으로 취급하지 않습니다. */
    private fun decodeData(data: ByteArray, aad: String): ByteArray {
        if (data.firstOrNull() == 0.toByte()) return data.copyOfRange(1,data.size)
        require(data.size >= 30 && data[0].toInt() in 1..2) { "얼굴 저장 데이터 형식이 다릅니다." }
        return unseal(data,aad,if (data[0] == 1.toByte()) encryptionKey else dataKey)
    }

    /** 이전 Keystore 직접 암호화 행도 유지하면서 GCM 인증을 검증합니다. */
    private fun unseal(data: ByteArray, aad: String, key: SecretKey): ByteArray {
        require(data.size >= 30 && data[1] == 12.toByte()) { "얼굴 저장 데이터 형식이 다릅니다." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,data.copyOfRange(2,14)))
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(data,14,data.size-14)
    }

    /** 동의 철회와 백그라운드 저장이 겹쳐도 새 생체정보를 기록하지 않습니다. */
    private fun requireConsent() {
        check(com.plugin.compatibility.TemporarySession.root == null && !com.plugin.compatibility.TemporarySession.changing) {
            "임시 공간에서는 영구 얼굴 저장·검색을 사용하지 않습니다."
        }
        check(epoch == epochs[storageId]) { "저장 데이터가 삭제되었습니다. 분석을 새로 시작하세요." }
    }

    /** 헤더는 즉시 확인하고 얼굴은 저장소가 열린 동안 32행씩 검증·복원합니다. */
    fun load(content: String, fingerprint: String): PersonRecord? = lock.withLock {
        requireConsent()
        readableDatabase.rawQuery("SELECT id,data FROM analysis WHERE content=? AND model=?",arrayOf(content,fingerprint)).use { cursor ->
            if (!cursor.moveToFirst()) return@withLock null
            val plaintext = decodeData(cursor.getBlob(1),"analysis:$content:$fingerprint")
            try {
                val header = json.decodeFromString<PersonRecord>(plaintext.toString(Charsets.UTF_8))
                validate(header.copy(faces=emptyList()),fingerprint)
                header.copy(faces=facePages(cursor.getLong(0),content,fingerprint))
            }
            finally { plaintext.fill(0) }
        }
    }

    /** 영구 캐시도 전체 얼굴·메타데이터를 한꺼번에 복호화하지 않습니다. */
    private fun facePages(owner: Long, content: String, fingerprint: String): List<PersonStoredFace> {
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM faces WHERE owner=?",arrayOf(owner.toString())).use { it.moveToFirst(); it.getInt(0) }
        return object : AbstractList<PersonStoredFace>() {
            private val cache = android.util.LruCache<Int,List<PersonStoredFace>>(4)
            override val size: Int get() = count
            /** 한 페이지 안에서만 행 인증·좌표·벡터 규격을 검사합니다. */
            override fun get(index: Int): PersonStoredFace = lock.withLock {
                requireConsent()
                if (index !in 0 until count) throw IndexOutOfBoundsException(index.toString())
                val page = index/32
                val faces = cache.get(page) ?: readableDatabase.rawQuery("SELECT position,data FROM faces WHERE owner=? AND position>=? AND position<? ORDER BY position",
                    arrayOf(owner.toString(),(page*32).toString(),minOf(count.toLong(),(page+1L)*32).toString())).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val position = cursor.getInt(0); check(position == page*32+size)
                            val plain = decodeData(cursor.getBlob(1),"face:$content:$fingerprint:$position")
                            val face = try { json.decodeFromString<PersonStoredFace>(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
                            validateFace(face); add(face)
                        }
                        check(size == minOf(32,count-page*32)) { "저장된 얼굴 페이지가 누락되었습니다." }
                    }.also { cache.put(page,it) }
                }
                faces[index%32]
            }
        }
    }

    /** 비정상 벡터·서로 다른 모델·지원하지 않는 버전은 저장 전에 거절합니다. */
    private fun validate(record: PersonRecord, fingerprint: String) {
        require(record.version == 1 && record.fingerprint == fingerprint && record.dimensions == 128 &&
            record.preprocessing == "sface-five-landmark-similarity-v1")
        record.faces.forEach(::validateFace)
    }

    /** 영구 저장과 페이지 복원에 같은 얼굴 규격을 적용합니다. */
    private fun validateFace(face: PersonStoredFace) {
        require(face.vector.size == 128 && face.frames > 0 && face.representativeMs >= 0)
        require(face.landmarks.isEmpty() || face.landmarks.size == 10 && face.landmarks.all { it.isFinite() })
        require(face.bounds.isEmpty() || face.bounds.size == 4 && face.bounds.all { it.isFinite() })
        require(face.intervals.all { it.startMs >= 0 && it.endMs >= it.startMs })
        PersonMath.normalize(face.vector)
    }

    /** 한 파일 분석을 원자적으로 확정하고 해당 모델의 파생 인덱스만 무효화합니다. */
    fun save(content: String, record: PersonRecord) = lock.withLock {
        requireConsent(); validate(record,record.fingerprint)
        val plain = json.encodeToString(record.copy(faces = emptyList())).toByteArray()
        val encrypted = try { encodeData(plain,"analysis:$content:${record.fingerprint}") } finally { plain.fill(0) }
        val db = writableDatabase; db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO analysis(content,model,data) VALUES(?,?,?)",
                arrayOf<Any>(content,record.fingerprint,encrypted))
            db.execSQL("UPDATE analysis SET data=? WHERE content=? AND model=?",arrayOf<Any>(encrypted,content,record.fingerprint))
            val owner = db.rawQuery("SELECT id FROM analysis WHERE content=? AND model=?",arrayOf(content,record.fingerprint)).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
            for ((position,face) in record.faces.withIndex()) {
                val payload = json.encodeToString(face).toByteArray()
                val blob = try { encodeData(payload,"face:$content:${record.fingerprint}:$position") } finally { payload.fill(0) }
                db.execSQL("INSERT OR IGNORE INTO faces(owner,position,data) VALUES(?,?,?)",arrayOf<Any>(owner,position,blob))
                db.execSQL("UPDATE faces SET data=? WHERE owner=? AND position=?",arrayOf<Any>(blob,owner,position))
            }
            db.delete("faces","owner=? AND position>=?",arrayOf(owner.toString(),record.faces.size.toString()))
            invalidate(record.fingerprint)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 같은 내용의 복사본은 모델 분석을 공유하되 접근 가능한 원본 URI를 각각 유지합니다. */
    fun bind(source: PersonSource) = lock.withLock {
        requireConsent()
        val id = sourceKey(source.uri)
        writableDatabase.execSQL("INSERT OR REPLACE INTO sources(id,content,data) VALUES(?,?,?)",
            arrayOf<Any>(id,source.content,encodeData(json.encodeToString(source).toByteArray(),"source:$id")))
    }

    /** 원본 연결과 분석 결과를 함께 확정해 중간 종료 시 고아 분석을 남기지 않습니다. */
    fun commit(source: PersonSource, record: PersonRecord) = lock.withLock {
        requireConsent()
        val db = writableDatabase; db.beginTransaction()
        try {
            save(source.content,record); bind(source); pruneOrphans()
            db.delete("video_checkpoint","source=? AND model=?",arrayOf(sourceKey(source.uri),record.fingerprint))
            db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
    }

    /** 재개 자료는 원본·모델별로 격리하고 마지막 완료 프레임의 전체 상태를 원자적으로 교체합니다. */
    fun saveVideoCheckpoint(fingerprint: String, state: PersonVideoCheckpoint) = lock.withLock {
        requireConsent(); validateCheckpoint(state,fingerprint)
        val source = sourceKey(state.source.uri)
        val plain = json.encodeToString(state.copy(tracks = emptyList())).toByteArray()
        val data = try { encodeData(plain,"video:$source:$fingerprint") } finally { plain.fill(0) }
        val db = writableDatabase; db.beginTransaction()
        try {
            val sameSource = db.rawQuery("SELECT data FROM video_checkpoint WHERE source=? AND model=?",arrayOf(source,fingerprint)).use { cursor ->
                if (!cursor.moveToFirst()) false else {
                    val payload = decodeData(cursor.getBlob(0),"video:$source:$fingerprint")
                    try { json.decodeFromString<PersonVideoCheckpoint>(payload.toString(Charsets.UTF_8)).source == state.source }
                    finally { payload.fill(0) }
                }
            }
            db.execSQL("INSERT OR IGNORE INTO video_checkpoint(source,model,data) VALUES(?,?,?)",arrayOf<Any>(source,fingerprint,data))
            db.execSQL("UPDATE video_checkpoint SET data=? WHERE source=? AND model=?",arrayOf<Any>(data,source,fingerprint))
            val owner = db.rawQuery("SELECT id FROM video_checkpoint WHERE source=? AND model=?",arrayOf(source,fingerprint)).use {
                check(it.moveToFirst()); it.getLong(0)
            }
            if (!sameSource) db.delete("video_checkpoint_tracks","owner=?",arrayOf(owner.toString()))
            val revisions = db.rawQuery("SELECT position,revision FROM video_checkpoint_tracks WHERE owner=?",arrayOf(owner.toString())).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getInt(0),cursor.getLong(1)) }
            }
            state.tracks.forEachIndexed { index,track ->
                // 프레임 수가 그대로면 활성 표본도 그대로이며, 종료 확정은 별도 비트로 구분합니다.
                val revision = track.frames.toLong()*2+if (track.completed) 1 else 0
                if (revisions[index] == revision) return@forEachIndexed
                val payload = json.encodeToString(track).toByteArray()
                val blob = try { encodeData(payload,"video-track:$source:$fingerprint:$index") } finally { payload.fill(0) }
                db.execSQL("INSERT OR REPLACE INTO video_checkpoint_tracks(owner,position,data,revision) VALUES(?,?,?,?)",arrayOf<Any>(owner,index,blob,revision))
            }
            db.delete("video_checkpoint_tracks","owner=? AND position>=?",arrayOf(owner.toString(),state.tracks.size.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 원본 변경·모델 불일치 시 재개하지 않으며 인증·형식 손상은 개별 실패로 전달합니다. */
    fun videoCheckpoint(source: PersonSource, fingerprint: String): PersonVideoCheckpoint? = lock.withLock {
        requireConsent(); val id = sourceKey(source.uri)
        readableDatabase.rawQuery("SELECT id,data FROM video_checkpoint WHERE source=? AND model=?",arrayOf(id,fingerprint)).use { cursor ->
            if (!cursor.moveToFirst()) return@withLock null
            val plain = decodeData(cursor.getBlob(1),"video:$id:$fingerprint")
            val header = try { json.decodeFromString<PersonVideoCheckpoint>(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
            if (header.source != source) return@withLock null
            val tracks = readableDatabase.rawQuery("SELECT position,data FROM video_checkpoint_tracks WHERE owner=? ORDER BY position",arrayOf(cursor.getLong(0).toString())).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        require(rows.getInt(0) == size)
                        val payload = decodeData(rows.getBlob(1),"video-track:$id:$fingerprint:$size")
                        try { add(json.decodeFromString<PersonVideoTrackState>(payload.toString(Charsets.UTF_8))) } finally { payload.fill(0) }
                    }
                }
            }
            header.copy(tracks = tracks).also { validateCheckpoint(it,fingerprint) }
        }
    }

    /** 손상된 시각·표본·좌표를 네이티브 디코더와 추적기에 전달하지 않습니다. */
    private fun validateCheckpoint(state: PersonVideoCheckpoint, fingerprint: String) {
        require(state.version == 1 && state.source.video && state.durationMs in 1..Long.MAX_VALUE/1000-60_000)
        require(state.stepMs in 1..1000 && state.nextUs in 1..state.durationMs*1000+1_000_000)
        require(state.signature.size == 256 && state.signature.all { it.isFinite() && it in 0f..255f })
        require(state.trackCount == state.tracks.size)
        state.tracks.forEachIndexed { index,track ->
            require(track.id == index+1 && track.frames > 0 && track.lastMs in 0..state.nextUs/1000)
            require(track.bounds.size == 4 && track.bounds.all { it.isFinite() } && track.bounds[2]>track.bounds[0] && track.bounds[3]>track.bounds[1])
            require(track.samples.size in 1..5 && track.intervals.isNotEmpty())
            require(!track.completed || track.samples.size == 1)
            require(track.intervals.all { it.startMs >= 0 && it.endMs in it.startMs..track.lastMs })
            validate(PersonRecord(fingerprint,true,track.samples),fingerprint)
        }
    }

    /** 현재 원본의 변경 여부를 비교할 수 있는 메타데이터를 복원합니다. */
    fun source(uri: String): PersonSource? = lock.withLock {
        requireConsent(); val id = sourceKey(uri)
        readableDatabase.rawQuery("SELECT data FROM sources WHERE id=?",arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@withLock null
            val plain = decodeData(cursor.getBlob(0),"source:$id")
            try { json.decodeFromString<PersonSource>(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
        }
    }

    /** 파일 삭제·권한 철회 후 다른 복사본도 없는 생체정보와 파생 인덱스를 정리합니다. */
    fun forget(uri: String) = lock.withLock {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.delete("sources","id=?",arrayOf(sourceKey(uri)))
            db.delete("video_checkpoint","source=?",arrayOf(sourceKey(uri)))
            pruneOrphans()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 모델 전환은 다른 모델의 캐시를 보존하고 명시적 삭제만 해당 영역을 비웁니다. */
    fun clear(fingerprint: String? = null) = lock.withLock {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.delete("analysis",if (fingerprint == null) null else "model=?",fingerprint?.let { arrayOf(it) })
            db.delete("search_index",if (fingerprint == null) null else "model=?",fingerprint?.let { arrayOf(it) })
            db.delete("index_ready",if (fingerprint == null) null else "model=?",fingerprint?.let { arrayOf(it) })
            db.delete("video_checkpoint",if (fingerprint == null) null else "model=?",fingerprint?.let { arrayOf(it) })
            if (fingerprint == null) { db.delete("sources",null,null); db.delete("queue",null,null) }
            else {
                db.execSQL("DELETE FROM sources WHERE content NOT IN (SELECT content FROM analysis)")
                PersonModels.models.firstOrNull { it.fingerprint == fingerprint }?.let { model -> db.delete("queue","model=?",arrayOf(model.id)) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // 삭제 이전에 열린 다른 분석기가 늦게 결과를 다시 저장하지 못하게 합니다.
        epoch = (epochs[storageId] ?: 0L)+1
        epochs[storageId] = epoch
    }

    /** 신규·변경 파일을 중복 요청 없이 영속 큐에 넣고 실패 파일만 재시도할 수 있습니다. */
    fun enqueue(uri: String, model: String, priority: Int = 0, force: Boolean = false) = lock.withLock {
        requireConsent(); val id = sourceKey(uri)
        val selected = PersonModels.models.singleOrNull { it.id == model } ?: error("지원하지 않는 분석 모델입니다.")
        val payload = encodeData(uri.toByteArray(),"queue:$id:$model")
        val db = writableDatabase; db.beginTransaction()
        try {
        if (force) {
            // 명시적 실패 재시도는 해당 내용·모델의 손상 캐시와 URI 연결만 다시 만듭니다.
            val content = readableDatabase.rawQuery("SELECT content FROM sources WHERE id=?",arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (content != null) writableDatabase.delete("analysis","content=? AND model=?",arrayOf(content,selected.fingerprint))
            writableDatabase.delete("sources","id=?",arrayOf(id)); invalidate(selected.fingerprint)
            writableDatabase.delete("video_checkpoint","source=? AND model=?",arrayOf(id,selected.fingerprint))
        }
        writableDatabase.execSQL("INSERT OR IGNORE INTO queue(source,model,data,state,priority) VALUES(?,?,?,'pending',?)",
            arrayOf<Any>(id,model,payload,priority))
        writableDatabase.execSQL("UPDATE queue SET state='pending',error='',priority=? WHERE source=? AND model=?",arrayOf<Any>(priority,id,model))
        db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 이전 프로세스의 실행 중 요청만 다시 대기시키며 완료 항목은 재추론하지 않습니다. */
    fun recover() = lock.withLock { writableDatabase.execSQL("UPDATE queue SET state='pending' WHERE state='running'") }

    /** 우선순위·등록 순으로 다음 파일 하나만 메모리에 올립니다. */
    fun next(): PersonPending? = lock.withLock {
        requireConsent()
        readableDatabase.rawQuery("SELECT id,source,model,data,state,error FROM queue WHERE state='pending' ORDER BY priority DESC,id LIMIT 1",null).use { cursor ->
            if (!cursor.moveToFirst()) return@withLock null
            val plain = decodeData(cursor.getBlob(3),"queue:${cursor.getString(1)}:${cursor.getString(2)}")
            try { PersonPending(cursor.getLong(0),plain.toString(Charsets.UTF_8),cursor.getString(2),cursor.getString(4),cursor.getString(5)) }
            finally { plain.fill(0) }
        }
    }

    /** 파일 단위 진행 상태를 남겨 취소·재시작·개별 실패를 구분합니다. */
    fun status(id: Long, state: String, error: String = "") = lock.withLock {
        require(state in setOf("pending","running","done","failed"))
        writableDatabase.execSQL("UPDATE queue SET state=?,error=? WHERE id=?",arrayOf<Any>(state,error.take(300),id))
    }

    /** 현재 큐와 모델별 얼굴 수를 UI·측정에 제공하며 생체정보는 노출하지 않습니다. */
    fun counts(): String = lock.withLock {
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM queue GROUP BY state",null).use { cursor ->
            buildList { while (cursor.moveToNext()) add("${cursor.getString(0)} ${cursor.getInt(1)}") }.joinToString(" · ")
        }
    }

    /** 실패 목록은 페이지별로만 복호화하여 대량 큐를 UI 메모리에 모두 올리지 않습니다. */
    fun failed(offset: Int = 0): List<PersonPending> = lock.withLock {
        requireConsent(); require(offset >= 0)
        readableDatabase.rawQuery("SELECT id,source,model,data,state,error FROM queue WHERE state='failed' ORDER BY id LIMIT 50 OFFSET ?",arrayOf(offset.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val plain = decodeData(cursor.getBlob(3),"queue:${cursor.getString(1)}:${cursor.getString(2)}")
                    try { add(PersonPending(cursor.getLong(0),plain.toString(Charsets.UTF_8),cursor.getString(2),cursor.getString(4),cursor.getString(5))) }
                    finally { plain.fill(0) }
                }
            }
        }
    }

    /** 접근 가능한 원본 연결이 없는 고아 분석만 정리합니다. */
    fun pruneOrphans() = lock.withLock {
        val db = writableDatabase; db.beginTransaction()
        try {
            val affected = db.rawQuery("SELECT DISTINCT model FROM analysis WHERE content NOT IN (SELECT content FROM sources)",null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            db.execSQL("DELETE FROM analysis WHERE content NOT IN (SELECT content FROM sources)")
            affected.forEach(::invalidate)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 평문 얼굴 데이터를 읽지 않고 저장 공간과 파일·얼굴 개수를 측정합니다. */
    fun statistics(): String = lock.withLock {
        val db = readableDatabase
        val files = db.rawQuery("SELECT COUNT(*) FROM sources",null).use { it.moveToFirst(); it.getLong(0) }
        val faces = db.rawQuery("SELECT COUNT(*) FROM faces",null).use { it.moveToFirst(); it.getLong(0) }
        val indexBytes = db.rawQuery("SELECT COALESCE(SUM(LENGTH(data)),0) FROM search_index",null).use { it.moveToFirst(); it.getLong(0) }
        "원본 ${files}개 · 얼굴 ${faces}개 · 검색 인덱스 ${indexBytes/1024}KB · DB ${File(db.path).length()/1024}KB"
    }

    /** 해당 모델의 검색 파생물만 지우며 원본 임베딩은 보존합니다. */
    private fun invalidate(fingerprint: String) {
        writableDatabase.delete("search_index","model=?",arrayOf(fingerprint))
        writableDatabase.delete("index_ready","model=?",arrayOf(fingerprint))
    }

    /** 파생 인덱스를 1,000개 벡터 단위로 암호화하여 CursorWindow와 메모리 한계를 지킵니다. */
    private fun index(fingerprint: String) {
        val db = writableDatabase
        db.rawQuery("SELECT entries FROM index_ready WHERE model=?",arrayOf(fingerprint)).use { if (it.moveToFirst()) return }
        var buffer = ByteArrayOutputStream(); var output = DataOutputStream(buffer)
        var part = 0; var inPart = 0; var total = 0
        /** 완성된 묶음만 DB에 저장하고 평문 벡터 버퍼를 비웁니다. */
        fun flush() {
            if (inPart == 0) return
            val data = buffer.toByteArray()
            java.nio.ByteBuffer.wrap(data).putInt(8,inPart)
            try { db.execSQL("INSERT INTO search_index(model,part,data) VALUES(?,?,?)",arrayOf<Any>(fingerprint,part,encodeData(data,"index:$fingerprint:$part"))) }
            finally { data.fill(0) }
            part++; inPart = 0; buffer = ByteArrayOutputStream(); output = DataOutputStream(buffer)
        }
        db.beginTransaction()
        try {
            invalidate(fingerprint)
            db.rawQuery("SELECT f.id,f.position,f.data,a.content FROM faces f JOIN analysis a ON f.owner=a.id WHERE a.model=? ORDER BY f.id",arrayOf(fingerprint)).use { cursor ->
                while (cursor.moveToNext()) {
                    if (inPart == 0) { output.writeInt(1); output.writeInt(128); output.writeInt(0) }
                    val plain = decodeData(cursor.getBlob(2),"face:${cursor.getString(3)}:$fingerprint:${cursor.getInt(1)}")
                    val face = try { json.decodeFromString<PersonStoredFace>(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
                    require(face.vector.size == 128); PersonMath.normalize(face.vector)
                    output.writeLong(cursor.getLong(0)); face.vector.forEach { output.writeFloat(it) }; face.vector.fill(0f)
                    inPart++; total++
                    if (inPart == INDEX_CHUNK) flush()
                }
            }
            flush()
            db.execSQL("INSERT INTO index_ready(model,entries) VALUES(?,?)",arrayOf<Any>(fingerprint,total))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 모든 크기에서 묶음 하나와 상위 K만 유지하며 정확한 코사인을 재계산합니다. */
    fun search(fingerprint: String, vector: FloatArray, count: Int): List<PersonSearch.Match> = lock.withLock {
        requireConsent(); require(vector.size == 128)
        PersonSearch(vector,count)
        for (attempt in 0..1) {
            index(fingerprint)
            val search = PersonSearch(vector,count)
            try {
                val expected = readableDatabase.rawQuery("SELECT entries FROM index_ready WHERE model=?",arrayOf(fingerprint)).use { check(it.moveToFirst()); it.getInt(0) }
                var seen = 0; var part = 0
                readableDatabase.rawQuery("SELECT part,data FROM search_index WHERE model=? ORDER BY part",arrayOf(fingerprint)).use { cursor ->
                    while (cursor.moveToNext()) {
                        require(cursor.getInt(0) == part)
                        val data = decodeData(cursor.getBlob(1),"index:$fingerprint:$part")
                        try {
                            java.nio.ByteBuffer.wrap(data).let { input ->
                                require(input.int == 1 && input.int == 128)
                                val total = input.int; require(total in 1..INDEX_CHUNK && data.size == 12+total*520)
                                val row = FloatArray(128)
                                try { repeat(total) { val id = input.long; for (i in row.indices) row[i] = input.float; search.offer(id,row) } }
                                finally { row.fill(0f) }
                                seen += total
                            }
                        } finally { data.fill(0) }
                        part++
                    }
                }
                require(seen == expected) { "검색 인덱스의 벡터 수가 다릅니다." }
                return@withLock search.results()
            } catch (failure: Exception) {
                invalidate(fingerprint)
                if (attempt == 1) throw failure
            }
        }
        error("얼굴 검색 인덱스를 복원할 수 없습니다.")
    }

    /** 검색 후보를 실제 파일·트랙 메타데이터와 연결합니다. */
    fun resolve(id: Long, fingerprint: String): Pair<PersonSource,PersonStoredFace>? = lock.withLock {
        requireConsent()
        readableDatabase.rawQuery("SELECT a.content,f.data,s.id,s.data,f.position FROM faces f JOIN analysis a ON f.owner=a.id JOIN sources s ON a.content=s.content WHERE f.id=? AND a.model=? LIMIT 1",
            arrayOf(id.toString(),fingerprint)).use { cursor ->
            if (!cursor.moveToFirst()) return@withLock null
            val recordBytes = decodeData(cursor.getBlob(1),"face:${cursor.getString(0)}:$fingerprint:${cursor.getInt(4)}")
            val sourceBytes = decodeData(cursor.getBlob(3),"source:${cursor.getString(2)}")
            try {
                val face = json.decodeFromString<PersonStoredFace>(recordBytes.toString(Charsets.UTF_8))
                validate(PersonRecord(fingerprint,false,listOf(face)),fingerprint)
                json.decodeFromString<PersonSource>(sourceBytes.toString(Charsets.UTF_8)) to face
            } finally { recordBytes.fill(0); sourceBytes.fill(0) }
        }
    }
}
