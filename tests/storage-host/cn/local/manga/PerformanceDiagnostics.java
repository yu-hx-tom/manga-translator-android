package cn.local.manga;

/**
 * Instrumentation is excluded from the storage contract under test. Image bytes remain synthetic.
 */
final class PerformanceDiagnostics {
    static boolean enabled() {
        return false;
    }

    static void written(String kind, long bytes) {}

    static void file(String kind, java.io.File file) {}

    static boolean compress(
            String kind,
            android.graphics.Bitmap bitmap,
            android.graphics.Bitmap.CompressFormat format,
            int quality,
            java.io.OutputStream out) {
        return bitmap.compress(format, quality, out);
    }
}
