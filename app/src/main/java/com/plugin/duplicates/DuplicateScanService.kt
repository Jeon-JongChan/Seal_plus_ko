package com.plugin.duplicates

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.plugin.compatibility.*
import com.plugin.ui.PluginMainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 사용자가 시작한 분석을 화면 수명과 분리하며 자동 삭제는 실행하지 않습니다. */
class DuplicateScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** 내부 시작 요청만 받습니다. */
    override fun onBind(intent: Intent): IBinder? = null

    /** 파일별 캐시를 보존하며 취소·실패 시 이전 검토 결과를 유지합니다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val raw = intent?.getStringExtra("options") ?: run { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "중복 미디어 분석", NotificationManager.IMPORTANCE_LOW))
        try { startForeground(ID, notification("미디어 목록 준비 중")) }
        catch (failure: Exception) { state.value = State(error = failure.message.orEmpty()); stopSelf(); return START_NOT_STICKY }
        state.value = State(running = true)
        job = scope.launch {
            try {
                val options = Json.decodeFromString<DuplicateOptions>(raw)
                val preferences = createPluginPreferences()
                val root = MediaLibrary.rootPath(preferences)
                val report = withContext(inferenceDispatcher) {
                    check(!TemporarySession.changing) { "저장소를 전환하고 있습니다." }
                    DuplicateStore(applicationContext).use { store ->
                        DuplicateEngine(applicationContext, options, store).use { engine ->
                            val files = MediaLibrary.listVideos(preferences)
                            engine.scan(files, root) { done, total, name ->
                                state.value = State(true, done, total, name, engine = engine.engine)
                                if (done % 10 == 0 || done == total) manager.notify(ID, notification("$done / $total · $name"))
                            }.also { store.save(it) }
                        }
                    }
                }
                results.value = report
                state.value = state.value.copy(running = false, message = "검토할 중복 후보 ${report.groups.size}개 그룹 · 읽기 오류 ${report.failures}개", engine = report.engine)
            } catch (cancel: CancellationException) { state.value = state.value.copy(running = false, message = "분석을 중단했습니다. 완료한 파일의 캐시는 보존됩니다."); throw cancel }
            catch (failure: Exception) { state.value = state.value.copy(running = false, error = failure.message ?: "중복 분석에 실패했습니다.") }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }

    /** 시스템이 중단을 요청하면 파일을 변경하지 않고 분석만 취소합니다. */
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf() }

    /** 서비스 종료는 디코딩·해시 작업에도 취소를 전달합니다. */
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** 알림을 누르면 앱으로 돌아가 보존된 후보 목록을 확인할 수 있습니다. */
    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(this, ID, Intent(this, PluginMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AI 이미지·영상 중복 제거").setContentText(text).setContentIntent(pending).setOngoing(true).build()
    }

    companion object {
        private const val CHANNEL = "duplicate_scan"
        private const val ID = 7460
        // GPU 컨텍스트의 생성·추론·해제는 코루틴이 중단되어도 같은 스레드를 사용해야 합니다.
        private val inferenceDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val state = MutableStateFlow(State())
        val results = MutableStateFlow(DuplicateReport())

        /** 분석 진행·취소·오류를 화면과 공유합니다. */
        data class State(val running: Boolean = false, val done: Int = 0, val total: Int = 0,
            val message: String = "", val error: String = "", val engine: String = "")

        /** 사용자가 누른 실행 버튼에서만 서비스가 시작됩니다. */
        fun start(context: Context, options: DuplicateOptions) {
            check(Build.VERSION.SDK_INT >= 28) { "AI 중복 분석은 Android 9 이상에서 지원합니다." }
            check(!state.value.running && !editing.value && !TemporarySession.changing) { "다른 중복 작업 또는 저장소 전환이 진행 중입니다." }
            state.value = State(running = true)
            try { context.startForegroundService(Intent(context, DuplicateScanService::class.java).putExtra("options", Json.encodeToString(options))) }
            catch (failure: Exception) { state.value = State(error = failure.message.orEmpty()); throw failure }
        }

        /** 사용자의 중단 요청은 현재 결과를 덮어쓰지 않습니다. */
        fun stop(context: Context) { context.stopService(Intent(context, DuplicateScanService::class.java)) }

        val editing = MutableStateFlow(false)

        /** 현재 활성 폴더의 저장 결과를 복구하며 다른 저장소의 후보는 표시하지 않습니다. */
        suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
            if (state.value.running || editing.value) return@withContext
            val loaded = DuplicateStore(context).use { it.load() }
            val root = MediaLibrary.rootPath(createPluginPreferences())
            results.value = if (loaded.root == root) loaded else DuplicateReport(root)
        }

        /** 제외 기록은 선택에 따라 남기고 현재 후보에서는 즉시 제거합니다. */
        suspend fun exclude(context: Context, media: DuplicateMedia, remember: Boolean) = withContext(inferenceDispatcher) {
            check(!state.value.running && editing.compareAndSet(false, true)) { "중복 작업이 진행 중입니다." }
            try {
                val report = results.value
                check(report.root == MediaLibrary.rootPath(createPluginPreferences()) && !TemporarySession.changing) { "저장소가 변경되었습니다. 다시 분석하세요." }
                DuplicateStore(context).use { store ->
                    val groups = mutableListOf<DuplicateGroup>()
                    for (group in report.groups) {
                        if (group.members.none { it.identity == media.identity }) { groups.add(group); continue }
                        val kept = group.members.filterNot { it.identity == media.identity }
                        if (kept.size < 2) continue
                        if (group.members.first().identity != media.identity) groups.add(group.copy(members = kept))
                        else {
                            // 기준 파일만 제외해도 나머지 중복은 유지하고 새 기준과 직접 일치하는지 다시 확인합니다.
                            val files = kept.map { item -> MediaLibrary.MediaFile(item.path, item.name, item.bytes,
                                item.durationMs / 1000.0, quality = com.junkfood.seal.database.objects.VideoQuality(
                                    width = item.width, height = item.height, duration = item.durationMs / 1000.0,
                                    bytes = item.bytes, codec = if (item.video) "video/mp4" else "image/png")) }
                            DuplicateEngine(context, DuplicateOptions(model = report.model, preset = report.preset, backend = report.backend), store).use { engine ->
                                groups.addAll(engine.scan(files, report.root) { _, _, _ -> }.groups)
                            }
                        }
                    }
                    val updated = report.copy(groups = groups)
                    withContext(NonCancellable) {
                        if (remember) store.exclude(media)
                        store.save(updated); results.value = updated
                    }
                }
            } finally { editing.value = false }
        }

        /** 승인한 계획만 삭제하며 변경된 원본·보존본이나 진행 중인 AI 파일은 건너뜁니다. */
        suspend fun delete(context: Context, keepers: Map<String, String>): Pair<Int, List<String>> = withContext(inferenceDispatcher) {
            check(!state.value.running && editing.compareAndSet(false, true)) { "중복 작업이 진행 중입니다." }
            try {
                val report = results.value
                val root = MediaLibrary.rootPath(createPluginPreferences())
                check(report.root == root && !TemporarySession.changing) { "저장소가 변경되었습니다. 다시 분석하세요." }
                val allowed = MediaLibrary.listVideos(createPluginPreferences()).map { it.path }.toSet()
                val removed = mutableSetOf<String>(); val failures = mutableListOf<String>()
                DuplicateStore(context).use { store ->
                    try {
                    DuplicateEngine(context, DuplicateOptions(model = report.model, backend = report.backend), store).use { engine ->
                        for (group in report.groups) {
                            val keeper = group.members.singleOrNull { it.path == keepers[group.id] } ?: continue
                            try {
                                check(keeper.path in allowed && keeper.path !in AiMediaFilter.pending.value && engine.hash(keeper.path) == keeper.sha256) { "보존 파일이 변경되었거나 처리 중입니다." }
                                for (target in group.members.filterNot { it.path == keeper.path }) {
                                    currentCoroutineContext().ensureActive()
                                    try {
                                        check(root == MediaLibrary.rootPath(createPluginPreferences()) && !TemporarySession.changing) { "저장소가 변경되었습니다." }
                                        check(target.path in allowed && target.path !in AiMediaFilter.pending.value && engine.hash(target.path) == target.sha256) { "삭제 대상이 변경되었거나 처리 중입니다." }
                                        check(engine.verifyPair(keeper, target, report.preset)) { "선택한 보존본과 직접 일치하지 않습니다. 다시 분석하세요." }
                                        check(engine.hash(keeper.path) == keeper.sha256) { "보존 파일이 변경되었습니다." }
                                        check(engine.hash(target.path) == target.sha256 && target.path !in AiMediaFilter.pending.value && keeper.path !in AiMediaFilter.pending.value) { "삭제 직전 파일이 변경되었거나 처리 중입니다." }
                                        // 성공을 검증한 공통 삭제만 사용하고 실패한 파일의 이력은 보존합니다.
                                        withContext(NonCancellable) {
                                            CompatibilityFiles.deleteFile(target.path).getOrThrow()
                                            PluginDatabaseUtil.deleteInfoByPath(target.path, removedFromStorage = true)
                                            removed.add(target.path)
                                        }
                                    } catch (cancel: CancellationException) { throw cancel }
                                    catch (failure: Exception) { failures.add("${target.name}: ${failure.message}") }
                                }
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { failures.add("${keeper.name}: ${failure.message}") }
                        }
                    }
                    } finally {
                        // 화면을 나가 작업이 취소되어도 이미 삭제한 파일은 검토 결과에서 제거합니다.
                        withContext(NonCancellable) {
                            val updated = report.copy(groups = report.groups.mapNotNull { group ->
                                val remaining = group.members.filterNot { it.path in removed }
                                if (remaining.size < 2) null else group.copy(members = remaining)
                            })
                            store.save(updated); results.value = updated
                        }
                    }
                }
                removed.size to failures
            } finally { editing.value = false }
        }
    }
}
