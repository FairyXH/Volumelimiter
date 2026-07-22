package io.github.fairyxh.volumelimiter.hook;

import android.media.AudioManager;

import java.lang.reflect.Method;
import java.util.Locale;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.utils.LogUtils;

public final class VolumeAdjustHook {
    private static final int STREAM_ASSISTANT = 11;
    private final ConfigManager configManager;
    private final DeviceRouteHook deviceRouteHook;
    private final ForegroundAppResolver foregroundAppResolver;
    private Method getStreamMaxVolume;
    private Method getStreamVolume;
    private Method getActiveStreamType;

    public VolumeAdjustHook(ConfigManager configManager, DeviceRouteHook deviceRouteHook,
                            ForegroundAppResolver foregroundAppResolver,
                            Class<?> audioServiceClass) {
        this.configManager = configManager;
        this.deviceRouteHook = deviceRouteHook;
        this.foregroundAppResolver = foregroundAppResolver;
        getStreamMaxVolume = findIntMethod(audioServiceClass, "getStreamMaxVolume", 1);
        getStreamVolume = findIntMethod(audioServiceClass, "getStreamVolume", 1);
        getActiveStreamType = findIntMethod(audioServiceClass, "getActiveStreamType", 1);
    }

    public Object[] clampSetArguments(Object audioService, Method method, Object[] originalArgs) {
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
        int maximum = calculateMaximum(audioService, streamType, explicitDevice);
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
        int maximum = calculateMaximum(audioService, streamType, null);
        int current = invokeInt(getStreamVolume, audioService, streamType, -1);
        boolean blocked = current >= 0 && current >= maximum;
        if (blocked) {
            LogUtils.debug(method.getName() + " blocked stream=" + streamType + " current="
                    + current + " maximum=" + maximum);
        }
        return blocked;
    }

    private int calculateMaximum(Object audioService, int streamType, Integer explicitDevice) {
        int systemMaximum = invokeInt(getStreamMaxVolume, audioService, streamType, Integer.MAX_VALUE);
        if (systemMaximum == Integer.MAX_VALUE) {
            return systemMaximum;
        }
        String device = deviceRouteHook.resolve(audioService, streamType, explicitDevice);
        String foregroundPackage = foregroundAppResolver.resolve();
        int percent = configManager.snapshot().getEffectivePercent(
                streamType, device, foregroundPackage);
        return Math.max(0, (systemMaximum * percent) / 100);
    }

    private int resolveAdjustmentStream(Object audioService, Method method, Object[] args,
                                        int directionPosition) {
        if (method.getName().equals("adjustStreamVolume")) {
            int streamPosition = findStreamPosition(method, args);
            return streamPosition >= 0 ? (Integer) args[streamPosition] : -1;
        }
        int suggested = findSuggestedStream(args, directionPosition);
        if (suggested >= 0) {
            return suggested;
        }
        return invokeInt(getActiveStreamType, audioService, suggested, AudioManager.STREAM_MUSIC);
    }

    private static int findSuggestedStream(Object[] args, int directionPosition) {
        for (int i = directionPosition + 1; i < args.length; i++) {
            if (args[i] instanceof Integer value && isKnownStream(value)) {
                return value;
            }
        }
        return -1;
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
