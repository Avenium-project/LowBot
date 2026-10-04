package io.lowbot.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import io.lowbot.core.Backend;
import io.lowbot.core.J;
import io.lowbot.engine.Model;
import io.lowbot.engine.Tools;

/** Reproduces premature completion using real provider response shapes. */
final class ResponseLifecycleTest {
    static JSONObject message(String text, String phase) {
        return J.obj("id", "msg-1", "type", "message", "phase", phase,
                "content", new JSONArray().put(J.obj("type", "output_text", "text", text)));
    }

    static String event(JSONObject value) { return "data: " + value.toString() + "\n\n"; }

    static Model.Response stream(String text) throws Exception {
        return Model.Responses.parseStream(new BufferedReader(new StringReader(text)));
    }

    static void run(Context ctx) throws Exception {
        JSONObject commentary = message("I will inspect the workspace now.", "commentary");
        String item = event(J.obj("type", "response.output_item.done", "item", commentary));
        String terminal = event(J.obj("type", "response.completed", "response", J.obj("status", "completed", "output", new JSONArray())));
        Model.Response progress = stream(item + terminal);
        SelfTest.check(!progress.canFinishTask() && "commentary".equals(progress.phase), "commentary survives SSE fallback and cannot finish a task");
        Model.Response complete = stream(event(J.obj("type", "response.completed", "response", J.obj("status", "completed",
                "output", new JSONArray().put(message("Done", "final_answer"))))).trim());
        SelfTest.check(complete.canFinishTask(), "final answer with terminal SSE event can finish, including EOF without blank line");
        for (String partial : new String[] {
                item,
                event(J.obj("type", "response.output_text.delta", "delta", "I will inspect")),
                event(J.obj("type", "response.output_item.done", "item", J.obj("type", "function_call", "call_id", "partial", "name", "task__complete", "arguments", "{}")))
        }) {
            boolean retry = false;
            try { stream(partial); } catch (Model.ProviderError e) { retry = e.retryable && "network".equals(e.kind); }
            SelfTest.check(retry, "EOF before terminal event is retryable, never a completed task or executable partial tool");
        }
        Model.Response truncated = Model.Responses.parse(J.obj("status", "incomplete", "incomplete_details", J.obj("reason", "max_output_tokens"),
                "output", new JSONArray().put(message("First part", "final_answer"))));
        SelfTest.check(!truncated.canFinishTask(), "truncated final answer cannot finish a task");
        Model.Response length = new Model.Response(); length.finish = "length";
        SelfTest.check(!length.canFinishTask(), "Chat Completions length limit cannot finish a task");
        Model.Request request = new Model.Request(); request.model = "test";
        request.messages.add(J.obj("role", "assistant", "content", progress.text, "phase", progress.phase));
        SelfTest.check("commentary".equals(Model.Responses.body(request).optJSONArray("input").optJSONObject(0).optString("phase")),
                "continuation requests retain the assistant commentary phase");
        for (int mode = 0; mode < 3; mode++) continuation(ctx, mode);
        retries(ctx, false);
        retries(ctx, true);
        android.content.pm.PackageInfo pkg = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS | android.content.pm.PackageManager.GET_SERVICES);
        SelfTest.check(java.util.Arrays.asList(pkg.requestedPermissions).contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"), "battery exemption permission declared");
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            boolean special = false;
            for (android.content.pm.ServiceInfo service : pkg.services) if (service.name.endsWith(".WorkService"))
                special = service.getForegroundServiceType() == android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            SelfTest.check(special, "local agent service declares its specialUse purpose");
        }
    }

    static void continuation(Context ctx, final int mode) throws Exception {
        final Backend b = Backend.forTest(ctx, "response-continuation-" + mode + ".db");
        final AtomicInteger calls = new AtomicInteger();
        try {
            JSONObject profile = b.providers.upsert(J.obj("kind", "scripted_mock", "name", "Continuation"), null);
            b.providers.overrides.put(profile.optString("id"), new Model.Adapter() {
                public List<String> listModels() { return Collections.singletonList("scripted-mock"); }
                public Model.Response complete(Model.Request req) throws Model.ProviderError {
                    int n = calls.incrementAndGet();
                    if (n == 1) {
                        JSONObject output = mode == 2
                                ? J.obj("type", "function_call", "call_id", "partial-complete", "name", Tools.wireName("task.complete"), "arguments", J.obj("result", "Premature").toString())
                                : message("I will inspect the workspace now.", mode == 0 ? "commentary" : "final_answer");
                        JSONArray items = new JSONArray();
                        if (mode == 2) items.put(message("Preparing the result", "commentary"));
                        items.put(output);
                        return Model.Responses.parse(J.obj("status", mode == 0 ? "completed" : "incomplete", "output", items));
                    }
                    if (n == 2) {
                        SelfTest.check(b.core.db.count("SELECT COUNT(*) FROM tasks WHERE status = 'completed'") == 0,
                                "progress text leaves the original task running");
                        if (mode < 2) SelfTest.check(req.messages.toString().contains(mode == 0 ? "commentary" : "cut short"),
                                "engine continues from saved commentary or truncated text " + mode);
                        else SelfTest.check(b.core.db.count("SELECT COUNT(*) FROM run_steps WHERE kind = 'tool'") == 0,
                                "incomplete task.complete call is discarded before any tool executes");
                        Model.Response r = new Model.Response();
                        r.toolCalls.add(new Model.ToolCall("list-after-commentary", Tools.wireName("workspace.list"), J.obj()));
                        return r;
                    }
                    return Model.Responses.parse(J.obj("status", "completed", "output", new JSONArray().put(message("Workspace inspected", "final_answer"))));
                }
            });
            JSONObject bot = b.bots.create(J.obj("name", "Continuation", "provider_profile_id", profile.optString("id")), null);
            String cid = b.tasks.privateConversation(bot.optString("id")).optString("id");
            JSONObject sent = b.tasks.postUserMessage(cid, "Inspect my workspace", "continuation", null, null);
            String tid = sent.optJSONArray("tasks").optJSONObject(0).optString("id");
            b.start(); b.engine.drain(20000);
            SelfTest.check(calls.get() == 3 && "completed".equals(b.tasks.requireTask(tid).optString("status")), "partial response continues through a real tool to the final answer " + mode);
            SelfTest.check(b.core.db.count("SELECT COUNT(*) FROM messages WHERE author_type = 'bot'") == 1
                    && "Workspace inspected".equals(b.tasks.requireTask(tid).optString("result_text")), "only final answer becomes the task result");
        } finally { b.engine.stop(); }
    }

    static void retries(Context ctx, final boolean persistentFailure) throws Exception {
        final Backend b = Backend.forTest(ctx, persistentFailure ? "persistent-error.db" : "intermittent-errors.db");
        final AtomicInteger calls = new AtomicInteger();
        b.core.settings.retryBaseMs = 5; b.core.settings.retryMaxMs = 10;
        try {
            JSONObject profile = b.providers.upsert(J.obj("kind", "scripted_mock", "name", "Retry test"), null);
            b.providers.overrides.put(profile.optString("id"), new Model.Adapter() {
                public List<String> listModels() { return Collections.singletonList("scripted-mock"); }
                public Model.Response complete(Model.Request req) throws Model.ProviderError {
                    int n = calls.incrementAndGet();
                    if (persistentFailure || n % 2 == 1) throw new Model.ProviderError("network", "Temporary interruption", true, 0);
                    Model.Response r = new Model.Response();
                    if (n < 12) r.toolCalls.add(new Model.ToolCall("retry-list-" + n, Tools.wireName("workspace.list"), J.obj()));
                    else r.text = "Done after six separated network errors";
                    return r;
                }
            });
            JSONObject bot = b.bots.create(J.obj("name", "Retry test", "provider_profile_id", profile.optString("id")), null);
            String cid = b.tasks.privateConversation(bot.optString("id")).optString("id");
            JSONObject sent = b.tasks.postUserMessage(cid, "Continue through transient failures", "retry", null, null);
            String tid = sent.optJSONArray("tasks").optJSONObject(0).optString("id");
            b.start(); b.engine.drain(20000);
            SelfTest.check(calls.get() == (persistentFailure ? 4 : 12)
                    && (persistentFailure ? "failed" : "completed").equals(b.tasks.requireTask(tid).optString("status")),
                    persistentFailure ? "consecutive persistent failures still stop after four attempts" : "successful progress resets retries between unrelated temporary failures");
        } finally { b.engine.stop(); }
    }
}
