package cn.local.manga;

/** Storage preparation never blocks Activity creation. */
public final class MangaApplication extends android.app.Application {
    @Override
    public void onCreate() {
        super.onCreate();
        StorageDatabase.start(
                this,
                () -> {
                    if (!StorageDatabase.problem.isEmpty()) {
                        String warning = StorageDatabase.problem;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(
                                        () ->
                                                android.widget.Toast.makeText(
                                                                this,
                                                                warning + "；请打开设置中的存储自检",
                                                                android.widget.Toast.LENGTH_LONG)
                                                        .show());
                    }
                });
    }
}
