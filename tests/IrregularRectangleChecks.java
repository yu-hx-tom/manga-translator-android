package cn.local.manga;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Arrays;

import javax.imageio.ImageIO;

/** Curved/concave balloon with original ink extending past a detector paragraph edge. */
public class IrregularRectangleChecks {
    static int checks;

    static void ok(boolean b, String name) {
        checks++;
        if (!b) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        int w = 240, h = 240;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(4));
        g.drawPolygon(
                new int[] {25, 130, 212, 194, 216, 170, 150, 72, 28, 40},
                new int[] {30, 17, 40, 103, 171, 208, 184, 218, 171, 97},
                10);
        int[] background = image.getRGB(0, 0, w, h, null, 0, w);
        g.fillRect(112, 60, 10, 115);
        g.fillRect(128, 65, 12, 105);
        g.fillRect(145, 66, 3, 5);
        g.dispose();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w), snapshot = pixels.clone();
        int[] box = {112, 60, 136, 175};
        WhiteBubbleCleaner.Mask m =
                WhiteBubbleCleaner.forText(
                        pixels, w, h, new int[][] {{111, 59, 141, 176}}, 30, box);
        ok(
                m.whiteBackground
                        && m.backgroundKind == WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER,
                "irregular balloon proven");
        boolean[] erase = WhiteBubbleCleaner.rectangleMask(m, w, h, box, new int[0][]);
        int recovered = 0, missed = 0, boundary = 0, changedOutside = 0;
        int[] cleaned = pixels.clone();
        for (int i = 0; i < pixels.length; i++) {
            if (erase[i]) {
                cleaned[i] = m.fillColors == null ? 0xffffffff : m.fillColors[i];
                if (!m.interior[i]) changedOutside++;
                if (background[i] != 0xffffffff) boundary++;
            }
            if (pixels[i] != background[i]) {
                if (erase[i]) recovered++;
                else missed++;
            }
        }
        Path out = Paths.get(args[0]);
        Files.createDirectories(out);
        ImageIO.write(image, "png", out.resolve("异形框原图.png").toFile());
        image.setRGB(0, 0, w, h, cleaned, 0, w);
        ImageIO.write(image, "png", out.resolve("异形框清字.png").toFile());
        ok(
                recovered > 2000 && missed == 0,
                "full glyph and ruby recovered beyond paragraph rectangle");
        ok(boundary == 0 && changedOutside == 0, "outline and exterior unchanged");
        ok(Arrays.equals(snapshot, pixels), "input immutable");
        boolean[] protectedMask =
                WhiteBubbleCleaner.rectangleMask(m, w, h, box, new int[][] {{127, 64, 141, 172}});
        boolean protectedInk = true;
        for (int y = 62; y < 174; y++)
            for (int x = 125; x < 143; x++) protectedInk &= !protectedMask[y * w + x];
        ok(protectedInk, "foreign paragraph exclusion after glyph union");
        WhiteBubbleCleaner.Mask refused =
                new WhiteBubbleCleaner.Mask(
                        m.erase,
                        m.interior,
                        false,
                        0,
                        null,
                        true,
                        null,
                        WhiteBubbleCleaner.BackgroundKind.TEXTURED_ART,
                        "fixture");
        boolean clear = true;
        for (boolean b : WhiteBubbleCleaner.rectangleMask(refused, w, h, box, new int[0][]))
            clear &= !b;
        ok(clear, "art remains refused even with a candidate erase mask");
        System.out.println("IrregularRectangleChecks: " + checks + " checks passed");
    }
}
