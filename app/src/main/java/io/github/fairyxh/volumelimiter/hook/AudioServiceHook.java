package io.github.fairyxh.volumelimiter.hook;

import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.utils.LogUtils;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class AudioServiceHook {
    /** 覆盖所有可能入口（含 OEM/Android 12+ 变体）：名称前缀匹配，不依赖精确签名。 */
    private static final String SET_PREFIX = "setStreamVolume";
    private static final String[] ADJUST_PREFIXES = {
            "adjustStreamVolume", "adjustSuggestedStreamVolume"
    };

    private final XposedModule module;
    private final ConfigManager configManager;

    public AudioServiceHook(XposedModule module, ConfigManager configManager) {
        this.module = module;
        this.configManager = configManager;
    }

    public int install(ClassLoader classLoader) {
        Class<?> audioServiceClass = findAudioServiceClass(classLoader);
        if (audioServiceClass == null) {
            LogUtils.error("AudioService class was not found", null);
            return 0;
        }
        DeviceRouteHook routeHook = new DeviceRouteHook(classLoader, audioServiceClass);
        AudioFocusTracker focusTracker = new AudioFocusTracker();
        focusTracker.install(module, classLoader);
        AudioAppResolver appResolver = new AudioAppResolver(
                focusTracker, new ForegroundAppResolver(classLoader));
        VolumeIndexMapper mapper = new VolumeIndexMapper();
        VolumeAdjustHook limiter = new VolumeAdjustHook(
                configManager, routeHook, appResolver, mapper, audioServiceClass, classLoader);
        new VolumePrecisionHook(module, configManager, mapper).install(classLoader, audioServiceClass);
        HandlerThread correctionThread = new HandlerThread("Volumelimiter-correction");
        correctionThread.start();
        Handler correctionHandler = new Handler(correctionThread.getLooper());
        Runnable correction = limiter::enforceCurrentLimits;
        Runnable requestCorrection = () -> {
            correctionHandler.removeCallbacks(correction);
            correctionHandler.postDelayed(correction, 100L);
        };
        focusTracker.setOwnerChangeListener(requestCorrection::run);
        ForegroundChangeHook.install(module, classLoader, requestCorrection);
        hookConstructors(audioServiceClass, limiter);
        Set<String> installedSignatures = new HashSet<>();
        int installed = 0;
        int limitCandidates = 0;

        for (Class<?> current = audioServiceClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                String signature = method.toGenericString();
                if (!installedSignatures.add(signature) || Modifier.isAbstract(method.getModifiers())
                        || method.isSynthetic()) {
                    continue;
                }
                if (method.getName().startsWith(SET_PREFIX)) {
                    limitCandidates++;
                    installed += hookSetMethod(method, limiter);
                } else if (nameStartsWithAny(method.getName(), ADJUST_PREFIXES)) {
                    limitCandidates++;
                    installed += hookAdjustMethod(method, limiter);
                } else if (method.getName().contains("Volume")) {
                    // 启动时枚举所有音量相关方法，便于确认 ROM 实际入口
                    LogUtils.info("AudioService volume method (not hooked): " + signature);
                }
            }
        }
        LogUtils.info("Installed " + installed + " AudioService hooks on API "
                + android.os.Build.VERSION.SDK_INT + " (limit candidates=" + limitCandidates + ")");
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

    private void hookConstructors(Class<?> audioServiceClass, VolumeAdjustHook limiter) {
        for (Constructor<?> constructor : audioServiceClass.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                module.hook(constructor)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            limiter.rememberAudioService(chain.getThisObject());
                            return result;
                        });
            } catch (Throwable error) {
                LogUtils.debug("Unable to hook AudioService constructor " + constructor + ": " + error);
            }
        }
    }

    private int hookSetMethod(Method method, VolumeAdjustHook limiter) {
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            Object[] args = chain.getArgs().toArray();
                            Object[] changed = limiter.clampSetArguments(
                                    chain.getThisObject(), method, args);
                            return changed == args ? chain.proceed() : chain.proceed(changed);
                        } catch (Throwable hookError) {
                            LogUtils.error("Limiter failed before " + method, hookError);
                            return chain.proceed();
                        }
                    });
            LogUtils.info("LimitHook installed: " + method);
            return 1;
        } catch (Throwable error) {
            LogUtils.error("Unable to hook " + method, error);
            return 0;
        }
    }

    private int hookAdjustMethod(Method method, VolumeAdjustHook limiter) {
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            Object[] args = chain.getArgs().toArray();
                            if (limiter.shouldBlockAdjustment(chain.getThisObject(), method, args)) {
                                return null;
                            }
                        } catch (Throwable hookError) {
                            LogUtils.error("Limiter failed before " + method, hookError);
                        }
                        return chain.proceed();
                    });
            LogUtils.info("LimitHook installed: " + method);
            return 1;
        } catch (Throwable error) {
            LogUtils.error("Unable to hook " + method, error);
            return 0;
        }
    }

    private static Class<?> findAudioServiceClass(ClassLoader classLoader) {
        String[] candidates = {
                "com.android.server.audio.AudioService",
                "com.android.server.audio.AudioService$Lifecycle"
        };
        for (String candidate : candidates) {
            try {
                Class<?> type = Class.forName(candidate, false, classLoader);
                if (candidate.endsWith("$Lifecycle")) {
                    for (Class<?> nested : type.getDeclaredClasses()) {
                        if (nested.getSimpleName().equals("AudioService")) {
                            return nested;
                        }
                    }
                } else {
                    return type;
                }
            } catch (Throwable error) {
                LogUtils.debug(candidate + " unavailable: " + error);
            }
        }
        return null;
    }
}
