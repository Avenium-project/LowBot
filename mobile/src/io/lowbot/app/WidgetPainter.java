package io.lowbot.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import io.lowbot.core.WidgetVisual;

/**
 * Draws a widget's visual for the phone's home screen, where only bitmaps can show it: charts and
 * stats natively from the same scene LowBot shows as SVG, images decoded from the stored file, and
 * the bot's own HTML by rendering it in an offscreen WebView without network access and taking a
 * picture of it (asynchronous; the widget shows its text until the picture is ready).
 */
final class WidgetPainter {
    interface Done { void bitmap(Bitmap b); }

    static final Handler main = new Handler(Looper.getMainLooper());
    static final Map<String, Bitmap> htmlCache = java.util.Collections.synchronizedMap(new LinkedHashMap<String, Bitmap>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 8; }
    });
    static final java.util.Set<String> pending = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private WidgetPainter() { }

    static int color(String c, int fallback) {
        try { return Color.parseColor(c); } catch (Exception e) { return fallback; }
    }

    /** Charts and stats: the 320×160 scene scaled to widthPx. */
    static Bitmap scene(JSONArray ops, int widthPx) {
        float k = widthPx / (float) WidgetVisual.W;
        Bitmap bm = Bitmap.createBitmap(widthPx, Math.round(WidgetVisual.H * k), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.scale(k, k);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < ops.length(); i++) {
            JSONObject o = ops.optJSONObject(i);
            String op = o.optString("op");
            if ("poly".equals(op)) {
                JSONArray pts = o.optJSONArray("pts");
                if (pts == null || pts.length() < 4) continue;
                Path path = new Path();
                path.moveTo((float) pts.optDouble(0), (float) pts.optDouble(1));
                for (int j = 2; j + 1 < pts.length(); j += 2) path.lineTo((float) pts.optDouble(j), (float) pts.optDouble(j + 1));
                if (o.optBoolean("closed")) path.close();
                int alpha = Math.round((float) o.optDouble("opacity", 1) * 255);
                if (!o.isNull("fill") && !o.optString("fill").isEmpty()) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(color(o.optString("fill"), Color.WHITE));
                    p.setAlpha(alpha);
                    c.drawPath(path, p);
                }
                if (!o.isNull("stroke") && !o.optString("stroke").isEmpty()) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth((float) o.optDouble("width", 1));
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setColor(color(o.optString("stroke"), Color.WHITE));
                    p.setAlpha(alpha);
                    c.drawPath(path, p);
                }
            } else if ("arc".equals(op)) {
                float cx = (float) o.optDouble("cx"), cy = (float) o.optDouble("cy"), r = (float) o.optDouble("r"), r0 = (float) o.optDouble("r0");
                p.setStyle(Paint.Style.FILL);
                p.setColor(color(o.optString("fill"), Color.WHITE));
                p.setAlpha(255);
                Path path = new Path();
                float start = (float) o.optDouble("start"), sweep = (float) Math.min(359.99, o.optDouble("sweep"));
                path.arcTo(new RectF(cx - r, cy - r, cx + r, cy + r), start, sweep);
                if (r0 > 0) path.arcTo(new RectF(cx - r0, cy - r0, cx + r0, cy + r0), start + sweep, -sweep);
                else path.lineTo(cx, cy);
                path.close();
                c.drawPath(path, p);
            } else if ("text".equals(op)) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(color(o.optString("color"), Color.WHITE));
                p.setAlpha(255);
                p.setTextSize(o.optInt("size", 10));
                p.setTypeface(o.optBoolean("bold") ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                String a = o.optString("anchor");
                p.setTextAlign("end".equals(a) ? Paint.Align.RIGHT : "middle".equals(a) ? Paint.Align.CENTER : Paint.Align.LEFT);
                c.drawText(o.optString("text"), (float) o.optDouble("x"), (float) o.optDouble("y"), p);
            }
        }
        return bm;
    }

    /** A stored image, scaled down to at most widthPx wide. */
    static Bitmap image(File f, int widthPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= widthPx) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        if (b.getWidth() > widthPx) {
            Bitmap s = Bitmap.createScaledBitmap(b, widthPx, Math.max(1, b.getHeight() * widthPx / b.getWidth()), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    /** The bot's HTML as a picture: offscreen WebView, network blocked, picture taken shortly after it loads. Main thread. */
    static void html(final Context ctx, final String html, final int widthPx, final int heightPx, final Done done) {
        final WebView wv = new WebView(ctx.getApplicationContext());
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        s.setGeolocationEnabled(false);
        wv.setBackgroundColor(Color.TRANSPARENT);
        final boolean[] finished = {false};
        final Runnable capture = new Runnable() { public void run() {
            if (finished[0]) return;
            finished[0] = true;
            Bitmap bm = null;
            try {
                bm = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
                wv.draw(new Canvas(bm));
            } catch (Throwable e) {
                bm = null;
            }
            wv.destroy();
            done.bitmap(bm);
        } };
        wv.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("data:")) return null;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public void onPageFinished(WebView v, String url) { main.postDelayed(capture, 700); }
        });
        // Lay the page out 320 CSS px wide (the width bots design for), whatever the bitmap size.
        wv.setInitialScale(Math.round(widthPx * 100f / WidgetVisual.W));
        wv.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        wv.layout(0, 0, widthPx, heightPx);
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        main.postDelayed(capture, 5000);
    }

    /** True when the bitmap is not one flat colour (i.e. something was drawn). */
    static boolean hasContent(Bitmap b) {
        if (b == null) return false;
        int first = b.getPixel(0, 0);
        for (int y = 0; y < b.getHeight(); y += Math.max(1, b.getHeight() / 24))
            for (int x = 0; x < b.getWidth(); x += Math.max(1, b.getWidth() / 24))
                if (b.getPixel(x, y) != first) return true;
        return Color.alpha(first) > 0;
    }
}
