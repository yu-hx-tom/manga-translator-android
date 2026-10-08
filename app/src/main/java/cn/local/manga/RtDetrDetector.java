package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import ai.onnxruntime.*;

import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Bundled RT-DETR CPU inference. Own one instance under the engine's detector lock. */
public final class RtDetrDetector implements AutoCloseable {
    private static final int SIZE = 640, WORK_LIMIT = 1600;
    private static final Object FILE_LOCK = new Object();
    private final OrtEnvironment environment;
    private OrtSession session;
    private Bitmap inputBitmap;
    private int[] inputPixels;
    private FloatBuffer inputFloats;
    private boolean closed;

    public RtDetrDetector(Context context, String modelId) throws Exception {
        DetectorModels.Spec model = DetectorModels.get(modelId);
        check();
        File path = extractModel(context, model);
        check();
        environment = OrtEnvironment.getEnvironment();
        environment.setTelemetry(false);
        OrtSession candidate = null;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(
                    Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            candidate = environment.createSession(path.getAbsolutePath(), options);
            check();
            validate(candidate);
            session = candidate;
            candidate = null;
        } finally {
            if (candidate != null) candidate.close();
        }
    }

    private static File extractModel(Context context, DetectorModels.Spec spec) throws Exception {
        synchronized (FILE_LOCK) {
            check();
            File dir = new File(context.getFilesDir(), "models/rtdetr");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建本地检测模型目录");
            File target = new File(dir, new File(spec.asset).getName());
            if (validFile(target, spec)) return target;
            File temp = File.createTempFile("model-", ".part", dir);
            try {
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                long bytes = 0;
                try (InputStream in = context.getAssets().open(spec.asset);
                        FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        check();
                        bytes += n;
                        if (bytes > spec.bytes) throw new IOException("内置检测模型大小不符");
                        hash.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                    out.getFD().sync();
                }
                if (bytes != spec.bytes || !hex(hash.digest()).equals(spec.sha256))
                    throw new IOException("内置检测模型校验失败，请重新安装完整APK");
                check();
                android.system.Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
                return target;
            } finally {
                if (temp.exists()) temp.delete();
            }
        }
    }

    private static boolean validFile(File file, DetectorModels.Spec spec) throws Exception {
        if (!file.isFile() || file.length() != spec.bytes) return false;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) != -1) {
                check();
                hash.update(b, 0, n);
            }
        }
        return hex(hash.digest()).equals(spec.sha256);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    static void validate(OrtSession candidate) throws Exception {
        Map<String, NodeInfo> inputs = candidate.getInputInfo(),
                outputs = candidate.getOutputInfo();
        if (inputs.size() != 2 || outputs.size() != 3) throw new IOException("RT-DETR模型输入输出数量不兼容");
        tensor(inputs, "images", OnnxJavaType.FLOAT, 4, new long[] {1, 3, SIZE, SIZE});
        tensor(inputs, "orig_target_sizes", OnnxJavaType.INT64, 2, new long[] {1, 2});
        tensor(outputs, "labels", OnnxJavaType.INT64, 2, new long[] {1, 300});
        tensor(outputs, "boxes", OnnxJavaType.FLOAT, 3, new long[] {1, 300, 4});
        tensor(outputs, "scores", OnnxJavaType.FLOAT, 2, new long[] {1, 300});
    }

    private static void tensor(
            Map<String, NodeInfo> nodes, String name, OnnxJavaType type, int rank, long[] expected)
            throws IOException {
        NodeInfo node = nodes.get(name);
        if (node == null || !(node.getInfo() instanceof TensorInfo))
            throw new IOException("RT-DETR缺少张量：" + name);
        TensorInfo info = (TensorInfo) node.getInfo();
        long[] dims = info.getShape();
        if (info.type != type || dims.length != rank)
            throw new IOException("RT-DETR张量类型不兼容：" + name);
        for (int i = 0; i < rank; i++)
            if (dims[i] > 0 && dims[i] != expected[i])
                throw new IOException("RT-DETR张量尺寸不兼容：" + name);
    }

    public synchronized List<Region> detect(Bitmap bitmap) throws Exception {
        check();
        if (closed) throw new IllegalStateException("检测器已关闭");
        if (bitmap == null
                || bitmap.isRecycled()
                || bitmap.getWidth() < 4
                || bitmap.getHeight() < 4) throw new IllegalArgumentException("请选择有效的漫画图片");
        Bitmap readable = bitmap, work = null;
        try {
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (readable == null) throw new IOException("无法读取漫画像素");
            }
            int width = readable.getWidth(), height = readable.getHeight();
            boolean tall = (double) height / width > 3.5;
            double scale = Math.min(1d, (double) WORK_LIMIT / Math.max(width, height));
            // Keep narrow long-strip lettering legible; slice instead of squeezing its full height
            // into 640.
            if (tall) scale = Math.min(1d, 640d / width);
            int w = Math.max(4, (int) Math.round(width * scale)),
                    h = Math.max(4, (int) Math.round(height * scale));
            if ((long) w * h > 8_000_000L) throw new IOException("长条图片过大，请拆分后检测");
            work =
                    (w == width && h == height)
                            ? readable
                            : Bitmap.createScaledBitmap(readable, w, h, true);
            ArrayList<Long> labels = new ArrayList<>();
            ArrayList<float[]> boxes = new ArrayList<>();
            ArrayList<Float> scores = new ArrayList<>();
            for (int[] window : sliceWindows(w, h)) {
                check();
                infer(work, new Rect(0, window[0], w, window[1]), labels, boxes, scores);
            }
            check();
            int[] pixels = new int[w * h];
            work.getPixels(pixels, 0, w, 0, 0, w, h);
            long[] ls = new long[labels.size()];
            float[] ss = new float[scores.size()];
            for (int i = 0; i < ls.length; i++) {
                ls[i] = labels.get(i);
                ss[i] = scores.get(i);
            }
            List<Region> grouped =
                    RtDetrRegions.fromPredictions(
                            w, h, pixels, ls, boxes.toArray(new float[0][]), ss);
            check();
            ArrayList<Region> result = new ArrayList<>();
            for (Region region : grouped) {
                ArrayList<Rect> lines = new ArrayList<>();
                for (Rect line : region.lines) lines.add(map(line, width, height, w, h));
                result.add(
                        new Region(
                                region.id,
                                map(region.box, width, height, w, h),
                                lines,
                                region.vertical,
                                region.contextBox == null
                                        ? null
                                        : map(region.contextBox, width, height, w, h)));
            }
            return result;
        } finally {
            if (work != null && work != readable) work.recycle();
            if (readable != bitmap) readable.recycle();
        }
    }

    /** [top,bottom) windows; pure geometry is also checked on the desktop JVM. */
    static int[][] sliceWindows(int width, int height) {
        if (width < 4 || height < 4) throw new IllegalArgumentException("图片尺寸无效");
        int window =
                (double) height / width > 3.5
                        ? Math.min(height, Math.max(4, (int) Math.round(2.5 * width)))
                        : height;
        int step = Math.max(1, (int) Math.round(window * .8));
        ArrayList<int[]> result = new ArrayList<>();
        for (int top = 0; ; ) {
            check();
            int bottom = Math.min(height, top + window);
            result.add(new int[] {top, bottom});
            if (bottom == height) break;
            top = Math.min(top + step, height - window);
        }
        return result.toArray(new int[0][]);
    }

    private static Rect map(Rect r, int width, int height, int w, int h) {
        return new Rect(
                Math.max(0, (int) Math.floor((double) r.left * width / w)),
                Math.max(0, (int) Math.floor((double) r.top * height / h)),
                Math.min(width, (int) Math.ceil((double) r.right * width / w)),
                Math.min(height, (int) Math.ceil((double) r.bottom * height / h)));
    }

    private void infer(
            Bitmap source, Rect tile, List<Long> labels, List<float[]> boxes, List<Float> scores)
            throws Exception {
        if (inputBitmap == null)
            inputBitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        if (inputPixels == null) inputPixels = new int[SIZE * SIZE];
        if (inputFloats == null)
            inputFloats =
                    ByteBuffer.allocateDirect(3 * SIZE * SIZE * 4)
                            .order(ByteOrder.nativeOrder())
                            .asFloatBuffer();
        inputBitmap.eraseColor(android.graphics.Color.WHITE);
        Canvas canvas = new Canvas(inputBitmap);
        canvas.drawBitmap(
                source, tile, new Rect(0, 0, SIZE, SIZE), new Paint(Paint.FILTER_BITMAP_FLAG));
        inputBitmap.getPixels(inputPixels, 0, SIZE, 0, 0, SIZE, SIZE);
        inputFloats.clear();
        int count = SIZE * SIZE;
        for (int i = 0; i < count; i++) {
            if ((i & 8191) == 0) check();
            int p = inputPixels[i];
            inputFloats.put(i, ((p >>> 16) & 255) / 255f);
            inputFloats.put(count + i, ((p >>> 8) & 255) / 255f);
            inputFloats.put(2 * count + i, (p & 255) / 255f);
        }
        try (OnnxTensor image =
                        OnnxTensor.createTensor(
                                environment, inputFloats, new long[] {1, 3, SIZE, SIZE});
                OnnxTensor sizes =
                        OnnxTensor.createTensor(
                                environment, new long[][] {{tile.width(), tile.height()}});
                OrtSession.RunOptions options = new OrtSession.RunOptions()) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("images", image);
            inputs.put("orig_target_sizes", sizes);
            final Thread requester = Thread.currentThread();
            final Object guardLock = new Object();
            final boolean[] finished = {false};
            Thread guard =
                    new Thread(
                            () -> {
                                while (true) {
                                    synchronized (guardLock) {
                                        if (finished[0]) return;
                                        if (requester.isInterrupted()) {
                                            try {
                                                options.setTerminate(true);
                                            } catch (OrtException ignored) {
                                            }
                                            return;
                                        }
                                    }
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException end) {
                                        return;
                                    }
                                }
                            },
                            "rtdetr-cancel");
            guard.setDaemon(true);
            guard.start();
            try (OrtSession.Result outputs = session.run(inputs, options)) {
                check();
                Object l =
                        outputs.get("labels")
                                .orElseThrow(() -> new IOException("缺少labels输出"))
                                .getValue();
                Object b =
                        outputs.get("boxes")
                                .orElseThrow(() -> new IOException("缺少boxes输出"))
                                .getValue();
                Object s =
                        outputs.get("scores")
                                .orElseThrow(() -> new IOException("缺少scores输出"))
                                .getValue();
                if (!(l instanceof long[][])
                        || !(b instanceof float[][][])
                        || !(s instanceof float[][])) throw new IOException("RT-DETR输出数据类型不兼容");
                long[][] ls = (long[][]) l;
                float[][][] bs = (float[][][]) b;
                float[][] ss = (float[][]) s;
                if (ls.length != 1
                        || bs.length != 1
                        || ss.length != 1
                        || ls[0].length != bs[0].length
                        || ls[0].length != ss[0].length
                        || ls[0].length > 1000) throw new IOException("RT-DETR输出数量不兼容");
                for (int i = 0; i < ls[0].length; i++) {
                    float[] box = bs[0][i];
                    if (box.length != 4) throw new IOException("RT-DETR输出框不兼容");
                    if (!Float.isFinite(ss[0][i])) throw new IOException("RT-DETR输出分数无效");
                    for (float v : box)
                        if (!Float.isFinite(v)) throw new IOException("RT-DETR输出坐标无效");
                    if (ss[0][i] < .3f || ls[0][i] < 0 || ls[0][i] > 2) continue;
                    labels.add(ls[0][i]);
                    scores.add(ss[0][i]);
                    boxes.add(new float[] {box[0], box[1] + tile.top, box[2], box[3] + tile.top});
                }
            } catch (OrtException error) {
                check();
                throw error;
            } finally {
                synchronized (guardLock) {
                    finished[0] = true;
                }
                guard.interrupt();
            }
        }
    }

    private static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("已取消检测");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
            }
            session = null;
        }
        if (inputBitmap != null) {
            inputBitmap.recycle();
            inputBitmap = null;
        }
        inputPixels = null;
        inputFloats = null;
    }
}
