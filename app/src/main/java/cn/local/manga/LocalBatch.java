package cn.local.manga;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One process-wide queue for local folder translation, so leaving the folder screen (to read, or to
 * check settings) does not stop the work. Only one folder runs at a time; the reader's "translate
 * this page" jumps to the front of the same queue, which keeps two writers off the same output
 * file.
 */
final class LocalBatch {
    interface Listener {
        void changed();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Map<String, String> ERRORS = new HashMap<>();
    private static final Map<String, String> LAST_SUMMARY = new HashMap<>();
    private static LocalBatch active;
    private static boolean notifyPending;

    private static final class Job {
        final LocalComics.Page page;
        final boolean force;

        Job(LocalComics.Page p, boolean f) {
            page = p;
            force = f;
        }
    }

    private final Context app;
    private final LocalComics.Folder folder;
    private final AppSettings settings;
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final LinkedHashMap<String, String> running = new LinkedHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ExecutorService pool;
    private final TranslationEngine engine;
    private int workers, total, done, failed, partial;
    private String halt = "";

    private LocalBatch(Context context, LocalComics.Folder folder, AppSettings settings) {
        app = context.getApplicationContext();
        this.folder = folder;
        this.settings = settings;
        engine = new TranslationEngine(app);
        // Text mode overlaps a second page's detection with the first page's requests; image mode
        // is per-box already.
        workers = "image".equals(settings.mode) ? 1 : 2;
        pool = Executors.newFixedThreadPool(workers);
    }

    /**
     * Queues pages of {@code folder}. Returns false when another folder is still running. {@code
     * front} puts the pages ahead of the waiting ones (used by the reader for the current page).
     */
    static synchronized String start(
            Context context,
            LocalComics.Folder folder,
            List<LocalComics.Page> pages,
            boolean force,
            boolean front,
            AppSettings settings) {
        if (active != null && !active.folder.key().equals(folder.key()))
            return "「" + active.folder.name + "」正在翻译，请等它完成或先停止。";
        if (active != null && (active.cancelled.get() || !active.halt.isEmpty()))
            return "上一轮正在收尾，请稍候再试。";
        boolean fresh = active == null;
        if (fresh) {
            if (!TranslationTaskManager.begin(context, "local", LocalBatch::stop))
                return StorageQuota.admissionProblem();
            active = new LocalBatch(context, folder, settings);
        }
        LocalBatch batch = active;
        List<Job> added = new ArrayList<>();
        for (LocalComics.Page page : pages) {
            String name = page.source.name;
            if (batch.running.containsKey(name)) continue;
            batch.queue.removeIf(job -> job.page.source.name.equals(name));
            added.add(new Job(page, force));
            ERRORS.remove(errorKey(folder, name));
        }
        if (front) for (int i = added.size() - 1; i >= 0; i--) batch.queue.addFirst(added.get(i));
        else batch.queue.addAll(added);
        batch.total = batch.done + batch.failed + batch.running.size() + batch.queue.size();
        if (fresh) for (int i = 0; i < batch.workers; i++) batch.pool.submit(batch::work);
        changed();
        return null;
    }

    static synchronized void stop() {
        if (active == null) return;
        LocalBatch batch = active;
        batch.cancelled.set(true);
        batch.queue.clear();
        batch.halt = "已停止。正在处理的页面已放弃；已发出的请求可能仍计费。";
        // Workers that never got a thread still have to be counted out, or the batch would never
        // finish.
        int neverStarted = batch.pool.shutdownNow().size();
        for (int i = 0; i < neverStarted; i++) batch.workerExit();
        changed();
    }

    private void work() {
        while (true) {
            Job job;
            synchronized (LocalBatch.class) {
                if (cancelled.get() || !halt.isEmpty() || queue.isEmpty()) {
                    workerExit();
                    return;
                }
                job = queue.pollFirst();
                running.put(job.page.source.name, "准备中…");
            }
            changed();
            String name = job.page.source.name;
            Exception[] throttle = new Exception[1];
            try {
                PageOutcome outcome =
                        LocalComics.translate(
                                app,
                                engine,
                                folder,
                                job.page,
                                settings,
                                job.force,
                                cancelled::get,
                                (message, n, t) -> {
                                    synchronized (LocalBatch.class) {
                                        if (running.containsKey(name)) running.put(name, message);
                                    }
                                    changed();
                                },
                                throttle);
                synchronized (LocalBatch.class) {
                    done++;
                    if (outcome.incomplete() && outcome.detected > 0) partial++;
                }
            } catch (CancellationException | InterruptedException stopped) {
                synchronized (LocalBatch.class) {
                    running.remove(name);
                    workerExit();
                }
                changed();
                return;
            } catch (Exception | OutOfMemoryError error) {
                if (cancelled.get()) {
                    synchronized (LocalBatch.class) {
                        running.remove(name);
                        workerExit();
                    }
                    changed();
                    return;
                }
                String message =
                        error instanceof OutOfMemoryError
                                ? "图片过大，手机内存不足"
                                : error.getMessage() == null ? "处理未完成" : error.getMessage();
                if (!settings.apiKey.isEmpty())
                    message = message.replace(settings.apiKey, "[密钥已隐藏]");
                synchronized (LocalBatch.class) {
                    failed++;
                    ERRORS.put(errorKey(folder, name), message);
                }
                if (error instanceof Exception && ApiClient.isThrottle((Exception) error))
                    throttle[0] = (Exception) error;
            }
            synchronized (LocalBatch.class) {
                running.remove(name);
                if (throttle[0] != null && halt.isEmpty()) {
                    halt = "接口限流或暂不可用（429/503），已暂停剩余页面。稍后再点「继续翻译」即可接着处理。";
                    queue.clear();
                }
            }
            changed();
        }
    }

    /**
     * Called with the class lock held. The last worker out closes the engine and publishes a
     * summary.
     */
    private void workerExit() {
        if (--workers > 0) return;
        String summary =
                "完成 "
                        + done
                        + " 页"
                        + (partial > 0 ? "（其中 " + partial + " 页部分段落未完成）" : "")
                        + (failed > 0 ? "，失败 " + failed + " 页" : "")
                        + (halt.isEmpty() ? "。" : "。" + halt);
        LAST_SUMMARY.put(folder.key(), summary);
        TranslationTaskManager.done("local", summary);
        if (active == this) active = null;
        pool.shutdown();
        new Thread(
                        () -> {
                            try {
                                engine.close();
                            } catch (Exception ignored) {
                            }
                        },
                        "local-batch-close")
                .start();
    }

    // ---------- Read-only views for the UI (main thread) ----------

    static synchronized boolean isActive() {
        return active != null;
    }

    static synchronized boolean isActive(LocalComics.Folder folder) {
        return active != null && active.folder.key().equals(folder.key());
    }

    static synchronized String activeFolderName() {
        return active == null ? "" : active.folder.name;
    }

    static synchronized LocalComics.Folder activeFolder() {
        return active == null ? null : active.folder;
    }

    /** "正在翻译 3/20…" for this folder, the last summary when finished, or "" when nothing to say. */
    static synchronized String progressLine(LocalComics.Folder folder) {
        if (active != null && active.folder.key().equals(folder.key())) {
            LocalBatch b = active;
            StringBuilder line = new StringBuilder();
            if (b.cancelled.get()) line.append("正在停止…");
            else
                line.append("正在翻译 ")
                        .append(Math.min(b.total, b.done + b.failed + b.running.size()))
                        .append(" / ")
                        .append(b.total)
                        .append(" 页 · 完成 ")
                        .append(b.done)
                        .append(b.failed > 0 ? " · 失败 " + b.failed : "");
            for (Map.Entry<String, String> page : b.running.entrySet())
                line.append("\n").append(page.getKey()).append("：").append(page.getValue());
            if (!b.halt.isEmpty()) line.append("\n").append(b.halt);
            return line.toString();
        }
        String last = LAST_SUMMARY.get(folder.key());
        return last == null ? "" : last;
    }

    static synchronized int[] counts(LocalComics.Folder folder) {
        if (active == null || !active.folder.key().equals(folder.key())) return null;
        return new int[] {active.done + active.failed, active.total};
    }

    /** RUNNING message, "排队中", or null for a page that is not in the queue. */
    static synchronized String stage(LocalComics.Folder folder, String pageName) {
        if (active == null || !active.folder.key().equals(folder.key())) return null;
        String message = active.running.get(pageName);
        if (message != null) return message;
        for (Job job : active.queue) if (job.page.source.name.equals(pageName)) return "排队中";
        return null;
    }

    static synchronized String error(LocalComics.Folder folder, String pageName) {
        return ERRORS.get(errorKey(folder, pageName));
    }

    private static String errorKey(LocalComics.Folder folder, String name) {
        return folder.key() + "\n" + name;
    }

    static void listen(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.add(listener);
        }
    }

    static void unlisten(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.remove(listener);
        }
    }

    /** Coalesces bursts of progress callbacks into at most one UI refresh every ~150 ms. */
    private static void changed() {
        if (active != null) {
            TranslationTaskManager.progress("local", progressLine(active.folder));
            TranslationTaskManager.counts(
                    "local", active.done, active.failed, active.running.size(), active.total);
        }
        synchronized (LISTENERS) {
            if (notifyPending) return;
            notifyPending = true;
        }
        MAIN.postDelayed(
                () -> {
                    List<Listener> copy;
                    synchronized (LISTENERS) {
                        notifyPending = false;
                        copy = new ArrayList<>(LISTENERS);
                    }
                    for (Listener listener : copy) listener.changed();
                },
                150);
    }
}
