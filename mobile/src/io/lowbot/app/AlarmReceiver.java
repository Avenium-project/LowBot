package io.lowbot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.lowbot.core.Backend;

/** Routine alarms (AlarmManager) and boot: fire due routines and wake the engine. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pr = goAsync();
        final LowBotApp app = LowBotApp.of(context);
        new Thread(new Runnable() {
            public void run() {
                try {
                    Backend b = app.backend;
                    b.routines.tick();
                    b.wake();
                    b.scheduleAlarm();
                } catch (Exception e) {
                    android.util.Log.e("LowBot", "alarm", e);
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }
}
