package io.github.fairyxh.volumelimiter.config;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigManager implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final class Snapshot {
        public final boolean enabled;
        public final boolean debug;
        private final int masterPercent;
        private final boolean perAppEnabled;
        private final Map<Integer, Integer> streamPercents;
        private final Map<String, Integer> devicePercents;
        private final Map<String, Integer> appPercents;

        Snapshot(boolean enabled, boolean debug, int masterPercent, boolean perAppEnabled,
                 Map<Integer, Integer> streamPercents, Map<String, Integer> devicePercents,
                 Map<String, Integer> appPercents) {
            this.enabled = enabled;
            this.debug = debug;
            this.masterPercent = masterPercent;
            this.perAppEnabled = perAppEnabled;
            this.streamPercents = streamPercents;
            this.devicePercents = devicePercents;
            this.appPercents = appPercents;
        }

        public int getEffectivePercent(int streamType, String deviceKey, String foregroundPackage) {
            int routePercent = LimitPolicy.resolvePercent(enabled, masterPercent, deviceKey,
                    devicePercents, perAppEnabled, foregroundPackage, appPercents);
            if (!enabled) {
                return 100;
            }
            Integer streamPercent = streamPercents.get(streamType);
            return streamPercent == null ? routePercent : Math.min(routePercent, streamPercent);
        }
    }

    private final SharedPreferences preferences;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public ConfigManager(SharedPreferences preferences) {
        this.preferences = preferences;
        reload();
        preferences.registerOnSharedPreferenceChangeListener(this);
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        reload();
    }

    private void reload() {
        Map<Integer, Integer> streams = new HashMap<>();
        for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
            if (preferences.getBoolean(PreferenceStorage.streamEnabledKey(entry.key), false)) {
                streams.put(entry.streamType, PreferenceStorage.readPercent(
                        preferences, PreferenceStorage.streamPercentKey(entry.key), 100));
            }
        }

        Map<String, Integer> devices = new HashMap<>();
        for (PreferenceStorage.DeviceEntry entry : PreferenceStorage.DEVICES.values()) {
            if (preferences.getBoolean(PreferenceStorage.deviceEnabledKey(entry.key), false)) {
                devices.put(entry.key, PreferenceStorage.readPercent(
                        preferences, PreferenceStorage.devicePercentKey(entry.key), 100));
            }
        }

        Map<String, Integer> apps = new HashMap<>();
        for (String packageName : PreferenceStorage.readAppPackages(preferences)) {
            if (preferences.getBoolean(PreferenceStorage.appEnabledKey(packageName), true)) {
                apps.put(packageName, PreferenceStorage.readPercent(preferences,
                        PreferenceStorage.appPercentKey(packageName), 100));
            }
        }
        snapshot.set(new Snapshot(
                preferences.getBoolean(PreferenceStorage.KEY_ENABLED, false),
                preferences.getBoolean(PreferenceStorage.KEY_DEBUG, false),
                PreferenceStorage.readPercent(preferences,
                        PreferenceStorage.KEY_MASTER_PERCENT, 100),
                preferences.getBoolean(PreferenceStorage.KEY_PER_APP_ENABLED, false),
                streams,
                devices,
                apps));
    }
}
