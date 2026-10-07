package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * The one definition of "translate a whole page": content hash → rendered-page cache → local detection →
 * translation → page statistics → cache write-back. Web images (manual selection), local folders and any
 * future source share it; they differ only in where the bitmap comes from and where the result goes.
 *
 * The browser's automatic reading queue keeps its own staged variant (memory leases, background mask
 * precompute, per-phase timings), but builds keys, statistics and throttle decisions from the helpers here,
 * so the cache formats cannot drift apart.
 */
final class PagePipeline {
    private PagePipeline() {}

    /** Suffix of the rendered-page key. Bump it (new key space) whenever rendering output changes. */
    private static final String RENDER_VERSION = "\npage-v0.9.4-dedup";

    /** Key into {@link RenderedPageCache}: verified source pixels + everything that changes the output. */
    static String contentKey(String sourceHash, AppSettings settings) throws Exception {
        String configuration = settings.renderFields().put(DetectorModels.get(settings.detectorModel).sha256).toString();
        // Only rendered text pages change; keep image-mode pages and paid crop replies reusable.
        return RenderedPageCache.key(sourceHash, configuration + RENDER_VERSION);
    }

    static PageOutcome outcomeOf(TranslationEngine.Result result) {
        return new PageOutcome(result.regions.size(), result.succeeded, result.failed, result.skipped, result.preservedOriginal,
                result.summary + (result.traceId.isEmpty() ? "" : "\n日志任务：" + result.traceId), result.transcript, result.needsCleanupRetry);
    }

    static PageOutcome noText(AppSettings settings, String detail) {
        return new PageOutcome(0, 0, 0, 0, 0, detail, new TranslationTranscript(settings.mode, Collections.emptyList()));
    }

    /** 429 / 503 from the model API or the image host, else 0. Only these slow the queue down. */
    static int throttleStatus(Throwable failure) {
        int status = failure instanceof ApiClient.RequestFailure ? ((ApiClient.RequestFailure) failure).status
                : failure instanceof BrowserImageLoader.HttpFailure ? ((BrowserImageLoader.HttpFailure) failure).status : 0;
        return status == 429 || status == 503 ? status : 0;
    }

    /** Outcome of {@link #run}. Close it to free the rendered bitmap. */
    static final class Page implements AutoCloseable {
        /** Page statistics and transcript; never null. */
        PageOutcome outcome;
        /** Freshly rendered page (only when {@link #rendered()}); owned by this object. */
        Bitmap image;
        /** Earlier rendered page restored from cache instead of {@link #image}. */
        byte[] cachedPng;
        /** Content key for writing the finished page back with {@link #remember}. */
        String key;
        /** Partial result after the API rate-limited this page. */
        Exception throttle;
        boolean noText;
        int succeeded;
        /** Engine summary for this run (used as the error text when nothing could be filled in). */
        String summary = "";

        /** Staged workbench draft for this render; filed by {@link #keepDraft} or discarded by close(). */
        java.io.File draft;

        boolean fromCache() { return cachedPng != null; }
        boolean rendered() { return image != null && succeeded > 0; }
        /** Files the staged draft under this page's content key; true when an editable draft now exists. */
        boolean keepDraft(Context context) {
            if (draft != null) { boolean kept = PageDraftStore.commit(context, draft, key); draft = null; return kept; }
            return PageDraftStore.find(context, key) != null; // e.g. restored from cache after an earlier edit-capable render
        }
        @Override public void close() {
            if (image != null && !image.isRecycled()) image.recycle(); image = null;
            PageDraftStore.discard(draft); draft = null;
        }
    }

    /**
     * Runs the full page flow on {@code source} (which stays owned by the caller).
     *
     * @param reuseRendered look up a finished page by content first; false preserves flows that must
     *                      always re-run detection (the browser's manual "retry" paths).
     * @param forceFresh    bypass detection and per-crop reply caches as well.
     */
    static Page run(Context context, TranslationEngine engine, Bitmap source, AppSettings settings,
                    boolean reuseRendered, boolean forceFresh, BooleanSupplier cancelled,
                    TranslationEngine.Progress progress, String noTextDetail) throws Exception {
        Page page = new Page();
        try {
            String hash = DetectionCache.contentHash(source, cancelled);
            page.key = contentKey(hash, settings);
            if (reuseRendered && !forceFresh) {
                RenderedPageCache.Entry previous = new RenderedPageCache(context.getCacheDir()).read(page.key);
                if (previous != null) {
                    page.cachedPng = previous.png; page.outcome = previous.outcome; page.succeeded = previous.outcome.succeeded;
                    return page;
                }
            }
            progress.update("本地检测文字…", 0, 0);
            List<Region> regions = engine.detect(source, settings, hash, forceFresh, cancelled);
            if (cancelled.getAsBoolean()) throw new CancellationException();
            if (regions.isEmpty()) { page.noText = true; page.outcome = noText(settings, noTextDetail); return page; }
            TranslationEngine.Result output = engine.translate(source, regions, regions, settings, progress, cancelled, forceFresh);
            page.image = output.image == source ? null : output.image;
            page.succeeded = output.succeeded;
            page.summary = output.summary;
            page.outcome = outcomeOf(output);
            page.throttle = output.throttleFailure;
            page.draft = output.draft;
            return page;
        } catch (Exception | Error failure) {
            page.close();
            throw failure;
        }
    }

    /** Stores a finished page for any later reader of the same pixels; incomplete pages are ignored by the cache. */
    static void remember(Context context, Page page, byte[] png) {
        if (png != null && page.key != null) new RenderedPageCache(context.getCacheDir()).write(page.key, png, page.outcome);
    }
}
