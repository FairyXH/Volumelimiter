package io.github.fairyxh.volumelimiter.hook;

import android.media.AudioManager;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.IntUnaryOperator;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.utils.LogUtils;

public final class VolumeAdjustHook {
    private static final int STREAM_ASSISTANT = 11;
    private final ConfigManager configManager;
    private final DeviceRouteHook deviceRouteHook;
    private final AudioAppResolver appResolver;
    private Method getStreamMaxVolume;
    private Method getStreamVolume;
    private Method getActiveStreamType;
    private Method setStreamVolume;
    private volatile Object audioServiceInstance;

    public VolumeAdjustHook(ConfigManager configManager, DeviceRouteHook deviceRouteHook,
                            AudioAppResolver appResolver,
                            Class<?> audioServiceClass) {
        this.configManager = configManager;
        this.deviceRouteHook = deviceRouteHook;
        this.appResolver = appResolver;
        getStreamMaxVolume = findIntMethod(audioServiceClass, "getStreamMaxVolume", 1);
        getStreamVolume = findIntMethod(audioServiceClass, "getStreamVolume", 1);
        getActiveStreamType = findIntMethod(audioServiceClass, "getActiveStreamType", 1);
        setStreamVolume = findSetStreamVolume(audioServiceClass);
    }

    public void enforceCurrentLimits() {
        ConfigManager.Snapshot config = configManager.snapshot();
        LogUtils.setDebug(config.debug);
        Object service = audioServiceInstance;
        if (!config.enabled || service == null) {
            return;
        }
        for (int streamType : new int[]{0, 1, 2, 3, 4, 5, 6, 11}) {
            try {
                int current = invokeInt(getStreamVolume, service, streamType, -1);
                int maximum = calculateMaximum(service, streamType, null, new Object[0]);
                if (current >= 0 && current > maximum) {
                    lowerStream(service, streamType, maximum);
                    LogUtils.debug("ActiveCorrection stream=" + streamType + " current="
                            + current + " limit=" + maximum + " applied=true");
                }
            } catch (Throwable error) {
                LogUtils.debug("Active volume correction failed stream=" + streamType + ": " + error);
            }
        }
    }

    public void rememberAudioService(Object audioService) {
        if (audioService != null) {
            audioServiceInstance = audioService;
        }
    }

    public Object[] clampSetArguments(Object audioService, Method method, Object[] originalArgs) {
        rememberAudioService(audioService);
        ConfigManager.Snapshot config = configManager.snapshot();
        LogUtils.setDebug(config.debug);
        if (!config.enabled) {
            return originalArgs;
        }
        int streamPosition = findStreamPosition(method, originalArgs);
        int indexPosition = findFollowingIntPosition(method, streamPosition);
        if (streamPosition < 0 || indexPosition < 0) {
            return originalArgs;
        }
        int streamType = (Integer) originalArgs[streamPosition];
        Integer explicitDevice = findExplicitDevice(method, originalArgs, indexPosition + 1);
        int maximum = calculateMaximum(audioService, streamType, explicitDevice, originalArgs);
        int requested = (Integer) originalArgs[indexPosition];
        if (requested <= maximum) {
            return originalArgs;
        }
        Object[] changed = originalArgs.clone();
        changed[indexPosition] = maximum;
        LogUtils.debug(method.getName() + " stream=" + streamType + " requested=" + requested
                + " limited=" + maximum);
        return changed;
    }

    public boolean shouldBlockAdjustment(Object audioService, Method method, Object[] args) {
        rememberAudioService(audioService);
        ConfigManager.Snapshot config = configManager.snapshot();
        LogUtils.setDebug(config.debug);
        if (!config.enabled) {
            return false;
        }
        int directionPosition = findDirectionPosition(method, args);
        if (directionPosition < 0 || ((Integer) args[directionPosition]) <= AudioManager.ADJUST_SAME) {
            return false;
        }
        int streamType = resolveAdjustmentStream(audioService, method, args, directionPosition);
        if (streamType < 0) {
            return false;
        }
        int maximum = calculateMaximum(audioService, streamType, null, args);
        int current = invokeInt(getStreamVolume, audioService, streamType, -1);
        boolean blocked = current >= 0 && current >= maximum;
        if (blocked) {
            LogUtils.debug(method.getName() + " blocked stream=" + streamType + " current="
                    + current + " maximum=" + maximum);
        }
        return blocked;
    }

    private int calculateMaximum(Object audioService, int streamType, Integer explicitDevice,
                                 Object[] hookArguments) {
        int systemMaximum = invokeInt(getStreamMaxVolume, audioService, streamType, Integer.MAX_VALUE);
        if (systemMaximum == Integer.MAX_VALUE) {
            return systemMaximum;
        }
        String device = deviceRouteHook.resolve(audioService, streamType, explicitDevice);
        AudioAppResolver.Result apps = appResolver.resolve(hookArguments);
        ConfigManager.Resolution resolution = configManager.snapshot().resolve(
                streamType, device, apps.candidates);
        int maximum = calculateLimitedIndex(systemMaximum, resolution.percent);
        LogUtils.debug("LimitDecision stream=" + streamType + " device=" + device
                + " directApp=" + apps.directPackage + " focusApp=" + apps.focusPackage
                + " foregroundApp=" + apps.foregroundPackage
                + " selectedApp=" + resolution.matchedPackage
                + " appRuleMatched=" + resolution.appRuleMatched
                + " percent=" + resolution.percent + " maximum=" + maximum
                + "/" + systemMaximum);
        return maximum;
    }

    private int resolveAdjustmentStream(Object audioService, Method method, Object[] args,
                                        int directionPosition) {
        return selectAdjustmentStream(method.getName(), args, directionPosition,
                suggested -> invokeInt(getActiveStreamType, audioService, suggested,
                        AudioManager.STREAM_MUSIC));
    }

    static int selectAdjustmentStream(String methodName, Object[] args, int directionPosition,
                                      IntUnaryOperator activeStreamResolver) {
        if (methodName.equals("adjustStreamVolume")) {
            return args.length > 0 && args[0] instanceof Integer value && isKnownStream(value)
                    ? value : -1;
        }
        int suggestedPosition = directionPosition + 1;
        if (suggestedPosition >= args.length || !(args[suggestedPosition] instanceof Integer)) {
            return -1;
        }
        int suggested = (Integer) args[suggestedPosition];
        return isKnownStream(suggested) ? suggested
                : activeStreamResolver.applyAsInt(suggested);
    }

    static int calculateLimitedIndex(int systemMaximum, int percent) {
        if (systemMaximum <= 0 || percent <= 0) {
            return 0;
        }
        long scaled = (long) systemMaximum * Math.min(percent, 100);
        return (int) Math.min(systemMaximum, Math.max(1L, (scaled + 99L) / 100L));
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

    private static int findDirectionPosition(Method method, Object[] args) {
        int position = method.getName().equals("adjustStreamVolume") ? 1 : 0;
        Class<?>[] types = method.getParameterTypes();
        if (position >= types.length || position >= args.length || types[position] != int.class
                || !(args[position] instanceof Integer)) {
            return -1;
        }
        return position;
    }

    private static Integer findExplicitDevice(Method method, Object[] args, int start) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = start; i < types.length && i < args.length; i++) {
            String parameterName = method.getParameters()[i].getName().toLowerCase(Locale.ROOT);
            if (types[i] == int.class && parameterName.contains("device")) {
                return (Integer) args[i];
            }
        }
        return null;
    }

    private static boolean isKnownStream(int streamType) {
        return streamType >= AudioManager.STREAM_VOICE_CALL && streamType <= STREAM_ASSISTANT;
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

    private void lowerStream(Object service, int streamType, int limit) {
        if (setStreamVolume == null) {
            return;
        }
        Class<?>[] types = setStreamVolume.getParameterTypes();
        Object[] args = new Object[types.length];
        int integerIndex = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) {
                args[i] = integerIndex == 0 ? streamType : integerIndex == 1 ? limit : 0;
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
            LogUtils.debug("Unable to lower active stream=" + streamType + ": " + error);
        }
    }

    private static int invokeInt(Method method, Object receiver, int argument, int fallback) {
        if (method == null) {
            return fallback;
        }
        try {
            Object value = method.invoke(receiver, argument);
            return value instanceof Integer ? (Integer) value : fallback;
        } catch (Throwable error) {
            LogUtils.debug("Unable to invoke " + method.getName() + ": " + error);
            return fallback;
        }
    }
}
