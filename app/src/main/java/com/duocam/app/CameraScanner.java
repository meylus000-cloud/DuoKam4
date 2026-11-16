package com.duocam.app;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Знаходить усі камери/об'єктиви телефону і розкладає їх по ролях: Фронт / 1x / 0.5x / Макро. */
final class CameraScanner {

    enum Slot { FRONT, MAIN, WIDE, MACRO }

    private CameraScanner() {}

    static List<CamInfo> scan(CameraManager cm) {
        List<CamInfo> out = new ArrayList<>();
        try {
            String[] ids = cm.getCameraIdList();
            Set<String> idSet = new HashSet<>(Arrays.asList(ids));
            for (String id : ids) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                CamInfo ci = build(id, null, ch, ch);
                if (ci != null) out.add(ci);

                Set<String> phys = ch.getPhysicalCameraIds();
                for (String pid : phys) {
                    if (idSet.contains(pid)) continue; // її й так видно як окрему камеру
                    try {
                        CameraCharacteristics pch = cm.getCameraCharacteristics(pid);
                        CamInfo pi = build(id, pid, ch, pch);
                        if (pi != null) out.add(pi);
                    } catch (Exception ignored) {
                        // фізична камера недоступна стороннім застосункам
                    }
                }
            }
            // камери, яких немає у списку, але які система все ж віддає за id (надширока / макро в деяких Motorola)
            for (int i = 0; i < 12; i++) {
                String pid = String.valueOf(i);
                if (idSet.contains(pid)) continue;
                boolean known = false;
                for (CamInfo k : out) {
                    if (pid.equals(k.physicalId)) known = true;
                }
                if (known) continue;
                try {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(pid);
                    CamInfo ci = build(pid, null, ch, ch);
                    if (ci != null) {
                        ci.unlisted = true;
                        out.add(ci);
                    }
                } catch (Exception ignored) {
                    // id не існує або заборонений
                }
            }
        } catch (CameraAccessException e) {
            // повернемо те, що встигли знайти
        }

        // Варіанти "зум логічної камери" (напр. 0.6x) - запасний шлях для надширокого кута.
        List<CamInfo> extra = new ArrayList<>();
        for (CamInfo c : out) {
            if (c.physicalId == null && c.zoomRatio == 1f && c.fallbackZoom > 0f) {
                CamInfo z = c.copy();
                z.zoomRatio = c.fallbackZoom;
                z.eqFocal = c.eqFocal * c.fallbackZoom;
                z.tanHalf = c.tanHalf / c.fallbackZoom;
                z.fallbackZoom = 0f;
                extra.add(z);
            }
        }
        out.addAll(extra);
        return out;
    }

    private static CamInfo build(String logicalId, String physId,
                                 CameraCharacteristics lch, CameraCharacteristics sch) {
        int[] caps = lch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean compatible = false;
        if (caps != null) {
            for (int c : caps) {
                if (c == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) compatible = true;
            }
        }
        if (!compatible) return null;

        Integer facing = lch.get(CameraCharacteristics.LENS_FACING);
        if (facing == null || facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return null;

        StreamConfigurationMap map = sch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size size = pickSize(map);
        if (size == null) return null;

        CamInfo ci = new CamInfo();
        ci.logicalId = logicalId;
        ci.physicalId = physId;
        ci.facing = facing;
        ci.videoSize = size;

        float[] fl = sch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF ps = sch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (fl != null && fl.length > 0 && ps != null) {
            double diag = Math.hypot(ps.getWidth(), ps.getHeight());
            if (diag > 0) ci.eqFocal = (float) (fl[0] * 43.27 / diag);
        }

        Float mf = sch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        ci.minFocus = mf == null ? 0f : mf;

        Size pa = sch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (pa != null) ci.megapixels = pa.getWidth() * (float) pa.getHeight() / 1e6f;

        Integer so = sch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        ci.sensorOrientation = so == null ? 90 : so;

        // FPS і автофокус беремо з логічної камери - саме їй йде запит
        Range<Integer>[] ranges = lch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null && ranges.length > 0) {
            Range<Integer> best = null;
            for (Range<Integer> r : ranges) {
                if (r.getUpper() == 30 && (best == null || r.getLower() > best.getLower())) best = r;
            }
            ci.fps = best != null ? best : ranges[0];
        }

        int[] af = lch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (af != null) {
            for (int m : af) {
                if (m == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ci.afContinuous = true;
            }
        }

        Float maxDig = lch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        Range<Float> zr = lch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zr != null) {
            ci.hasZoomRatio = true;
            ci.zoomMin = zr.getLower();
            ci.zoomMax = zr.getUpper();
            if (zr.getLower() < 0.95f) ci.fallbackZoom = zr.getLower();
        } else {
            ci.zoomMin = 1f;
            ci.zoomMax = maxDig == null ? 1f : maxDig;
        }

        // спалах, ручна експозиція, EV, баланс білого
        Boolean flash = lch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        ci.hasFlash = flash != null && flash;
        if (caps != null) {
            for (int c : caps) {
                if (c == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) ci.manualExposure = true;
            }
        }
        Range<Integer> isoR = lch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (isoR != null) {
            ci.isoMin = isoR.getLower();
            ci.isoMax = isoR.getUpper();
        }
        Range<Long> expR = lch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (expR != null) {
            ci.expMinNs = expR.getLower();
            ci.expMaxNs = expR.getUpper();
        }
        Range<Integer> evR = lch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (evR != null) {
            ci.evMin = evR.getLower();
            ci.evMax = evR.getUpper();
        }
        Rational evStep = lch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (evStep != null) ci.evStep = evStep.floatValue();
        int[] awb = lch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (awb != null) ci.awbModes = awb;
        ci.activeArray = lch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer tsSrc = lch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        ci.tsRealtime = tsSrc != null && tsSrc == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        if (fl != null && fl.length > 0 && ps != null && fl[0] > 0f) {
            ci.tanHalf = ps.getWidth() / (2f * fl[0]);
        }

        return ci;
    }

    /** Найбільший розмір ≤1080p, 16:9 - у пріоритеті. */
    private static Size pickSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        boolean bestIs169 = false;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
            boolean is169 = Math.abs(s.getWidth() / (double) s.getHeight() - 16.0 / 9.0) < 0.02;
            long area = (long) s.getWidth() * s.getHeight();
            if (best == null
                    || (is169 && !bestIs169)
                    || (is169 == bestIs169 && area > (long) best.getWidth() * best.getHeight())) {
                best = s;
                bestIs169 = is169;
            }
        }
        if (best == null) {
            for (Size s : sizes) {
                if (best == null || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        return best;
    }

    static boolean isMacroish(CamInfo c) {
        return c.zoomRatio == 1f && (c.megapixels <= 3.5f || (c.minFocus >= 14f && c.megapixels <= 6f));
    }

    /** Автоматичне розкладання: головна ≈ 26 мм, надширока значно ширша, макро - близький фокус / малий сенсор. */
    static Map<Slot, CamInfo> autoMap(List<CamInfo> all) {
        EnumMap<Slot, CamInfo> m = new EnumMap<>(Slot.class);
        CamInfo front = null;
        List<CamInfo> back = new ArrayList<>();
        for (CamInfo c : all) {
            if (c.facing == CameraCharacteristics.LENS_FACING_FRONT) {
                if (c.zoomRatio == 1f && (front == null || (front.physicalId != null && c.physicalId == null))) {
                    front = c;
                }
            } else if (c.facing == CameraCharacteristics.LENS_FACING_BACK) {
                back.add(c);
            }
        }
        if (front != null) m.put(Slot.FRONT, front);
        if (back.isEmpty()) return m;

        CamInfo main = null;
        float bestD = 1e9f;
        for (CamInfo c : back) {
            if (c.zoomRatio != 1f) continue;
            float d = Math.abs(c.eqFocal - 26f);
            if (c.physicalId == null) d -= 2f;
            if (isMacroish(c)) d += 100f;
            if (d < bestD) {
                bestD = d;
                main = c;
            }
        }
        if (main == null) main = back.get(0);
        m.put(Slot.MAIN, main);

        CamInfo wide = null;
        float wideEq = 1e9f;
        for (CamInfo c : back) {
            if (c == main || isMacroish(c)) continue;
            float eq = c.eqFocal + (c.zoomRatio != 1f ? 3f : 0f); // справжній об'єктив краще за зум
            if (eq < main.eqFocal * 0.8f && eq < wideEq) {
                wideEq = eq;
                wide = c;
            }
        }
        if (wide != null) m.put(Slot.WIDE, wide);

        CamInfo macro = null;
        for (CamInfo c : back) {
            if (c == main || c == wide || !isMacroish(c)) continue;
            if (macro == null || c.minFocus > macro.minFocus
                    || (c.minFocus == macro.minFocus && c.megapixels < macro.megapixels)) {
                macro = c;
            }
        }
        if (macro != null) m.put(Slot.MACRO, macro);

        // запасний варіант: фокусна/розмір сенсора могли визначитись неточно - роздаємо решту задніх камер
        List<CamInfo> rest = new ArrayList<>();
        for (CamInfo c : back) {
            if (c == main || c == wide || c == macro || c.zoomRatio != 1f) continue;
            boolean dupOfMain = Math.abs(c.eqFocal - main.eqFocal) < 0.1f * main.eqFocal
                    && c.megapixels >= main.megapixels * 0.9f;
            if (dupOfMain) continue;
            rest.add(c);
        }
        Collections.sort(rest, (a, b) -> Float.compare(a.eqFocal, b.eqFocal));
        if (wide == null) {
            for (CamInfo c : rest) {
                if (!isMacroish(c)) {
                    wide = c;
                    break;
                }
            }
            if (wide != null) {
                m.put(Slot.WIDE, wide);
                rest.remove(wide);
            }
        }
        if (macro == null) {
            for (CamInfo c : rest) {
                if (isMacroish(c)) {
                    macro = c;
                    break;
                }
            }
            if (macro == null && !rest.isEmpty()) macro = rest.get(0);
            if (macro != null) m.put(Slot.MACRO, macro);
        }
        return m;
    }

    // ---------------------------------------------------------------- діагностика

    /** Повний перелік того, що система віддає застосунку. Для показу користувачу. */
    static String diagnose(CameraManager cm) {
        StringBuilder sb = new StringBuilder();
        try {
            String[] ids = cm.getCameraIdList();
            Set<String> idSet = new HashSet<>(Arrays.asList(ids));
            sb.append("Система віддає камери: ").append(Arrays.toString(ids)).append("\n\n");
            for (String id : ids) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                describeChar(sb, "id " + id, ch);
                Set<String> phys = ch.getPhysicalCameraIds();
                if (!phys.isEmpty()) {
                    sb.append("  фізичні камери: ").append(phys).append("\n");
                    for (String pid : phys) {
                        try {
                            describeChar(sb, "  └ фіз. " + pid, cm.getCameraCharacteristics(pid));
                        } catch (Exception e) {
                            sb.append("  └ фіз. ").append(pid).append(": недоступна стороннім застосункам\n");
                        }
                    }
                }
                sb.append("\n");
            }
            sb.append("Перевірка id, яких немає у списку (0–11):\n");
            for (int i = 0; i < 12; i++) {
                String id = String.valueOf(i);
                if (idSet.contains(id)) continue;
                try {
                    cm.getCameraCharacteristics(id);
                    sb.append("  id ").append(id).append(": ДОСТУПНА\n");
                } catch (Exception e) {
                    sb.append("  id ").append(id).append(": недоступна\n");
                }
            }
        } catch (Exception e) {
            sb.append("Помилка: ").append(e);
        }
        return sb.toString();
    }

    private static void describeChar(StringBuilder sb, String title, CameraCharacteristics ch) {
        Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
        float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF ps = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size pa = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Float mf = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Integer lvl = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        Range<Float> zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);

        sb.append(title).append(": ");
        sb.append(facing == null ? "?" : facing == CameraCharacteristics.LENS_FACING_FRONT ? "фронтальна"
                : facing == CameraCharacteristics.LENS_FACING_BACK ? "задня" : "зовнішня");
        sb.append(", рівень ").append(lvl == null ? "?" : lvl);
        sb.append("\n    фокусна: ").append(fl == null ? "?" : Arrays.toString(fl)).append(" мм");
        if (ps != null) sb.append(", сенсор ").append(ps.getWidth()).append("×").append(ps.getHeight()).append(" мм");
        if (pa != null) {
            sb.append(", ").append(pa.getWidth()).append("×").append(pa.getHeight())
                    .append(" (").append(Math.round(pa.getWidth() * (float) pa.getHeight() / 1e6f)).append(" Мп)");
        }
        sb.append("\n    мін. фокус: ").append(mf == null ? "?" : mf).append(" дптр");
        sb.append(", спалах: ").append(flash != null && flash ? "є" : "немає");
        if (zr != null) sb.append(", зум ").append(zr.getLower()).append("–").append(zr.getUpper());
        sb.append("\n    можливості: ");
        if (caps != null) {
            for (int c : caps) {
                sb.append(c == 0 ? "звичайна" : c == 1 ? "ручна експозиція" : c == 8 ? "ГЛИБИНА"
                        : c == 11 ? "логічна" : String.valueOf(c)).append(' ');
            }
        }
        sb.append("\n");
    }
}
