package io.lowbot.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * LowBot Android shell.
 *
 * The UI is the same static web app as the browser version, served from the
 * APK's assets on the reserved origin https://appassets.androidplatform.net.
 * Only that origin is loaded inside the WebView; every other link opens in the
 * system browser. The app talks to YOUR LowBot server (HTTPS) with a per-device
 * token kept in the Android Keystore. Closing the app does not stop work on
 * the server.
 */
public class MainActivity extends Activity {
    static final String APP_HOST = "appassets.androidplatform.net";
    static final String APP_ORIGIN = "https://" + APP_HOST;
    private static final int REQ_FILE = 11;
    private static final int REQ_MIC_WEB = 12;
    private static final int REQ_MIC_DICTATE = 13;

    private WebView web;
    private volatile String currentHost = "";
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private String pendingDictationLang;
    private SpeechRecognizer recognizer;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(20, 20, 20));
        w.setNavigationBarColor(Color.rgb(20, 20, 20));

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(20, 20, 20));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(20, 20, 20));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        // Keep content clear of the status/navigation bars and the keyboard
        // (Android 15 draws edge-to-edge).
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int l, t, r, b;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                            | WindowInsets.Type.displayCutout());
                    l = i.left; t = i.top; r = i.right; b = i.bottom;
                } else {
                    l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                    r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(l, t, r, b);
                return insets;
            }
        });

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false); // target=_blank -> same view -> shouldOverrideUrlLoading
        s.setUserAgentString(s.getUserAgentString() + " LowBotAndroid/" + BuildConfigLite.VERSION_NAME);

        web.addJavascriptInterface(new NativeBridge(this), "LowBotNative");
        web.setWebViewClient(new ShellClient());
        web.setWebChromeClient(new ShellChrome());
        web.loadUrl(startUrl(getIntent()));
    }

    boolean isOnAppOrigin() {
        return APP_HOST.equals(currentHost);
    }

    private String startUrl(Intent intent) {
        String base = APP_ORIGIN + "/bots/index.html";
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "pair".equals(data.getHost())
                && ("lowbot".equals(data.getScheme()) || "opendots".equals(data.getScheme()))) {
            String server = data.getQueryParameter("server");
            String code = data.getQueryParameter("code");
            return base + "?server=" + Uri.encode(server == null ? "" : server) + "&code=" + Uri.encode(code == null ? "" : code);
        }
        return base;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) web.loadUrl(startUrl(intent));
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__lowbotBack && window.__lowbotBack()) ? 'handled' : 'no'",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (v == null || !v.contains("handled")) moveTaskToBack(true);
                    }
                });
    }

    // ------------------------------------------------------------- assets --
    private final class ShellClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            Uri u = Uri.parse(url);
            currentHost = u.getHost() == null ? "" : u.getHost();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!APP_HOST.equals(u.getHost())) return null; // network requests to the server go through normally
            String path = u.getPath() == null ? "/" : u.getPath();
            if (path.contains("..")) return notFound();
            if (path.endsWith("/")) path = path + "index.html";
            String asset = "www" + path;
            InputStream in = open(asset);
            if (in == null && !path.contains(".")) {
                asset = "www" + path + "/index.html";
                in = open(asset);
            }
            if (in == null) return notFound();
            String ext = MimeTypeMap.getFileExtensionFromUrl(asset);
            String mime = "js".equals(ext) ? "text/javascript" : "webmanifest".equals(ext) ? "application/manifest+json"
                    : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "no-cache");
            headers.put("X-Content-Type-Options", "nosniff");
            return new WebResourceResponse(mime == null ? "application/octet-stream" : mime, "utf-8", 200, "OK", headers, in);
        }

        private InputStream open(String asset) {
            try {
                return getAssets().open(asset);
            } catch (IOException e) {
                return null;
            }
        }

        private WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), null);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (APP_HOST.equals(u.getHost())) return false;
            // Everything else (sign-in pages, docs, links in answers) opens outside the app.
            if ("https".equals(u.getScheme()) || "http".equals(u.getScheme()) || "mailto".equals(u.getScheme())) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
            }
            return true;
        }
    }

    // ---------------------------------------------------- files & microphone --
    private final class ShellChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(i, "LowBot"), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            boolean audioOnly = APP_HOST.equals(request.getOrigin().getHost());
            for (String r : request.getResources()) {
                if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audioOnly = false;
            }
            if (!audioOnly) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPermission = request;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_WEB);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC_WEB && pendingPermission != null) {
            if (ok) pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingPermission.deny();
            pendingPermission = null;
        } else if (requestCode == REQ_MIC_DICTATE) {
            if (ok && pendingDictationLang != null) startDictation(pendingDictationLang);
            else dictationEvent("error", "microphone permission denied");
        }
    }

    // ----------------------------------------------------------- dictation --
    void startDictation(String lang) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingDictationLang = lang;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_DICTATE);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            dictationEvent("error", "speech recognition is not available on this device");
            return;
        }
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle results) {
                ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                dictationEvent("result", r != null && !r.isEmpty() ? r.get(0) : "");
            }
            @Override public void onError(int error) { dictationEvent("error", "speech error " + error); }
            @Override public void onReadyForSpeech(Bundle params) { dictationEvent("listening", ""); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en".equals(lang) ? "en-US" : "pl-PL");
        recognizer.startListening(i);
    }

    private void dictationEvent(String type, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("text", text);
            web.evaluateJavascript("window.dispatchEvent(new CustomEvent('lowbot:dictation',{detail:" + o + "}))", null);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        web.destroy();
        super.onDestroy();
    }
}
