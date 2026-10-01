package io.lowbot.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import io.lowbot.core.Backend;

/** "Allow once" / "Deny" straight from the notification (bound to that exact approval). */
public class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, final Intent intent) {
        final PendingResult pr = goAsync();
        final LowBotApp app = LowBotApp.of(context);
        context.getSystemService(NotificationManager.class).cancel(intent.getIntExtra("nid", 0));
        new Thread(new Runnable() {
            public void run() {
                try {
                    Backend b = app.backend;
                    JSONObject a = b.approvals.get(intent.getStringExtra("approval_id"));
                    if (a != null && "pending".equals(a.optString("status")))
                        b.approvals.decide(a.optString("id"), intent.getStringExtra("decision"), a.optString("args_hash"));
                } catch (Exception e) {
                    android.util.Log.w("LowBot", "notification decision failed: " + e.getMessage());
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }
}
