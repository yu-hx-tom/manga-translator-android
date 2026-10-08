package cn.local.manga;

import android.graphics.Bitmap;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** The real result stage with failed/skipped regions; excludes Android Canvas acceptance. */
public final class ThrottleResultChecks {
    static int checks;

    static void ok(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Files.createDirectories(root);
        AppSettings settings = new AppSettings();
        settings.baseUrl = "https://example.invalid/v1";
        settings.apiKey = "unused-local-fixture";
        try (TranslationEngine engine =
                new TranslationEngine(new android.content.Context(root.resolve("app").toFile()))) {
            for (int status : new int[] {0, 429, 503}) {
                List<Region> regions =
                        List.of(
                                PartialRetryChecks.region("missing"),
                                PartialRetryChecks.region("skipped"));
                try (TranslationEngine.PreparedText job =
                        PartialRetryChecks.job(root, regions, settings)) {
                    Files.write(job.source.toPath(), ImageCleanupApiChecks.png(20, 40));
                    job.values.put(
                            "skipped", PartialRetryChecks.item("skipped", "").put("skip", true));
                    job.throttleFailure =
                            status == 0
                                    ? null
                                    : new ApiClient.RequestFailure(
                                            "provider busy", true, 0, status);
                    TranslationEngine.Result result = engine.renderTextPage(job, () -> false);
                    ok(
                            result.throttleFailure == job.throttleFailure,
                            "text result preserves the same typed throttle exception or null");
                    ok(
                            result.failed == 1 && result.skipped == 1 && !result.image.isRecycled(),
                            "returning a throttle signal preserves normal failed/skipped result"
                                    + " bookkeeping");
                    result.image.recycle();
                }
            }
        }
        Method abort =
                TranslationEngine.class.getDeclaredMethod(
                        "abortForThrottle", TranslationEngine.Result.class, Exception.class);
        abort.setAccessible(true);
        for (int status : new int[] {400, 429, 503}) {
            TranslationEngine.Result result = new TranslationEngine.Result();
            result.image = new Bitmap(1, 1, new int[] {0xffffffff});
            ApiClient.RequestFailure failure =
                    new ApiClient.RequestFailure("HTTP 429 is response text only", true, 0, status);
            Throwable thrown = null;
            try {
                abort.invoke(null, result, failure);
            } catch (InvocationTargetException expected) {
                thrown = expected.getCause();
            }
            boolean throttle = status == 429 || status == 503;
            ok(
                    throttle ? thrown == failure : thrown == null,
                    "image throttle stop policy uses the actual HTTP status");
            ok(
                    throttle ? result.throttleFailure == failure : result.throttleFailure == null,
                    "image result records only a real throttle failure");
            ok(
                    result.image.isRecycled() == throttle,
                    "image throttle preserves the existing bitmap cleanup policy");
        }
        System.out.println(
                "ThrottleResultChecks: "
                        + checks
                        + " checks passed (production result propagation; local raster adapter, no"
                        + " Android Canvas/UI)");
    }
}
