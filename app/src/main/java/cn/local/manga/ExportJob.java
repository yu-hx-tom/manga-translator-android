package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One process-wide export at a time (same pattern as LocalBatch), so it keeps running while the user moves
 * between the workbench and the project list. Renders one page at a time to bound memory.
 */
final class ExportJob {
    interface Listener { void changed(); }

    /** What and how to export; persisted in the project for next time. */
    static final class Options {
        boolean jpeg, keepNames, reviewedOnly, includeUntranslated = true, script = true, comicInfo = true;
        boolean webp;int quality=92;java.util.Set<String> selected=new java.util.LinkedHashSet<>();
        org.json.JSONObject json() throws Exception {
            return new org.json.JSONObject().put("jpeg", jpeg).put("keepNames", keepNames).put("reviewedOnly", reviewedOnly)
                    .put("includeUntranslated", includeUntranslated).put("script", script).put("comicInfo", comicInfo).put("webp",webp).put("quality",quality).put("selected",new org.json.JSONArray(selected));
        }
        static Options from(org.json.JSONObject json) {
            Options o = new Options();
            if (json == null) return o;
            o.jpeg = json.optBoolean("jpeg"); o.keepNames = json.optBoolean("keepNames"); o.reviewedOnly = json.optBoolean("reviewedOnly");
            o.includeUntranslated = json.optBoolean("includeUntranslated", true); o.script = json.optBoolean("script", true); o.comicInfo = json.optBoolean("comicInfo", true);
            o.webp=json.optBoolean("webp");o.quality=Math.max(1,Math.min(100,json.optInt("quality",92)));org.json.JSONArray pages=json.optJSONArray("selected");if(pages!=null)for(int i=0;i<pages.length();i++)o.selected.add(pages.optString(i));
            return o;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static ExportJob active;
    private static String lastResult = "", lastProjectId = "";
    private static boolean notifyPending;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ComicProject project;
    private int done, total;
    private String stage = "准备中…";

    private ExportJob(ComicProject project) { this.project = project; }

    static synchronized boolean running() { return active != null; }
    static synchronized String globalStatus(){return active==null?lastResult:(active.cancelled.get()?"正在取消导出…":"导出中 "+active.done+" / "+active.total+" · "+active.stage);}
    static synchronized boolean running(ComicProject project) { return active != null && active.project.id.equals(project.id); }
    /** "导出中 3/20 …", or the last finished/failed message for this project, or "". */
    static synchronized String status(ComicProject project) {
        if (active != null && active.project.id.equals(project.id))
            return (active.cancelled.get() ? "正在取消…" : "导出中 " + active.done + " / " + active.total + " · " + active.stage);
        return project.id.equals(lastProjectId) ? lastResult : "";
    }
    static synchronized int[] progress(ComicProject project) {
        return active != null && active.project.id.equals(project.id) ? new int[]{active.done, Math.max(1, active.total)} : null;
    }
    static synchronized void cancel() { if (active != null) active.cancelled.set(true); changed(); }

    /** Starts exporting; returns an error message instead when another export is still running. */
    static synchronized String start(Context context, ComicProject project, Options options, ExportWriters.Writer writer) {
        if (active != null) { try { writer.abort(); } catch (Exception ignored) {} return "已有导出任务在进行，请等它完成。"; }
        if(!CacheStorage.beginUse()){try{writer.abort();}catch(Exception ignored){}return "缓存清理中，请稍后导出。";}
        ExportJob job = new ExportJob(project);
        active = job;
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {try{job.run(app, options, writer);}finally{CacheStorage.endUse();}}, "comic-export");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
        changed();
        return null;
    }

    private void run(Context context, Options options, ExportWriters.Writer writer) {
        String result;
        int written = 0;
        try {
            List<ComicProject.Page> pages = new ArrayList<>();
            for (ComicProject.Page page : project.pages) {
                if(!options.selected.isEmpty()&&!options.selected.contains(page.id))continue;
                if (options.reviewedOnly && !page.reviewed) continue;
                if (!options.includeUntranslated && ComicProject.KIND_ORIGINAL.equals(page.kind)) continue;
                pages.add(page);
            }
            if (pages.isEmpty()) throw new Exception("没有符合条件的页面（检查“只导出已校对的页”等选项）");
            synchronized (ExportJob.class) { total = pages.size(); }
            int digits = Math.max(3, String.valueOf(pages.size()).length());
            List<String> usedNames = new ArrayList<>();
            StringBuilder script = new StringBuilder();
            script.append(project.title).append(" · 共 ").append(pages.size()).append(" 页 · 导出于 ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).append("\n");
            for (int i = 0; i < pages.size(); i++) {
                if (cancelled.get()) throw new CancellationException();
                ComicProject.Page page = pages.get(i);
                setStage("第 " + (i + 1) + " 页：合成…");
                byte[] data; String extension;
                if (page.editable()) {
                    Bitmap image;
                    try (PageComposer composer = new PageComposer(PageDraft.read(project.draftDir(page)).withEdits(page.edits))) {
                        composer.layoutAll(project.effectiveEdits(page,composer.draft), cancelled::get);
                        image = composer.compose(null);
                        if (options.script) appendScript(script, i + 1, page, composer.draft);
                    }
                    try { setStage("第 " + (i + 1) + " 页：编码…"); data = encode(image, options); }
                    finally { image.recycle(); }
                    extension = options.webp?"webp":options.jpeg ? "jpg" : "png";
                } else {
                    File file = project.imageFile(page);
                    String own = file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    boolean sameFormat = !options.jpeg&&!options.webp&&own.equals("png");
                    if (sameFormat) { data = Files.readAllBytes(file.toPath()); extension = "png"; }
                    else {
                        Bitmap image;
                        try {
                            image = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file),
                                    (decoder, info, src) -> decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE));
                        } catch (Exception unreadable) { throw new Exception("第 " + (i + 1) + " 页图片无法读取"); }
                        try { data = encode(image, options); } finally { image.recycle(); }
                        extension = options.webp?"webp":options.jpeg ? "jpg" : "png";
                    }
                    if (options.script) script.append("\n==== 第 ").append(i + 1).append(" 页（").append(page.label).append("）====\n")
                            .append(ComicProject.KIND_ORIGINAL.equals(page.kind) ? "（未翻译的原图）\n" : "（成品图，没有可编辑的文字记录）\n");
                }
                String name = name(page, i + 1, digits, extension, options.keepNames, usedNames);
                setStage("第 " + (i + 1) + " 页：写入…");
                writer.write(name, options.webp?"image/webp":options.jpeg ? "image/jpeg" : "image/png", data);
                written++;
                synchronized (ExportJob.class) { done = written; }
                changed();
            }
            if (options.script) writer.write("翻译稿.txt", "text/plain", script.toString().getBytes(StandardCharsets.UTF_8));
            if (options.comicInfo && writer instanceof ExportWriters.ZipWriter && ((ExportWriters.ZipWriter) writer).comicBook)
                writer.write("ComicInfo.xml", "application/xml", comicInfo(project.title, pages.size()).getBytes(StandardCharsets.UTF_8));
            writer.close();
            result = "已导出 " + written + " 页到 " + writer.describe();
        } catch (CancellationException stopped) {
            writer.abort();
            result = "已取消导出" + (written > 0 ? "（已写入 " + written + " 页）" : "");
        } catch (Exception | OutOfMemoryError failure) {
            writer.abort();
            String message = failure instanceof OutOfMemoryError ? "手机内存不足" : failure.getMessage() == null ? "导出未完成" : failure.getMessage();
            result = "导出失败：" + message + (written > 0 ? "（已写入 " + written + " 页）" : "");
        }
        synchronized (ExportJob.class) { lastResult = result; lastProjectId = project.id; active = null; }
        changed();
    }

    private void setStage(String value) { synchronized (ExportJob.class) { stage = value; } changed(); }

    private static byte[] encode(Bitmap image, Options options) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1 << 16, image.getWidth() * image.getHeight()));
        if (!image.compress(options.webp?Bitmap.CompressFormat.WEBP:options.jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, options.jpeg||options.webp?options.quality:100, out)) throw new Exception("图片编码失败");
        return out.toByteArray();
    }

    static String name(ComicProject.Page page, int number, int digits, String extension, boolean keepNames, List<String> used) {
        String base = keepNames && page.label != null && !page.label.isEmpty() ? LocalComics.base(ExportWriters.safe(page.label)) : String.format(Locale.ROOT, "%0" + digits + "d", number);
        String name = base + "." + extension;
        for (int i = 2; used.contains(name.toLowerCase(Locale.ROOT)); i++) name = base + "_" + i + "." + extension;
        used.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    private static void appendScript(StringBuilder out, int number, ComicProject.Page page, PageDraft draft) {
        out.append("\n==== 第 ").append(number).append(" 页（").append(page.label).append("）====\n");
        int n = 0;
        for (PageDraft.Item item : draft.items) {
            PageComposer.Edit edit = page.edits.get(item.region.id);
            String text = edit != null && edit.text != null ? edit.text : item.machine;
            boolean hidden = edit != null && edit.hidden;
            if (item.original.isEmpty() && (text == null || text.isEmpty())) continue;
            out.append("[").append(++n).append("] 原文：").append(item.original.isEmpty() ? "（未识读）" : item.original.replace('\n', ' ')).append("\n")
               .append("    译文：").append(hidden ? "（已隐藏）" : text == null || text.isEmpty() ? "（无）" : text.replace('\n', ' ')).append("\n");
        }
        if (n == 0) out.append("（本页没有文字）\n");
    }

    private static String comicInfo(String title, int pages) {
        String t = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
                + "  <Title>" + t + "</Title>\n  <PageCount>" + pages + "</PageCount>\n  <LanguageISO>zh</LanguageISO>\n"
                + "  <Notes>由 漫游浏览器 汉化导出</Notes>\n</ComicInfo>\n";
    }

    static void listen(Listener listener) { synchronized (LISTENERS) { LISTENERS.add(listener); } }
    static void unlisten(Listener listener) { synchronized (LISTENERS) { LISTENERS.remove(listener); } }
    private static void changed() {
        synchronized (LISTENERS) { if (notifyPending) return; notifyPending = true; }
        MAIN.postDelayed(() -> {
            List<Listener> copy;
            synchronized (LISTENERS) { notifyPending = false; copy = new ArrayList<>(LISTENERS); }
            for (Listener listener : copy) listener.changed();
        }, 120);
    }
}
