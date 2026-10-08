package cn.local.manga;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The browser's per-reading page cache: translated PNGs for one opened document
 * (cacheDir/browser-pages/&lt;document&gt;), each with a ".outcome" statistics companion. Space is
 * reserved before a paid request so a full disk is reported up front instead of after paying. Moved
 * out of BrowserActivity unchanged in behavior.
 */
final class PageCacheStore {
    /**
     * Largest PNG the web page accepts back (Base64 transfer limit); also the cache's per-file cap.
     */
    static final int MAX_PNG_BYTES = 9 * 1024 * 1024 - 100;

    private static final long RESERVATION = 32L * 1024 * 1024, MIN_FREE = 256L * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final Map<String, Long> RESERVATIONS = new HashMap<>();

    static final class StorageFullException extends IOException {
        StorageFullException() {
            super("可用存储不足或无法保存译图；请清理空间后停止并重新开始");
        }

        StorageFullException(String message) {
            super(message);
        }
    }

    /** Only genuine low space blocks the queue; the message states the measured numbers. */
    private StorageFullException lowSpace(long needed) {
        StorageQuota.space(context.getFilesDir());
        long free = StorageQuota.availableBytes, mib = StoragePolicy.MIB;
        return new StorageFullException(
                free < 0
                        ? "无法读取手机剩余空间，已暂缓新图；请稍后重试"
                        : "手机剩余空间 "
                                + free / mib
                                + " MB，低于翻译所需的 "
                                + (StoragePolicy.MIN_FREE + needed) / mib
                                + " MB（固定保留 300 MB + 正在处理的页面预留 "
                                + needed / mib
                                + " MB），已暂缓新图；请清理空间后重新开始");
    }

    private final File root;
    private android.content.Context context;

    PageCacheStore(File cacheDir) {
        root = new File(cacheDir, "browser-pages");
    }

    PageCacheStore(android.content.Context context) {
        root = new File(context.getCacheDir(), "browser-pages-v2");
        this.context = context.getApplicationContext();
    }

    private static String storedKey(File file) {
        return file.getName().replaceFirst("\\.png$", "");
    }

    /**
     * Cache file for one web image in one opened document; unique per URL, size and render
     * settings.
     */
    File file(String page, String document, String url, int width, int height, AppSettings settings)
            throws Exception {
        JSONArray config =
                new JSONArray()
                        .put("browser-page-v1.1.6-color")
                        .put(page)
                        .put(document)
                        .put(url)
                        .put(width)
                        .put(height);
        JSONArray fields = settings.renderFields();
        for (int i = 0; i < fields.length(); i++) config.put(fields.get(i));
        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest(config.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte b : digest) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        File directory = new File(root, document);
        directory.mkdirs();
        return new File(directory, hash + ".png");
    }

    /**
     * Evicts least-recently-used pages of other documents until there is room; throws when there is
     * not.
     */
    // Conservative quota: count free bytes, not space reclaimable by evicting other apps.
    @android.annotation.SuppressLint("UsableSpace")
    void reserve(File file) throws StorageFullException {
        if (context != null) {
            synchronized (LOCK) {
                long reserved = 0;
                for (long bytes : RESERVATIONS.values()) reserved += bytes;
                if (!StorageQuota.canReserve(context, reserved + RESERVATION))
                    throw lowSpace(reserved + RESERVATION);
                RESERVATIONS.put(file.getPath(), RESERVATION);
                return;
            }
        }
        synchronized (LOCK) {
            File current = file.getParentFile(), cacheRoot = current.getParentFile();
            ArrayList<File> files = new ArrayList<>();
            File[] entries = cacheRoot.listFiles();
            if (entries != null)
                for (File entry : entries) {
                    if (entry.isFile()) files.add(entry);
                    else {
                        File[] children = entry.listFiles();
                        if (children != null)
                            for (File child : children) if (child.isFile()) files.add(child);
                    }
                }
            long cachedBytes = 0, reserved = 0;
            for (File entry : files) cachedBytes += entry.length();
            for (long bytes : RESERVATIONS.values()) reserved += bytes;
            long usable = cacheRoot.getUsableSpace(),
                    budget = AutoTranslationQueue.storageBudget(cachedBytes, usable);
            files.sort(Comparator.comparingLong(File::lastModified));
            for (File entry : files) {
                if (cachedBytes + reserved + RESERVATION <= budget
                        && usable >= MIN_FREE + reserved + RESERVATION) break;
                if (entry.getParentFile().equals(current)
                        || RESERVATIONS.containsKey(entry.getPath())
                        || entry.getName().endsWith(".tmp")
                        || entry.getName().endsWith(".outcome")
                        || entry.getName().endsWith(".draft")) continue;
                long bytes = entry.length();
                if (entry.delete()) {
                    cachedBytes -= bytes;
                    usable += bytes;
                    File metadata = new File(entry.getPath() + ".outcome");
                    long metadataBytes = metadata.length();
                    if (metadata.delete()) {
                        cachedBytes -= metadataBytes;
                        usable += metadataBytes;
                    }
                    File draftKey = new File(entry.getPath() + ".draft");
                    long keyBytes = draftKey.length();
                    if (draftKey.delete()) {
                        cachedBytes -= keyBytes;
                        usable += keyBytes;
                    }
                }
            }
            if (cachedBytes + reserved + RESERVATION > budget
                    || usable < MIN_FREE + reserved + RESERVATION) throw new StorageFullException();
            RESERVATIONS.put(file.getPath(), RESERVATION);
        }
    }

    void release(File file) {
        synchronized (LOCK) {
            RESERVATIONS.remove(file.getPath());
        }
    }

    /** Atomically replaces the PNG, then its statistics companion. */
    void save(File file, byte[] png, PageOutcome outcome) throws IOException {
        if (context != null) {
            try {
                if (png == null
                        || png.length > MAX_PNG_BYTES
                        || !RenderedPageCache.pngSignature(png))
                    throw new IOException("译图无效或超过回填上限");
                StoredCache.write(
                        context,
                        "web",
                        storedKey(file),
                        png,
                        new org.json.JSONObject().put("outcome", outcome.encode()),
                        file);
            } catch (Exception failed) {
                String reason = "译图保存未完成：" + StorageDatabase.message(failed);
                StorageDatabase.problem = reason;
                long bytes = png == null ? 0 : png.length;
                // 1.1.2 reported every failure as "storage full" and froze the whole queue; only
                // real low space does that now.
                if (!StorageQuota.canReserve(context, bytes)) throw lowSpace(bytes);
                throw new IOException(reason, failed);
            }
            return;
        }
        synchronized (LOCK) {
            File temporary = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(png);
                out.flush();
            } catch (Exception e) {
                temporary.delete();
                throw new StorageFullException();
            }
            try {
                RenderedPageCache.replace(temporary, file);
            } catch (IOException e) {
                temporary.delete();
                throw new StorageFullException();
            }
            File metadata = new File(file.getPath() + ".outcome"),
                    pending = new File(metadata.getPath() + ".tmp");
            try {
                try (Writer writer =
                        new OutputStreamWriter(
                                new FileOutputStream(pending), StandardCharsets.UTF_8)) {
                    writer.write(outcome.encode());
                }
                RenderedPageCache.replace(pending, metadata);
                if (PerformanceDiagnostics.enabled())
                    PerformanceDiagnostics.written(
                            "browser_cache", file.length() + metadata.length());
            } catch (Exception e) {
                pending.delete();
                metadata.delete();
                throw new StorageFullException();
            }
        }
    }

    File committedFile(File file) throws Exception {
        return context == null
                ? (file.isFile() ? file : null)
                : StoredCache.committedFile(context, "web", storedKey(file));
    }

    /**
     * PNG bytes when the file exists and fits the transfer limit, else null; {@code touch} marks it
     * recently used.
     */
    byte[] read(File file, boolean touch) throws IOException {
        if (context != null)
            try {
                StoredCache.Entry entry =
                        StoredCache.read(context, "web", storedKey(file), MAX_PNG_BYTES);
                return entry == null ? null : entry.bytes;
            } catch (Exception e) {
                throw new IOException("译图读取未完成", e);
            }
        synchronized (LOCK) {
            if (!file.isFile()) return null;
            byte[] png = readPng(file);
            if (touch) file.setLastModified(System.currentTimeMillis());
            return png;
        }
    }

    private static byte[] readPng(File file) throws IOException {
        long size = file.length();
        if (size <= 0 || size > MAX_PNG_BYTES) return null;
        try (InputStream in = new FileInputStream(file);
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) size)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_PNG_BYTES) throw new IOException("译图缓存过大");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * Remembers which workbench draft (content key) belongs to this cached page; null clears it.
     */
    void saveDraftKey(File image, String key) {
        if (context != null) {
            try {
                StoredCache.patch(context, "web", storedKey(image), "draftKey", key);
            } catch (Exception ignored) {
            }
            return;
        }
        synchronized (LOCK) {
            File file = new File(image.getPath() + ".draft");
            try {
                if (key == null) {
                    file.delete();
                    return;
                }
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(key.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                /* Optional: the page then opens read-only in the workbench. */
            }
        }
    }

    String readDraftKey(File image) {
        if (context != null)
            try {
                org.json.JSONObject metadata =
                        StoredCache.metadata(context, "web", storedKey(image));
                String key = metadata == null ? null : metadata.optString("draftKey", null);
                return PageDraftStore.validKey(key) ? key : null;
            } catch (Exception unavailable) {
                return null;
            }
        synchronized (LOCK) {
            File file = new File(image.getPath() + ".draft");
            try {
                if (!file.isFile() || file.length() > 128) return null;
                String key =
                        new String(
                                        java.nio.file.Files.readAllBytes(file.toPath()),
                                        StandardCharsets.UTF_8)
                                .trim();
                return PageDraftStore.validKey(key) ? key : null;
            } catch (IOException e) {
                return null;
            }
        }
    }

    PageOutcome readOutcome(File image) {
        if (context != null)
            try {
                org.json.JSONObject metadata =
                        StoredCache.metadata(context, "web", storedKey(image));
                return PageOutcome.decode(
                        metadata == null ? null : metadata.optString("outcome", null));
            } catch (Exception unavailable) {
                return PageOutcome.unknown();
            }
        synchronized (LOCK) {
            try {
                File file = new File(image.getPath() + ".outcome");
                if (!file.isFile() || file.length() > PageOutcome.MAX_ENCODED_SIZE)
                    return PageOutcome.unknown();
                return PageOutcome.decode(
                        new String(
                                java.nio.file.Files.readAllBytes(file.toPath()),
                                StandardCharsets.UTF_8));
            } catch (Exception e) {
                return PageOutcome.unknown();
            }
        }
    }
}
