package io.github.ceziar.aurora;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * Key filter for the TCL remote (BT_RC833A_B5). With the stock Google TV launcher disabled, several keys that
 * it used to answer stop working, so this service answers them instead:
 *
 *   Home            -> Aurora, whatever the TV believes its home screen is
 *   YouTube button  -> YouTube
 *   Gear button     -> the full TV Settings app
 *   TV / guide key  -> TCL's TV app
 *
 * The remote's other app buttons keep working natively; the OPTIONAL table below lets you give them new jobs.
 * Every other key passes straight through untouched and is not logged.
 *
 * It is an accessibility service because that is the only way to see these keys before the TV's own shortcut
 * handler (TclShortCutKeyManager) does, whichever app is in front.
 */
public class RemoteKeyService extends AccessibilityService {
    private static final String TAG = "RemoteKeys";

    private static final String TV_APP = "com.tcl.tv";

    // Linux scan codes reported by this remote, and the package each one opens.
    private static final int[] SCAN = {
            245, // YouTube button
            233, // gear (Settings) button
            365, // TV (guide) button
            // ---- OPTIONAL: these still work natively; uncomment a line (and its package) to change what it opens.
            // 242, // Netflix button over Bluetooth
            // 566, // Netflix button over IR (Android key code 4062)
            // 752, // Prime Video button
            // 236, // film-strip button
            // 227, // TCL Channel button
            // 762, // Disney+ button
    };
    private static final String[] PACKAGE = {
            "com.google.android.youtube.tv",
            "com.android.tv.settings",
            TV_APP,
            // "com.netflix.ninja",
            // "com.netflix.ninja",
            // "com.amazon.amazonvideo.livingroom",
            // "com.spotify.tv.android",
            // "com.tcl.channelplus",
            // "com.disney.disneyplus",
    };

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;

        // Home goes straight to Aurora. This also keeps Aurora's process alive, because the system keeps a
        // bound accessibility service running.
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (pressed) openAurora();
            return true;
        }
        // The guide key is also recognised by its Android key code, whatever its scan code.
        if (event.getKeyCode() == KeyEvent.KEYCODE_GUIDE) {
            if (pressed) launch(TV_APP);
            return true;
        }

        int scan = event.getScanCode();
        for (int i = 0; i < SCAN.length; i++) {
            if (SCAN[i] == scan) {
                if (pressed) launch(PACKAGE[i]);
                return true; // consume down and up, so the TV's own action for the button does not also fire
            }
        }

        // Only dedicated media/app keys ever reach these ranges; typed keys are never logged. This is how to
        // find the scan code of a button that is not listed above: press it, then read `adb logcat -s RemoteKeys`.
        if ((scan >= 200 || event.getKeyCode() >= 170) && event.getAction() == KeyEvent.ACTION_DOWN) {
            Log.d(TAG, "unmapped key: scan=" + scan + " keyCode=" + event.getKeyCode());
        }
        return false;
    }

    private void openAurora() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(home);
        } catch (Exception e) {
            Log.w(TAG, "could not open Aurora", e);
        }
    }

    private void launch(String pkg) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> found = getPackageManager().queryIntentActivities(query, 0);
        if (found.isEmpty()) {
            Log.w(TAG, "no launcher activity for " + pkg);
            return;
        }
        ResolveInfo ri = found.get(0);
        Intent go = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(go);
        } catch (Exception e) {
            Log.w(TAG, "could not start " + pkg, e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }
}
