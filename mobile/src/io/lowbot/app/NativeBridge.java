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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * The only native API visible to the web UI ({@code window.LowBotNative}).
 * Every call is refused unless the WebView currently shows the bundled app
 * origin, so a foreign page could never reach it even if it were loaded.
 */
final class NativeBridge {
    private static final String[] ALLOWED_KEYS = {"device_token"};
    private final MainActivity activity;
    private final SecureStore store;

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

    /** Called by the UI when it has nothing left to close on Back. */
    @JavascriptInterface
    public void exitApp() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.moveTaskToBack(true); }
        });
    }
}
