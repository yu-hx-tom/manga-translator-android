package cn.local.manga;

import com.sun.net.httpserver.HttpServer;

import org.json.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Production request path and mask geometry, without paid APIs or Android pixel claims. */
public class LocalOverlayChecks {
    static int checks;

    static void ok(boolean v, String message) {
        checks++;
        if (!v) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        int w = 40, h = 50;
        boolean[] interior = new boolean[w * h];
        for (int y = 3; y < 47; y++) for (int x = 3; x < 37; x++) interior[y * w + x] = true;
        WhiteBubbleCleaner.Mask paper =
                new WhiteBubbleCleaner.Mask(new boolean[w * h], interior, true, 0, null, false);
        boolean[] mask =
                WhiteBubbleCleaner.rectangleMask(
                        paper, w, h, new int[] {10, 10, 30, 40}, new int[0][]);
        int total = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                boolean expected = x >= 8 && x < 32 && y >= 8 && y < 42;
                if (mask[y * w + x]) total++;
                if (mask[y * w + x] != expected) throw new AssertionError("rectangle mismatch");
            }
        ok(total == 24 * 34, "solid rectangle includes spaces between glyphs");
        mask =
                WhiteBubbleCleaner.rectangleMask(
                        paper, w, h, new int[] {0, 0, 40, 50}, new int[][] {{15, 10, 25, 40}});
        for (int i = 0; i < mask.length; i++)
            if (mask[i] && !interior[i]) throw new AssertionError("outside balloon");
        ok(!mask[20 * w + 20] && !mask[0], "other paragraph and balloon boundary protected");
        for (WhiteBubbleCleaner.BackgroundKind kind :
                new WhiteBubbleCleaner.BackgroundKind[] {
                    WhiteBubbleCleaner.BackgroundKind.TEXTURED_ART,
                    WhiteBubbleCleaner.BackgroundKind.UNCERTAIN
                }) {
            WhiteBubbleCleaner.Mask art =
                    new WhiteBubbleCleaner.Mask(
                            new boolean[w * h],
                            interior,
                            false,
                            0,
                            null,
                            true,
                            null,
                            kind,
                            "fixture");
            for (boolean pixel :
                    WhiteBubbleCleaner.rectangleMask(
                            art, w, h, new int[] {0, 0, w, h}, new int[0][]))
                if (pixel) throw new AssertionError("background erased");
            ok(true, "background never receives rectangle");
        }
        Path root = Paths.get(args[0]);
        Files.createDirectories(root);
        AtomicInteger text = new AtomicInteger(), images = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        exchange.getRequestBody().readAllBytes();
                        boolean chat =
                                exchange.getRequestURI().getPath().equals("/v1/chat/completions");
                        if (chat) text.incrementAndGet();
                        else images.incrementAndGet();
                        String payload =
                                new JSONObject()
                                        .put(
                                                "translations",
                                                new JSONArray()
                                                        .put(PartialRetryChecks.item("a", "完整译文")))
                                        .toString();
                        byte[] body =
                                new JSONObject()
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
                                                                                                payload))))
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(chat ? 200 : 500, body.length);
                        exchange.getResponseBody().write(body);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        AppSettings settings = new AppSettings();
        settings.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        settings.apiKey = "fixture";
        settings.imageModel = "";
        settings.maxRetries = 0;
        try (TranslationEngine engine =
                new TranslationEngine(new android.content.Context(root.resolve("app").toFile()))) {
            settings.validate();
            List<Region> regions = List.of(PartialRetryChecks.region("a"));
            try (TranslationEngine.PreparedText job =
                    PartialRetryChecks.job(root, regions, settings)) {
                engine.requestTextPage(job, settings, () -> false);
                ok(
                        text.get() == 1 && images.get() == 0,
                        "fresh text needs one text request and zero image requests");
                ok(job.values.get("a").getString("zh").equals("完整译文"), "translation preserved");
                job.cleanupFailed.add("a");
                TranslationTranscript transcript =
                        TranslationEngine.textTranscript(job, Set.of("a"));
                ok(
                        transcript.rows.get(0).status.equals("in_place"),
                        "overlay is completed transcript");
            }
            Path directory = Files.createTempDirectory(root, "cached-");
            try (TranslationEngine.PreparedText job =
                    new TranslationEngine.PreparedText(directory.toFile(), regions, regions)) {
                job.settings = settings;
                job.values.put("a", PartialRetryChecks.item("a", "缓存译文"));
                engine.requestTextPage(job, settings, () -> false);
                ok(text.get() == 1 && images.get() == 0, "cached translation has no network tail");
            }
        } finally {
            server.stop(0);
        }
        System.out.println("LocalOverlayChecks: " + checks + " checks passed");
    }
}
