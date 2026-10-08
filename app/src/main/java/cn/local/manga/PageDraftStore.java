package cn.local.manga;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Editable page drafts, addressed by the same content key as RenderedPageCache (pixels + render
 * settings). Immutable files are committed through StoredDrafts; uncommitted renders wait in
 * .staging/. Quota and committed-view cleanup are owned by StorageQuota.
 */
final class PageDraftStore {
    private static final String ROOT = "page-drafts-v2",
            STAGING = ".staging",
            PREFS = "workbench",
            ENABLED = "drafts_enabled";
    private static final long STAGING_TTL = 6L * 60 * 60 * 1000;
    private static final ExecutorService MAINTENANCE =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "page-draft-maintenance");
                        thread.setPriority(Thread.MIN_PRIORITY);
                        thread.setDaemon(true);
                        return thread;
                    });
    private static volatile boolean swept;

    private PageDraftStore() {}

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(ENABLED, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File root(Context context) {
        return new File(context.getFilesDir(), ROOT);
    }

    static boolean validKey(String key) {
        return key != null && key.matches("[0-9a-f]{64}");
    }

    /**
     * Moves a just-rendered job's source PNG and masks into a staging folder. Called on the render
     * thread.
     */
    static File stage(
            Context context,
            TranslationEngine.PreparedText job,
            int width,
            int height,
            TranslationTranscript transcript)
            throws Exception {
        sweepOnce(context);
        File staging = new File(new File(root(context), STAGING), UUID.randomUUID().toString());
        if (!staging.mkdirs()) throw new java.io.IOException("draft staging unavailable");
        try {
            // Rename only: cacheDir and filesDir share the app's data partition.
            File source = new File(staging, PageDraft.SOURCE);
            if (!job.source.renameTo(source))
                java.nio.file.Files.copy(job.source.toPath(), source.toPath());
            for (int index = 0; index < job.regions.size(); index++) {
                File mask = new File(job.directory, CleanupPlan.fileName(index));
                if (mask.isFile()) mask.renameTo(new File(staging, CleanupPlan.fileName(index)));
            }
            byte[] json =
                    PageDraft.describe(
                                    width, height, job.regions, job.protectionRegions, transcript)
                            .put("inPlaceColor", job.settings.inPlaceColor)
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(new File(staging, PageDraft.JSON))) {
                out.write(json);
            }
            PerformanceDiagnostics.written("draft_metadata", json.length);
            return staging;
        } catch (Exception | Error failure) {
            deleteTree(staging);
            throw failure;
        }
    }

    /** Files a staged draft under its content key (replacing an older one). Never throws. */
    static boolean commit(Context context, File staging, String key) {
        if (staging == null || !staging.isDirectory() || !validKey(key)) {
            discard(staging);
            return false;
        }
        try {
            boolean committed = StoredDrafts.commit(context, staging, key);
            if (committed) discard(staging);
            return committed;
        } catch (Exception error) {
            StorageDatabase.problem = "草稿保存未完成：" + StorageDatabase.message(error);
            return false;
        }
    }

    static void discard(File staging) {
        if (staging != null) deleteTree(staging);
    }

    /** The committed draft folder for a key, or null. Marks it recently used. */
    static File find(Context context, String key) {
        if (!validKey(key)) return null;
        try {
            File stored = StoredDrafts.find(context, key);
            if (stored != null) return stored;
            boolean migrated =
                    StorageDatabase.call(
                            context,
                            s ->
                                    s.meta("draft_authority/" + key) != null
                                            || s.number(
                                                            "SELECT count(*) FROM legacy_verified"
                                                                    + " WHERE path=?",
                                                            "page-drafts/" + key)
                                                    > 0);
            if (migrated) return null;
            File legacy = new File(new File(context.getFilesDir(), "page-drafts"), key);
            return new File(legacy, PageDraft.JSON).isFile()
                            && new File(legacy, PageDraft.SOURCE).isFile()
                    ? legacy
                    : null;
        } catch (Exception error) {
            StorageDatabase.problem = "草稿读取未完成：" + StorageDatabase.message(error);
            return null;
        }
    }

    static long usage(Context context) {
        return size(root(context));
    }

    private static void sweepOnce(Context context) {
        if (swept) return;
        swept = true;
        MAINTENANCE.execute(
                () -> {
                    File[] stale = new File(root(context), STAGING).listFiles();
                    long now = System.currentTimeMillis();
                    if (stale != null)
                        for (File dir : stale)
                            if (now - dir.lastModified() > STAGING_TTL) deleteTree(dir);
                });
    }

    /** Used only for explicit migration verification and legacy usage reporting. */
    // Conservative quota: count free bytes, not space reclaimable by evicting other apps.
    @android.annotation.SuppressLint("UsableSpace")
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

    static void copyTree(File from, File to, java.util.function.BooleanSupplier cancelled)
            throws java.io.IOException {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new java.io.IOException("无法创建目录");
            File[] children = from.listFiles();
            if (children == null) throw new java.io.IOException("无法读取目录");
            for (File child : children) copyTree(child, new File(to, child.getName()), cancelled);
        } else
            try (java.io.InputStream in = new java.io.FileInputStream(from);
                    java.io.OutputStream out = new FileOutputStream(to)) {
                byte[] buffer = new byte[65536];
                int n;
                long diagnosticCopied = 0;
                while ((n = in.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean())
                        throw new java.util.concurrent.CancellationException();
                    out.write(buffer, 0, n);
                    if (PerformanceDiagnostics.enabled()) diagnosticCopied += n;
                }
                PerformanceDiagnostics.written("draft_copy", diagnosticCopied);
            }
    }

    /** Only for disposable import snapshots, never live browser/cache data. */
    static void takeSnapshot(File from, File to, java.util.function.BooleanSupplier cancelled)
            throws java.io.IOException {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        File parent = to.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("无法创建目录");
        if (!from.renameTo(to)) {
            copyTree(from, to, cancelled);
            deleteTree(from);
        }
    }
}
