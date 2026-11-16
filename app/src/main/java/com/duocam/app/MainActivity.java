package com.duocam.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.duocam.app.CameraScanner.Slot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private static final int YELLOW = Color.rgb(255, 214, 10);

    private final Handler ui = new Handler(Looper.getMainLooper());

    private SurfaceView surfaceView;
    private volatile Surface previewSurface;
    private LinearLayout timerBox;
    private TextView timerText;
    private RecordButton recBtn;
    private final EnumMap<Slot, TextView> lensBtns = new EnumMap<>(Slot.class);

    // нові елементи
    private TextView btnStab, btnTorch, btnPro, zoomLabel;
    private ZoomSlider zoomSlider;
    private View proPanel;
    private Switch swManual, swFocus;
    private SeekBar sbIso, sbShutter, sbFocus, sbEv;
    private TextView tvIso, tvShutter, tvFocus, tvEv, btnWb;
    private boolean proUpdating;
    private int[] wbModes = new int[0];
    private int wbIndex;

    private final ProState pro = new ProState();
    private int orientMode;   // 0 - авто, 1 - 16:9 (горизонтально), 2 - 9:16 (вертикально)
    private FrameLayout rootLayout;
    private LinearLayout toolsRow, zoomBox, lensRow;
    private TextView btnOrient;
    private final Stabilizer.Config stabCfg = new Stabilizer.Config();
    private Stabilizer stabilizer;

    private SharedPreferences prefs;
    private CameraManager cm;
    private List<CamInfo> allCams = new ArrayList<>();
    private Map<Slot, CamInfo> slotMap = new EnumMap<>(Slot.class);
    private Slot selected = Slot.MAIN;
    private Slot switchingTo = null;
    private boolean retried = false;
    private boolean askedPerms = false;

    private volatile GlRenderer renderer;
    private volatile CamController camCtl;
    private Recorder recorder;
    private boolean recording;
    private long recStartMs;

    // ---------------------------------------------------------------- життєвий цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        prefs = getSharedPreferences("duocam", MODE_PRIVATE);
        stabCfg.enabled = prefs.getBoolean("stab_on", false);
        stabCfg.strength = prefs.getFloat("stab_strength", 0.5f);
        stabCfg.roll = prefs.getBoolean("stab_roll", true);
        stabCfg.invert = prefs.getBoolean("stab_invert", false);
        stabCfg.syncMs = prefs.getInt("stab_sync", 0);
        orientMode = prefs.getInt("orient_mode", 0);
        stabilizer = new Stabilizer(this);

        setContentView(buildUi());
        applyOrientationMode();

        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        dm.registerDisplayListener(displayListener, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyLayout();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!askedPerms) {
                askedPerms = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                        REQ_PERMS);
            }
            return;
        }
        startPipeline();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("Без доступу до камери застосунок не працює");
            finish();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLayout();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recording) stopRecordingBlocking();
        stopPipeline();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        dm.unregisterDisplayListener(displayListener);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    // ---------------------------------------------------------------- конвеєр

    private void startPipeline() {
        if (renderer != null) return;
        cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        allCams = CameraScanner.scan(cm);
        buildSlotMap();
        if (slotMap.isEmpty()) {
            toast("Камери не знайдено");
            return;
        }
        if (!slotMap.containsKey(selected)) {
            selected = slotMap.containsKey(Slot.MAIN) ? Slot.MAIN : slotMap.keySet().iterator().next();
        }
        updateLensButtons();
        if (!slotMap.containsKey(Slot.WIDE) || !slotMap.containsKey(Slot.MACRO)) {
            toast("Не всі об'єктиви знайдено автоматично. Утримуйте кнопку об'єктива → «Усі камери телефону», щоб побачити, що віддає система, або призначити камеру вручну.");
        }

        stabilizer.start();
        GlRenderer r = new GlRenderer(glListener);
        r.setDisplayRotation(displayRotationDeg());
        r.setStabilization(stabilizer, stabCfg);
        r.setExtraRotation(prefs.getInt("extra_rot", 0));
        r.start();
        renderer = r;
        Surface s = previewSurface;
        if (s != null && s.isValid()) r.setPreviewSurface(s);
    }

    private void stopPipeline() {
        CamController c = camCtl;
        camCtl = null;
        if (c != null) c.close();
        GlRenderer r = renderer;
        renderer = null;
        if (r != null) r.shutdown();
        stabilizer.stop();
        switchingTo = null;
        retried = false;
        ui.removeCallbacks(zoomTick);
        zoomTicking = false;
        ui.removeCallbacks(proTick);
    }

    private final GlRenderer.Listener glListener = new GlRenderer.Listener() {
        @Override
        public void onCameraSurfaceReady() {
            ui.post(() -> {
                GlRenderer r = renderer;
                if (r == null || camCtl != null) return;
                CamInfo info = slotMap.get(selected);
                if (info == null) return;
                camCtl = new CamController(cm, r, pro, ctlCallback);
                switchingTo = selected;
                camCtl.switchTo(info, false);
                updateLensButtons();
            });
        }

        @Override
        public void onFirstFrameAfterSwitch() {
            CamController c = camCtl;
            if (c != null) c.onSwitchDone();
        }

        @Override
        public void onSwitchTimeout() {
            CamController c = camCtl;
            if (c != null) c.onSwitchTimeout();
        }

        @Override
        public void onGlError(String msg) {
            ui.post(() -> toast(msg));
        }
    };

    private final CamController.Callback ctlCallback = new CamController.Callback() {
        @Override
        public void onSwitched() {
            ui.post(() -> {
                if (switchingTo != null) {
                    selected = switchingTo;
                    switchingTo = null;
                }
                retried = false;
                updateLensButtons();
                updateToolButtons();
                updateZoomLabel();
                refreshProPanel();
                CamController c = camCtl;
                if (c != null) c.updateRequest(); // застосувати ліхтарик/PRO вже для нової камери
            });
        }

        @Override
        public void onFailed(final String message) {
            ui.post(() -> {
                toast(message);
                switchingTo = null;
                CamController c = camCtl;
                CamInfo back = slotMap.get(selected);
                if (c != null && !retried && back != null) {
                    // камера не відкрилась - повертаємось на попередню
                    retried = true;
                    switchingTo = selected;
                    c.switchTo(back, false);
                }
                updateLensButtons();
            });
        }
    };

    // ---------------------------------------------------------------- вибір камер

    private void buildSlotMap() {
        Map<Slot, CamInfo> m = CameraScanner.autoMap(allCams);
        for (Slot s : Slot.values()) {
            String v = prefs.getString("slot_" + s.name(), null);
            if (v == null) continue;
            if (v.equals("none")) {
                m.remove(s);
                continue;
            }
            for (CamInfo c : allCams) {
                if (c.key().equals(v)) {
                    m.put(s, c);
                    break;
                }
            }
        }
        slotMap = m;
    }

    private void onLensClicked(Slot slot) {
        CamController c = camCtl;
        if (c == null || c.isBusy()) return;
        CamInfo info = slotMap.get(slot);
        if (info == null) {
            toast("Камеру «" + slotLabel(slot) + "» не знайдено. Утримуйте кнопку, щоб призначити вручну.");
            return;
        }
        if (slot == selected) return;
        switchingTo = slot;
        updateLensButtons();
        c.switchTo(info, true);
    }

    private void showAssignDialog(final Slot slot) {
        final List<String> labels = new ArrayList<>();
        labels.add("Авто");
        labels.add("Приховати");
        labels.add("ℹ Усі камери телефону (діагностика)");
        for (CamInfo c : allCams) labels.add(c.describe());
        new AlertDialog.Builder(this)
                .setTitle("Камера для «" + slotLabel(slot) + "»")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 2) {
                        showDiagDialog();
                        return;
                    }
                    SharedPreferences.Editor e = prefs.edit();
                    String key = "slot_" + slot.name();
                    if (which == 0) e.remove(key);
                    else if (which == 1) e.putString(key, "none");
                    else e.putString(key, allCams.get(which - 3).key());
                    e.apply();
                    applySlotChange();
                })
                .setNegativeButton("Скасувати", null)
                .show();
    }

    private void showDiagDialog() {
        CameraManager m = cm != null ? cm : (CameraManager) getSystemService(CAMERA_SERVICE);
        final String text = CameraScanner.diagnose(m);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Камери телефону")
                .setView(sv)
                .setPositiveButton("Поділитися", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Поділитися списком камер"));
                })
                .setNegativeButton("Закрити", null)
                .show();
    }

    private void applySlotChange() {
        buildSlotMap();
        if (!slotMap.containsKey(selected)) {
            selected = slotMap.containsKey(Slot.MAIN) ? Slot.MAIN : selected;
        }
        updateLensButtons();
        CamController c = camCtl;
        CamInfo info = slotMap.get(selected);
        if (c != null && !c.isBusy() && info != null) {
            switchingTo = selected;
            c.switchTo(info, true);
        }
    }

    private static String slotLabel(Slot s) {
        switch (s) {
            case FRONT: return "Фронт";
            case WIDE: return "0,5×";
            case MACRO: return "Макро";
            default: return "1×";
        }
    }

    private CamInfo curInfo() {
        return slotMap.get(selected);
    }

    // ---------------------------------------------------------------- стабілізація

    private void onStabClicked() {
        if (!stabilizer.isAvailable()) {
            toast("У телефоні немає гіроскопа - програмна стабілізація недоступна");
            return;
        }
        stabCfg.enabled = !stabCfg.enabled;
        prefs.edit().putBoolean("stab_on", stabCfg.enabled).apply();
        updateToolButtons();
    }

    private void showStabDialog() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(12), dp(24), dp(4));

        final TextView title = new TextView(this);
        title.setTextSize(15);
        l.addView(title);

        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round(stabCfg.strength * 100f));
        l.addView(sb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        title.setText(strengthText(stabCfg.strength));
        sb.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                stabCfg.strength = p / 100f;
                title.setText(strengthText(stabCfg.strength));
                prefs.edit().putFloat("stab_strength", stabCfg.strength).apply();
            }
        });

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setAlpha(0.75f);
        hint.setText("Вища ступінь = плавніше відео, але сильніше обрізається кадр по краях (приблизно 5–20 %).");
        l.addView(hint);

        CheckBox cbRoll = new CheckBox(this);
        cbRoll.setText("Вирівнювати нахил (поворот кадру)");
        cbRoll.setChecked(stabCfg.roll);
        cbRoll.setOnCheckedChangeListener((CompoundButton b, boolean c) -> {
            stabCfg.roll = c;
            prefs.edit().putBoolean("stab_roll", c).apply();
        });
        l.addView(cbRoll);

        CheckBox cbInv = new CheckBox(this);
        cbInv.setText("Інвертувати напрямок (якщо кадр трясе сильніше, ніж без стабілізації)");
        cbInv.setChecked(stabCfg.invert);
        cbInv.setOnCheckedChangeListener((CompoundButton b, boolean c) -> {
            stabCfg.invert = c;
            prefs.edit().putBoolean("stab_invert", c).apply();
        });
        l.addView(cbInv);

        final TextView syncTitle = new TextView(this);
        syncTitle.setTextSize(15);
        syncTitle.setPadding(0, dp(10), 0, 0);
        l.addView(syncTitle);
        SeekBar sync = new SeekBar(this);
        sync.setMax(200);
        sync.setProgress(stabCfg.syncMs + 100);
        l.addView(sync, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        syncTitle.setText(syncText(stabCfg.syncMs));
        sync.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                stabCfg.syncMs = p - 100;
                syncTitle.setText(syncText(stabCfg.syncMs));
                prefs.edit().putInt("stab_sync", stabCfg.syncMs).apply();
            }
        });
        TextView syncHint = new TextView(this);
        syncHint.setTextSize(12);
        syncHint.setAlpha(0.75f);
        syncHint.setText("Якщо при тремтінні рук картинка все одно трясеться - потрясіть телефоном і потроху "
                + "зсувайте цей повзунок, поки тремтіння не зникне (зазвичай у межах ±40 мс).");
        l.addView(syncHint);
        GlRenderer rr = renderer;
        if (rr != null) {
            TextView dbg = new TextView(this);
            dbg.setTextSize(11);
            dbg.setAlpha(0.6f);
            dbg.setPadding(0, dp(8), 0, 0);
            dbg.setText(rr.debugInfo());
            l.addView(dbg);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(l);

        new AlertDialog.Builder(this)
                .setTitle("Програмна стабілізація")
                .setView(sv)
                .setPositiveButton("Готово", null)
                .show();
    }

    private static String syncText(int ms) {
        return "Синхронізація з гіроскопом: " + (ms > 0 ? "+" : "") + ms + " мс";
    }

    private static String strengthText(float s) {
        return "Ступінь стабілізації: " + Math.round(s * 100f) + " %";
    }

    // ---------------------------------------------------------------- ліхтарик

    private void onTorchClicked() {
        CamInfo info = curInfo();
        if (info == null || !info.hasFlash) {
            toast("На цій камері немає ліхтарика (спалах є лише в задніх камер)");
            return;
        }
        pro.torch = !pro.torch;
        CamController c = camCtl;
        if (c != null) c.updateRequest();
        updateToolButtons();
    }

    // ---------------------------------------------------------------- зум

    private boolean zoomTicking;
    private long zoomLastNs;

    private final Runnable zoomTick = new Runnable() {
        @Override
        public void run() {
            float v = zoomSlider.getValue();
            long now = SystemClock.elapsedRealtimeNanos();
            float dt = Math.min(0.1f, (now - zoomLastNs) / 1e9f);
            zoomLastNs = now;
            if (Math.abs(v) < 0.02f) {
                zoomTicking = false;
                return;
            }
            CamController c = camCtl;
            GlRenderer r = renderer;
            if (c != null && r != null && !c.isBusy()) {
                // чим далі повзунок від центру, тим швидше змінюється зум (експоненційно - рівномірно на око)
                float mag = (float) Math.pow(Math.abs(v), 1.6);
                float rate = 0.9f * Math.signum(v) * mag;
                float z = pro.zoom * (float) Math.exp(rate * dt);
                z = Math.max(1f, Math.min(z, pro.zoomMax));
                if (z != pro.zoom) {
                    pro.zoom = z;
                    r.setZoom(z);
                    c.updateRequest();
                    updateZoomLabel();
                }
            }
            ui.postDelayed(this, 16);
        }
    };

    private void onZoomValue(float v) {
        if (!zoomTicking && Math.abs(v) >= 0.02f) {
            zoomTicking = true;
            zoomLastNs = SystemClock.elapsedRealtimeNanos();
            ui.post(zoomTick);
        }
    }

    private void updateZoomLabel() {
        zoomLabel.setText(String.format(Locale.US, "%.1f×", pro.zoom));
    }

    // ---------------------------------------------------------------- PRO режим

    private void onProClicked() {
        pro.proMode = !pro.proMode;
        proPanel.setVisibility(pro.proMode ? View.VISIBLE : View.GONE);
        CamController c = camCtl;
        if (c != null) c.updateRequest();
        updateToolButtons();
        ui.removeCallbacks(proTick);
        if (pro.proMode) {
            refreshProPanel();
            ui.post(proTick);
        }
    }

    private final Runnable proTick = new Runnable() {
        @Override
        public void run() {
            if (!pro.proMode) return;
            CamInfo info = curInfo();
            if (info != null) updateProLabels(info);
            ui.postDelayed(this, 500);
        }
    };

    private double isoHi(CamInfo i) {
        return Math.max(i.isoMin + 1, Math.min(i.isoMax, 3200));
    }

    private int isoFromProgress(CamInfo i, int p) {
        double lo = Math.max(1, i.isoMin);
        return (int) Math.round(lo * Math.pow(isoHi(i) / lo, p / 1000.0));
    }

    private int progressFromIso(CamInfo i, int iso) {
        double lo = Math.max(1, i.isoMin);
        double v = Math.log(Math.max(lo, iso) / lo) / Math.log(isoHi(i) / lo) * 1000.0;
        return (int) Math.max(0, Math.min(1000, Math.round(v)));
    }

    private double shLo(CamInfo i) {
        return Math.max(i.expMinNs, 100_000L);
    }

    private double shHi(CamInfo i) {
        return Math.max(shLo(i) + 1, Math.min(i.expMaxNs, 33_333_333L));
    }

    private long shutterFromProgress(CamInfo i, int p) {
        return Math.round(shLo(i) * Math.pow(shHi(i) / shLo(i), p / 1000.0));
    }

    private int progressFromShutter(CamInfo i, long ns) {
        double v = Math.log(Math.max(shLo(i), ns) / shLo(i)) / Math.log(shHi(i) / shLo(i)) * 1000.0;
        return (int) Math.max(0, Math.min(1000, Math.round(v)));
    }

    private static String shutterText(long ns) {
        return "1/" + Math.max(1, Math.round(1e9 / Math.max(1, ns)));
    }

    private static String focusText(float diopter) {
        return diopter < 0.05f ? "∞" : Math.round(100f / diopter) + " см";
    }

    private void updateProLabels(CamInfo info) {
        if (pro.manualExp) {
            tvIso.setText("ISO " + pro.iso);
            tvShutter.setText("Витримка " + shutterText(pro.shutterNs));
        } else if (info.manualExposure) {
            tvIso.setText("ISO " + pro.lastIso + "  (авто)");
            tvShutter.setText("Витримка " + shutterText(Math.max(1, pro.lastShutterNs)) + "  (авто)");
        } else {
            tvIso.setText("ISO: недоступно на цій камері");
            tvShutter.setText("Витримка: недоступно на цій камері");
        }
        if (info.minFocus <= 0f) tvFocus.setText("Фокус: фіксований (недоступно)");
        else if (pro.manualFocus) tvFocus.setText("Фокус " + focusText(pro.focusDiopter));
        else tvFocus.setText("Фокус: авто");
        if (info.evMax > info.evMin) {
            tvEv.setText(String.format(Locale.US, "Експокорекція %+.1f EV", pro.evSteps * info.evStep));
        } else {
            tvEv.setText("Експокорекція: недоступно");
        }
        btnWb.setText("Баланс білого: " + (wbModes.length == 0 ? "—" : wbName(wbModes[wbIndex])));
    }

    private static String wbName(int mode) {
        switch (mode) {
            case CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT: return "Сонце";
            case CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT: return "Хмарно";
            case CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT: return "Лампа";
            case CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT: return "Флуор.";
            default: return "Авто";
        }
    }

    private void refreshProPanel() {
        CamInfo info = curInfo();
        if (info == null || proPanel == null) return;
        proUpdating = true;
        try {
            boolean man = info.manualExposure;
            swManual.setEnabled(man);
            swManual.setChecked(man && pro.manualExp);
            boolean me = man && pro.manualExp;
            sbIso.setEnabled(me);
            sbShutter.setEnabled(me);
            if (me) {
                sbIso.setProgress(progressFromIso(info, pro.iso));
                sbShutter.setProgress(progressFromShutter(info, pro.shutterNs));
            }

            boolean canFocus = info.minFocus > 0f;
            swFocus.setEnabled(canFocus);
            swFocus.setChecked(canFocus && pro.manualFocus);
            sbFocus.setEnabled(canFocus && pro.manualFocus);
            if (canFocus) sbFocus.setProgress(Math.round(pro.focusDiopter / info.minFocus * 1000f));

            boolean ev = info.evMax > info.evMin;
            sbEv.setEnabled(ev);
            if (ev) {
                sbEv.setMax(info.evMax - info.evMin);
                sbEv.setProgress(pro.evSteps - info.evMin);
            }

            int[] want = {CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
                    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT};
            List<Integer> avail = new ArrayList<>();
            for (int w : want) {
                for (int a : info.awbModes) {
                    if (a == w) {
                        avail.add(w);
                        break;
                    }
                }
            }
            wbModes = new int[avail.size()];
            for (int i = 0; i < wbModes.length; i++) wbModes[i] = avail.get(i);
            wbIndex = 0;
            for (int i = 0; i < wbModes.length; i++) if (wbModes[i] == pro.awbMode) wbIndex = i;
            btnWb.setEnabled(wbModes.length > 1);
            btnWb.setAlpha(wbModes.length > 1 ? 1f : 0.4f);

            updateProLabels(info);
        } finally {
            proUpdating = false;
        }
    }

    private void proChanged() {
        CamController c = camCtl;
        if (c != null) c.updateRequest();
        CamInfo info = curInfo();
        if (info != null) updateProLabels(info);
    }

    private View buildProPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(10));
        col.setBackground(roundBg(Color.argb(200, 0, 0, 0), dp(16), 0, 0));

        TextView title = label("PRO режим", 15, true);
        title.setTextColor(YELLOW);
        col.addView(title);

        // експозиція
        swManual = new Switch(this);
        swManual.setText("Ручна експозиція (ISO / витримка)");
        swManual.setTextColor(Color.WHITE);
        swManual.setTextSize(13);
        col.addView(swManual);
        tvIso = label("ISO", 12, false);
        col.addView(tvIso);
        sbIso = new SeekBar(this);
        sbIso.setMax(1000);
        col.addView(sbIso);
        tvShutter = label("Витримка", 12, false);
        col.addView(tvShutter);
        sbShutter = new SeekBar(this);
        sbShutter.setMax(1000);
        col.addView(sbShutter);

        // фокус
        swFocus = new Switch(this);
        swFocus.setText("Ручний фокус");
        swFocus.setTextColor(Color.WHITE);
        swFocus.setTextSize(13);
        col.addView(swFocus);
        tvFocus = label("Фокус", 12, false);
        col.addView(tvFocus);
        sbFocus = new SeekBar(this);
        sbFocus.setMax(1000);
        col.addView(sbFocus);

        // експокорекція і баланс білого
        tvEv = label("Експокорекція", 12, false);
        col.addView(tvEv);
        sbEv = new SeekBar(this);
        sbEv.setMax(1);
        col.addView(sbEv);
        btnWb = label("Баланс білого", 13, true);
        btnWb.setPadding(0, dp(8), 0, dp(4));
        col.addView(btnWb);

        // --- логіка ---
        swManual.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            if (proUpdating) return;
            CamInfo info = curInfo();
            if (info == null) return;
            pro.manualExp = on;
            if (on) {
                // стартуємо з того, що камера щойно вибрала сама
                int iso = pro.lastIso > 0 ? pro.lastIso : info.isoMin;
                long sh = pro.lastShutterNs > 0 ? pro.lastShutterNs : 16_666_667L;
                pro.iso = Math.max(info.isoMin, Math.min(iso, info.isoMax));
                pro.shutterNs = Math.max(info.expMinNs, Math.min(sh, Math.min(info.expMaxNs, 33_333_333L)));
            }
            refreshProPanel();
            proChanged();
        });
        sbIso.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                CamInfo info = curInfo();
                if (proUpdating || !fromUser || info == null) return;
                pro.iso = isoFromProgress(info, p);
                proChanged();
            }
        });
        sbShutter.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                CamInfo info = curInfo();
                if (proUpdating || !fromUser || info == null) return;
                pro.shutterNs = shutterFromProgress(info, p);
                proChanged();
            }
        });
        swFocus.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            if (proUpdating) return;
            CamInfo info = curInfo();
            if (info == null) return;
            pro.manualFocus = on;
            if (on) {
                float cur = Math.max(0f, Math.min(pro.lastFocus, info.minFocus));
                pro.focusDiopter = cur;
            }
            refreshProPanel();
            proChanged();
        });
        sbFocus.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                CamInfo info = curInfo();
                if (proUpdating || !fromUser || info == null) return;
                pro.focusDiopter = p / 1000f * info.minFocus;
                proChanged();
            }
        });
        sbEv.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                CamInfo info = curInfo();
                if (proUpdating || !fromUser || info == null) return;
                pro.evSteps = p + info.evMin;
                proChanged();
            }
        });
        btnWb.setOnClickListener(v -> {
            if (wbModes.length < 2) return;
            wbIndex = (wbIndex + 1) % wbModes.length;
            pro.awbMode = wbModes[wbIndex];
            proChanged();
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        sv.setVisibility(View.GONE);
        return sv;
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }

    // ---------------------------------------------------------------- запис

    private void toggleRecording() {
        if (recording) {
            stopRecordingAsync();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        GlRenderer r = renderer;
        if (r == null || camCtl == null) return;
        boolean audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean portrait = displayRotationDeg() % 180 == 0;
        Recorder rec = new Recorder(this, r, audio, portrait);
        try {
            rec.start();
        } catch (Exception e) {
            toast("Не вдалося почати запис: " + e.getMessage());
            return;
        }
        recorder = rec;
        recording = true;
        // розмір відео фіксується на старті запису - тримаємо екран у поточній орієнтації до зупинки
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        updateOrientButton();
        recStartMs = SystemClock.elapsedRealtime();
        recBtn.setRecording(true);
        timerBox.setVisibility(View.VISIBLE);
        ui.post(timerTick);
        if (!audio) toast("Немає доступу до мікрофона - відео буде без звуку");
    }

    private void stopRecordingAsync() {
        final Recorder rec = recorder;
        recorder = null;
        recording = false;
        applyOrientationMode();
        updateOrientButton();
        recBtn.setRecording(false);
        recBtn.setEnabled(false);
        timerBox.setVisibility(View.INVISIBLE);
        ui.removeCallbacks(timerTick);
        if (rec == null) {
            recBtn.setEnabled(true);
            return;
        }
        new Thread(() -> {
            final Uri saved = rec.stop();
            ui.post(() -> {
                recBtn.setEnabled(true);
                toast(saved != null ? "Збережено в Movies/DuoCam" : "Відео не збережено (запис надто короткий)");
            });
        }, "DuoCam-Stop").start();
    }

    private void stopRecordingBlocking() {
        Recorder rec = recorder;
        recorder = null;
        recording = false;
        applyOrientationMode();
        updateOrientButton();
        recBtn.setRecording(false);
        timerBox.setVisibility(View.INVISIBLE);
        ui.removeCallbacks(timerTick);
        if (rec != null) {
            Uri saved = rec.stop();
            if (saved != null) toast("Збережено в Movies/DuoCam");
        }
    }

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            long sec = (SystemClock.elapsedRealtime() - recStartMs) / 1000;
            timerText.setText(String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
            ui.postDelayed(this, 500);
        }
    };

    // ---------------------------------------------------------------- орієнтація

    private int displayRotationDeg() {
        try {
            int r = getDisplay().getRotation();
            switch (r) {
                case Surface.ROTATION_90: return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 90;
        }
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }

        @Override
        public void onDisplayChanged(int displayId) {
            GlRenderer r = renderer;
            if (r != null) r.setDisplayRotation(displayRotationDeg());
            applyLayout();
        }
    };

    // ---------------------------------------------------------------- інтерфейс

    private final SurfaceHolder.Callback surfaceCb = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            previewSurface = holder.getSurface();
            GlRenderer r = renderer;
            if (r != null) r.setPreviewSurface(previewSurface);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            GlRenderer r = renderer;
            if (r != null) r.setPreviewSurface(null);
            previewSurface = null;
        }
    };

    // ---------------------------------------------------------------- орієнтація екрана / запису

    private void applyOrientationMode() {
        if (recording) return;
        int o;
        switch (orientMode) {
            case 1: o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; break;
            case 2: o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; break;
            default: o = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR; break;
        }
        setRequestedOrientation(o);
    }

    private void onOrientClicked() {
        if (recording) {
            toast("Орієнтацію не можна змінювати під час запису");
            return;
        }
        orientMode = (orientMode + 1) % 3;
        prefs.edit().putInt("orient_mode", orientMode).apply();
        applyOrientationMode();
        updateOrientButton();
        toast(orientMode == 0 ? "Орієнтація: авто (за положенням телефона)"
                : orientMode == 1 ? "Орієнтація: 16:9 (горизонтально)" : "Орієнтація: 9:16 (вертикально)");
    }

    /** Ручна корекція повороту картинки - на випадок, якщо автоматика на конкретному телефоні помиляється. */
    private void showRotationDialog() {
        final int[] vals = {0, 90, 180, 270};
        final String[] names = {"Без корекції (авто)", "Повернути на 90° за годинниковою", "Повернути на 180°",
                "Повернути на 90° проти годинникової", "ℹ Технічні дані (для діагностики)"};
        int cur = prefs.getInt("extra_rot", 0);
        int checked = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) checked = i;
        new AlertDialog.Builder(this)
                .setTitle("Поворот картинки")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    if (which == 4) {
                        GlRenderer r = renderer;
                        toast(r == null ? "Камера ще не запущена" : r.debugInfo());
                        return;
                    }
                    prefs.edit().putInt("extra_rot", vals[which]).apply();
                    GlRenderer r = renderer;
                    if (r != null) r.setExtraRotation(vals[which]);
                    d.dismiss();
                })
                .setNegativeButton("Закрити", null)
                .show();
    }

    private void updateOrientButton() {
        if (btnOrient == null) return;
        btnOrient.setText(orientMode == 0 ? "↻ Авто" : orientMode == 1 ? "▭ 16:9" : "▯ 9:16");
        styleToggle(btnOrient, orientMode != 0, !recording);
    }

    private void setLp(View v, int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(lp);
    }

    /** Розкладка кнопок під горизонтальну або вертикальну орієнтацію. */
    private void applyLayout() {
        if (rootLayout == null) return;
        final int W = ViewGroup.LayoutParams.WRAP_CONTENT;
        boolean portrait = displayRotationDeg() % 180 == 0;
        if (portrait) {
            setLp(toolsRow, W, W, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0);
            setLp(timerBox, W, W, Gravity.TOP | Gravity.START, 24, 66, 0, 0);
            setLp(recBtn, dp(78), dp(78), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 76);
            setLp(zoomBox, W, W, Gravity.START | Gravity.CENTER_VERTICAL, 12, 0, 0, 0);
            setLp(proPanel, dp(280), dp(270), Gravity.TOP | Gravity.END, 0, 112, 8, 0);
        } else {
            setLp(toolsRow, W, W, Gravity.TOP | Gravity.END, 0, 14, 24, 0);
            setLp(timerBox, W, W, Gravity.TOP | Gravity.START, 24, 16, 0, 0);
            setLp(recBtn, dp(78), dp(78), Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 32, 0);
            setLp(zoomBox, W, W, Gravity.START | Gravity.CENTER_VERTICAL, 28, 0, 0, 0);
            setLp(proPanel, dp(320), dp(270), Gravity.END | Gravity.CENTER_VERTICAL, 0, 30, 130, 0);
        }
        setLp(lensRow, W, W, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootLayout = root;
        root.setBackgroundColor(Color.BLACK);
        final int W = ViewGroup.LayoutParams.WRAP_CONTENT;

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(surfaceCb);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // таймер запису
        timerBox = new LinearLayout(this);
        timerBox.setOrientation(LinearLayout.HORIZONTAL);
        timerBox.setGravity(Gravity.CENTER_VERTICAL);
        timerBox.setPadding(dp(12), dp(6), dp(14), dp(6));
        timerBox.setBackground(roundBg(Color.argb(150, 0, 0, 0), dp(18), 0, 0));
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.rgb(229, 57, 53));
        dot.setBackground(dotBg);
        timerBox.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        timerText = new TextView(this);
        timerText.setTextColor(Color.WHITE);
        timerText.setTextSize(16);
        timerText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timerText.setText("00:00");
        LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(W, W);
        ttl.leftMargin = dp(8);
        timerBox.addView(timerText, ttl);
        timerBox.setVisibility(View.INVISIBLE);
        root.addView(timerBox, new FrameLayout.LayoutParams(W, W));

        // кнопка запису
        recBtn = new RecordButton(this);
        recBtn.setOnClickListener(v -> toggleRecording());
        root.addView(recBtn, new FrameLayout.LayoutParams(dp(78), dp(78)));

        // панель: стабілізація, ліхтарик, PRO, орієнтація
        toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        btnStab = toolButton("Стаб");
        btnStab.setOnClickListener(v -> onStabClicked());
        btnStab.setOnLongClickListener(v -> {
            showStabDialog();
            return true;
        });
        btnTorch = toolButton("🔦");
        btnTorch.setOnClickListener(v -> onTorchClicked());
        btnPro = toolButton("PRO");
        btnPro.setOnClickListener(v -> onProClicked());
        btnOrient = toolButton("↻ Авто");
        btnOrient.setTextSize(12);
        btnOrient.setOnClickListener(v -> onOrientClicked());
        btnOrient.setOnLongClickListener(v -> {
            showRotationDialog();
            return true;
        });
        for (TextView b : new TextView[]{btnStab, btnTorch, btnPro, btnOrient}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(62), dp(40));
            lp.setMargins(dp(5), 0, dp(5), 0);
            toolsRow.addView(b, lp);
        }
        root.addView(toolsRow, new FrameLayout.LayoutParams(W, W));

        // PRO-панель
        proPanel = buildProPanel();
        root.addView(proPanel, new FrameLayout.LayoutParams(dp(320), dp(270)));

        // повзунок зуму
        zoomBox = new LinearLayout(this);
        zoomBox.setOrientation(LinearLayout.VERTICAL);
        zoomBox.setGravity(Gravity.CENTER_HORIZONTAL);
        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(14);
        zoomLabel.setTypeface(Typeface.DEFAULT_BOLD);
        zoomLabel.setText("1.0×");
        zoomBox.addView(zoomLabel, new LinearLayout.LayoutParams(W, W));
        zoomSlider = new ZoomSlider(this);
        zoomSlider.setListener(this::onZoomValue);
        zoomBox.addView(zoomSlider, new LinearLayout.LayoutParams(dp(56), dp(230)));
        root.addView(zoomBox, new FrameLayout.LayoutParams(W, W));

        // вибір об'єктива
        lensRow = new LinearLayout(this);
        lensRow.setOrientation(LinearLayout.HORIZONTAL);
        lensRow.setGravity(Gravity.CENTER);
        for (final Slot s : Slot.values()) {
            TextView tv = toolButton(slotLabel(s));
            tv.setOnClickListener(v -> onLensClicked(s));
            tv.setOnLongClickListener(v -> {
                showAssignDialog(s);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(40));
            lp.setMargins(dp(5), 0, dp(5), 0);
            lensRow.addView(tv, lp);
            lensBtns.put(s, tv);
        }
        root.addView(lensRow, new FrameLayout.LayoutParams(W, W));

        updateLensButtons();
        updateToolButtons();
        updateOrientButton();
        applyLayout();
        return root;
    }

    private TextView toolButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        styleToggle(tv, false, true);
        return tv;
    }

    private void styleToggle(TextView tv, boolean on, boolean usable) {
        tv.setTextColor(on ? YELLOW : Color.WHITE);
        tv.setBackground(roundBg(Color.argb(on ? 190 : 120, 0, 0, 0), dp(20), on ? dp(2) : 0, YELLOW));
        tv.setAlpha(usable ? 1f : 0.35f);
    }

    private void updateToolButtons() {
        if (btnStab == null) return;
        CamInfo info = curInfo();
        boolean flash = info != null && info.hasFlash;
        styleToggle(btnStab, stabCfg.enabled && stabilizer.isAvailable(), stabilizer.isAvailable());
        styleToggle(btnTorch, pro.torch && flash, flash || info == null);
        styleToggle(btnPro, pro.proMode, true);
    }

    private void updateLensButtons() {
        boolean busy = switchingTo != null;
        for (Slot s : Slot.values()) {
            TextView tv = lensBtns.get(s);
            if (tv == null) continue;
            boolean exists = slotMap.containsKey(s);
            boolean sel = (busy ? switchingTo : selected) == s && exists;
            styleToggle(tv, sel, true);
            tv.setAlpha(exists ? (busy && !sel ? 0.6f : 1f) : 0.35f);
        }
    }

    private GradientDrawable roundBg(int color, int radius, int strokeW, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
