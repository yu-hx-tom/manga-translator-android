package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends ShellActivity {
    private static final int IMPORT = 31;
    private static final int INK = Ui.INK, MUTED = Ui.MUTED, ACCENT = Ui.ACCENT;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger activeWorkers=new java.util.concurrent.atomic.AtomicInteger();
    private String settling;
    private Future<?> submit(Runnable job){final int token=generation;return worker.submit(()->{activeWorkers.incrementAndGet();try{if(token==generation&&!isDestroyed())job.run();}finally{activeWorkers.decrementAndGet();runOnUiThread(this::settle);}});}
    private void settle(){if(settling!=null&&activeWorkers.get()==0){String value=settling;settling=null;TranslationTaskManager.done("single",value);}}
    private final List<View> lockedWhileBusy = new ArrayList<>();
    private TranslationEngine engine;
    private AppSettings settings;
    private Bitmap original, translated;
    private ResultView preview;
    private TextView status, selectionInfo, imageInfo;
    private ProgressBar progress;
    private Button detectButton, translateButton, compareButton, saveImageButton, reviewButton, cancelButton, transcriptButton, workbenchButton;
    /** Staged workbench draft of the shown translation; filed only if the user opens 编辑与导出. */
    private java.io.File draft;
    private TranslationTranscript transcript;
    private LinearLayout reviewActions, statusCard;
    private GradientDrawable statusBackground;
    private AtomicBoolean cancellation = new AtomicBoolean(false);
    private Future<?> active;
    private int generation = 0;
    private boolean busy = false, showingOriginal = true, reviewed = false;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        engine = new TranslationEngine(getApplicationContext());
        settings = AppSettings.load(this);
        buildUi();
        if (settings.keyUnavailable) setStatus("原有密钥无法读取，请重新填写 API Key。", false);
        receiveImage(getIntent());
    }

    private void buildUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout shell = column();
        shell.setBackgroundColor(Ui.BG);
        Ui.insets(shell, 0, 0, 0, 0, false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(8), dp(18), dp(28));
        Ui.smoothLayout(page);
        scroll.addView(page);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(shell);

        LinearLayout navigation=row();
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        Button back=Ui.button(this,"‹  返回",Ui.TEXT,v->finish());
        back.setPadding(dp(10),0,dp(12),0);
        navigation.addView(back,new LinearLayout.LayoutParams(-2,dp(44)));
        navigation.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        Button settingsButton=Ui.button(this,"⚙  设置",Ui.TONAL,v->startActivity(new Intent(this,SettingsActivity.class)));
        navigation.addView(settingsButton,new LinearLayout.LayoutParams(-2,dp(44)));
        page.addView(navigation,space(-8,0,0,6));

        TextView eyebrow = label("MANGA · 本地文字检测", 12, ACCENT);
        eyebrow.setLetterSpacing(.08f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        page.addView(eyebrow, space(0, 6, 0, 0));
        TextView title = label("单页翻译", 28, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title, space(0, 4, 0, 4));
        page.addView(label("导入、确认文字、翻译并保存。", 15, MUTED));

        LinearLayout imageCard = card(page, "单页翻译", "导入图片 → 本地检测 → 确认选区 → 翻译回填", 16);
        Button importButton = Ui.button(this, "＋  导入漫画图片", Ui.OUTLINED, v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT);
        });
        imageCard.addView(importButton, space(0, 10, 0, 8));
        lockedWhileBusy.add(importButton);
        imageInfo = label("支持相册、文件，以及其他应用分享来的图片", 12, MUTED);
        imageCard.addView(imageInfo, space(0, 0, 0, 8));
        preview = new ResultView(this);
        Ui.roundClip(preview, 14);
        imageCard.addView(preview, new LinearLayout.LayoutParams(-1, dp(410)));
        preview.setSelectionChanged(this::updateSelection);
        selectionInfo = label("绿色框表示参与翻译；点一下可排除误检。", 13, MUTED);
        imageCard.addView(selectionInfo, space(0, 10, 0, 6));
        reviewActions = row();
        Button all = button("全选", false, v -> preview.selectAll(true));
        Button none = button("全不选", false, v -> preview.selectAll(false));
        addEqual(reviewActions, all, 0); addEqual(reviewActions, none, 8);
        lockedWhileBusy.add(all); lockedWhileBusy.add(none);
        imageCard.addView(reviewActions);
        reviewActions.setVisibility(View.GONE);
        LinearLayout actionRow = row();
        detectButton = button("1  检测文字", false, v -> detect());
        translateButton = button("2  翻译选区", true, v -> translate());
        addEqual(actionRow, detectButton, 0); addEqual(actionRow, translateButton, 8);
        imageCard.addView(actionRow, space(0, 8, 0, 0));
        LinearLayout resultRow = row();
        compareButton = button("查看原图", false, v -> compare());
        saveImageButton = button("保存译图", false, v -> saveImage());
        addEqual(resultRow, compareButton, 0); addEqual(resultRow, saveImageButton, 8);
        imageCard.addView(resultRow, space(0, 6, 0, 0));
        transcriptButton=Ui.button(this,"识读原文 / 译文",Ui.OUTLINED,v->TranscriptDialog.show(this,transcript));
        imageCard.addView(transcriptButton,space(0,5,0,0));
        workbenchButton=Ui.button(this,"编辑与导出（逐段改字、调字号）",Ui.TONAL,v->openWorkbench());
        imageCard.addView(workbenchButton,space(0,5,0,0));
        reviewButton = button("调整选区", false, v -> {
            preview.setBitmap(original);
            preview.setShowBoxes(true);
            showingOriginal = true;
            reviewed = true;
            updateSelection();
        });
        imageCard.addView(reviewButton, space(0, 5, 0, 0));

        statusCard = column();
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusBackground = round(Ui.INFO_SOFT, 16);
        statusCard.setBackground(statusBackground);
        statusCard.setTag(R.id.ui_tint_color, Ui.INFO_SOFT);
        Ui.smoothLayout(statusCard);
        page.addView(statusCard, space(0, 14, 0, 0));
        status = new Ui.StatusText(this);
        status.setText("导入一页图片。文字检测无需联网。");
        status.setTextSize(14); status.setTextColor(INK); status.setLineSpacing(dp(3), 1f);
        status.setTextIsSelectable(true);
        statusCard.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        progress.setVisibility(View.GONE);
        statusCard.addView(progress, space(0, 8, 0, 0));
        cancelButton = Ui.button(this, "取消当前任务", Ui.DANGER_TONAL, v -> cancelTask());
        cancelButton.setVisibility(View.GONE);
        statusCard.addView(cancelButton, space(0, 6, 0, 0));

        updateControls();
        Ui.enter(page, 40);
    }

    private void receiveImage(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null && "content".equals(uri.getScheme())) importImage(uri, null);
        } else if (intent.hasExtra("imagePath")) {
            importImage(null, intent.getStringExtra("imagePath"));
        } else if (intent.hasExtra("imageUri")) {
            String value = intent.getStringExtra("imageUri");
            if (value != null && "content".equals(Uri.parse(value).getScheme())) importImage(Uri.parse(value), null);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (busy) cancelTask();
        receiveImage(intent);
    }

    @Override protected void onResume() {
        super.onResume();AppSettings current=AppSettings.load(this);
        if(settings!=null&&!settings.detectorModel.equals(current.detectorModel)){
            cancelTask();reviewed=false;showingOriginal=true;
            Bitmap old=translated;translated=null;transcript=null;
            if(preview!=null){preview.setBitmap(original);preview.setRegions(new ArrayList<>());preview.setShowBoxes(true);}
            if(old!=null&&old!=original&&!old.isRecycled())old.recycle();
            finish("本地检测模型已改变，请重新检测文字；旧选区和译图已清除。");
        }
        settings=current;
    }

    private void importImage(Uri uri, String path) {
        int token = begin("正在读取图片…");
        active = submit(() -> {
            try {
                Bitmap loaded = path == null ? Storage.load(this, uri) : Storage.loadCached(this, path);
                deliver(token, () -> {
                    original = loaded;
                    translated = null;
                    replaceDraft(null);
                    transcript = null;
                    reviewed = false;
                    showingOriginal = true;
                    preview.setBitmap(original);
                    preview.resetZoom();
                    preview.setRegions(new ArrayList<>());
                    preview.setShowBoxes(true);
                    imageInfo.setText("已载入 " + original.getWidth() + " × " + original.getHeight() + " 像素 · 双指缩放查看");
                    finish("图片已载入。点击“检测文字”，确认选区后再翻译。");
                });
            } catch (Exception | OutOfMemoryError error) { fail(token, error); }
        });
    }

    private void detect() {
        if (original == null || busy) return;
        Bitmap input = original;
        final AppSettings detectionSettings=AppSettings.load(this);
        int token = begin("正在手机本地检测文字，首次加载模型可能稍慢…");
        active = submit(() -> {
            try {
                List<Region> regions = engine.detect(input,detectionSettings);
                deliver(token, () -> {
                    reviewed = true;
                    preview.setBitmap(original);
                    preview.setRegions(regions);
                    preview.setShowBoxes(true);
                    showingOriginal = true;
                    finish(regions.isEmpty() ? "未检测到文字。请导入更清晰的单页图片，或放大漫画后重新截屏。"
                            : "检测到 " + regions.size() + " 个段落。绿色选中，灰色排除；点框可调整。");
                });
            } catch (Exception | OutOfMemoryError error) { fail(token, error); }
        });
    }

    protected String translationDisabled(){return original==null?"请先选择图片":preview.getSelectedRegions().isEmpty()?"请先检测并选择文字框":null;}
    protected void startTranslation(){translate();}
    private void translate() {
        List<Region> selected = preview.getSelectedRegions();
        List<Region> protectionRegions = preview.getAllRegions();
        if (original == null || selected.isEmpty() || busy) return;
        AppSettings configuration;
        try { configuration = AppSettings.load(this); configuration.validate(); }
        catch (Exception error) { setStatus(safeMessage(error), true); startActivity(new Intent(this,SettingsActivity.class)); return; }
        if(!TranslationTaskManager.begin(this,"single",this::cancelTask)){android.widget.Toast.makeText(this,"已有翻译任务，请先停止",0).show();return;}
        Bitmap input = original;
        int token = begin("准备翻译 " + selected.size() + " 个选区…");
        AtomicBoolean cancelled = cancellation;
        active = submit(() -> {
            try {
                TranslationEngine.Result result = engine.translate(input, selected, protectionRegions, configuration,
                        (message, done, total) -> deliver(token, () -> {
                            status.setText(message);
                            progress.setIndeterminate(total <= 0);
                            if (total > 0) { progress.setMax(total); progress.setProgress(done, true); }
                        }), cancelled::get);
                runOnUiThread(() -> {
                    if(isDestroyed() || token != generation || cancellation.get()) {
                        if(result.image != input && !result.image.isRecycled())result.image.recycle();
                        return;
                    }
                    settings = configuration;
                    transcript = result.transcript;
                    replaceDraft(result.succeeded > 0 ? result.draft : null);
                    if (result.succeeded == 0) PageDraftStore.discard(result.draft);
                    if (result.succeeded == 0) {
                        if(result.image != input && !result.image.isRecycled())result.image.recycle();
                        translated = null;
                        preview.setBitmap(original);
                        preview.setShowBoxes(true);
                        showingOriginal = true;
                        reviewed = true;
                        finish(result.summary + "\n本次没有可回填的译文，原图已保留。可调整选区或检查接口后重试。");
                        return;
                    }
                    translated = result.image;
                    preview.setBitmap(translated);
                    preview.setShowBoxes(false);
                    showingOriginal = false;
                    reviewed = false;
                    finish(result.summary + "\n可以查看原图对比，或保存到相册。");
                });
            } catch (Exception | OutOfMemoryError error) { fail(token, error); }
        });
    }

    private void compare() {
        if (translated == null) return;
        showingOriginal = !showingOriginal;
        reviewed = false;
        preview.setShowBoxes(false);
        preview.setBitmap(showingOriginal ? original : translated);
        updateControls();
    }

    private void saveImage() {
        if (translated == null || busy) return;
        Bitmap output = translated;
        int token = begin("正在保存 PNG 到相册…");
        active = submit(() -> {
            try {
                Storage.save(this, output, "漫画译图");
                deliver(token, () -> finish("已保存到相册的“漫画翻译助手”文件夹。"));
            } catch (Exception error) { fail(token, error); }
        });
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==IMPORT&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            importImage(uri,null);
        }
    }

    private int begin(String message) {
        generation++;
        cancellation = new AtomicBoolean(false);
        busy = true;
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.VISIBLE);
        setStatus(message, false);
        updateControls();
        return generation;
    }

    private void deliver(int token, Runnable action) {
        runOnUiThread(() -> { if (!isDestroyed() && token == generation && !cancellation.get()) action.run(); });
    }

    private void finish(String message) {settling=message;settle();
        busy = false;
        progress.setVisibility(View.GONE);
        cancelButton.setVisibility(View.GONE);
        setStatus(message, false);
        updateSelection();
    }

    private void fail(int token, Throwable error) {
        new TranslationLog(new java.io.File(getFilesDir(),"diagnostics")).record("single-page","","page_failure",safeMessage(error),AppSettings.load(this));
        deliver(token, () -> {
            finish(safeMessage(error));
            status.setTextColor(Ui.DANGER);
            Ui.tint(statusCard, statusBackground, Ui.DANGER_SOFT);
            Ui.shake(statusCard);
        });
    }

    private String safeMessage(Throwable error) {
        if (error instanceof OutOfMemoryError) return "图片过大，手机内存不足。请先截取单页再试。";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "操作未完成，请重试。";
        String savedKey = settings == null ? "" : settings.apiKey;
        if (!savedKey.isEmpty()) message = message.replace(savedKey, "[密钥已隐藏]");
        return message.length() > 450 ? message.substring(0, 450) + "…" : message;
    }

    private void cancelTask() {
        TranslationTaskManager.stopping("single");
        cancellation.set(true);
        generation++;
        if (active != null) active.cancel(true);
        finish("已取消本次任务。已发送到接口的请求可能仍在服务端处理。");
    }

    private void updateSelection() {
        if (selectionInfo == null) return;
        int count = preview.getSelectedRegions().size();
        selectionInfo.setText(reviewed ? "已选 " + count + " 个段落 · 点文字框切换选择，双指放大查看"
                : translated != null ? (showingOriginal ? "当前显示原图" : "当前显示译图") : "检测后可点选文字框，排除误检。");
        updateControls();
    }

    private void updateControls() {
        if (detectButton == null || preview == null) return;
        for (View view : lockedWhileBusy) view.setEnabled(!busy);
        detectButton.setEnabled(!busy && original != null);
        translateButton.setEnabled(!busy && original != null && !preview.getSelectedRegions().isEmpty());
        compareButton.setEnabled(!busy && translated != null);
        compareButton.setText(showingOriginal ? "查看译图" : "查看原图");
        saveImageButton.setEnabled(!busy && translated != null);
        transcriptButton.setEnabled(!busy && transcript != null);
        workbenchButton.setVisibility(translated != null && draft != null ? View.VISIBLE : View.GONE);
        workbenchButton.setEnabled(!busy);
        reviewButton.setVisibility(translated != null ? View.VISIBLE : View.GONE);
        reviewButton.setEnabled(!busy);
        reviewActions.setVisibility(reviewed ? View.VISIBLE : View.GONE);
        preview.setEditable(!busy);
        if (selectionInfo != null && !reviewed && translated != null) {
            selectionInfo.setText(showingOriginal ? "当前显示原图 · 双指缩放可检查细节" : "当前显示译图 · 双指缩放可检查细节");
        }
    }

    private void replaceDraft(java.io.File next) {
        if (draft != null && draft != next) PageDraftStore.discard(draft);
        draft = next;
    }

    /** Files this page's draft and opens it as a one-page project in the workbench. */
    private void openWorkbench() {
        if (draft == null || busy) return;
        java.io.File staged = draft; draft = null;
        String key = LocalComics.sha("single\n" + java.util.UUID.randomUUID());
        if (!PageDraftStore.commit(this, staged, key)) { setStatus("无法保存可编辑草稿，请检查存储空间后重新翻译。", true); updateControls(); return; }
        updateControls();
        ProjectLauncher.launch(this, ProjectLauncher.dated("单页翻译"), "single", null,
                () -> new ProjectLauncher.Gathered(java.util.Collections.singletonList(new ProjectStore.PageSpec("单页", key, null, null, false)), ""), null);
    }

    private void setStatus(String message, boolean error) {
        status.setText(message);
        status.setTextColor(error ? Ui.DANGER : INK);
        Ui.tint(statusCard, statusBackground, error ? Ui.DANGER_SOFT : busy ? Ui.ACCENT_SOFT : Ui.INFO_SOFT);
        if (error) Ui.shake(statusCard);
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(sp); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }
    private LinearLayout card(LinearLayout parent, String heading, String detail, int top) {
        return Ui.section(this, parent, heading, detail, top);
    }
    private Button button(String text, boolean primary, View.OnClickListener listener) {
        return Ui.button(this, text, primary ? Ui.PRIMARY : Ui.TONAL, listener);
    }
    private void addEqual(LinearLayout row, View child, int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.leftMargin = dp(left); row.addView(child, params);
    }
    private LinearLayout.LayoutParams space(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return params;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        TranslationTaskManager.stopping("single");settling="单页任务已停止，已保存文件保留";settle();
        replaceDraft(null);
        cancellation.set(true);
        generation++;
        if (active != null) active.cancel(true);
        worker.shutdownNow();
        if(engine!=null)new Thread(()->{try{engine.close();}catch(Exception ignored){}},"manga-detector-close").start();
        super.onDestroy();
    }
}


