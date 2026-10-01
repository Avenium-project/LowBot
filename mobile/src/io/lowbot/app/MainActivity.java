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
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.lowbot.core.Core;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * LowBot for Android.
 *
 * The UI is the same static web app as the browser version, served from the
 * APK's assets on the reserved origin https://appassets.androidplatform.net.
 * Only that origin is loaded inside the WebView; every other link opens in the
 * system browser. The backend (database, engine, routines, tools, the bots'
 * browser) runs inside this app — see LowBotApp — and the UI reaches it through
 * the window.LowBotNative bridge, not the network.
 */
public class MainActivity extends Activity implements Computer.Host {
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
    private FrameLayout parking, takeoverContent;
    private LinearLayout takeover;
    private android.widget.EditText urlField;
    private Computer.Surface takeoverSurface;
    private TextToSpeech tts;
    private volatile String shareText;
    private Core.EventListener eventListener;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(20, 20, 20));
        w.setNavigationBarColor(Color.rgb(20, 20, 20));

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(20, 20, 20));
        // The bots' browser tabs live here, laid out behind the app UI so pages render.
        parking = new FrameLayout(this);
        root.addView(parking, new FrameLayout.LayoutParams(-1, -1));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(20, 20, 20));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildTakeover(), new FrameLayout.LayoutParams(-1, -1));
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
        handleIntent(getIntent());
        web.loadUrl(startUrl(getIntent()));
        LowBotApp.of(this).computer.attachHost(this);
    }

    // ----------------------------------------------------------- takeover --
    // ------------------------------------------------- takeover (browser control) --
    private TextView navBack, navFwd, navReload, recordPill, takeoverStatus;
    private android.widget.ProgressBar loadBar;
    private boolean recording;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private static android.graphics.drawable.GradientDrawable shape(int color, float radiusPx) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private TextView roundIcon(String glyph, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setBackground(shape(Color.rgb(42, 42, 42), dp(22)));
        t.setOnClickListener(l);
        return t;
    }

    private TextView pill(String text, int bg, int fg, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), 0, dp(18), 0);
        t.setBackground(shape(bg, dp(24)));
        t.setOnClickListener(l);
        return t;
    }

    private View buildTakeover() {
        takeover = new LinearLayout(this);
        takeover.setOrientation(LinearLayout.VERTICAL);
        takeover.setBackgroundColor(Color.rgb(20, 20, 20));
        takeover.setVisibility(View.GONE);
        takeover.setClickable(true);

        // Status line: who is in control.
        takeoverStatus = new TextView(this);
        takeoverStatus.setTextColor(Color.rgb(251, 191, 36));
        takeoverStatus.setTextSize(13);
        takeoverStatus.setGravity(Gravity.CENTER);
        takeoverStatus.setPadding(dp(16), dp(8), dp(16), dp(4));
        takeover.addView(takeoverStatus, new LinearLayout.LayoutParams(-1, -2));

        // Toolbar: back, forward, address pill, reload.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(12), dp(8));
        navBack = roundIcon("‹", new View.OnClickListener() {
            @Override public void onClick(View v) { if (takeoverSurface != null && takeoverSurface.web.canGoBack()) takeoverSurface.web.goBack(); }
        });
        navFwd = roundIcon("›", new View.OnClickListener() {
            @Override public void onClick(View v) { if (takeoverSurface != null && takeoverSurface.web.canGoForward()) takeoverSurface.web.goForward(); }
        });
        navReload = roundIcon("⟳", new View.OnClickListener() {
            @Override public void onClick(View v) { if (takeoverSurface != null) takeoverSurface.web.reload(); }
        });
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(44), dp(44));
        icon.setMargins(0, 0, dp(8), 0);
        bar.addView(navBack, icon);
        bar.addView(navFwd, icon);
        urlField = new android.widget.EditText(this);
        urlField.setSingleLine(true);
        urlField.setTextSize(15);
        urlField.setTextColor(Color.WHITE);
        urlField.setHintTextColor(Color.GRAY);
        urlField.setHint("Search or type a URL");
        urlField.setBackground(shape(Color.rgb(42, 42, 42), dp(22)));
        urlField.setPadding(dp(16), 0, dp(16), 0);
        urlField.setSelectAllOnFocus(true);
        urlField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        urlField.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT);
        urlField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                String u = v.getText().toString().trim();
                if (takeoverSurface != null && !u.isEmpty()) {
                    if (!u.contains(".") || u.contains(" ")) u = "https://duckduckgo.com/?q=" + Uri.encode(u);
                    else if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                    takeoverSurface.web.loadUrl(u);
                    takeoverSurface.web.requestFocus();
                    ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
                return true;
            }
        });
        bar.addView(urlField, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams last = new LinearLayout.LayoutParams(dp(44), dp(44));
        last.setMargins(dp(8), 0, 0, 0);
        bar.addView(navReload, last);
        takeover.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        loadBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadBar.setMax(100);
        loadBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        loadBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(20, 20, 20)));
        takeover.addView(loadBar, new LinearLayout.LayoutParams(-1, dp(3)));

        // The real page, with rounded top corners like a card.
        takeoverContent = new FrameLayout(this);
        takeoverContent.setBackground(shape(Color.WHITE, dp(18)));
        takeoverContent.setClipToOutline(true);
        LinearLayout.LayoutParams page = new LinearLayout.LayoutParams(-1, 0, 1f);
        page.setMargins(dp(8), dp(4), dp(8), 0);
        takeover.addView(takeoverContent, page);

        // Bottom dock: record to teach + give back control.
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(12), dp(10), dp(12), dp(10));
        recordPill = pill("● Record", Color.rgb(42, 42, 42), Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (takeoverSurface == null) return;
                setRecording(!recording);
                LowBotApp.of(MainActivity.this).computer.setRecording(takeoverSurface.id, recording);
            }
        });
        dock.addView(recordPill, new LinearLayout.LayoutParams(-2, dp(48)));
        View spacer = new View(this);
        dock.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView give = pill(getString(R.string.give_back), Color.WHITE, Color.BLACK, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final Computer.Surface s = takeoverSurface;
                if (s == null) { hideTakeover(); return; }
                new Thread(new Runnable() { public void run() { LowBotApp.of(MainActivity.this).computer.resume(s.id); } }).start();
            }
        });
        give.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        dock.addView(give, new LinearLayout.LayoutParams(-2, dp(48)));
        takeover.addView(dock, new LinearLayout.LayoutParams(-1, -2));
        return takeover;
    }

    private void setRecording(boolean on) {
        recording = on;
        recordPill.setText(on ? "● Recording…" : "● Record");
        recordPill.setTextColor(on ? Color.rgb(251, 113, 133) : Color.WHITE);
        recordPill.setBackground(shape(on ? Color.rgb(76, 5, 25) : Color.rgb(42, 42, 42), dp(24)));
    }

    @Override public ViewGroup surfaceParking() { return parking; }

    @Override public void pageChanged(Computer.Surface s, String url, int progress) {
        if (s != takeoverSurface) return;
        if (url != null && !urlField.hasFocus()) urlField.setText(url.startsWith("about:") ? "" : url);
        loadBar.setProgress(progress);
        loadBar.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
        navBack.setAlpha(s.web.canGoBack() ? 1f : 0.35f);
        navFwd.setAlpha(s.web.canGoForward() ? 1f : 0.35f);
    }

    @Override public void showTakeover(Computer.Surface s) {
        takeoverSurface = s;
        if (s.web.getParent() instanceof ViewGroup) ((ViewGroup) s.web.getParent()).removeView(s.web);
        ((android.content.MutableContextWrapper) s.web.getContext()).setBaseContext(this);
        takeoverContent.addView(s.web, new FrameLayout.LayoutParams(-1, -1));
        takeoverStatus.setText("● " + getString(R.string.you_control, s.botName().trim()) + " — sign in or finish the step, then give it back");
        setRecording(s.isRecording());
        pageChanged(s, s.web.getUrl(), 100);
        takeover.setVisibility(View.VISIBLE);
        takeover.bringToFront();
        takeover.setTranslationY(dp(40));
        takeover.setAlpha(0f);
        takeover.animate().translationY(0).alpha(1f).setDuration(220).start();
        if (s.web.getUrl() == null || s.web.getUrl().startsWith("about:")) {
            urlField.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(urlField, 0);
        } else s.web.requestFocus();
    }

    @Override public void hideTakeover() {
        takeover.animate().translationY(dp(40)).alpha(0f).setDuration(160).withEndAction(new Runnable() {
            @Override public void run() { takeover.setVisibility(View.GONE); }
        }).start();
        takeoverSurface = null;
        web.requestFocus();
    }

    // ------------------------------------------------------- events/share --
    void runJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override public void run() { if (web != null) web.evaluateJavascript(js, null); }
        });
    }

    void subscribeEvents() {
        if (eventListener != null) return;
        eventListener = new Core.EventListener() {
            @Override public void onEvent(JSONObject e) { runJs("window.__lowbotEvent&&window.__lowbotEvent(" + e + ")"); }
        };
        LowBotApp.of(this).uiListeners.add(eventListener);
    }

    String consumeShare() {
        String t = shareText;
        shareText = null;
        return t;
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String t = intent.getStringExtra(Intent.EXTRA_TEXT);
            String sub = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (t != null) {
                shareText = (sub != null && !t.contains(sub) ? sub + "\n" : "") + t;
                runJs("window.dispatchEvent(new Event('lowbot:share'))");
            }
        }
        String conv = intent.getStringExtra("open_conversation");
        if (conv != null) {
            try {
                runJs("window.dispatchEvent(new CustomEvent('lowbot:open',{detail:" + new JSONObject().put("conversation_id", conv) + "}))");
            } catch (Exception ignored) { }
        }
    }

    void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 14);
    }

    void speak(final String text, final String lang) {
        if (tts == null) {
            tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) speak(text, lang);
                }
            });
            return;
        }
        tts.setLanguage(java.util.Locale.US);
        tts.speak(text == null ? "" : text, TextToSpeech.QUEUE_FLUSH, null, "lowbot");
    }

    void stopSpeaking() {
        if (tts != null) tts.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LowBotApp.uiVisible = true;
    }

    @Override
    protected void onPause() {
        LowBotApp.uiVisible = false;
        super.onPause();
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
        handleIntent(intent);
        if (intent.getData() != null) web.loadUrl(startUrl(intent));
    }

    @Override
    public void onBackPressed() {
        if (takeover.getVisibility() == View.VISIBLE) {
            if (takeoverSurface != null && takeoverSurface.web.canGoBack()) takeoverSurface.web.goBack();
            return;
        }
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
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl".equals(lang) ? "pl-PL" : "en-US");
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
        if (tts != null) tts.shutdown();
        if (eventListener != null) LowBotApp.of(this).uiListeners.remove(eventListener);
        if (takeoverSurface != null) takeoverContent.removeView(takeoverSurface.web);
        LowBotApp.of(this).computer.detachHost(this);
        web.destroy();
        super.onDestroy();
    }
}
