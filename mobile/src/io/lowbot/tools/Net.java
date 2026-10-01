package io.lowbot.tools;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/**
 * Egress guard against SSRF for every fetch made on a bot's behalf (port of netguard.py).
 * Only http/https; no credentials in URLs; every resolved address must be public unless
 * LAN access was explicitly enabled; metadata addresses are always blocked; redirects are
 * followed manually and every hop is re-checked. Residual risk: HttpURLConnection
 * re-resolves the name after the check (DNS rebinding window) — documented in SECURITY.md.
 */
public final class Net {
    private Net() {}

    public static final class Denied extends Exception {
        public Denied(String m) { super(m); }
    }

    public static final class Response {
        public int status;
        public String finalUrl, contentType = "", charset = "UTF-8";
        public byte[] body = new byte[0];
        public final Map<String, String> headers = new java.util.HashMap<String, String>();
        public String text() {
            try { return new String(body, charset); } catch (Exception e) { return new String(body); }
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224) return false;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false; // CGNAT
            if (b0 == 169 && b1 == 254) return false;
            if (b0 == 172 && b1 >= 16 && b1 <= 31) return false;
            if (b0 == 192 && b1 == 168) return false;
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return false;
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return false; // unique local
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (Exception e) { return false; }
            }
        }
        return true;
    }

    static boolean alwaysBlocked(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff, b2 = b[2] & 0xff, b3 = b[3] & 0xff;
            if (b0 == 169 && b1 == 254 && b2 == 169 && b3 == 254) return true;
            if (b0 == 100 && b1 == 100 && b2 == 100 && b3 == 200) return true;
            if (b0 == 0) return true;
        }
        return false;
    }

    public static void vet(String url, boolean allowPrivate) throws Denied {
        URI u;
        try { u = new URI(url); } catch (Exception e) { throw new Denied("Invalid URL."); }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new Denied("Only http and https URLs are allowed.");
        if (u.getRawUserInfo() != null) throw new Denied("Credentials in URLs are not allowed.");
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        if (host.isEmpty()) throw new Denied("URL has no host.");
        if (host.equals("metadata.google.internal") || host.equals("metadata") || host.equals("instance-data") || host.equals("metadata.azure.com"))
            throw new Denied("Cloud metadata hosts are blocked.");
        InetAddress[] ips;
        try { ips = InetAddress.getAllByName(host.replaceAll("^\\[|\\]$", "")); } catch (Exception e) { throw new Denied("Cannot resolve " + host + "."); }
        for (InetAddress a : ips) {
            if (alwaysBlocked(a)) throw new Denied("Address " + a.getHostAddress() + " is a blocked metadata/unspecified address.");
            if (!allowPrivate && !isPublic(a)) throw new Denied("Address " + a.getHostAddress() + " is not public; LAN access must be enabled in Settings.");
        }
    }

    public static Response request(String method, String url, Map<String, String> headers, byte[] body, boolean allowPrivate,
                                   int maxRedirects, int maxBytes, int timeoutS) throws Exception {
        String cur = url;
        for (int hop = 0; hop <= Math.max(0, maxRedirects); hop++) {
            vet(cur, allowPrivate);
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(timeoutS * 1000);
                c.setRequestMethod(method);
                c.setRequestProperty("User-Agent", "LowBot/2 (Android; +personal agent)");
                if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                if (body != null) {
                    c.setDoOutput(true);
                    OutputStream os = c.getOutputStream();
                    os.write(body);
                    os.close();
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null && hop < maxRedirects && body == null) {
                    cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                    continue;
                }
                Response r = new Response();
                r.status = code;
                r.finalUrl = cur;
                r.contentType = c.getContentType() == null ? "" : c.getContentType();
                for (Map.Entry<String, java.util.List<String>> h : c.getHeaderFields().entrySet())
                    if (h.getKey() != null && !h.getValue().isEmpty()) r.headers.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue().get(0));
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("charset=([\\w-]+)").matcher(r.contentType);
                if (m.find()) r.charset = m.group(1);
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && bo.size() < maxBytes) bo.write(buf, 0, Math.min(n, maxBytes - bo.size()));
                    in.close();
                    r.body = bo.toByteArray();
                }
                return r;
            } finally {
                c.disconnect();
            }
        }
        throw new Denied("Too many redirects.");
    }

    /** Very small HTML → text extraction (scripts/styles dropped, block tags become newlines). */
    public static String[] htmlToText(String html) {
        String title = "";
        java.util.regex.Matcher t = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (t.find()) title = android.text.Html.fromHtml(t.group(1)).toString().trim();
        String s = html.replaceAll("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<(br|p|div|li|h[1-6]|tr)[^>]*>", "\n");
        String text = android.text.Html.fromHtml(s).toString();
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (!l.isEmpty()) out.append(l).append('\n');
        }
        StringBuilder links = new StringBuilder();
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("(?i)<a\\s[^>]*href=[\"']([^\"'#]+)[\"']").matcher(html);
        int n = 0;
        while (a.find() && n++ < 30) links.append(a.group(1)).append('\n');
        return new String[]{title, out.toString().trim(), links.toString().trim()};
    }
}
