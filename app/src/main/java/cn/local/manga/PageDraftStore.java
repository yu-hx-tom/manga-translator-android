package cn.local.manga;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Editable page drafts, addressed by the same content key as RenderedPageCache (pixels + render settings).
 * filesDir/page-drafts/&lt;key&gt;/ ; uncommitted renders wait in .staging/ and are swept after a while.
 *
 * Cost on the translation path is two file renames plus a small JSON write (no image encoding), and the
 * LRU trim runs on its own low-priority thread, so web translation speed is unaffected.
 */
final class PageDraftStore {
    private static final String ROOT = "page-drafts", STAGING = ".staging", PREFS = "workbench", ENABLED = "drafts_enabled";
    private static final long STAGING_TTL = 6L * 60 * 60 * 1000, MAX_BUDGET = 600L * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final ExecutorService MAINTENANCE = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "page-draft-maintenance"); thread.setPriority(Thread.MIN_PRIORITY); thread.setDaemon(true); return thread;
    });
    private static volatile boolean swept;

    private PageDraftStore() {}

    static boolean enabled(Context context) { return prefs(context).getBoolean(ENABLED, true); }
    static void setEnabled(Context context, boolean value) { prefs(context).edit().putBoolean(ENABLED, value).apply(); }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static File root(Context context) { return new File(context.getFilesDir(), ROOT); }
    static boolean validKey(String key) { return key != null && key.matches("[0-9a-f]{64}"); }

    /** Moves a just-rendered job's source PNG and masks into a staging folder. Called on the render thread. */
    static File stage(Context context, TranslationEngine.PreparedText job, int width, int height, TranslationTranscript transcript) throws Exception {
        sweepOnce(context);
        File staging = new File(new File(root(context), STAGING), UUID.randomUUID().toString());
        if (!staging.mkdirs()) throw new java.io.IOException("draft staging unavailable");
        try {
            // Rename only: cacheDir and filesDir share the app's data partition.
            File source = new File(staging, PageDraft.SOURCE);
            if (!job.source.renameTo(source)) java.nio.file.Files.copy(job.source.toPath(), source.toPath());
            for (int index = 0; index < job.regions.size(); index++) {
                File mask = new File(job.directory, CleanupPlan.fileName(index));
                if (mask.isFile()) mask.renameTo(new File(staging, CleanupPlan.fileName(index)));
            }
            byte[] json = PageDraft.describe(width, height, job.regions, job.protectionRegions, transcript).toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(new File(staging, PageDraft.JSON))) { out.write(json); }
            return staging;
        } catch (Exception | Error failure) { deleteTree(staging); throw failure; }
    }

    /** Files a staged draft under its content key (replacing an older one). Never throws. */
    static boolean commit(Context context, File staging, String key) {
        if (staging == null || !staging.isDirectory() || !validKey(key)) { discard(staging); return false; }
        synchronized (LOCK) {
            File target = new File(root(context), key);
            deleteTree(target);
            if (!staging.renameTo(target)) { discard(staging); return false; }
            target.setLastModified(System.currentTimeMillis());
        }
        MAINTENANCE.execute(() -> trim(context));
        return true;
    }
    static void discard(File staging) { if (staging != null) deleteTree(staging); }

    /** The committed draft folder for a key, or null. Marks it recently used. */
    static File find(Context context, String key) {
        if (!validKey(key)) return null;
        synchronized (LOCK) {
            File dir = new File(root(context), key);
            if (!new File(dir, PageDraft.JSON).isFile() || !new File(dir, PageDraft.SOURCE).isFile()) return null;
            dir.setLastModified(System.currentTimeMillis());
            return dir;
        }
    }

    static long usage(Context context) { return size(root(context)); }
    static void clear(Context context) { synchronized (LOCK) { deleteTree(root(context)); } }

    private static void sweepOnce(Context context) {
        if (swept) return;
        swept = true;
        MAINTENANCE.execute(() -> {
            File[] stale = new File(root(context), STAGING).listFiles();
            long now = System.currentTimeMillis();
            if (stale != null) for (File dir : stale) if (now - dir.lastModified() > STAGING_TTL) deleteTree(dir);
        });
    }

    /** Least-recently-used drafts go first once over min(600 MB, 15% of free space). Projects hold their own copies. */
    private static void trim(Context context) {
        synchronized (LOCK) {
            File root = root(context);
            File[] dirs = root.listFiles(file -> file.isDirectory() && !STAGING.equals(file.getName()));
            if (dirs == null) return;
            List<File> entries = new ArrayList<>();
            long total = 0;
            for (File dir : dirs) { entries.add(dir); total += size(dir); }
            long budget = Math.min(MAX_BUDGET, (long) (root.getUsableSpace() * .15));
            entries.sort(Comparator.comparingLong(File::lastModified));
            for (File dir : entries) {
                if (total <= budget) break;
                long bytes = size(dir);
                deleteTree(dir);
                total -= bytes;
            }
        }
    }

    static long size(File file) {
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += size(child);
        return total;
    }
    static void deleteTree(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    /** Recursive copy used when a project takes its own durable copy of a draft. */
    static void copyTree(File from, File to) throws java.io.IOException {
        copyTree(from, to, () -> false);
    }
    static void copyTree(File from, File to, java.util.function.BooleanSupplier cancelled) throws java.io.IOException {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new java.io.IOException("无法创建目录");
            File[] children = from.listFiles();
            if (children == null) throw new java.io.IOException("无法读取目录");
            for (File child : children) copyTree(child, new File(to, child.getName()), cancelled);
        } else try (java.io.InputStream in = new java.io.FileInputStream(from); java.io.OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                out.write(buffer, 0, n);
            }
        }
    }
    /** Only for disposable import snapshots, never live browser/cache data. */
    static void takeSnapshot(File from, File to, java.util.function.BooleanSupplier cancelled) throws java.io.IOException {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        File parent = to.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("无法创建目录");
        if (!from.renameTo(to)) { copyTree(from, to, cancelled); deleteTree(from); }
    }
}
