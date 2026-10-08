package cn.local.manga;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Runs the production HTTP client with intercepted HTTPS; no credential or paid server is used. */
public final class OpenCodeProtocolChecks {
    static int checks;
    static final List<Connection> sent = new ArrayList<>();
    static String reply;
    static int status = 200;

    static void ok(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    static class Connection extends HttpURLConnection {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        Connection(URL url) {
            super(url);
        }

        public void connect() {}

        public boolean usingProxy() {
            return false;
        }

        public void disconnect() {}

        public OutputStream getOutputStream() {
            return body;
        }

        public int getResponseCode() {
            return status;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8));
        }

        public InputStream getErrorStream() {
            return getInputStream();
        }

        public int getContentLength() {
            return reply.getBytes(StandardCharsets.UTF_8).length;
        }

        JSONObject payload() throws Exception {
            return new JSONObject(body.toString(StandardCharsets.UTF_8.name()));
        }
    }

    static String envelope(String translations, String state) throws Exception {
        return new JSONObject()
                .put("status", state)
                .put(
                        "usage",
                        new JSONObject()
                                .put("input_tokens", 12)
                                .put("output_tokens", 34)
                                .put("total_tokens", 46))
                .put(
                        "output",
                        new JSONArray()
                                .put(
                                        new JSONObject()
                                                .put("type", "reasoning")
                                                .put(
                                                        "content",
                                                        new JSONArray()
                                                                .put(
                                                                        new JSONObject()
                                                                                .put(
                                                                                        "text",
                                                                                        "ignore"
                                                                                            + " internal"
                                                                                            + " reasoning"))))
                                .put(
                                        new JSONObject()
                                                .put("type", "message")
                                                .put("role", "assistant")
                                                .put("status", "completed")
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
                                                                                        translations)))))
                .toString();
    }

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(
                protocol ->
                        "https".equals(protocol)
                                ? new URLStreamHandler() {
                                    protected URLConnection openConnection(URL url) {
                                        Connection c = new Connection(url);
                                        sent.add(c);
                                        return c;
                                    }
                                }
                                : null);
        AppSettings s = new AppSettings();
        s.baseUrl = "https://opencode.ai/zen/go/v1";
        s.apiKey = "test-secret-never-export";
        s.textModel = "gpt-6-luna";
        s.maxRetries = 0;
        JSONArray content =
                new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "翻译 id=a"))
                        .put(
                                new JSONObject()
                                        .put("type", "image_url")
                                        .put(
                                                "image_url",
                                                new JSONObject()
                                                        .put("url", "data:image/png;base64,AA==")
                                                        .put("detail", "high")));
        reply =
                envelope(
                        "{\"translations\":[{\"id\":\"a\",\"zh\":\"中文\",\"skip\":false}]}",
                        "completed");
        JSONObject result = ApiClient.chat(s, content, () -> false);
        Connection c = sent.get(0);
        JSONObject p = c.payload();
        ok(c.getURL().getPath().equals("/zen/go/v1/responses"), "GPT endpoint is Responses");
        ok(
                p.has("input")
                        && !p.has("messages")
                        && !p.has("max_tokens")
                        && !p.has("reasoning_effort"),
                "no Chat Completions fields in Responses");
        ok(
                p.getInt("max_output_tokens") == 5000
                        && p.getJSONObject("reasoning").getString("effort").equals("low")
                        && !p.getBoolean("store"),
                "Responses options translated");
        JSONArray input = p.getJSONArray("input").getJSONObject(0).getJSONArray("content");
        ok(
                input.getJSONObject(0).getString("type").equals("input_text")
                        && input.getJSONObject(1).getString("type").equals("input_image"),
                "text and image part conversion");
        ok(
                input.getJSONObject(1).getString("image_url").equals("data:image/png;base64,AA==")
                        && input.getJSONObject(1).getString("detail").equals("high"),
                "data URL and detail retained");
        ok(
                result.getJSONArray("translations").getJSONObject(0).getString("zh").equals("中文"),
                "assistant output parsed and reasoning ignored");
        ok(
                ApiClient.lastDiagnostic().contains("输入 12 / 输出 34")
                        && ApiClient.lastDiagnostic().contains("/responses"),
                "Responses token and protocol diagnostic");
        ok(
                "MangaTranslator/0.7.3".equals(c.getRequestProperty("User-Agent")),
                "honest app identity");
        ok(
                s.apiSessionId.equals(c.getRequestProperty("x-opencode-session"))
                        && ("Bearer " + s.apiKey).equals(c.getRequestProperty("Authorization")),
                "session and credential headers");
        Path prepared = Files.createTempFile("manga-responses-", ".json");
        try {
            Files.writeString(
                    prepared,
                    ApiClient.protocolContent(s, content).toString(),
                    StandardCharsets.UTF_8);
            ApiClient.chatPrepared(s, prepared.toFile(), () -> false);
            Connection second = sent.get(1);
            ok(
                    second.payload().toString().equals(p.toString()),
                    "prepared streaming and direct payload match");
            ok(
                    second.getRequestProperty("x-opencode-session")
                            .equals(c.getRequestProperty("x-opencode-session")),
                    "session stays stable across related requests");
            s.serviceTier = "priority";
            s.reasoningEffort = "omit";
            ApiClient.chatPrepared(s, prepared.toFile(), () -> false);
            p = sent.get(2).payload();
            ok(
                    p.getString("service_tier").equals("priority") && !p.has("reasoning"),
                    "Fast respected and omitted reasoning not sent");
        } finally {
            Files.deleteIfExists(prepared);
        }
        s.serviceTier = "auto";
        s.reasoningEffort = "low";
        reply = envelope("{}", "incomplete");
        try {
            ApiClient.chat(s, content, () -> false);
            throw new AssertionError("incomplete accepted");
        } catch (Exception expected) {
            ok(expected.getMessage().contains("未完成"), "incomplete response rejected");
        }
        reply =
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]}";
        try {
            ApiClient.chat(s, content, () -> false);
            throw new AssertionError("refusal accepted");
        } catch (Exception expected) {
            ok(expected.getMessage().contains("拒绝"), "refusal rejected");
        }
        reply = envelope("not json", "completed");
        try {
            ApiClient.chat(s, content, () -> false);
            throw new AssertionError("invalid JSON accepted");
        } catch (Exception expected) {
            ok(expected.getMessage().contains("JSON"), "invalid model text rejected");
        }
        status = 400;
        reply =
                "{\"error\":{\"code\":\"invalid_request\",\"param\":\"max_tokens\",\"message\":\"Use"
                    + " max_output_tokens; api_key="
                        + s.apiKey
                        + " https://example.test/private"
                        + " data:image/png;base64,AA==\",\"request\":\"private prompt\"}}";
        int before = sent.size();
        try {
            ApiClient.chat(s, content, () -> false);
            throw new AssertionError("400 accepted");
        } catch (Exception expected) {
            String message = expected.getMessage();
            ok(
                    message.contains("HTTP 400")
                            && message.contains("param=max_tokens")
                            && message.contains("Use max_output_tokens"),
                    "specific server error retained");
            ok(
                    !message.contains(s.apiKey)
                            && !message.contains("example.test")
                            && !message.contains("AA==")
                            && !message.contains("private prompt"),
                    "server error redacted and unrelated body not exported");
        }
        ok(sent.size() == before + 1, "400 does not retry or silently change options");
        ok(
                ApiClient.serverErrorFields(
                                new JSONObject().put("error", "Session header is required"),
                                s.apiKey)
                        .contains("Session header"),
                "string error supported");
        status = 200;
        reply =
                "{\"choices\":[{\"message\":{\"reasoning_content\":\"internal reasoning is not a"
                        + " translation\",\"content\":\"{\\\"translations\\\":[]}\"}}]}";
        for (String model : new String[] {"deepseek-v4.1-flash", "deepseek-v4-flash-vision-exp"}) {
            s.textModel = model;
            result = ApiClient.chat(s, content, () -> false);
            c = sent.get(sent.size() - 1);
            p = c.payload();
            ok(
                    c.getURL().getPath().endsWith("/chat/completions")
                            && p.has("messages")
                            && p.has("max_tokens")
                            && p.has("reasoning_effort"),
                    "DeepSeek keeps Chat Completions " + model);
            ok(
                    p.getString("model").equals(model)
                            && p.getInt("max_tokens") == 5000
                            && p.getString("reasoning_effort").equals("low")
                            && !p.has("service_tier"),
                    "exact DeepSeek model and default options " + model);
            JSONArray parts = p.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
            ok(
                    parts.getJSONObject(1).getString("type").equals("image_url")
                            && parts.getJSONObject(1)
                                    .getJSONObject("image_url")
                                    .getString("url")
                                    .startsWith("data:image/png;base64,")
                            && result.getJSONArray("translations").length() == 0,
                    "DeepSeek image schema and final content parsing " + model);
        }
        for (String model :
                new String[] {"gpt-5.6-luna", "grok-4.7", "muse-spark-1.3-contributor"}) {
            s.textModel = model;
            ok(ApiClient.usesResponses(s), "documented Responses model " + model);
        }
        s.textModel = "gpt-6-luna";
        s.baseUrl = "https://proxy.example/v1";
        ok(
                !ApiClient.usesResponses(s),
                "existing proxies keep their configured Chat compatibility");
        s.baseUrl = "https://opencode.ai.evil.test/zen/go/v1";
        ok(
                !ApiClient.isOpenCode(s.baseUrl),
                "lookalike host cannot receive OpenCode session header");
        s.baseUrl = "https://opencode.ai/zen/go/v10";
        ok(!ApiClient.isOpenCode(s.baseUrl), "path prefix cannot match another API");
        s.baseUrl = "https://opencode.ai/zen/v1";
        ok(ApiClient.usesResponses(s), "Zen and Go both supported");
        s.textModel = "minimax-m3";
        before = sent.size();
        try {
            ApiClient.chat(s, content, () -> false);
            throw new AssertionError("unsupported model sent");
        } catch (Exception expected) {
            ok(
                    expected.getMessage().contains("Messages") && sent.size() == before,
                    "unsupported Messages model explained before network");
        }
        System.out.println(
                "OpenCodeProtocolChecks: "
                        + checks
                        + " checks passed (intercepted HTTPS; no paid API or Android runtime)");
    }
}
