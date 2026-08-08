package io.github.fairyxh.volumelimiter.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.utils.LogUtils;

/**
 * 真实音量空间的限制与调节步长计算器。不引入虚拟音量空间：
 * UI/API 始终使用 Android 真实索引（0..nativeMaxIndex），getStreamVolume /
 * getStreamMaxVolume 完全直通原值。
 *
 * <p>两个独立概念：
 * <ul>
 *   <li>最大音量限制：nativeLimitIndex = nativeMaxIndex * masterPercent / 100，
 *       所有设置路径（setStreamVolume、adjustStreamVolume）都钳制到该上限。</li>
 *   <li>调节精度：音量键一次调整的步长 = round(nativeMaxIndex / precision)。
 *       例如 nativeMax=160、precision=50 → 每次约 3；precision=100 → 每次约 2。
 *       precision 只影响 adjustStreamVolume 的步进，不改变真实最大值。</li>
 * </ul>
 */
public final class VolumeIndexMapper {
    /** 每个音频流缓存的真实 native 最大索引（AudioSystem.getMaxVolumeIndex 原值）。 */
    private final Map<Integer, Integer> nativeMaxByStream = new ConcurrentHashMap<>();

    /** 保存真实最大索引；<=0 的无效值不缓存。 */
    public void rememberNativeMax(int streamType, int nativeMax) {
        if (nativeMax > 0) {
            nativeMaxByStream.put(streamType, nativeMax);
        }
    }

    /** 真实 native 最大索引；未缓存时返回 -1。 */
    public int nativeMaxIndex(int streamType) {
        Integer value = nativeMaxByStream.get(streamType);
        return value == null ? -1 : value;
    }

    /** 调节精度是否激活（precision >= 1）。 */
    public boolean isActive(int precision) {
        return precision >= PreferenceStorage.MIN_ADJUST_PRECISION;
    }

    /**
     * 真实空间上限：nativeMaxIndex * percent / 100（向上取整，保留至少 1 档）。
     * 与调节精度无关；真实最大值未缓存时返回 -1。
     */
    public int nativeLimit(int streamType, int percent) {
        int realMax = nativeMaxIndex(streamType);
        if (realMax <= 0) {
            return -1;
        }
        return calculateLimitedIndex(realMax, percent);
    }

    /**
     * 音量键一次调整的步长：round(nativeMaxIndex / precision)，至少 1 档。
     * 未缓存真实最大值或精度未激活时返回 -1，调用方应视为步进不可用。
     */
    public int adjustStep(int streamType, int precision) {
        int realMax = nativeMaxIndex(streamType);
        if (!isActive(precision) || realMax <= 0) {
            return -1;
        }
        return calculateAdjustStep(realMax, precision);
    }

    /** 纯静态步长计算：round(nativeMax / precision)，至少 1；参数无效返回 -1。 */
    static int calculateAdjustStep(int nativeMax, int precision) {
        if (nativeMax <= 0 || precision < PreferenceStorage.MIN_ADJUST_PRECISION) {
            return -1;
        }
        long step = Math.round((double) nativeMax / precision);
        return (int) Math.max(1L, step);
    }

    /**
     * 读取真实 native 最大值并缓存，优先使用 AudioService.getStreamMaxVolume
     * 实例方法（Oplus/ColorOS 上 AudioSystem.getMaxVolumeIndex 反射可能不可用）。
     * 失败时返回 -1。
     */
    static int readNativeMax(ClassLoader classLoader, Object audioService,
                             Method getStreamMaxVolume, int streamType,
                             VolumeIndexMapper mapper) {
        if (mapper.nativeMaxIndex(streamType) > 0) {
            return mapper.nativeMaxIndex(streamType);
        }
        if (audioService != null && getStreamMaxVolume != null) {
            try {
                Object result = getStreamMaxVolume.invoke(audioService, streamType);
                if (result instanceof Integer value && value > 0) {
                    mapper.rememberNativeMax(streamType, value);
                    return value;
                }
            } catch (Throwable error) {
                LogUtils.info("VolumeMapper nativeMax via getStreamMaxVolume failed stream="
                        + streamType + ": " + error);
            }
        }
        int fallback = readNativeMax(classLoader, streamType, mapper);
        if (fallback < 0) {
            LogUtils.info("VolumeMapper nativeMax unknown stream=" + streamType
                    + " (getStreamMaxVolume=" + (getStreamMaxVolume != null) + ")");
        }
        return fallback;
    }

    /**
     * 反射读取 AudioSystem.getMaxVolumeIndex 真实值（不 hook native 方法）；
     * 读取成功时顺便缓存到指定 mapper。失败返回 -1。
     */
    static int readNativeMax(ClassLoader classLoader, int streamType,
                             VolumeIndexMapper mapper) {
        if (mapper.nativeMaxIndex(streamType) > 0) {
            return mapper.nativeMaxIndex(streamType);
        }
        try {
            Class<?> type = Class.forName("android.media.AudioSystem", false, classLoader);
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("getMaxVolumeIndex")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == int.class
                        && method.getReturnType() == int.class) {
                    method.setAccessible(true);
                    Object result = method.invoke(null, streamType);
                    if (result instanceof Integer value) {
                        mapper.rememberNativeMax(streamType, value);
                        return value;
                    }
                    return -1;
                }
            }
        } catch (Throwable error) {
            LogUtils.debug("VolumeMapper unable to read native max stream=" + streamType
                    + ": " + error);
        }
        return -1;
    }

    /** 百分比 → 真实空间上限（与 VolumeAdjustHook 相同的溢出安全向上取整语义）。 */
    static int calculateLimitedIndex(int systemMaximum, int percent) {
        if (systemMaximum <= 0 || percent <= 0) {
            return 0;
        }
        long scaled = (long) systemMaximum * Math.min(percent, 100);
        return (int) Math.min(systemMaximum, Math.max(1L, (scaled + 99L) / 100L));
    }
}
