package cn.local.manga;

import java.nio.file.*;

public final class V072StyleChecks {
    static int count;

    static void ok(boolean v, String why) {
        if (!v) throw new AssertionError(why);
        count++;
    }

    static String normal(String s) {
        int end = s.indexOf("    private static int brightness(");
        return s.substring(
                s.indexOf("    private static int[] renderLocal("),
                end >= 0 ? end : s.indexOf("    private static void abortForThrottle("));
    }

    public static void main(String[] args) throws Exception {
        NearbyTextLayout.Plan p = new NearbyTextLayout.Plan();
        p.box = new int[] {10, 20, 80, 110};
        p.padding = 5;
        p.step = 20;
        p.columns = 3;
        p.points = "一二三四五六七八九十甲乙".codePoints().toArray();
        ok(p.rows() == 4, "four glyphs per column");
        float[][] expected = {
            {55, 25}, {55, 45}, {55, 65}, {55, 85}, {35, 25}, {35, 45}, {35, 65}, {35, 85},
            {15, 25}, {15, 45}, {15, 65}, {15, 85}
        };
        for (int i = 0; i < expected.length; i++)
            ok(
                    p.cellLeft(i) == expected[i][0] && p.cellTop(i) == expected[i][1],
                    "top-to-bottom then right-to-left position " + i);
        p.points = "一二三四五".codePoints().toArray();
        ok(
                p.rows() == 2 && p.cellLeft(4) == 15 && p.cellTop(4) == 25,
                "partial final column begins at top left");
        String engine = Files.readString(Paths.get(args[0], "TranslationEngine.java")),
                before = Files.readString(Paths.get(args[1], "TranslationEngine.java"));
        String fallback =
                engine.substring(
                        engine.indexOf("    private static int[] renderNearby("),
                        engine.indexOf("    private static int[] bounds("));
        ok(
                fallback.contains("setColor(Color.RED)")
                        && fallback.contains("setColor(Color.WHITE)")
                        && !fallback.contains("setColor(Color.BLACK)"),
                "fallback red fill and preserved white outline");
        ok(normal(engine).equals(normal(before)), "normal rendering unchanged byte for byte");
        ok(
                Files.readString(Paths.get(args[0], "BrowserActivity.java"))
                        .contains("browser-page-v7.2-red-vertical"),
                "old rendered page cache cannot mask new fallback styling");
        System.out.println("V072StyleChecks: " + count + " checks passed");
    }
}
