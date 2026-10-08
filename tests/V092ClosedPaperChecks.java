package cn.local.manga;

import org.json.JSONObject;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Arrays;

import javax.imageio.ImageIO;

/** Pixel protection tests for the paragraph-aware production entry point. */
public final class V092ClosedPaperChecks {
    static int checks;

    static void ok(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static boolean paper(WhiteBubbleCleaner.Mask m) {
        return m.whiteBackground
                && m.backgroundKind == WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER;
    }

    static int[] pixels(BufferedImage b) {
        return b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth());
    }

    static BufferedImage image(int[] p, int w, int h) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, w, h, p, 0, w);
        return b;
    }

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]);
        Files.createDirectories(out);
        int w = 350, h = 310, size = 26;
        BufferedImage source = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(5));
        g.drawRect(20, 15, 265, 275);
        // Connected artwork intrudes beside the text and a separate mark is elsewhere in the same
        // balloon.
        g.drawLine(20, 210, 52, 225);
        g.drawLine(52, 225, 58, 219);
        g.fillOval(239, 247, 12, 14);
        g.drawRect(302, 22, 34, 80);
        g.fillRect(315, 43, 7, 31);
        g.dispose();
        int[] background = pixels(source);
        g = source.createGraphics();
        g.setColor(Color.BLACK);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, size));
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        String jp = "こんにちは友";
        for (int column = 0; column < 3; column++)
            for (int row = 0; row < jp.length(); row++)
                g.drawString(jp.substring(row, row + 1), 69 + column * 48, 68 + row * 28);
        g.dispose();
        int[] before = pixels(source);
        int[][] anchors = {{67, 42, 96, 217}};
        int[] paragraph = {65, 40, 195, 220};
        WhiteBubbleCleaner.Mask old = WhiteBubbleCleaner.forText(before, w, h, anchors, size),
                mask = WhiteBubbleCleaner.forText(before, w, h, anchors, size, paragraph);
        ok(
                paper(mask) && mask.evidence.equals("verified_closed_paper_paragraph_recovery"),
                "paragraph recovers a closed bubble from only one of three column anchors");
        int[] after = before.clone();
        WhiteBubbleCleaner.apply(after, mask);
        int target = 0, remaining = 0, protectedChanged = 0, oldRemaining = 0;
        for (int p = 0; p < before.length; p++) {
            boolean glyph = before[p] != background[p] && (before[p] & 255) < 235;
            if (glyph) {
                target++;
                if ((after[p] & 255) < 245) remaining++;
                if (!old.erase[p]) oldRemaining++;
            }
            if (before[p] == background[p] && background[p] != 0xffffffff && after[p] != before[p])
                protectedChanged++;
        }
        ok(
                target > 600 && oldRemaining > 200,
                "fixture exercises genuinely missed Japanese columns");
        ok(remaining == 0, "all three Japanese columns and antialias fringes are removed");
        ok(
                protectedChanged == 0,
                "connected outline, isolated interior art and adjacent balloon stay byte exact");
        int[][] foreign = {{112, 40, 145, 220}};
        ok(
                WhiteBubbleCleaner.touchesForeignInk(before, w, h, mask, foreign),
                "fixture includes a detected foreign column inside the recovered paper");
        boolean[] safeErase = WhiteBubbleCleaner.excludeForeign(mask.erase, w, h, foreign),
                safeLayout = WhiteBubbleCleaner.excludeForeign(mask.interior, w, h, foreign);
        int foreignChanges = 0, foreignLayout = 0, ownRecovery = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = y * w + x;
                if (x >= 110 && x < 147 && y >= 38 && y < 222) {
                    if (safeErase[p]) foreignChanges++;
                    if (safeLayout[p]) foreignLayout++;
                } else if (safeErase[p] && before[p] != background[p]) ownRecovery++;
            }
        ok(
                foreignChanges == 0 && foreignLayout == 0 && ownRecovery > 200,
                "existing foreign paragraph guard excludes both erase and layout after recovery");
        int[] colored = before.clone();
        for (int p = 0; p < colored.length; p++)
            if (colored[p] == 0xffffffff) colored[p] = 0xffd0b080;
        WhiteBubbleCleaner.Mask color =
                WhiteBubbleCleaner.forText(colored, w, h, anchors, size, paragraph);
        ok(!paper(color), "colored paper never becomes a new white fill");
        int cx = 40, cy = 28, cw = 170, ch = 211;
        int[] crop = source.getRGB(cx, cy, cw, ch, null, 0, cw);
        WhiteBubbleCleaner.Mask open =
                WhiteBubbleCleaner.forText(
                        crop,
                        cw,
                        ch,
                        new int[][] {{27, 14, 56, 189}},
                        size,
                        new int[] {25, 12, 155, 192});
        ok(
                !open.evidence.equals("verified_closed_paper_paragraph_recovery"),
                "missing balloon boundary cannot be invented from paragraph coordinates");
        int beyond = 0;
        for (int y = 0; y < ch; y++)
            for (int x = 92; x < cw; x++) if (open.erase[y * cw + x]) beyond++;
        ok(
                beyond == 0,
                "unclosed ROI does not use paragraph recovery to erase distant unanchored ink");
        int[][] complete = {{64, 39, 197, 222}};
        WhiteBubbleCleaner.Mask
                completeOld = WhiteBubbleCleaner.forText(before, w, h, complete, 135),
                completeNew = WhiteBubbleCleaner.forText(before, w, h, complete, 135, paragraph);
        ok(
                completeOld.backgroundKind == completeNew.backgroundKind
                        && Arrays.equals(completeOld.erase, completeNew.erase)
                        && Arrays.equals(completeOld.interior, completeNew.interior),
                "fully anchored large glyph/art retains the legacy mask and background verdict");
        ImageIO.write(source, "png", out.resolve("合成三列原图.png").toFile());
        ImageIO.write(image(after, w, h), "png", out.resolve("合成三列闭域去字.png").toFile());
        JSONObject report =
                new JSONObject()
                        .put("checksPassed", checks)
                        .put("targetInkPixels", target)
                        .put("remainingTargetInkPixels", remaining)
                        .put("changedProtectedPixels", protectedChanged)
                        .put("foreignErasePixels", foreignChanges)
                        .put("foreignLayoutPixels", foreignLayout)
                        .put("apiCalls", 0)
                        .put("androidCanvasVerified", false);
        Files.writeString(out.resolve("合成保护验证.json"), report.toString(2));
        System.out.println("CLOSED PAPER CHECKS " + report);
    }
}
