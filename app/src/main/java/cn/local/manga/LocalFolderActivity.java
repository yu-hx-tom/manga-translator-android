package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One local folder: chapter subfolders and a page grid with per-page translation state. */
public final class LocalFolderActivity extends ShellActivity {
    static Intent intent(Context context, LocalComics.Folder folder) {
        return new Intent(context, LocalFolderActivity.class).putExtra("tree", folder.tree.toString())
                .putExtra("id", folder.documentId).putExtra("name", folder.name);
    }
    static LocalComics.Folder folderOf(Intent intent) {
        String tree = intent.getStringExtra("tree"), id = intent.getStringExtra("id");
        if (tree == null || id == null) return null;
        String name = intent.getStringExtra("name");
        return new LocalComics.Folder(Uri.parse(tree), id, name == null ? "文件夹" : name);
    }

    private LocalComics.Folder folder;
    private LocalComics.Listing listing;
    private final Map<String, PageOutcome> outcomes = new HashMap<>();
    private final List<Object> cells = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(), thumbs = Executors.newFixedThreadPool(2);
    private GridView grid;
    private Adapter adapter;
    private TextView summary, status;
    private ProgressBar progress;
    private Button primary, more, workbench;
    private GradientDrawable summaryBackground;
    private LinearLayout summaryCard;
    private boolean destroyed, firstLoad = true;
    private int primaryKind = Ui.PRIMARY;
    private int loadToken;
    private final LocalBatch.Listener batchChanged = this::batchChanged;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        folder = folderOf(getIntent());
        if (folder == null) { finish(); return; }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        Ui.insets(root, 0, 0, 0, 0, false);
        Button back = new Button(this); back.setText("‹  返回"); back.setOnClickListener(v -> finish());
        root.addView(Ui.topBar(this, back, folder.name));

        summaryCard = new LinearLayout(this); summaryCard.setOrientation(LinearLayout.VERTICAL);
        summaryCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        summaryBackground = Ui.round(this, Ui.SURFACE, 20); summaryBackground.setStroke(dp(1), Ui.OUTLINE);
        summaryCard.setBackground(summaryBackground); summaryCard.setTag(R.id.ui_tint_color, Ui.SURFACE);
        Ui.smoothLayout(summaryCard);
        LinearLayout.LayoutParams cardParams = Ui.margins(this, 16, 2, 16, 8);
        root.addView(summaryCard, cardParams);
        summary = Ui.heading(this, "读取中…", 15);
        summaryCard.addView(summary);
        status = new Ui.StatusText(this); status.setTextSize(12); status.setTextColor(Ui.MUTED); status.setLineSpacing(dp(2), 1f);
        summaryCard.addView(status, Ui.margins(this, 0, 4, 0, 0));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.setVisibility(View.GONE);
        summaryCard.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)) {{ topMargin = dp(8); }});
        LinearLayout actions = new LinearLayout(this);
        primary = Ui.button(this, "开始翻译", Ui.PRIMARY, v -> primaryAction());
        more = Ui.button(this, "更多", Ui.OUTLINED, v -> moreActions());
        workbench = Ui.button(this, "汉化与导出", Ui.TONAL, v -> openWorkbench(null));
        actions.addView(primary, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams workbenchParams = new LinearLayout.LayoutParams(-2, -2); workbenchParams.leftMargin = dp(8);
        actions.addView(workbench, workbenchParams);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(-2, -2); moreParams.leftMargin = dp(8);
        actions.addView(more, moreParams);
        summaryCard.addView(actions, Ui.margins(this, 0, 10, 0, 0));

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT); grid.setColumnWidth(dp(104));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(10)); grid.setVerticalSpacing(dp(12));
        grid.setPadding(dp(16), dp(4), dp(16), dp(24)); grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setVerticalScrollBarEnabled(false);
        android.view.animation.AnimationSet cellIn = new android.view.animation.AnimationSet(true);
        cellIn.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
        cellIn.addAnimation(new android.view.animation.ScaleAnimation(.92f, 1f, .92f, 1f,
                android.view.animation.Animation.RELATIVE_TO_SELF, .5f, android.view.animation.Animation.RELATIVE_TO_SELF, .5f));
        cellIn.setDuration(280); cellIn.setInterpolator(Ui.EASE);
        grid.setLayoutAnimation(new android.view.animation.GridLayoutAnimationController(cellIn, .12f, .12f));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> openCell(position));
        grid.setOnItemLongClickListener((parent, view, position, id) -> { pageMenu(position); return true; });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (Ui.motion()) { summaryCard.setAlpha(0f); summaryCard.setTranslationY(dp(12)); summaryCard.animate().alpha(1f).translationY(0f).setDuration(340).setInterpolator(Ui.EASE).start(); }
    }

    @Override protected void onResume() {
        super.onResume();
        LocalBatch.listen(batchChanged);
        reload();
    }
    @Override protected void onPause() { LocalBatch.unlisten(batchChanged); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); thumbs.shutdownNow(); super.onDestroy(); }

    /** Re-lists the folder off the main thread; outputs and records may have changed in the reader. */
    private void reload() {
        int token = ++loadToken;
        io.submit(() -> {
            try {
                LocalComics.Listing fresh = LocalComics.list(this, folder);
                Map<String, PageOutcome> records = new HashMap<>();
                for (LocalComics.Page page : fresh.pages) {
                    PageOutcome outcome = LocalComics.readOutcome(this, folder, page);
                    if (outcome != null) records.put(page.source.name, outcome);
                }
                runOnUiThread(() -> {
                    if (destroyed || token != loadToken) return;
                    listing = fresh; outcomes.clear(); outcomes.putAll(records);
                    cells.clear(); cells.addAll(fresh.folders); cells.addAll(fresh.pages);
                    adapter.notifyDataSetChanged();
                    if (firstLoad && !cells.isEmpty() && Ui.motion()) grid.scheduleLayoutAnimation();
                    firstLoad = false;
                    refreshHeader();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    summary.setText("无法读取文件夹");
                    status.setText(error.getMessage());
                    primary.setEnabled(false);
                });
            }
        });
    }

    /** Batch progress: refresh header + cells; when a page finishes, re-list so its translated thumbnail appears. */
    private int lastFinished = -1;
    private boolean wasRunning;
    private void batchChanged() {
        if (destroyed) return;
        boolean running = LocalBatch.isActive(folder);
        int[] counts = LocalBatch.counts(folder);
        int finished = counts == null ? -1 : counts[0];
        if (running != wasRunning || (running && finished != lastFinished)) { wasRunning = running; lastFinished = finished; reload(); }
        else { adapter.notifyDataSetChanged(); refreshHeader(); }
    }

    private void refreshHeader() {
        boolean running = LocalBatch.isActive(folder), busyElsewhere = LocalBatch.isActive() && !running;
        getWindow().getDecorView().setKeepScreenOn(running);
        if (listing == null) return;
        int done = 0, partial = 0, failed = 0, fresh = 0, noText = 0;
        for (LocalComics.Page page : listing.pages) {
            switch (LocalComics.state(outcomes.get(page.source.name), page)) {
                case DONE: done++; break; case PARTIAL: partial++; break; case FAILED: failed++; break;
                case NO_TEXT: noText++; break; default: fresh++;
            }
        }
        int pages = listing.pages.size();
        if (pages == 0) {
            summary.setText(listing.folders.isEmpty() ? "这个文件夹里没有图片" : listing.folders.size() + " 个子文件夹");
            status.setText(listing.folders.isEmpty() ? "请选择直接装有漫画图片（jpg / png / webp 等）的文件夹。" : "点开章节文件夹查看页面并翻译。");
            primary.setVisibility(View.GONE); more.setVisibility(View.GONE); workbench.setVisibility(View.GONE); progress.setVisibility(View.GONE);
            return;
        }
        primary.setVisibility(View.VISIBLE); more.setVisibility(View.VISIBLE); workbench.setVisibility(View.VISIBLE);
        summary.setText("共 " + pages + " 页 · 已完成 " + (done + noText) + (listing.folders.isEmpty() ? "" : " · " + listing.folders.size() + " 个子文件夹"));
        String states = (partial > 0 ? "部分完成 " + partial + " · " : "") + (failed > 0 ? "失败 " + failed + " · " : "") + "未翻译 " + fresh;
        String line = LocalBatch.progressLine(folder);
        status.setText(running ? line : busyElsewhere ? states + "\n「" + LocalBatch.activeFolderName() + "」正在翻译，完成后可开始本文件夹。"
                : states + (line.isEmpty() ? "\n译图保存在「" + LocalComics.OUTPUT_DIR + "」子文件夹。" : "\n" + line));
        int[] counts = LocalBatch.counts(folder);
        if (running && counts != null) {
            progress.setVisibility(View.VISIBLE); progress.setMax(Math.max(1, counts[1])); progress.setProgress(counts[0], true);
        } else progress.setVisibility(View.GONE);
        int remaining = partial + failed + fresh;
        primary.setText(running ? "停止翻译" : remaining == 0 ? "全部已完成" : done + partial + failed + noText > 0 ? "继续翻译（剩 " + remaining + " 页）" : "开始翻译 " + remaining + " 页");
        int kind = running ? Ui.DANGER_TONAL : Ui.PRIMARY;
        if (primaryKind != kind) { primaryKind = kind; Ui.style(primary, kind); }
        primary.setEnabled(running || (!busyElsewhere && remaining > 0));
        Ui.tint(summaryCard, summaryBackground, running ? 0xffF3F7FE : Ui.SURFACE);
    }

    protected String translationDisabled(){return listing==null||listing.pages.isEmpty()?"请先打开漫画章节":null;}
    protected void startTranslation(){primaryAction();}
    private void primaryAction() {
        if (LocalBatch.isActive(folder)) {
            new AlertDialog.Builder(this).setTitle("停止翻译？").setMessage("已完成的页面会保留；正在处理的页面会放弃，已发出的请求可能仍计费。")
                    .setNegativeButton("继续翻译", null).setPositiveButton("停止", (d, w) -> LocalBatch.stop()).show();
            return;
        }
        List<LocalComics.Page> todo = new ArrayList<>();
        for (LocalComics.Page page : listing.pages) {
            LocalComics.State state = LocalComics.state(outcomes.get(page.source.name), page);
            if (state != LocalComics.State.DONE && state != LocalComics.State.NO_TEXT) todo.add(page);
        }
        startPages(todo, false, false);
    }

    /** 汉化与导出: collect this folder's translated pages into a project (or reopen it) and edit/export there. */
    private void openWorkbench(String startLabel) {
        if (listing == null) return;
        if (listing.pages.isEmpty()) { status.setText("这个文件夹里没有页面；请进入章节子文件夹后再汉化与导出。"); Ui.shake(summaryCard); return; }
        ProjectLauncher.launch(this, folder.name, "local", "local:" + folder.key(), () -> ProjectLauncher.localFolder(this, folder), startLabel);
    }

    private void moreActions() {
        if (listing == null) return;
        boolean running = LocalBatch.isActive(folder);
        String[] items = {"全部重新翻译（重新检测并请求）", "刷新列表", "设置"};
        new AlertDialog.Builder(this).setItems(items, (d, which) -> {
            if (which == 0) {
                if (running || LocalBatch.isActive()) { status.setText("请先停止正在进行的翻译。"); return; }
                new AlertDialog.Builder(this).setTitle("全部重新翻译？")
                        .setMessage("将对 " + listing.pages.size() + " 页重新检测并调用当前接口，会产生费用，并覆盖「" + LocalComics.OUTPUT_DIR + "」里的旧译图。")
                        .setNegativeButton("取消", null).setPositiveButton("重新翻译", (x, y) -> startPages(new ArrayList<>(listing.pages), true, false)).show();
            } else if (which == 1) reload();
            else startActivity(new Intent(this, SettingsActivity.class));
        }).show();
    }

    private void startPages(List<LocalComics.Page> pages, boolean force, boolean front) {
        if (pages.isEmpty()) return;
        AppSettings settings = AppSettings.load(this);
        try { settings.validate(); }
        catch (Exception e) {
            new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("设置接口", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("取消", null).show();
            return;
        }
        String refused = LocalBatch.start(this, folder, pages, force, front, settings);
        if (refused != null) { status.setText(refused); Ui.shake(summaryCard); return; }
        refreshHeader();
    }

    private void openCell(int position) {
        if (position < 0 || position >= cells.size()) return;
        Object cell = cells.get(position);
        if (cell instanceof LocalComics.Entry) {
            LocalComics.Entry child = (LocalComics.Entry) cell;
            LocalComics.Folder next = new LocalComics.Folder(folder.tree, child.documentId, child.name);
            LocalComics.remember(this, next);
            startActivity(intent(this, next));
        } else {
            startActivity(LocalReaderActivity.intent(this, folder, listing.pages.indexOf(cell)));
        }
    }

    private void pageMenu(int position) {
        if (position < 0 || position >= cells.size() || !(cells.get(position) instanceof LocalComics.Page)) return;
        LocalComics.Page page = (LocalComics.Page) cells.get(position);
        PageOutcome outcome = outcomes.get(page.source.name);
        String error = LocalBatch.error(folder, page.source.name);
        String detail = (outcome == null ? "尚未翻译。" : outcome.describe()) + (error == null ? "" : "\n本次未完成：" + error);
        new AlertDialog.Builder(this).setTitle(page.source.name).setMessage(detail)
                .setPositiveButton("打开", (d, w) -> openCell(position))
                .setNeutralButton("重新翻译本页", (d, w) -> startPages(java.util.Collections.singletonList(page), true, true))
                .setNegativeButton("原文 / 译文", (d, w) -> TranscriptDialog.show(this, outcome == null ? null : outcome.transcript)).show();
    }

    private int dp(float value) { return Ui.dp(this, value); }

    // ---------- Grid ----------

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return cells.size(); }
        @Override public Object getItem(int position) { return cells.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return cells.get(position) instanceof LocalComics.Entry ? 0 : 1; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            Object cell = cells.get(position);
            if (cell instanceof LocalComics.Entry) return folderCell((LocalComics.Entry) cell, convert);
            return pageCell((LocalComics.Page) cell, position, convert);
        }
    }

    private View folderCell(LocalComics.Entry entry, View convert) {
        LinearLayout view = convert instanceof LinearLayout ? (LinearLayout) convert : null;
        if (view == null) {
            view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setGravity(Gravity.CENTER);
            view.setPadding(dp(8), dp(10), dp(8), dp(10));
            view.setBackground(Ui.ripple(Ui.card(this, 16), Ui.round(this, 0xffFFFFFF, 16), 0x241A73E8));
            view.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(150)));
            TextView icon = Ui.text(this, "📁", 34, Ui.INK); icon.setGravity(Gravity.CENTER);
            TextView name = Ui.text(this, "", 13, Ui.INK); name.setGravity(Gravity.CENTER); name.setMaxLines(3); name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            view.addView(icon); view.addView(name, Ui.margins(this, 0, 8, 0, 0));
            view.setDuplicateParentStateEnabled(false);
            Ui.pressable(view);
        }
        ((TextView) view.getChildAt(1)).setText(entry.name);
        return view;
    }

    private View pageCell(LocalComics.Page page, int position, View convert) {
        FrameLayout view = convert instanceof FrameLayout ? (FrameLayout) convert : null;
        if (view == null) {
            view = new FrameLayout(this);
            view.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(150)));
            view.setBackground(Ui.round(this, 0xffE6EBF2, 14));
            Ui.roundClip(view, 14);
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.addView(image, new FrameLayout.LayoutParams(-1, -1));
            GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xB0000000});
            TextView label = Ui.text(this, "", 11, Color.WHITE); label.setBackground(shade); label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE); label.setPadding(dp(8), dp(18), dp(8), dp(6));
            view.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            TextView badge = Ui.text(this, "", 10, Color.WHITE); badge.setPadding(dp(7), dp(2), dp(7), dp(3));
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            badgeParams.setMargins(0, dp(6), dp(6), 0);
            view.addView(badge, badgeParams);
            View press = new View(this);
            press.setBackground(Ui.ripple(null, Ui.round(this, 0xffFFFFFF, 14), 0x33FFFFFF));
            press.setDuplicateParentStateEnabled(true);
            view.addView(press, new FrameLayout.LayoutParams(-1, -1));
            Ui.pressable(view);
        }
        ImageView image = (ImageView) view.getChildAt(0);
        TextView label = (TextView) view.getChildAt(1), badge = (TextView) view.getChildAt(2);
        label.setText((listing.pages.indexOf(page) + 1) + " · " + page.source.name);
        String stage = LocalBatch.stage(folder, page.source.name);
        LocalComics.State state = LocalComics.state(outcomes.get(page.source.name), page);
        int color; String text;
        if (stage != null) { text = "排队中".equals(stage) ? "排队" : "翻译中"; color = "排队中".equals(stage) ? 0xE0667085 : 0xE01A73E8; }
        else switch (state) {
            case DONE: text = "✓ 已译"; color = 0xE0137333; break;
            case PARTIAL: text = "部分"; color = 0xE0B06000; break;
            case FAILED: text = "失败"; color = 0xE0B3261E; break;
            case NO_TEXT: text = "无文字"; color = 0xE0667085; break;
            default: text = null; color = 0;
        }
        badge.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (text != null) { badge.setText(text); badge.setBackground(Ui.round(this, color, 10)); }
        // Running cells breathe so progress is visible at a glance.
        if ("翻译中".equals(text) && Ui.motion()) {
            if (badge.getAnimation() == null) {
                android.view.animation.AlphaAnimation breathe = new android.view.animation.AlphaAnimation(1f, .45f);
                breathe.setDuration(700); breathe.setRepeatMode(android.view.animation.Animation.REVERSE);
                breathe.setRepeatCount(android.view.animation.Animation.INFINITE);
                badge.startAnimation(breathe);
            }
        } else badge.clearAnimation();
        // Prefer the translated page as the thumbnail once it exists.
        LocalComics.Entry shown = page.output != null ? page.output : page.source;
        String key = LocalComics.thumbKey(folder, shown);
        image.setTag(key);
        Bitmap cached = LocalComics.cachedThumb(key);
        if (cached != null) { image.setImageBitmap(cached); image.setAlpha(1f); return view; }
        image.setImageDrawable(null);
        int width = Math.max(dp(104), grid.getColumnWidth() > 0 ? grid.getColumnWidth() : dp(104));
        thumbs.submit(() -> {
            try {
                Bitmap bitmap = LocalComics.thumb(this, folder, shown, Math.min(width, 360));
                runOnUiThread(() -> {
                    if (destroyed || !key.equals(image.getTag())) return;
                    image.setImageBitmap(bitmap);
                    if (Ui.motion()) { image.setAlpha(0f); image.animate().alpha(1f).setDuration(220).start(); }
                });
            } catch (Exception | OutOfMemoryError ignored) { /* The tile keeps its placeholder color. */ }
        });
        return view;
    }
}
