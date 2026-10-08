package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.json.JSONArray;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Guards the 0.9.5 refactor (PagePipeline / PageCacheStore / WebPageBridge /
 * AppSettings.renderFields). The frozen 0.9.4 formulas must differ since 1.1.6 adds configurable
 * in-place color. Old red rendered pages must not satisfy new settings. No network, no model calls.
 */
public final class RefactorChecks {
    private RefactorChecks() {}

    public static String run(Context context) throws Exception {
        int checks = 0;
        AppSettings s = sample();

        // 1. Legacy red rendering is a different cache generation.
        String legacyOutputConfig =
                new JSONArray()
                        .put(s.baseUrl)
                        .put(s.apiKey)
                        .put(s.mode)
                        .put(s.textModel)
                        .put(s.imageModel)
                        .put(s.textPrompt)
                        .put(s.imagePrompt)
                        .put(s.reasoningEffort)
                        .put(s.detectorModel)
                        .toString();
        require(
                !legacyOutputConfig.equals(s.renderFingerprint()),
                "1.1.6 fingerprint excludes legacy red output");
        checks++;

        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String legacyKey =
                RenderedPageCache.key(
                        hash,
                        new JSONArray()
                                        .put(s.baseUrl)
                                        .put(s.apiKey)
                                        .put(s.mode)
                                        .put(s.textModel)
                                        .put(s.imageModel)
                                        .put(s.textPrompt)
                                        .put(s.imagePrompt)
                                        .put(s.reasoningEffort)
                                        .put(s.detectorModel)
                                        .put(DetectorModels.get(s.detectorModel).sha256)
                                        .toString()
                                + "\npage-v0.9.4-dedup");
        require(
                !legacyKey.equals(PagePipeline.contentKey(hash, s)),
                "1.1.6 completed cache excludes legacy red output");
        checks++;

        File root = new File(context.getCacheDir(), "refactor-checks");
        deleteTree(root);
        PageCacheStore store = new PageCacheStore(root);
        String legacyConfig =
                new JSONArray()
                        .put("browser-page-v0.9.4-dedup")
                        .put("https://example.test/c/1")
                        .put("doc-1")
                        .put("https://img.example.test/1.png")
                        .put(800)
                        .put(1200)
                        .put(s.baseUrl)
                        .put(s.apiKey)
                        .put(s.mode)
                        .put(s.textModel)
                        .put(s.imageModel)
                        .put(s.textPrompt)
                        .put(s.imagePrompt)
                        .put(s.reasoningEffort)
                        .put(s.detectorModel)
                        .toString();
        File expected =
                new File(
                        new File(new File(root, "browser-pages"), "doc-1"),
                        sha(legacyConfig) + ".png");
        File actual =
                store.file(
                        "https://example.test/c/1",
                        "doc-1",
                        "https://img.example.test/1.png",
                        800,
                        1200,
                        s);
        require(!expected.equals(actual), "1.1.6 browser cache excludes legacy red output");
        checks++;

        // Every field that changes output must change the fingerprint; tuning fields must not.
        for (String field :
                Arrays.asList(
                        "baseUrl",
                        "apiKey",
                        "mode",
                        "textModel",
                        "imageModel",
                        "textPrompt",
                        "imagePrompt",
                        "reasoningEffort")) {
            AppSettings changed = sample();
            AppSettings.class
                    .getField(field)
                    .set(
                            changed,
                            "image".equals(changed.mode) && "mode".equals(field)
                                    ? "text"
                                    : "mode".equals(field) ? "image" : "changed-" + field);
            require(
                    !changed.renderFingerprint().equals(s.renderFingerprint()),
                    "fingerprint must include " + field);
            checks++;
        }
        AppSettings tuned = sample();
        tuned.textConcurrency = 3;
        tuned.requestTimeoutSeconds = 99;
        tuned.maxRetries = 4;
        tuned.serviceTier = "priority";
        require(
                tuned.renderFingerprint().equals(s.renderFingerprint()),
                "request tuning must not invalidate pages");
        checks++;

        // 2. PageCacheStore round trip: reserve → save → read (+outcome) → release.
        Bitmap tiny = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        tiny.eraseColor(Color.WHITE);
        byte[] png = WebPageBridge.encodePng(tiny);
        tiny.recycle();
        require(
                png.length > 8 && (png[0] & 255) == 137 && png[1] == 'P',
                "encodePng returns a PNG");
        checks++;
        PageOutcome outcome =
                new PageOutcome(
                        3, 2, 1, 0, 0, "检测 3 段，已回填 2 段", TranslationTranscript.unavailable());
        store.reserve(actual);
        try {
            store.save(actual, png, outcome);
        } finally {
            store.release(actual);
        }
        require(Arrays.equals(png, store.read(actual, true)), "saved PNG reads back identically");
        checks++;
        PageOutcome restored = store.readOutcome(actual);
        require(
                restored.known
                        && restored.detected == 3
                        && restored.succeeded == 2
                        && restored.failed == 1,
                "outcome companion round trip");
        checks++;
        require(
                store.read(new File(actual.getPath() + ".missing"), false) == null,
                "missing cache file reads as null");
        checks++;
        require(
                !store.readOutcome(new File(actual.getPath() + ".missing")).known,
                "missing outcome is unknown");
        checks++;
        deleteTree(root);

        // 3. Pipeline helpers.
        TranslationEngine.Result result = new TranslationEngine.Result();
        result.regions = new ArrayList<>(Collections.nCopies(4, (Region) null));
        result.succeeded = 3;
        result.failed = 1;
        result.summary = "检测 4 段";
        result.traceId = "trace-1";
        PageOutcome fromResult = PagePipeline.outcomeOf(result);
        require(
                fromResult.detected == 4
                        && fromResult.succeeded == 3
                        && fromResult.incomplete()
                        && fromResult.detail.endsWith("日志任务：trace-1"),
                "outcomeOf keeps counts and trace id");
        checks++;
        PageOutcome none = PagePipeline.noText(s, "无文字");
        require(
                none.known && none.detected == 0 && !none.incomplete(),
                "noText outcome is complete with 0 regions");
        checks++;
        require(
                PagePipeline.throttleStatus(new Exception("x")) == 0,
                "plain errors are not throttles");
        checks++;
        PagePipeline.Page empty = new PagePipeline.Page();
        require(!empty.fromCache() && !empty.rendered(), "fresh Page has no result");
        empty.close();
        checks++;

        // 4. Natural page order.
        List<String> names =
                new ArrayList<>(
                        Arrays.asList(
                                "p10.jpg",
                                "p9.jpg",
                                "P2.jpg",
                                "第10话",
                                "第2话",
                                "cover.jpg",
                                "p1-10.jpg",
                                "p1-2.jpg",
                                "10.jpg",
                                "002.jpg",
                                "1.jpg"));
        Collections.sort(names, NaturalOrder.INSTANCE);
        require(
                names.equals(
                        Arrays.asList(
                                "1.jpg",
                                "002.jpg",
                                "10.jpg",
                                "cover.jpg",
                                "p1-2.jpg",
                                "p1-10.jpg",
                                "P2.jpg",
                                "p9.jpg",
                                "p10.jpg",
                                "第2话",
                                "第10话")),
                "natural order: " + names);
        checks++;
        require(
                NaturalOrder.INSTANCE.compare("99999999999999999999", "100000000000000000000") < 0,
                "long digit runs do not overflow");
        checks++;

        return checks + " refactor checks";
    }

    private static AppSettings sample() {
        AppSettings s = new AppSettings();
        s.baseUrl = "http://192.168.1.2:50254/v1";
        s.apiKey = "sk-test-not-real";
        s.mode = "text";
        s.textModel = "text-model";
        s.imageModel = "image-model";
        s.reasoningEffort = "low";
        return s;
    }

    private static String sha(String value) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b :
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
            hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError("Refactor: " + message);
    }
}
