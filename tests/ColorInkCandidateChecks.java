package cn.local.manga;

import org.json.JSONObject;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.Arrays;

/** Synthetic ground-truth ink/background checks; no network or API calls. */
public final class ColorInkCandidateChecks {
    static int checks;
    static final int W = 230, H = 235, S = 26;
    static final int[][] LINES = {{59, 39, 91, 194}, {107, 39, 139, 194}};
    static final int[] PARAGRAPH = {56, 36, 142, 197};

    static void ok(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    static final class Fixture {
        int[] background, source;
        int paper;
    }

    static Fixture fixture(int paper, int ink, boolean closed) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(closed ? 0xffa36fbc : paper, true));
        g.fillRect(0, 0, W, H);
        if (closed) {
            g.setColor(new Color(paper, true));
            g.fillRect(24, 18, 180, 196);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(4));
            g.drawRect(24, 18, 180, 196);
            g.drawLine(24, 170, 45, 170);
            g.fillOval(176, 174, 9, 11);
        }
        Fixture f = new Fixture();
        f.paper = paper;
        f.background = image.getRGB(0, 0, W, H, null, 0, W);
        g.setColor(new Color(ink, true));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, S));
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (int x : new int[] {60, 108})
            for (int j = 0; j < 5; j++) g.drawString("日文測試字".substring(j, j + 1), x, 65 + j * 29);
        g.dispose();
        f.source = image.getRGB(0, 0, W, H, null, 0, W);
        return f;
    }

    static int light(int c) {
        return ((c >>> 16 & 255) * 299 + (c >>> 8 & 255) * 587 + (c & 255) * 114) / 1000;
    }

    static WhiteBubbleCleaner.Mask run(Fixture f) {
        return WhiteBubbleCleaner.forText(f.source, W, H, LINES, S, PARAGRAPH);
    }

    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]), baseline = Paths.get(args[1]);
        Files.createDirectories(out);
        int inkPixels = 0, protectedChanged = 0;
        for (int ink : new int[] {0xff73131d, 0xff563269, 0xff934217}) {
            Fixture f = fixture(0xfffdfdfc, ink, true);
            WhiteBubbleCleaner.Mask m = run(f);
            ok(
                    m.whiteBackground
                            && m.backgroundKind == WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER,
                    "closed neutral white paper accepts localized color ink");
            int[] after = f.source.clone();
            WhiteBubbleCleaner.apply(after, m);
            int target = 0, left = 0, changed = 0;
            for (int p = 0; p < after.length; p++) {
                if (f.source[p] != f.background[p] && light(f.source[p]) < 235) {
                    target++;
                    if (light(after[p]) < 245) left++;
                }
                if (f.source[p] == f.background[p]
                        && f.background[p] != f.paper
                        && after[p] != f.source[p]) changed++;
            }
            ok(target > 800 && left == 0, "all independently drawn color glyph ink is removed");
            ok(changed == 0, "frame, connected artwork and isolated foreign mark stay byte exact");
            inkPixels += target;
            protectedChanged += changed;
            int[][] foreign = {{106, 36, 142, 199}};
            boolean[] erase = WhiteBubbleCleaner.excludeForeign(m.erase, W, H, foreign),
                    layout = WhiteBubbleCleaner.excludeForeign(m.interior, W, H, foreign);
            int overwritten = 0, own = 0;
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    int p = y * W + x;
                    if (x >= 104 && x < 144 && y >= 34 && y < 201) {
                        if (erase[p] || layout[p]) overwritten++;
                    } else if (erase[p] && f.source[p] != f.background[p]) own++;
                }
            ok(
                    overwritten == 0 && own > 300,
                    "foreign line plus two-pixel guard protects cleanup and layout while own ink is"
                            + " recovered");
        }
        ok(
                !run(fixture(0xffe6e6e6, 0xff73131d, true)).whiteBackground,
                "gray paper with color ink stays refused");
        ok(
                !run(fixture(0xffd0b080, 0xff73131d, true)).whiteBackground,
                "colored paper stays refused");
        ok(
                !run(fixture(0xfffdfdfc, 0xff73131d, false)).whiteBackground,
                "open exterior white with color ink cannot invent balloon closure");
        try (URLClassLoader loader =
                new URLClassLoader(
                        new java.net.URL[] {baseline.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            Class<?> type = loader.loadClass("cn.local.manga.WhiteBubbleCleaner");
            Method method =
                    type.getDeclaredMethod(
                            "forText",
                            int[].class,
                            int.class,
                            int.class,
                            int[][].class,
                            int.class,
                            int[].class);
            method.setAccessible(true);
            for (int paper : new int[] {0xffffffff, 0xfffdfdfc, 0xffdcdcdc}) {
                Fixture f = fixture(paper, 0xff111111, true);
                Object old = method.invoke(null, f.source, W, H, LINES, S, PARAGRAPH);
                WhiteBubbleCleaner.Mask now = run(f);
                ok(
                        now.whiteBackground == (boolean) field(old, "whiteBackground")
                                && now.backgroundKind
                                        .name()
                                        .equals(field(old, "backgroundKind").toString()),
                        "neutral background verdict remains identical to frozen production");
                ok(
                        Arrays.equals(now.erase, (boolean[]) field(old, "erase"))
                                && Arrays.equals(now.interior, (boolean[]) field(old, "interior"))
                                && Arrays.equals(now.fillColors, (int[]) field(old, "fillColors")),
                        "accepted original neutral cleanup and paper shades remain byte"
                                + " equivalent");
            }
        }
        JSONObject report =
                new JSONObject()
                        .put("passed", true)
                        .put("checks", checks)
                        .put("independentColorInkPixels", inkPixels)
                        .put("protectedArtworkChanged", protectedChanged)
                        .put("paidApiCalls", 0)
                        .put("androidRuntimeVerified", false);
        Files.writeString(out.resolve("彩墨保护断言.json"), report.toString(2));
        System.out.println(report);
    }
}
