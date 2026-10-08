package cn.local.manga;

import java.util.Arrays;

public class TargetedBubbleChecks {
    static int checks;

    static void ok(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    static void rect(int[] p, int w, int x0, int y0, int x1, int y1, int c) {
        for (int y = y0; y < y1; y++) Arrays.fill(p, y * w + x0, y * w + x1, c);
    }

    static void glyph(int[] p, int w, int x, int y) {
        rect(p, w, x, y, x + 5, y + 28, 0xff000000);
        rect(p, w, x, y + 22, x + 22, y + 28, 0xff000000);
    }

    static void grayBridge() {
        int w = 250, h = 260;
        int[] p = new int[w * h];
        Arrays.fill(p, 0xffffffff);
        rect(p, w, 30, 30, 205, 230, 0xff000000);
        rect(p, w, 33, 33, 202, 227, 0xffffffff);
        rect(p, w, 202, 80, 205, 140, 0xffe6e6e6);
        rect(p, w, 89, 96, 203, 97, 0xffe6e6e6);
        glyph(p, w, 85, 80);
        rect(p, w, 216, 70, 240, 185, 0xff000000);
        int[][] lines = {{78, 65, 124, 150}, {212, 65, 244, 190}};
        int[] box = {72, 60, 245, 195};
        WhiteBubbleCleaner.Mask m = WhiteBubbleCleaner.forText(p, w, h, lines, 32, box);
        boolean[] e = WhiteBubbleCleaner.rectangleMask(m, p, w, h, box, lines, new int[0][]);
        ok(
                e[85 * w + 87],
                "glyph separated from the outline by gray compression bridge is cleaned");
        ok(!e[100 * w + 203] && !e[96 * w + 170], "light gray outline and distant bridge retained");
        int damage = 0;
        for (int y = 70; y < 185; y++) for (int x = 216; x < 240; x++) if (e[y * w + x]) damage++;
        ok(damage == 0, "solid artwork/redaction block retained");
        for (int y = 30; y < 230; y++) ok(!e[y * w + 30], "outer frame retained");
    }

    static void texture() {
        int w = 240, h = 260;
        int[] p = new int[w * h];
        Arrays.fill(p, 0xff606060);
        rect(p, w, 30, 25, 210, 235, 0xff000000);
        for (int y = 28; y < 232; y++)
            for (int x = 33; x < 207; x++) {
                int v = (x / 7 + y / 9) % 2 == 0 ? 185 : 230;
                p[y * w + x] = 0xff000000 | (v << 16) | (v << 8) | v;
            }
        for (int x : new int[] {80, 145})
            for (int y : new int[] {60, 115, 170}) {
                rect(p, w, x - 2, y - 2, x + 24, y + 30, 0xffffffff);
                glyph(p, w, x, y);
            }
        rect(p, w, 121, 105, 124, 108, 0xff000000);
        int[][] lines = {{75, 53, 107, 205}};
        int[] box = {70, 48, 174, 207};
        WhiteBubbleCleaner.Mask m = WhiteBubbleCleaner.forText(p, w, h, lines, 32, box);
        boolean[] e = WhiteBubbleCleaner.rectangleMask(m, p, w, h, box, lines, new int[0][]);
        ok(
                e[64 * w + 82] && e[64 * w + 147],
                "both anchored and missing-column texture glyphs cleaned");
        ok(
                m.fillColors != null && (m.fillColors[64 * w + 147] & 255) < 250,
                "texture glyph uses paper samples, not white paint");
        ok(!e[106 * w + 122], "tiny texture speck is not promoted to a glyph");
        ok(!e[120 * w + 35] && !e[25 * w + 100], "unrelated texture and border retained");
        int untouched = 0;
        for (int y = 30; y < 220; y++)
            for (int x = 180; x < 200; x++) if (e[y * w + x]) untouched++;
        ok(untouched == 0, "remote original texture remains byte-for-byte outside erase mask");
    }

    public static void main(String[] args) {
        grayBridge();
        texture();
        System.out.println("TargetedBubbleChecks: " + checks + " assertions passed");
    }
}
