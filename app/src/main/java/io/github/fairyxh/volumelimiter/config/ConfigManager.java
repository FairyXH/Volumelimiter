package io.github.fairyxh.volumelimiter.config;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigManager implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final class AppRule {
        private final int masterPercent;
        private final Map<Integer, Integer> streamPercents;
        private final Map<String, Integer> devicePercents;

        AppRule(int masterPercent, Map<Integer, Integer> streamPercents,
                Map<String, Integer> devicePercents) {
            this.masterPercent = masterPercent;
            this.streamPercents = Collections.unmodifiableMap(new HashMap<>(streamPercents));
            this.devicePercents = Collections.unmodifiableMap(new HashMap<>(devicePercents));
        }

        int resolve(int streamType, String deviceKey) {
            int result = devicePercents.getOrDefault(deviceKey, masterPercent);
            Integer streamPercent = streamPercents.get(streamType);
            return streamPercent == null ? result : Math.min(result, streamPercent);
        }
    }

    public static final class Resolution {
        public final int percent;
        public final String matchedPackage;
        public final boolean appRuleMatched;

        Resolution(int percent, String matchedPackage, boolean appRuleMatched) {
            this.percent = percent;
            this.matchedPackage = matchedPackage;
            this.appRuleMatched = appRuleMatched;
        }
    }

    public static final class Snapshot {
        public final boolean enabled;
        public final boolean debug;
        private final int masterPercent;
        private final boolean perAppEnabled;
        private final Map<Integer, Integer> streamPercents;
        private final Map<String, Integer> devicePercents;
        private final Map<String, AppRule> appRules;

        Snapshot(boolean enabled, boolean debug, int masterPercent, boolean perAppEnabled,
                 Map<Integer, Integer> streamPercents, Map<String, Integer> devicePercents,
                 Map<String, AppRule> appRules) {
            this.enabled = enabled;
            this.debug = debug;
            this.masterPercent = masterPercent;
            this.perAppEnabled = perAppEnabled;
            this.streamPercents = Collections.unmodifiableMap(new HashMap<>(streamPercents));
            this.devicePercents = Collections.unmodifiableMap(new HashMap<>(devicePercents));
            this.appRules = Collections.unmodifiableMap(new HashMap<>(appRules));
        }

        public boolean hasAppRule(String packageName) {
            return perAppEnabled && packageName != null && appRules.containsKey(packageName);
        }

        public Resolution resolve(int streamType, String deviceKey, List<String> packageCandidates) {
            if (!enabled) {
                return new Resolution(100, null, false);
            }
            int routePercent = masterPercent;
            Integer devicePercent = devicePercents.get(deviceKey);
            if (devicePercent != null) {
                routePercent = devicePercent;
            }
            String matchedPackage = null;
            if (perAppEnabled) {
                for (String packageName : packageCandidates) {
                    AppRule rule = appRules.get(packageName);
                    if (rule != null) {
                        routePercent = rule.resolve(streamType, deviceKey);
                        matchedPackage = packageName;
                        break;
                    }
                }
            }
            Integer streamPercent = streamPercents.get(streamType);
            int result = streamPercent == null ? routePercent : Math.min(routePercent, streamPercent);
            return new Resolution(result, matchedPackage, matchedPackage != null);
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

        Map<String, AppRule> apps = new HashMap<>();
        for (String packageName : PreferenceStorage.readAppPackages(preferences)) {
            if (preferences.getBoolean(PreferenceStorage.appEnabledKey(packageName), true)) {
                Map<Integer, Integer> appStreams = new HashMap<>();
                for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
                    if (preferences.getBoolean(PreferenceStorage.appStreamEnabledKey(
                            packageName, entry.key), false)) {
                        appStreams.put(entry.streamType, PreferenceStorage.readPercent(preferences,
                                PreferenceStorage.appStreamPercentKey(packageName, entry.key), 100));
                    }
                }
                Map<String, Integer> appDevices = new HashMap<>();
                for (PreferenceStorage.DeviceEntry entry : PreferenceStorage.DEVICES.values()) {
                    if (preferences.getBoolean(PreferenceStorage.appDeviceEnabledKey(
                            packageName, entry.key), false)) {
                        appDevices.put(entry.key, PreferenceStorage.readPercent(preferences,
                                PreferenceStorage.appDevicePercentKey(packageName, entry.key), 100));
                    }
                }
                apps.put(packageName, new AppRule(PreferenceStorage.readPercent(preferences,
                        PreferenceStorage.appPercentKey(packageName), 100), appStreams, appDevices));
            }
        }
        snapshot.set(new Snapshot(
                preferences.getBoolean(PreferenceStorage.KEY_ENABLED, false),
                preferences.getBoolean(PreferenceStorage.KEY_DEBUG, false),
                PreferenceStorage.readPercent(preferences,
                        PreferenceStorage.KEY_MASTER_PERCENT, 100),
                preferences.getBoolean(PreferenceStorage.KEY_PER_APP_ENABLED, false) || !apps.isEmpty(),
                streams,
                devices,
                apps));
    }
}
