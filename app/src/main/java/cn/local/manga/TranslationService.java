package cn.local.manga;

import android.app.*;
import android.content.Intent;
import android.os.*;

/** Keeps admitted translation work alive when tabs change or the screen sleeps. */
public final class TranslationService extends Service {
    private PowerManager.WakeLock wake;
    private final Runnable update = this::notifyProgress;
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("translation", "漫画翻译进度", NotificationManager.IMPORTANCE_LOW));
        startForeground(41, notification());
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "manga:translation");
        wake.acquire(6 * 60 * 60 * 1000L);
        TranslationTaskManager.listen(update);
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TranslationService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "translation").setSmallIcon(R.drawable.ic_stat_translate).setColor(Ui.ACCENT).setContentTitle("漫游浏览器 · 翻译中")
                .setContentText(TranslationTaskManager.detail).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止翻译", stop).build()).build();
    }
    private void notifyProgress() { if (TranslationTaskManager.running()) getSystemService(NotificationManager.class).notify(41, notification()); }
    public int onStartCommand(Intent intent, int flags, int id) { if (intent != null && "stop".equals(intent.getAction())) TranslationTaskManager.stop(); return START_NOT_STICKY; }
    public IBinder onBind(Intent intent) { return null; }
    @Override public void onTimeout(int startId,int type){TranslationTaskManager.stop();stopSelf();}
    public void onDestroy() { TranslationTaskManager.unlisten(update);if(TranslationTaskManager.running())TranslationTaskManager.stop();if (wake != null && wake.isHeld()) wake.release(); super.onDestroy(); }
}
