"""Host regression test for the Android work-service feedback loop.

Run with Python 3 and JDK 17+: python3 -m unittest discover -s mobile/tests -v
Compiles the real WorkService against small Android lifecycle fakes. This checks
service dispatch, not Android OS integration (covered by ci-emulator-test.sh).
"""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/content/Context.java": """
package android.content;
public class Context {
    public Context getApplicationContext() { return io.lowbot.app.LowBotApp.INSTANCE; }
    public void startForegroundService(Intent i) { io.lowbot.app.LowBotApp.starts.add(i); }
    public void startService(Intent i) { io.lowbot.app.LowBotApp.starts.add(i); }
    public void stopService(Intent i) { io.lowbot.app.LowBotApp.stops++; }
    public <T> T getSystemService(Class<T> cls) { return cls.cast(android.os.PowerManager.INSTANCE); }
    public String getString(int id, Object... args) { return "notification"; }
}
""",
    "android/content/Intent.java": """
package android.content;
public class Intent {
    private int n;
    public Intent(Context c, Class<?> cls) {}
    public Intent putExtra(String key, int value) { n = value; return this; }
    public int getIntExtra(String key, int fallback) { return n; }
}
""",
    "android/app/Service.java": """
package android.app;
public class Service extends android.content.Context {
    public static final int START_STICKY = 1;
    public void startForeground(int id, Notification n) { io.lowbot.app.LowBotApp.notifications++; }
    public void startForeground(int id, Notification n, int type) { startForeground(id, n); }
    public void stopSelf() { io.lowbot.app.LowBotApp.stops++; }
    public int onStartCommand(android.content.Intent i, int flags, int startId) { return 0; }
    public void onDestroy() {}
    public void onTimeout(int startId, int type) {}
    public android.os.IBinder onBind(android.content.Intent i) { return null; }
}
""",
    "android/app/Notification.java": """
package android.app;
public class Notification {
    public static class Builder {
        public Builder(android.content.Context c, String channel) {}
        public Builder setSmallIcon(int id) { return this; }
        public Builder setContentTitle(String s) { return this; }
        public Builder setContentText(String s) { return this; }
        public Builder setOngoing(boolean b) { return this; }
        public Builder setContentIntent(PendingIntent i) { return this; }
        public Notification build() { return new Notification(); }
    }
}
""",
    "android/app/PendingIntent.java": """
package android.app;
public class PendingIntent {
    public static final int FLAG_IMMUTABLE = 1;
    public static PendingIntent getActivity(android.content.Context c, int n,
            android.content.Intent i, int f) { return new PendingIntent(); }
}
""",
    "android/content/pm/ServiceInfo.java": """
package android.content.pm;
public class ServiceInfo { public static final int FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1073741824; }
""",
    "android/os/Build.java": """
package android.os;
public class Build { public static class VERSION { public static int SDK_INT = 35; } }
""",
    "android/os/PowerManager.java": """
package android.os;
public class PowerManager {
    public static final PowerManager INSTANCE = new PowerManager();
    public static final int PARTIAL_WAKE_LOCK = 1;
    public WakeLock last;
    public WakeLock newWakeLock(int level, String tag) {
        if (level != PARTIAL_WAKE_LOCK) throw new AssertionError("must not keep screen awake");
        return last = new WakeLock();
    }
    public static class WakeLock {
        public boolean held; public int renewals; public long timeout;
        public void setReferenceCounted(boolean value) {
            if (value) throw new AssertionError("must not accumulate lock references");
        }
        public void acquire(long ms) { held = true; timeout = ms; renewals++; }
        public boolean isHeld() { return held; }
        public void release() { held = false; }
    }
}
""",
    "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper() { return new Looper(); } }",
    "android/os/Handler.java": """
package android.os;
public class Handler {
    public static final java.util.ArrayDeque<Runnable> callbacks = new java.util.ArrayDeque<>();
    public Handler(Looper l) {}
    public void post(Runnable r) { callbacks.add(r); }
    public void removeCallbacksAndMessages(Object o) { callbacks.clear(); }
    public static void drain() { while (!callbacks.isEmpty()) callbacks.remove().run(); }
}
""",
    "android/util/Log.java": "package android.util; public class Log { public static void e(String tag, String msg, Throwable e) { throw new AssertionError(msg, e); } }",
    "android/os/IBinder.java": "package android.os; public interface IBinder {}",
    "io/lowbot/app/MainActivity.java": "package io.lowbot.app; public class MainActivity {}",
    "io/lowbot/app/R.java": """
package io.lowbot.app;
public class R {
    public static class drawable { public static final int ic_stat = 1; }
    public static class string { public static final int working_title = 2, working_text = 3; }
}
""",
    "io/lowbot/app/LowBotApp.java": """
package io.lowbot.app;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayDeque;
public class LowBotApp extends Context {
    public static final LowBotApp INSTANCE = new LowBotApp();
    static final String CH_WORK = "work";
    public static final ArrayDeque<Intent> starts = new ArrayDeque<>();
    public static int notifications, stops;
    final Backend backend = new Backend();
    static LowBotApp of(Context c) { return INSTANCE; }
    static class Engine {
        int wakes, active;
        int activeCount() { return active; }
        void wake() { wakes++; }
    }
    static class Backend {
        final Engine engine = new Engine();
        int queued = 4, stateReports, backgroundJobs;
        long queuedCount() { return queued; }
        // Backend.wake's platform contract: wake the engine, count pending
        // work, and report that count to WorkService.update.
        void wake() {
            engine.wake();
            stateReports++;
            WorkService.update(INSTANCE, queued, queued);
        }
    }
}
""",
    "io/lowbot/app/WorkServiceTest.java": """
package io.lowbot.app;
public class WorkServiceTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static void dispatch(WorkService service) {
        int delivered = 0;
        while (!LowBotApp.starts.isEmpty() && delivered++ < 100) {
            int result = service.onStartCommand(LowBotApp.starts.remove(), 0, delivered);
            check(result == WorkService.START_STICKY, "service remains sticky");
        }
        check(LowBotApp.starts.isEmpty(), "service dispatch never settles: start/wake feedback loop");
    }
    public static void main(String[] args) {
        android.os.Build.VERSION.SDK_INT = Integer.parseInt(args[0]);
        WorkService service = new WorkService();
        LowBotApp.Backend backend = LowBotApp.INSTANCE.backend;
        // One backend wake with four busy bots must yield exactly one service
        // command, not an unbounded queue while those bots wait for their model.
        backend.wake();
        dispatch(service);
        check(WorkService.running, "work service starts");
        android.os.PowerManager.WakeLock cpu = android.os.PowerManager.INSTANCE.last;
        check(cpu.isHeld() && cpu.timeout == 120000, "work owns a bounded partial wake lock");
        service.checkWork();
        android.os.Handler.drain();
        check(cpu.renewals == 2, "live work renews the CPU lease");
        check(LowBotApp.notifications == 1, "one notification for initial start");
        check(backend.stateReports == 1, "service must not query/report work state on the main thread");
        check(backend.engine.wakes == 2, "service still wakes engine");

        backend.queued = 2;
        backend.wake();
        dispatch(service);
        check(LowBotApp.notifications == 2, "changed work count still updates notification");
        check(backend.stateReports == 2, "update must not feed back to backend");

        backend.queued = 0;
        backend.wake();
        check(LowBotApp.stops == 1, "service stops when work finishes");
        service.onDestroy();
        check(!WorkService.running, "destroy clears running state");
        check(!cpu.isHeld(), "finished work releases the CPU lease");
        service = new WorkService();
        backend.backgroundJobs = 1;

        // Background watcher work follows the same path, with no active runs.
        WorkService.update(LowBotApp.INSTANCE, 0, 1);
        dispatch(service);
        check(WorkService.running, "watchers keep service alive");
        check(backend.stateReports == 3, "watcher start must not query backend");

        // Android may restart a sticky service with a null intent.
        int wakes = backend.engine.wakes;
        service.onStartCommand(null, 0, 5);
        check(backend.engine.wakes == wakes + 1, "sticky restart wakes engine");
        check(LowBotApp.starts.isEmpty(), "sticky restart must not restart itself");
        service.checkWork();
        android.os.Handler.drain();
        check(LowBotApp.stops == 1, "watchers count as durable work during monitoring");
        backend.backgroundJobs = 0;
        service.checkWork(); // stale idle snapshot must not stop newly started work
        WorkService.update(LowBotApp.INSTANCE, 1, 0);
        android.os.Handler.drain();
        check(LowBotApp.stops == 1, "new active work invalidates an old idle snapshot");
        dispatch(service);
        service.onTimeout(5, 1);
        check(LowBotApp.stops == 2, "Android timeout still stops service");
        service.onDestroy();
        check(!android.os.PowerManager.INSTANCE.last.isHeld(), "OS timeout releases the CPU lease");
        service = new WorkService();
        service.onStartCommand(null, 0, 6);
        service.checkWork();
        android.os.Handler.drain();
        check(LowBotApp.stops == 3, "idle sticky restart stops instead of holding CPU forever");
        service.onDestroy();
        System.out.println("PASS: bounded dispatch, CPU lease, stop, watchers, restart, stale snapshot, timeout");
    }
}
""",
}


class WorkServiceRegressionTest(unittest.TestCase):
    def test_service_dispatch_settles(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            files = []
            for name, source in SOURCES.items():
                path = tmp / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                files.append(str(path))
            files.append(str(ROOT / "mobile/src/io/lowbot/app/WorkService.java"))
            compiled = subprocess.run([
                "java", "com.sun.tools.javac.Main", "-d", str(tmp), *files,
            ], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            for sdk in (26, 35):
                with self.subTest(sdk=sdk):
                    result = subprocess.run([
                        "java", "-cp", str(tmp), "io.lowbot.app.WorkServiceTest", str(sdk),
                    ], capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
