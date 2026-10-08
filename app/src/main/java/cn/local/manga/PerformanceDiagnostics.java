package cn.local.manga;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.SystemClock;

import org.json.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in measurement only. No bitmap is retained by the writer or sampler. */
final class PerformanceDiagnostics {
    private static volatile boolean initialized, enabled;

    // Only getApplicationContext() is assigned; this reference has process lifetime.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static Context app;

    private static File directory;
    private static final Object FILE_LOCK = new Object();
    private static final ThreadLocal<Page> current = new ThreadLocal<>();
    private static final Set<Page> active = ConcurrentHashMap.newKeySet();
    private static final Map<String, String[]> identities =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, String[]>(256, .75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String[]> entry) {
                            return size() > 512;
                        }
                    });
    private static final AtomicLong dropped = new AtomicLong(), errors = new AtomicLong();
    private static volatile String run = UUID.randomUUID().toString();
    private static ThreadPoolExecutor writer;
    private static ScheduledExecutorService sampler;
    private static long evicted;
    private static long lastSampleAt;
    private static final long HALF_LIMIT = 4L * 1024 * 1024;

    static synchronized void initialize(Context context) {
        if (initialized) return;
        app = context.getApplicationContext();
        directory = new File(app.getFilesDir(), "performance-diagnostics");
        enabled =
                app.getSharedPreferences("performance-diagnostics", 0).getBoolean("enabled", false);
        initialized = true;
        if (enabled) start();
    }

    static boolean enabled() {
        return enabled;
    }

    static synchronized boolean setEnabled(Context context, boolean value) {
        initialize(context);
        if (value == enabled) return true;
        if (!active.isEmpty()) return false;
        if (value) {
            run = UUID.randomUUID().toString();
            dropped.set(0);
            errors.set(0);
            synchronized (FILE_LOCK) {
                evicted = 0;
            }
        } else emit(null, "capture_end");
        app.getSharedPreferences("performance-diagnostics", 0)
                .edit()
                .putBoolean("enabled", value)
                .apply();
        enabled = value;
        if (value) start();
        else if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
        return true;
    }

    private static synchronized void start() {
        if (writer == null) {
            writer =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            5,
                            TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(256),
                            r -> new Thread(r, "manga-diagnostics-writer"),
                            (r, pool) -> dropped.incrementAndGet());
            writer.allowCoreThreadTimeOut(true);
        }
        if (sampler == null) {
            sampler =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> new Thread(r, "manga-diagnostics-sampler"));
            sampler.scheduleWithFixedDelay(PerformanceDiagnostics::sample, 0, 2, TimeUnit.SECONDS);
            emit(
                    null,
                    "capture_start",
                    "hashEncoding",
                    "width,height:big-endian-int32; pixels:ARGB big-endian, row-major; getPixels"
                            + " sRGB nonpremultiplied");
        }
    }

    static final class Page implements AutoCloseable {
        final String id = UUID.randomUUID().toString(), entry;
        final String captureRun = run;
        final long started = SystemClock.elapsedRealtime();
        final Page previous;
        String input = "", fingerprint = "", result = "unfinished";
        boolean closed;
        long diagnosticMs;

        Page(String entry) {
            this.entry = entry;
            previous = current.get();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            emit(
                    this,
                    "page_end",
                    "result",
                    result,
                    "elapsedMs",
                    SystemClock.elapsedRealtime() - started,
                    "diagnosticMs",
                    diagnosticMs);
            active.remove(this);
            if (current.get() == this) {
                if (previous == null) current.remove();
                else current.set(previous);
            }
        }
    }

    static Page begin(String entry) {
        if (!enabled) return null;
        Page page = new Page(entry);
        current.set(page);
        active.add(page);
        emit(page, "page_begin");
        ScheduledExecutorService sampling = sampler;
        if (active.size() == 1 && sampling != null)
            try {
                sampling.execute(() -> sample(true));
            } catch (RejectedExecutionException ignored) {
                errors.incrementAndGet();
            }
        return page;
    }

    static boolean hasPage() {
        return enabled && current.get() != null;
    }

    static Page currentPage() {
        return enabled ? current.get() : null;
    }

    static Page beginExport(
            ComicProject project, ComicProject.Page page, ExportJob.Options options) {
        if (!enabled) return null;
        Page trace = begin("export");
        Bitmap source = null;
        try {
            File file =
                    page.originalName != null
                            ? new File(project.pageDir(page), page.originalName)
                            : page.editable()
                                    ? new File(project.draftDir(page), PageDraft.SOURCE)
                                    : project.imageFile(page);
            source =
                    android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(file),
                            (decoder, info, input) ->
                                    decoder.setAllocator(
                                            android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE));
            trace.input = pixelHash(source);
            JSONObject edits = new JSONObject();
            for (Map.Entry<String, PageComposer.Edit> edit : page.edits.entrySet())
                edits.put(edit.getKey(), ComicProject.json(edit.getValue()));
            JSONObject fields =
                    new JSONObject()
                            .put("edits", edits)
                            .put("defaultStyle", ComicProject.json(project.defaultStyle))
                            .put("jpeg", options.jpeg)
                            .put("webp", options.webp)
                            .put("quality", options.quality);
            if (page.editable())
                fields.put(
                        "draft",
                        new JSONObject(
                                new String(
                                        Files.readAllBytes(
                                                new File(project.draftDir(page), PageDraft.JSON)
                                                        .toPath()),
                                        StandardCharsets.UTF_8)));
            trace.fingerprint = sha(canonical(fields).getBytes(StandardCharsets.UTF_8));
            emit(trace, "page_identity");
        } catch (Exception | OutOfMemoryError failure) {
            errors.incrementAndGet();
        } finally {
            if (source != null) source.recycle();
        }
        return trace;
    }

    static void rememberIdentity(File cache) {
        if (!enabled) return;
        Page p = current.get();
        if (p != null && !p.input.isEmpty())
            identities.put(cache.getPath(), new String[] {p.input, p.fingerprint});
    }

    static void restoreIdentity(File cache) {
        if (!enabled) return;
        Page p = current.get();
        String[] identity = identities.get(cache.getPath());
        if (p != null && identity != null) {
            p.input = identity[0];
            p.fingerprint = identity[1];
            emit(p, "page_identity", "restored", true);
        }
    }

    static void finish(Page page, String result) {
        if (page != null) {
            page.result = result;
            page.close();
        }
    }

    static void bind(Bitmap source, AppSettings settings) {
        if (!enabled) return;
        Page page = current.get();
        if (page == null || !page.input.isEmpty()) return;
        long at = SystemClock.elapsedRealtime();
        try {
            page.input = pixelHash(source);
            // Credentials and server URLs never enter this stable measurement fingerprint.
            JSONArray fields =
                    new JSONArray()
                            .put(settings.mode)
                            .put(settings.textModel)
                            .put(settings.imageModel)
                            .put(settings.textPrompt)
                            .put(settings.imagePrompt)
                            .put(settings.reasoningEffort)
                            .put(settings.detectorModel)
                            .put(DetectorModels.get(settings.detectorModel).sha256)
                            .put(ApiClient.textEndpoint(settings));
            page.fingerprint = sha(fields.toString().getBytes(StandardCharsets.UTF_8));
            emit(page, "page_identity", "width", source.getWidth(), "height", source.getHeight());
        } catch (Exception | OutOfMemoryError failure) {
            errors.incrementAndGet();
        } finally {
            page.diagnosticMs += SystemClock.elapsedRealtime() - at;
        }
    }

    static long clock() {
        return enabled ? SystemClock.elapsedRealtime() : 0;
    }

    static void phase(String stage, long started) {
        if (enabled && started != 0)
            emit(
                    current.get(),
                    "phase",
                    "stage",
                    stage,
                    "ms",
                    SystemClock.elapsedRealtime() - started);
    }

    static void duration(String stage, long millis) {
        if (enabled) emit(current.get(), "phase", "stage", stage, "ms", millis);
    }

    static void written(String layer, long bytes) {
        if (enabled) emit(current.get(), "write", "layer", layer, "bytes", bytes);
    }

    static void result(String result) {
        if (enabled) {
            Page p = current.get();
            if (p != null) p.result = result;
        }
    }

    static void pixels(String layer, Bitmap bitmap) {
        if (!enabled || bitmap == null) return;
        Page page = current.get();
        long at = SystemClock.elapsedRealtime();
        try {
            emit(
                    page,
                    "pixels",
                    "layer",
                    layer,
                    "sha256",
                    pixelHash(bitmap),
                    "width",
                    bitmap.getWidth(),
                    "height",
                    bitmap.getHeight());
        } catch (Exception | OutOfMemoryError failure) {
            errors.incrementAndGet();
        } finally {
            if (page != null) page.diagnosticMs += SystemClock.elapsedRealtime() - at;
        }
    }

    static String pixelHash(Bitmap bitmap) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        digest.update(ByteBuffer.allocate(8).putInt(width).putInt(height).array());
        int[] row = new int[width];
        byte[] bytes = new byte[Math.multiplyExact(width, 4)];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int v = row[x], i = x * 4;
                bytes[i] = (byte) (v >>> 24);
                bytes[i + 1] = (byte) (v >>> 16);
                bytes[i + 2] = (byte) (v >>> 8);
                bytes[i + 3] = (byte) v;
            }
            digest.update(bytes);
        }
        return hex(digest.digest());
    }

    static String sha(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }

    static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject j = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            j.keys().forEachRemaining(keys::add);
            Collections.sort(keys);
            StringBuilder s = new StringBuilder("{");
            for (String key : keys) {
                if (s.length() > 1) s.append(',');
                s.append(JSONObject.quote(key)).append(':').append(canonical(j.get(key)));
            }
            return s.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            StringBuilder s = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) s.append(',');
                s.append(canonical(a.get(i)));
            }
            return s.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    static void draft(File dir) {
        draft("draft", dir);
    }

    static void draft(String location, File dir) {
        if (!enabled || dir == null) return;
        long at = clock();
        try {
            JSONObject json =
                    new JSONObject(
                            new String(
                                    Files.readAllBytes(new File(dir, PageDraft.JSON).toPath()),
                                    StandardCharsets.UTF_8));
            emit(
                    current.get(),
                    "draft",
                    "layer",
                    location,
                    "sha256",
                    sha(canonical(json).getBytes(StandardCharsets.UTF_8)));
            for (String name : new String[] {PageDraft.SOURCE, "clean.png", "rendered.png"})
                file(location + "_" + name, new File(dir, name));
        } catch (Exception | OutOfMemoryError failure) {
            errors.incrementAndGet();
        } finally {
            phase("diagnostic_draft", at);
        }
    }

    static void file(String layer, File file) {
        if (!enabled) return;
        Bitmap bitmap = null;
        long at = clock();
        try {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) throw new IOException();
            emit(
                    current.get(),
                    "file",
                    "layer",
                    layer,
                    "bytes",
                    file.length(),
                    "sha256",
                    pixelHash(bitmap),
                    "width",
                    bitmap.getWidth(),
                    "height",
                    bitmap.getHeight());
        } catch (Exception | OutOfMemoryError failure) {
            errors.incrementAndGet();
        } finally {
            if (bitmap != null) bitmap.recycle();
            Page p = current.get();
            if (p != null) p.diagnosticMs += clock() - at;
        }
    }

    static void encoded(String layer, byte[] bytes) {
        if (!enabled) return;
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new IOException();
            pixels(layer, bitmap);
        } catch (Exception | OutOfMemoryError failure) {
            errors.incrementAndGet();
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    static void uri(Context context, String layer, android.net.Uri uri) {
        if (!enabled) return;
        uri(context.getContentResolver(), layer, uri);
    }

    static void uri(android.content.ContentResolver resolver, String layer, android.net.Uri uri) {
        if (!enabled) return;
        Bitmap bitmap = null;
        long at = clock();
        try (InputStream in = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) throw new IOException();
            pixels(layer, bitmap);
        } catch (Exception | OutOfMemoryError failure) {
            errors.incrementAndGet();
        } finally {
            if (bitmap != null) bitmap.recycle();
            phase("diagnostic_readback", at);
        }
    }

    static void zip(Context context, android.net.Uri uri, Map<String, Page> entries) {
        if (entries.isEmpty()) return;
        try (java.util.zip.ZipInputStream in =
                new java.util.zip.ZipInputStream(
                        context.getContentResolver().openInputStream(uri))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Page trace = entries.get(entry.getName());
                if (trace != null && entry.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    Bitmap bitmap = null;
                    try {
                        bitmap = BitmapFactory.decodeStream(in);
                        if (bitmap == null) throw new IOException();
                        String hash = pixelHash(bitmap);
                        in.closeEntry();
                        emit(
                                trace,
                                "pixels",
                                "layer",
                                "export_png_readback",
                                "sha256",
                                hash,
                                "width",
                                bitmap.getWidth(),
                                "height",
                                bitmap.getHeight());
                    } finally {
                        if (bitmap != null) bitmap.recycle();
                    }
                } else in.closeEntry();
            }
        } catch (Exception | OutOfMemoryError failure) {
            errors.incrementAndGet();
        }
    }

    static boolean compress(
            String layer,
            Bitmap bitmap,
            Bitmap.CompressFormat format,
            int quality,
            OutputStream out) {
        if (!enabled) return bitmap.compress(format, quality, out);
        long at = clock();
        CountingStream counting = new CountingStream(out);
        try {
            return bitmap.compress(format, quality, counting);
        } finally {
            emit(
                    current.get(),
                    "encode",
                    "layer",
                    layer,
                    "format",
                    format.toString(),
                    "quality",
                    quality,
                    "ms",
                    clock() - at,
                    "bytes",
                    counting.count,
                    "width",
                    bitmap.getWidth(),
                    "height",
                    bitmap.getHeight());
        }
    }

    private static final class CountingStream extends FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    static void event(String type, Object... fields) {
        if (enabled) emit(current.get(), type, fields);
    }

    private static void emit(Page page, String type, Object... fields) {
        if (writer == null) return;
        try {
            JSONObject row =
                    new JSONObject()
                            .put("diagnosticSchema", 1)
                            .put("run", page == null ? run : page.captureRun)
                            .put("version", BuildConfig.VERSION_NAME)
                            .put("event", type)
                            .put("elapsed", SystemClock.elapsedRealtime());
            if (page != null)
                row.put("page", page.id)
                        .put("entry", page.entry)
                        .put("input", page.input)
                        .put("fingerprint", page.fingerprint);
            for (int i = 0; i < fields.length; i += 2) row.put((String) fields[i], fields[i + 1]);
            byte[] bytes = (row + "\n").getBytes(StandardCharsets.UTF_8);
            writer.execute(() -> append(bytes));
        } catch (Exception | OutOfMemoryError failure) {
            errors.incrementAndGet();
        }
    }

    private static void append(byte[] bytes) {
        synchronized (FILE_LOCK) {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
                File current = new File(directory, "current.jsonl"),
                        previous = new File(directory, "previous.jsonl");
                if (current.length() + bytes.length > HALF_LIMIT) {
                    if (previous.exists()) {
                        try (BufferedReader rows = Files.newBufferedReader(previous.toPath())) {
                            String line;
                            while ((line = rows.readLine()) != null) {
                                if (run.equals(new JSONObject(line).optString("run"))) evicted++;
                            }
                        }
                        if (!previous.delete()) throw new IOException();
                    }
                    if (current.exists() && !current.renameTo(previous)) throw new IOException();
                }
                try (OutputStream out = new FileOutputStream(current, true)) {
                    out.write(bytes);
                }
            } catch (Exception failure) {
                errors.incrementAndGet();
            }
        }
    }

    private static void sample() {
        sample(false);
    }

    private static void sample(boolean firstPage) {
        if (!enabled || !firstPage && active.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastSampleAt < 2000) return;
        lastSampleAt = now;
        try {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            ((ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
            long rss = -1, threads = -1;
            for (String line : Files.readAllLines(new File("/proc/self/status").toPath())) {
                if (line.startsWith("VmRSS:"))
                    rss = Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
                if (line.startsWith("Threads:"))
                    threads = Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
            int workers = 0;
            for (Thread t : Thread.getAllStackTraces().keySet())
                if (t.isAlive()
                        && t.getName()
                                .matches(
                                        "(?:pool-.*|manga-.*|project-.*|local-.*|browser-.*|api-.*|image-.*|translation-.*|page-draft-.*|comic-.*|export-.*|rtdetr-.*)"))
                    workers++;
            Runtime runtime = Runtime.getRuntime();
            emit(
                    null,
                    "memory",
                    "nativeBytes",
                    Debug.getNativeHeapAllocatedSize(),
                    "javaBytes",
                    runtime.totalMemory() - runtime.freeMemory(),
                    "availMem",
                    info.availMem,
                    "threshold",
                    info.threshold,
                    "lowMemory",
                    info.lowMemory,
                    "rss",
                    rss,
                    "threads",
                    threads,
                    "appWorkers",
                    workers);
        } catch (Exception failure) {
            errors.incrementAndGet();
        }
    }

    static void export(OutputStream out) throws IOException {
        if (directory == null) return;
        if (writer == null
                && !new File(directory, "current.jsonl").isFile()
                && !new File(directory, "previous.jsonl").isFile()) return;
        // Called from the existing log screen's worker; bounded flush avoids a UI stall.
        ThreadPoolExecutor pool = writer;
        if (pool != null) {
            CountDownLatch flushed = new CountDownLatch(1);
            pool.execute(flushed::countDown);
            try {
                if (!flushed.await(10, TimeUnit.SECONDS)) errors.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.incrementAndGet();
            }
        }
        synchronized (FILE_LOCK) {
            if (directory != null)
                for (String name : new String[] {"previous.jsonl", "current.jsonl"}) {
                    File file = new File(directory, name);
                    if (file.isFile()) Files.copy(file.toPath(), out);
                }
            try {
                JSONObject status =
                        new JSONObject()
                                .put("diagnosticSchema", 1)
                                .put("event", "capture_state")
                                .put("run", run)
                                .put("dropped", dropped.get())
                                .put("errors", errors.get())
                                .put("evicted", evicted)
                                .put("activePages", active.size())
                                .put(
                                        "complete",
                                        dropped.get() == 0
                                                && errors.get() == 0
                                                && evicted == 0
                                                && active.isEmpty());
                out.write((status + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new IOException(e);
            }
        }
    }
}
