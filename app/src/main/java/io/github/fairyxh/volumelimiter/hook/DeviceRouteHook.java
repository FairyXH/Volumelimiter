package io.github.fairyxh.volumelimiter.hook;

import java.lang.reflect.Method;
import java.util.Collection;

import io.github.fairyxh.volumelimiter.utils.LogUtils;

public final class DeviceRouteHook {
    private static final int DEVICE_OUT_SPEAKER = 0x2;
    private static final int DEVICE_OUT_WIRED_HEADSET = 0x4;
    private static final int DEVICE_OUT_WIRED_HEADPHONE = 0x8;
    private static final int DEVICE_OUT_BLUETOOTH_A2DP = 0x80 | 0x100 | 0x200;
    private static final int DEVICE_OUT_HDMI = 0x400;

    private Method getDevicesForStream;
    private Method getDeviceSetForStream;

    public DeviceRouteHook(ClassLoader classLoader, Class<?> audioServiceClass) {
        try {
            Class<?> audioSystem = Class.forName("android.media.AudioSystem", false, classLoader);
            getDevicesForStream = audioSystem.getDeclaredMethod("getDevicesForStream", int.class);
            getDevicesForStream.setAccessible(true);
        } catch (Throwable error) {
            LogUtils.debug("AudioSystem.getDevicesForStream unavailable: " + error);
        }
        for (Method method : audioServiceClass.getDeclaredMethods()) {
            if ((method.getName().equals("getDeviceSetForStream")
                    || method.getName().equals("getDevicesForStream"))
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class) {
                try {
                    method.setAccessible(true);
                    getDeviceSetForStream = method;
                    break;
                } catch (Throwable ignored) {
                    // Fall back to AudioSystem or the default device profile.
                }
            }
        }
    }

    public String resolve(Object audioService, int streamType, Integer explicitDevice) {
        try {
            if (explicitDevice != null) {
                return fromLegacyMask(explicitDevice);
            }
            if (getDeviceSetForStream != null && audioService != null) {
                Object value = getDeviceSetForStream.invoke(audioService, streamType);
                String resolved = fromDeviceCollection(value);
                if (resolved != null) {
                    return resolved;
                }
            }
            if (getDevicesForStream != null) {
                Object value = getDevicesForStream.invoke(null, streamType);
                if (value instanceof Integer) {
                    return fromLegacyMask((Integer) value);
                }
            }
        } catch (Throwable error) {
            LogUtils.debug("Output route lookup failed: " + error);
        }
        return "default";
    }

    private String fromDeviceCollection(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return null;
        }
        boolean speaker = false;
        for (Object item : collection) {
            try {
                Method getInternalType = item.getClass().getMethod("getInternalType");
                Object type = getInternalType.invoke(item);
                if (type instanceof Integer) {
                    String result = fromLegacyMask((Integer) type);
                    if (!"default".equals(result) && !"speaker".equals(result)) {
                        return result;
                    }
                    speaker |= "speaker".equals(result);
                }
            } catch (Throwable ignored) {
                // Vendor collections may contain a different device descriptor type.
            }
        }
        return speaker ? "speaker" : null;
    }

    public static String fromLegacyMask(int mask) {
        if ((mask & DEVICE_OUT_BLUETOOTH_A2DP) != 0) {
            return "bluetooth";
        }
        if ((mask & (DEVICE_OUT_WIRED_HEADSET | DEVICE_OUT_WIRED_HEADPHONE)) != 0) {
            return "wired";
        }
        if ((mask & DEVICE_OUT_HDMI) != 0) {
            return "hdmi";
        }
        if ((mask & DEVICE_OUT_SPEAKER) != 0) {
            return "speaker";
        }
        return "default";
    }
}
