package cn.local.manga;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 汉化工作台: page-by-page proofreading of a ComicProject. Edits re-typeset only the touched paragraph on a
 * worker thread and recompose into an off-screen buffer that is then swapped in, so typing stays smooth.
 */
public class WorkbenchActivity extends ShellActivity {
    static Intent intent(Context context, String projectId, int page) {
        int tab=context instanceof ShellActivity?((ShellActivity)context).tab:2;
        return new Intent(context,tab==1?LocalWorkbenchActivity.class:WorkbenchActivity.class).putExtra("project", projectId).putExtra("page", page).putExtra("shellTab",tab);
    }

    private static final int PANEL_COLLAPSED = 0, PANEL_NORMAL = 1, PANEL_EXPANDED = 2;
    private static final float MIN_SCALE = .5f, MAX_SCALE = 2f, SCALE_STEP = .1f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService renderer = Executors.newSingleThreadExecutor(r -> new Thread(r, "workbench-render"));
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "workbench-io"));

    private ComicProject project;
    private int pageIndex = -1;
    private volatile int pageToken;
    private PageComposer composer;              // current page (main-thread reference; used on renderer thread)
    private PageDraft draft;
    private volatile int renderRevision;
    private volatile Bitmap onScreen;
    private final Map<String, PageComposer.Edit> pendingEdits = new LinkedHashMap<>();
    private boolean renderQueued, destroyed, dirty;
    private String selected;

    private ResultView preview;
    private TextView title, pageInfo, panelTitle, exportStatus;
    private Button saveButton; private int editRevision; private boolean saving;
    private Button reviewedChip, undoButton, redoButton, peekButton, prevButton, nextButton;
    private ProgressBar loading, exportProgress;
    private LinearLayout panel, cards;
    private ScrollView cardScroll;
    private int panelState = PANEL_NORMAL;
    private boolean panelAnimating;
    private final Map<String, Card> cardViews = new LinkedHashMap<>();

    private Deque<Step> undo = new ArrayDeque<>(), redo = new ArrayDeque<>(); private final Map<Integer,Deque<Step>> undoPages=new LinkedHashMap<>(),redoPages=new LinkedHashMap<>();
    private final Runnable autosave = this::saveDraft;
    private final ExportJob.Listener exportChanged = this::refreshExport;

    /** One undoable change of a paragraph's edit. */
    private static final class Step {
        final int page; final String region; final PageComposer.Edit before; PageComposer.Edit after; final boolean text; long at;
        Step(int page, String region, PageComposer.Edit before, PageComposer.Edit after, boolean text) {
            this.page = page; this.region = region; this.before = before; this.after = after; this.text = text; this.at = System.currentTimeMillis();
        }
    }
    private final class Card {
        final String region; View root; TextView status, reason; EditText input; TextView scaleLabel; Button orient; boolean binding;
        Card(String region) { this.region = region; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        String id = getIntent().getStringExtra("project");
        int start = state != null ? state.getInt("page", 0) : getIntent().getIntExtra("page", 0);
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                ComicProject loaded = ProjectStore.open(this, id);
                main.post(() -> {
                    if (destroyed) return;
                    project = loaded;
                    title.setText(project.title);
                    showPage(Math.max(0, Math.min(start, project.pages.size() - 1)), 0);offerRecovery();
                });
            } catch (Exception failure) {
                main.post(() -> { if (!destroyed) { Toast.makeText(this, "无法打开工程：" + failure.getMessage(), Toast.LENGTH_LONG).show(); finish(); } });
            }
        });
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        Ui.insets(root, 0, 0, 0, 0, true);

        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(4), dp(4), dp(8), dp(4));
        Button back = Ui.button(this, "‹", Ui.TEXT, v -> onBackPressed()); back.setTextSize(24); back.setContentDescription("返回");
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = Ui.heading(this, "汉化工作台", 17); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        undoButton = Ui.button(this, "↶", Ui.TEXT, v -> undo()); undoButton.setTextSize(20); undoButton.setContentDescription("撤销");
        redoButton = Ui.button(this, "↷", Ui.TEXT, v -> redo()); redoButton.setTextSize(20); redoButton.setContentDescription("重做");
        bar.addView(undoButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(redoButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button export = Ui.button(this, "保存", Ui.PRIMARY, v -> {flushPendingText();saveExplicit(null);}); saveButton=export;
        export.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(-2, dp(40)); exportParams.leftMargin = dp(4);
        bar.addView(export, exportParams);
        Button more = Ui.button(this, "⋮", Ui.TEXT, null); more.setTextSize(22); more.setContentDescription("更多");
        more.setOnClickListener(v -> projectMenu(more));
        bar.addView(more, new LinearLayout.LayoutParams(dp(40), dp(44)));
        root.addView(bar);

        LinearLayout exportRow = new LinearLayout(this); exportRow.setOrientation(LinearLayout.VERTICAL); exportRow.setPadding(dp(16), 0, dp(16), dp(4));
        exportStatus = Ui.text(this, "", 12, Ui.ACCENT_DEEP); exportRow.addView(exportStatus);
        exportProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        exportProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        exportRow.addView(exportProgress, new LinearLayout.LayoutParams(-1, dp(4)));
        exportRow.setVisibility(View.GONE); exportRow.setTag("exportRow");
        exportStatus.setOnClickListener(v -> { if (project != null && ExportJob.running(project)) new AlertDialog.Builder(this).setMessage("取消导出？已写入的页面会保留在文件夹/相册中，压缩包会被删除。")
                .setNegativeButton("继续导出", null).setPositiveButton("取消导出", (d, w) -> ExportJob.cancel()).show(); });
        root.addView(exportRow);

        LinearLayout info = new LinearLayout(this); info.setGravity(Gravity.CENTER_VERTICAL); info.setPadding(dp(16), 0, dp(12), dp(6));
        pageInfo = Ui.text(this, "", 13, Ui.MUTED); pageInfo.setSingleLine(true); pageInfo.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pageInfo.setOnClickListener(v->jump());
        info.addView(pageInfo, new LinearLayout.LayoutParams(0, -2, 1));
        reviewedChip = Ui.button(this, "标记已校对", Ui.OUTLINED, v -> toggleReviewed());
        reviewedChip.setTextSize(12); reviewedChip.setMinHeight(dp(34)); reviewedChip.setMinimumHeight(dp(34)); reviewedChip.setPadding(dp(12), 0, dp(12), 0);
        info.addView(reviewedChip, new LinearLayout.LayoutParams(-2, dp(34)));
        root.addView(info);

        FrameLayout stage = new FrameLayout(this);
        preview = new ResultView(this);
        preview.setEditable(false); preview.setShowBoxes(true);preview.setOnBoxChanged(this::boxChanged);preview.setOnAddBox(this::addBox);
        preview.setOnSwipe(this::turn);
        preview.setOnPick(this::select);
        preview.setContentDescription("漫画页面：点文字段可选中，左右滑动翻页，双指缩放");
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        loading = new ProgressBar(this); loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        stage.addView(loading, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        peekButton = Ui.button(this, "按住看原图", Ui.TONAL, null);
        peekButton.setTextSize(12); peekButton.setMinHeight(dp(36)); peekButton.setMinimumHeight(dp(36)); peekButton.setPadding(dp(14), 0, dp(14), 0);
        peekButton.setOnTouchListener(this::peek);
        FrameLayout.LayoutParams peekParams = new FrameLayout.LayoutParams(-2, dp(36), Gravity.BOTTOM | Gravity.START);
        peekParams.setMargins(dp(12), 0, 0, dp(12));
        stage.addView(peekButton, peekParams);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheet = new GradientDrawable(); sheet.setColor(Ui.SURFACE);
        float r = dp(20); sheet.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(sheet); panel.setElevation(dp(8));
        LinearLayout handle = new LinearLayout(this); handle.setGravity(Gravity.CENTER_VERTICAL); handle.setPadding(dp(16), dp(6), dp(8), dp(4));
        View grip = new View(this); grip.setBackground(Ui.round(this, 0xffD5DCE6, 3));
        LinearLayout handleColumn = new LinearLayout(this); handleColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gripParams = new LinearLayout.LayoutParams(dp(36), dp(4)); gripParams.gravity = Gravity.CENTER_HORIZONTAL; gripParams.bottomMargin = dp(6);
        panelTitle = Ui.heading(this, "本页段落", 15);
        handleColumn.addView(panelTitle);
        handle.addView(handleColumn, new LinearLayout.LayoutParams(0, -2, 1));
        Button batch=Ui.button(this,"批量样式",Ui.TEXT,v->batchStyle());handle.addView(batch);
        Button size = Ui.button(this, "⌃", Ui.TEXT, v -> cyclePanel()); size.setContentDescription("展开或收起段落面板"); size.setTextSize(18);
        handle.addView(size, new LinearLayout.LayoutParams(dp(44), dp(40)));
        LinearLayout gripRow = new LinearLayout(this); gripRow.setGravity(Gravity.CENTER); gripRow.setPadding(0, dp(6), 0, 0);
        gripRow.addView(grip, new LinearLayout.LayoutParams(dp(36), dp(4)));
        panel.addView(gripRow);
        panel.addView(handle);
        handle.setOnClickListener(v -> cyclePanel()); gripRow.setOnClickListener(v -> cyclePanel());
        View.OnTouchListener drag=new View.OnTouchListener(){float start; public boolean onTouch(View v,MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){start=e.getRawY();return true;}if(e.getActionMasked()==MotionEvent.ACTION_UP){float delta=e.getRawY()-start;if(Math.abs(delta)>dp(20))setPanel(Math.max(0,Math.min(2,panelState+(delta<0?1:-1))));else v.performClick();return true;}return true;}};gripRow.setOnTouchListener(drag);handleColumn.setOnTouchListener(drag);
        cardScroll = new ScrollView(this); cardScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        cards = new LinearLayout(this); cards.setOrientation(LinearLayout.VERTICAL); cards.setPadding(dp(12), 0, dp(12), dp(12));
        cardScroll.addView(cards);
        panel.addView(cardScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = new LinearLayout(this); nav.setPadding(dp(12), dp(6), dp(12), dp(8));
        nav.setTag("pageNav");
        prevButton = Ui.button(this, "‹ 上一页", Ui.TONAL, v -> turn(-1));
        nextButton = Ui.button(this, "下一页 ›", Ui.TONAL, v -> turn(1));
        nav.addView(prevButton, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, -2, 1); nextParams.leftMargin = dp(8);
        nav.addView(nextButton, nextParams);
        panel.addView(nav);
        root.addView(panel, new LinearLayout.LayoutParams(-1, panelHeight(PANEL_NORMAL)));
        // With the keyboard open the window shrinks: keep the page visible by capping the sheet at 62% of the space.
        root.addOnLayoutChangeListener((changed, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (panelAnimating) return;
            int wanted = Math.min(panelHeight(panelState), Math.round((bottom - top) * (panelState==PANEL_EXPANDED?.84f:.62f)));
            if (panel.getLayoutParams().height != wanted) { panel.getLayoutParams().height = wanted; panel.post(panel::requestLayout); }
        });
        setContentView(root);
        refreshUndo();
        if (Ui.motion()) { panel.setTranslationY(dp(60)); panel.setAlpha(0f); panel.animate().translationY(0).alpha(1f).setDuration(380).setInterpolator(Ui.EASE).start(); }
    }

    private int panelHeight(int state) {
        int screen = getResources().getDisplayMetrics().heightPixels;
        return state == PANEL_COLLAPSED ? dp(66) : state == PANEL_EXPANDED ? Math.round(screen * .78f) : Math.round(screen * .44f);
    }
    private void cyclePanel() { setPanel(panelState == PANEL_NORMAL ? PANEL_EXPANDED : panelState == PANEL_EXPANDED ? PANEL_COLLAPSED : PANEL_NORMAL); }
    private void setPanel(int state) {
        panelState = state;
        View nav=panel.findViewWithTag("pageNav");if(nav!=null)nav.setVisibility(state==PANEL_COLLAPSED?View.GONE:View.VISIBLE);
        int from = panel.getLayoutParams().height, to = panelHeight(state);
        if (!Ui.motion()) { panel.getLayoutParams().height = to; panel.requestLayout(); return; }
        View root = (View) panel.getParent();
        if (root != null && root.getHeight() > 0) to = Math.min(to, Math.round(root.getHeight() * (state==PANEL_EXPANDED?.84f:.62f)));
        ValueAnimator animator = ValueAnimator.ofInt(from, to).setDuration(200);
        animator.setInterpolator(Ui.EASE);
        animator.addUpdateListener(a -> { panel.getLayoutParams().height = (Integer) a.getAnimatedValue(); panel.requestLayout(); });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { panelAnimating = false; }
            @Override public void onAnimationCancel(android.animation.Animator a) { panelAnimating = false; }
        });
        panelAnimating = true;
        animator.start();
    }

    // ------------------------------------------------------------------ pages

    private void turn(int direction) {
        if (project == null) return;
        int target = pageIndex + direction;
        if (target < 0 || target >= project.pages.size()) {
            Toast.makeText(this, direction > 0 ? "已经是最后一页" : "已经是第一页", Toast.LENGTH_SHORT).show();
            return;
        }
        leaveEditor(()->showPage(target, direction));
    }

    private void showPage(int index, int direction) {
        if (project == null || index < 0 || index >= project.pages.size()) return;
        flushPendingText();
        final int token = ++pageToken;
        if(pageIndex>=0){undoPages.put(pageIndex,undo);redoPages.put(pageIndex,redo);} undo=undoPages.computeIfAbsent(index,k->new ArrayDeque<>());redo=redoPages.computeIfAbsent(index,k->new ArrayDeque<>());refreshUndo();
        pageIndex = index; selected = null;
        ComicProject.Page page = project.pages.get(index);
        final Map<String, PageComposer.Edit> edits = copy(page.edits);
        synchronized (pendingEdits) { pendingEdits.clear();renderQueued=false; }
        updateHeader();
        cardViews.clear(); cards.removeAllViews();
        cards.addView(note("读取中…"));
        loading.setVisibility(View.VISIBLE);
        preview.setHighlight(null);
        if (direction != 0 && Ui.motion()) preview.animate().translationX(-direction * dp(36)).alpha(.3f).setDuration(120).start();
        final PageComposer previous = composer;
        Bitmap oldImage=onScreen;preview.setBitmap(null);if(oldImage!=null&&!oldImage.isRecycled())oldImage.recycle();renderRevision++;
        composer = null; draft = null; onScreen = null;
        renderer.execute(() -> {
            if (previous != null) previous.close();
            if (token != pageToken) return;
            try {
                if (page.editable()) {
                    ProjectStore.ensureLayers(project.draftDir(page));
                    if(token!=pageToken)return;
                    PageDraft loaded = PageDraft.read(project.draftDir(page)).withEdits(edits);
                    PageComposer created = new PageComposer(loaded,1600);
                    Bitmap out;
                    try{created.layoutAll(project.effectiveEdits(page,loaded), () -> token != pageToken);out=created.compose(null);}catch(Exception|OutOfMemoryError failure){created.close();throw failure;}
                    main.post(() -> {
                        if (destroyed || token != pageToken) {out.recycle();new Thread(created::close).start();return; }
                        composer = created; draft = loaded; onScreen = out;
                        updateBoxes(loaded);
                        reveal(out, direction);
                        buildCards(page, loaded, created);
                    });
                } else {
                    Bitmap image = decodeBounded(project.imageFile(page));
                    main.post(() -> {
                        if (destroyed || token != pageToken) {image.recycle();return;}
                        preview.setRegionsQuiet(new ArrayList<>());
                        reveal(image, direction);
                        buildReadOnly(page);
                    });
                }
            } catch (CancellationException stale) {
            } catch (Exception | OutOfMemoryError failure) {
                main.post(() -> {
                    if (destroyed || token != pageToken) return;
                    loading.setVisibility(View.GONE);
                    preview.setAlpha(1f); preview.setTranslationX(0);
                    cards.removeAllViews();
                    cards.addView(note(failure instanceof OutOfMemoryError ? "图片过大，手机内存不足。" : "这一页无法打开：" + failure.getMessage()));
                });
            }
        });
    }

    private void reveal(Bitmap image, int direction) {
        onScreen=image;
        loading.setVisibility(View.GONE);
        preview.setBitmap(image);
        preview.resetZoom();
        if (direction != 0 && Ui.motion()) {
            preview.animate().cancel();
            preview.setTranslationX(direction * dp(36));
            preview.animate().translationX(0).alpha(1f).setDuration(260).setInterpolator(Ui.EASE).start();
        } else { preview.setTranslationX(0); preview.setAlpha(1f); }
    }

    private Bitmap decodeBounded(java.io.File file) throws Exception {
        if (file == null || !file.isFile()) throw new java.io.IOException("页面图片缺失");
        return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file), (decoder, info, src) -> {
            int[] size = BrowserImageLoader.boundedSize(info.getSize().getWidth(), info.getSize().getHeight());
            decoder.setTargetSize(size[0], size[1]);
        });
    }

    private void updateHeader() {
        ComicProject.Page page = project.pages.get(pageIndex);
        String kind = page.editable() ? "" : ComicProject.KIND_ORIGINAL.equals(page.kind) ? " · 未翻译" : " · 成品图";
        pageInfo.setText("第 " + (pageIndex + 1) + " / " + project.pages.size() + " 页 · " + page.label + kind);
        prevButton.setEnabled(pageIndex > 0);
        nextButton.setEnabled(pageIndex + 1 < project.pages.size());
        peekButton.setVisibility(page.editable() ? View.VISIBLE : View.GONE);
        paintReviewed(page.reviewed);
    }

    private void paintReviewed(boolean reviewed) {
        reviewedChip.setText(reviewed ? "✓ 已校对" : "标记已校对");
        Ui.style(reviewedChip, reviewed ? Ui.TONAL : Ui.OUTLINED);
        reviewedChip.setTextSize(12); reviewedChip.setMinHeight(dp(34)); reviewedChip.setMinimumHeight(dp(34)); reviewedChip.setPadding(dp(12), 0, dp(12), 0);
    }
    private void toggleReviewed() {
        if (project == null || pageIndex < 0) return;
        ComicProject.Page page = project.pages.get(pageIndex);
        page.reviewed = !page.reviewed;
        paintReviewed(page.reviewed);
        if (page.reviewed && Ui.motion()) { reviewedChip.setScaleX(.9f); reviewedChip.setScaleY(.9f); reviewedChip.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start(); }
        changed();
    }

    // ------------------------------------------------------------------ cards

    private void buildReadOnly(ComicProject.Page page) {
        cards.removeAllViews(); cardViews.clear();
        panelTitle.setText(ComicProject.KIND_ORIGINAL.equals(page.kind) ? "未翻译的原图" : "成品图（只读）");
        cards.addView(note(ComicProject.KIND_ORIGINAL.equals(page.kind)
                ? "这一页还没有翻译。点底部「开始翻译」处理整个工程，或点下方按钮翻译本页；导出时可选择包含原图。"
                : "这一页的译图来自旧版本缓存或没有可编辑记录，只能浏览和导出。重新翻译这一页后再生成工程，就能逐段修改。"));
        cards.addView(Ui.button(this,"重新翻译本页以编辑",Ui.PRIMARY,v->{
            if(ComicProject.KIND_RENDERED.equals(page.kind)&&page.originalName==null){String url=project.sourceKey.startsWith("web:")?project.sourceKey.substring(4):"";if(BrowserLibrary.isWebUrl(url))startActivity(new Intent(this,BrowserActivity.class).putExtra("url",url).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));else Toast.makeText(this,"请重新导入原图后翻译",1).show();}
            else ProjectTranslation.start(this,project,pageIndex,()->showPage(pageIndex,0));
        }));
    }

    private void buildCards(ComicProject.Page page, PageDraft loaded, PageComposer created) {
        cards.removeAllViews(); cardViews.clear();
        int index = 0, shown = 0;
        for (PageDraft.Item item : loaded.items) {
            index++;
            // Every detected paragraph gets a card, including ones the model skipped: users can add text to them.
            Card card = card(page, item, index);
            cards.addView(card.root, Ui.margins(this, 0, shown == 0 ? 2 : 8, 0, 0));
            cardViews.put(item.region.id, card);
            showPlacement(card, created.placement(item.region.id));
            if (Ui.motion() && shown < 8) {
                card.root.setAlpha(0f); card.root.setTranslationY(dp(14));
                card.root.animate().alpha(1f).translationY(0).setStartDelay(40L * shown).setDuration(300).setInterpolator(Ui.EASE).start();
            }
            shown++;
        }
        if (shown == 0) cards.addView(note("本页没有检测到文字。"));
        refreshPanelTitle();
    }

    private Card card(ComicProject.Page page, PageDraft.Item item, int number) {
        Card card = new Card(item.region.id);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setBackground(cardBackground(false));
        Ui.smoothLayout(root);
        card.root = root;

        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = Ui.text(this, String.valueOf(number), 12, Color.WHITE); badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT_BOLD); badge.setBackground(Ui.round(this, Ui.ACCENT, 10));
        head.addView(badge, new LinearLayout.LayoutParams(dp(22), dp(22)));
        card.status = Ui.text(this, "", 12, Ui.MUTED); card.status.setSingleLine(true); card.status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1); statusParams.leftMargin = dp(8);
        head.addView(card.status, statusParams);
        Button more = Ui.button(this, "⋯", Ui.TEXT, null); more.setTextSize(18); more.setContentDescription("段落操作");
        more.setOnClickListener(v -> regionMenu(more, item));
        head.addView(more, new LinearLayout.LayoutParams(dp(40), dp(36)));
        root.addView(head);

        TextView original = Ui.text(this, item.original.isEmpty() ? "（未识读到原文）" : "原文：" + item.original, 13, 0xff5B6475);
        original.setMaxLines(4); original.setEllipsize(TextUtils.TruncateAt.END); original.setTextIsSelectable(true);
        original.setOnClickListener(v->original.setMaxLines(original.getMaxLines()==4?Integer.MAX_VALUE:4));
        root.addView(original, Ui.margins(this, 0, 6, 0, 6));

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(1); input.setMaxLines(6); input.setTextSize(15); input.setHint("输入译文（换行 = 强制分行）");
        input.setGravity(Gravity.TOP | Gravity.START);
        Ui.field(input);
        card.input = input;
        card.binding = true; input.setText(textOf(page, item)); card.binding = false;
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable value) { if (!card.binding) textChanged(item, value.toString()); }
        });
        input.setOnFocusChangeListener((v, focused) -> { if (focused) select(item.region.id, false); });
        root.addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL);
        Button smaller = Ui.button(this, "A−", Ui.OUTLINED, v -> scale(item, -SCALE_STEP));
        Button larger = Ui.button(this, "A+", Ui.OUTLINED, v -> scale(item, SCALE_STEP));
        for (Button b : new Button[]{smaller, larger}) { b.setTextSize(13); b.setPadding(0, 0, 0, 0); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(38)); b.setMinimumHeight(dp(38)); }
        card.scaleLabel = Ui.text(this, "", 12, 0xff3C4657); card.scaleLabel.setGravity(Gravity.CENTER);
        controls.addView(smaller, new LinearLayout.LayoutParams(dp(46), dp(38)));
        controls.addView(card.scaleLabel, new LinearLayout.LayoutParams(dp(78), -2));
        controls.addView(larger, new LinearLayout.LayoutParams(dp(46), dp(38)));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        card.orient = Ui.button(this, "", Ui.TONAL, v -> cycleOrientation(item));
        card.orient.setTextSize(12); card.orient.setPadding(dp(12), 0, dp(12), 0); card.orient.setMinHeight(dp(38)); card.orient.setMinimumHeight(dp(38));
        controls.addView(card.orient, new LinearLayout.LayoutParams(-2, dp(38)));
        root.addView(controls, Ui.margins(this, 0, 8, 0, 0));root.addView(Ui.button(this,"字号 / 字体 / 颜色 / 描边 / 文本框",Ui.TEXT,v->{flushPendingText();select(item.region.id,false);StylePanel.show(this,editOf(page,item.region.id),item.region.box,e->apply(page,item.region.id,editOf(page,item.region.id).copy(),e,false));}));

        card.reason = Ui.text(this, "", 12, 0xffB06000); card.reason.setVisibility(View.GONE);
        root.addView(card.reason, Ui.margins(this, 0, 6, 0, 0));
        root.setOnClickListener(v -> select(item.region.id, false));
        paintControls(card, editOf(page, item.region.id));
        return card;
    }

    private GradientDrawable cardBackground(boolean active) {
        GradientDrawable shape = Ui.round(this, active ? 0xffF5F9FF : Ui.SURFACE, 16);
        shape.setStroke(dp(active ? 2 : 1), active ? Ui.ACCENT : Ui.OUTLINE);
        return shape;
    }

    private void paintControls(Card card, PageComposer.Edit edit) {
        card.scaleLabel.setText(Math.round(edit.scale * 100) + "%");
        card.orient.setText(edit.vertical == null ? "方向：自动" : edit.vertical ? "方向：竖排" : "方向：横排");
        float alpha = edit.hidden ? .5f : 1f;
        card.input.setAlpha(alpha);
    }

    private void showPlacement(Card card, PageComposer.Placement placement) {
        if (card == null || placement == null) return;
        String status;
        int color = Ui.MUTED;
        switch (placement.mode) {
            case BUBBLE: status = "● 气泡排版 · " + Math.round(placement.font) + " px"; color = 0xff137333; break;
            case IN_PLACE: status = "● 原位排字 · " + Math.round(placement.font) + " px"; color = 0xffB06000; break;
            default: status = "○ 不渲染译文"; break;
        }
        card.status.setText(status); card.status.setTextColor(color);
        String reason = placement.reason;
        if (placement.mode == PageComposer.Mode.BUBBLE) {
            PageComposer.Edit edit = editOf(project.pages.get(pageIndex), card.region);
            if (edit.scale > 1f) reason = "放大受气泡空间限制，实际字号以上方为准";
        }
        boolean show = reason != null && !reason.isEmpty();
        card.reason.setText(show ? reason : "");
        if (show != (card.reason.getVisibility() == View.VISIBLE)) Ui.reveal(card.reason, show, View.GONE);
    }

    private void refreshPanelTitle() {
        if (project == null || pageIndex < 0) return;
        ComicProject.Page page = project.pages.get(pageIndex);
        int edited = page.editedCount();
        panelTitle.setText("本页 " + cardViews.size() + " 段" + (edited > 0 ? " · 已改 " + edited : ""));
    }

    private View note(String text) {
        TextView view = Ui.text(this, text, 13, Ui.MUTED);
        view.setPadding(dp(8), dp(12), dp(8), dp(12));
        return view;
    }

    // ------------------------------------------------------------------ selection

    private void select(String regionId) { select(regionId, true); }
    private void select(String regionId, boolean scroll) {
        Card old = selected == null ? null : cardViews.get(selected);
        if (old != null) old.root.setBackground(cardBackground(false));
        selected = regionId;
        preview.setHighlight(regionId);if(!scroll)preview.focusRegion(regionId);
        Card card = regionId == null ? null : cardViews.get(regionId);
        if (card == null) return;
        card.root.setBackground(cardBackground(true));
        if (scroll) {
            if (panelState == PANEL_COLLAPSED) setPanel(PANEL_NORMAL);
            cardScroll.post(() -> cardScroll.smoothScrollTo(0, Math.max(0, card.root.getTop() - dp(8))));
            if (Ui.motion()) { card.root.setScaleX(.97f); card.root.setScaleY(.97f); card.root.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Ui.EASE).start(); }
        }
    }

    // ------------------------------------------------------------------ editing

    private String textOf(ComicProject.Page page, PageDraft.Item item) {
        PageComposer.Edit edit = page.edits.get(item.region.id);
        return edit != null && edit.text != null ? edit.text : item.machine;
    }
    private PageComposer.Edit editOf(ComicProject.Page page, String region) {
        return project.effective(page,region);
    }

    private Runnable pendingText;
    private void textChanged(PageDraft.Item item, String value) {
        if (pendingText != null) main.removeCallbacks(pendingText);
        pendingText = () -> { pendingText = null; commitText(item, value); };
        main.postDelayed(pendingText, 60);
    }
    private void flushPendingText() { if (pendingText != null) { main.removeCallbacks(pendingText); Runnable r = pendingText; pendingText = null; r.run(); } }
    private void commitText(PageDraft.Item item, String value) {
        if (project == null || pageIndex < 0) return;
        ComicProject.Page page = project.pages.get(pageIndex);
        PageComposer.Edit before = editOf(page, item.region.id).copy(), after = before.copy();
        after.text = value.equals(item.machine) ? null : value;
        apply(page, item.region.id, before, after, true);
    }
    private void scale(PageDraft.Item item, float delta) {
        ComicProject.Page page = project.pages.get(pageIndex);
        PageComposer.Edit before = editOf(page, item.region.id).copy(), after = before.copy();
        after.scale = Math.round(Math.max(MIN_SCALE, Math.min(MAX_SCALE, before.scale + delta)) * 10) / 10f;
        if (after.scale == before.scale) { Toast.makeText(this, delta > 0 ? "已是最大比例 200%" : "已是最小比例 50%", Toast.LENGTH_SHORT).show(); return; }
        apply(page, item.region.id, before, after, false);
    }
    private void cycleOrientation(PageDraft.Item item) {
        ComicProject.Page page = project.pages.get(pageIndex);
        PageComposer.Edit before = editOf(page, item.region.id).copy(), after = before.copy();
        after.vertical = before.vertical == null ? Boolean.TRUE : before.vertical ? Boolean.FALSE : null;
        apply(page, item.region.id, before, after, false);
    }

    private void regionMenu(View anchor, PageDraft.Item item) {
        ComicProject.Page page = project.pages.get(pageIndex);
        PageComposer.Edit edit = editOf(page, item.region.id);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "还原机翻（文字与样式）").setEnabled(!edit.isDefault());
        menu.getMenu().add(0, 2, 1, edit.hidden ? "显示本段译文" : "隐藏本段译文（保留底图）");
        menu.getMenu().add(0, 3, 2, edit.inPlace ? "改回自动排版" : "改为原位排字");
        menu.getMenu().add(0, 4, 3, "文字颜色：默认");
        menu.getMenu().add(0, 5, 4, "文字颜色：黑");
        menu.getMenu().add(0, 6, 5, "文字颜色：白");
        menu.getMenu().add(0, 7, 6, "文字颜色：红");menu.getMenu().add(0,8,7,"删除此条");menu.getMenu().add(0,9,8,"重新翻译此条");
        menu.setOnMenuItemClickListener(choice -> {
            if(choice.getItemId()==9){retranslate(item);return true;}if(choice.getItemId()==8){new AlertDialog.Builder(this).setMessage("删除此文字条目？").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{PageComposer.Edit e=edit.copy();e.format.deleted=true;apply(page,item.region.id,edit,e,false);showPage(pageIndex,0);}).show();return true;}
            PageComposer.Edit before = edit.copy(), after = before.copy();
            switch (choice.getItemId()) {
                case 1: after = new PageComposer.Edit(); break;
                case 2: after.hidden = !after.hidden; break;
                case 3: after.inPlace = !after.inPlace; break;
                case 4: after.color = 0; break;
                case 5: after.color = Color.BLACK; break;
                case 6: after.color = Color.WHITE; break;
                case 7: after.color = Color.RED; break;
            }
            apply(page, item.region.id, before, after, false);
            if (choice.getItemId() == 1) {
                Card card = cardViews.get(item.region.id);
                if (card != null) { card.binding = true; card.input.setText(item.machine); card.binding = false; }
            }
            return true;
        });
        menu.show();
    }

    /** Stores an edit, records undo, refreshes its card and schedules the re-render. */
    private void apply(ComicProject.Page page, String region, PageComposer.Edit before, PageComposer.Edit after, boolean text) {
        if(TranslationTaskManager.running()&&TranslationTaskManager.owner.equals("project:"+project.id)){Toast.makeText(this,"工程正在翻译，请先停止再编辑",0).show();return;}
        if (before.equals(after)) return;
        put(page, region, after);
        Step last = undo.peek();
        long now = System.currentTimeMillis();
        if (text && last != null && last.text && last.page == pageIndex && last.region.equals(region) && now - last.at < 1500) { last.after = after.copy(); last.at = now; }
        else undo.push(new Step(pageIndex, region, before.copy(), after.copy(), text));
        while (undo.size() > 200) undo.removeLast();
        redo.clear();
        refreshUndo();
        Card card = cardViews.get(region);
        if (card != null) paintControls(card, after);
        refreshPanelTitle();
        render(region, project.effective(page,region));
        changed();
    }
    private static void put(ComicProject.Page page, String region, PageComposer.Edit edit) {
        if (edit.isDefault()) page.edits.remove(region); else page.edits.put(region, edit.copy());
    }

    private void undo() { step(true); }
    private void redo() { step(false); }
    private void step(boolean back) {
        flushPendingText();
        Deque<Step> from = back ? undo : redo, to = back ? redo : undo;
        Step step = from.poll();
        if (step == null || project == null) return;
        to.push(step);
        ComicProject.Page page = project.pages.get(step.page);
        PageComposer.Edit value = back ? step.before : step.after;
        put(page, step.region, value);
        refreshUndo();
        changed();
        if (step.page != pageIndex) { showPage(step.page, step.page > pageIndex ? 1 : -1); return; }
        if(step.before.format.added!=step.after.format.added||step.before.format.deleted!=step.after.format.deleted){showPage(pageIndex,0);return;}
        Card card = cardViews.get(step.region);
        if (card != null && draft != null) {
            PageDraft.Item item = draft.item(step.region);
            card.binding = true; card.input.setText(value.text != null ? value.text : item == null ? "" : item.machine); card.binding = false;
            paintControls(card, value);
        }
        select(step.region);
        refreshPanelTitle();
        if(draft!=null)updateBoxes(draft);render(step.region,project.effective(page,step.region));
    }
    private void refreshUndo() {
        undoButton.setEnabled(!undo.isEmpty()); undoButton.setAlpha(undo.isEmpty() ? .35f : 1f);
        redoButton.setEnabled(!redo.isEmpty()); redoButton.setAlpha(redo.isEmpty() ? .35f : 1f);
    }

    // ------------------------------------------------------------------ rendering

    /** Coalesces edits: one worker pass re-typesets every touched paragraph, then composes once. */
    private void render(String region, PageComposer.Edit edit) {
        if (composer == null || draft==null || draft.item(region)==null) return;
        renderRevision++;
        synchronized (pendingEdits) {
            pendingEdits.put(region, edit.copy());
            if (renderQueued) return;
            renderQueued = true;
        }
        final int token = pageToken;
        final PageComposer target = composer;
        renderer.execute(() -> {
            if(token!=pageToken)return;
            final int revision=renderRevision;
            Map<String, PageComposer.Edit> batch;
            synchronized (pendingEdits) { batch = new LinkedHashMap<>(pendingEdits); pendingEdits.clear(); renderQueued = false; }
            if (token != pageToken) return;
            try {
                Map<String, PageComposer.Placement> placed = new LinkedHashMap<>();
                for (Map.Entry<String, PageComposer.Edit> entry : batch.entrySet())
                    placed.put(entry.getKey(), target.layout(entry.getKey(), entry.getValue(), () -> token != pageToken));
                Bitmap out = target.compose(null);
                main.post(() -> {
                    if (destroyed || token != pageToken||revision!=renderRevision) {out.recycle();return;}
                    Bitmap previousImage=onScreen;
                    onScreen = out;
                    preview.swapBitmap(out);
                    if(previousImage!=null&&previousImage!=out)main.postDelayed(()->{if(previousImage!=onScreen&&!previousImage.isRecycled())previousImage.recycle();},250);
                    for (Map.Entry<String, PageComposer.Placement> entry : placed.entrySet()) showPlacement(cardViews.get(entry.getKey()), entry.getValue());
                });
            } catch (CancellationException stale) {
            } catch (Exception | OutOfMemoryError failure) {
                main.post(() -> { if (!destroyed) Toast.makeText(this, "预览更新失败：" + failure.getMessage(), Toast.LENGTH_SHORT).show(); });
            }
        });
    }

    private boolean peek(View view, MotionEvent event) {
        if (composer == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: view.setPressed(true); preview.swapBitmap(composer.source()); return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                view.setPressed(false); if (onScreen != null) preview.swapBitmap(onScreen); if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick(); return true;
            default: return true;
        }
    }

    // ------------------------------------------------------------------ saving, export, menu

    private void changed() { editRevision++;if(!dirty)main.postDelayed(autosave,30000);dirty=true;paintSave(); }
    private void paintSave(){if(saveButton!=null){saveButton.setText(saving?"保存中…":dirty?"保存 ●":"保存");saveButton.setEnabled(!saving);saveButton.setAlpha(dirty?1f:.65f);}}
    private void saveDraft(){
        main.removeCallbacks(autosave);if(project==null||!dirty)return;
        try{String snapshot=project.snapshot();ComicProject p=project;io.execute(()->{try{p.write("draft.json",snapshot);}catch(Exception e){main.post(()->{if(!destroyed)Toast.makeText(this,"草稿保存失败："+e.getMessage(),1).show();});}});}catch(Exception e){Toast.makeText(this,"草稿保存失败",1).show();}
        if(!destroyed)main.postDelayed(autosave,30000);
    }
    private void saveNow(){saveExplicit(null);}
    private void saveExplicit(Runnable next){
        flushPendingText();if(project==null||saving)return;if(!dirty){if(next!=null)next.run();else Toast.makeText(this,"已保存",0).show();return;}
        final int revision=editRevision;final ComicProject p=project;final String snapshot;
        try{snapshot=p.snapshot();}catch(Exception e){Toast.makeText(this,"保存失败："+e.getMessage(),1).show();return;}
        saving=true;paintSave();io.execute(()->{try{p.write("project.json",snapshot);new java.io.File(p.dir,"draft.json").delete();main.post(()->{saving=false;if(revision==editRevision)dirty=false;paintSave();Toast.makeText(this,"已保存",0).show();if(next!=null&&!dirty)next.run();});}catch(Exception e){main.post(()->{saving=false;paintSave();Toast.makeText(this,"保存失败："+e.getMessage(),1).show();});}});
    }
    @Override protected void leaveEditor(Runnable next){
        flushPendingText();if(!dirty){next.run();return;}saveDraft();
        new AlertDialog.Builder(this).setTitle("有未保存的修改").setMessage("保存后再离开？")
            .setPositiveButton("保存",(d,w)->saveExplicit(next)).setNegativeButton("不保存",(d,w)->{
                io.execute(()->{try{ComicProject saved=ComicProject.load(project.dir);new java.io.File(project.dir,"draft.json").delete();main.post(()->{project=saved;dirty=false;main.removeCallbacks(autosave);undo.clear();redo.clear();paintSave();showPage(pageIndex,0);next.run();});}catch(Exception e){main.post(()->Toast.makeText(this,"无法读取已保存版本："+e.getMessage(),1).show());}});
            }).setNeutralButton("取消",null).show();
    }
    private void offerRecovery(){
        if(!new java.io.File(project.dir,"draft.json").isFile())return;
        new AlertDialog.Builder(this).setTitle("恢复上次未保存的编辑？").setPositiveButton("恢复",(d,w)->io.execute(()->{try{ComicProject recovered=ComicProject.load(project.dir,"draft.json");main.post(()->{project=recovered;changed();showPage(pageIndex,0);});}catch(Exception e){main.post(()->Toast.makeText(this,"草稿读取失败："+e.getMessage(),1).show());}})).setNegativeButton("不恢复",(d,w)->io.execute(()->new java.io.File(project.dir,"draft.json").delete())).show();
    }
    /** Blocks until pending writes reached disk (used right before an export snapshot). */
    private void saveBlocking() {
        flushPendingText();
        try{String snapshot=project.snapshot();ComicProject p=project;io.submit(()->{try{p.write("project.json",snapshot);new java.io.File(p.dir,"draft.json").delete();}catch(Exception e){throw new RuntimeException(e);}}).get();dirty=false;paintSave();}
        catch(Exception error){throw new IllegalStateException("无法写入工程",error);}
    }

    private void export() {
        if (project == null) return;
        flushPendingText();
        ExportFlow.show(this, project, this::saveBlocking);
    }

    private void refreshExport() {
        View row = getWindow().getDecorView().findViewWithTag("exportRow");
        if (row == null || project == null) return;
        String status = ExportJob.status(project);
        int[] progress = ExportJob.progress(project);
        boolean show = !status.isEmpty();
        if (show != (row.getVisibility() == View.VISIBLE)) Ui.reveal(row, show, View.GONE);
        exportStatus.setText(status + (progress != null ? "（点此可取消）" : ""));
        exportProgress.setVisibility(progress != null ? View.VISIBLE : View.GONE);
        if (progress != null) { exportProgress.setMax(progress[1]); exportProgress.setProgress(progress[0], true); }
        getWindow().getDecorView().setKeepScreenOn(progress != null);
    }

    private void projectMenu(View anchor) {
        if (project == null) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "重命名工程");
        menu.getMenu().add(0, 2, 1, "查找替换（全部页面）");
        menu.getMenu().add(0, 3, 2, "跳转到页…");
        menu.getMenu().add(0, 4, 3, "我的汉化工程");menu.getMenu().add(0,5,4,"导出");menu.getMenu().add(0,6,5,"设置");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: rename(); break;
                case 2: findReplace(); break;
                case 3: jump(); break;
                case 4: leaveEditor(()->startActivity(new Intent(this, ProjectListActivity.class))); break;case 5:export();break;case 6:startActivity(new Intent(this,SettingsActivity.class));break;
            }
            return true;
        });
        menu.show();
    }

    private void rename() {
        EditText name = new EditText(this); name.setSingleLine(true); name.setText(project.title); Ui.field(name);
        FrameLayout box = new FrameLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(name);
        new AlertDialog.Builder(this).setTitle("重命名工程").setMessage("名称会用作导出的文件夹名或压缩包名。").setView(box)
                .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                    String value = name.getText().toString().trim();
                    if (value.isEmpty()) return;
                    project.title = value; title.setText(value); changed();
                }).show();
    }

    private void jump() {
        String[] labels = new String[project.pages.size()];
        for (int i = 0; i < labels.length; i++) {
            ComicProject.Page page = project.pages.get(i);
            labels[i] = (i + 1) + " · " + page.label + (page.reviewed ? "  ✓" : "") + (page.editedCount() > 0 ? "  已改" : "") + (page.editable() ? "" : "  只读");
        }
        new AlertDialog.Builder(this).setTitle("跳转到页").setItems(labels, (d, which) -> { if (which != pageIndex) leaveEditor(()->showPage(which, which > pageIndex ? 1 : -1)); }).show();
    }

    /** Replaces text in every editable page's current translation (e.g. unify a character name). */
    private void findReplace() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText find = new EditText(this); find.setSingleLine(true); find.setHint("查找（例如：旧人名）"); Ui.field(find);
        EditText replace = new EditText(this); replace.setSingleLine(true); replace.setHint("替换为"); Ui.field(replace);
        box.addView(find); box.addView(replace, Ui.margins(this, 0, 8, 0, 0));
        new AlertDialog.Builder(this).setTitle("查找替换").setMessage("在所有可编辑页面的译文中替换。此操作不能撤销。").setView(box)
                .setNegativeButton("取消", null).setPositiveButton("全部替换", (d, w) -> {
                    String from = find.getText().toString(), to = replace.getText().toString();
                    if (from.isEmpty()) return;
                    flushPendingText();
                    ComicProject target = project;
                    io.execute(() -> {
                        int count = 0;
                        for (ComicProject.Page page : target.pages) {
                            if (!page.editable()) continue;
                            try {
                                PageDraft pageDraft = PageDraft.read(target.draftDir(page));
                                for (PageDraft.Item item : pageDraft.items) {
                                    PageComposer.Edit edit = editOf(page, item.region.id);
                                    String current = edit.text != null ? edit.text : item.machine;
                                    if (current == null || !current.contains(from)) continue;
                                    PageComposer.Edit next = edit.copy(); next.text = current.replace(from, to);
                                    synchronized (target) { put(page, item.region.id, next); }
                                    count += (current.length() - current.replace(from, "").length()) / from.length();
                                }
                            } catch (Exception ignored) {}
                        }
                        int replaced = count;
                        main.post(() -> {
                            if (destroyed) return;
                            Toast.makeText(this, replaced == 0 ? "没有找到“" + from + "”" : "已替换 " + replaced + " 处", Toast.LENGTH_LONG).show();
                            if (replaced > 0) { undo.clear(); redo.clear(); refreshUndo(); changed(); showPage(pageIndex, 0); }
                        });
                    });
                }).show();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onResume() { super.onResume(); ExportJob.listen(exportChanged); refreshExport();
        if(project!=null&&!dirty&&!saving&&!TranslationTaskManager.running()){
            ComicProject current=project;int revision=editRevision;
            io.execute(()->{try{ComicProject loaded=ComicProject.load(current.dir);main.post(()->{if(destroyed||dirty||saving||revision!=editRevision||loaded.updated==current.updated)return;project=loaded;undoPages.clear();redoPages.clear();undo.clear();redo.clear();showPage(Math.max(0,Math.min(pageIndex,project.pages.size()-1)),0);});}catch(Exception e){main.post(()->{if(!destroyed)Toast.makeText(this,"工程更新读取失败："+e.getMessage(),1).show();});}});
        }
    }
    @Override protected void onPause() { flushPendingText(); saveDraft(); ExportJob.unlisten(exportChanged); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putInt("page", Math.max(0, pageIndex)); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        ExportFlow.onActivityResult(this, request, result, data);
    }
    @Override public void onBackPressed() {
        flushPendingText(); saveNow();
        if (project != null) { ComicProject target = project; io.execute(() -> ProjectStore.writeCover(target)); }
        super.onBackPressed();
    }
    @Override protected void onDestroy() {
        main.removeCallbacks(autosave);destroyed = true; pageToken++;
        PageComposer last = composer; composer = null;
        renderer.execute(() -> { if (last != null) last.close(); });
        renderer.shutdown(); io.shutdown();
        super.onDestroy();
    }

    private static Map<String, PageComposer.Edit> copy(Map<String, PageComposer.Edit> edits) {
        Map<String, PageComposer.Edit> out = new LinkedHashMap<>();
        for (Map.Entry<String, PageComposer.Edit> e : edits.entrySet()) out.put(e.getKey(), e.getValue().copy());
        return out;
    }
    protected String translationDisabled(){return project==null?"请先打开工程":dirty?"请先保存编辑再翻译":null;}
    protected void startTranslation(){if(project!=null)ProjectTranslation.start(this,project,-1,()->showPage(pageIndex,0));}
    private void updateBoxes(PageDraft data){
        List<Region> regions=new ArrayList<>();if(project==null||pageIndex<0)return;
        float sx=onScreen==null?1:onScreen.getWidth()/(float)data.width,sy=onScreen==null?1:onScreen.getHeight()/(float)data.height;
        for(PageDraft.Item item:data.items){PageComposer.Edit e=editOf(project.pages.get(pageIndex),item.region.id);android.graphics.Rect box=e.format.box==null?item.region.box:e.format.box;if(!e.format.deleted)regions.add(new Region(item.region.id,new android.graphics.Rect(Math.round(box.left*sx),Math.round(box.top*sy),Math.round(box.right*sx),Math.round(box.bottom*sy)),java.util.Collections.emptyList(),item.region.vertical));}
        preview.setRegionsQuiet(regions);
    }
    private android.graphics.Rect originalBox(android.graphics.Rect box){float sx=draft==null||onScreen==null?1:draft.width/(float)onScreen.getWidth(),sy=draft==null||onScreen==null?1:draft.height/(float)onScreen.getHeight();return new android.graphics.Rect(Math.round(box.left*sx),Math.round(box.top*sy),Math.round(box.right*sx),Math.round(box.bottom*sy));}
    private void boxChanged(String id,android.graphics.Rect box){if(project==null||pageIndex<0)return;ComicProject.Page page=project.pages.get(pageIndex);PageComposer.Edit before=editOf(page,id).copy(),after=before.copy();after.format.box=originalBox(box);after.format.custom=true;apply(page,id,before,after,false);}
    private void addBox(android.graphics.Rect box){
        if(project==null||draft==null)return;flushPendingText();ComicProject.Page page=project.pages.get(pageIndex);String id="manual-"+java.util.UUID.randomUUID();
        PageComposer.Edit edit=project.defaultStyle.copy();edit.format.added=true;edit.format.custom=true;edit.format.box=originalBox(box);edit.text="新增译文";
        apply(page,id,new PageComposer.Edit(),edit,false);showPage(pageIndex,0);
    }
    private void batchStyle(){
        if(draft==null||selected==null){Toast.makeText(this,"请先选择一个文本条目",0).show();return;}
        PageComposer.Edit source=editOf(project.pages.get(pageIndex),selected).copy();
        new AlertDialog.Builder(this).setTitle("应用当前条目的样式").setItems(new String[]{"本页全部条目","整个工程全部条目","选择本页条目"},(d,which)->{
            if(which==2){String[] names=new String[draft.items.size()];boolean[] chosen=new boolean[names.length];for(int i=0;i<names.length;i++)names[i]=(i+1)+" · "+draft.items.get(i).machine;
                new AlertDialog.Builder(this).setTitle("选择条目").setMultiChoiceItems(names,chosen,(x,n,b)->chosen[n]=b).setNegativeButton("取消",null).setPositiveButton("应用",(x,w)->{for(int i=0;i<chosen.length;i++)if(chosen[i])copyStyle(project.pages.get(pageIndex),draft.items.get(i),source);showPage(pageIndex,0);}).show();
            }else if(which==0){for(PageDraft.Item item:draft.items)copyStyle(project.pages.get(pageIndex),item,source);showPage(pageIndex,0);}
            else new AlertDialog.Builder(this).setMessage("将样式应用到整个工程，保留各条目的文字和位置。继续？").setNegativeButton("取消",null).setPositiveButton("应用",(x,w)->{
                try{for(ComicProject.Page page:project.pages)if(page.editable())for(PageDraft.Item item:PageDraft.read(project.draftDir(page)).withEdits(page.edits).items){PageComposer.Edit before=editOf(page,item.region.id),next=styled(before,source);page.edits.put(item.region.id,next);}changed();showPage(pageIndex,0);}catch(Exception e){Toast.makeText(this,"批量样式失败："+e.getMessage(),1).show();}
            }).show();
        }).show();
    }
    private static PageComposer.Edit styled(PageComposer.Edit before,PageComposer.Edit source){PageComposer.Edit next=before.copy();next.scale=source.scale;next.vertical=source.vertical;next.color=source.color;TextStyle s=source.format.copy();s.box=before.format.box;s.added=before.format.added;s.deleted=before.format.deleted;next.format=s;return next;}
    private void copyStyle(ComicProject.Page page,PageDraft.Item item,PageComposer.Edit source){PageComposer.Edit before=editOf(page,item.region.id).copy();apply(page,item.region.id,before,styled(before,source),false);}
    private void retranslate(PageDraft.Item item){
        AppSettings settings=AppSettings.load(this);try{settings.validate();}catch(Exception e){Toast.makeText(this,e.getMessage(),1).show();return;}
        java.util.concurrent.atomic.AtomicBoolean stop=new java.util.concurrent.atomic.AtomicBoolean();String owner="text:"+project.id;
        if(!TranslationTaskManager.begin(this,owner,()->stop.set(true))){Toast.makeText(this,"请先停止当前翻译",0).show();return;}
        final int token=pageToken;final java.io.File source=draft.source();
        io.execute(()->{Bitmap image=null;try(TranslationEngine engine=new TranslationEngine(this)){
            image=BitmapFactory.decodeFile(source.getPath());if(image==null)throw new java.io.IOException("原图缺失");
            try(TranslationEngine.PreparedText job=engine.prepareTextPage(image,java.util.Collections.singletonList(item.region),settings,stop::get)){
                engine.requestTextPage(job,settings,stop::get);org.json.JSONObject value=job.values.get(item.region.id);if(value==null||value.optBoolean("skip"))throw new java.io.IOException("未返回可用译文");String text=value.getString("zh");
                main.post(()->{if(destroyed||token!=pageToken||stop.get())return;ComicProject.Page page=project.pages.get(pageIndex);PageComposer.Edit before=editOf(page,item.region.id).copy(),after=before.copy();after.text=text;apply(page,item.region.id,before,after,false);Card card=cardViews.get(item.region.id);if(card!=null){card.binding=true;card.input.setText(text);card.binding=false;}});
            }
            TranslationTaskManager.done(owner,stop.get()?"已停止":"条目翻译完成，请保存编辑");
        }catch(Exception e){TranslationTaskManager.done(owner,"条目翻译未完成："+e.getMessage());}finally{if(image!=null)image.recycle();}});
    }
    private int dp(float value) { return Ui.dp(this, value); }
}
