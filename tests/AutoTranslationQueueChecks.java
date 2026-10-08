package cn.local.manga;

import java.util.*;

public final class AutoTranslationQueueChecks {
    private static int checks;
    private static final long ROOM = 1024L * 1024 * 1024;

    private static void ok(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static AutoTranslationQueue.Image image(
            String id, boolean visible, boolean translated, int order) {
        return new AutoTranslationQueue.Image(id, "https://test/" + id, visible, translated, order);
    }

    private static void canvasMemory() {
        AutoTranslationQueue.Image canvas =
                new AutoTranslationQueue.Image(
                        "canvas", "manga-canvas:canvas:1", true, false, false, false, 0, 800, 1200);
        AutoTranslationQueue.Image ordinary = image("ordinary", true, false, 1);
        AutoTranslationQueue admission = new AutoTranslationQueue();
        admission.resume(0);
        admission.scan(Arrays.asList(canvas, ordinary));
        List<AutoTranslationQueue.Image> first = admission.take(0, ordinary.memoryBytes, ROOM);
        ok(
                first.size() == 1 && first.get(0) == ordinary,
                "canvas reconstruction cannot use ordinary-image-only budget or starve a fitting"
                        + " image");
        admission.finish(ordinary, admission.epoch(), true, true, 0);
        ok(
                admission.take(1000, canvas.memoryBytes - 1, ROOM).isEmpty()
                        && admission.waitingForMemory(),
                "canvas waits until its complete reconstruction reservation fits");
        List<AutoTranslationQueue.Image> prepared = admission.take(1000, canvas.memoryBytes, ROOM);
        ok(
                prepared.size() == 1
                        && prepared.get(0) == canvas
                        && canvas.reservedBytes == canvas.memoryBytes,
                "canvas resumes at exact full peak without losing queued work");
        admission.finish(canvas, admission.epoch(), true, false, 1000);
        AutoTranslationQueue.Image returning =
                new AutoTranslationQueue.Image(
                        canvas.id, canvas.url, true, false, false, false, 0, 800, 1200);
        admission.scan(Collections.singletonList(returning));
        List<AutoTranslationQueue.Image> restored =
                admission.take(5000, returning.restorationBytes, ROOM);
        ok(
                restored.size() == 1
                        && returning.cacheOnly
                        && returning.reservedBytes == returning.restorationBytes,
                "completed canvas restores cached pixels without reserving source reconstruction"
                        + " again");
        admission.cacheMiss(returning, admission.epoch());
        ok(
                admission.take(6000, returning.restorationBytes, ROOM).isEmpty()
                        && admission.waitingForMemory(),
                "missing canvas cache returns to full reconstruction budget before fresh work");

        AutoTranslationQueue.Image canvasOutput =
                new AutoTranslationQueue.Image(
                        "generated",
                        "manga-canvas:generated:1",
                        true,
                        false,
                        false,
                        false,
                        0,
                        800,
                        1200,
                        true);
        AutoTranslationQueue imageMode = new AutoTranslationQueue();
        imageMode.resume(0);
        imageMode.scan(Collections.singletonList(canvasOutput));
        long heap = 256L * AutoTranslationQueue.MIB;
        ok(
                imageMode.take(0, ROOM, heap).isEmpty()
                        && imageMode.oversized() == 1
                        && !imageMode.waitingForMemory(),
                "canvas plus image-model response exceeding guarded heap is terminal oversized"
                        + " rather than endless waiting");
        ok(
                imageMode.take(1000, ROOM, 512L * AutoTranslationQueue.MIB).size() == 1,
                "canvas image mode becomes feasible when full heap allowance actually grows");

        AutoTranslationQueue textMode = new AutoTranslationQueue();
        textMode.configureTextTarget(2);
        textMode.resume(0);
        textMode.scan(Arrays.asList(canvas, ordinary));
        List<AutoTranslationQueue.Image> staged =
                textMode.take(0, 8L * AutoTranslationQueue.MIB, 96L * AutoTranslationQueue.MIB);
        ok(
                staged.size() == 1 && staged.get(0) == ordinary && textMode.oversized() == 1,
                "lightweight text admission still rejects canvas peak that cannot fit maximum"
                        + " heap");
        ok(
                ordinary.reservedBytes == 4L * AutoTranslationQueue.MIB,
                "ordinary staged text job keeps its small metadata-only reservation");
        AutoTranslationQueue.Image canStage =
                new AutoTranslationQueue.Image(
                        "staged-canvas",
                        "manga-canvas:staged:1",
                        true,
                        false,
                        false,
                        false,
                        0,
                        800,
                        1200);
        textMode.stop();
        textMode.resume(0);
        textMode.scan(Collections.singletonList(canStage));
        ok(
                textMode.take(0, 4L * AutoTranslationQueue.MIB, 256L * AutoTranslationQueue.MIB)
                                        .size()
                                == 1
                        && canStage.reservedBytes == 4L * AutoTranslationQueue.MIB,
                "feasible canvas text job uses staged pixel gate rather than reserving pixels"
                        + " through HTTP wait");
    }

    public static void main(String[] args) {
        AutoTranslationQueue q = new AutoTranslationQueue();
        q.resume(0);
        List<AutoTranslationQueue.Image> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) all.add(image("page" + i, i == 10, false, i));
        q.scan(all);
        ok(q.waiting() == 20, "all loaded metadata admitted, no three-page limit");
        List<AutoTranslationQueue.Image> first = q.take(0, ROOM);
        ok(first.size() == 1 && first.get(0).id.equals("page10"), "start gently with visible page");
        ok(q.take(0, ROOM).isEmpty(), "no duplicate in-flight work");
        ok(q.take(1000, ROOM).size() == 1, "grow as resources permit");
        ok(
                q.take(2000, ROOM).size() == 1 && q.running() == 3,
                "resource allowance permits more than two simultaneous tasks");
        ok(
                q.take(3000, ROOM).size() == 1 && q.running() == 4,
                "no replacement fixed concurrency cap");
        int epoch = q.epoch();
        for (AutoTranslationQueue.Image x : q.runningImages())
            q.finish(x, epoch, true, false, 4000);
        ok(q.completed() == 4, "completion count");
        ok(
                q.take(4100, ROOM).size() == 5,
                "continue processing all loaded candidates beyond first three");
        q.stop();
        ok(q.running() == 0, "cancel releases reservations");
        q.finish(first.get(0), epoch, true, false, 5000);
        ok(q.completed() == 4, "old generation completion ignored");
        q.resume(5000);
        q.scan(Arrays.asList(image("new", true, false, 100), image("other", false, false, 0)));
        ok(
                q.take(5000, ROOM).get(0).id.equals("new"),
                "newly visible image wins after scrolling and resume");

        AutoTranslationQueue memory = new AutoTranslationQueue();
        memory.resume(0);
        AutoTranslationQueue.Image big = image("big", true, false, 0);
        memory.scan(Arrays.asList(big));
        ok(
                memory.take(0, big.memoryBytes - 1).isEmpty() && memory.running() == 0,
                "insufficient memory does not submit bitmap allocation");
        ok(
                memory.take(1000, big.memoryBytes).size() == 1,
                "one task progresses when full estimated budget fits");
        AutoTranslationQueue.Image small = image("small", false, false, 1);
        memory.scan(Arrays.asList(big, small));
        ok(
                memory.take(2000, big.memoryBytes + small.memoryBytes - 1).isEmpty(),
                "running task reservation applies backpressure");
        ok(
                AutoTranslationQueue.availableMemory(256L * 1024 * 1024, 250L * 1024 * 1024) == 0,
                "critically low heap never forces one task");
        ok(
                AutoTranslationQueue.estimateMemory(600, 1000)
                        < AutoTranslationQueue.estimateMemory(1600, 2400),
                "image dimensions weight allocation budget");
        ok(
                AutoTranslationQueue.estimateMemory(Integer.MAX_VALUE, Integer.MAX_VALUE)
                        < 256L * 1024 * 1024,
                "huge dimensions normalized without overflow");
        AutoTranslationQueue impossible = new AutoTranslationQueue();
        impossible.resume(0);
        impossible.scan(Arrays.asList(big));
        ok(
                impossible.take(0, 1, 1).isEmpty()
                        && impossible.oversized() == 1
                        && impossible.waiting() == 0,
                "image larger than maximum heap rejected instead of permanent wait");
        ok(
                impossible.take(1000, ROOM, ROOM).size() == 1,
                "re-evaluate feasibility after resource or mode changes");
        impossible.defer(big, impossible.epoch());
        ok(
                impossible.running() == 0 && impossible.take(2000, ROOM, ROOM).size() == 1,
                "executor allocation failure returns unsent work without losing attempt");
        AutoTranslationQueue headroom = new AutoTranslationQueue();
        headroom.resume(0);
        AutoTranslationQueue.Image overBudget =
                new AutoTranslationQueue.Image(
                        "headroom",
                        "https://test/headroom",
                        true,
                        false,
                        false,
                        false,
                        0,
                        2048,
                        2048,
                        true);
        long heap = 256L * 1024 * 1024;
        headroom.scan(Arrays.asList(overBudget));
        ok(
                overBudget.memoryBytes <= heap
                        && overBudget.memoryBytes > AutoTranslationQueue.availableMemory(heap, 0),
                "fixture fits heap but exceeds attainable guarded budget");
        ok(
                headroom.take(0, AutoTranslationQueue.availableMemory(heap, 0), heap).isEmpty()
                        && headroom.oversized() == 1
                        && headroom.waiting() == 0
                        && !headroom.waitingForMemory(),
                "unattainable guarded budget is oversized rather than permanent memory wait");

        AutoTranslationQueue retries = new AutoTranslationQueue();
        retries.resume(0);
        AutoTranslationQueue.Image e = image("e", true, false, 0);
        retries.scan(Arrays.asList(e));
        retries.take(0, ROOM);
        retries.finish(e, retries.epoch(), false, false, 0);
        retries.backoff(0, true);
        ok(retries.take(29999, ROOM).isEmpty(), "429/503 cools entire queue for thirty seconds");
        ok(retries.take(30000, ROOM).size() == 1, "retry after cooldown");
        retries.finish(e, retries.epoch(), false, false, 30000);
        ok(
                retries.take(999999, ROOM).isEmpty() && retries.exhausted() == 1,
                "maximum two attempts prevents retry billing loop");
        retries.clear();
        retries.resume(0);
        retries.scan(Arrays.asList(e));
        retries.take(0, ROOM);
        retries.finish(e, retries.epoch(), true, true, 0);
        ok(retries.take(99999, ROOM).isEmpty(), "no-text image is terminal");

        AutoTranslationQueue restored = new AutoTranslationQueue();
        restored.resume(0);
        AutoTranslationQueue.Image original = image("cached", true, false, 0);
        restored.scan(Arrays.asList(original));
        restored.take(0, ROOM);
        restored.finish(original, restored.epoch(), true, false, 0);
        ok(
                restored.take(1000, ROOM).isEmpty(),
                "late pre-completion scan cannot immediately restart task");
        restored.scan(Arrays.asList(image("cached", false, false, 0)));
        ok(
                restored.take(5000, ROOM).isEmpty(),
                "far evicted completed image does not cause cache refill thrash");
        restored.scan(Arrays.asList(original));
        ok(
                restored.take(6000, ROOM).size() == 1,
                "visible evicted image can restore from native cache");
        restored.finish(original, restored.epoch(), true, false, 6000);
        AutoTranslationQueue.Image near =
                new AutoTranslationQueue.Image(
                        "cached",
                        original.url,
                        false,
                        true,
                        false,
                        false,
                        0,
                        800,
                        1200,
                        false,
                        true);
        restored.scan(Arrays.asList(near));
        ok(
                restored.take(8000, ROOM).size() == 1 && near.cacheOnly,
                "near viewport restores only cached pixels before entering screen");
        restored.finish(near, restored.epoch(), true, false, 8000);
        ok(
                restored.take(12000, ROOM).isEmpty(),
                "same viewport cannot thrash a repeatedly evicted near image");
        restored.scan(Arrays.asList(original));
        restored.take(13000, ROOM);
        restored.cacheMiss(original, restored.epoch());
        restored.scan(Arrays.asList(near));
        ok(
                restored.take(14000, ROOM).isEmpty(),
                "near viewport cache miss waits without API request");
        restored.scan(Arrays.asList(original));
        ok(
                restored.take(15000, ROOM).size() == 1 && !original.cacheOnly,
                "visible cache miss can request translation under full memory reservation");
        restored.clear();
        restored.resume(0);
        AutoTranslationQueue.Image duplicate =
                new AutoTranslationQueue.Image("other", original.url, true, false, 0);
        restored.scan(Arrays.asList(original, duplicate));
        restored.take(0, ROOM);
        ok(restored.take(2000, ROOM).isEmpty(), "same source URL never runs concurrently");
        restored.clear();
        restored.resume(0);
        restored.scan(
                Arrays.asList(
                        new AutoTranslationQueue.Image(
                                "pending",
                                "https://test/p",
                                false,
                                true,
                                false,
                                true,
                                0,
                                800,
                                1200)));
        ok(
                restored.readyAhead() == 0 && restored.take(0, ROOM).isEmpty(),
                "pending replacement is not ready and not duplicated");
        restored.scan(
                Arrays.asList(
                        new AutoTranslationQueue.Image(
                                "behind",
                                "https://test/b",
                                false,
                                false,
                                true,
                                false,
                                0,
                                800,
                                1200),
                        new AutoTranslationQueue.Image(
                                "ahead",
                                "https://test/a",
                                false,
                                true,
                                true,
                                false,
                                1,
                                800,
                                1200)));
        ok(restored.readyAhead() == 1, "ready count excludes already-read images behind viewport");
        restored.clear();
        restored.resume(0);
        restored.scan(Collections.emptyList());
        ok(restored.take(0, ROOM).isEmpty(), "empty queue waits");
        restored.scan(Arrays.asList(e));
        ok(restored.take(1, ROOM).size() == 1, "later loaded image joins automatically");
        ok(
                AutoTranslationQueue.storageBudget(0, 4L * ROOM) > 100L * 1024 * 1024,
                "cache grows with available storage rather than fixed 100MiB");
        ok(
                AutoTranslationQueue.storageBudget(0, 256L * 1024 * 1024) == 0,
                "system storage reserve is never assigned to cache");
        ok(
                AutoTranslationQueue.storageBudget(100L * 1024 * 1024, 4L * ROOM)
                        > AutoTranslationQueue.storageBudget(0, 4L * ROOM),
                "existing cache participates in stable storage budget");
        AutoTranslationQueue disk = new AutoTranslationQueue();
        disk.resume(0);
        disk.scan(Arrays.asList(e));
        disk.take(0, ROOM);
        disk.finish(e, disk.epoch(), true, false, 0);
        disk.blockNewTranslations();
        disk.scan(Arrays.asList(e, image("fresh", false, false, 1)));
        ok(
                disk.take(5000, ROOM).size() == 1 && e.cacheOnly,
                "storage pressure still permits completed image restoration");
        disk.finish(e, disk.epoch(), true, false, 5000);
        ok(
                disk.take(5500, ROOM).isEmpty() && disk.waitingForStorage(),
                "storage pressure prevents fresh API work");
        disk.stop();
        disk.resume(6000);
        disk.scan(Arrays.asList(image("fresh", false, false, 1)));
        ok(disk.take(6000, ROOM).size() == 1, "explicit restart rechecks storage for new work");
        AutoTranslationQueue staged = new AutoTranslationQueue();
        staged.configureTextTarget(10);
        staged.resume(0);
        staged.scan(all);
        ok(
                staged.take(0, 80L * 1024 * 1024, 256L * 1024 * 1024).size() == 10,
                "ten staged text jobs admitted without reserving ten page bitmaps");
        ok(
                staged.runningImages().stream().mapToLong(x -> x.reservedBytes).sum()
                        == 40L * 1024 * 1024,
                "network-wait job reservations are lightweight");
        staged.stop();
        staged.configureTextTarget(16);
        staged.resume(0);
        staged.scan(all);
        ok(
                staged.take(0, 80L * 1024 * 1024, 256L * 1024 * 1024).size() == 16,
                "higher user target permits more than ten staged jobs");
        canvasMemory();
        System.out.println(
                "AutoTranslationQueue: "
                        + checks
                        + " checks passed (all-loaded, adaptive concurrency, memory backpressure,"
                        + " retry, cancellation, canvas reconstruction)");
    }
}
