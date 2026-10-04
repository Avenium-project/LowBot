package io.lowbot.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that keeps the process alive while bots work, so tasks continue
 * after you leave the app (Grok Bot: work continues without the app open). It stops
 * itself when nothing is queued or running. It cannot keep working while the phone is off.
 */
public class WorkService extends Service {
    static volatile boolean running = false;
    private static volatile long workRevision;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock cpu;
    private ScheduledExecutorService monitor;
    private boolean destroyed;

    static synchronized void update(Context ctx, int active, int queued) {
        workRevision++;
        queued = Math.max(active, queued);
        Context app = ctx.getApplicationContext();
        if (queued > 0 && !running) {
            try {
                Intent i = new Intent(app, WorkService.class).putExtra("n", queued);
                app.startForegroundService(i);
            } catch (Exception ignored) {
                // Background start restrictions: work still continues while the app is open.
            }
        } else if (queued > 0) {
            Intent i = new Intent(app, WorkService.class).putExtra("n", queued);
            try { app.startService(i); } catch (Exception ignored) { }
        } else if (running) {
            app.stopService(new Intent(app, WorkService.class));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int n = intent == null ? 0 : intent.getIntExtra("n", 0);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notif = new Notification.Builder(this, LowBotApp.CH_WORK)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.working_title))
                .setContentText(getString(R.string.working_text, Math.max(1, n)))
                .setOngoing(true)
                .setContentIntent(open)
                .build();
        // Local agent execution is not a data-sync job (which has a 6 h budget
        // on Android 15). Declare its actual purpose using specialUse on 14+.
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(42, notif);
        running = true;
        renewCpuLease();
        if (monitor == null) {
            monitor = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "lowbot-work-monitor");
                    t.setDaemon(true);
                    return t;
                }
            });
            monitor.scheduleWithFixedDelay(new Runnable() { public void run() { checkWork(); } }, 30, 30, TimeUnit.SECONDS);
        }
        // Backend.wake() reports work state back to update(), which starts this
        // service again while work is queued. Wake only the engine here: calling
        // back into the platform creates an endless main-thread notification/DB loop.
        LowBotApp.of(this).backend.engine.wake();
        return START_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Respect any OS timeout; the persisted run can resume when reopened.
        stopSelf();
    }

    private void renewCpuLease() {
        if (cpu == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LowBot:bot-work");
            cpu.setReferenceCounted(false);
        }
        // A bounded lease is renewed only while durable work still exists.
        cpu.acquire(120000);
    }

    void checkWork() {
        try {
            final long revision = workRevision;
            LowBotApp app = LowBotApp.of(this);
            final long count = Math.max(app.backend.engine.activeCount(), app.backend.queuedCount() + app.backend.backgroundJobs);
            main.post(new Runnable() { public void run() {
                synchronized (WorkService.class) {
                    if (destroyed || revision != workRevision) return;
                    if (count == 0) stopSelf();
                    else renewCpuLease();
                }
            } });
        } catch (Exception e) {
            android.util.Log.e("LowBot", "work service monitor", e);
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (monitor != null) monitor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (cpu != null && cpu.isHeld()) cpu.release();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
