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
    private static final Set<String> SET_METHODS = Set.of(
            "setStreamVolume", "setStreamVolumeWithAttribution");
    private static final Set<String> ADJUST_METHODS = Set.of(
            "adjustStreamVolume", "adjustSuggestedStreamVolume",
            "adjustSuggestedStreamVolumeForUid");

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
        VolumeAdjustHook limiter = new VolumeAdjustHook(
                configManager, routeHook, appResolver, audioServiceClass);
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

        for (Class<?> current = audioServiceClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                String signature = method.toGenericString();
                if (!installedSignatures.add(signature) || Modifier.isAbstract(method.getModifiers())
                        || method.isSynthetic()) {
                    continue;
                }
                if (SET_METHODS.contains(method.getName())) {
                    installed += hookSetMethod(method, limiter);
                } else if (ADJUST_METHODS.contains(method.getName())) {
                    installed += hookAdjustMethod(method, limiter);
                }
            }
        }
        LogUtils.info("Installed " + installed + " AudioService hooks on API "
                + android.os.Build.VERSION.SDK_INT);
        return installed;
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
            LogUtils.debug("Hooked " + method);
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
            LogUtils.debug("Hooked " + method);
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
