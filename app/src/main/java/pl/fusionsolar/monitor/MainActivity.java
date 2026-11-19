package pl.fusionsolar.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight native, remote-friendly, fullscreen PV dashboard for Android 6+. */
public final class MainActivity extends Activity {
    private static final String PREFS = "fusion_solar_local_config";
    private static final String KEY_URL = "kiosk_url";
    private static final String KEY_CACHE = "last_kpi";
    private static final String KEY_CACHE_TIME = "last_kpi_time";
    private static final long REFRESH_MS = 10 * 60 * 1000L;
    private static final int WHITE = Color.rgb(239, 247, 250);
    private static final int SOFT = Color.rgb(158, 183, 200);
    private static final int GOLD = Color.rgb(255, 199, 74);
    private static final int GREEN = Color.rgb(113, 217, 170);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private TextView powerValue, todayValue, monthValue, yearValue, totalValue;
    private TextView statusText, lastSyncText;
    private boolean loading = false;
    private boolean active = false;
    private final Runnable timer = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refresh();
            mainHandler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildDashboard();
        showCachedValues();
        boolean changed = applyConfigurationFromIntent(getIntent());
        if (changed) status("Nowy adres Kiosk zapisany na projektorze.", GREEN);
        if (prefs.getString(KEY_URL, "").isEmpty()) {
            mainHandler.postDelayed(() -> openSettings(), 400);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (applyConfigurationFromIntent(intent)) {
            status("Adres zapisany. Pobieram dane…", GREEN);
            refresh();
        }
    }

    private boolean applyConfigurationFromIntent(Intent intent) {
        if (intent == null) return false;
        String url = intent.getStringExtra("kiosk_url");
        String b64 = intent.getStringExtra("kiosk_url_b64");
        if (b64 != null && !b64.isEmpty()) {
            try { url = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8); }
            catch (Exception ex) { status("Nieprawidłowo zakodowany adres Kiosk.", GOLD); return false; }
        }
        if (url == null || url.trim().isEmpty()) return false;
        try {
            FusionSolarClient.endpointFromKioskUrl(url);
            prefs.edit().putString(KEY_URL, url.trim()).remove(KEY_CACHE).remove(KEY_CACHE_TIME).apply();
            clearValues();
            return true;
        } catch (Exception ex) {
            status(ex.getMessage(), GOLD);
            return false;
        }
    }

    private void buildDashboard() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(22), dp(28), dp(18));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(7, 22, 35), Color.rgb(10, 37, 50), Color.rgb(7, 22, 35)}));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(62)));

        TextView sun = label("☀", 31, GOLD, true);
        header.addView(sun, new LinearLayout.LayoutParams(dp(56), -2));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        titles.addView(label("FUSIONSOLAR", 23, WHITE, true));
        titles.addView(label("MONITOR FOTOWOLTAIKI  •  XIAOMI", 11, SOFT, false));

        Button settingsButton = button("USTAW URL");
        settingsButton.setOnClickListener(v -> openSettings());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(136), dp(46)));
        Button reloadButton = button("ODŚWIEŻ");
        reloadButton.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams reloadParams = new LinearLayout.LayoutParams(dp(136), dp(46));
        reloadParams.leftMargin = dp(12);
        header.addView(reloadButton, reloadParams);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(12), dp(12), dp(12), dp(12));
        hero.setBackground(rounded(Color.rgb(13, 43, 57), dp(20), Color.rgb(25, 72, 85)));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, 0, 1.03f);
        heroParams.topMargin = dp(16);
        root.addView(hero, heroParams);
        hero.addView(label("AKTUALNA MOC PRODUKCJI", 18, SOFT, true));
        powerValue = label("— kW", 72, GOLD, true);
        powerValue.setGravity(Gravity.CENTER);
        hero.addView(powerValue, new LinearLayout.LayoutParams(-1, -2));
        hero.addView(label("DANE Z CHMURY FUSIONSOLAR", 12, SOFT, false));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 0, 0.95f);
        rowParams.topMargin = dp(18);
        root.addView(row, rowParams);
        todayValue = addCard(row, "DZISIAJ", "— kWh", GOLD);
        monthValue = addCard(row, "TEN MIESIĄC", "— kWh", WHITE);
        yearValue = addCard(row, "TEN ROK", "— kWh", WHITE);
        totalValue = addCard(row, "OD POCZĄTKU", "— kWh", GREEN);

        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footParams = new LinearLayout.LayoutParams(-1, dp(78));
        footParams.topMargin = dp(10);
        root.addView(foot, footParams);
        LinearLayout msg = new LinearLayout(this);
        msg.setOrientation(LinearLayout.VERTICAL);
        foot.addView(msg, new LinearLayout.LayoutParams(0, -2, 1));
        statusText = label("Oczekiwanie na adres Kiosk", 14, SOFT, false);
        statusText.setSingleLine(false);
        msg.addView(statusText);
        lastSyncText = label("Dane FusionSolar mogą mieć ok. 30 min opóźnienia", 11, SOFT, false);
        msg.addView(lastSyncText);
        TextView keys = label("OK: ODŚWIEŻ    •    MENU: USTAW URL", 11, SOFT, false);
        keys.setGravity(Gravity.RIGHT);
        foot.addView(keys, new LinearLayout.LayoutParams(-2, -2));
    }

    private TextView addCard(LinearLayout parent, String heading, String initial, int color) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(12), dp(8), dp(12));
        card.setBackground(rounded(Color.rgb(18, 45, 61), dp(18), Color.rgb(33, 72, 89)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (parent.getChildCount() > 0) lp.leftMargin = dp(12);
        parent.addView(card, lp);
        TextView label = label(heading, 13, SOFT, true);
        label.setGravity(Gravity.CENTER);
        card.addView(label);
        TextView value = label(initial, 28, color, true);
        value.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(-1, -2);
        valLp.topMargin = dp(12);
        card.addView(value, valLp);
        return value;
    }

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(s);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextColor(WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setBackground(rounded(Color.rgb(26, 67, 83), dp(12), Color.rgb(61, 111, 129)));
        b.setFocusable(true);
        return b;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + 0.5f); }

    private void status(String s, int color) {
        if (statusText != null) {
            statusText.setText(s);
            statusText.setTextColor(color);
        }
    }

    private void clearValues() {
        if (powerValue == null) return;
        powerValue.setText("— kW");
        todayValue.setText("— kWh");
        monthValue.setText("— kWh");
        yearValue.setText("— kWh");
        totalValue.setText("— kWh");
    }

    private void showCachedValues() {
        String cached = prefs.getString(KEY_CACHE, "");
        if (cached.isEmpty()) return;
        try {
            showMetrics(new JSONObject(cached));
            DateFormat df = android.text.format.DateFormat.getDateFormat(this);
            String time = android.text.format.DateFormat.getTimeFormat(this).format(
                    new Date(prefs.getLong(KEY_CACHE_TIME, 0)));
            status("Wczytano ostatnie zapisane dane (mogą być nieaktualne)", GOLD);
            lastSyncText.setText("Ostatnie udane pobranie: " + df.format(new Date(prefs.getLong(KEY_CACHE_TIME, 0))) + " " + time);
        } catch (Exception ignored) { }
    }

    private void showMetrics(JSONObject kpi) {
        powerValue.setText(format(kpi, "realTimePower", "kW", false));
        todayValue.setText(format(kpi, "dailyEnergy", "kWh", true));
        monthValue.setText(format(kpi, "monthEnergy", "kWh", true));
        yearValue.setText(format(kpi, "yearEnergy", "kWh", true));
        totalValue.setText(format(kpi, "cumulativeEnergy", "kWh", true));
    }

    private String format(JSONObject kpi, String key, String unit, boolean asEnergy) {
        Object raw = kpi.opt(key);
        if (raw == null || raw == JSONObject.NULL || String.valueOf(raw).trim().isEmpty()) return "— " + unit;
        try {
            double value = Double.parseDouble(String.valueOf(raw).trim().replace(',', '.'));
            if (Double.isNaN(value) || Double.isInfinite(value)) return "— " + unit;
            if (asEnergy && Math.abs(value) >= 1000) {
                value /= 1000.0;
                unit = "MWh";
            }
            NumberFormat nf = NumberFormat.getNumberInstance(new Locale("pl", "PL"));
            nf.setGroupingUsed(true);
            nf.setMinimumFractionDigits(0);
            nf.setMaximumFractionDigits(asEnergy ? 2 : 3);
            return nf.format(value) + " " + unit;
        } catch (Exception ignored) {
            return "— " + unit;
        }
    }

    private void refresh() {
        String kioskUrl = prefs.getString(KEY_URL, "");
        if (kioskUrl.isEmpty()) { status("Ustaw URL w menu lub przez ADB.", GOLD); return; }
        if (loading) return;
        loading = true;
        status("Łączenie z FusionSolar…", SOFT);
        worker.submit(() -> {
            JSONObject data = null;
            String error = null;
            try { data = FusionSolarClient.fetch(getApplicationContext(), kioskUrl); }
            catch (Exception ex) {
                error = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "nieznany błąd" : ex.getMessage());
                // Do not log the kiosk link or token, which allows access to private plant data.
                Log.e("FusionSolarMonitor", "Błąd pobierania: " + ex.getClass().getSimpleName());
            }
            final JSONObject result = data;
            final String failure = error;
            mainHandler.post(() -> {
                loading = false;
                if (isFinishing()) return;
                if (result != null) {
                    long now = System.currentTimeMillis();
                    prefs.edit().putString(KEY_CACHE, result.toString()).putLong(KEY_CACHE_TIME, now).apply();
                    showMetrics(result);
                    status("Połączono z FusionSolar  ●", GREEN);
                    lastSyncText.setText("Sprawdzono: " + android.text.format.DateFormat.getTimeFormat(this).format(new Date(now)) +
                            "  •  Dane chmurowe mogą być opóźnione ~30 min");
                } else {
                    status(failure == null ? "Nie udało się pobrać danych." : failure, GOLD);
                    if (prefs.contains(KEY_CACHE)) {
                        lastSyncText.setText("Wyświetlane są ostatnie zapisane dane.");
                    }
                }
            });
        });
    }

    private void openSettings() {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(14);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setHint("https://...fusionsolar.huawei.com/...#/kiosk?kk=...");
        edit.setText(prefs.getString(KEY_URL, ""));
        edit.setSelectAllOnFocus(true);
        FrameLayout frame = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(22), dp(4), dp(22), dp(4));
        frame.addView(edit, lp);
        new AlertDialog.Builder(this)
                .setTitle("Adres FusionSolar Kiosk")
                .setMessage("Wklej pełny URL Kiosk. Link pozostaje tylko w pamięci projektora. Najwygodniej ustawić go przez ADB (opis w README).")
                .setView(frame)
                .setPositiveButton("Zapisz", (dialog, which) -> {
                    String input = edit.getText().toString().trim();
                    try {
                        FusionSolarClient.endpointFromKioskUrl(input);
                        prefs.edit().putString(KEY_URL, input).remove(KEY_CACHE).remove(KEY_CACHE_TIME).apply();
                        clearValues();
                        refresh();
                    } catch (Exception ex) {
                        status(ex.getMessage(), GOLD);
                    }
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { openSettings(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (getCurrentFocus() == null || !(getCurrentFocus() instanceof Button)) {
                refresh();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        mainHandler.removeCallbacks(timer);
        refresh();
        mainHandler.postDelayed(timer, REFRESH_MS);
    }

    @Override protected void onStop() {
        active = false;
        mainHandler.removeCallbacks(timer);
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
