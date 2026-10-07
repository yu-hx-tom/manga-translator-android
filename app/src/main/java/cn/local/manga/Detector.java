package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/** Offline PP-OCRv6 detection and geometry/white-caption paragraph grouping. */
public final class Detector implements AutoCloseable {
    private static final int INFERENCE_LIMIT = 960;
    private static final int GROUPING_LIMIT = 1600;
    private static final int MAX_LINES = 384;
    private static final Comparator<Rect> PAGE_ORDER =
            Comparator.comparingInt((Rect r) -> r.top).thenComparingInt(r -> -r.left);
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final int inputWidth, inputHeight;
    private boolean closed;
    private FloatBuffer inputFloats;
    private Bitmap inputBitmap;
    private int[] inputPixels;

    public Detector(Context context) throws Exception {
        environment = OrtEnvironment.getEnvironment();
        environment.setTelemetry(false);
        byte[] model;
        try (InputStream input = context.getAssets().open("detector.onnx");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[65536];
            int n;
            while ((n = input.read(chunk)) != -1) {
                checkInterrupted();
                if (bytes.size() + n > 32 * 1024 * 1024)
                    throw new IOException("离线检测模型过大或已损坏");
                bytes.write(chunk, 0, n);
            }
            model = bytes.toByteArray();
        }
        OrtSession candidate;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            options.setInterOpNumThreads(1);
            candidate = environment.createSession(model, options);
        }
        try {
            if (candidate.getInputInfo().size() != 1)
                throw new IOException("离线检测模型输入数量不兼容");
            Map.Entry<String, NodeInfo> entry = candidate.getInputInfo().entrySet().iterator().next();
            if (!(entry.getValue().getInfo() instanceof TensorInfo))
                throw new IOException("离线检测模型输入类型不兼容");
            long[] shape = ((TensorInfo) entry.getValue().getInfo()).getShape();
            if (shape.length != 4 || (shape[0] > 0 && shape[0] != 1)
                    || (shape[1] > 0 && shape[1] != 3))
                throw new IOException("离线检测模型需要 NCHW 彩色图像输入");
            inputHeight = modelDimension(shape[2]);
            inputWidth = modelDimension(shape[3]);
            inputName = entry.getKey();
            session = candidate;
        } catch (Exception error) {
            candidate.close();
            throw error;
        }
    }

    private static int modelDimension(long dimension) throws IOException {
        if (dimension <= 0) return INFERENCE_LIMIT;
        if (dimension > INFERENCE_LIMIT || dimension < 32)
            throw new IOException("模型固定尺寸超出手机检测限制（32—960）");
        return (int) dimension;
    }

    /** Coordinates are original bitmap pixels, with exclusive right and bottom edges. */
    public synchronized List<Region> detect(Bitmap bitmap) throws Exception {
        if (closed) throw new IllegalStateException("检测器已关闭");
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() < 4 || bitmap.getHeight() < 4)
            throw new IllegalArgumentException("请选择有效的漫画图片");
        checkInterrupted();
        if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap software = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) throw new IOException("无法读取图片像素，请改用截图测试");
            try { return detectPixels(software); } finally { software.recycle(); }
        }
        return detectPixels(bitmap);
    }

    private List<Region> detectPixels(Bitmap bitmap) throws Exception {
        List<Rect> lines = detectLines(bitmap);
        for (Rect tile : supplementTiles(bitmap.getWidth(), bitmap.getHeight())) {
            checkInterrupted();
            Bitmap crop = Bitmap.createBitmap(bitmap, tile.left, tile.top, tile.width(), tile.height());
            try { addSupplementalLines(lines, detectLines(crop), tile, bitmap.getWidth(), bitmap.getHeight()); }
            finally { if (crop != bitmap) crop.recycle(); }
        }
        lines.sort(PAGE_ORDER);
        return group(bitmap, lines);
    }

    private List<Rect> detectLines(Bitmap bitmap) throws Exception {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        double scale = Math.min((double) inputWidth / width, (double) inputHeight / height);
        int resizedWidth = Math.max(1, (int) (width * scale));
        int resizedHeight = Math.max(1, (int) (height * scale));
        int offsetX = (inputWidth - resizedWidth) / 2;
        int offsetY = (inputHeight - resizedHeight) / 2;
        int pixelsCount = inputWidth * inputHeight;
        if(inputBitmap==null)inputBitmap=Bitmap.createBitmap(inputWidth,inputHeight,Bitmap.Config.ARGB_8888);
        if(inputPixels==null)inputPixels=new int[pixelsCount];
        Bitmap input=inputBitmap;
        // detect() is synchronized; the previous OnnxTensor is closed before this buffer is reused.
        if(inputFloats==null)inputFloats=ByteBuffer.allocateDirect(3*pixelsCount*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        FloatBuffer floats=inputFloats;floats.clear();
        {
            Canvas canvas = new Canvas(input);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(bitmap, null,
                    new Rect(offsetX, offsetY, offsetX + resizedWidth, offsetY + resizedHeight),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            input.getPixels(inputPixels,0,inputWidth,0,0,inputWidth,inputHeight);
            for (int y = 0; y < inputHeight; y++) {
                checkInterrupted();

                for (int x = 0; x < inputWidth; x++) {
                    int p = y * inputWidth + x, color = inputPixels[p];
                    // Same BGR channel order and normalization as the tested desktop model.
                    floats.put(p, ((color & 255) / 255f - .485f) / .229f);
                    floats.put(p + pixelsCount, (((color >>> 8) & 255) / 255f - .456f) / .224f);
                    floats.put(p + 2 * pixelsCount, (((color >>> 16) & 255) / 255f - .406f) / .225f);
                }
            }
        }
        List<Rect> lines;
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, floats,
                new long[]{1, 3, inputHeight, inputWidth});
             OrtSession.Result output = session.run(Collections.singletonMap(inputName, tensor))) {
            checkInterrupted();
            if (!(output.get(0) instanceof OnnxTensor))
                throw new IOException("离线检测模型输出不是图像概率图");
            OnnxTensor result = (OnnxTensor) output.get(0);
            long[] shape = result.getInfo().getShape();
            if (shape.length < 2 || shape.length > 4)
                throw new IOException("离线检测模型输出维度不兼容");
            for (int i = 0; i < shape.length - 2; i++) {
                if (shape[i] != 1) throw new IOException("离线检测模型输出通道不兼容");
            }
            int h = (int) shape[shape.length - 2], w = (int) shape[shape.length - 1];
            if (w < 1 || h < 1 || w > INFERENCE_LIMIT || h > INFERENCE_LIMIT)
                throw new IOException("离线检测概率图尺寸不兼容");
            FloatBuffer probabilities = result.getFloatBuffer();
            if (probabilities == null || probabilities.remaining() != w * h)
                throw new IOException("离线检测模型输出数据不兼容");
            lines = extractLines(probabilities, w, h, width, height,
                    inputWidth, inputHeight, resizedWidth, resizedHeight, offsetX, offsetY);
        }
        return lines;
    }

    static List<Rect> supplementTiles(int width, int height) {
        int tw = Math.min(width, (int) Math.ceil(width * .60));
        int th = Math.min(height, (int) Math.ceil(height * .60));
        List<Rect> tiles = new ArrayList<>();
        for (int y : new int[]{0, height - th}) for (int x : new int[]{0, width - tw})
            tiles.add(new Rect(x, y, x + tw, y + th));
        return tiles;
    }

    static void addSupplementalLines(List<Rect> existing, List<Rect> local,
            Rect tile, int pageWidth, int pageHeight) throws IOException {
        int margin = Math.max(2, (int) Math.ceil(Math.min(tile.width(), tile.height()) * .025));
        List<Rect> candidates = new ArrayList<>();
        for (Rect line : local) {
            // A truncated line must be detected in another overlapping tile, never stitched by guesswork.
            if ((tile.left > 0 && line.left < margin)
                    || (tile.top > 0 && line.top < margin)
                    || (tile.right < pageWidth && line.right > tile.width() - margin)
                    || (tile.bottom < pageHeight && line.bottom > tile.height() - margin)) continue;
            Rect mapped = new Rect(line);
            mapped.offset(tile.left, tile.top);
            candidates.add(mapped);
        }
        candidates.sort(Comparator.comparingInt((Rect r) -> -r.width() * r.height()));
        for (Rect candidate : candidates) {
            boolean duplicate = false;
            for (Rect prior : existing) {
                int iw = Math.max(0, Math.min(prior.right, candidate.right) - Math.max(prior.left, candidate.left));
                int ih = Math.max(0, Math.min(prior.bottom, candidate.bottom) - Math.max(prior.top, candidate.top));
                double intersection = (double) iw * ih;
                double a = (double) prior.width() * prior.height(), b = (double) candidate.width() * candidate.height();
                if (intersection / (a + b - intersection) >= .35
                        || intersection / b >= .70) { duplicate = true; break; }
            }
            if (!duplicate) {
                existing.add(candidate);
                if (existing.size() > MAX_LINES) throw new IOException("补检文字候选超过 384 个，请分段处理");
            }
        }
    }

    private static List<Rect> extractLines(FloatBuffer p, int w, int h, int originalWidth,
            int originalHeight, int tensorWidth, int tensorHeight, int resizedWidth,
            int resizedHeight, int offsetX, int offsetY) throws InterruptedException, IOException {
        boolean[] visited = new boolean[w * h];
        int[] queue = new int[w * h];
        List<Rect> lines = new ArrayList<>();
        double sx = (double) resizedWidth / originalWidth, sy = (double) resizedHeight / originalHeight;
        for (int start = 0; start < visited.length; start++) {
            if ((start & 4095) == 0) checkInterrupted();
            if (visited[start] || !(p.get(start) > .2f)) continue;
            visited[start] = true;
            queue[0] = start;
            int head = 0, tail = 1, minX = start % w, maxX = minX, minY = start / w, maxY = minY;
            double total = 0;
            while (head < tail) {
                if ((head & 4095) == 0) checkInterrupted();
                int index = queue[head++], x = index % w, y = index / w;
                total += p.get(index);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++) {
                    for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++) {
                        int n = yy * w + xx;
                        if (!visited[n] && p.get(n) > .2f) {
                            visited[n] = true;
                            queue[tail++] = n;
                        }
                    }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (tail < 3 || Math.min(bw, bh) < 3 || total / tail < .45) continue;
            double pad = (double) bw * bh / (2 * (bw + bh)) * 1.4;
            Rect box = new Rect(
                    clamp((int) Math.floor(((minX - pad) * tensorWidth / w - offsetX) / sx), 0, originalWidth),
                    clamp((int) Math.floor(((minY - pad) * tensorHeight / h - offsetY) / sy), 0, originalHeight),
                    clamp((int) Math.ceil(((maxX + 1 + pad) * tensorWidth / w - offsetX) / sx), 0, originalWidth),
                    clamp((int) Math.ceil(((maxY + 1 + pad) * tensorHeight / h - offsetY) / sy), 0, originalHeight));
            if (box.width() <= 3 || box.height() <= 3) continue;
            lines.add(box);
            if (lines.size() > MAX_LINES)
                throw new IOException("本页文字候选超过 384 个，请分段截屏或选取较短的漫画页");
        }
        lines.sort(PAGE_ORDER);
        return lines;
    }

    /** Package-visible for synthetic grouping tests; never reads cached page coordinates. */
    static List<Region> group(Bitmap source, List<Rect> originalLines) throws InterruptedException {
        if (originalLines.isEmpty()) return new ArrayList<>();
        if (originalLines.size() > MAX_LINES) throw new IllegalArgumentException("文字候选过多");
        checkInterrupted();
        // ponytail: cap grouping at 1600 pixels; tile long strips if real phone tests need more detail.
        double scale = Math.min(1, (double) GROUPING_LIMIT / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) (source.getWidth() * scale));
        int height = Math.max(1, (int) (source.getHeight() * scale));
        int count = width * height;
        byte[] gray = new byte[count];
        Bitmap working = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(working);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(source, null, new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                checkInterrupted();
                working.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    int color = row[x];
                    gray[y * width + x] = (byte) ((((color >>> 16) & 255) * 299
                            + ((color >>> 8) & 255) * 587 + (color & 255) * 114 + 500) / 1000);
                }
            }
        } finally {
            working.recycle();
        }
        List<Rect> lines = new ArrayList<>();
        for (Rect line : originalLines) lines.add(new Rect(
                clamp((int) Math.floor((double) line.left * width / source.getWidth()), 0, width - 1),
                clamp((int) Math.floor((double) line.top * height / source.getHeight()), 0, height - 1),
                clamp((int) Math.ceil((double) line.right * width / source.getWidth()), 1, width),
                clamp((int) Math.ceil((double) line.bottom * height / source.getHeight()), 1, height)));
        boolean[] protectedLines = longLines(gray, width, height);
        int[] membership = whiteMembership(gray, protectedLines, width, height, lines);
        int[] parent = new int[lines.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        // ponytail: at most 384 candidates, so bounded O(n²) grouping needs no spatial index.
        for (int i = 0; i < lines.size(); i++) {
            checkInterrupted();
            for (int j = 0; j < i; j++) {
                if (barrier(lines.get(i), lines.get(j), protectedLines, width)) continue;
                if (membership[i] != 0 && membership[j] != 0 && membership[i] != membership[j]) continue;
                if (pairMatches(lines.get(i), lines.get(j))) connect(parent, i, j);
            }
        }
        Map<Integer, List<Integer>> enclosed = new LinkedHashMap<>();
        for (int i = 0; i < membership.length; i++) {
            if (membership[i] != 0) enclosed.computeIfAbsent(membership[i], k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> members : enclosed.values()) {
            checkInterrupted();
            if (members.size() < 2) continue;
            int first = members.get(0);
            for (int member : members)
                if (!barrier(lines.get(first), lines.get(member), protectedLines, width)) connect(parent, first, member);
        }
        Map<Integer, List<Integer>> buckets = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) buckets.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        List<Region> result = new ArrayList<>();
        for (List<Integer> members : buckets.values()) {
            Rect bounds = union(originalLines, members);
            int verticalCount = 0;
            for (int member : members) {
                Rect b = originalLines.get(member);
                if (b.height() > b.width() * 1.3) verticalCount++;
            }
            boolean vertical = verticalCount * 2 >= members.size();
            List<Rect> ordered = new ArrayList<>();
            for (int member : members) ordered.add(new Rect(originalLines.get(member)));
            ordered.sort(vertical ? Comparator.comparingInt((Rect r) -> -r.left).thenComparingInt(r -> r.top)
                    : Comparator.comparingInt((Rect r) -> r.top).thenComparingInt(r -> r.left));
            List<Integer> characterWidths=new ArrayList<>();for(Rect line:ordered)characterWidths.add(Math.min(line.width(),line.height()));
            Collections.sort(characterWidths);
            // Keep API crops focused on text. Rendering independently expands its original-image ROI to find the boundary.
            int padding=2; // API crop hugs the union of detected lines; background ROI is independent.
            bounds.inset(-padding,-padding);
            bounds.intersect(0, 0, source.getWidth(), source.getHeight());
            result.add(new Region("", bounds, ordered, vertical));
        }
        result.sort(Comparator.comparing((Region r) -> r.box, PAGE_ORDER));
        List<Region> named = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            Region region = result.get(i);
            named.add(new Region(String.format(Locale.ROOT, "block_%02d", i + 1),
                    region.box, region.lines, region.vertical));
        }
        return named;
    }

    private static boolean[] longLines(byte[] gray, int w, int h) throws InterruptedException {
        boolean[] protect = new boolean[gray.length];
        int minimum = Math.max(24, (int) Math.round(Math.min(w, h) * .06));
        for (int y = 0; y < h; y++) {
            checkInterrupted();
            int start = -1;
            for (int x = 0; x <= w; x++) {
                if (x < w && (gray[y * w + x] & 255) < 125) { if (start < 0) start = x; }
                else if (start >= 0) {
                    if (x - start >= minimum) Arrays.fill(protect, y * w + start, y * w + x, true);
                    start = -1;
                }
            }
        }
        for (int x = 0; x < w; x++) {
            checkInterrupted();
            int start = -1;
            for (int y = 0; y <= h; y++) {
                if (y < h && (gray[y * w + x] & 255) < 125) { if (start < 0) start = y; }
                else if (start >= 0) {
                    if (y - start >= minimum) for (int yy = start; yy < y; yy++) protect[yy * w + x] = true;
                    start = -1;
                }
            }
        }
        return protect;
    }

    private static int[] whiteMembership(byte[] gray, boolean[] protect, int w, int h,
            List<Rect> lines) throws InterruptedException {
        boolean[] white = new boolean[gray.length];
        for (int i = 0; i < gray.length; i++) white[i] = (gray[i] & 255) > 215;
        for (int i = 0; i < gray.length; i++) if (protect[i]) white[i] = false;
        int[] labels = new int[gray.length], queue = new int[gray.length];
        int validLabels = 0;
        for (int start = 0; start < gray.length; start++) {
            if ((start & 4095) == 0) checkInterrupted();
            if (!white[start] || labels[start] != 0) continue;
            int label = validLabels + 1, head = 0, tail = 1;
            queue[0] = start; labels[start] = label;
            boolean touches = false;
            while (head < tail) {
                if ((head & 4095) == 0) checkInterrupted();
                int index = queue[head++], x = index % w, y = index / w;
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches = true;
                if (x > 0) tail = enqueue(index - 1, label, tail, white, labels, queue);
                if (x < w - 1) tail = enqueue(index + 1, label, tail, white, labels, queue);
                if (y > 0) tail = enqueue(index - w, label, tail, white, labels, queue);
                if (y < h - 1) tail = enqueue(index + w, label, tail, white, labels, queue);
            }
            if (touches || tail <= 30 || tail >= gray.length * .65) {
                for (int i = 0; i < tail; i++) labels[queue[i]] = -1;
            } else validLabels++;
        }
        int[] membership = new int[lines.size()], counts = new int[validLabels + 1];
        for (int i = 0; i < lines.size(); i++) {
            checkInterrupted();
            Arrays.fill(counts, 0);
            Rect r = lines.get(i);
            int largest = 0;
            for (int y = r.top; y < r.bottom; y++) for (int x = r.left; x < r.right; x++) {
                int label = labels[y * w + x];
                if (label > 0) { counts[label]++; if (counts[label] > counts[largest]) largest = label; }
            }
            if ((double) counts[largest] / (r.width() * r.height()) > .30) membership[i] = largest;
        }
        return membership;
    }

    private static int enqueue(int index, int label, int tail, boolean[] white, int[] labels, int[] queue) {
        if (white[index] && labels[index] == 0) { labels[index] = label; queue[tail++] = index; }
        return tail;
    }

    private static boolean barrier(Rect a, Rect b, boolean[] protect, int w) {
        int y1 = Math.max(a.top, b.top), y2 = Math.min(a.bottom, b.bottom);
        int x1 = (int) Math.rint(Math.min(a.exactCenterX(), b.exactCenterX()));
        int x2 = (int) Math.rint(Math.max(a.exactCenterX(), b.exactCenterX()));
        if (y2 - y1 >= 12 && x2 > x1) {
            for (int x = x1; x < x2; x++) {
                int n = 0;
                for (int y = y1; y < y2; y++) if (protect[y * w + x]) n++;
                if (n > (y2 - y1) * .6) return true;
            }
        }
        x1 = Math.max(a.left, b.left); x2 = Math.min(a.right, b.right);
        y1 = (int) Math.rint(Math.min(a.exactCenterY(), b.exactCenterY()));
        y2 = (int) Math.rint(Math.max(a.exactCenterY(), b.exactCenterY()));
        if (x2 - x1 >= 10 && y2 > y1) {
            for (int y = y1; y < y2; y++) {
                int n = 0;
                for (int x = x1; x < x2; x++) if (protect[y * w + x]) n++;
                if (n > (x2 - x1) * .6) return true;
            }
        }
        return false;
    }

    private static boolean pairMatches(Rect a, Rect b) {
        double aw = a.width(), ah = a.height(), bw = b.width(), bh = b.height();
        double ox = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        double oy = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        double gx = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right));
        double gy = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom));
        boolean va = ah > aw * 1.3, vb = bh > bw * 1.3;
        if (ox * oy / Math.min(aw * ah, bw * bh) > .7) return true;
        if (va && vb) {
            if (Math.min(aw, bw) < Math.max(aw, bw) * .65 && oy / Math.min(ah, bh) > .75
                    && gx < Math.max(aw, bw) * .3) return true;
            if (Math.max(aw, bw) / Math.min(aw, bw) < 1.9 && gx <= Math.min(aw, bw) * .85
                    && oy / Math.min(ah, bh) > .55 && Math.abs(a.top - b.top) <= Math.max(6, Math.min(ah, bh) * .45)) return true;
            return ox / Math.min(aw, bw) > .7 && gy <= Math.min(aw, bw) * .22
                    && Math.max(aw, bw) / Math.min(aw, bw) < 1.6;
        }
        return !va && !vb && aw > ah * 1.6 && bw > bh * 1.6 && gy <= Math.min(ah, bh) * .65
                && ox / Math.min(aw, bw) > .65 && Math.abs(a.left - b.left) < Math.max(ah, bh);
    }

    private static Rect union(List<Rect> lines, List<Integer> members) {
        Rect result = new Rect(lines.get(members.get(0)));
        for (int index : members) result.union(lines.get(index));
        return result;
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) { parent[index] = parent[parent[index]]; index = parent[index]; }
        return index;
    }

    private static void connect(int[] parent, int a, int b) { parent[find(parent, b)] = find(parent, a); }
    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("检测已取消");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        inputFloats=null;inputPixels=null;
        if(inputBitmap!=null){inputBitmap.recycle();inputBitmap=null;}
        try { session.close(); } catch (OrtException error) { Log.w("MangaDetector", "Closing detector", error); }
    }
}
