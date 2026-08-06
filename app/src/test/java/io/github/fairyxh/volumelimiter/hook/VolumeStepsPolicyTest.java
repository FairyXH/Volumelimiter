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
}
