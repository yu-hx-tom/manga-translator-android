package cn.local.manga;

import android.graphics.Rect;

import org.json.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

import javax.imageio.ImageIO;

/**
 * Ten real pages: production prompt/HTTP/reply repair, production masks/cells, desktop glyph
 * rasterization.
 */
public final class BaselineMangaReview {
    static Path root, out, fixtures, predictions;
    static final String MODEL = "rtdetr_legacy_r50_int8_t4";

    static JSONObject rect(Rect b) {
        return new JSONObject()
                .put("x", b.left)
                .put("y", b.top)
                .put("width", (b.right - b.left))
                .put("height", (b.bottom - b.top));
    }

    static List<Region> regions(BufferedImage image, int page) throws Exception {
        return RtDetrRegionsChecks.predict(
                image,
                new JSONObject(
                        Files.readString(
                                predictions
                                        .resolve(MODEL)
                                        .resolve(String.format("page%02d.json", page)))));
    }

    static void translate(int page) throws Exception {
        Path dir = out.resolve(String.format("第%02d页", page));
        Files.createDirectories(dir);
        Path result = dir.resolve("真实译文.json");
        JSONObject previous =
                Files.isRegularFile(result) ? new JSONObject(Files.readString(result)) : null;
        JSONObject local =
                new JSONObject(
                        Files.readString(
                                Paths.get(
                                        System.getProperty("user.home"),
                                        ".antigravity_cockpit/codex_local_access.json")));
        AppSettings s = new AppSettings();
        s.apiKey = local.getString("apiKey");
        s.baseUrl = "http://127.0.0.1:" + local.getInt("port") + "/v1";
        s.textModel = "gpt-6-astra";
        s.reasoningEffort = "low";
        s.serviceTier = "auto";
        s.maxRetries = 3;
        s.retryIntervalSeconds = 5;
        BufferedImage image =
                ImageIO.read(fixtures.resolve(String.format("样本%02d.png", page)).toFile());
        List<Region> regions = regions(image, page);
        java.lang.reflect.Method instruction =
                TranslationEngine.class.getDeclaredMethod("textInstruction", AppSettings.class);
        instruction.setAccessible(true);
        JSONArray content = (JSONArray) instruction.invoke(null, s);
        Files.writeString(dir.resolve("App实际提示词.txt"), content.getJSONObject(0).getString("text"));
        Set<String> previousIds = new HashSet<>();
        if (previous != null)
            for (Object v : previous.getJSONArray("translations")) {
                JSONObject value = (JSONObject) v;
                if (PartialTranslations.valid(value)) previousIds.add(value.getString("id"));
            }
        if (previousIds.size() == regions.size()) {
            System.out.println("page " + page + ": cached full valid translation");
            return;
        }
        List<Region> missing = new ArrayList<>();
        for (Region region : regions) {
            if (previousIds.contains(region.id)) continue;
            missing.add(region);
            Rect b = region.box;
            BufferedImage crop =
                    image.getSubimage(b.left, b.top, (b.right - b.left), (b.bottom - b.top));
            int[] size = ApiClient.textInputSize(crop.getWidth(), crop.getHeight());
            BufferedImage enlarged =
                    new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
            Graphics2D scaleGraphics = enlarged.createGraphics();
            scaleGraphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            scaleGraphics.drawImage(crop, 0, 0, size[0], size[1], null);
            scaleGraphics.dispose();
            crop = enlarged;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            ImageIO.write(crop, "png", bytes);
            ImageIO.write(crop, "png", dir.resolve(region.id + "_发送裁切.png").toFile());
            content.put(
                    new JSONObject()
                            .put("type", "text")
                            .put("text", TranslationEngine.cropInstruction(region)));
            content.put(
                    new JSONObject()
                            .put("type", "image_url")
                            .put(
                                    "image_url",
                                    new JSONObject()
                                            .put(
                                                    "url",
                                                    "data:image/png;base64,"
                                                            + Base64.getEncoder()
                                                                    .encodeToString(
                                                                            bytes.toByteArray()))
                                            .put("detail", "high")));
        }
        Path temp = Files.createTempDirectory(out, "api-job-");
        try (TranslationEngine engine =
                        new TranslationEngine(
                                new android.content.Context(
                                        out.resolve("private-engine").toFile()));
                TranslationEngine.PreparedText job =
                        new TranslationEngine.PreparedText(temp.toFile(), regions, regions)) {
            job.settings = s;
            for (Region region : regions)
                job.identities.put(region.id, "real-page-" + page + "-" + region.id);
            java.lang.reflect.Method write =
                    TranslationEngine.class.getDeclaredMethod(
                            "writeBatch",
                            TranslationEngine.PreparedText.class,
                            JSONArray.class,
                            List.class);
            write.setAccessible(true);
            write.invoke(null, job, content, missing);
            if (previous != null)
                for (Object v : previous.getJSONArray("translations")) {
                    JSONObject item = (JSONObject) v;
                    if (previousIds.contains(item.getString("id")))
                        job.values.put(item.getString("id"), item);
                }
            content = null;
            image = null;
            System.out.println(
                    "page "
                            + page
                            + ": requesting "
                            + missing.size()
                            + " actual crops with app prompt");
            engine.requestTextPage(job, s, () -> false);
            JSONArray values = new JSONArray();
            for (Region region : regions)
                if (job.values.containsKey(region.id)) values.put(job.values.get(region.id));
            JSONObject record =
                    new JSONObject()
                            .put("model", s.textModel)
                            .put("reasoning", s.reasoningEffort)
                            .put("paidApiUsed", !job.values.isEmpty())
                            .put(
                                    "attempt",
                                    previous == null ? 1 : previous.optInt("attempt", 1) + 1)
                            .put("productionRequestEngine", true)
                            .put("preservesFullTranslations", true)
                            .put("httpMaxRetries", s.maxRetries)
                            .put("translations", values)
                            .put("errors", job.errors)
                            .put("expectedRegions", regions.size())
                            .put("receivedRegions", values.length());
            if (previous != null)
                Files.writeString(
                        dir.resolve("调用第" + previous.optInt("attempt", 1) + "次.json"),
                        previous.toString(2));
            Files.writeString(result, record.toString(2));
            System.out.println(
                    "page "
                            + page
                            + ": actual translations "
                            + values.length()
                            + "/"
                            + regions.size());
        }
    }

    static JSONObject render(
            BufferedImage source,
            BufferedImage cleaned,
            BufferedImage textPage,
            BufferedImage cleanupPage,
            Region region,
            List<Region> regions,
            String text,
            Path dir)
            throws Exception {
        JSONObject record =
                new JSONObject()
                        .put("id", region.id)
                        .put("zh", text)
                        .put("sourceBox", rect(region.box));
        if (region.lines.isEmpty())
            return record.put("status", "refused").put("reason", "no_reliable_ink_anchors");
        Rect roi = RtDetrRegions.renderBounds(region, source.getWidth(), source.getHeight());
        int w = (roi.right - roi.left), h = (roi.bottom - roi.top);
        int[] original = source.getRGB(roi.left, roi.top, w, h, null, 0, w);
        int[][] lines = new int[region.lines.size()][4];
        int[] sizes = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            Rect b = region.lines.get(i);
            lines[i] =
                    new int[] {
                        b.left - roi.left, b.top - roi.top, b.right - roi.left, b.bottom - roi.top
                    };
            sizes[i] = Math.min((b.right - b.left), (b.bottom - b.top));
        }
        Arrays.sort(sizes);
        int estimate = sizes[sizes.length / 2];
        WhiteBubbleCleaner.Mask mask =
                WhiteBubbleCleaner.findPreferWhitePaper(original, w, h, lines, estimate);
        int[][] foreign = RtDetrRegions.foreignLines(regions, region.id, roi.left, roi.top);
        if (!mask.whiteBackground)
            return record.put("status", "refused").put("reason", "unconfirmed_glyph_cleanup");
        if (WhiteBubbleCleaner.touchesForeignInk(original, w, h, mask, foreign))
            return record.put("status", "refused").put("reason", "foreign_ink");
        boolean[] safe = mask.interior.clone();
        BubbleLayout.Plan plan;
        try {
            plan = BubbleLayout.plan(text, safe, w, h, region.vertical, estimate, lines);
        } catch (Exception e) {
            return record.put("status", "refused").put("reason", "layout_fit");
        }
        if (BubbleLayout.touchesForeignLines(plan, foreign))
            throw new AssertionError("foreign layout");
        int l = w, t = h, r = 0, b = 0;
        for (int[] cell : plan.cells) {
            l = Math.min(l, cell[0]);
            t = Math.min(t, cell[1]);
            r = Math.max(r, cell[2]);
            b = Math.max(b, cell[3]);
            for (int y = cell[1]; y < cell[3]; y++)
                for (int x = cell[0]; x < cell[2]; x++)
                    if (!safe[y * w + x]) throw new AssertionError("cell outside safe area");
        }
        BufferedImage layer = new BufferedImage(r - l, b - t, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = layer.createGraphics();
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, plan.font));
        g.setColor(Color.BLACK);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        FontMetrics metrics = g.getFontMetrics();
        for (int i = 0; i < plan.cells.length; i++) {
            int[] cell = plan.cells[i];
            String glyph = new String(Character.toChars(plan.codepoints[i]));
            Shape clip = g.getClip();
            g.clipRect(cell[0] - l, cell[1] - t, cell[2] - cell[0], cell[3] - cell[1]);
            g.drawString(
                    glyph,
                    (cell[0] + cell[2] - metrics.stringWidth(glyph)) / 2 - l,
                    (cell[1] + cell[3] - metrics.getHeight()) / 2 + metrics.getAscent() - t);
            g.setClip(clip);
        }
        g.dispose();
        int[] erased = original.clone();
        WhiteBubbleCleaner.apply(erased, mask);
        int erasedDark = 0, residual = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = y * w + x;
                if (mask.erase[p]) {
                    cleaned.setRGB(roi.left + x, roi.top + y, erased[p]);
                    cleanupPage.setRGB(roi.left + x, roi.top + y, erased[p]);
                    erasedDark++;
                } else if (erased[p] != original[p])
                    throw new AssertionError("cleanup escaped mask");
            }
        g = textPage.createGraphics();
        g.drawImage(layer, roi.left + l, roi.top + t, null);
        g.dispose();
        ImageIO.write(layer, "png", dir.resolve(region.id + "_透明中文层.png").toFile());
        return record.put("status", "cleaned_layout")
                .put("font", plan.font)
                .put("vertical", region.vertical)
                .put("cleanupPixels", erasedDark)
                .put(
                        "textLayer",
                        new JSONObject()
                                .put("x", roi.left + l)
                                .put("y", roi.top + t)
                                .put("width", r - l)
                                .put("height", b - t))
                .put("safeCells", true)
                .put("glyphLayerTransparent", true);
    }

    public static void main(String[] args) throws Exception {
        root = Paths.get(args[0]);
        out = Paths.get(args[1]);
        fixtures = root.resolve("tests/0.5.1验证/原始素材");
        predictions = Paths.get("E:/codexwork/漫画检测模型评测_20260929/results");
        Files.createDirectories(out);
        if (args.length < 3 || !args[2].equals("render-only")) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<?>> jobs = new ArrayList<>();
            for (int p = 1; p <= 10; p++) {
                final int page = p;
                jobs.add(
                        pool.submit(
                                () -> {
                                    try {
                                        translate(page);
                                    } catch (Exception e) {
                                        throw new RuntimeException(
                                                "page "
                                                        + page
                                                        + " translation failed: "
                                                        + e.getClass().getSimpleName());
                                    }
                                }));
            }
            pool.shutdown();
            for (Future<?> job : jobs) job.get();
        }
        JSONArray pages = new JSONArray();
        int accepted = 0, refused = 0, skipped = 0, missing = 0;
        StringBuilder html =
                new StringBuilder(
                        "<!doctype html><meta"
                            + " charset='utf-8'><title>十页真实翻译与透明图层回填</title><style>body{font-family:system-ui;background:#eee}section{display:flex;gap:10px}img{width:32%;object-fit:contain}.checker{background:repeating-conic-gradient(#ddd"
                            + " 0% 25%,#fff 0% 50%) 0/16px 16px}</style><h1>十页真实翻译回填</h1><p>使用 App"
                            + " 内置提示词、真实模型返回、生产去字和布局算法；中文字形由桌面字体绘制，未验证 Android 手机显示。</p>");
        for (int page = 1; page <= 10; page++) {
            Path dir = out.resolve(String.format("第%02d页", page));
            BufferedImage source =
                    ImageIO.read(fixtures.resolve(String.format("样本%02d.png", page)).toFile());
            List<Region> regions = regions(source, page);
            JSONObject translation = new JSONObject(Files.readString(dir.resolve("真实译文.json")));
            Map<String, JSONObject> values = new HashMap<>();
            for (Object value : translation.getJSONArray("translations")) {
                JSONObject item = (JSONObject) value;
                values.put(item.getString("id"), item);
            }
            BufferedImage cleaned = RtDetrRegionsChecks.copy(source),
                    textPage =
                            new BufferedImage(
                                    source.getWidth(),
                                    source.getHeight(),
                                    BufferedImage.TYPE_INT_ARGB),
                    cleanupPage =
                            new BufferedImage(
                                    source.getWidth(),
                                    source.getHeight(),
                                    BufferedImage.TYPE_INT_ARGB);
            JSONArray entries = new JSONArray();
            for (Region region : regions) {
                JSONObject value = values.get(region.id), record;
                if (value == null) {
                    record =
                            new JSONObject()
                                    .put("id", region.id)
                                    .put("status", "missing_translation");
                    missing++;
                } else if (value.getBoolean("skip")) {
                    record = new JSONObject().put("id", region.id).put("status", "model_skip");
                    skipped++;
                } else {
                    record =
                            render(
                                    source,
                                    cleaned,
                                    textPage,
                                    cleanupPage,
                                    region,
                                    regions,
                                    value.getString("zh"),
                                    dir);
                    if (record.getString("status").equals("cleaned_layout")) accepted++;
                    else refused++;
                }
                entries.put(record);
            }
            BufferedImage finalImage = RtDetrRegionsChecks.copy(cleaned);
            Graphics2D g = finalImage.createGraphics();
            g.drawImage(textPage, 0, 0, null);
            g.dispose();
            for (Object[] file :
                    new Object[][] {
                        {source, "原图.png"},
                        {cleaned, "仅去除日文.png"},
                        {textPage, "透明中文层.png"},
                        {cleanupPage, "透明去字层.png"},
                        {finalImage, "中文回填.png"}
                    })
                ImageIO.write(
                        (BufferedImage) file[0], "png", dir.resolve((String) file[1]).toFile());
            JSONObject record = new JSONObject().put("page", page).put("paragraphs", entries);
            pages.put(record);
            Files.writeString(dir.resolve("回填记录.json"), record.toString(2));
            html.append("<h2>第")
                    .append(page)
                    .append("页</h2><section><img src='第")
                    .append(String.format("%02d", page))
                    .append("页/原图.png'><img src='第")
                    .append(String.format("%02d", page))
                    .append("页/仅去除日文.png'><img src='第")
                    .append(String.format("%02d", page))
                    .append("页/中文回填.png'></section><p><a href='第")
                    .append(String.format("%02d", page))
                    .append("页/透明中文层.png'>透明中文层</a> · <a href='第")
                    .append(String.format("%02d", page))
                    .append("页/回填记录.json'>排版记录</a></p>");
        }
        JSONObject report =
                new JSONObject()
                        .put("realApiTranslations", true)
                        .put("model", "gpt-6-astra")
                        .put("appPrompt", true)
                        .put("accepted", accepted)
                        .put("refused", refused)
                        .put("modelSkip", skipped)
                        .put("missing", missing)
                        .put("pages", pages)
                        .put("androidRuntimeVerified", false);
        Files.writeString(out.resolve("结果.json"), report.toString(2));
        Files.writeString(out.resolve("对照.html"), html.toString());
        System.out.println(
                "REAL REVIEW: accepted="
                        + accepted
                        + ", refused="
                        + refused
                        + ", skipped="
                        + skipped
                        + ", missing="
                        + missing);
    }
}
