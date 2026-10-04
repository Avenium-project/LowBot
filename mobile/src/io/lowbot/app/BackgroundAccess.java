package io.lowbot.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/** Requests the system's background-work exemption once, when bots first work. */
final class BackgroundAccess {
    static void request(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        PowerManager power = activity.getSystemService(PowerManager.class);
        if (power == null || power.isIgnoringBatteryOptimizations(activity.getPackageName())) return;
        SharedPreferences prefs = activity.getSharedPreferences("background-access", 0);
        if (prefs.getBoolean("battery-requested", false)) return;
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (android.content.ActivityNotFoundException | SecurityException unavailable) { return; }
        }
        // Respect a refusal: do not reopen the prompt on every task or resume.
        prefs.edit().putBoolean("battery-requested", true).apply();
    }
}
