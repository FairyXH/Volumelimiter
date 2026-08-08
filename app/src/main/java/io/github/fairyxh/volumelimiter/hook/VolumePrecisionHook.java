package io.github.fairyxh.volumelimiter.hook;

import android.media.AudioManager;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.utils.LogUtils;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 调节精度：只影响音量键一次调整多少（adjustStreamVolume 的步进）。
 *
 * <p>真实音量索引全程保持不变：getStreamVolume / getStreamMaxVolume /
 * VolumeStreamState 最大值 / AudioSystem 最大值都不做任何 hook，UI 永远显示
 * 系统原始 x/nativeMax。精度激活时，音量键一次按 stepSize = round(nativeMax /
 * precision) 档（例如 nativeMax=160、precision=50 → 每次约 3 档）；关闭时
 * 直通系统原生每次 1 档。
 *
 * <p>最大音量限制由 VolumeAdjustHook 在 HIGHEST 优先级按 nativeLimitIndex
 * 独立钳制，与本类无关。本类只 hook adjust* 方法（LOWEST，位于限制之后）。
 */
public final class VolumePrecisionHook {
    private static final Set<String> ADJUST_METHODS = Set.of(
            "adjustStreamVolume", "adjustSuggestedStreamVolume",
            "adjustSuggestedStreamVolumeForUid");

    private final XposedModule module;
    private final ConfigManager configManager;
    private final VolumeIndexMapper mapper;
    private final Set<Integer> loggedStreams = new HashSet<>();
    private ClassLoader classLoader;
    private Method getStreamVolume;
    private Method getActiveStreamType;
    private Method setStreamVolume;

    public VolumePrecisionHook(XposedModule module, ConfigManager configManager,
                               VolumeIndexMapper mapper) {
        this.module = module;
        this.configManager = configManager;
        this.mapper = mapper;
    }

    public int install(ClassLoader classLoader, Class<?> audioServiceClass) {
        this.classLoader = classLoader;
        getStreamVolume = findIntMethod(audioServiceClass, "getStreamVolume", 1);
        getActiveStreamType = findIntMethod(audioServiceClass, "getActiveStreamType", 1);
        setStreamVolume = findSetStreamVolume(audioServiceClass);
        int installed = 0;
        for (Class<?> current = audioServiceClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                if (ADJUST_METHODS.contains(method.getName())) {
                    installed += hookAdjustStreamVolume(method);
                }
            }
        }
        if (installed > 0) {
            LogUtils.info("Installed " + installed + " volume-precision hooks");
        }
        return installed;
    }

    /**
     * 音量键：按真实索引计算“当前 ± stepSize”的目标，钳制到 0..nativeLimit 后
     * 通过 setStreamVolume 写回（限制 hook 会再次确认）。优先级 LOWEST：
     * VolumeAdjustHook 已在 HIGHEST 完成上限 block。
     */
    private int hookAdjustStreamVolume(Method method) {
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ConfigManager.Snapshot config = configManager.snapshot();
                        if (config.adjustPrecision < PreferenceStorage.MIN_ADJUST_PRECISION) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        int directionPosition = findDirectionPosition(method, args);
                        if (directionPosition < 0
                                || !(args[directionPosition] instanceof Integer)) {
                            return chain.proceed();
                        }
                        int direction = (Integer) args[directionPosition];
                        if (direction != AudioManager.ADJUST_RAISE
                                && direction != AudioManager.ADJUST_LOWER) {
                            return chain.proceed();
                        }
                        int stream = resolveAdjustmentStream(chain.getThisObject(),
                                method, args, directionPosition);
                        if (stream < 0) {
                            return chain.proceed();
                        }
                        ensureNativeMax(stream);
                        int step = mapper.adjustStep(stream, config.adjustPrecision);
                        if (step < 0) {
                            return chain.proceed();
                        }
                        int current = readStreamVolume(chain.getThisObject(), stream);
                        if (current < 0) {
                            return chain.proceed();
                        }
                        int target = direction > 0
                                ? current + step : current - step;
                        int nativeLimit = mapper.nativeLimit(stream, config.masterPercent());
                        if (nativeLimit >= 0) {
                            target = Math.min(target, nativeLimit);
                        }
                        target = Math.max(0, target);
                        if (target == current) {
                            return null;
                        }
                        int flags = args.length > 2 && args[2] instanceof Integer
                                ? (Integer) args[2] : 0;
                        logAdjust(stream, config, current, target, step, nativeLimit);
                        callSetStreamVolume(chain.getThisObject(), stream, target, flags);
                        return null;
                    });
            LogUtils.debug("VolumePrecision hooked " + method.getName());
            return 1;
        } catch (Throwable error) {
            LogUtils.debug("VolumePrecision unable to hook " + method.getName() + ": " + error);
            return 0;
        }
    }

    private void logAdjust(int stream, ConfigManager.Snapshot config, int current,
                           int target, int step, int nativeLimit) {
        if (loggedStreams.add(stream)) {
            LogUtils.info("VolumePrecision mapper initialized: nativeMaxIndex="
                    + mapper.nativeMaxIndex(stream) + " precision=" + config.adjustPrecision
                    + " step=" + step + " nativeLimit=" + nativeLimit);
        }
        LogUtils.debug("VolumePrecision adjust stream=" + stream
                + " native=" + current + " -> " + target
                + " step=" + step + " limit=" + nativeLimit);
    }

    private int resolveAdjustmentStream(Object audioService, Method method, Object[] args,
                                        int directionPosition) {
        return VolumeAdjustHook.selectAdjustmentStream(method.getName(), args,
                directionPosition, suggested -> {
                    if (getActiveStreamType == null) {
                        return AudioManager.STREAM_MUSIC;
                    }
                    try {
                        Object value = getActiveStreamType.invoke(audioService, suggested);
                        return value instanceof Integer ? (Integer) value
                                : AudioManager.STREAM_MUSIC;
                    } catch (Throwable error) {
                        return AudioManager.STREAM_MUSIC;
                    }
                });
    }

    /** 读取真实索引：getStreamVolume 未被 hook，直接返回系统原值。 */
    private int readStreamVolume(Object service, int stream) {
        if (getStreamVolume == null) {
            return -1;
        }
        try {
            Object value = getStreamVolume.invoke(service, stream);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable error) {
            LogUtils.debug("VolumePrecision unable to read stream volume: " + error);
            return -1;
        }
    }

    private void callSetStreamVolume(Object service, int stream, int index, int flags) {
        if (setStreamVolume == null) {
            return;
        }
        Class<?>[] types = setStreamVolume.getParameterTypes();
        Object[] args = new Object[types.length];
        int integerIndex = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) {
                args[i] = integerIndex == 0 ? stream
                        : integerIndex == 1 ? index
                        : integerIndex == 2 ? flags : 0;
                integerIndex++;
            } else if (types[i] == boolean.class) {
                args[i] = false;
            } else if (types[i] == String.class) {
                args[i] = "io.github.fairyxh.volumelimiter";
            } else {
                args[i] = null;
            }
        }
        try {
            setStreamVolume.invoke(service, args);
        } catch (Throwable error) {
            LogUtils.debug("VolumePrecision unable to set adjusted volume: " + error);
        }
    }

    /** 反射读取 AudioSystem.getMaxVolumeIndex 真实值并缓存（不 hook native 方法）。 */
    private void ensureNativeMax(int stream) {
        VolumeIndexMapper.readNativeMax(classLoader, stream, mapper);
    }

    private static int findDirectionPosition(Method method, Object[] args) {
        int position = method.getName().equals("adjustStreamVolume") ? 1 : 0;
        Class<?>[] types = method.getParameterTypes();
        if (position >= types.length || position >= args.length || types[position] != int.class
                || !(args[position] instanceof Integer)) {
            return -1;
        }
        return position;
    }

    private static Method findIntMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount
                        && method.getParameterTypes()[0] == int.class) {
                    try {
                        method.setAccessible(true);
                        return method;
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static Method findSetStreamVolume(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if ((method.getName().equals("setStreamVolume")
                        || method.getName().equals("setStreamVolumeWithAttribution"))
                        && types.length >= 2 && types[0] == int.class && types[1] == int.class) {
                    try {
                        method.setAccessible(true);
                        return method;
                    } catch (Throwable ignored) {
                        // Try the next overload.
                    }
                }
            }
        }
        return null;
    }
}
