package io.lowbot.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.lowbot.core.Backend;
import io.lowbot.core.Core;
import io.lowbot.core.J;
import io.lowbot.engine.Model;
import io.lowbot.engine.Tools;

/** Deterministic real-engine regression tests; invoked by the on-device SelfTest. */
final class PriorityMessageTest {
    static void run(Context ctx) throws Exception {
        scenario(ctx, false);
        scenario(ctx, true);
    }

    static void scenario(Context ctx, final boolean staleFinal) throws Exception {
        final Backend b = Backend.forTest(ctx, staleFinal ? "priority-final.db" : "priority-tools.db");
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger modelCalls = new AtomicInteger(), priorityCalls = new AtomicInteger();
        final java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try {
            JSONObject profile = b.providers.upsert(J.obj("kind", "scripted_mock", "name", "Priority test"), null);
            b.providers.overrides.put(profile.optString("id"), new Model.Adapter() {
                public List<String> listModels() { return Collections.singletonList("scripted-mock"); }
                public Model.Response complete(Model.Request req) throws Model.ProviderError {
                    Model.Response out = new Model.Response();
                    String last = req.messages.get(req.messages.size() - 1).optString("content");
                    if (last.startsWith("[Priority user message]")) {
                        if (!req.tools.isEmpty()) throw new Model.ProviderError("test", "Priority turn exposed tools");
                        priorityCalls.incrementAndGet();
                        boolean question = last.contains("2 + 2");
                        out.text = J.obj("reply", question ? "4" : "Added the footnote to my task list.",
                                "todo", question ? "" : "Add a footnote").toString();
                    } else if (modelCalls.incrementAndGet() == 1) {
                        started.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new Model.ProviderError("test", "Timed out waiting for follow-up");
                        } catch (InterruptedException e) { throw new Model.ProviderError("test", "Interrupted"); }
                        if (staleFinal) out.text = "Stale final answer";
                        else out.toolCalls.add(new Model.ToolCall("priority-write", Tools.wireName("workspace.write"),
                                J.obj("path", "priority-test.txt", "content", "Original work")));
                    } else {
                        if (!req.system.contains("- [ ] Add a footnote") || !req.messages.toString().contains("already replied"))
                            throw new Model.ProviderError("test", "Continuation lost checklist or acknowledgement");
                        out.text = "Original task and footnote completed";
                    }
                    return out;
                }
            });
            JSONObject bot = b.bots.create(J.obj("name", "Priority bot", "provider_profile_id", profile.optString("id"),
                    "policy", new JSONArray().put(J.obj("tool", "workspace.write", "effect", "allow"))), null);
            final String cid = b.tasks.privateConversation(bot.optString("id")).optString("id");
            b.core.listeners.add(new Core.EventListener() { public void onEvent(JSONObject ev) {
                if ("run.tool_started".equals(ev.optString("type"))) order.add("tool");
                if ("message.created".equals(ev.optString("type")) && cid.equals(ev.optString("conversation_id"))) {
                    JSONObject m = b.core.db.one("SELECT text FROM messages WHERE id = ? AND author_type = 'bot'", ev.optJSONObject("payload").optString("message_id"));
                    if (m != null) order.add(m.optString("text"));
                }
            } });
            b.start();
            JSONObject sent = b.tasks.postUserMessage(cid, "Write the original file", "initial", null, null);
            String tid = sent.optJSONArray("tasks").optJSONObject(0).optString("id");
            SelfTest.check(started.await(10, TimeUnit.SECONDS), "priority test: model call is in flight");
            JSONObject question = b.tasks.postUserMessage(cid, "What is 2 + 2?", "question", null, null);
            b.tasks.postUserMessage(cid, "Add a footnote", "addition", null, null);
            JSONObject duplicate = b.tasks.postUserMessage(cid, "Add a footnote", "addition", null, null);
            SelfTest.check(tid.equals(question.optString("steered_task")) && duplicate.optBoolean("duplicate"), "priority messages target the existing task and deduplicate");
            release.countDown();
            b.engine.drain(20000);
            SelfTest.check(priorityCalls.get() == 2 && modelCalls.get() == 2, "one acknowledgement per follow-up, original work resumes");
            SelfTest.check(order.size() == (staleFinal ? 3 : 4) && "4".equals(order.get(0))
                    && "Added the footnote to my task list.".equals(order.get(1)), "question answered and todo confirmed before continuation");
            SelfTest.check(staleFinal ? !order.contains("Stale final answer") : "tool".equals(order.get(2)),
                    staleFinal ? "in-flight final answer cannot overtake the user's question" : "pending tool waits until both user messages are handled");
            SelfTest.check("completed".equals(b.tasks.requireTask(tid).optString("status"))
                    && b.core.db.count("SELECT COUNT(*) FROM tasks") == 1, "acknowledgements do not end or duplicate the original task");
            SelfTest.check(b.core.db.count("SELECT COUNT(*) FROM run_steps WHERE kind = 'steering' AND status = 'completed'") == 2,
                    "handled-message checkpoints persist exactly once");
            SelfTest.check(b.tasks.requireTask(tid).optString("instructions").contains("- [ ] Add a footnote"), "todo survives in durable task instructions");
        } finally {
            release.countDown();
            b.engine.stop();
        }
    }
}
