package io.lowbot.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bot profiles: lifecycle, permission narrowing, Duplicate, export. Mirrors server/app/v2/bots.py. */
public final class Bots {
    public static final String[] DEFAULT_TOOLS = {
        "workspace.*", "web.fetch", "memory.*", "user.ask", "secret.request",
        "task.delegate", "task.get_status", "task.complete", "bot.message", "artifact.share",
        "browser.*", "routine.create",
    };
    static final List<String> ORG_ROLES = Arrays.asList("ceo", "head", "manager", "worker");
    static final Set<String> EDITABLE = new HashSet<String>(Arrays.asList(
        "name", "label", "avatar", "role_description", "instructions", "provider_profile_id", "model",
        "tools", "policy", "budget", "org_role", "reports_to", "can_create_bots",
        "team_memory_access", "computer_mode", "pinned", "hidden", "notify"));

    private final Backend b;
    private final Db db;

    Bots(Backend b) { this.b = b; this.db = b.core.db; }

    static JSONObject row(JSONObject r) {
        JSONObject bot = J.parse(r.toString());
        J.put(bot, "tools", J.parseArr(bot.optString("tools_json")));
        J.put(bot, "policy", J.parseArr(bot.optString("policy_json")));
        J.put(bot, "budget", J.parse(bot.optString("budget_json")));
        bot.remove("tools_json"); bot.remove("policy_json"); bot.remove("budget_json");
        for (String f : new String[]{"can_create_bots", "team_memory_access", "pinned", "hidden", "paused", "notify"})
            J.put(bot, f, J.bool(bot, f));
        return bot;
    }

    public JSONObject get(String id) {
        if (id == null) return null;
        JSONObject r = db.one("SELECT * FROM bots WHERE id = ?", id);
        return r == null ? null : row(r);
    }

    public JSONObject require(String id) {
        JSONObject bot = get(id);
        if (bot == null) throw new ApiError(404, "Unknown bot: " + id);
        return bot;
    }

    public JSONObject byHandle(String handle) {
        JSONObject r = db.one("SELECT * FROM bots WHERE handle = ? COLLATE NOCASE", handle.replaceFirst("^@", ""));
        return r == null ? null : row(r);
    }

    public List<JSONObject> list(boolean includeHidden) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        Map<String, String> st = statuses();
        for (JSONObject r : db.all("SELECT * FROM bots" + (includeHidden ? "" : " WHERE hidden = 0") + " ORDER BY pinned DESC, name COLLATE NOCASE")) {
            JSONObject bot = row(r);
            String s = st.get(bot.optString("id"));
            J.put(bot, "status", bot.optBoolean("paused") ? "paused" : (s == null ? "idle" : s));
            out.add(bot);
        }
        return out;
    }

    /** Status shown in the UI is derived from backend run state only. */
    Map<String, String> statuses() {
        List<String> order = Arrays.asList("waiting_approval", "waiting_input", "unknown_outcome", "running",
                "waiting_dependency", "retry_scheduled", "queued");
        String[] labels = {"needs_approval", "needs_input", "needs_resolution", "working", "waiting", "retrying", "queued"};
        Map<String, Integer> best = new HashMap<String, Integer>();
        for (JSONObject r : db.all("SELECT bot_id, status FROM runs WHERE status IN ('waiting_approval','waiting_input','unknown_outcome','running','waiting_dependency','retry_scheduled','queued')")) {
            int rank = order.indexOf(r.optString("status"));
            Integer cur = best.get(r.optString("bot_id"));
            if (cur == null || rank < cur) best.put(r.optString("bot_id"), rank);
        }
        Map<String, String> out = new HashMap<String, String>();
        for (Map.Entry<String, Integer> e : best.entrySet()) out.put(e.getKey(), labels[e.getValue()]);
        return out;
    }

    private String uniqueHandle(String name) {
        String base = J.slug(name);
        if (base.length() > 32) base = base.substring(0, 32);
        String h = base;
        int n = 2;
        while (db.one("SELECT 1 FROM bots WHERE handle = ?", h) != null) h = base + "-" + (n++);
        return h;
    }

    private void validate(JSONObject d) {
        if (d.has("name") && d.optString("name").trim().isEmpty()) throw new ApiError(422, "Bot name is required.");
        String role = J.str(d, "org_role", null);
        if (role != null && !role.isEmpty() && !ORG_ROLES.contains(role)) throw new ApiError(422, "org_role must be one of ceo, head, manager, worker.");
        String cm = J.str(d, "computer_mode", null);
        if (cm != null && !cm.equals("shared") && !cm.equals("isolated")) throw new ApiError(422, "computer_mode must be shared or isolated.");
        JSONArray pol = d.optJSONArray("policy");
        if (pol != null) for (int i = 0; i < pol.length(); i++) {
            JSONObject r = pol.optJSONObject(i);
            if (r == null || r.optString("tool").isEmpty() || !Arrays.asList("allow", "ask", "deny").contains(r.optString("effect")))
                throw new ApiError(422, "Policy rules need {tool, effect: allow|ask|deny}.");
        }
        String rt = J.str(d, "reports_to", null);
        if (rt != null && !rt.isEmpty() && get(rt) == null) throw new ApiError(422, "reports_to must reference an existing bot.");
    }

    private static void checkFields(JSONObject d, boolean allowHandle) {
        Iterator<String> it = d.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!EDITABLE.contains(k) && !(allowHandle && k.equals("handle")))
                throw new ApiError(422, "Unsupported bot field: " + k);
        }
    }

    public JSONObject create(final JSONObject d, final JSONObject createdBy) {
        checkFields(d, true);
        validate(d);
        JSONArray toolsArr = d.optJSONArray("tools");
        List<String> tools = toolsArr != null && toolsArr.length() > 0 ? J.strings(toolsArr) : Arrays.asList(DEFAULT_TOOLS);
        JSONArray policy = d.optJSONArray("policy") == null ? new JSONArray() : d.optJSONArray("policy");
        boolean canCreate = J.bool(d, "can_create_bots");
        if (createdBy != null) {
            if (!createdBy.optBoolean("can_create_bots")) throw new ApiError(403, "This bot is not allowed to create bots.");
            List<String> parent = J.strings(createdBy.optJSONArray("tools"));
            for (String t : tools) {
                boolean ok = false;
                for (String p : parent) if (p.equals(t) || J.glob(p, t)) ok = true;
                if (!ok) throw new ApiError(403, "A created bot cannot receive tools its creator does not have.");
            }
            JSONArray merged = J.parseArr(createdBy.optJSONArray("policy").toString());
            for (int i = 0; i < policy.length(); i++) {
                JSONObject r = policy.optJSONObject(i);
                if (r != null && !"allow".equals(r.optString("effect"))) merged.put(r);
            }
            policy = merged;
            canCreate = false;
        }
        final String id = J.id("bot");
        String handle = J.str(d, "handle", null);
        handle = handle == null || handle.isEmpty() ? uniqueHandle(J.str(d, "name", "bot")) : handle;
        if (db.one("SELECT 1 FROM bots WHERE handle = ?", handle) != null) handle = uniqueHandle(handle);
        final String now = J.nowIso();
        final JSONObject row = J.obj("id", id, "name", J.str(d, "name", "New bot"), "handle", handle,
                "label", J.str(d, "label", ""), "avatar", J.str(d, "avatar", "🤖"),
                "role_description", J.str(d, "role_description", ""), "instructions", J.str(d, "instructions", ""),
                "provider_profile_id", emptyNull(J.str(d, "provider_profile_id", null)), "model", emptyNull(J.str(d, "model", null)),
                "tools_json", J.arr(tools).toString(), "policy_json", policy.toString(),
                "budget_json", d.optJSONObject("budget") == null ? "{}" : d.optJSONObject("budget").toString(),
                "org_role", emptyNull(J.str(d, "org_role", null)), "reports_to", emptyNull(J.str(d, "reports_to", null)),
                "can_create_bots", canCreate, "created_by_bot_id", createdBy == null ? null : createdBy.optString("id"),
                "team_memory_access", J.bool(d, "team_memory_access"), "computer_mode", J.str(d, "computer_mode", "shared"),
                "notify", !d.has("notify") || J.bool(d, "notify"),
                "pinned", J.bool(d, "pinned"), "hidden", J.bool(d, "hidden"), "paused", false, "created_at", now, "updated_at", now);
        db.tx(new Runnable() {
            @Override public void run() {
                db.insert("bots", row);
                b.core.emit("bot.created", null, null, null, id, J.obj("name", row.optString("name")));
                b.core.audit("bot.create", createdBy == null ? "user" : "bot", createdBy == null ? null : createdBy.optString("id"),
                        null, null, null, null, null, J.obj("bot_id", id));
            }
        });
        return require(id);
    }

    static Object emptyNull(String s) { return s == null || s.isEmpty() ? null : s; }

    public JSONObject update(final String id, final JSONObject d) {
        require(id);
        checkFields(d, false);
        validate(d);
        String rt = J.str(d, "reports_to", null);
        if (id.equals(rt)) throw new ApiError(422, "A bot cannot report to itself.");
        if (rt != null && !rt.isEmpty()) {
            Set<String> seen = new HashSet<String>();
            seen.add(id);
            String cur = rt;
            while (cur != null) {
                if (!seen.add(cur)) throw new ApiError(422, "reports_to would create a cycle.");
                JSONObject p = get(cur);
                cur = p == null ? null : J.str(p, "reports_to", null);
            }
        }
        db.tx(new Runnable() {
            @Override public void run() {
                Iterator<String> it = d.keys();
                List<String> fields = new ArrayList<String>();
                while (it.hasNext()) {
                    String k = it.next();
                    fields.add(k);
                    Object v = d.opt(k);
                    String col = k.equals("tools") ? "tools_json" : k.equals("policy") ? "policy_json" : k.equals("budget") ? "budget_json" : k;
                    if (col.endsWith("_json")) v = v == null || v == JSONObject.NULL ? (k.equals("budget") ? "{}" : "[]") : v.toString();
                    if (v instanceof String && (k.equals("provider_profile_id") || k.equals("model") || k.equals("org_role") || k.equals("reports_to")))
                        v = emptyNull((String) v);
                    db.exec("UPDATE bots SET " + col + " = ?, updated_at = ? WHERE id = ?", v == JSONObject.NULL ? null : v, J.nowIso(), id);
                }
                b.core.emit("bot.updated", null, null, null, id, J.obj("fields", J.arr(fields)));
                b.core.audit("bot.update", "user", null, null, null, null, null, null, J.obj("bot_id", id, "fields", J.arr(fields)));
            }
        });
        return require(id);
    }

    public JSONObject setPaused(final String id, final boolean paused) {
        require(id);
        db.tx(new Runnable() {
            @Override public void run() {
                String now = J.nowIso();
                db.exec("UPDATE bots SET paused = ?, updated_at = ? WHERE id = ?", paused, now, id);
                if (paused) {
                    db.exec("UPDATE runs SET status = 'paused', updated_at = ? WHERE bot_id = ? AND status IN ('queued','retry_scheduled')", now, id);
                    db.exec("UPDATE runs SET control = 'pause' WHERE bot_id = ? AND status = 'running'", id);
                } else {
                    db.exec("UPDATE runs SET status = 'queued', control = '', updated_at = ? WHERE bot_id = ? AND status = 'paused'", now, id);
                }
                b.core.emit(paused ? "bot.paused" : "bot.resumed", null, null, null, id, null);
                b.core.audit(paused ? "bot.pause" : "bot.resume", "user", null, null, null, null, null, null, J.obj("bot_id", id));
            }
        });
        b.wake();
        return require(id);
    }

    /**
     * Grok Bot "Duplicate": "<name> copy" with profile, settings, skills, routines and avatar;
     * no history, memory or attachments. Routines are copied disabled so they never double-fire.
     */
    public JSONObject duplicate(String id) {
        final JSONObject src = require(id);
        JSONObject d = new JSONObject();
        for (String k : EDITABLE) if (src.has(k) && !k.equals("pinned") && !k.equals("hidden")) J.put(d, k, src.opt(k));
        J.put(d, "name", src.optString("name") + " copy");
        final JSONObject copy = create(d, null);
        final String srcId = id;
        db.tx(new Runnable() {
            @Override public void run() {
                for (JSONObject r : db.all("SELECT skill_id FROM bot_skills WHERE bot_id = ?", srcId))
                    db.insert("bot_skills", J.obj("bot_id", copy.optString("id"), "skill_id", r.optString("skill_id")));
                for (JSONObject r : db.all("SELECT * FROM routines WHERE bot_id = ?", srcId)) {
                    String now = J.nowIso();
                    db.insert("routines", J.obj("id", J.id("rtn"), "bot_id", copy.optString("id"), "name", r.optString("name"),
                            "kind", r.optString("kind"), "schedule_json", r.optString("schedule_json"), "timezone", r.optString("timezone"),
                            "prompt", r.optString("prompt"), "conversation_id", null, "enabled", 0,
                            "overlap_policy", r.optString("overlap_policy"), "catchup_policy", r.optString("catchup_policy"),
                            "created_at", now, "updated_at", now));
                }
                b.core.audit("bot.duplicate", "user", null, null, null, null, null, null, J.obj("source_bot_id", srcId, "bot_id", copy.optString("id")));
            }
        });
        return copy;
    }

    public JSONObject export(String id) {
        JSONObject bot = require(id);
        JSONObject out = new JSONObject();
        for (String k : EDITABLE) if (bot.has(k) && !k.equals("provider_profile_id") && !k.equals("reports_to")) J.put(out, k, bot.opt(k));
        List<String> skills = new ArrayList<String>();
        for (JSONObject r : db.all("SELECT s.slug FROM bot_skills bs JOIN skills s ON s.id = bs.skill_id WHERE bs.bot_id = ?", id))
            skills.add(r.optString("slug"));
        return J.obj("format", "opendots.bot.v1", "bot", out, "skills", J.arr(skills));
    }

    public JSONObject importBot(JSONObject payload) {
        if (!"opendots.bot.v1".equals(payload.optString("format"))) throw new ApiError(422, "Unsupported export format.");
        JSONObject src = payload.optJSONObject("bot");
        JSONObject d = new JSONObject();
        if (src != null) for (String k : EDITABLE) if (src.has(k)) J.put(d, k, src.opt(k));
        return create(d, null);
    }

    /** Delete cancels active work and removes profile, memories and routines (Grok: deleting a bot deletes its routines). */
    public void delete(final String id) {
        require(id);
        db.tx(new Runnable() {
            @Override public void run() {
                String now = J.nowIso();
                db.exec("UPDATE runs SET status = 'cancelled', finished_at = ?, updated_at = ?, error = 'bot deleted' WHERE bot_id = ? AND status IN "
                        + "('queued','paused','retry_scheduled','waiting_approval','waiting_input','waiting_dependency')", now, now, id);
                db.exec("UPDATE runs SET control = 'cancel' WHERE bot_id = ? AND status = 'running'", id);
                db.exec("UPDATE tasks SET status = 'cancelled', updated_at = ? WHERE bot_id = ? AND status NOT IN ('completed','failed','cancelled')", now, id);
                db.exec("UPDATE approvals SET status = 'invalidated' WHERE bot_id = ? AND status = 'pending'", id);
                db.exec("DELETE FROM routines WHERE bot_id = ?", id);
                for (JSONObject m : db.all("SELECT id FROM memories WHERE bot_id = ? AND scope = 'bot'", id))
                    db.exec("DELETE FROM memories_fts WHERE memory_id = ?", m.optString("id"));
                db.exec("DELETE FROM memories WHERE bot_id = ? AND scope = 'bot'", id);
                db.exec("DELETE FROM bot_skills WHERE bot_id = ?", id);
                db.exec("DELETE FROM memberships WHERE member_type = 'bot' AND member_id = ?", id);
                db.exec("DELETE FROM computer_sessions WHERE bot_id = ?", id);
                db.exec("UPDATE bots SET reports_to = NULL WHERE reports_to = ?", id);
                db.exec("UPDATE conversations SET archived = 1 WHERE kind = 'private' AND default_bot_id = ?", id);
                db.exec("DELETE FROM bots WHERE id = ?", id);
                b.core.emit("bot.deleted", null, null, null, id, null);
                b.core.audit("bot.delete", "user", null, null, null, null, null, null, J.obj("bot_id", id));
            }
        });
    }

    public boolean isSubordinate(String managerId, String botId) {
        JSONObject cur = get(botId);
        Set<String> seen = new HashSet<String>();
        while (cur != null && J.str(cur, "reports_to", null) != null && seen.add(cur.optString("id"))) {
            if (managerId.equals(cur.optString("reports_to"))) return true;
            cur = get(cur.optString("reports_to"));
        }
        return false;
    }

    public String canDelegate(JSONObject from, JSONObject to) {
        if (!b.core.kvBool("hierarchy_enforced", false)) return null;
        if (J.str(from, "org_role", null) == null || J.str(to, "org_role", null) == null) return null;
        if (isSubordinate(from.optString("id"), to.optString("id"))) return null;
        return "Hierarchy rules: " + from.optString("name") + " may only delegate to its reports.";
    }
}
