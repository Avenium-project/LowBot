package io.lowbot.core;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable approvals bound to user, run step, tool and the exact argument hash;
 * they expire, are single-use and are invalidated by edits. Decisions follow Grok Bot:
 * Allow once, Always allow (stores a rule for this bot + tool), Deny.
 */
public final class Approvals {
    private final Backend b;
    private final Db db;

    Approvals(Backend b) { this.b = b; this.db = b.core.db; }

    static JSONObject row(JSONObject r) {
        J.put(r, "display", J.parse(r.optString("display_json")));
        J.put(r, "review", J.parse(r.optString("review_json")));
        r.remove("display_json");
        r.remove("review_json");
        return r;
    }

    public JSONObject get(String id) {
        JSONObject r = db.one("SELECT * FROM approvals WHERE id = ?", id);
        return r == null ? null : row(r);
    }

    public List<JSONObject> list(String status) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        List<JSONObject> rows = status == null || status.isEmpty()
                ? db.all("SELECT * FROM approvals ORDER BY created_at DESC LIMIT 200")
                : db.all("SELECT * FROM approvals WHERE status = ? ORDER BY created_at DESC LIMIT 200", status);
        for (JSONObject r : rows) out.add(row(r));
        return out;
    }

    /** Inside the engine's transaction. */
    public String open(JSONObject run, String stepId, JSONObject task, String tool, JSONObject args, JSONObject card, JSONObject review) {
        String aid = J.id("apr");
        String cid = J.str(task, "conversation_id", null);
        db.insert("approvals", J.obj("id", aid, "run_id", run.optString("id"), "step_id", stepId, "task_id", task.optString("id"),
                "bot_id", run.optString("bot_id"), "conversation_id", cid, "tool", tool, "args_hash", J.argsHash(tool, args),
                "display_json", J.redactObj(args).toString(), "summary", card.optString("summary", tool), "effect", card.optString("effect"),
                "target", card.optString("target"), "status", "pending", "approver_user_id", Core.OWNER,
                "expires_at", J.isoIn(b.core.settings.approvalTtlS), "review_json", review == null ? "{}" : review.toString(),
                "created_at", J.nowIso()));
        b.core.emit("approval.requested", cid, task.optString("id"), run.optString("id"), run.optString("bot_id"),
                J.obj("approval_id", aid, "tool", tool, "summary", card.optString("summary")));
        JSONObject bot = b.bots.get(run.optString("bot_id"));
        if (bot == null || bot.optBoolean("notify", true))
            b.core.notify("approval", "Approval needed: " + card.optString("summary", tool), card.optString("effect"),
                    task.optString("id"), aid, cid, run.optString("bot_id"));
        b.core.audit("approval.open", "bot", run.optString("bot_id"), task.optString("id"), run.optString("id"), aid, null, null, J.obj("tool", tool));
        return aid;
    }

    /** decision: approve (Allow once) | always (Always allow) | deny. */
    public JSONObject decide(final String id, final String decision, final String argsHashSeen) {
        if (!"approve".equals(decision) && !"deny".equals(decision) && !"always".equals(decision))
            throw new ApiError(422, "decision must be approve, always or deny");
        db.tx(new Runnable() {
            @Override public void run() {
                JSONObject a = get(id);
                if (a == null) throw new ApiError(404, "Unknown approval.");
                if (!"pending".equals(a.optString("status"))) throw new ApiError(409, "Approval is " + a.optString("status") + ", not pending.");
                if (a.optString("expires_at").compareTo(J.nowIso()) <= 0) {
                    expire(a);
                    throw new ApiError(409, "Approval expired.");
                }
                if (argsHashSeen == null || !argsHashSeen.equals(a.optString("args_hash")))
                    throw new ApiError(409, "The action changed since you viewed it. Review it again.");
                String status = "deny".equals(decision) ? "denied" : "approved";
                int n = db.change("UPDATE approvals SET status = ?, decided_by = ?, decided_at = ? WHERE id = ? AND status = 'pending'",
                        status, Core.OWNER, J.nowIso(), id);
                if (n != 1) throw new ApiError(409, "Approval was decided concurrently.");
                if ("always".equals(decision)) {
                    String pid = J.id("pol");
                    db.insert("policy_rules", J.obj("id", pid, "bot_id", a.optString("bot_id"), "tool_pattern", a.optString("tool"),
                            "effect", "allow", "source", "always_allow", "created_at", J.nowIso()));
                    b.core.audit("policy.add", "user", Core.OWNER, a.optString("task_id"), a.optString("run_id"), id, "always_allow", null,
                            J.obj("rule_id", pid, "tool", a.optString("tool"), "bot_id", a.optString("bot_id")));
                }
                requeue(a.optString("run_id"));
                b.core.emit("approval." + status, J.str(a, "conversation_id", null), a.optString("task_id"), a.optString("run_id"),
                        a.optString("bot_id"), J.obj("approval_id", id, "always", "always".equals(decision)));
                b.core.audit("approval.decide", "user", Core.OWNER, a.optString("task_id"), a.optString("run_id"), id, status, null,
                        J.obj("tool", a.optString("tool")));
            }
        });
        b.wake();
        return get(id);
    }

    /** Editing a draft invalidates the old approval and issues a new one for the new arguments. */
    public JSONObject edit(final String id, final JSONObject args, String validationError) {
        if (validationError != null) throw new ApiError(422, validationError);
        final String[] nid = new String[1];
        db.tx(new Runnable() {
            @Override public void run() {
                JSONObject a = get(id);
                if (a == null || !"pending".equals(a.optString("status"))) throw new ApiError(409, "Only pending approvals can be edited.");
                db.exec("UPDATE approvals SET status = 'invalidated', decided_at = ? WHERE id = ?", J.nowIso(), id);
                JSONObject step = db.one("SELECT * FROM run_steps WHERE id = ?", a.optString("step_id"));
                JSONObject inp = J.parse(step.optString("input_json"));
                J.put(inp, "arguments", args);
                J.put(inp, "edited_by_user", true);
                db.exec("UPDATE run_steps SET input_json = ?, updated_at = ? WHERE id = ?", inp.toString(), J.nowIso(), a.optString("step_id"));
                nid[0] = J.id("apr");
                db.insert("approvals", J.obj("id", nid[0], "run_id", a.optString("run_id"), "step_id", a.optString("step_id"),
                        "task_id", a.optString("task_id"), "bot_id", a.optString("bot_id"), "conversation_id", a.opt("conversation_id"),
                        "tool", a.optString("tool"), "args_hash", J.argsHash(a.optString("tool"), args), "display_json", J.redactObj(args).toString(),
                        "summary", a.optString("summary"), "effect", a.optString("effect"), "target", a.optString("target"),
                        "status", "pending", "approver_user_id", Core.OWNER, "expires_at", J.isoIn(b.core.settings.approvalTtlS),
                        "created_at", J.nowIso()));
                b.core.emit("approval.edited", J.str(a, "conversation_id", null), a.optString("task_id"), a.optString("run_id"), null,
                        J.obj("approval_id", nid[0], "replaces", id));
                b.core.audit("approval.edit", "user", Core.OWNER, a.optString("task_id"), a.optString("run_id"), nid[0], null, null, J.obj("replaces", id));
            }
        });
        return get(nid[0]);
    }

    /** Single use: approved -> consumed iff the arguments still hash to what was approved. Inside a tx. */
    public boolean consume(String id, String tool, JSONObject args) {
        return db.change("UPDATE approvals SET status = 'consumed', consumed_at = ? WHERE id = ? AND status = 'approved' AND args_hash = ? AND tool = ? AND expires_at > ?",
                J.nowIso(), id, J.argsHash(tool, args), tool, J.nowIso()) == 1;
    }

    void requeue(String runId) {
        db.exec("UPDATE runs SET status = 'queued', updated_at = ? WHERE id = ? AND status = 'waiting_approval'", J.nowIso(), runId);
    }

    void expire(JSONObject a) {
        db.exec("UPDATE approvals SET status = 'expired', decided_at = ? WHERE id = ? AND status = 'pending'", J.nowIso(), a.optString("id"));
        requeue(a.optString("run_id"));
        b.core.emit("approval.expired", J.str(a, "conversation_id", null), a.optString("task_id"), a.optString("run_id"), null,
                J.obj("approval_id", a.optString("id")));
        b.core.audit("approval.expire", "system", null, null, a.optString("run_id"), a.optString("id"), null, null, null);
    }

    public int sweepExpired() {
        final List<JSONObject> rows = db.all("SELECT * FROM approvals WHERE status = 'pending' AND expires_at <= ?", J.nowIso());
        if (rows.isEmpty()) return 0;
        db.tx(new Runnable() {
            @Override public void run() { for (JSONObject r : rows) expire(row(r)); }
        });
        b.wake();
        return rows.size();
    }
}
