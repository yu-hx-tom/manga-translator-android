package cn.local.manga;

import java.util.Arrays;

/** Synthetic masks only: no manga files or network access. */
public final class BubbleFontSizeChecks {
    static int checks;

    static void ok(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static boolean[] rectangle(int w, int h, int l, int t, int r, int b) {
        boolean[] mask = new boolean[w * h];
        for (int y = t; y < b; y++) Arrays.fill(mask, y * w + l, y * w + r, true);
        return mask;
    }

    static BubbleLayout.Plan plan(
            boolean[] mask,
            int w,
            int h,
            boolean vertical,
            int estimate,
            int[][] seeds,
            String text,
            float scale)
            throws Exception {
        BubbleLayout.Plan p = BubbleLayout.plan(text, mask, w, h, vertical, estimate, seeds, scale);
        String compact = text.replaceAll("\\s+", "");
        ok(p.cells.length == compact.codePointCount(0, compact.length()), "complete translation");
        for (int[] cell : p.cells) {
            ok(cell[0] >= 0 && cell[1] >= 0 && cell[2] <= w && cell[3] <= h, "crop bounds");
            for (int y = cell[1]; y < cell[3]; y++)
                for (int x = cell[0]; x < cell[2]; x++)
                    if (!mask[y * w + x]) throw new AssertionError("outside safe mask");
        }
        return p;
    }

    public static void main(String[] args) throws Exception {
        String text = "这里的对话应该清楚易读";
        boolean[] large = rectangle(320, 360, 10, 10, 310, 350);
        int[][] seeds = {{144, 100, 160, 260}};
        for (boolean vertical : new boolean[] {true, false}) {
            BubbleLayout.Plan p = plan(large, 320, 360, vertical, 16, seeds, text, 1);
            System.out.println(
                    (vertical ? "vertical" : "horizontal") + " large bubble font=" + p.font);
            ok(p.font >= 40 && p.font <= 65, "large bubble must not stay at tiny detected size");
            BubbleLayout.Plan shortText = plan(large, 320, 360, vertical, 16, seeds, "好", 1);
            ok(
                    shortText.font >= p.font && shortText.font <= 75,
                    "short reply readable with whitespace");
            BubbleLayout.Plan longer =
                    plan(large, 320, 360, vertical, 16, seeds, text.repeat(5), 1);
            ok(longer.font < p.font, "long translation shrinks to fit");
            BubbleLayout.Plan smaller = plan(large, 320, 360, vertical, 16, seeds, text, .75f);
            BubbleLayout.Plan bigger = plan(large, 320, 360, vertical, 16, seeds, text, 1.25f);
            ok(
                    smaller.font < p.font && bigger.font > p.font,
                    "editor scale applies after adaptation");
            boolean[] doubled = rectangle(640, 720, 20, 20, 620, 700);
            BubbleLayout.Plan highRes =
                    plan(
                            doubled,
                            640,
                            720,
                            vertical,
                            32,
                            new int[][] {{288, 200, 320, 520}},
                            text,
                            1);
            ok(
                    Math.abs(highRes.font - p.font * 2) <= 2,
                    "resolution scales lettering proportionally");
        }
        boolean[] narrow = rectangle(100, 300, 10, 10, 90, 290);
        plan(narrow, 100, 300, true, 12, new int[][] {{44, 60, 56, 220}}, text, 1);
        boolean[] small = rectangle(600, 400, 10, 10, 90, 150);
        int[][] smallSeed = {{42, 35, 54, 120}};
        BubbleLayout.Plan alone = plan(small, 600, 400, true, 12, smallSeed, text, 1);
        boolean[] disconnected = small.clone();
        for (int y = 10; y < 390; y++)
            Arrays.fill(disconnected, y * 600 + 180, y * 600 + 590, true);
        BubbleLayout.Plan distant = plan(disconnected, 600, 400, true, 12, smallSeed, text, 1);
        ok(alone.font == distant.font, "unrelated empty region must not inflate font");
        boolean[] oval = new boolean[320 * 360];
        for (int y = 0; y < 360; y++)
            for (int x = 0; x < 320; x++)
                oval[y * 320 + x] =
                        Math.pow((x - 160) / 145.0, 2) + Math.pow((y - 180) / 165.0, 2) < 1;
        BubbleLayout.Plan curved = plan(oval, 320, 360, true, 16, seeds, text, 1);
        ok(curved.font >= 32, "curved bubble also uses available space");
        boolean[] protectedMask = large.clone();
        for (int y = 60; y < 300; y++)
            Arrays.fill(protectedMask, y * 320 + 210, y * 320 + 290, false);
        BubbleLayout.Plan protectedPlan = plan(protectedMask, 320, 360, true, 16, seeds, text, 1);
        ok(
                !BubbleLayout.touchesForeignLines(protectedPlan, new int[][] {{210, 60, 290, 300}}),
                "larger font respects adjacent paragraph");
        boolean[] lobes = rectangle(360, 420, 10, 10, 110, 150);
        for (int y = 210; y < 410; y++) Arrays.fill(lobes, y * 360 + 130, y * 360 + 350, true);
        BubbleLayout.Plan split =
                plan(
                        lobes,
                        360,
                        420,
                        true,
                        12,
                        new int[][] {{45, 30, 57, 130}, {234, 235, 246, 385}},
                        "上段文字\n下段文字",
                        1);
        ok(split.cellFont(4) > split.cellFont(0), "each bubble uses its own space estimate");
        ok(split.cells[0][1] < 150 && split.cells[7][1] >= 210, "split bubble reading order");
        System.out.println("BubbleFontSizeChecks: " + checks + " checks passed");
    }
}
