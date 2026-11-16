package com.duocam.app;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;
import android.util.Size;

import java.util.Locale;

/** Один "кандидат" на роль камери: окрема камера, фізичний об'єктив логічної камери або зум. */
final class CamInfo {
    String logicalId;
    String physicalId;          // null = відкриваємо саму камеру
    int facing;                 // CameraCharacteristics.LENS_FACING_*
    float eqFocal = 26f;        // еквівалентна фокусна відстань (35 мм), мм
    float minFocus;             // мін. дистанція фокусування, діоптрії (0 = фіксований фокус)
    float megapixels = 12f;
    int sensorOrientation = 90;
    Size videoSize = new Size(1920, 1080);
    Range<Integer> fps = new Range<>(30, 30);
    boolean afContinuous;
    float zoomRatio = 1f;       // !=1 -> зум логічної камери (варіант "0.5x")
    float fallbackZoom = 0f;    // зум логічної камери, якщо фізичний потік не вдалося відкрити

    // можливості для PRO / ліхтарика / зуму / стабілізації
    boolean hasFlash;
    boolean manualExposure;     // MANUAL_SENSOR: ручні ISO і витримка
    int isoMin = 100, isoMax = 800;
    long expMinNs = 100_000L, expMaxNs = 33_333_333L;
    int evMin, evMax;
    float evStep = 0.1667f;
    int[] awbModes = new int[0];
    float tanHalf = 0.70f;      // tan(половини горизонтального кута огляду)
    Rect activeArray;
    boolean hasZoomRatio;
    float zoomMin = 1f, zoomMax = 1f;
    boolean unlisted;           // камера не в getCameraIdList(), знайдена пробою id
    boolean tsRealtime;         // timestamp кадрів у тій же шкалі, що й гіроскоп

    String key() {
        return logicalId + "|" + (physicalId == null ? "-" : physicalId) + (zoomRatio != 1f ? "|z" : "");
    }

    CamInfo copy() {
        CamInfo c = new CamInfo();
        c.logicalId = logicalId;
        c.physicalId = physicalId;
        c.facing = facing;
        c.eqFocal = eqFocal;
        c.minFocus = minFocus;
        c.megapixels = megapixels;
        c.sensorOrientation = sensorOrientation;
        c.videoSize = videoSize;
        c.fps = fps;
        c.afContinuous = afContinuous;
        c.zoomRatio = zoomRatio;
        c.fallbackZoom = fallbackZoom;
        c.hasFlash = hasFlash;
        c.manualExposure = manualExposure;
        c.isoMin = isoMin;
        c.isoMax = isoMax;
        c.expMinNs = expMinNs;
        c.expMaxNs = expMaxNs;
        c.evMin = evMin;
        c.evMax = evMax;
        c.evStep = evStep;
        c.awbModes = awbModes;
        c.tanHalf = tanHalf;
        c.activeArray = activeArray;
        c.hasZoomRatio = hasZoomRatio;
        c.zoomMin = zoomMin;
        c.zoomMax = zoomMax;
        c.tsRealtime = tsRealtime;
        c.unlisted = unlisted;
        return c;
    }

    String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(facing == CameraCharacteristics.LENS_FACING_FRONT ? "Фронтальна" : "Задня");
        sb.append(" · id ").append(logicalId);
        if (physicalId != null) sb.append('/').append(physicalId);
        sb.append(" · ").append(Math.round(eqFocal)).append(" мм · ")
                .append(Math.round(megapixels)).append(" Мп");
        if (minFocus > 0f) sb.append(" · фокус від ").append(Math.round(100f / minFocus)).append(" см");
        if (unlisted) sb.append(" · прихована");
        if (zoomRatio != 1f) sb.append(String.format(Locale.US, " · зум ×%.1f", zoomRatio));
        return sb.toString();
    }
}
