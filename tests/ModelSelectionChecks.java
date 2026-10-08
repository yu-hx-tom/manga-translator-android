package cn.local.manga;

import com.sun.net.httpserver.HttpServer;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Real production /models parsing and chat payloads against local fake HTTP, never an upstream
 * model.
 */
public final class ModelSelectionChecks {
    private static int checks;

    private static void ok(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger gets = new AtomicInteger(),
                posts = new AtomicInteger(),
                code = new AtomicInteger(200);
        AtomicBoolean delayed = new AtomicBoolean();
        AtomicReference<String> response =
                new AtomicReference<>(
                        "{\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-a\"},{\"id\":\"model-b\"}]}");
        AtomicReference<String> authorization = new AtomicReference<>(),
                posted = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext(
                "/v1/models",
                exchange -> {
                    try {
                        gets.incrementAndGet();
                        if (!exchange.getRequestMethod().equals("GET"))
                            throw new AssertionError("Models must be GET");
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        if (delayed.get())
                            try {
                                Thread.sleep(700);
                            } catch (InterruptedException ignored) {
                            }
                        byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(code.get(), bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (IOException ignored) {
                    } finally {
                        exchange.close();
                    }
                });
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try {
                        posts.incrementAndGet();
                        if (!exchange.getRequestMethod().equals("POST"))
                            throw new AssertionError("Chat must be POST");
                        posted.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        byte[] bytes =
                                "{\"choices\":[{\"message\":{\"content\":\"{\\\"translations\\\":[]}\"}}]}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        AppSettings settings = new AppSettings();
        settings.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        settings.apiKey = "local-only-model-test-key";
        settings.textModel = "";
        settings.imageModel = "";
        settings.maxRetries = 2;
        settings.retryIntervalSeconds = 1;
        File prepared = File.createTempFile("model-selection-", ".json");
        try {
            List<String> models = ApiClient.listModels(settings, () -> false);
            ok(
                    models.equals(Arrays.asList("model-a", "model-b")),
                    "duplicate IDs removed in stable order");
            ok(
                    gets.get() == 1 && posts.get() == 0,
                    "listing makes GET only, no billed translation POST");
            ok(
                    settings.textModel.isEmpty() && settings.imageModel.isEmpty(),
                    "loading neither requires nor changes existing model fields");
            ok(
                    authorization.get().equals("Bearer " + settings.apiKey),
                    "GET uses current endpoint credential");
            settings.textModel = models.get(1);
            JSONArray content =
                    new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", "fixture"));
            ApiClient.chat(settings, content, () -> false);
            JSONObject body = new JSONObject(posted.get());
            ok(
                    body.getString("model").equals("model-b"),
                    "selected model used by regular chat entry");
            ok(
                    body.getJSONArray("messages")
                            .getJSONObject(0)
                            .getJSONArray("content")
                            .getJSONObject(0)
                            .getString("text")
                            .equals("fixture"),
                    "regular chat content preserved");
            settings.textModel = models.get(0);
            Files.writeString(prepared.toPath(), content.toString(), StandardCharsets.UTF_8);
            ApiClient.chatPrepared(settings, prepared, () -> false);
            ok(
                    new JSONObject(posted.get()).getString("model").equals("model-a"),
                    "selected model used by streamed browser/single-page engine entry");
            ok(posts.get() == 2, "only explicitly invoked mock chat actions POST");
            int oldGets = gets.get();
            for (String invalid :
                    new String[] {
                        "not-json",
                        "{}",
                        "{\"data\":[]}",
                        "{\"data\":[{\"id\":5}]}",
                        "{\"data\":[{\"id\":\"\"}]}",
                        "{\"data\":[{\"id\":\"bad\\nname\"}]}"
                    }) {
                response.set(invalid);
                int before = gets.get();
                try {
                    ApiClient.listModels(settings, () -> false);
                    throw new AssertionError("invalid model list");
                } catch (Exception expected) {
                    ok(
                            !expected.getMessage().contains(settings.apiKey),
                            "malformed-list error safe");
                }
                ok(
                        gets.get() == before + 1 && settings.textModel.equals("model-a"),
                        "malformed/empty list not retried and current selection retained");
            }
            response.set("{\"data\":[{\"id\":\"" + settings.apiKey + "\"}]}");
            try {
                ApiClient.listModels(settings, () -> false);
                throw new AssertionError("key echoed as model");
            } catch (Exception expected) {
                ok(
                        !expected.getMessage().contains(settings.apiKey)
                                && settings.textModel.equals("model-a"),
                        "server cannot reflect key through chooser model ID");
            }
            code.set(401);
            response.set("upstream echoes " + settings.apiKey);
            int before = gets.get();
            try {
                ApiClient.listModels(settings, () -> false);
                throw new AssertionError("401");
            } catch (Exception expected) {
                ok(
                        expected.getMessage().contains("HTTP 401")
                                && !expected.getMessage().contains(settings.apiKey),
                        "unauthorized list has clear safe error");
            }
            ok(
                    gets.get() == before + 1 && settings.textModel.equals("model-a"),
                    "401 not retried and selection retained");
            code.set(200);
            response.set("{\"data\":[{\"id\":\"model-c\"}]}");
            delayed.set(true);
            AtomicBoolean cancel = new AtomicBoolean();
            new Thread(
                            () -> {
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException ignored) {
                                }
                                cancel.set(true);
                            })
                    .start();
            before = gets.get();
            try {
                ApiClient.listModels(settings, cancel::get);
                throw new AssertionError("cancel list");
            } catch (CancellationException expected) {
                ok(
                        gets.get() == before + 1 && settings.textModel.equals("model-a"),
                        "canceled load cannot change selected model");
            }
            ok(posts.get() == 2, "all failed/canceled list actions remain GET-only");
            System.out.println(
                    "ModelSelectionChecks: "
                            + checks
                            + " checks passed (real local GET/POST with production org.json"
                            + " parsing; no paid API; Android chooser/save UI not executed)");
        } finally {
            server.stop(0);
            handlers.shutdownNow();
            Files.deleteIfExists(prepared.toPath());
        }
    }
}
