package cn.local.manga;

import static cn.local.manga.BatchMangaTranslationReview.*;

import org.json.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

import javax.imageio.ImageIO;

/** Offline original-page evidence for paragraph-bounded closed-paper recovery. */
public final class V092ClosedPaperReview {
    static boolean local(WhiteBubbleCleaner.Mask m) {
        return m.whiteBackground
                && m.backgroundKind == WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER;
    }

    static void write(Path path, BufferedImage image) throws Exception {
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    static BufferedImage clean(
            int[] source, int w, int h, WhiteBubbleCleaner.Mask mask, int[][] foreign) {
        int[] pixels = source.clone();
        boolean[] safe = WhiteBubbleCleaner.excludeForeign(mask.erase, w, h, foreign);
        for (int p = 0; p < pixels.length; p++)
            if (safe[p]) pixels[p] = mask.fillColors == null ? 0xffffffff : mask.fillColors[p];
        return image(pixels, w, h);
    }

    static JSONObject layout(
            WhiteBubbleCleaner.Mask mask,
            Geometry g,
            Region region,
            String zh,
            int[][] foreign,
            BufferedImage cleaned) {
        JSONObject r = new JSONObject().put("accepted", false);
        if (!local(mask) || zh.isBlank()) return r;
        try {
            BubbleLayout.Plan plan =
                    BubbleLayout.plan(
                            zh,
                            WhiteBubbleCleaner.excludeForeign(mask.interior, g.w, g.h, foreign),
                            g.w,
                            g.h,
                            region.vertical,
                            g.estimate,
                            g.lines);
            if (BubbleLayout.touchesForeignLines(plan, foreign))
                throw new Exception("Foreign text layout overlap");
            Graphics2D graphics = cleaned.createGraphics();
            drawLocal(graphics, plan);
            graphics.dispose();
            r.put("accepted", true).put("font", plan.font).put("cells", new JSONArray(plan.cells));
        } catch (Exception error) {
            r.put("failure", error.toString());
        }
        return r;
    }

    static void drawLocal(Graphics2D g, BubbleLayout.Plan plan) {
        g.setColor(Color.BLACK);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (int i = 0; i < plan.codepoints.length; i++) {
            int[] b = plan.cells[i];
            String c = new String(Character.toChars(plan.codepoints[i]));
            Font font = new Font("Microsoft YaHei", Font.PLAIN, plan.font);
            if (!font.canDisplay(plan.codepoints[i]))
                font = new Font("Segoe UI Symbol", Font.PLAIN, plan.font);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            Shape clip = g.getClip();
            g.clipRect(b[0], b[1], b[2] - b[0], b[3] - b[1]);
            g.drawString(
                    c,
                    (b[0] + b[2] - fm.stringWidth(c)) / 2f,
                    (b[1] + b[3] - fm.getHeight()) / 2f + fm.getAscent());
            g.setClip(clip);
        }
    }

    public static void main(String[] args) throws Exception {
        Path project = Paths.get(args[0]),
                out = Paths.get(args[1]),
                sourceRoot = Paths.get("E:/codexwork/漫画翻译结果_20260930_新素材/逐页"),
                oldRoot = project.resolve("tests/0.9.1验证/30张完整翻译测试_20261005/逐页");
        Set<String> selected =
                args.length < 3 || args[2].equals("all")
                        ? null
                        : new HashSet<>(Arrays.asList(args[2].split(",")));
        JSONArray records = new JSONArray();
        int oldLocal = 0, newLocal = 0, gained = 0, lost = 0, changed = 0;
        for (int page = 1; page <= 30; page++) {
            String prefix = String.format("P%02d_", page);
            if (selected != null && selected.stream().noneMatch(s -> s.startsWith(prefix)))
                continue;
            Path sourceDir = sourceRoot.resolve(String.format("第%02d页", page));
            BufferedImage source = ImageIO.read(sourceDir.resolve("原图.png").toFile());
            List<Region> regions = predict(source, json(sourceDir.resolve("检测原始结果.json")));
            Map<String, String> texts = new HashMap<>();
            for (Object raw :
                    json(oldRoot.resolve(String.format("第%02d页/结果.json", page)))
                            .getJSONArray("regions")) {
                JSONObject r = (JSONObject) raw;
                texts.put(r.getString("regionId"), r.optString("zh"));
            }
            for (Region region : regions) {
                String id = prefix + region.id;
                if (selected != null && !selected.contains(id)) continue;
                Geometry g = new Geometry(source, region);
                int[] paragraph = {
                    region.box.left - g.roi.left,
                    region.box.top - g.roi.top,
                    region.box.right - g.roi.left,
                    region.box.bottom - g.roi.top
                };
                WhiteBubbleCleaner.Mask before = g.mask,
                        after =
                                region.lines.isEmpty()
                                        ? before
                                        : WhiteBubbleCleaner.forText(
                                                g.pixels, g.w, g.h, g.lines, g.estimate, paragraph);
                int[][] foreign =
                        RtDetrRegions.foreignLines(regions, region.id, g.roi.left, g.roi.top);
                boolean[] a = WhiteBubbleCleaner.excludeForeign(before.erase, g.w, g.h, foreign),
                        b = WhiteBubbleCleaner.excludeForeign(after.erase, g.w, g.h, foreign);
                int added = 0, removed = 0, remaining = 0;
                for (int y = 0; y < g.h; y++)
                    for (int x = 0; x < g.w; x++) {
                        int p = y * g.w + x;
                        if (b[p] && !a[p]) added++;
                        if (a[p] && !b[p]) removed++;
                        int color = g.pixels[p],
                                v =
                                        (((color >>> 16) & 255) * 299
                                                        + ((color >>> 8) & 255) * 587
                                                        + (color & 255) * 114)
                                                / 1000;
                        if (x >= paragraph[0]
                                && x < paragraph[2]
                                && y >= paragraph[1]
                                && y < paragraph[3]
                                && v < 160
                                && !b[p]) remaining++;
                    }
                if (local(before)) oldLocal++;
                if (local(after)) newLocal++;
                if (!local(before) && local(after)) gained++;
                if (local(before) && !local(after)) lost++;
                if (added + removed > 0) changed++;
                JSONObject record =
                        new JSONObject()
                                .put("id", id)
                                .put("page", page)
                                .put("paragraphBox", new JSONArray(box(region.box)))
                                .put("roi", new JSONArray(box(g.roi)))
                                .put("lines", new JSONArray(g.lines))
                                .put("estimate", g.estimate)
                                .put("oldLocal", local(before))
                                .put("newLocal", local(after))
                                .put("oldEvidence", before.evidence)
                                .put("newEvidence", after.evidence)
                                .put("oldMaskPixels", before.pixels)
                                .put("newMaskPixels", after.pixels)
                                .put("addedErasePixelsAfterForeignGuard", added)
                                .put("removedErasePixelsAfterForeignGuard", removed)
                                .put("remainingParagraphDarkPixelsMayIncludeOutline", remaining);
                if (selected != null || added + removed > 0) {
                    Path dir = out.resolve(id);
                    BufferedImage oldClean = clean(g.pixels, g.w, g.h, before, foreign),
                            newClean = clean(g.pixels, g.w, g.h, after, foreign),
                            marked = image(g.pixels, g.w, g.h),
                            overlay = image(g.pixels, g.w, g.h);
                    Graphics2D marker = marked.createGraphics();
                    marker.setColor(Color.RED);
                    marker.drawRect(
                            paragraph[0],
                            paragraph[1],
                            paragraph[2] - paragraph[0],
                            paragraph[3] - paragraph[1]);
                    marker.setColor(Color.BLUE);
                    for (int[] line : g.lines)
                        marker.drawRect(line[0], line[1], line[2] - line[0], line[3] - line[1]);
                    marker.dispose();
                    for (int p = 0; p < b.length; p++)
                        if (b[p] && !a[p]) overlay.setRGB(p % g.w, p / g.w, 0xff00c040);
                        else if (a[p] && !b[p]) overlay.setRGB(p % g.w, p / g.w, 0xffe0a000);
                    write(dir.resolve("原图_段落红框_锚点蓝框.png"), marked);
                    write(dir.resolve("旧版仅去字.png"), oldClean);
                    write(dir.resolve("闭域恢复仅去字.png"), newClean);
                    write(dir.resolve("新增去字绿_减少黄.png"), overlay);
                    record.put(
                                    "oldLayout",
                                    layout(
                                            before,
                                            g,
                                            region,
                                            texts.getOrDefault(region.id, ""),
                                            foreign,
                                            oldClean))
                            .put(
                                    "newLayout",
                                    layout(
                                            after,
                                            g,
                                            region,
                                            texts.getOrDefault(region.id, ""),
                                            foreign,
                                            newClean));
                    write(dir.resolve("旧版中文回填.png"), oldClean);
                    write(dir.resolve("闭域恢复中文回填.png"), newClean);
                    GroupedBackgroundReview.compareImages(
                            dir.resolve("原图_旧版_闭域恢复.png"),
                            image(g.pixels, g.w, g.h),
                            oldClean,
                            newClean);
                    save(dir.resolve("结果.json"), record);
                }
                records.put(record);
            }
            source.flush();
            System.out.println("CLOSED PAPER PAGE " + page);
        }
        JSONObject result =
                new JSONObject()
                        .put("regions", records)
                        .put("count", records.length())
                        .put("oldLocal", oldLocal)
                        .put("newLocal", newLocal)
                        .put("gained", gained)
                        .put("lost", lost)
                        .put("changedMasks", changed)
                        .put(
                                "productionCleanerSha256",
                                hash(
                                        project.resolve(
                                                "app/src/main/java/cn/local/manga/WhiteBubbleCleaner.java")))
                        .put(
                                "evaluatedCleanerSha256",
                                hash(
                                        args.length > 3
                                                ? Paths.get(args[3])
                                                : project.resolve(
                                                        "app/src/main/java/cn/local/manga/WhiteBubbleCleaner.java")))
                        .put(
                                "comparison",
                                "Same frozen 0.9.1 RT geometry and BubbleLayout; legacy"
                                    + " five-argument cleaner versus paragraph-aware closed-paper"
                                    + " recovery")
                        .put("apiCalls", 0)
                        .put("androidCanvasVerified", false);
        save(out.resolve("结果.json"), result);
        System.out.println(
                "CLOSED PAPER REVIEW "
                        + records.length()
                        + " old="
                        + oldLocal
                        + " new="
                        + newLocal
                        + " gained="
                        + gained
                        + " lost="
                        + lost
                        + " changed="
                        + changed);
    }
}
