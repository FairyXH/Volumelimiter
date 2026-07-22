package io.github.fairyxh.volumelimiter.config;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class PreferenceStorage {
    public static final String GROUP = "volume_limiter";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_MASTER_PERCENT = "master_percent";
    public static final String KEY_PER_APP_ENABLED = "per_app_enabled";
    public static final String KEY_APP_PACKAGES = "app_packages";
    public static final String KEY_DEBUG = "debug";
    public static final String KEY_TEMPLATES_JSON = "templates_json";
    public static final String KEY_AUTO_APPLY_NEW_APPS = "auto_apply_new_apps";
    public static final String KEY_DEFAULT_TEMPLATE_ID = "default_template_id";
    public static final String KEY_NOTIFY_NEW_APPS = "notify_new_apps";

    public static final class StreamEntry {
        public final int streamType;
        public final String key;
        public final String label;

        StreamEntry(int streamType, String key, String label) {
            this.streamType = streamType;
            this.key = key;
            this.label = label;
        }
    }

    public static final class DeviceEntry {
        public final String key;
        public final String label;

        DeviceEntry(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    public static final Map<Integer, StreamEntry> STREAMS;
    public static final Map<String, DeviceEntry> DEVICES;

    static {
        Map<Integer, StreamEntry> streams = new LinkedHashMap<>();
        addStream(streams, 3, "media", "媒体");
        addStream(streams, 2, "ring", "铃声");
        addStream(streams, 5, "notification", "通知");
        addStream(streams, 4, "alarm", "闹钟");
        addStream(streams, 1, "system", "系统声音");
        addStream(streams, 0, "voice_call", "通话");
        addStream(streams, 6, "bluetooth_sco", "Bluetooth SCO");
        addStream(streams, 11, "assistant", "Assistant");
        STREAMS = Collections.unmodifiableMap(streams);

        Map<String, DeviceEntry> devices = new LinkedHashMap<>();
        addDevice(devices, "speaker", "扬声器");
        addDevice(devices, "bluetooth", "蓝牙耳机");
        addDevice(devices, "wired", "有线耳机");
        addDevice(devices, "hdmi", "HDMI");
        DEVICES = Collections.unmodifiableMap(devices);
    }

    private PreferenceStorage() {
    }

    private static void addStream(Map<Integer, StreamEntry> map, int type, String key, String label) {
        map.put(type, new StreamEntry(type, key, label));
    }

    private static void addDevice(Map<String, DeviceEntry> map, String key, String label) {
        map.put(key, new DeviceEntry(key, label));
    }

    public static String streamEnabledKey(String key) {
        return "stream_" + key + "_enabled";
    }

    public static String streamPercentKey(String key) {
        return "stream_" + key + "_percent";
    }

    public static String deviceEnabledKey(String key) {
        return "device_" + key + "_enabled";
    }

    public static String devicePercentKey(String key) {
        return "device_" + key + "_percent";
    }

    public static String appEnabledKey(String packageName) {
        return "app_" + packageName + "_enabled";
    }

    public static String appPercentKey(String packageName) {
        return "app_" + packageName + "_percent";
    }

    public static String appStreamEnabledKey(String packageName, String streamKey) {
        return "app_" + packageName + "_stream_" + streamKey + "_enabled";
    }

    public static String appStreamPercentKey(String packageName, String streamKey) {
        return "app_" + packageName + "_stream_" + streamKey + "_percent";
    }

    public static String appDeviceEnabledKey(String packageName, String deviceKey) {
        return "app_" + packageName + "_device_" + deviceKey + "_enabled";
    }

    public static String appDevicePercentKey(String packageName, String deviceKey) {
        return "app_" + packageName + "_device_" + deviceKey + "_percent";
    }

    public static void removeAppRule(SharedPreferences.Editor editor, String packageName) {
        editor.remove(appEnabledKey(packageName)).remove(appPercentKey(packageName));
        for (StreamEntry entry : STREAMS.values()) {
            editor.remove(appStreamEnabledKey(packageName, entry.key));
            editor.remove(appStreamPercentKey(packageName, entry.key));
        }
        for (DeviceEntry entry : DEVICES.values()) {
            editor.remove(appDeviceEnabledKey(packageName, entry.key));
            editor.remove(appDevicePercentKey(packageName, entry.key));
        }
    }

    public static Set<String> readAppPackages(SharedPreferences preferences) {
        Set<String> packages = preferences.getStringSet(KEY_APP_PACKAGES, Collections.emptySet());
        return packages == null ? new HashSet<>() : new HashSet<>(packages);
    }

    public static int readPercent(SharedPreferences preferences, String key, int fallback) {
        return LimitPolicy.clamp(preferences.getInt(key, fallback));
    }
}
