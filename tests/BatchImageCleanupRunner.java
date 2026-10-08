package cn.local.manga;

import android.graphics.BitmapFactory;

import org.json.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import javax.imageio.ImageIO;

/**
 * Durable live test runner, serial by default. Main uses the configured local gateway; never stores
 * credentials.
 */
public final class BatchImageCleanupRunner {
    static final String MODEL = "gpt-image-2.5";

    static final class Spec {
        JSONObject row;
        String id, inputSha, identity;
        Path input, output;
        int width, height;
        int[][] targets, protect;
        Path reuseImage;
        JSONObject reuseReport;
        String reusePixelSha;
    }

    static final class Plan {
        Path root;
        JSONObject manifest;
        List<Spec> regions = new ArrayList<>();
        int maximum, timeout, concurrency = 1;
        long[] backoff;
    }

    static synchronized JSONObject read(Path path) throws Exception {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("\ufeff")) text = text.substring(1);
        return new JSONObject(text);
    }

    static synchronized void save(Path path, JSONObject value) throws Exception {
        if (value.has("status")) {
            value.put("success", "success".equals(value.optString("status")));
            if (value.has("attemptsStarted"))
                value.put("actualRequests", value.getInt("attemptsStarted"));
            if (value.has("cacheUsed")) value.put("reused", value.getBoolean("cacheUsed"));
            if (value.has("output")) value.put("returnedPath", value.getString("output"));
        }
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, value.toString(2) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(
                    tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sha(Path path) throws Exception {
        return LiveImageCleanupProbe.hash(Files.readAllBytes(path));
    }

    static String sha(String value) throws Exception {
        return LiveImageCleanupProbe.hash(value.getBytes(StandardCharsets.UTF_8));
    }

    static Path resolve(Path root, String value) {
        Path path = Paths.get(value);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    static void check(Plan plan) {
        if (Thread.currentThread().isInterrupted() || Files.exists(plan.root.resolve("CANCEL")))
            throw new CancellationException("Batch cancelled");
    }

    static int[] imageSize(Path path) throws Exception {
        if (!Files.isRegularFile(path)
                || Files.size(path) < 8
                || Files.size(path) > ImageCleanup.MAX_RESULT_BYTES)
            throw new IOException("Image file missing or outside byte limit");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path.toString(), bounds);
        if (!Arrays.asList("image/png", "image/jpeg", "image/webp").contains(bounds.outMimeType))
            throw new IOException("Image metadata is invalid or unsupported by host adapter");
        return new int[] {bounds.outWidth, bounds.outHeight};
    }

    static String pixelSha(Path path) throws Exception {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("Cached input pixel decode failed");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int[] row = new int[image.getWidth()];
        byte[] bytes = new byte[row.length * 4];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, row.length, 1, row, 0, row.length);
            for (int x = 0; x < row.length; x++) {
                int c = row[x];
                for (int b = 0; b < 4; b++) bytes[x * 4 + b] = (byte) (c >>> (24 - b * 8));
            }
            digest.update(bytes);
        }
        image.flush();
        return HexFormat.of().formatHex(digest.digest());
    }

    static Plan validate(Path root, Path manifest, AppSettings settings) throws Exception {
        Plan plan = new Plan();
        plan.root = root.toAbsolutePath().normalize();
        plan.manifest = read(manifest);
        if (plan.manifest.optInt("schemaVersion") != 1)
            throw new IOException("Unsupported cleanup manifest schema");
        plan.maximum = plan.manifest.optInt("maxAttemptsPerRegion", 3);
        plan.timeout = plan.manifest.optInt("requestTimeoutSeconds", 600);
        if (plan.maximum < 1 || plan.maximum > 3 || plan.timeout < 10 || plan.timeout > 600)
            throw new IOException("Manifest request/retry budget is outside authorized bounds");
        JSONArray delays = plan.manifest.optJSONArray("backoffSeconds");
        if (delays == null) delays = new JSONArray().put(30).put(60);
        plan.backoff = new long[Math.max(0, plan.maximum - 1)];
        for (int i = 0; i < plan.backoff.length; i++) {
            long seconds = delays.getLong(i);
            if (seconds < 0 || seconds > 300) throw new IOException("Invalid retry backoff");
            plan.backoff[i] = seconds * 1000;
        }
        JSONArray rows = plan.manifest.getJSONArray("regions");
        if (rows.length() > 2048) throw new IOException("Too many manifest regions");
        Set<String> unique = new HashSet<>();
        for (Object raw : rows) {
            check(plan);
            Spec s = new Spec();
            s.row = (JSONObject) raw;
            s.id = s.row.getString("id");
            if (!s.id.matches("[A-Za-z0-9_-]{1,80}") || !unique.add(s.id.toLowerCase(Locale.ROOT)))
                throw new IOException(
                        "Region id is invalid or duplicated (including case-insensitive filesystem"
                                + " aliases)");
            s.input = resolve(plan.root, s.row.getString("inputPng"));
            s.width = s.row.getInt("width");
            s.height = s.row.getInt("height");
            ImageCleanup.validateInputSize(s.width, s.height);
            int[] size = imageSize(s.input);
            if (size[0] != s.width || size[1] != s.height)
                throw new IOException(s.id + " input dimensions disagree with manifest");
            byte[] signature = new byte[8];
            try (InputStream input = Files.newInputStream(s.input)) {
                if (input.read(signature) != 8 || !ImageCleanup.pngSignature(signature))
                    throw new IOException(s.id + " input is not PNG");
            }
            s.inputSha = sha(s.input);
            if (!s.inputSha.equalsIgnoreCase(s.row.getString("inputSha256")))
                throw new IOException(s.id + " input SHA256 changed");
            for (String key : List.of("targetBoxes", "protectedBoxes"))
                for (Object box : s.row.getJSONArray(key))
                    if (!(box instanceof JSONArray) || ((JSONArray) box).length() != 4)
                        throw new IOException(s.id + " invalid rectangle coordinates");
            s.targets = LiveImageCleanupProbe.boxes(s.row.getJSONArray("targetBoxes"));
            s.protect = LiveImageCleanupProbe.boxes(s.row.getJSONArray("protectedBoxes"));
            String prompt = ImageCleanup.prompt(s.width, s.height, s.targets, s.protect);
            s.identity =
                    sha(
                            new JSONArray()
                                    .put(s.inputSha)
                                    .put(s.width)
                                    .put(s.height)
                                    .put(prompt)
                                    .put(ImageCleanup.PROMPT_VERSION)
                                    .put(settings.baseUrl)
                                    .put(settings.apiKey)
                                    .put(settings.imageModel)
                                    .toString());
            s.output = plan.root.resolve("requests").resolve(s.id);
            if (Files.exists(s.output.resolve("request_result.json"))
                    && !read(s.output.resolve("request_result.json"))
                            .optString("identitySha256")
                            .equals(s.identity))
                throw new IOException(
                        s.id
                                + " manifest differs from existing attempt evidence; use a new"
                                + " batch directory");
            if (s.row.has("reuseRequestResult")) validateReuse(plan, s, settings);
            plan.regions.add(s);
        }
        return plan;
    }

    static void validateReuse(Plan plan, Spec s, AppSettings settings) throws Exception {
        Path reportPath = resolve(plan.root, s.row.getString("reuseRequestResult"));
        JSONObject r = read(reportPath);
        JSONObject geometry = r.getJSONObject("sample");
        if (!r.optBoolean("success")
                || r.optInt("inputWidth") != s.width
                || r.optInt("inputHeight") != s.height
                || !r.optString("model").equals(settings.imageModel)
                || !r.optString("promptVersion").equals(ImageCleanup.PROMPT_VERSION)
                || !r.optString("endpoint").equals(settings.baseUrl + "/images/edits")
                || !geometry.getJSONArray("targetBoxes")
                        .toString()
                        .equals(s.row.getJSONArray("targetBoxes").toString())
                || !geometry.getJSONArray("protectedBoxes")
                        .toString()
                        .equals(s.row.getJSONArray("protectedBoxes").toString()))
            throw new IOException(
                    s.id + " historical request does not match current production parameters");
        if (!r.optString("inputSha256").equalsIgnoreCase(s.inputSha)) {
            Path previous = reportPath.getParent().resolve("input_roi.png");
            int[] old = imageSize(previous);
            if (old[0] != s.width || old[1] != s.height)
                throw new IOException(s.id + " cached input dimensions changed");
            s.reusePixelSha = pixelSha(s.input);
            if (!s.reusePixelSha.equals(pixelSha(previous)))
                throw new IOException(s.id + " cached input pixels changed");
        }
        for (String extension : List.of("png", "jpg", "webp")) {
            Path image = reportPath.getParent().resolve("returned_original." + extension);
            if (Files.isRegularFile(image)) {
                s.reuseImage = image;
                break;
            }
        }
        if (s.reuseImage == null
                || !sha(s.reuseImage).equalsIgnoreCase(r.getString("returnedSha256")))
            throw new IOException(s.id + " cached output SHA256 mismatch");
        int[] size = imageSize(s.reuseImage);
        ImageCleanup.validateReturnedSize(size[0], size[1], s.width, s.height);
        s.reuseReport = r;
    }

    static JSONObject base(Spec s) throws Exception {
        JSONObject value =
                new JSONObject()
                        .put("id", s.id)
                        .put("identitySha256", s.identity)
                        .put("inputSha256", s.inputSha)
                        .put("input", s.output.resolve("input.png").toString())
                        .put("width", s.width)
                        .put("height", s.height)
                        .put("targetBoxes", s.row.getJSONArray("targetBoxes"))
                        .put("protectedBoxes", s.row.getJSONArray("protectedBoxes"))
                        .put("model", MODEL)
                        .put("promptVersion", ImageCleanup.PROMPT_VERSION)
                        .put("updatedAt", Instant.now().toString())
                        .put("nativeAndroidRuntime", false);
        for (String key : List.of("page", "regionId", "sourceCrop", "sourcePage", "sourceSize"))
            if (s.row.has(key)) value.put(key, s.row.get(key));
        return value;
    }

    static synchronized void event(Plan plan, String id, String phase) throws Exception {
        JSONObject value =
                new JSONObject()
                        .put("time", Instant.now().toString())
                        .put("id", id)
                        .put("phase", phase);
        Files.writeString(
                plan.root.resolve("events.jsonl"),
                value.toString() + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        System.out.println(phase + " " + id);
        System.out.flush();
    }

    static synchronized void snapshot(Plan plan, boolean complete) throws Exception {
        JSONArray rows = new JSONArray();
        int success = 0, failed = 0, unknown = 0, attempts = 0, cached = 0;
        for (Spec s : plan.regions) {
            Path path = s.output.resolve("request_result.json");
            JSONObject row = Files.exists(path) ? read(path) : base(s).put("status", "pending");
            rows.put(row);
            if ("success".equals(row.optString("status"))) success++;
            if ("terminal_failed".equals(row.optString("status"))) failed++;
            if ("in_flight_unknown".equals(row.optString("status"))) unknown++;
            if (row.optBoolean("cacheUsed")) cached++;
            for (int n = 1; n <= plan.maximum; n++)
                if (Files.exists(
                        s.output.resolve(String.format(Locale.ROOT, "attempt_%02d/start.json", n))))
                    attempts++;
        }
        save(
                plan.root.resolve("status.json"),
                new JSONObject()
                        .put("updatedAt", Instant.now().toString())
                        .put("runFinished", complete)
                        .put("regions", rows)
                        .put("total", plan.regions.size())
                        .put("succeeded", success)
                        .put("terminalFailed", failed)
                        .put("inFlightUnknown", unknown)
                        .put("cacheUsedRegions", cached)
                        .put("applicationCallsStarted", attempts)
                        .put("maximumAttemptsPerRegion", plan.maximum)
                        .put("concurrency", plan.concurrency)
                        .put("appInternalRetries", 0)
                        .put("actualBillingUnknown", true));
    }

    static Path attemptImage(Path dir) throws IOException {
        for (String extension : List.of("png", "jpg", "webp")) {
            Path path = dir.resolve("output." + extension);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }

    static String extension(byte[] data) {
        return ImageCleanup.pngSignature(data)
                ? "png"
                : (data.length > 2 && (data[0] & 255) == 255 && (data[1] & 255) == 216)
                        ? "jpg"
                        : "webp";
    }

    static void success(
            Plan plan, Spec s, Path image, boolean cacheUsed, String source, int attempts)
            throws Exception {
        int[] size = imageSize(image);
        ImageCleanup.validateReturnedSize(size[0], size[1], s.width, s.height);
        Path target =
                s.output.resolve(
                        "output."
                                + image.getFileName()
                                        .toString()
                                        .substring(
                                                image.getFileName().toString().lastIndexOf('.')
                                                        + 1));
        if (!image.equals(target)) Files.copy(image, target, StandardCopyOption.REPLACE_EXISTING);
        JSONObject result =
                base(s).put("status", "success")
                        .put("cacheUsed", cacheUsed)
                        .put("cacheSource", source)
                        .put("attemptsStarted", attempts)
                        .put("apiRequestSentThisBatch", attempts > 0)
                        .put("origin", attempts > 0 ? "live_this_batch" : "historical_cache")
                        .put("liveRequestThisBatch", attempts > 0)
                        .put("output", target.toString())
                        .put("outputSha256", sha(target))
                        .put("outputWidth", size[0])
                        .put("outputHeight", size[1])
                        .put("protectedCompositeApplied", false);
        if (s.reuseReport != null)
            result.put("reuseRequestResult", s.row.getString("reuseRequestResult"))
                    .put(
                            "historicalInputPixelSha256",
                            s.reusePixelSha == null ? JSONObject.NULL : s.reusePixelSha);
        save(s.output.resolve("request_result.json"), result);
        event(plan, s.id, cacheUsed ? "CACHE_SUCCESS" : "SUCCESS");
    }

    static boolean retryable(Exception failure) {
        if (!(failure instanceof ApiClient.RequestFailure f) || !f.retryable) return false;
        String message = String.valueOf(f.getMessage()).toLowerCase(Locale.ROOT);
        if (message.matches(
                "(?s).*(content.policy|policy.violation|content.filter|safety.violation|unsupported|not"
                    + " supported|不支持|内容拒绝|违反政策).*")) return false;
        return f.status == 0
                || f.status == 408
                || f.status == 429
                || f.status == 500
                || f.status == 502
                || f.status == 503
                || f.status == 504;
    }

    static void waitUntil(Plan plan, long when) throws Exception {
        while (System.currentTimeMillis() < when) {
            check(plan);
            Thread.sleep(Math.min(1000, Math.max(1, when - System.currentTimeMillis())));
        }
        check(plan);
    }

    static void process(Plan plan, Spec s, AppSettings settings) throws Exception {
        check(plan);
        Files.createDirectories(s.output);
        Path state = s.output.resolve("request_result.json");
        if (!Files.exists(s.output.resolve("input.png")))
            Files.copy(s.input, s.output.resolve("input.png"));
        if (!sha(s.output.resolve("input.png")).equalsIgnoreCase(s.inputSha))
            throw new IOException(
                    s.id + " preserved input changed; refusing to upload different pixels");
        Files.writeString(
                s.output.resolve("prompt.txt"),
                ImageCleanup.prompt(s.width, s.height, s.targets, s.protect),
                StandardCharsets.UTF_8);
        int existing = 0;
        for (int n = 1; n <= plan.maximum; n++)
            if (Files.exists(
                    s.output.resolve(String.format(Locale.ROOT, "attempt_%02d/start.json", n))))
                existing++;
        if (Files.exists(state)) {
            JSONObject previous = read(state);
            String status = previous.optString("status");
            if ("success".equals(status)) {
                Path output = Paths.get(previous.getString("output"));
                if (!sha(output).equalsIgnoreCase(previous.getString("outputSha256")))
                    throw new IOException(
                            s.id + " saved output changed; refusing a paid replacement");
                success(plan, s, output, true, "completed_attempt", existing);
                return;
            }
            if ("terminal_failed".equals(status)) {
                event(plan, s.id, "TERMINAL_ALREADY_RECORDED");
                return;
            }
        }
        if (s.reuseImage != null && existing == 0) {
            success(plan, s, s.reuseImage, true, "historical_request", 0);
            return;
        }
        for (int n = 1; n <= plan.maximum; n++) {
            check(plan);
            Path dir = s.output.resolve(String.format(Locale.ROOT, "attempt_%02d", n)),
                    start = dir.resolve("start.json"),
                    done = dir.resolve("result.json");
            JSONObject previous = null;
            if (Files.exists(start)) {
                if (!read(start).optString("identitySha256").equals(s.identity))
                    throw new IOException("Attempt identity differs from manifest");
                if (!Files.exists(done)) {
                    Path recovered = attemptImage(dir);
                    if (recovered != null) {
                        int[] size = imageSize(recovered);
                        ImageCleanup.validateReturnedSize(size[0], size[1], s.width, s.height);
                        save(
                                done,
                                base(s).put("status", "success")
                                        .put("attempt", n)
                                        .put("recoveredLocally", true)
                                        .put("outputSha256", sha(recovered)));
                        success(plan, s, recovered, true, "recovered_complete_image", n);
                        return;
                    }
                    save(
                            state,
                            base(s).put("status", "in_flight_unknown")
                                    .put("attemptsStarted", n)
                                    .put("cacheUsed", false)
                                    .put(
                                            "reason",
                                            "An attempt has start evidence but no terminal"
                                                    + " response; do not resend on an observation"
                                                    + " timeout"));
                    event(plan, s.id, "IN_FLIGHT_UNKNOWN");
                    return;
                }
                previous = read(done);
                if ("success".equals(previous.optString("status"))) {
                    Path output = attemptImage(dir);
                    if (output == null
                            || !sha(output).equalsIgnoreCase(previous.getString("outputSha256")))
                        throw new IOException("Completed attempt image is missing or changed");
                    success(plan, s, output, true, "completed_attempt", n);
                    return;
                }
                if (!previous.optBoolean("retryable")) {
                    save(
                            state,
                            base(s).put(
                                            "status",
                                            previous.optBoolean("completionKnown", true)
                                                    ? "terminal_failed"
                                                    : "in_flight_unknown")
                                    .put("cacheUsed", false)
                                    .put("attemptsStarted", n)
                                    .put("failure", previous.optString("failure")));
                    return;
                }
            } else {
                Files.createDirectories(dir);
                save(
                        start,
                        base(s).put("status", "request_started")
                                .put("attempt", n)
                                .put("startedAt", Instant.now().toString())
                                .put("timeoutSeconds", plan.timeout)
                                .put("appInternalRetries", 0));
                save(
                        state,
                        base(s).put("status", "requesting")
                                .put("cacheUsed", false)
                                .put("attemptsStarted", n));
                event(plan, s.id, "REQUEST_" + n);
                snapshot(plan, false);
                long began = System.currentTimeMillis();
                try {
                    byte[] bytes =
                            ApiClient.cleanImagePrepared(
                                    settings,
                                    s.output.resolve("input.png").toFile(),
                                    s.width,
                                    s.height,
                                    s.targets,
                                    s.protect,
                                    () -> Files.exists(plan.root.resolve("CANCEL")));
                    Path output = dir.resolve("output." + extension(bytes));
                    Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
                    save(
                            done,
                            base(s).put("status", "success")
                                    .put("attempt", n)
                                    .put("elapsedMs", System.currentTimeMillis() - began)
                                    .put("completedAt", Instant.now().toString())
                                    .put("outputSha256", sha(output)));
                    success(plan, s, output, false, "live_request", n);
                    return;
                } catch (CancellationException cancelled) {
                    save(
                            done,
                            base(s).put("status", "cancelled")
                                    .put("completionKnown", false)
                                    .put("retryable", false)
                                    .put("attempt", n)
                                    .put("completedAt", Instant.now().toString())
                                    .put(
                                            "failure",
                                            "Client cancelled; upstream completion is unknown"));
                    save(
                            state,
                            base(s).put("status", "in_flight_unknown")
                                    .put("cacheUsed", false)
                                    .put("attemptsStarted", n)
                                    .put(
                                            "failure",
                                            "Client cancelled; do not silently repeat this"
                                                    + " request"));
                    throw cancelled;
                } catch (Exception failure) {
                    long delay =
                            n < plan.maximum
                                    ? Math.max(
                                            plan.backoff[n - 1],
                                            failure instanceof ApiClient.RequestFailure f
                                                    ? Math.min(300_000, Math.max(0, f.retryAfterMs))
                                                    : 0)
                                    : 0;
                    previous =
                            base(s).put("status", "failed")
                                    .put("completionKnown", true)
                                    .put("retryable", retryable(failure))
                                    .put("attempt", n)
                                    .put("elapsedMs", System.currentTimeMillis() - began)
                                    .put("completedAt", Instant.now().toString())
                                    .put("nextAllowedAtMillis", System.currentTimeMillis() + delay)
                                    .put(
                                            "failure",
                                            failure.toString()
                                                    .replace(settings.apiKey, "[redacted]"));
                    if (failure instanceof ApiClient.RequestFailure f)
                        previous.put("httpStatus", f.status);
                    save(done, previous);
                    event(plan, s.id, "FAILED_" + n);
                }
            }
            if (n >= plan.maximum || !previous.optBoolean("retryable")) {
                save(
                        state,
                        base(s).put("status", "terminal_failed")
                                .put("cacheUsed", false)
                                .put("attemptsStarted", n)
                                .put("failure", previous.optString("failure")));
                event(plan, s.id, "TERMINAL_FAILED");
                return;
            }
            long until =
                    previous.optLong(
                            "nextAllowedAtMillis",
                            System.currentTimeMillis() + plan.backoff[n - 1]);
            save(
                    state,
                    base(s).put("status", "retry_wait")
                            .put("cacheUsed", false)
                            .put("attemptsStarted", n)
                            .put("nextAllowedAtMillis", until));
            event(plan, s.id, "BACKOFF");
            snapshot(plan, false);
            waitUntil(plan, until);
        }
    }

    static int concurrency() throws IOException {
        try {
            int count = Integer.parseInt(System.getProperty("manga.imageQueue.concurrency", "1"));
            if (count >= 1 && count <= 4) return count;
        } catch (NumberFormatException ignored) {
        }
        throw new IOException("Image queue concurrency must be an integer from 1 to 4");
    }

    static void parallel(Plan plan, AppSettings settings) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(plan.concurrency);
        CompletionService<Void> completed = new ExecutorCompletionService<>(pool);
        java.util.concurrent.atomic.AtomicBoolean stopped =
                new java.util.concurrent.atomic.AtomicBoolean();
        try {
            for (Spec s : plan.regions)
                completed.submit(
                        () -> {
                            if (stopped.get()) return null;
                            try {
                                process(plan, s, settings);
                                snapshot(plan, false);
                                return null;
                            } catch (Exception | Error failure) {
                                stopped.set(true);
                                throw failure;
                            }
                        });
            pool.shutdown();
            Throwable firstFailure = null;
            for (int i = 0; i < plan.regions.size(); i++)
                try {
                    completed.take().get();
                } catch (ExecutionException failure) {
                    if (firstFailure == null) firstFailure = failure.getCause();
                }
            // A local failure stops queued work; requests already running finish naturally.
            if (firstFailure instanceof Exception e) throw e;
            if (firstFailure instanceof Error e) throw e;
        } catch (InterruptedException cancelled) {
            pool.shutdownNow();
            throw cancelled;
        } finally {
            // Keep runner.lock until every worker has stopped, including any explicitly cancelled
            // HTTP guard.
            pool.shutdown();
            boolean interrupted = false;
            while (!pool.isTerminated())
                try {
                    pool.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                    pool.shutdownNow();
                }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static void run(Plan plan, AppSettings settings) throws Exception {
        plan.concurrency = concurrency();
        Files.createDirectories(plan.root);
        settings.maxRetries = 0;
        settings.requestTimeoutSeconds = plan.timeout;
        try (FileChannel channel =
                        FileChannel.open(
                                plan.root.resolve("runner.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            if (lock == null)
                throw new IOException(
                        "Another batch runner is active; observe the existing process instead of"
                                + " starting another");
            boolean finished = false;
            try {
                if (plan.concurrency == 1) {
                    for (Spec s : plan.regions) {
                        process(plan, s, settings);
                        snapshot(plan, false);
                    }
                } else parallel(plan, settings);
                finished = true;
            } finally {
                boolean interrupted = Thread.interrupted();
                try {
                    snapshot(plan, finished);
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]),
                manifest = Paths.get(args[1]),
                configPath = Paths.get(args[2]);
        String mode = args[3];
        JSONObject config = read(configPath);
        if (!config.optBoolean("enabled")
                || config.optInt("port") != 50254
                || !MODEL.equals(config.optString("imageGenerationModel", MODEL)))
            throw new IOException(
                    "Configured local gateway differs from authorized endpoint/model");
        AppSettings settings = new AppSettings();
        settings.baseUrl = "http://127.0.0.1:50254/v1";
        settings.apiKey = config.optString("apiKey");
        settings.imageModel = MODEL;
        settings.maxRetries = 0;
        settings.retryIntervalSeconds = 1;
        settings.rateLimitWaitSeconds = 1;
        config = null;
        if (settings.apiKey.isBlank())
            throw new IOException("Local API credential is not configured");
        Plan plan = validate(root, manifest, settings);
        Files.createDirectories(root);
        save(
                root.resolve("manifest_validation.json"),
                new JSONObject()
                        .put("valid", true)
                        .put("regions", plan.regions.size())
                        .put(
                                "historicalReuseEligible",
                                plan.regions.stream().filter(s -> s.reuseImage != null).count())
                        .put("maximumAttemptsPerRegion", plan.maximum)
                        .put("requestTimeoutSeconds", plan.timeout)
                        .put("manifestSha256", sha(manifest))
                        .put("checkedAt", Instant.now().toString())
                        .put("apiCallsInValidation", 0));
        if ("validate".equals(mode)) {
            System.out.println(
                    "VALIDATED " + plan.regions.size() + " regions; no API request made");
            return;
        }
        if (!"run".equals(mode)) throw new IOException("Mode must be validate or run");
        run(plan, settings);
    }
}
