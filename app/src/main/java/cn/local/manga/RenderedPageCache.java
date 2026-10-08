package cn.local.manga;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Completed pages only, addressed by verified source pixels and rendering settings. */
final class RenderedPageCache {
    private static final Object LOCK = new Object();
    private static final int MAGIC = 0x4d504731,
            MAX_PNG = 9 * 1024 * 1024,
            MAX_META = PageOutcome.MAX_ENCODED_SIZE;
    private static final long LIMIT = 192L * 1024 * 1024;
    private final File directory;
    private android.content.Context context;

    static final class Entry {
        final byte[] png;
        final PageOutcome outcome;

        Entry(byte[] png, PageOutcome outcome) {
            this.png = png;
            this.outcome = outcome;
        }
    }

    RenderedPageCache(File cache) {
        directory = new File(cache, "rendered-pages-v1");
    }

    RenderedPageCache(android.content.Context context) {
        this(context.getCacheDir());
        this.context = context.getApplicationContext();
    }

    static String key(String content, String configuration) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("rendered-page-bubble-font-20261008-r31\n".getBytes(StandardCharsets.UTF_8));
        digest.update(content.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        byte[] bytes = digest.digest(configuration.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte b : bytes) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hash.toString();
    }

    private File file(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid cache key");
        return new File(directory, key + ".page");
    }

    Entry read(String key) {
        if (context != null)
            try {
                StoredCache.Entry value = StoredCache.read(context, "rendered", key, MAX_PNG);
                if (value == null) return null;
                PageOutcome outcome = PageOutcome.decode(value.metadata.optString("outcome"));
                return outcome.incomplete() || outcome.succeeded == 0 || !pngSignature(value.bytes)
                        ? null
                        : new Entry(value.bytes, outcome);
            } catch (Exception unavailable) {
                return null;
            }
        synchronized (LOCK) {
            File file = file(key);
            try {
                if (!file.isFile() || file.length() > MAX_PNG + MAX_META + 64L) return null;
                try (DataInputStream in =
                        new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                    if (in.readInt() != MAGIC) throw new IOException();
                    int metaSize = in.readInt(), size = in.readInt();
                    if (metaSize < 1
                            || metaSize > MAX_META
                            || size < 8
                            || size > MAX_PNG
                            || file.length() != 44L + metaSize + size) throw new IOException();
                    byte[] expected = new byte[32], meta = new byte[metaSize], png = new byte[size];
                    in.readFully(expected);
                    in.readFully(meta);
                    in.readFully(png);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    digest.update(meta);
                    if (!MessageDigest.isEqual(expected, digest.digest(png)) || !pngSignature(png))
                        throw new IOException();
                    PageOutcome outcome =
                            PageOutcome.decode(new String(meta, StandardCharsets.UTF_8));
                    if (outcome.incomplete() || outcome.succeeded == 0) throw new IOException();
                    file.setLastModified(System.currentTimeMillis());
                    return new Entry(png, outcome);
                }
            } catch (Exception invalid) {
                file.delete();
                return null;
            }
        }
    }

    // Conservative quota: count free bytes, not space reclaimable by evicting other apps.
    @android.annotation.SuppressLint("UsableSpace")
    void write(String key, byte[] png, PageOutcome outcome) {
        if (context != null) {
            try {
                if (outcome == null || outcome.incomplete() || outcome.succeeded == 0) {
                    StoredCache.remove(context, "rendered", key);
                    return;
                }
                if (png.length <= MAX_PNG && pngSignature(png))
                    StoredCache.write(
                            context,
                            "rendered",
                            key,
                            png,
                            new org.json.JSONObject().put("outcome", outcome.encode()),
                            null);
            } catch (Exception ignored) {
            }
            return;
        }
        synchronized (LOCK) {
            File target = file(key), temporary = null;
            if (outcome == null || outcome.incomplete() || outcome.succeeded == 0) {
                target.delete();
                return;
            }
            try {
                if (png.length > MAX_PNG || !pngSignature(png)) return;
                byte[] meta = outcome.encode().getBytes(StandardCharsets.UTF_8);
                if (meta.length > MAX_META) return;
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                File[] files = directory.listFiles();
                long total = 0;
                if (files != null) {
                    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                    for (File f : files) if (f.isFile()) total += f.length();
                    for (File f : files) {
                        if (total + png.length + meta.length + 44 <= LIMIT
                                && directory.getUsableSpace() > 320L * 1024 * 1024 + png.length)
                            break;
                        long bytes = f.length();
                        if (f.isFile() && f.delete()) total -= bytes;
                    }
                }
                if (total + png.length + meta.length + 44 > LIMIT
                        || directory.getUsableSpace() <= 320L * 1024 * 1024 + png.length) return;
                temporary = File.createTempFile("page-", ".tmp", directory);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(meta);
                try (DataOutputStream out =
                        new DataOutputStream(
                                new BufferedOutputStream(new FileOutputStream(temporary)))) {
                    out.writeInt(MAGIC);
                    out.writeInt(meta.length);
                    out.writeInt(png.length);
                    out.write(digest.digest(png));
                    out.write(meta);
                    out.write(png);
                }
                replace(temporary, target);
                if (PerformanceDiagnostics.enabled())
                    PerformanceDiagnostics.written("shared_cache", target.length());
            } catch (Exception ignored) {
                /* Optional cross-visit cache must not prevent displaying this result. */
            } finally {
                if (temporary != null) temporary.delete();
            }
        }
    }

    static void replace(File from, File to) throws IOException {
        try {
            Files.move(
                    from.toPath(),
                    to.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static boolean pngSignature(byte[] b) {
        return b.length >= 8
                && (b[0] & 255) == 137
                && b[1] == 80
                && b[2] == 78
                && b[3] == 71
                && b[4] == 13
                && b[5] == 10
                && b[6] == 26
                && b[7] == 10;
    }
}
