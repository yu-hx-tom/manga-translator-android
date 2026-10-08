package android.graphics;

import java.io.ByteArrayInputStream;
import java.io.File;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Metadata-only host adapter. Fails if network code tries to allocate decoded pixels. */
public final class BitmapFactory {
    public static int boundsReads, pixelDecodes;

    public static final class Options {
        public boolean inJustDecodeBounds;
        public int outWidth = -1, outHeight = -1;
        public String outMimeType;
    }

    public static Bitmap decodeFile(String path, Options options) {
        return inspect(new File(path), options);
    }

    public static Bitmap decodeByteArray(byte[] value, int start, int size, Options options) {
        return inspect(new ByteArrayInputStream(value, start, size), options);
    }

    public static Bitmap decodeByteArray(byte[] value, int start, int size) {
        pixelDecodes++;
        throw new AssertionError("Pixel decode ran during cleanup network phase");
    }

    private static Bitmap inspect(Object source, Options options) {
        if (options == null || !options.inJustDecodeBounds) {
            pixelDecodes++;
            throw new AssertionError("Pixel decode ran during cleanup network phase");
        }
        boundsReads++;
        options.outWidth = options.outHeight = -1;
        options.outMimeType = null;
        try (ImageInputStream stream = ImageIO.createImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                options.outWidth = reader.getWidth(0);
                options.outHeight = reader.getHeight(0);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                options.outMimeType = "image/" + (format.equals("jpg") ? "jpeg" : format);
            } finally {
                reader.dispose();
            }
        } catch (Exception invalid) {
            options.outWidth = options.outHeight = -1;
            options.outMimeType = null;
        }
        return null;
    }
}
