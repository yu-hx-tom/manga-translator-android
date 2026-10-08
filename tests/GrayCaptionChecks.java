package cn.local.manga;

import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

public final class GrayCaptionChecks {
    static int checks;
    static boolean rtEntry;

    static WhiteBubbleCleaner.Mask find(int[] pixels, int w, int h, int[][] lines, int size) {
        return rtEntry
                ? WhiteBubbleCleaner.findPreferWhitePaper(pixels, w, h, lines, size)
                : WhiteBubbleCleaner.find(pixels, w, h, lines, size);
    }

    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        rtEntry = args.length > 1 && args[1].equals("rt");
        int w = 180, h = 220;
        int[] background = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int v = x >= 22 && x < 158 && y >= 22 && y < 198 ? 180 + (y - 22) / 5 : 120;
                background[y * w + x] = 0xff000000 | (v << 16) | (v << 8) | v;
            }
        for (int y = 20; y < 200; y++)
            for (int x = 20; x < 160; x++)
                if (x < 22 || x >= 158 || y < 22 || y >= 198) background[y * w + x] = 0xff000000;
        // A colored annotation is not monochrome text and must remain untouched.
        for (int y = 30; y < 38; y++)
            for (int x = 130; x < 145; x++) background[y * w + x] = 0xffe43121;
        // Dark patterned artwork inside the same caption, outside detected text, must not be wiped
        // as another glyph.
        for (int y = 65; y < 170; y += 12)
            for (int x = 127; x < 140; x++) background[y * w + x] = 0xff555555;
        int[] original = background.clone();
        for (int y = 55; y < 165; y += 25) {
            for (int yy = y - 2; yy < y + 15; yy++)
                for (int x = 80; x < 96; x++) original[yy * w + x] = 0xffffffff;
            for (int yy = y; yy < y + 13; yy++)
                for (int x = 82; x < 94; x++) original[yy * w + x] = 0xff000000;
        }
        int[][] lines = {{77, 49, 101, 178}};
        WhiteBubbleCleaner.Mask mask = find(original, w, h, lines, 24);
        check(mask.whiteBackground, "closed neutral gradient caption accepted");
        check(mask.fillColors != null, "gray caption must restore original shade");
        int[] cleaned = original.clone();
        WhiteBubbleCleaner.apply(cleaned, mask);
        int residual = 0, protectedChanges = 0, whitePatches = 0, maxShadeError = 0;
        for (int p = 0; p < original.length; p++) {
            if (original[p] != background[p]) {
                if ((cleaned[p] & 255) < 150) residual++;
                if ((cleaned[p] & 255) > 245) whitePatches++;
                maxShadeError =
                        Math.max(
                                maxShadeError,
                                Math.abs((cleaned[p] & 255) - (background[p] & 255)));
            }
            if ((background[p] == 0xff000000
                            || background[p] == 0xffe43121
                            || background[p] == 0xff555555
                            || p % w < 20
                            || p % w >= 160
                            || p / w < 20
                            || p / w >= 200)
                    && cleaned[p] != original[p]) protectedChanges++;
        }
        check(residual == 0, "gray caption Japanese strokes erased");
        check(whitePatches == 0, "white glyph halo is not reused as gray paper");
        check(maxShadeError <= 5, "gradient fill stays close to original paper");
        check(
                protectedChanges == 0,
                "border exterior color annotation and gray-paper artwork unchanged");
        int[] open = original.clone();
        for (int y = 0; y < h; y++) for (int x = 20; x < 23; x++) open[y * w + x] = 0xffbbbbbb;
        check(
                !find(open, w, h, lines, 24).whiteBackground,
                "gray region open to ROI edge must reject");
        int[] textured = original.clone();
        for (int y = 23; y < 197; y++)
            for (int x = 23; x < 157; x++)
                if (original[y * w + x] == background[y * w + x]
                        && background[y * w + x] != 0xff555555
                        && background[y * w + x] != 0xffe43121) {
                    int v = ((x / 7 + y / 9) % 2 == 0) ? 185 : 230;
                    textured[y * w + x] = 0xff000000 | (v << 16) | (v << 8) | v;
                }
        WhiteBubbleCleaner.Mask texturedMask = find(textured, w, h, lines, 24);
        check(
                texturedMask.texturedBackground
                        && !texturedMask.whiteBackground
                        && texturedMask.pixels == 0,
                "patterned translucent caption must choose non-erasing overlay");
        int[] preserved = textured.clone();
        WhiteBubbleCleaner.apply(preserved, texturedMask);
        check(Arrays.equals(textured, preserved), "all underlying patterned pixels preserved");
        int[] beforeRepair = textured.clone();
        WhiteBubbleCleaner.Mask repaired = WhiteBubbleCleaner.forText(textured, w, h, lines, 24);
        check(
                repaired.whiteBackground
                        && repaired.texturedBackground
                        && repaired.fillColors != null,
                "verified outlined glyphs on patterned paper are locally repaired");
        check(Arrays.equals(textured, beforeRepair), "repair does not mutate source pixels");
        int[] restored = textured.clone();
        WhiteBubbleCleaner.apply(restored, repaired);
        int escaped = 0, remainingInk = 0, halo = 0;
        for (int p = 0; p < restored.length; p++) {
            if (restored[p] != textured[p] && !repaired.erase[p]) escaped++;
            if (original[p] != background[p]) {
                if ((restored[p] & 255) < 150) remainingInk++;
                if ((restored[p] & 255) > 245) halo++;
            }
        }
        check(escaped == 0, "restoration stays inside verified original glyph mask");
        check(
                remainingInk == 0 && halo == 0,
                "original dark strokes and white outlines are removed");
        check(
                restored[32 * w + 135] == textured[32 * w + 135]
                        && restored[20 * w + 90] == textured[20 * w + 90],
                "colored annotation and caption frame retained");
        Thread.currentThread().interrupt();
        boolean cancelled = false;
        try {
            GrayGlyphRepair.repair(textured, w, h, texturedMask);
        } catch (java.util.concurrent.CancellationException expected) {
            cancelled = true;
        } finally {
            Thread.interrupted();
        }
        check(cancelled, "pattern restoration responds to cancellation");
        BubbleLayout.Plan plan =
                BubbleLayout.plan("保留底纹", texturedMask.interior, w, h, true, 24, lines);
        check(plan.cells.length == 4, "textured caption still has safe overlay layout");
        Path out = Paths.get(args[0]);
        Files.createDirectories(out);
        ImageIO.write(
                BubbleLayoutChecks.image(original, w, h),
                "png",
                out.resolve("灰纸含白描边原图.png").toFile());
        ImageIO.write(
                BubbleLayoutChecks.image(cleaned, w, h), "png", out.resolve("灰纸去字.png").toFile());
        String json =
                "{\"checksPassed\":"
                        + checks
                        + ",\"residualDarkInk\":"
                        + residual
                        + ",\"whiteHaloPatches\":"
                        + whitePatches
                        + ",\"maxGradientShadeError\":"
                        + maxShadeError
                        + ",\"protectedChanges\":"
                        + protectedChanges
                        + ",\"openGrayRejected\":true,\"coloredAnnotationPreserved\":true,\"texturedCaptionPreserved\":true,\"scope\":\"Production"
                        + " WhiteBubbleCleaner neutral gradient and white glyph halo, no API\"}";
        Files.writeString(out.resolve("gray_caption_result.json"), json);
        System.out.println(json);
    }
}
