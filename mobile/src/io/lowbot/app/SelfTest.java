package io.lowbot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.lowbot.core.ApiError;
import io.lowbot.core.Backend;
import io.lowbot.core.J;
import io.lowbot.core.Router;
import io.lowbot.core.Routines;

/**
 * On-device acceptance test of the phone-hosted backend, run in CI on an Android
 * emulator:  adb shell am broadcast -n io.lowbot.app/.SelfTest  (needs the DUMP
 * permission, which only the shell/system has). Uses its own database and the
 * labelled scripted mock provider — no real model, no user data touched.
 * Prints "LOWBOT_SELFTEST PASS <n>" or "LOWBOT_SELFTEST FAIL <reason>" to logcat.
 */
public class SelfTest extends BroadcastReceiver {
    static final String TAG = "LOWBOT_SELFTEST";

    @Override
    public void onReceive(final Context context, Intent intent) {
        // The run (with the Linux download) can outlast a broadcast's time limit, so it continues on its own
        // thread in the app process, which stays alive because the app is on screen during the test.
        new Thread(new Runnable() {
            public void run() { Log.i(TAG, runAll(context.getApplicationContext())); }
        }, "selftest").start();
    }

    static final List<String> passed = new ArrayList<String>();

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        passed.add(name);
        Log.i(TAG, "ok " + name);
    }

    static String runAll(Context ctx) {
        passed.clear();
        Backend b = null;
        try {
            b = Backend.forTest(ctx, "selftest.db");
            b.start();
            b = scenario(ctx, b);
            browser(ctx, b);
            linux(ctx, b);
            String r = "LOWBOT_SELFTEST PASS " + passed.size() + " " + passed;
            write(ctx, r);
            return r;
        } catch (Throwable e) {
            Log.e(TAG, "failure", e);
            String r = "LOWBOT_SELFTEST FAIL after " + passed + ": " + e;
            write(ctx, r);
            return r;
        } finally {
            if (b != null) b.engine.stop();
        }
    }

    static void write(Context ctx, String s) {
        try {
            FileOutputStream fo = new FileOutputStream(new File(ctx.getFilesDir(), "selftest.txt"));
            fo.write(s.getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignored) { }
    }

    static JSONObject api(Router r, String method, String path, Object body) {
        Router.Response res = r.handle(method, path, body == null ? null : body.toString());
        if (res.status >= 400) throw new AssertionError(method + " " + path + " -> " + res.status + " " + res.text);
        Object v = J.parse(res.text);
        if (res.text != null && res.text.startsWith("[")) return J.obj("items", J.parseArr(res.text));
        return (JSONObject) v;
    }

    static JSONObject lastBotMessage(Backend b, String cid) {
        return b.core.db.one("SELECT * FROM messages WHERE conversation_id = ? AND author_type = 'bot' ORDER BY seq DESC", cid);
    }

    static String runStatus(Backend b, String taskId) {
        return b.core.db.scalar("SELECT status FROM runs WHERE task_id = ?", taskId);
    }

    static Backend scenario(Context ctx, Backend b) throws Exception {
        Router r = new Router(b);
        check("ok".equals(api(r, "GET", "/api/v2/health", null).optString("status")), "health");

        JSONArray script = new JSONArray()
                .put(J.obj("when", "take it into account", "reply", "steered: {{last}}"))
                .put(J.obj("when", "mail widget", "call", J.obj("name", "widget.create", "arguments",
                        J.obj("title", "Mail", "content", "- 2 new messages", "refresh", "Check my inbox", "schedule", "every hour"))))
                .put(J.obj("after_tool", "widget.create", "reply", "widget made"))
                .put(J.obj("when", "say nothing", "reply", ""))
                .put(J.obj("when", "Write the new handoff now", "reply", "## Goal\nKeep testing LowBot.\n## Next steps\n- continue"))
                .put(J.obj("when", "write a file", "call", J.obj("name", "workspace.write", "arguments", J.obj("path", "notes/a.txt", "content", "hello phone"))))
                .put(J.obj("after_tool", "workspace.write", "reply", "file written"))
                .put(J.obj("when", "post it", "call", J.obj("name", "http.post", "arguments", J.obj("url", "https://example.invalid/hook", "json", J.obj("a", 1)))))
                .put(J.obj("after_tool", "http.post", "reply", "post step finished: {{last}}"))
                .put(J.obj("when", "ask me", "call", J.obj("name", "user.ask", "arguments", J.obj("question", "Which colour?"))))
                .put(J.obj("after_tool", "user.ask", "reply", "thanks: {{last}}"))
                .put(J.obj("when", "need key", "call", J.obj("name", "secret.request", "arguments", J.obj("name", "DEMO_KEY", "description", "Demo API key"))))
                .put(J.obj("after_tool", "secret.request", "reply", "got placeholder {{last}}"))
                .put(J.obj("when", "please delegate", "call", J.obj("name", "task.delegate", "arguments", J.obj("bot", "helper", "instructions", "Count to three", "wait", true))))
                .put(J.obj("after_tool", "task.delegate", "reply", "delegation done: {{last}}"))
                .put(J.obj("when", "remember", "call", J.obj("name", "memory.save", "arguments", J.obj("content", "The user likes green tea"))))
                .put(J.obj("after_tool", "memory.save", "reply", "remembered"))
                .put(J.obj("when", "set up team space", "call", J.obj("name", "project.create", "arguments",
                        J.obj("name", "team", "rules", "Write every report to report.md.", "members", new JSONArray().put("helper")))))
                .put(J.obj("after_tool", "project.create", "reply", "workspace ready"))
                .put(J.obj("when", "hire a researcher", "call", J.obj("name", "bot.create", "arguments",
                        J.obj("name", "Researcher", "soul", "# Researcher\nFinds sources.", "workspace", "team"))))
                .put(J.obj("after_tool", "bot.create", "reply", "hired: {{last}}"))
                .put(J.obj("when", "fire the researcher", "call", J.obj("name", "bot.delete", "arguments", J.obj("bot", "researcher", "reason", "test"))))
                .put(J.obj("after_tool", "bot.delete", "reply", "removed: {{last}}"))
                .put(J.obj("when", "you handle my trading", "call", J.obj("name", "self.set_role", "arguments", J.obj("role", "Trading research and paper trading"))))
                .put(J.obj("after_tool", "self.set_role", "reply", "role set"));
        JSONObject prov = api(r, "POST", "/api/v2/providers", J.obj("kind", "scripted_mock", "name", "Mock", "script", script));
        check(prov.optBoolean("is_mock"), "mock provider is labelled");
        JSONObject bot = api(r, "POST", "/api/v2/bots", J.obj("name", "Asystent", "role_description", "test"));
        JSONObject helper = api(r, "POST", "/api/v2/bots", J.obj("name", "Helper"));
        check("helper".equals(helper.optString("handle")), "handle from name");
        String cid = api(r, "POST", "/api/v2/bots/" + bot.optString("id") + "/conversation", null).optString("id");

        // 1. plain chat
        JSONObject sent = api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "hello there", "client_msg_id", "c1"));
        JSONObject dup = api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "hello there", "client_msg_id", "c1"));
        check(dup.optBoolean("duplicate") && dup.optString("message_id").equals(sent.optString("message_id")), "client_msg_id idempotent");
        b.engine.drain(20000);
        check(lastBotMessage(b, cid).optString("text").equals("[mock] hello there"), "bot replies");

        // 2. workspace tool
        api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "write a file"));
        b.engine.drain(20000);
        check("file written".equals(lastBotMessage(b, cid).optString("text")), "workspace.write tool");
        check(new File(b.core.workspace, "notes/a.txt").exists(), "file on disk");
        JSONObject listing = api(r, "GET", "/api/v2/workspace/files?path=notes", null);
        check(listing.optJSONArray("items").length() == 1 && "notes/a.txt".equals(listing.optJSONArray("items").optJSONObject(0).optString("path")), "file explorer lists the workspace");
        Router.Response fileRes = r.handle("GET", "/api/v2/workspace/file?path=notes%2Fa.txt", null);
        check(fileRes.status == 200 && "hello phone".equals(new String(fileRes.bytes, "UTF-8")), "file explorer opens a file");
        check(r.handle("GET", "/api/v2/workspace/files?path=..%2F..", null).status == 403, "file explorer stays inside the workspace");

        // 3. approval → deny (external action needs approval, nothing is sent)
        JSONObject t3 = api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "post it")).optJSONArray("tasks").getJSONObject(0);
        b.engine.drain(20000);
        check("waiting_approval".equals(runStatus(b, t3.optString("id"))), "external action waits for approval");
        JSONObject appr = api(r, "GET", "/api/v2/approvals", null).optJSONArray("items").getJSONObject(0);
        boolean stale = false;
        try { b.approvals.decide(appr.optString("id"), "approve", "wrong-hash"); } catch (ApiError e) { stale = e.status == 409; }
        check(stale, "approval bound to args hash");
        api(r, "POST", "/api/v2/approvals/" + appr.optString("id") + "/decide", J.obj("decision", "deny", "args_hash", appr.optString("args_hash")));
        b.engine.drain(20000);
        check(lastBotMessage(b, cid).optString("text").contains("denied"), "denied step reported to model");
        check(b.core.db.count("SELECT COUNT(*) FROM operations") == 0, "denied action never dispatched");

        // 4. user.ask → answer
        JSONObject t4 = api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "ask me")).optJSONArray("tasks").getJSONObject(0);
        b.engine.drain(20000);
        check("waiting_input".equals(runStatus(b, t4.optString("id"))), "user.ask parks run");
        api(r, "POST", "/api/v2/tasks/" + t4.optString("id") + "/answer", J.obj("answer", "green"));
        b.engine.drain(20000);
        check(lastBotMessage(b, cid).optString("text").contains("green"), "answer resumes run");

        // 5. secret.request → secure answer; value never in messages/events
        JSONObject t5 = api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "need key")).optJSONArray("tasks").getJSONObject(0);
        b.engine.drain(20000);
        boolean plainRefused = false;
        try { b.tasks.answerInput(t5.optString("id"), "sk-should-not-be-here"); } catch (ApiError e) { plainRefused = true; }
        check(plainRefused, "secret not accepted as chat answer");
        api(r, "POST", "/api/v2/tasks/" + t5.optString("id") + "/secret", J.obj("value", "super-secret-value-123"));
        b.engine.drain(20000);
        check(lastBotMessage(b, cid).optString("text").contains("{{secret:DEMO_KEY}}"), "placeholder returned");
        check(b.core.db.count("SELECT COUNT(*) FROM messages WHERE text LIKE '%super-secret-value%'") == 0
                && b.core.db.count("SELECT COUNT(*) FROM events WHERE payload_json LIKE '%super-secret-value%'") == 0
                && b.core.db.count("SELECT COUNT(*) FROM run_steps WHERE output_json LIKE '%super-secret-value%'") == 0, "secret value absent from chat/events/steps");
        check("super-secret-value-123".equals(b.core.secretGet(b.core.secretIdByName("user:DEMO_KEY"))), "secret decrypts from keystore vault");

        // 6. delegation with wait
        api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "please delegate"));
        b.engine.drain(30000);
        check(lastBotMessage(b, cid).optString("text").startsWith("delegation done"), "delegation waits and resumes");
        check(b.core.db.count("SELECT COUNT(*) FROM handoffs WHERE status = 'completed'") == 1, "handoff recorded");

        // 7. memory + FTS search
        api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "remember this"));
        b.engine.drain(20000);
        check(b.memory.search("green tea", b.bots.get(bot.optString("id")), 5).size() == 1, "memory saved and searchable");
        check(b.mind.memories(bot.optString("id")).size() == 1, "small memory written as memories/*.md");
        check(b.mind.soul(bot.optString("id")).startsWith("# Asystent"), "soul.md seeded for a new bot");

        // 7b. project rules + handoff instead of compaction
        api(r, "PATCH", "/api/v2/conversations/" + cid, J.obj("project", "demo"));
        b.mind.setProjectRules("demo", "Use tabs. Run tests before saying done.");
        check(b.mind.promptSection(bot.optString("id"), "demo").contains("Use tabs"), "project AGENTS.md in prompt");
        b.core.settings.handoffMaxMessages = 4;
        api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "hello rotation"));
        b.engine.drain(20000);
        b.core.settings.handoffMaxMessages = 40;
        check(b.mind.handoff(bot.optString("id")).contains("Keep testing LowBot"), "handoff replaced agents.md");
        check(b.mind.handoffSeq(bot.optString("id"), cid) > 0, "context restarts after the handoff");
        check(lastBotMessage(b, cid).optString("text").equals("[mock] hello rotation"), "bot continues after the handoff");

        // 7c. an empty model answer is retried once, then reported clearly (never "(no response)")
        String cidEmpty = api(r, "POST", "/api/v2/bots/" + helper.optString("id") + "/conversation", null).optString("id");
        api(r, "POST", "/api/v2/conversations/" + cidEmpty + "/messages", J.obj("text", "say nothing"));
        b.engine.drain(30000);
        check(b.core.db.count("SELECT COUNT(*) FROM messages WHERE text = '(no response)'") == 0
                && b.core.db.count("SELECT COUNT(*) FROM messages WHERE author_type = 'system' AND text LIKE '%empty answer%'") == 1, "empty answer reported, not '(no response)'");

        // 7c2. Steering: a message to a bot that is still working joins its run instead of starting another
        String cidSteer = api(r, "POST", "/api/v2/conversations", J.obj("kind", "group", "title", "steer", "bot_ids", new JSONArray().put(helper.optString("id")))).optString("id");
        JSONObject tSteer = api(r, "POST", "/api/v2/conversations/" + cidSteer + "/messages", J.obj("text", "post it now")).optJSONArray("tasks").getJSONObject(0);
        b.engine.drain(20000);
        check("waiting_approval".equals(runStatus(b, tSteer.optString("id"))), "steer setup: run is waiting");
        long before = b.core.db.count("SELECT COUNT(*) FROM tasks WHERE conversation_id = ?", cidSteer);
        JSONObject steerRes = api(r, "POST", "/api/v2/conversations/" + cidSteer + "/messages", J.obj("text", "add a footnote"));
        check(steerRes.optJSONArray("tasks").length() == 0 && tSteer.optString("id").equals(steerRes.optString("steered_task"))
                && b.core.db.count("SELECT COUNT(*) FROM tasks WHERE conversation_id = ?", cidSteer) == before, "message while working steers the run (no second task)");
        JSONObject aSteer = b.approvals.list("pending").get(0);
        b.approvals.decide(aSteer.optString("id"), "deny", aSteer.optString("args_hash"));
        b.engine.drain(20000);
        check(lastBotMessage(b, cidSteer).optString("text").contains("add a footnote"), "the bot sees the steering message in its next step");

        // 7c3. Widgets made by a bot (on by default; the user can switch it off)
        api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "make me a mail widget"));
        b.engine.drain(20000);
        JSONObject wList = api(r, "GET", "/api/v2/widgets", null);
        JSONObject wMade = wList.optJSONArray("widgets").optJSONObject(0);
        check(wMade != null && "Mail".equals(wMade.optString("title")) && !wMade.isNull("routine_id")
                && b.core.db.count("SELECT COUNT(*) FROM routines WHERE id = ?", wMade.optString("routine_id")) == 1, "bot makes a widget with a refresh routine");
        check(b.core.db.count("SELECT COUNT(*) FROM messages WHERE meta_json LIKE ?", "%" + wMade.optString("id") + "%") == 1, "widget card posted in the chat");
        check(api(r, "PATCH", "/api/v2/widgets/" + wMade.optString("id"), J.obj("on_home", true)).optBoolean("on_home"), "widget added to home");
        api(r, "POST", "/api/v2/widgets/settings", J.obj("bots_may_create", false));
        check(!b.tools.forBot(b.bots.get(bot.optString("id")), null, b.capabilities()).containsKey("widget.create"), "widgets switch off removes the tool");
        api(r, "POST", "/api/v2/widgets/settings", J.obj("bots_may_create", true));
        for (String sh : new String[]{"circle", "blob", "square", "pill", "triangle", "hexagon", "cloud", "drop"}) {
            android.graphics.Bitmap bm = BotSprites.draw("shape:" + sh + ":#ef2b3c", "x", 112, BotSprites.AWAKE, 10);
            int px = bm.getPixel(56, (int) (bm.getHeight() * 0.62f));
            check(android.graphics.Color.alpha(px) > 0, "widget sprite draws " + sh);
        }

        // 7d. Team: shared workspace, a bot creates and deletes another bot (with the user's approval)
        api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "set up team space"));
        b.engine.drain(20000);
        check("workspace ready".equals(lastBotMessage(b, cid).optString("text")), "bot creates a shared workspace");
        check(b.mind.members("team").contains(helper.optString("id")) && b.mind.members("team").contains(bot.optString("id")), "workspace members");
        check(b.mind.promptSection(helper.optString("id"), "team").contains("report.md"), "shared rules reach other members");
        new File(b.mind.projectDir("team"), "notes.md").createNewFile();
        check(b.mind.promptSection(bot.optString("id"), "team").contains("team/notes.md"), "shared files listed for members");
        JSONObject tHire = api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "hire a researcher")).optJSONArray("tasks").getJSONObject(0);
        b.engine.drain(20000);
        check("waiting_approval".equals(runStatus(b, tHire.optString("id"))) && b.bots.byHandle("researcher") == null, "bot.create waits for the user");
        JSONObject aHire = b.approvals.list("pending").get(0);
        b.approvals.decide(aHire.optString("id"), "approve", aHire.optString("args_hash"));
        b.engine.drain(20000);
        JSONObject researcher = b.bots.byHandle("researcher");
        check(researcher != null && lastBotMessage(b, cid).optString("text").startsWith("hired"), "bot created another bot");
        check(b.engine.toolLog(tHire.optString("id"), "other").contains("bot.create"), "earlier tool calls stay visible to the bot");
        check(researcher.optString("avatar").matches("shape:[a-z]+:#[0-9a-fA-F]{6}"), "created bot gets a random character sprite");
        JSONObject emoji = api(r, "POST", "/api/v2/bots", J.obj("name", "Emoji", "avatar", "\uD83D\uDCCA"));
        check(emoji.optString("avatar").startsWith("shape:"), "emoji avatar replaced by a sprite");
        b.bots.delete(emoji.optString("id"));
        JSONObject limited = api(r, "POST", "/api/v2/bots", J.obj("name", "Limited", "tools", new JSONArray().put("workspace.*").put("bot.*")));
        JSONObject sub = b.bots.create(J.obj("name", "Sub"), b.bots.get(limited.optString("id")));
        check(sub.optJSONArray("tools").length() == 2, "default tools narrowed to the creator's (" + sub.optJSONArray("tools") + ")");
        b.bots.delete(sub.optString("id"));
        b.bots.delete(limited.optString("id"));
        check(b.mind.soul(researcher.optString("id")).contains("Finds sources") && b.mind.members("team").contains(researcher.optString("id")),
                "new bot gets its soul and joins the workspace");
        JSONObject tFire = api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "fire the researcher")).optJSONArray("tasks").getJSONObject(0);
        b.engine.drain(20000);
        check("waiting_approval".equals(runStatus(b, tFire.optString("id"))), "bot.delete always asks");
        JSONObject aFire = b.approvals.list("pending").get(0);
        b.approvals.decide(aFire.optString("id"), "approve", aFire.optString("args_hash"));
        b.engine.drain(20000);
        check(b.bots.byHandle("researcher") == null && !b.mind.members("team").contains(researcher.optString("id")), "bot deleted another bot");

        // 7e. A bot sets its own role from what the user asks; team tools need no per-bot switch
        api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "from now on you handle my trading"));
        b.engine.drain(20000);
        check("Trading research and paper trading".equals(b.bots.get(bot.optString("id")).optString("role_description")), "bot sets its own role");
        JSONObject bare = api(r, "POST", "/api/v2/bots", J.obj("name", "Bare", "tools", new JSONArray().put("web.fetch").put("-linux.*")));
        java.util.Map<String, io.lowbot.engine.Tools.Spec> bareTools = b.tools.forBot(b.bots.get(bare.optString("id")), null, null);
        check(bareTools.containsKey("bot.create") && bareTools.containsKey("self.set_role") && !bareTools.containsKey("linux.run"),
                "team tools always available; -linux.* switches the terminal off");
        b.bots.delete(bare.optString("id"));

        // 8. Always allow stores a rule for this bot + tool
        JSONObject t8 = api(r, "POST", "/api/v2/conversations/" + cid + "/messages", J.obj("text", "post it again")).optJSONArray("tasks").getJSONObject(0);
        b.engine.drain(20000);
        JSONObject a8 = b.approvals.list("pending").get(0);
        b.approvals.decide(a8.optString("id"), "always", a8.optString("args_hash"));
        check(b.core.db.count("SELECT COUNT(*) FROM policy_rules WHERE bot_id = ? AND tool_pattern = 'http.post' AND effect = 'allow'", bot.optString("id")) == 1, "always allow saves rule");
        b.engine.drain(30000);
        String st8 = runStatus(b, t8.optString("id"));
        check("completed".equals(st8) || "unknown_outcome".equals(st8), "approved external call ran exactly once (" + st8 + ")");

        // 9. Duplicate (Grok): "<name> copy", routines copied disabled, no history
        api(r, "POST", "/api/v2/routines", J.obj("bot_id", bot.optString("id"), "name", "Morning", "prompt", "brief me", "schedule", "every weekday at 8:00"));
        JSONObject copy = api(r, "POST", "/api/v2/bots/" + bot.optString("id") + "/duplicate", null);
        check("Asystent copy".equals(copy.optString("name")), "duplicate name");
        check(b.core.db.count("SELECT COUNT(*) FROM routines WHERE bot_id = ? AND enabled = 0", copy.optString("id"))
                == b.core.db.count("SELECT COUNT(*) FROM routines WHERE bot_id = ?", bot.optString("id"))
                && b.core.db.count("SELECT COUNT(*) FROM routines WHERE bot_id = ? AND enabled = 1", copy.optString("id")) == 0, "duplicate routines disabled");
        check(b.core.db.count("SELECT COUNT(*) FROM memories WHERE bot_id = ?", copy.optString("id")) == 0, "duplicate has no memory");

        // 10. Routines: DST rules in Europe/Warsaw
        JSONObject s1 = Routines.parse("codziennie o 2:30", "Europe/Warsaw");
        List<String> spring = Routines.nextRuns(s1, "Europe/Warsaw", 1, Instant.parse("2026-03-28T12:00:00Z"));
        List<String> spring2 = Routines.nextRuns(s1, "Europe/Warsaw", 1, Instant.parse(spring.get(0)));
        check(spring.get(0).equals("2026-03-29T01:00:00.000Z") && spring2.get(0).equals("2026-03-30T00:30:00.000Z"),
                "spring-forward gap fires at 03:00 local (" + spring + spring2 + ")");
        List<String> autumn = Routines.nextRuns(s1, "Europe/Warsaw", 2, Instant.parse("2026-10-24T12:00:00Z"));
        check(autumn.get(0).equals("2026-10-25T00:30:00.000Z") && autumn.get(1).equals("2026-10-26T01:30:00.000Z"),
                "fall-back fires once at first 02:30 (" + autumn + ")");
        check("cron".equals(Routines.parse("every weekday at 8:00 AM", "Europe/Warsaw").optString("kind")), "english schedule");

        // 11. routine fires once per slot (dedupe)
        JSONObject rt = b.routines.list(bot.optString("id")).get(0);
        JSONObject f1 = b.routines.fire(rt.optString("id"), "slot:x", "schedule", null);
        JSONObject f2 = b.routines.fire(rt.optString("id"), "slot:x", "schedule", null);
        check(f2.optBoolean("duplicate") && f1.optString("routine_run_id").equals(f2.optString("routine_run_id")), "routine slot dedupe");
        b.engine.drain(20000);

        // 12. crash recovery: a run left 'running' by a dead process is requeued and finished
        JSONObject t12 = b.tasks.createTask(bot.optString("id"), cid, "user", "local-user", "hello again", "", "", null, 80, null, null, null);
        b.engine.stop();
        final String rid = t12.optString("run_id");
        b.core.db.exec("UPDATE runs SET status = 'running', owner = 'dead-process' WHERE id = ?", rid);
        b.core.db.close();
        Backend b2 = Backend.reopen(ctx, "selftest.db");
        b2.start();
        b2.engine.drain(20000);
        check("completed".equals(b2.core.db.scalar("SELECT status FROM runs WHERE id = ?", rid)), "run recovered after restart");
        check(b2.core.db.count("SELECT COUNT(*) FROM events WHERE type = 'run.recovered'") >= 1, "recovery audited");
        b2.engine.stop();

        // 12b. ChatGPT sign-in plumbing (no network): consent required, PKCE URL, loopback listener, JWT claims
        boolean needsConsent = false;
        try { b2.chatgpt.startLogin(false); } catch (ApiError e) { needsConsent = e.status == 422; }
        check(needsConsent, "chatgpt sign-in requires explicit consent");
        String url = b2.chatgpt.startLogin(true).optString("url");
        check(url.startsWith("https://auth.openai.com/oauth/authorize?") && url.contains("code_challenge_method=S256") && url.contains("state="), "chatgpt PKCE authorize URL");
        java.net.Socket probe = new java.net.Socket("127.0.0.1", 1455);
        probe.getOutputStream().write("GET /auth/callback?state=wrong&code=x HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes("UTF-8"));
        String reply = new java.util.Scanner(probe.getInputStream(), "UTF-8").useDelimiter("\\A").next();
        probe.close();
        check(reply.contains("400") && reply.contains("state mismatch"), "chatgpt callback rejects wrong state (CSRF)");
        String payload = android.util.Base64.encodeToString("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acc_1\"}}".getBytes("UTF-8"),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        check("acc_1".equals(io.lowbot.core.ChatGpt.accountIdOf("h." + payload + ".s")), "chatgpt account id from JWT");
        b2.chatgpt.logout();

        // 13. SSRF guard
        boolean blocked = false;
        try { io.lowbot.tools.Net.vet("http://169.254.169.254/latest/meta-data", true); } catch (io.lowbot.tools.Net.Denied e) { blocked = true; }
        check(blocked, "metadata address blocked");
        blocked = false;
        try { io.lowbot.tools.Net.vet("http://192.168.1.1/", false); } catch (io.lowbot.tools.Net.Denied e) { blocked = true; }
        check(blocked, "LAN blocked by default");
        return b2;
    }

    /** The bots' Linux: install the pinned Alpine through proot, run commands in a persistent shell. */
    static void linux(Context ctx, Backend b) throws Exception {
        Linux l = new Linux(ctx, b);
        if (!l.available()) { passed.add("linux(skipped: runtime not in this build)"); return; }
        try {
            if (!l.installed()) { l.doInstall(); }
        } catch (Exception e) {
            Log.w(TAG, "linux install skipped: " + e);
            passed.add("linux(skipped: install " + e.getMessage() + ")");
            return;
        }
        check(l.installed(), "linux installed (Alpine " + l.installedVersion + ")");
        JSONObject bot = b.bots.list(true).get(0);
        File ws = io.lowbot.tools.Builtin.rootFor(b, bot);
        new File(ws, "linux-test.txt").createNewFile();
        JSONObject r1;
        try {
            r1 = l.run(bot.optString("id"), ws, "cat /etc/alpine-release && ls /workspace && cd /tmp && export LB=42", 60, "bot", null);
        } catch (io.lowbot.engine.Tools.ToolError e) {
            if ("x86_64".equals(Linux.arch()) && e.getMessage().contains("can't fork")) {
                // Emulator-only limitation: x86_64 Android blocks the fork syscall; phones (arm64) use clone.
                passed.add("linux shell(skipped on x86_64 emulator: fork blocked — verify on an arm64 phone)");
                l.closeAll();
                return;
            }
            throw e;
        }
        check(r1.optInt("exit_code") == 0 && r1.optString("output").contains("linux-test.txt"), "linux runs commands with /workspace (" + J.truncate(r1.optString("output"), 120) + ")");
        JSONObject r2 = l.run(bot.optString("id"), ws, "pwd; echo $LB", 30, "bot", null);
        check(r2.optString("output").contains("/tmp") && r2.optString("output").contains("42"), "linux shell persists cd and variables");
        JSONObject r3 = l.run(bot.optString("id"), ws, "false", 30, "bot", null);
        check(r3.optInt("exit_code") == 1, "linux exit code reported");
        boolean timedOut = false;
        try { l.run(bot.optString("id"), ws, "sleep 30", 2, "bot", null); } catch (io.lowbot.engine.Tools.ToolError e) { timedOut = e.getMessage().contains("timed out"); }
        check(timedOut, "linux command timeout kills the shell");
        check(l.run(bot.optString("id"), ws, "echo back", 30, "bot", null).optString("output").contains("back"), "linux shell restarts after a timeout");
        l.closeAll();
    }

    /** The bots' browser: open a page in an offscreen WebView and read it. Network may be unavailable in CI. */
    static void browser(Context ctx, Backend b) {
        Computer c = new Computer(ctx, b);
        try {
            JSONObject bot = b.bots.list(true).get(0);
            Computer.Surface s = c.surface(bot.optString("id"));
            c.navigate(s, "https://example.com/");
            JSONObject st = c.state(s);
            check(st.optString("title").toLowerCase().contains("example"), "browser loads a page");
            JSONObject page = c.readPage(s, 150, false);
            check(page.optJSONArray("elements").length() >= 1 && page.optJSONArray("elements").optString(0).startsWith("[1]<a")
                    && page.has("scroll"), "browser-use style element list (" + J.truncate(page.optJSONArray("elements").optString(0), 60) + ")");
            JSONObject md = J.parse(c.js(s, Computer.EXTRACT_JS + "(5000)", 10000));
            check(md.optString("markdown").contains("# Example Domain"), "page extracted as Markdown");
            c.js(s, Computer.HIGHLIGHT_JS + "(true)", 5000);
            check("1".equals(c.js(s, "String(document.querySelectorAll('#__lb_hl > div').length)", 5000)), "screenshot highlight overlay");
            c.js(s, Computer.HIGHLIGHT_JS + "(false)", 5000);
            c.newTab(s);
            check(c.tabs(s).optJSONArray("tabs").length() == 2, "second browser tab");
            c.closeTab(s, 1);
            byte[] jpg = c.screenshot(s.id);
            check(jpg.length > 1000, "browser screenshot");
            c.close(s.id);
        } catch (io.lowbot.engine.Tools.ToolError e) {
            Log.w(TAG, "browser check skipped: " + e.getMessage());
            passed.add("browser(skipped: " + e.getMessage() + ")");
        }
    }
}
