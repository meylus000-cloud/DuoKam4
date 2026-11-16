package com.duocam.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Програмна (цифрова) стабілізація на гіроскопі.
 *
 * Інтегруємо кутову швидкість у кути, згладжуємо їх фільтром низьких частот; різниця
 * "згладжений кут - справжній кут" і є поправкою: рендерер зсуває/повертає картинку
 * на цю величину (з невеликим запасом-кропом по краях). Кути зберігаються в кільцевому
 * буфері, щоб брати поправку саме на момент зйомки кадру (за його timestamp).
 */
final class Stabilizer implements SensorEventListener {

    static final class Config {
        volatile boolean enabled;
        volatile float strength = 0.5f;   // 0..1
        volatile boolean roll = true;     // вирівнювати нахил
        volatile boolean invert;          // аварійний перемикач знаку
        volatile int syncMs;              // ручна підстройка часу кадру відносно гіроскопа (мс)
    }

    private static final int CAP = 1024;

    private final SensorManager sm;
    private final Sensor gyro;
    private HandlerThread thread;

    private final Object lock = new Object();
    private final long[] ts = new long[CAP];
    private final float[] ox = new float[CAP];
    private final float[] oy = new float[CAP];
    private final float[] oz = new float[CAP];
    private int head = -1;
    private int count = 0;

    // стан інтегратора (потік сенсора)
    private long lastTs;
    private float tx, ty, tz, sx, sy, sz;

    // параметри (пише рендерер, читає потік сенсора)
    private volatile float tau = 0.35f;
    private volatile float limYaw = 0.05f;
    private volatile float limPitch = 0.03f;
    private volatile float limRoll = 0.02f;
    private volatile int displayRot = 90;
    private volatile boolean front;
    private volatile boolean useRoll = true;
    private volatile boolean invert;

    private final float[] axU = new float[3];
    private final float[] axR = new float[3];
    private final float[] axF = new float[3];

    Stabilizer(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    boolean isAvailable() { return gyro != null; }

    void start() {
        if (gyro == null || thread != null) return;
        synchronized (lock) {
            head = -1;
            count = 0;
        }
        lastTs = 0;
        tx = ty = tz = sx = sy = sz = 0f;
        thread = new HandlerThread("DuoCam-Gyro");
        thread.start();
        sm.registerListener(this, gyro, 5000, new Handler(thread.getLooper())); // 200 Гц
    }

    void stop() {
        if (thread == null) return;
        sm.unregisterListener(this);
        thread.quitSafely();
        thread = null;
    }

    void configure(float tau, float limYaw, float limPitch, float limRoll,
                   boolean useRoll, boolean invert, int displayRot, boolean front) {
        this.tau = tau;
        this.limYaw = limYaw;
        this.limPitch = limPitch;
        this.limRoll = limRoll;
        this.useRoll = useRoll;
        this.invert = invert;
        this.displayRot = displayRot;
        this.front = front;
    }

    /**
     * Поправка на момент tsNs у системі координат зображення (радіани):
     * out[0] - рискання (зсув по горизонталі), out[1] - тангаж (по вертикалі), out[2] - крен (поворот).
     */
    boolean query(long tsNs, float[] out) {
        float x, y, z;
        synchronized (lock) {
            if (count == 0) return false;
            int newest = head;
            int idx = newest;
            if (tsNs >= ts[newest]) {
                x = ox[newest];
                y = oy[newest];
                z = oz[newest];
            } else {
                int steps = 0;
                while (steps < count - 1 && ts[idx] > tsNs) {
                    idx = (idx - 1 + CAP) % CAP;
                    steps++;
                }
                if (ts[idx] > tsNs) { // старіше за все, що маємо
                    x = ox[idx];
                    y = oy[idx];
                    z = oz[idx];
                } else {
                    int nxt = (idx + 1) % CAP;
                    long span = ts[nxt] - ts[idx];
                    float f = span > 0 ? (float) (tsNs - ts[idx]) / span : 0f;
                    x = ox[idx] + (ox[nxt] - ox[idx]) * f;
                    y = oy[idx] + (oy[nxt] - oy[idx]) * f;
                    z = oz[idx] + (oz[nxt] - oz[idx]) * f;
                }
            }
        }
        float[] u = new float[3], r = new float[3], fw = new float[3];
        axes(displayRot, front, u, r, fw);
        float yaw = x * u[0] + y * u[1] + z * u[2];
        float pitch = x * r[0] + y * r[1] + z * r[2];
        float roll = useRoll ? (x * fw[0] + y * fw[1] + z * fw[2]) : 0f;
        float sgn = invert ? -1f : 1f;
        out[0] = yaw * sgn;
        out[1] = pitch * sgn;
        out[2] = roll * sgn;
        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long t = e.timestamp;
        if (lastTs == 0) {
            lastTs = t;
            return;
        }
        float dt = (t - lastTs) * 1e-9f;
        lastTs = t;
        if (dt <= 0f || dt > 0.1f) return;

        tx += e.values[0] * dt;
        ty += e.values[1] * dt;
        tz += e.values[2] * dt;

        float a = 1f - (float) Math.exp(-dt / Math.max(0.02f, tau));
        sx += (tx - sx) * a;
        sy += (ty - sy) * a;
        sz += (tz - sz) * a;

        // поправка не повинна виходити за запас кропу: підтягуємо згладжений кут
        axes(displayRot, front, axU, axR, axF);
        float ox0 = sx - tx, oy0 = sy - ty, oz0 = sz - tz;
        float yaw = ox0 * axU[0] + oy0 * axU[1] + oz0 * axU[2];
        float pitch = ox0 * axR[0] + oy0 * axR[1] + oz0 * axR[2];
        float roll = ox0 * axF[0] + oy0 * axF[1] + oz0 * axF[2];
        float ey = excess(yaw, limYaw);
        float ep = excess(pitch, limPitch);
        float er = excess(roll, limRoll);
        if (ey != 0f) { sx -= ey * axU[0]; sy -= ey * axU[1]; sz -= ey * axU[2]; }
        if (ep != 0f) { sx -= ep * axR[0]; sy -= ep * axR[1]; sz -= ep * axR[2]; }
        if (er != 0f) { sx -= er * axF[0]; sy -= er * axF[1]; sz -= er * axF[2]; }

        synchronized (lock) {
            head = (head + 1) % CAP;
            ts[head] = t;
            ox[head] = sx - tx;
            oy[head] = sy - ty;
            oz[head] = sz - tz;
            if (count < CAP) count++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static float excess(float v, float lim) {
        if (v > lim) return v - lim;
        if (v < -lim) return v + lim;
        return 0f;
    }

    /**
     * Осі зображення в системі координат пристрою (одиничні вектори).
     * U - вгору по зображенню, R - вправо по зображенню, F - вперед (куди дивиться камера).
     * Екран при повороті: 0: R=+x, U=+y; 90: R=-y, U=+x; 180: R=-x, U=-y; 270: R=+y, U=-x.
     * Задня камера: зображення = вікно крізь телефон, F = -z. Фронтальна (без дзеркала): R інвертується, F = +z.
     */
    private static void axes(int rot, boolean front, float[] u, float[] r, float[] f) {
        float rx = 0, ry = 0, ux = 0, uy = 0;
        switch (rot) {
            case 90:  ry = -1; ux = 1; break;
            case 180: rx = -1; uy = -1; break;
            case 270: ry = 1; ux = -1; break;
            default:  rx = 1; uy = 1; break;
        }
        u[0] = ux; u[1] = uy; u[2] = 0;
        float s = front ? -1f : 1f;
        r[0] = rx * s; r[1] = ry * s; r[2] = 0;
        f[0] = 0; f[1] = 0; f[2] = front ? 1f : -1f;
    }
}
