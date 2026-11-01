package io.github.ceziar.aurora;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps the remote-button service alive. This TV resets Android's accessibility master switch (at cold boot, and
 * sometimes without any reboot), after which a service the user has ticked is never started. If the user has
 * ticked "Aurora remote buttons" and has granted WRITE_SECURE_SETTINGS once over adb, this turns the master switch
 * back on, and restarts the service if it is ticked, the switch is on, and it is still not running.
 * It never ticks the service by itself: if the user has not ticked it, nothing happens.
 */
public class ServiceGuard {
    private static final String TAG = "ServiceGuard";
    private static final long CHECK_MS = 60_000L;
    private static final long RESTART_GAP_MS = 5 * 60_000L;
    private static long lastRestart;
    private static boolean looping;

    /** Runs a check now and then once a minute for as long as the process lives. */
    public static void start(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        ensure(app);
        if (looping) return;
        looping = true;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                ensure(app);
                h.postDelayed(this, CHECK_MS);
            }
        }, CHECK_MS);
    }

    static void ensure(Context ctx) {
        try {
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
            String me = new ComponentName(ctx, RemoteKeyService.class).flattenToString();
            String list = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || !list.contains(me)) return; // the user has not ticked it: leave it alone
            if (Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.i(TAG, "accessibility master switch was off: turned on");
                return;
            }
            long now = System.currentTimeMillis();
            if (!RemoteKeyService.running && now - lastRestart > RESTART_GAP_MS) {
                lastRestart = now;
                Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
                Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
                Log.i(TAG, "service was ticked but not running: restarted");
            }
        } catch (Exception e) {
            Log.w(TAG, "check failed", e);
        }
    }

    /** Starts the guard after a reboot. */
    public static class BootReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            start(context);
        }
    }
}
