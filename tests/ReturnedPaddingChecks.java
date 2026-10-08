package cn.local.manga;

import static cn.local.manga.BatchMangaTranslationReview.*;

import org.json.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

/**
 * Real production recovery checks: bounded two-sided padding, distributed context and unchanged
 * guard.
 */
public final class ReturnedPaddingChecks {
    static int checks;

    static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static int[] direct(int[] raw, int rw, int rh, int w, int h) {
        BufferedImage input = image(raw, rw, rh),
                scaled = ReturnedCanvasReview.resized(input, w, h, false);
        int[] p = scaled.getRGB(0, 0, w, h, null, 0, w);
        input.flush();
        scaled.flush();
        return p;
    }

    static int[] scene(int w, int h) {
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int value = ((x / 3 + y / 5) % 2 == 0 ? 225 : 35) + ((x + y) % 17);
                p[y * w + x] = 0xff000000 | value << 16 | value << 8 | value;
            }
        return p;
    }

    static int[] padding(int[] edited, int w, int h, int rw, int rh, int ox, int oy, int color) {
        int[] p = new int[rw * rh];
        Arrays.fill(p, color);
        for (int y = 0; y < h; y++) System.arraycopy(edited, y * w, p, (y + oy) * rw + ox, w);
        return p;
    }

    static RepairPixels.Normalized run(
            int[] original, int[] raw, int w, int h, int rw, int rh, boolean[] edit)
            throws Exception {
        return RepairPixels.tryRecoverPadding(
                original, direct(raw, rw, rh, w, h), w, h, edit, raw, rw, rh);
    }

    public static void main(String[] args) throws Exception {
        int w = 80, h = 320, rw = 160;
        boolean[] editable =
                RepairPixels.editable(w, h, new int[][] {{20, 20, 60, 300}}, new int[0][]);
        int[] original = scene(w, h), edited = original.clone();
        for (int i = 0; i < edited.length; i++) if (editable[i]) edited[i] = 0xffbcbcbc;
        int[] raw = padding(edited, w, h, rw, h, 40, 0, 0xff000000),
                before = raw.clone(),
                sourceBefore = original.clone();
        RepairPixels.Normalized n = run(original, raw, w, h, rw, h, editable);
        ok(n != null, "centered black padding recovered from distributed asymmetric context");
        ok(
                Arrays.equals(n.pixels, edited),
                "equal-scale recovery preserves every returned content pixel");
        ok(
                n.matchedContextBins == 8 && n.paddingAgreement == 1,
                "all eight exterior bins and full-length padding are verified");
        ok(
                Arrays.equals(raw, before) && Arrays.equals(original, sourceBefore),
                "recovery never modifies original or raw evidence");
        int[] composite = RepairPixels.composite(original, n.pixels, w, h, editable);
        for (int i = 0; i < composite.length; i++)
            if (!editable[i])
                ok(composite[i] == original[i], "protected exterior stays byte exact");
        ok(
                run(
                                original,
                                padding(edited, w, h, rw, h, 40, 0, 0xffffffff),
                                w,
                                h,
                                rw,
                                h,
                                editable)
                        != null,
                "uniform white padding also recovered");
        ok(
                RepairPixels.tryRecoverPadding(original, edited, w, h, editable, raw, rw, h)
                        == null,
                "already accepted ordinary resize is kept unchanged");
        ok(
                run(original, padding(edited, w, h, rw, h, 0, 0, 0xff000000), w, h, rw, h, editable)
                        == null,
                "asymmetric content placement is not searched or guessed");
        int[] altered = edited.clone();
        for (int i = 0; i < altered.length; i++) if (!editable[i]) altered[i] ^= 0x00ffffff;
        ok(
                run(
                                original,
                                padding(altered, w, h, rw, h, 40, 0, 0xff000000),
                                w,
                                h,
                                rw,
                                h,
                                editable)
                        == null,
                "real reconstruction with surrounding drift is rejected despite true padding");
        int[] noisy = raw.clone(), noise = scene(30, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < 30; x++) noisy[y * rw + x] = noise[y * 30 + x];
        ok(
                run(original, noisy, w, h, rw, h, editable) == null,
                "nonuniform image content cannot be called a blank side band");
        boolean[] whole = new boolean[w * h];
        Arrays.fill(whole, true);
        ok(
                run(original, raw, w, h, rw, h, whole) == null,
                "target-only image without context never authorizes aspect recovery");
        boolean[] corner = whole.clone();
        for (int y = 0; y < 90; y++) Arrays.fill(corner, y * w, y * w + 30, false);
        ok(
                run(original, raw, w, h, rw, h, corner) == null,
                "context concentrated in one corner cannot prove alignment");
        int[] blank = new int[w * h];
        Arrays.fill(blank, 0xffffffff);
        ok(
                run(blank, padding(blank, w, h, rw, h, 40, 0, 0xff000000), w, h, rw, h, editable)
                        == null,
                "flat context without structural features cannot prove registration");
        int[] badBand = edited.clone();
        for (int y = 80; y < 160; y++)
            for (int x = 0; x < 20; x++) badBand[y * w + x] ^= 0x00ffffff;
        ok(
                run(
                                original,
                                padding(badBand, w, h, rw, h, 40, 0, 0xff000000),
                                w,
                                h,
                                rw,
                                h,
                                editable)
                        == null,
                "bad distributed context band cannot hide in a small global average");
        int hw = 320, hh = 80;
        boolean[] horizontal =
                RepairPixels.editable(hw, hh, new int[][] {{20, 20, 300, 60}}, new int[0][]);
        int[] ho = scene(hw, hh), he = ho.clone();
        for (int i = 0; i < he.length; i++) if (horizontal[i]) he[i] = 0xffbcbcbc;
        ok(
                run(
                                ho,
                                padding(he, hw, hh, hw, 160, 0, 40, 0xff000000),
                                hw,
                                hh,
                                hw,
                                160,
                                horizontal)
                        != null,
                "horizontal top and bottom padding follows the same evidence rules");
        boolean invalid = false;
        try {
            RepairPixels.tryRecoverPadding(original, new int[1], w, h, editable, raw, rw, h);
        } catch (Exception expected) {
            invalid = true;
        }
        ok(invalid, "malformed buffer dimensions rejected before reading pixels");
        Path root = Paths.get(args[0]), out = Paths.get(args[1]);
        Files.createDirectories(out);
        JSONArray real = new JSONArray();
        for (String id : new String[] {"P20_rt_2", "P21_rt_2"}) {
            JSONObject r =
                    json(root.resolve("requests").resolve(id).resolve("request_result.json"));
            BufferedImage input = ImageIO.read(resolved(root, r.getString("input")).toFile()),
                    returned = ImageIO.read(resolved(root, r.getString("returnedPath")).toFile());
            int iw = input.getWidth(), ih = input.getHeight();
            int[] originalPixels = input.getRGB(0, 0, iw, ih, null, 0, iw);
            boolean[] mask =
                    RepairPixels.editable(
                            iw,
                            ih,
                            boxes(r.getJSONArray("targetBoxes")),
                            boxes(r.getJSONArray("protectedBoxes")));
            for (int probeMode : new int[] {0, 1, 2}) {
                boolean sampled = probeMode > 0;
                int sample = 1;
                while ((long) ((returned.getWidth() + sample - 1) / sample)
                                * ((returned.getHeight() + sample - 1) / sample)
                        > (long) iw * ih) sample *= 2;
                if (probeMode == 2) sample = Math.max(1, sample / 2);
                BufferedImage decode =
                        sampled
                                ? ReturnedCanvasReview.resized(
                                        returned,
                                        (returned.getWidth() + sample - 1) / sample,
                                        (returned.getHeight() + sample - 1) / sample,
                                        false)
                                : returned;
                int dw = decode.getWidth(), dh = decode.getHeight();
                int[] rp = decode.getRGB(0, 0, dw, dh, null, 0, dw);
                RepairPixels.Normalized normalized = run(originalPixels, rp, iw, ih, dw, dh, mask);
                JSONObject row =
                        new JSONObject()
                                .put("id", id)
                                .put("sampled", sampled)
                                .put("sampleSize", sampled ? sample : 1)
                                .put(
                                        "sampleMethod",
                                        "desktop bilinear approximation; Android decoder not"
                                                + " asserted")
                                .put("decodedSize", new JSONArray(new int[] {dw, dh}))
                                .put("normalizationAccepted", normalized != null);
                BufferedImage scaled = ReturnedCanvasReview.resized(decode, iw, ih, false),
                        cropped = ReturnedCanvasReview.resized(decode, iw, ih, true);
                row.put(
                        "contextDiagnosis",
                        ReturnedCanvasReview.context(
                                originalPixels, scaled, cropped, mask, iw, ih));
                double scale = Math.min(dw / (double) iw, dh / (double) ih),
                        left = (dw - iw * scale) / 2,
                        top = (dh - ih * scale) / 2;
                var paddingMethod =
                        RepairPixels.class.getDeclaredMethod(
                                "paddingAgreement",
                                int[].class,
                                int.class,
                                int.class,
                                double[].class,
                                boolean.class);
                paddingMethod.setAccessible(true);
                row.put(
                        "diagnosticPaddingAgreement",
                        paddingMethod.invoke(
                                null,
                                rp,
                                dw,
                                dh,
                                new double[] {left, top, left + iw * scale, top + ih * scale},
                                left > top));
                scaled.flush();
                cropped.flush();
                if (normalized != null) {
                    int[] merged =
                            RepairPixels.composite(originalPixels, normalized.pixels, iw, ih, mask);
                    int changes = 0;
                    for (int i = 0; i < mask.length; i++)
                        if (!mask[i] && merged[i] != originalPixels[i]) changes++;
                    row.put("fullOutsideMeanDelta", normalized.fullOutsideMeanDelta)
                            .put(
                                    "normalizedOutsideMeanDelta",
                                    normalized.normalizedOutsideMeanDelta)
                            .put("contextPixels", normalized.contextPixels)
                            .put("matchedContextBins", normalized.matchedContextBins)
                            .put("paddingAgreement", normalized.paddingAgreement)
                            .put("sourceRect", new JSONArray(normalized.sourceRect))
                            .put("outsideCompositeChangedPixels", changes);
                    ImageIO.write(
                            image(merged, iw, ih),
                            "png",
                            out.resolve(
                                            id
                                                    + (sampled ? "_采样" + sample + "诊断" : "_完整raw")
                                                    + "_保护合成.png")
                                    .toFile());
                }
                real.put(row);
                if (!sampled)
                    ok(normalized != null, "actual centered-padding response recovers: " + id);
                if (decode != returned) decode.flush();
            }
            input.flush();
            returned.flush();
        }
        save(
                out.resolve("结果.json"),
                new JSONObject()
                        .put("checks", checks)
                        .put("passed", true)
                        .put("realResponses", real)
                        .put("apiCalls", 0)
                        .put("productionRepairPixelsSha256", hash(Paths.get(args[2]))));
        System.out.println("ReturnedPaddingChecks " + checks + " passed; " + real);
    }
}
