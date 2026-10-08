package cn.local.manga;

import java.util.*;

/**
 * Actual production plans: source-scale regressions plus cell containment and full-transcript
 * checks.
 */
public final class AdaptiveTypographyChecks {
    static int checks;

    static void ok(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    static boolean[] rectangle(int w, int h, int l, int t, int r, int b) {
        boolean[] mask = new boolean[w * h];
        for (int y = t; y < b; y++) for (int x = l; x < r; x++) mask[y * w + x] = true;
        return mask;
    }

    static void inside(BubbleLayout.Plan p, boolean[] mask, int w, int h, int count) {
        ok(
                p.cells.length == count && p.codepoints.length == count,
                "bubble retains every translated codepoint");
        for (int[] cell : p.cells) {
            ok(
                    cell[0] >= 0 && cell[1] >= 0 && cell[2] <= w && cell[3] <= h,
                    "bubble cell within crop");
            for (int y = cell[1]; y < cell[3]; y++)
                for (int x = cell[0]; x < cell[2]; x++)
                    if (!mask[y * w + x]) throw new AssertionError("bubble cell escaped safe mask");
        }
        checks++;
    }

    static void inside(NearbyTextLayout.Plan p, int expected) {
        ok(p.points.length == expected, "fallback retains complete Unicode transcript");
        for (int i = 0; i < p.points.length; i++)
            ok(
                    p.cellLeft(i) >= p.box[0] - .001f
                            && p.cellTop(i) >= p.box[1] - .001f
                            && p.cellLeft(i) + p.cellStep(i) <= p.box[2] + .001f
                            && p.cellTop(i) + p.cellStep(i) <= p.box[3] + .001f,
                    "fallback cell stays inside immutable target");
    }

    static void avoids(NearbyTextLayout.Plan p, int[][] protectedBoxes) {
        for (int i = 0; i < p.points.length; i++)
            for (int[] r : protectedBoxes) {
                float x = p.cellLeft(i), y = p.cellTop(i), s = p.cellStep(i);
                ok(
                        x >= r[2] + 2 || x + s <= r[0] - 2 || y >= r[3] + 2 || y + s <= r[1] - 2,
                        "glyph keeps the full two-pixel foreign protection");
            }
    }

    static float[][] cells(NearbyTextLayout.Plan p) {
        float[][] out = new float[p.points.length][4];
        for (int i = 0; i < out.length; i++)
            out[i] = new float[] {p.cellLeft(i), p.cellTop(i), p.cellStep(i), p.cellFont(i)};
        return out;
    }

    static void translated(NearbyTextLayout.Plan p, float[][] before, float dx, float dy) {
        for (int i = 0; i < before.length; i++)
            ok(
                    Math.abs(p.cellLeft(i) - before[i][0] - dx) < .001f
                            && Math.abs(p.cellTop(i) - before[i][1] - dy) < .001f
                            && p.cellStep(i) == before[i][2]
                            && p.cellFont(i) == before[i][3],
                    "rigid adjustment preserves all per-cell sizes and relative positions");
    }

    static void protectedPlacement() {
        int[][] protectedCrop = {{109, 285, 186, 380}};
        NearbyTextLayout.Plan p =
                NearbyTextLayout.forCrop(
                        1062,
                        1500,
                        new int[] {315, 818, 439, 1136},
                        284,
                        787,
                        true,
                        "啊、啊……",
                        new int[][] {{310, 816, 444, 1138}});
        float[][] before = cells(p);
        int[] box = p.box.clone(), points = p.points.clone();
        float font = p.font;
        ok(
                NearbyTextLayout.fitProtected(p, protectedCrop),
                "actual P12_rt_7 fits by translation instead of a new protected_text_layout"
                        + " rejection");
        float dx = p.cellLeft(0) - before[0][0], dy = p.cellTop(0) - before[0][1];
        translated(p, before, dx, dy);
        ok(
                dx * dx + dy * dy <= 17 * 17,
                "compact ellipsis needs no greater move than the earlier full-cell plan");
        inside(p, 5);
        avoids(p, protectedCrop);
        ok(
                Arrays.equals(box, p.box)
                        && Arrays.equals(points, p.points)
                        && p.font == font
                        && font > 47,
                "actual P12 retains source-sized type, original target and every character");
        before = cells(p);
        ok(NearbyTextLayout.fitProtected(p, protectedCrop), "already safe adjusted plan accepted");
        translated(p, before, 0, 0);
        NearbyTextLayout.Plan noRoom =
                NearbyTextLayout.forCrop(
                        100,
                        100,
                        new int[] {0, 0, 100, 100},
                        0,
                        0,
                        true,
                        "字",
                        new int[][] {{10, 10, 90, 90}});
        before = cells(noRoom);
        ok(
                !NearbyTextLayout.fitProtected(noRoom, new int[][] {{0, 0, 100, 100}}),
                "no-space protection keeps the existing refusal");
        translated(noRoom, before, 0, 0);
        NearbyTextLayout.Plan mixed = new NearbyTextLayout.Plan();
        mixed.box = new int[] {10, 10, 100, 100};
        mixed.points = new int[] {'啊', '♥'};
        mixed.columns = 1;
        mixed.sourceGrouped = true;
        mixed.font = 15;
        mixed.step = 20;
        mixed.placedCells = new float[][] {{40, 35}, {75, 70}};
        mixed.placedSteps = new float[] {20, 10};
        mixed.placedFonts = new float[] {15, 7.5f};
        before = cells(mixed);
        int[][] obstacle = {{55, 35, 70, 55}};
        ok(
                NearbyTextLayout.fitProtected(mixed, obstacle),
                "mixed-size source groups can move as one rigid paragraph");
        translated(mixed, before, -7, 0);
        inside(mixed, 2);
        avoids(mixed, obstacle);
        NearbyTextLayout.Plan corner = new NearbyTextLayout.Plan();
        corner.box = new int[] {0, 0, 100, 100};
        corner.points = new int[] {'字'};
        corner.columns = 1;
        corner.step = 20;
        corner.font = 15;
        corner.placedCells = new float[][] {{40, 40}};
        before = cells(corner);
        int[][] cornerBoxes = {{55, 0, 100, 52}, {0, 55, 52, 100}};
        ok(
                NearbyTextLayout.fitProtected(corner, cornerBoxes),
                "two protections may require a combined x/y translation");
        translated(corner, before, -7, -7);
        avoids(corner, cornerBoxes);
        inside(corner, 1);
        NearbyTextLayout.Plan crowded =
                NearbyTextLayout.forCrop(
                        1000,
                        1000,
                        new int[] {10, 10, 990, 990},
                        0,
                        0,
                        true,
                        "字".repeat(1000),
                        null);
        before = cells(crowded);
        ok(
                !NearbyTextLayout.fitProtected(
                        crowded,
                        new int[][] {
                            {0, 0, 1000, 1000},
                            {0, 0, 1000, 1000},
                            {0, 0, 1000, 1000},
                            {0, 0, 1000, 1000},
                            {0, 0, 1000, 1000}
                        }),
                "dense text/protection combinations use the bounded refusal path");
        translated(crowded, before, 0, 0);
    }

    static void compactAndIndependent() throws Exception {
        String text = "前文还在继续嗯……后文";
        int dots = text.indexOf('…');
        boolean[] mask = rectangle(180, 240, 5, 5, 175, 235);
        for (boolean vertical : new boolean[] {true, false}) {
            BubbleLayout.Plan b =
                    BubbleLayout.plan(
                            text, mask, 180, 240, vertical, 38, new int[][] {{30, 40, 70, 180}});
            inside(b, mask, 180, 240, text.length());
            for (int i = dots; i < dots + 2; i++) {
                int[] a = b.cells[i - 1], c = b.cells[i];
                ok(
                        vertical
                                ? Math.abs(a[0] + a[2] - c[0] - c[2]) <= 1 && c[1] >= a[3]
                                : Math.abs(a[1] + a[3] - c[1] - c[3]) <= 1 && c[0] >= a[2],
                        "bubble ellipsis remains with the preceding character in geometric reading"
                                + " order");
                ok(
                        b.cellFont(i) < b.font,
                        "ellipsis is a compact mark instead of a full body cell");
            }
            NearbyTextLayout.Plan n =
                    NearbyTextLayout.forCrop(
                            200,
                            260,
                            new int[] {5, 5, 195, 255},
                            0,
                            0,
                            vertical,
                            text,
                            new int[][] {{30, 40, 70, 180}});
            inside(n, text.length());
            for (int i = dots; i < dots + 2; i++) {
                float ax = n.cellLeft(i - 1) + n.cellStep(i - 1) / 2,
                        ay = n.cellTop(i - 1) + n.cellStep(i - 1) / 2,
                        cx = n.cellLeft(i) + n.cellStep(i) / 2,
                        cy = n.cellTop(i) + n.cellStep(i) / 2;
                ok(
                        vertical
                                ? Math.abs(ax - cx) < .001 && cy > ay
                                : Math.abs(ay - cy) < .001 && cx > ax,
                        "fallback ellipsis never occupies an independent line");
            }
        }
        boolean[] upper = rectangle(220, 360, 15, 10, 100, 130),
                lower = rectangle(220, 360, 50, 180, 210, 350);
        for (int i = 0; i < upper.length; i++) upper[i] |= lower[i];
        BubbleLayout.Plan p =
                BubbleLayout.plan(
                        "上泡正文。\n下。",
                        upper,
                        220,
                        360,
                        true,
                        30,
                        new int[][] {{25, 25, 45, 110}, {110, 210, 160, 320}});
        inside(p, upper, 220, 360, 7);
        for (int i = 0; i < 5; i++)
            ok(
                    p.cells[i][3] <= 130,
                    "explicit translated paragraph belongs wholly to the first source bubble");
        for (int i = 5; i < 7; i++)
            ok(
                    p.cells[i][1] >= 180,
                    "second translated paragraph belongs wholly to the second source bubble");
        ok(
                p.cellFont(5) > p.cellFont(0) * 1.5,
                "each source bubble retains its independent source scale");
    }

    static void standaloneMarks() throws Exception {
        boolean[] mask = rectangle(150, 260, 5, 5, 145, 255);
        int[][] source = {{40, 40, 80, 190}};
        for (String text : new String[] {"！？", "——！", "……"})
            for (boolean vertical : new boolean[] {true, false}) {
                BubbleLayout.Plan b = BubbleLayout.plan(text, mask, 150, 260, vertical, 40, source);
                inside(b, mask, 150, 260, text.length());
                for (int i = 0; i < b.cells.length; i++)
                    ok(
                            b.cellFont(i) == b.font && b.cellStep(i) == b.font,
                            "a standalone mark paragraph preserves the full source-driven scale");
                NearbyTextLayout.Plan n =
                        NearbyTextLayout.forCrop(
                                150, 260, new int[] {5, 5, 145, 255}, 0, 0, vertical, text, source);
                inside(n, text.length());
                for (int i = 0; i < n.points.length; i++)
                    ok(
                            n.cellFont(i) == n.font && n.cellStep(i) == n.step,
                            "fallback standalone marks retain their common source scale");
            }
        BubbleLayout.Plan mixed = BubbleLayout.plan("字！？", mask, 150, 260, true, 40, source);
        ok(
                mixed.cellFont(1) == mixed.font * .5f && mixed.cellFont(2) == mixed.font * .5f,
                "mixed body text keeps the established compact punctuation policy");
        NearbyTextLayout.Plan nearby =
                NearbyTextLayout.forCrop(
                        150, 260, new int[] {5, 5, 145, 255}, 0, 0, true, "字！？", source);
        ok(
                nearby.cellFont(1) == nearby.font * .5f && nearby.cellFont(2) == nearby.font * .5f,
                "fallback mixed body text keeps compact trailing marks");
    }

    public static void main(String[] args) throws Exception {
        protectedPlacement();
        compactAndIndependent();
        standaloneMarks();
        boolean[] balloon = rectangle(220, 460, 5, 5, 215, 455);
        BubbleLayout.Plan large =
                BubbleLayout.plan(
                        "啊！", balloon, 220, 460, true, 90, new int[][] {{60, 40, 150, 360}});
        inside(large, balloon, 220, 460, 2);
        ok(large.font >= 85, "large original exclamation no longer capped at 36 px");
        BubbleLayout.Plan aside =
                BubbleLayout.plan(
                        "好", balloon, 220, 460, true, 18, new int[][] {{70, 70, 88, 250}});
        inside(aside, balloon, 220, 460, 1);
        ok(
                aside.font >= 18 && aside.font <= 27 && large.font > aside.font * 3,
                "small original aside only grows moderately despite a large empty balloon");
        boolean[] split = rectangle(120, 260, 4, 4, 116, 256);
        int[][] anchors = {{92, 10, 112, 100}, {10, 145, 30, 240}};
        BubbleLayout.Plan divided =
                BubbleLayout.plan("这段译文需要清晰地放进气泡里面", split, 120, 260, true, 30, anchors);
        BubbleLayout.Plan whole =
                BubbleLayout.plan("这段译文需要清晰地放进气泡里面", split, 120, 260, true, 30, null);
        inside(divided, split, 120, 260, divided.codepoints.length);
        ok(
                divided.font >= whole.font * .8f,
                "source lobes cannot force tiny type when a substantially larger safe placement"
                        + " fits");
        boolean[] protectedBubble = rectangle(160, 200, 4, 4, 156, 196);
        for (int y = 20; y < 140; y++)
            for (int x = 100; x < 150; x++) protectedBubble[y * 160 + x] = false;
        BubbleLayout.Plan protectedPlan =
                BubbleLayout.plan(
                        "完整译文不会穿过受保护的邻段",
                        protectedBubble,
                        160,
                        200,
                        true,
                        34,
                        new int[][] {{30, 30, 64, 150}});
        inside(protectedPlan, protectedBubble, 160, 200, protectedPlan.codepoints.length);
        boolean[] stacked = rectangle(240, 380, 20, 10, 120, 140);
        boolean[] lower = rectangle(240, 380, 80, 230, 200, 360);
        for (int i = 0; i < stacked.length; i++) stacked[i] |= lower[i];
        BubbleLayout.Plan stackedPlan =
                BubbleLayout.plan(
                        "上面先读下面后读",
                        stacked,
                        240,
                        380,
                        true,
                        25,
                        new int[][] {{100, 240, 125, 320}, {40, 30, 65, 100}});
        inside(stackedPlan, stacked, 240, 380, 8);
        ok(
                stackedPlan.cells[0][1] < 140 && stackedPlan.cells[7][1] >= 230,
                "vertically separated lobes read top first even when the lower one is farther right"
                        + " and arrives first");
        boolean[] side = rectangle(330, 210, 10, 10, 110, 180),
                rightSide = rectangle(330, 210, 210, 40, 310, 190);
        for (int i = 0; i < side.length; i++) side[i] |= rightSide[i];
        BubbleLayout.Plan sidePlan =
                BubbleLayout.plan(
                        "右边先读左边后读",
                        side,
                        330,
                        210,
                        true,
                        25,
                        new int[][] {{30, 20, 55, 130}, {240, 60, 265, 150}});
        inside(sidePlan, side, 330, 210, 8);
        ok(
                sidePlan.cells[0][0] >= 210 && sidePlan.cells[7][0] < 110,
                "overlapping vertical bands retain right-to-left reading order");
        try {
            BubbleLayout.plan("字".repeat(1000), split, 120, 260, true, 30, anchors);
            throw new AssertionError("illegibly dense balloon accepted");
        } catch (Exception expected) {
            checks++;
        }
        int[] sfxBox = {937, 1058, 1032, 1303};
        int[][] sfxLines = {{932, 1055, 1037, 1308}};
        NearbyTextLayout.Plan largeRed =
                NearbyTextLayout.forCrop(1061, 1500, sfxBox, 0, 0, true, "啊啊啊！", sfxLines);
        inside(largeRed, 4);
        ok(
                largeRed.font >= 40,
                "actual P15 fallback fits all four glyphs at original target scale instead of"
                        + " page-width 25 px");
        NearbyTextLayout.Plan crop =
                NearbyTextLayout.forCrop(1061, 1500, sfxBox, 900, 1000, true, "啊啊啊！", sfxLines);
        ok(
                Math.abs(crop.font - largeRed.font) < .001f && crop.vertical,
                "repair and red fallback share exact original font scale");
        for (int i = 0; i < 4; i++)
            ok(
                    Math.abs(crop.cellLeft(i) + 900 - largeRed.cellLeft(i)) < .001f
                            && Math.abs(crop.cellTop(i) + 1000 - largeRed.cellTop(i)) < .001f,
                    "crop changes only coordinates");
        NearbyTextLayout.Plan modest =
                NearbyTextLayout.forCrop(
                        1061,
                        1500,
                        new int[] {100, 100, 300, 500},
                        0,
                        0,
                        true,
                        "好",
                        new int[][] {{130, 160, 150, 320}});
        inside(modest, 1);
        ok(
                modest.font <= 30,
                "ordinary small source is not indiscriminately enlarged to fill its anchor");
        String mixedText = "不是♥ 不要，太厉害了！嗯啊！嗯啊！啊♥啊♥啊♥";
        int[][] mixedLines = {
            {228, 934, 262, 1054},
            {155, 995, 185, 1142},
            {125, 995, 155, 1142},
            {29, 1081, 143, 1410}
        };
        NearbyTextLayout.Plan mixed =
                NearbyTextLayout.forCrop(
                        1061,
                        1500,
                        new int[] {34, 929, 266, 1408},
                        0,
                        0,
                        true,
                        mixedText,
                        mixedLines);
        inside(mixed, 23);
        ok(
                mixed.sourceGrouped,
                "mixed source lettering and repeated suffix activate anchored styles");
        ok(
                Arrays.equals(
                        mixed.points, mixedText.replaceAll("\\s+", "").codePoints().toArray()),
                "mixed styles retain exact text and reading sequence");
        ok(
                mixed.cellLeft(0) >= 228
                        && mixed.cellLeft(3) >= 155
                        && mixed.cellLeft(3) < 185
                        && mixed.cellLeft(11) >= 125
                        && mixed.cellLeft(11) < 155
                        && mixed.cellLeft(17) < 125,
                "explicit text break preserves headline, two normal source columns and final large"
                        + " utterance positions");
        ok(
                mixed.cellFont(17) > mixed.cellFont(3) * 2 && mixed.cellFont(17) >= 50,
                "exact repeated ending uses display type without absorbing earlier normal"
                        + " utterances");
        ok(
                mixed.cellFont(18) < mixed.cellFont(17) * .5f && mixed.points[18] == '♥',
                "trailing hearts retain their codepoints as smaller marks after each large letter");
        for (int i = 0; i < mixed.points.length; i++)
            for (int j = i + 1; j < mixed.points.length; j++)
                ok(
                        mixed.cellLeft(i) + mixed.cellStep(i) <= mixed.cellLeft(j) + .001f
                                || mixed.cellLeft(j) + mixed.cellStep(j)
                                        <= mixed.cellLeft(i) + .001f
                                || mixed.cellTop(i) + mixed.cellStep(i) <= mixed.cellTop(j) + .001f
                                || mixed.cellTop(j) + mixed.cellStep(j) <= mixed.cellTop(i) + .001f,
                        "source groups never overlap each other's glyph cells");
        NearbyTextLayout.Plan mixedCrop =
                NearbyTextLayout.forCrop(
                        1061,
                        1500,
                        new int[] {34, 929, 266, 1408},
                        10,
                        900,
                        true,
                        mixedText,
                        mixedLines);
        for (int i = 0; i < mixed.points.length; i++)
            ok(
                    Math.abs(mixedCrop.cellLeft(i) + 10 - mixed.cellLeft(i)) < .001f
                            && Math.abs(mixedCrop.cellTop(i) + 900 - mixed.cellTop(i)) < .001f
                            && mixedCrop.cellFont(i) == mixed.cellFont(i),
                    "mixed-style crop preserves source positions and individual fonts");
        for (boolean vertical : new boolean[] {false, true})
            for (int n : new int[] {1, 2, 13, 50, 300}) {
                String text = "字".repeat(n - 1) + "𠮷";
                NearbyTextLayout.Plan p =
                        NearbyTextLayout.forCrop(
                                1061,
                                1500,
                                new int[] {10, 20, 110, 235},
                                0,
                                0,
                                vertical,
                                text,
                                new int[][] {{40, 40, 72, 210}});
                inside(p, n);
                ok(
                        p.vertical == vertical && p.points[n - 1] == 0x20bb7,
                        "direction and supplementary Unicode retained");
                for (int i = 1; i < n; i++)
                    if (vertical) {
                        if (i % p.rows() != 0)
                            ok(
                                    p.cellTop(i) > p.cellTop(i - 1)
                                            && p.cellLeft(i) == p.cellLeft(i - 1),
                                    "vertical advances downward");
                        else
                            ok(
                                    p.cellLeft(i) < p.cellLeft(i - 1),
                                    "vertical advances right to left");
                    } else {
                        if (i % p.columns != 0)
                            ok(
                                    p.cellLeft(i) > p.cellLeft(i - 1)
                                            && p.cellTop(i) == p.cellTop(i - 1),
                                    "horizontal advances rightward");
                        else ok(p.cellTop(i) > p.cellTop(i - 1), "horizontal advances downward");
                    }
            }
        System.out.println(
                "AdaptiveTypographyChecks: "
                        + checks
                        + " checks passed; large bubble="
                        + large.font
                        + ", large fallback="
                        + largeRed.font
                        + ", small aside="
                        + aside.font);
    }
}
