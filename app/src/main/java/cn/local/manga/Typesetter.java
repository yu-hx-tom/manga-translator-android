package cn.local.manga;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Local text placement shared by translation rendering (TranslationEngine.renderTextPage) and the
 * workbench editor (PageComposer). The drawing code was moved here verbatim from renderLocal/drawInPlace,
 * so with {@link Style#DEFAULT} both callers produce identical pixels.
 *
 * A white-paper region is split into a text-independent {@link Bubble} (cleanup + safe layout area,
 * computed once) and a cheap {@link #typesetBubble} step that the editor re-runs on every text/style change.
 */
final class Typesetter {
    private Typesetter() {}

    /** Workbench overrides. DEFAULT reproduces the translation output exactly. */
    static final class Style {
        static final Style DEFAULT = new Style(1f, null, 0);
        /** Multiplies the preferred lettering size; layout still stays inside the safe area. */
        final float scale;
        /** null = keep the detected direction. */
        final Boolean vertical;
        /** 0 = black; in-place task/draft defaults are supplied by the caller. */
        final int color;
        Style(float scale, Boolean vertical, int color) { this.scale = scale; this.vertical = vertical; this.color = color; }
        boolean vertical(boolean detected) { return vertical == null ? detected : vertical; }
        boolean isDefault() { return scale == 1f && vertical == null && color == 0; }
    }

    /** Text-independent preparation of one white-paper region: what to erase and where text may go. */
    static final class Bubble {
        final int left, top, w, h, estimate;
        final boolean[] erase, layoutArea;
        final int[] fillColors;
        final int[][] localLines, foreignLines;
        final boolean solid, vertical;
        Bubble(int left, int top, int w, int h, boolean[] erase, int[] fillColors, boolean[] layoutArea, int estimate,
               int[][] localLines, int[][] foreignLines, boolean solid, boolean vertical) {
            this.left = left; this.top = top; this.w = w; this.h = h; this.erase = erase; this.fillColors = fillColors;
            this.layoutArea = layoutArea; this.estimate = estimate; this.localLines = localLines; this.foreignLines = foreignLines;
            this.solid = solid; this.vertical = vertical;
        }
        Rect bounds() { return new Rect(left, top, left + w, top + h); }
    }

    /**
     * First half of the former renderLocal: verifies the region can be cleaned locally and derives the
     * cleanup mask and layout area. Throws with the original Chinese reason when it must fall back to
     * in-place text. {@code region} is the rendering region (expanded bounds); {@code mask} may be null.
     */
    static Bubble prepareBubble(Bitmap crop, Region region, Rect originalBounds, List<Region> allRegions, WhiteBubbleCleaner.Mask mask) throws Exception {
        int w = crop.getWidth(), h = crop.getHeight(), count = w * h;
        if (count > 4_000_000) throw new Exception("单个文字区域过大，请缩小图片后重试");
        int[] pixels = new int[count];
        crop.getPixels(pixels, 0, w, 0, 0, w, h);
        TranslationEngine.CleanupGeometry geometry = new TranslationEngine.CleanupGeometry(region, w, h);
        int estimate = geometry.estimate; int[][] localLines = geometry.lines;
        if (mask == null) mask = WhiteBubbleCleaner.forText(pixels, w, h, localLines, estimate, relativeBounds(originalBounds, region.box));
        int[][] foreignLines = RtDetrRegions.foreignLines(allRegions, region.id, region.box.left, region.box.top);
        // One white balloon may contain multiple detected paragraphs. Clean only this paragraph's unprotected ink.
        boolean[] erase = WhiteBubbleCleaner.rectangleMask(mask, pixels, w, h, relativeBounds(originalBounds, region.box), localLines, foreignLines);
        int erased = 0; for (boolean pixel : erase) if (pixel) erased++;
        mask = new WhiteBubbleCleaner.Mask(erase, mask.interior, mask.whiteBackground, erased, mask.fillColors, mask.texturedBackground, mask.glyphCandidate, mask.backgroundKind, mask.evidence);
        // Test against the immutable source PNG, including regions that already succeeded, before drawing any layer.
        if (WhiteBubbleCleaner.touchesForeignInk(pixels, w, h, mask, foreignLines, localLines))
            throw new Exception("同一气泡被分成多段，清除会影响其他段，已保留本段原文");
        boolean solid = mask.whiteBackground;
        boolean[] layoutArea = (solid || mask.texturedBackground) ? mask.interior : WhiteBubbleCleaner.complexTextArea(pixels, w, h, localLines);
        if (!WhiteBubbleCleaner.canClean(mask)) throw new Exception("背景文字使用原位嵌字");
        if (mask.pixels == 0) throw new Exception("文字与受保护段落重叠，已保留原文");
        if (layoutArea == null) throw new Exception("未能确认文字背景，已保留原文");
        layoutArea = WhiteBubbleCleaner.excludeForeign(layoutArea, w, h, foreignLines);
        return new Bubble(region.box.left, region.box.top, w, h, mask.erase, mask.fillColors, layoutArea, estimate, localLines, foreignLines, solid, region.vertical);
    }

    /** Cleanup layer: erased pixels take the verified paper colour; everything else stays transparent. */
    static Bitmap patch(Bubble bubble) {
        int count = bubble.w * bubble.h;
        int[] pixels = new int[count]; // 0 == Color.TRANSPARENT
        for (int i = 0; i < count; i++) if (bubble.erase[i]) pixels[i] = bubble.fillColors == null ? Color.WHITE : bubble.fillColors[i];
        return Bitmap.createBitmap(pixels, bubble.w, bubble.h, Bitmap.Config.ARGB_8888);
    }

    /** Glyph layer for a bubble, positioned in page coordinates. Close it to free the bitmap. */
    static final class BubbleText implements AutoCloseable {
        final Bitmap layer; final int left, top; final float font;
        BubbleText(Bitmap layer, int left, int top, float font) { this.layer = layer; this.left = left; this.top = top; this.font = font; }
        Rect bounds() { return new Rect(left, top, left + layer.getWidth(), top + layer.getHeight()); }
        @Override public void close() { if (!layer.isRecycled()) layer.recycle(); }
    }

    /** Second half of the former renderLocal: lays out and draws the translation into a transparent layer. */
    static BubbleText typesetBubble(Bubble bubble, String translation, Style style) throws Exception {
        int w = bubble.w, h = bubble.h;
        BubbleLayout.Plan plan = BubbleLayout.plan(translation, bubble.layoutArea, w, h, style.vertical(bubble.vertical), bubble.estimate, bubble.localLines, style.scale);
        if (BubbleLayout.touchesForeignLines(plan, bubble.foreignLines))
            throw new Exception("译文会覆盖其他文字段，已保留本段原文");
        // Plan and draw the complete transparent layer before committing any cleanup to the page.
        int layerLeft = w, layerTop = h, layerRight = 0, layerBottom = 0;
        for (int[] cell : plan.cells) { layerLeft = Math.min(layerLeft, cell[0]); layerTop = Math.min(layerTop, cell[1]); layerRight = Math.max(layerRight, cell[2]); layerBottom = Math.max(layerBottom, cell[3]); }
        Bitmap layer = Bitmap.createBitmap(layerRight - layerLeft, layerBottom - layerTop, Bitmap.Config.ARGB_8888);
        try {
            int ink = style.color == 0 ? Color.BLACK : style.color;
            // This bitmap contains Chinese glyphs only; its unused pixels retain alpha=0.
            Canvas canvas = new Canvas(layer); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            paint.setTextAlign(Paint.Align.LEFT); paint.setColor(ink); Rect bounds = new Rect();
            for (int i = 0; i < plan.cells.length; i++) {
                paint.setTextSize(plan.cellFont(i));
                int[] cell = plan.cells[i]; String character = new String(Character.toChars(plan.codepoints[i]));
                paint.getTextBounds(character, 0, character.length(), bounds);
                // Font overhang cannot escape its proven interior cell, even for unusual fallback glyphs.
                canvas.save(); canvas.clipRect(cell[0] - layerLeft, cell[1] - layerTop, cell[2] - layerLeft, cell[3] - layerTop);
                float scale = Math.min(1f, Math.min(Math.max(1, cell[2] - cell[0] - 2) / (float) Math.max(1, bounds.width()), Math.max(1, cell[3] - cell[1] - 2) / (float) Math.max(1, bounds.height())));
                canvas.translate((cell[0] + cell[2]) / 2f - layerLeft, (cell[1] + cell[3]) / 2f - layerTop); canvas.scale(scale, scale);
                float cx = -(bounds.left + bounds.right) / 2f, baseline = -(bounds.top + bounds.bottom) / 2f;
                if (!bubble.solid) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1.5f, plan.cellFont(i) * .13f)); paint.setColor(Color.WHITE); canvas.drawText(character, cx, baseline, paint); }
                paint.setStyle(Paint.Style.FILL); paint.setColor(ink); canvas.drawText(character, cx, baseline, paint); canvas.restore();
            }
        } catch (RuntimeException | Error failure) { layer.recycle(); throw failure; }
        return new BubbleText(layer, bubble.left + layerLeft, bubble.top + layerTop, plan.font);
    }

    /** Commits a prepared bubble: cleanup first, then the glyph layer (the former renderLocal order). */
    static void draw(Canvas page, Bubble bubble, Bitmap patch, BubbleText text) {
        page.drawBitmap(patch, bubble.left, bubble.top, null);
        page.drawBitmap(text.layer, text.left, text.top, null);
    }

    /** In-place text over the original strokes (background text). Drawn after every bubble on the page. */
    static final class NearbyText {
        final NearbyTextLayout.Plan plan; final int[][] clipOut; final int color;
        NearbyText(NearbyTextLayout.Plan plan, int[][] clipOut, int color) { this.plan = plan; this.clipOut = clipOut; this.color = color; }
        float font() { return plan.font; }
        Rect bounds() { return new Rect(plan.box[0], plan.box[1], plan.box[2], plan.box[3]); }
    }

    /** The former renderNearby layout: inside the detected box, moved off other paragraphs when possible. */
    static NearbyText planNearby(int pageWidth, int pageHeight, Region region, List<Region> regions, String text, Style style) {
        NearbyTextLayout.Plan plan = NearbyTextLayout.forCrop(pageWidth, pageHeight, bounds(region.box), 0, 0, style.vertical(region.vertical), text, absoluteLines(region), style.scale);
        List<int[]> protectedBoxes = new ArrayList<>(), clipOut = new ArrayList<>();
        for (Region other : regions) if (!other.id.equals(region.id)) {
            protectedBoxes.add(bounds(other.box));
            clipOut.add(new int[]{other.box.left - 2, other.box.top - 2, other.box.right + 2, other.box.bottom + 2});
        }
        NearbyTextLayout.fitProtected(plan, protectedBoxes.toArray(new int[0][]));
        return new NearbyText(plan, clipOut.toArray(new int[0][]), style.color == 0 ? Color.BLACK : style.color);
    }

    static void draw(Canvas canvas, NearbyText text, BooleanSupplier cancelled) {
        int saved = canvas.save();
        try {
            int[] box = text.plan.box;
            canvas.clipRect(box[0], box[1], box[2], box[3]);
            for (int[] other : text.clipOut) canvas.clipOutRect(other[0], other[1], other[2], other[3]);
            drawInPlace(canvas, text.plan, text.color, cancelled);
        } finally { canvas.restoreToCount(saved); }
    }

    /** White-outlined glyphs in their planned cells (moved verbatim from TranslationEngine). */
    private static void drawInPlace(Canvas canvas, NearbyTextLayout.Plan plan, int color, BooleanSupplier cancelled) {
        int[] box = plan.box; Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < plan.points.length; i++) {
            check(cancelled); String character = new String(Character.toChars(plan.points[i]));
            float x = plan.cellLeft(i), y = plan.cellTop(i), step = plan.cellStep(i), font = plan.cellFont(i);
            paint.setTextSize(font); Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.save(); canvas.clipRect(x, y, Math.min(box[2], x + step), Math.min(box[3], y + step));
            float width = paint.measureText(character), scale = Math.min(1f, step * .78f / Math.max(1f, Math.max(width, fm.descent - fm.ascent)));
            canvas.translate(x + step / 2, y + step / 2); canvas.scale(scale, scale);
            float baseline = -(fm.ascent + fm.descent) / 2;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeWidth(Math.max(.1f, font * .14f)); paint.setColor(Color.WHITE); canvas.drawText(character, 0, baseline, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(color); canvas.drawText(character, 0, baseline, paint); canvas.restore();
        }
    }

    static int[] bounds(Rect box) { return new int[]{box.left, box.top, box.right, box.bottom}; }
    static int[] relativeBounds(Rect box, Rect origin) { return new int[]{box.left - origin.left, box.top - origin.top, box.right - origin.left, box.bottom - origin.top}; }
    static int[][] absoluteLines(Region region) { int[][] lines = new int[region.lines.size()][]; for (int i = 0; i < lines.length; i++) lines[i] = bounds(region.lines.get(i)); return lines; }
    private static void check(BooleanSupplier cancelled) { if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException("已取消"); }
}
