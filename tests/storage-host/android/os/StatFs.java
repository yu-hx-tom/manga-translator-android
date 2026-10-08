package android.os;

public class StatFs {
    private final java.io.File file;
    public static long availableOverride = -1;

    public StatFs(String path) {
        file = new java.io.File(path);
    }

    public long getAvailableBytes() {
        return availableOverride >= 0 ? availableOverride : file.getUsableSpace();
    }
}
