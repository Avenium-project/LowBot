package io.lowbot.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.List;

import io.lowbot.core.Backend;
import io.lowbot.core.J;

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
                render(ctx, AppWidgetManager.getInstance(ctx), appWidgetId);
            }
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(ctx, mgr, id);
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        for (int id : ids) LowBotApp.of(ctx).backend.core.kvSet("card_widget:" + id, null);
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

    static void render(Context ctx, AppWidgetManager mgr, int appWidgetId) {
        Backend b = LowBotApp.of(ctx).backend;
        JSONObject w = b.widgets.get(b.core.kvGet("card_widget:" + appWidgetId));
        if (w == null) {
            List<JSONObject> all = b.widgets.list();
            for (JSONObject x : all) if (x.optBoolean("on_home")) { w = x; break; }
            if (w == null && !all.isEmpty()) w = all.get(0);
        }
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_card);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (w == null) {
            rv.setTextViewText(R.id.card_title, "LowBot");
            rv.setTextViewText(R.id.card_text, "Ask a bot to make a widget, then add it here.");
            rv.setTextViewText(R.id.card_time, "");
        } else {
            JSONObject bot = b.bots.get(w.optString("bot_id"));
            rv.setTextViewText(R.id.card_title, w.optString("title"));
            rv.setTextViewText(R.id.card_text, plain(w.optString("content")));
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
