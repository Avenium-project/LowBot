package io.lowbot.app;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import io.lowbot.core.ApiError;
import io.lowbot.core.Backend;
import io.lowbot.core.J;
import io.lowbot.engine.Tools;
import io.lowbot.engine.Tools.Ctx;
import io.lowbot.engine.Tools.Spec;
import io.lowbot.engine.Tools.ToolError;
import io.lowbot.tools.Builtin;

/**
 * A full Linux for the bots' terminal: Alpine Linux run through proot (no root needed).
 *
 * proot, its loader and libtalloc ship inside the APK as lib*.so (mobile/jniLibs), because apps
 * targeting Android 10+ may only execute files installed with the package. The Alpine
 * minirootfs is downloaded on first use from a URL pinned in assets/linux/alpine.json and
 * checked against the pinned SHA-256 before it is unpacked.
 *
 * Each bot gets one long-lived shell, so cd, variables and background jobs persist between
 * commands. The workspace folder is mounted at /workspace. Commands inside Linux can reach the
 * network directly — LowBot's egress guard does not apply there; every command goes through
 * the normal approval gateway instead.
 */
public final class Linux {
    static final int LOG_MAX = 96 * 1024, OUT_MAX = 16000;
    final Context app;
    final Backend backend;
    final File base, rootfs, tmp;
    final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    volatile String state = "idle", error = null, installedVersion = null;
    volatile long progress = 0, total = 0;

    Linux(Context app, Backend backend) {
        this.app = app;
        this.backend = backend;
        base = new File(app.getFilesDir(), "linux");
        rootfs = new File(base, "rootfs");
        tmp = new File(base, "tmp");
        File ready = new File(rootfs, ".lowbot-ready");
        if (ready.isFile()) installedVersion = read(ready).trim();
    }

    // ------------------------------------------------------------- runtime
    File lib(String name) { return new File(app.getApplicationInfo().nativeLibraryDir, name); }

    /** proot is part of this build (the APK was built with mobile/jniLibs). */
    public boolean available() { return lib("libproot.so").isFile() && lib("libproot-loader.so").isFile(); }

    public boolean installed() { return installedVersion != null; }

    static String arch() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        if (abi.startsWith("arm64")) return "aarch64";
        if (abi.equals("x86_64")) return "x86_64";
        return null;
    }

    JSONObject pinned() throws Exception {
        InputStream in = app.getAssets().open("linux/alpine.json");
        JSONObject all = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        in.close();
        String a = arch();
        if (a == null || !all.has(a)) throw new ApiError(422, "This phone's processor (" + Build.SUPPORTED_ABIS[0] + ") is not supported.");
        return all.getJSONObject(a);
    }

    public JSONObject status() {
        JSONArray s = new JSONArray();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            JSONObject bot = backend.bots.get(e.getKey());
            s.put(J.obj("bot_id", e.getKey(), "bot", bot == null ? e.getKey() : bot.optString("name"), "alive", e.getValue().alive(),
                    "busy", e.getValue().busy, "cwd", e.getValue().cwd));
        }
        return J.obj("available", available(), "installed", installed(), "version", installedVersion, "state", state, "error", error,
                "progress", progress, "total", total, "arch", arch(), "size_bytes", size(), "sessions", s,
                "network_note", "Commands in Linux can reach the internet and your local network directly; LowBot's network guard does not apply. Each command still needs approval (Settings → Execution).");
    }

    private long sizeCache = 0, sizeAt = 0;

    long size() {
        if (!installed()) return 0;
        if (System.currentTimeMillis() - sizeAt > 60000) { sizeCache = du(rootfs); sizeAt = System.currentTimeMillis(); }
        return sizeCache;
    }

    /** Bots get the terminal tools once Linux is installed (and the runtime is in this build). */
    void syncCapability() {
        if (available() && installed()) backend.extraCapabilities.add("linux");
        else backend.extraCapabilities.remove("linux");
        // Runtime shipped but Alpine not downloaded yet (fresh install, reinstall): bots can offer to install it.
        if (available() && !installed()) backend.extraCapabilities.add("linux_installable");
        else backend.extraCapabilities.remove("linux_installable");
    }

    // ------------------------------------------------------------- install
    public synchronized JSONObject install() {
        if (!available()) throw new ApiError(501, "This build of LowBot does not include the Linux runtime (proot).");
        if ("installing".equals(state)) return status();
        state = "installing"; error = null; progress = 0; total = 0;
        new Thread(new Runnable() { public void run() {
            try {
                doInstall();
                state = "ready";
            } catch (Throwable e) {
                state = "failed";
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            emit();
        } }, "linux-install").start();
        emit();
        return status();
    }

    void doInstall() throws Exception {
        JSONObject pin = pinned();
        File dl = new File(app.getCacheDir(), "alpine-minirootfs.tar.gz");
        HttpURLConnection c = (HttpURLConnection) new URL(pin.getString("url")).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        if (c.getResponseCode() != 200) throw new Exception("Download failed (HTTP " + c.getResponseCode() + ").");
        total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : pin.optLong("size");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        InputStream in = new BufferedInputStream(c.getInputStream());
        OutputStream out = new FileOutputStream(dl);
        byte[] buf = new byte[65536];
        int n;
        long last = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            sha.update(buf, 0, n);
            progress += n;
            if (progress - last > 512 * 1024) { last = progress; emit(); }
        }
        out.close();
        in.close();
        String got = hex(sha.digest());
        if (!got.equalsIgnoreCase(pin.getString("sha256"))) { dl.delete(); throw new Exception("Checksum mismatch — download discarded."); }
        File staging = new File(base, "rootfs.new");
        deleteTree(staging);
        staging.mkdirs();
        untar(new GZIPInputStream(new FileInputStream(dl)), staging);
        dl.delete();
        write(new File(staging, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        write(new File(staging, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost\n");
        write(new File(staging, "etc/profile.d/lowbot.sh"), "export PS1='' TERM=dumb LANG=C.UTF-8\n");
        new File(staging, "workspace").mkdirs();
        new File(staging, "tmp").mkdirs();
        write(new File(staging, ".lowbot-ready"), pin.getString("version") + "\n");
        closeAll();
        deleteTree(rootfs);
        if (!staging.renameTo(rootfs)) throw new Exception("Could not move the Linux files into place.");
        installedVersion = pin.getString("version");
        syncCapability();
    }

    public synchronized void remove() {
        closeAll();
        deleteTree(rootfs);
        deleteTree(new File(base, "rootfs.new"));
        installedVersion = null;
        syncCapability();
        state = "idle";
        emit();
    }

    // ------------------------------------------------------------- sessions
    final class Session {
        final String botId;
        final Process p;
        final OutputStream stdin;
        final StringBuilder log = new StringBuilder();
        final StringBuilder pending = new StringBuilder();
        volatile boolean busy;
        volatile String cwd = "/workspace";

        Session(String botId, File workspace) throws Exception {
            this.botId = botId;
            tmp.mkdirs();
            ProcessBuilder pb = prootBuilder(workspace, botId, null, "/bin/sh");
            p = pb.start();
            stdin = p.getOutputStream();
            final InputStream out = p.getInputStream();
            Thread t = new Thread(new Runnable() { public void run() {
                byte[] buf = new byte[8192];
                int n;
                try {
                    while ((n = out.read(buf)) > 0) {
                        String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                        synchronized (Session.this) { pending.append(s); Session.this.notifyAll(); }
                    }
                } catch (Exception ignored) { }
                synchronized (Session.this) { Session.this.notifyAll(); }
            } }, "linux-" + botId);
            t.setDaemon(true);
            t.start();
        }

        boolean alive() {
            try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
        }

        synchronized void appendLog(String s) {
            log.append(backend.core.scrubSecrets(s)); // filled-in secrets never show in the terminal log
            if (log.length() > LOG_MAX) log.delete(0, log.length() - LOG_MAX);
        }

        void kill() { p.destroy(); try { p.destroyForcibly(); } catch (Throwable ignored) { } }
    }

    /** Root of the per-bot ping folders. */
    File pingDir() { File d = new File(base, "pings"); d.mkdirs(); return d; }

    /** A bot's own folder (bound at /lowbot/pings) where `lowbot-ping` drops a JSON file to wake it. Bots never
     *  see each other's folders, so a program can only wake the bot that runs it. */
    File pingDir(String botId) {
        File d = new File(pingDir(), botId == null ? "_" : botId.replaceAll("[^A-Za-z0-9_-]", "_"));
        d.mkdirs();
        return d;
    }

    static final String PING_SH = "#!/bin/sh\n# lowbot-ping \"message\" ['{\"json\":\"data\"}'] — wakes your bot with this message.\n"
            + "[ -z \"$1\" ] && { echo 'usage: lowbot-ping \"message\" [json]' >&2; exit 2; }\n"
            + "f=/lowbot/pings/.$$.$(date +%s).tmp\n"
            + "python3 -c 'import json,sys,os,time;print(json.dumps({\"bot\":os.environ.get(\"LOWBOT_BOT\",\"\"),\"watcher\":os.environ.get(\"LOWBOT_WATCHER\",\"\"),\"message\":sys.argv[1],\"data\":sys.argv[2] if len(sys.argv)>2 else None,\"time\":time.time()}))' \"$1\" \"$2\" > \"$f\" 2>/dev/null \\\n"
            + "  || printf '{\"bot\":\"%s\",\"watcher\":\"%s\",\"message\":\"%s\"}' \"$LOWBOT_BOT\" \"$LOWBOT_WATCHER\" \"$(printf %s \"$1\" | tr -d '\"\\\\' | head -c 2000)\" > \"$f\"\n"
            + "mv \"$f\" \"/lowbot/pings/ping-$$-$(date +%s%N 2>/dev/null || date +%s).json\" && echo 'pinged'\n";

    static final String PING_PY = "\"\"\"LowBot: wake your bot from a program.\n\n    from lowbot import ping\n    ping('BTC fell below 60000', {'price': 59800})\n\"\"\"\n"
            + "import json, os, time, uuid\n\n"
            + "def ping(message, data=None):\n"
            + "    d = '/lowbot/pings'\n"
            + "    tmp = os.path.join(d, '.%s.tmp' % uuid.uuid4().hex)\n"
            + "    with open(tmp, 'w') as f:\n"
            + "        json.dump({'bot': os.environ.get('LOWBOT_BOT', ''), 'watcher': os.environ.get('LOWBOT_WATCHER', ''),\n"
            + "                   'message': str(message)[:2000], 'data': data, 'time': time.time()}, f, default=str)\n"
            + "    os.replace(tmp, os.path.join(d, 'ping-%s.json' % uuid.uuid4().hex))\n"
            + "    return True\n";

    /** Installs lowbot-ping and the Python `lowbot` module into the Linux (idempotent). */
    void installHelpers() {
        try {
            File sh = new File(rootfs, "usr/local/bin/lowbot-ping");
            if (!sh.isFile() || !read(sh).equals(PING_SH)) { write(sh, PING_SH); Os.chmod(sh.getPath(), 0755); }
            File py = new File(rootfs, "opt/lowbot/lowbot.py");
            if (!py.isFile() || !read(py).equals(PING_PY)) write(py, PING_PY);
            new File(rootfs, "lowbot/pings").mkdirs();
        } catch (Exception ignored) { }
    }

    /** proot command line shared by the bots' shells and their watcher programs. */
    ProcessBuilder prootBuilder(File workspace, String botId, String watcherId, String... cmd) {
        tmp.mkdirs();
        installHelpers();
        List<String> args = new ArrayList<String>(Arrays.asList(lib("libproot.so").getPath(), "--kill-on-exit", "--link2symlink", "-0",
                "-r", rootfs.getPath(), "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", workspace.getPath() + ":/workspace",
                "-b", pingDir(botId).getPath() + ":/lowbot/pings", "-w", "/workspace"));
        args.addAll(Arrays.asList(cmd));
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PROOT_LOADER", lib("libproot-loader.so").getPath());
        if (lib("libproot-loader32.so").isFile()) env.put("PROOT_LOADER_32", lib("libproot-loader32.so").getPath());
        env.put("PROOT_TMP_DIR", tmp.getPath());
        // x86_64 Android blocks the fork syscall musl uses there (arm64 has only clone); without proot's
        // seccomp acceleration every syscall goes through ptrace, which lets proot handle it.
        if ("x86_64".equals(arch())) env.put("PROOT_NO_SECCOMP", "1");
        env.put("LD_LIBRARY_PATH", app.getApplicationInfo().nativeLibraryDir);
        env.put("HOME", "/root");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("TERM", "dumb");
        env.put("LANG", "C.UTF-8");
        env.put("PS1", "");
        env.put("PYTHONPATH", "/opt/lowbot");
        env.put("PYTHONUNBUFFERED", "1");
        env.put("LOWBOT_BOT", botId);
        if (watcherId != null) env.put("LOWBOT_WATCHER", watcherId);
        return pb;
    }

    Session session(String botId, File workspace) throws ToolError {
        if (!available()) throw new ToolError("The Linux runtime is not part of this LowBot build.");
        if (!installed()) throw new ToolError("Linux is not installed yet. Ask the user to install it in Settings → Linux terminal.");
        Session s = sessions.get(botId);
        if (s != null && s.alive()) return s;
        try {
            s = new Session(botId, workspace);
        } catch (Exception e) {
            throw new ToolError("Could not start Linux: " + e.getMessage());
        }
        sessions.put(botId, s);
        return s;
    }

    /** Runs one command in the bot's shell; output is stdout+stderr. Kills the shell on timeout. */
    public JSONObject run(String botId, File workspace, String command, int timeoutS, String who, Ctx ctx) throws ToolError {
        Session s = session(botId, workspace);
        synchronized (s) {
            if (s.busy) throw new ToolError("This bot's terminal is already running a command.");
            s.busy = true;
        }
        String tok = Long.toHexString(new SecureRandom().nextLong() & Long.MAX_VALUE);
        Pattern done = Pattern.compile("\n?__LB_" + tok + "_(\\d+)_(.*?)__\n");
        s.appendLog((who.equals("user") ? "you" : "bot") + " $ " + command + "\n");
        emit();
        try {
            synchronized (s) { s.pending.setLength(0); }
            String script = "{\n" + command + "\n} </dev/null 2>&1; printf '\\n__LB_" + tok + "_%d_%s__\\n' \"$?\" \"$PWD\"\n";
            try {
                s.stdin.write(script.getBytes(StandardCharsets.UTF_8));
                s.stdin.flush();
            } catch (Exception e) {
                sessions.remove(botId);
                throw new ToolError("The terminal stopped; it will restart on the next command.");
            }
            long deadline = System.currentTimeMillis() + timeoutS * 1000L;
            while (true) {
                String text;
                synchronized (s) {
                    text = s.pending.toString();
                    Matcher m = done.matcher(text);
                    if (m.find()) {
                        String output = text.substring(0, m.start());
                        s.cwd = m.group(2);
                        s.pending.setLength(0);
                        s.appendLog(output.isEmpty() || output.endsWith("\n") ? output : output + "\n");
                        int code = Integer.parseInt(m.group(1));
                        if (code != 0) s.appendLog("[exit " + code + "]\n");
                        emit();
                        return J.obj("exit_code", code, "cwd", s.cwd, "output", J.redact(tail(output, OUT_MAX)),
                                "truncated", output.length() > OUT_MAX);
                    }
                    if (!s.alive()) {
                        sessions.remove(botId);
                        s.appendLog(text + "\n[terminal exited]\n");
                        emit();
                        throw new ToolError("The terminal exited. Output: " + J.redact(tail(text, 4000)));
                    }
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0 || (ctx != null && ctx.cancelled())) {
                        s.kill();
                        sessions.remove(botId);
                        s.appendLog(text + "\n[killed after " + timeoutS + " s — terminal restarted]\n");
                        emit();
                        throw new ToolError((left <= 0 ? "Command timed out after " + timeoutS + " s" : "Cancelled")
                                + "; the terminal was restarted (cd and variables reset). Partial output: " + J.redact(tail(text, 4000)));
                    }
                    try { s.wait(Math.min(left, 1000)); } catch (InterruptedException e) { throw new ToolError("Interrupted."); }
                }
            }
        } finally {
            s.busy = false;
        }
    }

    public JSONObject log(String botId) {
        Session s = sessions.get(botId);
        String text;
        if (s == null) text = "";
        else synchronized (s) { text = s.log.toString() + (s.busy ? s.pending.toString() : ""); }
        return J.obj("bot_id", botId, "log", text, "alive", s != null && s.alive(), "busy", s != null && s.busy, "cwd", s == null ? "/workspace" : s.cwd);
    }

    public void reset(String botId) {
        Session s = sessions.remove(botId);
        if (s != null) s.kill();
        emit();
    }

    void closeAll() {
        for (String id : sessions.keySet()) reset(id);
    }

    void emit() {
        try {
            backend.core.db.tx(new Runnable() { public void run() { backend.core.emit("linux.updated", null, null, null, null, null); } });
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------- tools
    void register(Tools reg) {
        syncCapability();
        reg.register(new Spec("linux.install", "Your Linux terminal is not installed on this phone yet (e.g. after a reinstall). Install it: downloads "
                + "Alpine Linux (≈4 MB, checked by SHA-256) once; the user approves. After it finishes, linux.run and watcher.* work.",
                Tools.obj(new JSONObject()), Tools.EXTERNAL, "ask", new Tools.Executor() {
            public Object run(Tools.Ctx ctx, JSONObject a) throws Exception {
                if (installed()) return J.obj("status", "already installed", "version", installedVersion);
                install();
                long until = System.currentTimeMillis() + 240000;
                while ("installing".equals(state) && System.currentTimeMillis() < until) Thread.sleep(1000);
                if (installed()) return J.obj("status", "installed", "version", installedVersion, "next", "Use linux.run now.");
                throw new Tools.ToolError("Linux install " + ("installing".equals(state) ? "is still running — try linux.run in a minute" : "failed: " + error));
            }
        }).needs("linux_installable").timeout(260).card(new Tools.Summarize() {
            public JSONObject card(JSONObject a) {
                return J.obj("summary", "Install the Linux terminal (Alpine, ≈4 MB download)", "effect", "downloads and installs Linux for the bots on this phone", "target", "linux");
            }
        }));
        reg.register(new Spec("linux.run", "Run a shell command in your own Linux (Alpine, /bin/sh) on this phone. Your shell persists between calls "
                + "(cd, variables, background jobs). Install software with `apk add <pkg>` (e.g. python3, git, nodejs, curl). "
                + "Shared workspace files are in /workspace. Prefer short, non-interactive commands; output is stdout+stderr.",
                Tools.obj(J.obj("command", J.obj("type", "string"), "timeout_s", J.obj("type", "integer", "minimum", 1, "maximum", 900)), "command"),
                Tools.EXTERNAL, "ask", new Tools.Executor() {
            public Object run(Ctx ctx, JSONObject a) throws Exception {
                String cmd = Builtin.fillSecrets(ctx, a.optString("command"));
                return Linux.this.run(ctx.bot.optString("id"), Builtin.root(ctx), cmd, a.optInt("timeout_s", 120), "bot", ctx);
            }
        }).needs("linux").timeout(920).card(new Tools.Summarize() {
            public JSONObject card(JSONObject a) {
                return J.obj("summary", "Run in Linux: " + J.truncate(a.optString("command"), 300),
                        "effect", "runs a command in the bot's Linux on this phone (can use the internet)", "target", "linux");
            }
        }));
        reg.register(new Spec("linux.reset", "Restart your Linux shell (clears cd, variables and running jobs; files stay).",
                Tools.obj(new JSONObject()), Tools.INTERNAL, "allow", new Tools.Executor() {
            public Object run(Ctx ctx, JSONObject a) {
                reset(ctx.bot.optString("id"));
                return J.obj("reset", true);
            }
        }).needs("linux"));
    }

    // ------------------------------------------------------------- tar
    /** Minimal ustar/pax reader: files, directories, symlinks, hard links; refuses paths that escape the target. */
    static void untar(InputStream in, File dest) throws Exception {
        String root = dest.getCanonicalPath();
        byte[] h = new byte[512];
        String longName = null, longLink = null;
        while (true) {
            if (!readFully(in, h)) break;
            boolean empty = true;
            for (byte x : h) if (x != 0) { empty = false; break; }
            if (empty) break;
            String name = str(h, 0, 100), link = str(h, 157, 100), prefix = str(h, 345, 155);
            int mode = (int) oct(h, 100, 8);
            long size = oct(h, 124, 12);
            char type = (char) h[156];
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                byte[] data = new byte[(int) size];
                readFully(in, data);
                skip(in, pad(size));
                String v = new String(data, StandardCharsets.UTF_8);
                if (type == 'L') longName = v.replace("\0", "");
                else if (type == 'K') longLink = v.replace("\0", "");
                else if (type == 'x') {
                    for (String line : v.split("\n")) {
                        int sp = line.indexOf(' '), eq = line.indexOf('=');
                        if (sp < 0 || eq < 0) continue;
                        String k = line.substring(sp + 1, eq), val = line.substring(eq + 1);
                        if (k.equals("path")) longName = val;
                        if (k.equals("linkpath")) longLink = val;
                    }
                }
                continue;
            }
            String path = longName != null ? longName : (prefix.isEmpty() ? name : prefix + "/" + name);
            if (longLink != null) link = longLink;
            longName = null; longLink = null;
            path = path.replaceFirst("^(\\./)+", "").replaceFirst("^/+", "");
            if (path.isEmpty() || path.equals(".")) { skip(in, size + pad(size)); continue; }
            if (("/" + path + "/").contains("/../")) throw new Exception("Unsafe path in archive: " + path);
            File f = new File(dest, path);
            File parent = f.getParentFile();
            parent.mkdirs();
            if (!parent.getCanonicalPath().startsWith(root)) throw new Exception("Archive path escapes the target: " + path);
            if (type == '5') {
                f.mkdirs();
                Os.chmod(f.getPath(), (mode & 07777) | 0700);
            } else if (type == '2') {
                f.delete();
                Os.symlink(link, f.getPath());
            } else if (type == '1') {
                File src = new File(dest, link.replaceFirst("^(\\./)+", "").replaceFirst("^/+", ""));
                if (!src.getCanonicalPath().startsWith(root)) throw new Exception("Hard link escapes the target: " + link);
                f.delete();
                try { Os.link(src.getPath(), f.getPath()); } catch (Exception e) { copy(src, f); }
            } else if (type == '0' || type == 0 || type == '7') {
                f.delete();
                OutputStream o = new FileOutputStream(f);
                long left = size;
                byte[] buf = new byte[65536];
                while (left > 0) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) throw new Exception("Truncated archive.");
                    o.write(buf, 0, r);
                    left -= r;
                }
                o.close();
                skip(in, pad(size));
                Os.chmod(f.getPath(), (mode & 07777) | 0600);
                continue;
            }
            skip(in, size + pad(size));
        }
        in.close();
    }

    static long pad(long size) { return (512 - size % 512) % 512; }

    static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    static long oct(byte[] b, int off, int len) {
        String s = str(b, off, len).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    static boolean readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }

    static void skip(InputStream in, long n) throws Exception {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) return;
            n -= r;
        }
    }

    // ------------------------------------------------------------- files
    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    static String read(File f) {
        try { FileInputStream in = new FileInputStream(f); String s = new String(readAll(in), StandardCharsets.UTF_8); in.close(); return s; }
        catch (Exception e) { return ""; }
    }

    static void write(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        if (f.exists() || isLink(f)) f.delete();
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes(StandardCharsets.UTF_8));
        o.close();
    }

    static void copy(File a, File b) throws Exception {
        FileInputStream in = new FileInputStream(a);
        FileOutputStream o = new FileOutputStream(b);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        o.close();
        in.close();
    }

    static boolean isLink(File f) {
        try { return android.system.OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode); } catch (Exception e) { return false; }
    }

    /** Deletes a tree without following symlinks. */
    static void deleteTree(File f) {
        if (isLink(f)) { f.delete(); return; }
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static long du(File f) {
        if (isLink(f)) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += du(k);
        return n;
    }

    static String tail(String s, int max) { return s.length() <= max ? s : "…" + s.substring(s.length() - max); }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
