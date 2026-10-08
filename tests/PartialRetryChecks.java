package cn.local.manga;

import android.graphics.Rect;

import com.sun.net.httpserver.HttpServer;

import org.json.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real engine request/cache/reply code against a controlled localhost API, no Android bitmap
 * rendering.
 */
public final class PartialRetryChecks {
    static int checks;

    static void ok(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    static JSONObject item(String id, String zh) throws Exception {
        return new JSONObject().put("id", id).put("zh", zh).put("skip", false);
    }

    static JSONObject response(JSONObject... items) throws Exception {
        return new JSONObject().put("translations", new JSONArray(Arrays.asList(items)));
    }

    static Region region(String id) {
        return new Region(id, new Rect(0, 0, 20, 40), List.of(new Rect(1, 1, 19, 39)), true);
    }

    static JSONArray content(List<Region> regions) throws Exception {
        JSONArray a = new JSONArray().put(new JSONObject().put("type", "text").put("text", "test"));
        for (Region r : regions) {
            a.put(new JSONObject().put("type", "text").put("text", "裁切 id=" + r.id));
            a.put(
                    new JSONObject()
                            .put("type", "image_url")
                            .put(
                                    "image_url",
                                    new JSONObject().put("url", "data:image/png;base64," + r.id)));
        }
        return a;
    }

    static TranslationEngine.PreparedText job(Path root, List<Region> regions, AppSettings s)
            throws Exception {
        Path dir = Files.createTempDirectory(root, "job-");
        TranslationEngine.PreparedText j =
                new TranslationEngine.PreparedText(dir.toFile(), regions, regions);
        j.settings = s;
        for (Region r : regions) j.identities.put(r.id, dir.getFileName() + r.id);
        java.lang.reflect.Method write =
                TranslationEngine.class.getDeclaredMethod(
                        "writeBatch",
                        TranslationEngine.PreparedText.class,
                        JSONArray.class,
                        List.class);
        write.setAccessible(true);
        write.invoke(null, j, content(regions), regions);
        return j;
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Files.createDirectories(root);
        List<Region> regions = List.of(region("a"), region("b"));
        PartialTranslations.Reply reply =
                PartialTranslations.read(response(item("a", "甲")), regions);
        ok(
                reply.values.size() == 1 && reply.failures.containsKey("b"),
                "missing id preserves valid sibling");
        reply =
                PartialTranslations.read(
                        response(item("a", "甲"), item("a", "重复"), item("b", "乙")), regions);
        ok(
                reply.values.size() == 1
                        && reply.values.containsKey("b")
                        && reply.failures.containsKey("a"),
                "duplicate rejects only ambiguous region");
        reply =
                PartialTranslations.read(
                        response(item("a", ""), item("b", "乙"), item("unknown", "外部")), regions);
        ok(
                reply.values.size() == 1 && reply.values.containsKey("b"),
                "invalid and unknown entries cannot replace valid sibling");
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger request = new AtomicInteger(), mode = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try {
                        bodies.add(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        int n = request.incrementAndGet();
                        int status =
                                mode.get() == 2 && n >= 2
                                        ? 429
                                        : mode.get() == 1 && n == 2 ? 400 : 200;
                        JSONObject value =
                                mode.get() == 4
                                        ? (n == 1
                                                ? response(
                                                        item("a", "保留甲"), item("b", "这段译文超过可用空间"))
                                                : response(item("b", "短译")))
                                        : n == 1
                                                ? response(item("a", "保留甲"))
                                                : response(item("b", "补回乙"));
                        String raw =
                                status == 200
                                        ? new JSONObject()
                                                .put(
                                                        "choices",
                                                        new JSONArray()
                                                                .put(
                                                                        new JSONObject()
                                                                                .put(
                                                                                        "message",
                                                                                        new JSONObject()
                                                                                                .put(
                                                                                                        "content",
                                                                                                        value
                                                                                                                .toString()))))
                                                .toString()
                                        : "{}";
                        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        server.createContext(
                "/zen/go/v1/responses",
                exchange -> {
                    try {
                        bodies.add(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        int n = request.incrementAndGet();
                        JSONObject value =
                                n == 1 ? response(item("a", "保留甲")) : response(item("b", "补回乙"));
                        String raw =
                                new JSONObject()
                                        .put("status", "completed")
                                        .put(
                                                "output",
                                                new JSONArray()
                                                        .put(
                                                                new JSONObject()
                                                                        .put("type", "message")
                                                                        .put("role", "assistant")
                                                                        .put(
                                                                                "content",
                                                                                new JSONArray()
                                                                                        .put(
                                                                                                new JSONObject()
                                                                                                        .put(
                                                                                                                "type",
                                                                                                                "output_text")
                                                                                                        .put(
                                                                                                                "text",
                                                                                                                value
                                                                                                                        .toString())))))
                                        .toString();
                        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        exchange.close();
                    }
                });
        // Intercept only this process's HTTPS; route production OpenCode URLs to the loopback
        // fixture.
        URL.setURLStreamHandlerFactory(
                protocol ->
                        !"https".equals(protocol)
                                ? null
                                : new URLStreamHandler() {
                                    protected URLConnection openConnection(URL url)
                                            throws java.io.IOException {
                                        if (!"opencode.ai".equals(url.getHost()))
                                            throw new java.io.IOException(
                                                    "unexpected external host");
                                        return new URL(
                                                        "http://127.0.0.1:"
                                                                + server.getAddress().getPort()
                                                                + url.getPath())
                                                .openConnection();
                                    }
                                });
        AppSettings s = new AppSettings();
        s.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        s.apiKey = "test-private-key-do-not-export";
        s.maxRetries = 1;
        s.retryIntervalSeconds = 1;
        android.content.Context ctx = new android.content.Context(root.resolve("app").toFile());
        try (TranslationEngine engine = new TranslationEngine(ctx)) {
            try (TranslationEngine.PreparedText j = job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        j.values.size() == 2 && request.get() == 2,
                        "partial response repaired in one additional request");
                JSONArray retry =
                        new JSONObject(bodies.get(1))
                                .getJSONArray("messages")
                                .getJSONObject(0)
                                .getJSONArray("content");
                ok(
                        retry.toString().contains("id=b") && !retry.toString().contains("id=a"),
                        "retry sends only missing region and never successful crop");
                ok(
                        j.values.get("a").getString("zh").equals("保留甲"),
                        "successful first translation preserved");
                ok(
                        new java.io.File(
                                        ctx.getCacheDir(),
                                        "translations-v2/" + j.identities.get("a") + ".json")
                                .isFile(),
                        "successful region persisted in the new cache before retry");
                java.lang.reflect.Method cached =
                        TranslationEngine.class.getDeclaredMethod(
                                "cachedText", String.class, boolean.class);
                cached.setAccessible(true);
                JSONObject reused =
                        (JSONObject) cached.invoke(engine, j.identities.get("a"), false);
                ok(
                        reused != null && reused.getString("zh").equals("保留甲"),
                        "missing-only queue retry reuses successful cached translation");
                ok(
                        cached.invoke(engine, j.identities.get("a"), true) == null,
                        "fresh queue retry bypasses cached translation");
                ok(
                        cached.invoke(engine, j.identities.get("a"), false) != null,
                        "fresh retry does not delete old successful cache while requesting");
                ok(
                        j.errors.isEmpty(),
                        "recovered missing entry does not leave a current failure summary");
            }
            request.set(0);
            bodies.clear();
            mode.set(1);
            try (TranslationEngine.PreparedText j = job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        request.get() == 2 && j.values.size() == 1 && j.values.containsKey("a"),
                        "failed repair retains first response success");
            }
            request.set(0);
            bodies.clear();
            mode.set(2);
            s.rateLimitWaitSeconds = 1;
            try (TranslationEngine.PreparedText j = job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        j.throttleFailure != null
                                && j.values.containsKey("a")
                                && request.get() == 3,
                        "throttled repair preserves success and exposes scheduler cooldown signal");
            }
            request.set(0);
            bodies.clear();
            mode.set(0);
            s.maxRetries = 0;
            try (TranslationEngine.PreparedText j = job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        request.get() == 1 && j.values.size() == 1,
                        "zero retries disables automatic semantic repair");
            }
            request.set(0);
            s.maxRetries = 1;
            try (TranslationEngine.PreparedText j = job(root, regions, s)) {
                boolean cancelled = false;
                try {
                    engine.requestTextPage(j, s, () -> request.get() >= 1);
                } catch (java.util.concurrent.CancellationException e) {
                    cancelled = true;
                }
                ok(cancelled && request.get() == 1, "cancel prevents repair request");
            }
            request.set(0);
            bodies.clear();
            mode.set(4);
            s.maxRetries = 1;
            try (TranslationEngine.PreparedText j = job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        request.get() == 1
                                && j.values.size() == 2
                                && j.values.get("b").getString("zh").equals("这段译文超过可用空间"),
                        "valid full translation is retained without a layout-capacity repair");
                ok(
                        j.values.get("a").getString("zh").equals("保留甲"),
                        "full reply retains successful sibling");
                ok(j.errors.isEmpty(), "full reply leaves no spurious capacity failure");
            }
            request.set(0);
            bodies.clear();
            s.maxRetries = 0;
            try (TranslationEngine.PreparedText j = job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        request.get() == 1 && j.values.size() == 2,
                        "zero retry also retains full valid text");
            }
            ok(
                    !TranslationEngine.cropInstruction(region("x")).contains("最多容纳"),
                    "production crop prompt no longer requests layout-dependent shortening");
            ok(
                    Arrays.equals(ApiClient.textInputSize(20, 50), new int[] {80, 200}),
                    "tiny vision crop enlarged fourfold");
            ok(
                    Arrays.equals(ApiClient.textInputSize(800, 400), new int[] {1536, 768}),
                    "vision crop enlargement is bounded and preserves aspect ratio");
            s.textModel = "deepseek-v4.1-flash";
            ok(
                    !ApiClient.textImageInput("data:image/png;base64,x", s).has("detail"),
                    "third-party image payload omits vendor-specific detail hint");
            s.textModel = "gpt-6-luna";
            ok(
                    ApiClient.textImageInput("data:image/png;base64,x", s)
                            .getString("detail")
                            .equals("high"),
                    "GPT vision input requests high detail");
            s.maxRetries = 1;
            request.set(0);
            bodies.clear();
            mode.set(0);
            s.baseUrl = "https://opencode.ai/zen/go/v1";
            s.textModel = "gpt-6-luna";
            try (TranslationEngine.PreparedText j = job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        j.values.size() == 2 && request.get() == 2,
                        "real engine Responses batch repaired in one additional request");
                JSONArray first =
                        new JSONObject(bodies.get(0))
                                .getJSONArray("input")
                                .getJSONObject(0)
                                .getJSONArray("content");
                ok(
                        new JSONObject(bodies.get(0)).getInt("max_output_tokens") == 10000,
                        "Responses initial request budgets for original plus translation");
                ok(
                        first.getJSONObject(0).getString("type").equals("input_text")
                                && first.getJSONObject(2).getString("type").equals("input_image"),
                        "engine disk preparation uses Responses image schema");
                JSONArray repair =
                        new JSONObject(bodies.get(1))
                                .getJSONArray("input")
                                .getJSONObject(0)
                                .getJSONArray("content");
                ok(
                        repair.toString().contains("id=b") && !repair.toString().contains("id=a"),
                        "Responses repair sends only failed crop");
                boolean nativeTypes = true;
                for (int i = 0; i < repair.length(); i++)
                    nativeTypes &= repair.getJSONObject(i).getString("type").startsWith("input_");
                ok(nativeTypes, "repair instruction remains native Responses type");
                ok(
                        j.values.get("a").getString("zh").equals("保留甲") && j.errors.isEmpty(),
                        "Responses retry retains first success and clears failure");
            }
        } finally {
            server.stop(0);
        }
        TranslationLog log = new TranslationLog(root.resolve("logs").toFile());
        log.record(
                "task",
                "b",
                "request",
                "HTTP 429 key="
                        + s.apiKey
                        + " https://example.test/path?key=private Bearer abcdef sk-abcdef",
                s);
        String saved = log.read();
        ok(
                saved.contains("HTTP 429")
                        && !saved.contains(s.apiKey)
                        && !saved.contains("example.test")
                        && !saved.contains("abcdef"),
                "logs retain failure reason but redact keys and URLs");
        java.io.ByteArrayOutputStream exported = new java.io.ByteArrayOutputStream();
        log.export(exported);
        ok(
                exported.toString(StandardCharsets.UTF_8).equals(saved),
                "export matches retained diagnostics exactly");
        for (int i = 0; i < 900; i++) log.record("task", "region", "failure", "x".repeat(1600), s);
        long bytes = 0;
        try (var files = Files.list(root.resolve("logs"))) {
            for (Path f : files.toList()) bytes += Files.size(f);
        }
        ok(bytes <= 1024 * 1024, "log rotation bounds persistent storage");
        for (String line : log.read().split("\n")) if (!line.isEmpty()) new JSONObject(line);
        ok(!log.read().isEmpty(), "rotated logs remain valid JSONL");
        System.out.println(
                "PartialRetryChecks: "
                        + checks
                        + " checks passed (real localhost HTTP and engine cache; no Android"
                        + " rendering)");
    }
}
