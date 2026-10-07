package io.lowbot.engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.lowbot.core.J;

/**
 * Provider-neutral model contract plus adapters for documented public HTTP APIs:
 * Chat Completions (xAI, OpenCode Go, OpenRouter, local servers) and OpenAI
 * Responses. The scripted mock is explicitly labelled and never a model.
 */
public final class Model {
    private Model() {}

    public static final class ToolWire {
        public final String name, description;
        public final JSONObject parameters;
        public ToolWire(String name, String description, JSONObject parameters) {
            this.name = name; this.description = description; this.parameters = parameters;
        }
    }

    /**
     * messages: {"role":"user","content":str,"images":[url]} | {"role":"assistant","content":str,
     * "tool_calls":[{"id","name","arguments"}]} | {"role":"tool","call_id","name","content"}
     */
    public static final class Request {
        public String model, system;
        public List<JSONObject> messages = new ArrayList<JSONObject>();
        public List<ToolWire> tools = new ArrayList<ToolWire>();
        public int timeoutS = 120;
        /** Conversation (or task) this request belongs to; sent to providers that route by session (OpenCode Go). */
        public String session;
    }

    public static final class ToolCall {
        public final String id, name;
        public final Object arguments; // JSONObject when parseable, else raw string
        public ToolCall(String id, String name, Object arguments) { this.id = id; this.name = name; this.arguments = arguments; }
    }

    public static final class Response {
        public String text = "";
        public List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        public int inputTokens, outputTokens;
        public String model = "";
        public String finish = "";
    }

    /** Text from a Chat Completions "content", which may be a string or an array of parts. */
    static String contentText(Object c) {
        if (c instanceof String) return (String) c;
        if (c instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray a = (JSONArray) c;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && ("text".equals(p.optString("type")) || "output_text".equals(p.optString("type")))) sb.append(p.optString("text"));
                else if (a.opt(i) instanceof String) sb.append(a.optString(i));
            }
            return sb.toString();
        }
        return "";
    }

    /** kind: auth | rate_limit | server | bad_request | timeout | network | capability | config | budget */
    public static final class ProviderError extends Exception {
        public final String kind;
        public final boolean retryable;
        public final double retryAfter;
        public ProviderError(String kind, String message, boolean retryable, double retryAfter) {
            super(message); this.kind = kind; this.retryable = retryable; this.retryAfter = retryAfter;
        }
        public ProviderError(String kind, String message) { this(kind, message, false, 0); }
    }

    public interface Adapter {
        Response complete(Request req) throws ProviderError;
        List<String> listModels();
    }

    static ProviderError classify(int status, String retryAfter, String detail) {
        double ra = 0;
        try { if (retryAfter != null) ra = Double.parseDouble(retryAfter); } catch (NumberFormatException ignored) { }
        String d = detail == null || detail.isEmpty() ? "" : " " + detail;
        if (status == 401 || status == 403) return new ProviderError("auth", "Provider rejected the credentials (HTTP " + status + ")." + d);
        if (status == 429) return new ProviderError("rate_limit", "Provider rate limit (HTTP 429)." + d, true, ra);
        if (status == 408 || status == 409 || status >= 500) return new ProviderError("server", "Provider error (HTTP " + status + ")." + d, true, ra);
        if (status == 404) return new ProviderError("bad_request", "Endpoint or model not found (HTTP 404)." + d);
        return new ProviderError("bad_request", "Provider refused the request (HTTP " + status + ")." + d);
    }

    static Object parseArgs(Object raw) {
        if (raw instanceof JSONObject) return raw;
        if (raw == null || raw == JSONObject.NULL || "".equals(raw)) return new JSONObject();
        try { return new JSONObject(raw.toString()); } catch (Exception e) { return raw.toString(); }
    }

    // ------------------------------------------------------------------ HTTP
    public abstract static class Http implements Adapter {
        final String base, key;
        final JSONObject headers;
        /** Name of a request header carrying the session id ("x-opencode-session"), or null. Set only for providers that need it. */
        String sessionHeader;
        final String ownSession = "lowbot-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        public Http sessionHeader(String name) { this.sessionHeader = name; return this; }

        /** Header value for a conversation, or null when this provider takes no session header. The same conversation always
         *  gives the same value, so the provider can route its requests to one place (and reuse its cache). */
        public String sessionValue(String session) {
            if (sessionHeader == null) return null;
            return session == null || session.isEmpty() ? ownSession : session.replaceAll("[^A-Za-z0-9_.:-]", "_");
        }

        Http(String base, String key, JSONObject headers) throws ProviderError {
            if (base == null || base.isEmpty()) throw new ProviderError("config", "Provider base URL is not configured.");
            this.base = base.replaceAll("/+$", "");
            this.key = key == null ? "" : key;
            this.headers = headers == null ? new JSONObject() : headers;
        }

        HttpURLConnection open(String path, String method, int timeoutS) throws Exception { return open(path, method, timeoutS, null); }

        HttpURLConnection open(String path, String method, int timeoutS, String session) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(Math.max(5, timeoutS) * 1000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "LowBot-Android");
            if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
            java.util.Iterator<String> it = headers.keys();
            while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, headers.optString(k)); }
            if (sessionHeader != null) c.setRequestProperty(sessionHeader, sessionValue(session));
            return c;
        }

        JSONObject post(String path, JSONObject body, int timeoutS, String session) throws ProviderError {
            HttpURLConnection c = null;
            try {
                c = open(path, "POST", timeoutS, session);
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.close();
                int code = c.getResponseCode();
                String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code < 200 || code >= 300) throw classify(code, c.getHeaderField("Retry-After"), errorDetail(text));
                try { return new JSONObject(text); } catch (Exception e) {
                    throw new ProviderError("server", "Provider returned invalid JSON.", true, 0);
                }
            } catch (ProviderError e) {
                throw e;
            } catch (SocketTimeoutException e) {
                throw new ProviderError("timeout", "Model request timed out.", true, 0);
            } catch (Exception e) {
                throw new ProviderError("network", "Network error: " + e.getClass().getSimpleName(), true, 0);
            } finally {
                if (c != null) c.disconnect();
            }
        }

        @Override public List<String> listModels() {
            HttpURLConnection c = null;
            try {
                c = open("/models", "GET", 20);
                if (c.getResponseCode() != 200) return null;
                JSONArray data = new JSONObject(read(c.getInputStream())).optJSONArray("data");
                List<String> out = new ArrayList<String>();
                if (data != null) for (int i = 0; i < data.length(); i++) {
                    String id = data.optJSONObject(i) == null ? null : data.optJSONObject(i).optString("id", null);
                    if (id != null && !out.contains(id)) out.add(id);
                }
                java.util.Collections.sort(out);
                return out;
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > 8 * 1024 * 1024) break;
        }
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    static String errorDetail(String text) {
        String msg = text == null ? "" : text;
        try {
            JSONObject o = new JSONObject(text);
            Object err = o.opt("error");
            if (err instanceof JSONObject) msg = ((JSONObject) err).optString("message", "");
            else if (err instanceof String) msg = (String) err;
            else msg = o.optString("message", "");
        } catch (Exception ignored) { }
        return J.truncate(J.redact(msg), 300);
    }

    public static String errorDetailPublic(String text) { return errorDetail(text); }

    static String argsString(Object a) {
        return a instanceof JSONObject ? a.toString() : String.valueOf(a);
    }

    public static final class ChatCompletions extends Http {
        public ChatCompletions(String base, String key, JSONObject headers) throws ProviderError { super(base, key, headers); }

        JSONObject body(Request req) {
            JSONArray messages = new JSONArray();
            if (req.system != null && !req.system.isEmpty()) messages.put(J.obj("role", "system", "content", req.system));
            for (JSONObject m : req.messages) {
                String role = m.optString("role");
                if ("user".equals(role)) {
                    JSONArray images = m.optJSONArray("images");
                    if (images != null && images.length() > 0) {
                        JSONArray content = new JSONArray().put(J.obj("type", "text", "text", m.optString("content")));
                        for (int i = 0; i < images.length(); i++)
                            content.put(J.obj("type", "image_url", "image_url", J.obj("url", images.optString(i))));
                        messages.put(J.obj("role", "user", "content", content));
                    } else {
                        messages.put(J.obj("role", "user", "content", m.optString("content")));
                    }
                } else if ("assistant".equals(role)) {
                    String content = m.optString("content");
                    JSONObject item = J.obj("role", "assistant", "content", content.isEmpty() ? null : content);
                    JSONArray tcs = m.optJSONArray("tool_calls");
                    if (tcs != null && tcs.length() > 0) {
                        JSONArray out = new JSONArray();
                        for (int i = 0; i < tcs.length(); i++) {
                            JSONObject c = tcs.optJSONObject(i);
                            out.put(J.obj("id", c.optString("id"), "type", "function",
                                    "function", J.obj("name", c.optString("name"), "arguments", argsString(c.opt("arguments")))));
                        }
                        J.put(item, "tool_calls", out);
                    }
                    messages.put(item);
                } else if ("tool".equals(role)) {
                    messages.put(J.obj("role", "tool", "tool_call_id", m.optString("call_id"), "content", m.optString("content")));
                }
            }
            JSONObject body = J.obj("model", req.model, "messages", messages);
            if (!req.tools.isEmpty()) {
                JSONArray tools = new JSONArray();
                for (ToolWire t : req.tools)
                    tools.put(J.obj("type", "function", "function", J.obj("name", t.name, "description", t.description, "parameters", t.parameters)));
                J.put(body, "tools", tools);
            }
            return body;
        }

        @Override public Response complete(Request req) throws ProviderError {
            JSONObject data = post("/chat/completions", body(req), req.timeoutS, req.session);
            JSONArray choices = data.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new ProviderError("server", "Provider returned no choices.", true, 0);
            JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
            if (msg == null) msg = new JSONObject();
            Response r = new Response();
            r.text = contentText(msg.opt("content"));
            r.finish = choices.optJSONObject(0).optString("finish_reason", "");
            JSONArray tcs = msg.optJSONArray("tool_calls");
            if (tcs != null) for (int i = 0; i < tcs.length(); i++) {
                JSONObject c = tcs.optJSONObject(i);
                JSONObject fn = c.optJSONObject("function");
                if (fn == null) fn = new JSONObject();
                r.toolCalls.add(new ToolCall(c.optString("id", "call_" + i), fn.optString("name"), parseArgs(fn.opt("arguments"))));
            }
            JSONObject usage = data.optJSONObject("usage");
            if (usage != null) { r.inputTokens = usage.optInt("prompt_tokens"); r.outputTokens = usage.optInt("completion_tokens"); }
            r.model = data.optString("model");
            return r;
        }
    }

    public static final class Responses extends Http {
        public Responses(String base, String key, JSONObject headers) throws ProviderError { super(base, key, headers); }

        @Override public Response complete(Request req) throws ProviderError {
            JSONObject data = post("/responses", body(req), req.timeoutS, req.session);
            return parse(data);
        }

        /** Responses API request body (also used by the ChatGPT-account adapter). */
        public static JSONObject body(Request req) {
            JSONArray items = new JSONArray();
            for (JSONObject m : req.messages) {
                String role = m.optString("role");
                if ("user".equals(role)) {
                    JSONArray content = new JSONArray().put(J.obj("type", "input_text", "text", m.optString("content")));
                    JSONArray images = m.optJSONArray("images");
                    if (images != null) for (int i = 0; i < images.length(); i++) content.put(J.obj("type", "input_image", "image_url", images.optString(i)));
                    items.put(J.obj("role", "user", "content", content));
                } else if ("assistant".equals(role)) {
                    if (!m.optString("content").isEmpty())
                        items.put(J.obj("role", "assistant", "content", new JSONArray().put(J.obj("type", "output_text", "text", m.optString("content")))));
                    JSONArray tcs = m.optJSONArray("tool_calls");
                    if (tcs != null) for (int i = 0; i < tcs.length(); i++) {
                        JSONObject c = tcs.optJSONObject(i);
                        items.put(J.obj("type", "function_call", "call_id", c.optString("id"), "name", c.optString("name"), "arguments", argsString(c.opt("arguments"))));
                    }
                } else if ("tool".equals(role)) {
                    items.put(J.obj("type", "function_call_output", "call_id", m.optString("call_id"), "output", m.optString("content")));
                }
            }
            JSONObject body = J.obj("model", req.model, "input", items, "store", false);
            if (req.system != null && !req.system.isEmpty()) J.put(body, "instructions", req.system);
            if (!req.tools.isEmpty()) {
                JSONArray tools = new JSONArray();
                for (ToolWire t : req.tools) tools.put(J.obj("type", "function", "name", t.name, "description", t.description, "parameters", t.parameters));
                J.put(body, "tools", tools);
            }
            return body;
        }

        public static Response parse(JSONObject data) throws ProviderError {
            if ("failed".equals(data.optString("status"))) throw new ProviderError("server", "Responses API reported a failed response.", true, 0);
            Response r = new Response();
            r.finish = data.optString("status", "");
            JSONObject inc = data.optJSONObject("incomplete_details");
            if (inc != null) r.finish = inc.optString("reason", r.finish);
            StringBuilder text = new StringBuilder();
            JSONArray out = data.optJSONArray("output");
            if (out != null) for (int i = 0; i < out.length(); i++) {
                JSONObject item = out.optJSONObject(i);
                if ("message".equals(item.optString("type"))) {
                    JSONArray parts = item.optJSONArray("content");
                    if (parts != null) for (int k = 0; k < parts.length(); k++) {
                        JSONObject p = parts.optJSONObject(k);
                        if ("output_text".equals(p.optString("type"))) text.append(p.optString("text"));
                        else if ("refusal".equals(p.optString("type"))) text.append(p.optString("refusal"));
                    }
                } else if ("function_call".equals(item.optString("type"))) {
                    r.toolCalls.add(new ToolCall(item.optString("call_id", item.optString("id")), item.optString("name"), parseArgs(item.opt("arguments"))));
                }
            }
            r.text = text.toString();
            JSONObject usage = data.optJSONObject("usage");
            if (usage != null) { r.inputTokens = usage.optInt("input_tokens"); r.outputTokens = usage.optInt("output_tokens"); }
            r.model = data.optString("model");
            return r;
        }
    }

    // ------------------------------------------------------------ SCRIPTED MOCK
    /**
     * Deterministic offline mock (labelled is_mock everywhere). Rules, first match wins:
     * {"when": regex, "reply": text} | {"when":..., "call": {"name","arguments"}} | {"calls": [...]}
     * "on": "user" (default) | "tool" | "any"; "after_tool": name. {{last}} = latest text.
     */
    public static final class Scripted implements Adapter {
        final JSONArray script;
        int n = 0;

        public Scripted(JSONArray script) { this.script = script == null ? new JSONArray() : script; }

        @Override public synchronized Response complete(Request req) {
            n++;
            JSONObject last = req.messages.isEmpty() ? J.obj("role", "user", "content", "") : req.messages.get(req.messages.size() - 1);
            String lastText = last.optString("content");
            String role = last.optString("role");
            String lastTool = "tool".equals(role) ? last.optString("name") : null;
            Response r = new Response();
            r.inputTokens = 10;
            r.outputTokens = 5;
            r.model = "scripted-mock";
            for (int i = 0; i < script.length(); i++) {
                JSONObject rule = script.optJSONObject(i);
                if (rule == null) continue;
                String on = rule.optString("on", rule.has("after_tool") ? "tool" : "user");
                if (!"any".equals(on) && !on.equals(role)) continue;
                if (rule.has("after_tool")) {
                    String at = rule.optString("after_tool");
                    if (lastTool == null || !(lastTool.equals(at) || lastTool.equals(Tools.wireName(at)))) continue;
                }
                if (rule.has("when") && !Pattern.compile(rule.optString("when"), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(lastText).find()) continue;
                JSONArray calls = rule.optJSONArray("calls");
                if (calls == null && rule.optJSONObject("call") != null) calls = new JSONArray().put(rule.optJSONObject("call"));
                if (calls != null && calls.length() > 0) {
                    r.text = rule.optString("text", "");
                    for (int j = 0; j < calls.length(); j++) {
                        JSONObject c = calls.optJSONObject(j);
                        JSONObject args = c.optJSONObject("arguments");
                        r.toolCalls.add(new ToolCall("call_" + n + "_" + j, Tools.wireName(c.optString("name")),
                                J.parse(args == null ? "{}" : args.toString().replace("{{last}}", JSONObject.quote(lastText).replaceAll("^\"|\"$", "")))));
                    }
                    return r;
                }
                r.text = rule.optString("reply", "").replace("{{last}}", lastText);
                return r;
            }
            r.text = "[mock] " + lastText;
            return r;
        }

        @Override public List<String> listModels() {
            List<String> l = new ArrayList<String>();
            l.add("scripted-mock");
            return l;
        }
    }
}
