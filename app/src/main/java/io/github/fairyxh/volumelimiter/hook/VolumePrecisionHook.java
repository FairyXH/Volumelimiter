package io.github.fairyxh.volumelimiter.hook;

import android.media.AudioManager;

import java.lang.reflect.Method;

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
    /** 覆盖所有可能入口（含 OEM/Android 12+ 变体）：名称前缀匹配，不依赖精确签名。 */
    private static final String[] ADJUST_PREFIXES = {
            "adjustStreamVolume", "adjustSuggestedStreamVolume"
    };

    private final XposedModule module;
    private final ConfigManager configManager;
    private final VolumeIndexMapper mapper;
    private ClassLoader classLoader;
    private Method getStreamVolume;
    private Method getStreamMaxVolume;
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
        getStreamMaxVolume = findIntMethod(audioServiceClass, "getStreamMaxVolume", 1);
        getActiveStreamType = findIntMethod(audioServiceClass, "getActiveStreamType", 1);
        setStreamVolume = findSetStreamVolume(audioServiceClass);
        int installed = 0;
        for (Class<?> current = audioServiceClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                if (nameStartsWithAny(method.getName(), ADJUST_PREFIXES)) {
                    installed += hookAdjustStreamVolume(method);
                }
            }
        }
        if (installed > 0) {
            LogUtils.info("Installed " + installed + " volume-precision hooks");
        }
        return installed;
    }

    private static boolean nameStartsWithAny(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
                            LogUtils.info("PrecisionHook: disabled precision=0 method="
                                    + method.getName() + " -> passthrough");
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
                            LogUtils.info("PrecisionHook: stream unresolved method="
                                    + method.getName() + " -> passthrough");
                            return chain.proceed();
                        }
                        ensureNativeMax(chain.getThisObject(), stream);
                        int step = mapper.adjustStep(stream, config.adjustPrecision);
                        if (step < 0) {
                            LogUtils.info("PrecisionHook: nativeMax unknown stream=" + stream
                                    + " -> passthrough");
                            return chain.proceed();
                        }
                        int current = readStreamVolume(chain.getThisObject(), stream);
                        if (current < 0) {
                            return chain.proceed();
                        }
                        int target = direction > 0
                                ? current + step : current - step;
                        int nativeLimit = mapper.nativeLimit(stream, config.masterPercent());
                        // 上限只作用于提高方向；降低方向永不截断（只允许降到 >= 0）
                        if (direction > 0 && nativeLimit >= 0) {
                            target = Math.min(target, nativeLimit);
                        }
                        target = Math.max(0, target);
                        int flags = args.length > 2 && args[2] instanceof Integer
                                ? (Integer) args[2] : 0;
                        // 保证 SystemUI 音量条（VolumeDialog）显示：原生 adjust 链会把
                        // FLAG_SHOW_UI 带给 sendVolumeUpdate，改走 setStreamVolume 后
                        // 必须显式补上，否则屏幕浮条不弹出（控制中心滑块仍会变化）。
                        flags |= AudioManager.FLAG_SHOW_UI;
                        // 每次按音量键都输出，用于确认 Hook 命中与实际入口
                        LogUtils.info("PrecisionHook: method=" + method.getName()
                                + " stream=" + stream
                                + " oldIndex=" + current
                                + " direction=" + direction
                                + " step=" + step
                                + " newIndex=" + target
                                + " limit=" + nativeLimit
                                + " flags=" + flags);
                        if (target == current) {
                            return null;
                        }
                        callSetStreamVolume(chain.getThisObject(), stream, target, flags);
                        // Oplus/ColorOS 的音量条只响应原生 adjust 链的 sendVolumeUpdate；
                        // 改走 setStreamVolume 后主动反射通知一次，让浮条弹出。
                        notifyVolumeUi(chain.getThisObject(), stream, current, target, flags);
                        return null;
                    });
            LogUtils.info("PrecisionHook installed: " + method);
            return 1;
        } catch (Throwable error) {
            LogUtils.error("VolumePrecision unable to hook " + method.getName(), error);
            return 0;
        }
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

    /**
     * 反射调用 AudioService.sendVolumeUpdate(int,int,int,int) 通知 SystemUI
     * 显示音量条。Oplus/ColorOS 的 VolumeDialog 只响应原生 adjust 链发出的
     * 通知，改走 setStreamVolume 后需要主动补发。找不到方法时 fail-open。
     */
    private void notifyVolumeUi(Object audioService, int stream, int oldIndex,
                                int newIndex, int flags) {
        if (audioService == null) {
            return;
        }
        try {
            Method sendVolumeUpdate = null;
            for (Class<?> current = audioService.getClass(); current != null && sendVolumeUpdate == null;
                 current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    Class<?>[] types = method.getParameterTypes();
                    if (method.getName().equals("sendVolumeUpdate")
                            && types.length == 4
                            && types[0] == int.class && types[1] == int.class
                            && types[2] == int.class && types[3] == int.class) {
                        method.setAccessible(true);
                        sendVolumeUpdate = method;
                        break;
                    }
                }
            }
            if (sendVolumeUpdate == null) {
                LogUtils.info("PrecisionHook: sendVolumeUpdate not found, volume UI skipped");
                return;
            }
            sendVolumeUpdate.invoke(audioService, stream, oldIndex, newIndex, flags);
            LogUtils.info("PrecisionHook: volume UI notified stream=" + stream
                    + " old=" + oldIndex + " new=" + newIndex + " flags=" + flags);
        } catch (Throwable error) {
            LogUtils.info("PrecisionHook: volume UI notify failed: " + error);
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

    /**
     * 反射读取真实 native 最大值并缓存：优先 AudioService.getStreamMaxVolume，
     * AudioSystem.getMaxVolumeIndex 仅作兜底（不 hook native 方法）。
     */
    private void ensureNativeMax(Object audioService, int stream) {
        VolumeIndexMapper.readNativeMax(classLoader, audioService, getStreamMaxVolume,
                stream, mapper);
    }

    private static int findDirectionPosition(Method method, Object[] args) {
        // adjustStreamVolume* 的 direction 在第 2 个参数；adjustSuggestedStreamVolume* 在第 1 个
        int position = method.getName().startsWith("adjustStreamVolume") ? 1 : 0;
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
