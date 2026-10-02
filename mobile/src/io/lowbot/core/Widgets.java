package io.lowbot.core;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * Widgets a bot makes for the user: a small card (title + short Markdown + optionally a chart, a big
 * number, an image or the bot's own HTML — see {@link WidgetVisual}) the user can put on
 * LowBot's home screen or the phone's home screen. A widget may be refreshed by a routine of
 * its bot ("check my mail every hour and update the widget"). Bots may create widgets only
 * while the user allows it (Settings → Widgets).
 */
public final class Widgets {
    public static final int CONTENT_MAX = 2000;
    private final Backend b;
    private final Db db;

    Widgets(Backend b) { this.b = b; this.db = b.core.db; }

    public boolean botsMayCreate() { return b.core.kvBool("bot_widgets", true); }

    public void setBotsMayCreate(boolean on) { b.core.kvSet("bot_widgets", on ? "1" : "0"); }

    public File mediaDir() { return new File(b.core.filesDir, "widget-media"); }

    static JSONObject shape(JSONObject r) {
        J.put(r, "on_home", J.bool(r, "on_home"));
        JSONObject v = J.parse(r.optString("visual_json", null));
        r.remove("visual_json");
        J.put(r, "visual_type", v.optString("type", null));
        return r;
    }

    public List<JSONObject> list() {
        List<JSONObject> rows = db.all("SELECT * FROM widgets ORDER BY on_home DESC, updated_at DESC");
        for (JSONObject r : rows) shape(r);
        return rows;
    }

    public JSONObject get(String id) {
        JSONObject r = id == null ? null : db.one("SELECT * FROM widgets WHERE id = ?", id);
        return r == null ? null : shape(r);
    }

    /** The stored visual (chart/stat/image/html), or null. */
    public JSONObject visual(String id) {
        String v = db.scalar("SELECT visual_json FROM widgets WHERE id = ?", id);
        return v == null || v.isEmpty() ? null : J.parse(v);
    }

    /** {type, html, height} for showing the visual in LowBot (sandboxed, no network). */
    public JSONObject visualDocument(String id) {
        require(id);
        JSONObject v = visual(id);
        if (v == null) return J.obj("type", null);
        return J.obj("type", v.optString("type"), "html", WidgetVisual.document(v, mediaDir()), "height", WidgetVisual.height(v));
    }

    /** A visual from tool arguments (chart | stat | image | html); null leaves it unchanged. */
    public JSONObject setVisual(final String id, JSONObject args, File workspaceRoot) {
        final JSONObject w = require(id);
        if (args.optBoolean("clear_visual")) {
            db.tx(new Runnable() { public void run() {
                db.exec("UPDATE widgets SET visual_json = NULL, updated_at = ? WHERE id = ?", J.nowIso(), id);
                b.core.emit("widget.updated", null, null, null, w.optString("bot_id"), J.obj("widget_id", id));
            } });
            return get(id);
        }
        final JSONObject v = WidgetVisual.fromArgs(args, workspaceRoot, mediaDir(), id);
        if (v == null) return w;
        db.tx(new Runnable() { public void run() {
            db.exec("UPDATE widgets SET visual_json = ?, updated_at = ? WHERE id = ?", v.toString(), J.nowIso(), id);
            b.core.emit("widget.updated", null, null, null, w.optString("bot_id"), J.obj("widget_id", id));
        } });
        return get(id);
    }

    public JSONObject require(String id) {
        JSONObject w = get(id);
        if (w == null) throw new ApiError(404, "Unknown widget: " + id);
        return w;
    }

    static String clean(String s, int max, String what) {
        String v = s == null ? "" : s.trim();
        if (v.length() > max) throw new ApiError(422, what + " is too long (max " + max + " characters).");
        return v;
    }

    public JSONObject create(final String botId, String title, String content, final String routineId) {
        final String t = clean(title, 60, "Title");
        if (t.isEmpty()) throw new ApiError(422, "A widget needs a short title.");
        final String c = clean(content, CONTENT_MAX, "Widget content");
        if (db.count("SELECT COUNT(*) FROM widgets") >= 40) throw new ApiError(409, "Too many widgets (40). Delete one first.");
        final String id = J.id("wdg");
        db.tx(new Runnable() { public void run() {
            String now = J.nowIso();
            db.insert("widgets", J.obj("id", id, "bot_id", botId, "title", t, "content", c, "on_home", false, "routine_id", routineId,
                    "created_at", now, "updated_at", now));
            b.core.emit("widget.updated", null, null, null, botId, J.obj("widget_id", id));
        } });
        return get(id);
    }

    public JSONObject update(final String id, final String title, final String content) {
        final JSONObject w = require(id);
        final String t = title == null ? null : clean(title, 60, "Title");
        final String c = content == null ? null : clean(content, CONTENT_MAX, "Widget content");
        db.tx(new Runnable() { public void run() {
            if (t != null && !t.isEmpty()) db.exec("UPDATE widgets SET title = ? WHERE id = ?", t, id);
            if (c != null) db.exec("UPDATE widgets SET content = ? WHERE id = ?", c, id);
            db.exec("UPDATE widgets SET updated_at = ? WHERE id = ?", J.nowIso(), id);
            b.core.emit("widget.updated", null, null, null, w.optString("bot_id"), J.obj("widget_id", id));
        } });
        return get(id);
    }

    public JSONObject setHome(final String id, final boolean on) {
        require(id);
        db.tx(new Runnable() { public void run() {
            db.exec("UPDATE widgets SET on_home = ? WHERE id = ?", on, id);
            b.core.emit("widget.updated", null, null, null, null, J.obj("widget_id", id));
        } });
        return get(id);
    }

    public void delete(final String id) {
        final JSONObject w = require(id);
        if (!w.isNull("routine_id") && !w.optString("routine_id").isEmpty()) {
            try { b.routines.delete(w.optString("routine_id")); } catch (RuntimeException ignored) { }
        }
        File[] media = mediaDir().listFiles();
        if (media != null) for (File f : media) if (f.getName().startsWith(id + ".")) f.delete();
        db.tx(new Runnable() { public void run() {
            db.exec("DELETE FROM widgets WHERE id = ?", id);
            b.core.emit("widget.deleted", null, null, null, w.optString("bot_id"), J.obj("widget_id", id));
        } });
    }

}
