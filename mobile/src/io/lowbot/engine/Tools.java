package io.lowbot.engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.lowbot.core.Backend;
import io.lowbot.core.J;

/**
 * Tool contracts and registry. The model only proposes calls; the engine validates,
 * applies policy, asks for approval and executes. Unknown tools are rejected.
 */
public final class Tools {
    public static final String READ = "read", WORKSPACE = "workspace", INTERNAL = "internal", EXTERNAL = "external";

    /** A tool failed in a way the model should see (not a crash). */
    public static final class ToolError extends Exception {
        public ToolError(String m) { super(m); }
    }

    /** Returned by a tool that parks the run (question, delegation, takeover). */
    public static final class Wait {
        public final String kind; // input | dependency
        public final JSONObject detail;
        public Wait(String kind, JSONObject detail) { this.kind = kind; this.detail = detail == null ? new JSONObject() : detail; }
    }

    public static final class Ctx {
        public final Backend b;
        public final JSONObject task, run, bot;
        public final String stepId, idempotencyKey;
        public final Engine engine;
        public Ctx(Backend b, Engine engine, JSONObject task, JSONObject run, JSONObject bot, String stepId, String key) {
            this.b = b; this.engine = engine; this.task = task; this.run = run; this.bot = bot; this.stepId = stepId; this.idempotencyKey = key;
        }
        public boolean cancelled() { return "cancel".equals(b.core.db.scalar("SELECT control FROM runs WHERE id = ?", run.optString("id"))); }
    }

    public interface Executor { Object run(Ctx ctx, JSONObject args) throws Exception; }

    /** Optional per-call escalation: return a reason when this call needs approval although policy allows the tool. */
    public interface Escalate { String check(Ctx ctx, JSONObject args); }

    public interface Summarize { JSONObject card(JSONObject args); }

    public static final class Spec {
        public final String name, description, effectKind, defaultEffect;
        public final JSONObject schema;
        public final Executor exec;
        public boolean hardAsk = false;
        public Summarize summarize;
        public Escalate escalate;
        public String requires; // capability flag e.g. "browser"
        public int timeoutS = 120;

        public Spec(String name, String description, JSONObject schema, String effectKind, String defaultEffect, Executor exec) {
            this.name = name; this.description = description; this.schema = schema; this.effectKind = effectKind;
            this.defaultEffect = defaultEffect; this.exec = exec;
        }

        public Spec hard() { hardAsk = true; return this; }
        public Spec card(Summarize s) { summarize = s; return this; }
        public Spec timeout(int s) { timeoutS = s; return this; }
        public Spec needs(String cap) { requires = cap; return this; }
        public Spec escalate(Escalate e) { escalate = e; return this; }

        public String validate(Object args) {
            if (!(args instanceof JSONObject)) return "Tool arguments must be a JSON object.";
            return check((JSONObject) args, schema, "");
        }

        public JSONObject card(JSONObject args) {
            if (summarize != null) return summarize.card(args);
            StringBuilder preview = new StringBuilder();
            Iterator<String> it = args.keys();
            int n = 0;
            while (it.hasNext() && n++ < 3) {
                String k = it.next();
                if (preview.length() > 0) preview.append("; ");
                preview.append(k).append(": ").append(J.truncate(String.valueOf(args.opt(k)), 80));
            }
            String head = description.split("\\.")[0];
            return J.obj("summary", preview.length() > 0 ? head + " (" + preview + ")" : head, "target", "", "effect", effectText(effectKind));
        }
    }

    public static String effectText(String kind) {
        if (READ.equals(kind)) return "read-only";
        if (WORKSPACE.equals(kind)) return "changes files in the workspace";
        if (INTERNAL.equals(kind)) return "changes data inside LowBot";
        if (EXTERNAL.equals(kind)) return "has an effect outside LowBot";
        return kind;
    }

    /** Minimal JSON-schema check: type, required, enum, additionalProperties=false, array items. */
    static String check(Object v, JSONObject s, String path) {
        if (s == null) return null;
        String where = path.isEmpty() ? "arguments" : path;
        JSONArray en = s.optJSONArray("enum");
        if (en != null) {
            for (int i = 0; i < en.length(); i++) if (en.opt(i).equals(v)) return null;
            return "Invalid arguments: " + where + " must be one of " + en;
        }
        String type = s.optString("type", "");
        if ("object".equals(type)) {
            if (!(v instanceof JSONObject)) return "Invalid arguments: " + where + " must be an object";
            JSONObject o = (JSONObject) v;
            JSONObject props = s.optJSONObject("properties");
            JSONArray req = s.optJSONArray("required");
            if (req != null) for (int i = 0; i < req.length(); i++)
                if (!o.has(req.optString(i)) || o.isNull(req.optString(i))) return "Invalid arguments: '" + req.optString(i) + "' is a required property";
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject ps = props == null ? null : props.optJSONObject(k);
                if (ps == null) {
                    if (s.has("additionalProperties") && !s.optBoolean("additionalProperties", true))
                        return "Invalid arguments: additional property '" + k + "' is not allowed";
                    continue;
                }
                if (o.isNull(k)) continue;
                String e = check(o.opt(k), ps, k);
                if (e != null) return e;
            }
            return null;
        }
        if ("string".equals(type) && !(v instanceof String)) return "Invalid arguments: " + where + " must be a string";
        if ("boolean".equals(type) && !(v instanceof Boolean)) return "Invalid arguments: " + where + " must be a boolean";
        if ("integer".equals(type)) {
            if (!(v instanceof Integer || v instanceof Long)) return "Invalid arguments: " + where + " must be an integer";
            long n = ((Number) v).longValue();
            if (s.has("minimum") && n < s.optLong("minimum")) return "Invalid arguments: " + where + " is below the minimum";
            if (s.has("maximum") && n > s.optLong("maximum")) return "Invalid arguments: " + where + " is above the maximum";
        }
        if ("number".equals(type) && !(v instanceof Number)) return "Invalid arguments: " + where + " must be a number";
        if ("array".equals(type)) {
            if (!(v instanceof JSONArray)) return "Invalid arguments: " + where + " must be an array";
            JSONObject items = s.optJSONObject("items");
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length() && items != null; i++) {
                String e = check(a.opt(i), items, where + "[" + i + "]");
                if (e != null) return e;
            }
        }
        return null;
    }

    public static JSONObject obj(JSONObject props, String... required) {
        return J.obj("type", "object", "properties", props, "required", new JSONArray(java.util.Arrays.asList(required)), "additionalProperties", false);
    }

    public static final JSONObject S = J.obj("type", "string");

    public static String wireName(String name) {
        String w = name.replace(".", "__").replaceAll("[^a-zA-Z0-9_-]", "_");
        return w.length() > 64 ? w.substring(0, 64) : w;
    }

    // ------------------------------------------------------------------ registry
    public interface DynamicProvider { List<Spec> specs(JSONObject bot); }

    private final Map<String, Spec> statics = new TreeMap<String, Spec>();
    private final List<DynamicProvider> dynamic = new ArrayList<DynamicProvider>();

    public void register(Spec s) {
        if (statics.containsKey(s.name)) throw new IllegalStateException("Duplicate tool: " + s.name);
        statics.put(s.name, s);
    }

    public void addProvider(DynamicProvider p) { dynamic.add(p); }

    public Map<String, Spec> all(JSONObject bot) {
        Map<String, Spec> out = new TreeMap<String, Spec>(statics);
        for (DynamicProvider p : dynamic) {
            try {
                for (Spec s : p.specs(bot)) out.put(s.name, s);
            } catch (Exception ignored) { }
        }
        return out;
    }

    public Map<String, Spec> forBot(JSONObject bot, List<String> skillTools, java.util.Set<String> caps) {
        Map<String, Spec> out = new TreeMap<String, Spec>();
        List<String> allowed = J.strings(bot.optJSONArray("tools"));
        for (Map.Entry<String, Spec> e : all(bot).entrySet()) {
            if (!J.anyGlob(allowed, e.getKey()) && !J.anyGlob(MIND_TOOLS, e.getKey())) continue;
            if (skillTools != null && !skillTools.isEmpty() && !J.anyGlob(skillTools, e.getKey())) continue;
            if (e.getValue().requires != null && caps != null && !caps.contains(e.getValue().requires)) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public Spec get(JSONObject bot, String name) { return all(bot).get(name); }

    /** Every bot can keep its own memory files (soul.md still needs approval). */
    static final List<String> MIND_TOOLS = java.util.Arrays.asList("memory.save", "memory.search", "memory.forget", "handoff.write", "soul.update", "project.use", "project.update_rules");
}
