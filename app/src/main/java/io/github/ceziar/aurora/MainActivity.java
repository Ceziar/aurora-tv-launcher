package io.github.ceziar.aurora;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Aurora: a minimal Android TV home screen with an animated northern-lights scene, clock/date/weather top right,
 * app ribbon along the bottom. Long-press OK on an app to hide or reorder it.
 */
public class MainActivity extends Activity {

    // Where the weather comes from (Open-Meteo, no API key). Change these three for your own town.
    private static final String LOCATION_NAME = "Helsinki";
    private static final double LAT = 60.1699;
    private static final double LON = 24.9384;
    private static final long WEATHER_REFRESH_MS = 15 * 60 * 1000L;

    // Hidden on first run: TCL helper apps that clutter the ribbon (everything can be shown again from the
    // manage dialog: the "..." tile at the end of the ribbon, or the remote's Menu key).
    private static final String[] DEFAULT_HIDDEN = {
            "com.tcl.ocean.instructions", // User Guide
            "com.tcl.suspension",         // Quick Panel
            "com.tcl.dashboard",          // TCL Home
            "com.tcl.guard",              // Security guard
            "com.tcl.ui_mediaCenter"      // Media Player
    };

    // Default ribbon order (packages not listed come after, alphabetically). User reordering overrides this.
    private static final String[] DEFAULT_ORDER = {
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.spotify.tv.android",
            "com.tcl.tv", "com.google.android.videos", "com.android.vending",
            "com.android.tv.settings"
    };

    private static class AppItem {
        ComponentName component;
        String label;
        Drawable image;
        boolean isBanner;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private PackageManager pm;

    private AuroraView backdrop;

    private TextView weatherView;
    private TextView caption;
    private LinearLayout ribbon;
    private HorizontalScrollView scroll;
    private String ribbonSignature = "";

    private float density;

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            fetchWeather();
            handler.postDelayed(this, WEATHER_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        pm = getPackageManager();
        density = getResources().getDisplayMetrics().density;

        if (!prefs.getBoolean("init", false)) {
            Set<String> hidden = new HashSet<>();
            Collections.addAll(hidden, DEFAULT_HIDDEN);
            prefs.edit().putStringSet("hidden", hidden).putBoolean("init", true).apply();
        }
        buildUi();
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        backdrop = new AuroraView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));


        // Dark scrims keep the text readable on a bright flame.
        root.addView(scrim(GradientDrawable.Orientation.TOP_BOTTOM, 0xAA000000, 0x00000000),
                new FrameLayout.LayoutParams(-1, dp(220), Gravity.TOP));
        root.addView(scrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xDD000000, 0x00000000),
                new FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM));

        // Clock / date / weather, top right.
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.END);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(26);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(8, 0, 2, 0xAA000000);
        clock.setGravity(Gravity.END);
        info.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("cccc d.M.yyyy");
        date.setFormat12Hour("cccc d.M.yyyy");
        date.setTextSize(13);
        date.setTextColor(0xFFEEEEEE);
        date.setShadowLayer(6, 0, 2, 0xAA000000);
        date.setGravity(Gravity.END);
        info.addView(date);

        weatherView = new TextView(this);
        weatherView.setTextSize(13);
        weatherView.setTextColor(0xFFFFD9A0);
        weatherView.setShadowLayer(6, 0, 2, 0xAA000000);
        weatherView.setGravity(Gravity.END);
        weatherView.setText("");
        info.addView(weatherView);

        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        ip.topMargin = dp(28);
        ip.rightMargin = dp(56);
        root.addView(info, ip);

        // App ribbon, bottom.
        ribbon = new LinearLayout(this);
        ribbon.setOrientation(LinearLayout.HORIZONTAL);
        ribbon.setPadding(dp(56), dp(16), dp(56), dp(16));
        ribbon.setClipChildren(false);
        ribbon.setClipToPadding(false);
        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(ribbon, new ViewGroup.LayoutParams(-2, -2));
        caption = new TextView(this);
        caption.setTextSize(18);
        caption.setTextColor(Color.WHITE);
        caption.setShadowLayer(6, 0, 2, 0xCC000000);
        caption.setPadding(dp(56), 0, dp(56), 0);
        caption.setSingleLine(true);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        bottom.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        rp.bottomMargin = dp(32);
        root.addView(bottom, rp);

        setContentView(root);
    }

    private View scrim(GradientDrawable.Orientation o, int from, int to) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[]{from, to}));
        return v;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(weatherTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildRibbonIfChanged(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(weatherTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first app.
        scroll.smoothScrollTo(0, 0);
        if (ribbon.getChildCount() > 0) ribbon.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        // A home screen has nowhere to go back to.
    }

    // ---------------------------------------------------------------- weather

    private void fetchWeather() {
        new Thread(() -> {
            String text = null;
            try {
                URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + LAT
                        + "&longitude=" + LON + "&current=temperature_2m,weather_code&timezone=auto");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                for (String line; (line = r.readLine()) != null; ) sb.append(line);
                r.close();
                JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                long t = Math.round(cur.getDouble("temperature_2m"));
                text = LOCATION_NAME + "  " + (t > 0 ? "+" : "") + t + "°C  " + describe(cur.getInt("weather_code"));
            } catch (Exception ignored) {
                // Keep the previous text; try again next tick.
            }
            final String shown = text;
            if (shown != null) handler.post(() -> weatherView.setText(shown));
        }).start();
    }

    private static String describe(int code) {
        if (code == 0) return "Selkeää";
        if (code <= 2) return "Puolipilvistä";
        if (code == 3) return "Pilvistä";
        if (code == 45 || code == 48) return "Sumua";
        if (code >= 51 && code <= 57) return "Tihkusadetta";
        if (code >= 61 && code <= 67) return "Sadetta";
        if (code >= 71 && code <= 77) return "Lumisadetta";
        if (code >= 80 && code <= 82) return "Sadekuuroja";
        if (code == 85 || code == 86) return "Lumikuuroja";
        if (code >= 95) return "Ukkosta";
        return "";
    }

    // ---------------------------------------------------------------- ribbon

    private List<AppItem> loadApps(boolean includeHidden) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<String>());
        List<AppItem> items = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            boolean isHidden = hidden.contains(ri.activityInfo.packageName);
            if (isHidden != includeHidden) continue;
            AppItem it = new AppItem();
            it.component = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            it.label = String.valueOf(ri.loadLabel(pm));
            Drawable banner = ri.activityInfo.loadBanner(pm);
            it.isBanner = banner != null;
            it.image = banner != null ? banner : ri.loadIcon(pm);
            items.add(it);
        }
        if (!includeHidden) applySavedOrder(items);
        return items;
    }

    private void applySavedOrder(List<AppItem> items) {
        final List<String> order = new ArrayList<>();
        String saved = prefs.getString("order", "");
        if (!saved.isEmpty()) {
            Collections.addAll(order, saved.split("\n"));
        } else {
            for (String pkg : DEFAULT_ORDER) {
                for (AppItem it : items) {
                    if (it.component.getPackageName().equals(pkg)) order.add(it.component.flattenToShortString());
                }
            }
        }
        Collections.sort(items, (a, b) -> {
            int ia = order.indexOf(a.component.flattenToShortString());
            int ib = order.indexOf(b.component.flattenToShortString());
            if (ia < 0) ia = Integer.MAX_VALUE;
            if (ib < 0) ib = Integer.MAX_VALUE;
            return ia != ib ? Integer.compare(ia, ib) : a.label.compareToIgnoreCase(b.label);
        });
    }

    private void saveOrder(List<AppItem> items) {
        StringBuilder sb = new StringBuilder();
        for (AppItem it : items) sb.append(it.component.flattenToShortString()).append('\n');
        prefs.edit().putString("order", sb.toString()).apply();
    }

    private void rebuildRibbonIfChanged(boolean force) {
        List<AppItem> apps = loadApps(false);
        StringBuilder sig = new StringBuilder(prefs.getStringSet("hidden", new HashSet<String>()).toString());
        for (AppItem it : apps) sig.append('|').append(it.component.flattenToShortString());
        if (!force && sig.toString().equals(ribbonSignature)) return;
        ribbonSignature = sig.toString();

        int focusIndex = -1;
        View focused = ribbon.getFocusedChild();
        if (focused != null) focusIndex = ribbon.indexOfChild(focused);

        // Every app always fits on screen: cards shrink as the list grows (capped at full size).
        int count = apps.size() + 1; // + the "hidden apps" card
        int sidePad = dp(56);
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * sidePad;
        int fullGap = dp(18);
        int fullW = dp(176);
        int gap = fullGap;
        int cardW = (avail - (count - 1) * gap) / count;
        if (cardW > fullW) {
            cardW = fullW;
        } else {
            gap = Math.max(dp(6), Math.round(cardW * 0.12f));
            cardW = (avail - (count - 1) * gap) / count;
        }

        ribbon.removeAllViews();
        for (AppItem it : apps) ribbon.addView(makeCard(it, apps, cardW, gap));
        ribbon.addView(makeMoreCard(cardW, gap));

        int target = Math.max(0, Math.min(focusIndex, ribbon.getChildCount() - 1));
        ribbon.getChildAt(target).requestFocus();
    }

    private View makeCard(final AppItem app, final List<AppItem> all, final int cardW, int gap) {
        final int cardH = cardW * 9 / 16;
        final float focusScale = cardW < dp(120) ? 1.3f : 1.12f;
        final GradientDrawable bg = cardBackground(false);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(bg);
        card.setClipToOutline(true);
        ImageView img = new ImageView(this);
        img.setImageDrawable(app.image);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int iconSize = Math.round(cardH * 0.65f);
            card.addView(img, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        }

        // The app name is shown once, above the ribbon, for the focused card.
        final LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.addView(card, new LinearLayout.LayoutParams(cardW, cardH));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = gap;
        item.setLayoutParams(lp);

        item.setOnFocusChangeListener((v, has) -> {
            card.setBackground(cardBackground(has));
            card.setForeground(has ? focusRing() : null); // outline over the banner artwork
            v.setTranslationZ(has ? dp(8) : 0); // draw above neighbours without reordering them
            v.animate().scaleX(has ? focusScale : 1f).scaleY(has ? focusScale : 1f).setDuration(120).start();
            if (has) caption.setText(app.label);
        });
        item.setOnClickListener(v -> launch(app));
        item.setOnLongClickListener(v -> { showEditDialog(app, all); return true; });
        return item;
    }

    private View makeMoreCard(int cardW, int gap) {
        final int cardH = cardW * 9 / 16;
        final float focusScale = cardW < dp(120) ? 1.3f : 1.12f;
        final GradientDrawable bg = cardBackground(false);
        TextView plus = new TextView(this);
        plus.setText("•••");
        plus.setTextColor(0xCCFFFFFF);
        plus.setTextSize(cardW < dp(120) ? 12 : 20);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(bg);
        LinearLayout item = new LinearLayout(this);
        item.setFocusable(true);
        item.setClickable(true);
        item.addView(plus, new LinearLayout.LayoutParams(cardW, cardH));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = gap;
        item.setLayoutParams(lp);
        item.setOnFocusChangeListener((v, has) -> {
            plus.setBackground(cardBackground(has));
            v.setTranslationZ(has ? dp(8) : 0);
            v.animate().scaleX(has ? focusScale : 1f).scaleY(has ? focusScale : 1f).setDuration(120).start();
            if (has) caption.setText("Näytä / piilota sovelluksia");
        });
        item.setOnClickListener(v -> showManageDialog());
        return item;
    }

    private GradientDrawable focusRing() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(3), Color.WHITE);
        return g;
    }

    private GradientDrawable cardBackground(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? 0x66FFFFFF : 0x44202020);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(focused ? 3 : 1), focused ? Color.WHITE : 0x55FFFFFF);
        return g;
    }

    private void launch(AppItem app) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- editing

    private void showEditDialog(final AppItem app, final List<AppItem> all) {
        String[] options = {"Siirrä vasemmalle", "Siirrä oikealle", "Piilota listalta"};
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(options, (d, which) -> {
                    int idx = all.indexOf(app);
                    if (which == 0 && idx > 0) {
                        Collections.swap(all, idx, idx - 1);
                        saveOrder(all);
                    } else if (which == 1 && idx < all.size() - 1) {
                        Collections.swap(all, idx, idx + 1);
                        saveOrder(all);
                    } else if (which == 2) {
                        Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", new HashSet<String>()));
                        hidden.add(app.component.getPackageName());
                        prefs.edit().putStringSet("hidden", hidden).apply();
                    }
                    rebuildRibbonIfChanged(true);
                    int newIdx = Math.max(0, Math.min(which == 0 ? idx - 1 : which == 1 ? idx + 1 : idx,
                            ribbon.getChildCount() - 1));
                    ribbon.getChildAt(newIdx).requestFocus();
                })
                .show();
    }

    /**
     * One list of every launchable app: tick = shown in the ribbon, untick = hidden. Long-press OK on an
     * app to pick it up, move it with up/down, and press OK to drop it; Save keeps both changes.
     */
    private void showManageDialog() {
        final List<AppItem> all = new ArrayList<>(loadApps(false));
        final int shownCount = all.size();
        all.addAll(loadApps(true));
        final List<String> labels = new ArrayList<>();
        final List<Boolean> on = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            labels.add(all.get(i).label);
            on.add(i < shownCount);
        }
        final int[] grabbed = {-1};
        final boolean[] justGrabbed = {false};
        final ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, labels) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View v = super.getView(position, convertView, parent);
                        v.setBackgroundColor(position == grabbed[0] ? 0x55FFFFFF : Color.TRANSPARENT);
                        return v;
                    }
                };
        final ListView list = new ListView(this);
        final Drawable normalSelector = list.getSelector();
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setAdapter(adapter);
        for (int i = 0; i < on.size(); i++) list.setItemChecked(i, on.get(i));

        final String idleHint = "OK: näytä/piilota.  Pitkä OK: siirrä sovellusta ylös/alas.";
        final TextView hint = new TextView(this);
        hint.setText(idleHint);
        hint.setTextSize(14);
        hint.setPadding(dp(24), dp(8), dp(24), dp(8));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        box.addView(list, new LinearLayout.LayoutParams(-1, -2));

        list.setOnItemClickListener((p, v, pos, id) -> on.set(pos, list.isItemChecked(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            grabbed[0] = pos;
            list.setSelector(new ColorDrawable(Color.TRANSPARENT)); // the highlight follows the app instead
            adapter.notifyDataSetChanged();
            justGrabbed[0] = true; // the key-up that ends this long press must not drop the app
            hint.setText("Siirretään: " + labels.get(pos) + "  (ylös/alas, OK = valmis)");
            return true;
        });

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Näytä, piilota ja järjestä sovelluksia")
                .setView(box)
                .setPositiveButton("Tallenna", (dd, w) -> {
                    Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", new HashSet<String>()));
                    for (int i = 0; i < all.size(); i++) {
                        String pkg = all.get(i).component.getPackageName();
                        if (on.get(i)) hidden.remove(pkg); else hidden.add(pkg);
                    }
                    prefs.edit().putStringSet("hidden", hidden).apply();
                    saveOrder(all);
                    rebuildRibbonIfChanged(true);
                })
                .setNegativeButton("Peruuta", null)
                .create();
        d.setOnKeyListener((dd, keyCode, ev) -> {
            if (grabbed[0] < 0) return false;
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (down && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                moveManaged(grabbed, -1, all, labels, on, adapter, list);
            } else if (down && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                moveManaged(grabbed, 1, all, labels, on, adapter, list);
            } else if (!down && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_BACK)) {
                if (justGrabbed[0]) {
                    justGrabbed[0] = false;
                } else {
                    dropManaged(grabbed, list, adapter, normalSelector);
                    hint.setText(idleHint);
                }
            }
            return true; // while an app is picked up, only these keys do anything
        });
        d.show();
    }

    private void moveManaged(int[] grabbed, int dir, List<AppItem> all, List<String> labels, List<Boolean> on,
                             ArrayAdapter<String> adapter, ListView list) {
        int i = grabbed[0];
        int j = i + dir;
        if (j < 0 || j >= all.size()) return;
        Collections.swap(all, i, j);
        Collections.swap(labels, i, j);
        Collections.swap(on, i, j);
        adapter.notifyDataSetChanged();
        list.setItemChecked(i, on.get(i));
        list.setItemChecked(j, on.get(j));
        grabbed[0] = j;
        // The app moves down the list; the list itself only scrolls if the app would leave the screen.
        if (j <= list.getFirstVisiblePosition() || j >= list.getLastVisiblePosition()) {
            list.smoothScrollToPosition(j);
        }
    }

    private void dropManaged(int[] grabbed, ListView list, ArrayAdapter<String> adapter, Drawable normalSelector) {
        int pos = grabbed[0];
        grabbed[0] = -1;
        list.setSelector(normalSelector);
        adapter.notifyDataSetChanged();
        View row = list.getChildAt(pos - list.getFirstVisiblePosition());
        if (row != null) list.setSelectionFromTop(pos, row.getTop()); // focus lands on the dropped app, no scroll
    }
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { // the remote's menu/options key
            showManageDialog();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- fallback background

    /**
     * Aurora borealis over a snowy ridge, a small lit town and a frozen lake. The real moon (photographs,
     * phase from the calendar) hangs behind the aurora and lights the ice blue; now and then a distant
     * snowmobile headlight crosses. The aurora is computed per pixel into a tiny bitmap that the GPU
     * smooths when scaled up, which gives the soft glow, and is screened over the sky; the curtains
     * fold, lean and breathe slowly.
     * Nothing changes faster than about once per two seconds, so it cannot flash.
     */
    private static class AuroraView extends View {
        private static final int W = 240;
        private static final int H = 135;
        private static final int CURTAINS = 3;
        private static final int STARS = 90;
        private static final int LIGHTS = 40;
        private static final double SYNODIC_DAYS = 29.530588853;
        private static final long NEW_MOON_REF_MS = 947182440000L; // new moon 2000-01-06 18:14 UTC
        private static final float MOON_X = 0.16f; // position and radius as fractions of the screen
        private static final float MOON_Y = 0.20f;
        private static final float MOON_R = 0.06f;
                // Per curtain: baseline (fraction of height), opacity, and colours at the hem, in the bright
        // body, higher up and at the top of the rays. Saturated but transparent, so it glows without
        // getting bright.
        private static final float[] LINE = {0.58f, 0.50f, 0.64f};
        private static final float[] LEVEL = {0.75f, 0.55f, 0.5f};
        private static final int[][] HEM = {{190, 80, 255}, {150, 70, 255}, {170, 60, 255}};
        private static final int[][] BODY = {{30, 255, 110}, {50, 255, 150}, {150, 70, 255}};
        private static final int[][] UPPER = {{30, 225, 120}, {50, 190, 170}, {120, 60, 230}};
        private static final int[][] TOP = {{110, 90, 240}, {130, 80, 250}, {90, 60, 200}};
        private static final float TO_IDX = 1024f / (2f * (float) Math.PI);
        private static final float[] SIN = new float[1024];

        static {
            for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i * 2 * Math.PI / 1024);
        }

        private final int horizon = (int) (H * 0.70f);
        private final int skyRows = horizon + 1;
        // The sky holds only the aurora's light (screened over moon and stars); the lake is opaque.
        private final Bitmap skyBmp = Bitmap.createBitmap(W, skyRows, Bitmap.Config.ARGB_8888);
        private final Bitmap lakeBmp = Bitmap.createBitmap(W, H - skyRows, Bitmap.Config.ARGB_8888);
        private final int[] pixelsSky = new int[W * skyRows];
        private final int[] pixelsLake = new int[W * (H - skyRows)];
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint screenPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint skyPaint = new Paint();
        private final Paint shorePaint = new Paint();
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect srcSky = new Rect(0, 0, W, skyRows);
        private final Rect srcLake = new Rect(0, 0, W, H - skyRows);
        private final Rect dstSky = new Rect();
        private final Rect dstLake = new Rect();

        // Colour/opacity of a curtain by height above its lower edge (0 = hem, 1 = top of the rays).
        private final float[][] tabR = new float[CURTAINS][256];
        private final float[][] tabG = new float[CURTAINS][256];
        private final float[][] tabB = new float[CURTAINS][256];
        private final float[][] tabA = new float[CURTAINS][256];

        private final float[][] base = new float[CURTAINS][W];
        private final float[][] invH = new float[CURTAINS][W];
        private final float[][] lean = new float[CURTAINS][W];
        private final float[][] level = new float[CURTAINS][W];
        private final float[] rt1 = new float[CURTAINS];
        private final float[] rt2 = new float[CURTAINS];
        private final float[] glow = new float[W];

        // Moon: real photographs cut from a phase sheet and laid out as an atlas of square frames.
        private static final int MAX_FRAMES = 64;
        private Bitmap atlas;
        private int frameSize;
        private int atlasCols;
        private int frameCount;
        private float discR; // radius of the moon within a frame, in px
        private final float[] frameIllum = new float[MAX_FRAMES];
        private final char[] frameSide = new char[MAX_FRAMES];
        private Bitmap moonBmp;
        private int[] moonPixels;
        private final Rect moonDst = new Rect();
        private long moonStamp = -1; // minute of the last moon update
        private float moonLight;     // 0 = new moon .. 1 = full moon

        private final Random rnd = new Random();
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean sledActive;
        private long sledStart;
        private long sledDur;
        private long sledNext;
        private int sledDir;
        private float sledLane;

        private final float[] starX = new float[STARS];
        private final float[] starY = new float[STARS];
        private final float[] starA = new float[STARS];
        private final float[] starPhase = new float[STARS];
        private final float[] lightX = new float[LIGHTS];
        private final float[] lightY = new float[LIGHTS];
        private final float[] lightA = new float[LIGHTS];
        private final long start = System.currentTimeMillis();

        AuroraView(Context c) {
            super(c);
            setBackgroundColor(0xFF02040A);
            for (int i = 0; i < CURTAINS; i++) buildTable(i);
            screenPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            Random r = new Random(7);
            for (int i = 0; i < STARS; i++) {
                starX[i] = r.nextFloat();
                starY[i] = r.nextFloat() * 0.6f;
                starA[i] = 90 + r.nextInt(140);
                starPhase[i] = r.nextFloat() * 6.28f;
            }
            for (int i = 0; i < LIGHTS; i++) {
                lightX[i] = 0.58f + 0.32f * r.nextFloat();
                lightY[i] = r.nextFloat();
                lightA[i] = 120 + r.nextInt(130);
            }
            loadMoonAtlas(c);
            initSled();
            meteorNext = start + 7000 + rnd.nextInt(8000);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            skyPaint.setShader(new LinearGradient(0, 0, 0, skyRows * h / (float) H,
                    0xFF01030A, 0xFF061118, Shader.TileMode.CLAMP));
            // Soft shoreline: the dark of the ridge fades out over the top of the ice.
            float lakeTop = skyRows * h / (float) H;
            shorePaint.setShader(new LinearGradient(0, lakeTop - 2, 0, lakeTop + 0.035f * h,
                    0xCC03080C, 0x0003080C, Shader.TileMode.CLAMP));
            // Hazy hilltops: the ridge is see-through at its highest points and solid lower down.
            float shoreY = horizon * h / (float) H;
            ridgePaint.setShader(new LinearGradient(0, shoreY - 0.085f * h, 0, shoreY + 3,
                    new int[]{0x5503080C, 0xB303080C, 0xFF03080C}, new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
        }

        // ---- moon

        private static float smooth(float a, float b, float x) {
            float k = clamp((x - a) / (b - a), 0f, 1f);
            return k * k * (3f - 2f * k);
        }
        private void loadMoonAtlas(Context c) {
            try (InputStream meta = c.getAssets().open("moon_phases.txt");
                 InputStream img = c.getAssets().open("moon_phases.png")) {
                BufferedReader r = new BufferedReader(new InputStreamReader(meta));
                String[] head = r.readLine().trim().split(" ");
                frameSize = Integer.parseInt(head[0]);
                atlasCols = Integer.parseInt(head[1]);
                frameCount = Math.min(MAX_FRAMES, Integer.parseInt(head[2]));
                discR = Float.parseFloat(head[3]);
                for (int i = 0; i < frameCount; i++) {
                    String[] p = r.readLine().trim().split(" ");
                    frameIllum[i] = Float.parseFloat(p[0]);
                    frameSide[i] = p[1].charAt(0);
                }
                atlas = BitmapFactory.decodeStream(img);
                moonBmp = Bitmap.createBitmap(frameSize, frameSize, Bitmap.Config.ARGB_8888);
                moonPixels = new int[frameSize * frameSize];
            } catch (Exception e) {
                atlas = null; // no moon; the rest of the scene still works
            }
        }

        private int[] framePixels(int idx) {
            int[] px = new int[frameSize * frameSize];
            atlas.getPixels(px, 0, frameSize, (idx % atlasCols) * frameSize, (idx / atlasCols) * frameSize,
                    frameSize, frameSize);
            return px;
        }

        /**
         * Picks the moon for the current phase; the phase moves slowly, so once a minute is plenty. Every
         * phase is a real photograph: the frame whose illumination is closest on the right side (waxing
         * moons are lit on the right, waning ones on the left), or a crossfade of the two nearest frames
         * where the set has a gap.
         */
        private void updateMoon(long nowMs) {
            long minute = nowMs / 60000L;
            if (minute == moonStamp) return;
            moonStamp = minute;
            double days = (nowMs - NEW_MOON_REF_MS) / 86400000.0;
            double f = (days / SYNODIC_DAYS) % 1.0;
            if (f < 0) f += 1.0;
            moonLight = (float) ((1 - Math.cos(2 * Math.PI * f)) / 2);
            if (atlas == null) return;

            char side = f < 0.5 ? 'R' : 'L';
            int lo = -1;
            int hi = -1; // the nearest frames at or below / at or above the wanted illumination
            for (int i = 0; i < frameCount; i++) {
                if (frameSide[i] != 'B' && frameSide[i] != side) continue; // new and full moon suit both sides
                float v = frameIllum[i];
                if (v <= moonLight && (lo < 0 || v > frameIllum[lo])) lo = i;
                if (v >= moonLight && (hi < 0 || v < frameIllum[hi])) hi = i;
            }
            if (lo < 0) lo = hi;
            if (hi < 0) hi = lo;
            if (lo < 0) return;
            float gap = frameIllum[hi] - frameIllum[lo];
            if (gap <= 0.07f) {
                moonPixels = framePixels(moonLight - frameIllum[lo] < frameIllum[hi] - moonLight ? lo : hi);
            } else {
                float k = (moonLight - frameIllum[lo]) / gap;
                int[] a = framePixels(lo);
                int[] b = framePixels(hi);
                for (int i = 0; i < moonPixels.length; i++) {
                    int p = a[i];
                    int q = b[i];
                    int r = to255(((p >> 16) & 255) * (1 - k) + ((q >> 16) & 255) * k);
                    int g = to255(((p >> 8) & 255) * (1 - k) + ((q >> 8) & 255) * k);
                    int bl = to255((p & 255) * (1 - k) + (q & 255) * k);
                    moonPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
                }
            }
            // The photographs are neutral grey; tint them to a warm white.
            for (int i = 0; i < moonPixels.length; i++) {
                int p = moonPixels[i];
                moonPixels[i] = 0xFF000000 | (to255(((p >> 16) & 255) * 1.06f) << 16)
                        | (to255(((p >> 8) & 255) * 1.0f) << 8) | to255((p & 255) * 0.84f);
            }
            moonBmp.setPixels(moonPixels, 0, frameSize, 0, 0, frameSize, frameSize);
        }
        /** The moon is screened onto the sky (its black surround adds nothing), and the aurora goes over it. */
        private void drawMoon(Canvas canvas, float w, float h) {
            if (moonBmp == null) return;
            float cx = MOON_X * w;
            float cy = MOON_Y * h;
            float half = MOON_R * h * (frameSize / 2f) / discR;
            moonDst.set((int) (cx - half), (int) (cy - half), (int) (cx + half), (int) (cy + half));
            canvas.drawBitmap(moonBmp, null, moonDst, screenPaint);
        }
        // ---- ridge

        private final Path ridgePath = new Path();
        private final Path ridgeTop = new Path();
        private final Paint ridgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ridgeRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ridgeFeatherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private void drawRidge(Canvas canvas, float w, float h) {
            float shoreY = horizon * h / H;
            ridgePath.reset();
            ridgeTop.reset();
            ridgePath.moveTo(0, shoreY + 3);
            for (float x = 0; x <= w + 6; x += 6f) {
                float xb = x / w * W;
                float n = 0.5f * sin(xb * 0.035 + 0.6) + 0.3f * sin(xb * 0.09 + 2.0) + 0.2f * sin(xb * 0.21 + 4.0);
                float y = shoreY - h * (0.045f + 0.03f * n);
                ridgePath.lineTo(x, y);
                if (x == 0) ridgeTop.moveTo(x, y); else ridgeTop.lineTo(x, y);
            }
            ridgePath.lineTo(w + 6, shoreY + 3);
            ridgePath.close();
            canvas.drawPath(ridgePath, ridgePaint);
            // Feather the top edge into the sky with a few wide, faint strokes of the ridge colour.
            ridgeFeatherPaint.setStyle(Paint.Style.STROKE);
            ridgeFeatherPaint.setStrokeJoin(Paint.Join.ROUND);
            ridgeFeatherPaint.setColor(Color.argb(30, 3, 8, 12));
            ridgeFeatherPaint.setStrokeWidth(26f);
            canvas.drawPath(ridgeTop, ridgeFeatherPaint);
            ridgeFeatherPaint.setColor(Color.argb(45, 3, 8, 12));
            ridgeFeatherPaint.setStrokeWidth(12f);
            canvas.drawPath(ridgeTop, ridgeFeatherPaint);
            // A wide, faint warm rim where the moon catches the snow.
            ridgeRimPaint.setStyle(Paint.Style.STROKE);
            ridgeRimPaint.setStrokeWidth(14f);
            ridgeRimPaint.setColor(Color.argb((int) (3 + 12 * moonLight), 215, 205, 175));
            canvas.drawPath(ridgeTop, ridgeRimPaint);
        }

        // ---- shooting stars

        private boolean meteorOn;
        private long meteorStart;
        private long meteorDur;
        private long meteorNext;
        private float mX0;
        private float mY0;
        private float mDx;
        private float mDy;
        private float mLen;
        private final Paint meteorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        /**
         * A rare, brief shooting star: a white dot with a short fading tail, moving fast in a random
         * direction for a random distance. One at a time and at least 12 s apart, fading in and out
         * smoothly, so it never flashes.
         */
        private void drawMeteor(Canvas canvas, float w, float h, long now) {
            if (!meteorOn) {
                if (now < meteorNext) return;
                meteorOn = true;
                meteorStart = now;
                double ang = rnd.nextDouble() * 2 * Math.PI;
                mDx = (float) Math.cos(ang);
                mDy = (float) Math.sin(ang);
                mLen = w * (0.06f + 0.24f * rnd.nextFloat());          // 6-30 % of the screen width
                float speed = w * (0.35f + 0.55f * rnd.nextFloat());   // px per second
                meteorDur = Math.max(400L, (long) (mLen / speed * 1000f));
                mX0 = w * (0.08f + 0.84f * rnd.nextFloat());
                mY0 = h * (0.03f + 0.42f * rnd.nextFloat());
            }
            float p = (now - meteorStart) / (float) meteorDur;
            if (p >= 1f) {
                meteorOn = false;
                meteorNext = now + 12000 + rnd.nextInt(33000);
                return;
            }
            float env = smooth(0f, 0.18f, p) * (1f - smooth(0.55f, 1f, p));
            float hx = mX0 + mDx * mLen * p;
            float hy = mY0 + mDy * mLen * p;
            float tail = Math.min(mLen * p, w * 0.06f);
            int segs = 8;
            for (int i = 0; i < segs; i++) {
                float a0 = i / (float) segs;
                float a1 = (i + 1) / (float) segs;
                meteorPaint.setColor(Color.argb((int) (200 * env * a1 * a1), 235, 242, 255));
                meteorPaint.setStrokeWidth(1f + 1.6f * a1);
                canvas.drawLine(hx - mDx * tail * (1 - a0), hy - mDy * tail * (1 - a0),
                        hx - mDx * tail * (1 - a1), hy - mDy * tail * (1 - a1), meteorPaint);
            }
            meteorPaint.setColor(Color.argb((int) (70 * env), 220, 235, 255));
            canvas.drawCircle(hx, hy, 5.5f, meteorPaint);
            meteorPaint.setColor(Color.argb((int) (255 * env), 255, 255, 255));
            canvas.drawCircle(hx, hy, 2.2f, meteorPaint);
        }

        // ---- snowmobile

        private void initSled() {
            glowPaint.setShader(new RadialGradient(0, 0, 6, 0xAAFF9A3A, 0x00FF9A3A, Shader.TileMode.CLAMP));
            corePaint.setColor(Color.rgb(255, 150, 50));
            trailPaint.setColor(Color.argb(230, 0, 0, 0));
            sledNext = start + 5000 + rnd.nextInt(8000);
        }

        /** An occasional snowmobile crossing the lake, headlight steady (no blinking). */
        private void drawSled(Canvas canvas, float w, float h, long now) {
            if (!sledActive) {
                if (now < sledNext) return;
                sledActive = true;
                sledStart = now;
                sledDur = 45000 + rnd.nextInt(25000); // slow: far away
                sledDir = rnd.nextBoolean() ? 1 : -1;
                sledLane = rnd.nextFloat();
            }
            float p = (now - sledStart) / (float) sledDur;
            if (p >= 1f) {
                sledActive = false;
                sledNext = now + 25000 + rnd.nextInt(45000);
                return;
            }
            float s = h / 1080f;
            float margin = 30f;
            float x = sledDir > 0 ? -margin + p * (w + 2 * margin) : w + margin - p * (w + 2 * margin);
            float y = horizon * h / H + (0.004f + 0.008f * sledLane) * h;
            canvas.save();
            canvas.translate(x, y);
            canvas.scale(s, s);
            canvas.drawCircle(0, 0, 6, glowPaint);
            canvas.drawCircle(0, 0, 1.3f, corePaint);
            // The sled and what it tows: a few black dots trailing behind the headlight.
            for (int k = 1; k <= 4; k++) {
                canvas.drawCircle(-sledDir * (3.5f + k * 4f), 0.5f, 1.5f - 0.15f * k, trailPaint);
            }
            canvas.restore();
        }

        private void buildTable(int c) {
            float[] ku = {-0.15f, 0.0f, 0.06f, 0.2f, 0.5f, 1.0f};
            float[] ka = {0f, 0.6f, 0.85f, 1f, 0.45f, 0f};
            float[][] kc = new float[6][3];
            for (int j = 0; j < 3; j++) {
                kc[0][j] = HEM[c][j];
                kc[1][j] = HEM[c][j];
                kc[2][j] = (HEM[c][j] + BODY[c][j]) / 2f;
                kc[3][j] = BODY[c][j];
                kc[4][j] = UPPER[c][j];
                kc[5][j] = TOP[c][j];
            }
            for (int i = 0; i < 256; i++) {
                float u = -0.15f + 1.15f * i / 255f;
                int k = 0;
                while (k < 4 && u > ku[k + 1]) k++;
                float f = clamp((u - ku[k]) / (ku[k + 1] - ku[k]), 0f, 1f);
                tabA[c][i] = ka[k] + (ka[k + 1] - ka[k]) * f;
                tabR[c][i] = kc[k][0] + (kc[k + 1][0] - kc[k][0]) * f;
                tabG[c][i] = kc[k][1] + (kc[k + 1][1] - kc[k][1]) * f;
                tabB[c][i] = kc[k][2] + (kc[k + 1][2] - kc[k][2]) * f;
            }
        }
        private static float sin(double v) {
            return (float) Math.sin(v);
        }

        private static float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private static int to255(float v) {
            return v < 0f ? 0 : v > 255f ? 255 : (int) v;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long nowMs = System.currentTimeMillis();
            updateMoon(nowMs);
            float moonX = MOON_X * W;
            float t = (nowMs - start) / 2000f; // half speed: slow and calm
            float breathe = 0.9f + 0.1f * sin(t * 0.25f);
            float reachPx = H * 0.62f;

            // Per-column curtain shape for this moment.
            for (int c = 0; c < CURTAINS; c++) {
                float ph = c * 2.1f;
                rt1[c] = t * 0.35f + ph;
                rt2[c] = t * 0.22f + ph * 2f;
                for (int x = 0; x < W; x++) {
                    base[c][x] = H * LINE[c]
                            + H * 0.06f * sin(x * 0.030 + t * 0.16 + ph)
                            + H * 0.03f * sin(x * 0.075 - t * 0.23 + ph * 2)
                            + H * 0.012f * sin(x * 0.16 + t * 0.4 + ph);
                    float hr = clamp(0.6f + 0.35f * sin(x * 0.021 + t * 0.11 + ph * 3)
                            + 0.1f * sin(x * 0.05 - t * 0.2), 0.25f, 1f);
                    invH[c][x] = 1f / (reachPx * hr);
                    lean[c][x] = 0.12f * sin(x * 0.02 + t * 0.13 + ph * 1.3);
                    level[c][x] = LEVEL[c] * breathe
                            * clamp(0.7f + 0.3f * sin(x * 0.02 + t * 0.12 + ph * 1.7), 0.3f, 1f);
                }
            }
            for (int x = 0; x < W; x++) {
                float g = 0f;
                for (int c = 0; c < CURTAINS; c++) {
                    float rv = 0.5f + 0.25f * SIN[(int) ((x * 1.15f + rt1[c]) * TO_IDX) & 1023]
                            + 0.25f * SIN[(int) ((x * 2.9f - rt2[c]) * TO_IDX) & 1023];
                    g += level[c][x] * (0.35f + 0.65f * rv);
                }
                glow[x] = g / CURTAINS;
            }

            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    float r = 0f;
                    float g = 0f;
                    float b = 0f;
                    if (y <= horizon) {
                        for (int c = 0; c < CURTAINS; c++) {
                            float dy = base[c][x] - y;
                            float hn = dy * invH[c][x];
                            if (hn < -0.15f || hn > 1f) continue;
                            int idx = (int) ((hn + 0.15f) * 221.7f);
                            float xs = x + lean[c][x] * dy;
                            float rv = 0.5f + 0.25f * SIN[(int) ((xs * 0.55f + rt1[c]) * TO_IDX) & 1023]
                                    + 0.25f * SIN[(int) ((xs * 1.1f - rt2[c]) * TO_IDX) & 1023];
                            float w = tabA[c][idx] * (0.85f + 0.15f * rv) * level[c][x];
                            r += tabR[c][idx] * w;
                            g += tabG[c][idx] * w;
                            b += tabB[c][idx] * w;
                        }
                    } else {
                        // Still water mirroring the glow above.
                        float k = (y - horizon) / (float) (H - horizon);
                        float ripple = 0.9f + 0.1f * SIN[(int) ((x * 0.4f - t * 0.4f) * TO_IDX) & 1023];
                        float refl = glow[x] * 0.30f * (1 - k) * (1 - k) * ripple;
                        // Snow-covered ice scatters moonlight into a broad, smooth, warm-white glow that fades
                        // with distance, instead of a sparkling path.
                        float fall = (1f - k) * (1f - 0.35f * k);
                        float dxm = (x - moonX) * (1f / 22f);
                        float spread = 1f / (1f + dxm * dxm);
                        float wash = moonLight * (0.25f + 0.75f * spread) * fall;
                        r = 2 + 100 * refl + 85 * wash;
                        g = 11 + 230 * refl + 76 * wash;
                        b = 15 + 150 * refl + 58 * wash;
                    }
                    int argb = 0xFF000000 | (to255(r) << 16) | (to255(g) << 8) | to255(b);
                    if (y < skyRows) pixelsSky[y * W + x] = argb;
                    else pixelsLake[(y - skyRows) * W + x] = argb;
                }
            }
            skyBmp.setPixels(pixelsSky, 0, W, 0, 0, W, skyRows);
            lakeBmp.setPixels(pixelsLake, 0, W, 0, 0, W, H - skyRows);
            float w = getWidth();
            float h = getHeight();
            float skyBottom = skyRows * h / H;

            // Back to front: night sky, stars, moon, then the aurora's light over all of them.
            canvas.drawRect(0, 0, w, skyBottom, skyPaint);
            float moonCx = MOON_X * w;
            float moonCy = MOON_Y * h;
            float moonR = MOON_R * h;
            for (int i = 0; i < STARS; i++) {
                if (Math.hypot(starX[i] * w - moonCx, starY[i] * h - moonCy) < moonR * 1.2f) continue;
                float a = starA[i] * (0.75f + 0.25f * sin(t * 0.7f + starPhase[i]));
                dotPaint.setColor(Color.argb((int) a, 220, 235, 255));
                canvas.drawCircle(starX[i] * w, starY[i] * h, i % 5 == 0 ? 2.2f : 1.4f, dotPaint);
            }
            drawMoon(canvas, w, h);
            dstSky.set(0, 0, (int) w, (int) Math.ceil(skyBottom));
            canvas.drawBitmap(skyBmp, srcSky, dstSky, screenPaint);
            drawMeteor(canvas, w, h, nowMs);
            dstLake.set(0, (int) skyBottom, (int) w, (int) h);
            canvas.drawBitmap(lakeBmp, srcLake, dstLake, bmpPaint);
            canvas.drawRect(0, skyBottom - 2, w, skyBottom + 0.035f * h, shorePaint);
            drawRidge(canvas, w, h);

            // Lights of a small town along the far shore.
            float shoreY = horizon * h / H;
            for (int i = 0; i < LIGHTS; i++) {
                float a = lightA[i] * (0.85f + 0.15f * sin(t * 0.5f + i));
                dotPaint.setColor(Color.argb((int) a, 255, 190, 110));
                canvas.drawCircle(lightX[i] * w, shoreY - 2 + lightY[i] * 5, i % 7 == 0 ? 2.4f : 1.5f, dotPaint);
            }
            drawSled(canvas, w, h, nowMs);
            postInvalidateDelayed(40);
        }
    }
}