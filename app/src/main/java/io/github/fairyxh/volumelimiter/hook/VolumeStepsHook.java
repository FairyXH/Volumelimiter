package io.github.fairyxh.volumelimiter.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.utils.LogUtils;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Replaces the system volume step count with a user-specified value.
 *
 * <p>音量阶数 = 用户输入 N，对应的最大音量索引为 N-1（至少 1）。每个 Hook 独立安装并
 * fail-open：未配置阶数时原样放行，不影响现有的音量限制 Hook。
 */
public final class  VolumeStepsHook {
    private final XposedModule module;
    private final ConfigManager configManager;

    public VolumeStepsHook(XposedModule module, ConfigManager configManager) {
        this.module = module;
        this.configManager = configManager;
    }

    public int install(ClassLoader classLoader, Class<?> audioServiceClass) {
        int installed = 0;
        installed += hookAudioSystem(classLoader);
        installed += hookAudioService(audioServiceClass);
        installed += hookVolumeStreamState(audioServiceClass);
        installed += hookVolumeStreamStateConstructors(audioServiceClass);
        if (installed > 0) {
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
                installed += hookReplaceMax(method, "AudioSystem.getMaxVolumeIndex");
            }
        }
        return installed;
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
                    installed += hookClampStreamVolume(method);
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
            if (!method.getName().equals("getMaxIndex")
                    || method.getReturnType() != int.class) {
                continue;
            }
            if (method.getParameterCount() == 0
                    || (method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class)) {
                installed += hookReplaceMax(method, "VolumeStreamState.getMaxIndex");
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
     * Syncs the internal {@code mIndexMax} so the constructor result matches the custom step
     * count. {@code AudioSystem.getMaxVolumeIndex} is native and cannot be hooked reliably by
     * the YAHFA backend, which used to leave {@code mIndexMax} at the system default while the
     * public {@code getMaxIndex()}/{@code getStreamMaxVolume()} hooks reported the custom value.
     * That mismatch made volume keys stop at the real system maximum after setting a custom
     * step count.
     */
    private int hookVolumeStreamStateConstructors(Class<?> audioServiceClass) {
        Class<?> stateClass = findVolumeStreamState(audioServiceClass);
        if (stateClass == null) {
            return 0;
        }
        int installed = 0;
        for (Constructor<?> constructor : stateClass.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                module.hook(constructor)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            ConfigManager.Snapshot config = configManager.snapshot();
                            if (config.volumeSteps >= PreferenceStorage.MIN_VOLUME_STEPS) {
                                syncMaxIndex(chain.getThisObject(), stateClass,
                                        customMaxIndex(config.volumeSteps) + 1);
                            }
                            return result;
                        });
                installed++;
            } catch (Throwable error) {
                LogUtils.debug("VolumeSteps unable to hook VolumeStreamState constructor "
                        + constructor + ": " + error);
            }
        }
        if (installed > 0) {
            LogUtils.info("VolumeSteps synced " + installed + " VolumeStreamState constructors");
        }
        return installed;
    }

    private static void syncMaxIndex(Object streamState, Class<?> stateClass, int maxIndex) {
        try {
            Field field = stateClass.getDeclaredField("mIndexMax");
            field.setAccessible(true);
            field.setInt(streamState, maxIndex);
            LogUtils.debug("VolumeSteps set mIndexMax=" + maxIndex);
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to sync mIndexMax: " + error);
        }
    }

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

    private int hookClampStreamVolume(Method method) {
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
                        Object original = chain.proceed();
                        if (original instanceof Integer value) {
                            return Math.min(value, customMaxIndex(config.volumeSteps));
                        }
                        return original;
                    });
            LogUtils.debug("VolumeSteps hooked AudioService.getStreamVolume");
            return 1;
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to hook AudioService.getStreamVolume: " + error);
            return 0;
        }
    }

    /** 用户阶数 N 对应的系统最大索引（0 基）；未配置时返回 -1。 */
    static int customMaxIndex(int steps) {
        if (steps < PreferenceStorage.MIN_VOLUME_STEPS) {
            return -1;
        }
        return Math.max(1, Math.min(steps, PreferenceStorage.MAX_VOLUME_STEPS) - 1);
    }
}
