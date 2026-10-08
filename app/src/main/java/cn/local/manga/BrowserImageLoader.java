package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.CookieManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Loads page images using the WebView's site session, never the translation service credential. */
public final class BrowserImageLoader {
    public static final int MAX_ENCODED_BYTES = 24 * 1024 * 1024;
    public static final int MAX_PIXELS = 8_000_000;
    public static final int MAX_SIDE = 6000;
    public static final String LIMIT_NOTE = "图片超过 800 万像素或长边 6000 像素时会自动缩小；单张下载上限 24 MiB。";
    private static final long HTTP_DEADLINE_MS = 60_000;
    private static final int MAX_REDIRECTS = 5;
    private static final ThreadLocal<Boolean> CACHE_HIT = ThreadLocal.withInitial(() -> false);
    static final class HttpFailure extends Exception {
        final int status;
        HttpFailure(int status,String message){super(message);this.status=status;}
    }
    private BrowserImageLoader() {}
    public static boolean lastCacheHit() { return CACHE_HIT.get(); }

    public static Bitmap load(Context context, String imageUrl, String pageUrl, String userAgent, BooleanSupplier cancelled) throws Exception {
        CACHE_HIT.set(false);
        if (cancelled == null) throw new IllegalArgumentException("需要取消状态");
        checkCancelled(cancelled);
        if (imageUrl != null && imageUrl.regionMatches(true, 0, "data:", 0, 5)) {
            Bitmap bitmap = decodeDataUrl(imageUrl);
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) { bitmap.recycle(); checkCancelled(cancelled); }
            return bitmap;
        }
        URL target = networkUrl(imageUrl);
        URL page = null;
        try { page = networkUrl(pageUrl); } catch (Exception noWebOrigin) { /* Missing origins cannot authorize local destinations. */ }
        String agent = requestUserAgent(userAgent);
        SourceImageCache originals = new SourceImageCache(context);
        String unusableCacheKey = null;
        long deadline = SystemClock.elapsedRealtime() + HTTP_DEADLINE_MS;
        Thread requester = Thread.currentThread();
        AtomicReference<HttpURLConnection> active = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean(), timedOut = new AtomicBoolean();
        Thread guard = new Thread(() -> {
            while (!finished.get()) {
                boolean timeout = SystemClock.elapsedRealtime() >= deadline;
                if (requester.isInterrupted() || cancelled.getAsBoolean() || timeout) {
                    timedOut.set(timeout);
                    HttpURLConnection connection = active.get();
                    if (connection != null) connection.disconnect();
                    return;
                }
                try { Thread.sleep(150); } catch (InterruptedException stop) { return; }
            }
        }, "manga-web-image-cancel");
        guard.setDaemon(true);
        guard.start();
        try {
            for (int redirects = 0; ;) {
                checkCancelled(cancelled);
                int remaining = (int) Math.max(0, deadline - SystemClock.elapsedRealtime());
                if (remaining == 0) throw new Exception("网页图片请求超时（最长 60 秒），请稍后重试");
                ensureDestinationAllowed(target, page, deadline, cancelled);
                checkCancelled(cancelled);
                remaining = (int) Math.max(0, deadline - SystemClock.elapsedRealtime());
                if (remaining == 0) throw new Exception("网页图片请求超时（最长 60 秒），请稍后重试");
                HttpURLConnection connection = (HttpURLConnection) target.openConnection();
                active.set(connection);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(Math.min(15_000, remaining));
                connection.setReadTimeout(remaining);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.5");
                connection.setRequestProperty("User-Agent", agent);
                String referer = referer(pageUrl, target.toExternalForm());
                if (referer != null) connection.setRequestProperty("Referer", referer);
                // Re-read the exact target URL on every redirect. Domain/path/Secure cookie filtering is performed by WebView.
                String cookie;
                try { cookie = CookieManager.getInstance().getCookie(target.toExternalForm()); }
                catch (RuntimeException unavailable) { throw new Exception("无法读取网页会话，请先在内置浏览器中打开该页面后重试"); }
                if (cookie != null && !cookie.isEmpty() && cookie.indexOf('\r') < 0 && cookie.indexOf('\n') < 0)
                    connection.setRequestProperty("Cookie", cookie);
                String cacheKey = SourceImageCache.key(target.toExternalForm(), pageUrl, referer, agent, cookie);
                SourceImageCache.Entry cached = cacheKey.equals(unusableCacheKey) ? null : originals.find(cacheKey, cancelled);
                if (cached != null) cached.conditionalHeaders(connection);
                try {
                    int code = connection.getResponseCode();
                    checkCancelled(cancelled);
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        if (redirects >= MAX_REDIRECTS) throw new Exception("网页图片重定向超过 5 次，已停止请求");
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.trim().isEmpty()) throw new Exception("网页图片重定向地址缺失");
                        try { target = networkUrl(target.toURI().resolve(new URI(location.trim())).toString()); }
                        catch (Exception invalid) { throw new Exception("网页图片重定向到了无效或不支持的地址"); }
                        redirects++;
                        continue;
                    }
                    if (code == 401 || code == 403) throw new HttpFailure(code,"图片网站拒绝访问（HTTP " + code + "），请在内置浏览器登录或完成网站验证后重试");
                    if (code == 429) throw new HttpFailure(code,"图片网站请求过多（HTTP 429），请稍后重试");
                    if (code != 304 && (code < 200 || code >= 300)) throw new HttpFailure(code,"网页图片下载失败（HTTP " + code + "）");
                    String contentType = connection.getContentType();
                    if (contentType != null && (contentType.toLowerCase(Locale.ROOT).startsWith("text/html") || contentType.toLowerCase(Locale.ROOT).contains("application/json")))
                        throw new Exception("网站返回了网页或验证内容，无法作为漫画图片读取；请在浏览器中完成登录或验证");
                    if (connection.getContentLengthLong() > MAX_ENCODED_BYTES) throw new Exception("网页图片超过 24 MiB 下载上限");
                    byte[] encoded;
                    if (code == 304) {
                        if (cached == null) throw new Exception("图片网站返回了无法使用的缓存校验响应，请稍后重试");
                        encoded = originals.read(cached, cancelled);
                        if (encoded == null) { unusableCacheKey = cacheKey; originals.remove(cacheKey); continue; }
                        if (!SourceImageCache.mayStore(SourceImageCache.combinedHeader(connection, "Cache-Control"), SourceImageCache.combinedHeader(connection, "Vary"))) originals.remove(cacheKey);
                    } else try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[16_384];
                        int amount;
                        while ((amount = input.read(buffer)) != -1) {
                            checkCancelled(cancelled);
                            if (SystemClock.elapsedRealtime() >= deadline) throw new Exception("网页图片请求超时（最长 60 秒），请稍后重试");
                            if ((long) output.size() + amount > MAX_ENCODED_BYTES) throw new Exception("网页图片超过 24 MiB 下载上限");
                            output.write(buffer, 0, amount);
                        }
                        encoded = output.toByteArray();
                    }
                    checkCancelled(cancelled);
                    Bitmap bitmap = decode(encoded);
                    try {
                        checkCancelled(cancelled);
                        if (code == 200) originals.store(cacheKey, encoded, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"),
                                SourceImageCache.combinedHeader(connection, "Cache-Control"), SourceImageCache.combinedHeader(connection, "Vary"), cancelled);
                        checkCancelled(cancelled); CACHE_HIT.set(code == 304); return bitmap;
                    } catch (Exception failed) { bitmap.recycle(); throw failed; }
                } finally {
                    active.compareAndSet(connection, null);
                    connection.disconnect();
                }
            }
        } catch (java.io.IOException connectionFailure) {
            checkCancelled(cancelled);
            if (timedOut.get() || connectionFailure instanceof java.net.SocketTimeoutException)
                throw new Exception("网页图片请求超时（最长 60 秒），请稍后重试");
            throw new Exception("网页图片连接失败，请检查网络，或回到页面后重新选择图片");
        } finally {
            finished.set(true);
            guard.interrupt();
            HttpURLConnection connection = active.getAndSet(null);
            if (connection != null) connection.disconnect();
        }
    }

    public static Bitmap decodeDataUrl(String dataUrl) throws Exception {
        if (dataUrl == null) throw new Exception("图片数据为空");
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || comma > 64) throw new Exception("图片数据地址无效");
        String header = dataUrl.substring(0, comma).toLowerCase(Locale.ROOT);
        if (!header.matches("data:image/(png|jpeg|webp|gif);base64"))
            throw new Exception("仅支持 PNG、JPEG、WebP、GIF 的 Base64 图片数据");
        long characters = (long) dataUrl.length() - comma - 1;
        if (characters <= 0 || characters > ((long) MAX_ENCODED_BYTES + 2) / 3 * 4)
            throw new Exception("图片数据为空或超过 24 MiB 上限");
        String encoded = dataUrl.substring(comma + 1);
        // Android's decoder tolerates ignored characters; reject them before allocating decoded bytes.
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=' || c == '\r' || c == '\n'))
                throw new Exception("图片 Base64 数据无效");
        }
        byte[] decoded;
        try { decoded = Base64.decode(encoded, Base64.DEFAULT); }
        catch (IllegalArgumentException invalid) { throw new Exception("图片 Base64 数据无效"); }
        return decode(decoded);
    }

    private static Bitmap decode(byte[] encoded) throws Exception {
        if (encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) throw new Exception("图片数据为空或超过 24 MiB 上限");
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded)), (decoder, info, source) -> {
                int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                int[] target = boundedSize(width, height);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSize(target[0], target[1]);
                decoder.setOnPartialImageListener(error -> false);
            });
        } catch (Exception invalid) {
            throw new Exception("图片无法完整解码，可能是网站验证页、损坏文件或手机不支持的图片格式");
        }
    }

    static int[] boundedSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("图片尺寸无效");
        double scale = Math.min(1d, (double) MAX_SIDE / Math.max(width, height));
        scale = Math.min(scale, Math.sqrt((double) MAX_PIXELS / ((long) width * height)));
        return new int[]{Math.max(1, (int) Math.floor(width * scale)), Math.max(1, (int) Math.floor(height * scale))};
    }

    static URL networkUrl(String address) throws Exception {
        try {
            if (address == null || address.length() > 16_384) throw new Exception();
            URI uri = new URI(address.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535) throw new Exception();
            String ascii = uri.toASCIIString();
            int fragment = ascii.indexOf('#');
            return new URL(fragment < 0 ? ascii : ascii.substring(0, fragment));
        } catch (Exception invalid) { throw new Exception("网页图片地址必须使用普通 HTTP 或 HTTPS，不能包含账户凭据"); }
    }

    static String referer(String page, String image) {
        try {
            URL from = networkUrl(page), to = networkUrl(image);
            if ("https".equalsIgnoreCase(from.getProtocol()) && "http".equalsIgnoreCase(to.getProtocol())) return null;
            if (sameOrigin(from, to)) return from.toExternalForm();
            return from.getProtocol() + "://" + from.getAuthority() + "/";
        } catch (Exception invalid) { return null; }
    }
    private static boolean sameOrigin(URL first, URL second) {
        int firstPort = first.getPort() < 0 ? first.getDefaultPort() : first.getPort();
        int secondPort = second.getPort() < 0 ? second.getDefaultPort() : second.getPort();
        return first.getProtocol().equalsIgnoreCase(second.getProtocol()) && first.getHost().equalsIgnoreCase(second.getHost()) && firstPort == secondPort;
    }
    /** Public pages cannot use the native downloader as a bridge into localhost or the LAN. */
    private static void ensureDestinationAllowed(URL target, URL page, long deadline, BooleanSupplier cancelled) throws Exception {
        InetAddress[] addresses = resolve(target.getHost(), deadline, cancelled);
        boolean restricted = false;
        for (InetAddress address : addresses) if (isPrivateOrLocalAddress(address)) restricted = true;
        InetAddress[] pageAddresses = restricted && page != null && sameOrigin(page, target) ? resolve(page.getHost(), deadline, cancelled) : null;
        if (permitsDestination(target, page, addresses, pageAddresses)) return;
        throw new Exception("为保护本机和局域网，公共网页不能通过图片下载器访问内网地址；内网页面仅允许读取同源图片");
    }
    static boolean permitsDestination(URL target, URL page, InetAddress[] addresses, InetAddress[] pageAddresses) {
        if (addresses == null || addresses.length == 0) return false;
        boolean restricted = false;
        for (InetAddress address : addresses) restricted |= isPrivateOrLocalAddress(address);
        if (!restricted) return true;
        // A user viewing an internal site can fetch images only from that exact scheme/host/port.
        if (page == null || !sameOrigin(page, target) || pageAddresses == null || pageAddresses.length == 0) return false;
        for (InetAddress address : pageAddresses) if (!isPrivateOrLocalAddress(address)) return false;
        return true;
    }
    static boolean isPrivateOrLocalAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int a = raw[0] & 255, b = raw[1] & 255;
            // 198.18.0.0/15 is intentionally allowed: local VPN clients commonly use it for fake-IP DNS.
            return a == 0 || a == 10 || a == 127 || a == 169 && b == 254 || a == 172 && b >= 16 && b <= 31
                || a == 192 && b == 168 || a == 100 && b >= 64 && b <= 127 || a >= 224;
        }
        if (raw.length == 16) {
            if ((raw[0] & 0xfe) == 0xfc) return true; // IPv6 unique-local fc00::/7.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= raw[i] == 0;
            mapped &= (raw[10] & 255) == 255 && (raw[11] & 255) == 255;
            if (mapped) {
                try { return isPrivateOrLocalAddress(InetAddress.getByAddress(new byte[]{raw[12], raw[13], raw[14], raw[15]})); }
                catch (Exception impossible) { return true; }
            }
        }
        return false;
    }
    private static InetAddress[] resolve(String host, long deadline, BooleanSupplier cancelled) throws Exception {
        FutureTask<InetAddress[]> task = new FutureTask<>(() -> InetAddress.getAllByName(host));
        Thread resolver = new Thread(task, "manga-image-dns");
        resolver.setDaemon(true);
        resolver.start();
        try {
            while (true) {
                checkCancelled(cancelled);
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new Exception("网页图片地址解析超时（最长 60 秒），请稍后重试");
                try {
                    InetAddress[] addresses = task.get(Math.min(150, remaining), TimeUnit.MILLISECONDS);
                    if (addresses == null || addresses.length == 0) throw new Exception("网页图片地址无法解析，请检查网络");
                    return addresses;
                } catch (TimeoutException keepWaiting) { /* Poll only to honor cancellation while platform DNS is running. */ }
                catch (ExecutionException failed) { throw new Exception("网页图片地址无法解析，请检查网络"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("已取消图片读取"); }
            }
        } finally { if (!task.isDone()) task.cancel(true); }
    }
    private static String requestUserAgent(String value) throws Exception {
        if (value == null || value.trim().isEmpty()) return "Mozilla/5.0 (Android) MangaAssistant/0.2";
        if (value.length() > 2048 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new Exception("浏览器标识无效，请重新打开内置浏览器");
        return value;
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException("已取消图片读取");
    }
}
