package cn.local.manga;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Local comic folders opened through the system folder picker (Storage Access Framework). Originals
 * are only read; translated pages are written to a "译图" child folder next to them.
 */
final class LocalComics {
    static final String OUTPUT_DIR = "译图";
    private static final String PREFS = "local_comics", RECENT = "recent";

    private LocalComics() {}

    /**
     * A folder inside a granted tree. The key identifies it across screens and in local records.
     */
    static final class Folder {
        final Uri tree;
        final String documentId, name;

        Folder(Uri tree, String documentId, String name) {
            this.tree = tree;
            this.documentId = documentId;
            this.name = name;
        }

        Uri uri() {
            return DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        }

        String key() {
            return tree + "\n" + documentId;
        }
    }

    static final class Entry {
        final String documentId, name, mime;
        final long size, modified;

        Entry(String documentId, String name, String mime, long size, long modified) {
            this.documentId = documentId;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.modified = modified;
        }

        boolean directory() {
            return Document.MIME_TYPE_DIR.equals(mime);
        }
    }

    /** One comic page and, when present, its translated output. */
    static final class Page {
        final Entry source;
        final String outputBase;
        Entry output;

        Page(Entry source, String outputBase) {
            this.source = source;
            this.outputBase = outputBase;
        }
    }

    /** Folder contents: chapter subfolders first, then pages in natural (human) order. */
    static final class Listing {
        final List<Entry> folders = new ArrayList<>();
        final List<Page> pages = new ArrayList<>();
        String outputId;
    }

    // ---------- Listing ----------

    static Listing list(Context context, Folder folder) throws IOException {
        Listing listing = new Listing();
        List<Entry> children =
                children(context.getContentResolver(), folder.tree, folder.documentId);
        Map<String, Integer> baseCounts = new HashMap<>();
        List<Entry> images = new ArrayList<>();
        for (Entry child : children) {
            if (child.directory()) {
                if (OUTPUT_DIR.equals(child.name)) listing.outputId = child.documentId;
                else if (!child.name.startsWith(".")) listing.folders.add(child);
            } else if (isImage(child)) {
                images.add(child);
                baseCounts.merge(base(child.name).toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        Collections.sort(listing.folders, (a, b) -> natural(a.name, b.name));
        Collections.sort(images, (a, b) -> natural(a.name, b.name));
        for (Entry image : images) {
            String stem = base(image.name);
            // Two sources with the same stem (001.jpg + 001.png) keep their extension in the output
            // name.
            String output =
                    baseCounts.get(stem.toLowerCase(Locale.ROOT)) > 1
                            ? image.name.replace('.', '_')
                            : stem;
            listing.pages.add(new Page(image, output));
        }
        if (listing.outputId != null) {
            Map<String, Entry> outputs = new HashMap<>();
            for (Entry out : children(context.getContentResolver(), folder.tree, listing.outputId))
                if (!out.directory() && isImage(out))
                    outputs.put(base(out.name).toLowerCase(Locale.ROOT), out);
            for (Page page : listing.pages)
                page.output = outputs.get(page.outputBase.toLowerCase(Locale.ROOT));
        }
        return listing;
    }

    private static List<Entry> children(ContentResolver resolver, Uri tree, String documentId)
            throws IOException {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        String[] columns = {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        };
        List<Entry> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(uri, columns, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取文件夹内容，请重新选择文件夹");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null) continue;
                result.add(
                        new Entry(
                                cursor.getString(0),
                                name,
                                cursor.isNull(2) ? "" : cursor.getString(2),
                                cursor.isNull(3) ? 0 : cursor.getLong(3),
                                cursor.isNull(4) ? 0 : cursor.getLong(4)));
            }
        } catch (SecurityException lost) {
            throw new IOException("文件夹访问权限已失效，请重新选择该文件夹");
        }
        return result;
    }

    static boolean isImage(Entry entry) {
        String lower = entry.name.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".")) return false;
        if (entry.mime.startsWith("image/")) return true;
        return lower.matches(".*\\.(jpe?g|png|webp|bmp|gif|heic|heif|avif)$");
    }

    /** Lower-case file extension without the dot ("png" when there is none). */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        String extension =
                dot > 0 && dot < name.length() - 1
                        ? name.substring(dot + 1).toLowerCase(Locale.ROOT)
                        : "png";
        return extension.matches("[a-z0-9]{1,5}") ? extension : "png";
    }

    static String base(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static int natural(String a, String b) {
        return NaturalOrder.INSTANCE.compare(a, b);
    }

    // ---------- Images ----------

    /** Full page, bounded exactly like web pages so detection and layout behave the same. */
    static Bitmap load(Context context, Folder folder, Entry entry) throws IOException {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(folder.tree, entry.documentId);
        try {
            return ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.getContentResolver(), uri),
                    (decoder, info, src) -> {
                        int[] target =
                                BrowserImageLoader.boundedSize(
                                        info.getSize().getWidth(), info.getSize().getHeight());
                        decoder.setTargetSize(target[0], target[1]);
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
        } catch (SecurityException lost) {
            throw new IOException("文件夹访问权限已失效，请重新选择该文件夹");
        } catch (IOException | RuntimeException broken) {
            throw new IOException("图片无法解码：" + entry.name + "（文件可能损坏或格式不受支持）");
        }
    }

    private static final LruCache<String, Bitmap> THUMBS =
            new LruCache<String, Bitmap>(24 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    static Bitmap cachedThumb(String key) {
        return THUMBS.get(key);
    }

    /** Small preview for the grid; keyed by document so a new translation shows a new thumbnail. */
    static Bitmap thumb(Context context, Folder folder, Entry entry, int width) throws IOException {
        String key = thumbKey(folder, entry);
        Bitmap cached = THUMBS.get(key);
        if (cached != null) return cached;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(folder.tree, entry.documentId);
        Bitmap value =
                ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(context.getContentResolver(), uri),
                        (decoder, info, src) -> {
                            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                            float scale = Math.min(1f, (float) width / Math.max(1, w));
                            decoder.setTargetSize(
                                    Math.max(1, Math.round(w * scale)),
                                    Math.max(1, Math.round(h * scale)));
                        });
        THUMBS.put(key, value);
        return value;
    }

    static String thumbKey(Folder folder, Entry entry) {
        return folder.tree + "\n" + entry.documentId + "\n" + entry.modified + "\n" + entry.size;
    }

    // ---------- Output ----------

    /**
     * Writes the translated page to 译图/&lt;name&gt;; JPEG sources stay JPEG (q95) to keep sizes
     * sane.
     */
    static void writeOutput(Context context, Folder folder, Page page, Bitmap image)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        boolean jpeg =
                page.source.mime.contains("jpeg")
                        || page.source.name.toLowerCase(Locale.ROOT).matches(".*\\.jpe?g$");
        String mime = jpeg ? "image/jpeg" : "image/png",
                name = page.outputBase + (jpeg ? ".jpg" : ".png");
        try {
            String outputId = null;
            for (Entry child : children(resolver, folder.tree, folder.documentId))
                if (child.directory() && OUTPUT_DIR.equals(child.name)) {
                    outputId = child.documentId;
                    break;
                }
            Uri outputDir;
            if (outputId == null) {
                outputDir =
                        DocumentsContract.createDocument(
                                resolver, folder.uri(), Document.MIME_TYPE_DIR, OUTPUT_DIR);
                if (outputDir == null) throw new IOException();
                outputId = DocumentsContract.getDocumentId(outputDir);
            }
            outputDir = DocumentsContract.buildDocumentUriUsingTree(folder.tree, outputId);
            Uri target = null;
            for (Entry child : children(resolver, folder.tree, outputId))
                if (!child.directory() && base(child.name).equalsIgnoreCase(page.outputBase)) {
                    // Format changed since the last run (e.g. png → jpg): replace rather than keep
                    // both.
                    if (child.name.equalsIgnoreCase(name))
                        target =
                                DocumentsContract.buildDocumentUriUsingTree(
                                        folder.tree, child.documentId);
                    else
                        DocumentsContract.deleteDocument(
                                resolver,
                                DocumentsContract.buildDocumentUriUsingTree(
                                        folder.tree, child.documentId));
                }
            boolean created = target == null;
            if (created) target = DocumentsContract.createDocument(resolver, outputDir, mime, name);
            if (target == null) throw new IOException();
            try (OutputStream out = resolver.openOutputStream(target, "wt")) {
                if (out == null
                        || !PerformanceDiagnostics.compress(
                                "local_output",
                                image,
                                jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,
                                jpeg ? 95 : 100,
                                out)) throw new IOException();
            } catch (IOException | RuntimeException failed) {
                if (created)
                    try {
                        DocumentsContract.deleteDocument(resolver, target);
                    } catch (Exception ignored) {
                    }
                throw failed;
            }
            PerformanceDiagnostics.pixels("local_master", image);
            PerformanceDiagnostics.uri(
                    context, jpeg ? "local_lossy_output" : "local_png_output", target);
        } catch (SecurityException
                | IOException
                | IllegalArgumentException
                | UnsupportedOperationException failed) {
            throw new IOException(
                    "无法在该文件夹写入「" + OUTPUT_DIR + "」，请确认存储空间充足，或改选可写入的文件夹（例如“下载”或“文档”里的子文件夹）");
        }
    }

    static Bitmap loadOutput(Context context, Folder folder, Page page) throws IOException {
        return page.output == null ? null : load(context, folder, page.output);
    }

    // ---------- Per-page results (app-private: statistics + transcript; never images or keys)
    // ----------

    private static File outcomeFile(Context context, Folder folder, Page page) {
        return new File(
                new File(new File(context.getFilesDir(), "local-comics"), sha(folder.key())),
                sha(page.source.name) + ".outcome");
    }

    static PageOutcome readOutcome(Context context, Folder folder, Page page) {
        File file = outcomeFile(context, folder, page);
        try {
            if (!file.isFile() || file.length() > PageOutcome.MAX_ENCODED_SIZE) return null;
            return PageOutcome.decode(
                    new String(
                            java.nio.file.Files.readAllBytes(file.toPath()),
                            StandardCharsets.UTF_8));
        } catch (Exception unreadable) {
            return null;
        }
    }

    static void writeOutcome(Context context, Folder folder, Page page, PageOutcome outcome) {
        File file = outcomeFile(context, folder, page);
        try {
            file.getParentFile().mkdirs();
            File temporary = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(outcome.encode().getBytes(StandardCharsets.UTF_8));
            }
            RenderedPageCache.replace(temporary, file);
        } catch (Exception ignored) {
            /* Statistics are optional; the translated image is the result. */
        }
    }

    /** Which workbench draft belongs to this page's current translation (null clears it). */
    static void writeDraftKey(Context context, Folder folder, Page page, String key) {
        File file = new File(outcomeFile(context, folder, page).getPath() + ".draft");
        try {
            if (key == null) {
                file.delete();
                return;
            }
            file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(key.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            /* Optional: without it the page opens read-only in the workbench. */
        }
    }

    static String readDraftKey(Context context, Folder folder, Page page) {
        File file = new File(outcomeFile(context, folder, page).getPath() + ".draft");
        try {
            if (!file.isFile() || file.length() > 128) return null;
            String key =
                    new String(
                                    java.nio.file.Files.readAllBytes(file.toPath()),
                                    StandardCharsets.UTF_8)
                            .trim();
            return PageDraftStore.validKey(key) ? key : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    enum State {
        NEW,
        DONE,
        PARTIAL,
        NO_TEXT,
        FAILED
    }

    static State state(PageOutcome outcome, Page page) {
        if (page.output != null)
            return outcome != null && outcome.known && !outcome.incomplete()
                    ? State.DONE
                    : State.PARTIAL;
        if (outcome == null) return State.NEW;
        if (outcome.known && outcome.detected == 0) return State.NO_TEXT;
        return State.FAILED;
    }

    /**
     * Translates one page end-to-end. Reuses a page rendered earlier (here or in the browser) when
     * the pixels and settings match, so re-runs and resumed batches do not pay twice.
     */
    static PageOutcome translate(
            Context context,
            TranslationEngine engine,
            Folder folder,
            Page page,
            AppSettings settings,
            boolean forceFresh,
            BooleanSupplier cancelled,
            TranslationEngine.Progress progress,
            Exception[] throttle)
            throws Exception {
        Bitmap source = null;
        PagePipeline.Page result = null;
        PerformanceDiagnostics.Page diagnostic = PerformanceDiagnostics.begin("local_folder");
        try {
            progress.update("读取原图…", 0, 0);
            long diagnosticLoadAt = PerformanceDiagnostics.clock();
            source = load(context, folder, page.source);
            PerformanceDiagnostics.phase("load", diagnosticLoadAt);
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            result =
                    PagePipeline.run(
                            context,
                            engine,
                            source,
                            settings,
                            true,
                            forceFresh,
                            cancelled,
                            progress,
                            "本页未检测到文字；如有对白，可在设置中更换检测模型后重新翻译。");
            if (result.fromCache()) {
                Bitmap restored =
                        android.graphics.BitmapFactory.decodeByteArray(
                                result.cachedPng, 0, result.cachedPng.length);
                if (restored != null) {
                    try {
                        writeOutput(context, folder, page, restored);
                    } finally {
                        restored.recycle();
                    }
                    writeOutcome(context, folder, page, result.outcome);
                    writeDraftKey(
                            context, folder, page, result.keepDraft(context) ? result.key : null);
                    return result.outcome;
                }
                // The cache is only a shortcut: an undecodable entry falls back to normal detection
                // + translation
                // (as before the refactor) instead of failing the page and trapping later retries
                // on the same entry.
                result.close();
                result =
                        PagePipeline.run(
                                context,
                                engine,
                                source,
                                settings,
                                false,
                                forceFresh,
                                cancelled,
                                progress,
                                "本页未检测到文字；如有对白，可在设置中更换检测模型后重新翻译。");
            }
            writeOutcome(context, folder, page, result.outcome);
            if (result.noText) return result.outcome;
            if (result.throttle != null) throttle[0] = result.throttle;
            if (!result.rendered()) throw new Exception(result.summary);
            progress.update("保存译图…", 0, 0);
            writeOutput(context, folder, page, result.image);
            writeDraftKey(context, folder, page, result.keepDraft(context) ? result.key : null);
            if (!result.outcome.incomplete()) {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (result.image.getByteCount() < 64 * 1024 * 1024
                        && PerformanceDiagnostics.compress(
                                "shared_cache", result.image, Bitmap.CompressFormat.PNG, 100, png))
                    PagePipeline.remember(context, result, png.toByteArray());
            }
            return result.outcome;
        } finally {
            if (result != null) result.close();
            if (source != null && !source.isRecycled()) source.recycle();
            if (diagnostic != null) diagnostic.close();
        }
    }

    // ---------- Recent folders ----------

    static final class Recent {
        final Folder folder;
        final long opened;

        Recent(Folder folder, long opened) {
            this.folder = folder;
            this.opened = opened;
        }
    }

    static List<Recent> recents(Context context) {
        List<Recent> result = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(prefs(context).getString(RECENT, "[]"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                result.add(
                        new Recent(
                                new Folder(
                                        Uri.parse(row.getString("tree")),
                                        row.getString("id"),
                                        row.getString("name")),
                                row.optLong("opened")));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static void remember(Context context, Folder folder) {
        List<Recent> rows = recents(context);
        rows.removeIf(r -> r.folder.key().equals(folder.key()));
        rows.add(0, new Recent(folder, System.currentTimeMillis()));
        save(context, rows.subList(0, Math.min(12, rows.size())));
    }

    static void forget(Context context, Folder folder) {
        List<Recent> rows = recents(context);
        rows.removeIf(r -> r.folder.key().equals(folder.key()));
        save(context, rows);
        boolean stillUsed = false;
        for (Recent r : rows) if (r.folder.tree.equals(folder.tree)) stillUsed = true;
        if (!stillUsed)
            try {
                context.getContentResolver()
                        .releasePersistableUriPermission(
                                folder.tree,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
    }

    static boolean granted(Context context, Uri tree) {
        for (android.content.UriPermission p :
                context.getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(tree) && p.isReadPermission()) return true;
        return false;
    }

    private static void save(Context context, List<Recent> rows) {
        JSONArray array = new JSONArray();
        try {
            for (Recent r : rows)
                array.put(
                        new JSONObject()
                                .put("tree", r.folder.tree.toString())
                                .put("id", r.folder.documentId)
                                .put("name", r.folder.name)
                                .put("opened", r.opened));
        } catch (Exception ignored) {
        }
        prefs(context).edit().putString(RECENT, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String sha(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
