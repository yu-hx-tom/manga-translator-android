package cn.local.manga;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;

public class ConnectedFrameInkChecks {
    static int cases;

    static void ok(boolean b, String message) {
        if (!b) throw new AssertionError(message);
    }

    static void fixture(int thickness, boolean broad, boolean open, boolean color, int anchorMode) {
        int w = 260, h = 240;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color ? new Color(0xffd0b080) : Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(thickness));
        g.drawPolygon(
                new int[] {30, 170, 185, 180, 160, 35, 25},
                new int[] {25, 20, 45, 195, 214, 196, 115},
                7);
        // Exterior scribble belongs to the protected background and connects to the same frame.
        g.drawLine(183, 90, 235, 70);
        g.drawLine(220, 55, 236, 105);
        g.drawLine(205, 60, 244, 108);
        if (open) {
            g.setColor(color ? new Color(0xffd0b080) : Color.WHITE);
            g.fillRect(20, 112, 25, 18);
            g.setColor(Color.BLACK);
        }
        int[] background = image.getRGB(0, 0, w, h, null, 0, w);
        g.fillRect(65, 60, 18, 112);
        g.fillRect(135, 60, 18, 112);
        g.fillRect(80, 102, 63, 20);
        g.fillRect(149, 87, 38, 6);
        g.fillRect(108, 154, 4, 4);
        g.dispose();
        int[] src = image.getRGB(0, 0, w, h, null, 0, w), snap = src.clone();
        int[][] lines =
                anchorMode == 1
                        ? new int[0][]
                        : anchorMode == 2
                                ? new int[][] {{64, 59, 85, 174}}
                                : new int[][] {{62, 58, broad ? 248 : 166, 174}};
        WhiteBubbleCleaner.Mask m =
                WhiteBubbleCleaner.forText(
                        src, w, h, lines, 104, new int[] {62, 58, broad ? 248 : 166, 174});
        boolean[] erase =
                WhiteBubbleCleaner.rectangleMask(
                        m, w, h, new int[] {62, 58, broad ? 248 : 166, 174}, new int[0][]);
        int target = 0, recovered = 0, damage = 0;
        for (int i = 0; i < src.length; i++) {
            if (src[i] != background[i]) {
                target++;
                if (erase[i]) recovered++;
            } else if (background[i] != (color ? 0xffd0b080 : 0xffffffff) && erase[i]) damage++;
        }
        ok(Arrays.equals(src, snap), "immutable source");
        if (damage > 0)
            for (int i = 0; i < src.length; i++)
                if (src[i] == background[i]
                        && background[i] != (color ? 0xffd0b080 : 0xffffffff)
                        && erase[i])
                    System.out.println(
                            "SYNTHETIC damaged x="
                                    + (i % w)
                                    + " y="
                                    + (i / w)
                                    + " evidence="
                                    + m.evidence);
        ok(
                damage == 0,
                "frame or exterior scribble damage: thickness="
                        + thickness
                        + " broad="
                        + broad
                        + " damage="
                        + damage);
        if (!open && !color) {
            ok(recovered > target * .65, "insufficient inner recovery " + recovered + "/" + target);
            ok(erase[155 * w + 109], "missed detached punctuation recovered");
        } else
            ok(
                    !m.evidence.equals("closed_paper_inner_ink_with_frame_guard"),
                    "open/color paper must not invent a closed white balloon");
        if (!open && !color) {
            boolean[] protectedMask =
                    WhiteBubbleCleaner.rectangleMask(
                            m,
                            w,
                            h,
                            new int[] {62, 58, 166, 174},
                            new int[][] {{130, 55, 155, 178}});
            for (int y = 53; y < 180; y++)
                for (int x = 128; x < 157; x++)
                    ok(!protectedMask[y * w + x], "neighbor paragraph protection");
        }
        cases++;
        System.out.println(
                "fixture thickness="
                        + thickness
                        + " anchorMode="
                        + anchorMode
                        + " broad="
                        + broad
                        + " open="
                        + open
                        + " color="
                        + color
                        + " ink="
                        + target
                        + " recovered="
                        + recovered
                        + " protectedDamage="
                        + damage);
    }

    public static void main(String[] args) {
        for (int t : new int[] {3, 7, 12}) {
            fixture(t, false, false, false, 0);
            fixture(t, true, false, false, 0);
            fixture(t, false, false, false, 1);
            fixture(t, false, false, false, 2);
        }
        fixture(5, false, true, false, 0);
        fixture(5, false, false, true, 0);
        fixture(5, false, true, false, 1);
        fixture(5, false, false, true, 1);
        System.out.println("ConnectedFrameInkChecks: " + cases + " cases passed");
    }
}
