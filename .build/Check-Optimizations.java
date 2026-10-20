import com.junkfood.seal.util.DownloadUtil;
import com.plugin.compatibility.InstagramMedia;
import com.plugin.compatibility.MediaOrderingKt;
import com.plugin.compatibility.MediaRules;
import com.plugin.compatibility.QueueMedia;
import com.plugin.queue.PluginTask;
import com.plugin.duplicates.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import kotlin.Unit;
import kotlinx.coroutines.JobKt;

/** 실제 앱 클래스의 정렬·게시물 집계·취소·해시 계약을 프레임워크 없이 검사합니다. */
class CheckOptimizations {
    /** 중복 판정·보존 추천·후보 인덱스를 실제 앱 코드로 검증합니다. */
    static void checkDuplicates() {
        var rules = DuplicateRules.INSTANCE;
        assert DuplicateBackend.AUTO.getRoutes().equals(List.of("NPU", "GPU", "CPU"));
        assert DuplicateBackend.GPU.getRoutes().equals(List.of("GPU", "CPU"));
        assert DuplicateBackend.CPU.getRoutes().equals(List.of("CPU"));
        assert new DuplicateOptions().getBackend() == DuplicateBackend.AUTO;
        assert kotlinx.serialization.json.Json.Default.decodeFromString(DuplicateOptions.Companion.serializer(), "{\"model\":\"large\"}").getBackend() == DuplicateBackend.AUTO;
        var a = new DuplicateMedia("/a.jpg", "a.jpg", 100, 1, 100, 100, 0, false, "a");
        var b = new DuplicateMedia("/b.jpg", "b.jpg", 200, 2, 50, 50, 0, false, "b");
        var c = new DuplicateMedia("/c.jpg", "c.jpg", 100, 3, 100, 100, 0, false, "a");
        assert rules.keeper(List.of(a, b), KeepPriority.SIZE).equals(b);
        assert rules.keeper(List.of(a, b), KeepPriority.RESOLUTION).equals(a);
        assert rules.keeper(List.of(a, b), KeepPriority.NEWEST).equals(b);
        assert rules.keeper(List.of(a, b), KeepPriority.OLDEST).equals(a);
        float[] pixels = new float[]{10, 20, 30, 100, 110, 120};
        var f = new DuplicateFeature(a, List.of(new DuplicateFrame(123L, new float[]{1, 0}, 20, pixels)));
        var g = new DuplicateFeature(b, List.of(new DuplicateFrame(123L, new float[]{1, 0}, 20, pixels)));
        assert rules.similarity(f, g, DuplicatePreset.STRICT) == 1.0;
        assert rules.similarity(f, new DuplicateFeature(c, List.of()), DuplicatePreset.STRICT) == 1.0;
        assert rules.similarity(f, new DuplicateFeature(b, List.of(new DuplicateFrame(123L, new float[]{0, 1}, 20, pixels))), DuplicatePreset.LENIENT) == null;
        assert rules.similarity(f, new DuplicateFeature(b, List.of(new DuplicateFrame(123L, new float[]{1, 0}, 0, pixels))), DuplicatePreset.LENIENT) == null;
        assert rules.similarity(f, new DuplicateFeature(b, List.of(new DuplicateFrame(123L, new float[]{1, 0}, 20, new float[]{120, 110, 100, 30, 20, 10}))), DuplicatePreset.LENIENT) == null;
        assert rules.structure(pixels, pixels) == 1.0;
        assert rules.cosine(new float[]{0, 0}, new float[]{1, 0}) == -1;
        assert rules.cosine(new float[]{Float.NaN, 1}, new float[]{1, 0}) == -1;
        var index = new DuplicateHashIndex();
        var random = new Random(42);
        var hashes = new ArrayList<Long>();
        for (int id = 0; id < 400; id++) { long hash = random.nextLong(); hashes.add(hash); index.add(hash, id); }
        index.add(hashes.get(0), 400);
        assert index.candidates(hashes.get(0), 0).equals(List.of(0, 400));
        for (int radius : new int[]{0, 2, 10, 32, 64}) {
            var expected = new HashSet<Integer>();
            long query = hashes.get(30);
            for (int id = 0; id < hashes.size(); id++) if (Long.bitCount(query ^ hashes.get(id)) <= radius) expected.add(id);
            if (Long.bitCount(query ^ hashes.get(0)) <= radius) expected.add(400);
            assert new HashSet<>(index.candidates(query, radius)).equals(expected) : "후보 인덱스 누락";
        }
        var va = new DuplicateMedia("/a.mp4", "a.mp4", 100, 1, 100, 100, 10000, true, "v1");
        var vb = new DuplicateMedia("/b.mp4", "b.mp4", 110, 1, 100, 100, 20000, true, "v2");
        assert rules.similarity(new DuplicateFeature(va, f.getFrames()), new DuplicateFeature(vb, f.getFrames()), DuplicatePreset.LENIENT) == null;
        System.out.println("중복 판정·오탐 방어·보존 우선순위·BK 인덱스 검사 통과");
    }
    /** 정렬 순서와 동률 안정성을 비교하기 위한 값입니다. */
    record Item(int id, long time, String name) {}

    /** 실제 다운로드 모델로 같은 게시물의 페이지·재시도 요청을 만듭니다. */
    static PluginTask task(String page, String id, long time) {
        var media = new InstagramMedia(page, "https://www.instagram.com/p/check/", "https://example.com/" + page,
            "", false, "검사", "검사", 0, true, 1920, 1080, "instagram", "", Integer.parseInt(page), 2, false);
        return new PluginTask(media.getPostUrl(), new PluginTask.TypeInfo.SocialMedia(media),
            DownloadUtil.DownloadPreferences.Companion.getEMPTY(), id, time, null);
    }

    /** 실제 상태 모델에 완료 파일이나 진행 상태를 연결합니다. */
    static PluginTask.State state(PluginTask.DownloadState download) {
        return new PluginTask.State(download, null, new PluginTask.ViewState());
    }

    /** 실제 파일을 열지 않고 완료된 경로의 집계 계약을 검사합니다. */
    static PluginTask.State completed(String path, boolean missing) {
        return state(new PluginTask.DownloadState.Completed(path, List.of(path), missing));
    }

    /** 독립된 표준 정렬 결과·선택자 호출 수와 실제 앱 집계 결과를 함께 검증합니다. */
    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        checkDuplicates();
        var items = new ArrayList<Item>();
        for (int i = 0; i < 2000; i++) items.add(new Item(i, i % 97, i % 2 == 0 ? "Name" + i % 29 : "name" + i % 29));
        Collections.shuffle(items, new Random(42));
        int oldCalls = 0;
        for (boolean byName : new boolean[] {false, true}) {
            var referenceCalls = new AtomicInteger();
            Comparator<Item> byTime = Comparator.comparingLong(item -> { referenceCalls.incrementAndGet(); return item.time(); });
            Comparator<Item> byTitle = Comparator.comparing(item -> { referenceCalls.incrementAndGet(); return item.name(); }, String.CASE_INSENSITIVE_ORDER);
            var expected = new ArrayList<>(items);
            expected.sort(byName ? byTitle.thenComparing(byTime.reversed()) : byTime.reversed().thenComparing(byTitle));
            oldCalls += referenceCalls.get();
            var timeCalls = new AtomicInteger();
            var nameCalls = new AtomicInteger();
            var actual = MediaOrderingKt.sortMedia(items, byName,
                item -> { timeCalls.incrementAndGet(); return item.time(); },
                item -> { nameCalls.incrementAndGet(); return item.name(); });
            assert actual.equals(expected) : "정렬 순서 또는 동률 안정성 변경";
            assert timeCalls.get() == items.size() && nameCalls.get() == items.size() : "정렬 키 반복 계산";
        }

        var queue = QueueMedia.INSTANCE;
        assert MediaRules.INSTANCE.mediaKind("/storage/photo.PNG", "") == 2 : "이미지 선택 수 분류 오류";
        assert MediaRules.INSTANCE.mediaKind("/storage/song.mp3", "") == 3;
        assert MediaRules.INSTANCE.mediaKind("/storage/song.mp3.mp4", "") == 1 : "제목 중간의 확장자를 음원으로 오인";
        assert queue.snapshot(Map.of(), () -> Unit.INSTANCE).getStates().isEmpty();
        var first = task("0", "first", 1);
        var second = task("1", "second", 3);
        var tasks = new LinkedHashMap<PluginTask, PluginTask.State>();
        tasks.put(first, completed("/storage/first.mp4", false));
        tasks.put(second, completed("/storage/second.mp4", false));
        var snapshot = queue.snapshot(tasks, () -> Unit.INSTANCE);
        assert snapshot.getStates().size() == 1 : "같은 게시물 중복 카드";
        var card = snapshot.getStates().get(first);
        assert card.getViewState().getMediaCount() == 2 && card.getViewState().getCompletedCount() == 2;
        assert queue.completedPages(second, tasks).equals(List.of("/storage/first.mp4", "/storage/second.mp4"));
        assert queue.members(first, tasks).size() == 2;

        var retry = task("0", "retry", 4);
        tasks.put(retry, completed("/storage/retry.mp4", false));
        snapshot = queue.snapshot(tasks, () -> Unit.INSTANCE);
        assert snapshot.getStates().size() == 1 && snapshot.getStates().get(second).getViewState().getCompletedCount() == 2 : "재시도 페이지 중복 집계";

        tasks.clear();
        tasks.put(first, completed("/storage/first-AI-deadbeef.mp4", false));
        snapshot = queue.snapshot(tasks, () -> Unit.INSTANCE);
        assert snapshot.getMissingPages().get(first) == 1 : "AI 복제만 남은 원본 삭제 누락";
        assert snapshot.getStates().get(first).getViewState().getMediaCount() == 3 : "게시물 원본 수와 AI 복제 수 누락";
        tasks.put(first, completed("/storage/first.mp4", true));
        assert queue.snapshot(tasks, () -> Unit.INSTANCE).getMissingPages().get(first) == 1;

        tasks.put(first, state(new PluginTask.DownloadState.Running(JobKt.Job(null), "first", Float.NaN, "")));
        tasks.put(second, state(new PluginTask.DownloadState.Running(JobKt.Job(null), "second", 2f, "")));
        card = queue.snapshot(tasks, () -> Unit.INSTANCE).getStates().get(first);
        float progress = ((PluginTask.DownloadState.Running) card.getDownloadState()).getProgress();
        assert Float.isFinite(progress) && Math.abs(progress - 0.5f) < 0.0001f : "잘못된 진행률이 UI에 전달됨";
        var calls = new AtomicInteger();
        try {
            queue.snapshot(tasks, () -> { if (calls.incrementAndGet() == 2) throw new CancellationException(); return Unit.INSTANCE; });
            throw new AssertionError("취소된 집계 결과 반환");
        } catch (CancellationException expected) {
            assert calls.get() == 2;
        }
        assert first.hashCode() == Arrays.hashCode(new Object[] {first.getUrl(), first.getType(), first.getPreferences(), first.getId()}) : "기존 해시 계약 변경";
        System.out.println("정렬·동률·집계·재시도·AI 원본 삭제·진행률·취소·해시 검사 통과");
        System.out.println("정렬 키 조회: 기존 " + oldCalls + "회, 최적화 " + (items.size() * 4) + "회 (이름순·시간순 합계)");
    }
}
