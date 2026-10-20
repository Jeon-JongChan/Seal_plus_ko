package com.plugin.duplicates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.plugin.compatibility.MediaLibrary
import com.plugin.compatibility.MediaRules
import com.plugin.compatibility.TemporarySession
import com.plugin.compatibility.enhancement.EnhancementModels
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

private const val FEATURE_VERSION = "v4-litert-rgb01"

/** SHA 검증 후 지각 해시·시간순 AI 임베딩으로 검토할 후보만 만듭니다. */
class DuplicateEngine(private val context: Context, private val options: DuplicateOptions,
    private val store: DuplicateStore) : AutoCloseable {
    private var embedder: DuplicateLiteRt? = null
    private var routeIndex = 0
    var engine: String = "AI 실행 없음 · 캐시 또는 SHA-256 비교"
        private set

    /** 모델은 실제 시각 분석이 필요할 때만 한 번 초기화합니다. */
    private fun embedding(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val pixels = IntArray(224 * 224)
        try { scaled.getPixels(pixels, 0, 224, 0, 0, 224, 224) }
        finally { if (scaled !== bitmap) scaled.recycle() }
        // 내장 모델의 정규화 메타데이터는 mean=0, std=255입니다. 모든 가속기에 같은 값을 전달합니다.
        val values = FloatArray(pixels.size * 3) { (pixels[it / 3] ushr (16 - it % 3 * 8) and 255) / 255f }
        while (true) {
            val route = options.backend.routes[routeIndex]
            try {
                val session = embedder ?: DuplicateLiteRt(context, options.model, route).also { embedder = it }
                val vector = session.embed(values)
                engine = "LiteRT $route${if (routeIndex > 0) " 대체" else ""}" +
                    if (route == "NPU") " 요청 경로 실행 · 실제 연산 분배 확인 불가" else " 경로 실행"
                return vector
            } catch (failure: Exception) { fallback(failure) }
            catch (failure: LinkageError) { fallback(failure) }
        }
    }

    /** 초기화·첫 추론·후속 추론 실패는 같은 프레임을 다음 가속기로 다시 계산합니다. */
    private fun fallback(failure: Throwable) {
        embedder?.close(); embedder = null
        if (routeIndex == options.backend.routes.lastIndex) throw failure
        android.util.Log.w("DuplicateAI", "${options.backend.routes[routeIndex]} 실패로 다음 실행 경로 사용", failure)
        routeIndex++
    }

    /** 메타데이터는 캐시 유효성 확인용이며 삭제 전에는 전체 SHA를 다시 확인합니다. */
    private fun stamp(file: MediaLibrary.MediaFile): Pair<String, Long> {
        val modified = if (file.path.startsWith("content://")) DocumentFile.fromSingleUri(context, Uri.parse(file.path))?.lastModified() ?: 0L
            else File(file.path).lastModified()
        return "$FEATURE_VERSION:${file.bytes}:$modified" to modified
    }

    /** 스트리밍 해시는 대용량 영상 전체를 메모리에 적재하지 않습니다. */
    suspend fun hash(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128 * 1024)
        EnhancementModels.open(context, path).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 한 장의 9×8 dHash·명암 분산과 재사용 가능한 AI 벡터를 계산합니다. */
    private fun frame(bitmap: Bitmap): DuplicateFrame {
        // 원본 해상도와 실행 장치에 관계없이 해시·구조·임베딩에 같은 중간 크기를 사용합니다.
        val normalized = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
        val tiny = Bitmap.createScaledBitmap(normalized, 9, 8, true)
        val pixels = IntArray(72)
        try { tiny.getPixels(pixels, 0, 9, 0, 0, 9, 8) } finally { if (tiny !== normalized) tiny.recycle() }
        val gray = pixels.map { ((it ushr 16 and 255) * 299 + (it ushr 8 and 255) * 587 + (it and 255) * 114) / 1000.0 }
        var bits = 0L
        for (y in 0..7) for (x in 0..7) if (gray[y * 9 + x] > gray[y * 9 + x + 1]) bits = bits or (1L shl (y * 8 + x))
        val mean = gray.average(); val contrast = sqrt(gray.sumOf { (it - mean) * (it - mean) } / gray.size)
        val grid = Bitmap.createScaledBitmap(normalized, 16, 16, true)
        val colors = IntArray(256)
        try { grid.getPixels(colors, 0, 16, 0, 0, 16, 16) } finally { grid.recycle() }
        val structure = FloatArray(256 * 3) { index -> (colors[index / 3] ushr (16 - (index % 3) * 8) and 255).toFloat() }
        try {
            val vector = embedding(normalized)
            return DuplicateFrame(bits, vector, contrast, structure)
        } finally { if (normalized !== bitmap) normalized.recycle() }
    }

    /** 축소 디코딩과 영상당 8개 프레임으로 메모리·디코더 사용량을 제한합니다. */
    private suspend fun frames(media: DuplicateMedia): List<DuplicateFrame> {
        if (!media.video) {
            val source = if (media.path.startsWith("content://")) ImageDecoder.createSource(context.contentResolver, Uri.parse(media.path))
                else ImageDecoder.createSource(File(media.path))
            val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1.0, 256.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            }
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                ?: error("움직이는 이미지는 SHA-256 완전 일치만 비교합니다.")
            try { currentCoroutineContext().ensureActive(); return listOf(frame(bitmap)) } finally { bitmap.recycle() }
        }
        require(media.durationMs > 0) { "영상 길이를 읽을 수 없습니다." }
        val retriever = MediaMetadataRetriever()
        try {
            if (media.path.startsWith("content://")) retriever.setDataSource(context, Uri.parse(media.path)) else retriever.setDataSource(media.path)
            return (0 until 8).map { index ->
                currentCoroutineContext().ensureActive()
                val timeUs = media.durationMs * 1000 * (2 * index + 1) / 16
                val bitmap = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 256, 256)
                    ?: error("영상 프레임을 읽을 수 없습니다.")
                try {
                    if (index == 0) {
                        val thumbnail = File(TemporarySession.root?.let { File(it, ".data") } ?: context.cacheDir,
                            "duplicate-thumbnails/${media.sha256}.jpg")
                        thumbnail.parentFile?.mkdirs()
                        thumbnail.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    }
                    frame(bitmap)
                } finally { bitmap.recycle() }
            }
        } finally { retriever.release() }
    }

    /** 보존 우선순위로 기준 파일을 먼저 골라 연결만 된 간접 유사 파일을 합치지 않습니다. */
    suspend fun scan(files: List<MediaLibrary.MediaFile>, root: String,
        progress: (Int, Int, String) -> Unit): DuplicateReport {
        val eligible = files.filter { MediaRules.mediaKind(it.path, it.quality?.codec.orEmpty()) in 1..2 && it.bytes > 0 }
            .distinctBy { if (it.path.startsWith("content://")) it.path else File(it.path).canonicalPath }
        val stamps = eligible.associate { it.path to stamp(it) }
        val ordered = eligible.sortedWith(compareByDescending<MediaLibrary.MediaFile> { when (options.priority) {
            KeepPriority.SIZE -> it.bytes
            KeepPriority.RESOLUTION -> (it.quality?.width ?: 0).toLong() * (it.quality?.height ?: 0)
            KeepPriority.NEWEST -> stamps.getValue(it.path).second
            KeepPriority.OLDEST -> -stamps.getValue(it.path).second
        } }.thenBy { it.path })
        val excluded = store.exclusions()
        val indexes = mapOf(false to DuplicateHashIndex(), true to DuplicateHashIndex())
        val anchors = mutableListOf<Pair<DuplicateMedia, String>>()
        val groups = mutableMapOf<Int, DuplicateGroup>(); val hashes = mutableMapOf<String, Int>()
        // ponytail: 최근 기준 파일 32개만 벡터를 유지합니다. 실제 병목이 확인되면 SQLite 벡터 검색을 도입합니다.
        val hot = object : LinkedHashMap<Int, DuplicateFeature>(32, 0.75f, true) {
            /** 대량 미디어에서도 벡터 캐시의 최대 항목 수를 제한합니다. */
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, DuplicateFeature>?): Boolean = size > 32
        }
        var failures = 0; var comparisons = 0L; var done = 0
        for (file in ordered) {
            currentCoroutineContext().ensureActive(); progress(done, ordered.size, file.name)
            try {
                val (stamp, modified) = stamps.getValue(file.path)
                val sha = hash(file.path)
                // 크기·수정시각이 보존된 외부 교체도 잡고 비싼 디코딩·추론만 재사용합니다.
                var feature = if (modified > 0) store.cached(file.path, options.model, stamp)?.takeIf { it.media.sha256 == sha } else null
                if (feature == null) {
                    val quality = MediaLibrary.readMediaQuality(file.path) ?: file.quality
                    val media = DuplicateMedia(file.path, file.name, file.bytes, modified, quality?.width ?: 0,
                        quality?.height ?: 0, ((quality?.duration ?: file.duration) * 1000).toLong(),
                        MediaRules.mediaKind(file.path, quality?.codec.orEmpty()) == 1, sha)
                    feature = DuplicateFeature(media, emptyList())
                }
                if (feature.media.identity in excluded) continue
                val key = "${feature.media.video}:${feature.media.bytes}:${feature.media.sha256}"
                val exact = hashes[key]
                // 완전 동일 파일은 AI를 생략하고, 예전에 생략했더라도 기준 파일이 되면 특징을 보충합니다.
                if (exact == null && feature.frames.isEmpty()) {
                    feature = feature.copy(frames = try { frames(feature.media) }
                        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (_: Exception) { failures++; emptyList() })
                }
                val current = feature
                store.cache(current, options.model, stamp)
                var match = exact; var score = if (exact != null) 1.0 else 0.0
                if (match == null && current.frames.isNotEmpty()) {
                    for (id in indexes.getValue(current.media.video).candidates(current.frames.first().hash, options.preset.distance)) {
                        currentCoroutineContext().ensureActive(); comparisons++
                        val anchor = hot[id] ?: store.cached(anchors[id].first.path, options.model, anchors[id].second)?.also { hot[id] = it } ?: continue
                        val similarity = DuplicateRules.similarity(anchor, current, options.preset) ?: continue
                        if (similarity > score) { match = id; score = similarity }
                    }
                }
                if (match == null) {
                    val id = anchors.size; anchors.add(current.media to stamp); hot[id] = current; hashes[key] = id
                    current.frames.firstOrNull()?.let { indexes.getValue(current.media.video).add(it.hash, id) }
                } else {
                    val old = groups[match]
                    groups[match] = DuplicateGroup((old?.members ?: listOf(anchors[match].first)) + current.media,
                        (old?.exact ?: true) && exact != null, minOf(old?.similarity ?: 1.0, score))
                    // 변형 파일의 동일 사본도 반드시 기준 파일과 검증된 같은 그룹에만 연결합니다.
                    hashes[key] = match
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { failures++ }
            finally { done++; progress(done, ordered.size, file.name) }
        }
        return DuplicateReport(root, groups.values.toList(), done, failures, comparisons, options.model, options.preset, options.backend, engine)
    }

    /** 사용자가 보존본을 바꿔도 그 파일과 삭제 대상이 직접 일치하는지 재검증합니다. */
    suspend fun verifyPair(keeper: DuplicateMedia, target: DuplicateMedia, preset: DuplicatePreset): Boolean {
        if (keeper.sha256 == target.sha256 && keeper.bytes == target.bytes && keeper.video == target.video) return true
        /** 캐시에서 생략된 프레임은 삭제 승인 시에만 보충합니다. */
        suspend fun feature(media: DuplicateMedia): DuplicateFeature {
            val stamp = "$FEATURE_VERSION:${media.bytes}:${media.modified}"
            val cached = store.cached(media.path, options.model, stamp)
            return cached?.takeIf { it.frames.isNotEmpty() } ?: DuplicateFeature(media, frames(media)).also { store.cache(it, options.model, stamp) }
        }
        return DuplicateRules.similarity(feature(keeper), feature(target), preset) != null
    }

    /** 추론 종료·취소 시 네이티브 모델 메모리를 반환합니다. */
    override fun close() { embedder?.close(); embedder = null }
}
