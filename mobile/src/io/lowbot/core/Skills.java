package io.lowbot.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Versioned skills invoked with /slug (server/app/v2/skills.py). "Teach a task" turns a
 * consented recording (tool steps of a run, or the user's own actions while they had
 * taken over the phone browser) into an editable DRAFT skill.
 */
public final class Skills {
    static final String FORMAT = "opendots.skill.v1";
    private final Backend b;
    private final Db db;

    Skills(Backend b) { this.b = b; this.db = b.core.db; }

    public JSONObject full(String id, int version) {
        JSONObject s = db.one("SELECT * FROM skills WHERE id = ?", id);
        if (s == null) return null;
        JSONObject v = db.one("SELECT * FROM skill_versions WHERE skill_id = ? AND version = ?", id, version > 0 ? version : s.optInt("current_version"));
        if (v == null) return null;
        J.put(s, "version", v.optInt("version"));
        J.put(s, "instructions", v.optString("instructions"));
        J.put(s, "inputs", J.parseArr(v.optString("inputs_json")));
        J.put(s, "tools", J.parseArr(v.optString("tools_json")));
        J.put(s, "completion_criteria", v.optString("completion_criteria"));
        J.put(s, "source", v.optString("source"));
        JSONArray versions = new JSONArray();
        for (JSONObject r : db.all("SELECT version FROM skill_versions WHERE skill_id = ? ORDER BY version", id)) versions.put(r.optInt("version"));
        J.put(s, "versions", versions);
        JSONArray bots = new JSONArray();
        for (JSONObject r : db.all("SELECT bot_id FROM bot_skills WHERE skill_id = ?", id)) bots.put(r.optString("bot_id"));
        J.put(s, "bot_ids", bots);
        return s;
    }

    public List<JSONObject> list() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject r : db.all("SELECT id FROM skills ORDER BY name")) out.add(full(r.optString("id"), 0));
        return out;
    }

    public JSONObject bySlug(String slug) {
        String id = db.scalar("SELECT id FROM skills WHERE slug = ?", slug);
        return id == null ? null : full(id, 0);
    }

    public JSONObject create(final JSONObject d, final String source) {
        String name = J.str(d, "name", "").trim();
        if (name.isEmpty() || J.str(d, "instructions", "").trim().isEmpty()) throw new ApiError(422, "A skill needs a name and instructions.");
        final String slug = J.slug(J.str(d, "slug", "").isEmpty() ? name : d.optString("slug"));
        if (db.one("SELECT 1 FROM skills WHERE slug = ?", slug) != null) throw new ApiError(422, "Skill /" + slug + " already exists.");
        final String sid = J.id("skl");
        final String n = name;
        db.tx(new Runnable() {
            @Override public void run() {
                db.insert("skills", J.obj("id", sid, "slug", slug, "name", n, "current_version", 1, "created_at", J.nowIso(), "updated_at", J.nowIso()));
                insertVersion(sid, 1, d, source);
                setBots(sid, d.optJSONArray("bot_ids"));
                b.core.audit("skill.create", "user", null, null, null, null, null, null, J.obj("skill_id", sid, "slug", slug));
            }
        });
        return full(sid, 0);
    }

    void setBots(String sid, JSONArray bots) {
        if (bots == null) return;
        db.exec("DELETE FROM bot_skills WHERE skill_id = ?", sid);
        for (int i = 0; i < bots.length(); i++) db.exec("INSERT OR IGNORE INTO bot_skills(bot_id, skill_id) VALUES (?, ?)", bots.optString(i), sid);
    }

    void insertVersion(String sid, int v, JSONObject d, String source) {
        db.insert("skill_versions", J.obj("skill_id", sid, "version", v, "instructions", d.optString("instructions"),
                "inputs_json", d.optJSONArray("inputs") == null ? "[]" : d.optJSONArray("inputs").toString(),
                "tools_json", d.optJSONArray("tools") == null ? "[]" : d.optJSONArray("tools").toString(),
                "completion_criteria", J.str(d, "completion_criteria", ""), "source", source, "created_at", J.nowIso()));
    }

    /** Each edit creates a new immutable version. */
    public JSONObject update(final String sid, final JSONObject d) {
        final JSONObject cur = full(sid, 0);
        if (cur == null) throw new ApiError(404, "Unknown skill.");
        final JSONObject merged = new JSONObject();
        for (String k : new String[]{"instructions", "inputs", "tools", "completion_criteria"}) J.put(merged, k, d.has(k) ? d.opt(k) : cur.opt(k));
        db.tx(new Runnable() {
            @Override public void run() {
                int v = cur.optInt("version") + 1;
                insertVersion(sid, v, merged, J.str(d, "source", "manual"));
                db.exec("UPDATE skills SET current_version = ?, name = ?, updated_at = ? WHERE id = ?", v, J.str(d, "name", cur.optString("name")), J.nowIso(), sid);
                if (d.has("bot_ids")) setBots(sid, d.optJSONArray("bot_ids") == null ? new JSONArray() : d.optJSONArray("bot_ids"));
            }
        });
        return full(sid, 0);
    }

    public JSONObject rollback(String sid, int version) {
        JSONObject old = full(sid, version);
        if (old == null) throw new ApiError(422, "Unknown skill version.");
        JSONObject d = new JSONObject();
        for (String k : new String[]{"instructions", "inputs", "tools", "completion_criteria"}) J.put(d, k, old.opt(k));
        return update(sid, d);
    }

    public void delete(final String sid) {
        db.tx(new Runnable() {
            @Override public void run() {
                db.exec("DELETE FROM skills WHERE id = ?", sid);
                db.exec("DELETE FROM bot_skills WHERE skill_id = ?", sid);
            }
        });
    }

    public JSONObject export(String sid) {
        JSONObject s = full(sid, 0);
        if (s == null) throw new ApiError(404, "Unknown skill.");
        JSONObject out = J.obj("format", FORMAT, "slug", s.optString("slug"), "name", s.optString("name"));
        for (String k : new String[]{"instructions", "inputs", "tools", "completion_criteria"}) J.put(out, k, J.redact(s.opt(k), null));
        return out;
    }

    public JSONObject importSkill(JSONObject p) {
        if (!FORMAT.equals(p.optString("format"))) throw new ApiError(422, "Unsupported skill format.");
        return create(p, "import");
    }

    /** Draft a skill from the tool steps of a run. */
    public JSONObject draftFromRun(String runId, String name, boolean consent) {
        if (!consent) throw new ApiError(422, "Recording a demonstration requires explicit consent.");
        List<JSONObject> steps = db.all("SELECT * FROM run_steps WHERE run_id = ? AND kind = 'tool' ORDER BY seq", runId);
        if (steps.isEmpty()) throw new ApiError(422, "That run has no tool steps to learn from.");
        JSONArray actions = new JSONArray();
        for (JSONObject s : steps) {
            JSONObject args = J.parse(s.optString("input_json")).optJSONObject("arguments");
            actions.put(J.obj("tool", s.optString("tool_name"), "arguments", args == null ? new JSONObject() : args,
                    "ok", "completed".equals(s.optString("status")), "error", s.opt("error")));
        }
        return draft(actions, name, "teach:" + runId);
    }

    /** Draft a skill from what the user did in the phone browser while they had taken over. */
    public JSONObject draftFromRecording(String botId, String name, boolean consent) {
        if (!consent) throw new ApiError(422, "Recording a demonstration requires explicit consent.");
        JSONArray rec = J.parseArr(db.scalar("SELECT recording_json FROM computer_sessions WHERE bot_id = ?", botId));
        if (rec.length() == 0) throw new ApiError(422, "Nothing was recorded. Take over the computer, tick 'Record to teach', then do the task once.");
        return draft(rec, name, "teach:recording");
    }

    JSONObject draft(JSONArray actions, String name, String source) {
        List<String> lines = new ArrayList<String>();
        JSONArray tools = new JSONArray(), inputs = new JSONArray();
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            JSONObject args = (JSONObject) J.redact(a.optJSONObject("arguments") == null ? new JSONObject() : a.optJSONObject("arguments"), null);
            Iterator<String> it = J.parse(args.toString()).keys();
            while (it.hasNext()) {
                String k = it.next();
                if ((k.equals("text") || k.equals("content") || k.equals("value")) && args.opt(k) instanceof String) {
                    String ph = "input_" + (inputs.length() + 1);
                    inputs.put(J.obj("name", ph, "description", "value typed for " + a.optString("tool") + "." + k));
                    J.put(args, k, "{{" + ph + "}}");
                }
            }
            boolean ok = !a.has("ok") || a.optBoolean("ok");
            lines.add((i + 1) + ". " + a.optString("tool") + " " + args + " — expected: " + (ok ? "success" : "handle failure: " + a.optString("error")));
            boolean seen = false;
            for (int k = 0; k < tools.length(); k++) if (tools.optString(k).equals(a.optString("tool"))) seen = true;
            if (!seen) tools.put(a.optString("tool"));
        }
        String instr = "DRAFT generated from a demonstration. Review every step; pages change.\nSteps:\n" + android.text.TextUtils.join("\n", lines)
                + "\n\nAfter each step, verify the result (read the page/file) before continuing. If a step fails, stop and ask the user via user.ask.";
        return create(J.obj("name", name == null || name.isEmpty() ? "Taught task" : name, "instructions", instr, "inputs", inputs, "tools", tools,
                "completion_criteria", "All steps verified; result reported to the user."), source);
    }
}
