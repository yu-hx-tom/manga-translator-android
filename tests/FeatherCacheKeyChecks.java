package cn.local.manga;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.ZipFile;

import javax.tools.ToolProvider;

/**
 * Executes archived 0.9.0 and current production key methods against the real rendered-page cache.
 */
public final class FeatherCacheKeyChecks {
    static int checks;

    static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    static String source(ZipFile zip, String name) throws Exception {
        String baseline = System.getProperty("manga.keys.baseline");
        if (baseline != null && !baseline.isEmpty())
            return Files.readString(Paths.get(baseline).resolve(name + ".java"))
                    .replace("\r\n", "\n");
        var item =
                zip.stream()
                        .filter(
                                e ->
                                        e.getName()
                                                .replace('\\', '/')
                                                .endsWith(
                                                        "app/src/main/java/cn/local/manga/"
                                                                + name
                                                                + ".java"))
                        .findFirst()
                        .orElseThrow();
        try (var input = zip.getInputStream(item)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    static String method(String source, String signature) {
        int start = source.indexOf(signature), end = source.indexOf("\n    }", start);
        if (start < 0 || end < 0)
            throw new IllegalArgumentException("Missing production method " + signature);
        return source.substring(start, end + 6);
    }

    static Class<?> compile(String name, String source, String engine, Path classes, Path report)
            throws Exception {
        String methods =
                method(source, "private String persistentPageKey(")
                        + "\n"
                        + method(source, "private File pageCacheFile(");
        var version =
                java.util.regex.Pattern.compile(
                                "private static final String CACHE_VERSION\\s*=\\s*\"[^\"]+\";")
                        .matcher(engine);
        if (!version.find()) throw new AssertionError("Missing real response cache version");
        String cleanup = method(engine, "private void prepareCleanup(");
        int start = cleanup.indexOf("MessageDigest digest="),
                end = cleanup.indexOf("if(!forceFresh)", start);
        if (start < 0 || end < 0)
            throw new AssertionError("Missing exact raw cleanup identity block");
        String raw =
                "public File cleanupKey(AppSettings settings,File input,int[][] targets,int[][]"
                    + " protectedBoxes,int width,int height)throws Exception{Job job=new"
                    + " Job(settings); android.graphics.Rect roi=new"
                    + " android.graphics.Rect(0,0,width,height); File"
                    + " cache=getCacheDir();java.util.function.BooleanSupplier cancelled=()->false;"
                        + cleanup.substring(start, end)
                        + "return saved;}";
        String host =
                "package cn.local.manga; import java.io.File; import java.util.Locale; import"
                        + " java.nio.charset.StandardCharsets; import java.security.MessageDigest;"
                        + " import org.json.JSONArray; public final class "
                        + name
                        + " { public File getCacheDir(){return new"
                        + " File(System.getProperty(\"feather.test.cache\"));} "
                        + version.group()
                        + " static final class Job {final AppSettings settings;Job(AppSettings"
                        + " s){settings=s;}} static void check(java.util.function.BooleanSupplier"
                        + " c){CacheFiles.check(c);} "
                        + methods.replace(
                                        "private String persistentPageKey",
                                        "public String persistentPageKey")
                                .replace("private File pageCacheFile", "public File pageCacheFile")
                        + method(engine, "private String identityData(")
                                .replace(
                                        "private String identityData", "public String identityData")
                        + raw
                        + " }";
        Path file = report.resolve(name + ".java");
        Files.writeString(file, host);
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
                                file.toString());
        if (result != 0) throw new AssertionError("Production key method compilation failed");
        return Class.forName("cn.local.manga." + name);
    }

    static Object responseKey(
            Object instance, AppSettings settings, String pixels, boolean vertical)
            throws Exception {
        Region region =
                new Region(
                        "one",
                        new android.graphics.Rect(0, 0, 16, 24),
                        java.util.List.of(),
                        vertical);
        return instance.getClass()
                .getMethod("identityData", String.class, Region.class, AppSettings.class)
                .invoke(instance, pixels, region, settings);
    }

    static java.io.File rawKey(
            Object instance,
            AppSettings settings,
            Path crop,
            int[][] targets,
            int[][] protectedBoxes)
            throws Exception {
        return (java.io.File)
                instance.getClass()
                        .getMethod(
                                "cleanupKey",
                                AppSettings.class,
                                java.io.File.class,
                                int[][].class,
                                int[][].class,
                                int.class,
                                int.class)
                        .invoke(instance, settings, crop.toFile(), targets, protectedBoxes, 16, 24);
    }

    static Object invoke(Object instance, String name, AppSettings settings) throws Exception {
        if (name.equals("persistentPageKey"))
            return instance.getClass()
                    .getMethod(name, String.class, AppSettings.class)
                    .invoke(instance, "same-source-pixels", settings);
        return instance.getClass()
                .getMethod(
                        name,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        int.class,
                        AppSettings.class)
                .invoke(
                        instance,
                        "https://example.test/chapter",
                        "same-document",
                        "https://example.test/page.png",
                        800,
                        1200,
                        settings);
    }

    public static void main(String[] args) throws Exception {
        Path project = Paths.get(args[0]),
                classes = Paths.get(args[1]),
                report = Paths.get(args[2]);
        Files.createDirectories(report);
        System.setProperty("feather.test.cache", report.resolve("cache").toString());
        Path src = project.resolve("app/src/main/java/cn/local/manga");
        try (ZipFile old =
                new ZipFile(project.resolve("交付/0.9.0/0.9.0源码与检查脚本_不含模型权重.zip").toFile())) {
            String priorEngine = source(old, "TranslationEngine"),
                    engine =
                            Files.readString(src.resolve("TranslationEngine.java"))
                                    .replace("\r\n", "\n");
            Object before =
                    compile(
                                    "BeforeFeatherKeys",
                                    source(old, "BrowserActivity"),
                                    priorEngine,
                                    classes,
                                    report)
                            .getConstructor()
                            .newInstance();
            Object after =
                    compile(
                                    "AfterFeatherKeys",
                                    Files.readString(src.resolve("BrowserActivity.java"))
                                            .replace("\r\n", "\n"),
                                    engine,
                                    classes,
                                    report)
                            .getConstructor()
                            .newInstance();
            AppSettings settings = new AppSettings();
            settings.baseUrl = "https://fixture.invalid/v1";
            settings.apiKey = "local-feather-fixture";
            for (String method : new String[] {"persistentPageKey", "pageCacheFile"}) {
                settings.mode = "text";
                ok(
                        !invoke(before, method, settings).equals(invoke(after, method, settings)),
                        method
                                + " separates old hard-edge and new feathered completed text"
                                + " images");
                settings.mode = "image";
                ok(
                        invoke(before, method, settings).equals(invoke(after, method, settings)),
                        method + " keeps unchanged image-translation mode cache reusable");
            }
            settings.mode = "text";
            for (String name :
                    new String[] {
                        "baseUrl",
                        "apiKey",
                        "mode",
                        "textModel",
                        "imageModel",
                        "textPrompt",
                        "imagePrompt",
                        "reasoningEffort",
                        "detectorModel"
                    }) {
                var field = AppSettings.class.getField(name);
                Object original = field.get(settings),
                        changed =
                                name.equals("mode")
                                        ? "image"
                                        : name.equals("detectorModel")
                                                ? (original.equals(DetectorModels.PP_ID)
                                                        ? DetectorModels.DEFAULT_ID
                                                        : DetectorModels.PP_ID)
                                                : original + "-changed";
                for (String method : new String[] {"persistentPageKey", "pageCacheFile"}) {
                    Object previous = invoke(after, method, settings);
                    field.set(settings, changed);
                    ok(
                            !previous.equals(invoke(after, method, settings)),
                            method + " remains sensitive to configuration field " + name);
                    field.set(settings, original);
                }
            }
            var sessionMethod =
                    after.getClass()
                            .getMethod(
                                    "pageCacheFile",
                                    String.class,
                                    String.class,
                                    String.class,
                                    int.class,
                                    int.class,
                                    AppSettings.class);
            Object[] sessionArgs = {
                "https://example.test/chapter",
                "same-document",
                "https://example.test/page.png",
                800,
                1200,
                settings
            };
            Object sessionBaseline = sessionMethod.invoke(after, sessionArgs);
            for (int i = 0; i < 5; i++) {
                Object previous = sessionArgs[i];
                sessionArgs[i] = i < 3 ? previous + "-changed" : ((Integer) previous) + 1;
                ok(
                        !sessionBaseline.equals(sessionMethod.invoke(after, sessionArgs)),
                        "session cache retains page/document/image URL/dimension field " + i);
                sessionArgs[i] = previous;
            }
            ok(
                    !invoke(after, "persistentPageKey", settings)
                            .equals(
                                    after.getClass()
                                            .getMethod(
                                                    "persistentPageKey",
                                                    String.class,
                                                    AppSettings.class)
                                            .invoke(after, "changed-source-pixels", settings)),
                    "persistent cache retains exact source content identity");
            settings.mode = "text";
            String oldKey = (String) invoke(before, "persistentPageKey", settings),
                    newKey = (String) invoke(after, "persistentPageKey", settings);
            RenderedPageCache cache = new RenderedPageCache(report.resolve("rendered").toFile());
            byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3};
            PageOutcome complete = new PageOutcome(1, 1, 0, 0, "complete");
            cache.write(oldKey, png, complete);
            ok(
                    cache.read(oldKey) != null && cache.read(newKey) == null,
                    "actual cache containing old completed text cannot bypass feather"
                            + " recomposition");
            cache.write(newKey, png, complete);
            ok(
                    cache.read(newKey) != null && cache.read(oldKey) != null,
                    "new result stores independently while retaining old evidence");
            java.io.File oldSession = (java.io.File) invoke(before, "pageCacheFile", settings),
                    newSession = (java.io.File) invoke(after, "pageCacheFile", settings);
            Files.write(oldSession.toPath(), png);
            ok(
                    oldSession.isFile() && !newSession.exists(),
                    "old session PNG cannot satisfy new session file lookup");
            ok(
                    method(priorEngine, "private String identityData(")
                            .equals(method(engine, "private String identityData(")),
                    "paid text crop identity calculation remains unchanged");
            java.util.regex.Pattern version =
                    java.util.regex.Pattern.compile("CACHE_VERSION\\s*=\\s*\"([^\"]+)\"");
            var a = version.matcher(priorEngine);
            var b = version.matcher(engine);
            ok(
                    a.find() && b.find() && a.group(1).equals(b.group(1)),
                    "text response cache version remains reusable without paid retranslation");
            String oldPrepare = method(priorEngine, "private void prepareCleanup("),
                    newPrepare = method(engine, "private void prepareCleanup(");
            ok(
                    oldPrepare
                            .substring(oldPrepare.indexOf("MessageDigest digest="))
                            .equals(
                                    newPrepare.substring(
                                            newPrepare.indexOf("MessageDigest digest="))),
                    "raw image-cleanup identity and reuse stay independent from local mask and"
                            + " typography changes");
            ok(
                    source(old, "ImageCleanup")
                            .equals(
                                    Files.readString(src.resolve("ImageCleanup.java"))
                                            .replace("\r\n", "\n")),
                    "cleanup prompt and protocol remain identical, allowing raw response cache"
                            + " reuse");
            ok(
                    source(old, "RenderedPageCache")
                            .equals(
                                    Files.readString(src.resolve("RenderedPageCache.java"))
                                            .replace("\r\n", "\n")),
                    "rendered cache format unchanged with revision isolated in mode-specific keys");
            for (String mode : new String[] {"text", "image"})
                for (boolean vertical : new boolean[] {false, true}) {
                    settings.mode = mode;
                    ok(
                            responseKey(before, settings, "same-base64-crop", vertical)
                                    .equals(
                                            responseKey(
                                                    after, settings, "same-base64-crop", vertical)),
                            "executed paid "
                                    + mode
                                    + " response identity stays reusable for orientation "
                                    + vertical);
                    ok(
                            !responseKey(after, settings, "same-base64-crop", vertical)
                                    .equals(
                                            responseKey(
                                                    after,
                                                    settings,
                                                    "changed-base64-crop",
                                                    vertical)),
                            "paid response identity still distinguishes actual crop pixels");
                }
            settings.mode = "text";
            Path crop = report.resolve("cleanup-input.png");
            Files.write(crop, png);
            int[][] targets = {{4, 4, 12, 20}}, protect = {{0, 0, 2, 24}};
            java.io.File oldRaw = rawKey(before, settings, crop, targets, protect),
                    newRaw = rawKey(after, settings, crop, targets, protect);
            ok(
                    oldRaw.equals(newRaw),
                    "executed archived/current raw cleanup digest produces identical cache path");
            Files.write(oldRaw.toPath(), png);
            ok(
                    java.util.Arrays.equals(Files.readAllBytes(newRaw.toPath()), png),
                    "new raw cleanup lookup reuses the exact already-paid result file");
            for (String name : new String[] {"baseUrl", "apiKey", "imageModel"}) {
                var field = AppSettings.class.getField(name);
                Object original = field.get(settings);
                field.set(settings, original + "-changed");
                ok(
                        !rawKey(after, settings, crop, targets, protect).equals(newRaw),
                        "raw cleanup retains service/account/model isolation: " + name);
                field.set(settings, original);
            }
            ok(
                    !rawKey(after, settings, crop, new int[][] {{5, 4, 12, 20}}, protect)
                            .equals(newRaw),
                    "raw cleanup identity includes exact target coordinates");
            ok(
                    !rawKey(after, settings, crop, targets, new int[][] {{0, 0, 3, 24}})
                            .equals(newRaw),
                    "raw cleanup identity includes protected paragraph coordinates");
            Files.write(crop, new byte[] {1, 2, 3, 4});
            ok(
                    !rawKey(after, settings, crop, targets, protect).equals(newRaw),
                    "raw cleanup identity includes exact source bytes");
        }
        System.out.println(
                "FeatherCacheKeyChecks: "
                        + checks
                        + " checks passed (compiled archived/current production keys and real disk"
                        + " cache; no network or Android UI)");
    }
}
