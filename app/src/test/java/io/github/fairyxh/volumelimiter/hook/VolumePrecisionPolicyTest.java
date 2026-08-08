package io.github.fairyxh.volumelimiter.hook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VolumePrecisionPolicyTest {
    @Test
    public void precisionFiftyStepsAboutThreePerPress() {
        // nativeMax=160、precision=50 -> round(160/50)=3
        assertEquals(3, VolumeIndexMapper.calculateAdjustStep(160, 50));
    }

    @Test
    public void precisionOneHundredStepsAboutOneOrTwoPerPress() {
        // nativeMax=160、precision=100 -> round(160/100)=2
        assertEquals(2, VolumeIndexMapper.calculateAdjustStep(160, 100));
    }

    @Test
    public void precisionOneJumpsToMaximumInOnePress() {
        assertEquals(160, VolumeIndexMapper.calculateAdjustStep(160, 1));
    }

    @Test
    public void precisionDisabledReturnsUnavailable() {
        assertEquals(-1, VolumeIndexMapper.calculateAdjustStep(160, 0));
        assertEquals(-1, VolumeIndexMapper.calculateAdjustStep(-1, 50));
    }

    @Test
    public void precisionNeverDropsBelowOneStep() {
        assertEquals(1, VolumeIndexMapper.calculateAdjustStep(15, 50));
        assertEquals(1, VolumeIndexMapper.calculateAdjustStep(1, 100));
    }
}
