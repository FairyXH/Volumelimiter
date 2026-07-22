package io.github.fairyxh.volumelimiter.config;

import java.util.Map;

/** Pure precedence rules shared by the hook and local unit tests. */
public final class LimitPolicy {
    private LimitPolicy() {
    }

    public static int resolvePercent(boolean masterEnabled, int masterPercent,
                                     String deviceKey, Map<String, Integer> deviceOverrides,
                                     boolean perAppEnabled, String foregroundPackage,
                                     Map<String, Integer> appOverrides) {
        if (!masterEnabled) {
            return 100;
        }
        int result = clamp(masterPercent);
        Integer devicePercent = deviceOverrides.get(deviceKey);
        if (devicePercent != null) {
            result = clamp(devicePercent);
        }
        if (perAppEnabled && foregroundPackage != null) {
            Integer appPercent = appOverrides.get(foregroundPackage);
            if (appPercent != null) {
                result = clamp(appPercent);
            }
        }
        return result;
    }

    public static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
