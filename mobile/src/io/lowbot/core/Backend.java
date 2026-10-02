package io.lowbot.core;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

import io.lowbot.engine.Engine;
import io.lowbot.engine.Tools;
import io.lowbot.tools.Builtin;

/**
 * The LowBot backend hosted on the phone: one instance per process, created by the
 * Application. No external server — data, engine, scheduler and tools live here.
 */
public final class Backend {
    /** Implemented by the Android layer (alarms, foreground service, browser). */
    public interface Platform {
        void scheduleAlarm(long atMillis);
        void workStateChanged(int activeRuns, int queued);
        boolean browserAvailable();
    }

    private static Backend instance;

    public final Core core;
    public final Bots bots;
    public final Tasks tasks;
    public final Approvals approvals;
    public final Providers providers;
    public final Memory memory;
    public final Skills skills;
    public final Artifacts artifacts;
    public final Routines routines;
    public final ChatGpt chatgpt;
    public final Mind mind;
    public final Widgets widgets;
    public final Tools tools = new Tools();
    public final Engine engine;
    public volatile Platform platform;
    public final Set<String> extraCapabilities = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    public static synchronized Backend get(Context ctx) {
        if (instance == null) {
            instance = new Backend(ctx.getApplicationContext(), "lowbot.db"); // caller registers platform tools, then start()
        }
        return instance;
    }

    /** Separate instance (own database) for self-tests. */
    public static Backend forTest(Context ctx, String dbName) {
        ctx.getApplicationContext().deleteDatabase(dbName);
        return new Backend(ctx.getApplicationContext(), dbName);
    }

    /** Reopen an existing database (self-test of crash recovery). */
    public static Backend reopen(Context ctx, String dbName) {
        return new Backend(ctx.getApplicationContext(), dbName);
    }

    Backend(Context ctx, String dbName) {
        core = new Core(ctx, dbName);
        bots = new Bots(this);
        tasks = new Tasks(this);
        approvals = new Approvals(this);
        providers = new Providers(this);
        memory = new Memory(this);
        skills = new Skills(this);
        artifacts = new Artifacts(this);
        routines = new Routines(this);
        chatgpt = new ChatGpt(this);
        mind = new Mind(this);
        widgets = new Widgets(this);
        Builtin.register(tools);
        engine = new Engine(this);
        String tz = core.kvGet("timezone");
        if (tz != null) core.settings.timezone = tz;
        core.settings.allowPrivateNetwork = core.kvBool("allow_private_network", false);
    }

    private boolean started;

    public synchronized void start() {
        if (started) return;
        started = true;
        bots.upgradeTeamTools();
        bots.upgradeSprites();
        bots.upgradeLinuxTools();
        engine.start();
        try {
            routines.tick();
        } catch (RuntimeException e) {
            android.util.Log.e("LowBot", "routine tick", e);
        }
    }

    public void wake() {
        engine.wake();
        Platform p = platform;
        if (p != null) p.workStateChanged(engine.activeCount(), (int) queuedCount());
    }

    public long queuedCount() {
        return core.db.count("SELECT COUNT(*) FROM runs WHERE status IN ('queued','running','retry_scheduled')");
    }

    public void onRunFinished() {
        Platform p = platform;
        if (p != null) p.workStateChanged(engine.activeCount(), (int) queuedCount());
    }

    public void scheduleAlarm() {
        Platform p = platform;
        if (p == null) return;
        long next = routines.nextDueMillis();
        String retry = core.db.scalar("SELECT MIN(not_before) FROM runs WHERE status = 'retry_scheduled'");
        if (retry != null) next = next == 0 ? J.parseIso(retry) : Math.min(next, J.parseIso(retry));
        p.scheduleAlarm(next);
    }

    public Set<String> capabilities() {
        Set<String> caps = new HashSet<String>(extraCapabilities);
        Platform p = platform;
        if (p != null && p.browserAvailable()) caps.add("browser");
        caps.add("terminal");
        if (widgets.botsMayCreate()) caps.add("widgets");
        return caps;
    }

    public String imageDataUrl(String artifactId) {
        JSONObject meta = artifacts.get(artifactId);
        File f = artifacts.path(artifactId);
        if (meta == null || f == null || !meta.optString("mime").startsWith("image/") || f.length() > 8000000) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            in.close();
            return "data:" + meta.optString("mime") + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** Answer a secret.request: the value goes to the encrypted vault, never into messages, events or the model. */
    /** Stores the value(s) the user typed into a secret card; the run continues with placeholders only. */
    public void answerSecret(final String taskId, JSONObject body) {
        final JSONObject run = tasks.runFor(taskId);
        if (run == null || !"waiting_input".equals(run.optString("status"))) throw new ApiError(409, "This task is not waiting for a secret.");
        final JSONObject waiting = J.parse(run.optString("waiting_json"));
        JSONObject req = waiting.optJSONObject("secret_request");
        if (req == null) throw new ApiError(409, "This task is waiting for an answer, not a secret.");
        JSONArray fields = req.optJSONArray("fields");
        if (fields == null || fields.length() == 0) fields = new JSONArray().put(J.obj("name", req.optString("name"), "description", req.optString("description")));
        JSONObject values = body.optJSONObject("values");
        if (values == null) values = J.obj(fields.optJSONObject(0).optString("name"), body.optString("value"));
        final JSONArray placeholders = new JSONArray();
        final List<String> names = new ArrayList<String>();
        for (int i = 0; i < fields.length(); i++) {
            String n = fields.optJSONObject(i).optString("name");
            String v = values.optString(n, "");
            if (v.isEmpty()) throw new ApiError(422, "Fill in " + n + ".");
            names.add(n);
            placeholders.put("{{secret:" + n + "}}");
        }
        for (int i = 0; i < fields.length(); i++) {
            String n = fields.optJSONObject(i).optString("name");
            core.secretPut("user:" + n, "user_secret", fields.optJSONObject(i).optString("description", req.optString("description")), values.optString(n), core.secretIdByName("user:" + n));
        }
        final String name = String.join(", ", names);
        core.db.tx(new Runnable() {
            @Override public void run() {
                core.db.exec("UPDATE run_steps SET status = 'completed', output_json = ?, updated_at = ? WHERE id = ? AND status = 'waiting'",
                        J.obj("stored", true, "placeholders", placeholders, "placeholder", placeholders.optString(0)).toString(), J.nowIso(), waiting.optString("step_id"));
                if (core.db.change("UPDATE runs SET status = 'queued', waiting_json = '{}', updated_at = ? WHERE id = ? AND status = 'waiting_input'",
                        J.nowIso(), run.optString("id")) != 1) throw new ApiError(409, "The task state changed; refresh and retry.");
                JSONObject t = tasks.requireTask(taskId);
                if (J.str(t, "conversation_id", null) != null)
                    tasks.insertMessage(t.optString("conversation_id"), "user", Core.OWNER, "🔒 Saved in the vault: " + name + " (hidden)", null, taskId, null, null, null,
                            J.obj("secret_provided", name));
                core.audit("secret.provide", "user", Core.OWNER, taskId, run.optString("id"), null, null, null, J.obj("name", name));
                core.emit("run.input_received", null, taskId, run.optString("id"), run.optString("bot_id"), J.obj("secret", name));
            }
        });
        wake();
    }
}
