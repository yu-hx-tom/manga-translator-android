package cn.local.manga;

import com.sun.net.httpserver.HttpServer;

import org.json.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.imageio.ImageIO;

/**
 * Real production API plus runner persistence under controlled loopback responses; no paid request.
 */
public final class BatchImageCleanupChecks {
    static int checks;

    static void ok(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(16, 24, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 24; y++)
            for (int x = 0; x < 16; x++) image.setRGB(x, y, 0xff040506 + x * 0x00010101);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    static JSONObject row(Path root, byte[] image) throws Exception {
        Files.createDirectories(root);
        Path input = root.resolve("source.png");
        Files.write(input, image);
        return new JSONObject()
                .put("id", "P01_rt_1")
                .put("page", 1)
                .put("regionId", "rt_1")
                .put("inputPng", input.toString())
                .put("inputSha256", LiveImageCleanupProbe.hash(image))
                .put("width", 16)
                .put("height", 24)
                .put("targetBoxes", new JSONArray().put(new JSONArray(new int[] {4, 4, 12, 20})))
                .put("protectedBoxes", new JSONArray().put(new JSONArray(new int[] {0, 0, 2, 24})));
    }

    static Path manifest(Path root, JSONObject row) throws Exception {
        Path path = root.resolve("manifest.json");
        BatchImageCleanupRunner.save(
                path,
                new JSONObject()
                        .put("schemaVersion", 1)
                        .put("maxAttemptsPerRegion", 3)
                        .put("requestTimeoutSeconds", 15)
                        .put("backoffSeconds", new JSONArray().put(0).put(0))
                        .put("regions", new JSONArray().put(row)));
        return path;
    }

    static JSONObject status(Path root) throws Exception {
        return BatchImageCleanupRunner.read(root.resolve("requests/P01_rt_1/request_result.json"));
    }

    static void run(Path root, Path manifest, AppSettings settings) throws Exception {
        BatchImageCleanupRunner.run(
                BatchImageCleanupRunner.validate(root, manifest, settings), settings);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        byte[] image = png();
        AtomicInteger mode = new AtomicInteger(), calls = new AtomicInteger();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext(
                "/v1/images/edits",
                exchange -> {
                    try {
                        exchange.getRequestBody().readAllBytes();
                        int call = calls.incrementAndGet(), state = mode.get();
                        int code =
                                state == 0
                                        ? (call == 1 ? 408 : 200)
                                        : state == 1
                                                ? 408
                                                : state == 2 ? 400 : state == 3 ? 500 : 200;
                        String body =
                                code == 200
                                        ? new JSONObject()
                                                .put(
                                                        "data",
                                                        new JSONArray()
                                                                .put(
                                                                        new JSONObject()
                                                                                .put(
                                                                                        "b64_json",
                                                                                        Base64
                                                                                                .getEncoder()
                                                                                                .encodeToString(
                                                                                                        image))))
                                                .toString()
                                        : state == 3
                                                ? "{\"error\":{\"message\":\"unsupported image"
                                                        + " content policy\"}}"
                                                : "{\"error\":{\"message\":\"controlled network"
                                                        + " error\"}}";
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(code, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Throwable e) {
                        handlerFailure.compareAndSet(null, e);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        AppSettings settings = new AppSettings();
        settings.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        settings.apiKey = "fixture-private-credential";
        settings.imageModel = BatchImageCleanupRunner.MODEL;
        settings.maxRetries = 0;
        settings.requestTimeoutSeconds = 15;
        settings.retryIntervalSeconds = 1;
        settings.rateLimitWaitSeconds = 1;
        try {
            Path recovered = root.resolve("recover");
            Path file = manifest(recovered, row(recovered, image));
            BatchImageCleanupRunner.validate(recovered, file, settings);
            ok(calls.get() == 0, "manifest validation makes no API request");
            run(recovered, file, settings);
            JSONObject result = status(recovered);
            ok(
                    calls.get() == 2
                            && result.getBoolean("success")
                            && result.getInt("actualRequests") == 2,
                    "408 then success executes exactly two requests");
            ok(
                    !result.getBoolean("reused")
                            && Files.isRegularFile(Paths.get(result.getString("returnedPath"))),
                    "live output is durable and correctly marked as requested");
            ok(
                    BatchImageCleanupRunner.read(recovered.resolve("status.json"))
                            .getBoolean("runFinished"),
                    "successful serial batch records terminal run completion");
            run(recovered, file, settings);
            ok(
                    calls.get() == 2 && status(recovered).getBoolean("reused"),
                    "resume after success makes zero new API requests and marks cache use");
            ok(
                    status(recovered).getInt("actualRequests") == 2,
                    "resume retains historical batch request count");
            ok(
                    status(recovered).getBoolean("liveRequestThisBatch")
                            && "live_this_batch".equals(status(recovered).getString("origin")),
                    "resume never relabels this batch's paid request as a historical cached"
                            + " request");
            mode.set(1);
            calls.set(0);
            Path failed = root.resolve("failed");
            file = manifest(failed, row(failed, image));
            run(failed, file, settings);
            ok(
                    calls.get() == 3
                            && !status(failed).getBoolean("success")
                            && "terminal_failed".equals(status(failed).getString("status")),
                    "three transient failures are terminal at the explicit cap");
            run(failed, file, settings);
            ok(calls.get() == 3, "resume does not reset the retry cap for a terminal failure");
            mode.set(2);
            calls.set(0);
            Path refused = root.resolve("refused");
            file = manifest(refused, row(refused, image));
            run(refused, file, settings);
            ok(
                    calls.get() == 1
                            && "terminal_failed".equals(status(refused).getString("status")),
                    "400 content refusal is never blindly retried");
            mode.set(3);
            calls.set(0);
            Path unsupported = root.resolve("unsupported");
            file = manifest(unsupported, row(unsupported, image));
            run(unsupported, file, settings);
            ok(
                    calls.get() == 1,
                    "explicit unsupported/content-policy message prevents retry even with HTTP"
                            + " 500");
            mode.set(4);
            calls.set(0);
            Path unknown = root.resolve("unknown");
            file = manifest(unknown, row(unknown, image));
            BatchImageCleanupRunner.Plan plan =
                    BatchImageCleanupRunner.validate(unknown, file, settings);
            BatchImageCleanupRunner.Spec spec = plan.regions.get(0);
            BatchImageCleanupRunner.save(
                    spec.output.resolve("attempt_01/start.json"),
                    BatchImageCleanupRunner.base(spec)
                            .put("status", "request_started")
                            .put("attempt", 1));
            BatchImageCleanupRunner.run(plan, settings);
            ok(
                    calls.get() == 0
                            && "in_flight_unknown".equals(status(unknown).getString("status")),
                    "unknown started request is never resent merely because its observer"
                            + " restarted");
            Files.write(spec.output.resolve("attempt_01/output.png"), image);
            BatchImageCleanupRunner.run(
                    BatchImageCleanupRunner.validate(unknown, file, settings), settings);
            ok(
                    calls.get() == 0
                            && status(unknown).getBoolean("success")
                            && status(unknown).getBoolean("reused"),
                    "already-written complete image recovers a crashed metadata commit without"
                            + " another API call");
            Path saved = Paths.get(status(recovered).getString("returnedPath"));
            Files.write(saved, new byte[] {1, 2, 3});
            boolean corrupt = false;
            try {
                run(recovered, recovered.resolve("manifest.json"), settings);
            } catch (IOException expected) {
                corrupt = true;
            }
            ok(
                    corrupt && calls.get() == 0,
                    "corrupt saved output is not silently used or replaced with a paid retry");
            Path changed = root.resolve("changed");
            JSONObject changedRow = row(changed, image);
            file = manifest(changed, changedRow);
            Files.write(changed.resolve("source.png"), new byte[] {1, 2, 3});
            boolean rejected = false;
            try {
                BatchImageCleanupRunner.validate(changed, file, settings);
            } catch (Exception expected) {
                rejected = true;
            }
            ok(
                    rejected && calls.get() == 0,
                    "changed source is refused during validation before billing");
            Path prior = root.resolve("historical");
            Files.createDirectories(prior);
            Files.write(prior.resolve("input_roi.png"), image);
            Files.write(prior.resolve("returned_original.png"), image);
            Path reused = root.resolve("reused");
            byte[] reencoded = Arrays.copyOf(image, image.length + 4);
            JSONObject reusedRow = row(reused, reencoded);
            JSONObject historical =
                    new JSONObject()
                            .put("success", true)
                            .put(
                                    "sample",
                                    new JSONObject()
                                            .put(
                                                    "targetBoxes",
                                                    reusedRow.getJSONArray("targetBoxes"))
                                            .put(
                                                    "protectedBoxes",
                                                    reusedRow.getJSONArray("protectedBoxes")))
                            .put("inputWidth", 16)
                            .put("inputHeight", 24)
                            .put("inputSha256", LiveImageCleanupProbe.hash(image))
                            .put("returnedSha256", LiveImageCleanupProbe.hash(image))
                            .put("model", settings.imageModel)
                            .put("promptVersion", ImageCleanup.PROMPT_VERSION)
                            .put("endpoint", settings.baseUrl + "/images/edits");
            BatchImageCleanupRunner.save(prior.resolve("request_result.json"), historical);
            reusedRow.put("reuseRequestResult", prior.resolve("request_result.json").toString());
            file = manifest(reused, reusedRow);
            run(reused, file, settings);
            ok(
                    calls.get() == 0
                            && status(reused).getBoolean("reused")
                            && status(reused).getInt("actualRequests") == 0,
                    "historical matching decoded pixels reuse succeeds despite PNG container hash"
                            + " difference");
            ok(
                    !status(reused).getBoolean("liveRequestThisBatch")
                            && "historical_cache".equals(status(reused).getString("origin")),
                    "historical cache records remain distinguishable from resumed live requests");
            reusedRow.put(
                    "protectedBoxes", new JSONArray().put(new JSONArray(new int[] {0, 0, 3, 24})));
            Path mismatch = root.resolve("mismatch");
            Files.createDirectories(mismatch);
            file = manifest(mismatch, reusedRow);
            rejected = false;
            try {
                BatchImageCleanupRunner.validate(mismatch, file, settings);
            } catch (IOException expected) {
                rejected = true;
            }
            ok(
                    rejected && calls.get() == 0,
                    "changed protection parameters cannot masquerade as a compatible historical"
                            + " request");
            JSONObject summary = BatchImageCleanupRunner.read(unknown.resolve("status.json"));
            ok(
                    summary.getInt("applicationCallsStarted") == 1,
                    "summary counts durable started attempts even for recovered observer"
                            + " interruptions");
            ok(
                    handlerFailure.get() == null,
                    "controlled HTTP fixture completed without handler failures");
            try (var files = Files.walk(root)) {
                for (Path path :
                        files.filter(Files::isRegularFile)
                                .filter(
                                        p ->
                                                p.toString().endsWith(".json")
                                                        || p.toString().endsWith(".jsonl")
                                                        || p.toString().endsWith(".txt"))
                                .toList())
                    ok(
                            !Files.readString(path).contains(settings.apiKey),
                            "credentials never appear in persisted reports, events or prompts");
            }
            System.out.println(
                    "BatchImageCleanupChecks: "
                            + checks
                            + " checks passed (real API/local HTTP, durable resume, terminal"
                            + " retries, historical pixel equivalence; no paid API)");
        } finally {
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
