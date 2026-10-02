package io.lowbot.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * The visual part of a widget a bot makes: a chart from numbers, a big number (stat), an image
 * (e.g. a matplotlib PNG from the bot's Linux) or the bot's own HTML/SVG. Charts and stats are
 * turned into a small "scene" of shapes (320×160 units) that the app draws natively on the
 * phone's home screen and serialises to SVG inside LowBot. Everything is shown in a document
 * without network access (CSP + sandboxed iframe), so a widget can never load or send anything.
 */
public final class WidgetVisual {
    public static final int W = 320, H = 160, HTML_MAX = 40000, IMAGE_MAX = 1500 * 1024;
    public static final String[] PALETTE = {"#60a5fa", "#f472b6", "#34d399", "#fbbf24", "#a78bfa", "#f87171"};
    static final String UP = "#34d399", DOWN = "#f87171", GRID = "#3f3f46", MUTED = "#a1a1aa", TEXT = "#f4f4f5";

    private WidgetVisual() { }

    // ------------------------------------------------------------------ validation
    /** Builds the stored visual from tool arguments (chart | stat | image | html), or null when none is given. */
    public static JSONObject fromArgs(JSONObject a, File workspaceRoot, File mediaDir, String widgetId) {
        int n = (a.optJSONObject("chart") != null ? 1 : 0) + (a.optJSONObject("stat") != null ? 1 : 0)
                + (a.optString("image").trim().isEmpty() ? 0 : 1) + (a.optString("html").trim().isEmpty() ? 0 : 1);
        if (n == 0) return null;
        if (n > 1) throw new ApiError(422, "Give only one of chart, stat, image or html.");
        if (a.optJSONObject("chart") != null) return chart(a.optJSONObject("chart"));
        if (a.optJSONObject("stat") != null) return stat(a.optJSONObject("stat"));
        if (!a.optString("image").trim().isEmpty()) return image(a.optString("image").trim(), workspaceRoot, mediaDir, widgetId);
        String html = a.optString("html");
        if (html.length() > HTML_MAX) throw new ApiError(422, "html is too long (max " + HTML_MAX + " characters).");
        return J.obj("type", "html", "html", html, "height", clampInt(a.optInt("height", 180), 80, 420));
    }

    static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static JSONObject chart(JSONObject c) {
        String kind = c.optString("type", "line").toLowerCase(Locale.ROOT);
        if (!java.util.Arrays.asList("line", "area", "bar", "pie", "donut").contains(kind))
            throw new ApiError(422, "chart.type must be line, area, bar, pie or donut.");
        JSONArray series = c.optJSONArray("series");
        if (series == null && c.optJSONArray("values") != null) series = new JSONArray().put(J.obj("name", c.optString("name"), "values", c.optJSONArray("values")));
        if (series == null || series.length() == 0) throw new ApiError(422, "chart needs series: [{name, values: [numbers]}] (or values: [numbers]).");
        if (series.length() > 6) throw new ApiError(422, "At most 6 series.");
        JSONArray clean = new JSONArray();
        int len = -1;
        for (int i = 0; i < series.length(); i++) {
            JSONObject s = series.optJSONObject(i);
            JSONArray v = s == null ? null : s.optJSONArray("values");
            if (v == null || v.length() == 0) throw new ApiError(422, "Each series needs values: [numbers].");
            if (v.length() > 500) throw new ApiError(422, "At most 500 values per series.");
            JSONArray nums = new JSONArray();
            for (int k = 0; k < v.length(); k++) {
                double d = v.optDouble(k, Double.NaN);
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new ApiError(422, "chart values must be numbers.");
                try { nums.put(d); } catch (Exception ignored) { }
            }
            len = Math.max(len, nums.length());
            clean.put(J.obj("name", J.truncate(s.optString("name"), 24), "values", nums));
        }
        JSONArray labels = new JSONArray();
        JSONArray lab = c.optJSONArray("labels");
        if (lab != null) for (int i = 0; i < Math.min(lab.length(), 500); i++) labels.put(J.truncate(lab.optString(i), 16));
        return J.obj("type", "chart", "chart", kind, "series", clean, "labels", labels, "unit", J.truncate(c.optString("unit"), 6));
    }

    static JSONObject stat(JSONObject s) {
        String value = J.truncate(s.optString("value").trim(), 16);
        if (value.isEmpty()) throw new ApiError(422, "stat needs a value, e.g. {\"value\": \"59 800 $\", \"label\": \"BTC\", \"change\": \"-3.2%\"}.");
        JSONArray trend = new JSONArray();
        JSONArray t = s.optJSONArray("trend");
        if (t != null) for (int i = 0; i < Math.min(t.length(), 200); i++) {
            double d = t.optDouble(i, Double.NaN);
            if (!Double.isNaN(d) && !Double.isInfinite(d)) try { trend.put(d); } catch (Exception ignored) { }
        }
        return J.obj("type", "stat", "value", value, "label", J.truncate(s.optString("label"), 28), "change", J.truncate(s.optString("change"), 12), "trend", trend);
    }

    static JSONObject image(String path, File root, File mediaDir, String widgetId) {
        String p = path.replaceFirst("^/workspace/", "").replaceFirst("^/+", "");
        File f;
        try {
            f = new File(root, p).getCanonicalFile();
            if (!f.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new ApiError(403, "image must be a file in your workspace.");
        } catch (java.io.IOException e) {
            throw new ApiError(422, "Bad image path.");
        }
        if (!f.isFile()) throw new ApiError(404, "No such file in your workspace: " + p);
        String name = f.getName().toLowerCase(Locale.ROOT);
        String ext = name.endsWith(".png") ? "png" : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "jpg" : name.endsWith(".webp") ? "webp"
                : name.endsWith(".gif") ? "gif" : null;
        if (ext == null) throw new ApiError(422, "image must be a .png, .jpg, .webp or .gif file.");
        if (f.length() > IMAGE_MAX) throw new ApiError(422, "image is too large (max 1.5 MB).");
        mediaDir.mkdirs();
        File out = new File(mediaDir, widgetId + "." + ext);
        try {
            java.nio.file.Files.copy(f.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new ApiError(500, "Could not store the image.");
        }
        return J.obj("type", "image", "file", out.getName(), "mime", "jpg".equals(ext) ? "image/jpeg" : "image/" + ext);
    }

    // ------------------------------------------------------------------ scene
    /** Shapes in a 320×160 box: poly {pts, closed, fill, stroke, width, opacity}, arc, text. */
    public static JSONArray scene(JSONObject v) {
        JSONArray ops = new JSONArray();
        if ("chart".equals(v.optString("type"))) chartScene(v, ops);
        else if ("stat".equals(v.optString("type"))) statScene(v, ops);
        return ops;
    }

    static double[] nums(JSONArray a) {
        double[] d = new double[a == null ? 0 : a.length()];
        for (int i = 0; i < d.length; i++) d[i] = a.optDouble(i, 0);
        return d;
    }

    static String fmt(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) return trim(v / 1e9) + "B";
        if (a >= 1e6) return trim(v / 1e6) + "M";
        if (a >= 1e4) return trim(v / 1e3) + "k";
        if (a >= 100 || v == Math.rint(v)) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static String trim(double v) { return String.format(Locale.ROOT, "%.1f", v).replaceAll("\\.0$", ""); }

    static JSONObject text(double x, double y, String s, int size, String color, String anchor, boolean bold) {
        return J.obj("op", "text", "x", x, "y", y, "text", s, "size", size, "color", color, "anchor", anchor, "bold", bold);
    }

    static JSONObject poly(double[] pts, boolean closed, String fill, String stroke, double width, double opacity) {
        JSONArray p = new JSONArray();
        for (double d : pts) try { p.put(Math.round(d * 10) / 10.0); } catch (Exception ignored) { }
        return J.obj("op", "poly", "pts", p, "closed", closed, "fill", fill, "stroke", stroke, "width", width, "opacity", opacity);
    }

    static void chartScene(JSONObject v, JSONArray ops) {
        String kind = v.optString("chart");
        JSONArray series = v.optJSONArray("series");
        JSONArray labels = v.optJSONArray("labels");
        String unit = v.optString("unit");
        boolean legend = series.length() > 1 || "pie".equals(kind) || "donut".equals(kind);
        if ("pie".equals(kind) || "donut".equals(kind)) {
            double[] vals = nums(series.optJSONObject(0).optJSONArray("values"));
            double total = 0;
            for (double d : vals) total += Math.max(0, d);
            if (total <= 0) return;
            double start = -90;
            for (int i = 0; i < vals.length; i++) {
                double sweep = Math.max(0, vals[i]) / total * 360;
                ops.put(J.obj("op", "arc", "cx", 80, "cy", 80, "r", 66, "r0", "donut".equals(kind) ? 38 : 0, "start", start, "sweep", sweep,
                        "fill", PALETTE[i % PALETTE.length]));
                start += sweep;
            }
            for (int i = 0; i < Math.min(vals.length, 6); i++) {
                double y = 30 + i * 22;
                ops.put(poly(new double[]{170, y - 9, 180, y - 9, 180, y + 1, 170, y + 1}, true, PALETTE[i % PALETTE.length], null, 0, 1));
                String name = labels != null && i < labels.length() ? labels.optString(i) : "#" + (i + 1);
                ops.put(text(186, y, J.truncate(name, 14) + "  " + fmt(vals[i]) + unit, 11, TEXT, "start", false));
            }
            return;
        }
        double left = 34, right = W - 8, top = legend ? 22 : 10, bottom = H - 20;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int len = 0;
        for (int s = 0; s < series.length(); s++) {
            double[] d = nums(series.optJSONObject(s).optJSONArray("values"));
            len = Math.max(len, d.length);
            for (double x : d) { min = Math.min(min, x); max = Math.max(max, x); }
        }
        if ("bar".equals(kind)) { min = Math.min(0, min); max = Math.max(0, max); }
        if (max == min) { max += 1; min -= 1; }
        // Round the axis to "nice" steps (…, 1, 2, 2.5, 5, 10, …) so the grid reads 0 / 5k / 10k, not 0 / 4374 / 8748.
        double raw = (max - min) / 2, mag = Math.pow(10, Math.floor(Math.log10(raw))), step = mag * 10;
        for (double m : new double[]{1, 2, 2.5, 5, 10}) if (m * mag >= raw) { step = m * mag; break; }
        double lo = Math.floor(min / step) * step, hi = lo + 2 * step;
        while (hi < max) { step = step * 2; lo = Math.floor(min / step) * step; hi = lo + 2 * step; }
        min = lo;
        max = hi;
        for (int g = 0; g <= 2; g++) {
            double val = min + (max - min) * g / 2.0, y = bottom - (bottom - top) * g / 2.0;
            ops.put(poly(new double[]{left, y, right, y}, false, null, GRID, 1, 1));
            ops.put(text(left - 4, y + 3.5, fmt(val), 9, MUTED, "end", false));
        }
        if (labels != null && labels.length() > 0) {
            int[] at = labels.length() == 1 ? new int[]{0} : new int[]{0, (labels.length() - 1) / 2, labels.length() - 1};
            for (int k = 0; k < at.length; k++) {
                if (k > 0 && at[k] == at[k - 1]) continue;
                double x = len <= 1 ? (left + right) / 2 : left + (right - left) * at[k] / (double) Math.max(1, labels.length() - 1);
                ops.put(text(x, H - 5, labels.optString(at[k]), 9, MUTED, k == 0 ? "start" : k == at.length - 1 ? "end" : "middle", false));
            }
        }
        double span = max - min;
        for (int s = 0; s < series.length(); s++) {
            double[] d = nums(series.optJSONObject(s).optJSONArray("values"));
            String color = PALETTE[s % PALETTE.length];
            if ("bar".equals(kind)) {
                double slot = (right - left) / Math.max(1, len), group = slot * 0.75, bw = group / series.length();
                double zero = bottom - (0 - min) / span * (bottom - top);
                for (int i = 0; i < d.length; i++) {
                    double x0 = left + slot * i + (slot - group) / 2 + bw * s, y = bottom - (d[i] - min) / span * (bottom - top);
                    double y0 = Math.min(y, zero), y1 = Math.max(y, zero);
                    if (y1 - y0 < 1) y1 = y0 + 1;
                    ops.put(poly(new double[]{x0, y0, x0 + Math.max(1, bw - 1), y0, x0 + Math.max(1, bw - 1), y1, x0, y1}, true, color, null, 0, 1));
                }
            } else {
                double[] pts = new double[d.length * 2];
                for (int i = 0; i < d.length; i++) {
                    pts[2 * i] = d.length == 1 ? (left + right) / 2 : left + (right - left) * i / (double) (d.length - 1);
                    pts[2 * i + 1] = bottom - (d[i] - min) / span * (bottom - top);
                }
                if ("area".equals(kind) && d.length > 1) {
                    double[] area = new double[pts.length + 4];
                    System.arraycopy(pts, 0, area, 0, pts.length);
                    area[pts.length] = pts[pts.length - 2]; area[pts.length + 1] = bottom;
                    area[pts.length + 2] = pts[0]; area[pts.length + 3] = bottom;
                    ops.put(poly(area, true, color, null, 0, 0.22));
                }
                ops.put(poly(pts, false, null, color, 2, 1));
            }
        }
        if (legend) for (int s = 0; s < series.length(); s++) {
            double x = left + s * 70;
            ops.put(poly(new double[]{x, 6, x + 8, 6, x + 8, 14, x, 14}, true, PALETTE[s % PALETTE.length], null, 0, 1));
            ops.put(text(x + 12, 13.5, J.truncate(series.optJSONObject(s).optString("name"), 9), 10, MUTED, "start", false));
        }
    }

    static void statScene(JSONObject v, JSONArray ops) {
        String change = v.optString("change");
        String color = change.startsWith("-") || change.startsWith("−") || change.startsWith("▼") ? DOWN : change.isEmpty() ? MUTED : UP;
        if (!v.optString("label").isEmpty()) ops.put(text(12, 26, v.optString("label"), 14, MUTED, "start", false));
        ops.put(text(12, 74, v.optString("value"), v.optString("value").length() > 10 ? 30 : 40, TEXT, "start", true));
        if (!change.isEmpty()) ops.put(text(12, 100, change, 15, color, "start", true));
        double[] t = nums(v.optJSONArray("trend"));
        if (t.length > 1) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double d : t) { min = Math.min(min, d); max = Math.max(max, d); }
            if (max == min) { max += 1; min -= 1; }
            double l = 12, r = W - 12, top = 112, bottom = H - 8;
            double[] pts = new double[t.length * 2];
            for (int i = 0; i < t.length; i++) {
                pts[2 * i] = l + (r - l) * i / (double) (t.length - 1);
                pts[2 * i + 1] = bottom - (t[i] - min) / (max - min) * (bottom - top);
            }
            double[] area = new double[pts.length + 4];
            System.arraycopy(pts, 0, area, 0, pts.length);
            area[pts.length] = r; area[pts.length + 1] = bottom; area[pts.length + 2] = l; area[pts.length + 3] = bottom;
            String trendColor = t[t.length - 1] >= t[0] ? UP : DOWN;
            ops.put(poly(area, true, trendColor, null, 0, 0.18));
            ops.put(poly(pts, false, null, trendColor, 2, 1));
        }
    }

    // ------------------------------------------------------------------ SVG + document
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static String svg(JSONArray ops) {
        StringBuilder sb = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + W + " " + H + "\" width=\"100%\" "
                + "font-family=\"system-ui,-apple-system,Roboto,sans-serif\">");
        for (int i = 0; i < ops.length(); i++) {
            JSONObject o = ops.optJSONObject(i);
            String op = o.optString("op");
            if ("poly".equals(op)) {
                JSONArray p = o.optJSONArray("pts");
                StringBuilder pts = new StringBuilder();
                for (int k = 0; k + 1 < p.length(); k += 2) pts.append(k == 0 ? "" : " ").append(p.optDouble(k)).append(',').append(p.optDouble(k + 1));
                boolean fill = !o.isNull("fill") && !o.optString("fill").isEmpty();
                sb.append(o.optBoolean("closed") ? "<polygon" : "<polyline").append(" points=\"").append(pts).append("\" fill=\"")
                        .append(fill ? esc(o.optString("fill")) : "none").append('"');
                if (!o.isNull("stroke") && !o.optString("stroke").isEmpty())
                    sb.append(" stroke=\"").append(esc(o.optString("stroke"))).append("\" stroke-width=\"").append(o.optDouble("width", 1))
                            .append("\" stroke-linejoin=\"round\" stroke-linecap=\"round\"");
                if (o.optDouble("opacity", 1) < 1) sb.append(" opacity=\"").append(o.optDouble("opacity")).append('"');
                sb.append("/>");
            } else if ("arc".equals(op)) {
                sb.append("<path d=\"").append(arcPath(o)).append("\" fill=\"").append(esc(o.optString("fill"))).append("\"/>");
            } else if ("text".equals(op)) {
                sb.append("<text x=\"").append(o.optDouble("x")).append("\" y=\"").append(o.optDouble("y")).append("\" font-size=\"").append(o.optInt("size"))
                        .append("\" fill=\"").append(esc(o.optString("color"))).append("\" text-anchor=\"").append(esc(o.optString("anchor")))
                        .append(o.optBoolean("bold") ? "\" font-weight=\"700" : "").append("\">").append(esc(o.optString("text"))).append("</text>");
            }
        }
        return sb.append("</svg>").toString();
    }

    static String arcPath(JSONObject o) {
        double cx = o.optDouble("cx"), cy = o.optDouble("cy"), r = o.optDouble("r"), r0 = o.optDouble("r0"), a0 = Math.toRadians(o.optDouble("start"));
        double sweep = Math.min(359.99, o.optDouble("sweep")), a1 = a0 + Math.toRadians(sweep);
        int large = sweep > 180 ? 1 : 0;
        String p = String.format(Locale.ROOT, "M%.2f %.2f A%.2f %.2f 0 %d 1 %.2f %.2f", cx + r * Math.cos(a0), cy + r * Math.sin(a0), r, r, large,
                cx + r * Math.cos(a1), cy + r * Math.sin(a1));
        if (r0 > 0) p += String.format(Locale.ROOT, " L%.2f %.2f A%.2f %.2f 0 %d 0 %.2f %.2f Z", cx + r0 * Math.cos(a1), cy + r0 * Math.sin(a1), r0, r0, large,
                cx + r0 * Math.cos(a0), cy + r0 * Math.sin(a0));
        else p += String.format(Locale.ROOT, " L%.2f %.2f Z", cx, cy);
        return p;
    }

    static final String CSP = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; media-src data:; "
            + "style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:\">";

    /** A self-contained document showing the visual (no network: CSP default-src 'none'). */
    public static String document(JSONObject v, File mediaDir) {
        String type = v.optString("type"), body;
        if ("html".equals(type)) body = v.optString("html");
        else if ("image".equals(type)) {
            String data = "";
            try {
                data = android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(new File(mediaDir, v.optString("file")).toPath()), android.util.Base64.NO_WRAP);
            } catch (Exception ignored) { }
            body = "<img alt=\"\" src=\"data:" + esc(v.optString("mime")) + ";base64," + data + "\" style=\"display:block;width:100%;height:100%;object-fit:contain\">";
        } else body = svg(scene(v));
        return "<!doctype html><html><head><meta charset=\"utf-8\">" + CSP + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;background:transparent;color:#f4f4f5;font:14px system-ui,-apple-system,Roboto,sans-serif;overflow:hidden}"
                + "svg{display:block}</style></head><body>" + body + "</body></html>";
    }

    /** Height (CSS px at a 320 px width) the visual wants. */
    public static int height(JSONObject v) {
        if ("html".equals(v.optString("type"))) return v.optInt("height", 180);
        return "image".equals(v.optString("type")) ? 200 : H;
    }
}
