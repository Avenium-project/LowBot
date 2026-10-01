package io.lowbot.core;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import io.lowbot.engine.Model;

/**
 * Sign in with a ChatGPT (Plus/Pro) account and use its Codex quota from the phone.
 *
 * Approach (same as OpenCode and github.com/7shi/codex-oauth): OAuth 2 + PKCE against
 * auth.openai.com with the Codex CLI's public client id; the browser redirects to
 * http://localhost:1455/auth/callback, served here by a one-shot loopback listener; the
 * tokens are stored encrypted in the Android Keystore vault; requests go to the Responses
 * API of the Codex backend (chatgpt.com/backend-api/wham, store=false, stateless).
 *
 * UNOFFICIAL: this backend is not documented for third-party apps and OpenAI may change or
 * restrict it at any time. Opt-in only; the user signs in on OpenAI's own page (LowBot never
 * sees the password) and usage counts against their ChatGPT plan limits.
 */
public final class ChatGpt {
    static final String AUTH_URL = "https://auth.openai.com/oauth/authorize";
    static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    public static final String BASE_URL = "https://chatgpt.com/backend-api/wham";
    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    static final String REDIRECT = "http://localhost:1455/auth/callback";
    static final String SCOPE = "openid profile email offline_access";
    static final String VAULT_NAME = "chatgpt_oauth";
    static final long SAFETY_MS = 60000;

    private final Backend b;
    private volatile JSONObject login = null; // {status, url, error}
    private volatile ServerSocket listener;

    ChatGpt(Backend b) { this.b = b; }

    // ------------------------------------------------------------------ status
    JSONObject tokens() {
        String id = b.core.secretIdByName(VAULT_NAME);
        return id == null ? null : J.parse(b.core.secretGet(id));
    }

    void saveTokens(JSONObject t) {
        b.core.secretPut(VAULT_NAME, "oauth", "ChatGPT sign-in", t.toString(), b.core.secretIdByName(VAULT_NAME));
    }

    public JSONObject status() {
        JSONObject t = tokens();
        return J.obj("logged_in", t != null && t.has("refresh"), "account", t == null ? null : t.opt("email"),
                "plan", t == null ? null : t.opt("plan"), "login", login, "unofficial", true,
                "accepted_risk", b.core.kvBool("chatgpt_oauth_accepted", false));
    }

    public void logout() {
        String id = b.core.secretIdByName(VAULT_NAME);
        b.core.secretDelete(id);
        login = null;
        stopListener();
        b.core.db.tx(new Runnable() { public void run() { b.core.audit("chatgpt.logout", "user", null, null, null, null, null, null, null); } });
    }

    // ------------------------------------------------------------------- login
    static String b64url(byte[] d) { return Base64.encodeToString(d, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /** Starts the loopback listener and returns the URL to open in the system browser. */
    public synchronized JSONObject startLogin(boolean acceptRisk) {
        if (!acceptRisk && !b.core.kvBool("chatgpt_oauth_accepted", false))
            throw new ApiError(422, "Confirm that you understand this sign-in is unofficial.");
        b.core.kvSet("chatgpt_oauth_accepted", "1");
        stopListener();
        SecureRandom rnd = new SecureRandom();
        byte[] v = new byte[64], st = new byte[24];
        rnd.nextBytes(v);
        rnd.nextBytes(st);
        final String verifier = b64url(v), state = b64url(st);
        String challenge;
        try { challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception e) { throw new ApiError(500, "SHA-256 unavailable"); }
        final ServerSocket ss;
        try {
            ss = new ServerSocket(1455, 4, InetAddress.getByName("127.0.0.1"));
            ss.setSoTimeout(10 * 60 * 1000);
        } catch (Exception e) {
            throw new ApiError(409, "Port 1455 on this phone is busy; close other apps that sign in to OpenAI and retry.");
        }
        listener = ss;
        String url = AUTH_URL + "?response_type=code&client_id=" + enc(CLIENT_ID) + "&redirect_uri=" + enc(REDIRECT)
                + "&scope=" + enc(SCOPE) + "&code_challenge=" + challenge + "&code_challenge_method=S256&state=" + state
                + "&id_token_add_organizations=true&codex_cli_simplified_flow=true&originator=opencode";
        login = J.obj("status", "waiting_for_user", "url", url);
        Thread t = new Thread(new Runnable() { public void run() { awaitCallback(ss, state, verifier); } }, "chatgpt-login");
        t.setDaemon(true);
        t.start();
        return login;
    }

    void stopListener() {
        ServerSocket s = listener;
        listener = null;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    void awaitCallback(ServerSocket ss, String state, String verifier) {
        try {
            while (true) {
                Socket sock = ss.accept();
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                    String line = in.readLine();
                    String path = line == null ? "" : line.split(" ").length > 1 ? line.split(" ")[1] : "";
                    if (!path.startsWith("/auth/callback")) { respond(sock, 404, "Not found"); continue; }
                    android.net.Uri u = android.net.Uri.parse("http://localhost" + path);
                    String err = null;
                    if (!state.equals(u.getQueryParameter("state"))) err = "state mismatch (possible CSRF) — start sign-in again";
                    else if (u.getQueryParameter("error") != null) err = u.getQueryParameter("error_description") != null ? u.getQueryParameter("error_description") : u.getQueryParameter("error");
                    String code = u.getQueryParameter("code");
                    if (err == null && code == null) err = "no authorization code";
                    if (err == null) {
                        try {
                            exchange(code, verifier);
                            respond(sock, 200, "<h1>LowBot: signed in to ChatGPT</h1><p>You can return to the app.</p>");
                            login = J.obj("status", "done");
                            ensureProfile();
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                    }
                    if (err != null) {
                        respond(sock, 400, "<h1>Sign-in failed</h1><p>" + android.text.Html.escapeHtml(err) + "</p>");
                        login = J.obj("status", "failed", "output", new JSONArray().put(err));
                    }
                    break;
                } finally {
                    try { sock.close(); } catch (Exception ignored) { }
                }
            }
        } catch (SocketTimeoutException e) {
            login = J.obj("status", "failed", "output", new JSONArray().put("Sign-in timed out."));
        } catch (Exception e) {
            if (listener != null) login = J.obj("status", "failed", "output", new JSONArray().put(e.getClass().getSimpleName()));
        } finally {
            stopListener();
        }
    }

    static void respond(Socket s, int code, String html) throws Exception {
        byte[] body = ("<!doctype html><meta charset=utf-8><body style='font-family:sans-serif;padding:2em'>" + html).getBytes(StandardCharsets.UTF_8);
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        o.write(body);
        o.flush();
    }

    JSONObject tokenRequest(String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Accept", "application/json");
            OutputStream o = c.getOutputStream();
            o.write(form.getBytes(StandardCharsets.UTF_8));
            o.close();
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code < 200 || code >= 300) throw new Model.ProviderError("auth", "ChatGPT token request failed (HTTP " + code + ").");
            return J.parse(text);
        } finally {
            c.disconnect();
        }
    }

    void exchange(String code, String verifier) throws Exception {
        JSONObject t = tokenRequest("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT)
                + "&client_id=" + enc(CLIENT_ID) + "&code_verifier=" + enc(verifier));
        store(t, null);
        b.core.db.tx(new Runnable() { public void run() { b.core.audit("chatgpt.login", "user", null, null, null, null, null, null, null); } });
    }

    /** Keeps only what is needed; account id and email are read from the JWT payload (no signature check needed). */
    void store(JSONObject t, JSONObject previous) {
        JSONObject claims = jwt(t.optString("id_token"));
        JSONObject access = jwt(t.optString("access_token"));
        String account = accountId(claims);
        if (account == null) account = accountId(access);
        if (account == null && previous != null) account = J.str(previous, "accountId", null);
        JSONObject auth = claims.optJSONObject("https://api.openai.com/auth");
        JSONObject out = J.obj("access", t.optString("access_token"),
                "refresh", t.has("refresh_token") ? t.optString("refresh_token") : previous == null ? null : previous.optString("refresh"),
                "expires", J.now() + t.optLong("expires_in", 3600) * 1000L, "accountId", account,
                "email", claims.has("email") ? claims.optString("email") : previous == null ? null : previous.opt("email"),
                "plan", auth != null ? auth.opt("chatgpt_plan_type") : previous == null ? null : previous.opt("plan"));
        saveTokens(out);
    }

    /** For tests: account id from a JWT. */
    public static String accountIdOf(String token) { return accountId(jwt(token)); }

    static JSONObject jwt(String token) {
        if (token == null || token.split("\\.").length < 2) return new JSONObject();
        try { return J.parse(new String(Base64.decode(token.split("\\.")[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONObject(); }
    }

    static String accountId(JSONObject p) {
        if (p.has("chatgpt_account_id")) return p.optString("chatgpt_account_id");
        JSONObject a = p.optJSONObject("https://api.openai.com/auth");
        if (a != null && a.has("chatgpt_account_id")) return a.optString("chatgpt_account_id");
        JSONArray orgs = p.optJSONArray("organizations");
        if (orgs != null && orgs.length() > 0 && orgs.optJSONObject(0) != null) return orgs.optJSONObject(0).optString("id", null);
        return null;
    }

    synchronized JSONObject validTokens(boolean force) throws Model.ProviderError {
        JSONObject t = tokens();
        if (t == null || !t.has("refresh")) throw new Model.ProviderError("config", "Not signed in to ChatGPT. Settings → Integrations → Sign in with ChatGPT.");
        if (force || t.optLong("expires") < J.now() + SAFETY_MS) {
            try {
                store(tokenRequest("grant_type=refresh_token&refresh_token=" + enc(t.optString("refresh")) + "&client_id=" + enc(CLIENT_ID)), t);
            } catch (Model.ProviderError e) {
                throw new Model.ProviderError("auth", "ChatGPT session expired; sign in again.");
            } catch (Exception e) {
                throw new Model.ProviderError("network", "Could not refresh the ChatGPT session: " + e.getClass().getSimpleName(), true, 0);
            }
            t = tokens();
        }
        return t;
    }

    void ensureProfile() {
        if (b.core.db.one("SELECT 1 FROM provider_profiles WHERE kind = 'chatgpt_oauth'") != null) return;
        b.providers.upsert(J.obj("kind", "chatgpt_oauth", "name", "ChatGPT (your account)"), null);
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        r.close();
        return sb.toString();
    }

    // ----------------------------------------------------------------- adapter
    public Model.Adapter adapter() {
        return new Model.Adapter() {
            @Override public Model.Response complete(Model.Request req) throws Model.ProviderError {
                try {
                    return call(req, false);
                } catch (Model.ProviderError e) {
                    if ("auth".equals(e.kind)) return call(req, true); // refresh once on 401
                    throw e;
                }
            }

            @Override public List<String> listModels() {
                try {
                    JSONObject t = validTokens(false);
                    HttpURLConnection c = open(t, "/models?client_version=2.0.0", "GET", 20);
                    try {
                        if (c.getResponseCode() != 200) return null;
                        JSONArray ms = J.parse(read(c.getInputStream())).optJSONArray("models");
                        List<String> out = new ArrayList<String>();
                        if (ms != null) for (int i = 0; i < ms.length(); i++) {
                            JSONObject m = ms.optJSONObject(i);
                            if (m != null && m.has("slug")) out.add(m.optString("slug"));
                        }
                        return out;
                    } finally { c.disconnect(); }
                } catch (Exception e) {
                    return null;
                }
            }
        };
    }

    HttpURLConnection open(JSONObject t, String path, String method, int timeoutS) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(Math.max(30, timeoutS) * 1000);
        c.setRequestProperty("Authorization", "Bearer " + t.optString("access"));
        if (J.str(t, "accountId", null) != null) c.setRequestProperty("ChatGPT-Account-Id", t.optString("accountId"));
        c.setRequestProperty("User-Agent", "LowBot-Android/2");
        c.setRequestProperty("Content-Type", "application/json");
        return c;
    }

    Model.Response call(Model.Request req, boolean forceRefresh) throws Model.ProviderError {
        JSONObject t = validTokens(forceRefresh);
        JSONObject body = Model.Responses.body(req);
        if (J.str(body, "instructions", "").isEmpty()) J.put(body, "instructions", "You are a helpful assistant.");
        J.put(body, "store", false);
        J.put(body, "stream", true);
        HttpURLConnection c = null;
        try {
            c = open(t, "/responses", "POST", req.timeoutS);
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "text/event-stream");
            OutputStream o = c.getOutputStream();
            o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            o.close();
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                String detail = Model.errorDetailPublic(read(c.getErrorStream()));
                if (code == 429) throw new Model.ProviderError("rate_limit", "ChatGPT plan limit reached (HTTP 429). " + detail, true, 60);
                if (code == 401) throw new Model.ProviderError("auth", "ChatGPT rejected the session (HTTP 401).");
                if (code >= 500) throw new Model.ProviderError("server", "ChatGPT backend error (HTTP " + code + ").", true, 0);
                throw new Model.ProviderError("bad_request", "ChatGPT backend refused the request (HTTP " + code + "). " + detail);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            JSONObject done = null;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("data:")) { data.append(line.substring(5).trim()); continue; }
                if (!line.trim().isEmpty() || data.length() == 0) continue;
                JSONObject ev = J.parse(data.toString());
                data.setLength(0);
                String type = ev.optString("type");
                if ("response.completed".equals(type) || "response.incomplete".equals(type)) { done = ev.optJSONObject("response"); break; }
                if ("response.failed".equals(type) || "error".equals(type))
                    throw new Model.ProviderError("server", "ChatGPT stream ended with " + type + ".", true, 0);
            }
            r.close();
            if (done == null) throw new Model.ProviderError("server", "ChatGPT stream ended before completion.", true, 0);
            return Model.Responses.parse(done);
        } catch (Model.ProviderError e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new Model.ProviderError("timeout", "ChatGPT request timed out.", true, 0);
        } catch (Exception e) {
            throw new Model.ProviderError("network", "Network error: " + e.getClass().getSimpleName(), true, 0);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
