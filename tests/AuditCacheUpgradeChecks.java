package cn.local.manga;

import java.io.File;
import java.nio.file.*;
import java.util.List;

import javax.tools.ToolProvider;

/**
 * Upgrade from the audited sources: stale grouping/pages miss; unchanged paid crops remain
 * reusable.
 */
public final class AuditCacheUpgradeChecks {
    static int checks;

    static void ok(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    static String read(Path path) throws Exception {
        return Files.readString(path).replace("\r\n", "\n");
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]), classes = Path.of(args[1]), out = Path.of(args[2]);
        Files.createDirectories(out);
        Path src = project.resolve("app/src/main/java/cn/local/manga"),
                old = project.resolve("tests/0.9.4验证/修改前");
        Path oldEngine =
                project.resolve(
                        "tests/0.9.4验证/请求与设置/修改前/app/src/main/java/cn/local/manga/TranslationEngine.java");
        System.setProperty("feather.test.cache", out.resolve("cache").toString());
        Object before =
                FeatherCacheKeyChecks.compile(
                                "BeforeAuditKeys",
                                read(old.resolve("BrowserActivity.java")),
                                read(oldEngine),
                                classes,
                                out)
                        .getConstructor()
                        .newInstance();
        Object after =
                FeatherCacheKeyChecks.compile(
                                "AfterAuditKeys",
                                read(src.resolve("BrowserActivity.java")),
                                read(src.resolve("TranslationEngine.java")),
                                classes,
                                out)
                        .getConstructor()
                        .newInstance();
        AppSettings settings = new AppSettings();
        settings.baseUrl = "https://fixture.invalid/v1";
        settings.apiKey = "local-upgrade-check";
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3};
        RenderedPageCache cache = new RenderedPageCache(out.resolve("rendered").toFile());
        PageOutcome complete = new PageOutcome(1, 1, 0, 0, "complete");
        for (String mode : new String[] {"text", "image"}) {
            settings.mode = mode;
            String
                    oldKey =
                            (String)
                                    FeatherCacheKeyChecks.invoke(
                                            before, "persistentPageKey", settings),
                    newKey =
                            (String)
                                    FeatherCacheKeyChecks.invoke(
                                            after, "persistentPageKey", settings);
            ok(!oldKey.equals(newKey), mode + " completed pages use new grouping revision");
            cache.write(oldKey, png, complete);
            ok(
                    cache.read(oldKey) != null && cache.read(newKey) == null,
                    mode + " old completed image cannot bypass corrected grouping");
            cache.write(newKey, png, complete);
            ok(cache.read(newKey) != null, mode + " corrected completed image can be reused");
            File
                    oldSession =
                            (File) FeatherCacheKeyChecks.invoke(before, "pageCacheFile", settings),
                    newSession =
                            (File) FeatherCacheKeyChecks.invoke(after, "pageCacheFile", settings);
            Files.createDirectories(oldSession.toPath().getParent());
            Files.write(oldSession.toPath(), png);
            ok(
                    !oldSession.equals(newSession) && !newSession.exists(),
                    mode + " session cache cannot return an old duplicate-paragraph page");
            for (boolean vertical : new boolean[] {false, true})
                ok(
                        FeatherCacheKeyChecks.responseKey(before, settings, "same-crop", vertical)
                                .equals(
                                        FeatherCacheKeyChecks.responseKey(
                                                after, settings, "same-crop", vertical)),
                        mode
                                + " unchanged paid crop response survives for orientation "
                                + vertical);
        }
        settings.mode = "text";
        Path crop = out.resolve("crop.png");
        Files.write(crop, png);
        int[][] target = {{4, 4, 12, 20}}, protect = {{0, 0, 2, 24}};
        File oldRaw = FeatherCacheKeyChecks.rawKey(before, settings, crop, target, protect),
                newRaw = FeatherCacheKeyChecks.rawKey(after, settings, crop, target, protect);
        ok(oldRaw.equals(newRaw), "identical cleanup crop and protection reuse paid raw response");
        Files.createDirectories(oldRaw.toPath().getParent());
        Files.write(oldRaw.toPath(), png);
        ok(
                java.util.Arrays.equals(Files.readAllBytes(newRaw.toPath()), png),
                "unchanged raw cleanup response remains accessible");
        ok(
                !newRaw.equals(
                        FeatherCacheKeyChecks.rawKey(
                                after, settings, crop, target, new int[][] {{0, 0, 3, 24}})),
                "changed protection geometry cannot reuse an incompatible repair");
        Path oldDetector = out.resolve("BeforeAuditDetectionCache.java");
        Files.writeString(
                oldDetector,
                read(old.resolve("DetectionCache.java"))
                        .replace("DetectionCache", "BeforeAuditDetectionCache"));
        int result =
                ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-encoding",
                                "UTF-8",
                                "-cp",
                                System.getProperty("java.class.path"),
                                "-d",
                                classes.toString(),
                                oldDetector.toString());
        if (result != 0) throw new AssertionError("Archived detection cache did not compile");
        Class<?> oldType = Class.forName("cn.local.manga.BeforeAuditDetectionCache");
        Object oldCache =
                oldType.getConstructor(File.class).newInstance(out.resolve("detection").toFile());
        var oldFileMethod =
                oldType.getDeclaredMethod("file", String.class, String.class, int.class, int.class);
        oldFileMethod.setAccessible(true);
        DetectionCache detection = new DetectionCache(out.resolve("detection").toFile());
        String content = "a".repeat(64);
        for (String model : DetectorModels.IDS) {
            File oldFile = (File) oldFileMethod.invoke(oldCache, content, model, 80, 100),
                    newFile = detection.file(content, model, 80, 100);
            ok(
                    !oldFile.equals(newFile),
                    "detection algorithm revision invalidates old grouping for " + model);
            List<Region> regions =
                    List.of(
                            new Region(
                                    "one",
                                    new android.graphics.Rect(2, 3, 30, 40),
                                    List.of(),
                                    true));
            detection.write(content, model, 80, 100, regions, () -> false);
            ok(
                    detection.read(content, model, 80, 100, () -> false) != null,
                    "new detection entry readable");
            Files.move(newFile.toPath(), oldFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ok(
                    detection.read(content, model, 80, 100, () -> false) == null
                            && oldFile.isFile(),
                    "old entry ignored without deleting prior evidence");
        }
        System.out.println(
                "AuditCacheUpgradeChecks: "
                        + checks
                        + " checks passed; real disk caches and compiled old/current production"
                        + " keys, no external API");
    }
}
