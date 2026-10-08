package android.graphics;
/** Host fixture: exact pixel access and lifetime only; no Android image/rendering claims. */
public final class Bitmap {
    private final int width, height;
    private final int[] pixels;
    private boolean recycled;
    public Bitmap(int width, int height, int[] pixels) { this.width = width; this.height = height; this.pixels = pixels.clone(); }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public void getPixels(int[] target, int offset, int stride, int x, int y, int count, int rows) {
        if (recycled) throw new IllegalStateException("recycled");
        for (int row = 0; row < rows; row++) System.arraycopy(pixels, (y + row) * width + x, target, offset + row * stride, count);
    }
    public void recycle() { recycled = true; }
    public boolean isRecycled() { return recycled; }
}
