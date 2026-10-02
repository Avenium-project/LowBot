package io.lowbot.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conversations, messages and the task/run lifecycle. Mirrors server/app/v2/tasks.py. */
public final class Tasks {
    static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z0-9][A-Za-z0-9_\\-]{0,40})");
    static final Pattern SLASH = Pattern.compile("^/([a-z0-9][a-z0-9\\-]*)\\b");
    public static final int PRIORITY_REPLY = 95, PRIORITY_USER = 80, PRIORITY_DELEGATED = 60, PRIORITY_BACKGROUND = 30;

    private final Backend b;
    private final Db db;

    Tasks(Backend b) { this.b = b; this.db = b.core.db; }

    public static List<String> mentions(String text) {
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = MENTION.matcher(text == null ? "" : text);
        while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
        return new ArrayList<String>(out);
    }

    static boolean terminal(String s) {
        return "completed".equals(s) || "failed".equals(s) || "cancelled".equals(s);
    }

    // ------------------------------------------------------------ conversations
    public JSONObject createConversation(String kind, final List<String> botIds, final String title) {
        if (!"private".equals(kind) && !"group".equals(kind)) throw new ApiError(422, "kind must be private or group");
        if (botIds.isEmpty()) throw new ApiError(422, "A conversation needs at least one bot.");
        if ("private".equals(kind) && botIds.size() != 1) throw new ApiError(422, "A private conversation has exactly one bot.");
        if ("group".equals(kind) && new LinkedHashSet<String>(botIds).size() > 12) throw new ApiError(422, "A group chat can have up to 12 bots.");
        for (String id : botIds) b.bots.require(id);
        final String cid = J.id("conv");
        final String k = kind;
        db.tx(new Runnable() {
            @Override public void run() {
                String now = J.nowIso();
                db.insert("conversations", J.obj("id", cid, "kind", k, "title", title == null ? "" : title,
                        "default_bot_id", botIds.get(0), "created_at", now, "updated_at", now));
                db.insert("memberships", J.obj("conversation_id", cid, "member_type", "user", "member_id", Core.OWNER, "joined_at", now));
                for (String id : new LinkedHashSet<String>(botIds))
                    db.insert("memberships", J.obj("conversation_id", cid, "member_type", "bot", "member_id", id, "joined_at", now));
                b.core.emit("conversation.created", cid, null, null, null, J.obj("kind", k));
            }
        });
        return getConversation(cid);
    }

    public JSONObject privateConversation(String botId) {
        b.bots.require(botId);
        JSONObject r = db.one("SELECT id FROM conversations WHERE kind = 'private' AND default_bot_id = ? AND archived = 0 ORDER BY created_at", botId);
        if (r != null) return getConversation(r.optString("id"));
        List<String> ids = new ArrayList<String>();
        ids.add(botId);
        return createConversation("private", ids, "");
    }

    public JSONObject getConversation(String id) {
        JSONObject c = db.one("SELECT * FROM conversations WHERE id = ?", id);
        if (c == null) throw new ApiError(404, "Unknown conversation.");
        JSONArray bots = new JSONArray();
        for (JSONObject m : db.all("SELECT member_id FROM memberships WHERE conversation_id = ? AND member_type = 'bot' ORDER BY joined_at", id))
            bots.put(m.optString("member_id"));
        J.put(c, "bot_ids", bots);
        J.put(c, "archived", J.bool(c, "archived"));
        J.put(c, "pinned", J.bool(c, "pinned"));
        J.put(c, "hidden", J.bool(c, "hidden"));
        JSONObject last = db.one("SELECT seq, text, author_type, author_id, created_at FROM messages WHERE conversation_id = ? ORDER BY seq DESC", id);
        J.put(c, "last_seq", last == null ? 0 : last.optLong("seq"));
        J.put(c, "last_message", last == null ? null : J.obj("text", J.truncate(last.optString("text"), 160),
                "author_type", last.optString("author_type"), "author_id", last.opt("author_id"), "created_at", last.optString("created_at")));
        J.put(c, "unread", db.count("SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND seq > ? AND author_type != 'user'", id, c.optLong("last_read_seq")));
        return c;
    }

    public List<JSONObject> listConversations() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject r : db.all("SELECT id FROM conversations WHERE archived = 0 ORDER BY pinned DESC, updated_at DESC"))
            out.add(getConversation(r.optString("id")));
        return out;
    }

    public JSONObject updateConversation(final String id, final JSONObject d) {
        getConversation(id);
        db.tx(new Runnable() {
            @Override public void run() {
                if (d.has("title")) db.exec("UPDATE conversations SET title = ? WHERE id = ?", d.optString("title"), id);
                if (d.has("pinned")) db.exec("UPDATE conversations SET pinned = ? WHERE id = ?", J.bool(d, "pinned"), id);
                if (d.has("hidden")) db.exec("UPDATE conversations SET hidden = ? WHERE id = ?", J.bool(d, "hidden"), id);
                if (d.has("archived")) db.exec("UPDATE conversations SET archived = ? WHERE id = ?", J.bool(d, "archived"), id);
                if (d.has("project")) {
                    String p = d.isNull("project") || d.optString("project").trim().isEmpty() ? null : b.mind.projectDir(d.optString("project")).getName();
                    if (p != null) b.mind.projectDir(p).mkdirs();
                    db.exec("UPDATE conversations SET project = ? WHERE id = ?", p, id);
                }
                b.core.emit("conversation.updated", id, null, null, null, null);
            }
        });
        return getConversation(id);
    }

    public JSONObject addMember(final String cid, final String botId) {
        JSONObject c = getConversation(cid);
        b.bots.require(botId);
        if ("private".equals(c.optString("kind"))) throw new ApiError(422, "Convert to a group conversation to add bots.");
        db.tx(new Runnable() {
            @Override public void run() {
                db.exec("INSERT OR IGNORE INTO memberships(conversation_id, member_type, member_id, joined_at) VALUES (?, 'bot', ?, ?)", cid, botId, J.nowIso());
                b.core.emit("conversation.member_added", cid, null, null, botId, null);
            }
        });
        return getConversation(cid);
    }

    public void markUnread(final String cid) {
        final long seq = db.count("SELECT COALESCE(MAX(seq), 1) FROM messages WHERE conversation_id = ? AND author_type != 'user'", cid);
        db.tx(new Runnable() {
            @Override public void run() { db.exec("UPDATE conversations SET last_read_seq = ? WHERE id = ?", Math.max(0, seq - 1), cid); }
        });
    }

    public void markRead(final String cid, final long seq) {
        db.tx(new Runnable() {
            @Override public void run() {
                db.exec("UPDATE conversations SET last_read_seq = MAX(last_read_seq, ?) WHERE id = ?", seq, cid);
                db.exec("UPDATE tasks SET unread = 0 WHERE conversation_id = ?", cid);
            }
        });
    }

    // ----------------------------------------------------------------- messages
    public List<JSONObject> messages(String cid, long after, int limit) {
        List<JSONObject> rows = db.all("SELECT * FROM messages WHERE conversation_id = ? AND seq > ? ORDER BY seq LIMIT ?", cid, after, limit);
        for (JSONObject r : rows) {
            J.put(r, "mentions", J.parseArr(r.optString("mentions_json")));
            J.put(r, "attachments", J.parseArr(r.optString("attachments_json")));
            J.put(r, "meta", J.parse(r.optString("meta_json")));
            r.remove("mentions_json"); r.remove("attachments_json"); r.remove("meta_json");
        }
        return rows;
    }

    /** Inside a transaction. */
    public JSONObject insertMessage(String cid, String authorType, String authorId, String text, List<String> mentions,
                                    String taskId, String clientMsgId, String threadRoot, JSONArray attachments, JSONObject meta) {
        String mid = J.id("msg");
        db.insert("messages", J.obj("id", mid, "conversation_id", cid, "author_type", authorType, "author_id", authorId,
                "text", text, "mentions_json", J.arr(mentions == null ? new ArrayList<String>() : mentions).toString(),
                "task_id", taskId, "client_msg_id", clientMsgId, "thread_root_id", threadRoot,
                "attachments_json", attachments == null ? "[]" : attachments.toString(),
                "meta_json", meta == null ? "{}" : meta.toString(), "created_at", J.nowIso()));
        db.exec("UPDATE conversations SET updated_at = ?, hidden = 0 WHERE id = ?", J.nowIso(), cid);
        long seq = Long.parseLong(db.scalar("SELECT seq FROM messages WHERE id = ?", mid));
        b.core.emit("message.created", cid, taskId, null, "bot".equals(authorType) ? authorId : null,
                J.obj("message_id", mid, "seq", seq, "author_type", authorType));
        return J.obj("id", mid, "seq", seq);
    }

    /**
     * Persist a user message and the tasks it creates in ONE transaction. Idempotent on
     * client_msg_id. Routing (Grok Bot): @bot → that bot; several mentions → each;
     * @everyone → all members; no mention in a group → the lead bot coordinates and may
     * hand off to a better-suited member.
     */
    public JSONObject postUserMessage(final String cid, String rawText, final String clientMsgId, final JSONArray attachments, final String threadRoot) {
        final String text = vaultPastedSecrets(rawText == null ? "" : rawText.trim());
        if (text.isEmpty() && (attachments == null || attachments.length() == 0)) throw new ApiError(422, "Message is empty.");
        JSONObject conv = getConversation(cid);
        if (clientMsgId != null) {
            JSONObject ex = db.one("SELECT id, seq FROM messages WHERE conversation_id = ? AND client_msg_id = ?", cid, clientMsgId);
            if (ex != null) {
                return J.obj("message_id", ex.optString("id"), "seq", ex.optLong("seq"), "duplicate", true,
                        "tasks", Db.toArray(db.all("SELECT id, bot_id, status FROM tasks WHERE source_message_id = ?", ex.optString("id"))));
            }
        }
        final List<String> mentions = mentions(text);
        final List<JSONObject> targets = route(conv, mentions);
        final JSONObject skill = skillFor(text);
        final JSONObject[] out = new JSONObject[1];
        db.tx(new Runnable() {
            @Override public void run() {
                // Steering: a message to a bot that is already working on this chat's request joins that run
                // (the bot sees it before its next step) instead of starting a second task.
                String steerTask = null;
                if (targets.size() == 1 && skill == null) steerTask = activeUserTask(cid, targets.get(0).optString("id"));
                JSONObject msg = insertMessage(cid, "user", Core.OWNER, text, mentions, null, clientMsgId, threadRoot, attachments,
                        steerTask == null ? null : J.obj("steer", steerTask));
                JSONArray tasks = new JSONArray();
                if (steerTask == null) for (JSONObject bot : targets) {
                    tasks.put(createTask(bot.optString("id"), cid, "user", Core.OWNER, text, J.truncate(text, 80), "",
                            null, PRIORITY_USER, msg.optString("id"), skill, null));
                }
                else {
                    b.core.emit("task.steered", cid, steerTask, null, targets.get(0).optString("id"), J.obj("message_id", msg.optString("id")));
                    // The bot answers this message right away in a short parallel reply (status, result so far, "will do"),
                    // while the running task picks the message up before its next step and carries on.
                    JSONObject reply = createTask(targets.get(0).optString("id"), cid, "user", Core.OWNER, text, J.truncate(text, 80), QUICK_REPLY,
                            null, PRIORITY_REPLY, msg.optString("id"), null, null);
                    db.exec("UPDATE runs SET max_steps = 6 WHERE task_id = ?", reply.optString("id"));
                    tasks.put(reply);
                }
                out[0] = J.obj("message_id", msg.optString("id"), "seq", msg.optLong("seq"), "tasks", tasks, "duplicate", false, "steered_task", steerTask);
            }
        });
        b.wake();
        return out[0];
    }

    // Things that are clearly credentials: provider keys and private-key blocks.
    static final java.util.regex.Pattern PASTED_SECRET = java.util.regex.Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----"
            + "|\\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_\\-]{20,}|xai-[A-Za-z0-9_\\-]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}"
            + "|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_\\-]{35}|xox[abpr]-[A-Za-z0-9\\-]{20,}|glpat-[A-Za-z0-9_\\-]{20,})\\b");

    /**
     * A key pasted straight into the chat goes to the encrypted vault; the message keeps only
     * the placeholder, so neither the chat history nor the model ever holds the value.
     */
    String vaultPastedSecrets(String text) {
        if (text.isEmpty()) return text;
        java.util.regex.Matcher m = PASTED_SECRET.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            any = true;
            String v = m.group();
            String name = null;
            for (JSONObject r : db.all("SELECT id, name FROM secret_references WHERE name LIKE 'user:%'"))
                if (v.equals(b.core.secretGet(r.optString("id")))) name = r.optString("name").substring(5);
            if (name == null) {
                int n = 1;
                while (b.core.secretIdByName("user:PASTED_KEY_" + n) != null) n++;
                name = "PASTED_KEY_" + n;
                b.core.secretPut("user:" + name, "user_secret", "Pasted in chat", v, null);
                final String nm = name;
                db.tx(new Runnable() { public void run() { b.core.audit("secret.pasted", "user", Core.OWNER, null, null, null, null, null, J.obj("name", nm)); } });
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("{{secret:" + name + "}}"));
        }
        m.appendTail(sb);
        return any ? sb.toString() : text;
    }

    /** The bot's in-progress task for a user request in this chat (not one parked on a question). */
    /** Marks the short reply a bot gives to a message sent while it works. */
    public static final String QUICK_REPLY = "quick_reply";

    public String activeUserTask(String cid, String botId) {
        return db.scalar("SELECT t.id FROM tasks t JOIN runs r ON r.task_id = t.id WHERE t.conversation_id = ? AND t.bot_id = ? "
                + "AND t.requester_type = 'user' AND t.expected_output != 'quick_reply' AND r.status IN ('queued','running','retry_scheduled','waiting_approval','waiting_dependency') "
                + "ORDER BY t.created_at DESC LIMIT 1", cid, botId);
    }

    /** Steering messages for a task, oldest first. */
    public List<JSONObject> steerMessages(String taskId) {
        return db.all("SELECT id, text, created_at, attachments_json FROM messages WHERE author_type = 'user' AND meta_json LIKE ? ORDER BY seq",
                "%\"steer\":\"" + taskId + "\"%");
    }

    List<JSONObject> route(JSONObject conv, List<String> mentions) {
        List<JSONObject> members = new ArrayList<JSONObject>();
        for (String id : J.strings(conv.optJSONArray("bot_ids"))) {
            JSONObject bot = b.bots.get(id);
            if (bot != null) members.add(bot);
        }
        if (mentions.contains("everyone") && !members.isEmpty()) return members;
        if (!mentions.isEmpty()) {
            List<JSONObject> chosen = new ArrayList<JSONObject>();
            for (JSONObject m : members) if (mentions.contains(m.optString("handle").toLowerCase(Locale.ROOT))) chosen.add(m);
            if (!chosen.isEmpty()) return chosen;
        }
        JSONObject def = null;
        for (JSONObject m : members) if (m.optString("id").equals(conv.optString("default_bot_id"))) def = m;
        if (def == null && !members.isEmpty()) def = members.get(0);
        if (def == null) throw new ApiError(422, "No bot is available in this conversation.");
        List<JSONObject> one = new ArrayList<JSONObject>();
        one.add(def);
        return one;
    }

    JSONObject skillFor(String text) {
        Matcher m = SLASH.matcher(text.trim());
        if (!m.find()) return null;
        return db.one("SELECT s.id, s.slug, v.version FROM skills s JOIN skill_versions v ON v.skill_id = s.id AND v.version = s.current_version WHERE s.slug = ?", m.group(1));
    }

    /** A bot speaks in a conversation; @mentions of member bots wake them (bounded by loop guards). */
    public JSONObject postBotMessage(final JSONObject task, String rawText, final JSONObject meta, final boolean routeMentions) {
        final String text = b.core.scrubSecrets(rawText); // a bot never shows a stored secret's value
        final String cid = J.str(task, "conversation_id", null);
        if (cid == null) throw new ApiError(422, "This task has no conversation to post to.");
        final JSONObject conv = getConversation(cid);
        final List<String> mentions = mentions(text);
        final JSONObject[] out = new JSONObject[1];
        db.tx(new Runnable() {
            @Override public void run() {
                JSONObject msg = insertMessage(cid, "bot", task.optString("bot_id"), text, mentions, task.optString("id"), null, null, null, meta);
                db.exec("UPDATE tasks SET unread = 1 WHERE id = ?", task.optString("id"));
                JSONArray created = new JSONArray();
                if (routeMentions && !mentions.isEmpty()) {
                    for (String bid : J.strings(conv.optJSONArray("bot_ids"))) {
                        JSONObject bot = b.bots.get(bid);
                        if (bot == null || bid.equals(task.optString("bot_id"))) continue;
                        if (!mentions.contains(bot.optString("handle").toLowerCase(Locale.ROOT)) && !mentions.contains("everyone")) continue;
                        try {
                            created.put(createTask(bid, cid, "bot", task.optString("bot_id"), text, J.truncate(text, 80), "",
                                    task, PRIORITY_DELEGATED, msg.optString("id"), null, null));
                        } catch (ApiError e) {
                            b.core.emit("delegation.rejected", cid, task.optString("id"), null, task.optString("bot_id"),
                                    J.obj("target_bot_id", bid, "reason", e.getMessage()));
                        }
                    }
                }
                J.put(msg, "tasks", created);
                out[0] = msg;
            }
        });
        b.wake();
        return out[0];
    }

    // -------------------------------------------------------------------- tasks
    public JSONObject getTask(String id) { return id == null ? null : db.one("SELECT * FROM tasks WHERE id = ?", id); }

    public JSONObject requireTask(String id) {
        JSONObject t = getTask(id);
        if (t == null) throw new ApiError(404, "Unknown task.");
        return t;
    }

    public JSONObject runFor(String taskId) {
        return db.one("SELECT * FROM runs WHERE task_id = ? ORDER BY created_at DESC", taskId);
    }

    public void guardDelegation(JSONObject parent, String toBot) {
        Core.Settings s = b.core.settings;
        if (parent.optInt("depth") + 1 > s.maxDelegationDepth) throw new ApiError(409, "Delegation depth limit (" + s.maxDelegationDepth + ") reached.");
        if (db.count("SELECT COUNT(*) FROM tasks WHERE correlation_id = ?", parent.optString("correlation_id")) >= s.maxTasksPerCorrelation)
            throw new ApiError(409, "Message budget for this conversation chain (" + s.maxTasksPerCorrelation + ") exhausted.");
        JSONObject cur = parent;
        while (cur != null) {
            if (cur.optString("bot_id").equals(toBot) && !terminal(cur.optString("status")))
                throw new ApiError(409, "Delegation cycle detected: the target bot is already waiting on this chain.");
            cur = getTask(J.str(cur, "parent_task_id", null));
        }
        JSONObject from = b.bots.get(parent.optString("bot_id"));
        JSONObject to = b.bots.require(toBot);
        if (from != null) {
            String reason = b.bots.canDelegate(from, to);
            if (reason != null) throw new ApiError(403, reason);
        }
    }

    /** Call inside a transaction (or standalone: wraps itself). */
    public JSONObject createTask(final String botId, final String cid, final String requesterType, final String requesterId,
                                 final String instructions, final String title, final String expected, final JSONObject parent,
                                 final int priority, final String sourceMessageId, final JSONObject skill, final String routineRunId) {
        final JSONObject bot = b.bots.require(botId);
        if (parent != null) guardDelegation(parent, botId);
        final String tid = J.id("task"), rid = J.id("run");
        db.tx(new Runnable() {
            @Override public void run() {
                String now = J.nowIso();
                String corr = parent != null ? parent.optString("correlation_id") : J.id("corr");
                String root = parent != null ? (J.str(parent, "root_task_id", null) != null ? parent.optString("root_task_id") : parent.optString("id")) : null;
                JSONObject budget = bot.optJSONObject("budget");
                int maxSteps = budget != null && budget.optInt("max_steps") > 0 ? budget.optInt("max_steps") : b.core.settings.defaultMaxSteps;
                db.insert("tasks", J.obj("id", tid, "conversation_id", cid, "bot_id", botId, "requester_type", requesterType,
                        "requester_id", requesterId, "parent_task_id", parent == null ? null : parent.optString("id"), "root_task_id", root,
                        "correlation_id", corr, "depth", parent == null ? 0 : parent.optInt("depth") + 1,
                        "title", title == null || title.isEmpty() ? J.truncate(instructions, 80) : title, "instructions", instructions,
                        "expected_output", expected == null ? "" : expected, "status", "queued", "priority", priority,
                        "skill_id", skill == null ? null : skill.optString("id"), "skill_version", skill == null ? null : skill.optInt("version"),
                        "source_message_id", sourceMessageId, "routine_run_id", routineRunId, "created_at", now, "updated_at", now));
                db.insert("runs", J.obj("id", rid, "task_id", tid, "bot_id", botId, "status", bot.optBoolean("paused") ? "paused" : "queued",
                        "priority", priority, "max_attempts", b.core.settings.maxAttempts, "max_steps", maxSteps,
                        "created_at", now, "updated_at", now));
                b.core.emit("task.created", cid, tid, rid, botId, J.obj("title", title, "requester_type", requesterType,
                        "requester_id", requesterId, "parent_task_id", parent == null ? null : parent.optString("id")));
            }
        });
        b.wake();
        return J.obj("id", tid, "run_id", rid, "bot_id", botId, "status", "queued");
    }

    public JSONObject taskTree(String id) {
        JSONObject t = requireTask(id);
        JSONArray children = new JSONArray();
        for (JSONObject c : db.all("SELECT id FROM tasks WHERE parent_task_id = ? ORDER BY created_at", id)) children.put(taskTree(c.optString("id")));
        return J.obj("task", t, "run", runFor(id), "children", children,
                "handoffs", Db.toArray(db.all("SELECT * FROM handoffs WHERE from_task_id = ? OR to_task_id = ?", id, id)));
    }

    // ------------------------------------------------------------------ control
    public JSONObject cancel(final String id, final String actor) {
        final JSONObject task = requireTask(id);
        db.tx(new Runnable() {
            @Override public void run() {
                String now = J.nowIso();
                JSONObject run = runFor(id);
                if (run != null && "running".equals(run.optString("status"))) {
                    db.exec("UPDATE runs SET control = 'cancel', updated_at = ? WHERE id = ?", now, run.optString("id"));
                } else if (run != null && !terminal(run.optString("status"))) {
                    db.exec("UPDATE runs SET status = 'cancelled', finished_at = ?, updated_at = ?, owner = NULL WHERE id = ?", now, now, run.optString("id"));
                    finishTask(task, "cancelled", null, "Cancelled by user.");
                }
                db.exec("UPDATE approvals SET status = 'invalidated' WHERE task_id = ? AND status = 'pending'", id);
                for (JSONObject c : db.all("SELECT id FROM tasks WHERE parent_task_id = ? AND status NOT IN ('completed','failed','cancelled')", id))
                    cancel(c.optString("id"), actor);
                b.core.audit("task.cancel", actor, null, id, null, null, null, null, null);
            }
        });
        b.engine.signalControl();
        return requireTask(id);
    }

    public void pause(final String id) {
        final JSONObject run = runFor(id);
        if (run == null || terminal(run.optString("status"))) throw new ApiError(409, "Nothing to pause.");
        db.tx(new Runnable() {
            @Override public void run() {
                String st = run.optString("status");
                if ("queued".equals(st) || "retry_scheduled".equals(st)) {
                    db.exec("UPDATE runs SET status = 'paused', updated_at = ? WHERE id = ?", J.nowIso(), run.optString("id"));
                    b.core.emit("run.paused", null, id, run.optString("id"), run.optString("bot_id"), null);
                } else {
                    db.exec("UPDATE runs SET control = 'pause' WHERE id = ?", run.optString("id"));
                }
            }
        });
    }

    public void resume(final String id) {
        final JSONObject run = runFor(id);
        if (run == null) throw new ApiError(409, "Nothing to resume.");
        db.tx(new Runnable() {
            @Override public void run() {
                if ("paused".equals(run.optString("status"))) {
                    db.exec("UPDATE runs SET status = 'queued', control = '', updated_at = ? WHERE id = ?", J.nowIso(), run.optString("id"));
                    b.core.emit("run.resumed", null, id, run.optString("id"), run.optString("bot_id"), null);
                } else {
                    db.exec("UPDATE runs SET control = '' WHERE id = ?", run.optString("id"));
                }
            }
        });
        b.wake();
    }

    /** Answer a bot's question (run in waiting_input). */
    public void answerInput(final String id, final String answer) {
        final JSONObject run = runFor(id);
        if (run == null || !"waiting_input".equals(run.optString("status"))) throw new ApiError(409, "This task is not waiting for input.");
        JSONObject waiting = J.parse(run.optString("waiting_json"));
        if (waiting.has("secret_request")) throw new ApiError(409, "This bot asked for a secret; use the secure field.");
        final String stepId = J.str(waiting, "step_id", null);
        db.tx(new Runnable() {
            @Override public void run() {
                if (stepId != null)
                    db.exec("UPDATE run_steps SET status = 'completed', output_json = ?, updated_at = ? WHERE id = ? AND status = 'waiting'",
                            J.obj("answer", answer).toString(), J.nowIso(), stepId);
                int n = db.change("UPDATE runs SET status = 'queued', waiting_json = '{}', updated_at = ? WHERE id = ? AND status = 'waiting_input'",
                        J.nowIso(), run.optString("id"));
                if (n != 1) throw new ApiError(409, "The task state changed; refresh and retry.");
                JSONObject t = requireTask(id);
                if (J.str(t, "conversation_id", null) != null)
                    insertMessage(t.optString("conversation_id"), "user", Core.OWNER, answer, null, id, null, null, null, J.obj("answer_to_task", id));
                b.core.emit("run.input_received", null, id, run.optString("id"), run.optString("bot_id"), null);
            }
        });
        b.wake();
    }

    // ----------------------------------------------------- completion & handoffs
    /** Inside a transaction. */
    public void finishTask(JSONObject task, String status, String result, String error) {
        result = b.core.scrubSecrets(result);
        error = b.core.scrubSecrets(error);
        String now = J.nowIso();
        db.exec("UPDATE tasks SET status = ?, result_text = ?, error = ?, updated_at = ?, completed_at = ?, unread = 1 WHERE id = ?",
                status, result, error, now, now, task.optString("id"));
        String cid = J.str(task, "conversation_id", null);
        b.core.emit("task." + status, cid, task.optString("id"), null, task.optString("bot_id"),
                J.obj("result", J.truncate(result == null ? "" : result, 400), "error", error));
        JSONObject bot = b.bots.get(task.optString("bot_id"));
        String rt = task.optString("requester_type");
        if (("user".equals(rt) || "routine".equals(rt)) && (bot == null || bot.optBoolean("notify", true))) {
            String kind = "completed".equals(status) ? "task_completed" : "failed".equals(status) ? "task_failed" : null;
            if (kind != null)
                b.core.notify(kind, (bot == null ? task.optString("bot_id") : bot.optString("name")) + ": " + task.optString("title"),
                        J.truncate(result != null ? result : error == null ? "" : error, 400), task.optString("id"), null, cid, task.optString("bot_id"));
        }
        propagate(task, status, result, error);
        // Steering that arrived after the bot's last model call was never seen: answer it as a new request.
        String lastModel = null;
        for (JSONObject st : db.all("SELECT s.output_json, s.created_at FROM run_steps s JOIN runs r ON r.id = s.run_id WHERE r.task_id = ? AND s.kind = 'model'", task.optString("id"))) {
            String at = J.parse(st.optString("output_json")).optString("requested_at", st.optString("created_at"));
            if (lastModel == null || at.compareTo(lastModel) > 0) lastModel = at;
        }
        if (!"cancelled".equals(status)) for (JSONObject m : steerMessages(task.optString("id"))) {
            if (lastModel != null && m.optString("created_at").compareTo(lastModel) < 0) continue;
            createTask(task.optString("bot_id"), cid, "user", Core.OWNER, m.optString("text"), J.truncate(m.optString("text"), 80), "",
                    null, PRIORITY_USER, m.optString("id"), null, null);
        }
        if (J.str(task, "routine_run_id", null) != null)
            db.exec("UPDATE routine_runs SET status = ?, error = ? WHERE id = ?", status, error, task.optString("routine_run_id"));
    }

    void propagate(JSONObject task, String status, String result, String error) {
        for (JSONObject h : db.all("SELECT * FROM handoffs WHERE to_task_id = ? AND status = 'open'", task.optString("id"))) {
            db.exec("UPDATE handoffs SET status = ?, completed_at = ? WHERE id = ?", status, J.nowIso(), h.optString("id"));
            b.core.emit("handoff.completed", J.str(task, "conversation_id", null), h.optString("from_task_id"), null, h.optString("from_bot_id"),
                    J.obj("handoff_id", h.optString("id"), "to_task_id", task.optString("id"), "status", status));
            JSONObject payload = J.obj("task_id", task.optString("id"), "status", status, "result", result, "error", error);
            if (J.bool(h, "wait") && J.str(h, "step_id", null) != null) {
                db.exec("UPDATE run_steps SET status = 'completed', output_json = ?, updated_at = ? WHERE id = ? AND status = 'waiting'",
                        payload.toString(), J.nowIso(), h.optString("step_id"));
                JSONObject pr = runFor(h.optString("from_task_id"));
                if (pr != null && "waiting_dependency".equals(pr.optString("status"))
                        && db.count("SELECT COUNT(*) FROM run_steps WHERE run_id = ? AND status = 'waiting'", pr.optString("id")) == 0) {
                    db.exec("UPDATE runs SET status = 'queued', waiting_json = '{}', updated_at = ? WHERE id = ?", J.nowIso(), pr.optString("id"));
                    b.core.emit("run.woken", null, h.optString("from_task_id"), pr.optString("id"), h.optString("from_bot_id"),
                            J.obj("by_task", task.optString("id")));
                }
            } else {
                JSONObject parent = getTask(h.optString("from_task_id"));
                if (parent != null && !"cancelled".equals(parent.optString("status"))) {
                    try {
                        createTask(h.optString("from_bot_id"), J.str(parent, "conversation_id", null), "bot", task.optString("bot_id"),
                                "Result of delegated task " + task.optString("id") + " (" + status + "):\n" + (result != null ? result : error),
                                "Result from delegated task: " + task.optString("title"), "", null, PRIORITY_DELEGATED, null, null, null);
                    } catch (RuntimeException ignored) { }
                }
            }
        }
        b.wake();
    }

    public String openHandoff(JSONObject parent, String childTaskId, String toBot, boolean wait, String stepId) {
        String hid = J.id("hof");
        db.insert("handoffs", J.obj("id", hid, "from_task_id", parent.optString("id"), "from_bot_id", parent.optString("bot_id"),
                "to_task_id", childTaskId, "to_bot_id", toBot, "wait", wait, "step_id", stepId, "status", "open", "created_at", J.nowIso()));
        b.core.emit("handoff.created", J.str(parent, "conversation_id", null), parent.optString("id"), null, parent.optString("bot_id"),
                J.obj("handoff_id", hid, "to_task_id", childTaskId, "to_bot_id", toBot, "wait", wait));
        return hid;
    }

    public String dedupeDelegation(JSONObject parent, String toBot, String instructions) {
        return db.scalar("SELECT t.id FROM handoffs h JOIN tasks t ON t.id = h.to_task_id WHERE h.from_task_id = ? AND h.to_bot_id = ? AND t.instructions = ?",
                parent.optString("id"), toBot, instructions);
    }
}
