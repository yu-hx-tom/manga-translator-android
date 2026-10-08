package cn.local.manga;

import static cn.local.manga.BatchMangaTranslationReview.*;

import org.json.*;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.*;

import javax.imageio.ImageIO;

/**
 * Offline geometry investigation using immutable real image responses; never accepts a result for
 * production.
 */
public final class ReturnedCanvasReview {
    static JSONObject context(
            int[] before, BufferedImage a, BufferedImage b, boolean[] editable, int w, int h) {
        int[] direct = a.getRGB(0, 0, w, h, null, 0, w), crop = b.getRGB(0, 0, w, h, null, 0, w);
        long[] sums = new long[8], squares = new long[8], errA = new long[8], errB = new long[8];
        int[] count = new int[8], edges = new int[8];
        boolean vertical = h >= w;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (editable[i]) continue;
                int bucket =
                        vertical
                                ? Math.min(3, y * 4 / h) * 2 + Math.min(1, x * 2 / w)
                                : Math.min(3, x * 4 / w) * 2 + Math.min(1, y * 2 / h);
                int value =
                        ((before[i] >> 16 & 255) + (before[i] >> 8 & 255) + (before[i] & 255)) / 3;
                count[bucket]++;
                sums[bucket] += value;
                squares[bucket] += value * value;
                int da = 0, db = 0;
                for (int shift = 0; shift < 24; shift += 8) {
                    da += Math.abs((before[i] >> shift & 255) - (direct[i] >> shift & 255));
                    db += Math.abs((before[i] >> shift & 255) - (crop[i] >> shift & 255));
                }
                errA[bucket] += da / 3;
                errB[bucket] += db / 3;
                if (x > 0 && !editable[i - 1]) {
                    int neighbor =
                            ((before[i - 1] >> 16 & 255)
                                            + (before[i - 1] >> 8 & 255)
                                            + (before[i - 1] & 255))
                                    / 3;
                    if (Math.abs(value - neighbor) >= 30) edges[bucket]++;
                }
            }
        JSONArray bins = new JSONArray();
        for (int i = 0; i < 8; i++)
            bins.put(
                    new JSONObject()
                            .put("bin", i)
                            .put("pixels", count[i])
                            .put(
                                    "sourceStd",
                                    Math.sqrt(
                                            Math.max(
                                                    0,
                                                    squares[i] / (double) Math.max(1, count[i])
                                                            - Math.pow(
                                                                    sums[i]
                                                                            / (double)
                                                                                    Math.max(
                                                                                            1,
                                                                                            count[
                                                                                                    i]),
                                                                    2))))
                            .put("edgePixels", edges[i])
                            .put("directMeanDelta", errA[i] / (double) Math.max(1, count[i]))
                            .put("cropMeanDelta", errB[i] / (double) Math.max(1, count[i])));
        return new JSONObject()
                .put("grid", "4 longitudinal bands x 2 cross-axis halves")
                .put("bins", bins);
    }

    static BufferedImage resized(BufferedImage b, int w, int h, boolean crop) {
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (crop) {
            double scale = Math.max(w / (double) b.getWidth(), h / (double) b.getHeight());
            AffineTransform t =
                    AffineTransform.getTranslateInstance(
                            (w - b.getWidth() * scale) / 2, (h - b.getHeight() * scale) / 2);
            t.scale(scale, scale);
            g.drawImage(b, t, null);
        } else g.drawImage(b, 0, 0, w, h, null);
        g.dispose();
        return result;
    }

    static JSONObject inspect(
            int[] before, BufferedImage candidate, boolean[] editable, int w, int h, Path output)
            throws Exception {
        int[] after = candidate.getRGB(0, 0, w, h, null, 0, w);
        long error = 0;
        int outside = 0, changed = 0;
        for (int i = 0; i < before.length; i++) {
            int delta =
                    (Math.abs((before[i] >> 16 & 255) - (after[i] >> 16 & 255))
                                    + Math.abs((before[i] >> 8 & 255) - (after[i] >> 8 & 255))
                                    + Math.abs((before[i] & 255) - (after[i] & 255)))
                            / 3;
            if (!editable[i]) {
                error += delta;
                outside++;
            } else if (delta > 8) changed++;
        }
        JSONObject result =
                new JSONObject()
                        .put("outsideMeanDelta", error / (double) Math.max(1, outside))
                        .put("outsidePixels", outside)
                        .put("changedTargetPixels", changed)
                        .put("productionGuardAccepted", false);
        try {
            int[] merged = RepairPixels.composite(before, after, w, h, editable);
            int exteriorChanges = 0;
            for (int i = 0; i < before.length; i++)
                if (!editable[i] && merged[i] != before[i]) exteriorChanges++;
            result.put("productionGuardAccepted", true)
                    .put("outsideCompositeChangedPixels", exteriorChanges);
            if (output != null) ImageIO.write(image(merged, w, h), "png", output.toFile());
        } catch (Exception rejected) {
            result.put("rejection", rejected.toString());
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]), out = Paths.get(args[1]);
        String chosen = args.length > 2 ? args[2] : "all";
        Files.createDirectories(out);
        JSONArray records = new JSONArray();
        int direct = 0, cropped = 0, gained = 0, lost = 0;
        for (Object raw : json(root.resolve("cleanup_manifest.json")).getJSONArray("regions")) {
            JSONObject r = (JSONObject) raw;
            String id = r.getString("id");
            Path recordPath = root.resolve("requests").resolve(id).resolve("request_result.json");
            if (!Files.isRegularFile(recordPath)) continue;
            JSONObject response = json(recordPath);
            if (!response.optBoolean("success")) continue;
            Path input = resolved(root, r.getString("inputPng")),
                    returned = resolved(root, response.getString("returnedPath"));
            if (!hash(input).equals(r.getString("inputSha256"))
                    || !hash(returned).equals(response.getString("outputSha256")))
                throw new Exception("Immutable input/output hash mismatch " + id);
            BufferedImage original = ImageIO.read(input.toFile()),
                    decoded = ImageIO.read(returned.toFile());
            int w = original.getWidth(), h = original.getHeight();
            ImageCleanup.validateReturnedSize(decoded.getWidth(), decoded.getHeight(), w, h);
            boolean[] editable =
                    RepairPixels.editable(
                            w,
                            h,
                            boxes(r.getJSONArray("targetBoxes")),
                            boxes(r.getJSONArray("protectedBoxes")));
            int[] before = original.getRGB(0, 0, w, h, null, 0, w);
            BufferedImage stretched = resized(decoded, w, h, false),
                    corrected = resized(decoded, w, h, true);
            boolean selected = chosen.equals("all") || chosen.equals(id);
            Path dir = out.resolve(id);
            if (selected) Files.createDirectories(dir);
            JSONObject
                    a =
                            inspect(
                                    before,
                                    stretched,
                                    editable,
                                    w,
                                    h,
                                    selected ? dir.resolve("旧全幅缩放_保护合成.png") : null),
                    b =
                            inspect(
                                    before,
                                    corrected,
                                    editable,
                                    w,
                                    h,
                                    selected ? dir.resolve("等比居中裁切_保护合成.png") : null);
            if (a.getBoolean("productionGuardAccepted")) direct++;
            if (b.getBoolean("productionGuardAccepted")) cropped++;
            if (!a.getBoolean("productionGuardAccepted") && b.getBoolean("productionGuardAccepted"))
                gained++;
            if (a.getBoolean("productionGuardAccepted") && !b.getBoolean("productionGuardAccepted"))
                lost++;
            JSONObject normalization = new JSONObject();
            int[] adopted =
                    normalizeReturned(
                            before,
                            stretched.getRGB(0, 0, w, h, null, 0, w),
                            w,
                            h,
                            editable,
                            decoded,
                            normalization);
            JSONObject selectedResult =
                    inspect(
                            before,
                            image(adopted, w, h),
                            editable,
                            w,
                            h,
                            selected ? dir.resolve("生产严格适配_保护合成.png") : null);
            JSONObject row =
                    new JSONObject()
                            .put(
                                    "normalization",
                                    normalization.getJSONObject("returnNormalization"))
                            .put("selectedProductionGuard", selectedResult)
                            .put("id", id)
                            .put("inputSha256", hash(input))
                            .put("rawSha256", hash(returned))
                            .put("inputSize", new JSONArray(new int[] {w, h}))
                            .put(
                                    "returnedSize",
                                    new JSONArray(
                                            new int[] {decoded.getWidth(), decoded.getHeight()}))
                            .put("directResize", a)
                            .put("centerAspectCrop", b)
                            .put(
                                    "distributedContext",
                                    context(before, stretched, corrected, editable, w, h));
            records.put(row);
            if (selected) {
                ImageIO.write(original, "png", dir.resolve("原input.png").toFile());
                ImageIO.write(stretched, "png", dir.resolve("旧全幅缩放.png").toFile());
                ImageIO.write(corrected, "png", dir.resolve("等比居中裁切.png").toFile());
                BufferedImage panel = new BufferedImage(w * 3, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = panel.createGraphics();
                g.drawImage(original, 0, 0, null);
                g.drawImage(stretched, w, 0, null);
                g.drawImage(corrected, w * 2, 0, null);
                g.dispose();
                ImageIO.write(panel, "png", dir.resolve("原图_旧缩放_等比裁切.png").toFile());
                panel.flush();
                save(dir.resolve("结果.json"), row);
            }
            original.flush();
            decoded.flush();
            stretched.flush();
            corrected.flush();
        }
        JSONObject result =
                new JSONObject()
                        .put("diagnosticOnly", true)
                        .put("originalEvidenceUnchanged", true)
                        .put("networkCalls", 0)
                        .put("count", records.length())
                        .put("directGuardAccepted", direct)
                        .put("centerCropGuardAccepted", cropped)
                        .put("gained", gained)
                        .put("lost", lost)
                        .put("regions", records);
        save(out.resolve("结果.json"), result);
        System.out.println(
                "CANVAS PROBE count="
                        + records.length()
                        + " direct="
                        + direct
                        + " cropped="
                        + cropped
                        + " gained="
                        + gained
                        + " lost="
                        + lost);
    }
}
