package com.duocam.app;

import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.Collections;
import java.util.concurrent.Executor;

/**
 * Керує однією відкритою камерою. Перемикання: розмиваємо картинку -> закриваємо стару камеру ->
 * відкриваємо нову -> рендерер сам прибирає розмиття після першого кадру нової камери.
 * Запис при цьому не зупиняється (кодер живиться від рендерера).
 * Ліхтарик, PRO-параметри і зум застосовуються через оновлення повторюваного запиту.
 */
final class CamController {

    interface Callback {
        void onSwitched();
        void onFailed(String message);
    }

    private static final String TAG = "DuoCam.Cam";
    private static final long BLUR_WAIT_MS = 520;

    private final CameraManager cm;
    private final GlRenderer renderer;
    private final ProState pro;
    private final Callback cb;
    private final HandlerThread thread;
    private final Handler handler;
    private final Executor executor;

    private CameraDevice device;
    private CameraCaptureSession session;
    private CamInfo pending;
    private CamInfo current;
    private Surface activeSurface;
    private float baseZoom = 1f;
    private boolean updateScheduled;
    private volatile boolean busy;
    private volatile boolean released;

    CamController(CameraManager cm, GlRenderer renderer, ProState pro, Callback cb) {
        this.cm = cm;
        this.renderer = renderer;
        this.pro = pro;
        this.cb = cb;
        thread = new HandlerThread("DuoCam-Camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        executor = r -> handler.post(r);
    }

    boolean isBusy() { return busy; }

    /** Перемкнутися на камеру. withBlur=true: спершу плавно розмити картинку. */
    void switchTo(final CamInfo info, final boolean withBlur) {
        if (busy) return;
        busy = true;
        handler.post(() -> {
            if (released) return;
            long delay = (withBlur && device != null) ? BLUR_WAIT_MS : 0;
            renderer.setBlurTarget(1f);
            handler.postDelayed(() -> beginReopen(info), delay);
        });
    }

    /** Застосувати поточні PRO-налаштування / ліхтарик / зум (з об'єднанням частих викликів). */
    void updateRequest() {
        handler.post(() -> {
            if (released || updateScheduled) return;
            updateScheduled = true;
            handler.postDelayed(() -> {
                updateScheduled = false;
                applyRequestNow();
            }, 25);
        });
    }

    /** Викликається рендерером, коли перший кадр нової камери вже на екрані. */
    void onSwitchDone() {
        busy = false;
        cb.onSwitched();
    }

    void onSwitchTimeout() {
        fail("Камера не віддає кадри");
    }

    void close() {
        released = true;
        handler.post(() -> {
            pending = null;
            closeSession();
            if (device != null) {
                device.close();
                device = null;
            }
            thread.quitSafely();
        });
    }

    // ----------------------------------------------------------------

    private void beginReopen(CamInfo info) {
        if (released) return;
        pending = info;
        pro.resetForNewCamera();
        renderer.setZoom(1f);
        if (device != null) {
            closeSession();
            CameraDevice d = device;
            device = null;
            d.close(); // onClosed -> openPending()
        } else {
            openPending();
        }
    }

    private void closeSession() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) { }
        session = null;
    }

    private void openPending() {
        final CamInfo info = pending;
        pending = null;
        if (info == null || released) return;
        current = info;
        try {
            renderer.armNextCamera(info);
            cm.openCamera(info.logicalId, stateCb, handler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            fail("Не вдалося відкрити камеру: " + e.getMessage());
        }
    }

    private final CameraDevice.StateCallback stateCb = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice c) {
            if (released) {
                c.close();
                return;
            }
            device = c;
            createSession(c, current, false);
        }

        @Override
        public void onDisconnected(CameraDevice c) {
            c.close();
            if (device == c) device = null;
            if (!released) fail("Камеру відключено");
        }

        @Override
        public void onError(CameraDevice c, int error) {
            c.close();
            if (device == c) device = null;
            if (!released) fail("Помилка камери (код " + error + ")");
        }

        @Override
        public void onClosed(CameraDevice c) {
            if (!released && pending != null) openPending();
        }
    };

    private final CameraCaptureSession.CaptureCallback captureCb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Float fd = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (iso != null) pro.lastIso = iso;
            if (exp != null) {
                pro.lastShutterNs = exp;
                renderer.setExposureNs(exp);
            }
            if (fd != null) pro.lastFocus = fd;
        }
    };

    private void createSession(final CameraDevice c, final CamInfo info, final boolean fallback) {
        try {
            final Surface s = renderer.getCameraSurface();
            if (s == null) {
                fail("Немає поверхні для камери");
                return;
            }
            renderer.setBufferSize(info.videoSize.getWidth(), info.videoSize.getHeight());

            OutputConfiguration oc = new OutputConfiguration(s);
            final boolean usePhysical = info.physicalId != null && !fallback;
            if (usePhysical) oc.setPhysicalCameraId(info.physicalId);

            final float zoom;
            if (usePhysical) zoom = 1f;
            else if (info.physicalId != null) zoom = info.fallbackZoom > 0f ? info.fallbackZoom : 1f;
            else zoom = info.zoomRatio;

            SessionConfiguration sc = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(oc),
                    executor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession sess) {
                            if (released || device != c) {
                                sess.close();
                                return;
                            }
                            session = sess;
                            activeSurface = s;
                            baseZoom = zoom;
                            float maxUser = info.zoomMax / Math.max(0.1f, baseZoom);
                            pro.zoomMax = Math.max(1f, Math.min(8f, maxUser));
                            applyRequestNow();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession sess) {
                            sess.close();
                            if (released) return;
                            if (!fallback && info.physicalId != null && info.fallbackZoom > 0f) {
                                // фізичний потік не підтримується - їдемо через зум логічної камери
                                createSession(c, info, true);
                            } else {
                                fail("Камера не підтримує потрібний режим");
                            }
                        }
                    });
            c.createCaptureSession(sc);
        } catch (Exception e) {
            Log.w(TAG, "createSession", e);
            fail("Помилка сесії камери: " + e.getMessage());
        }
    }

    /** Збирає запит з усіма налаштуваннями (ліхтарик, PRO, зум) і запускає його повторюваним. */
    private void applyRequestNow() {
        final CameraDevice c = device;
        final CameraCaptureSession s = session;
        final CamInfo info = current;
        final Surface surf = activeSurface;
        if (released || c == null || s == null || info == null || surf == null) return;
        try {
            CaptureRequest.Builder b = c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(surf);
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, info.fps);

            boolean proOn = pro.proMode;

            // ліхтарик
            if (pro.torch && info.hasFlash) {
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
            }

            // експозиція
            if (proOn && pro.manualExp && info.manualExposure) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, clampInt(pro.iso, info.isoMin, info.isoMax));
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampLong(pro.shutterNs, info.expMinNs, info.expMaxNs));
            } else if (proOn && info.evMax > info.evMin) {
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampInt(pro.evSteps, info.evMin, info.evMax));
            }

            // фокус
            if (proOn && pro.manualFocus && info.minFocus > 0f) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.max(0f, Math.min(pro.focusDiopter, info.minFocus)));
            } else if (info.afContinuous) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }

            // баланс білого
            if (proOn && pro.awbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO && contains(info.awbModes, pro.awbMode)) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, pro.awbMode);
            }

            // зум (апаратний: ZOOM_RATIO або CROP_REGION - якість краща, ніж цифровий зум по готовому кадру)
            float z = baseZoom * pro.zoom;
            z = Math.max(info.zoomMin > 0 ? info.zoomMin : 0.1f, Math.min(z, Math.max(info.zoomMax, baseZoom)));
            if (info.hasZoomRatio) {
                if (z != 1f) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
            } else if (z > 1.001f && info.activeArray != null) {
                Rect a = info.activeArray;
                int w = Math.round(a.width() / z);
                int h = Math.round(a.height() / z);
                int l = a.left + (a.width() - w) / 2;
                int t = a.top + (a.height() - h) / 2;
                b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(l, t, l + w, t + h));
            }

            s.setRepeatingRequest(b.build(), captureCb, handler);
        } catch (IllegalStateException | CameraAccessException e) {
            Log.w(TAG, "applyRequest", e);
        } catch (Exception e) {
            Log.w(TAG, "applyRequest", e);
            fail("Не вдалося запустити камеру: " + e.getMessage());
        }
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static long clampLong(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }

    private void fail(String msg) {
        busy = false;
        pending = null;
        renderer.setBlurTarget(0f);
        cb.onFailed(msg);
    }
}
