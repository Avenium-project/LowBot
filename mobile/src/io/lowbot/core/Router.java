package io.lowbot.core;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.lowbot.engine.Tools;
import io.lowbot.tools.Mcp;

/**
 * The local /api/v2 used by the bundled UI through the native bridge (no network, no
 * server). Paths and JSON shapes match the LowBot server so one UI serves both.
 */
public final class Router {
    /** Computer endpoints are implemented by the Android layer. */
    public interface ComputerApi {
        JSONObject list();
        byte[] screenshot(String sid) throws Exception;
        JSONObject takeOver(String sid);
        JSONObject resume(String sid);
        JSONObject open(String botId);
        void record(String sid, boolean on);
        void reset();
    }

    /** The bots' Linux (proot + Alpine), implemented by the Android layer. */
    public interface LinuxApi {
        JSONObject status();
        JSONObject install();
        void remove();
        JSONObject log(String botId);
        void reset(String botId);
    }

    public static final class Response {
        public int status = 200;
        public String type = "application/json";
        public String text;
        public byte[] bytes;
        public String filename;

        static Response json(Object o) {
            Response r = new Response();
            r.text = o == null ? "null" : o instanceof List ? Db.toArray(castList(o)).toString() : o.toString();
            return r;
        }

        @SuppressWarnings("unchecked")
        static List<JSONObject> castList(Object o) { return (List<JSONObject>) o; }

        static Response error(int status, String msg) {
            Response r = json(J.obj("detail", msg));
            r.status = status;
            return r;
        }
    }

    final Backend b;
    public volatile ComputerApi computer;
    public volatile LinuxApi linux;
    public volatile Mcp mcp;

    public Router(Backend b) { this.b = b; }

    public Response handle(String method, String rawPath, String bodyText) {
        try {
            Uri u = Uri.parse("http://local" + (rawPath.startsWith("/") ? rawPath : "/" + rawPath));
            String path = u.getPath() == null ? "/" : u.getPath();
            if (path.startsWith("/api/v2")) path = path.substring(7);
            JSONObject body = bodyText == null || bodyText.isEmpty() ? new JSONObject() : J.parse(bodyText);
            return route(method.toUpperCase(), path, u, body);
        } catch (ApiError e) {
            return Response.error(e.status, e.getMessage());
        } catch (Exception e) {
            android.util.Log.e("LowBot", "api " + method + " " + rawPath, e);
            return Response.error(500, "Internal error: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static String q(Uri u, String k, String def) {
        String v = u.getQueryParameter(k);
        return v == null || v.isEmpty() ? def : v;
    }

    static int qi(Uri u, String k, int def, int max) {
        try { return Math.min(max, Integer.parseInt(q(u, k, String.valueOf(def)))); } catch (NumberFormatException e) { return def; }
    }

    Response route(String m, String path, Uri u, JSONObject body) throws Exception {
        String[] p = path.replaceAll("^/+|/+$", "").split("/");
        String a = p.length > 0 ? p[0] : "", id = p.length > 1 ? Uri.decode(p[1]) : null, sub = p.length > 2 ? p[2] : null;
        boolean get = m.equals("GET"), post = m.equals("POST"), patch = m.equals("PATCH"), del = m.equals("DELETE");

        if (a.equals("health")) return Response.json(health());

        // --------------------------------------------------------------- bots
        if (a.equals("bots")) {
            if (id == null && get) return Response.json(b.bots.list(!"false".equals(q(u, "include_hidden", "true"))));
            if (id == null && post) return Response.json(b.bots.create(body, null));
            if ("import".equals(id) && post) return Response.json(b.bots.importBot(body));
            if (sub == null && get) return Response.json(b.bots.require(id));
            if (sub == null && patch) return Response.json(b.bots.update(id, body));
            if (sub == null && del) { b.bots.delete(id); return Response.json(J.obj("deleted", id)); }
            if ("pause".equals(sub)) return Response.json(b.bots.setPaused(id, true));
            if ("resume".equals(sub)) return Response.json(b.bots.setPaused(id, false));
            if ("duplicate".equals(sub)) return Response.json(b.bots.duplicate(id));
            if ("export".equals(sub)) return Response.json(b.bots.export(id));
            if ("conversation".equals(sub)) return Response.json(b.tasks.privateConversation(id));
            if ("mind".equals(sub) && get) { b.bots.require(id); return Response.json(b.mind.all(id)); }
            if ("mind".equals(sub) && p.length == 4 && (post || patch)) {
                b.bots.require(id);
                String what = p[3];
                if ("soul".equals(what)) b.mind.setSoul(id, body.optString("content"));
                else if ("agents".equals(what)) b.mind.replaceHandoff(id, body.optString("content"), null, 0, "user");
                else if ("memories".equals(what)) b.mind.remember(id, body.optString("title"), body.optString("content"));
                else throw new ApiError(404, "Unknown file.");
                return Response.json(b.mind.all(id));
            }
            if ("mind".equals(sub) && p.length == 5 && del && "memories".equals(p[3])) { b.mind.forget(id, Uri.decode(p[4])); return Response.json(b.mind.all(id)); }
        }

        // ------------------------------------------------------ conversations
        if (a.equals("conversations")) {
            if (id == null && get) return Response.json(b.tasks.listConversations());
            if (id == null && post) return Response.json(b.tasks.createConversation(body.optString("kind", "group"), J.strings(body.optJSONArray("bot_ids")), body.optString("title")));
            if (sub == null && get) return Response.json(b.tasks.getConversation(id));
            if (sub == null && patch) return Response.json(b.tasks.updateConversation(id, body));
            if ("members".equals(sub) && post) return Response.json(b.tasks.addMember(id, body.optString("bot_id")));
            if ("messages".equals(sub) && get) {
                b.tasks.getConversation(id);
                return Response.json(b.tasks.messages(id, Long.parseLong(q(u, "after", "0")), qi(u, "limit", 200, 500)));
            }
            if ("messages".equals(sub) && post) {
                if (body.optString("text").length() > 50000) throw new ApiError(422, "Message too long.");
                JSONArray att = body.optJSONArray("attachments");
                if (att != null) for (int i = 0; i < att.length(); i++) {
                    JSONObject x = att.optJSONObject(i);
                    if (x != null && J.str(x, "artifact_id", null) != null && b.artifacts.get(x.optString("artifact_id")) == null) throw new ApiError(422, "Unknown attachment.");
                    if (x != null) x.remove("url"); // images are read from the artifact store when the bot runs
                }
                return Response.json(b.tasks.postUserMessage(id, body.optString("text"), J.str(body, "client_msg_id", null), att, J.str(body, "thread_root_id", null)));
            }
            if ("unread".equals(sub) && post) { b.tasks.markUnread(id); return Response.json(J.obj("ok", true)); }
            if ("read".equals(sub) && post) { b.tasks.markRead(id, body.optLong("seq")); return Response.json(J.obj("ok", true)); }
        }

        // -------------------------------------------------------------- tasks
        if (a.equals("tasks")) {
            if (id == null && get) {
                StringBuilder sql = new StringBuilder("SELECT t.*, r.id AS run_id, r.status AS run_status FROM tasks t LEFT JOIN runs r ON r.task_id = t.id WHERE 1 = 1");
                List<Object> args = new ArrayList<Object>();
                String st = q(u, "status", null);
                if ("active".equals(st)) sql.append(" AND t.status NOT IN ('completed','failed','cancelled')");
                else if (st != null) { sql.append(" AND t.status = ?"); args.add(st); }
                if (q(u, "bot_id", null) != null) { sql.append(" AND t.bot_id = ?"); args.add(q(u, "bot_id", null)); }
                if (q(u, "conversation_id", null) != null) { sql.append(" AND t.conversation_id = ?"); args.add(q(u, "conversation_id", null)); }
                sql.append(" ORDER BY t.created_at DESC LIMIT ?");
                args.add(qi(u, "limit", 100, 500));
                return Response.json(b.core.db.all(sql.toString(), args.toArray()));
            }
            if (sub == null && get) {
                JSONObject tree = b.tasks.taskTree(id);
                JSONObject run = tree.optJSONObject("run");
                if (run != null) {
                    JSONArray steps = new JSONArray();
                    for (JSONObject s : b.core.db.all("SELECT * FROM run_steps WHERE run_id = ? ORDER BY seq", run.optString("id"))) {
                        J.put(s, "input", J.parse(s.optString("input_json")));
                        J.put(s, "output", J.parse(s.optString("output_json")));
                        s.remove("input_json"); s.remove("output_json");
                        steps.put(s);
                    }
                    J.put(tree, "steps", steps);
                    J.put(run, "waiting", J.parse(run.optString("waiting_json")));
                    run.remove("waiting_json");
                }
                return Response.json(tree);
            }
            if ("cancel".equals(sub)) return Response.json(b.tasks.cancel(id, "user"));
            if ("pause".equals(sub)) { b.tasks.pause(id); return Response.json(J.obj("ok", true)); }
            if ("resume".equals(sub)) { b.tasks.resume(id); return Response.json(J.obj("ok", true)); }
            if ("answer".equals(sub)) { b.tasks.answerInput(id, body.optString("answer")); return Response.json(J.obj("ok", true)); }
            if ("secret".equals(sub)) { b.answerSecret(id, body.optString("value")); return Response.json(J.obj("ok", true)); }
        }
        if (a.equals("runs") && "resolve".equals(sub)) { b.engine.resolveUnknown(id, body.optString("outcome"), body.optString("note")); return Response.json(J.obj("ok", true)); }

        // ---------------------------------------------------------- approvals
        if (a.equals("approvals")) {
            if (id == null && get) return Response.json(b.approvals.list(q(u, "status", u.getQueryParameter("status") == null ? "pending" : "")));
            if ("decide".equals(sub)) return Response.json(b.approvals.decide(id, body.optString("decision"), body.optString("args_hash")));
            if ("edit".equals(sub)) {
                JSONObject ap = b.approvals.get(id);
                if (ap == null) throw new ApiError(404, "Unknown approval");
                JSONObject bot = b.bots.get(ap.optString("bot_id"));
                Tools.Spec spec = bot == null ? null : b.tools.get(bot, ap.optString("tool"));
                if (spec == null) throw new ApiError(409, "Tool no longer available");
                JSONObject args = body.optJSONObject("arguments") == null ? new JSONObject() : body.optJSONObject("arguments");
                return Response.json(b.approvals.edit(id, args, spec.validate(args)));
            }
        }

        // ------------------------------------------------------------- events
        if (a.equals("events") && get) {
            List<JSONObject> rows = b.core.eventsAfter(Long.parseLong(q(u, "after", "0")), qi(u, "limit", 200, 1000), q(u, "conversation_id", null));
            return Response.json(J.obj("events", Db.toArray(rows), "cursor", rows.isEmpty() ? Long.parseLong(q(u, "after", "0")) : rows.get(rows.size() - 1).optLong("id")));
        }

        // ------------------------------------------------------ notifications
        if (a.equals("notifications")) {
            if (id == null && get) {
                boolean unread = "true".equals(q(u, "unread", "false"));
                return Response.json(J.obj("items", Db.toArray(b.core.db.all("SELECT * FROM notifications" + (unread ? " WHERE read_at IS NULL" : "") + " ORDER BY created_at DESC LIMIT ?", qi(u, "limit", 100, 500))),
                        "unread", b.core.db.count("SELECT COUNT(*) FROM notifications WHERE read_at IS NULL")));
            }
            final String nid = id;
            if ("read-all".equals(id)) {
                b.core.db.tx(new Runnable() { public void run() { b.core.db.exec("UPDATE notifications SET read_at = ? WHERE read_at IS NULL", J.nowIso()); } });
                return Response.json(J.obj("ok", true));
            }
            if ("read".equals(sub)) {
                b.core.db.tx(new Runnable() { public void run() { b.core.db.exec("UPDATE notifications SET read_at = ? WHERE id = ? AND read_at IS NULL", J.nowIso(), nid); } });
                return Response.json(J.obj("ok", true));
            }
        }

        // ------------------------------------------------------------- search
        if (a.equals("search") && get) return Response.json(search(q(u, "q", ""), q(u, "scope", "all")));

        // ----------------------------------------------------------- routines
        if (a.equals("routines")) {
            if ("parse".equals(id) && post) {
                String tz = J.str(body, "timezone", b.core.settings.timezone);
                JSONObject s = Routines.parse(body.optString("text"), tz);
                List<String> runs = Routines.nextRuns(s, tz, 5, null);
                return Response.json(J.obj("schedule", s, "timezone", tz, "next_runs_utc", J.arr(runs), "next_runs_local", J.arr(Routines.describeLocal(runs, tz))));
            }
            if (id == null && get) return Response.json(b.routines.list(q(u, "bot_id", null)));
            if (id == null && post) {
                if (J.str(body, "bot_id", null) == null || b.bots.get(body.optString("bot_id")) == null) throw new ApiError(422, "bot_id is required");
                return Response.json(b.routines.create(body.optString("bot_id"), J.str(body, "name", "Routine"), body.optString("prompt"), body.optString("schedule"),
                        J.str(body, "kind", null), J.str(body, "timezone", null), J.str(body, "conversation_id", null), J.str(body, "overlap_policy", "skip"),
                        J.str(body, "catchup_policy", "latest"), !body.has("enabled") || J.bool(body, "enabled")));
            }
            if (sub == null && patch) return Response.json(b.routines.update(id, body));
            if (sub == null && del) { b.routines.delete(id); return Response.json(J.obj("deleted", id)); }
            if ("history".equals(sub)) return Response.json(b.routines.history(id));
            if ("simulate".equals(sub)) return Response.json(b.routines.simulate(id));
            if ("test-run".equals(sub)) return Response.json(b.routines.testRun(id));
        }
        if (a.equals("hooks")) throw new ApiError(404, "Webhooks are not available on the phone (no public address).");

        // ----------------------------------------------------------- projects
        if (a.equals("projects")) {
            if (id == null && get) return Response.json(b.mind.projects());
            if (id == null && post) return Response.json(b.mind.createWorkspace(body.optString("name"), body.optString("agents_md", ""), J.strings(body.optJSONArray("members"))));
            if (sub == null && get) return Response.json(b.mind.workspace(id));
            if (sub == null && patch) {
                if (body.has("agents_md")) b.mind.setProjectRules(id, body.optString("agents_md"));
                if (body.has("members")) b.mind.setMembers(id, J.strings(body.optJSONArray("members")));
                return Response.json(b.mind.workspace(id));
            }
            if (sub == null && del) { b.mind.deleteWorkspace(id); return Response.json(J.obj("deleted", id)); }
            if ("files".equals(sub) && get) return Response.json(b.mind.files(id, 500));
            if ("agents".equals(sub) && (post || patch)) { b.mind.setProjectRules(id, body.optString("content")); return Response.json(b.mind.projects()); }
            if ("chat".equals(sub) && post) {
                // The workspace's group chat: all members, bound to the workspace folder and rules.
                JSONObject w = b.mind.workspace(id);
                List<String> mem = J.strings(w.optJSONArray("members"));
                if (mem.isEmpty()) throw new ApiError(422, "Add at least one bot to the workspace first.");
                String existing = b.core.db.scalar("SELECT id FROM conversations WHERE kind = 'group' AND project = ? AND archived = 0 ORDER BY created_at LIMIT 1", w.optString("name"));
                if (existing != null) {
                    for (String mb : mem) if (b.core.db.one("SELECT 1 FROM memberships WHERE conversation_id = ? AND member_type = 'bot' AND member_id = ?", existing, mb) == null)
                        b.tasks.addMember(existing, mb);
                    return Response.json(b.tasks.getConversation(existing));
                }
                JSONObject c = b.tasks.createConversation("group", mem.size() > 12 ? mem.subList(0, 12) : mem, w.optString("name"));
                return Response.json(b.tasks.updateConversation(c.optString("id"), J.obj("project", w.optString("name"))));
            }
        }

        // ------------------------------------------------------------- skills
        if (a.equals("skills")) {
            if (id == null && get) return Response.json(b.skills.list());
            if (id == null && post) return Response.json(b.skills.create(body, "manual"));
            if ("import".equals(id) && post) return Response.json(b.skills.importSkill(body));
            if ("teach".equals(id) && post) {
                if (J.str(body, "bot_id", null) != null) return Response.json(b.skills.draftFromRecording(body.optString("bot_id"), J.str(body, "name", "Taught task"), J.bool(body, "consent")));
                return Response.json(b.skills.draftFromRun(body.optString("run_id"), J.str(body, "name", "Taught task"), J.bool(body, "consent")));
            }
            if (sub == null && patch) return Response.json(b.skills.update(id, body));
            if (sub == null && del) { b.skills.delete(id); return Response.json(J.obj("deleted", id)); }
            if ("rollback".equals(sub)) return Response.json(b.skills.rollback(id, body.optInt("version")));
            if ("export".equals(sub)) return Response.json(b.skills.export(id));
        }

        // ------------------------------------------------------------- memory
        if (a.equals("memories")) {
            if (id == null && get) {
                String query = q(u, "q", null), botId = q(u, "bot_id", null);
                int limit = qi(u, "limit", 100, 500);
                if (query != null) {
                    List<JSONObject> out = new ArrayList<JSONObject>();
                    for (JSONObject r : b.memory.search(query, null, limit))
                        if (botId == null || J.str(r, "bot_id", null) == null || botId.equals(r.optString("bot_id"))) out.add(r);
                    return Response.json(out);
                }
                return Response.json(b.memory.list(null, botId, limit));
            }
            if (id == null && post) return Response.json(b.memory.save(body.optString("content"), J.str(body, "scope", "bot"), J.str(body, "bot_id", null), "user", "user"));
            if (patch) return Response.json(b.memory.update(id, body.optString("content")));
            if (del) { b.memory.delete(id); return Response.json(J.obj("deleted", id)); }
        }

        // ---------------------------------------------------------- providers
        if (a.equals("providers")) {
            if ("presets".equals(id)) return Response.json(b.providers.presets());
            if (id == null && get) return Response.json(J.obj("profiles", Db.toArray(b.providers.list()), "default_profile_id", b.providers.defaultId()));
            if (id == null && post) return Response.json(b.providers.upsert(body, null));
            if (sub == null && patch) return Response.json(b.providers.upsert(body, id));
            if (sub == null && del) { b.providers.delete(id); return Response.json(J.obj("deleted", id)); }
            if ("default".equals(sub)) {
                if (b.providers.get(id) == null) throw new ApiError(404, "Unknown profile");
                b.core.kvSet("default_provider_profile_id", id);
                return Response.json(J.obj("default_profile_id", id));
            }
            if ("test".equals(sub)) return Response.json(b.providers.test(id, J.str(body, "model", null)));
        }

        // ----------------------------------------------------- policy/settings
        if (a.equals("policies")) {
            if (id == null && get) {
                JSONArray tools = new JSONArray();
                for (java.util.Map.Entry<String, Tools.Spec> e : b.tools.all(J.obj("id", "", "tools", new JSONArray().put("*"))).entrySet())
                    tools.put(J.obj("name", e.getKey(), "effect_kind", e.getValue().effectKind, "default", e.getValue().defaultEffect, "hard_ask", e.getValue().hardAsk, "description", e.getValue().description));
                return Response.json(J.obj("rules", Db.toArray(b.core.db.all("SELECT * FROM policy_rules ORDER BY created_at")),
                        "hierarchy_enforced", b.core.kvBool("hierarchy_enforced", false), "tools", tools));
            }
            if (id == null && post) {
                final JSONObject bd = body;
                if (!Arrays.asList("allow", "ask", "deny").contains(bd.optString("effect")) || bd.optString("tool").isEmpty()) throw new ApiError(422, "Need tool pattern and effect allow|ask|deny");
                final String pid = J.id("pol");
                b.core.db.tx(new Runnable() { public void run() {
                    b.core.db.insert("policy_rules", J.obj("id", pid, "bot_id", J.str(bd, "bot_id", null), "tool_pattern", bd.optString("tool"), "effect", bd.optString("effect"), "created_at", J.nowIso()));
                    b.core.audit("policy.add", "user", null, null, null, null, null, null, J.obj("rule_id", pid, "tool", bd.optString("tool"), "effect", bd.optString("effect")));
                } });
                return Response.json(J.obj("id", pid));
            }
            if (del) {
                final String pid = id;
                b.core.db.tx(new Runnable() { public void run() {
                    b.core.db.exec("DELETE FROM policy_rules WHERE id = ?", pid);
                    b.core.audit("policy.delete", "user", null, null, null, null, null, null, J.obj("rule_id", pid));
                } });
                return Response.json(J.obj("deleted", id));
            }
        }
        if (a.equals("settings")) {
            if ("hierarchy".equals(id)) { b.core.kvSet("hierarchy_enforced", J.bool(body, "enforced") ? "1" : "0"); return Response.json(J.obj("hierarchy_enforced", J.bool(body, "enforced"))); }
            if (id == null && get) return Response.json(settings());
            if (id == null && (post || patch)) {
                if (body.has("auto_review")) b.core.kvSet("auto_review", J.bool(body, "auto_review") ? "1" : "0");
                if (body.has("local_execution")) {
                    String le = body.optString("local_execution");
                    if (!Arrays.asList("ask", "always", "never").contains(le)) throw new ApiError(422, "local_execution must be ask, always or never");
                    b.core.kvSet("local_execution", le);
                }
                if (body.has("allow_private_network")) {
                    b.core.kvSet("allow_private_network", J.bool(body, "allow_private_network") ? "1" : "0");
                    b.core.settings.allowPrivateNetwork = J.bool(body, "allow_private_network");
                }
                if (body.has("timezone")) {
                    Routines.zone(body.optString("timezone"));
                    b.core.kvSet("timezone", body.optString("timezone"));
                    b.core.settings.timezone = body.optString("timezone");
                }
                b.core.db.tx(new Runnable() { public void run() { b.core.audit("settings.update", "user", null, null, null, null, null, null, null); } });
                return Response.json(settings());
            }
        }

        // ---------------------------------------------------------- artifacts
        if (a.equals("artifacts")) {
            if (id == null && get) return Response.json(b.artifacts.list(q(u, "conversation_id", null), q(u, "task_id", null)));
            if ("upload".equals(id) && post) {
                byte[] data = Base64.decode(body.optString("data_base64"), Base64.DEFAULT);
                return Response.json(b.artifacts.create(body.optString("name", "upload"), data, J.str(body, "mime", null), null, null, null,
                        J.str(body, "conversation_id", q(u, "conversation_id", null))));
            }
            if ("download".equals(sub)) {
                JSONObject meta = b.artifacts.get(id);
                File f = b.artifacts.path(id);
                if (meta == null || f == null) throw new ApiError(404, "File does not exist");
                Response r = new Response();
                r.type = meta.optString("mime");
                r.bytes = readFile(f);
                r.filename = meta.optString("name");
                return r;
            }
        }

        // ---------------------------------------------------------- usage/audit
        if (a.equals("usage")) return Response.json(b.providers.usageSummary(q(u, "bot_id", null)));
        if (a.equals("audit")) {
            if ("export".equals(id)) {
                StringBuilder sb = new StringBuilder();
                for (JSONObject r : b.core.db.all("SELECT * FROM audit_log ORDER BY id")) sb.append(r).append('\n');
                Response r = new Response();
                r.type = "application/x-ndjson";
                r.bytes = sb.toString().getBytes("UTF-8");
                r.filename = "lowbot-audit.jsonl";
                return r;
            }
            List<JSONObject> rows = q(u, "task_id", null) != null
                    ? b.core.db.all("SELECT * FROM audit_log WHERE task_id = ? ORDER BY id DESC LIMIT ?", q(u, "task_id", null), qi(u, "limit", 200, 2000))
                    : b.core.db.all("SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", qi(u, "limit", 200, 2000));
            for (JSONObject r : rows) { J.put(r, "summary", J.parse(r.optString("summary_json"))); r.remove("summary_json"); }
            return Response.json(rows);
        }

        // -------------------------------------------------------------- linux
        if (a.equals("linux")) {
            LinuxApi l = linux;
            if (l == null) throw new ApiError(503, "Linux is not available.");
            if (id == null && get) return Response.json(l.status());
            if ("install".equals(id) && post) return Response.json(l.install());
            if (id == null && del) { l.remove(); return Response.json(l.status()); }
            if ("sessions".equals(id) && sub != null) {
                // Read-only view of a bot's terminal, plus restart. Commands only run through the bots' approved tool calls.
                String[] rest = p.length > 3 ? new String[]{sub, p[3]} : new String[]{sub};
                b.bots.require(rest[0]);
                if (rest.length == 1 && get) return Response.json(l.log(rest[0]));
                if (rest.length == 2 && "reset".equals(rest[1]) && post) { l.reset(rest[0]); return Response.json(l.log(rest[0])); }
            }
        }

        // ----------------------------------------------------------- computer
        if (a.equals("computers")) {
            ComputerApi c = computer;
            if (c == null) throw new ApiError(503, "The computer (browser) is not available.");
            if ("surfaces".equals(id) && p.length == 2) return Response.json(c.list());
            if ("open".equals(id) && post) return Response.json(c.open(body.optString("bot_id")));
            if ("reset".equals(id) && post) { c.reset(); return Response.json(J.obj("reset", true)); }
            if ("surfaces".equals(id) && p.length >= 4) {
                String sid = Uri.decode(p[2]), act = p[3];
                if ("screenshot".equals(act)) {
                    Response r = new Response();
                    r.type = "image/jpeg";
                    try { r.bytes = c.screenshot(sid); } catch (Exception e) { throw new ApiError(409, e.getMessage()); }
                    return r;
                }
                if ("takeover".equals(act)) return Response.json(c.takeOver(sid));
                if ("resume".equals(act)) return Response.json(c.resume(sid));
                if ("record".equals(act)) { c.record(sid, J.bool(body, "on")); return Response.json(J.obj("recording", J.bool(body, "on"))); }
                if ("input".equals(act)) throw new ApiError(409, "On the phone you control the page directly after Take over.");
            }
        }

        // ---------------------------------------------------------------- MCP
        if (a.equals("mcp")) {
            Mcp mc = mcp;
            if ("elicitations".equals(id)) return Response.json(new JSONArray());
            if (mc != null && "connections".equals(id)) {
                String cid = p.length > 2 ? Uri.decode(p[2]) : null, act = p.length > 3 ? p[3] : null;
                if (cid == null && get) return Response.json(mc.rows());
                if (cid == null && post) return Response.json(mc.save(body, null));
                if (act == null && patch) return Response.json(mc.save(body, cid));
                if (act == null && del) { mc.delete(cid); return Response.json(J.obj("deleted", cid)); }
                if ("test".equals(act)) return Response.json(mc.refresh(cid));
            }
        }

        // ------------------------------------------------------- integrations
        if (a.equals("integrations")) {
            if (id == null && get) return Response.json(J.obj(
                    "phone", true,
                    "codex", J.obj("installed", false, "logged_in", false, "reason", "The Codex CLI cannot run inside an Android app."),
                    "chatgpt", b.chatgpt.status(),
                    "opencode", J.obj("installed", false, "go_key_configured", b.core.kvGet("opencode_go_secret_id") != null)));
            if ("opencode".equals(id) && "key".equals(sub)) {
                String key = body.optString("api_key").trim();
                if (!key.isEmpty() && key.length() < 8) throw new ApiError(422, "That does not look like an API key.");
                String old = b.core.kvGet("opencode_go_secret_id");
                if (key.isEmpty()) { b.core.secretDelete(old); b.core.kvSet("opencode_go_secret_id", null); }
                else b.core.kvSet("opencode_go_secret_id", b.core.secretPut("opencode_go", "api_key", "OpenCode Go", key, old));
                if (!J.str(body, "default_model", "").isEmpty()) b.core.kvSet("opencode_default_model", body.optString("default_model"));
                return Response.json(J.obj("go_key_configured", !key.isEmpty()));
            }
            if ("chatgpt".equals(id) && "login".equals(sub)) return Response.json(b.chatgpt.startLogin(J.bool(body, "accept_risk")));
            if ("chatgpt".equals(id) && "logout".equals(sub)) { b.chatgpt.logout(); return Response.json(J.obj("ok", true)); }
            if ("codex".equals(id)) throw new ApiError(501, "ChatGPT sign-in via Codex needs a computer; on the phone use an API key (xAI, OpenAI, OpenCode Go, OpenRouter).");
        }

        // ------------------------------------------------- phone-only features
        if (a.equals("devices") && get) return Response.json(new JSONArray());
        if (a.equals("pair")) throw new ApiError(404, "This LowBot runs on your phone; there is no server to pair with.");
        if (a.equals("admin") && "backups".equals(id)) return Response.json(J.obj("backups", new JSONArray(),
                "restore", "Use Settings → Backup → Export to save a copy into Downloads.", "note", "Secrets are bound to this phone's keystore and are not exported."));
        if (a.equals("admin") && "backup".equals(id) && post) return Response.json(backup());
        if (a.equals("voice")) throw new ApiError(501, "On the phone speech is handled by Android (dictation and voice chat).");

        return Response.error(404, "Not found: " + m + " " + path);
    }

    JSONObject health() {
        JSONArray caps = new JSONArray();
        for (String c : b.capabilities()) caps.put(c);
        if (mcp != null) caps.put("mcp");
        return J.obj("status", "ok", "api", "v2", "host", "phone", "schema_version", Db.VERSION, "capabilities", caps,
                "limits", J.obj("max_active_runs", b.core.settings.maxActiveRuns, "max_active_surfaces", b.core.settings.maxActiveSurfaces),
                "timezone", b.core.settings.timezone, "active_runs", b.engine.activeCount());
    }

    JSONObject settings() {
        return J.obj("auto_review", b.core.kvBool("auto_review", false), "local_execution", J.str(J.obj("v", b.core.kvGet("local_execution")), "v", "ask"),
                "allow_private_network", b.core.settings.allowPrivateNetwork, "timezone", b.core.settings.timezone,
                "hierarchy_enforced", b.core.kvBool("hierarchy_enforced", false));
    }

    /** Grok Bot search scopes: Messages, Bots, Group Chats, Files, Routines, All. */
    JSONObject search(String text, String scope) {
        if (text.trim().length() < 2) throw new ApiError(422, "Type at least 2 characters.");
        String like = "%" + text.replace("%", "").replace("_", "") + "%";
        Db db = b.core.db;
        boolean all = "all".equals(scope);
        JSONObject out = new JSONObject();
        if (all || "bots".equals(scope))
            J.put(out, "bots", Db.toArray(db.all("SELECT id, name, handle, avatar, label FROM bots WHERE name LIKE ? OR handle LIKE ? OR role_description LIKE ? OR label LIKE ? LIMIT 20", like, like, like, like)));
        if (all || "messages".equals(scope))
            J.put(out, "messages", Db.toArray(db.all("SELECT id, conversation_id, seq, author_type, author_id, substr(text, 1, 240) AS text, created_at FROM messages WHERE text LIKE ? ORDER BY seq DESC LIMIT 30", like)));
        if (all || "groups".equals(scope))
            J.put(out, "groups", Db.toArray(db.all("SELECT id, title, updated_at FROM conversations WHERE kind = 'group' AND archived = 0 AND title LIKE ? LIMIT 20", like)));
        if (all || "files".equals(scope))
            J.put(out, "artifacts", Db.toArray(db.all("SELECT id, name, conversation_id, version, mime, size FROM artifacts WHERE name LIKE ? ORDER BY created_at DESC LIMIT 20", like)));
        if (all || "routines".equals(scope))
            J.put(out, "routines", Db.toArray(db.all("SELECT id, bot_id, name, enabled, next_run_at FROM routines WHERE name LIKE ? OR prompt LIKE ? LIMIT 20", like, like)));
        if (all) {
            J.put(out, "tasks", Db.toArray(db.all("SELECT id, bot_id, title, status, conversation_id FROM tasks WHERE title LIKE ? OR instructions LIKE ? ORDER BY created_at DESC LIMIT 20", like, like)));
            J.put(out, "memories", Db.toArray(b.memory.search(text, null, 20)));
        }
        return out;
    }

    JSONObject backup() throws Exception {
        File dbFile = b.core.ctx.getDatabasePath("lowbot.db");
        b.core.db.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(FULL)", null).close();
        byte[] data = readFile(dbFile);
        String name = "lowbot-backup-" + J.nowIso().replace(":", "").substring(0, 15) + ".sqlite";
        JSONObject art = b.artifacts.create(name, data, "application/vnd.sqlite3", null, null, null, null);
        b.core.kvSet("last_backup", J.obj("name", name, "at", J.nowIso()).toString());
        return J.obj("name", name, "size", data.length, "artifact_id", art.optString("id"),
                "note", "Secrets (API keys) are encrypted with this phone's keystore and cannot be restored on another phone.");
    }

    static byte[] readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] data = new byte[(int) f.length()];
        int off = 0, n;
        while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
        in.close();
        return data;
    }
}
