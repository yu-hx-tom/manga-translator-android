package cn.local.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Invoke run(targetContext) from the project's instrumentation runner. No network or test framework. */
public final class DetectorChecks {
    private DetectorChecks() { }

    public static void run(Context targetContext) throws Exception {
        Bitmap plain = white(180, 140);
        try {
            List<Rect> adjacent = Arrays.asList(new Rect(40, 25, 58, 85), new Rect(68, 25, 86, 85));
            List<Region> merged = Detector.group(plain, adjacent);
            require(merged.size() == 1 && merged.get(0).lines.size() == 2, "adjacent columns");
            require(merged.get(0).vertical && merged.get(0).lines.get(0).left == 68,
                    "Japanese columns must be ordered right to left");
            require(merged.get(0).box.equals(new Rect(38, 23, 88, 87)), "original coordinates and padding");
            require(merged.get(0).id.equals("block_01"), "stable region IDs");
            require(Detector.group(plain, Arrays.asList(new Rect(40, 25, 58, 85),
                    new Rect(57, 30, 64, 55))).size() == 1, "furigana attaches");
            require(Detector.group(plain, Arrays.asList(new Rect(40, 20, 58, 62),
                    new Rect(40, 64, 58, 100))).size() == 1, "column fragments reconnect");
            require(Detector.group(plain, Arrays.asList(new Rect(10, 20, 28, 85),
                    new Rect(120, 20, 138, 85))).size() == 2, "open white background is not a shared bubble");
            require(Detector.group(plain, Arrays.asList(new Rect(10, 20, 75, 42),
                    new Rect(82, 20, 96, 75))).size() == 2, "art text and vertical dialogue stay separate");
            require(Detector.group(plain, Collections.emptyList()).isEmpty(), "empty input");
            Canvas canvas = new Canvas(plain);
            Paint black = new Paint(); black.setColor(Color.BLACK);
            canvas.drawRect(63, 10, 65, 106, black);
            require(Detector.group(plain, adjacent).size() == 2, "vertical panel border stops merging");
            plain.eraseColor(Color.WHITE);
            canvas.drawRect(10, 63, 141, 64, black);
            require(Detector.group(plain, Arrays.asList(new Rect(40, 20, 58, 62),
                    new Rect(40, 64, 58, 100))).size() == 2, "horizontal panel border stops merging");
        } finally { plain.recycle(); }

        Bitmap caption = white(500, 400);
        try {
            Canvas canvas = new Canvas(caption);
            Paint black = new Paint(); black.setColor(Color.BLACK);
            canvas.drawRect(10, 10, 161, 101, black);
            Paint white = new Paint(); white.setColor(Color.WHITE);
            canvas.drawRect(12, 12, 159, 99, white);
            List<Region> regions = Detector.group(caption, Arrays.asList(
                    new Rect(22, 20, 38, 75), new Rect(100, 20, 118, 75)));
            require(regions.size() == 1, "separated title in one enclosed white caption");
        } finally { caption.recycle(); }

        Bitmap blank = white(425, 602);
        try (Detector detector = new Detector(targetContext)) {
            require(detector.detect(blank).isEmpty(), "bundled ONNX model must not detect blank page text");
            Thread.currentThread().interrupt();
            try {
                detector.detect(blank);
                throw new AssertionError("interrupted detection must stop");
            } catch (InterruptedException expected) {
                require(Thread.currentThread().isInterrupted(), "interrupt flag is preserved");
            } finally { Thread.interrupted(); }
        } finally { blank.recycle(); }
    }

    private static Bitmap white(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        return bitmap;
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError("Detector: " + message);
    }
}
