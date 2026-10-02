package io.lowbot.app;

import android.content.Context;
import org.json.JSONObject;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import io.lowbot.core.Backend;
import io.lowbot.core.J;
import io.lowbot.engine.Model;
import io.lowbot.engine.Tools;

/** Exercises new runs and persisted legacy caps through the real engine. */
final class StepLimitTest {
    static void run(Context ctx) throws Exception {
        for (int storedCap : new int[] {0, 1, 24}) scenario(ctx, storedCap);
    }

    static void scenario(Context ctx, int storedCap) throws Exception {
        Backend b = Backend.forTest(ctx, "step-limit-" + storedCap + ".db");
        final AtomicInteger calls = new AtomicInteger();
        try {
            JSONObject profile = b.providers.upsert(J.obj("kind", "scripted_mock", "name", "Long task"), null);
            b.providers.overrides.put(profile.optString("id"), new Model.Adapter() {
                public List<String> listModels() { return Collections.singletonList("scripted-mock"); }
                public Model.Response complete(Model.Request req) {
                    Model.Response out = new Model.Response();
                    int n = calls.incrementAndGet();
                    if (n <= 40) out.toolCalls.add(new Model.ToolCall("list-" + n,
                            Tools.wireName("workspace.list"), J.obj()));
                    else out.text = "All forty steps completed";
                    return out;
                }
            });
            JSONObject bot = b.bots.create(J.obj("name", "Long task", "provider_profile_id", profile.optString("id"),
                    "budget", J.obj("max_steps", 1)), null);
            String cid = b.tasks.privateConversation(bot.optString("id")).optString("id");
            JSONObject sent = b.tasks.postUserMessage(cid, "Complete forty steps", "long-task", null, null);
            String tid = sent.optJSONArray("tasks").optJSONObject(0).optString("id");
            JSONObject run = b.tasks.runFor(tid);
            SelfTest.check(run.optInt("max_steps", -1) == 0, "new run ignores legacy per-bot step cap " + storedCap);
            b.core.db.exec("UPDATE runs SET max_steps = ? WHERE id = ?", storedCap, run.optString("id"));
            b.start();
            b.engine.drain(30000);
            SelfTest.check("completed".equals(b.tasks.requireTask(tid).optString("status")) && calls.get() == 41,
                    "forty tool steps complete with stored legacy cap " + storedCap);
            SelfTest.check(b.core.db.count("SELECT COUNT(*) FROM run_steps WHERE run_id = ? AND kind = 'model'",
                    run.optString("id")) == 41, "all model steps persist beyond legacy cap " + storedCap);
        } finally {
            b.engine.stop();
        }
    }
}
