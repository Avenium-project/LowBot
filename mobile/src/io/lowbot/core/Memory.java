package io.lowbot.core;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durable bot memory and team knowledge, FTS4 full-text search (server/app/v2/memory.py). */
public final class Memory {
    private final Backend b;
    private final Db db;

    Memory(Backend b) { this.b = b; this.db = b.core.db; }

    static String ftsQuery(String text) {
        List<String> terms = new ArrayList<String>();
        Matcher m = Pattern.compile("[\\p{L}\\p{N}_]+").matcher(text == null ? "" : text);
        while (m.find() && terms.size() < 12) if (m.group().length() > 1) terms.add(m.group().toLowerCase() + "*");
        return android.text.TextUtils.join(" OR ", terms);
    }

    public JSONObject save(String content, final String scope, String botId, String source, String createdBy) {
        if (!"bot".equals(scope) && !"team".equals(scope)) throw new ApiError(422, "scope must be bot or team");
        final String c = J.truncate(content == null ? "" : content.trim(), 4000);
        if (c.isEmpty()) throw new ApiError(422, "Memory content is empty.");
        final String mid = J.id("mem");
        final JSONObject row = J.obj("id", mid, "scope", scope, "bot_id", "bot".equals(scope) ? botId : null, "content", c,
                "source", J.truncate(source, 300), "created_by", createdBy, "created_at", J.nowIso(), "updated_at", J.nowIso());
        final String bid = botId;
        db.tx(new Runnable() {
            @Override public void run() {
                db.insert("memories", row);
                db.exec("INSERT INTO memories_fts(content, memory_id) VALUES (?, ?)", c, mid);
                b.core.emit("memory.saved", null, null, null, bid, J.obj("memory_id", mid, "scope", scope));
            }
        });
        return get(mid);
    }

    public JSONObject get(String id) { return db.one("SELECT * FROM memories WHERE id = ? AND deleted_at IS NULL", id); }

    /** bot == null: the owner sees everything. */
    public List<JSONObject> search(String query, JSONObject bot, int limit) {
        String q = ftsQuery(query);
        if (q.isEmpty()) return list(bot, null, limit);
        if (bot == null)
            return db.all("SELECT m.* FROM memories_fts f JOIN memories m ON m.id = f.memory_id WHERE memories_fts MATCH ? AND m.deleted_at IS NULL ORDER BY m.updated_at DESC LIMIT ?", q, limit);
        if (bot.optBoolean("team_memory_access"))
            return db.all("SELECT m.* FROM memories_fts f JOIN memories m ON m.id = f.memory_id WHERE memories_fts MATCH ? AND m.deleted_at IS NULL AND (m.scope = 'team' OR m.bot_id = ?) ORDER BY m.updated_at DESC LIMIT ?", q, bot.optString("id"), limit);
        return db.all("SELECT m.* FROM memories_fts f JOIN memories m ON m.id = f.memory_id WHERE memories_fts MATCH ? AND m.deleted_at IS NULL AND m.scope = 'bot' AND m.bot_id = ? ORDER BY m.updated_at DESC LIMIT ?", q, bot.optString("id"), limit);
    }

    public List<JSONObject> list(JSONObject bot, String botId, int limit) {
        if (bot != null) {
            if (bot.optBoolean("team_memory_access"))
                return db.all("SELECT * FROM memories WHERE deleted_at IS NULL AND (scope = 'team' OR bot_id = ?) ORDER BY updated_at DESC LIMIT ?", bot.optString("id"), limit);
            return db.all("SELECT * FROM memories WHERE deleted_at IS NULL AND scope = 'bot' AND bot_id = ? ORDER BY updated_at DESC LIMIT ?", bot.optString("id"), limit);
        }
        if (botId != null)
            return db.all("SELECT * FROM memories WHERE deleted_at IS NULL AND (bot_id = ? OR scope = 'team') ORDER BY updated_at DESC LIMIT ?", botId, limit);
        return db.all("SELECT * FROM memories WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT ?", limit);
    }

    public JSONObject update(final String id, String content) {
        final String c = J.truncate(content == null ? "" : content.trim(), 4000);
        db.tx(new Runnable() {
            @Override public void run() {
                if (db.change("UPDATE memories SET content = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL", c, J.nowIso(), id) != 1)
                    throw new ApiError(404, "Unknown memory.");
                db.exec("DELETE FROM memories_fts WHERE memory_id = ?", id);
                db.exec("INSERT INTO memories_fts(content, memory_id) VALUES (?, ?)", c, id);
                b.core.audit("memory.update", "user", null, null, null, null, null, null, J.obj("memory_id", id));
            }
        });
        return get(id);
    }

    public void delete(final String id) {
        db.tx(new Runnable() {
            @Override public void run() {
                db.exec("UPDATE memories SET deleted_at = ?, content = '' WHERE id = ?", J.nowIso(), id);
                db.exec("DELETE FROM memories_fts WHERE memory_id = ?", id);
                b.core.audit("memory.delete", "user", null, null, null, null, null, null, J.obj("memory_id", id));
            }
        });
    }
}
