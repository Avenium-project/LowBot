package io.lowbot.app;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import io.lowbot.tools.Builtin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.lowbot.core.Backend;
import io.lowbot.core.Core;
import io.lowbot.core.J;
import io.lowbot.core.Router;
import io.lowbot.engine.Tools;
import io.lowbot.tools.Mcp;

/**
 * Hosts the LowBot backend in the app process: database, engine, scheduler, tools,
 * notifications and the bots' browser. Nothing runs on an external server.
 */
public class LowBotApp extends Application {
    static final String CH_WORK = "work", CH_ATTENTION = "attention", CH_DONE = "done";
    static volatile boolean uiVisible = false;

    Backend backend;
    Router router;
    Computer computer;
    Linux linux;
    Watchers watchers;
    final List<Core.EventListener> uiListeners = new CopyOnWriteArrayList<Core.EventListener>();
    final Handler main = new Handler(Looper.getMainLooper());

    static LowBotApp of(Context c) { return (LowBotApp) c.getApplicationContext(); }

    @Override
    public void onCreate() {
        super.onCreate();
        channels();
        backend = Backend.get(this);
        router = new Router(backend);
        computer = Computer.get(this, backend);
        computer.register(backend.tools);
        linux = new Linux(this, backend);
        linux.register(backend.tools);
        watchers = new Watchers(backend, linux);
        watchers.register(backend.tools);
        router.linux = new Router.LinuxApi() {
            public JSONObject status() { return linux.status(); }
            public JSONObject install() { return linux.install(); }
            public void remove() { linux.remove(); }
            public JSONObject log(String botId) { return linux.log(botId); }
            public void reset(String botId) { linux.reset(botId); }
            public JSONObject run(String botId, String command, int timeoutS) throws Exception {
                return linux.run(botId, Builtin.rootFor(backend, backend.bots.require(botId)), command, timeoutS, "user", null);
            }
            public JSONArray watchers() { return watchers.list(); }
            public void stopWatcher(String id, boolean delete) { watchers.stop(id, delete); }
            public String watcherLog(String id) { return watchers.logs(id, 200); }
        };
        final Mcp mcp = new Mcp(backend);
        backend.tools.addProvider(new Tools.DynamicProvider() {
            public List<Tools.Spec> specs(JSONObject bot) { return mcp.specs(bot); }
        });
        router.mcp = mcp;
        router.computer = new Router.ComputerApi() {
            public JSONObject list() { return computer.listSurfaces(); }
            public byte[] screenshot(String sid) throws Exception { return computer.screenshot(sid); }
            public JSONObject takeOver(String sid) { return computer.takeOver(sid); }
            public JSONObject resume(String sid) { return computer.resume(sid); }
            public JSONObject open(String botId) { return computer.openForHuman(botId); }
            public void record(String sid, boolean on) { computer.setRecording(sid, on); }
            public void reset() { computer.reset(); }
        };
        backend.platform = new Backend.Platform() {
            public void scheduleAlarm(long at) { LowBotApp.this.scheduleAlarm(at); }
            public void workStateChanged(int active, int queued) { WorkService.update(LowBotApp.this, active, queued); }
            public boolean browserAvailable() { return true; }
        };
        backend.core.notifier = new Core.Notifier() {
            public void show(JSONObject n) { notifyOs(n); }
        };
        backend.core.listeners.add(new Core.EventListener() {
            public void onEvent(JSONObject e) {
                for (Core.EventListener l : uiListeners) l.onEvent(e);
                String t = e.optString("type");
                if (t.startsWith("run.") || t.startsWith("task.") || t.startsWith("bot.") || t.startsWith("approval.")) BotsWidget.requestRefresh(LowBotApp.this);
                if (t.startsWith("widget.") || t.startsWith("bot.")) main.post(new Runnable() { public void run() { CardWidget.refreshAll(LowBotApp.this); } });
            }
        });
        backend.start();
        watchers.restore();
        backend.scheduleAlarm();
        backend.wake();
    }

    void channels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_WORK, getString(R.string.ch_work), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_ATTENTION, getString(R.string.ch_attention), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT));
    }

    void scheduleAlarm(long at) {
        AlarmManager am = getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 1, new Intent(this, AlarmReceiver.class).setAction("io.lowbot.app.TICK"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (at <= 0) return;
        long when = Math.max(at, System.currentTimeMillis() + 1000);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    /** Grok Bot: notify when a bot finishes or needs you; muted while the app is on screen. */
    void notifyOs(JSONObject n) {
        String kind = n.optString("kind");
        boolean attention = kind.equals("approval") || kind.equals("needs_input") || kind.equals("needs_resolution");
        if (uiVisible && !attention) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        int nid = n.optString("id").hashCode();
        Intent open = new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .putExtra("open_conversation", J.str(n, "conversation_id", null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, nid, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = new Notification.Builder(this, attention ? CH_ATTENTION : CH_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(n.optString("title"))
                .setContentText(n.optString("body"))
                .setStyle(new Notification.BigTextStyle().bigText(n.optString("body")))
                .setAutoCancel(true)
                .setContentIntent(contentPi);
        String aid = J.str(n, "approval_id", null);
        if (aid != null) {
            nb.addAction(new Notification.Action.Builder(null, getString(R.string.allow_once), action(aid, "approve", nid)).build());
            nb.addAction(new Notification.Action.Builder(null, getString(R.string.deny), action(aid, "deny", nid)).build());
        }
        getSystemService(NotificationManager.class).notify(nid, nb.build());
    }

    PendingIntent action(String approvalId, String decision, int nid) {
        Intent i = new Intent(this, ActionReceiver.class).setAction("io.lowbot.app.DECIDE." + decision)
                .putExtra("approval_id", approvalId).putExtra("decision", decision).putExtra("nid", nid);
        return PendingIntent.getBroadcast(this, (approvalId + decision).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
