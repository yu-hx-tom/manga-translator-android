package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Page-by-page reader for a local folder: swipe to turn, compare original/translation, translate one page. */
public final class LocalReaderActivity extends ShellActivity {
    static Intent intent(Context context, LocalComics.Folder folder, int index) {
        return LocalFolderActivity.intent(context, folder).setClass(context, LocalReaderActivity.class).putExtra("index", index);
    }

    private LocalComics.Folder folder;
    private LocalComics.Listing listing;
    private int index, loadToken;
    private String currentName;
    private boolean showTranslated = true, destroyed;
    private Bitmap translatedShown;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ResultView preview;
    private TextView title, status;
    private Button previous, next, toggle, translate, transcript, save;
    private String lastStage;
    private final LocalBatch.Listener batchChanged = this::batchChanged;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        folder = LocalFolderActivity.folderOf(getIntent());
        if (folder == null) { finish(); return; }
        index = Math.max(0, getIntent().getIntExtra("index", 0));
        if (state != null) { index = state.getInt("index", index); showTranslated = state.getBoolean("translated", true); }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        Ui.insets(root, 0, 0, 0, 0, false);
        Button back = new Button(this); back.setText("‹  " + folder.name); back.setOnClickListener(v -> finish());
        back.setMaxWidth(Ui.dp(this, 220)); back.setSingleLine(true); back.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout bar = Ui.topBar(this, back, "");
        title = (TextView) bar.getChildAt(1);
        root.addView(bar);

        preview = new ResultView(this);
        preview.setEditable(false); preview.setShowBoxes(false);
        preview.setOnSwipe(this::turn);
        preview.setContentDescription("漫画页面，左右滑动翻页，双击或双指缩放");
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(10)); panel.setBackgroundColor(Ui.SURFACE); panel.setElevation(dp(6));
        Ui.smoothLayout(panel);
        status = new Ui.StatusText(this); status.setTextSize(12); status.setTextColor(Ui.MUTED); status.setMaxLines(3);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> showDetail());
        panel.addView(status, Ui.margins(this, 4, 0, 4, 6));
        LinearLayout row1 = new LinearLayout(this);
        previous = Ui.button(this, "‹ 上一页", Ui.TONAL, v -> turn(-1));
        toggle = Ui.button(this, "看原图", Ui.OUTLINED, v -> { showTranslated = !showTranslated; show(false, 0); });
        next = Ui.button(this, "下一页 ›", Ui.TONAL, v -> turn(1));
        add(row1, previous, 0); add(row1, toggle, 6); add(row1, next, 6);
        panel.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        translate = Ui.button(this, "翻译本页", Ui.PRIMARY, v -> translateCurrent());
        transcript = Ui.button(this, "原文 / 译文", Ui.OUTLINED, v -> showTranscript());
        // 汉化编辑 opens the workbench at this page; a long press still saves the shown translation to the gallery.
        save = Ui.button(this, "汉化编辑", Ui.TONAL, v -> openWorkbench());
        save.setOnLongClickListener(v -> { if (translatedShown != null) saveCurrent(); else Toast.makeText(this, "当前显示的不是译图", Toast.LENGTH_SHORT).show(); return true; });
        add(row2, translate, 0); add(row2, transcript, 6); add(row2, save, 6);
        panel.addView(row2, Ui.margins(this, 0, 6, 0, 0));
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        updateButtons();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("index", index); out.putBoolean("translated", showTranslated);
    }
    @Override protected void onResume() { super.onResume(); LocalBatch.listen(batchChanged); relist(true); }
    @Override protected void onPause() { LocalBatch.unlisten(batchChanged); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }

    /** Re-reads the folder (outputs may be new), keeping the reader on the same page by name. */
    private void relist(boolean animate) {
        io.submit(() -> {
            try {
                LocalComics.Listing fresh = LocalComics.list(this, folder);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    listing = fresh;
                    if (currentName != null) for (int i = 0; i < fresh.pages.size(); i++) if (fresh.pages.get(i).source.name.equals(currentName)) index = i;
                    if (fresh.pages.isEmpty()) { status.setText("这个文件夹里没有图片。"); updateButtons(); return; }
                    index = Math.min(index, fresh.pages.size() - 1);
                    show(animate, 0);
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!destroyed) status.setText(error.getMessage()); }); }
        });
    }

    private void turn(int direction) {
        if (listing == null) return;
        int target = index + direction;
        if (target < 0 || target >= listing.pages.size()) {
            Toast.makeText(this, direction > 0 ? "已经是最后一页" : "已经是第一页", Toast.LENGTH_SHORT).show();
            if (Ui.motion()) { // small rubber-band nudge at the ends
                preview.animate().cancel();
                preview.animate().translationX(-direction * dp(18)).setDuration(110).withEndAction(() ->
                        preview.animate().translationX(0).setDuration(220).setInterpolator(Ui.EASE).start()).start();
            }
            return;
        }
        index = target;
        show(true, direction);
    }

    /** Loads the current page (translated when available and wanted) and slides it in from the turn side. */
    private void show(boolean animate, int direction) {
        if (listing == null || listing.pages.isEmpty()) return;
        LocalComics.Page page = listing.pages.get(index);
        currentName = page.source.name;
        title.setText((index + 1) + " / " + listing.pages.size());
        boolean wantTranslated = showTranslated && page.output != null;
        int token = ++loadToken;
        if (direction != 0 && Ui.motion()) {
            preview.animate().cancel();
            preview.animate().translationX(-direction * dp(36)).alpha(.25f).setDuration(120).setInterpolator(Ui.STANDARD).start();
        }
        updateButtons();
        io.submit(() -> {
            try {
                Bitmap bitmap = wantTranslated ? LocalComics.loadOutput(this, folder, page) : LocalComics.load(this, folder, page.source);
                PageOutcome outcome = LocalComics.readOutcome(this, folder, page);
                runOnUiThread(() -> {
                    if (destroyed || token != loadToken) return;
                    translatedShown = wantTranslated ? bitmap : null;
                    preview.setBitmap(bitmap);
                    preview.resetZoom();
                    if (direction != 0 && Ui.motion()) {
                        preview.animate().cancel();
                        preview.setTranslationX(direction * dp(36));
                        preview.animate().translationX(0).alpha(1f).setDuration(260).setInterpolator(Ui.EASE).start();
                    } else { preview.setTranslationX(0); preview.setAlpha(1f); }
                    describe(page, outcome, wantTranslated);
                    updateButtons();
                });
            } catch (Exception | OutOfMemoryError error) {
                runOnUiThread(() -> {
                    if (destroyed || token != loadToken) return;
                    preview.setAlpha(1f); preview.setTranslationX(0);
                    status.setText(error instanceof OutOfMemoryError ? "图片过大，手机内存不足" : error.getMessage());
                });
            }
        });
    }

    private PageOutcome currentOutcome;
    private void describe(LocalComics.Page page, PageOutcome outcome, boolean translatedShownNow) {
        currentOutcome = outcome;
        String stage = LocalBatch.stage(folder, page.source.name), error = LocalBatch.error(folder, page.source.name);
        String text;
        if (stage != null) text = "排队中".equals(stage) ? "已加入翻译队列，稍候…" : "正在翻译本页：" + stage;
        else switch (LocalComics.state(outcome, page)) {
            case DONE: text = (translatedShownNow ? "译图" : "原图") + " · " + (outcome == null ? "已翻译" : outcome.describe().split("\n")[0]); break;
            case PARTIAL: text = (translatedShownNow ? "译图" : "原图") + " · 部分段落未完成，可点「重新翻译」补全（复用已有译文）"; break;
            case NO_TEXT: text = "本页未检测到文字"; break;
            case FAILED: text = "上次翻译未完成" + (error == null ? "，可点「翻译本页」重试" : "：" + error); break;
            default: text = error == null ? "原图 · 尚未翻译" : "翻译未完成：" + error;
        }
        status.setText(text);
    }

    private void updateButtons() {
        boolean ready = listing != null && !listing.pages.isEmpty();
        LocalComics.Page page = ready ? listing.pages.get(index) : null;
        previous.setEnabled(ready && index > 0);
        next.setEnabled(ready && index + 1 < listing.pages.size());
        toggle.setEnabled(page != null && page.output != null);
        toggle.setText(showTranslated && page != null && page.output != null ? "看原图" : "看译图");
        String stage = page == null ? null : LocalBatch.stage(folder, page.source.name);
        translate.setEnabled(page != null && stage == null);
        translate.setText(stage != null ? "翻译中…" : page != null && (page.output != null || currentOutcome != null) ? "重新翻译" : "翻译本页");
        transcript.setEnabled(currentOutcome != null && !currentOutcome.transcript.rows.isEmpty());
        save.setEnabled(ready);
        getWindow().getDecorView().setKeepScreenOn(LocalBatch.isActive(folder));
    }

    private void batchChanged() {
        if (destroyed || listing == null || listing.pages.isEmpty()) return;
        String stage = LocalBatch.stage(folder, currentName);
        // Page just left the queue (finished or failed): re-list to pick up the new output.
        if (lastStage != null && stage == null) { lastStage = null; showTranslated = true; relist(false); return; }
        lastStage = stage;
        describe(listing.pages.get(index), currentOutcome, translatedShown != null);
        updateButtons();
    }

    private void translateCurrent() {
        if (listing == null) return;
        LocalComics.Page page = listing.pages.get(index);
        boolean retry = page.output != null || currentOutcome != null;
        AppSettings settings = AppSettings.load(this);
        try { settings.validate(); }
        catch (Exception e) {
            new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("设置接口", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("取消", null).show();
            return;
        }
        Runnable go = () -> {
            // A complete page is redone from scratch; a partial one keeps the paid replies it already has.
            boolean force = retry && LocalComics.state(currentOutcome, page) == LocalComics.State.DONE;
            String refused = LocalBatch.start(this, folder, Collections.singletonList(page), force, true, settings);
            if (refused != null) { status.setText(refused); Ui.shake(status); return; }
            lastStage = "排队中";
            describe(page, currentOutcome, translatedShown != null);
            updateButtons();
        };
        if (!retry) { go.run(); return; }
        new AlertDialog.Builder(this).setTitle("重新翻译本页？")
                .setMessage("已完成的页面会重新检测并调用接口（可能产生费用），并覆盖原来的译图；部分完成的页面只补发缺失段落。")
                .setNegativeButton("取消", null).setPositiveButton("重新翻译", (d, w) -> go.run()).show();
    }

    private void showTranscript() { TranscriptDialog.show(this, currentOutcome == null ? null : currentOutcome.transcript); }
    private void showDetail() {
        if (currentOutcome == null) return;
        new AlertDialog.Builder(this).setTitle(currentName).setMessage(currentOutcome.describe()).setPositiveButton("关闭", null).show();
    }

    private void openWorkbench() {
        if (listing == null || listing.pages.isEmpty()) return;
        ProjectLauncher.launch(this, folder.name, "local", "local:" + folder.key(), () -> ProjectLauncher.localFolder(this, folder), currentName);
    }

    private void saveCurrent() {
        Bitmap image = translatedShown;
        if (image == null) return;
        save.setEnabled(false);
        io.submit(() -> {
            String message;
            try { Storage.save(this, image, "漫画译图"); message = "已保存到相册的“漫画翻译助手”文件夹"; }
            catch (Exception e) { message = e.getMessage() == null ? "保存失败" : e.getMessage(); }
            String done = message;
            runOnUiThread(() -> { if (!destroyed) { Toast.makeText(this, done, Toast.LENGTH_SHORT).show(); updateButtons(); } });
        });
    }

    private void add(LinearLayout row, View child, int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(left);
        if (child instanceof Button) ((Button) child).setPadding(dp(6), dp(6), dp(6), dp(6));
        row.addView(child, params);
    }
    private int dp(float value) { return Ui.dp(this, value); }
}
