package com.plugin.duplicates

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.junkfood.seal.App
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PRIVATE_DIRECTORY
import com.junkfood.seal.util.SDCARD_DOWNLOAD
import com.plugin.compatibility.*
import com.plugin.ui.PluginMainActivity
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 생성한 복사본만 대상으로 화면 종료 뒤 전경 서비스의 승인 삭제를 검증합니다. */
class DuplicateDeletionCheck : Instrumentation() {
    /** 계측 실행 요청에서만 검사 서비스를 시작합니다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** 저장소·기존 결과를 복원하고 생성한 보존본과 변조 파일은 남깁니다. */
    override fun onStart() {
        waitForIdleSync()
        val oldRoot = App.videoDownloadDir
        val oldPrivate = PRIVATE_DIRECTORY.getBoolean()
        val oldSd = SDCARD_DOWNLOAD.getBoolean()
        val oldResults = DuplicateScanService.results.value
        var previous: DuplicateReport? = null
        var resultCode = Activity.RESULT_CANCELED
        var result = ""
        try {
            check(!TemporarySession.changing && TemporarySession.root == null &&
                !DuplicateScanService.state.value.running && !DuplicateScanService.editing.value)
            val sandbox = File(targetContext.getExternalFilesDir("delete-check"), UUID.randomUUID().toString()).apply { check(mkdirs()) }
            App.videoDownloadDir = sandbox.path
            PRIVATE_DIRECTORY.updateBoolean(false); SDCARD_DOWNLOAD.updateBoolean(false)
            DuplicateStore(targetContext).use { previous = it.load(DuplicateTarget.IMAGE) }
            val files = (0..3).map { index -> File(sandbox, "$index.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) } }
            val hash = MessageDigest.getInstance("SHA-256").digest(files[0].readBytes()).joinToString("") { "%02x".format(it) }
            val media = files.map { DuplicateMedia(it.path, it.name, it.length(), it.lastModified(), 1, 1, 0, false, hash) }
            val group = DuplicateGroup(media, true, 1.0)
            val report = DuplicateReport(sandbox.path, listOf(group), target = DuplicateTarget.IMAGE, backend = DuplicateBackend.CPU)
            DuplicateScanService.results.value = report
            files[3].appendText("변경된 파일 보존")
            val activity = startActivitySync(Intent(targetContext, PluginMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                DuplicateScanService.startDelete(targetContext, mapOf(group.id to files[0].path), report)
                check(DuplicateScanService.editing.value)
                check(runCatching { DuplicateScanService.startDelete(targetContext, mapOf(group.id to files[0].path), report) }.isFailure)
                activity.finish()
            }
            runBlocking { withTimeout(30_000) { while (DuplicateScanService.editing.value) delay(50) } }
            check(files[0].exists() && files[3].exists() && !files[1].exists() && !files[2].exists())
            check(DuplicateScanService.state.value.message == "2개 삭제 · 실패 1개") { DuplicateScanService.state.value.toString() }
            check(DuplicateScanService.state.value.done == 3 && DuplicateScanService.state.value.total == 3)
            check(!DuplicateScanService.state.value.deleting)
            resultCode = Activity.RESULT_OK
            result = "화면 종료 후 삭제·중복 실행 거절·보존본 유지·변조 파일 거절·진행률 통과\n검사 자료: $sandbox"
        } catch (failure: Throwable) {
            result = failure.stackTraceToString()
        } finally {
            if (DuplicateScanService.editing.value) {
                DuplicateScanService.stop(targetContext)
                runBlocking { withTimeoutOrNull(10_000) { while (DuplicateScanService.editing.value) delay(50) } }
            }
            previous?.let { saved -> DuplicateStore(targetContext).use { it.save(saved) } }
            DuplicateScanService.results.value = oldResults
            App.videoDownloadDir = oldRoot
            PRIVATE_DIRECTORY.updateBoolean(oldPrivate); SDCARD_DOWNLOAD.updateBoolean(oldSd)
        }
        finish(resultCode, Bundle().apply { putString("stream", result) })
    }
}
