package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Worker-thread access to the page script (window.__mangaBrowserV1): evaluate, read large strings in chunks,
 * load an original image, and hand a translated PNG back. Every call is tied to a task token; a stale token,
 * a destroyed WebView or a changed page cancels instead of touching the new page. Moved out of
 * BrowserActivity unchanged in behavior.
 */
final class WebPageBridge {
    /** What the bridge needs from its activity. All methods are called on the main thread. */
    interface Host {
        WebView web();
        boolean valid(int token);
        boolean destroyed();
        /** True (after cancelling the task) when the reader navigated away from the translated page. */
        boolean pageChanged();
    }

    private static final int CHUNK = 32768, MAX_STRING = 12 * 1024 * 1024;
    private final Context context;
    private final Handler main;
    private final Host host;
    /** Transfers handed to the page but not yet confirmed, so a stop can withdraw them. */
    private final ConcurrentHashMap<String, String[]> replacements = new ConcurrentHashMap<>();

    WebPageBridge(Context context, Handler main, Host host) { this.context = context; this.main = main; this.host = host; }

    void check(int token) { if (!host.valid(token) || Thread.currentThread().isInterrupted()) throw new CancellationException(); }

    /** Evaluates {@code script} on the main thread and waits (max 12 s) for its JSON result. Worker threads only. */
    Object js(String script, int token) throws Exception {
        check(token);
        CompletableFuture<Object> future = new CompletableFuture<>();
        main.post(() -> {
            WebView web = host.web();
            if (!host.valid(token) || web == null || host.pageChanged()) { future.completeExceptionally(new CancellationException()); return; }
            try {
                web.evaluateJavascript(script, raw -> {
                    try {
                        if (!host.valid(token) || host.pageChanged()) throw new CancellationException();
                        future.complete(raw == null ? JSONObject.NULL : new JSONTokener(raw).nextValue());
                    } catch (Exception e) { future.completeExceptionally(e); }
                });
            } catch (Exception e) { future.completeExceptionally(e); }
        });
        try { return future.get(12, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw new Exception("网页已变化或脚本执行失败"); }
    }

    /** The page's record of an image's original source, or null when the id is unknown. */
    JSONObject original(String id, int token) throws Exception {
        Object value = js("JSON.stringify(window.__mangaBrowserV1.getOriginal(" + JSONObject.quote(id) + "))", token);
        return value instanceof String ? new JSONObject((String) value) : null;
    }
    /** Throws {@code message} unless the image still shows the original {@code expected}. */
    JSONObject requireOriginal(String id, String expected, int token, String message) throws Exception {
        JSONObject value = original(id, token);
        if (value == null || !expected.equals(value.optString("url"))) throw new Exception(message);
        return value;
    }

    /** Original pixels of one page image: network/cache, canvas snapshot, or recorded canvas drawing. */
    Bitmap loadImage(String id, String expected, String page, String agent, int token, BooleanSupplier stopped) throws Exception {
        requireOriginal(id, expected, token, "图片已重绘或更换，等待新图片");
        Bitmap result = null; boolean accepted = false;
        try {
            if (expected.startsWith("manga-canvas:")) {
                String data = dump(id, token);
                if (data != null) result = BrowserImageLoader.decodeDataUrl(data);
                else {
                    String json = readString("JSON.stringify(window.__mangaBrowserV1.getCapturePlan(" + JSONObject.quote(id) + "))", token);
                    if (json == null || json.length() > MAX_STRING) throw new Exception("画布绘制记录缺失或过大，请刷新页面或翻译当前画面");
                    CanvasCapture plan = CanvasCapture.parse(new JSONObject(json));
                    requireOriginal(id, expected, token, "图片已重绘或更换，等待新图片");
                    result = plan.render(url -> BrowserImageLoader.load(context, url, page, agent, stopped), stopped);
                }
            } else {
                try { result = BrowserImageLoader.load(context, expected, page, agent, stopped); }
                catch (Exception network) {
                    check(token);
                    if (stopped.getAsBoolean()) throw new CancellationException();
                    String data = dump(id, token);
                    if (data == null || !data.startsWith("data:image/")) throw network;
                    result = BrowserImageLoader.decodeDataUrl(data);
                }
            }
            requireOriginal(id, expected, token, "图片已重绘或更换，等待新图片");
            if (stopped.getAsBoolean()) throw new CancellationException();
            accepted = true;
            return result;
        } finally { if (!accepted && result != null && !result.isRecycled()) result.recycle(); }
    }

    private String dump(String id, int token) throws Exception {
        return readString("window.__mangaBrowserV1.dump(" + JSONObject.quote(id) + ")", token);
    }

    /** Reads a possibly huge string result in 32 KB slices through a temporary window slot. */
    String readString(String expression, int token) throws Exception {
        String slot = "__mangaOriginal_" + UUID.randomUUID().toString().replace("-", "");
        String quoted = JSONObject.quote(slot);
        try {
            Object count = js("window[" + quoted + "]=(" + expression + ");typeof window[" + quoted + "]==='string'?window[" + quoted + "].length:0", token);
            if (!(count instanceof Number)) return null;
            int length = ((Number) count).intValue();
            if (length <= 0 || length > MAX_STRING) return null;
            StringBuilder value = new StringBuilder(length);
            for (int p = 0; p < length; p += CHUNK) {
                check(token);
                Object chunk = js("window[" + quoted + "].slice(" + p + "," + Math.min(p + CHUNK, length) + ")", token);
                if (!(chunk instanceof String)) return null;
                value.append((String) chunk);
            }
            return value.toString();
        } finally {
            main.post(() -> { WebView web = host.web(); if (!host.destroyed() && web != null) web.evaluateJavascript("delete window[" + quoted + "]", null); });
        }
    }

    /** PNG for handing back to the page; rejects pages the page script cannot accept. */
    static byte[] encodePng(Bitmap bitmap) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!PerformanceDiagnostics.compress("web_display",bitmap,Bitmap.CompressFormat.PNG, 100, bytes)) throw new IOException("译图编码失败");
        if (bytes.size() > PageCacheStore.MAX_PNG_BYTES) throw new Exception("译图超过网页回填上限，未修改原图；可用单页导出查看");
        return bytes.toByteArray();
    }

    /**
     * Sends {@code png} to the page in chunks and waits until the page confirms it replaced image {@code id}
     * (still showing {@code expected}). False when the page declined because the image changed meanwhile.
     */
    boolean replace(String id, String expected, byte[] png, int token) throws Exception {
        if (png.length > PageCacheStore.MAX_PNG_BYTES) throw new IOException("译图缓存过大");
        String b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP);
        String slot = "__mangaTransfer_" + UUID.randomUUID().toString().replace("-", "");
        String quoted = JSONObject.quote(slot);
        js("window[" + quoted + "]=[];true", token);
        try {
            for (int p = 0; p < b64.length(); p += CHUNK) {
                check(token);
                js("window[" + quoted + "].push(" + JSONObject.quote(b64.substring(p, Math.min(p + CHUNK, b64.length()))) + ");true", token);
            }
            replacements.put(slot, new String[]{id, expected, slot});
            Object replaced = js("window.__mangaBrowserV1.replace(" + JSONObject.quote(id) + "," + JSONObject.quote(expected)
                    + ",'data:image/png;base64,'+window[" + quoted + "].join('')," + quoted + ")", token);
            if (!Boolean.TRUE.equals(replaced)) throw new Exception("网页图像状态已改变或回填缓存已满，请刷新后减少选图重试");
            for (int attempt = 0; attempt < 40; attempt++) {
                check(token);
                Object state = js("window.__mangaBrowserV1.getReplacementState(" + JSONObject.quote(id) + ")", token);
                if ("applied".equals(state)) return true;
                if (!"pending".equals(state)) return false;
                Thread.sleep(200);
            }
            return false;
        } finally {
            // UUID cleanup is safe even after cancellation/navigation and must not depend on the obsolete task token.
            main.post(() -> {
                replacements.remove(slot);
                WebView web = host.web();
                if (!host.destroyed() && web != null) web.evaluateJavascript("if(Object.prototype.hasOwnProperty.call(window," + quoted + ")){delete window[" + quoted + "];"
                        + (host.valid(token) ? "if(window.__mangaBrowserV1)window.__mangaBrowserV1.cancelReplacement(" + JSONObject.quote(id) + ","
                        + JSONObject.quote(expected) + "," + quoted + ")" : "") + "}", null);
            });
        }
    }
    boolean replace(String id, String expected, Bitmap bitmap, int token) throws Exception { return replace(id, expected, encodePng(bitmap), token); }

    /** Withdraws every unconfirmed transfer (main thread), e.g. when the user stops translating. */
    void cancelPendingReplacements() {
        WebView web = host.web();
        if (web == null || host.destroyed()) return;
        for (String[] item : replacements.values())
            web.evaluateJavascript("if(window.__mangaBrowserV1)window.__mangaBrowserV1.cancelReplacement(" + JSONObject.quote(item[0]) + ","
                    + JSONObject.quote(item[1]) + "," + JSONObject.quote(item[2]) + ")", null);
    }
}
