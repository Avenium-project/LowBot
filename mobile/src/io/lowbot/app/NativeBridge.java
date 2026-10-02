package io.lowbot.app;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.lowbot.core.Router;

/**
 * The only native API visible to the web UI ({@code window.LowBotNative}).
 * Every call is refused unless the WebView currently shows the bundled app
 * origin, so a foreign page could never reach it even if it were loaded.
 */
final class NativeBridge {
    private static final String[] ALLOWED_KEYS = {"device_token"};
    private final MainActivity activity;
    private final SecureStore store;
    private static final ExecutorService API = Executors.newFixedThreadPool(4);

    NativeBridge(MainActivity activity) {
        this.activity = activity;
        this.store = new SecureStore(activity);
    }

    private boolean trusted() {
        return activity.isOnAppOrigin();
    }

    private static boolean allowedKey(String key) {
        for (String k : ALLOWED_KEYS) if (k.equals(key)) return true;
        return false;
    }

    @JavascriptInterface
    public String platform() {
        return "android";
    }

    @JavascriptInterface
    public String appVersion() {
        return BuildConfigLite.VERSION_NAME;
    }

    @JavascriptInterface
    public String secretGet(String key) {
        if (!trusted() || !allowedKey(key)) return null;
        return store.get(key);
    }

    @JavascriptInterface
    public boolean secretSet(String key, String value) {
        if (!trusted() || !allowedKey(key) || value == null || value.length() > 512) return false;
        try {
            store.put(key, value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void secretRemove(String key) {
        if (trusted() && allowedKey(key)) store.remove(key);
    }

    /** Opens an https link in the system browser (sign-in pages, docs). */
    @JavascriptInterface
    public boolean openExternal(String url) {
        if (!trusted() || url == null || !url.startsWith("https://")) return false;
        final Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.startActivity(i); }
        });
        return true;
    }

    /**
     * Opens a file in another app ("view") or the share sheet ("share"). The bytes are written to
     * cache/shared/ and handed over through FilesProvider with a one-off read grant.
     */
    @JavascriptInterface
    public String openFile(String name, String mime, String base64, String mode) {
        if (!trusted() || base64 == null) return "denied";
        try {
            String safe = (name == null || name.trim().isEmpty() ? "file" : name.trim()).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
            if (safe.length() > 120) safe = safe.substring(safe.length() - 120);
            File root = FilesProvider.root(activity);
            File[] old = root.listFiles();
            long now = System.currentTimeMillis();
            if (old != null) for (File d : old) if (now - d.lastModified() > 24 * 3600 * 1000L) { File[] fs = d.listFiles(); if (fs != null) for (File x : fs) x.delete(); d.delete(); }
            File dir = new File(root, Long.toHexString(now) + Integer.toHexString((int) (Math.random() * 0xffff)));
            if (!dir.mkdirs()) return "error";
            File f = new File(dir, safe);
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(Base64.decode(base64, Base64.DEFAULT)); } finally { out.close(); }
            Uri uri = FilesProvider.uriFor(f, activity);
            String type = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            final Intent i;
            if ("share".equals(mode)) {
                Intent send = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i = Intent.createChooser(send, safe);
            } else {
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (view.resolveActivity(activity.getPackageManager()) == null && !type.startsWith("text/")) {
                    view.setDataAndType(uri, "*/*");
                }
                i = Intent.createChooser(view, "Open " + safe);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try { activity.startActivity(i); }
                    catch (Exception e) { Toast.makeText(activity, "No app on this phone can open this file", Toast.LENGTH_SHORT).show(); }
                }
            });
            return "ok";
        } catch (Exception e) {
            return "error";
        }
    }

    /** Saves a downloaded artifact into Downloads/LowBot. */
    @JavascriptInterface
    public boolean saveFile(String name, String mime, String base64) {
        if (!trusted() || base64 == null) return false;
        String safe = (name == null ? "file" : name).replaceAll("[^A-Za-z0-9._ -]", "_");
        if (safe.length() > 100) safe = safe.substring(0, 100);
        byte[] data = Base64.decode(base64, Base64.DEFAULT);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LowBot");
                Uri uri = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                OutputStream out = activity.getContentResolver().openOutputStream(uri);
                try { out.write(data); } finally { out.close(); }
            } else {
                File dir = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LowBot");
                if (!dir.exists() && !dir.mkdirs()) return false;
                FileOutputStream out = new FileOutputStream(new File(dir, safe));
                try { out.write(data); } finally { out.close(); }
            }
            final String shown = safe;
            activity.runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(activity, "Downloads/LowBot/" + shown, Toast.LENGTH_SHORT).show();
                }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Native speech-to-text (WebView has no Web Speech API). */
    @JavascriptInterface
    public void startDictation(final String lang) {
        if (!trusted()) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.startDictation(lang); }
        });
    }

    /** The backend runs inside this app: the UI talks to it through this bridge (no network). */
    @JavascriptInterface
    public boolean isLocal() {
        return true;
    }

    /**
     * Local API call. The answer arrives as window.__lowbotResolve(id, status, type, body, isBase64, filename).
     * Body/answers never leave the phone except to the model providers you configured.
     */
    @JavascriptInterface
    public void request(final String id, final String method, final String path, final String body) {
        if (!trusted() || id == null || method == null || path == null) return;
        final Router router = LowBotApp.of(activity).router;
        API.submit(new Runnable() {
            @Override public void run() {
                Router.Response r = router.handle(method, path, body);
                boolean b64 = r.bytes != null;
                String payload = b64 ? Base64.encodeToString(r.bytes, Base64.NO_WRAP) : r.text;
                final String js = "window.__lowbotResolve&&window.__lowbotResolve(" + JSONObject.quote(id) + "," + r.status + ","
                        + JSONObject.quote(r.type) + "," + JSONObject.quote(payload == null ? "" : payload) + "," + b64 + ","
                        + JSONObject.quote(r.filename == null ? "" : r.filename) + ")";
                activity.runJs(js);
            }
        });
    }

    /** Start pushing backend events to window.__lowbotEvent (replaces the server's SSE stream). */
    @JavascriptInterface
    public void subscribe() {
        if (trusted()) activity.subscribeEvents();
    }

    /** Voice chat: speak a bot's reply with Android text-to-speech. */
    @JavascriptInterface
    public void speak(final String text, final String lang) {
        if (!trusted()) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.speak(text, lang); }
        });
    }

    @JavascriptInterface
    public void stopSpeaking() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.stopSpeaking(); }
        });
    }

    /** Text shared into LowBot from another app (Android share sheet), consumed once. */
    @JavascriptInterface
    public String consumeShare() {
        return trusted() ? activity.consumeShare() : null;
    }

    @JavascriptInterface
    public void requestNotifications() {
        if (!trusted()) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.requestNotificationPermission(); }
        });
    }

    @JavascriptInterface
    public void stopDictation() {
        if (!trusted()) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.stopDictation(); }
        });
    }

    /** Called by the UI when it has nothing left to close on Back. */
    @JavascriptInterface
    public void exitApp() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.moveTaskToBack(true); }
        });
    }
}
