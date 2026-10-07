package cn.local.manga;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One image surface for region review and a zoomable original/result comparison. */
public final class ResultView extends View {
    private android.graphics.drawable.Drawable placeholderIcon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final List<Region> regions = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private Bitmap bitmap;
    private boolean showBoxes = true, editable = true;
    private float zoom = 1f, panX = 0f, panY = 0f;
    private Runnable selectionChanged;
    private java.util.function.IntConsumer swipe;
    // Visual-only transition state: previous image cross-fades out, boxes stagger in, a tapped box pulses.
    private Bitmap fading;
    private float fadeIn = 1f, boxesIn = 1f, pulse = 0f;
    private String pulseId;
    private ValueAnimator fadeAnimator, boxAnimator, pulseAnimator, zoomAnimator;
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ResultView(Context context) {
        super(context);
        setContentDescription("漫画预览，可双指缩放，单击文字框选择或取消");
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float old = zoom;
                zoom = Math.max(1f, Math.min(6f, old * detector.getScaleFactor()));
                float focusX = detector.getFocusX() - getWidth() / 2f;
                float focusY = detector.getFocusY() - getHeight() / 2f;
                panX = focusX - (focusX - panX) * zoom / old;
                panY = focusY - (focusY - panY) * zoom / old;
                clampPan();
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public void onLongPress(MotionEvent event){
                if(addBox==null||bitmap==null||regionAt(event.getX(),event.getY())!=null)return;
                float s=fitScale()*zoom;int x=Math.max(0,(int)((event.getX()-left(s))/s)),y=Math.max(0,(int)((event.getY()-top(s))/s));
                addBox.accept(new Rect(x,y,Math.min(bitmap.getWidth(),x+180),Math.min(bitmap.getHeight(),y+100)));
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (bitmap != null && pick != null) {
                    // Workbench: a tap selects one paragraph (or clears the selection on empty space).
                    Region hit = regionAt(event.getX(), event.getY());
                    if (hit != null) startPulse(hit.id);
                    pick.accept(hit == null ? null : hit.id);
                    return true;
                }
                if (bitmap == null || !showBoxes || !editable) return false;
                float scale = fitScale() * zoom;
                float x = (event.getX() - left(scale)) / scale;
                float y = (event.getY() - top(scale)) / scale;
                Region target = null;
                for (Region region : regions) {
                    if (region.box.contains((int) x, (int) y)
                            && (target == null || area(region.box) < area(target.box))) target = region;
                }
                if (target != null) {
                    if (!selected.remove(target.id)) selected.add(target.id);
                    startPulse(target.id);
                    performClick();
                    invalidate();
                    if (selectionChanged != null) selectionChanged.run();
                    return true;
                }
                return false;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                animateZoom(zoom > 1.01f ? 1f : 2.5f, event.getX(), event.getY());
                return true;
            }
            @Override public boolean onFling(MotionEvent first, MotionEvent last, float vx, float vy) {
                // Page turns only at fit size, so panning a zoomed page never flips it by accident.
                if (swipe == null || zoom > 1.01f || Math.abs(vx) < 900 * getResources().getDisplayMetrics().density / 2.5f
                        || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                swipe.accept(vx < 0 ? 1 : -1);
                return true;
            }
            @Override public boolean onScroll(MotionEvent first, MotionEvent last, float dx, float dy) {
                if (zoom <= 1.01f) return false;
                panX -= dx;
                panY -= dy;
                clampPan();
                invalidate();
                return true;
            }
        });
    }

    private static long area(Rect r) { return (long) r.width() * r.height(); }
    private java.util.function.Consumer<String> pick;
    private String highlight;
    private java.util.function.BiConsumer<String,Rect> boxChanged;
    private java.util.function.Consumer<Rect> addBox;
    private Region dragging;private Rect dragStart;private float dragX,dragY;private boolean resizing;
    public void setOnBoxChanged(java.util.function.BiConsumer<String,Rect> listener){boxChanged=listener;}
    public void setOnAddBox(java.util.function.Consumer<Rect> listener){addBox=listener;}
    public void focusRegion(String id){
        if(bitmap==null||id==null)return;
        for(Region r:regions)if(r.id.equals(id)){
            zoom=Math.max(1f,Math.min(6f,Math.min(getWidth()/(r.box.width()*1.8f),getHeight()/(r.box.height()*1.8f))/fitScale()));
            float scale=fitScale()*zoom;panX=(bitmap.getWidth()/2f-r.box.exactCenterX())*scale;panY=(bitmap.getHeight()/2f-r.box.exactCenterY())*scale;clampPan();invalidate();return;
        }
    }
    /** Smallest region under a view coordinate, or null. */
    private Region regionAt(float viewX, float viewY) {
        float scale = fitScale() * zoom;
        float x = (viewX - left(scale)) / scale, y = (viewY - top(scale)) / scale;
        Region target = null;
        for (Region region : regions)
            if (region.box.contains((int) x, (int) y) && (target == null || area(region.box) < area(target.box))) target = region;
        return target;
    }
    /** Workbench mode: taps report a paragraph id instead of toggling selection. */
    public void setOnPick(java.util.function.Consumer<String> listener) { pick = listener; }
    /** Outlines one paragraph (even with boxes hidden); null clears. */
    public void setHighlight(String regionId) {
        if (java.util.Objects.equals(highlight, regionId)) return;
        highlight = regionId;
        if (regionId != null) startPulse(regionId);
        invalidate();
    }
    /** Replaces the picture without cross-fade or zoom reset (live editing of the same page). */
    public void swapBitmap(Bitmap value) {
        if (bitmap == null || value == null || bitmap.getWidth() != value.getWidth() || bitmap.getHeight() != value.getHeight()) { setBitmap(value); return; }
        if (fadeAnimator != null) fadeAnimator.cancel();
        fading = null; fadeIn = 1f;
        bitmap = value;
        invalidate();
    }
    /** Regions for hit-testing/outlines without the review-mode reveal animation. */
    public void setRegionsQuiet(List<Region> values) {
        regions.clear(); regions.addAll(values); selected.clear(); boxesIn = 1f; invalidate();
    }
    public void setSelectionChanged(Runnable listener) { selectionChanged = listener; }
    /** Horizontal fling at fit size: +1 = next page, -1 = previous. */
    public void setOnSwipe(java.util.function.IntConsumer listener) { swipe = listener; }
    public void resetZoom() { if (zoomAnimator != null) zoomAnimator.cancel(); zoom = 1f; panX = panY = 0f; invalidate(); }
    public void setEditable(boolean value) { editable = value; }
    public void setShowBoxes(boolean value) {
        if (value && !showBoxes && !regions.isEmpty()) revealBoxes();
        showBoxes = value; invalidate();
    }
    public void setBitmap(Bitmap value) {
        if (bitmap == null || value == null || bitmap.getWidth() != value.getWidth()
                || bitmap.getHeight() != value.getHeight()) {
            if (zoomAnimator != null) zoomAnimator.cancel();
            zoom = 1f; panX = panY = 0f;
        }
        Bitmap previous = bitmap;
        bitmap = value;
        if (previous != value && value != null && ValueAnimator.areAnimatorsEnabled()) crossFade(previous);
        invalidate();
    }
    public void setRegions(List<Region> values) {
        regions.clear();
        regions.addAll(values);
        if (!values.isEmpty()) revealBoxes();
        selectAll(true);
    }
    private void crossFade(Bitmap previous) {
        if (fadeAnimator != null) fadeAnimator.cancel();
        fading = previous;
        fadeIn = 0f;
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(previous == null ? 260 : 320);
        fadeAnimator.setInterpolator(Ui.STANDARD);
        fadeAnimator.addUpdateListener(a -> { fadeIn = (float) a.getAnimatedValue(); invalidate(); });
        fadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { fading = null; fadeIn = 1f; invalidate(); }
        });
        fadeAnimator.start();
    }
    private void revealBoxes() {
        if (boxAnimator != null) boxAnimator.cancel();
        boxesIn = 1f;
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        boxesIn = 0f;
        boxAnimator =ValueAnimator.ofFloat(0f, 1f).setDuration(360 + Math.min(regions.size(), 24) * 40L);
        boxAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        boxAnimator.addUpdateListener(a -> { boxesIn = (float) a.getAnimatedValue(); invalidate(); });
        boxAnimator.start();
    }
    private void startPulse(String id) {
        if (pulseAnimator != null) pulseAnimator.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        pulseId = id;
        pulseAnimator = ValueAnimator.ofFloat(1f, 0f).setDuration(380);
        pulseAnimator.setInterpolator(Ui.STANDARD);
        pulseAnimator.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); invalidate(); });
        pulseAnimator.start();
    }
    /** Zooms around the tapped point, keeping it under the finger like a pinch would. */
    private void animateZoom(float target, float x, float y) {
        if (zoomAnimator != null) zoomAnimator.cancel();
        final float startZoom = zoom, startX = panX, startY = panY;
        final float focusX = x - getWidth() / 2f, focusY = y - getHeight() / 2f;
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(280);
        zoomAnimator.setInterpolator(Ui.EASE);
        zoomAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            zoom = startZoom + (target - startZoom) * t;
            panX = focusX - (focusX - startX) * zoom / startZoom;
            panY = focusY - (focusY - startY) * zoom / startZoom;
            if (target <= 1f) { panX *= 1f - t; panY *= 1f - t; }
            clampPan();
            invalidate();
        });
        zoomAnimator.start();
    }
    /** Region i eases in over its own slice of the shared reveal timeline (cubic ease-out). */
    private float boxProgress(int i) {
        if (boxesIn >= 1f) return 1f;
        int n = Math.min(regions.size(), 24);
        float total = 360f + n * 40f, local = (boxesIn * total - Math.min(i, n) * 40f) / 360f;
        local = Math.max(0f, Math.min(1f, local));
        return 1f - (1f - local) * (1f - local) * (1f - local);
    }
    public void selectAll(boolean value) {
        selected.clear();
        if (value) for (Region r : regions) selected.add(r.id);
        invalidate();
        if (selectionChanged != null) selectionChanged.run();
    }
    public List<Region> getAllRegions() { return new ArrayList<>(regions); }
    public List<Region> getSelectedRegions() {
        List<Region> result = new ArrayList<>();
        for (Region r : regions) if (selected.contains(r.id)) result.add(r);
        return result;
    }
    private float fitScale() { return fitScale(bitmap); }
    private float fitScale(Bitmap image) {
        if (image == null) return 1f;
        return Math.min((getWidth() - 16f) / image.getWidth(), (getHeight() - 16f) / image.getHeight());
    }
    private float left(float scale) { return (getWidth() - bitmap.getWidth() * scale) / 2f + panX; }
    private float top(float scale) { return (getHeight() - bitmap.getHeight() * scale) / 2f + panY; }
    private void clampPan() {
        if (bitmap == null) return;
        float scale = fitScale() * zoom;
        float maxX = Math.max(0, (bitmap.getWidth() * scale - getWidth()) / 2f);
        float maxY = Math.max(0, (bitmap.getHeight() * scale - getHeight()) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { clampPan(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Ui.SURFACE_SOFT);
        float density = getResources().getDisplayMetrics().density;
        if (bitmap == null) {
            float inset = 14 * density;
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(1.5f * density);
            dash.setColor(Ui.OUTLINE);
            dash.setPathEffect(new DashPathEffect(new float[]{8 * density, 6 * density}, 0));
            canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, 16 * density, 16 * density, dash);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Ui.PLACEHOLDER);
            paint.setTextSize(34 * density);
            if(placeholderIcon==null)placeholderIcon=Icons.icon(getContext(),R.drawable.ic_image,Ui.PLACEHOLDER);android.graphics.drawable.Drawable placeholder=placeholderIcon;int size=Math.round(40*density),left=(getWidth()-size)/2,top=Math.round(getHeight()/2f-44*density);placeholder.setBounds(left,top,left+size,top+size);placeholder.draw(canvas);
            paint.setColor(Ui.MUTED);
            paint.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText("导入一页漫画，开始离线检测", getWidth() / 2f, getHeight() / 2f + 22 * density, paint);
            return;
        }
        // The outgoing image is only drawn while still alive; callers may recycle it right after swapping.
        boolean blending = fading != null && fading != bitmap && !fading.isRecycled();
        if (blending) drawImage(canvas, fading, 1f - fadeIn);
        float scale = fitScale() * zoom;
        canvas.save();
        canvas.translate(left(scale), top(scale));
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(fading != null && fadeIn < 1f ? Math.round(255 * fadeIn) : 255);
        canvas.drawBitmap(bitmap, 0f, 0f, paint);
        paint.setAlpha(255);
        if (showBoxes) {
            int index = 0;
            for (Region region : regions) {
                index++;
                float shown = boxProgress(index - 1);
                if (shown <= 0f) continue;
                int alpha = Math.round(255 * shown);
                boolean active = selected.contains(region.id);
                float grow = (1f - shown) * 10 * density / scale;
                RectF box = new RectF(region.box.left - grow, region.box.top - grow, region.box.right + grow, region.box.bottom + grow);
                paint.setStyle(Paint.Style.FILL);
                int fill = active ? 0x241A73E8 : 0x30909B9E;
                paint.setColor(fill);
                paint.setAlpha(Math.round(Color.alpha(fill) * shown));
                canvas.drawRect(box, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f * density / scale);
                paint.setColor(active ? Ui.ACCENT : Ui.MUTED);
                paint.setAlpha(alpha);
                canvas.drawRect(box, paint);
                if (pulse > 0f && region.id.equals(pulseId)) {
                    float ring = (1f - pulse) * 12 * density / scale;
                    paint.setStrokeWidth(3f * density / scale);
                    paint.setAlpha(Math.round(200 * pulse));
                    canvas.drawRect(box.left - ring, box.top - ring, box.right + ring, box.bottom + ring, paint);
                    paint.setAlpha(alpha);
                }
                paint.setStyle(Paint.Style.FILL);
                float badge = 15 * density / scale;
                RectF badgeRect = new RectF(box.left, box.top, box.left + badge * 1.5f, box.top + badge);
                canvas.drawRect(badgeRect, paint);
                paint.setColor(Color.WHITE);
                paint.setAlpha(alpha);
                paint.setTextSize(badge * .72f);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.valueOf(index), badgeRect.centerX(), badgeRect.top + badge * .77f, paint);
            }
            paint.setAlpha(255);
        }
        if (highlight != null) {
            // Workbench selection: a rounded blue outline (+ pulse ring) that never covers the lettering.
            for (Region region : regions) if (region.id.equals(highlight)) {
                float pad = 4 * density / scale, ring = pulse > 0f && highlight.equals(pulseId) ? (1f - pulse) * 10 * density / scale : 0f;
                RectF box = new RectF(region.box.left - pad - ring, region.box.top - pad - ring, region.box.right + pad + ring, region.box.bottom + pad + ring);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.4f * density / scale);
                paint.setColor(Ui.ACCENT);
                paint.setAlpha(ring > 0f ? Math.round(255 * Math.max(.35f, pulse)) : 255);
                canvas.drawRoundRect(box, 6 * density / scale, 6 * density / scale, paint);
                paint.setAlpha(255);
                break;
            }
        }
        canvas.restore();
    }
    private void drawImage(Canvas canvas, Bitmap image, float opacity) {
        float scale = fitScale(image) * zoom;
        canvas.save();
        canvas.translate((getWidth() - image.getWidth() * scale) / 2f + panX, (getHeight() - image.getHeight() * scale) / 2f + panY);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, opacity))));
        canvas.drawBitmap(image, 0f, 0f, paint);
        paint.setAlpha(255);
        canvas.restore();
    }
    @Override protected void onDetachedFromWindow() {
        for (ValueAnimator a : new ValueAnimator[]{fadeAnimator, boxAnimator, pulseAnimator, zoomAnimator}) if (a != null) a.cancel();
        fading = null;
        super.onDetachedFromWindow();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return super.onTouchEvent(event);
        if(boxChanged!=null&&event.getPointerCount()==1){
            float s=fitScale()*zoom,x=(event.getX()-left(s))/s,y=(event.getY()-top(s))/s;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){Region hit=regionAt(event.getX(),event.getY());if(hit!=null&&hit.id.equals(highlight)){dragging=hit;dragStart=new Rect(hit.box);dragX=x;dragY=y;resizing=Math.abs(x-hit.box.right)<24/s&&Math.abs(y-hit.box.bottom)<24/s;}}
            if(dragging!=null){
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE){int dx=Math.round(x-dragX),dy=Math.round(y-dragY);Rect r=new Rect(dragStart);if(resizing){r.right=Math.min(bitmap.getWidth(),Math.max(r.left+8,r.right+dx));r.bottom=Math.min(bitmap.getHeight(),Math.max(r.top+8,r.bottom+dy));}else{r.offset(Math.max(-r.left,Math.min(bitmap.getWidth()-r.right,dx)),Math.max(-r.top,Math.min(bitmap.getHeight()-r.bottom,dy)));}dragging.box.set(r);invalidate();return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_UP){Region region=dragging;dragging=null;boxChanged.accept(region.id,new Rect(region.box));performClick();return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging.box.set(dragStart);dragging=null;return true;}
            }
        }else dragging=null;
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && zoomAnimator != null) zoomAnimator.cancel();
        scaleDetector.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(event.getPointerCount() > 1 || zoom > 1.01f);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
