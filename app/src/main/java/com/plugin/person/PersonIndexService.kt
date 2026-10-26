package com.plugin.person

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.plugin.PluginId
import com.plugin.PluginState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 네트워크 없는 OS 영속 작업으로 파일별 분석을 순차 처리하고 프로세스 중단 후 재개합니다. */
class PersonIndexService : JobService() {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob()+dispatcher)
    private var stopFlag: AtomicBoolean? = null

    companion object {
        private const val JOB_ID = 4151
        private val mutableProgress = MutableStateFlow("")
        val progress = mutableProgress.asStateFlow()
        @Volatile private var running: Job? = null
        private val scheduling = Any()
        private var wake = false
        private var owner: Any? = null

        /** 사용 중인 모델 파일을 관리 화면에서 제거하지 않도록 상태를 제공합니다. */
        fun isRunning(): Boolean = running?.isActive == true

        /** 동의한 요청만 시스템 큐에 등록하며 URI는 암호화 DB에서 읽습니다. */
        fun resume(context: Context) {
            check(com.plugin.compatibility.TemporarySession.root == null && !com.plugin.compatibility.TemporarySession.changing) { "일반 저장소에서 사전 분석을 재개하세요." }
            val preferences = context.getSharedPreferences("person-ai",0)
            check(preferences.getBoolean("storage-consent",false)) { "사전 분석 저장에 먼저 동의하세요." }
            check(PluginState.isEnabled(PluginId.PERSON_AI)) { "얼굴 비교 플러그인을 먼저 활성화하세요." }
            preferences.edit().putBoolean("index-paused",false).commit()
            synchronized(scheduling) {
            // 같은 ID를 다시 schedule하면 OS가 현재 분석을 중단하므로 실행 중에는 신호만 남깁니다.
            if (running?.isActive == true) { wake = true; return }
            val builder = JobInfo.Builder(JOB_ID,ComponentName(context,PersonIndexService::class.java))
                .setPersisted(true).setMinimumLatency(1_000)
                .setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            if (Build.VERSION.SDK_INT >= 26) builder.setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true)
            check(context.getSystemService(JobScheduler::class.java).schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) { "시스템 사전 분석 큐 등록 실패" }
            mutableProgress.value = "사전 분석 대기 · 배터리·시스템 실행 조건을 확인합니다."
            }
        }

        /** 명시적 중단은 재예약을 막고 완료 파일의 특징은 그대로 보존합니다. */
        fun pause(context: Context) {
            context.getSharedPreferences("person-ai",0).edit().putBoolean("index-paused",true).commit()
            synchronized(scheduling) {
                wake = false; running?.cancel()
                context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            }
            mutableProgress.value = "사전 분석 중단 · 완료 파일과 영상 재개 지점을 재사용합니다."
        }
    }

    /** OS 메인 스레드를 막지 않고 모델 실행을 한 전용 스레드로 넘깁니다. */
    override fun onStartJob(params: JobParameters): Boolean {
        val stopped = AtomicBoolean(false); stopFlag = stopped
        val token = Any()
        val work = scope.launch(start = CoroutineStart.LAZY) {
            val preferences = getSharedPreferences("person-ai",0)
            var retry = false
            try {
                check(preferences.getBoolean("storage-consent",false) && !preferences.getBoolean("index-paused",true) && PluginState.isEnabled(PluginId.PERSON_AI) &&
                    com.plugin.compatibility.TemporarySession.root == null && !com.plugin.compatibility.TemporarySession.changing)
                PersonStore(this@PersonIndexService).use { store ->
                    store.recover()
                    val engines = mutableMapOf<String,PersonOnnx>()
                    try {
                        while (true) {
                            ensureActive()
                            if (!preferences.getBoolean("storage-consent",false) || preferences.getBoolean("index-paused",false) || !PluginState.isEnabled(PluginId.PERSON_AI)) break
                            val request = store.next() ?: break
                            synchronized(scheduling) { if (owner === token) wake = false }
                            store.status(request.id,"running")
                            try {
                                val engine = engines.getOrPut(request.model) { PersonOnnx(this@PersonIndexService,request.model,
                                    preferences.getString("backend","auto") ?: "auto",preferences.getBoolean("fallback",true)) }
                                val analyzer = PersonAnalyzer(this@PersonIndexService,engine,request.model,store)
                                val samples = analyzer.analyze(android.net.Uri.parse(request.uri)) { message ->
                                    if (Build.VERSION.SDK_INT >= 29 && getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
                                        throw CancellationException("기기 냉각 대기")
                                    mutableProgress.value = "사전 분석 #${request.id} · $message"
                                }
                                PersonPages.release(samples,true)
                                ensureActive(); store.status(request.id,"done")
                                mutableProgress.value = "사전 분석 ${if (analyzer.reused) "캐시 재사용" else "완료"} · ${store.counts()}"
                            } catch (cancel: CancellationException) {
                                store.status(request.id,"pending"); throw cancel
                            } catch (failure: Exception) {
                                // 파일 URI·생체정보가 예외에 섞여 평문 상태 필드에 남지 않게 원인 종류만 기록합니다.
                                store.status(request.id,"failed","분석 실패 · ${failure.javaClass.simpleName}")
                                mutableProgress.value = "개별 파일 분석 실패 · ${store.counts()}"
                            } catch (failure: LinkageError) { store.status(request.id,"failed","모델 실행 환경 오류") }
                        }
                    } finally { engines.values.forEach { it.close() } }
                    mutableProgress.value = "사전 분석 종료 · ${store.counts()}"
                }
            } catch (cancel: CancellationException) {
                retry = !preferences.getBoolean("index-paused",false) && preferences.getBoolean("storage-consent",false)
                mutableProgress.value = if (retry) "시스템 중단·발열 대기 · 자동 재개 예정" else "사전 분석 중단"
            } catch (_: Exception) { mutableProgress.value = "사전 분석을 시작할 수 없습니다. 저장 동의와 플러그인 상태를 확인하세요." }
            finally {
                synchronized(scheduling) {
                    if (owner === token) {
                        val again = retry || wake
                        running = null; owner = null; wake = false
                        if (!stopped.get()) jobFinished(params,again)
                    } else if (!stopped.get()) jobFinished(params,retry)
                }
            }
        }
        synchronized(scheduling) { owner = token; running = work; wake = false; work.start() }
        return true
    }

    /** 시스템 제약으로 중단하면 진행 파일만 되돌리고 OS 재예약을 허용합니다. */
    override fun onStopJob(params: JobParameters): Boolean {
        stopFlag?.set(true); running?.cancel()
        val preferences = getSharedPreferences("person-ai",0)
        return preferences.getBoolean("storage-consent",false) && !preferences.getBoolean("index-paused",false)
    }

    /** 종료 시 네이티브 자원 정리가 같은 작업 스레드에서 마무리되게 합니다. */
    override fun onDestroy() { scope.cancel(); dispatcher.close(); super.onDestroy() }
}
