package cn.local.manga;

import ai.onnxruntime.*;

import org.json.*;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import javax.imageio.ImageIO;

/** Real desktop ORT 1.22 smoke, not Android runtime or a performance benchmark. */
public final class RtDetrRuntimeChecks {
    private static int checks;

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path models = Paths.get(args[0]), source = Paths.get(args[1]), report = Paths.get(args[2]);
        BufferedImage original = ImageIO.read(source.toFile()),
                small = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, 640, 640, null);
        g.dispose();
        float[] rgb = new float[3 * 640 * 640];
        for (int y = 0; y < 640; y++)
            for (int x = 0; x < 640; x++) {
                int p = small.getRGB(x, y), i = y * 640 + x;
                rgb[i] = ((p >>> 16) & 255) / 255f;
                rgb[640 * 640 + i] = ((p >>> 8) & 255) / 255f;
                rgb[2 * 640 * 640 + i] = (p & 255) / 255f;
            }
        require(DetectorModels.DEFAULT_ID.equals("rtdetr_r50_int8"), "default model");
        require(!DetectorModels.isValid("unknown"), "unknown rejected");
        try {
            DetectorModels.get("unknown");
            throw new AssertionError("silent fallback");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        require(
                RtDetrDetector.sliceWindows(480, 640).length == 1,
                "ordinary page remains one input");
        int[][] windows = RtDetrDetector.sliceWindows(480, 10000);
        int covered = 0;
        for (int[] window : windows) {
            require(
                    window[0] <= covered && window[1] > covered && window[1] - window[0] <= 1200,
                    "strip overlap and forward progress");
            covered = window[1];
        }
        require(covered == 10000, "strip bottom not lost");
        Thread.currentThread().interrupt();
        try {
            RtDetrDetector.sliceWindows(480, 10000);
            throw new AssertionError("cancelled slicing");
        } catch (java.util.concurrent.CancellationException expected) {
            checks++;
        } finally {
            Thread.interrupted();
        }
        JSONArray tested = new JSONArray();
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        environment.setTelemetry(false);
        require(
                environment.getVersion().startsWith("1.22."),
                "test must use Android-matching ORT 1.22");
        for (String id : DetectorModels.IDS) {
            if (DetectorModels.PP_ID.equals(id)) continue;
            DetectorModels.Spec spec = DetectorModels.get(id);
            Path model = models.resolve(Paths.get(spec.asset).getFileName());
            require(Files.size(model) == spec.bytes, "model size " + id);
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(model)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) != -1) hash.update(b, 0, n);
            }
            StringBuilder digest = new StringBuilder();
            for (byte b : hash.digest()) digest.append(String.format(Locale.ROOT, "%02x", b & 255));
            require(spec.sha256.equals(digest.toString()), "model hash " + id);
            int accepted = 0;
            JSONArray predictions = new JSONArray();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(4);
                options.setInterOpNumThreads(1);
                try (OrtSession session = environment.createSession(model.toString(), options)) {
                    RtDetrDetector.validate(session);
                    checks++;
                    try (OnnxTensor image =
                                    OnnxTensor.createTensor(
                                            environment,
                                            FloatBuffer.wrap(rgb),
                                            new long[] {1, 3, 640, 640});
                            OnnxTensor size =
                                    OnnxTensor.createTensor(
                                            environment,
                                            new long[][] {
                                                {original.getWidth(), original.getHeight()}
                                            })) {
                        Map<String, OnnxTensor> inputs = new HashMap<>();
                        inputs.put("images", image);
                        inputs.put("orig_target_sizes", size);
                        try (OrtSession.Result out = session.run(inputs)) {
                            long[][] labels = (long[][]) out.get("labels").get().getValue();
                            float[][][] boxes = (float[][][]) out.get("boxes").get().getValue();
                            float[][] scores = (float[][]) out.get("scores").get().getValue();
                            require(
                                    labels.length == 1
                                            && labels[0].length == 300
                                            && boxes[0].length == 300
                                            && scores[0].length == 300,
                                    "all output contracts " + id);
                            for (int i = 0; i < 300; i++)
                                if (scores[0][i] >= .3f) {
                                    accepted++;
                                    require(labels[0][i] >= 0 && labels[0][i] <= 2, "label");
                                    float[] b = boxes[0][i];
                                    require(
                                            b.length == 4
                                                    && Float.isFinite(b[0])
                                                    && Float.isFinite(b[1])
                                                    && Float.isFinite(b[2])
                                                    && Float.isFinite(b[3])
                                                    && b[2] >= b[0]
                                                    && b[3] >= b[1],
                                            "finite xyxy");
                                    predictions.put(
                                            new JSONObject()
                                                    .put("label", labels[0][i])
                                                    .put("score", scores[0][i])
                                                    .put("box", new JSONArray(b)));
                                }
                            require(accepted > 0, "real Japanese sample detected " + id);
                        }
                        try (OrtSession.RunOptions run = new OrtSession.RunOptions()) {
                            run.setTerminate(true);
                            try (OrtSession.Result ignored = session.run(inputs, run)) {
                                throw new AssertionError("terminated run succeeded");
                            } catch (OrtException expected) {
                                checks++;
                            }
                        }
                    }
                }
            }
            tested.put(
                    new JSONObject()
                            .put("id", id)
                            .put("sha256", spec.sha256)
                            .put("acceptedCandidates", accepted)
                            .put("predictions", predictions)
                            .put("sessionClosedBeforeNextModel", true));
        }
        JSONObject out =
                new JSONObject()
                        .put("passed", true)
                        .put("checksPassed", checks)
                        .put("runtime", environment.getVersion())
                        .put(
                                "scope",
                                "Windows Java ORT real model single-image smoke; Java2D bilinear"
                                    + " preprocessing, not Android Canvas or mobile performance")
                        .put("androidRuntimeVerified", false)
                        .put("paidApiUsed", false)
                        .put("source", source.toString())
                        .put("models", tested);
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(2));
        System.out.println(checks + " checks passed");
    }
}
