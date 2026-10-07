package cn.local.manga;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide admission and cancellation for every translation entry point. */
final class TranslationTaskManager {
    enum State { IDLE, RUNNING, STOPPING, DONE }
    static volatile State state = State.IDLE;
    static volatile String owner = "", detail = "";
    private static Runnable cancel;
    private static Context app;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    static synchronized boolean begin(Context context, String key, Runnable stop) {
        if (running() || !CacheStorage.beginUse()) return false;
        app = context.getApplicationContext(); owner = key; cancel = stop; state = State.RUNNING; detail = "正在准备翻译…";
        try { app.startForegroundService(new Intent(app, TranslationService.class)); }
        catch (RuntimeException error) { CacheStorage.endUse();state = State.IDLE; owner = ""; cancel = null; detail = "无法启动后台翻译服务：" + error.getMessage(); changed(); return false; }
        changed(); return true;
    }
    static boolean running() { return state == State.RUNNING || state == State.STOPPING; }
    static synchronized void stop() {
        if (state != State.RUNNING) return;
        state = State.STOPPING; detail = "正在停止…已完成页将保留"; changed();
        Runnable action = cancel; if (action != null) MAIN.post(action);
    }
    static synchronized void stopping(String key){if(owner.equals(key)&&state==State.RUNNING){state=State.STOPPING;detail="正在停止…已完成页将保留";changed();}}
    static synchronized void progress(String key, String value) { if (owner.equals(key) && state == State.RUNNING) { detail = value; changed(); } }
    static synchronized void done(String key, String value) {
        if (!owner.equals(key) || !running()) return;
        state = State.DONE; cancel = null; detail = value;
        CacheStorage.endUse();
        if (app != null) app.stopService(new Intent(app, TranslationService.class));
        changed(); MAIN.postDelayed(() -> { synchronized (TranslationTaskManager.class) { if (state == State.DONE) { state = State.IDLE; detail = ""; changed(); } } }, 6000);
    }
    static void listen(Runnable listener) { listeners.addIfAbsent(listener); }
    static void unlisten(Runnable listener) { listeners.remove(listener); }
    private static void changed() { MAIN.post(() -> { for (Runnable listener : listeners) listener.run(); }); }
}
