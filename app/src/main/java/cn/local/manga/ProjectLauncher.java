package cn.local.manga;

import android.app.Activity;
import android.app.AlertDialog;
import android.provider.DocumentsContract;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** "汉化与导出": turns translated pages from any source into a project and opens the workbench. */
final class ProjectLauncher {
    private ProjectLauncher() {}

    /** Shared result for a gather step: pages plus a note about what could not be included. */
    static final class Gathered {
        final List<ProjectStore.PageSpec> pages; final String note; Runnable cleanup=()->{};
        Gathered(List<ProjectStore.PageSpec> pages, String note) { this.pages = pages; this.note = note; }
    }
    interface Gatherer { Gathered gather(ProjectStore.Progress progress, java.util.function.BooleanSupplier cancelled) throws Exception; }

    /**
     * Opens the existing project for {@code sourceKey} (asking whether to rebuild it), or gathers pages in the
     * background and creates a new one. {@code startLabel} jumps to that page when present.
     */
    static void launch(Activity activity, String title, String sourceKind, String sourceKey, Callable<Gathered> gather, String startLabel) {
        launch(activity,title,sourceKind,sourceKey,(progress,cancelled)->gather.call(),startLabel);
    }
    static void launch(Activity activity, String title, String sourceKind, String sourceKey, Gatherer gather, String startLabel) {
        new Thread(() -> {
            ComicProject existing = ProjectStore.findBySource(activity, sourceKey);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (existing == null) { create(activity, title, sourceKind, sourceKey, gather, startLabel, null); return; }
                if(ExportJob.running(existing)||TranslationTaskManager.running()&&TranslationTaskManager.owner.equals("project:"+existing.id)){Toast.makeText(activity,"已有工程正在处理，请等待完成后再导入",Toast.LENGTH_LONG).show();return;}
                new AlertDialog.Builder(activity).setTitle("已有汉化工程")
                        .setMessage("「" + existing.title + "」已包含 " + existing.pages.size() + " 页，已校对 " + existing.reviewedCount() + " 页。\n"
                                + "重新生成会按最新翻译重建工程，之前的修改将丢失。")
                        .setPositiveButton("打开已有工程", (d, w) -> activity.startActivity(ProjectReaderActivity.intent(activity, existing.id, 2)))
                        .setNeutralButton("重新导入（覆盖）", (d, w) -> new AlertDialog.Builder(activity).setMessage("覆盖已有工程及其修改？").setNegativeButton("取消",null).setPositiveButton("覆盖",(x,y)->create(activity,title,sourceKind,sourceKey,gather,startLabel,existing)).show())
                        .setNegativeButton("另存为新工程", (d,w)->create(activity,title,sourceKind,sourceKey,gather,startLabel,null)).show();
            });
        }, "project-lookup").start();
    }

    private static void create(Activity activity, String title, String sourceKind, String sourceKey, Gatherer gather, String startLabel, ComicProject replace) {
        if(!CacheStorage.beginUse()){Toast.makeText(activity,"缓存清理中，请稍后导入",Toast.LENGTH_SHORT).show();return;}
        AtomicBoolean cancelled = new AtomicBoolean();
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 24); box.setPadding(pad, Ui.dp(activity, 8), pad, 0);
        TextView message = Ui.text(activity, "正在收集已翻译的页面…", 14, Ui.INK);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true); bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        box.addView(message); box.addView(bar, Ui.margins(activity, 0, 12, 0, 4));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("生成汉化工程").setView(box).setCancelable(false)
                .setNegativeButton("取消", (d, w) -> cancelled.set(true)).show();
        new Thread(() -> {
            String error = null; ComicProject created = null; String note = "";
            Gathered gathered=null;
            try {
                gathered = gather.gather((done,total)->activity.runOnUiThread(()->{bar.setIndeterminate(false);bar.setMax(Math.max(1,total));bar.setProgress(done);message.setText("正在保存页面副本 " + done + " / " + total + "…");}),cancelled::get);final int totalPages=gathered.pages.size();
                note = gathered.note == null ? "" : gathered.note;
                if (cancelled.get()) return;
                activity.runOnUiThread(() -> { message.setText("正在复制页面与草稿…"); bar.setIndeterminate(false); bar.setMax(Math.max(1, totalPages)); });
                created = ProjectStore.create(activity, title, sourceKind, sourceKey, gathered.pages,
                        (done, total) -> activity.runOnUiThread(() -> { bar.setProgress(done, true); message.setText(done==total?"正在生成封面…":"正在整理页面 " + done + " / " + total + "…"); }),
                        cancelled::get);
                if (replace != null) ProjectStore.delete(replace);
            } catch (java.util.concurrent.CancellationException stopped) { activity.runOnUiThread(dialog::dismiss);return;
            } catch (Exception failure) { error = failure.getMessage() == null ? "生成失败" : failure.getMessage(); }
            finally{try{if(gathered!=null)gathered.cleanup.run();}finally{CacheStorage.endUse();}if(cancelled.get())activity.runOnUiThread(dialog::dismiss);}
            String failure = error, extra = note; ComicProject project = created;
            activity.runOnUiThread(() -> {
                try { dialog.dismiss(); } catch (Exception ignored) {}
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failure != null) { new AlertDialog.Builder(activity).setTitle("无法生成工程").setMessage(failure).setPositiveButton("知道了", null).show(); return; }
                int editable = project.editableCount();
                String summary = "工程已生成：" + project.pages.size() + " 页，其中可编辑 " + editable + " 页" + (extra.isEmpty() ? "" : "。" + extra);
                Toast.makeText(activity, summary, Toast.LENGTH_LONG).show();
                activity.startActivity(ProjectReaderActivity.intent(activity, project.id, 2));
            });
        }, "project-create").start();
    }

    private static int indexOf(ComicProject project, String label) {
        if (label != null) for (int i = 0; i < project.pages.size(); i++) if (label.equals(project.pages.get(i).label)) return i;
        return 0;
    }

    /** Local folder pages in reading order: draft when available, else the translated image, else the original. */
    static Gathered localFolder(Activity activity, LocalComics.Folder folder) throws Exception {
        LocalComics.Listing listing = LocalComics.list(activity, folder);
        List<ProjectStore.PageSpec> specs = new ArrayList<>();
        int untranslated = 0;
        for (LocalComics.Page page : listing.pages) {
            String key = LocalComics.readDraftKey(activity, folder, page);
            LocalComics.Entry image = page.output != null ? page.output : page.source;
            if (page.output == null) untranslated++;
            String extension = LocalComics.extensionOf(image.name);
            specs.add(new ProjectStore.PageSpec(page.source.name, key,
                    () -> activity.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(folder.tree, image.documentId)),
                    extension, page.output == null));
        }
        if (listing.pages.isEmpty()) throw new Exception("这个文件夹里没有漫画图片");
        return new Gathered(specs, untranslated > 0 ? untranslated + " 页尚未翻译，以原图加入" : "");
    }

    static String dated(String prefix) { return prefix + " " + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new java.util.Date()); }
}
