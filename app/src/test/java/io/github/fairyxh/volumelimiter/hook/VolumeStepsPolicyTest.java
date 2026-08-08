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

    @Test
    public void virtualClampEnforcesPercentLimit() {
        // 场景3：50 阶 + 60% -> 最大虚拟索引 29
        assertEquals(29, VolumeStepsHook.clampVirtualIndex(49, 50, 60));
        assertEquals(29, VolumeStepsHook.clampVirtualIndex(200, 50, 60));
        // 未到上限时保持原值
        assertEquals(20, VolumeStepsHook.clampVirtualIndex(20, 50, 60));
        // 负数钳制到 0
        assertEquals(0, VolumeStepsHook.clampVirtualIndex(-5, 50, 60));
    }

    @Test
    public void virtualClampAllowsFullRangeAtOneHundredPercent() {
        assertEquals(49, VolumeStepsHook.clampVirtualIndex(49, 50, 100));
    }

    @Test
    public void virtualClampStopsAtHalfRange() {
        // 50 阶 + 50% -> round(49 * 0.5) = 25
        assertEquals(25, VolumeStepsHook.clampVirtualIndex(49, 50, 50));
        assertEquals(25, VolumeStepsHook.clampVirtualIndex(30, 50, 50));
    }

    @Test
    public void virtualClampPassesThroughWhenStepsDisabled() {
        // 场景4：steps 关闭 = 恢复系统原生行为，不施加虚拟空间限制
        assertEquals(49, VolumeStepsHook.clampVirtualIndex(49, 0, 60));
        assertEquals(15, VolumeStepsHook.clampVirtualIndex(15, 0, 60));
    }
}
