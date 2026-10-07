package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 我的汉化工程: open, export, rename or delete saved projects. */
public final class ProjectListActivity extends ShellActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView summary;
    private boolean destroyed;
    private final ExportJob.Listener exportChanged = this::reload;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        Ui.insets(root, 0, 0, 0, 0, false);
        root.addView(Ui.appBar(this,null,"汉化工具",null,Icons.iconButton(this,R.drawable.ic_more_vert,"页面选项",this::settings)));
        ScrollView scroll = new ScrollView(this); scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(2), dp(16), dp(24));
        Button more=Ui.button(this,"⋮ 设置",Ui.TEXT,v->startActivity(new Intent(this,SettingsActivity.class)));page.addView(more);
        LinearLayout sources=new LinearLayout(this); sources.addView(Ui.button(this,"导入浏览器已翻译的漫画",Ui.TONAL,v->SessionRepository.choose(this)),new LinearLayout.LayoutParams(0,-2,1)); sources.addView(Ui.button(this,"选择本地文件开始翻译",Ui.TONAL,v->LocalImport.choose(this)),new LinearLayout.LayoutParams(0,-2,1));page.addView(sources);
        summary = Ui.text(this, "读取中…", 13, Ui.MUTED);
        page.addView(summary, Ui.margins(this, 4, 0, 4, 8));
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); Ui.smoothLayout(list);
        page.addView(list);
        LinearLayout help = Ui.section(this, page, "如何创建工程",
                "在浏览器翻译完一章后，点菜单「汉化与导出本章」；或在本地漫画文件夹页点「汉化与导出」。工程会保存每页的原文、译文和你的修改，可随时继续编辑和重新导出。", 16);
        help.setAlpha(.92f);
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); ExportJob.listen(exportChanged); reload(); }
    @Override protected void onPause() { ExportJob.unlisten(exportChanged); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if(LocalImport.result(this,request,result,data))return;
        ExportFlow.onActivityResult(this, request, result, data);
    }

    private boolean animatedOnce;
    private void reload() {
        io.execute(() -> {
            List<ComicProject> projects = ProjectStore.list(this);
            long[] sizes = new long[projects.size()];
            Bitmap[] covers = new Bitmap[projects.size()];
            for (int i = 0; i < projects.size(); i++) {
                sizes[i] = ProjectStore.size(projects.get(i));
                java.io.File cover = ProjectStore.cover(projects.get(i));
                if (cover.isFile()) covers[i] = BitmapFactory.decodeFile(cover.getPath());
            }
            runOnUiThread(() -> {
                if (destroyed) return;
                list.removeAllViews();
                long total = 0; for (long size : sizes) total += size;
                summary.setText(projects.isEmpty() ? "还没有汉化工程。" : projects.size() + " 个工程 · 共占用 " + Formatter.formatShortFileSize(this, total));
                for (int i = 0; i < projects.size(); i++) {
                    View row = row(projects.get(i), sizes[i], covers[i]);
                    list.addView(row, Ui.margins(this, 0, 0, 0, 10));
                    if (!animatedOnce && Ui.motion()) {
                        row.setAlpha(0f); row.setTranslationY(dp(12));
                        row.animate().alpha(1f).translationY(0).setStartDelay(50L * Math.min(i, 8)).setDuration(320).setInterpolator(Ui.EASE).start();
                    }
                }
                animatedOnce = true;
            });
        });
    }

    private View row(ComicProject project, long size, Bitmap cover) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(12), dp(10));
        row.setBackground(Ui.ripple(Ui.card(this, 18), Ui.round(this, 0xffFFFFFF, 18), 0x241A73E8));
        FrameLayout thumbBox = new FrameLayout(this); thumbBox.setBackground(Ui.round(this, 0xffE6EBF2, 12)); Ui.roundClip(thumbBox, 12);
        ImageView thumb = new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (cover != null) thumb.setImageBitmap(cover);
        thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        row.addView(thumbBox, new LinearLayout.LayoutParams(dp(64), dp(88)));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.heading(this, project.title, 15); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        int edited = 0; for (ComicProject.Page p : project.pages) edited += p.editedCount();
        String detail = project.pages.size() + " 页 · 已校对 " + project.reviewedCount() + (edited > 0 ? " · 修改 " + edited + " 段" : "")
                + "\n" + (project.updated > 0 ? DateUtils.getRelativeTimeSpanString(project.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + " 编辑 · " : "")
                + Formatter.formatShortFileSize(this, size);
        TextView info = Ui.text(this, detail, 12, 0xff8790A0);
        words.addView(name); words.addView(info, Ui.margins(this, 0, 4, 0, 0));
        String export = ExportJob.status(project);
        if (!export.isEmpty()) { TextView status = Ui.text(this, export, 12, Ui.ACCENT_DEEP); status.setMaxLines(2); words.addView(status, Ui.margins(this, 0, 4, 0, 0)); }
        // Reviewed progress as a slim bar.
        View track = new View(this); track.setBackground(Ui.round(this, 0xffE8EEF8, 2));
        FrameLayout bar = new FrameLayout(this); bar.addView(track, new FrameLayout.LayoutParams(-1, dp(4)));
        View fill = new View(this); fill.setBackground(Ui.round(this, 0xff137333, 2));
        float ratio = project.pages.isEmpty() ? 0 : project.reviewedCount() / (float) project.pages.size();
        bar.addView(fill, new FrameLayout.LayoutParams(0, dp(4)));
        bar.post(() -> { fill.getLayoutParams().width = Math.round(bar.getWidth() * ratio); fill.requestLayout(); });
        words.addView(bar, Ui.margins(this, 0, 8, 0, 0));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1); wordsParams.leftMargin = dp(12);
        row.addView(words, wordsParams);
        row.addView(Ui.text(this, "›", 22, 0xffA8B2C2));
        Ui.pressable(row);
        row.setOnClickListener(v -> startActivity(ProjectReaderActivity.intent(this, project.id, 2)));
        row.setOnLongClickListener(v -> { manage(project); return true; });
        return row;
    }

    private void manage(ComicProject project) {
        if(TranslationTaskManager.running()&&TranslationTaskManager.owner.equals("project:"+project.id)){android.widget.Toast.makeText(this,"请先停止该工程的翻译",0).show();return;}
        new AlertDialog.Builder(this).setTitle(project.title).setItems(new String[]{"打开编辑", "导出…", "重命名", "删除工程"}, (d, which) -> {
            if (which == 0) startActivity(ProjectReaderActivity.intent(this, project.id, 2));
            else if (which == 1) ExportFlow.show(this, project, () -> {});
            else if (which == 2) {
                EditText name = new EditText(this); name.setSingleLine(true); name.setText(project.title); Ui.field(name);
                FrameLayout box = new FrameLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(name);
                new AlertDialog.Builder(this).setTitle("重命名工程").setView(box).setNegativeButton("取消", null)
                        .setPositiveButton("保存", (x, y) -> {
                            String value = name.getText().toString().trim(); if (value.isEmpty()) return;
                            project.title = value;
                            io.execute(() -> { try { project.save(); } catch (Exception error) { runOnUiThread(()->android.widget.Toast.makeText(this,"保存失败："+error.getMessage(),1).show()); } runOnUiThread(this::reload); });
                        }).show();
            } else new AlertDialog.Builder(this).setTitle("删除工程？").setMessage("删除「" + project.title + "」的所有修改和页面副本。已导出的文件不受影响。此操作不能撤销。")
                    .setNegativeButton("取消", null).setPositiveButton("删除", (x, y) -> {
                        if (ExportJob.running(project)||TranslationTaskManager.owner.equals("project:"+project.id)&&TranslationTaskManager.running()) { summary.setText("该工程正在导出，完成后再删除。"); return; }
                        io.execute(() -> { ProjectStore.delete(project); runOnUiThread(this::reload); });
                    }).show();
        }).show();
    }

    private int dp(float value) { return Ui.dp(this, value); }
}
