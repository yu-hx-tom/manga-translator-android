package cn.local.manga;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * Runs the real native plan parser/matrix conversion on JDK. No Canvas or Bitmap methods execute.
 */
public final class CanvasCaptureChecks {
    static int checks;

    static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    static JSONObject draw() throws Exception {
        return new JSONObject()
                .put("url", "https://example.test/sheet.png")
                .put("sourceWidth", 1000)
                .put("sourceHeight", 1600)
                .put("sx", 100)
                .put("sy", 200)
                .put("sw", 300)
                .put("sh", 400)
                .put("dx", 10)
                .put("dy", 20)
                .put("dw", 150)
                .put("dh", 200)
                .put("matrix", new JSONArray(new double[] {1, 0, 0, 1, 0, 0}))
                .put("smoothing", false);
    }

    static JSONObject plan(JSONObject... operations) throws Exception {
        return new JSONObject()
                .put("kind", "canvas")
                .put("width", 800)
                .put("height", 1200)
                .put("ops", new JSONArray(Arrays.asList(operations)));
    }

    interface Fixture {
        JSONObject create() throws Exception;
    }

    static void rejects(Fixture fixture, String label) throws Exception {
        JSONObject value = fixture.create();
        boolean rejected = false;
        try {
            CanvasCapture.parse(value);
        } catch (Exception expected) {
            rejected = true;
        }
        ok(rejected, label);
    }

    public static void main(String[] args) throws Exception {
        CanvasCapture parsed = CanvasCapture.parse(plan(draw()));
        CanvasCapture.Draw op = parsed.operations.get(0);
        ok(
                parsed.width == 800 && parsed.height == 1200 && parsed.operations.size() == 1,
                "canvas output dimensions preserved");
        ok(
                op.sx == 100
                        && op.sy == 200
                        && op.sw == 300
                        && op.sh == 400
                        && op.dx == 10
                        && op.dy == 20
                        && op.dw == 150
                        && op.dh == 200,
                "source and destination crops keep distinct coordinates");
        ok(
                !op.smoothing && op.sourceWidth == 1000 && op.sourceHeight == 1600,
                "natural source geometry and nearest-neighbor request preserved");
        parsed =
                CanvasCapture.parse(
                        plan(
                                draw().put(
                                                "matrix",
                                                new JSONArray(
                                                        new double[] {0, 1, -1, 0, 20, 40}))));
        float[] matrix = parsed.operations.get(0).matrixValues();
        ok(
                Arrays.equals(matrix, new float[] {0, -1, 20, 1, 0, 40, 0, 0, 1}),
                "Canvas2D affine values map to Android row-major positions");
        ok(
                matrix[0] * 2 + matrix[1] * 3 + matrix[2] == 17
                        && matrix[3] * 2 + matrix[4] * 3 + matrix[5] == 42,
                "rotation plus translation transforms a known point correctly");
        parsed =
                CanvasCapture.parse(
                        plan(
                                draw().put(
                                                "matrix",
                                                new JSONArray(
                                                        new double[] {-1, 0, 0, 1, 300, 0}))));
        ok(
                parsed.operations.get(0).matrixValues()[0] == -1,
                "matrix reflection remains supported while negative crop sizes are rejected");
        parsed = CanvasCapture.parse(plan(draw().put("url", "data:image/png;base64,iVBORw0KGgo=")));
        ok(
                parsed.operations.get(0).url.startsWith("data:image/png"),
                "supported embedded raster can be planned without decoding");
        rejects(() -> null, "missing plan rejected");
        rejects(() -> plan(draw()).put("kind", "image"), "non-canvas plan rejected");
        rejects(
                () -> plan(draw()).put("unsupported", true),
                "unsupported WebGL or offscreen plans rejected");
        rejects(
                () -> plan(draw()).put("error", "incomplete recording"),
                "incomplete drawing history rejected");
        rejects(() -> plan(draw()).put("width", 0), "zero target dimension rejected");
        rejects(() -> plan(draw()).put("height", 1.5), "fractional target dimension rejected");
        rejects(() -> plan(draw()).put("width", 6001), "target long-side bound enforced");
        rejects(
                () -> plan(draw()).put("width", 4000).put("height", 2001),
                "eight-million-pixel target budget enforced");
        ok(
                CanvasCapture.parse(plan(draw()).put("width", 4000).put("height", 2000)).width
                        == 4000,
                "exact target pixel budget accepted");
        rejects(() -> plan(), "empty draw list rejected");
        JSONArray operations = new JSONArray();
        for (int i = 0; i < 512; i++) operations.put(draw());
        ok(
                CanvasCapture.parse(plan(draw()).put("ops", operations)).operations.size() == 512,
                "exact draw-operation budget accepted");
        operations.put(draw());
        rejects(() -> plan(draw()).put("ops", operations), "too many operations rejected");
        JSONArray sources = new JSONArray();
        for (int i = 0; i < 16; i++)
            sources.put(draw().put("url", "https://example.test/" + i + ".png"));
        ok(
                CanvasCapture.parse(plan(draw()).put("ops", sources)).operations.size() == 16,
                "sixteen distinct static sources accepted");
        sources.put(draw().put("url", "https://example.test/extra.png"));
        rejects(() -> plan(draw()).put("ops", sources), "too many distinct sources rejected");
        rejects(
                () -> plan(draw().put("url", "file:///sdcard/private.png")),
                "file URL cannot enter source loader");
        rejects(() -> plan(draw().put("url", "javascript:alert(1)")), "script URL rejected");
        rejects(
                () -> plan(draw().put("url", "data:text/html;base64,eA==")),
                "non-image data URL rejected");
        rejects(
                () -> plan(draw().put("sourceWidth", 0)),
                "invalid natural source dimension rejected");
        rejects(
                () -> plan(draw().put("sourceWidth", 100001)),
                "natural source dimension budget enforced");
        rejects(
                () -> plan(draw().put("matrix", new JSONArray(new int[] {1, 0, 0, 1, 0}))),
                "incomplete affine transform rejected");
        rejects(
                () ->
                        plan(
                                draw().put(
                                                "matrix",
                                                new JSONArray()
                                                        .put(1)
                                                        .put(0)
                                                        .put(0)
                                                        .put(1)
                                                        .put("NaN")
                                                        .put(0))),
                "nonfinite affine transform rejected");
        rejects(() -> plan(draw().put("dx", 1000001)), "extreme destination coordinate rejected");
        rejects(() -> plan(draw().put("sw", 0)), "zero source crop width rejected");
        rejects(() -> plan(draw().put("dh", -1)), "negative destination size rejected");
        rejects(() -> plan(draw().put("sx", -1)), "negative source crop origin rejected natively");
        rejects(
                () -> plan(draw().put("sx", 900)),
                "source crop outside natural image rejected natively");
        rejects(
                () -> plan(draw().put("sy", 1500)),
                "source crop below natural image rejected natively");
        rejects(
                () -> plan(draw().put("sw", 1e-40)),
                "subnormal crop size cannot overflow replay scale");
        rejects(
                () -> plan(draw(), draw().put("sourceWidth", 2000).put("sourceHeight", 3200)),
                "one source URL cannot silently change its natural coordinate system");
        JSONArray hugeSources = new JSONArray();
        String tail = "a".repeat(12 * 1024 * 1024 / 16);
        for (int i = 0; i < 16; i++)
            hugeSources.put(draw().put("url", "https://example.test/" + i + tail));
        rejects(
                () -> plan(draw()).put("ops", hugeSources),
                "aggregate unique source-address budget enforced");
        System.out.println(
                "CanvasCaptureChecks: "
                        + checks
                        + " checks passed (real parser and affine conversion; no Android raster"
                        + " replay)");
    }
}
