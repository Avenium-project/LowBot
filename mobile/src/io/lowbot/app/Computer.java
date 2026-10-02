package io.lowbot.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import io.lowbot.core.ApiError;
import io.lowbot.core.Backend;
import io.lowbot.core.Core;
import io.lowbot.core.Db;
import io.lowbot.core.J;
import io.lowbot.engine.Tools;
import io.lowbot.engine.Tools.Ctx;
import io.lowbot.engine.Tools.Spec;
import io.lowbot.engine.Tools.ToolError;
import io.lowbot.tools.Builtin;
import io.lowbot.tools.Net;

/**
 * The bots' computer on a phone: a real browser (Android WebView) per bot, sharing one
 * cookie jar like Grok Bot's shared computer. Live view via screenshots; Take over shows
 * the real page full-screen so you can sign in, pass 2FA or a CAPTCHA yourself, then
 * Resume. Bots never type into password fields.
 */
public final class Computer {
    static final Pattern RISKY = Pattern.compile("(?i)(send|submit|publish|post|pay|buy|order|checkout|delete|remove|confirm|transfer|wyślij|wyslij|zapłać|zaplac|kup|zamów|zamow|usuń|usun|opublikuj|potwierdź|potwierdz|przelew)");
    static final int VIEW_W = 412, VIEW_H = 860; // CSS-ish phone viewport in dp

    /** MainActivity shows the real page for a human takeover. */
    public interface Host {
        ViewGroup surfaceParking();
        void showTakeover(Surface s);
        void hideTakeover();
        /** Page/URL/progress of a surface changed (main thread). */
        void pageChanged(Surface s, String url, int progress);
    }

    void notifyPage(Surface s, String url, int progress) {
        Host h = host;
        if (h != null) h.pageChanged(s, url, progress);
    }

    public final class Surface {
        public final String id, botId;
        public volatile WebView web;
        final List<WebView> tabs = new java.util.concurrent.CopyOnWriteArrayList<WebView>();
        final Map<Integer, String> refs = new ConcurrentHashMap<Integer, String>();
        volatile boolean busy;
        volatile CountDownLatch loading;
        volatile boolean recording;
        Surface(String id, String botId, WebView web) { this.id = id; this.botId = botId; this.web = web; tabs.add(web); }
        public String botName() {
            JSONObject b = backend.bots.get(botId);
            if (b == null) return "";
            String av = b.optString("avatar"), name = b.optString("name");
            // Shape avatars ("shape:drop:#ef2b3c") are drawn by the UI, never shown as text.
            return av.isEmpty() || av.startsWith("shape:") || av.equals("\uD83E\uDD16") ? name : av + " " + name;
        }
        public boolean isRecording() { return recording; }
    }

    static Computer instance;
    final Context app;
    final Backend backend;
    final Db db;
    final Handler main = new Handler(Looper.getMainLooper());
    final Map<String, Surface> surfaces = new ConcurrentHashMap<String, Surface>();
    volatile Host host;

    Computer(Context app, Backend backend) {
        this.app = app;
        this.backend = backend;
        this.db = backend.core.db;
        CookieManager.getInstance().setAcceptCookie(true);
    }

    static synchronized Computer get(Context ctx, Backend b) {
        if (instance == null) instance = new Computer(ctx.getApplicationContext(), b);
        return instance;
    }

    // ------------------------------------------------------------- threading
    <T> T onMain(final Callable<T> c, long timeoutMs) throws ToolError {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return c.call(); } catch (ToolError e) { throw e; } catch (Exception e) { throw new ToolError(e.getMessage()); }
        }
        final AtomicReference<T> out = new AtomicReference<T>();
        final AtomicReference<Exception> err = new AtomicReference<Exception>();
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            public void run() {
                try { out.set(c.call()); } catch (Exception e) { err.set(e); } finally { done.countDown(); }
            }
        });
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new ToolError("The browser did not respond in time.");
        } catch (InterruptedException e) {
            throw new ToolError("Interrupted.");
        }
        if (err.get() instanceof ToolError) throw (ToolError) err.get();
        if (err.get() != null) throw new ToolError(err.get().getClass().getSimpleName() + ": " + err.get().getMessage());
        return out.get();
    }

    String js(final Surface s, final String script, long timeoutMs) throws ToolError {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<String>();
        onMain(new Callable<Void>() {
            public Void call() {
                s.web.evaluateJavascript(script, new ValueCallback<String>() {
                    public void onReceiveValue(String v) { out.set(v); done.countDown(); }
                });
                return null;
            }
        }, 5000);
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new ToolError("Page script timed out.");
        } catch (InterruptedException e) {
            throw new ToolError("Interrupted.");
        }
        String v = out.get();
        if (v == null || v.equals("null")) return null;
        try {
            Object o = new org.json.JSONTokener(v).nextValue(); // evaluateJavascript returns a JSON literal
            return o instanceof String ? (String) o : String.valueOf(o);
        } catch (Exception e) {
            return v;
        }
    }

    // --------------------------------------------------------------- surfaces
    String sessionId(String botId) {
        String id = db.scalar("SELECT id FROM computer_sessions WHERE bot_id = ?", botId);
        if (id != null) return id;
        final String nid = J.id("cs");
        final String b = botId;
        db.tx(new Runnable() { public void run() {
            db.insert("computer_sessions", J.obj("id", nid, "bot_id", b, "controller", "bot:" + b, "status", "idle", "url", "",
                    "created_at", J.nowIso(), "updated_at", J.nowIso()));
        } });
        return nid;
    }

    String controller(String sid) {
        String c = db.scalar("SELECT controller FROM computer_sessions WHERE id = ?", sid);
        return c == null ? "" : c;
    }

    Surface surface(final String botId) throws ToolError {
        final String sid = sessionId(botId);
        Surface s = surfaces.get(sid);
        if (s != null) return s;
        if (surfaces.size() >= backend.core.settings.maxActiveSurfaces) closeOldest();
        return onMain(new Callable<Surface>() {
            public Surface call() {
                Surface existing = surfaces.get(sid);
                if (existing != null) return existing;
                Surface ns = new Surface(sid, botId, newWebView());
                configure(ns, ns.web);
                park(ns);
                surfaces.put(sid, ns);
                return ns;
            }
        }, 10000);
    }

    void configure(final Surface s, WebView w) {
        w.setWebViewClient(new SurfaceClient(s));
        w.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { if (v == s.web) notifyPage(s, v.getUrl(), p); }
        });
        w.addJavascriptInterface(new Recorder(s), "LowBotRec");
    }

    // ------------------------------------------------------------------ tabs
    static final int MAX_TABS = 5;

    JSONObject tabs(final Surface s) throws ToolError {
        return onMain(new Callable<JSONObject>() { public JSONObject call() {
            JSONArray a = new JSONArray();
            for (int i = 0; i < s.tabs.size(); i++) {
                WebView w = s.tabs.get(i);
                a.put(J.obj("index", i, "title", J.truncate(w.getTitle() == null ? "" : w.getTitle(), 80), "url", w.getUrl() == null ? "about:blank" : w.getUrl(), "active", w == s.web));
            }
            return J.obj("tabs", a);
        } }, 5000);
    }

    void switchTab(final Surface s, final int index) throws ToolError {
        if (index < 0 || index >= s.tabs.size()) throw new ToolError("No tab " + index + " (see browser.tabs list).");
        onMain(new Callable<Void>() { public Void call() {
            WebView w = s.tabs.get(index);
            if (w == s.web) return null;
            if (s.web.getParent() instanceof ViewGroup) ((ViewGroup) s.web.getParent()).removeView(s.web);
            s.web = w;
            s.refs.clear();
            park(s);
            return null;
        } }, 5000);
    }

    void newTab(final Surface s) throws ToolError {
        if (s.tabs.size() >= MAX_TABS) throw new ToolError("Too many tabs (" + MAX_TABS + "). Close one first.");
        onMain(new Callable<Void>() { public Void call() {
            WebView w = newWebView();
            configure(s, w);
            s.tabs.add(w);
            return null;
        } }, 5000);
        switchTab(s, s.tabs.size() - 1);
    }

    void closeTab(final Surface s, final int index) throws ToolError {
        if (s.tabs.size() <= 1) throw new ToolError("This is the only tab.");
        if (index < 0 || index >= s.tabs.size()) throw new ToolError("No tab " + index + ".");
        final WebView w = s.tabs.get(index);
        if (w == s.web) switchTab(s, index == 0 ? 1 : index - 1);
        s.tabs.remove(w);
        main.post(new Runnable() { public void run() {
            if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
            w.destroy();
        } });
    }

    void closeOldest() {
        String oldest = null;
        for (Surface s : surfaces.values()) if (!s.busy && !controller(s.id).startsWith("human:")) { oldest = s.id; break; }
        if (oldest != null) close(oldest);
    }

    public void close(final String sid) {
        final Surface s = surfaces.remove(sid);
        if (s == null) return;
        main.post(new Runnable() { public void run() {
            for (WebView w : s.tabs) {
                if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
                w.destroy();
            }
        } });
    }

    @SuppressLint("SetJavaScriptEnabled")
    WebView newWebView() {
        WebView w = new WebView(new MutableContextWrapper(app));
        WebSettings st = w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        st.setSupportMultipleWindows(false);
        st.setJavaScriptCanOpenWindowsAutomatically(false);
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(Color.WHITE);
        return w;
    }

    /** Keep the page laid out (behind the app UI when it is open, detached otherwise). */
    void park(Surface s) {
        if (s.web.getParent() instanceof ViewGroup) ((ViewGroup) s.web.getParent()).removeView(s.web);
        Host h = host;
        float d = app.getResources().getDisplayMetrics().density;
        int w = (int) (VIEW_W * d), hgt = (int) (VIEW_H * d);
        if (h != null && h.surfaceParking() != null) {
            ((MutableContextWrapper) s.web.getContext()).setBaseContext(h.surfaceParking().getContext());
            h.surfaceParking().addView(s.web, new ViewGroup.LayoutParams(w, hgt));
        } else {
            s.web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hgt, View.MeasureSpec.EXACTLY));
            s.web.layout(0, 0, w, hgt);
        }
    }

    /** Called when MainActivity (re)creates its layout. */
    void attachHost(final Host h) {
        host = h;
        main.post(new Runnable() { public void run() { for (Surface s : surfaces.values()) park(s); } });
    }

    void detachHost(Host h) {
        if (host != h) return;
        host = null;
        for (Surface s : surfaces.values()) {
            if (s.web.getParent() instanceof ViewGroup) ((ViewGroup) s.web.getParent()).removeView(s.web);
            ((MutableContextWrapper) s.web.getContext()).setBaseContext(app);
            park(s);
        }
    }

    final class SurfaceClient extends WebViewClient {
        final Surface s;
        SurfaceClient(Surface s) { this.s = s; }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            if (!scheme.equals("http") && !scheme.equals("https")) return true; // no intent:, file:, javascript: etc.
            return false;
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            notifyPage(s, url, 5);
        }

        @Override public void onPageFinished(WebView view, String url) {
            notifyPage(s, url, 100);
            CountDownLatch l = s.loading;
            if (l != null) l.countDown();
            if (s.recording) view.evaluateJavascript(RECORD_JS, null);
            final String u = url;
            new Thread(new Runnable() { public void run() {
                db.tx(new Runnable() { public void run() {
                    db.exec("UPDATE computer_sessions SET url = ?, updated_at = ? WHERE id = ?", J.truncate(u, 500), J.nowIso(), s.id);
                } });
            } }).start();
        }
    }

    /** Teach a task: records what the human does while they control the page. */
    final class Recorder {
        final Surface s;
        Recorder(Surface s) { this.s = s; }

        @JavascriptInterface
        public void record(String json) {
            if (!s.recording || json == null || json.length() > 4000) return;
            final JSONObject a = J.parse(json);
            if (a.length() == 0) return;
            db.tx(new Runnable() { public void run() {
                JSONArray rec = J.parseArr(db.scalar("SELECT recording_json FROM computer_sessions WHERE id = ?", s.id));
                if (rec.length() < 200) rec.put(a);
                db.exec("UPDATE computer_sessions SET recording_json = ? WHERE id = ?", rec.toString(), s.id);
            } });
        }
    }

    static final String RECORD_JS = "(function(){if(window.__lbRec)return;window.__lbRec=1;"
            + "function lab(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.name||e.id||e.tagName)+'').trim().slice(0,80);}"
            + "if(window.LowBotRec)LowBotRec.record(JSON.stringify({tool:'browser.navigate',arguments:{url:location.href}}));"
            + "document.addEventListener('click',function(ev){var e=ev.target.closest('a,button,input,select,textarea,[role=button],[onclick]');if(!e)return;"
            + "LowBotRec.record(JSON.stringify({tool:'browser.click',arguments:{label:lab(e)}}));},true);"
            + "document.addEventListener('change',function(ev){var e=ev.target;if(!e||!('value' in e))return;var pw=(e.type==='password');"
            + "LowBotRec.record(JSON.stringify({tool:'browser.type',arguments:{label:lab(e),text:pw?'(password — the user types it)':String(e.value).slice(0,200)}}));},true);})();";

    // browser-use style page reading: numbered interactive elements of the visible viewport (topmost only),
    // scroll position, page text; an overlay draws the same numbers on screenshots.
    static final String READ_JS = "(function(max, all){ var SEL='a[href],button,input:not([type=hidden]),select,textarea,summary,label[for],[role=button],[role=link],[role=tab],[role=menuitem],[role=menuitemcheckbox],[role=checkbox],[role=radio],[role=switch],[role=option],[role=combobox],[role=textbox],[role=searchbox],[onclick],[contenteditable=true],[tabindex]:not([tabindex=\"-1\"]),[aria-haspopup]'; document.querySelectorAll('[data-lb-ref]').forEach(function(e){e.removeAttribute('data-lb-ref');}); var vh=window.innerHeight, vw=window.innerWidth, set=[], seen=new Set(); document.querySelectorAll(SEL).forEach(function(e){ if(!seen.has(e)){seen.add(e);set.push(e);} }); var extra=document.querySelectorAll('div,span,li,img,svg,td'); for(var x=0;x<extra.length&&x<4000;x++){var e=extra[x]; if(seen.has(e))continue; try{ if(getComputedStyle(e).cursor==='pointer' && !(e.parentElement&&getComputedStyle(e.parentElement).cursor==='pointer')){seen.add(e);set.push(e);} }catch(_){} } set.sort(function(a,b){var p=a.compareDocumentPosition(b);return p&Node.DOCUMENT_POSITION_FOLLOWING?-1:1;}); function label(e){ var t=(e.getAttribute('aria-label')||e.innerText||e.value||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||e.name||'')+''; if(!t.trim()&&e.id){var l=document.querySelector('label[for=\"'+e.id+'\"]');if(l)t=l.innerText;} if(!t.trim()){var img=e.querySelector&&e.querySelector('img[alt],svg[aria-label]');if(img)t=img.getAttribute('alt')||img.getAttribute('aria-label')||'';} return t.replace(/\\s+/g,' ').trim().slice(0,90); } var out=[], i=0, below=0; for(var k=0;k<set.length&&out.length<max;k++){ var e=set[k], r=e.getBoundingClientRect(); if(r.width<3||r.height<3)continue; var st=getComputedStyle(e); if(st.visibility==='hidden'||st.display==='none'||+st.opacity===0)continue; var inView=r.bottom>0&&r.top<vh&&r.right>0&&r.left<vw; if(!inView){ if(r.top>=vh) below++; if(!all) continue; } if(inView){ var cx=Math.min(vw-1,Math.max(0,r.left+r.width/2)), cy=Math.min(vh-1,Math.max(0,r.top+r.height/2)); var top=document.elementFromPoint(cx,cy); if(top&&top!==e&&!e.contains(top)&&!top.contains(e)) continue; } i++; e.setAttribute('data-lb-ref',i); var tag=e.tagName.toLowerCase(), ty=(e.getAttribute('type')||'').toLowerCase(), role=e.getAttribute('role')||'', attrs=''; if(ty)attrs+=' type='+ty; if(role)attrs+=' role='+role; var lb=label(e); if(ty==='password')lb='(password field \u2014 never type into it)'; if(tag==='input'||tag==='textarea'){ if(e.placeholder)attrs+=' placeholder=\"'+e.placeholder.slice(0,40)+'\"'; if(ty==='checkbox'||ty==='radio')attrs+=e.checked?' checked':' unchecked'; else if(ty!=='password'&&e.value)attrs+=' value=\"'+String(e.value).slice(0,60)+'\"'; } if(tag==='select'){ var o=e.options[e.selectedIndex]; attrs+=' selected=\"'+(o?o.text.slice(0,40):'')+'\" options='+e.options.length; lb=''; } if(e.getAttribute('aria-expanded'))attrs+=' expanded='+e.getAttribute('aria-expanded'); if(e.disabled)attrs+=' disabled'; var line='['+i+']<'+tag+attrs+'>'+lb+(tag==='a'&&e.href?' -> '+String(e.href).slice(0,100):''); if(!inView) line+=' (off-screen)'; out.push(line); } var se=document.scrollingElement||document.documentElement, above=Math.round(se.scrollTop), rest=Math.max(0,Math.round(se.scrollHeight-se.scrollTop-vh)); var txt=document.body?document.body.innerText:''; return JSON.stringify({url:location.href,title:document.title, scroll:{pixels_above:above,pixels_below:rest,hint:rest>50?'More below \u2014 scroll down to see more elements.':'At the bottom of the page.'}, elements:out,text:txt.slice(0,5000),truncated:txt.length>5000}); })";
    static final String HIGHLIGHT_JS = "(function(on){ var old=document.getElementById('__lb_hl'); if(old)old.remove(); if(!on)return 'off'; var box=document.createElement('div'); box.id='__lb_hl'; box.style.cssText='position:fixed;inset:0;pointer-events:none;z-index:2147483647'; var colors=['#FF2D55','#00A2FF','#34C759','#FF9500','#AF52DE','#FFCC00','#00C7BE','#FF3B30']; document.querySelectorAll('[data-lb-ref]').forEach(function(e){ var r=e.getBoundingClientRect(); if(r.bottom<0||r.top>innerHeight||r.width<3)return; var n=+e.getAttribute('data-lb-ref'), c=colors[n%colors.length]; var b=document.createElement('div'); b.style.cssText='position:fixed;left:'+r.left+'px;top:'+r.top+'px;width:'+r.width+'px;height:'+r.height+'px;border:2px solid '+c+';box-sizing:border-box;border-radius:3px'; var t=document.createElement('div'); t.textContent=n; t.style.cssText='position:absolute;right:-2px;top:-2px;transform:translateY(-100%);background:'+c+';color:#fff;font:bold 11px sans-serif;padding:0 3px;border-radius:3px;line-height:15px'; if(r.top<16)t.style.transform='none'; b.appendChild(t); box.appendChild(b); }); document.documentElement.appendChild(box); return 'on'; })";
    static final String EXTRACT_JS = "(function(max){ var out=[], len=0; function push(s){ if(len>max)return; out.push(s); len+=s.length; } function text(n){ return (n.innerText||n.textContent||'').replace(/\\s+/g,' ').trim(); } function walk(n){ if(len>max||!n)return; if(n.nodeType===3){ var t=n.textContent.replace(/\\s+/g,' '); if(t.trim())push(t); return; } if(n.nodeType!==1)return; var tag=n.tagName.toLowerCase(); if(['script','style','noscript','svg','template','iframe','select','option','textarea','input'].indexOf(tag)>=0)return; var st=getComputedStyle(n); if(st.display==='none'||st.visibility==='hidden')return; if(/^h[1-6]$/.test(tag)){ push('\\n\\n'+'#'.repeat(+tag[1])+' '+text(n)+'\\n'); return; } if(tag==='a'&&n.href){ var t2=text(n); if(t2)push('['+t2+']('+n.href+')'); return; } if(tag==='img'){ if(n.alt)push('![' + n.alt + ']'); return; } if(tag==='li'){ push('\\n- '); for(var c=n.firstChild;c;c=c.nextSibling)walk(c); return; } if(tag==='br'){ push('\\n'); return; } if(tag==='table'){ push('\\n'); var rows=n.querySelectorAll('tr'); for(var r=0;r<rows.length&&r<80;r++){ var cells=rows[r].querySelectorAll('th,td'), a=[]; for(var c2=0;c2<cells.length;c2++)a.push(text(cells[c2]).slice(0,80)); push('| '+a.join(' | ')+' |\\n'); if(r===0)push('|'+a.map(function(){return '---';}).join('|')+'|\\n'); } return; } var block=['p','div','section','article','header','footer','main','aside','nav','form','ul','ol','pre','blockquote','tr','dl','dt','dd','figure'].indexOf(tag)>=0; if(block)push('\\n'); for(var ch=n.firstChild;ch;ch=ch.nextSibling)walk(ch); if(block)push('\\n'); } walk(document.body); var md=out.join('').replace(/[ \\t]+\\n/g,'\\n').replace(/\\n{3,}/g,'\\n\\n').trim(); return JSON.stringify({url:location.href,title:document.title,markdown:md.slice(0,max),truncated:md.length>max}); })";

    JSONObject readPage(Surface s, int max, boolean all) throws ToolError {
        JSONObject r = J.parse(js(s, READ_JS + "(" + Math.max(10, Math.min(300, max)) + "," + all + ")", 12000));
        s.refs.clear();
        JSONArray el = r.optJSONArray("elements");
        if (el != null) for (int i = 0; i < el.length(); i++) {
            String line = el.optString(i);
            int c = line.indexOf(']');
            if (line.startsWith("[") && c > 1) try { s.refs.put(Integer.parseInt(line.substring(1, c)), line.substring(c + 1).trim()); } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    JSONObject state(Surface s) throws ToolError {
        String v = js(s, "JSON.stringify({url:location.href,title:document.title,text_excerpt:(document.body?document.body.innerText:'').slice(0,1500)})", 8000);
        return J.parse(v);
    }

    Object act(Ctx ctx, ActFn fn) throws Exception {
        Surface s = surface(ctx.bot.optString("id"));
        synchronized (s) {
            if (!controller(s.id).equals("bot:" + ctx.bot.optString("id")))
                return new Tools.Wait("input", J.obj("reason", "takeover", "surface_id", s.id, "question", "A person has taken over this computer. Waiting for Resume."));
            s.busy = true;
            try {
                return fn.run(s);
            } finally {
                s.busy = false;
                final Surface fs = s;
                final Ctx fc = ctx;
                db.tx(new Runnable() { public void run() {
                    db.exec("UPDATE computer_sessions SET status = 'open', updated_at = ? WHERE id = ?", J.nowIso(), fs.id);
                    backend.core.emit("computer.action", null, fc.task.optString("id"), null, fc.bot.optString("id"), J.obj("surface_id", fs.id));
                } });
            }
        }
    }

    interface ActFn { Object run(Surface s) throws Exception; }

    void navigate(final Surface s, final String url) throws ToolError {
        try {
            Net.vet(url, backend.core.settings.allowPrivateNetwork);
        } catch (Net.Denied e) {
            throw new ToolError("Blocked by egress policy: " + e.getMessage());
        }
        s.loading = new CountDownLatch(1);
        onMain(new Callable<Void>() { public Void call() { s.web.loadUrl(url); return null; } }, 5000);
        try { s.loading.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new ToolError("Interrupted."); }
        String finalUrl = onMain(new Callable<String>() { public String call() { return s.web.getUrl(); } }, 5000);
        if (finalUrl != null && finalUrl.startsWith("http")) {
            try {
                Net.vet(finalUrl, backend.core.settings.allowPrivateNetwork);
            } catch (Net.Denied e) {
                onMain(new Callable<Void>() { public Void call() { s.web.loadUrl("about:blank"); return null; } }, 5000);
                throw new ToolError("Navigation ended on a blocked address: " + e.getMessage());
            }
        }
    }

    void key(final Surface s, final int code) throws ToolError {
        onMain(new Callable<Void>() { public Void call() {
            long t = SystemClock.uptimeMillis();
            s.web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            s.web.dispatchKeyEvent(new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, code, 0));
            return null;
        } }, 5000);
    }

    static int keyCode(String k) {
        String n = k == null ? "" : k.trim().toLowerCase();
        if (n.equals("enter") || n.equals("return")) return KeyEvent.KEYCODE_ENTER;
        if (n.equals("tab")) return KeyEvent.KEYCODE_TAB;
        if (n.equals("escape") || n.equals("esc")) return KeyEvent.KEYCODE_ESCAPE;
        if (n.equals("backspace")) return KeyEvent.KEYCODE_DEL;
        if (n.equals("delete")) return KeyEvent.KEYCODE_FORWARD_DEL;
        if (n.equals("arrowdown") || n.equals("down")) return KeyEvent.KEYCODE_DPAD_DOWN;
        if (n.equals("arrowup") || n.equals("up")) return KeyEvent.KEYCODE_DPAD_UP;
        if (n.equals("arrowleft") || n.equals("left")) return KeyEvent.KEYCODE_DPAD_LEFT;
        if (n.equals("arrowright") || n.equals("right")) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if (n.equals("pagedown")) return KeyEvent.KEYCODE_PAGE_DOWN;
        if (n.equals("pageup")) return KeyEvent.KEYCODE_PAGE_UP;
        if (n.equals("space")) return KeyEvent.KEYCODE_SPACE;
        return -1;
    }

    public byte[] screenshot(String sid) throws ToolError {
        final Surface s = surfaces.get(sid);
        if (s == null) throw new ToolError("Surface is not open.");
        return onMain(new Callable<byte[]>() { public byte[] call() throws ToolError {
            int w = s.web.getWidth(), h = s.web.getHeight();
            if (w <= 0 || h <= 0) throw new ToolError("The page is not laid out yet.");
            float scale = Math.min(1f, 720f / w);
            Bitmap bm = Bitmap.createBitmap((int) (w * scale), (int) (h * scale), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            c.scale(scale, scale);
            s.web.draw(c);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 60, bo);
            bm.recycle();
            return bo.toByteArray();
        } }, 8000);
    }

    // ---------------------------------------------------------------- takeover
    public JSONObject takeOver(final String sid) {
        final Surface s = surfaces.get(sid);
        db.tx(new Runnable() { public void run() {
            if (db.change("UPDATE computer_sessions SET controller = ?, lock_version = lock_version + 1, status = 'taken_over', recording_json = NULL, updated_at = ? WHERE id = ?",
                    "human:" + Core.OWNER, J.nowIso(), sid) != 1) throw new ApiError(404, "Unknown surface.");
            JSONObject row = db.one("SELECT * FROM computer_sessions WHERE id = ?", sid);
            backend.core.emit("computer.taken_over", null, null, null, row.optString("bot_id"), J.obj("surface_id", sid));
            backend.core.audit("computer.takeover", "user", Core.OWNER, null, null, null, null, null, J.obj("surface_id", sid));
        } });
        if (s != null) main.post(new Runnable() { public void run() { Host h = host; if (h != null) h.showTakeover(s); } });
        return db.one("SELECT * FROM computer_sessions WHERE id = ?", sid);
    }

    /** Open the computer of a bot (creates the surface) and take it over: used by the monitor button and "Log in for me". */
    public JSONObject openForHuman(String botId) {
        try {
            Surface s = surface(botId);
            return takeOver(s.id);
        } catch (ToolError e) {
            throw new ApiError(409, e.getMessage());
        }
    }

    public void setRecording(String sid, boolean on) {
        final Surface s = surfaces.get(sid);
        if (s == null) return;
        s.recording = on;
        if (on) main.post(new Runnable() { public void run() { s.web.evaluateJavascript(RECORD_JS, null); } });
    }

    public JSONObject resume(final String sid) {
        final Surface s = surfaces.get(sid);
        if (s != null) s.recording = false;
        db.tx(new Runnable() { public void run() {
            JSONObject row = db.one("SELECT * FROM computer_sessions WHERE id = ?", sid);
            if (row == null) throw new ApiError(404, "Unknown surface.");
            db.exec("UPDATE computer_sessions SET controller = ?, lock_version = lock_version + 1, status = 'open', updated_at = ? WHERE id = ?",
                    "bot:" + row.optString("bot_id"), J.nowIso(), sid);
            for (JSONObject run : db.all("SELECT * FROM runs WHERE status = 'waiting_input' AND bot_id = ?", row.optString("bot_id"))) {
                JSONObject w = J.parse(run.optString("waiting_json"));
                if ("takeover".equals(w.optString("reason")) && sid.equals(w.optString("surface_id"))) {
                    db.exec("UPDATE run_steps SET status = 'completed', output_json = ?, updated_at = ? WHERE id = ? AND status = 'waiting'",
                            J.obj("note", "The user took over the computer and returned control. The page may have changed: read it again before acting.").toString(),
                            J.nowIso(), w.optString("step_id"));
                    db.exec("UPDATE runs SET status = 'queued', waiting_json = '{}', updated_at = ? WHERE id = ?", J.nowIso(), run.optString("id"));
                }
            }
            backend.core.emit("computer.resumed", null, null, null, row.optString("bot_id"), J.obj("surface_id", sid));
            backend.core.audit("computer.resume", "user", Core.OWNER, null, null, null, null, null, J.obj("surface_id", sid));
        } });
        main.post(new Runnable() { public void run() {
            Host h = host;
            if (h != null) h.hideTakeover();
            if (s != null) park(s);
        } });
        backend.wake();
        return db.one("SELECT * FROM computer_sessions WHERE id = ?", sid);
    }

    public JSONObject listSurfaces() {
        List<JSONObject> rows = db.all("SELECT * FROM computer_sessions ORDER BY updated_at DESC");
        JSONArray out = new JSONArray();
        for (JSONObject r : rows) {
            Surface s = surfaces.get(r.optString("id"));
            J.put(r, "live", s != null);
            J.put(r, "busy", s != null && s.busy);
            J.put(r, "mode", "phone browser");
            J.put(r, "recording", s != null && s.recording);
            J.put(r, "recorded_steps", J.parseArr(r.optString("recording_json")).length());
            r.remove("recording_json");
            out.put(r);
        }
        return J.obj("kind", "phone", "surfaces", out, "max_active_surfaces", backend.core.settings.maxActiveSurfaces);
    }

    public void reset() {
        for (String id : new ArrayList<String>(surfaces.keySet())) close(id);
        main.post(new Runnable() { public void run() { CookieManager.getInstance().removeAllCookies(null); } });
    }

    // ------------------------------------------------------------------- tools
    void register(Tools reg) {
        final JSONObject S = Tools.S, I = J.obj("type", "integer"), N = J.obj("type", "number");
        reg.register(new Spec("browser.navigate", "Open a URL in your browser tab (the phone's browser) and return the page state.",
                Tools.obj(J.obj("url", S), "url"), Tools.READ, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    navigate(s, a.optString("url"));
                    return state(s);
                } });
            }
        }).needs("browser").timeout(60).card(card("Open")));
        reg.register(new Spec("browser.read", "Read the page like browser-use: numbered interactive elements in the visible part of the page "
                + "([12]<button>Sign in</button>), scroll position (pixels above/below) and the page text. Use the numbers as ref in click/type/select. "
                + "Elements below the fold appear after browser.scroll; include_offscreen lists them too.",
                Tools.obj(J.obj("max_elements", I, "include_offscreen", J.obj("type", "boolean"))), Tools.READ, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    return readPage(s, a.optInt("max_elements", 150), a.optBoolean("include_offscreen"));
                } });
            }
        }).needs("browser"));
        reg.register(new Spec("browser.extract", "Get the whole page as Markdown (headings, links, lists, tables) to find or extract information without clicking.",
                Tools.obj(J.obj("max_chars", I)), Tools.READ, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    return J.parse(js(s, EXTRACT_JS + "(" + Math.max(1000, Math.min(30000, a.optInt("max_chars", 15000))) + ")", 15000));
                } });
            }
        }).needs("browser"));
        reg.register(new Spec("browser.select", "Choose an option in a dropdown (<select>) by ref and the option's visible text or value.",
                Tools.obj(J.obj("ref", I, "option", S), "ref", "option"), Tools.EXTERNAL, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    String r = js(s, "(function(v){var e=document.querySelector('[data-lb-ref=\"" + a.optInt("ref") + "\"]');if(!e)return 'missing';"
                            + "if(e.tagName!=='SELECT')return 'notselect';var lv=v.toLowerCase(),hit=-1;for(var i=0;i<e.options.length;i++){var o=e.options[i];"
                            + "if(o.text.trim().toLowerCase()===lv||o.value.toLowerCase()===lv){hit=i;break;}if(hit<0&&o.text.toLowerCase().indexOf(lv)>=0)hit=i;}"
                            + "if(hit<0)return 'nooption:'+Array.prototype.map.call(e.options,function(o){return o.text.trim();}).slice(0,30).join(' | ');"
                            + "e.selectedIndex=hit;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok:'+e.options[hit].text.trim();})("
                            + JSONObject.quote(a.optString("option")) + ")", 8000);
                    if ("missing".equals(r)) throw new ToolError("Element ref " + a.optInt("ref") + " not found; call browser.read again.");
                    if ("notselect".equals(r)) throw new ToolError("Element " + a.optInt("ref") + " is not a <select>. For a custom dropdown, click it and then click the option.");
                    if (r.startsWith("nooption:")) throw new ToolError("No such option. Options: " + r.substring(9));
                    Thread.sleep(400);
                    return J.obj("selected", r.substring(3), "page", state(s));
                } });
            }
        }).needs("browser").card(card("Choose in")));
        reg.register(new Spec("browser.wait", "Wait up to 10 seconds for the page to finish loading or change.",
                Tools.obj(J.obj("seconds", N)), Tools.READ, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    Thread.sleep((long) (Math.max(0.5, Math.min(10, a.optDouble("seconds", 2))) * 1000));
                    return state(s);
                } });
            }
        }).needs("browser"));
        reg.register(new Spec("browser.tabs", "Tabs of your browser: action list | new (optionally open url) | switch (index) | close (index). Up to 5 tabs.",
                Tools.obj(J.obj("action", J.obj("type", "string", "enum", new JSONArray().put("list").put("new").put("switch").put("close")), "url", S, "index", I), "action"),
                Tools.READ, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    String act = a.optString("action");
                    if ("new".equals(act)) { newTab(s); if (!a.optString("url").isEmpty()) navigate(s, a.optString("url")); }
                    else if ("switch".equals(act)) switchTab(s, a.optInt("index", -1));
                    else if ("close".equals(act)) closeTab(s, a.optInt("index", -1));
                    JSONObject t = tabs(s);
                    if (!"list".equals(act)) J.put(t, "page", state(s));
                    return t;
                } });
            }
        }).needs("browser"));
        reg.register(new Spec("browser.click", "Click an element by ref (from browser.read), or x/y viewport coordinates in CSS pixels. Returns the resulting page state; verify it.",
                Tools.obj(J.obj("ref", I, "x", N, "y", N)), Tools.EXTERNAL, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(final Surface s) throws Exception {
                    if (a.has("ref")) {
                        String r = js(s, "(function(){var e=document.querySelector('[data-lb-ref=\"" + a.optInt("ref") + "\"]');if(!e)return 'missing';"
                                + "e.scrollIntoView({block:'center'});e.focus&&e.focus();e.click();return 'ok';})()", 8000);
                        if (!"ok".equals(r)) throw new ToolError("Element ref " + a.optInt("ref") + " not found; call browser.read again.");
                    } else if (a.has("x") && a.has("y")) {
                        final float d = app.getResources().getDisplayMetrics().density;
                        onMain(new Callable<Void>() { public Void call() {
                            long t = SystemClock.uptimeMillis();
                            float x = (float) a.optDouble("x") * d, y = (float) a.optDouble("y") * d;
                            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
                            MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
                            s.web.dispatchTouchEvent(down); s.web.dispatchTouchEvent(up);
                            down.recycle(); up.recycle();
                            return null;
                        } }, 5000);
                    } else throw new ToolError("Provide ref (from browser.read) or x and y.");
                    Thread.sleep(700);
                    return state(s);
                } });
            }
        }).needs("browser").card(card("Click")).escalate(new Tools.Escalate() {
            public String check(Ctx ctx, JSONObject a) {
                Surface s = surfaces.get(sessionId(ctx.bot.optString("id")));
                String label = s != null && a.has("ref") ? s.refs.get(a.optInt("ref")) : null;
                if (label != null && RISKY.matcher(label).find()) return "Clicking '" + J.truncate(label, 80) + "' may send, publish, pay or delete.";
                return null;
            }
        }));
        reg.register(new Spec("browser.type", "Fill a text field by ref; submit=true presses Enter afterwards. Never used for passwords: ask the user to take over instead. "
                + "{{secret:NAME}} placeholders are filled from the secure vault.",
                Tools.obj(J.obj("ref", I, "text", S, "submit", J.obj("type", "boolean")), "ref", "text"), Tools.EXTERNAL, "allow", new Tools.Executor() {
            public Object run(final Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    String text = Builtin.fillSecrets(ctx, a.optString("text"));
                    String r = js(s, "(function(v){var e=document.querySelector('[data-lb-ref=\"" + a.optInt("ref") + "\"]');if(!e)return 'missing';"
                            + "if(e.type==='password')return 'password';e.scrollIntoView({block:'center'});e.focus();"
                            + "if(e.isContentEditable){document.execCommand('selectAll',false,null);document.execCommand('insertText',false,v);return 'ok';}"
                            + "var p=Object.getPrototypeOf(e),d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;"
                            + "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';})(" + JSONObject.quote(text) + ")", 8000);
                    if ("password".equals(r)) throw new ToolError("This is a password field. Bots never type passwords: call browser.request_takeover so the user signs in.");
                    if (!"ok".equals(r)) throw new ToolError("Element ref " + a.optInt("ref") + " not found; call browser.read again.");
                    if (a.optBoolean("submit")) { key(s, KeyEvent.KEYCODE_ENTER); Thread.sleep(1200); }
                    return state(s);
                } });
            }
        }).needs("browser").card(card("Type into")).escalate(new Tools.Escalate() {
            public String check(Ctx ctx, JSONObject a) { return a.optBoolean("submit") ? "Typing and submitting a form may send data." : null; }
        }));
        reg.register(new Spec("browser.press", "Press a key: Enter, Tab, Escape, Backspace, ArrowDown/Up/Left/Right, PageDown/PageUp, Space.",
                Tools.obj(J.obj("key", S), "key"), Tools.EXTERNAL, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    int code = keyCode(a.optString("key"));
                    if (code < 0) throw new ToolError("Unsupported key.");
                    key(s, code);
                    Thread.sleep(500);
                    return state(s);
                } });
            }
        }).needs("browser").card(card("Press")).escalate(new Tools.Escalate() {
            public String check(Ctx ctx, JSONObject a) { return keyCode(a.optString("key")) == KeyEvent.KEYCODE_ENTER ? "Pressing Enter may submit a form." : null; }
        }));
        reg.register(new Spec("browser.scroll", "Scroll: direction down/up by pages (default 1 screen), or dy CSS pixels, or to the first place showing text.",
                Tools.obj(J.obj("direction", J.obj("type", "string", "enum", new JSONArray().put("down").put("up")), "pages", N, "dy", N, "text", S)), Tools.READ, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    if (!a.optString("text").isEmpty()) {
                        String r = js(s, "(function(q){q=q.toLowerCase();var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),n;"
                                + "while((n=w.nextNode())){if(n.textContent.toLowerCase().indexOf(q)>=0&&n.parentElement&&n.parentElement.getClientRects().length){"
                                + "n.parentElement.scrollIntoView({block:'center'});return 'ok';}}return 'none';})(" + JSONObject.quote(a.optString("text")) + ")", 8000);
                        if (!"ok".equals(r)) throw new ToolError("Text not found on the page.");
                    } else {
                        String dy = a.has("dy") ? String.valueOf(a.optDouble("dy"))
                                : ("up".equals(a.optString("direction")) ? "-" : "") + "window.innerHeight*0.85*" + Math.max(0.1, Math.min(10, a.optDouble("pages", 1)));
                        js(s, "window.scrollBy(0," + dy + ");'ok'", 5000);
                    }
                    Thread.sleep(300);
                    return readPage(s, 150, false);
                } });
            }
        }).needs("browser"));
        reg.register(new Spec("browser.back", "Go back to the previous page.", Tools.obj(new JSONObject()), Tools.READ, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(final Surface s) throws Exception {
                    onMain(new Callable<Void>() { public Void call() { if (s.web.canGoBack()) s.web.goBack(); return null; } }, 5000);
                    Thread.sleep(1000);
                    return state(s);
                } });
            }
        }).needs("browser"));
        reg.register(new Spec("browser.screenshot", "Screenshot of your tab with the interactive elements outlined and numbered (same numbers as browser.read). "
                + "You SEE the image in the next message (if the model supports images). highlight=false gives a clean screenshot.",
                Tools.obj(J.obj("highlight", J.obj("type", "boolean"))), Tools.READ, "allow", new Tools.Executor() {
            public Object run(final Ctx ctx, final JSONObject a) throws Exception {
                return act(ctx, new ActFn() { public Object run(Surface s) throws Exception {
                    boolean hl = !a.has("highlight") || a.optBoolean("highlight");
                    JSONObject page = hl ? readPage(s, 150, false) : null;
                    if (hl) { js(s, HIGHLIGHT_JS + "(true)", 5000); Thread.sleep(250); }
                    byte[] jpg;
                    try { jpg = screenshot(s.id); } finally { if (hl) js(s, HIGHLIGHT_JS + "(false)", 5000); }
                    JSONObject art = backend.artifacts.create("screenshot.jpg", jpg, "image/jpeg", ctx.task, ctx.run.optString("id"), ctx.bot.optString("id"), null);
                    JSONObject out = J.obj("artifact_id", art.optString("id"), "image_artifact_id", art.optString("id"), "size", jpg.length,
                            "note", "The screenshot is attached as an image in the next message" + (hl ? "; boxes are numbered like the elements below." : "."));
                    if (page != null) { J.put(out, "elements", page.optJSONArray("elements")); J.put(out, "scroll", page.optJSONObject("scroll")); J.put(out, "url", page.optString("url")); }
                    return out;
                } });
            }
        }).needs("browser"));
        reg.register(new Spec("browser.request_takeover", "Hand the computer to the user (sign in, 2FA, CAPTCHA, payment details) and wait until they press Resume.",
                Tools.obj(J.obj("reason", S), "reason"), Tools.INTERNAL, "allow", new Tools.Executor() {
            public Object run(final Ctx ctx, final JSONObject a) throws Exception {
                final Surface s = surface(ctx.bot.optString("id"));
                db.tx(new Runnable() { public void run() {
                    db.exec("UPDATE computer_sessions SET status = 'needs_human', updated_at = ? WHERE id = ?", J.nowIso(), s.id);
                    backend.core.notify("needs_input", ctx.bot.optString("name") + " needs you on the computer", a.optString("reason"),
                            ctx.task.optString("id"), null, J.str(ctx.task, "conversation_id", null), ctx.bot.optString("id"));
                } });
                if (J.str(ctx.task, "conversation_id", null) != null)
                    backend.tasks.postBotMessage(ctx.task, "🖥️ " + a.optString("reason"), J.obj("takeover_request", J.obj("surface_id", s.id, "bot_id", ctx.bot.optString("id"))), false);
                return new Tools.Wait("input", J.obj("reason", "takeover", "surface_id", s.id, "question", a.optString("reason")));
            }
        }).needs("browser"));
    }

    static Tools.Summarize card(final String verb) {
        return new Tools.Summarize() {
            public JSONObject card(JSONObject a) {
                String what = a.has("url") ? a.optString("url") : a.has("ref") ? "element " + a.optInt("ref") : a.has("key") ? a.optString("key") : "";
                return J.obj("summary", verb + " " + what, "effect", "interacts with a web page", "target", a.optString("url", ""));
            }
        };
    }
}
