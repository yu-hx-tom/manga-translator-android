package cn.local.manga;

import com.sun.net.httpserver.HttpServer;

import org.json.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real transport and engine salvage, with bounded repairs after token truncation. */
public final class TruncationRetryChecks {
    static int checks;

    static void ok(boolean value, String why) {
        if (!value) throw new AssertionError(why);
        checks++;
    }

    static String partial() throws Exception {
        return "{\"translations\":["
                + PartialRetryChecks.item("a", "保留{甲}与\"引号\"")
                + ",{\"id\":\"b\",\"zh\":\"未完";
    }

    public static void main(String[] args) throws Exception {
        JSONObject recovered = ApiClient.completeTranslations(partial());
        ok(recovered.getJSONArray("translations").length() == 1, "never salvage an unclosed item");
        ok(
                recovered
                        .getJSONArray("translations")
                        .getJSONObject(0)
                        .getString("zh")
                        .equals("保留{甲}与\"引号\""),
                "quoted braces and escaped quotes retained");
        ok(
                ApiClient.completeTranslations("explanation " + partial())
                        .getJSONArray("translations")
                        .isEmpty(),
                "untrusted unanchored JSON fragment rejected");
        String duplicate =
                "{\"translations\":["
                        + PartialRetryChecks.item("a", "甲")
                        + ","
                        + PartialRetryChecks.item("a", "乙")
                        + ",{";
        List<Region> regions = new ArrayList<>();
        for (String id : List.of("a", "b", "c", "d", "e", "f"))
            regions.add(PartialRetryChecks.region(id));
        ok(
                PartialTranslations.read(ApiClient.completeTranslations(duplicate), regions)
                        .values
                        .isEmpty(),
                "salvaged duplicate remains ambiguous");
        AtomicInteger calls = new AtomicInteger(), mode = new AtomicInteger();
        List<JSONObject> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        JSONObject body =
                                new JSONObject(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8));
                        bodies.add(body);
                        int n = calls.incrementAndGet();
                        boolean
                                responses =
                                        exchange.getRequestURI().getPath().endsWith("responses"),
                                cut = n == 1 || mode.get() == 1;
                        JSONArray content =
                                body.getJSONArray(responses ? "input" : "messages")
                                        .getJSONObject(0)
                                        .getJSONArray("content");
                        JSONObject translations =
                                new JSONObject().put("translations", new JSONArray());
                        for (int i = 0; i < content.length(); i++) {
                            String label = content.getJSONObject(i).optString("text");
                            if (label.startsWith("裁切 id="))
                                translations
                                        .getJSONArray("translations")
                                        .put(
                                                PartialRetryChecks.item(
                                                        label.substring("裁切 id=".length()), "补回"));
                        }
                        String text = cut ? partial() : translations.toString();
                        JSONObject envelope = new JSONObject();
                        if (responses)
                            envelope.put("status", cut ? "incomplete" : "completed")
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
                                                                                                            text)))));
                        else
                            envelope.put(
                                    "choices",
                                    new JSONArray()
                                            .put(
                                                    new JSONObject()
                                                            .put(
                                                                    "finish_reason",
                                                                    cut ? "length" : "stop")
                                                            .put(
                                                                    "message",
                                                                    new JSONObject()
                                                                            .put(
                                                                                    "content",
                                                                                    text))));
                        byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        URL.setURLStreamHandlerFactory(
                protocol ->
                        !protocol.equals("https")
                                ? null
                                : new URLStreamHandler() {
                                    protected URLConnection openConnection(URL url)
                                            throws java.io.IOException {
                                        return new URL(
                                                        "http://127.0.0.1:"
                                                                + server.getAddress().getPort()
                                                                + url.getPath())
                                                .openConnection();
                                    }
                                });
        Path root = Paths.get(args[0]);
        Files.createDirectories(root);
        AppSettings s = new AppSettings();
        s.apiKey = "test-key";
        s.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        s.maxRetries = 1;
        s.retryIntervalSeconds = 1;
        try (TranslationEngine engine =
                new TranslationEngine(new android.content.Context(root.resolve("app").toFile()))) {
            try (var j = PartialRetryChecks.job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        calls.get() == 4 && j.values.size() == 6,
                        "one initial batch and three repairs fill only five missing entries");
                ok(
                        j.values.get("a").getString("zh").startsWith("保留"),
                        "complete first translation retained");
                Set<String> retried = new HashSet<>();
                for (int n = 1; n < bodies.size(); n++) {
                    JSONObject b = bodies.get(n);
                    ok(b.getInt("max_tokens") == 16384, "repair increases reasoning/output budget");
                    JSONArray c =
                            b.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
                    int ids = 0;
                    for (int k = 0; k < c.length(); k++) {
                        String label = c.getJSONObject(k).optString("text");
                        if (label.startsWith("裁切 id=")) {
                            String id = label.substring("裁切 id=".length());
                            ok(
                                    !id.equals("a") && retried.add(id),
                                    "success never resent and missing region retried once");
                            ids++;
                        }
                    }
                    ok(ids <= 2, "truncation repair contains at most two crops");
                }
                ok(j.errors.isEmpty(), "successful repair clears current error summary");
            }
            calls.set(0);
            bodies.clear();
            mode.set(1);
            try (var j = PartialRetryChecks.job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        calls.get() == 4 && j.values.size() == 1,
                        "repeated truncation is bounded to one repair wave and retains first"
                                + " success");
            }
            calls.set(0);
            bodies.clear();
            mode.set(0);
            s.maxRetries = 0;
            try (var j = PartialRetryChecks.job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        calls.get() == 1 && j.values.size() == 1,
                        "zero retries still salvages first valid entry without repairing");
            }
            calls.set(0);
            bodies.clear();
            s.maxRetries = 1;
            s.baseUrl = "https://opencode.ai/zen/go/v1";
            s.textModel = "gpt-6-luna";
            try (var j = PartialRetryChecks.job(root, regions, s)) {
                engine.requestTextPage(j, s, () -> false);
                ok(
                        calls.get() == 4 && j.values.size() == 6,
                        "Responses incomplete envelope follows bounded salvage and repair");
                ok(
                        bodies.get(1).getInt("max_output_tokens") == 16384,
                        "Responses repair uses native increased output budget");
            }
        } finally {
            server.stop(0);
        }
        System.out.println(
                "TruncationRetryChecks: "
                        + checks
                        + " checks passed (production parser, transport and engine; controlled"
                        + " localhost only)");
    }
}
