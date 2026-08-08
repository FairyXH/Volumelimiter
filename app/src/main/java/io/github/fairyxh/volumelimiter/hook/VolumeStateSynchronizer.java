package io.github.fairyxh.volumelimiter.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.utils.LogUtils;

/**
 * 配置变化后的音量重新同步：masterPercent / volumeSteps 改变时，AudioService
 * 内部仍保存旧的 native 索引（例如 50% 限制时的 75），如果不重算，提高限制后
 * 实际声音不会恢复。本组件在配置 reload 后按“旧限制中的真实音量比例”重算
 * 每个音频流的真实索引并写回，保证 50% → 100% 时音量立即恢复。
 *
 * <p>实现只依赖反射与现有 Hook 链：真实索引优先从 AudioService.mStreamStates
 * 读取（绕过 Hook），写回时调用 setStreamVolume（虚拟索引，由 VolumeStepsHook
 * 在 LOWEST 统一转真实；VolumeAdjustHook 在 HIGHEST 按新配置继续钳制）。
 */
public final class VolumeStateSynchronizer {
    private static final int[] STREAMS = {0, 1, 2, 3, 4, 5, 6, 11};

    private final ConfigManager configManager;
    private final VolumeIndexMapper mapper;
    private final Method getStreamVolume;
    private final Method setStreamVolume;
    private volatile Object audioServiceInstance;
    private volatile int lastSteps;
    private volatile int lastPercent;

    public VolumeStateSynchronizer(ConfigManager configManager, VolumeIndexMapper mapper,
                                   Class<?> audioServiceClass) {
        this.configManager = configManager;
        this.mapper = mapper;
        getStreamVolume = findIntMethod(audioServiceClass, "getStreamVolume", 1);
        setStreamVolume = findSetStreamVolume(audioServiceClass);
        ConfigManager.Snapshot initial = configManager.snapshot();
        lastSteps = initial == null ? 0 : initial.volumeSteps;
        lastPercent = initial == null ? 100 : initial.masterPercent();
    }

    public void rememberAudioService(Object audioService) {
        if (audioService != null) {
            audioServiceInstance = audioService;
        }
    }

    /** 配置 reload 后调用：只有 volumeSteps / masterPercent 变化时才重算并写回。 */
    public synchronized void syncIfConfigChanged() {
        ConfigManager.Snapshot config = configManager.snapshot();
        Object service = audioServiceInstance;
        if (!config.enabled || service == null) {
            return;
        }
        if (config.volumeSteps == lastSteps && config.masterPercent() == lastPercent) {
            return;
        }
        int oldSteps = lastSteps;
        int oldPercent = lastPercent;
        lastSteps = config.volumeSteps;
        lastPercent = config.masterPercent();
        for (int streamType : STREAMS) {
            try {
                int realIndex = readRealIndex(service, streamType);
                if (realIndex < 0) {
                    continue;
                }
                int oldLimit = mapper.nativeLimit(streamType, oldSteps, oldPercent);
                int newLimit = mapper.nativeLimit(streamType, config.volumeSteps,
                        config.masterPercent());
                int newReal;
                if (config.volumeSteps >= PreferenceStorage.MIN_VOLUME_STEPS
                        && oldLimit >= 0 && newLimit >= 0) {
                    // 阶数激活：保持用户当前音量在旧限制中的比例，映射到新限制。
                    newReal = VolumeIndexMapper.rescaleForConfig(realIndex, oldLimit, newLimit);
                } else {
                    // 阶数关闭：恢复原始 Android 音量逻辑，保持真实索引不变
                    // （VolumeAdjustHook 仍会按新 masterPercent 钳制）。
                    newReal = realIndex;
                }
                int target;
                if (config.volumeSteps >= PreferenceStorage.MIN_VOLUME_STEPS) {
                    target = mapper.nativeToVirtual(streamType, newReal,
                            config.volumeSteps, config.masterPercent());
                } else {
                    target = newReal;
                }
                logSync(streamType, realIndex, oldSteps, oldPercent, config, newReal, newLimit);
                callSetStreamVolume(service, streamType, target);
            } catch (Throwable error) {
                LogUtils.debug("VolumeSync failed stream=" + streamType + ": " + error);
            }
        }
    }

    private void logSync(int stream, int oldNative, int oldSteps, int oldPercent,
                         ConfigManager.Snapshot config, int newNative, int newLimit) {
        int oldVirtual = oldSteps >= PreferenceStorage.MIN_VOLUME_STEPS
                ? mapper.nativeToVirtual(stream, oldNative, oldSteps, oldPercent)
                : oldNative;
        LogUtils.info("VolumeSync: stream=" + stream
                + " oldNative=" + oldNative + " oldVirtual=" + oldVirtual
                + " oldSteps=" + oldSteps + " oldPercent=" + oldPercent
                + " newSteps=" + config.volumeSteps + " newPercent=" + config.masterPercent()
                + " newNativeLimit=" + newLimit + " newNative=" + newNative);
    }

    /** 读取真实 native 索引：优先 VolumeStreamState.getIndex()（绕过显示 Hook）。 */
    private int readRealIndex(Object service, int streamType) {
        try {
            Field statesField = findField(service.getClass(), "mStreamStates");
            if (statesField != null) {
                statesField.setAccessible(true);
                Object states = statesField.get(service);
                if (states instanceof Map<?, ?>) {
                    Object state = ((Map<?, ?>) states).get(streamType);
                    if (state != null) {
                        for (Method method : state.getClass().getDeclaredMethods()) {
                            if (method.getName().equals("getIndex")
                                    && method.getParameterCount() == 0
                                    && method.getReturnType() == int.class) {
                                method.setAccessible(true);
                                Object value = method.invoke(state);
                                if (value instanceof Integer) {
                                    return (Integer) value;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable error) {
            LogUtils.debug("VolumeSync unable to read real index stream=" + streamType
                    + ": " + error);
        }
        return readVirtualFallback(service, streamType);
    }

    /** 兜底：读显示索引后按当前配置互逆，可能带舍入误差，但保证不阻塞同步。 */
    private int readVirtualFallback(Object service, int streamType) {
        ConfigManager.Snapshot config = configManager.snapshot();
        if (getStreamVolume == null) {
            return -1;
        }
        try {
            Object value = getStreamVolume.invoke(service, streamType);
            if (value instanceof Integer virtual) {
                if (config.volumeSteps >= PreferenceStorage.MIN_VOLUME_STEPS) {
                    return mapper.virtualToNative(streamType, virtual,
                            config.volumeSteps, config.masterPercent());
                }
                return virtual;
            }
        } catch (Throwable error) {
            LogUtils.debug("VolumeSync fallback read failed stream=" + streamType + ": " + error);
        }
        return -1;
    }

    private void callSetStreamVolume(Object service, int streamType, int index) {
        if (setStreamVolume == null) {
            return;
        }
        Class<?>[] types = setStreamVolume.getParameterTypes();
        Object[] args = new Object[types.length];
        int integerIndex = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) {
                args[i] = integerIndex == 0 ? streamType
                        : integerIndex == 1 ? index : 0;
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
            LogUtils.debug("VolumeSync unable to set stream=" + streamType + ": " + error);
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // Continue up the hierarchy.
            }
        }
        return null;
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
