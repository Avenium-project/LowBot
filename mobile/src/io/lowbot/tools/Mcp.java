package io.lowbot.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.lowbot.core.ApiError;
import io.lowbot.core.Backend;
import io.lowbot.core.Db;
import io.lowbot.core.J;
import io.lowbot.engine.Tools;
import io.lowbot.engine.Tools.Ctx;
import io.lowbot.engine.Tools.Spec;
import io.lowbot.engine.Tools.ToolError;

/**
 * MCP client over Streamable HTTP (JSON-RPC 2.0; JSON or SSE responses; Mcp-Session-Id).
 * stdio servers cannot run on a phone. Server-initiated requests (elicitation, sampling)
 * are declined because a single POST stream cannot wait for a human here.
 */
public final class Mcp {
    static final String PROTOCOL = "2025-06-18";
    final Backend b;
    final Db db;
    final Map<String, String> sessions = new ConcurrentHashMap<String, String>();
    final AtomicLong ids = new AtomicLong(1);

    public Mcp(Backend b) { this.b = b; this.db = b.core.db; }

    public static String safe(String n) { return n.replaceAll("[^a-zA-Z0-9_-]", "_"); }

    JSONObject pub(JSONObject r) {
        J.put(r, "auth_configured", J.str(r, "auth_secret_id", null) != null);
        r.remove("auth_secret_id");
        J.put(r, "tools", J.parseArr(r.optString("tools_cache_json")));
        r.remove("tools_cache_json");
        J.put(r, "enabled", J.bool(r, "enabled"));
        J.put(r, "connected", sessions.containsKey(r.optString("id")));
        J.put(r, "args", new JSONArray());
        J.put(r, "env_secret_names", new JSONArray());
        J.put(r, "bot_ids", new JSONArray());
        J.put(r, "scope", "global");
        J.put(r, "logs", new JSONArray());
        return r;
    }

    public List<JSONObject> rows() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject r : db.all("SELECT * FROM tool_connections ORDER BY name")) out.add(pub(r));
        return out;
    }

    public JSONObject save(final JSONObject d, String existing) {
        String transport = d.has("transport") ? d.optString("transport") : existing == null ? "" : db.scalar("SELECT transport FROM tool_connections WHERE id = ?", existing);
        if ("stdio".equals(transport)) throw new ApiError(422, "stdio MCP servers cannot run on a phone. Use an MCP server URL (Streamable HTTP).");
        if (!"http".equals(transport)) throw new ApiError(422, "transport must be http");
        if (d.has("name") && !d.optString("name").matches("[a-zA-Z0-9_-]{1,40}")) throw new ApiError(422, "Name: 1-40 chars of letters, digits, _ or -.");
        if (d.has("url")) {
            String u = d.optString("url");
            if (!u.startsWith("https://") && !u.startsWith("http://")) throw new ApiError(422, "Use an http(s) URL without credentials.");
            try { Net.vet(u, b.core.settings.allowPrivateNetwork); } catch (Net.Denied e) { throw new ApiError(422, e.getMessage()); }
        }
        final String cid = existing != null ? existing : J.id("mcp");
        db.tx(new Runnable() { public void run() {
            if (db.one("SELECT 1 FROM tool_connections WHERE id = ?", cid) == null)
                db.insert("tool_connections", J.obj("id", cid, "name", J.str(d, "name", cid), "transport", "http", "url", d.optString("url"),
                        "timeout_s", d.optDouble("timeout_s", 30), "created_at", J.nowIso(), "updated_at", J.nowIso()));
            else {
                for (String k : new String[]{"name", "url"}) if (d.has(k)) db.exec("UPDATE tool_connections SET " + k + " = ? WHERE id = ?", d.optString(k), cid);
                if (d.has("enabled")) db.exec("UPDATE tool_connections SET enabled = ? WHERE id = ?", J.bool(d, "enabled"), cid);
            }
            if (!J.str(d, "auth_token", "").isEmpty()) {
                String old = db.scalar("SELECT auth_secret_id FROM tool_connections WHERE id = ?", cid);
                db.exec("UPDATE tool_connections SET auth_secret_id = ? WHERE id = ?", b.core.secretPut("mcp:" + cid + ":auth", "mcp_auth", "MCP token", d.optString("auth_token"), old), cid);
            }
            b.core.audit("mcp.save", "user", null, null, null, null, null, null, J.obj("connection_id", cid));
        } });
        sessions.remove(cid);
        return pub(db.one("SELECT * FROM tool_connections WHERE id = ?", cid));
    }

    public void delete(final String cid) {
        final String sid = db.scalar("SELECT auth_secret_id FROM tool_connections WHERE id = ?", cid);
        db.tx(new Runnable() { public void run() { db.exec("DELETE FROM tool_connections WHERE id = ?", cid); b.core.secretDelete(sid); } });
        sessions.remove(cid);
    }

    JSONObject rpc(JSONObject conn, String method, JSONObject params, boolean notification) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/event-stream");
        h.put("MCP-Protocol-Version", PROTOCOL);
        String token = b.core.secretGet(J.str(conn, "auth_secret_id", null));
        if (token != null) h.put("Authorization", "Bearer " + token);
        String sess = sessions.get(conn.optString("id"));
        if (sess != null && !"-".equals(sess)) h.put("Mcp-Session-Id", sess);
        long id = ids.getAndIncrement();
        JSONObject msg = J.obj("jsonrpc", "2.0", "method", method, "params", params == null ? new JSONObject() : params);
        if (!notification) J.put(msg, "id", id);
        Net.Response r = Net.request("POST", conn.optString("url"), h, msg.toString().getBytes(StandardCharsets.UTF_8),
                b.core.settings.allowPrivateNetwork, 0, 4 * 1024 * 1024, (int) Math.max(5, conn.optDouble("timeout_s", 30)));
        if ("initialize".equals(method)) sessions.put(conn.optString("id"), r.headers.containsKey("mcp-session-id") ? r.headers.get("mcp-session-id") : "-");
        if (r.status == 404 && sess != null) { sessions.remove(conn.optString("id")); throw new ToolError("MCP session expired; retry."); }
        if (r.status == 401 || r.status == 403) throw new ToolError("MCP server rejected the credentials (HTTP " + r.status + ").");
        if (r.status >= 400) throw new ToolError("MCP server error HTTP " + r.status + ".");
        if (notification) return null;
        String text = r.text();
        if (r.contentType.contains("text/event-stream")) {
            for (String block : text.split("\r?\n\r?\n")) {
                StringBuilder data = new StringBuilder();
                for (String line : block.split("\r?\n")) if (line.startsWith("data:")) data.append(line.substring(5).trim());
                JSONObject m = J.parse(data.toString());
                if (m.optLong("id", -1) == id && (m.has("result") || m.has("error"))) return unwrap(m);
            }
            throw new ToolError("MCP stream ended without a response.");
        }
        return unwrap(J.parse(text));
    }

    static JSONObject unwrap(JSONObject m) throws ToolError {
        if (m.has("error")) throw new ToolError("MCP error: " + J.truncate(J.redact(m.optJSONObject("error") == null ? m.optString("error") : m.optJSONObject("error").optString("message")), 300));
        JSONObject res = m.optJSONObject("result");
        return res == null ? new JSONObject() : res;
    }

    void ensure(JSONObject conn) throws Exception {
        if (sessions.containsKey(conn.optString("id"))) return;
        rpc(conn, "initialize", J.obj("protocolVersion", PROTOCOL, "capabilities", new JSONObject(),
                "clientInfo", J.obj("name", "LowBot-Android", "version", "2")), false);
        rpc(conn, "notifications/initialized", null, true);
    }

    public JSONObject refresh(final String cid) {
        final JSONObject conn = db.one("SELECT * FROM tool_connections WHERE id = ?", cid);
        if (conn == null) throw new ApiError(404, "Unknown connection.");
        String status;
        final JSONArray tools = new JSONArray();
        try {
            sessions.remove(cid);
            ensure(conn);
            JSONObject res = rpc(conn, "tools/list", new JSONObject(), false);
            JSONArray t = res.optJSONArray("tools");
            if (t != null) for (int i = 0; i < t.length(); i++) {
                JSONObject x = t.optJSONObject(i);
                JSONObject ann = x.optJSONObject("annotations");
                tools.put(J.obj("name", x.optString("name"), "description", J.truncate(x.optString("description"), 900),
                        "input_schema", x.optJSONObject("inputSchema") == null ? J.obj("type", "object") : x.optJSONObject("inputSchema"),
                        "read_only", ann != null && ann.optBoolean("readOnlyHint")));
            }
            status = "ok: " + tools.length() + " tools";
        } catch (Exception e) {
            status = "error: " + e.getMessage();
        }
        final String st = status;
        db.tx(new Runnable() { public void run() {
            db.exec("UPDATE tool_connections SET tools_cache_json = ?, last_status = ?, updated_at = ? WHERE id = ?",
                    st.startsWith("ok") ? tools.toString() : db.scalar("SELECT tools_cache_json FROM tool_connections WHERE id = ?", cid), st, J.nowIso(), cid);
        } });
        return pub(db.one("SELECT * FROM tool_connections WHERE id = ?", cid));
    }

    public List<Spec> specs(JSONObject bot) {
        List<Spec> out = new ArrayList<Spec>();
        for (final JSONObject row : db.all("SELECT * FROM tool_connections WHERE enabled = 1")) {
            JSONArray tools = J.parseArr(row.optString("tools_cache_json"));
            for (int i = 0; i < tools.length(); i++) {
                final JSONObject t = tools.optJSONObject(i);
                JSONObject schema = t.optJSONObject("input_schema");
                if (schema == null || !"object".equals(schema.optString("type"))) schema = J.obj("type", "object", "properties", new JSONObject());
                boolean ro = t.optBoolean("read_only");
                final String connId = row.optString("id"), toolName = t.optString("name"), connName = row.optString("name");
                out.add(new Spec("mcp." + safe(connName) + "." + safe(toolName), "[MCP " + connName + "] " + t.optString("description", toolName),
                        schema, ro ? Tools.READ : Tools.EXTERNAL, ro ? "allow" : "ask", new Tools.Executor() {
                    public Object run(Ctx ctx, JSONObject args) throws Exception {
                        JSONObject conn = db.one("SELECT * FROM tool_connections WHERE id = ?", connId);
                        if (conn == null) throw new ToolError("MCP connection removed.");
                        ensure(conn);
                        JSONObject res = rpc(conn, "tools/call", J.obj("name", toolName, "arguments", args), false);
                        StringBuilder text = new StringBuilder();
                        JSONArray content = res.optJSONArray("content");
                        if (content != null) for (int k = 0; k < content.length(); k++) {
                            JSONObject c = content.optJSONObject(k);
                            if (c != null && "text".equals(c.optString("type"))) text.append(c.optString("text")).append('\n');
                            else if (c != null) text.append("[").append(c.optString("type")).append(" content]\n");
                        }
                        return J.obj("is_error", res.optBoolean("isError"), "text", J.truncate(text.toString(), 12000),
                                "structured", res.opt("structuredContent"));
                    }
                }).timeout((int) row.optDouble("timeout_s", 30) + 10).card(new Tools.Summarize() {
                    public JSONObject card(JSONObject a) {
                        return J.obj("summary", "MCP " + connName + ": " + toolName, "effect", "calls an external MCP tool", "target", J.truncate(J.redactObj(a).toString(), 200));
                    }
                }));
            }
        }
        return out;
    }
}
