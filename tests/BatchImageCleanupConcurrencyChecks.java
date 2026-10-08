package cn.local.manga;

import com.sun.net.httpserver.HttpServer;

import org.json.*;

import java.net.InetSocketAddress;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Four real production requests against loopback only; checks overlap, lock and zero-call resume.
 */
public final class BatchImageCleanupConcurrencyChecks {
    static int checks;

    static void ok(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        String previous = System.getProperty("manga.imageQueue.concurrency");
        byte[] image = BatchImageCleanupChecks.png();
        AtomicInteger calls = new AtomicInteger(),
                active = new AtomicInteger(),
                peak = new AtomicInteger();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        CountDownLatch arrived = new CountDownLatch(4), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool(),
                caller = Executors.newSingleThreadExecutor();
        server.setExecutor(handlers);
        server.createContext(
                "/v1/images/edits",
                exchange -> {
                    int running = active.incrementAndGet();
                    peak.accumulateAndGet(running, Math::max);
                    try {
                        exchange.getRequestBody().readAllBytes();
                        calls.incrementAndGet();
                        arrived.countDown();
                        if (!release.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("Parallel request gate timed out");
                        byte[] bytes =
                                new JSONObject()
                                        .put(
                                                "data",
                                                new JSONArray()
                                                        .put(
                                                                new JSONObject()
                                                                        .put(
                                                                                "b64_json",
                                                                                Base64.getEncoder()
                                                                                        .encodeToString(
                                                                                                image))))
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Throwable error) {
                        handlerFailure.compareAndSet(null, error);
                    } finally {
                        active.decrementAndGet();
                        exchange.close();
                    }
                });
        server.start();
        AppSettings settings = new AppSettings();
        settings.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        settings.apiKey = "parallel-fixture-private-credential";
        settings.imageModel = BatchImageCleanupRunner.MODEL;
        settings.maxRetries = 0;
        settings.requestTimeoutSeconds = 15;
        settings.retryIntervalSeconds = 1;
        settings.rateLimitWaitSeconds = 1;
        try {
            System.clearProperty("manga.imageQueue.concurrency");
            ok(BatchImageCleanupRunner.concurrency() == 1, "default remains serial");
            for (String invalid : List.of("0", "5", "invalid")) {
                System.setProperty("manga.imageQueue.concurrency", invalid);
                boolean rejected = false;
                try {
                    BatchImageCleanupRunner.concurrency();
                } catch (java.io.IOException expected) {
                    rejected = true;
                }
                ok(rejected, "invalid concurrency is rejected before requests");
            }
            System.setProperty("manga.imageQueue.concurrency", "4");
            Path aliases = root.resolve("case-alias");
            JSONObject upper = BatchImageCleanupChecks.row(aliases, image).put("id", "P01_rt_1"),
                    lower = new JSONObject(upper.toString()).put("id", "p01_rt_1");
            Path aliasManifest = aliases.resolve("manifest.json");
            BatchImageCleanupRunner.save(
                    aliasManifest,
                    new JSONObject()
                            .put("schemaVersion", 1)
                            .put("regions", new JSONArray().put(upper).put(lower)));
            boolean aliasRejected = false;
            try {
                BatchImageCleanupRunner.validate(aliases, aliasManifest, settings);
            } catch (java.io.IOException expected) {
                aliasRejected = true;
            }
            ok(
                    aliasRejected && calls.get() == 0,
                    "case-insensitive Windows directory aliases are refused before any request");
            JSONArray rows = new JSONArray();
            for (int i = 1; i <= 4; i++)
                rows.put(
                        BatchImageCleanupChecks.row(root.resolve("inputs/" + i), image)
                                .put("id", "P01_rt_" + i)
                                .put("regionId", "rt_" + i));
            Path manifest = root.resolve("manifest.json");
            BatchImageCleanupRunner.save(
                    manifest,
                    new JSONObject()
                            .put("schemaVersion", 1)
                            .put("maxAttemptsPerRegion", 3)
                            .put("requestTimeoutSeconds", 15)
                            .put("backoffSeconds", new JSONArray().put(0).put(0))
                            .put("regions", rows));
            BatchImageCleanupRunner.Plan plan =
                    BatchImageCleanupRunner.validate(root, manifest, settings);
            Future<?> first =
                    caller.submit(
                            () -> {
                                try {
                                    BatchImageCleanupRunner.run(plan, settings);
                                } catch (Exception error) {
                                    throw new CompletionException(error);
                                }
                            });
            try {
                ok(
                        arrived.await(8, TimeUnit.SECONDS),
                        "all four regions reach the loopback server concurrently");
                ok(
                        calls.get() == 4 && active.get() == 4 && peak.get() == 4,
                        "exactly four requests overlap within the cap");
                boolean locked = false;
                try {
                    BatchImageCleanupRunner.run(plan, settings);
                } catch (OverlappingFileLockException | java.io.IOException expected) {
                    locked = true;
                }
                ok(
                        locked && calls.get() == 4,
                        "one runner.lock excludes a second runner while workers are active");
                JSONObject running = BatchImageCleanupRunner.read(root.resolve("status.json"));
                ok(
                        !running.getBoolean("runFinished") && running.getInt("concurrency") == 4,
                        "in-progress snapshot remains readable and incomplete");
            } finally {
                release.countDown();
            }
            first.get(20, TimeUnit.SECONDS);
            JSONObject complete = BatchImageCleanupRunner.read(root.resolve("status.json"));
            ok(
                    complete.getBoolean("runFinished")
                            && complete.getInt("succeeded") == 4
                            && complete.getInt("applicationCallsStarted") == 4,
                    "terminal snapshot includes all four successful regions exactly once");
            for (BatchImageCleanupRunner.Spec spec : plan.regions) {
                JSONObject result =
                        BatchImageCleanupRunner.read(spec.output.resolve("request_result.json"));
                ok(
                        result.getBoolean("success") && result.getInt("actualRequests") == 1,
                        "one successful request per distinct region");
                ok(
                        result.getBoolean("liveRequestThisBatch")
                                && !result.getBoolean("cacheUsed"),
                        "first completion retains live provenance");
                ok(
                        Files.isRegularFile(spec.output.resolve("attempt_01/start.json"))
                                && !Files.exists(spec.output.resolve("attempt_02")),
                        "no extra attempt was created");
            }
            BatchImageCleanupRunner.run(
                    BatchImageCleanupRunner.validate(root, manifest, settings), settings);
            ok(calls.get() == 4, "parallel resume makes zero new HTTP requests");
            complete = BatchImageCleanupRunner.read(root.resolve("status.json"));
            ok(
                    complete.getInt("cacheUsedRegions") == 4
                            && complete.getInt("applicationCallsStarted") == 4,
                    "resume preserves the original request count");
            for (BatchImageCleanupRunner.Spec spec : plan.regions) {
                JSONObject result =
                        BatchImageCleanupRunner.read(spec.output.resolve("request_result.json"));
                ok(
                        result.getBoolean("cacheUsed")
                                && result.getBoolean("liveRequestThisBatch")
                                && result.getInt("actualRequests") == 1,
                        "resumed result does not relabel this run as historical traffic");
            }
            List<String> events = Files.readAllLines(root.resolve("events.jsonl"));
            ok(
                    events.size() == 12,
                    "four starts, four successes and four resumed successes persist without lost"
                            + " append");
            Set<String> ids = new HashSet<>();
            for (Object value : rows) ids.add(((JSONObject) value).getString("id"));
            for (String line : events)
                ok(
                        ids.contains(new JSONObject(line).getString("id")),
                        "each appended event is intact JSON with a known region");
            try (var files = Files.walk(root)) {
                ok(
                        files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                        "no shared status temporary-file race remains");
            }
            Path unknown = root.resolve("unknown");
            Path unknownManifest =
                    BatchImageCleanupChecks.manifest(
                            unknown, BatchImageCleanupChecks.row(unknown, image));
            BatchImageCleanupRunner.Plan pending =
                    BatchImageCleanupRunner.validate(unknown, unknownManifest, settings);
            BatchImageCleanupRunner.Spec spec = pending.regions.get(0);
            BatchImageCleanupRunner.save(
                    spec.output.resolve("attempt_01/start.json"),
                    BatchImageCleanupRunner.base(spec)
                            .put("status", "request_started")
                            .put("attempt", 1));
            BatchImageCleanupRunner.run(pending, settings);
            ok(
                    calls.get() == 4
                            && "in_flight_unknown"
                                    .equals(
                                            BatchImageCleanupChecks.status(unknown)
                                                    .getString("status")),
                    "parallel mode never resends an unknown previous request");
            ok(
                    handlerFailure.get() == null && active.get() == 0,
                    "all controlled requests completed naturally with no handler error");
            BatchImageCleanupRunner.save(
                    root.resolve("并发自检结果.json"),
                    new JSONObject()
                            .put("passed", true)
                            .put("checks", checks)
                            .put("maximumConcurrentRequests", peak.get())
                            .put("actualLoopbackCalls", calls.get())
                            .put("resumeNewCalls", 0)
                            .put("paidApiCalls", 0));
            System.out.println(
                    "BatchImageCleanupConcurrencyChecks: "
                            + checks
                            + " checks passed (4 overlapping loopback regions, exactly once, lock,"
                            + " zero-call resume; no paid API)");
        } finally {
            release.countDown();
            caller.shutdownNow();
            server.stop(0);
            handlers.shutdownNow();
            if (previous == null) System.clearProperty("manga.imageQueue.concurrency");
            else System.setProperty("manga.imageQueue.concurrency", previous);
        }
    }
}
