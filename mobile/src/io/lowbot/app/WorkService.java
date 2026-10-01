package io.lowbot.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that keeps the process alive while bots work, so tasks continue
 * after you leave the app (Grok Bot: work continues without the app open). It stops
 * itself when nothing is queued or running. It cannot keep working while the phone is off.
 */
public class WorkService extends Service {
    static volatile boolean running = false;

    static void update(Context ctx, int active, int queued) {
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
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(42, notif);
        running = true;
        LowBotApp.of(this).backend.wake();
        return START_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 limits dataSync services to 6 h/day; work resumes next time the app opens.
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
