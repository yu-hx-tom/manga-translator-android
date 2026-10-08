package android.content;

/** Host-only private filesystem context, never packaged into the APK. */
public class Context {
    private final java.io.File root;

    public Context(java.io.File root) {
        this.root = root;
    }

    public Context getApplicationContext() {
        return this;
    }

    public java.io.File getCacheDir() {
        java.io.File f = new java.io.File(root, "cache");
        f.mkdirs();
        return f;
    }

    public java.io.File getFilesDir() {
        java.io.File f = new java.io.File(root, "files");
        f.mkdirs();
        return f;
    }
}
