package io.github.fairyxh.volumelimiter.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public final class ConfigManagerPolicyTest {
    @Test
    public void firstConfiguredCandidateWins() {
        ConfigManager.Snapshot snapshot = snapshot(Map.of(
                "com.direct", new ConfigManager.AppRule(40, Map.of(), Map.of()),
                "com.focus", new ConfigManager.AppRule(55, Map.of(), Map.of())));

        ConfigManager.Resolution result = snapshot.resolve(3, "speaker",
                List.of("com.direct", "com.focus", "com.foreground"));

        assertTrue(result.appRuleMatched);
        assertEquals("com.direct", result.matchedPackage);
        assertEquals(40, result.percent);
    }

    @Test
    public void focusCandidateFallsBackWhenDirectCallerHasNoRule() {
        ConfigManager.Snapshot snapshot = snapshot(Map.of(
                "com.music", new ConfigManager.AppRule(50, Map.of(), Map.of())));

        ConfigManager.Resolution result = snapshot.resolve(3, "speaker",
                List.of("com.android.systemui", "com.music"));

        assertEquals("com.music", result.matchedPackage);
        assertEquals(50, result.percent);
    }

    @Test
    public void appDeviceAndStreamLimitsAreCombined() {
        ConfigManager.AppRule rule = new ConfigManager.AppRule(80,
                Map.of(3, 50), Map.of("bluetooth", 70));
        ConfigManager.Snapshot snapshot = snapshot(Map.of("com.music", rule));

        ConfigManager.Resolution result = snapshot.resolve(3, "bluetooth",
                List.of("com.music"));

        assertEquals(50, result.percent);
    }

    @Test
    public void missingAppRuleFallsBackToDeviceAndGlobalStreamSafetyLimit() {
        ConfigManager.Snapshot snapshot = new ConfigManager.Snapshot(true, false, 80, true,
                Map.of(3, 45), Map.of("speaker", 70), Map.of());

        ConfigManager.Resolution result = snapshot.resolve(3, "speaker",
                List.of("com.unknown"));

        assertFalse(result.appRuleMatched);
        assertEquals(45, result.percent);
    }

    @Test
    public void deviceOverrideAppliesWithoutStreamOrAppRule() {
        ConfigManager.Snapshot snapshot = new ConfigManager.Snapshot(true, false, 80, true,
                Map.of(), Map.of("speaker", 55), Map.of());

        ConfigManager.Resolution result = snapshot.resolve(3, "speaker",
                List.of("com.unknown"));

        assertFalse(result.appRuleMatched);
        assertEquals(55, result.percent);
    }

    private static ConfigManager.Snapshot snapshot(Map<String, ConfigManager.AppRule> rules) {
        return new ConfigManager.Snapshot(true, false, 90, true,
                Map.of(), Map.of(), rules);
    }
}
