package cn.local.manga;

import com.sun.net.httpserver.HttpServer;

import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Actual admission classes and actual loopback transport; no paid API or Android bitmap work. */
public final class V073SchedulerChecks {
    private static int checks;
    private static final BooleanSupplier RUNNING = () -> false;
    private static final Method TRANSPORT;

    static {
        try {
            TRANSPORT =
                    ApiClient.class.getDeclaredMethod(
                            "requestStream",
                            String.class,
                            String.class,
                            String.class,
                            long.class,
                            Class.forName("cn.local.manga.ApiClient$BodyWriter"),
                            String.class,
                            BooleanSupplier.class,
                            int.class,
                            AppSettings.class);
            TRANSPORT.setAccessible(true);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void ok(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) throw new AssertionError("condition timed out");
            Thread.sleep(5);
        }
    }

    private static byte[] http(String address, AppSettings settings) throws Exception {
        try {
            return (byte[])
                    TRANSPORT.invoke(
                            null, address, "GET", null, -1L, null, null, RUNNING, 2048, settings);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        }
    }

    private static AppSettings settings() {
        AppSettings settings = new AppSettings();
        settings.maxRetries = 1;
        settings.retryIntervalSeconds = 1;
        settings.rateLimitWaitSeconds = 1;
        settings.requestTimeoutSeconds = 10;
        return settings;
    }

    private static <T> T phase(
            TranslationStages stages,
            String phase,
            Callable<T> action,
            BooleanSupplier cancelled,
            IntSupplier priority)
            throws Exception {
        if (phase.equals("network")) return stages.request(action, cancelled, priority);
        if (phase.equals("detection")) return stages.detection(action, cancelled, priority);
        try (TranslationStages.Lease ignored = stages.pixels(64, cancelled, priority)) {
            return action.call();
        }
    }

    private static int waiting(TranslationStages stages, String phase) {
        try {
            Field field =
                    TranslationStages.class.getDeclaredField(
                            phase.equals("pixels") ? "PIXEL_WAITERS" : phase + "Waiters");
            field.setAccessible(true);
            if (phase.equals("pixels")) {
                Field lock = TranslationStages.class.getDeclaredField("PIXEL_LOCK");
                lock.setAccessible(true);
                synchronized (lock.get(null)) {
                    return ((List<?>) field.get(null)).size();
                }
            }
            synchronized (stages) {
                return ((List<?>) field.get(stages)).size();
            }
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void priorities(ExecutorService workers, String phase) throws Exception {
        TranslationStages stages = new TranslationStages(1, 64, () -> 64);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> owner =
                workers.submit(
                        () ->
                                phase(
                                        stages,
                                        phase,
                                        () -> {
                                            entered.countDown();
                                            release.await();
                                            return null;
                                        },
                                        RUNNING,
                                        () -> 0));
        ok(entered.await(2, TimeUnit.SECONDS), phase + " owner entered");
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger movedPriority = new AtomicInteger(2);
        Future<?> background =
                workers.submit(
                        () ->
                                phase(
                                        stages,
                                        phase,
                                        () -> {
                                            order.add("background");
                                            return null;
                                        },
                                        RUNNING,
                                        () -> 2));
        until(() -> waiting(stages, phase) == 1);
        Future<?> moved =
                workers.submit(
                        () ->
                                phase(
                                        stages,
                                        phase,
                                        () -> {
                                            order.add("visible");
                                            return null;
                                        },
                                        RUNNING,
                                        movedPriority::get));
        until(() -> waiting(stages, phase) == 2);
        movedPriority.set(0);
        release.countDown();
        owner.get(3, TimeUnit.SECONDS);
        moved.get(3, TimeUnit.SECONDS);
        background.get(3, TimeUnit.SECONDS);
        ok(
                order.equals(Arrays.asList("visible", "background")),
                phase + " samples changed viewport priority for already queued work");
        CountDownLatch held = new CountDownLatch(1), unblock = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        Future<?> holder =
                workers.submit(
                        () ->
                                phase(
                                        stages,
                                        phase,
                                        () -> {
                                            held.countDown();
                                            unblock.await();
                                            return null;
                                        },
                                        RUNNING,
                                        () -> 0));
        held.await(2, TimeUnit.SECONDS);
        Future<Boolean> abandoned =
                workers.submit(
                        () -> {
                            try {
                                phase(stages, phase, () -> null, cancelled::get, () -> 0);
                                return false;
                            } catch (CancellationException expected) {
                                return true;
                            }
                        });
        until(() -> waiting(stages, phase) == 1);
        cancelled.set(true);
        ok(abandoned.get(2, TimeUnit.SECONDS), phase + " cancelled queued work exits");
        ok(waiting(stages, phase) == 0, phase + " cancelled waiter is removed");
        unblock.countDown();
        holder.get(3, TimeUnit.SECONDS);
        ok(
                phase(stages, phase, () -> 42, RUNNING, () -> 2) == 42,
                phase + " cancellation leaks no permit");
        ok(
                stages.networkActive() == 0 && stages.reservedBytes() == 0,
                phase + " all resources released");
    }

    private static void queue() {
        long room = 1024L * 1024 * 1024;
        AutoTranslationQueue queue = new AutoTranslationQueue();
        queue.configureTextTarget(2);
        queue.resume(0);
        List<AutoTranslationQueue.Image> chapter = new ArrayList<>();
        for (int i = 0; i < 34; i++)
            chapter.add(
                    new AutoTranslationQueue.Image(
                            "p" + i, "https://comic.test/" + i, false, false, i));
        queue.scan(chapter);
        ok(queue.waiting() == 34, "all 34 chapter pages remain queued");
        List<AutoTranslationQueue.Image> active = queue.take(0, room, room);
        ok(active.size() == 2, "background admission respects configured target");
        AutoTranslationQueue.Image retained = chapter.get(20);
        for (int i = 20; i < 23; i++)
            chapter.set(
                    i,
                    new AutoTranslationQueue.Image(
                            "p" + i, "https://comic.test/" + i, true, false, i));
        queue.scan(chapter);
        ok(
                queue.priority(retained) == 0 && queue.priority(active.get(0)) == 2,
                "priority snapshot updates old task objects by identity");
        List<AutoTranslationQueue.Image> visible = queue.take(0, room, room);
        ok(
                visible.size() == 2
                        && visible.stream().allMatch(x -> x.visible)
                        && queue.running() == 4,
                "at most two newly visible jobs bypass occupied background page slots");
        ok(queue.take(0, room, room).isEmpty(), "extra visible admission is bounded");
        ok(queue.waiting() == 30, "remaining chapter metadata is preserved");
        AutoTranslationQueue.Image completed = active.get(0);
        queue.finish(completed, queue.epoch(), true, false, 0);
        ok(
                queue.take(0, room, room).size() == 1,
                "third current page enters when an overflow job slot frees");
        queue.stop();
        ok(
                queue.running() == 0 && queue.priority(retained) == 3,
                "stop clears admission and priority snapshot");
    }

    private static void cooldown(
            ExecutorService workers,
            String address,
            int status,
            int interval,
            int limited,
            int retryAfter,
            long expected)
            throws Exception {
        AtomicLong clock = new AtomicLong();
        TranslationStages stages = new TranslationStages(2, 64, () -> 64, clock::get);
        AppSettings options = settings();
        options.maxRetries = 0;
        options.retryIntervalSeconds = interval;
        options.rateLimitWaitSeconds = limited;
        try {
            stages.withRequests(
                    () -> http(address + "/throttle/" + status + "/" + retryAfter, options),
                    RUNNING,
                    () -> 2);
            throw new AssertionError("expected throttle");
        } catch (ApiClient.RequestFailure failure) {
            ok(failure.status == status, "actual transport retains throttle status " + status);
        }
        ok(stages.networkActive() == 0, "throttle delay owns no HTTP slot");
        AtomicInteger calls = new AtomicInteger();
        Future<Integer> next =
                workers.submit(
                        () ->
                                stages.withRequests(
                                        () ->
                                                TranslationStages.requestAttempt(
                                                        calls::incrementAndGet, RUNNING),
                                        RUNNING,
                                        () -> 0));
        until(() -> waiting(stages, "network") == 1);
        clock.set(expected - 1);
        Thread.sleep(150);
        ok(calls.get() == 0, "shared throttle waits full settings/header delay " + expected);
        clock.set(expected);
        ok(
                next.get(2, TimeUnit.SECONDS) == 1,
                "shared throttle resumes exactly at settings/header delay " + expected);
    }

    private static void overlappingCooldown(ExecutorService workers) throws Exception {
        AtomicLong clock = new AtomicLong();
        TranslationStages stages = new TranslationStages(2, 64, () -> 64, clock::get);
        CountDownLatch entered = new CountDownLatch(2),
                longer = new CountDownLatch(1),
                shorter = new CountDownLatch(1);
        Future<?> first =
                workers.submit(
                        () -> {
                            try {
                                stages.withRequests(
                                        () ->
                                                TranslationStages.requestAttempt(
                                                        () -> {
                                                            entered.countDown();
                                                            longer.await();
                                                            TranslationStages
                                                                    .throttleWaitForAttempt(120000);
                                                            throw new ApiClient.RequestFailure(
                                                                    "HTTP 429", true, 0, 429);
                                                        },
                                                        RUNNING),
                                        RUNNING,
                                        () -> 0);
                            } catch (Exception expected) {
                            }
                            ;
                        });
        Future<?> second =
                workers.submit(
                        () -> {
                            try {
                                stages.withRequests(
                                        () ->
                                                TranslationStages.requestAttempt(
                                                        () -> {
                                                            entered.countDown();
                                                            shorter.await();
                                                            TranslationStages
                                                                    .throttleWaitForAttempt(1000);
                                                            throw new ApiClient.RequestFailure(
                                                                    "HTTP 503", true, 0, 503);
                                                        },
                                                        RUNNING),
                                        RUNNING,
                                        () -> 0);
                            } catch (Exception expected) {
                            }
                            ;
                        });
        ok(
                entered.await(2, TimeUnit.SECONDS),
                "two admitted attempts can report different throttle delays");
        longer.countDown();
        first.get(2, TimeUnit.SECONDS);
        shorter.countDown();
        second.get(2, TimeUnit.SECONDS);
        ok(stages.networkActive() == 0, "concurrent throttle failures release both permits");
        AtomicInteger calls = new AtomicInteger();
        Future<Integer> next =
                workers.submit(() -> stages.request(calls::incrementAndGet, RUNNING));
        until(() -> waiting(stages, "network") == 1);
        clock.set(119999);
        Thread.sleep(150);
        ok(calls.get() == 0, "later short throttle cannot shorten earlier server cooldown");
        clock.set(120000);
        ok(next.get(2, TimeUnit.SECONDS) == 1, "longest concurrent cooldown eventually resumes");
    }

    public static void main(String[] args) throws Exception {
        ExecutorService workers = Executors.newCachedThreadPool(),
                handlers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        AtomicInteger retryCount = new AtomicInteger(),
                active = new AtomicInteger(),
                peak = new AtomicInteger();
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch failed = new CountDownLatch(1), three = new CountDownLatch(3);
        server.createContext(
                "/retry",
                exchange -> {
                    int attempt = retryCount.incrementAndGet();
                    order.add("retry" + attempt);
                    byte[] body = (attempt == 1 ? "{}" : "ok").getBytes(StandardCharsets.UTF_8);
                    try {
                        exchange.sendResponseHeaders(attempt == 1 ? 502 : 200, body.length);
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                        if (attempt == 1) failed.countDown();
                    }
                });
        server.createContext(
                "/fast",
                exchange -> {
                    order.add("fast");
                    byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                    try {
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });
        server.createContext(
                "/throttle",
                exchange -> {
                    String[] parts = exchange.getRequestURI().getPath().split("/");
                    int status = Integer.parseInt(parts[2]), after = Integer.parseInt(parts[3]);
                    if (after > 0)
                        exchange.getResponseHeaders().set("Retry-After", Integer.toString(after));
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    try {
                        exchange.sendResponseHeaders(status, body.length);
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });
        server.createContext(
                "/overlap",
                exchange -> {
                    int n = active.incrementAndGet();
                    peak.accumulateAndGet(n, Math::max);
                    three.countDown();
                    try {
                        three.await(3, TimeUnit.SECONDS);
                        Thread.sleep(40);
                        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                        exchange.close();
                    }
                });
        server.start();
        String address = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            for (String phase : Arrays.asList("network", "detection", "pixels"))
                priorities(workers, phase);
            queue();
            TranslationStages stages = new TranslationStages(1, 64, () -> 64);
            AppSettings options = settings();
            Future<byte[]> retry =
                    workers.submit(
                            () ->
                                    stages.withRequests(
                                            () -> http(address + "/retry", options),
                                            RUNNING,
                                            () -> 2));
            ok(failed.await(2, TimeUnit.SECONDS), "first real HTTP attempt returned 502");
            until(() -> stages.networkActive() == 0);
            ok(!retry.isDone(), "retry is waiting with no network permit");
            Future<byte[]> fast =
                    workers.submit(
                            () ->
                                    stages.withRequests(
                                            () -> http(address + "/fast", options),
                                            RUNNING,
                                            () -> 0));
            ok(
                    new String(fast.get(800, TimeUnit.MILLISECONDS), StandardCharsets.UTF_8)
                            .equals("ok"),
                    "current page uses released slot before background retry");
            ok(
                    new String(retry.get(3, TimeUnit.SECONDS), StandardCharsets.UTF_8).equals("ok"),
                    "same background request completes its configured retry");
            ok(
                    order.equals(Arrays.asList("retry1", "fast", "retry2")),
                    "transport order proves delay is outside network lease");
            ok(
                    retryCount.get() == 2 && stages.networkActive() == 0,
                    "exact configured retry budget and no leaked request lease");
            TranslationStages parallel = new TranslationStages(3, 64, () -> 64);
            List<Future<byte[]>> pending = new ArrayList<>();
            for (int i = 0; i < 11; i++)
                pending.add(
                        workers.submit(
                                () ->
                                        parallel.withRequests(
                                                () -> http(address + "/overlap", options),
                                                RUNNING,
                                                () -> 2)));
            for (Future<byte[]> request : pending) request.get(5, TimeUnit.SECONDS);
            ok(
                    peak.get() == 3,
                    "actual HTTP overlap equals target and never exceeds it with 11 page jobs");
            ok(parallel.networkActive() == 0, "all actual HTTP permits released");
            try {
                stages.withRequests(
                        () -> {
                            throw new Exception("planned page failure");
                        },
                        RUNNING,
                        () -> 0);
            } catch (Exception expected) {
                ok(expected.getMessage().contains("planned"), "page failure propagated");
            }
            ok(
                    TranslationStages.requestAttempt(() -> stages.networkActive(), RUNNING) == 0,
                    "thread-local gate removed after a page exception");
            AtomicLong clock = new AtomicLong();
            TranslationStages throttled = new TranslationStages(2, 64, () -> 64, clock::get);
            try {
                throttled.withRequests(
                        () ->
                                TranslationStages.requestAttempt(
                                        () -> {
                                            throw new ApiClient.RequestFailure(
                                                    "HTTP 503", true, 0, 503);
                                        },
                                        RUNNING),
                        RUNNING,
                        () -> 2);
            } catch (Exception expected) {
                ok(throttled.networkActive() == 0, "throttle failure releases active HTTP permit");
            }
            AtomicBoolean cancelled = new AtomicBoolean();
            Future<Boolean> cooldown =
                    workers.submit(
                            () -> {
                                try {
                                    throttled.withRequests(
                                            () ->
                                                    TranslationStages.requestAttempt(
                                                            () -> 1, RUNNING),
                                            cancelled::get,
                                            () -> 0);
                                    return false;
                                } catch (CancellationException expected) {
                                    return true;
                                }
                            });
            until(() -> waiting(throttled, "network") == 1);
            cancelled.set(true);
            ok(
                    cooldown.get(2, TimeUnit.SECONDS),
                    "page cancellation interrupts shared network cooldown");
            clock.set(30000);
            ok(
                    throttled.withRequests(
                                    () -> TranslationStages.requestAttempt(() -> 7, RUNNING),
                                    RUNNING,
                                    () -> 0)
                            == 7,
                    "shared 503 cooldown resumes normally");
            cooldown(workers, address, 429, 1, 1, 0, 1000);
            cooldown(workers, address, 429, 1, 120, 0, 120000);
            cooldown(workers, address, 429, 1, 1, 45, 45000);
            cooldown(workers, address, 503, 7, 120, 0, 7000);
            cooldown(workers, address, 503, 1, 1, 90, 90000);
            overlappingCooldown(workers);
            String result =
                    "{\"checksPassed\":"
                            + checks
                            + ",\"peakActualHttpRequests\":"
                            + peak.get()
                            + ",\"retryOrder\":\"retry1,fast,retry2\",\"paidApiUsed\":false,\"androidRuntimeVerified\":false}";
            if (args.length > 0) {
                Path output = Paths.get(args[0]);
                Files.createDirectories(output);
                Files.writeString(output.resolve("scheduler_result.json"), result);
            }
            System.out.println("V073SchedulerChecks: " + checks + " checks passed");
            System.out.println(result);
        } finally {
            server.stop(0);
            workers.shutdownNow();
            handlers.shutdownNow();
        }
    }
}
