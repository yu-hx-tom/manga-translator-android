package cn.local.manga;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Small private disk caches: complete-file replacement, bounded oldest-first eviction. */
final class CacheFiles {
    private static final Object WRITE_LOCK = new Object();

    private CacheFiles() {}

    static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new CancellationException("已取消缓存处理");
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String hex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2], digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            hex[i * 2] = digits[(bytes[i] & 255) >>> 4];
            hex[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(hex);
    }

    static String key(String... fields) {
        MessageDigest digest = digest();
        for (String field : fields) {
            byte[] value = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
            digest.update(
                    new byte[] {
                        (byte) (value.length >>> 24),
                        (byte) (value.length >>> 16),
                        (byte) (value.length >>> 8),
                        (byte) value.length
                    });
            digest.update(value);
        }
        return hex(digest.digest());
    }

    static boolean validKey(String key) {
        return key != null && key.matches("[0-9a-f]{64}");
    }

    interface Writer {
        void write(FileOutputStream output) throws IOException;
    }

    static void write(File file, byte[] bytes, long cap, int entries, BooleanSupplier cancelled)
            throws IOException {
        write(
                file,
                bytes.length,
                cap,
                entries,
                cancelled,
                output -> writeBytes(output, bytes, cancelled));
    }

    static void writeBytes(java.io.OutputStream output, byte[] bytes, BooleanSupplier cancelled)
            throws IOException {
        for (int offset = 0; offset < bytes.length; offset += 65536) {
            check(cancelled);
            output.write(bytes, offset, Math.min(65536, bytes.length - offset));
        }
    }

    // Conservative quota: use free space without asking Android to evict other caches.
    @android.annotation.SuppressLint("UsableSpace")
    static void write(
            File file, long size, long cap, int entries, BooleanSupplier cancelled, Writer writer)
            throws IOException {
        // ponytail: a short shared disk-write lock keeps caps exact; no network or image work holds
        // it.
        synchronized (WRITE_LOCK) {
            check(cancelled);
            File directory = file.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建缓存目录");
            File[] abandoned =
                    directory.listFiles(
                            item ->
                                    item.isFile()
                                            && item.getName().startsWith("pending-")
                                            && item.getName().endsWith(".tmp"));
            if (abandoned != null) for (File item : abandoned) item.delete();
            if (size > cap || directory.getUsableSpace() < size + 320L * 1024 * 1024) return;
            File temporary = File.createTempFile("pending-", ".tmp", directory);
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    writer.write(output);
                    output.flush();
                    output.getFD().sync();
                }
                check(cancelled);
                StorageFiles.publish(temporary, file);
                StorageQuota.note(file);
            } finally {
                temporary.delete();
            }
        }
    }

    private static void trim(File directory, long cap, int entries, File keep) {
        File[] files =
                directory.listFiles(file -> file.isFile() && file.getName().endsWith(".cache"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long bytes = 0;
        for (File file : files) bytes += file.length();
        int count = files.length;
        for (File file : files) {
            if (bytes <= cap && count <= entries) break;
            if (!file.equals(keep)) {
                long size = file.length();
                if (file.delete()) {
                    bytes -= size;
                    count--;
                }
            }
        }
    }
}
