package io.lowbot.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.PowerManager;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.lowbot.core.Backend;

/**
 * Home-screen widget: up to five bots. Bots that are working hop one after another,
 * bots that need you are awake with an amber dot, the rest sleep. Tap a bot to open its chat.
 * The hop animation runs only while some bot works and the screen is on.
 */
public class BotsWidget extends AppWidgetProvider {
    static final int[] SLOT = {R.id.slot0, R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4};
    static final int[] IMG = {R.id.bot0, R.id.bot1, R.id.bot2, R.id.bot3, R.id.bot4};
    static final int[] NAME = {R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4};
    static final float[] HOP = {0, 7, 13, 16, 13, 7, 0, 0};
    static final List<String> BUSY = Arrays.asList("working", "queued", "retrying", "waiting");
    static final long FRAME_MS = 95;

    /** Widget work (database reads, drawing sprites, RemoteViews updates) runs here, never on the app's main thread. */
    static final Handler main;
    static {
        android.os.HandlerThread t = new android.os.HandlerThread("lowbot-widgets", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        t.start();
        main = new Handler(t.getLooper());
    }
    static List<JSONObject> shown = new ArrayList<JSONObject>();
    static List<Integer> busySlots = new ArrayList<Integer>();
    static final Map<String, Bitmap> cache = new HashMap<String, Bitmap>();
    static int frame = 0, lastHopSlot = -1;
    static volatile boolean ticking = false, refreshQueued = false;
    static Context appCtx;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) { requestRefresh(ctx); }

    @Override
    public void onDisabled(Context ctx) { busySlots = new ArrayList<Integer>(); }

    static int[] ids(Context ctx) {
        return AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, BotsWidget.class));
    }

    /** Debounced refresh, called on backend events (runs starting/finishing, bots changing). */
    static void requestRefresh(final Context ctx) {
        appCtx = ctx.getApplicationContext();
        if (refreshQueued) return;
        refreshQueued = true;
        main.postDelayed(new Runnable() { public void run() { refreshQueued = false; refresh(appCtx); } }, 400);
    }

    static int state(JSONObject bot) {
        String s = bot.optString("status");
        if (BUSY.contains(s)) return BotSprites.AWAKE;
        if (s.startsWith("needs_")) return BotSprites.ATTENTION;
        return BotSprites.ASLEEP;
    }

    static int px(Context ctx, float dp) { return (int) (dp * ctx.getResources().getDisplayMetrics().density); }

    static Bitmap sprite(Context ctx, JSONObject bot, int state, float hop) {
        String key = bot.optString("avatar") + "|" + bot.optString("id") + "|" + state + "|" + hop;
        Bitmap b = cache.get(key);
        if (b == null) {
            if (cache.size() > 120) cache.clear();
            b = BotSprites.draw(bot.optString("avatar"), bot.optString("id"), px(ctx, 56), state, hop);
            cache.put(key, b);
        }
        return b;
    }

    static synchronized void refresh(Context ctx) {
        appCtx = ctx.getApplicationContext();
        int[] ids = ids(ctx);
        if (ids.length == 0) { busySlots = new ArrayList<Integer>(); return; }
        Backend b = LowBotApp.of(ctx).backend;
        List<JSONObject> bots = new ArrayList<JSONObject>(b.bots.list(false));
        java.util.Collections.sort(bots, new java.util.Comparator<JSONObject>() {
            public int compare(JSONObject x, JSONObject y) {
                int rx = rank(x), ry = rank(y);
                return rx != ry ? rx - ry : x.optString("name").compareToIgnoreCase(y.optString("name"));
            }
            int rank(JSONObject o) {
                int st = state(o);
                return st == BotSprites.AWAKE ? 0 : st == BotSprites.ATTENTION ? 1 : o.optBoolean("pinned") ? 2 : 3;
            }
        });
        if (bots.size() > SLOT.length) bots = bots.subList(0, SLOT.length);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_bots);
        rv.setViewVisibility(R.id.empty, bots.isEmpty() ? View.VISIBLE : View.GONE);
        List<Integer> busy = new ArrayList<Integer>();
        for (int i = 0; i < SLOT.length; i++) {
            if (i >= bots.size()) { rv.setViewVisibility(SLOT[i], View.GONE); continue; }
            JSONObject bot = bots.get(i);
            int st = state(bot);
            if (st == BotSprites.AWAKE) busy.add(i);
            rv.setViewVisibility(SLOT[i], View.VISIBLE);
            rv.setImageViewBitmap(IMG[i], sprite(ctx, bot, st, 0));
            rv.setTextViewText(NAME[i], bot.optString("name"));
            Intent open = new Intent(ctx, MainActivity.class).setAction("io.lowbot.OPEN_BOT." + bot.optString("id"))
                    .putExtra("open_bot", bot.optString("id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            rv.setOnClickPendingIntent(SLOT[i], PendingIntent.getActivity(ctx, 100 + i, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        if (bots.isEmpty()) {
            Intent launch = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 99, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        AppWidgetManager.getInstance(ctx).updateAppWidget(ids, rv);
        shown = bots;
        busySlots = busy;
        lastHopSlot = -1;
        if (!busy.isEmpty() && !ticking) { ticking = true; main.post(TICK); }
    }

    /** One animation frame: the working bots hop one after another. */
    static final Runnable TICK = new Runnable() {
        public void run() {
            Context ctx = appCtx;
            List<Integer> busy = busySlots;
            if (ctx == null || busy.isEmpty() || ids(ctx).length == 0) { ticking = false; return; }
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) { main.postDelayed(this, 2000); return; }
            int per = HOP.length;
            int slot = busy.get((frame / per) % busy.size());
            float hop = HOP[frame % per];
            frame++;
            try {
                RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_bots);
                if (lastHopSlot >= 0 && lastHopSlot != slot && lastHopSlot < shown.size())
                    rv.setImageViewBitmap(IMG[lastHopSlot], sprite(ctx, shown.get(lastHopSlot), BotSprites.AWAKE, 0));
                if (slot < shown.size()) rv.setImageViewBitmap(IMG[slot], sprite(ctx, shown.get(slot), BotSprites.AWAKE, hop));
                lastHopSlot = slot;
                AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(ids(ctx), rv);
            } catch (Exception e) {
                ticking = false;
                return;
            }
            main.postDelayed(this, FRAME_MS);
        }
    };
}
