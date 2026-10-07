package cn.local.manga;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Re-renders one draft page with user edits, replaying TranslationEngine.renderTextPage exactly:
 * per region in detection order "bubble cleanup + glyphs" (via Typesetter), falling back to in-place text,
 * and all in-place text drawn last. With no edits the result equals the translation output.
 *
 * Text-independent bubble preparation is cached per region, so an edit only re-typesets that region
 * (milliseconds) and recomposes the page. Not thread-safe across instances' bitmaps: callers use one
 * worker thread per composer.
 */
final class PageComposer implements AutoCloseable {
    /** User overrides for one region; null text = machine translation. */
    static final class Edit {
        String text; float scale = 1f; Boolean vertical; int color; boolean hidden, inPlace; TextStyle format=new TextStyle();
        Edit copy() { Edit e = new Edit(); e.text = text; e.scale = scale; e.vertical = vertical; e.color = color; e.hidden = hidden; e.inPlace = inPlace; e.format=format.copy(); return e; }
        boolean isDefault() { return text == null && scale == 1f && vertical == null && color == 0 && !hidden && !inPlace && format.isDefault(); }
        Typesetter.Style style() { return new Typesetter.Style(scale, vertical, color); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Edit)) return false; Edit e = (Edit) o;
            return java.util.Objects.equals(text, e.text) && scale == e.scale && java.util.Objects.equals(vertical, e.vertical)
                    && color == e.color && hidden == e.hidden && inPlace == e.inPlace && format.equals(e.format);
        }
        @Override public int hashCode() { return java.util.Objects.hash(text, scale, vertical, color, hidden, inPlace,format); }
    }

    enum Mode { BUBBLE, IN_PLACE, ORIGINAL }
    /** What the editor shows for a region after layout. */
    static final class Placement {
        final Mode mode; final float font; final String reason; final Rect bounds;
        Placement(Mode mode, float font, String reason, Rect bounds) { this.mode = mode; this.font = font; this.reason = reason; this.bounds = bounds; }
    }

    final PageDraft draft;
    private final Bitmap source;
    private Bitmap clean;
    private final float drawingScale;
    private final Map<String,Edit> custom=new LinkedHashMap<>();
    private final Map<String, Object> bubbles = new HashMap<>();          // Typesetter.Bubble or the Exception why not
    private final Map<String, Bitmap> patches = new HashMap<>();
    private final Map<String, Typesetter.BubbleText> glyphs = new HashMap<>();
    private final Map<String, Typesetter.NearbyText> inPlace = new HashMap<>();
    private final Map<String, Placement> placements = new LinkedHashMap<>();
    private final Paint copy = new Paint();
    private boolean closed;

    PageComposer(PageDraft draft) throws Exception {
        this(draft,0);
    }
    PageComposer(PageDraft draft,int maxDimension) throws Exception {
        this.draft = draft;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize=1;
        if(maxDimension>0&&new java.io.File(draft.dir,"clean.png").isFile())while(Math.max(draft.width,draft.height)/options.inSampleSize>maxDimension)options.inSampleSize*=2;
        source = BitmapFactory.decodeFile(draft.source().getPath(), options);
        if (source == null) throw new java.io.IOException("草稿原图无法读取");
        if (options.inSampleSize==1&&(source.getWidth() != draft.width || source.getHeight() != draft.height)) { source.recycle(); throw new java.io.IOException("草稿原图尺寸不符"); }
        drawingScale=source.getWidth()/(float)draft.width;
        clean=BitmapFactory.decodeFile(new java.io.File(draft.dir,"clean.png").getPath(),options);
        copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
    }

    /** The untouched original page (do not recycle; owned by the composer). */
    Bitmap source() { return source; }

    /** Lays out every region for the given edits (missing entries = machine translation). */
    synchronized void layoutAll(Map<String, Edit> edits, BooleanSupplier cancelled) {
        for (PageDraft.Item item : draft.items) {
            Edit edit = edits == null ? null : edits.get(item.region.id);
            layout(item, edit == null ? new Edit() : edit, cancelled);
        }
    }

    /** Re-typesets one region; cheap after the first call for that region. */
    synchronized Placement layout(String regionId, Edit edit, BooleanSupplier cancelled) {
        PageDraft.Item item = draft.item(regionId);
        if (item == null) throw new IllegalArgumentException("unknown region " + regionId);
        return layout(item, edit == null ? new Edit() : edit, cancelled);
    }

    synchronized Placement placement(String regionId) { return placements.get(regionId); }

    private Placement layout(PageDraft.Item item, Edit edit, BooleanSupplier cancelled) {
        if (closed) throw new CancellationException();
        String id = item.region.id;
        release(id);
        custom.remove(id);
        String text = edit.text != null ? edit.text : item.machine;
        Placement placement;
        if(edit.format.deleted||edit.hidden) placement=new Placement(Mode.ORIGINAL,0,"不渲染此条文字",null);
        else if(!edit.format.isDefault()) { custom.put(id,edit.copy());placement=new Placement(Mode.BUBBLE,edit.format.fontSize,null,edit.format.box==null?item.region.box:edit.format.box); }
        else if (text == null || text.trim().isEmpty()) placement = new Placement(Mode.ORIGINAL, 0, item.translated() ? "译文为空，显示原图" : "没有译文，保留原图", null);
        else {
            Typesetter.Style style = edit.style();
            String why = null;
            if (!edit.inPlace) {
                try {
                    Typesetter.Bubble bubble = bubble(item, cancelled);
                    Typesetter.BubbleText glyph = Typesetter.typesetBubble(bubble, text, style);
                    glyphs.put(id, glyph);
                    Rect bounds = bubble.bounds(); bounds.union(glyph.bounds());
                    placement = new Placement(Mode.BUBBLE, glyph.font, null, bounds);
                    placements.put(id, placement);
                    return placement;
                } catch (CancellationException stop) { throw stop; }
                catch (Exception refused) { why = refused.getMessage(); }
            } else why = "已改为原位排字";
            try {
                Typesetter.NearbyText text2 = Typesetter.planNearby(draft.width, draft.height, item.region, draft.protection, text, style);
                inPlace.put(id, text2);
                placement = new Placement(Mode.IN_PLACE, text2.font(), why, text2.bounds());
            } catch (RuntimeException unplaceable) {
                placement = new Placement(Mode.ORIGINAL, 0, unplaceable.getMessage() == null ? "无法排入译文" : unplaceable.getMessage(), null);
            }
        }
        placements.put(id, placement);
        return placement;
    }

    /** Cached, text-independent cleanup preparation, exactly as renderTextPage computes it. */
    private Typesetter.Bubble bubble(PageDraft.Item item, BooleanSupplier cancelled) throws Exception {
        Object cached = bubbles.get(item.region.id);
        if (cached instanceof Typesetter.Bubble) return (Typesetter.Bubble) cached;
        if (cached instanceof Exception) throw (Exception) cached;
        Bitmap crop = null;
        try {
            java.io.File stored=new java.io.File(draft.dir,"bubble-"+item.index+".bin");
            if(stored.isFile()){Typesetter.Bubble b=BubbleStore.read(stored);bubbles.put(item.region.id,b);patches.put(item.region.id,Typesetter.patch(b));return b;}
            if(clean!=null)throw new Exception("此区域未能安全去字，使用原位排字");
            Region rendering = TranslationEngine.renderRegion(item.region, draft.width, draft.height);
            Rect box = rendering.box;
            if (box.isEmpty() || box.left < 0 || box.top < 0 || box.right > draft.width || box.bottom > draft.height) throw new Exception("原图区域读取失败");
            crop = Bitmap.createBitmap(source, box.left, box.top, box.width(), box.height());
            WhiteBubbleCleaner.Mask mask = CleanupPlan.read(draft.mask(item.index), crop.getWidth(), crop.getHeight(), cancelled);
            Typesetter.Bubble bubble = Typesetter.prepareBubble(crop, rendering, item.region.box, draft.protection, mask);
            bubbles.put(item.region.id, bubble);
            patches.put(item.region.id, Typesetter.patch(bubble));
            return bubble;
        } catch (CancellationException stop) { throw stop; }
        catch (Exception refused) { bubbles.put(item.region.id, refused); throw refused; }
        finally { if (crop != null && crop != source) crop.recycle(); }
    }

    /**
     * Draws the page into {@code target} (same size, mutable) or a new bitmap when null.
     * Order matches renderTextPage: bubbles in detection order, then every in-place text.
     */
    synchronized Bitmap compose(Bitmap target) {
        if (closed) throw new CancellationException();
        if (target == null || target.getWidth() != source.getWidth() || target.getHeight() != source.getHeight() || !target.isMutable())
            target = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(target);
        canvas.drawBitmap(clean==null?source:clean, 0, 0, copy);
        canvas.scale(drawingScale,source.getHeight()/(float)draft.height);
        List<Typesetter.NearbyText> late = new ArrayList<>();
        for (PageDraft.Item item : draft.items) {
            Placement placement = placements.get(item.region.id);
            if (placement == null) continue;
            Edit edit=custom.get(item.region.id);
            if(edit!=null){TextRenderer.draw(canvas,item,edit);continue;}
            if (placement.mode == Mode.BUBBLE) {
                Object bubble = bubbles.get(item.region.id);
                Bitmap patch = patches.get(item.region.id);
                Typesetter.BubbleText glyph = glyphs.get(item.region.id);
                if (bubble instanceof Typesetter.Bubble && patch != null && glyph != null) {
                    if(clean==null)Typesetter.draw(canvas,(Typesetter.Bubble)bubble,patch,glyph);
                    else canvas.drawBitmap(glyph.layer,glyph.left,glyph.top,null);
                }
            } else if (placement.mode == Mode.IN_PLACE) {
                Typesetter.NearbyText text = inPlace.get(item.region.id);
                if (text != null) late.add(text);
            }
        }
        for (Typesetter.NearbyText text : late) Typesetter.draw(canvas, text, () -> false);
        return target;
    }

    /** Run once on the pipeline worker, before committing the draft. */
    synchronized void persistLayers(Bitmap rendered) throws Exception {
        Bitmap background=source.copy(Bitmap.Config.ARGB_8888,true);Canvas c=new Canvas(background);
        try{
            for(PageDraft.Item item:draft.items){
                if(!item.translated())continue;
                try{Typesetter.Bubble b=bubble(item,()->false);Bitmap p=patches.get(item.region.id);c.drawBitmap(p,b.left,b.top,null);BubbleStore.write(new java.io.File(draft.dir,"bubble-"+item.index+".bin"),b);}catch(java.io.IOException failure){throw failure;}catch(Exception refused){/* Preserve original pixels for backgrounds which the existing cleaner refuses. */}
            }
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(draft.dir,"clean.png"))){if(!background.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("无法写入去字底图");}
            if(rendered!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(draft.dir,"rendered.png"))){if(!rendered.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("无法写入合成缓存");}
        }finally{background.recycle();}
    }

    private void release(String id) {
        Typesetter.BubbleText glyph = glyphs.remove(id);
        if (glyph != null) glyph.close();
        inPlace.remove(id);
        placements.remove(id);
    }

    @Override public synchronized void close() {
        closed = true;
        for (Typesetter.BubbleText glyph : glyphs.values()) glyph.close();
        for (Bitmap patch : patches.values()) if (!patch.isRecycled()) patch.recycle();
        glyphs.clear(); patches.clear(); bubbles.clear(); inPlace.clear(); placements.clear();
        if (!source.isRecycled()) source.recycle();
        if(clean!=null&&!clean.isRecycled())clean.recycle();
    }
}
