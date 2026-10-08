package cn.local.manga;

import java.util.*;

/** Production scheduler checks; no HTTP calls or Android UI. */
public final class QueueRetryChecks {
    static int checks;
    static final long ROOM = 2L * 1024 * 1024 * 1024;

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    static AutoTranslationQueue.Image image(String id, boolean translated) {
        return new AutoTranslationQueue.Image(
                id, "https://test/" + id, true, translated, Integer.parseInt(id));
    }

    public static void main(String[] args) {
        AutoTranslationQueue q = new AutoTranslationQueue();
        q.configureTextTarget(32);
        q.resume(0);
        List<AutoTranslationQueue.Image> pages = new ArrayList<>();
        for (int i = 0; i < 34; i++) pages.add(image("" + i, false));
        q.scan(pages);
        List<AutoTranslationQueue.Image> first = q.take(0, ROOM, ROOM);
        check(first.size() == 34, "initial 34-page fixture admitted");
        int epoch = q.epoch();
        for (int i = 0; i < 34; i++) q.finish(first.get(i), epoch, i < 21, false, 1, false);
        pages.clear();
        for (int i = 0; i < 34; i++) pages.add(image("" + i, i < 21));
        q.scan(pages);
        check(
                q.completed() == 21 && q.exhausted() == 13 && q.waiting() == 0,
                "21 finished and 13 exhausted at end of first run");
        q.restartUnfinished();
        q.requestedOnly(false);
        q.resume(10000);
        q.scan(pages);
        List<AutoTranslationQueue.Image> retries = q.take(10000, ROOM, ROOM);
        check(retries.size() == 13, "Start readmits exactly the remaining 13 pages");
        check(
                retries.stream()
                        .allMatch(
                                x ->
                                        Integer.parseInt(x.id) >= 21
                                                && x.retryMode == 1
                                                && !x.cacheOnly),
                "successful 21 excluded; missing-only mode bypasses composite cache");
        check(
                q.completed() == 21 && q.epoch() == epoch,
                "restart retains completed state and generation");
        for (AutoTranslationQueue.Image p : retries) q.finish(p, epoch, true, false, 10001);
        check(q.completed() == 34, "remaining pages finish without double-counting successes");
        pages.clear();
        for (int i = 0; i < 34; i++) pages.add(image("" + i, true));
        q.scan(pages);
        q.restartUnfinished();
        q.resume(20000);
        check(
                q.take(20000, ROOM, ROOM).isEmpty(),
                "Start after full completion issues no new translations");

        AutoTranslationQueue active = new AutoTranslationQueue();
        active.configureTextTarget(3);
        active.resume(0);
        AutoTranslationQueue.Image a = image("1", false),
                b = image("2", false),
                completed = image("3", true);
        active.scan(Arrays.asList(a, b, completed));
        List<AutoTranslationQueue.Image> running = active.take(0, ROOM, ROOM);
        check(running.size() == 2, "other two pages active");
        int token = active.epoch();
        check(active.retry(completed, true), "translated page can reenter request queue");
        check(
                active.epoch() == token && active.running() == 2,
                "long press never stops or invalidates other workers");
        active.scan(Arrays.asList(a, b, completed));
        List<AutoTranslationQueue.Image> manual = active.take(1, ROOM, ROOM);
        check(
                manual.size() == 1
                        && manual.get(0).id.equals("3")
                        && manual.get(0).retryMode == 2
                        && !manual.get(0).cacheOnly,
                "fresh retranslation admitted despite translated DOM state");
        check(
                active.running() == 3 && !active.retry(completed, true),
                "repeat click does not duplicate in-flight request");
        check(active.take(2, ROOM, ROOM).isEmpty(), "same page never has overlapping workers");
        active.finish(running.get(0), token, true, false, 3);
        active.finish(running.get(1), token, true, false, 3);
        active.finish(manual.get(0), token, true, false, 3);
        check(active.completed() == 3, "unrelated callbacks remain valid");
        check(
                active.retry(image("3", true), false) && active.completed() == 2,
                "partial page reset affects only its own completion");
        check(!active.retry(image("3", true), true), "queued click coalesces");
        active.scan(Collections.emptyList());
        manual = active.take(5000, ROOM, ROOM);
        check(
                manual.size() == 1 && manual.get(0).retryMode == 2,
                "queued retry survives scroll scan and upgrades to fresh mode");
        active.finish(manual.get(0), token, false, false, 5001, false);
        active.scan(List.of(image("3", true)));
        check(
                active.take(12000, ROOM, ROOM).isEmpty(),
                "paid retry failure is not silently resubmitted forever");
        active.restartUnfinished();
        active.resume(13000);
        manual = active.take(13000, ROOM, ROOM);
        check(
                manual.size() == 1 && manual.get(0).retryMode == 2,
                "explicit Start preserves failed fresh-retry intent instead of serving old cached"
                        + " text");

        AutoTranslationQueue single = new AutoTranslationQueue();
        single.requestedOnly(true);
        single.configureTextTarget(10);
        single.resume(0);
        single.retry(image("1", true), false);
        single.scan(List.of(image("1", true), image("2", false)));
        List<AutoTranslationQueue.Image> only = single.take(0, ROOM, ROOM);
        check(
                only.size() == 1 && only.get(0).id.equals("1") && only.get(0).retryMode == 1,
                "idle long-press starts only requested page with cached text reuse");
        single.stop();
        single.scan(List.of(image("1", true), image("2", false)));
        check(single.take(5000, ROOM, ROOM).isEmpty(), "Stop removes pending manual requests");
        System.out.println(
                "QueueRetryChecks: "
                        + checks
                        + " checks passed (34/21/13 resume, shared queue, deduplication, retry"
                        + " modes)");
    }
}
