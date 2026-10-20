import com.junkfood.seal.util.DownloadUtil;
import com.plugin.compatibility.InstagramMedia;
import com.plugin.compatibility.MediaOrderingKt;
import com.plugin.compatibility.MediaRules;
import com.plugin.compatibility.QueueMedia;
import com.plugin.queue.PluginTask;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import kotlin.Unit;
import kotlinx.coroutines.JobKt;

/** 실제 앱 클래스의 정렬·게시물 집계·취소·해시 계약을 프레임워크 없이 검사합니다. */
class CheckOptimizations {
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
