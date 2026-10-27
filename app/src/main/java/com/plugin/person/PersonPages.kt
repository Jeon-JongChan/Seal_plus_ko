package com.plugin.person

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.LruCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 화면 결과를 디스크에 두고 최근 32개씩 네 페이지만 보관하는 읽기 전용 목록입니다. */
class PersonPages private constructor(val file: File, private val uri: String, private val fingerprint: String) : AbstractList<PersonSample>(), AutoCloseable {
    private val database = try { SQLiteDatabase.openOrCreateDatabase(file,null) }
        catch (failure: Throwable) { SQLiteDatabase.deleteDatabase(file); throw failure }
    private val json = Json { encodeDefaults=true; ignoreUnknownKeys=true }
    private val cache = LruCache<Int,List<PersonSample>>(4)
    private var count = 0
    private var references = 1
    private var ownerClosed = false
    private var closed = false
    /** 동일한 내용을 가진 다른 결과 파일도 Compose 수명을 구분합니다. */
    val identity: String get() = file.path
    /** 페이지 조회 없이 이미 확정된 전체 결과 수를 반환합니다. */
    override val size: Int get() = count
    /** 회귀 검사에서 실제 캐시 상한을 확인합니다. */
    val cachedPages: Int get() = synchronized(this) { cache.size() }

    companion object {
        private val initialized = mutableSetOf<String>()

        /** 앱 전용 임시 파일을 완성한 경우에만 공개하며 중간 실패는 원본을 변경하지 않습니다. */
        suspend fun create(context: Context,uri: String,fingerprint: String,samples: Iterable<PersonSample>): PersonPages {
            val root = File(com.plugin.compatibility.TemporarySession.root ?: context.cacheDir.path,"person-results")
            check(root.isDirectory || root.mkdirs()) { "인물 결과 임시 폴더를 만들 수 없습니다." }
            synchronized(initialized) {
                val directory = root.canonicalPath
                if (directory !in initialized) {
                    // ponytail: 작성자는 앱 기본 프로세스입니다. 별도 프로세스로 분리할 때는 파일 잠금으로 생존 소유자를 확인해야 합니다.
                    root.listFiles { file -> file.isFile && file.name.startsWith("person-result-") && file.name.endsWith(".db") }
                        ?.forEach(SQLiteDatabase::deleteDatabase)
                    initialized += directory
                }
            }
            val pages = PersonPages(File.createTempFile("person-result-",".db",root),uri,fingerprint)
            try {
                pages.database.execSQL("CREATE TABLE results(position INTEGER PRIMARY KEY, data TEXT NOT NULL)")
                pages.database.beginTransaction()
                try {
                    pages.database.compileStatement("INSERT INTO results(position,data) VALUES(?,?)").use { statement ->
                        for (sample in samples) {
                            currentCoroutineContext().ensureActive()
                            require(sample.fingerprint == fingerprint && sample.vector.size == 128)
                            require(sample.sourceUri == null || sample.sourceUri == uri)
                            PersonMath.cosine(sample.vector,sample.vector)
                            statement.bindLong(1,pages.count.toLong())
                            statement.bindString(2,pages.json.encodeToString(stored(sample)))
                            statement.executeInsert(); pages.count = Math.addExact(pages.count,1)
                        }
                    }
                    pages.database.setTransactionSuccessful()
                } finally { pages.database.endTransaction() }
                return pages
            } catch (failure: Throwable) { pages.close(); throw failure }
        }

        /** 영구 저장에도 전체 결과를 복사하지 않고 필요한 행만 변환합니다. */
        fun stored(samples: List<PersonSample>): List<PersonStoredFace> = object : AbstractList<PersonStoredFace>() {
            override val size: Int get() = samples.size
            /** 현재 행만 원본 메타데이터·특징 저장 형식으로 옮깁니다. */
            override fun get(index: Int): PersonStoredFace = stored(samples[index])
        }

        /** 화면 표시와 영구 저장에서 같은 필드 대응을 사용합니다. */
        private fun stored(sample: PersonSample): PersonStoredFace = PersonStoredFace(sample.vector,sample.issues,
            sample.landmarks,sample.bounds,sample.trackId,sample.intervals,sample.frames,sample.representativeMs,sample.evidence)

        /** 디스크 목록은 소유권을 반환하고 일반 목록은 기존 메모리 정리 규칙을 따릅니다. */
        fun release(samples: List<PersonSample>, recyclePreviews: Boolean = false) {
            if (samples is PersonPages) samples.close()
            else samples.forEach { sample ->
                sample.vector.fill(0f)
                sample.evidence.forEach { it.vector.fill(0f) }
                if (recyclePreviews) sample.preview?.recycle()
            }
        }

        /** 같은 URI를 재사용한 두 입력은 공유 소유권을 한 번만 반환합니다. */
        fun release(first: List<PersonSample>,second: List<PersonSample>,recyclePreviews: Boolean = false) {
            release(first,recyclePreviews)
            if (second !== first) release(second,recyclePreviews)
        }
    }

    /** 임의 위치도 최대 한 페이지를 읽으며 페이지 밖 벡터는 장기 보관하지 않습니다. */
    @Synchronized
    override fun get(index: Int): PersonSample {
        check(!closed) { "인물 결과가 닫혔습니다. 다시 분석하세요." }
        if (index !in 0 until count) throw IndexOutOfBoundsException(index.toString())
        val page = index/32
        val rows = cache.get(page) ?: database.rawQuery("SELECT position,data FROM results WHERE position>=? AND position<? ORDER BY position",
            arrayOf((page*32).toString(),minOf(count.toLong(),(page+1L)*32).toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(cursor.getInt(0) == page*32+size)
                    val face = json.decodeFromString<PersonStoredFace>(cursor.getString(1))
                    require(face.vector.size == 128); PersonMath.cosine(face.vector,face.vector)
                    add(PersonSample(null,face.vector,face.issues,fingerprint,face.trackId,face.intervals,face.frames,
                        uri,face.landmarks,face.bounds,face.representativeMs,face.evidence))
                }
                check(size == minOf(32,count-page*32)) { "인물 결과 페이지가 누락되었습니다." }
            }.also { cache.put(page,it) }
        }
        return rows[index%32]
    }

    /** 화면이 이전 결과를 그리는 동안에는 파일을 유지하고 해제 콜백은 한 번만 적용합니다. */
    @Synchronized
    fun retain(): () -> Unit {
        check(!closed)
        references++
        var released = false
        return { synchronized(this) { if (!released) { released=true; releaseReference() } } }
    }

    /** 소유자와 화면의 참조가 모두 끝난 자체 임시 DB만 정리합니다. */
    private fun releaseReference() {
        references--
        if (references != 0) return
        closed = true
        cache.snapshot().values.forEach { rows -> rows.forEach { sample -> sample.vector.fill(0f); sample.evidence.forEach { it.vector.fill(0f) } } }; cache.evictAll()
        try { database.close() } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    /** 소유자 종료가 중복 호출되어도 화면 참조 수를 중복 감소시키지 않습니다. */
    @Synchronized
    override fun close() {
        if (ownerClosed) return
        ownerClosed = true; releaseReference()
    }
}
