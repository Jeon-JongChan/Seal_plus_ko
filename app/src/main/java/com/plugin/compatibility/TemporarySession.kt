package com.plugin.compatibility

import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Room
import com.junkfood.seal.App
import com.junkfood.seal.database.AppDatabase
import com.junkfood.seal.database.VideoInfoDao
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 기존 설정은 유지하고 다운로드 자료의 저장소만 임시 세션으로 전환합니다. */
object TemporarySession {
    private const val KEY = "temporary_download_session"
    private val json = Json { ignoreUnknownKeys = true }

    /** 원래 루트와 세션 소유 경계를 기록해 재실행·통합·삭제에서도 자료를 보호합니다. */
    @Serializable data class Session(val id: String, val base: String, val root: String, val video: String, val audio: String,
        val privateDirectory: Boolean = false)

    var session by mutableStateOf(runCatching { MMKV.defaultMMKV().decodeString(KEY) }.getOrNull()?.let { json.decodeFromString<Session>(it) })
        private set
    private val changes = MutableStateFlow(session)
    private var database: AppDatabase? = null
    private var databaseRoot: String? = null
    internal var normalDao: VideoInfoDao? = null
    private val stores = mutableMapOf<String, MMKV>()
    @Volatile var changing = false
        internal set
    val root: String? get() = session?.root

    /** 기록에 지정한 UUID 하위 폴더만 세션 소유 경로로 인정합니다. */
    fun owned(value: Session): File {
        require(value.id.matches(Regex("[a-f0-9-]{36}")))
        val expected = File(value.base, "tmp/session-${value.id}").absoluteFile
        val actual = File(value.root).canonicalFile
        check(expected.path == value.root && expected == actual) { "임시 폴더 경계가 변경되었습니다." }
        return actual
    }

    /** 원본 앱의 다운로드 루트 getter도 같은 임시 경로를 반환합니다. */
    @JvmStatic fun downloadPath(normal: String): String = session?.let { owned(it).path } ?: normal

    /** 새 작업은 임시 루트에만 저장하며 사용자 명령의 임의 출력 경로는 허용하지 않습니다. */
    fun preferences(value: DownloadPreferences): DownloadPreferences = if (session == null) value else
        value.copyPreservingPluginOptions { copy(privateDirectory = false, sdcard = false) }.copyPluginOptions(selectedFolder = null)

    /** 이름은 재사용하고 임시 메타데이터의 실제 파일만 tmp 안에 생성합니다. */
    @Synchronized fun storage(name: String): MMKV {
        val directory = session?.let { File(owned(it), ".data/mmkv").apply { check(isDirectory || mkdirs()) }.path }
        return stores.getOrPut("${directory.orEmpty()}:$name") {
            if (directory == null) MMKV.mmkvWithID(name) else MMKV.mmkvWithID(name, MMKV.SINGLE_PROCESS_MODE, null, directory)
        }
    }

    /** 일반 이력 DB는 유지하고 임시 이력 DB는 tmp의 내부 자료 폴더에 둡니다. */
    fun databasePath(): String = root?.let { File(it, ".data/app_database").path }
        ?: App.context.getDatabasePath("app_database").path

    /** 활성 변경은 대기열 지도와 함께 Snapshot에서 호출하고 흐름은 마지막에 알립니다. */
    fun publish(value: Session?) {
        if (value == null) MMKV.defaultMMKV().removeValueForKey(KEY)
        else check(MMKV.defaultMMKV().encode(KEY, json.encodeToString(value)))
        session = value
        PluginMediaMetadataStore.clearMemory()
        SocialPostMetadataStore.clearMemory()
        FrequentAccountStore.reload()
    }

    /** 화면의 기존 이력 구독도 새 저장소로 전환합니다. */
    fun notifyChange() { changes.value = session }

    /** tmp 삭제 전에 SQLite와 MMKV의 열린 파일을 닫습니다. */
    @Synchronized fun closeTemporary() {
        database?.close(); database = null; databaseRoot = null
        stores.filterKeys { !it.startsWith(":") }.values.forEach { it.close() }
        stores.keys.removeAll { !it.startsWith(":") }
    }

    /** tmp 대기열·통합 기록은 원자적 파일로 보존합니다. */
    fun write(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }

    /** 원본 DAO를 감싸 이력만 격리하고 쿠키·명령·앱 설정은 그대로 사용합니다. */
    @JvmStatic fun wrap(normal: VideoInfoDao): VideoInfoDao {
        normalDao = normal
        return HistoryDao(normal)
    }

    /** 한 세션의 Room 인스턴스는 반복 조회에서 재생성하지 않습니다. */
    @Synchronized internal fun dao(): VideoInfoDao {
        val active = session ?: return checkNotNull(normalDao)
        val directory = File(owned(active), ".data").apply { check(isDirectory || mkdirs()) }
        if (databaseRoot != active.root) {
            database?.close()
            database = Room.databaseBuilder(App.context, AppDatabase::class.java, File(directory, "app_database").path).build()
            databaseRoot = active.root
        }
        return checkNotNull(database).videoInfoDao()
    }

    /** 다운로드 전환 직전 예약된 일반 이력 삽입은 원래 DB에서 마무리합니다. */
    private fun destination(path: String): VideoInfoDao = if (root != null && !File(path).absolutePath.startsWith(root + File.separator))
        checkNotNull(normalDao) else dao()

    /** 원본 DAO 계약의 이력 함수만 선택 저장소에 연결하는 어댑터입니다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class HistoryDao(private val normal: VideoInfoDao) : VideoInfoDao by normal {
        /** 활성 변경을 포함한 이력 흐름입니다. */
        override fun getDownloadHistoryFlow() = changes.flatMapLatest { dao().getDownloadHistoryFlow() }
        /** 현재 이력만 반환합니다. */
        override suspend fun getDownloadHistory() = dao().getDownloadHistory()
        /** 단일 이력을 저장합니다. */
        override suspend fun insert(info: DownloadedVideoInfo) = destination(info.videoPath).insert(info)
        /** 백업 목록은 현재 저장소에만 삽입합니다. */
        override suspend fun insertAll(infoList: List<DownloadedVideoInfo>) {
            check(root == null || infoList.all { File(it.videoPath).canonicalPath.startsWith(root + File.separator) }) {
                "임시 숨기기에서는 일반 이력 백업을 가져올 수 없습니다."
            }
            dao().insertAll(infoList)
        }
        /** 같은 경로의 중복 이력을 막습니다. */
        override suspend fun insertInfoDistinctByPath(videoInfo: DownloadedVideoInfo, path: String) = destination(path).insertInfoDistinctByPath(videoInfo, path)
        /** 현재 저장소의 ID만 찾습니다. */
        override suspend fun getInfoById(id: Int) = dao().getInfoById(id)
        /** 현재 저장소의 경로만 찾습니다. */
        override suspend fun getInfoByPath(path: String) = dao().getInfoByPath(path)
        /** 현재 이력만 삭제합니다. */
        override suspend fun deleteInfoById(id: Int) = dao().deleteInfoById(id)
        /** 현재 경로의 이력만 삭제합니다. */
        override suspend fun deleteInfoByPath(path: String) = dao().deleteInfoByPath(path)
        /** 선택 이력을 삭제합니다. */
        override suspend fun deleteInfo(vararg info: DownloadedVideoInfo) = dao().deleteInfo(*info)
        /** 선택 목록을 삭제합니다. */
        override suspend fun deleteInfoList(idList: List<DownloadedVideoInfo>) = dao().deleteInfoList(idList)
    }
}
