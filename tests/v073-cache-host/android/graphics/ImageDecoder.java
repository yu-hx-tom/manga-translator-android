package android.graphics;
import java.nio.ByteBuffer;
/** ImageIO only allows testing downloader flow with real encoded fixtures, not Android decoder parity. */
public final class ImageDecoder {
    public static final int ALLOCATOR_SOFTWARE = 1;
    public static final class Source { final ByteBuffer bytes; Source(ByteBuffer bytes) { this.bytes = bytes; } }
    public static final class ImageInfo {
        private final android.util.Size size;
        ImageInfo(int width, int height) { size = new android.util.Size(width, height); }
        public android.util.Size getSize() { return size; }
    }
    public static final class DecodeException extends Exception {}
    public interface OnHeaderDecodedListener { void onHeaderDecoded(ImageDecoder decoder, ImageInfo info, Source source); }
    public interface OnPartialImageListener { boolean onPartialImage(DecodeException error); }
    public static Source createSource(ByteBuffer bytes) { return new Source(bytes); }
    public static Bitmap decodeBitmap(Source source, OnHeaderDecodedListener listener) throws java.io.IOException {
        byte[] bytes = new byte[source.bytes.remaining()]; source.bytes.get(bytes);
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (image == null) throw new java.io.IOException("invalid image");
        listener.onHeaderDecoded(new ImageDecoder(), new ImageInfo(image.getWidth(), image.getHeight()), source);
        return new Bitmap(image.getWidth(), image.getHeight(), image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    }
    public void setAllocator(int allocator) {}
    public void setTargetSize(int width, int height) {}
    public void setOnPartialImageListener(OnPartialImageListener listener) {}
}
