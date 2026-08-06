package io.github.fairyxh.volumelimiter.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.utils.LogUtils;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 虚拟音量阶数：把 AudioService 内部音量索引重新标定为 0..N-1（N 阶）。
 *
 * <p>核心是 hook VolumeStreamState.getValidIndex —— setIndex 内部用它对索引做上限钳制。
 * 让它允许到 N-1 后，内部存储、显示、音量键步进全部处于用户空间：50 阶时每按一次
 * 音量键前进 1 用户档（2%），按 50 次从 0% 到 100%。不修改 mIndexMax（final 字段），
 * 不改 native 上限；真实档位少于用户阶数时，native 声音按真实档位跳变，属硬件限制。
 */
public final class VolumeStepsHook {
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
            if (method.getReturnType() != int.class) {
                continue;
            }
            if (method.getName().equals("getMaxIndex")
                    && (method.getParameterCount() == 0
                    || (method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class))) {
                installed += hookReplaceMax(method, "VolumeStreamState.getMaxIndex");
            } else if (method.getName().equals("getValidIndex")
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class) {
                installed += hookClampValidIndex(method);
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
                        if (args.length > 0 && args[0] instanceof Integer index) {
                            return clampValidIndex(index, config.volumeSteps);
                        }
                        return chain.proceed();
                    });
            LogUtils.debug("VolumeSteps hooked VolumeStreamState.getValidIndex");
            return 1;
        } catch (Throwable error) {
            LogUtils.debug("VolumeSteps unable to hook VolumeStreamState.getValidIndex: " + error);
            return 0;
        }
    }

    /** 用户阶数 N 对应的显示最大索引（0 基）；未配置时返回 -1。 */
    static int customMaxIndex(int steps) {
        if (steps < PreferenceStorage.MIN_VOLUME_STEPS) {
            return -1;
        }
        return Math.max(1, Math.min(steps, PreferenceStorage.MAX_VOLUME_STEPS) - 1);
    }

    /** 内部索引钳制到 0..N-1；未配置阶数时原样返回。 */
    static int clampValidIndex(int index, int steps) {
        if (steps < PreferenceStorage.MIN_VOLUME_STEPS) {
            return index;
        }
        return Math.max(0, Math.min(index, customMaxIndex(steps)));
    }
}
