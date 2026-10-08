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
    private android.widget.ImageButton previous,next,compare;
    private boolean holdingOriginal;
    private String lastStage;
    private final LocalBatch.Listener batchChanged = this::batchChanged;

    // Native ImageButton handles accessibility clicks; the touch path also calls performClick once.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        folder = LocalFolderActivity.folderOf(getIntent());
        if (folder == null) { finish(); return; }
        index = Math.max(0, getIntent().getIntExtra("index", 0));
        if (state != null) { index = state.getInt("index", index); showTranslated = state.getBoolean("translated", true); }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        Ui.insets(root, 0, 0, 0, 0, false);
        root.addView(Ui.appBar(this,Icons.iconButton(this,R.drawable.ic_arrow_back,"返回",v->finish()),folder.name,"旧版文件夹"));
        preview = new ResultView(this);
        preview.setEditable(false); preview.setShowBoxes(false);
        preview.setOnSwipe(this::turn);
        preview.setContentDescription("漫画页面，左右滑动翻页，双击或双指缩放");
        android.widget.FrameLayout frame=new android.widget.FrameLayout(this);frame.addView(preview,new android.widget.FrameLayout.LayoutParams(-1,-1));
        compare=Icons.iconButton(this,R.drawable.ic_visibility,"按住看原图，点击切换原图和译图",v->{if(!holdingOriginal){showTranslated=!showTranslated;show(false,0);}});compare.setBackground(Ui.round(this,Ui.OVERLAY,24));
        compare.setOnTouchListener((v,event)->{if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){holdingOriginal=true;show(false,0);return true;}if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP||event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL){if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP)v.performClick();holdingOriginal=false;show(false,0);return true;}return true;});
        android.widget.FrameLayout.LayoutParams cp=new android.widget.FrameLayout.LayoutParams(dp(48),dp(48),android.view.Gravity.BOTTOM|android.view.Gravity.START);cp.setMargins(dp(12),0,0,dp(12));frame.addView(compare,cp);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(10)); panel.setBackgroundColor(Ui.SURFACE); panel.setElevation(dp(6));
        Ui.smoothLayout(panel);
        status = new Ui.StatusText(this); status.setTextSize(12); status.setTextColor(Ui.MUTED); status.setMaxLines(3);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> showDetail());
        panel.addView(status, Ui.margins(this, 4, 0, 4, 6));
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        previous=Icons.iconButton(this,R.drawable.ic_chevron_left,"上一页",v->turn(-1));next=Icons.iconButton(this,R.drawable.ic_chevron_right,"下一页",v->turn(1));
        row.addView(previous,new LinearLayout.LayoutParams(dp(48),dp(48)));title=Ui.text(this,"读取中…",14,Ui.INK);title.setGravity(android.view.Gravity.CENTER);title.setMinHeight(dp(48));title.setContentDescription("页码，点击跳转");title.setOnClickListener(v->jump());row.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));row.addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));row.addView(Icons.iconButton(this,R.drawable.ic_more_vert,"阅读选项",v->readerMenu()),new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(row);
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
        title.setText(title.getContext().getString(R.string.local_reader_activity_message_11, (index + 1), listing.pages.size()));
        boolean wantTranslated = showTranslated && !holdingOriginal && page.output != null;
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
        compare.setEnabled(page!=null&&page.output!=null);
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

        io.submit(() -> {
            String message;
            try { Storage.save(this, image, "漫画译图"); message = "已保存到相册的“漫画翻译助手”文件夹"; }
            catch (Exception e) { message = e.getMessage() == null ? "保存失败" : e.getMessage(); }
            String done = message;
            runOnUiThread(() -> { if (!destroyed) { Toast.makeText(this, done, Toast.LENGTH_SHORT).show(); updateButtons(); } });
        });
    }

    @Override protected String translationDisabled(){return listing==null||listing.pages.isEmpty()?"请先打开漫画页面":null;}
    @Override protected void startTranslation(){translateCurrent();}
    private void readerMenu(){
        boolean ready=listing!=null&&!listing.pages.isEmpty();Ui.Sheet s=Ui.sheet(this,"阅读选项");
        s.item(R.drawable.ic_compare,showTranslated?"切换到原图":"切换到译图",()->{showTranslated=!showTranslated;show(false,0);});
        s.item(R.drawable.ic_subtitles,"原文 / 译文",null,currentOutcome!=null&&!currentOutcome.transcript.rows.isEmpty(),this::showTranscript);
        s.item(R.drawable.ic_translate,"翻译本页",null,ready&&!TranslationTaskManager.running(),this::translateCurrent);
        s.item(R.drawable.ic_edit_note,"在汉化工具中打开",null,ready,this::openWorkbench);
        s.item(R.drawable.ic_ios_share,"保存到相册",null,translatedShown!=null,this::saveCurrent);s.show();
    }
    private void jump(){if(listing==null||listing.pages.isEmpty())return;android.widget.EditText input=new android.widget.EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setHint("1–"+listing.pages.size());Ui.field(input);new AlertDialog.Builder(this).setTitle("跳转到页码").setView(input).setNegativeButton("取消",null).setPositiveButton("跳转",(d,w)->{try{int target=Integer.parseInt(input.getText().toString())-1;if(target>=0&&target<listing.pages.size()){index=target;show(false,0);}}catch(NumberFormatException ignored){}}).show();}
    private int dp(float value) { return Ui.dp(this, value); }
}
