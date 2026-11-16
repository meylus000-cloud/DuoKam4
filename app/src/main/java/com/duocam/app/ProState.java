package com.duocam.app;

import android.hardware.camera2.CameraMetadata;

/** Спільний стан ручних налаштувань (PRO), ліхтарика і зуму між інтерфейсом і камерою. */
final class ProState {
    volatile boolean proMode;

    volatile boolean manualExp;
    volatile int iso = 100;
    volatile long shutterNs = 16_666_667L;

    volatile boolean manualFocus;
    volatile float focusDiopter;

    volatile int evSteps;
    volatile int awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;

    volatile boolean torch;

    volatile float zoom = 1f;
    volatile float zoomMax = 1f;

    // що камера реально застосувала (для відображення в автоматичному режимі)
    volatile int lastIso;
    volatile long lastShutterNs;
    volatile float lastFocus;

    /** Об'єктив змінився - діапазони інші, ручні значення скидаємо в авто. Ліхтарик і PRO-режим лишаються. */
    void resetForNewCamera() {
        manualExp = false;
        manualFocus = false;
        evSteps = 0;
        awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
        zoom = 1f;
    }
}
