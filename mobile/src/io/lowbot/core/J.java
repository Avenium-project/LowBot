package io.lowbot.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers: ids, time, JSON, hashing, redaction. */
public final class J {
    private J() {}

    /** Tests may override the clock (milliseconds since epoch). */
    public static volatile long fixedClock = 0;

    public static long now() {
        return fixedClock != 0 ? fixedClock : System.currentTimeMillis();
    }

    public static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    public static String nowIso() {
        return iso(now());
    }

    public static String isoIn(long seconds) {
        return iso(now() + seconds * 1000L);
    }

    public static long parseIso(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Canonical JSON (sorted keys) so equal arguments hash identically. */
    public static String canonical(Object v) {
        try {
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                TreeMap<String, Object> m = new TreeMap<String, Object>();
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    m.put(k, o.get(k));
                }
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (String k : m.keySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(JSONObject.quote(k)).append(':').append(canonical(m.get(k)));
                }
                return sb.append('}').toString();
            }
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < a.length(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(canonical(a.get(i)));
                }
                return sb.append(']').toString();
            }
            if (v instanceof String) return JSONObject.quote((String) v);
            if (v == null || v == JSONObject.NULL) return "null";
            return String.valueOf(v);
        } catch (JSONException e) {
            return "null";
        }
    }

    public static String argsHash(String tool, JSONObject args) {
        return sha256(tool + "\n" + canonical(args));
    }

    public static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    public static JSONObject parse(String s) {
        if (s == null || s.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static JSONArray parseArr(String s) {
        if (s == null || s.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v == null ? JSONObject.NULL : v);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public static String str(JSONObject o, String k, String def) {
        if (o == null || !o.has(k) || o.isNull(k)) return def;
        return o.optString(k, def);
    }

    public static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<String>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    public static JSONArray arr(List<?> items) {
        JSONArray a = new JSONArray();
        for (Object o : items) a.put(o);
        return a;
    }

    public static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, Math.max(0, n - 20)) + "\n…[truncated " + (s.length() - n + 20) + " chars]";
    }

    public static String slug(String s) {
        String r = (s == null ? "" : s).toLowerCase(Locale.ROOT)
                .replace('ą', 'a').replace('ć', 'c').replace('ę', 'e').replace('ł', 'l').replace('ń', 'n')
                .replace('ó', 'o').replace('ś', 's').replace('ź', 'z').replace('ż', 'z')
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return r.isEmpty() ? "bot" : r;
    }

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|authorization|password|secret|token|bearer)\\b(\\s*[:=]\\s*|\\s+)['\"]?([A-Za-z0-9_\\-.=/+]{8,})");
    private static final Pattern KEY_SHAPES = Pattern.compile("\\b(sk-[A-Za-z0-9_\\-]{16,}|xai-[A-Za-z0-9_\\-]{16,}|ghp_[A-Za-z0-9]{20,})\\b");
    private static final String[] SENSITIVE_KEYS = {"api_key", "apikey", "authorization", "cookie", "credential", "password", "secret", "token"};

    public static String redact(String s) {
        if (s == null) return null;
        Matcher m = SENSITIVE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + "[REDACTED]"));
        m.appendTail(sb);
        return KEY_SHAPES.matcher(sb.toString()).replaceAll("[REDACTED]");
    }

    /** Deep copy with credential-like keys and values removed. */
    public static Object redact(Object v, String key) {
        if (key != null) {
            String k = key.toLowerCase(Locale.ROOT);
            for (String s : SENSITIVE_KEYS) if (k.contains(s)) return "[REDACTED]";
        }
        try {
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v, out = new JSONObject();
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    out.put(k, redact(o.get(k), k));
                }
                return out;
            }
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v, out = new JSONArray();
                for (int i = 0; i < a.length(); i++) out.put(redact(a.get(i), key));
                return out;
            }
            if (v instanceof String) return redact((String) v);
        } catch (JSONException e) {
            return "[unserializable]";
        }
        return v;
    }

    public static JSONObject redactObj(JSONObject o) {
        return (JSONObject) redact(o, null);
    }

    /** fnmatch-style glob (* and ?), case-sensitive. */
    public static boolean glob(String pattern, String s) {
        if (pattern == null || s == null) return false;
        StringBuilder re = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') re.append(".*");
            else if (c == '?') re.append('.');
            else re.append(Pattern.quote(String.valueOf(c)));
        }
        return s.matches(re.toString());
    }

    public static boolean anyGlob(List<String> patterns, String s) {
        for (String p : patterns) if (glob(p, s)) return true;
        return false;
    }

    public static boolean bool(JSONObject o, String k) {
        Object v = o == null ? null : o.opt(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return "true".equals(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }
}
