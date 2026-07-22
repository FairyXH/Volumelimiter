package io.github.fairyxh.volumelimiter.config;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Map;

public final class LimitPolicyTest {
    @Test
    public void usesMasterWhenNoOverrideExists() {
        assertEquals(70, LimitPolicy.resolvePercent(true, 70, "speaker",
                Map.of(), true, "com.example.player", Map.of()));
    }

    @Test
    public void deviceOverrideReplacesMasterInsteadOfTakingMinimum() {
        assertEquals(90, LimitPolicy.resolvePercent(true, 50, "speaker",
                Map.of("speaker", 90), false, null, Map.of()));
    }

    @Test
    public void foregroundAppOverrideHasHighestPriority() {
        assertEquals(35, LimitPolicy.resolvePercent(true, 70, "speaker",
                Map.of("speaker", 80), true, "com.example.player",
                Map.of("com.example.player", 35)));
    }

    @Test
    public void disabledPerAppFeatureFallsBackToDevice() {
        assertEquals(80, LimitPolicy.resolvePercent(true, 70, "speaker",
                Map.of("speaker", 80), false, "com.example.player",
                Map.of("com.example.player", 35)));
    }

    @Test
    public void disabledMasterDisablesAllLimits() {
        assertEquals(100, LimitPolicy.resolvePercent(false, 20, "speaker",
                Map.of("speaker", 30), true, "com.example.player",
                Map.of("com.example.player", 10)));
    }

    @Test
    public void percentagesAreClamped() {
        assertEquals(100, LimitPolicy.resolvePercent(true, 150, "default",
                Map.of(), false, null, Map.of()));
        assertEquals(0, LimitPolicy.resolvePercent(true, 80, "speaker",
                Map.of("speaker", -10), false, null, Map.of()));
    }
}
