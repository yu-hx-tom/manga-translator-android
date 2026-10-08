package android.graphics;

/** Host-only coordinate adapter for production Region metadata; no Bitmap/UI emulation. */
public class Rect {
    public int left, top, right, bottom;

    public Rect(int l, int t, int r, int b) {
        left = l;
        top = t;
        right = r;
        bottom = b;
    }

    public Rect(Rect r) {
        this(r.left, r.top, r.right, r.bottom);
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }
}
