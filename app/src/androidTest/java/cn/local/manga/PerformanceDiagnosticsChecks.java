package cn.local.manga;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Compiled for the user-run test APK only; host checks do not execute Android Bitmap. */
final class PerformanceDiagnosticsChecks {
    static String run() throws Exception {
        int[] values = {0xff123456, 0xffabcdef, 0xff010203, 0xfffefdfc};
        Bitmap bitmap = Bitmap.createBitmap(values, 2, 2, Bitmap.Config.ARGB_8888), decoded = null;
        try {
            ByteBuffer expected = ByteBuffer.allocate(24).putInt(2).putInt(2);
            for (int value : values) expected.putInt(value);
            if (!PerformanceDiagnostics.pixelHash(bitmap)
                    .equals(PerformanceDiagnostics.sha(expected.array())))
                throw new AssertionError("ARGB hash byte order");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                throw new AssertionError("PNG encode");
            byte[] bytes = out.toByteArray();
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (!PerformanceDiagnostics.pixelHash(bitmap)
                    .equals(PerformanceDiagnostics.pixelHash(decoded)))
                throw new AssertionError("PNG readback hash");
            return "2 Android pixel checks";
        } finally {
            bitmap.recycle();
            if (decoded != null) decoded.recycle();
        }
    }
}
