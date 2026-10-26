package com.plugin.person

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

/** 이름·예시 얼굴·폴더와 시작 시 설정을 고정한 사용자 분류 요청입니다. */
@Serializable
data class PersonSortRequest(val name: String, val reference: String, val face: Int, val source: String,
    val destination: String, val type: String, val model: String, val strength: Int, val backend: String, val fallback: Boolean)

/** 화면을 닫아도 알림과 함께 진행하는 파일별 동일인물 분류입니다. */
class PersonSortService : Service() {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob()+dispatcher)
    private var work: Job? = null
    private var lastNotification = 0L
    private val preferences by lazy { getSharedPreferences("person-sort",0) }

    companion object {
        private val json = Json { ignoreUnknownKeys=true; encodeDefaults=true }
        private val mutable = MutableStateFlow(Progress())
        val state = mutable.asStateFlow()

        /** 남은 수는 현재 사전 집계 기준이며 로그는 최근 여덟 줄만 유지합니다. */
        @Serializable
        data class Progress(val running: Boolean=false, val total: Int=0, val done: Int=0, val moved: Int=0,
            val reused: Int=0, val held: Int=0, val failed: Int=0, val message: String="", val logs: List<String> = emptyList(), val route: String="")

        /** 화면 재진입·프로세스 재시작 후 마지막 파일 단위 통계를 복원합니다. */
        fun restore(context: Context) {
            if (!mutable.value.running) {
                val saved = context.getSharedPreferences("person-sort",0).getString("progress",null) ?: return
                mutable.value = runCatching { json.decodeFromString<Progress>(saved).copy(running=false) }.getOrDefault(Progress())
            }
        }

        /** 사용자 시작 버튼에서만 실행하며 요청을 서비스 인수에 넣어 시스템 재전달을 지원합니다. */
        @Synchronized
        fun start(context: Context, request: PersonSortRequest) {
            check(!mutable.value.running) { "동일인물 분류가 진행 중입니다." }
            check(com.plugin.PluginState.isEnabled(com.plugin.PluginId.PERSON_AI)) { "인물구분 플러그인을 켜세요." }
            check(com.plugin.compatibility.TemporarySession.root == null && !com.plugin.compatibility.TemporarySession.changing) { "일반 저장소에서 분류를 시작하세요." }
            require(request.name.isNotBlank() && request.name !in setOf(".","..") && request.name.length <= 80 &&
                request.name.none { it == '/' || it == '\\' || it.isISOControl() }) { "대상 이름에는 경로 구분자를 사용할 수 없습니다." }
            require(request.type in setOf("all","image","video") && request.strength in 1..5 && request.face >= 0)
            mutable.value = Progress(running=true,message="백그라운드 분류 준비 중")
            try { ContextCompat.startForegroundService(context,Intent(context,PersonSortService::class.java)
                .putExtra("request",json.encodeToString(request))) }
            catch (failure: Exception) { mutable.value=Progress(message=failure.message.orEmpty()); throw failure }
        }

        /** 다음 파일 처리 전 취소를 반영하며 이미 완료한 이동은 유지합니다. */
        fun stop(context: Context) {
            if (mutable.value.running) context.startService(Intent(context,PersonSortService::class.java).setAction("stop"))
        }

        /** 같은 문서의 트리 URI 차이를 제거해 출력 폴더 재순회와 예시 사진 오분류를 막습니다. */
        private fun documentKey(uri: Uri): String = uri.authority+":"+DocumentsContract.getDocumentId(uri)

    }

    /** 알림에는 화면 복귀와 취소 동작을 제공하고 설치 직후부터 진행 상태를 보입니다. */
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("person-sort","동일인물 파일 분류",NotificationManager.IMPORTANCE_LOW))
        startForeground(4115,notification("분류 준비 중"))
    }

    /** 최근 로그와 남은 파일 수를 알림·화면에 전달하며 고빈도 영상 진행은 초당 한 번 알립니다. */
    private fun publish(message: String, log: Boolean=false) {
        val old=mutable.value
        mutable.value=old.copy(message=message,logs=if (log) (old.logs+message).takeLast(8) else old.logs)
        if (log) preferences.edit().putString("progress",json.encodeToString(mutable.value)).apply()
        val now=android.os.SystemClock.elapsedRealtime()
        if (log || now-lastNotification >= 1000) {
            getSystemService(NotificationManager::class.java).notify(4115,notification(message)); lastNotification=now
        }
    }

    /** 알림을 누르면 앱으로 돌아오며 중지는 해당 분류 서비스에만 전달합니다. */
    private fun notification(message: String): android.app.Notification {
        val open=PendingIntent.getActivity(this,4115,Intent(this,com.plugin.ui.PluginMainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,4116,Intent(this,PersonSortService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"person-sort").setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("동일인물 분류 · 남은 ${maxOf(0,mutable.value.total-mutable.value.done)}개")
            .setContentText(message).setContentIntent(open).setOngoing(true).addAction(0,"중지",stop).build()
    }

    /** 네이티브 엔진의 생성·사용·해제는 같은 전용 스레드에서 수행합니다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { work?.cancel() ?: stopSelf(); return START_NOT_STICKY }
        if (work != null) return START_REDELIVER_INTENT
        val encoded=intent?.getStringExtra("request") ?: preferences.getString("request",null)
            ?: run { stopSelf(); return START_NOT_STICKY }
        preferences.edit().putString("request",encoded).commit()
        mutable.value=Progress(running=true,message="파일 목록 확인 중")
        work=scope.launch {
            try { sort(json.decodeFromString(encoded)); publish("분류 완료",true) }
            catch (_: CancellationException) { publish("분류 중지 · 완료한 이동은 유지됩니다.",true) }
            catch (failure: Throwable) { mutable.value=mutable.value.copy(failed=mutable.value.failed+1); publish("분류 실패: ${failure.message ?: failure.javaClass.simpleName}",true) }
        }.also { it.invokeOnCompletion { stopSelf() } }
        return START_REDELIVER_INTENT
    }

    /** 폴더를 두 번 순회해 총수를 구하고 파일 목록 전체를 메모리에 쌓지 않습니다. */
    private suspend fun sort(request: PersonSortRequest) {
        val source=DocumentFile.fromTreeUri(this,Uri.parse(request.source)) ?: error("원본 폴더를 열 수 없습니다.")
        // 별도 저장 위치를 고르지 않으면 작업 폴더 아래에 명칭 폴더를 만듭니다.
        val destination=DocumentFile.fromTreeUri(this,Uri.parse(request.destination.ifBlank { request.source })) ?: error("저장 위치를 열 수 없습니다.")
        check(source.isDirectory && source.canRead() && source.canWrite() && destination.isDirectory && destination.canWrite()) { "폴더의 읽기·쓰기 권한을 확인하세요." }
        val folder=destination.findFile(request.name)?.also { check(it.isDirectory) { "같은 이름의 파일이 있습니다." } }
            ?: destination.createDirectory(request.name) ?: error("대상 폴더를 만들 수 없습니다.")
        check(documentKey(source.uri) != documentKey(folder.uri)) { "원본 폴더와 대상 이름 폴더는 달라야 합니다." }
        val threshold=PersonCalibration.load(this,PersonModels.models.single { it.id==request.model })?.get(request.strength-1)
            ?: error("선택한 모델의 검증 임계값이 없습니다.")
        val excluded=documentKey(folder.uri)
        val cancellation=currentCoroutineContext().job
        val total=files(source,excluded,request.type,cancellation).count()
        mutable.value=mutable.value.copy(total=total)
        publish("대상 ${total}개 · 예시 얼굴 준비 중",true)
        PersonStore(this).use { store -> PersonOnnx(this,request.model,request.backend,request.fallback).use { engine ->
            val analyzer=PersonAnalyzer(this,engine,request.model,store)
            val exampleUri=referenceUri(request.reference)
            val examples=analyzer.analyze(Uri.parse(exampleUri)) { publish("예시 · $it") }
            val referenceContent=store.source(exampleUri)?.content
            val reference=try {
                val selected=examples.getOrNull(request.face) ?: error("예시 얼굴을 다시 선택하세요.")
                check(selected.issues.isEmpty()) { "선명하고 정면을 향한 예시 사진을 선택하세요: ${selected.issues.joinToString()}" }
                selected.vector.clone()
            } finally { PersonPages.release(examples,true) }
            mutable.value=mutable.value.copy(route=engine.backend)
            try {
                for (file in files(source,excluded,request.type,cancellation)) {
                    currentCoroutineContext().ensureActive()
                    // SAF 이동 뒤 원본 문서가 사라져도 진행 로그에는 시작 당시 파일 이름을 유지합니다.
                    val fileName=file.name.orEmpty()
                    check(com.plugin.PluginState.isEnabled(com.plugin.PluginId.PERSON_AI)) { "플러그인이 비활성화되어 중지합니다." }
                    try {
                        val samples=analyzer.analyze(file.uri) { publish("$fileName · $it") }
                        mutable.value=mutable.value.copy(route=engine.backend)
                        if (analyzer.reused) mutable.value=mutable.value.copy(reused=mutable.value.reused+1)
                        var accepted=false; var held=false
                        try { for (sample in samples) {
                            currentCoroutineContext().ensureActive()
                            val verdict=PersonMath.decide(PersonMath.cosine(reference,sample.vector),threshold.reject,threshold.accept,sample.issues.isEmpty())
                            if (verdict == "동일인물") accepted=true
                            else if (verdict == "판정 보류") held=true
                        } } finally { PersonPages.release(samples,true) }
                        if (accepted) {
                            val previous=store.source(file.uri.toString())
                            // 이동과 다운로드 이력·분석 캐시 연결 변경은 한 파일 단위로 마친 뒤 취소합니다.
                            withContext(NonCancellable) {
                                val old=file.uri.toString()
                                val beforeMove=PersonAnalyzer.metadata(this@PersonSortService,file.uri)
                                check(previous != null && beforeMove.first==previous.bytes && beforeMove.second==previous.modified) {
                                    "분석 후 원본 파일이 변경되어 이동을 중단했습니다."
                                }
                                val moved=com.plugin.compatibility.MediaLibrary.moveToFolder(
                                    com.plugin.compatibility.MediaLibrary.MediaFile(old,fileName,file.length(),0.0,
                                        parentUri=requireNotNull(file.parentFile).uri.toString()),folder.uri.toString(),folder)
                                val metadata=PersonAnalyzer.metadata(this@PersonSortService,Uri.parse(moved))
                                store.bind(previous.copy(uri=moved,bytes=metadata.first,modified=metadata.second))
                                store.forget(old)
                                // 예시 사진 자체가 이동되어 기존 URI를 열 수 없을 때만 새 위치로 연결합니다.
                                if (previous.content==referenceContent) {
                                    val exampleAvailable=runCatching { contentResolver.openInputStream(Uri.parse(exampleUri))?.use { it.read()>=0 }==true }.getOrDefault(false)
                                    if (!exampleAvailable) preferences.edit()
                                        .putString("reference-remap:${request.reference}",moved).putString("reference",moved).commit()
                                }
                            }
                            mutable.value=mutable.value.copy(moved=mutable.value.moved+1)
                            publish("분류: $fileName",true)
                        } else {
                            if (held) mutable.value=mutable.value.copy(held=mutable.value.held+1)
                            publish("${if (held) "보류" else "불일치·얼굴 없음"}: $fileName",true)
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { mutable.value=mutable.value.copy(failed=mutable.value.failed+1); publish("오류: $fileName · ${failure.message}",true) }
                    mutable.value=mutable.value.copy(done=mutable.value.done+1)
                    publish("처리 ${mutable.value.done}/${total} · 남은 ${maxOf(0,total-mutable.value.done)}개",true)
                }
            } finally { reference.fill(0f) }
        } }
    }

    /** 현재 선택한 사진을 우선하고, 실제로 열리지 않을 때만 과거 이동 연결을 따라갑니다. */
    private fun referenceUri(original: String): String {
        var candidate=original
        val visited=mutableSetOf<String>()
        while (visited.add(candidate)) {
            val readable=runCatching {
                contentResolver.openInputStream(Uri.parse(candidate))?.use { true } == true
            }.getOrDefault(false)
            if (readable) return candidate
            // 여러 번 이동한 예시도 찾되 오래된 연결이나 순환은 무한히 따라가지 않습니다.
            candidate=preferences.getString("reference-remap:$candidate",null) ?: break
        }
        error("예시 사진을 열 수 없습니다. 대상 예시 사진을 다시 선택하고 얼굴을 인식하세요.")
    }

    /** 결과 폴더는 순회 전에 제외하고 이미지·영상만 유형에 맞춰 한 개씩 반환합니다. */
    private fun files(folder: DocumentFile, excluded: String, type: String, cancellation: Job, depth: Int=0): Sequence<DocumentFile> = sequence {
        cancellation.ensureActive()
        check(depth <= 128) { "폴더 깊이가 너무 큽니다." }
        if (documentKey(folder.uri)==excluded) return@sequence
        check(folder.canRead()) { "폴더 읽기 실패: ${folder.name}" }
        for (child in folder.listFiles()) {
            cancellation.ensureActive()
            if (child.isDirectory) yieldAll(files(child,excluded,type,cancellation,depth+1))
            else if (child.isFile) {
                val ext=child.name.orEmpty().substringAfterLast('.').lowercase()
                val image=child.type?.startsWith("image/")==true || com.plugin.faces.FaceRules.isImage(child.name.orEmpty())
                val video=child.type?.startsWith("video/")==true || ext in setOf("mp4","mkv","mov","webm","avi","3gp","m4v","mts")
                if ((image && type!="video") || (video && type!="image")) yield(child)
            }
        }
    }

    /** 파일 처리 종료 뒤 네이티브 자원을 정리하고 마지막 통계는 다음 진입에 남깁니다. */
    override fun onDestroy() {
        scope.cancel(); dispatcher.close()
        mutable.value=mutable.value.copy(running=false)
        preferences.edit().putString("progress",json.encodeToString(mutable.value)).remove("request").apply()
        super.onDestroy()
    }

    /** 화면은 상태 흐름을 구독하므로 서비스 바인딩을 사용하지 않습니다. */
    override fun onBind(intent: Intent?): IBinder? = null
}
