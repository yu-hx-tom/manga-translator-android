package cn.local.manga;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Exact pixel/model/algorithm identities keep all full-page and four-tile detection results reusable. */
public final class DetectionCache {
    private static final String ALGORITHM = "detection-v2-pp-full-four-tile-tight-rtdetr-v094-dedup";
    private static final int MAGIC = 0x4d444331, MAX_ENTRY = 512 * 1024, MAX_REGIONS = 2048, MAX_LINES = 16000;
    private final File directory;
    public DetectionCache(File cacheDirectory) { directory = new File(cacheDirectory, "detections-v1"); }

    /** SHA-256 of a version marker, width/height, then row-major big-endian ARGB values. */
    public static String contentHash(Bitmap bitmap, BooleanSupplier cancelled) {
        CacheFiles.check(cancelled); int width = bitmap.getWidth(), height = bitmap.getHeight();
        MessageDigest digest = pixelDigest(width, height); int[] row = new int[width]; byte[] bytes = new byte[width * 4];
        for (int y = 0; y < height; y++) { CacheFiles.check(cancelled); bitmap.getPixels(row, 0, width, 0, y, width, 1); updatePixels(digest, row, bytes); }
        CacheFiles.check(cancelled); return CacheFiles.hex(digest.digest());
    }
    static MessageDigest pixelDigest(int width, int height) {
        if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE / 4) throw new IllegalArgumentException("图片尺寸无效");
        MessageDigest digest = CacheFiles.digest();
        digest.update("manga-pixels-argb-v1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int value : new int[]{width, height}) digest.update(new byte[]{(byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte)value});
        return digest;
    }
    static void updatePixels(MessageDigest digest, int[] pixels, byte[] bytes) {
        for (int i = 0; i < pixels.length; i++) { int value = pixels[i], offset = i * 4; bytes[offset] = (byte)(value >>> 24); bytes[offset + 1] = (byte)(value >>> 16); bytes[offset + 2] = (byte)(value >>> 8); bytes[offset + 3] = (byte)value; }
        digest.update(bytes, 0, pixels.length * 4);
    }
    File file(String contentHash, String detectorId, int width, int height) {
        if (!CacheFiles.validKey(contentHash) || width <= 0 || height <= 0) throw new IllegalArgumentException("检测缓存标识无效");
        return new File(directory, CacheFiles.key(ALGORITHM, contentHash, detectorId, DetectorModels.get(detectorId).sha256, Integer.toString(width), Integer.toString(height)) + ".cache");
    }
    public List<Region> read(String contentHash, String detectorId, int width, int height, BooleanSupplier cancelled) {
        CacheFiles.check(cancelled); File file = file(contentHash, detectorId, width, height);
        try {
            if (file.length() < 44 || file.length() > MAX_ENTRY) { file.delete(); return null; }
            byte[] bytes = Files.readAllBytes(file.toPath()); CacheFiles.check(cancelled);
            if (bytes.length < 48 || bytes.length > MAX_ENTRY) throw new IOException("检测缓存长度无效");
            int payloadSize = bytes.length - 32; MessageDigest digest = CacheFiles.digest(); digest.update(bytes, 0, payloadSize);
            if (!MessageDigest.isEqual(digest.digest(), java.util.Arrays.copyOfRange(bytes, payloadSize, bytes.length))) throw new IOException("检测缓存校验失败");
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, 0, payloadSize))) {
                if (input.readInt() != MAGIC || input.readInt() != width || input.readInt() != height) throw new IOException("检测缓存尺寸不一致");
                int count = input.readInt(); if (count <= 0 || count > MAX_REGIONS) throw new IOException("检测缓存区域数量无效");
                List<Region> regions = new ArrayList<>(count); HashSet<String> ids = new HashSet<>(); int totalLines = 0;
                for (int i = 0; i < count; i++) {
                    CacheFiles.check(cancelled); String id = input.readUTF(); if (id.isEmpty() || id.length() > 256 || !ids.add(id)) throw new IOException("检测缓存区域标识无效");
                    Rect box = readRect(input, width, height); boolean vertical = input.readBoolean();
                    Rect context = input.readBoolean() ? readRect(input, width, height) : null;
                    int countLines = input.readInt(); if (countLines < 0 || countLines > MAX_LINES || (totalLines += countLines) > MAX_LINES) throw new IOException("检测缓存文字行数量无效");
                    List<Rect> lines = new ArrayList<>(countLines); for (int j = 0; j < countLines; j++) lines.add(readRect(input, width, height));
                    regions.add(new Region(id, box, lines, vertical, context));
                }
                if (input.available() != 0) throw new IOException("检测缓存尾部无效");
                CacheFiles.check(cancelled); file.setLastModified(System.currentTimeMillis()); return regions;
            }
        } catch (IOException invalid) { file.delete(); return null; }
    }
    public void write(String contentHash, String detectorId, int width, int height, List<Region> regions, BooleanSupplier cancelled) {
        CacheFiles.check(cancelled);
        // Zero regions may be a bad/partial page: let a later visit detect it again.
        if (regions == null || regions.size() > MAX_REGIONS) return;
        if (regions.isEmpty()) { file(contentHash, detectorId, width, height).delete(); return; }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); HashSet<String> ids = new HashSet<>(); int totalLines = 0;
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC); output.writeInt(width); output.writeInt(height); output.writeInt(regions.size());
                for (Region region : regions) {
                    CacheFiles.check(cancelled);
                    if (region == null || region.id == null || region.id.isEmpty() || region.id.length() > 256 || !ids.add(region.id) || (totalLines += region.lines.size()) > MAX_LINES) return;
                    output.writeUTF(region.id); writeRect(output, region.box, width, height); output.writeBoolean(region.vertical);
                    output.writeBoolean(region.contextBox != null); if (region.contextBox != null) writeRect(output, region.contextBox, width, height);
                    output.writeInt(region.lines.size()); for (Rect line : region.lines) writeRect(output, line, width, height);
                }
                output.flush(); if (bytes.size() + 32 > MAX_ENTRY) return;
                output.write(CacheFiles.digest().digest(bytes.toByteArray()));
            }
            CacheFiles.write(file(contentHash, detectorId, width, height), bytes.toByteArray(), 16L * 1024 * 1024, 1024, cancelled);
        } catch (IOException unavailable) { /* Detection succeeds even if the optional cache cannot be written. */ }
    }
    private static Rect readRect(DataInputStream input, int width, int height) throws IOException {
        Rect rect = new Rect(input.readInt(), input.readInt(), input.readInt(), input.readInt()); validate(rect, width, height); return rect;
    }
    private static void writeRect(DataOutputStream output, Rect rect, int width, int height) throws IOException {
        validate(rect, width, height); output.writeInt(rect.left); output.writeInt(rect.top); output.writeInt(rect.right); output.writeInt(rect.bottom);
    }
    private static void validate(Rect rect, int width, int height) throws IOException {
        if (rect == null || rect.left < 0 || rect.top < 0 || rect.right <= rect.left || rect.bottom <= rect.top || rect.right > width || rect.bottom > height) throw new IOException("检测缓存坐标越界");
    }
}
