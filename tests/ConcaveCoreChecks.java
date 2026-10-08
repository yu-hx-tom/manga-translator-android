package cn.local.manga;

import java.awt.*;
import java.awt.image.BufferedImage;

public class ConcaveCoreChecks {
    public static void main(String[] args) {
        int w = 220, h = 230;
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(4));
        g.drawPolygon(
                new int[] {30, 175, 175, 30, 30, 99, 99, 30},
                new int[] {20, 20, 205, 205, 125, 125, 90, 90},
                8);
        int[] bg = b.getRGB(0, 0, w, h, null, 0, w);
        g.fillRect(105, 50, 60, 125);
        g.dispose();
        int[] p = b.getRGB(0, 0, w, h, null, 0, w);
        int[][] lines = {{55, 49, 166, 176}};
        int[] box = {55, 49, 166, 176};
        WhiteBubbleCleaner.Mask m = WhiteBubbleCleaner.forText(p, w, h, lines, 111, box);
        if (!m.whiteBackground || m.backgroundKind != WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER)
            throw new AssertionError("concave boundary crossing rectangular text core refused");
        boolean[] erase = WhiteBubbleCleaner.rectangleMask(m, w, h, box, new int[0][]);
        int missed = 0, damaged = 0;
        for (int i = 0; i < p.length; i++) {
            if (p[i] != bg[i] && !erase[i]) missed++;
            if (bg[i] != 0xffffffff && erase[i]) damaged++;
        }
        if (missed != 0 || damaged != 0)
            throw new AssertionError("missed=" + missed + " damaged=" + damaged);
        System.out.println(
                "ConcaveCoreChecks: passed, residual=" + missed + ", outlineDamage=" + damaged);
    }
}
