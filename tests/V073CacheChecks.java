package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.webkit.CookieManager;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real disk/HTTP flow; host image adapter does not validate Android decoder or device performance.
 */
public final class V073CacheChecks {
    private static int checks;

    private static void check(boolean ok, String detail) {
        checks++;
        if (!ok) throw new AssertionError(detail);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("manga-v073-cache-").toFile();
        sourceCache(root);
        detectionCache(root);
        boundedWrites(root);
        downloader(new File(root, "http"));
        System.out.println(
                checks
                        + " checks passed; real local HTTP and disk, no paid API, no Android"
                        + " runtime validation");
    }

    private static void sourceCache(File root) throws Exception {
        SourceImageCache cache = new SourceImageCache(root);
        String key =
                SourceImageCache.key(
                        "https://comic.test/page.png",
                        "https://comic.test/chapter",
                        "https://comic.test/chapter",
                        "ua",
                        "secret-cookie");
        byte[] original = "image-content-one".getBytes(StandardCharsets.UTF_8);
        check(!key.contains("secret"), "cookie only in key digest");
        check(
                !key.equals(
                        SourceImageCache.key(
                                "https://comic.test/page.png",
                                "https://comic.test/chapter",
                                "https://comic.test/chapter",
                                "ua",
                                "other-cookie")),
                "cookie scope");
        check(
                !key.equals(
                        SourceImageCache.key(
                                "https://comic.test/page.png",
                                "https://comic.test/other",
                                "https://comic.test/chapter",
                                "ua",
                                "secret-cookie")),
                "page scope");
        check(
                !key.equals(
                        SourceImageCache.key(
                                "https://comic.test/page.png",
                                "https://comic.test/chapter",
                                "https://comic.test/chapter",
                                "other-ua",
                                "secret-cookie")),
                "agent scope");
        check(cache.find(key, () -> false) == null, "initial miss");
        cache.store(
                key,
                original,
                "\"one\"",
                null,
                "private, no-cache",
                "Cookie, User-Agent",
                () -> false);
        SourceImageCache.Entry first = cache.find(key, () -> false);
        check(
                first != null && Arrays.equals(cache.read(first, () -> false), original),
                "original roundtrip");
        check(
                !new String(
                                Files.readAllBytes(cache.file(key).toPath()),
                                StandardCharsets.ISO_8859_1)
                        .contains("secret-cookie"),
                "no plaintext session in cache metadata");
        byte[] changed = "different-image".getBytes(StandardCharsets.UTF_8);
        cache.store(key, changed, "\"two\"", null, null, null, () -> false);
        check(
                cache.read(first, () -> false) == null,
                "entry superseded during conditional request rejected");
        check(
                Arrays.equals(cache.read(cache.find(key, () -> false), () -> false), changed),
                "replacement bytes");
        byte[] damaged = Files.readAllBytes(cache.file(key).toPath());
        damaged[damaged.length - 1] ^= 1;
        Files.write(cache.file(key).toPath(), damaged);
        check(
                cache.read(cache.find(key, () -> false), () -> false) == null,
                "corruption digest rejected");
        check(!cache.file(key).exists(), "corrupt file removed");
        cache.store(key, original, "one", null, "NO-STORE", null, () -> false);
        check(cache.find(key, () -> false) == null, "no-store skipped");
        cache.store(key, original, "one", null, null, "*", () -> false);
        check(cache.find(key, () -> false) == null, "Vary wildcard skipped");
        cache.store(key, original, "one", null, null, "X-Unrepresented-Header", () -> false);
        check(cache.find(key, () -> false) == null, "unknown vary skipped");
        cache.store(key, original, null, null, null, null, () -> false);
        check(cache.find(key, () -> false) == null, "no validators skipped");
        cache.store(key, original, "bad\r\nvalidator", null, null, null, () -> false);
        check(cache.find(key, () -> false) == null, "header injection rejected");
        cache.store(key, original, null, "Mon, 01 Jan 2024 00:00:00 GMT", null, null, () -> false);
        check(
                Arrays.equals(cache.read(cache.find(key, () -> false), () -> false), original),
                "Last-Modified-only roundtrip");
        try {
            cache.store(key, changed, "three", null, null, null, () -> true);
            check(false, "cancelled source write");
        } catch (CancellationException expected) {
            check(
                    Arrays.equals(cache.read(cache.find(key, () -> false), () -> false), original),
                    "cancel leaves previous source");
        }
        Files.write(cache.file(key).toPath(), new byte[] {1, 2});
        check(cache.find(key, () -> false) == null, "truncated source miss");
    }

    private static void detectionCache(File root) throws Exception {
        DetectionCache cache = new DetectionCache(root);
        Bitmap image = new Bitmap(2, 2, new int[] {0xff000000, 0xff123456, 0x80010203, 0xffffffff});
        String hash = DetectionCache.contentHash(image, () -> false);
        check(
                hash.equals(
                        DetectionCache.contentHash(
                                new Bitmap(
                                        2,
                                        2,
                                        new int[] {0xff000000, 0xff123456, 0x80010203, 0xffffffff}),
                                () -> false)),
                "same pixel identity");
        check(
                !hash.equals(
                        DetectionCache.contentHash(
                                new Bitmap(
                                        2,
                                        2,
                                        new int[] {0xff000000, 0xff123455, 0x80010203, 0xffffffff}),
                                () -> false)),
                "changed pixel identity");
        check(
                !hash.equals(
                        DetectionCache.contentHash(
                                new Bitmap(
                                        1,
                                        4,
                                        new int[] {0xff000000, 0xff123456, 0x80010203, 0xffffffff}),
                                () -> false)),
                "dimensions included");
        try {
            DetectionCache.contentHash(image, () -> true);
            check(false, "hash cancellation");
        } catch (CancellationException expected) {
            check(true, "hash cancellation");
        }
        String detector = DetectorModels.PP_ID;
        Region region =
                new Region(
                        "block_01",
                        new Rect(0, 0, 2, 2),
                        List.of(new Rect(0, 0, 1, 1)),
                        true,
                        new Rect(0, 0, 2, 2));
        check(cache.read(hash, detector, 2, 2, () -> false) == null, "detection miss");
        cache.write(hash, detector, 2, 2, List.of(region), () -> false);
        List<Region> stored = cache.read(hash, detector, 2, 2, () -> false);
        check(
                stored != null
                        && stored.size() == 1
                        && stored.get(0).id.equals("block_01")
                        && stored.get(0).vertical,
                "detection fields roundtrip");
        check(
                stored.get(0).lines.get(0).right == 1 && stored.get(0).contextBox.bottom == 2,
                "line/context roundtrip");
        check(
                cache.read(hash, DetectorModels.DEFAULT_ID, 2, 2, () -> false) == null,
                "model identity separates cache");
        check(
                cache.read(hash, detector, 4, 1, () -> false) == null,
                "detection dimensions separate");
        check(
                cache.read(
                                DetectionCache.contentHash(
                                        new Bitmap(2, 2, new int[] {1, 2, 3, 4}), () -> false),
                                detector,
                                2,
                                2,
                                () -> false)
                        == null,
                "content update miss");
        cache.write(hash, detector, 2, 2, List.of(), () -> false);
        check(
                cache.read(hash, detector, 2, 2, () -> false) == null,
                "fresh empty detection invalidates prior cached regions");
        cache.write(hash, detector, 2, 2, List.of(region), () -> false);
        cache.write(
                hash,
                detector,
                2,
                2,
                List.of(new Region("outside", new Rect(-1, 0, 2, 2), List.of(), false)),
                () -> false);
        check(
                cache.read(hash, detector, 2, 2, () -> false).get(0).id.equals("block_01"),
                "invalid geometry does not overwrite");
        byte[] corrupt = Files.readAllBytes(cache.file(hash, detector, 2, 2).toPath());
        corrupt[20] ^= 1;
        Files.write(cache.file(hash, detector, 2, 2).toPath(), corrupt);
        check(cache.read(hash, detector, 2, 2, () -> false) == null, "detection checksum rejected");
        cache.write(hash, detector, 2, 2, List.of(region), () -> false);
        byte[] coordinates = Files.readAllBytes(cache.file(hash, detector, 2, 2).toPath());
        // Header(16), UTF length(2), block_01(8), left at 26. Forge a checksum-valid negative
        // coordinate.
        Arrays.fill(coordinates, 26, 30, (byte) 255);
        java.security.MessageDigest digest = CacheFiles.digest();
        digest.update(coordinates, 0, coordinates.length - 32);
        System.arraycopy(digest.digest(), 0, coordinates, coordinates.length - 32, 32);
        Files.write(cache.file(hash, detector, 2, 2).toPath(), coordinates);
        check(
                cache.read(hash, detector, 2, 2, () -> false) == null,
                "checksum-valid invalid geometry rejected");
        try {
            cache.read("../outside", detector, 2, 2, () -> false);
            check(false, "path traversal");
        } catch (IllegalArgumentException expected) {
            check(true, "path traversal blocked");
        }
    }

    private static void boundedWrites(File root) throws Exception {
        File directory = new File(root, "bounded");
        directory.mkdirs();
        byte[] bytes = new byte[] {1, 2, 3, 4};
        for (int i = 0; i < 5; i++)
            CacheFiles.write(new File(directory, i + ".cache"), bytes, 8, 2, () -> false);
        File[] files = directory.listFiles(item -> item.getName().endsWith(".cache"));
        long size = 0;
        for (File file : files) size += file.length();
        check(
                files.length <= 2 && size <= 8 && new File(directory, "4.cache").exists(),
                "byte and entry cap");
        File preserved = new File(directory, "4.cache");
        AtomicInteger polls = new AtomicInteger();
        try {
            CacheFiles.write(
                    preserved, new byte[200_000], 500_000, 3, () -> polls.incrementAndGet() >= 4);
            check(false, "mid-write cancellation");
        } catch (CancellationException expected) {
            check(
                    Arrays.equals(Files.readAllBytes(preserved.toPath()), bytes),
                    "cancelled atomic write preserves old entry");
        }
        check(
                directory.listFiles(item -> item.getName().endsWith(".tmp")).length == 0,
                "cancelled temporary removed");
        Files.write(new File(directory, "pending-crashed.tmp").toPath(), new byte[] {1});
        CacheFiles.write(preserved, bytes, 8, 2, () -> false);
        check(!new File(directory, "pending-crashed.tmp").exists(), "abandoned temporary removed");
    }

    private static byte[] png(int color) throws Exception {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static void downloader(File root) throws Exception {
        Context context = new Context(root);
        byte[] first = png(0xff112233), second = png(0xff445566);
        AtomicReference<byte[]> body = new AtomicReference<>(first);
        AtomicReference<String> version = new AtomicReference<>("\"v1\"");
        AtomicReference<String> policy = new AtomicReference<>("private, no-cache"),
                vary = new AtomicReference<>("Cookie");
        AtomicInteger downloads = new AtomicInteger(), validations = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/image",
                exchange -> {
                    exchange.getResponseHeaders().set("ETag", version.get());
                    exchange.getResponseHeaders().set("Cache-Control", policy.get());
                    exchange.getResponseHeaders().set("Vary", vary.get());
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    if (version.get()
                            .equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                        validations.incrementAndGet();
                        exchange.sendResponseHeaders(304, -1);
                    } else {
                        downloads.incrementAndGet();
                        byte[] data = body.get();
                        exchange.sendResponseHeaders(200, data.length);
                        exchange.getResponseBody().write(data);
                    }
                    exchange.close();
                });
        AtomicInteger modifiedDownloads = new AtomicInteger(),
                modifiedValidations = new AtomicInteger();
        String modified = "Mon, 01 Jan 2024 00:00:00 GMT";
        server.createContext(
                "/modified",
                exchange -> {
                    exchange.getResponseHeaders().set("Last-Modified", modified);
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    if (modified.equals(
                            exchange.getRequestHeaders().getFirst("If-Modified-Since"))) {
                        modifiedValidations.incrementAndGet();
                        exchange.sendResponseHeaders(304, -1);
                    } else {
                        modifiedDownloads.incrementAndGet();
                        exchange.sendResponseHeaders(200, first.length);
                        exchange.getResponseBody().write(first);
                    }
                    exchange.close();
                });
        server.createContext(
                "/duplicate",
                exchange -> {
                    exchange.getResponseHeaders().set("ETag", "duplicate");
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.getResponseHeaders().add("Cache-Control", "private");
                    exchange.getResponseHeaders().add("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(200, first.length);
                    exchange.getResponseBody().write(first);
                    exchange.close();
                });
        server.createContext(
                "/hop",
                exchange -> {
                    int hop = Integer.parseInt(exchange.getRequestURI().getPath().substring(4));
                    exchange.getResponseHeaders()
                            .set("Location", hop == 4 ? "/image" : "/hop" + (hop + 1));
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/loop",
                exchange -> {
                    exchange.getResponseHeaders().set("Location", "/loop");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/lan",
                exchange -> {
                    exchange.getResponseHeaders().set("Location", "http://10.0.0.1/private");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort(),
                page = origin + "/chapter",
                url = origin + "/image";
        try {
            Bitmap a = BrowserImageLoader.load(context, url, page, "test-agent", () -> false);
            String aHash = DetectionCache.contentHash(a, () -> false);
            a.recycle();
            check(
                    downloads.get() == 1 && !BrowserImageLoader.lastCacheHit(),
                    "first HTTP download");
            Bitmap b = BrowserImageLoader.load(context, url, page, "test-agent", () -> false);
            check(
                    downloads.get() == 1
                            && validations.get() == 1
                            && BrowserImageLoader.lastCacheHit(),
                    "304 original reused without body download");
            check(
                    aHash.equals(DetectionCache.contentHash(b, () -> false)),
                    "304 decoded pixels unchanged");
            b.recycle();
            version.set("\"v2\"");
            body.set(second);
            Bitmap c = BrowserImageLoader.load(context, url, page, "test-agent", () -> false);
            check(
                    downloads.get() == 2
                            && !BrowserImageLoader.lastCacheHit()
                            && !aHash.equals(DetectionCache.contentHash(c, () -> false)),
                    "same URL new content downloaded");
            c.recycle();
            SourceImageCache raw = new SourceImageCache(context.getCacheDir());
            String key = SourceImageCache.key(url, page, page, "test-agent", CookieManager.cookie);
            byte[] corrupt = Files.readAllBytes(raw.file(key).toPath());
            corrupt[corrupt.length - 1] ^= 1;
            Files.write(raw.file(key).toPath(), corrupt);
            BrowserImageLoader.load(context, url, page, "test-agent", () -> false).recycle();
            check(
                    downloads.get() == 3
                            && validations.get() == 2
                            && !BrowserImageLoader.lastCacheHit(),
                    "corrupt304 cache retries unconditional GET");
            CookieManager.cookie = "session=two";
            BrowserImageLoader.load(context, url, page, "test-agent", () -> false).recycle();
            check(
                    downloads.get() == 4 && !BrowserImageLoader.lastCacheHit(),
                    "new cookie session requires own original");
            policy.set("no-store");
            version.set("\"v3\"");
            BrowserImageLoader.load(context, url, page, "test-agent", () -> false).recycle();
            BrowserImageLoader.load(context, url, page, "test-agent", () -> false).recycle();
            check(
                    downloads.get() == 6 && !BrowserImageLoader.lastCacheHit(),
                    "no-store response removes and skips original");
            policy.set("private");
            vary.set("*");
            BrowserImageLoader.load(context, url, page, "test-agent", () -> false).recycle();
            BrowserImageLoader.load(context, url, page, "test-agent", () -> false).recycle();
            check(
                    downloads.get() == 8 && !BrowserImageLoader.lastCacheHit(),
                    "Vary wildcard never reused");
            int before = downloads.get();
            try {
                BrowserImageLoader.load(
                        context, url, "https://public.example/chapter", "test-agent", () -> false);
                check(false, "public page private bridge");
            } catch (Exception expected) {
                check(
                        expected.getMessage().contains("内网") && downloads.get() == before,
                        "private network destination check retained");
            }
            try {
                BrowserImageLoader.load(context, url, page, "test-agent", () -> true);
                check(false, "loader cancel");
            } catch (CancellationException expected) {
                check(
                        downloads.get() == before && !BrowserImageLoader.lastCacheHit(),
                        "cancel before network and hit state reset");
            }
            BrowserImageLoader.load(context, origin + "/modified", page, "test-agent", () -> false)
                    .recycle();
            BrowserImageLoader.load(context, origin + "/modified", page, "test-agent", () -> false)
                    .recycle();
            check(
                    modifiedDownloads.get() == 1
                            && modifiedValidations.get() == 1
                            && BrowserImageLoader.lastCacheHit(),
                    "Last-Modified conditional HTTP request");
            BrowserImageLoader.load(context, origin + "/duplicate", page, "test-agent", () -> false)
                    .recycle();
            check(
                    raw.find(
                                    SourceImageCache.key(
                                            origin + "/duplicate",
                                            page,
                                            page,
                                            "test-agent",
                                            CookieManager.cookie),
                                    () -> false)
                            == null,
                    "duplicate response headers honor no-store");
            BrowserImageLoader.load(context, origin + "/hop0", page, "test-agent", () -> false)
                    .recycle();
            check(downloads.get() == before + 1, "five valid redirects still accepted");
            try {
                BrowserImageLoader.load(context, origin + "/loop", page, "test-agent", () -> false);
                check(false, "redirect limit");
            } catch (Exception expected) {
                check(expected.getMessage().contains("重定向超过 5"), "redirect limit retained");
            }
            try {
                BrowserImageLoader.load(context, origin + "/lan", page, "test-agent", () -> false);
                check(false, "redirect private bridge");
            } catch (Exception expected) {
                check(
                        expected.getMessage().contains("内网"),
                        "each redirect destination revalidated");
            }
        } finally {
            server.stop(0);
        }
    }
}
