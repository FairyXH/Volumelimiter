package io.github.fairyxh.volumelimiter.hook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VolumeStepsPolicyTest {
    @Test
    public void unsetStepsAreDisabled() {
        assertEquals(-1, VolumeStepsHook.customMaxIndex(0));
    }

    @Test
    public void stepsMapToZeroBasedMaxIndex() {
        assertEquals(29, VolumeStepsHook.customMaxIndex(30));
        assertEquals(14, VolumeStepsHook.customMaxIndex(15));
    }

    @Test
    public void customMaxNeverDropsBelowOneStep() {
        assertEquals(1, VolumeStepsHook.customMaxIndex(1));
        assertEquals(1, VolumeStepsHook.customMaxIndex(2));
    }

    @Test
    public void stepsAreClampedToSupportedRange() {
        assertEquals(99, VolumeStepsHook.customMaxIndex(100));
        assertEquals(99, VolumeStepsHook.customMaxIndex(200));
    }

    @Test
    public void validIndexIsClampedToUserSpace() {
        // 50 阶：内部索引允许到 49
        assertEquals(0, VolumeStepsHook.clampValidIndex(0, 50));
        assertEquals(49, VolumeStepsHook.clampValidIndex(49, 50));
        assertEquals(49, VolumeStepsHook.clampValidIndex(200, 50));
        assertEquals(0, VolumeStepsHook.clampValidIndex(-5, 50));
    }

    @Test
    public void validIndexPassesThroughWhenStepsUnset() {
        assertEquals(15, VolumeStepsHook.clampValidIndex(15, 0));
    }
}
