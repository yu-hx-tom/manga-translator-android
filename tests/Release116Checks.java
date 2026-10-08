package cn.local.manga;

import android.content.Context;
import android.graphics.Rect;

import org.json.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Synthetic filesystem and rendering-plan checks; no Android font rasterization claim. */
public final class Release116Checks {
    private static int checks;

    private interface Action {
        void run() throws Exception;
    }

    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
        checks++;
    }

    private static void rejects(Action action, String reason) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            checks++;
            return;
        }
        throw new AssertionError(reason);
    }

    private static byte[] png(int color) throws Exception {
        int[] pixels = new int[192];
        Arrays.fill(pixels, color);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new android.graphics.Bitmap(16, 12, pixels)
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    public static void main(String[] args) {
        try {
            run(new File(args[0]));
            System.out.println(
                    checks
                            + " release 1.1.6 checks passed (host links/SQLite/settings/layout, not"
                            + " Android runtime)");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(File root) throws Exception {
        root.mkdirs();
        Context c = new Context(root);
        PageCacheStore web = new PageCacheStore(c);
        File alias = new File(c.getCacheDir(), "browser-pages-v2/document/page.png");
        byte[] a = png(0xff112233), b = png(0xff445566);
        PageOutcome outcome = new PageOutcome(1, 1, 0, 0, "synthetic");
        web.save(alias, a, outcome);
        check(Files.isSymbolicLink(alias.toPath()), "web view uses a real symbolic link");
        Path original = alias.toPath().toRealPath();
        check(
                !original.startsWith(c.getCacheDir().toPath().toRealPath()),
                "fixture resolves outside cache into blobs");
        File view = StorageFiles.viewChild(c.getCacheDir(), "browser-pages-v2/document/page.png");
        check(
                Files.isSymbolicLink(view.toPath()),
                "view resolver preserves the final link rather than returning its target");
        check(
                StorageFiles.relativeView(c.getCacheDir(), alias)
                        .equals("browser-pages-v2/document/page.png"),
                "saved alias keeps its logical cache-relative name");
        check(Arrays.equals(a, web.read(alias, true)), "save then read a linked web cache");
        check(
                web.committedFile(alias).toPath().toRealPath().equals(original),
                "session receives the committed blob");
        web.save(alias, b, outcome);
        check(
                Arrays.equals(b, web.read(alias, true)),
                "overwrite existing link then read new content");
        check(
                Arrays.equals(a, Files.readAllBytes(original)),
                "overwrite never mutates original shared bytes");
        Path replacement = alias.toPath().toRealPath();
        Files.delete(alias.toPath());
        check(
                Arrays.equals(b, web.read(alias, true)) && Files.isSymbolicLink(alias.toPath()),
                "missing alias rebuilt on read");
        Files.delete(alias.toPath());
        Files.createSymbolicLink(
                alias.toPath(), new File(c.getFilesDir(), "missing-blob").toPath());
        check(
                Arrays.equals(b, web.read(alias, true)),
                "dangling alias replaced without following it");
        File outside = new File(root, "outside.bin");
        Files.write(outside.toPath(), a);
        Files.delete(alias.toPath());
        Files.createSymbolicLink(alias.toPath(), outside.toPath());
        rejects(
                () -> web.read(alias, true),
                "foreign final link must not be read as a trusted view");
        check(Arrays.equals(a, Files.readAllBytes(outside.toPath())), "foreign target unchanged");
        Files.delete(alias.toPath());
        web.read(alias, true);
        StorageDatabase.call(
                c,
                s -> {
                    StorageQuota.removeCache(s, "web/page");
                    return null;
                });
        check(
                !Files.exists(alias.toPath(), LinkOption.NOFOLLOW_LINKS),
                "cache removal deletes the alias");
        check(
                Files.exists(replacement) && Arrays.equals(b, Files.readAllBytes(replacement)),
                "alias removal preserves blob for grace/other references");
        File outsideDir = new File(root, "outside-dir");
        outsideDir.mkdirs();
        Path escape = new File(c.getCacheDir(), "browser-pages-v2/escape").toPath();
        Files.createSymbolicLink(escape, outsideDir.toPath());
        rejects(
                () -> StorageFiles.viewChild(c.getCacheDir(), "browser-pages-v2/escape/new.png"),
                "parent directory link escaping root rejected");
        rejects(
                () ->
                        StorageFiles.relativeView(
                                c.getCacheDir(), escape.resolve("new.png").toFile()),
                "write path escaping through parent rejected");
        for (String bad :
                new String[] {
                    "../outside.bin",
                    "/outside.bin",
                    "browser-pages-v2/../other",
                    "browser-pages-v2//page",
                    "C:/outside",
                    "browser-pages-v2\\page"
                })
            rejects(
                    () -> StorageFiles.viewChild(c.getCacheDir(), bad),
                    "malformed relative path rejected");
        Path rootAlias = new File(root, "cache-alias").toPath();
        Files.createSymbolicLink(rootAlias, c.getCacheDir().toPath());
        check(
                StorageFiles.relativeView(
                                rootAlias.toFile(),
                                rootAlias.resolve("browser-pages-v2/document/new.png").toFile())
                        .equals("browser-pages-v2/document/new.png"),
                "aliased cache root normalized without resolving final leaf");

        AppSettings settings = new AppSettings();
        settings.baseUrl = "https://example.invalid/v1";
        settings.apiKey = "synthetic-key";
        check(settings.inPlaceColor == 0xff000000, "new default is opaque black");
        check(
                AppSettings.load(c).inPlaceColor == 0xff000000,
                "missing preference defaults to black");
        check(
                AppSettings.parseInPlaceColor("#123AbC") == 0xff123abc,
                "custom six-digit RGB parsed");
        check(
                AppSettings.formatInPlaceColor(0xff123abc).equals("#123ABC"),
                "color formatting roundtrip");
        boolean invalid = false;
        try {
            AppSettings.parseInPlaceColor("#GG0000");
        } catch (Exception expected) {
            invalid = true;
        }
        check(invalid, "invalid color refused");
        String black = settings.renderFingerprint(),
                blackPage = PagePipeline.contentKey("a".repeat(64), settings);
        File blackWeb = web.file("page", "doc", "https://example.invalid/image", 16, 12, settings);
        settings.inPlaceColor = 0xff1565c0;
        check(!black.equals(settings.renderFingerprint()), "color changes output fingerprint");
        check(
                !blackPage.equals(PagePipeline.contentKey("a".repeat(64), settings)),
                "color changes completed-page cache key");
        check(
                !blackWeb.equals(
                        web.file("page", "doc", "https://example.invalid/image", 16, 12, settings)),
                "color changes browser image key");
        check(
                AppSettings.fromSnapshot(settings.snapshot()).inPlaceColor == settings.inPlaceColor,
                "preset snapshot roundtrips color");
        JSONObject legacy = settings.snapshot();
        legacy.remove("inPlaceColor");
        check(
                AppSettings.fromSnapshot(legacy).inPlaceColor == 0xff000000,
                "old preset defaults to black");
        Region region =
                new Region("r1", new Rect(10, 10, 110, 100), Collections.emptyList(), false);
        check(
                Typesetter.planNearby(
                                        160,
                                        120,
                                        region,
                                        Collections.singletonList(region),
                                        "TEST",
                                        Typesetter.Style.DEFAULT)
                                .color
                        == 0xff000000,
                "default fallback plan uses black");
        check(
                Typesetter.planNearby(
                                        160,
                                        120,
                                        region,
                                        Collections.singletonList(region),
                                        "TEST",
                                        new Typesetter.Style(1f, null, settings.inPlaceColor))
                                .color
                        == settings.inPlaceColor,
                "custom fallback plan uses chosen color");
        File draft = new File(root, "draft");
        draft.mkdirs();
        Files.write(new File(draft, PageDraft.SOURCE).toPath(), a);
        JSONObject data =
                PageDraft.describe(
                                16,
                                12,
                                Collections.emptyList(),
                                Collections.emptyList(),
                                TranslationTranscript.unavailable())
                        .put("inPlaceColor", settings.inPlaceColor);
        File metadata = new File(draft, PageDraft.JSON);
        Files.writeString(metadata.toPath(), data.toString());
        PageDraft loaded = PageDraft.read(draft);
        check(loaded.inPlaceColor == settings.inPlaceColor, "draft reload keeps render-time color");
        check(
                loaded.withEdits(Collections.emptyMap()).inPlaceColor == settings.inPlaceColor,
                "draft expansion preserves render-time color");
        data.remove("inPlaceColor");
        Files.writeString(metadata.toPath(), data.toString());
        check(
                PageDraft.read(draft).inPlaceColor == 0xffff0000,
                "legacy drafts preserve original red appearance");
    }
}
