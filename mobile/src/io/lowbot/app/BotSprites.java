package io.lowbot.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a bot character (the same shapes as the app: client/components/v2/ui.jsx SHAPES)
 * into a bitmap for the home-screen widget: awake (open eyes), asleep (closed eyes + "z")
 * or needing attention (open eyes + amber dot), lifted by a hop offset.
 */
final class BotSprites {
    // Paths in a 100x100 box and eye anchors — kept in sync with ui.jsx.
    static final Map<String, String> SHAPES = new HashMap<String, String>();
    static final Map<String, float[]> EYES = new HashMap<String, float[]>();
    static {
        put("circle", "M50 6C74.3 6 94 25.7 94 50C94 74.3 74.3 94 50 94C25.7 94 6 74.3 6 50C6 25.7 25.7 6 50 6z", 58, 32);
        put("blob", "M52 10c26 0 42 14 42 38c0 26-20 42-46 42C22 90 6 74 6 50C6 26 24 10 52 10z", 58, 32);
        put("square", "M30 8h40c14 0 22 8 22 22v40c0 14-8 22-22 22H30C16 92 8 84 8 70V30C8 16 16 8 30 8z", 58, 30);
        put("pill", "M30 22h40C85.5 22 98 34.5 98 50C98 65.5 85.5 78 70 78H30C14.5 78 2 65.5 2 50C2 34.5 14.5 22 30 22z", 60, 38);
        put("triangle", "M41 14c4.5-7.5 13.5-7.5 18 0l34 58c4.5 7.8-1 16-10 16H17c-9 0-14.5-8.2-10-16z", 52, 44);
        put("hexagon", "M50 6l38 22v44L50 94L12 72V28z", 56, 34);
        put("cloud", "M30 82c-14 0-24-10-24-22c0-11 8-20 19-21c2-15 13-25 27-25c13 0 24 9 27 21c10 1 17 10 17 21c0 14-10 26-26 26z", 58, 40);
        put("drop", "M50 6c14 20 36 38 36 58c0 18-16 30-36 30S14 82 14 64C14 44 36 26 50 6z", 56, 50);
    }
    static void put(String k, String d, float ex, float ey) { SHAPES.put(k, d); EYES.put(k, new float[]{ex, ey}); }

    static final int ASLEEP = 0, AWAKE = 1, ATTENTION = 2;

    /** avatar "shape:<name>:#rrggbb"; anything else falls back to a pill in a colour from the id. */
    static Bitmap draw(String avatar, String id, int sizePx, int state, float hop) {
        String shape = "pill";
        int color = fallbackColor(id);
        if (avatar != null && avatar.startsWith("shape:")) {
            String[] p = avatar.split(":");
            if (p.length == 3 && SHAPES.containsKey(p[1])) shape = p[1];
            try { if (p.length == 3) color = Color.parseColor(p[2]); } catch (Exception ignored) { }
        }
        int h = (int) (sizePx * 1.3f);
        Bitmap bm = Bitmap.createBitmap(sizePx, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        float s = sizePx / 100f;
        float top = h - sizePx - hop * s;
        // soft shadow that shrinks while the bot is in the air
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb((int) (70 - Math.min(hop, 14) * 3), 0, 0, 0));
        float sw = (34 - Math.min(hop, 14)) * s;
        c.drawOval(new RectF(sizePx / 2f - sw, h - 7 * s, sizePx / 2f + sw, h - 1 * s), shadow);
        Path path = parse(SHAPES.get(shape));
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate(0, top);
        path.transform(m);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(color);
        c.drawPath(path, body);
        float[] e = EYES.get(shape);
        float ex = e[0] * s, ey = top + e[1] * s;
        Paint eye = new Paint(Paint.ANTI_ALIAS_FLAG);
        eye.setColor(Color.rgb(28, 25, 23));
        eye.setStrokeCap(Paint.Cap.ROUND);
        if (state == ASLEEP) {
            eye.setStrokeWidth(3.2f * s);
            c.drawLine(ex - 7 * s, ey + 2 * s, ex - 1 * s, ey + 2 * s, eye);
            c.drawLine(ex + 5 * s, ey + 2 * s, ex + 11 * s, ey + 2 * s, eye);
            Paint z = new Paint(Paint.ANTI_ALIAS_FLAG);
            z.setColor(Color.argb(200, 255, 255, 255));
            z.setTypeface(Typeface.DEFAULT_BOLD);
            z.setTextSize(16 * s);
            c.drawText("z", Math.min(sizePx - 12 * s, ex + 16 * s), Math.max(14 * s, ey - 10 * s), z);
            z.setTextSize(11 * s);
            c.drawText("z", Math.min(sizePx - 7 * s, ex + 25 * s), Math.max(7 * s, ey - 20 * s), z);
        } else {
            RectF l = new RectF(ex - 6 * s, ey - 6 * s, ex - 1 * s, ey + 6 * s);
            RectF r = new RectF(ex + 6 * s, ey - 6 * s, ex + 11 * s, ey + 6 * s);
            c.drawRoundRect(l, 2.5f * s, 2.5f * s, eye);
            c.drawRoundRect(r, 2.5f * s, 2.5f * s, eye);
            if (state == ATTENTION) {
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setColor(Color.rgb(251, 191, 36));
                c.drawCircle(sizePx - 9 * s, top + 9 * s, 8 * s, dot);
            }
        }
        return bm;
    }

    static int fallbackColor(String id) {
        int[] cs = {0xFFEF2B3C, 0xFFFF6A00, 0xFF22C55E, 0xFF1E88FF, 0xFF9B5CF6, 0xFFFF2D95, 0xFF14B8A6};
        int h = 0;
        if (id != null) for (int i = 0; i < id.length(); i++) h = h * 31 + id.charAt(i);
        return cs[Math.abs(h % cs.length)];
    }

    /** Minimal SVG path parser: M L H V C S Z, absolute and relative. */
    static Path parse(String d) {
        Path p = new Path();
        float x = 0, y = 0, sx = 0, sy = 0, cx2 = 0, cy2 = 0;
        char cmd = 'M', prev = ' ';
        int i = 0, n = d.length();
        java.util.List<Float> nums = new java.util.ArrayList<Float>();
        while (i < n) {
            char ch = d.charAt(i);
            if (Character.isLetter(ch)) { cmd = ch; i++; }
            nums.clear();
            while (i < n && !Character.isLetter(d.charAt(i))) {
                char cc = d.charAt(i);
                if (cc == ' ' || cc == ',') { i++; continue; }
                int st = i;
                if (cc == '-' || cc == '+') i++;
                boolean dot = false;
                while (i < n) {
                    char k = d.charAt(i);
                    if (Character.isDigit(k)) i++;
                    else if (k == '.' && !dot) { dot = true; i++; }
                    else break;
                }
                if (i == st) { i++; continue; }
                nums.add(Float.parseFloat(d.substring(st, i)));
            }
            boolean rel = Character.isLowerCase(cmd);
            char C = Character.toUpperCase(cmd);
            int k = 0;
            if (C == 'Z') { p.close(); x = sx; y = sy; prev = 'Z'; continue; }
            do {
                switch (C) {
                    case 'M': {
                        float nx = nums.get(k) + (rel ? x : 0), ny = nums.get(k + 1) + (rel ? y : 0);
                        if (k == 0) { p.moveTo(nx, ny); sx = nx; sy = ny; } else p.lineTo(nx, ny);
                        x = nx; y = ny; k += 2; break;
                    }
                    case 'L': { x = nums.get(k) + (rel ? x : 0); y = nums.get(k + 1) + (rel ? y : 0); p.lineTo(x, y); k += 2; break; }
                    case 'H': { x = nums.get(k) + (rel ? x : 0); p.lineTo(x, y); k += 1; break; }
                    case 'V': { y = nums.get(k) + (rel ? y : 0); p.lineTo(x, y); k += 1; break; }
                    case 'C': {
                        float ox = rel ? x : 0, oy = rel ? y : 0;
                        float x1 = nums.get(k) + ox, y1 = nums.get(k + 1) + oy;
                        cx2 = nums.get(k + 2) + ox; cy2 = nums.get(k + 3) + oy;
                        x = nums.get(k + 4) + ox; y = nums.get(k + 5) + oy;
                        p.cubicTo(x1, y1, cx2, cy2, x, y); k += 6; break;
                    }
                    case 'S': {
                        float ox = rel ? x : 0, oy = rel ? y : 0;
                        float x1 = (prev == 'C' || prev == 'S') ? 2 * x - cx2 : x, y1 = (prev == 'C' || prev == 'S') ? 2 * y - cy2 : y;
                        cx2 = nums.get(k) + ox; cy2 = nums.get(k + 1) + oy;
                        x = nums.get(k + 2) + ox; y = nums.get(k + 3) + oy;
                        p.cubicTo(x1, y1, cx2, cy2, x, y); k += 4; break;
                    }
                    default: k = nums.size();
                }
                prev = C;
            } while (k < nums.size());
        }
        return p;
    }
}
