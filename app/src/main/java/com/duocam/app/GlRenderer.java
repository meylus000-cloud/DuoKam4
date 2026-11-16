package com.duocam.app;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Єдиний конвеєр картинки: камера -> SurfaceTexture -> OpenGL -> (екран + відеокодер).
 * Саме тому запис не переривається під час зміни камери: кодер отримує кадри від рендерера,
 * а не від конкретної камери. Під час перемикання картинка плавно розмивається, камера
 * змінюється "за лаштунками", потім розмиття плавно зникає.
 */
final class GlRenderer extends Thread implements SurfaceTexture.OnFrameAvailableListener {

    interface Listener {
        void onCameraSurfaceReady();
        void onFirstFrameAfterSwitch();
        void onSwitchTimeout();
        void onGlError(String msg);
    }

    private static final String TAG = "DuoCam.GL";

    static final int VIDEO_W = 1920;
    static final int VIDEO_H = 1080;
    private static final float ASPECT = VIDEO_W / (float) VIDEO_H;

    private static final int LOW_W = 256;
    private static final int LOW_H = 144;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final float BLUR_IN_S = 0.5f;      // скільки триває наростання розмиття
    private static final float BLUR_OUT_S = 0.6f;     // скільки триває зникнення розмиття
    private static final long SETTLE_NS = 350_000_000L;   // тримаємо розмиття, поки нова камера "прогріється"
    private static final long ARM_TIMEOUT_NS = 5_000_000_000L;

    private static final float PORTRAIT_ASPECT = VIDEO_H / (float) VIDEO_W;
    private final float[] rotM = new float[16];

    private static final String VERT =
            "attribute vec4 aPos;\n"
                    + "attribute vec2 aTex;\n"
                    + "uniform mat4 uTexMat;\n"
                    + "uniform float uMirror;\n"
                    + "uniform vec4 uStab;\n"      // масштаб, кут, dx, dy
                    + "uniform float uAspect;\n"
                    + "varying vec2 vTex;\n"
                    + "varying vec2 vUv;\n"
                    + "void main() {\n"
                    + "  vec2 c = aPos.xy;\n"
                    + "  c.x *= uAspect;\n"
                    + "  float ca = cos(uStab.y);\n"
                    + "  float sa = sin(uStab.y);\n"
                    + "  c = vec2(ca * c.x - sa * c.y, sa * c.x + ca * c.y) * uStab.x;\n"
                    + "  c.x /= uAspect;\n"
                    + "  c += uStab.zw;\n"
                    + "  gl_Position = vec4(c.x * uMirror, c.y, 0.0, 1.0);\n"
                    + "  vTex = (uTexMat * vec4(aTex, 0.0, 1.0)).xy;\n"
                    + "  vUv = aTex;\n"
                    + "}\n";

    private static final String FRAG_COPY =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTex;\n"
                    + "uniform samplerExternalOES uOes;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(uOes, vTex);\n"
                    + "}\n";

    private static final String FRAG_FINAL =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                    + "precision highp float;\n"
                    + "#else\n"
                    + "precision mediump float;\n"
                    + "#endif\n"
                    + "varying vec2 vTex;\n"
                    + "varying vec2 vUv;\n"
                    + "uniform samplerExternalOES uOes;\n"
                    + "uniform sampler2D uLow;\n"
                    + "uniform float uBlur;\n"
                    + "uniform vec2 uTexel;\n"
                    + "void main() {\n"
                    + "  vec4 sharp = texture2D(uOes, vTex);\n"
                    + "  if (uBlur <= 0.002) { gl_FragColor = sharp; return; }\n"
                    + "  float radius = uBlur * 18.0;\n"
                    + "  vec4 acc = vec4(0.0);\n"
                    + "  for (int i = 0; i < 40; i++) {\n"
                    + "    float fi = float(i);\n"
                    + "    float r = sqrt((fi + 0.5) / 40.0);\n"
                    + "    float th = fi * 2.39996323;\n"
                    + "    vec2 off = vec2(cos(th), sin(th)) * r * radius * uTexel;\n"
                    + "    acc += texture2D(uLow, vUv + off);\n"
                    + "  }\n"
                    + "  vec4 blurred = acc / 40.0;\n"
                    + "  float k = smoothstep(0.0, 0.25, uBlur);\n"
                    + "  gl_FragColor = mix(sharp, blurred * (1.0 - 0.12 * uBlur), k);\n"
                    + "}\n";

    private static final class Prog {
        int id, aPos, aTex, uTexMat, uMirror, uOes, uLow, uBlur, uTexel, uStab, uAspect;
    }

    private final Listener listener;
    private final Object lock = new Object();
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true;
    private boolean frameAvailable;

    // EGL
    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLConfig cfg;
    private EGLSurface pbuf = EGL14.EGL_NO_SURFACE;
    private EGLSurface previewEgl = EGL14.EGL_NO_SURFACE;
    private EGLSurface encEgl = EGL14.EGL_NO_SURFACE;

    // GL
    private Prog progCopy, progFinal;
    private int oesTex, lowTex, fbo;
    private FloatBuffer posBuf, texBuf;
    private volatile SurfaceTexture st;
    private volatile Surface camSurface;
    private final float[] stMat = new float[16];
    private final float[] texMat = new float[16];
    private boolean hasFrame;

    // камера / орієнтація
    private volatile int displayRotationDeg = 90;
    private int activeSensor = 90;
    private boolean activeFront;
    private volatile int pendingSensor = 90;
    private volatile boolean pendingFront;
    private volatile boolean awaitFirst;
    private volatile long armedAtNs;

    // розмиття
    private volatile float blurTarget = 1f;
    private volatile float blurLevel = 1f;
    private volatile long unblurAtNs;

    // запис
    private long recStartNs;
    private long lastPtsNs = -1;
    private boolean encPortrait;

    // стабілізація і зум
    private Stabilizer stabilizer;
    private Stabilizer.Config stabCfg;
    private volatile float zoomEff = 1f;
    private volatile float pendingTanHalf = 0.7f;
    private volatile boolean pendingTsRealtime;
    private float activeTanHalf = 0.7f;
    private boolean activeTsRealtime;
    private float stabAmt;
    private float stabDx, stabDy, stabAlpha;
    private final float[] stabOff = new float[3];

    GlRenderer(Listener listener) {
        super("DuoCam-GL");
        this.listener = listener;
    }

    // ---------------------------------------------------------------- публічний API

    // ручна корекція повороту картинки (0/90/180/270) - запасний варіант, якщо авто не підійшло
    private volatile int extraRotation;
    private volatile int dbgSensor, dbgDisp, dbgPsi, dbgRot, dbgDet;
    private volatile String dbgClock = "?";
    private volatile float dbgLatMs;
    private volatile long exposureNs = 16_000_000L;

    void setExposureNs(long ns) { exposureNs = ns; }

    void setExtraRotation(int deg) { extraRotation = ((deg % 360) + 360) % 360; }

    String debugInfo() {
        return "сенсор " + dbgSensor + "°, екран " + dbgDisp + "°, поворот " + dbgRot
                + "°, підказка драйвера " + dbgPsi + "°" + (dbgDet > 0 ? " + дзеркало" : "")
                + ", ручна корекція " + extraRotation + "°, годинник кадрів: " + dbgClock
                + ", затримка кадру " + Math.round(dbgLatMs) + " мс";
    }

    /** Викликати до start(). */
    void setStabilization(Stabilizer s, Stabilizer.Config c) {
        stabilizer = s;
        stabCfg = c;
    }

    /** Поточний зум відносно ширини кадру об'єктива (потрібен, щоб знати реальний кут огляду). */
    void setZoom(float z) { zoomEff = Math.max(0.1f, z); }

    Surface getCameraSurface() { return camSurface; }

    void setBufferSize(int w, int h) {
        SurfaceTexture t = st;
        if (t != null) t.setDefaultBufferSize(w, h);
    }

    void setDisplayRotation(int deg) { displayRotationDeg = deg; }

    void setBlurTarget(float t) {
        unblurAtNs = 0L;
        blurTarget = t;
    }

    void setBlurImmediate(float t) {
        unblurAtNs = 0L;
        blurTarget = t;
        blurLevel = t;
    }

    /** Викликати ПЕРЕД відкриттям нової камери: наступний кадр нової камери застосує її орієнтацію. */
    void armNextCamera(CamInfo info) {
        pendingSensor = info.sensorOrientation;
        pendingFront = info.facing == CameraCharacteristics.LENS_FACING_FRONT;
        pendingTanHalf = info.tanHalf;
        pendingTsRealtime = info.tsRealtime;
        armedAtNs = SystemClock.elapsedRealtimeNanos();
        awaitFirst = true;
    }

    void setPreviewSurface(final Surface s) {
        runAndWait(() -> {
            if (previewEgl != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
                EGL14.eglDestroySurface(dpy, previewEgl);
                previewEgl = EGL14.EGL_NO_SURFACE;
            }
            if (s != null && s.isValid()) {
                EGLSurface es = EGL14.eglCreateWindowSurface(dpy, cfg, s, new int[]{EGL14.EGL_NONE}, 0);
                if (es != null && es != EGL14.EGL_NO_SURFACE) {
                    previewEgl = es;
                    EGL14.eglMakeCurrent(dpy, es, es, ctx);
                    EGL14.eglSwapInterval(dpy, 0);
                    EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
                } else {
                    Log.w(TAG, "preview eglCreateWindowSurface failed");
                }
            }
        });
    }

    boolean startEncoding(final Surface encoderInput, final long startNs, final boolean portrait) {
        final boolean[] ok = {false};
        runAndWait(() -> {
            encPortrait = portrait;
            recStartNs = startNs;
            lastPtsNs = -1;
            EGLSurface es = EGL14.eglCreateWindowSurface(dpy, cfg, encoderInput, new int[]{EGL14.EGL_NONE}, 0);
            if (es != null && es != EGL14.EGL_NO_SURFACE) {
                encEgl = es;
                EGL14.eglMakeCurrent(dpy, es, es, ctx);
                EGL14.eglSwapInterval(dpy, 0);
                EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
                ok[0] = true;
            }
        });
        return ok[0];
    }

    void stopEncoding() {
        runAndWait(() -> {
            if (encEgl != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
                EGL14.eglDestroySurface(dpy, encEgl);
                encEgl = EGL14.EGL_NO_SURFACE;
            }
        });
    }

    void shutdown() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
        try { join(2000); } catch (InterruptedException ignored) { }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture t) {
        synchronized (lock) {
            frameAvailable = true;
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- потік

    private void runAndWait(final Runnable r) {
        if (!isAlive() || !running) return;
        final CountDownLatch l = new CountDownLatch(1);
        tasks.add(() -> {
            try { r.run(); } finally { l.countDown(); }
        });
        synchronized (lock) { lock.notifyAll(); }
        try { l.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    @Override
    public void run() {
        try {
            initGl();
        } catch (Exception e) {
            Log.e(TAG, "initGl", e);
            listener.onGlError("Помилка OpenGL: " + e.getMessage());
            releaseGl();
            return;
        }
        listener.onCameraSurfaceReady();

        long last = SystemClock.elapsedRealtimeNanos();
        while (running) {
            synchronized (lock) {
                if (!frameAvailable && tasks.isEmpty()) {
                    try { lock.wait(33); } catch (InterruptedException e) { break; }
                }
            }
            Runnable t;
            while ((t = tasks.poll()) != null) {
                try { t.run(); } catch (Exception e) { Log.w(TAG, "task", e); }
            }
            if (!running) break;

            long now = SystemClock.elapsedRealtimeNanos();
            boolean got;
            synchronized (lock) {
                got = frameAvailable;
                frameAvailable = false;
            }
            float dt = Math.min(0.1f, (now - last) / 1e9f);
            last = now;
            try {
                drawFrame(now, got, dt);
            } catch (Exception e) {
                Log.w(TAG, "drawFrame", e);
            }
        }
        releaseGl();
    }

    private void drawFrame(long nowNs, boolean newFrame, float dt) {
        if (!EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)) return;

        boolean latched = false;
        if (newFrame) {
            try {
                st.updateTexImage();
                st.getTransformMatrix(stMat);
                latched = true;
                hasFrame = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "updateTexImage", e);
            }
            if (latched && awaitFirst) {
                activeSensor = pendingSensor;
                activeFront = pendingFront;
                activeTanHalf = pendingTanHalf;
                activeTsRealtime = pendingTsRealtime;
                awaitFirst = false;
                unblurAtNs = nowNs + SETTLE_NS;
                listener.onFirstFrameAfterSwitch();
            }
        }

        if (awaitFirst && nowNs - armedAtNs > ARM_TIMEOUT_NS) {
            awaitFirst = false;
            blurTarget = 0f;
            listener.onSwitchTimeout();
        }
        long ub = unblurAtNs;
        if (ub != 0L && nowNs >= ub) {
            unblurAtNs = 0L;
            blurTarget = 0f;
        }

        float lv = blurLevel;
        float target = blurTarget;
        if (lv < target) lv = Math.min(target, lv + dt / BLUR_IN_S);
        else if (lv > target) lv = Math.max(target, lv - dt / BLUR_OUT_S);
        blurLevel = lv;
        float eased = lv * lv * (3f - 2f * lv);

        if (!hasFrame) {
            clearPreview();
            return;
        }

        // матриця текстури + поворот кадру за годинниковою стрілкою на rot (0/90/180/270), щоб картинка була рівно
        int disp = displayRotationDeg;
        int rot = activeFront ? (activeSensor + disp) % 360 : (activeSensor - disp + 360) % 360;
        // Буфер камери завжди лежить у "сирій" орієнтації сенсора. Підказки повороту/дзеркала в матриці
        // SurfaceTexture (на Motorola вони різні для задньої і фронтальної камер) ігноруємо повністю й рахуємо
        // поворот самі: рядки пам'яті йдуть згори донизу (перевертання по Y), далі поворот на rot.
        int psi = Math.round((float) Math.toDegrees(Math.atan2(-stMat[1], stMat[0])) / 90f) * 90;
        dbgPsi = ((psi % 360) + 360) % 360;
        dbgDet = (stMat[0] * stMat[5] - stMat[1] * stMat[4]) > 0 ? 1 : 0;
        int need = (((rot + extraRotation) % 360) + 360) % 360;
        dbgSensor = activeSensor;
        dbgDisp = disp;
        dbgRot = rot;
        Matrix.setIdentityM(texMat, 0);
        Matrix.translateM(texMat, 0, 0.5f, 0.5f, 0f);
        Matrix.scaleM(texMat, 0, 1f, -1f, 1f);
        if (need != 0) Matrix.rotateM(texMat, 0, need, 0f, 0f, 1f);
        Matrix.translateM(texMat, 0, -0.5f, -0.5f, 0f);
        // портрет (9:16), якщо екран у вертикальному положенні; кадр камери 1920x1080 тоді вкладається 1:1
        boolean previewPortrait = disp % 180 == 0;
        float prevAspect = previewPortrait ? PORTRAIT_ASPECT : ASPECT;

        // програмна стабілізація: зсув/поворот/кроп на момент зйомки кадру
        float amtTarget = (stabCfg != null && stabilizer != null && stabilizer.isAvailable() && stabCfg.enabled)
                ? 1f : 0f;
        if (stabAmt < amtTarget) stabAmt = Math.min(amtTarget, stabAmt + dt / 0.3f);
        else if (stabAmt > amtTarget) stabAmt = Math.max(amtTarget, stabAmt - dt / 0.3f);
        stabScale = 1f;
        if (stabAmt > 0.001f) {
            float s = stabCfg.strength;
            float m = 0.05f + 0.15f * s;                       // запас по краях (кроп 5–20 %)
            // горизонтальний кут огляду у вихідному кадрі: у портреті ширина кадру - це коротка сторона сенсора
            float tanHalfOut = activeTanHalf * (previewPortrait ? PORTRAIT_ASPECT : 1f);
            float tanHalfEff = tanHalfOut / Math.max(1f, zoomEff);
            float lt = 0.6f * m;                               // бюджет на зсув
            float lr = 0.4f * m / Math.max(prevAspect, 1f / prevAspect); // бюджет на поворот (рад)
            stabilizer.configure(0.10f + 0.50f * s, lt * tanHalfEff, lt * tanHalfEff / prevAspect, lr,
                    stabCfg.roll, stabCfg.invert, disp, activeFront);
            if (latched) {
                long ts = frameTimeForGyro() + stabCfg.syncMs * 1_000_000L;
                if (stabilizer.query(ts, stabOff)) {
                    stabDx = clampAbs(stabOff[0] / tanHalfEff, lt);
                    stabDy = clampAbs(-stabOff[1] * prevAspect / tanHalfEff, lt);
                    stabAlpha = clampAbs(stabOff[2], lr);
                }
            }
            stabScale = 1f + m * stabAmt;
        } else {
            stabDx = stabDy = stabAlpha = 0f;
        }

        // прохід 1: зменшена копія кадру (джерело розмиття)
        if (eased > 0.002f) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, LOW_W, LOW_H);
            GLES20.glUseProgram(progCopy.id);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glUniform1i(progCopy.uOes, 0);
            GLES20.glUniformMatrix4fv(progCopy.uTexMat, 1, false, texMat, 0);
            GLES20.glUniform1f(progCopy.uMirror, 1f);
            GLES20.glUniform4f(progCopy.uStab, 1f, 0f, 0f, 0f);
            GLES20.glUniform1f(progCopy.uAspect, ASPECT);
            drawQuad(progCopy);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        // кодер (дзеркала немає)
        if (encEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(dpy, encEgl, encEgl, ctx);
            GLES20.glViewport(0, 0, encPortrait ? VIDEO_H : VIDEO_W, encPortrait ? VIDEO_W : VIDEO_H);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            drawFinal(1f, eased, encPortrait ? PORTRAIT_ASPECT : ASPECT);
            long pts = nowNs - recStartNs;
            if (pts <= lastPtsNs) pts = lastPtsNs + 1_000_000L;
            lastPtsNs = pts;
            EGLExt.eglPresentationTimeANDROID(dpy, encEgl, pts);
            EGL14.eglSwapBuffers(dpy, encEgl);
        }

        // екран (фронтальна дзеркалиться тільки в прев'ю)
        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(dpy, previewEgl, previewEgl, ctx);
            int[] wh = new int[1];
            EGL14.eglQuerySurface(dpy, previewEgl, EGL14.EGL_WIDTH, wh, 0);
            int w = wh[0];
            EGL14.eglQuerySurface(dpy, previewEgl, EGL14.EGL_HEIGHT, wh, 0);
            int h = wh[0];
            GLES20.glViewport(0, 0, w, h);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            int vw, vh;
            if (w / (float) Math.max(1, h) > prevAspect) {
                vh = h;
                vw = Math.round(h * prevAspect);
            } else {
                vw = w;
                vh = Math.round(w / prevAspect);
            }
            GLES20.glViewport((w - vw) / 2, (h - vh) / 2, vw, vh);
            drawFinal(activeFront ? -1f : 1f, eased, prevAspect);
            EGL14.eglSwapBuffers(dpy, previewEgl);
        }
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
    }

    private float stabScale = 1f;

    /**
     * Момент часу (у годиннику гіроскопа) середини експозиції цього кадру. Саме на цей момент треба брати кут
     * із гіроскопа; помилка в десятки мілісекунд на частоті тремтіння рук (5-8 Гц) повністю знищує стабілізацію.
     */
    private long frameTimeForGyro() {
        long ts = st.getTimestamp();
        long bootNow = SystemClock.elapsedRealtimeNanos();
        long monoNow = System.nanoTime();
        long latBoot = bootNow - ts;
        long latMono = monoNow - ts;
        final long MAXLAT = 500_000_000L;
        long out;
        if (activeTsRealtime && latBoot >= 0 && latBoot < MAXLAT) {
            out = ts;
            dbgClock = "realtime";
            dbgLatMs = latBoot / 1e6f;
        } else if (latMono >= 0 && latMono < MAXLAT) {
            out = ts + (bootNow - monoNow);   // перевід monotonic -> elapsedRealtime
            dbgClock = "monotonic";
            dbgLatMs = latMono / 1e6f;
        } else if (latBoot >= 0 && latBoot < MAXLAT) {
            out = ts;
            dbgClock = "realtime?";
            dbgLatMs = latBoot / 1e6f;
        } else {
            out = bootNow - 80_000_000L;
            dbgClock = "невідомо";
            dbgLatMs = 80f;
        }
        // timestamp = початок експозиції першого рядка; центр кадру знято приблизно через
        // половину зчитування (~12 мс) + половину експозиції
        return out + 12_000_000L + exposureNs / 2;
    }

    private static float clampAbs(float v, float lim) {
        return v > lim ? lim : (v < -lim ? -lim : v);
    }

    private void clearPreview() {
        if (previewEgl == EGL14.EGL_NO_SURFACE) return;
        EGL14.eglMakeCurrent(dpy, previewEgl, previewEgl, ctx);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        EGL14.eglSwapBuffers(dpy, previewEgl);
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
    }

    private void drawFinal(float mirror, float blurEased, float aspect) {
        GLES20.glUseProgram(progFinal.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniform1i(progFinal.uOes, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lowTex);
        GLES20.glUniform1i(progFinal.uLow, 1);
        GLES20.glUniformMatrix4fv(progFinal.uTexMat, 1, false, texMat, 0);
        GLES20.glUniform1f(progFinal.uMirror, mirror);
        GLES20.glUniform1f(progFinal.uBlur, blurEased);
        GLES20.glUniform2f(progFinal.uTexel, 1f / LOW_W, 1f / LOW_H);
        GLES20.glUniform4f(progFinal.uStab, stabScale, stabAlpha * stabAmt, stabDx * stabAmt, stabDy * stabAmt);
        GLES20.glUniform1f(progFinal.uAspect, aspect);
        drawQuad(progFinal);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void drawQuad(Prog p) {
        GLES20.glEnableVertexAttribArray(p.aPos);
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(p.aTex);
        GLES20.glVertexAttribPointer(p.aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(p.aPos);
        GLES20.glDisableVertexAttribArray(p.aTex);
    }

    // ---------------------------------------------------------------- ініціалізація

    private void initGl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (dpy == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("no EGL display");
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw new RuntimeException("eglInitialize");

        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) {
            throw new RuntimeException("eglChooseConfig");
        }
        cfg = cfgs[0];
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)) throw new RuntimeException("eglMakeCurrent");

        progCopy = buildProgram(VERT, FRAG_COPY);
        progFinal = buildProgram(VERT, FRAG_FINAL);

        int[] t = new int[2];
        GLES20.glGenTextures(2, t, 0);
        oesTex = t[0];
        lowTex = t[1];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lowTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, LOW_W, LOW_H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fbo = f[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, lowTex, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        posBuf = floatBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        texBuf = floatBuffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});

        SurfaceTexture s = new SurfaceTexture(oesTex);
        s.setDefaultBufferSize(VIDEO_W, VIDEO_H);
        s.setOnFrameAvailableListener(this);
        st = s;
        camSurface = new Surface(s);
        Matrix.setIdentityM(stMat, 0);
        Matrix.setIdentityM(texMat, 0);
    }

    private void releaseGl() {
        try {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
                if (encEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, encEgl);
                if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, previewEgl);
                encEgl = EGL14.EGL_NO_SURFACE;
                previewEgl = EGL14.EGL_NO_SURFACE;
            }
            Surface cs = camSurface;
            if (cs != null) cs.release();
            SurfaceTexture s = st;
            if (s != null) {
                s.setOnFrameAvailableListener(null);
                s.release();
            }
            camSurface = null;
            st = null;
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf);
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglTerminate(dpy);
            }
        } catch (Exception e) {
            Log.w(TAG, "releaseGl", e);
        }
        dpy = EGL14.EGL_NO_DISPLAY;
        ctx = EGL14.EGL_NO_CONTEXT;
        pbuf = EGL14.EGL_NO_SURFACE;
    }

    private static FloatBuffer floatBuffer(float[] a) {
        FloatBuffer fb = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(a).position(0);
        return fb;
    }

    private static Prog buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int id = GLES20.glCreateProgram();
        GLES20.glAttachShader(id, v);
        GLES20.glAttachShader(id, f);
        GLES20.glLinkProgram(id);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(id);
            GLES20.glDeleteProgram(id);
            throw new RuntimeException("link: " + log);
        }
        Prog p = new Prog();
        p.id = id;
        p.aPos = GLES20.glGetAttribLocation(id, "aPos");
        p.aTex = GLES20.glGetAttribLocation(id, "aTex");
        p.uTexMat = GLES20.glGetUniformLocation(id, "uTexMat");
        p.uMirror = GLES20.glGetUniformLocation(id, "uMirror");
        p.uOes = GLES20.glGetUniformLocation(id, "uOes");
        p.uLow = GLES20.glGetUniformLocation(id, "uLow");
        p.uBlur = GLES20.glGetUniformLocation(id, "uBlur");
        p.uTexel = GLES20.glGetUniformLocation(id, "uTexel");
        p.uStab = GLES20.glGetUniformLocation(id, "uStab");
        p.uAspect = GLES20.glGetUniformLocation(id, "uAspect");
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("shader: " + log);
        }
        return s;
    }
}
