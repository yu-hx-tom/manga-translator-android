package cn.local.manga;

import org.json.*;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

/**
 * Offline comparison against frozen current inputs; never invokes an API or edits their records.
 */
public final class ColorInkCandidateReview {
    static void png(Path file, int[] pixels, int width, int height) throws Exception {
        ImageIO.write(
                BatchMangaTranslationReview.image(pixels, width, height), "png", file.toFile());
    }

    static int[] crop(int[] pixels, int w, int[] r) {
        int rw = r[2] - r[0], rh = r[3] - r[1], result[] = new int[rw * rh];
        for (int y = 0; y < rh; y++)
            System.arraycopy(pixels, (r[1] + y) * w + r[0], result, y * rw, rw);
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]), output = Paths.get(args[1]);
        Files.createDirectories(output);
        Set<String> wanted = new LinkedHashSet<>(Arrays.asList(args[2].split(",")));
        JSONObject frozen =
                BatchMangaTranslationReview.json(root.resolve("翻译输出/translation_plan.json"));
        JSONArray result = new JSONArray();
        for (Object rawPage : frozen.getJSONArray("pages")) {
            JSONObject page = (JSONObject) rawPage;
            List<JSONObject> selected = new ArrayList<>();
            for (Object raw : page.getJSONArray("regions")) {
                JSONObject row = (JSONObject) raw;
                if (wanted.contains(row.getString("id"))) selected.add(row);
            }
            if (selected.isEmpty()) continue;
            Path sourcePath = Paths.get(page.getString("sourcePage"));
            if (!BatchMangaTranslationReview.hash(sourcePath)
                    .equals(page.getString("sourceSha256")))
                throw new Exception("Source hash changed");
            BufferedImage source = ImageIO.read(sourcePath.toFile());
            List<Region> regions =
                    BatchMangaTranslationReview.readRegions(source, sourcePath.getParent());
            for (JSONObject old : selected) {
                String id = old.getString("id");
                Path folder = output.resolve(id);
                Files.createDirectories(folder);
                Region region =
                        regions.stream()
                                .filter(r -> r.id.equals(old.getString("regionId")))
                                .findFirst()
                                .orElseThrow();
                BatchMangaTranslationReview.Geometry g =
                        new BatchMangaTranslationReview.Geometry(source, region);
                WhiteBubbleCleaner.Mask m = g.mask;
                int[][] foreign =
                        RtDetrRegions.foreignLines(regions, region.id, g.roi.left, g.roi.top);
                boolean[] erase = WhiteBubbleCleaner.excludeForeign(m.erase, g.w, g.h, foreign);
                int[] cleaned = g.pixels.clone(), display = g.pixels.clone();
                int count = 0, colored = 0, outside = 0, protectedPixels = 0;
                for (int p = 0; p < cleaned.length; p++)
                    if (erase[p]) {
                        cleaned[p] = m.fillColors == null ? 0xffffffff : m.fillColors[p];
                        display[p] = 0xffff00ff;
                        count++;
                        int c = g.pixels[p], r = c >>> 16 & 255, gg = c >>> 8 & 255, b = c & 255;
                        if (Math.max(r, Math.max(gg, b)) - Math.min(r, Math.min(gg, b)) > 20)
                            colored++;
                        if (!m.interior[p]) outside++;
                    }
                for (int[] f : foreign)
                    for (int y = Math.max(0, f[1] - 2); y < Math.min(g.h, f[3] + 2); y++)
                        for (int x = Math.max(0, f[0] - 2); x < Math.min(g.w, f[2] + 2); x++)
                            if (cleaned[y * g.w + x] != g.pixels[y * g.w + x]) protectedPixels++;
                int[] focus =
                        BatchMangaTranslationReview.ints(
                                old.getJSONObject("cleanupInput").getJSONArray("sourceCrop"));
                int[] rel = {
                    focus[0] - g.roi.left,
                    focus[1] - g.roi.top,
                    focus[2] - g.roi.left,
                    focus[3] - g.roi.top
                };
                int cw = rel[2] - rel[0], ch = rel[3] - rel[1];
                png(folder.resolve("原图ROI.png"), crop(g.pixels, g.w, rel), cw, ch);
                png(folder.resolve("候选仅去字ROI.png"), crop(cleaned, g.w, rel), cw, ch);
                png(folder.resolve("去字掩码ROI.png"), crop(display, g.w, rel), cw, ch);
                JSONObject row = new JSONObject(old.toString());
                BufferedImage current =
                        BatchMangaTranslationReview.image(
                                source.getRGB(
                                        0,
                                        0,
                                        source.getWidth(),
                                        source.getHeight(),
                                        null,
                                        0,
                                        source.getWidth()),
                                source.getWidth(),
                                source.getHeight());
                String layout = "not_attempted";
                try {
                    BatchMangaTranslationReview.local(
                            source, current, regions, region, old.getString("zh"), row, folder);
                    layout = "local_white";
                } catch (Exception failure) {
                    layout = failure.toString();
                }
                int[] actual = current.getRGB(focus[0], focus[1], cw, ch, null, 0, cw);
                png(folder.resolve("候选中文回填ROI.png"), actual, cw, ch);
                current.flush();
                JSONObject entry =
                        new JSONObject()
                                .put("id", id)
                                .put("baselineRoute", old.getString("route"))
                                .put("baselineMaskPixels", old.getInt("maskPixels"))
                                .put("candidateWhite", m.whiteBackground)
                                .put("candidateKind", m.backgroundKind.name())
                                .put("evidence", m.evidence)
                                .put("erasePixels", count)
                                .put("coloredPixelsErased", colored)
                                .put("outsidePaperChanged", outside)
                                .put("foreignGuardPixelsChanged", protectedPixels)
                                .put("layout", layout)
                                .put("layoutDetails", row)
                                .put("sourceSha256", page.getString("sourceSha256"))
                                .put("focus", new JSONArray(focus))
                                .put(
                                        "renderRoi",
                                        new JSONArray(BatchMangaTranslationReview.box(g.roi)))
                                .put("characterSize", g.estimate)
                                .put("clippedLines", new JSONArray(g.lines));
                JSONObject hashes = new JSONObject();
                for (String name :
                        List.of("原图ROI.png", "候选仅去字ROI.png", "去字掩码ROI.png", "候选中文回填ROI.png"))
                    hashes.put(name, BatchMangaTranslationReview.hash(folder.resolve(name)));
                entry.put("filesSha256", hashes);
                result.put(entry);
                BatchMangaTranslationReview.save(folder.resolve("结果.json"), entry);
                System.out.println(
                        id
                                + " white="
                                + m.whiteBackground
                                + " kind="
                                + m.backgroundKind
                                + " erased="
                                + count
                                + " color="
                                + colored
                                + " layout="
                                + layout);
                wanted.remove(id);
            }
            source.flush();
        }
        if (!wanted.isEmpty()) throw new Exception("Missing sample IDs: " + wanted);
        BatchMangaTranslationReview.save(
                output.resolve("结果.json"),
                new JSONObject()
                        .put("isolatedCandidate", true)
                        .put("productionEdited", false)
                        .put("paidApiRequests", 0)
                        .put("samples", result));
    }
}
