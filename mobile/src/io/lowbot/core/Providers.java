package io.lowbot.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import io.lowbot.engine.Model;

/** Provider profiles, adapter resolution, capability tests and usage/budget (server/app/v2/providers/service.py). */
public final class Providers {
    /** Only documented public base URLs. Model ids are never invented. */
    public static final Map<String, JSONObject> PRESETS = new LinkedHashMap<String, JSONObject>();
    static {
        PRESETS.put("xai", J.obj("label", "xAI API (Grok models)", "adapter", "chat", "base_url", "https://api.x.ai/v1", "key_required", true));
        PRESETS.put("opencode_go", J.obj("label", "OpenCode Go (subscription, API key)", "adapter", "chat", "base_url", "https://opencode.ai/zen/go/v1", "key_required", true));
        PRESETS.put("openai_responses", J.obj("label", "OpenAI Responses API", "adapter", "responses", "base_url", "https://api.openai.com/v1", "key_required", true));
        PRESETS.put("openrouter", J.obj("label", "OpenRouter", "adapter", "chat", "base_url", "https://openrouter.ai/api/v1", "key_required", true));
        PRESETS.put("chat_completions", J.obj("label", "Chat Completions-compatible", "adapter", "chat", "base_url", "", "key_required", false));
        PRESETS.put("local", J.obj("label", "Own OpenAI-compatible endpoint (HTTPS, e.g. via Tailscale)", "adapter", "chat", "base_url", "", "key_required", false));
        PRESETS.put("scripted_mock", J.obj("label", "Scripted mock (offline, NOT a model)", "adapter", "mock", "base_url", "", "key_required", false));
    }

    /** Tests may inject adapters per profile id. */
    public final Map<String, Model.Adapter> overrides = new java.util.concurrent.ConcurrentHashMap<String, Model.Adapter>();

    private final Backend b;
    private final Db db;

    Providers(Backend b) { this.b = b; this.db = b.core.db; }

    public JSONArray presets() {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, JSONObject> e : PRESETS.entrySet()) {
            JSONObject p = J.parse(e.getValue().toString());
            p.remove("adapter");
            J.put(p, "kind", e.getKey());
            out.put(p);
        }
        return out;
    }

    JSONObject row(JSONObject r) {
        for (String k : new String[]{"models_json", "capabilities_json", "prices_json", "last_test_json"}) {
            String v = r.optString(k);
            J.put(r, k.replace("_json", ""), k.equals("models_json") ? (Object) J.parseArr(v) : J.parse(v));
            r.remove(k);
        }
        J.put(r, "api_key_configured", J.str(r, "api_key_secret_id", null) != null);
        r.remove("api_key_secret_id");
        J.put(r, "allow_fallback", false);
        J.put(r, "is_mock", "scripted_mock".equals(r.optString("kind")));
        return r;
    }

    public List<JSONObject> list() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject r : db.all("SELECT * FROM provider_profiles ORDER BY created_at")) out.add(row(r));
        return out;
    }

    public JSONObject get(String id) {
        JSONObject r = id == null ? null : db.one("SELECT * FROM provider_profiles WHERE id = ?", id);
        return r == null ? null : row(r);
    }

    public String defaultId() { return b.core.kvGet("default_provider_profile_id"); }

    public JSONObject upsert(final JSONObject d, String existing) {
        JSONObject cur = existing == null ? null : get(existing);
        if (existing != null && cur == null) throw new ApiError(404, "Unknown provider profile.");
        final String kind = d.has("kind") ? d.optString("kind") : cur == null ? "" : cur.optString("kind");
        final JSONObject preset = PRESETS.get(kind);
        if (preset == null) throw new ApiError(422, "Unknown provider kind. Use one of: " + PRESETS.keySet());
        if (d.has("base_url")) validateUrl(d.optString("base_url").trim(), kind);
        if ("scripted_mock".equals(kind) && d.has("script") && d.optJSONArray("script") == null)
            throw new ApiError(422, "Mock script must be a JSON list of rules.");
        final String pid = existing != null ? existing : J.id("prov");
        db.tx(new Runnable() {
            @Override public void run() {
                String now = J.nowIso();
                if (db.one("SELECT 1 FROM provider_profiles WHERE id = ?", pid) == null) {
                    db.insert("provider_profiles", J.obj("id", pid, "name", J.str(d, "name", "").trim().isEmpty() ? preset.optString("label") : d.optString("name").trim(),
                            "kind", kind, "base_url", J.str(d, "base_url", "").trim().isEmpty() ? preset.optString("base_url") : d.optString("base_url").trim(),
                            "default_model", J.str(d, "default_model", "").trim(), "created_at", now, "updated_at", now));
                } else {
                    for (String k : new String[]{"name", "base_url", "default_model"})
                        if (d.has(k)) db.exec("UPDATE provider_profiles SET " + k + " = ? WHERE id = ?", d.optString(k).trim(), pid);
                }
                if (d.has("models")) db.exec("UPDATE provider_profiles SET models_json = ? WHERE id = ?", String.valueOf(d.optJSONArray("models") == null ? "[]" : d.optJSONArray("models")), pid);
                if (d.has("prices")) db.exec("UPDATE provider_profiles SET prices_json = ? WHERE id = ?", String.valueOf(d.optJSONObject("prices") == null ? "{}" : d.optJSONObject("prices")), pid);
                if (d.has("capabilities")) db.exec("UPDATE provider_profiles SET capabilities_json = ? WHERE id = ?", String.valueOf(d.optJSONObject("capabilities")), pid);
                if ("scripted_mock".equals(kind) && d.optJSONArray("script") != null)
                    db.exec("UPDATE provider_profiles SET capabilities_json = ? WHERE id = ?",
                            J.obj("script", d.optJSONArray("script"), "tools", true, "vision", false, "streaming", false).toString(), pid);
                db.exec("UPDATE provider_profiles SET updated_at = ? WHERE id = ?", now, pid);
                if (!J.str(d, "api_key", "").isEmpty()) {
                    String old = db.scalar("SELECT api_key_secret_id FROM provider_profiles WHERE id = ?", pid);
                    String sid = b.core.secretPut("provider:" + pid, "api_key", "API key", d.optString("api_key"), old);
                    db.exec("UPDATE provider_profiles SET api_key_secret_id = ? WHERE id = ?", sid, pid);
                }
                if (J.bool(d, "clear_api_key")) {
                    b.core.secretDelete(db.scalar("SELECT api_key_secret_id FROM provider_profiles WHERE id = ?", pid));
                    db.exec("UPDATE provider_profiles SET api_key_secret_id = NULL WHERE id = ?", pid);
                }
                if (b.core.kvGet("default_provider_profile_id") == null)
                    db.exec("INSERT OR REPLACE INTO kv(key, value) VALUES ('default_provider_profile_id', ?)", pid);
                b.core.audit("provider.save", "user", null, null, null, null, null, null, J.obj("profile_id", pid, "kind", kind));
            }
        });
        return get(pid);
    }

    static void validateUrl(String url, String kind) {
        if (url.isEmpty()) return;
        if (!url.startsWith("https://")) throw new ApiError(422, "Base URL must start with https:// (Android blocks unencrypted connections).");
    }

    public void delete(final String id) {
        final String sid = db.scalar("SELECT api_key_secret_id FROM provider_profiles WHERE id = ?", id);
        db.tx(new Runnable() {
            @Override public void run() {
                db.exec("DELETE FROM provider_profiles WHERE id = ?", id);
                db.exec("UPDATE bots SET provider_profile_id = NULL WHERE provider_profile_id = ?", id);
                if (id.equals(b.core.kvGet("default_provider_profile_id"))) db.exec("DELETE FROM kv WHERE key = 'default_provider_profile_id'");
                b.core.secretDelete(sid);
            }
        });
    }

    public static final class Resolved {
        public JSONObject profile;
        public Model.Adapter adapter;
        public String model;
    }

    public Resolved resolve(JSONObject bot) throws Model.ProviderError {
        String pid = J.str(bot, "provider_profile_id", null);
        if (pid == null) pid = defaultId();
        if (pid == null) throw new Model.ProviderError("config", "No model provider configured. Add one in Settings → Models.");
        JSONObject p = get(pid);
        if (p == null) throw new Model.ProviderError("config", "The bot's provider profile no longer exists.");
        String model = J.str(bot, "model", null);
        if (model == null || model.isEmpty()) model = p.optString("default_model");
        if ("scripted_mock".equals(p.optString("kind")) && model.isEmpty()) model = "scripted-mock";
        if (model.isEmpty()) throw new Model.ProviderError("config", "No model id set for this bot or provider profile.");
        Resolved r = new Resolved();
        r.profile = p;
        r.model = model;
        r.adapter = adapter(p);
        return r;
    }

    public Model.Adapter adapter(JSONObject p) throws Model.ProviderError {
        Model.Adapter o = overrides.get(p.optString("id"));
        if (o != null) return o;
        JSONObject preset = PRESETS.get(p.optString("kind"));
        if (preset == null) throw new Model.ProviderError("config", "This provider kind is not available on the phone.");
        if ("mock".equals(preset.optString("adapter"))) {
            JSONObject caps = p.optJSONObject("capabilities");
            return new Model.Scripted(caps == null ? null : caps.optJSONArray("script"));
        }
        String key = b.core.secretGet(db.scalar("SELECT api_key_secret_id FROM provider_profiles WHERE id = ?", p.optString("id")));
        if (key == null && "opencode_go".equals(p.optString("kind"))) key = b.core.secretGet(b.core.kvGet("opencode_go_secret_id"));
        if (preset.optBoolean("key_required") && (key == null || key.isEmpty()))
            throw new Model.ProviderError("config", "API key missing for provider '" + p.optString("name") + "'.");
        JSONObject headers = "openrouter".equals(p.optString("kind")) ? J.obj("X-Title", "LowBot") : null;
        return "responses".equals(preset.optString("adapter"))
                ? new Model.Responses(p.optString("base_url"), key, headers)
                : new Model.ChatCompletions(p.optString("base_url"), key, headers);
    }

    /** Probe real capabilities; nothing is assumed from the preset. Runs off the UI thread. */
    public JSONObject test(final String id, String model) {
        JSONObject p = get(id);
        if (p == null) throw new ApiError(404, "Unknown provider profile.");
        JSONObject result = J.obj("at", J.nowIso(), "is_mock", p.optBoolean("is_mock"), "checks", new JSONObject());
        JSONObject checks = result.optJSONObject("checks");
        JSONObject caps = new JSONObject();
        Model.Adapter a;
        try {
            a = adapter(p);
        } catch (Model.ProviderError e) {
            J.put(checks, "config", J.obj("ok", false, "error", e.getMessage()));
            return saveTest(id, result, caps);
        }
        List<String> models = a.listModels();
        J.put(result, "models", models == null ? null : J.arr(models));
        if (model == null || model.isEmpty()) model = p.optString("default_model");
        if ((model == null || model.isEmpty()) && models != null && !models.isEmpty()) model = models.get(0);
        if (model == null || model.isEmpty()) {
            J.put(checks, "model", J.obj("ok", false, "error", "Enter a model id (the provider did not list models)."));
            return saveTest(id, result, caps);
        }
        J.put(result, "model", model);
        try {
            Model.Request r = new Model.Request();
            r.model = model; r.system = ""; r.timeoutS = 60;
            r.messages.add(J.obj("role", "user", "content", "Reply with the single word OK."));
            Model.Response resp = a.complete(r);
            J.put(checks, "text", J.obj("ok", true, "sample", J.truncate(resp.text, 60), "usage", new JSONArray().put(resp.inputTokens).put(resp.outputTokens)));
            J.put(caps, "text", true);
            if (p.optString("default_model").isEmpty()) {
                final String chosen = model;
                db.tx(new Runnable() { public void run() { db.exec("UPDATE provider_profiles SET default_model = ? WHERE id = ?", chosen, id); } });
            }
        } catch (Model.ProviderError e) {
            J.put(checks, "text", J.obj("ok", false, "kind", e.kind, "error", e.getMessage()));
            J.put(caps, "text", false);
            return saveTest(id, result, caps);
        }
        try {
            Model.Request r = new Model.Request();
            r.model = model; r.system = "You must call the provided tool."; r.timeoutS = 60;
            r.messages.add(J.obj("role", "user", "content", "Call the ping tool with value 'abc'."));
            r.tools.add(new Model.ToolWire("ping", "Echo a value.", J.obj("type", "object",
                    "properties", J.obj("value", J.obj("type", "string")), "required", new JSONArray().put("value"))));
            Model.Response resp = a.complete(r);
            boolean ok = false;
            for (Model.ToolCall c : resp.toolCalls) if ("ping".equals(c.name)) ok = true;
            J.put(checks, "tools", J.obj("ok", ok, "detail", ok ? "tool call returned" : "model answered without calling the tool"));
            J.put(caps, "tools", ok);
        } catch (Model.ProviderError e) {
            J.put(checks, "tools", J.obj("ok", false, "kind", e.kind, "error", e.getMessage()));
            J.put(caps, "tools", false);
        }
        J.put(checks, "streaming", J.obj("ok", false, "detail", "not used by the on-phone engine"));
        return saveTest(id, result, caps);
    }

    JSONObject saveTest(final String id, final JSONObject result, JSONObject caps) {
        JSONObject p = get(id);
        final JSONObject merged = J.parse(p.optJSONObject("capabilities").toString());
        java.util.Iterator<String> it = caps.keys();
        while (it.hasNext()) { String k = it.next(); J.put(merged, k, caps.opt(k)); }
        J.put(merged, "tested_at", result.optString("at"));
        db.tx(new Runnable() {
            @Override public void run() {
                db.exec("UPDATE provider_profiles SET last_test_json = ?, capabilities_json = ?, updated_at = ? WHERE id = ?",
                        result.toString(), merged.toString(), J.nowIso(), id);
            }
        });
        J.put(result, "capabilities", merged);
        return result;
    }

    // ------------------------------------------------------------- usage/budget
    static Double price(JSONObject profile, String model, long tin, long tout) {
        JSONObject prices = profile.optJSONObject("prices");
        JSONObject p = prices == null ? null : prices.optJSONObject(model);
        if (p == null) return null; // unpriced: never invent a price
        return tin / 1e6 * p.optDouble("input_per_mtok", 0) + tout / 1e6 * p.optDouble("output_per_mtok", 0);
    }

    double spentToday(String botId) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(J.now());
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        String v = db.scalar("SELECT COALESCE(SUM(CASE WHEN status = 'confirmed' THEN COALESCE(cost_confirmed, cost_estimated) "
                + "WHEN status = 'reserved' THEN cost_estimated ELSE 0 END), 0) FROM usage_entries WHERE bot_id = ? AND created_at >= ?",
                botId, J.iso(c.getTimeInMillis()));
        return v == null ? 0 : Double.parseDouble(v);
    }

    /** Budget is checked and reserved atomically before the model call. */
    public String reserve(final JSONObject bot, final String runId, final JSONObject profile, final String model, final long estIn) throws Model.ProviderError {
        final JSONObject budget = bot.optJSONObject("budget") == null ? new JSONObject() : bot.optJSONObject("budget");
        Double c = price(profile, model, estIn, 1024);
        final double cost = c == null ? 0 : c;
        final String uid = J.id("use");
        final String[] err = new String[1];
        db.tx(new Runnable() {
            @Override public void run() {
                if (budget.has("max_cost_per_day") && !budget.isNull("max_cost_per_day")
                        && spentToday(bot.optString("id")) + cost > budget.optDouble("max_cost_per_day")) {
                    err[0] = "Daily cost budget of " + budget.optDouble("max_cost_per_day") + " reached for " + bot.optString("name") + ".";
                    return;
                }
                if (budget.has("max_tokens_per_run") && !budget.isNull("max_tokens_per_run")) {
                    long used = db.count("SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM usage_entries WHERE run_id = ? AND status = 'confirmed'", runId);
                    if (used + estIn > budget.optLong("max_tokens_per_run")) {
                        err[0] = "Token budget per run (" + budget.optLong("max_tokens_per_run") + ") reached.";
                        return;
                    }
                }
                db.insert("usage_entries", J.obj("id", uid, "run_id", runId, "bot_id", bot.optString("id"), "kind", "tokens", "status", "reserved",
                        "provider", profile.optString("kind"), "model", model, "cost_estimated", cost, "created_at", J.nowIso()));
            }
        });
        if (err[0] != null) throw new Model.ProviderError("budget", err[0]);
        return uid;
    }

    public void confirm(final String uid, JSONObject profile, String model, final int tin, final int tout) {
        final Double cost = price(profile, model, tin, tout);
        db.tx(new Runnable() {
            @Override public void run() {
                db.exec("UPDATE usage_entries SET status = 'confirmed', input_tokens = ?, output_tokens = ?, cost_estimated = ?, cost_confirmed = ? WHERE id = ?",
                        tin, tout, cost == null ? 0.0 : cost, cost, uid);
            }
        });
    }

    public void release(final String uid) {
        db.tx(new Runnable() {
            @Override public void run() { db.exec("UPDATE usage_entries SET status = 'released', cost_estimated = 0 WHERE id = ?", uid); }
        });
    }

    public JSONObject usageSummary(String botId) {
        List<JSONObject> rows = botId == null
                ? db.all("SELECT bot_id, kind, status, SUM(input_tokens) tin, SUM(output_tokens) tout, SUM(cost_estimated) est, SUM(cost_confirmed) conf, COUNT(*) n FROM usage_entries GROUP BY bot_id, kind, status")
                : db.all("SELECT bot_id, kind, status, SUM(input_tokens) tin, SUM(output_tokens) tout, SUM(cost_estimated) est, SUM(cost_confirmed) conf, COUNT(*) n FROM usage_entries WHERE bot_id = ? GROUP BY bot_id, kind, status", botId);
        return J.obj("rows", Db.toArray(rows), "note", "Costs are only computed for models with user-entered prices; 'est' is a pre-call reservation, 'conf' uses reported token usage.");
    }
}
