package com.plugin.duplicates

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import com.plugin.PluginId
import com.plugin.PluginState
import com.plugin.compatibility.*
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import com.plugin.runtime.pluginQueue
import com.plugin.queue.PluginTask
import com.plugin.queue.PluginTaskBackup
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.junkfood.seal.App
import com.junkfood.seal.util.VIDEO_DIRECTORY
import com.junkfood.seal.util.PRIVATE_DIRECTORY
import com.junkfood.seal.util.SDCARD_DOWNLOAD
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean

/** 릴리스에는 포함하지 않는 실제 모델·파일·저장소 통합 검사입니다. */
class DuplicateCheck : Instrumentation() {
    private var keepUi = false
    private var restoreOnly = false

    /** 생성한 검사 파일을 UI 확인용으로 보존할지 인자로 선택합니다. */
    override fun onCreate(arguments: Bundle?) {
        keepUi = arguments?.getString("keepUi") == "true"
        restoreOnly = arguments?.getString("restoreSettings") == "true"
        super.onCreate(arguments); start()
    }

    /** 생성한 파일만 조작하며 정상·오탐·제외·변경 후 삭제 거절을 검사합니다. */
    override fun onStart() {
        try {
            // 자체 APK 계측은 Application 초기화와 겹칠 수 있어 UI 스레드의 초기 작업을 기다립니다.
            waitForIdleSync()
            runOnMainSync { com.plugin.runtime.PluginAppHelper.initDownloader(targetContext) }
            val queue = org.koin.core.context.GlobalContext.get().get<com.junkfood.seal.download.DownloaderV2>().pluginQueue()
            runBlocking { withTimeout(10_000) { while (!(queue as com.plugin.runtime.PluginTaskEngine).restored) delay(20) } }
            check(TemporarySession.root == null) { "일반 저장소에서만 검사를 실행하세요." }
            if (restoreOnly) { restoreSettings(); finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "검사 전 저장소 설정 복원\n") }); return }
            queue.getTaskStateMap().keys.filter { it.id.startsWith("duplicate-check-") }.toList().forEach { queue.remove(it) }
            PluginTaskBackup.save(queue.getTaskStateMap().toMap())
            val context = targetContext
            val backup = context.getSharedPreferences("duplicate-check-settings", 0)
            if (!backup.getBoolean("active", false)) backup.edit().putBoolean("active", true)
                .putString("root", VIDEO_DIRECTORY.getString("")).putString("video", App.videoDownloadDir)
                .putBoolean("duplicates", PluginState.isEnabled(PluginId.DUPLICATES))
                .putBoolean("private", PRIVATE_DIRECTORY.getBoolean()).putBoolean("sd", SDCARD_DOWNLOAD.getBoolean()).commit()
            if (!backup.contains("queue")) backup.edit().putBoolean("queue", PluginState.isEnabled(PluginId.QUEUE))
                .putBoolean("library", PluginState.isEnabled(PluginId.LIBRARY)).commit()
            val sandbox = context.getExternalFilesDir("duplicate-check") ?: error("앱 소유 검사 폴더가 없습니다.")
            App.videoDownloadDir = sandbox.path; VIDEO_DIRECTORY.updateString(sandbox.path)
            PRIVATE_DIRECTORY.updateBoolean(false); SDCARD_DOWNLOAD.updateBoolean(false)
            val result = runBlocking { checkWorkflow() }
            if (!keepUi) restoreSettings()
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", result) })
        } catch (failure: Throwable) {
            restoreSettings()
            android.util.Log.e("DuplicateCheck", "통합 검사 실패", failure)
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure.stackTraceToString()) })
        }
    }

    /** 넓은 저장소 권한 없이 앱 소유 폴더만 사용하고 검사 전 설정으로 되돌립니다. */
    private fun restoreSettings() {
        val backup = targetContext.getSharedPreferences("duplicate-check-settings", 0)
        if (!backup.getBoolean("active", false)) return
        runBlocking {
            val queue = org.koin.core.context.GlobalContext.get().get<com.junkfood.seal.download.DownloaderV2>().pluginQueue()
            queue.getTaskStateMap().keys.filter { it.id.startsWith("duplicate-check-") }.toList().forEach { queue.remove(it) }
            PluginTaskBackup.save(queue.getTaskStateMap().toMap())
            val sandbox = targetContext.getExternalFilesDir("duplicate-check")!!.path + "/duplicate-check-"
            PluginDatabaseUtil.getDownloadHistory().filter { it.videoPath.startsWith(sandbox) }.forEach { PluginDatabaseUtil.deleteInfoByPath(it.videoPath, false) }
        }
        PluginState.setEnabled(PluginId.DUPLICATES, backup.getBoolean("duplicates", false))
        PluginState.setEnabled(PluginId.QUEUE, backup.getBoolean("queue", false))
        PluginState.setEnabled(PluginId.LIBRARY, backup.getBoolean("library", false))
        VIDEO_DIRECTORY.updateString(backup.getString("root", "").orEmpty())
        App.videoDownloadDir = backup.getString("video", "").orEmpty()
        PRIVATE_DIRECTORY.updateBoolean(backup.getBoolean("private", false)); SDCARD_DOWNLOAD.updateBoolean(backup.getBoolean("sd", false))
        backup.edit().putBoolean("active", false).commit()
    }

    /** 앱에 이미 포함된 FFmpeg로 짧은 정적 영상과 코덱 변형을 만듭니다. */
    private fun video(image: File, output: File, seconds: Int = 1, width: Int = 256) {
        val context = targetContext
        FFmpeg.getInstance().init(context)
        val command = listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path,
            "-y", "-nostdin", "-loglevel", "error", "-loop", "1", "-i", image.path,
            "-t", seconds.toString(), "-vf", "scale=$width:$width", "-r", "8", "-c:v", "libx264", "-pix_fmt", "yuv420p", output.path)
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
            environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").path }
        }.start()
        try {
            val error = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0 && output.length() > 0) { error }
        } finally { process.destroy() }
    }

    /** 두 AI 모델과 같은 실제 저장 코드로 분석·제외·삭제를 끝까지 검증합니다. */
    private suspend fun checkWorkflow(): String {
        val context = targetContext
        val legacy = Json { ignoreUnknownKeys = true }.decodeFromString<PluginPreferencesRecord>("""{"listProcessing":true,"socialProcessing":true}""")
        check(legacy.socialProcessing) { "제거한 목록 옵션의 구형 백업을 읽을 수 없습니다." }
        val root = MediaLibrary.rootPath(createPluginPreferences())
        val directory = File(root, "duplicate-check-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(256 * 256) { index ->
            val x = index % 256; val y = index / 256
            val band = if ((x / 32 + y / 32) % 2 == 0) 45 else 180
            android.graphics.Color.rgb((band + x / 4).coerceAtMost(255), (band + y / 4).coerceAtMost(255), 70 + x / 2)
        }
        bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 256)
        val a = File(directory, "원본.png")
        a.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val copy = File(directory, "동일복사.png"); a.copyTo(copy)
        val jpeg = File(directory, "재압축.jpg")
        jpeg.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
        val noise = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(7)
        noise.setPixels(IntArray(256 * 256) { android.graphics.Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }, 0, 256, 0, 0, 256, 256)
        val other = File(directory, "다른사진.png")
        other.outputStream().use { noise.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); noise.recycle()
        val movie = File(directory, "영상원본.mp4"); video(a, movie)
        val movieCopy = File(directory, "영상동일.mp4"); movie.copyTo(movieCopy)
        val movieVariant = File(directory, "영상축소.mp4"); video(a, movieVariant, width = 128)
        val longer = File(directory, "길이다름.mp4"); video(a, longer, seconds = 2)
        val files = directory.listFiles()!!.map { file ->
            val quality = MediaLibrary.readMediaQuality(file.path)
            MediaLibrary.MediaFile(file.path, file.name, file.length(), quality?.duration ?: 0.0, quality = quality)
        }
        val notes = mutableListOf<String>()
        DuplicateStore(context).use { store ->
            val previous = store.load()
            check(store.exclusions().isEmpty()) { "기존 제외 기록이 있어 격리된 에뮬레이터에서 검사를 실행해야 합니다." }
            try {
                for (model in listOf("small", "large")) {
                    val options = DuplicateOptions(model = model)
                    DuplicateEngine(context, options, store).use { engine ->
                        val report = engine.scan(files, root) { _, _, _ -> }
                        check(report.failures == 0) { "$model 파일 분석 오류 ${report.failures}" }
                        check(report.groups.any { group -> setOf(a.path, copy.path, jpeg.path).all { path -> group.members.any { it.path == path } } }) { "$model 재압축 이미지 후보 누락: $report" }
                        check(report.groups.any { group -> setOf(movie.path, movieCopy.path, movieVariant.path).all { path -> group.members.any { it.path == path } } }) { "$model 영상 변형 후보 누락: $report" }
                        check(report.groups.none { group -> group.members.any { it.path == other.path || it.path == longer.path } }) { "다른 사진·다른 길이 영상 오탐" }
                        val repeated = engine.scan(files, root) { _, _, _ -> }
                        check(repeated.groups.map { it.members.map { media -> media.path } } == report.groups.map { it.members.map { media -> media.path } }) { "캐시 재사용 결과 불일치" }
                        store.save(report); check(store.load().groups.size == report.groups.size)
                        DuplicateScanService.results.value = report
                        val excluded = report.groups.first().members.last()
                        DuplicateScanService.exclude(context, excluded, true)
                        check(excluded.identity in store.exclusions())
                        check(engine.scan(files, root) { _, _, _ -> }.groups.none { group -> group.members.any { it.identity == excluded.identity } })
                        store.clearExclusions()
                        DuplicateScanService.results.value = report
                        val transient = report.groups.last().members.last()
                        DuplicateScanService.exclude(context, transient, false)
                        check(transient.identity !in store.exclusions())
                        check(engine.scan(files, root) { _, _, _ -> }.groups.any { group -> group.members.any { it.identity == transient.identity } })
                        val imageGroup = report.groups.first { !it.members.first().video }
                        val anchor = imageGroup.members.first()
                        DuplicateScanService.results.value = report
                        DuplicateScanService.exclude(context, anchor, false)
                        val remaining = imageGroup.members.drop(1).map { it.identity }.toSet()
                        check(DuplicateScanService.results.value.groups.any { group -> group.members.map { it.identity }.toSet().containsAll(remaining) }) {
                            "기준 파일 제외 시 나머지 중복 후보가 함께 사라졌습니다."
                        }
                        check(DuplicateScanService.results.value.groups.none { group -> group.members.any { it.identity == anchor.identity } })
                        notes.add("$model: 이미지·영상 변형 후보 / 오탐 방어 / 캐시 / 제외 기록 통과")
                    }
                }
                // 삭제 검사는 이 함수가 생성한 동일 복사본 두 개만 대상으로 합니다.
                val safe = File(directory, "삭제검사원본.png"); a.copyTo(safe)
                val victim = File(directory, "삭제검사복사.png"); a.copyTo(victim)
                val entries = listOf(safe, victim).map { MediaLibrary.MediaFile(it.path, it.name, it.length(), 0.0, quality = MediaLibrary.readMediaQuality(it.path)) }
                DuplicateEngine(context, DuplicateOptions(), store).use { engine ->
                    val report = engine.scan(entries, root) { _, _, _ -> }
                    check(report.groups.size == 1)
                    DuplicateScanService.results.value = report
                    victim.appendText("변경")
                    val refused = DuplicateScanService.delete(context, mapOf(report.groups.first().id to safe.path))
                    check(refused.first == 0 && refused.second.isNotEmpty() && victim.exists() && safe.exists())
                    a.copyTo(victim, overwrite = true)
                    val current = engine.scan(entries, root) { _, _, _ -> }
                    DuplicateScanService.results.value = current
                    val deleted = DuplicateScanService.delete(context, mapOf(current.groups.first().id to safe.path))
                    check(deleted.first == 1 && deleted.second.isEmpty() && !victim.exists() && safe.exists()) {
                        "승인 삭제 결과: $deleted · 대상 존재=${victim.exists()} · 보존본 존재=${safe.exists()}"
                    }
                }
                val blocked = File(directory, "삭제실패폴더").apply { mkdirs() }
                File(blocked, "보존.txt").writeText("보존")
                PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "삭제 실패 검사", "", "", "", blocked.path))
                val saved = PluginDatabaseUtil.getDownloadHistory().filter { it.videoPath == blocked.path }
                PluginDatabaseUtil.deleteInfoListSafe(saved, true)
                check(PluginDatabaseUtil.getDownloadHistory().any { it.videoPath == blocked.path } && blocked.isDirectory)
                PluginDatabaseUtil.deleteInfoByPath(blocked.path, false)
                PluginMediaMetadataStore.save(a.path, aiReplaced = true)
                check(AiMediaFilter.matches(listOf(a.path), emptySet()))
                AiMediaFilter.begin(listOf(other.path)); check(AiMediaFilter.matches(listOf(other.path), AiMediaFilter.pending.value)); AiMediaFilter.end(listOf(other.path))
                check(!AiMediaFilter.matches(listOf(other.path), AiMediaFilter.pending.value))
                notes.add("삭제 직전 변경 거절 / 승인 삭제 / 삭제 실패 이력 보존 / 복제·교체 필터 통과")
                if (keepUi) {
                    PluginState.setEnabled(PluginId.DUPLICATES, true)
                    PluginState.setEnabled(PluginId.QUEUE, true)
                    PluginState.setEnabled(PluginId.LIBRARY, true)
                    val queue = org.koin.core.context.GlobalContext.get().get<com.junkfood.seal.download.DownloaderV2>().pluginQueue()
                    val postUrl = "duplicate-check:${directory.name}"
                    PluginMediaMetadataStore.save(a.path, postPageCount = 2)
                    PluginMediaMetadataStore.save(movie.path, postPageIndex = 1, postPageCount = 2)
                    for ((file, label) in listOf(a to "필터 검사 · AI 교체", other to "필터 검사 · 일반")) {
                        PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, label, "", if (file == a) postUrl else file.path,
                            file.path, file.path, if (file == a) "Instagram" else "External"))
                        val task = PluginTask(file.path, preferences = createPluginPreferences(), id = "duplicate-check-${file.name}-${System.currentTimeMillis()}")
                        queue.enqueue(task, PluginTask.State(PluginTask.DownloadState.Completed(file.path, listOf(file.path)), null,
                            PluginTask.ViewState(title = label, isImage = true, thumbnailUrl = file.path)))
                    }
                    PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "필터 검사 · AI 교체", "", postUrl, movie.path, movie.path, "Instagram"))
                    PluginTaskBackup.save(queue.getTaskStateMap().toMap())
                    val report = DuplicateEngine(context, DuplicateOptions(), store).use { it.scan(files, root) { _, _, _ -> } }
                    store.save(report); DuplicateScanService.results.value = report
                }
            } finally {
                if (!keepUi) { store.save(previous); DuplicateScanService.results.value = previous }
            }
        }
        return notes.joinToString("\n") + "\n검사 파일: ${directory.path}\n"
    }
}
