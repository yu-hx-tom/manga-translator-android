package cn.local.manga;

import android.graphics.Rect;

import org.json.*;

import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Desktop audit of current production geometry using cached real translations. Never makes a
 * network request.
 */
public final class BatchMangaTranslationReview {
    static JSONObject json(Path p) throws Exception {
        String s = Files.readString(p);
        return new JSONObject(s.startsWith("\ufeff") ? s.substring(1) : s);
    }

    static String hash(Path p) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    static int[] box(Rect r) {
        return new int[] {r.left, r.top, r.right, r.bottom};
    }

    static int[][] boxes(JSONArray a) {
        int[][] b = new int[a.length()][4];
        for (int i = 0; i < b.length; i++)
            for (int j = 0; j < 4; j++) b[i][j] = a.getJSONArray(i).getInt(j);
        return b;
    }

    static int[] ints(JSONArray a) {
        int[] b = new int[a.length()];
        for (int i = 0; i < b.length; i++) b[i] = a.getInt(i);
        return b;
    }

    static BufferedImage image(int[] p, int w, int h) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, w, h, p, 0, w);
        return b;
    }

    static String relative(Path root, Path p) {
        return root.toAbsolutePath()
                .normalize()
                .relativize(p.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    static void save(Path p, JSONObject data) throws Exception {
        Files.createDirectories(p.getParent());
        Path t = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(t, data.toString(2));
        Files.move(t, p, StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean paragraphAware() {
        return Boolean.getBoolean("manga.review.paragraphBounds");
    }

    static boolean storedRegions() {
        return Boolean.getBoolean("manga.review.precomputedRegions");
    }

    static int pageCount() {
        int count = Integer.parseInt(System.getProperty("manga.review.pageCount", "30"));
        if (count < 1 || count > 512)
            throw new IllegalArgumentException("Invalid explicit page count");
        return count;
    }

    static Rect exactRect(JSONArray value, int w, int h) {
        if (value.length() != 4)
            throw new IllegalArgumentException("Rectangle needs four coordinates");
        Rect r = new Rect(value.getInt(0), value.getInt(1), value.getInt(2), value.getInt(3));
        if (r.left < 0
                || r.top < 0
                || r.right > w
                || r.bottom > h
                || r.width() <= 0
                || r.height() <= 0) throw new IllegalArgumentException("Invalid stored rectangle");
        return r;
    }

    static List<Region> readRegions(BufferedImage image, Path dir) throws Exception {
        if (!storedRegions()) return predict(image, json(dir.resolve("检测原始结果.json")));
        JSONObject saved = json(dir.resolve("段落检测.json"));
        int w = image.getWidth(), h = image.getHeight();
        if (saved.getInt("schemaVersion") != 1
                || saved.getInt("width") != w
                || saved.getInt("height") != h
                || !saved.getString("sourceSha256").equalsIgnoreCase(hash(dir.resolve("原图.png"))))
            throw new IllegalStateException(
                    "Stored production regions do not match the source image");
        List<Region> regions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object raw : saved.getJSONArray("regions")) {
            JSONObject value = (JSONObject) raw;
            String id = value.getString("id");
            if (!ids.add(id) || !id.matches("[A-Za-z0-9_-]{1,80}"))
                throw new IllegalStateException("Duplicate or invalid region ID");
            List<Rect> lines = new ArrayList<>();
            for (Object line : value.getJSONArray("lines"))
                lines.add(exactRect((JSONArray) line, w, h));
            Rect context =
                    value.isNull("contextBox")
                            ? null
                            : exactRect(value.getJSONArray("contextBox"), w, h);
            Region region =
                    new Region(
                            id,
                            exactRect(value.getJSONArray("box"), w, h),
                            lines,
                            value.getBoolean("vertical"),
                            context);
            if (value.has("renderBounds")
                    && !Arrays.equals(
                            box(RtDetrRegions.renderBounds(region, w, h)),
                            ints(value.getJSONArray("renderBounds"))))
                throw new IllegalStateException("Stored render bounds differ from production");
            regions.add(region);
        }
        return regions;
    }

    // Reflection keeps the historical runner compilable against the frozen 0.9.1 sources.
    static WhiteBubbleCleaner.Mask textMask(
            int[] pixels, int w, int h, int[][] lines, int size, int[] paragraph) throws Exception {
        if (!paragraphAware()) return WhiteBubbleCleaner.forText(pixels, w, h, lines, size);
        return (WhiteBubbleCleaner.Mask)
                WhiteBubbleCleaner.class
                        .getDeclaredMethod(
                                "forText",
                                int[].class,
                                int.class,
                                int.class,
                                int[][].class,
                                int.class,
                                int[].class)
                        .invoke(null, pixels, w, h, lines, size, paragraph);
    }

    static NearbyTextLayout.Plan nearby(int w, int h, Region r, int left, int top, String text)
            throws Exception {
        if (!paragraphAware())
            return NearbyTextLayout.forCrop(w, h, box(r.box), left, top, r.vertical, text);
        int[][] lines =
                r.lines.stream().map(BatchMangaTranslationReview::box).toArray(int[][]::new);
        return (NearbyTextLayout.Plan)
                NearbyTextLayout.class
                        .getDeclaredMethod(
                                "forCrop",
                                int.class,
                                int.class,
                                int[].class,
                                int.class,
                                int.class,
                                boolean.class,
                                String.class,
                                int[][].class)
                        .invoke(null, w, h, box(r.box), left, top, r.vertical, text, lines);
    }

    static float cellStep(NearbyTextLayout.Plan p, int i) throws Exception {
        try {
            return ((Number)
                            NearbyTextLayout.Plan.class
                                    .getDeclaredMethod("cellStep", int.class)
                                    .invoke(p, i))
                    .floatValue();
        } catch (NoSuchMethodException legacy) {
            return p.step;
        }
    }

    static float cellFont(NearbyTextLayout.Plan p, int i) throws Exception {
        try {
            return ((Number)
                            NearbyTextLayout.Plan.class
                                    .getDeclaredMethod("cellFont", int.class)
                                    .invoke(p, i))
                    .floatValue();
        } catch (NoSuchMethodException legacy) {
            return p.font;
        }
    }

    static float cellFont(BubbleLayout.Plan p, int i) throws Exception {
        try {
            return ((Number)
                            BubbleLayout.Plan.class
                                    .getDeclaredMethod("cellFont", int.class)
                                    .invoke(p, i))
                    .floatValue();
        } catch (NoSuchMethodException legacy) {
            return p.font;
        }
    }

    static float cellStep(BubbleLayout.Plan p, int i) throws Exception {
        try {
            return ((Number)
                            BubbleLayout.Plan.class
                                    .getDeclaredMethod("cellStep", int.class)
                                    .invoke(p, i))
                    .floatValue();
        } catch (NoSuchMethodException legacy) {
            return p.step;
        }
    }

    static void fontEvidence(JSONObject row, BubbleLayout.Plan plan) throws Exception {
        JSONArray fonts = new JSONArray(), steps = new JSONArray();
        float low = Float.MAX_VALUE, high = 0;
        for (int i = 0; i < plan.cells.length; i++) {
            float font = cellFont(plan, i);
            fonts.put(font);
            steps.put(cellStep(plan, i));
            low = Math.min(low, font);
            high = Math.max(high, font);
        }
        row.put("cellFonts", fonts)
                .put("cellSteps", steps)
                .put("fontRange", new JSONArray(new float[] {low, high}))
                .put("mixedBubbleFonts", low != high);
    }

    static int[] normalizeReturned(
            int[] before,
            int[] full,
            int w,
            int h,
            boolean[] editable,
            BufferedImage returned,
            JSONObject row)
            throws Exception {
        JSONObject proof =
                new JSONObject()
                        .put("method", "full_resize_compatibility")
                        .put("recoveryAttempted", false);
        row.put("returnNormalization", proof);
        java.lang.reflect.Method method;
        try {
            method =
                    RepairPixels.class.getDeclaredMethod(
                            "tryRecoverPadding",
                            int[].class,
                            int[].class,
                            int.class,
                            int.class,
                            boolean[].class,
                            int[].class,
                            int.class,
                            int.class);
        } catch (NoSuchMethodException legacy) {
            return full;
        }
        long error = 0;
        int outside = 0;
        for (int i = 0; i < before.length; i++)
            if (!editable[i]) {
                outside++;
                error +=
                        (Math.abs((before[i] >> 16 & 255) - (full[i] >> 16 & 255))
                                        + Math.abs((before[i] >> 8 & 255) - (full[i] >> 8 & 255))
                                        + Math.abs((before[i] & 255) - (full[i] & 255)))
                                / 3;
            }
        proof.put("fullOutsideMeanDelta", error / (double) Math.max(1, outside));
        if (error <= outside * 45L) return full;
        proof.put("recoveryAttempted", true);
        Object normalized =
                method.invoke(
                        null,
                        before,
                        full,
                        w,
                        h,
                        editable,
                        returned.getRGB(
                                0,
                                0,
                                returned.getWidth(),
                                returned.getHeight(),
                                null,
                                0,
                                returned.getWidth()),
                        returned.getWidth(),
                        returned.getHeight());
        if (normalized == null) {
            proof.put("strictPaddingEvidenceAccepted", false);
            return full;
        }
        Class<?> type = normalized.getClass();
        for (String key :
                new String[] {
                    "method",
                    "fullOutsideMeanDelta",
                    "normalizedOutsideMeanDelta",
                    "paddingAgreement",
                    "contextPixels",
                    "matchedContextBins"
                }) proof.put(key, type.getDeclaredField(key).get(normalized));
        proof.put(
                        "sourceRect",
                        new JSONArray(
                                (double[]) type.getDeclaredField("sourceRect").get(normalized)))
                .put("strictPaddingEvidenceAccepted", true);
        return (int[]) type.getDeclaredField("pixels").get(normalized);
    }

    static boolean fitProtected(NearbyTextLayout.Plan p, int[][] protections) throws Exception {
        try {
            return (Boolean)
                    NearbyTextLayout.class
                            .getDeclaredMethod(
                                    "fitProtected", NearbyTextLayout.Plan.class, int[][].class)
                            .invoke(null, p, protections);
        } catch (NoSuchMethodException legacy) {
            return true;
        }
    }

    static void protectedFitEvidence(
            JSONObject row, NearbyTextLayout.Plan plan, int[][] protections) throws Exception {
        float x = plan.cellLeft(0), y = plan.cellTop(0);
        boolean fitted = fitProtected(plan, protections);
        row.put("protectedLayoutFitFound", fitted)
                .put(
                        "protectedLayoutTranslation",
                        new JSONArray(new float[] {plan.cellLeft(0) - x, plan.cellTop(0) - y}))
                .put("neighborSafetyPadding", 2);
    }

    static void fontEvidence(JSONObject row, NearbyTextLayout.Plan plan) throws Exception {
        JSONArray fonts = new JSONArray(), steps = new JSONArray();
        for (int i = 0; i < plan.points.length; i++) {
            fonts.put(cellFont(plan, i));
            steps.put(cellStep(plan, i));
        }
        row.put("cellFonts", fonts).put("cellSteps", steps);
        try {
            row.put(
                    "sourceGrouped",
                    NearbyTextLayout.Plan.class.getDeclaredField("sourceGrouped").getBoolean(plan));
        } catch (NoSuchFieldException legacy) {
            row.put("sourceGrouped", false);
        }
    }

    static List<Region> predict(BufferedImage image, JSONObject p) {
        JSONArray raw = p.getJSONArray("boxes"),
                types = p.getJSONArray("box_types"),
                confidence = p.getJSONArray("scores");
        long[] labels = new long[raw.length()];
        float[][] bs = new float[raw.length()][4];
        float[] scores = new float[raw.length()];
        for (int i = 0; i < raw.length(); i++) {
            String type = types.getString(i);
            labels[i] =
                    type.equals("bubble")
                            ? 0
                            : type.equals("text_bubble") ? 1 : type.equals("text_free") ? 2 : -1;
            scores[i] = confidence.getFloat(i);
            for (int k = 0; k < 4; k++) bs[i][k] = raw.getJSONArray(i).getFloat(k);
        }
        int w = image.getWidth(), h = image.getHeight();
        return RtDetrRegions.fromPredictions(
                w, h, image.getRGB(0, 0, w, h, null, 0, w), labels, bs, scores);
    }

    static final class Geometry {
        final Rect roi;
        final int w, h;
        final int[][] lines;
        final int estimate;
        final int[] pixels;
        final WhiteBubbleCleaner.Mask mask;

        Geometry(BufferedImage source, Region r) throws Exception {
            roi = RtDetrRegions.renderBounds(r, source.getWidth(), source.getHeight());
            w = roi.width();
            h = roi.height();
            if ((long) w * h > ImageCleanup.MAX_INPUT_PIXELS) throw new Exception("去字区域超过400万像素");
            pixels = source.getRGB(roi.left, roi.top, w, h, null, 0, w);
            List<int[]> list = new ArrayList<>();
            List<Integer> sizes = new ArrayList<>();
            for (Rect b : r.lines) {
                int l = Math.max(0, b.left - roi.left),
                        t = Math.max(0, b.top - roi.top),
                        rr = Math.min(w, b.right - roi.left),
                        bb = Math.min(h, b.bottom - roi.top);
                if (l < rr && t < bb) {
                    list.add(new int[] {l, t, rr, bb});
                    sizes.add(Math.min(rr - l, bb - t));
                }
            }
            lines = list.toArray(new int[0][]);
            Collections.sort(sizes);
            estimate = sizes.isEmpty() ? Math.min(w, h) : sizes.get(sizes.size() / 2);
            if (!r.lines.isEmpty() && lines.length == 0) throw new Exception("段落无法可靠拆分原文字笔画");
            int[] paragraph = {
                r.box.left - roi.left,
                r.box.top - roi.top,
                r.box.right - roi.left,
                r.box.bottom - roi.top
            };
            mask =
                    r.lines.isEmpty()
                            ? WhiteBubbleCleaner.classifyOnly(pixels, w, h, new int[][] {paragraph})
                            : textMask(pixels, w, h, lines, estimate, paragraph);
        }
    }

    static JSONObject hashes(Path project) throws Exception {
        Path source =
                Paths.get(
                        System.getProperty(
                                "manga.review.sourceDir",
                                project.resolve("app/src/main/java/cn/local/manga").toString()));
        JSONObject h = new JSONObject();
        for (String name :
                new String[] {
                    "TranslationEngine",
                    "RtDetrRegions",
                    "WhiteBubbleCleaner",
                    "GrayGlyphRepair",
                    "BubbleLayout",
                    "ImageCleanup",
                    "RepairPixels",
                    "NearbyTextLayout"
                }) h.put(name, hash(source.resolve(name + ".java")));
        return h;
    }

    static void prepare(Path input, Path out, Path project) throws Exception {
        Path requests = out.resolve("requests");
        if (Files.isDirectory(requests))
            try (var files = Files.walk(requests)) {
                if (files.anyMatch(
                        p ->
                                p.getFileName().toString().equals("start.json")
                                        || p.getFileName()
                                                .toString()
                                                .equals("request_result.json")))
                    throw new IllegalStateException("批次已有真实请求记录，禁止重做prepare覆盖输入；请使用新的输出目录");
            }
        JSONArray planPages = new JSONArray(), manifest = new JSONArray();
        JSONObject totals = new JSONObject();
        int total = 0, local = 0, ai = 0, skip = 0, missing = 0, prepFail = 0;
        for (int page = 1; page <= pageCount(); page++) {
            Path dir = input.resolve("逐页").resolve(String.format("第%02d页", page)),
                    sourceFile = dir.resolve("原图.png");
            BufferedImage source = ImageIO.read(sourceFile.toFile());
            List<Region> regions = readRegions(source, dir);
            Map<String, JSONObject> translations = new HashMap<>();
            for (Object v : json(dir.resolve("真实译文.json")).getJSONArray("translations")) {
                JSONObject t = (JSONObject) v;
                translations.put(t.getString("id"), t);
            }
            JSONArray rows = new JSONArray();
            for (Region r : regions) {
                total++;
                String id = String.format("P%02d_", page) + r.id;
                JSONObject t = translations.get(r.id);
                JSONObject row =
                        new JSONObject()
                                .put("id", id)
                                .put("regionId", r.id)
                                .put("page", page)
                                .put("box", new JSONArray(box(r.box)))
                                .put("vertical", r.vertical)
                                .put(
                                        "lines",
                                        new JSONArray(
                                                r.lines.stream()
                                                        .map(BatchMangaTranslationReview::box)
                                                        .toArray(int[][]::new)));
                rows.put(row);
                if (t == null || (!t.optBoolean("skip") && t.optString("zh").isBlank())) {
                    row.put("route", "missing_translation");
                    missing++;
                    continue;
                }
                row.put("zh", t.optString("zh")).put("translationSkip", t.optBoolean("skip"));
                if (t.optBoolean("skip")) {
                    row.put("route", "model_skip");
                    skip++;
                    continue;
                }
                try {
                    Geometry g = new Geometry(source, r);
                    row.put("renderRoi", new JSONArray(box(g.roi)))
                            .put("clippedLines", new JSONArray(g.lines))
                            .put("estimate", g.estimate)
                            .put("backgroundKind", g.mask.backgroundKind.name())
                            .put("backgroundEvidence", g.mask.evidence)
                            .put("maskWhite", g.mask.whiteBackground)
                            .put("maskPixels", g.mask.pixels);
                    if (g.mask.whiteBackground
                            && g.mask.backgroundKind
                                    == WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER) {
                        row.put("route", "local_white");
                        local++;
                        continue;
                    }
                    int pad =
                            Math.max(8, Math.min(48, Math.min(r.box.width(), r.box.height()) / 4));
                    Rect roi =
                            new Rect(
                                    Math.max(0, r.box.left - pad),
                                    Math.max(0, r.box.top - pad),
                                    Math.min(source.getWidth(), r.box.right + pad),
                                    Math.min(source.getHeight(), r.box.bottom + pad));
                    int w = roi.width(), h = roi.height();
                    int[][] targets = {
                        {
                            r.box.left - roi.left,
                            r.box.top - roi.top,
                            r.box.right - roi.left,
                            r.box.bottom - roi.top
                        }
                    };
                    List<int[]> protectedAreas = new ArrayList<>();
                    for (Region other : regions)
                        if (!other.id.equals(r.id)) {
                            int l = Math.max(other.box.left, roi.left),
                                    tt = Math.max(other.box.top, roi.top),
                                    rr = Math.min(other.box.right, roi.right),
                                    b = Math.min(other.box.bottom, roi.bottom);
                            if (l < rr && tt < b)
                                protectedAreas.add(
                                        new int[] {
                                            l - roi.left, tt - roi.top, rr - roi.left, b - roi.top
                                        });
                        }
                    int[][] protections = protectedAreas.toArray(new int[0][]);
                    ImageCleanup.validateInputSize(w, h);
                    ImageCleanup.prompt(w, h, targets, protections);
                    Path png = out.resolve("requests").resolve(id).resolve("input.png");
                    Files.createDirectories(png.getParent());
                    ImageIO.write(
                            image(source.getRGB(roi.left, roi.top, w, h, null, 0, w), w, h),
                            "png",
                            png.toFile());
                    JSONObject request =
                            new JSONObject()
                                    .put("id", id)
                                    .put("page", page)
                                    .put("regionId", r.id)
                                    .put("inputPng", relative(out, png))
                                    .put("inputSha256", hash(png))
                                    .put("width", w)
                                    .put("height", h)
                                    .put("targetBoxes", new JSONArray(targets))
                                    .put("protectedBoxes", new JSONArray(protections))
                                    .put("sourceCrop", new JSONArray(box(roi)))
                                    .put("sourcePage", sourceFile.toString())
                                    .put(
                                            "sourceSize",
                                            new JSONArray(
                                                    new int[] {
                                                        source.getWidth(), source.getHeight()
                                                    }));
                    manifest.put(request);
                    row.put("route", "image_api").put("cleanupInput", request);
                    ai++;
                } catch (Exception failure) {
                    row.put("route", "preparation_failed").put("failure", failure.toString());
                    prepFail++;
                }
            }
            planPages.put(
                    new JSONObject()
                            .put("page", page)
                            .put("sourcePage", sourceFile.toString())
                            .put("sourceSha256", hash(sourceFile))
                            .put("translationSha256", hash(dir.resolve("真实译文.json")))
                            .put("predictionSha256", hash(dir.resolve("检测原始结果.json")))
                            .put(
                                    "precomputedRegionsSha256",
                                    storedRegions()
                                            ? hash(dir.resolve("段落检测.json"))
                                            : JSONObject.NULL)
                            .put("width", source.getWidth())
                            .put("height", source.getHeight())
                            .put("regions", rows));
            source.flush();
            System.out.println(
                    "PREPARED page=" + page + " regions=" + regions.size() + " cumulativeAI=" + ai);
        }
        totals.put("regions", total)
                .put("localCandidates", local)
                .put("imageApiCandidates", ai)
                .put("modelSkip", skip)
                .put("missingTranslation", missing)
                .put("preparationFailed", prepFail);
        save(
                out.resolve("translation_plan.json"),
                new JSONObject()
                        .put("schemaVersion", 1)
                        .put("productionSourceSha256", hashes(project))
                        .put("paragraphBoundsRecovery", paragraphAware())
                        .put("precomputedRegions", storedRegions())
                        .put(
                                "freshTranslationsFromThisBatch",
                                Boolean.getBoolean("manga.review.freshTranslations"))
                        .put(
                                "cachedRealTranslations",
                                !Boolean.getBoolean("manga.review.freshTranslations"))
                        .put("androidCanvasVerified", false)
                        .put("counts", totals)
                        .put("pages", planPages));
        save(
                out.resolve("cleanup_manifest.json"),
                new JSONObject()
                        .put("schemaVersion", 1)
                        .put("maxAttemptsPerRegion", 3)
                        .put("requestTimeoutSeconds", 600)
                        .put("backoffSeconds", new JSONArray(new int[] {30, 60}))
                        .put("regions", manifest));
        System.out.println("PREPARE COMPLETE " + totals);
    }

    static void drawLocal(BufferedImage layer, BubbleLayout.Plan plan) throws Exception {
        Graphics2D g = layer.createGraphics();
        g.setColor(Color.BLACK);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        boolean perGlyph;
        try {
            BubbleLayout.Plan.class.getDeclaredMethod("cellFont", int.class);
            perGlyph = true;
        } catch (NoSuchMethodException legacy) {
            perGlyph = false;
        }
        if (perGlyph)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            for (int i = 0; i < plan.cells.length; i++) {
                int[] c = plan.cells[i];
                int code = plan.codepoints[i];
                float font = cellFont(plan, i);
                Font primary = new Font("Microsoft YaHei", Font.PLAIN, 1).deriveFont(font),
                        symbols = new Font("Segoe UI Symbol", Font.PLAIN, 1).deriveFont(font),
                        f = primary.canDisplay(code) ? primary : symbols;
                if (!f.canDisplay(code))
                    throw new Exception("Desktop font missing U+" + Integer.toHexString(code));
                String glyph = new String(Character.toChars(code));
                Graphics2D cell = (Graphics2D) g.create();
                cell.setFont(f);
                cell.clipRect(c[0], c[1], c[2] - c[0], c[3] - c[1]);
                if (perGlyph) {
                    GlyphVector vector = f.createGlyphVector(cell.getFontRenderContext(), glyph);
                    Rectangle2D ink = vector.getVisualBounds();
                    double scale =
                            Math.min(
                                    1,
                                    Math.min(
                                            Math.max(1, c[2] - c[0] - 2)
                                                    / Math.max(1, ink.getWidth()),
                                            Math.max(1, c[3] - c[1] - 2)
                                                    / Math.max(1, ink.getHeight())));
                    cell.translate((c[0] + c[2]) / 2.0, (c[1] + c[3]) / 2.0);
                    cell.scale(scale, scale);
                    cell.fill(
                            vector.getOutline(
                                    (float) -ink.getCenterX(), (float) -ink.getCenterY()));
                } else {
                    FontMetrics fm = cell.getFontMetrics();
                    cell.drawString(
                            glyph,
                            (c[0] + c[2] - fm.stringWidth(glyph)) / 2f,
                            (c[1] + c[3] - fm.getAscent() - fm.getDescent()) / 2f + fm.getAscent());
                }
                cell.dispose();
            }
        } finally {
            g.dispose();
        }
    }

    static void drawInPlace(Graphics2D g, NearbyTextLayout.Plan plan, Color color)
            throws Exception {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (int i = 0; i < plan.points.length; i++) {
            float font = cellFont(plan, i), step = cellStep(plan, i);
            Font primary = new Font("Microsoft YaHei", Font.PLAIN, 1).deriveFont(font),
                    symbols = new Font("Segoe UI Symbol", Font.PLAIN, 1).deriveFont(font);
            int code = plan.points[i];
            Font f = primary.canDisplay(code) ? primary : symbols;
            if (!f.canDisplay(code))
                throw new Exception("Desktop font missing U+" + Integer.toHexString(code));
            Graphics2D cell = (Graphics2D) g.create();
            cell.setFont(f);
            FontMetrics fm = cell.getFontMetrics();
            GlyphVector glyph =
                    f.createGlyphVector(
                            cell.getFontRenderContext(), new String(Character.toChars(code)));
            float x = plan.cellLeft(i),
                    y = plan.cellTop(i),
                    width = (float) glyph.getLogicalBounds().getBounds2D().getWidth(),
                    scale =
                            Math.min(
                                    1f,
                                    step
                                            * .78f
                                            / Math.max(
                                                    1f,
                                                    Math.max(
                                                            width,
                                                            fm.getAscent() + fm.getDescent())));
            cell.clip(
                    new Rectangle2D.Float(
                            x,
                            y,
                            Math.min(plan.box[2], x + step) - x,
                            Math.min(plan.box[3], y + step) - y));
            cell.translate(x + step / 2, y + step / 2);
            cell.scale(scale, scale);
            Shape outline = glyph.getOutline(-width / 2f, (fm.getAscent() - fm.getDescent()) / 2f);
            cell.setStroke(
                    new BasicStroke(
                            Math.max(.1f, font * .14f),
                            BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_ROUND));
            cell.setColor(Color.WHITE);
            cell.draw(outline);
            cell.setColor(color);
            cell.fill(outline);
            cell.dispose();
        }
    }

    static void local(
            BufferedImage source,
            BufferedImage current,
            List<Region> regions,
            Region r,
            String text,
            JSONObject row,
            Path out)
            throws Exception {
        Geometry g = new Geometry(source, r);
        if (g.lines.length == 0) throw new Exception("没有可靠原文行锚框");
        int[][] foreign = RtDetrRegions.foreignLines(regions, r.id, g.roi.left, g.roi.top);
        WhiteBubbleCleaner.Mask m = g.mask;
        boolean[] erase = WhiteBubbleCleaner.excludeForeign(m.erase, g.w, g.h, foreign);
        int count = 0;
        for (boolean b : erase) if (b) count++;
        m =
                new WhiteBubbleCleaner.Mask(
                        erase,
                        m.interior,
                        m.whiteBackground,
                        count,
                        m.fillColors,
                        m.texturedBackground,
                        m.glyphCandidate,
                        m.backgroundKind,
                        m.evidence);
        if (WhiteBubbleCleaner.touchesForeignInk(g.pixels, g.w, g.h, m, foreign))
            throw new Exception("本地去字影响其他段");
        if (!m.whiteBackground || m.backgroundKind != WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER)
            throw new Exception("背景未经PLAIN确认");
        if (m.pixels == 0) throw new Exception("所有笔画受保护");
        boolean[] safe = WhiteBubbleCleaner.excludeForeign(m.interior, g.w, g.h, foreign);
        BubbleLayout.Plan plan =
                BubbleLayout.plan(text, safe, g.w, g.h, r.vertical, g.estimate, g.lines);
        if (BubbleLayout.touchesForeignLines(plan, foreign)) throw new Exception("本地译文覆盖其他段");
        for (int[] cell : plan.cells)
            for (int y = cell[1]; y < cell[3]; y++)
                for (int x = cell[0]; x < cell[2]; x++)
                    if (!safe[y * g.w + x]) throw new Exception("本地字格逃出安全空间");
        BufferedImage layer = new BufferedImage(g.w, g.h, BufferedImage.TYPE_INT_ARGB);
        drawLocal(layer, plan);
        int[] cleaned = g.pixels.clone();
        WhiteBubbleCleaner.apply(cleaned, m);
        int changed = 0;
        if (paragraphAware()) {
            Path roi = out.resolve("local_cleanup").resolve(row.getString("id") + "_生产仅去字ROI.png");
            Files.createDirectories(roi.getParent());
            BufferedImage proof = image(cleaned, g.w, g.h);
            ImageIO.write(proof, "png", roi.toFile());
            proof.flush();
            row.put("productionCleanupOnlyRoi", relative(out, roi))
                    .put("productionCleanupOnlyRoiSha256", hash(roi));
        }
        // Commit only the erase mask and transparent glyph layer, never an opaque source crop.
        for (int y = 0; y < g.h; y++)
            for (int x = 0; x < g.w; x++) {
                int p = y * g.w + x;
                if (erase[p]) {
                    current.setRGB(g.roi.left + x, g.roi.top + y, cleaned[p]);
                    changed++;
                }
            }
        Graphics2D target = current.createGraphics();
        target.drawImage(layer, g.roi.left, g.roi.top, null);
        target.dispose();
        layer.flush();
        row.put("status", "local_white")
                .put("compositeApplied", true)
                .put("cleanupApplied", true)
                .put("layoutApplied", true)
                .put("font", plan.font)
                .put("glyphCount", plan.codepoints.length)
                .put("cells", new JSONArray(plan.cells))
                .put("cellsCoordinateSpace", "renderRoi")
                .put("committedErasePixels", changed)
                .put("foreignInkAndLayoutProtected", true);
        fontEvidence(row, plan);
    }

    static Path resolved(Path root, String value) {
        Path p = Paths.get(value);
        return p.isAbsolute() ? p : root.resolve(p);
    }

    static JSONObject requestMetadata(Path out, JSONObject row) throws Exception {
        Path record =
                out.resolve("requests").resolve(row.getString("id")).resolve("request_result.json");
        row.put("requestResult", relative(out, record));
        if (!Files.isRegularFile(record)) {
            row.put("requestStatus", "not_started");
            return null;
        }
        JSONObject result = json(record);
        int actual = result.optInt("actualRequests", result.optInt("attemptsStarted", 0));
        boolean submitted =
                actual > 0
                        || result.optBoolean("apiRequestSentThisBatch")
                        || result.optBoolean("liveRequestThisBatch");
        row.put("actualRequests", actual)
                .put("apiSubmitted", submitted)
                .put("apiSuccess", result.optBoolean("success"))
                .put("cacheUsed", result.optBoolean("cacheUsed"))
                .put("reused", result.optBoolean("reused"))
                .put("requestStatus", result.optString("status"))
                .put("requestResultSha256", hash(record))
                .put("requestOrigin", result.optString("origin"));
        for (String key :
                new String[] {
                    "reusedFrom",
                    "historicalRequestResultSha256",
                    "historicalActualRequests",
                    "paidApiRequests",
                    "promptSha256",
                    "strictReuseVerified"
                }) if (result.has(key)) row.put(key, result.get(key));
        return result;
    }

    /**
     * Evidence only: isolates the visual effect of the production 2-pixel feather without accepting
     * it.
     */
    static void diagnostics(
            Path out, JSONObject row, int[] before, int[] after, int w, int h, boolean[] editable)
            throws Exception {
        long outsideError = 0;
        int outside = 0, inside = 0, rawChanged = 0, transparent = 0;
        for (int p = 0; p < before.length; p++) {
            int delta =
                    (Math.abs((before[p] >> 16 & 255) - (after[p] >> 16 & 255))
                                    + Math.abs((before[p] >> 8 & 255) - (after[p] >> 8 & 255))
                                    + Math.abs((before[p] & 255) - (after[p] & 255)))
                            / 3;
            if (editable[p]) {
                inside++;
                if (delta > 8) rawChanged++;
                if ((after[p] >>> 24) < 250) transparent++;
            } else {
                outside++;
                outsideError += delta;
            }
        }
        byte[] distance = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = y * w + x;
                if (editable[p])
                    distance[p] =
                            (byte)
                                    Math.min(
                                            3,
                                            1
                                                    + Math.min(
                                                            x == 0 ? 0 : distance[p - 1],
                                                            y == 0 ? 0 : distance[p - w]));
            }
        for (int y = h - 1; y >= 0; y--)
            for (int x = w - 1; x >= 0; x--) {
                int p = y * w + x;
                if (distance[p] > 0)
                    distance[p] =
                            (byte)
                                    Math.min(
                                            distance[p],
                                            1
                                                    + Math.min(
                                                            x == w - 1 ? 0 : distance[p + 1],
                                                            y == h - 1 ? 0 : distance[p + w]));
            }
        int[] candidate = before.clone();
        int changedAfterFeather = 0;
        for (int p = 0; p < candidate.length; p++) {
            if (distance[p] >= 3) candidate[p] = after[p];
            else if (distance[p] == 2) {
                int blended = 0;
                for (int shift = 0; shift < 32; shift += 8)
                    blended |=
                            (((before[p] >>> shift & 255) + (after[p] >>> shift & 255) + 1) / 2)
                                    << shift;
                candidate[p] = blended;
            }
            int delta =
                    (Math.abs((before[p] >> 16 & 255) - (candidate[p] >> 16 & 255))
                                    + Math.abs((before[p] >> 8 & 255) - (candidate[p] >> 8 & 255))
                                    + Math.abs((before[p] & 255) - (candidate[p] & 255)))
                            / 3;
            if (editable[p] && delta > 8) changedAfterFeather++;
        }
        Path dir = out.resolve("requests").resolve(row.getString("id")).resolve("合成审查");
        Files.createDirectories(dir);
        BufferedImage original = image(before, w, h),
                returned = image(after, w, h),
                masked = image(candidate, w, h);
        ImageIO.write(original, "png", dir.resolve("原图ROI.png").toFile());
        ImageIO.write(returned, "png", dir.resolve("模型返回_缩回原尺寸.png").toFile());
        ImageIO.write(masked, "png", dir.resolve("仅目标2px羽化_诊断候选.png").toFile());
        int scale = Math.max(1, Math.min(4, 900 / h));
        BufferedImage panel =
                new BufferedImage(w * scale * 3 + 16, h * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = panel.createGraphics();
        g.setColor(new Color(220, 220, 220));
        g.fillRect(0, 0, panel.getWidth(), panel.getHeight());
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        BufferedImage[] views = {original, returned, masked};
        for (int i = 0; i < views.length; i++)
            g.drawImage(views[i], i * (w * scale + 8), 0, w * scale, h * scale, null);
        g.dispose();
        ImageIO.write(panel, "png", dir.resolve("原图_返回_诊断候选.png").toFile());
        panel.flush();
        original.flush();
        returned.flush();
        masked.flush();
        JSONObject evidence =
                new JSONObject()
                        .put("insidePixels", inside)
                        .put("outsidePixels", outside)
                        .put("modelOutsideMeanDelta", outsideError / (double) Math.max(1, outside))
                        .put("productionOutsideMeanThreshold", 45)
                        .put("rawChangedInsideOverDelta8", rawChanged)
                        .put("transparentInside", transparent)
                        .put("changedAfterDiagnosticFeatherOverDelta8", changedAfterFeather)
                        .put("diagnosticOnly", true)
                        .put("diagnosticCandidateDoesNotOverrideProductionRejection", true)
                        .put("comparison", relative(out, dir.resolve("原图_返回_诊断候选.png")));
        save(dir.resolve("结果.json"), evidence);
        row.put("pixelDiagnostics", evidence);
    }

    static void repaired(
            BufferedImage source,
            BufferedImage current,
            Region r,
            String text,
            JSONObject row,
            Path out,
            JSONObject request)
            throws Exception {
        row.put("processingStage", "request_response").put("pixelCompositeAccepted", false);
        if (request == null) throw new Exception("图像去字请求尚未开始");
        if (!request.optBoolean("success"))
            throw new Exception(
                    "图像去字未成功：" + request.optString("failure", request.optString("status")));
        row.put("processingStage", "request_identity");
        JSONObject input = row.getJSONObject("cleanupInput");
        Path inputFile = resolved(out, input.getString("inputPng"));
        if (!hash(inputFile).equals(input.getString("inputSha256"))
                || !input.getString("inputSha256").equals(request.getString("inputSha256")))
            throw new Exception("请求输入SHA不匹配");
        for (String field : new String[] {"width", "height"})
            if (request.getInt(field) != input.getInt(field)) throw new Exception("请求尺寸元数据不匹配");
        for (String field : new String[] {"targetBoxes", "protectedBoxes"})
            if (!request.getJSONArray(field)
                    .toString()
                    .equals(input.getJSONArray(field).toString()))
                throw new Exception("请求保护/目标框元数据不匹配");
        Path returned = resolved(out, request.getString("returnedPath"));
        if (!hash(returned).equals(request.getString("outputSha256")))
            throw new Exception("模型输出SHA不匹配");
        row.put("processingStage", "returned_size_decode");
        BufferedImage result = ImageIO.read(returned.toFile());
        if (result == null) throw new Exception("模型输出解码失败");
        int w = input.getInt("width"), h = input.getInt("height");
        row.put("returnedWidth", result.getWidth())
                .put("returnedHeight", result.getHeight())
                .put("inputWidth", w)
                .put("inputHeight", h)
                .put("outputSha256", request.getString("outputSha256"))
                .put("returnedPath", relative(out, returned));
        ImageCleanup.validateReturnedSize(result.getWidth(), result.getHeight(), w, h);
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D resizer = scaled.createGraphics();
        resizer.setComposite(AlphaComposite.Src);
        resizer.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        resizer.drawImage(result, 0, 0, w, h, null);
        resizer.dispose();
        int[] crop = ints(input.getJSONArray("sourceCrop"));
        int[][] targets = boxes(input.getJSONArray("targetBoxes")),
                protectedBoxes = boxes(input.getJSONArray("protectedBoxes"));
        row.put("processingStage", "production_pixel_composite");
        int[] before = source.getRGB(crop[0], crop[1], w, h, null, 0, w),
                after = scaled.getRGB(0, 0, w, h, null, 0, w);
        boolean[] editable = RepairPixels.editable(w, h, targets, protectedBoxes);
        after = normalizeReturned(before, after, w, h, editable, result, row);
        scaled.setRGB(0, 0, w, h, after, 0, w);
        diagnostics(out, row, before, after, w, h, editable);
        int[] merged = RepairPixels.composite(before, after, w, h, editable);
        int exterior = 0;
        for (int p = 0; p < before.length; p++)
            if (!editable[p] && merged[p] != before[p]) exterior++;
        if (exterior != 0) throw new Exception("生产合成改动保护区");
        row.put("pixelCompositeAccepted", true).put("processingStage", "protected_text_layout");
        ImageIO.write(
                image(merged, w, h),
                "png",
                out.resolve("requests")
                        .resolve(row.getString("id"))
                        .resolve("合成审查/生产保护合成_仅去字.png")
                        .toFile());
        NearbyTextLayout.Plan plan =
                nearby(source.getWidth(), source.getHeight(), r, crop[0], crop[1], text);
        if (paragraphAware()) protectedFitEvidence(row, plan, protectedBoxes);
        fontEvidence(row, plan);
        JSONArray cells = new JSONArray();
        for (int i = 0; i < plan.points.length; i++) {
            float x = plan.cellLeft(i), y = plan.cellTop(i), step = cellStep(plan, i);
            cells.put(new JSONArray(new float[] {x, y, x + step, y + step}));
            for (int[] b : protectedBoxes)
                if (x < b[2] + 2 && x + step > b[0] - 2 && y < b[3] + 2 && y + step > b[1] - 2)
                    throw new Exception("修图译文覆盖其他段");
        }
        row.put("processingStage", "desktop_glyph_raster");
        BufferedImage staged = image(merged, w, h);
        Graphics2D glyphs = staged.createGraphics();
        drawInPlace(glyphs, plan, Color.BLACK);
        glyphs.dispose();
        int[] filled = staged.getRGB(0, 0, w, h, null, 0, w);
        int commit = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (editable[y * w + x]) {
                    current.setRGB(crop[0] + x, crop[1] + y, filled[y * w + x]);
                    commit++;
                }
        row.put("processingStage", "committed");
        row.put("status", row.optBoolean("apiSubmitted") ? "api_live" : "api_cached")
                .put("compositeApplied", true)
                .put("cleanupApplied", true)
                .put("layoutApplied", true)
                .put("font", plan.font)
                .put("glyphCount", plan.points.length)
                .put("cells", cells)
                .put("cellsCoordinateSpace", "sourceCrop")
                .put("returnedWidth", result.getWidth())
                .put("returnedHeight", result.getHeight())
                .put("inputWidth", w)
                .put("inputHeight", h)
                .put("outputSha256", request.getString("outputSha256"))
                .put("returnedPath", relative(out, returned))
                .put("outsideCompositeChangedPixels", exterior)
                .put("committedEditablePixels", commit)
                .put("protectedLayoutPassed", true)
                .put(
                        "resizeMethod",
                        row.getJSONObject("returnNormalization")
                                        .optBoolean("strictPaddingEvidenceAccepted")
                                ? "production_java_verified_padding_bilinear"
                                : "desktop_awt_bilinear");
        result.flush();
        scaled.flush();
        staged.flush();
    }

    static void fallback(
            BufferedImage current, List<Region> all, Region r, String text, JSONObject row)
            throws Exception {
        NearbyTextLayout.Plan plan =
                paragraphAware()
                        ? nearby(current.getWidth(), current.getHeight(), r, 0, 0, text)
                        : NearbyTextLayout.plan(
                                current.getWidth(),
                                current.getHeight(),
                                box(r.box),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                text,
                                null);
        if (paragraphAware()) {
            List<int[]> protections = new ArrayList<>();
            for (Region other : all) if (!other.id.equals(r.id)) protections.add(box(other.box));
            protectedFitEvidence(row, plan, protections.toArray(new int[0][]));
        }
        Graphics2D g = current.createGraphics();
        Area clip =
                new Area(
                        new Rectangle(
                                plan.box[0],
                                plan.box[1],
                                plan.box[2] - plan.box[0],
                                plan.box[3] - plan.box[1]));
        for (Region other : all)
            if (!other.id.equals(r.id))
                clip.subtract(
                        new Area(
                                new Rectangle(
                                        other.box.left - 2,
                                        other.box.top - 2,
                                        other.box.width() + 4,
                                        other.box.height() + 4)));
        g.clip(clip);
        drawInPlace(g, plan, Color.RED);
        g.dispose();
        row.put("status", "failed_red")
                .put("compositeApplied", false)
                .put("cleanupApplied", false)
                .put("layoutApplied", true)
                .put("originalInkPreserved", true)
                .put("font", plan.font)
                .put("glyphCount", plan.points.length)
                .put("fallbackVertical", plan.vertical);
        fontEvidence(row, plan);
    }

    static JSONObject counts(JSONArray regions) {
        JSONObject result = new JSONObject();
        int requests = 0,
                submitted = 0,
                success = 0,
                applied = 0,
                reused = 0,
                historicalCalls = 0,
                historicalSuccess = 0;
        for (Object raw : regions) {
            JSONObject r = (JSONObject) raw;
            String s = r.getString("status");
            result.put(s, result.optInt(s) + 1);
            requests += r.optInt("actualRequests");
            if (r.optBoolean("apiSubmitted")) submitted++;
            if (r.optBoolean("apiSuccess")) success++;
            if (r.getString("status").startsWith("api_") && r.optBoolean("compositeApplied"))
                applied++;
            if (r.has("reusedFrom")) {
                reused++;
                historicalCalls += r.optInt("historicalActualRequests");
                if (r.optBoolean("apiSuccess")) historicalSuccess++;
            }
        }
        return result.put("regions", regions.length())
                .put("actualRequests", requests)
                .put("paidApiRequests", requests == 0 ? 0 : JSONObject.NULL)
                .put("apiSubmittedRegions", submitted)
                .put("apiResponseSuccessRegions", success)
                .put("apiAppliedRegions", applied)
                .put("historicalRawReusedRegions", reused)
                .put("historicalSuccessfulRawRegions", historicalSuccess)
                .put("historicalRequestsReferenced", historicalCalls);
    }

    static BufferedImage annotated(BufferedImage translated, JSONArray rows) {
        BufferedImage marks =
                image(
                        translated.getRGB(
                                0,
                                0,
                                translated.getWidth(),
                                translated.getHeight(),
                                null,
                                0,
                                translated.getWidth()),
                        translated.getWidth(),
                        translated.getHeight());
        Graphics2D g = marks.createGraphics();
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        for (Object raw : rows) {
            JSONObject r = (JSONObject) raw;
            String s = r.getString("status");
            Color color =
                    s.equals("local_white")
                            ? new Color(0, 153, 66)
                            : s.equals("api_live")
                                    ? new Color(156, 38, 207)
                                    : s.equals("api_cached")
                                            ? new Color(0, 117, 204)
                                            : s.equals("model_skip") ? Color.GRAY : Color.RED;
            int[] b = ints(r.getJSONArray("box"));
            g.setColor(color);
            g.setStroke(new BasicStroke(r.optBoolean("apiSubmitted") ? 4 : 2));
            g.drawRect(b[0], b[1], b[2] - b[0], b[3] - b[1]);
            String label =
                    r.getString("regionId")
                            + " "
                            + (s.equals("api_live")
                                    ? "实传并回填"
                                    : s.equals("api_cached")
                                            ? (r.has("reusedFrom") ? "历史实传复用" : "缓存回填")
                                            : s.equals("local_white")
                                                    ? "本地"
                                                    : s.equals("model_skip")
                                                            ? "跳过"
                                                            : r.optBoolean("apiSubmitted")
                                                                    ? "实传未回填"
                                                                    : "红字兜底");
            FontMetrics fm = g.getFontMetrics();
            int y = Math.max(fm.getAscent(), b[1] - 3),
                    x = Math.min(b[0], Math.max(0, marks.getWidth() - fm.stringWidth(label) - 4));
            g.setColor(Color.WHITE);
            g.fillRect(x, y - fm.getAscent(), fm.stringWidth(label) + 4, fm.getHeight());
            g.setColor(color);
            g.drawString(label, x + 2, y);
        }
        g.dispose();
        return marks;
    }

    static void render(Path input, Path out, Path project) throws Exception {
        JSONObject plan = json(out.resolve("translation_plan.json"));
        if (plan.optBoolean("precomputedRegions") != storedRegions())
            throw new Exception("Stored-region mode changed after prepare");
        if (plan.optBoolean("paragraphBoundsRecovery") != paragraphAware())
            throw new Exception("渲染模式与prepare不一致");
        JSONObject frozen = plan.getJSONObject("productionSourceSha256"), now = hashes(project);
        for (String key : frozen.keySet())
            if (!frozen.getString(key).equals(now.getString(key)))
                throw new Exception("生产代码在prepare之后发生变化：" + key);
        JSONArray pages = new JSONArray(), allRows = new JSONArray();
        int unfinished = 0;
        for (Object raw : plan.getJSONArray("pages")) {
            JSONObject p = (JSONObject) raw;
            int page = p.getInt("page");
            Path sourceFile = Paths.get(p.getString("sourcePage"));
            if (!hash(sourceFile).equals(p.getString("sourceSha256")))
                throw new Exception("源图变化：" + page);
            BufferedImage source = ImageIO.read(sourceFile.toFile()),
                    current =
                            image(
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
            if (storedRegions()
                    && (!hash(sourceFile.getParent().resolve("段落检测.json"))
                                    .equals(p.getString("precomputedRegionsSha256"))
                            || !hash(sourceFile.getParent().resolve("检测原始结果.json"))
                                    .equals(p.getString("predictionSha256"))))
                throw new Exception("Detection evidence changed after prepare");
            List<Region> regions = readRegions(source, sourceFile.getParent());
            Map<String, Region> byId = new HashMap<>();
            for (Region r : regions) byId.put(r.id, r);
            JSONArray rows = new JSONArray();
            List<JSONObject> pending = new ArrayList<>();
            for (Object v : p.getJSONArray("regions")) {
                JSONObject row =
                        new JSONObject(v.toString())
                                .put("apiSubmitted", false)
                                .put("apiSuccess", false)
                                .put("compositeApplied", false)
                                .put("cleanupApplied", false)
                                .put("layoutApplied", false)
                                .put("actualRequests", 0)
                                .put("cacheUsed", false)
                                .put("reused", false);
                rows.put(row);
                allRows.put(row);
                String route = row.getString("route");
                Region r = byId.get(row.getString("regionId"));
                if (r == null
                        || !new JSONArray(box(r.box))
                                .toString()
                                .equals(row.getJSONArray("box").toString()))
                    throw new Exception("检测框或段ID变化");
                if (route.equals("model_skip") || route.equals("missing_translation")) {
                    row.put("status", route);
                    continue;
                }
                try {
                    if (route.equals("image_api")) {
                        JSONObject request = requestMetadata(out, row);
                        if (request == null
                                || (!request.optBoolean("success")
                                        && !request.optString("status").equals("terminal_failed")))
                            unfinished++;
                        repaired(source, current, r, row.getString("zh"), row, out, request);
                    } else if (route.equals("local_white"))
                        local(source, current, regions, r, row.getString("zh"), row, out);
                    else throw new Exception(row.optString("failure", "去字准备失败"));
                } catch (Exception failure) {
                    row.put("failure", failure.toString())
                            .put(
                                    "rejectedStage",
                                    row.optString(
                                            "processingStage",
                                            route.equals("local_white")
                                                    ? "local_mask_or_layout"
                                                    : "cleanup_prepare"));
                    pending.add(row);
                }
            }
            // Match the engine's two phases: finish all ordinary cleanup before drawing red
            // fallbacks.
            for (JSONObject row : pending)
                fallback(
                        current,
                        regions,
                        byId.get(row.getString("regionId")),
                        row.getString("zh"),
                        row);
            Path dir = out.resolve("逐页").resolve(String.format("第%02d页", page));
            Files.createDirectories(dir);
            Path original = dir.resolve("原图.png"),
                    translated = dir.resolve("中文回填.png"),
                    marked = dir.resolve("标注_实际传图回填区域.png"),
                    comparison = dir.resolve("原图_译图对照.png");
            Files.copy(sourceFile, original, StandardCopyOption.REPLACE_EXISTING);
            ImageIO.write(current, "png", translated.toFile());
            BufferedImage marks = annotated(current, rows);
            ImageIO.write(marks, "png", marked.toFile());
            marks.flush();
            BufferedImage side =
                    new BufferedImage(
                            source.getWidth() * 2 + 12,
                            source.getHeight(),
                            BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = side.createGraphics();
            sg.setColor(new Color(220, 220, 220));
            sg.fillRect(0, 0, side.getWidth(), side.getHeight());
            sg.drawImage(source, 0, 0, null);
            sg.drawImage(current, source.getWidth() + 12, 0, null);
            sg.dispose();
            ImageIO.write(side, "png", comparison.toFile());
            side.flush();
            JSONObject pageResult =
                    new JSONObject()
                            .put("schemaVersion", 1)
                            .put("page", page)
                            .put("width", source.getWidth())
                            .put("height", source.getHeight())
                            .put("originalFile", relative(out, original))
                            .put("translatedFile", relative(out, translated))
                            .put("annotatedFile", relative(out, marked))
                            .put("comparisonFile", relative(out, comparison))
                            .put("originalSha256", hash(original))
                            .put("translatedSha256", hash(translated))
                            .put("counts", counts(rows))
                            .put("regions", rows)
                            .put(
                                    "freshTranslationsFromThisBatch",
                                    plan.optBoolean("freshTranslationsFromThisBatch"))
                            .put(
                                    "cachedRealTranslations",
                                    plan.optBoolean("cachedRealTranslations", true))
                            .put("androidCanvasVerified", false);
            save(dir.resolve("结果.json"), pageResult);
            pages.put(pageResult);
            source.flush();
            current.flush();
            System.out.println("RENDERED page=" + page + " " + pageResult.getJSONObject("counts"));
        }
        JSONObject report =
                new JSONObject()
                        .put("schemaVersion", 1)
                        .put("pages", pages)
                        .put("counts", counts(allRows))
                        .put("apiRegionsUnfinished", unfinished)
                        .put("allApiRegionsTerminal", unfinished == 0)
                        .put("productionSourceSha256", now)
                        .put(
                                "freshTranslationsFromThisBatch",
                                plan.optBoolean("freshTranslationsFromThisBatch"))
                        .put(
                                "cachedRealTranslations",
                                plan.optBoolean("cachedRealTranslations", true))
                        .put("networkCallsByRenderer", 0)
                        .put("androidCanvasVerified", false)
                        .put(
                                "validationScope",
                                "Current production detector-anchor, cleanup, pixel-composite and"
                                    + " layout algorithms; Desktop AWT fonts/resampling. Does not"
                                    + " prove Android Canvas, Android codec sampling, or new"
                                    + " OCR/translation API calls.");
        save(out.resolve("all_pages_results.json"), report);
        System.out.println(
                "RENDER COMPLETE " + report.getJSONObject("counts") + " unfinished=" + unfinished);
    }

    static void diagnose(Path out, String id, Path project) throws Exception {
        for (Object raw : json(out.resolve("translation_plan.json")).getJSONArray("pages")) {
            JSONObject p = (JSONObject) raw;
            for (Object value : p.getJSONArray("regions")) {
                JSONObject row = new JSONObject(value.toString());
                if (!row.getString("id").equals(id)) continue;
                Path sourceFile = Paths.get(p.getString("sourcePage"));
                BufferedImage source = ImageIO.read(sourceFile.toFile()),
                        scratch =
                                image(
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
                Region target = null;
                for (Region r : readRegions(source, sourceFile.getParent()))
                    if (r.id.equals(row.getString("regionId"))) target = r;
                if (target == null) throw new Exception("Unknown region");
                try {
                    repaired(
                            source,
                            scratch,
                            target,
                            row.getString("zh"),
                            row,
                            out,
                            requestMetadata(out, row));
                } catch (Exception failure) {
                    row.put("failure", failure.toString())
                            .put("rejectedStage", row.optString("processingStage"));
                }
                row.put("writesToTranslatedPage", false)
                        .put("diagnosticProductionSourceSha256", hashes(project));
                if (row.optBoolean("compositeApplied")) {
                    int[] b = ints(row.getJSONObject("cleanupInput").getJSONArray("sourceCrop"));
                    Path candidate =
                            out.resolve("requests").resolve(id).resolve("合成审查/单段诊断_中文回填ROI.png");
                    BufferedImage cropImage =
                            image(
                                    scratch.getRGB(
                                            b[0],
                                            b[1],
                                            b[2] - b[0],
                                            b[3] - b[1],
                                            null,
                                            0,
                                            b[2] - b[0]),
                                    b[2] - b[0],
                                    b[3] - b[1]);
                    ImageIO.write(cropImage, "png", candidate.toFile());
                    cropImage.flush();
                    row.put("diagnosticChineseRoiFile", relative(out, candidate));
                }
                save(out.resolve("requests").resolve(id).resolve("合成审查/单段处理结果.json"), row);
                System.out.println(
                        "DIAGNOSTIC "
                                + id
                                + " "
                                + row.optString("status", row.optString("failure")));
                return;
            }
        }
        throw new Exception("Unknown sample " + id);
    }

    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args[1]), out = Paths.get(args[2]), project = Paths.get(args[3]);
        Files.createDirectories(out);
        if (args[0].equals("prepare")) prepare(input, out, project);
        else if (args[0].equals("render")) render(input, out, project);
        else if (args[0].equals("diagnose")) diagnose(out, args[4], project);
        else throw new IllegalArgumentException("Unknown mode");
    }
}
