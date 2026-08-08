package io.github.fairyxh.volumelimiter.hook;

import android.media.AudioManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.utils.LogUtils;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 虚拟音量阶数：AudioService 内部仍使用真实 native 索引，仅在边界做
 * “虚拟空间（0..N-1，UI/音量键）” 与 “真实空间（0..nativeLimit，AudioFlinger）”
 * 之间的转换，由 {@link VolumeIndexMapper} 负责。
 *
 * <p>链路：getStreamVolume 返回虚拟索引；setStreamVolume 把虚拟索引转回真实索引；
 * 音量键 adjust 按“虚拟 +1” 计算目标后再转真实并校正；VolumeStreamState.getValidIndex
 * 仍保留（限制在虚拟范围，设置真实值时先经 mapper 再转回），但不再破坏真实索引。
 * 不修改 mIndexMax（final 字段），不改 native 上限，不改 Audio HAL。
 */
public final class VolumeStepsHook {
    /** AudioManager.STREAM_ASSISTANT 是 @hide 常量，公共 SDK 不可直接引用。 */
    private static final int STREAM_ASSISTANT = 11;
    private static final Set<String> SET_METHODS = Set.of(
            "setStreamVolume", "setStreamVolumeWithAttribution");
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

    public VolumeStepsHook(XposedModule module, ConfigManager configManager,
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
        installed += hookAudioSystem(classLoader);
        installed += hookAudioService(audioServiceClass);
        installed += hookVolumeStreamState(audioServiceClass);
        installed += hookSetAndAdjust(audioServiceClass);
        if (installed > 0) {
            ConfigManager.Snapshot config = configManager.snapshot();
            LogUtils.info("VolumeSteps mapper initialized: nativeMaxIndex=<pending> virtualSteps="
                    + config.volumeSteps + " nativeLimit=<pending>");
            LogUtils.info("Installed " + installed + " volume-steps hooks");
        }
        return installed;
    }

    private int hookAudioSystem(ClassLoader classLoader) {
        Class<?> type;
        try {
            type = Class.forName("android.media.AudioSystem", false, classLoader);
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps: AudioSystem unavailable: " + error);
            return 0;
        }
        int installed = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    && method.getName().equals("getMaxVolumeIndex")
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class
                    && method.getReturnType() == int.class) {
                installed += hookNativeMaxCache(method);
            }
        }
        return installed;
    }

    /**
     * AudioSystem.getMaxVolumeIndex 是 native 范围来源。hook 只缓存真实最大值，
     * 返回值保持真实值 —— 若改成虚拟值会破坏 VolumeStreamState 内部初始化。
     */
    private int hookNativeMaxCache(Method method) {
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        int stream = args.length > 0 && args[0] instanceof Integer
                                ? (Integer) args[0] : -1;
                        Object original = chain.proceed();
                        if (stream >= 0 && original instanceof Integer value) {
                            mapper.rememberNativeMax(stream, value);
                        }
                        return original;
                    });
            LogUtils.debug("VolumeSteps hooked AudioSystem.getMaxVolumeIndex (native max cache)");
            return 1;
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to hook AudioSystem.getMaxVolumeIndex: " + error);
            return 0;
        }
    }

    private int hookAudioService(Class<?> audioServiceClass) {
        int installed = 0;
        for (Class<?> current = audioServiceClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != int.class
                        || method.getReturnType() != int.class) {
                    continue;
                }
                if (method.getName().equals("getStreamMaxVolume")) {
                    installed += hookReplaceMax(method, "AudioService.getStreamMaxVolume");
                } else if (method.getName().equals("getStreamVolume")) {
                    installed += hookMapStreamVolume(method);
                }
            }
        }
        return installed;
    }

    private int hookVolumeStreamState(Class<?> audioServiceClass) {
        Class<?> stateClass = findVolumeStreamState(audioServiceClass);
        if (stateClass == null) {
            LogUtils.debug("VolumeSteps: VolumeStreamState not found");
            return 0;
        }
        int installed = 0;
        for (Method method : stateClass.getDeclaredMethods()) {
            if (method.getReturnType() != int.class) {
                continue;
            }
            if (method.getName().equals("getMaxIndex")
                    && (method.getParameterCount() == 0
                    || (method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class))) {
                installed += hookReplaceMax(method, "VolumeStreamState.getMaxIndex");
            } else if (method.getName().equals("getValidIndex")
                    && method.getParameterCount() >= 1
                    && method.getParameterTypes()[0] == int.class) {
                installed += hookClampValidIndex(method);
            }
        }
        return installed;
    }

    private int hookSetAndAdjust(Class<?> audioServiceClass) {
        int installed = 0;
        for (Class<?> current = audioServiceClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                if (SET_METHODS.contains(method.getName())) {
                    installed += hookSetStreamVolume(method);
                } else if (ADJUST_METHODS.contains(method.getName())) {
                    installed += hookAdjustStreamVolume(method);
                }
            }
        }
        return installed;
    }

    private static Class<?> findVolumeStreamState(Class<?> audioServiceClass) {
        for (Class<?> current = audioServiceClass; current != null; current = current.getSuperclass()) {
            for (Class<?> nested : current.getDeclaredClasses()) {
                if (nested.getSimpleName().equals("VolumeStreamState")) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * 最大索引 getter 只影响 UI/API 显示：返回用户空间的 customMaxIndex。
     * 真实 native 最大值通过 {@link #ensureNativeMax(int)} 反射读取并缓存。
     */
    private int hookReplaceMax(Method method, String label) {
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ConfigManager.Snapshot config = configManager.snapshot();
                        if (config.volumeSteps < PreferenceStorage.MIN_VOLUME_STEPS) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        int stream = args.length > 0 && args[0] instanceof Integer
                                ? (Integer) args[0] : -1;
                        if (stream >= 0) {
                            ensureNativeMax(stream);
                            logMapperInitialized(stream, config);
                        }
                        chain.proceed();
                        return customMaxIndex(config.volumeSteps);
                    });
            LogUtils.debug("VolumeSteps hooked " + label);
            return 1;
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to hook " + label + ": " + error);
            return 0;
        }
    }

    /** getStreamVolume：真实 native 索引 → 虚拟索引（UI 显示空间）。 */
    private int hookMapStreamVolume(Method method) {
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ConfigManager.Snapshot config = configManager.snapshot();
                        if (config.volumeSteps < PreferenceStorage.MIN_VOLUME_STEPS) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        int stream = args.length > 0 && args[0] instanceof Integer
                                ? (Integer) args[0] : -1;
                        Object original = chain.proceed();
                        if (!(original instanceof Integer nativeIndex) || stream < 0) {
                            return original;
                        }
                        ensureNativeMax(stream);
                        logMapperInitialized(stream, config);
                        int virtualIndex = mapper.nativeToVirtual(stream, nativeIndex,
                                config.volumeSteps, config.masterPercent());
                        LogUtils.debug("VolumeSteps native -> virtual stream=" + stream
                                + " native=" + nativeIndex + " virtual=" + virtualIndex);
                        return virtualIndex;
                    });
            LogUtils.debug("VolumeSteps hooked AudioService.getStreamVolume");
            return 1;
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to hook AudioService.getStreamVolume: " + error);
            return 0;
        }
    }

    /**
     * setStreamVolume：调用方传入虚拟索引，转换为真实 native 索引后再进入原方法。
     * 优先级 LOWEST，位于 VolumeAdjustHook 的限制钳制之后。
     */
    private int hookSetStreamVolume(Method method) {
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ConfigManager.Snapshot config = configManager.snapshot();
                        if (config.volumeSteps < PreferenceStorage.MIN_VOLUME_STEPS) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        int streamPosition = findStreamPosition(method, args);
                        int indexPosition = findFollowingIntPosition(method, streamPosition);
                        if (streamPosition < 0 || indexPosition < 0
                                || !(args[indexPosition] instanceof Integer)) {
                            return chain.proceed();
                        }
                        int stream = (Integer) args[streamPosition];
                        int virtualIndex = (Integer) args[indexPosition];
                        ensureNativeMax(stream);
                        logMapperInitialized(stream, config);
                        int nativeIndex = mapper.virtualToNative(stream, virtualIndex,
                                config.volumeSteps, config.masterPercent());
                        LogUtils.debug("VolumeSteps virtual -> native stream=" + stream
                                + " virtual=" + virtualIndex + " native=" + nativeIndex);
                        Object[] changed = args.clone();
                        changed[indexPosition] = nativeIndex;
                        return chain.proceed(changed);
                    });
            LogUtils.debug("VolumeSteps hooked " + method.getName());
            return 1;
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to hook " + method.getName() + ": " + error);
            return 0;
        }
    }

    /**
     * 音量键：不允许按 native 步进（每次真实索引 +1）。改为按虚拟空间计算
     * “虚拟 +1 / -1” 的目标索引，转真实后校正回 AudioService。
     * 优先级 LOWEST：VolumeAdjustHook 已在 HIGHEST 完成上限 block。
     */
    private int hookAdjustStreamVolume(Method method) {
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ConfigManager.Snapshot config = configManager.snapshot();
                        if (config.volumeSteps < PreferenceStorage.MIN_VOLUME_STEPS) {
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
                        int flags = args.length > 2 && args[2] instanceof Integer
                                ? (Integer) args[2] : 0;
                        int oldDisplay = readDisplayVolume(chain.getThisObject(), stream);
                        if (oldDisplay < 0) {
                            return chain.proceed();
                        }
                        int oldReal = mapper.virtualToNative(stream, oldDisplay,
                                config.volumeSteps, config.masterPercent());
                        int targetDisplay = clampValidIndex(oldDisplay
                                + (direction > 0 ? 1 : -1), config.volumeSteps);
                        int targetReal = mapper.virtualToNative(stream, targetDisplay,
                                config.volumeSteps, config.masterPercent());
                        int nativeLimit = mapper.nativeLimit(stream, config.volumeSteps,
                                config.masterPercent());
                        // 真实档位比用户阶数粗时，纯映射可能原地踏步：强制真实索引至少移动 1 档。
                        if (direction > 0 && targetReal <= oldReal && nativeLimit > oldReal) {
                            targetReal = Math.min(nativeLimit, oldReal + 1);
                        } else if (direction < 0 && targetReal >= oldReal) {
                            targetReal = Math.max(0, oldReal - 1);
                        }
                        int newDisplay = mapper.nativeToVirtual(stream, targetReal,
                                config.volumeSteps, config.masterPercent());
                        LogUtils.debug("VolumeSteps adjust stream=" + stream
                                + " virtual=" + oldDisplay + " -> " + newDisplay
                                + " native=" + oldReal + " -> " + targetReal);
                        chain.proceed();
                        callSetStreamVolume(chain.getThisObject(), stream, newDisplay, flags);
                        return null;
                    });
            LogUtils.debug("VolumeSteps hooked " + method.getName());
            return 1;
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to hook " + method.getName() + ": " + error);
            return 0;
        }
    }

    /**
     * getValidIndex：仍限制在虚拟范围，但“设置真实值时先经过 mapper”：
     * 真实 → 虚拟 → clamp 到 0..N-1 → 真实。数学上等价于 clamp 到
     * 0..nativeLimit，因此不会破坏真实索引，native 仍能收到完整映射后的值。
     */
    private int hookClampValidIndex(Method method) {
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        ConfigManager.Snapshot config = configManager.snapshot();
                        if (config.volumeSteps < PreferenceStorage.MIN_VOLUME_STEPS) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        if (args.length == 0 || !(args[0] instanceof Integer index)) {
                            return chain.proceed();
                        }
                        int stream = readStreamType(chain.getThisObject());
                        if (stream < 0) {
                            return chain.proceed();
                        }
                        ensureNativeMax(stream);
                        int virtual = mapper.nativeToVirtual(stream, index,
                                config.volumeSteps, config.masterPercent());
                        int clamped = clampValidIndex(virtual, config.volumeSteps);
                        return mapper.virtualToNative(stream, clamped,
                                config.volumeSteps, config.masterPercent());
                    });
            LogUtils.debug("VolumeSteps hooked VolumeStreamState.getValidIndex");
            return 1;
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to hook VolumeStreamState.getValidIndex: " + error);
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

    private int readDisplayVolume(Object service, int stream) {
        if (getStreamVolume == null) {
            return -1;
        }
        try {
            Object value = getStreamVolume.invoke(service, stream);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to read stream volume: " + error);
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
            LogUtils.debug("VolumeSteps unable to set adjusted volume: " + error);
        }
    }

    /** 反射读取 AudioSystem.getMaxVolumeIndex 真实值；native 方法本身不可 hook 时作为兜底。 */
    private void ensureNativeMax(int stream) {
        if (mapper.nativeMaxIndex(stream) > 0) {
            return;
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
                    Object result = method.invoke(null, stream);
                    if (result instanceof Integer value) {
                        mapper.rememberNativeMax(stream, value);
                    }
                    return;
                }
            }
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to read native max stream=" + stream + ": " + error);
        }
    }

    private void logMapperInitialized(int stream, ConfigManager.Snapshot config) {
        int realMax = mapper.nativeMaxIndex(stream);
        if (realMax > 0 && loggedStreams.add(stream)) {
            int limit = mapper.nativeLimit(stream, config.volumeSteps, config.masterPercent());
            LogUtils.info("VolumeSteps mapper initialized: nativeMaxIndex=" + realMax
                    + " virtualSteps=" + config.volumeSteps + " nativeLimit=" + limit);
        }
    }

    /** 用户阶数 N 对应的显示最大索引（0 基）；未配置时返回 -1。 */
    static int customMaxIndex(int steps) {
        if (steps < PreferenceStorage.MIN_VOLUME_STEPS) {
            return -1;
        }
        return Math.max(1, Math.min(steps, PreferenceStorage.MAX_VOLUME_STEPS) - 1);
    }

    /** 虚拟空间钳制到 0..N-1；未配置阶数时原样返回。 */
    static int clampValidIndex(int index, int steps) {
        if (steps < PreferenceStorage.MIN_VOLUME_STEPS) {
            return index;
        }
        return Math.max(0, Math.min(index, customMaxIndex(steps)));
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

    private static int findStreamPosition(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length && i < args.length; i++) {
            if (types[i] == int.class && args[i] instanceof Integer value && isKnownStream(value)) {
                return i;
            }
        }
        return -1;
    }

    private static int findFollowingIntPosition(Method method, int start) {
        if (start < 0) {
            return -1;
        }
        Class<?>[] types = method.getParameterTypes();
        for (int i = start + 1; i < types.length; i++) {
            if (types[i] == int.class) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isKnownStream(int streamType) {
        return streamType >= AudioManager.STREAM_VOICE_CALL
                && streamType <= STREAM_ASSISTANT;
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

    private static int readStreamType(Object state) {
        try {
            for (Class<?> current = state.getClass(); current != null;
                 current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField("mStreamType");
                    field.setAccessible(true);
                    Object value = field.get(state);
                    if (value instanceof Integer) {
                        return (Integer) value;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Continue up the hierarchy.
                }
            }
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to read VolumeStreamState stream type: " + error);
        }
        return -1;
    }
}
