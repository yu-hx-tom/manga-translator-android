package cn.local.manga;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.*;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Device-only synthetic checks: real Android symlinks, settings persistence and colored glyph
 * pixels.
 */
final class Version116Checks {
    private static int checks;

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
        checks++;
    }

    static String run(Context app) throws Exception {
        File root = new File(app.getCacheDir(), "v116-check-" + UUID.randomUUID());
        root.mkdirs();
        String preferences = "v116-check-" + UUID.randomUUID();
        Context isolated =
                new ContextWrapper(app) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return app.getSharedPreferences(preferences, mode);
                    }
                };
        try {
            File cache = new File(root, "cache"),
                    blobs = new File(root, "files/blobs"),
                    source = new File(blobs, "synthetic.bin");
            cache.mkdirs();
            blobs.mkdirs();
            Files.write(source.toPath(), new byte[] {1, 2, 3});
            File alias = new File(cache, "browser-pages-v2/doc/page.png");
            alias.getParentFile().mkdirs();
            android.system.Os.symlink(source.getCanonicalPath(), alias.getPath());
            check(
                    alias.getCanonicalFile().equals(source.getCanonicalFile()),
                    "Android canonical path resolves the final link");
            check(
                    Files.isSymbolicLink(
                            StorageFiles.viewChild(cache, "browser-pages-v2/doc/page.png")
                                    .toPath()),
                    "view resolver keeps final link on Android");
            check(
                    StorageFiles.relativeView(cache, alias).equals("browser-pages-v2/doc/page.png"),
                    "Android alias relative name stays inside cache");
            File next = new File(blobs, "next.bin");
            Files.write(next.toPath(), new byte[] {4, 5, 6});
            StorageFiles.linkOrCopy(next, alias);
            check(
                    Arrays.equals(Files.readAllBytes(alias.toPath()), new byte[] {4, 5, 6}),
                    "existing alias replaced atomically");
            check(
                    Arrays.equals(Files.readAllBytes(source.toPath()), new byte[] {1, 2, 3}),
                    "original shared bytes preserved");
            Files.delete(StorageFiles.viewChild(cache, "browser-pages-v2/doc/page.png").toPath());
            check(source.isFile() && next.isFile(), "deleting alias leaves originals intact");
            AppSettings settings = AppSettings.load(isolated);
            check(settings.inPlaceColor == Color.BLACK, "new settings default black");
            settings.inPlaceColor = 0xff1565c0;
            settings.save(isolated);
            check(
                    AppSettings.load(isolated).inPlaceColor == settings.inPlaceColor,
                    "Android preferences save and reload chosen color");
            Region region =
                    new Region(
                            "synthetic",
                            new Rect(30, 30, 300, 150),
                            Collections.emptyList(),
                            false);
            for (int ink : new int[] {Color.BLACK, settings.inPlaceColor}) {
                Bitmap image = Bitmap.createBitmap(340, 190, Bitmap.Config.ARGB_8888);
                try {
                    image.eraseColor(0xffaaaaaa);
                    Typesetter.draw(
                            new Canvas(image),
                            Typesetter.planNearby(
                                    340,
                                    190,
                                    region,
                                    Collections.singletonList(region),
                                    "TEST",
                                    new Typesetter.Style(1f, null, ink)),
                            () -> false);
                    int[] pixels = new int[340 * 190];
                    image.getPixels(pixels, 0, 340, 0, 0, 340, 190);
                    boolean foreground = false, outline = false;
                    for (int pixel : pixels) {
                        foreground |= pixel == ink;
                        outline |= pixel == Color.WHITE;
                    }
                    check(foreground, "actual glyphs contain requested color");
                    check(outline, "white outline retained");
                } finally {
                    image.recycle();
                }
            }
            return checks + " v1.1.6 Android checks";
        } finally {
            app.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit();
            StorageQuota.deleteTree(root);
        }
    }
}
