package io.github.fairyxh.volumelimiter.hook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public final class VolumeAdjustmentPolicyTest {
    @Test
    public void defaultSuggestedStreamIsResolvedWithoutScanningFlags() {
        int useDefaultStream = Integer.MIN_VALUE;
        Object[] args = {1, useDefaultStream, 3, "com.example.music", 10001};
        AtomicInteger received = new AtomicInteger();

        int stream = VolumeAdjustHook.selectAdjustmentStream(
                "adjustSuggestedStreamVolume", args, 0, suggested -> {
                    received.set(suggested);
                    return 3;
                });

        assertEquals(useDefaultStream, received.get());
        assertEquals(3, stream);
    }

    @Test
    public void explicitAdjustStreamUsesFirstArgument() {
        Object[] args = {3, 1, 4096, "com.example.music"};

        int stream = VolumeAdjustHook.selectAdjustmentStream(
                "adjustStreamVolume", args, 1, suggested -> 0);

        assertEquals(3, stream);
    }

    @Test
    public void positiveLimitKeepsAtLeastOneVolumeStep() {
        assertEquals(1, VolumeAdjustHook.calculateLimitedIndex(3, 20));
        assertEquals(0, VolumeAdjustHook.calculateLimitedIndex(15, 0));
        assertEquals(3, VolumeAdjustHook.calculateLimitedIndex(15, 20));
    }
}
