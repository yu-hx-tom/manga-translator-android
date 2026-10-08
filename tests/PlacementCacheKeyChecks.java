package cn.local.manga;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.ZipFile;

import javax.tools.ToolProvider;

/**
 * Execute the exact old/new production key methods in a host shell with only getCacheDir supplied.
 */
public final class PlacementCacheKeyChecks {
    static int checks;

    static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    static String source(ZipFile zip, String name) throws Exception {
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

    static Class<?> compile(String name, String source, Path classes, Path report)
            throws Exception {
        String methods =
                method(source, "private String persistentPageKey(")
                        + "\n"
                        + method(source, "private File pageCacheFile(");
        String host =
                "package cn.local.manga; import java.io.File; import java.util.Locale; import"
                    + " java.nio.charset.StandardCharsets; import org.json.JSONArray; public final"
                    + " class "
                        + name
                        + " { public File getCacheDir(){return new"
                        + " File(System.getProperty(\"placement.test.cache\"));} "
                        + methods.replace(
                                        "private String persistentPageKey",
                                        "public String persistentPageKey")
                                .replace("private File pageCacheFile", "public File pageCacheFile")
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
        System.setProperty("placement.test.cache", report.resolve("cache").toString());
        Path src = project.resolve("app/src/main/java/cn/local/manga");
        try (ZipFile old =
                new ZipFile(project.resolve("交付/0.8.0/0.8.0源码与检查脚本_不含模型权重.zip").toFile())) {
            Object before =
                    compile("LegacyPlacementKeys", source(old, "BrowserActivity"), classes, report)
                            .getConstructor()
                            .newInstance();
            Object after =
                    compile(
                                    "CurrentPlacementKeys",
                                    Files.readString(src.resolve("BrowserActivity.java"))
                                            .replace("\r\n", "\n"),
                                    classes,
                                    report)
                            .getConstructor()
                            .newInstance();
            AppSettings settings = new AppSettings();
            settings.baseUrl = "https://fixture.invalid/v1";
            settings.apiKey = "local-test-key";
            for (String method : new String[] {"persistentPageKey", "pageCacheFile"}) {
                settings.mode = "text";
                ok(
                        !invoke(before, method, settings).equals(invoke(after, method, settings)),
                        method + " rejects old completed nearby text images");
                settings.mode = "image";
                ok(
                        invoke(before, method, settings).equals(invoke(after, method, settings)),
                        method + " preserves image-mode completed pages byte for byte");
            }
            String priorEngine = source(old, "TranslationEngine"),
                    engine =
                            Files.readString(src.resolve("TranslationEngine.java"))
                                    .replace("\r\n", "\n");
            ok(
                    method(priorEngine, "private String identityData(")
                            .equals(method(engine, "private String identityData(")),
                    "paid crop reply identity calculation unchanged");
            java.util.regex.Pattern version =
                    java.util.regex.Pattern.compile("CACHE_VERSION\\s*=\\s*\"([^\"]+)\"");
            var a = version.matcher(priorEngine);
            var b = version.matcher(engine);
            ok(
                    a.find() && b.find() && a.group(1).equals(b.group(1)),
                    "crop reply cache version unchanged despite local placement revision");
            ok(
                    source(old, "RenderedPageCache")
                            .equals(
                                    Files.readString(src.resolve("RenderedPageCache.java"))
                                            .replace("\r\n", "\n")),
                    "shared rendered cache format unchanged; mode-specific key isolates placement"
                            + " revision");
        }
        System.out.println(
                "PlacementCacheKeyChecks: "
                        + checks
                        + " checks passed (compiled old/new production key methods; no network or"
                        + " Android UI)");
    }
}
