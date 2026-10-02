package io.lowbot.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.List;

import io.lowbot.core.Backend;
import io.lowbot.core.J;
import io.lowbot.core.WidgetVisual;

/**
 * A widget a bot made (title + short text), placed on the phone's home screen. Which LowBot
 * widget a home-screen widget shows is kept in kv "card_widget:<appWidgetId>"; a widget added
 * from the launcher without a choice shows the newest widget that is on LowBot's home.
 */
public class CardWidget extends AppWidgetProvider {
    static final String PINNED = "io.lowbot.app.CARD_PINNED";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (PINNED.equals(intent.getAction())) {
            int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            String wid = intent.getStringExtra("widget_id");
            if (appWidgetId >= 0 && wid != null) {
                LowBotApp.of(ctx).backend.core.kvSet("card_widget:" + appWidgetId, wid);
                requestRefresh(ctx);
            }
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) { requestRefresh(ctx); }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        for (int id : ids) LowBotApp.of(ctx).backend.core.kvSet("card_widget:" + id, null);
    }

    static boolean refreshQueued = false;

    /** Debounced, off the main thread (bots working send many events). */
    static void requestRefresh(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        synchronized (CardWidget.class) {
            if (refreshQueued) return;
            refreshQueued = true;
        }
        BotsWidget.main.postDelayed(new Runnable() { public void run() {
            synchronized (CardWidget.class) { refreshQueued = false; }
            try { refreshAll(app); } catch (Throwable e) { android.util.Log.w("LowBot", "card widget: " + e); }
        } }, 500);
    }

    static void refreshAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        for (int id : mgr.getAppWidgetIds(new ComponentName(ctx, CardWidget.class))) render(ctx, mgr, id);
    }

    /** Ask the launcher to place a widget for LowBot widget `wid` (Android 8+, if the launcher supports it). */
    static boolean pin(Context ctx, String wid) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (Build.VERSION.SDK_INT < 26 || !mgr.isRequestPinAppWidgetSupported()) return false;
        Intent cb = new Intent(ctx, CardWidget.class).setAction(PINNED).putExtra("widget_id", wid);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, wid.hashCode(), cb, flags);
        return mgr.requestPinAppWidget(new ComponentName(ctx, CardWidget.class), null, pi);
    }

    static String plain(String md) {
        return md.replaceAll("(?m)^#{1,6}\\s*", "").replaceAll("\\*\\*|__|`", "").replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .replaceAll("(?m)^\\|?\\s*-{2,}.*$\\n?", "").replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").trim();
    }

    /** The widget's chart/stat/image/HTML as a bitmap, or null (HTML is drawn in the background, then the widget refreshes). */
    static Bitmap visual(final Context ctx, Backend b, JSONObject w) {
        final JSONObject v = b.widgets.visual(w.optString("id"));
        if (v == null) return null;
        int width = Math.min(720, BotsWidget.px(ctx, 300));
        try {
            String type = v.optString("type");
            if ("chart".equals(type) || "stat".equals(type)) return WidgetPainter.scene(WidgetVisual.scene(v), width);
            if ("image".equals(type)) return WidgetPainter.image(new java.io.File(b.widgets.mediaDir(), v.optString("file")), width);
            if ("html".equals(type)) {
                final String key = w.optString("id") + "|" + w.optString("updated_at");
                Bitmap cached = WidgetPainter.htmlCache.get(key);
                if (cached != null) return cached;
                if (WidgetPainter.pending.add(key)) {
                    final int hgt = Math.round(width * WidgetVisual.height(v) / (float) WidgetVisual.W);
                    final String doc = WidgetVisual.document(v, b.widgets.mediaDir());
                    final int wd = width;
                    WidgetPainter.main.post(new Runnable() { public void run() {
                        WidgetPainter.html(ctx, doc, wd, hgt, new WidgetPainter.Done() { public void bitmap(Bitmap bm) {
                            WidgetPainter.pending.remove(key);
                            if (WidgetPainter.hasContent(bm)) { WidgetPainter.htmlCache.put(key, bm); requestRefresh(ctx); }
                        } });
                    } });
                }
            }
        } catch (Throwable e) {
            android.util.Log.w("LowBot", "widget visual: " + e);
        }
        return null;
    }

    static void render(Context ctx, AppWidgetManager mgr, int appWidgetId) {
        Backend b = LowBotApp.of(ctx).backend;
        JSONObject w = b.widgets.get(b.core.kvGet("card_widget:" + appWidgetId));
        if (w == null) {
            List<JSONObject> all = b.widgets.list();
            for (JSONObject x : all) if (x.optBoolean("on_home")) { w = x; break; }
            if (w == null && !all.isEmpty()) w = all.get(0);
        }
        Bitmap visual = w == null ? null : visual(ctx, b, w);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), visual != null ? R.layout.widget_card_visual : R.layout.widget_card);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (w == null) {
            rv.setTextViewText(R.id.card_title, "LowBot");
            rv.setTextViewText(R.id.card_text, "Ask a bot to make a widget, then add it here.");
            rv.setTextViewText(R.id.card_time, "");
        } else {
            JSONObject bot = b.bots.get(w.optString("bot_id"));
            rv.setTextViewText(R.id.card_title, w.optString("title"));
            String text = plain(w.optString("content"));
            rv.setTextViewText(R.id.card_text, text);
            if (visual != null) {
                rv.setImageViewBitmap(R.id.card_visual, visual);
                rv.setViewVisibility(R.id.card_text, text.isEmpty() ? View.GONE : View.VISIBLE);
            }
            String t = w.optString("updated_at");
            rv.setTextViewText(R.id.card_time, t.length() >= 16 ? java.time.Instant.parse(t).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) : "");
            if (bot != null) {
                rv.setImageViewBitmap(R.id.card_bot, BotSprites.draw(bot.optString("avatar"), bot.optString("id"), BotsWidget.px(ctx, 26), BotSprites.AWAKE, 0));
                open.setAction("io.lowbot.OPEN_BOT." + bot.optString("id")).putExtra("open_bot", bot.optString("id"));
            }
        }
        rv.setOnClickPendingIntent(R.id.card_root, PendingIntent.getActivity(ctx, 200 + appWidgetId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mgr.updateAppWidget(appWidgetId, rv);
    }
}
