package io.github.fairyxh.volumelimiter.hook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VolumeIndexMapperTest {
    private static final int STREAM_MUSIC = 3;
    private static final int NATIVE_MAX = 150;

    private VolumeIndexMapper mapperWithNativeMax() {
        VolumeIndexMapper mapper = new VolumeIndexMapper();
        mapper.rememberNativeMax(STREAM_MUSIC, NATIVE_MAX);
        return mapper;
    }

    @Test
    public void fullVirtualStepMapsToNativeLimit() {
        // 50 阶 + 50% 限制 + nativeMax=150 -> nativeLimit=75，virtual 49 -> native 75
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(75, mapper.virtualToNative(STREAM_MUSIC, 49, 50, 50));
        assertEquals(49, mapper.nativeToVirtual(STREAM_MUSIC, 75, 50, 50));
    }

    @Test
    public void zeroAndHalfVirtualMapProportionally() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(0, mapper.virtualToNative(STREAM_MUSIC, 0, 50, 50));
        // round(25 * 75 / 49) = 38
        assertEquals(38, mapper.virtualToNative(STREAM_MUSIC, 25, 50, 50));
        // round(38 * 49 / 75) = 25
        assertEquals(25, mapper.nativeToVirtual(STREAM_MUSIC, 38, 50, 50));
    }

    @Test
    public void noPercentLimitMapsFullNativeRange() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(NATIVE_MAX, mapper.virtualToNative(STREAM_MUSIC, 49, 50, 100));
        assertEquals(49, mapper.nativeToVirtual(STREAM_MUSIC, NATIVE_MAX, 50, 100));
    }

    @Test
    public void zeroPercentLimitMapsEverythingToZero() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(0, mapper.nativeLimit(STREAM_MUSIC, 50, 0));
        assertEquals(0, mapper.virtualToNative(STREAM_MUSIC, 49, 50, 0));
        assertEquals(0, mapper.nativeToVirtual(STREAM_MUSIC, 150, 50, 0));
    }

    @Test
    public void disabledStepsPassThroughUnchanged() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(75, mapper.virtualToNative(STREAM_MUSIC, 75, 0, 50));
        assertEquals(75, mapper.nativeToVirtual(STREAM_MUSIC, 75, 0, 50));
    }

    @Test
    public void unknownNativeMaxPassesThroughUntilCached() {
        VolumeIndexMapper mapper = new VolumeIndexMapper();
        assertEquals(49, mapper.virtualToNative(STREAM_MUSIC, 49, 50, 50));
        assertEquals(75, mapper.nativeToVirtual(STREAM_MUSIC, 75, 50, 50));
        mapper.rememberNativeMax(STREAM_MUSIC, NATIVE_MAX);
        assertEquals(75, mapper.virtualToNative(STREAM_MUSIC, 49, 50, 50));
    }

    @Test
    public void mappedValuesStayInsideTheirRanges() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(75, mapper.virtualToNative(STREAM_MUSIC, 999, 50, 50));
        assertEquals(0, mapper.virtualToNative(STREAM_MUSIC, -10, 50, 50));
        assertEquals(49, mapper.nativeToVirtual(STREAM_MUSIC, 999, 50, 50));
        assertEquals(0, mapper.nativeToVirtual(STREAM_MUSIC, -10, 50, 50));
    }

    @Test
    public void limitedIndexKeepsAtLeastOneStep() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        // ceil(150 * 1%) = 2，限制百分比保证至少 1 档
        assertEquals(2, mapper.nativeLimit(STREAM_MUSIC, 50, 1));
        assertEquals(75, mapper.nativeLimit(STREAM_MUSIC, 50, 50));
    }

    @Test
    public void rescaleRaisesVolumeWhenPercentIncreases() {
        // 50% -> 100%：真实 75（旧限制 75）按比例映射到新限制 150
        assertEquals(150, VolumeIndexMapper.rescaleForConfig(75, 75, 150));
        // 半格：真实 38/75 -> 77/150
        assertEquals(76, VolumeIndexMapper.rescaleForConfig(38, 75, 150));
    }

    @Test
    public void rescaleLowersVolumeWhenPercentDecreases() {
        // 100% -> 30%：真实 150（旧限制 150）-> 45
        assertEquals(45, VolumeIndexMapper.rescaleForConfig(150, 150, 45));
        // 50% -> 30%：真实 75（旧限制 75）-> 45
        assertEquals(45, VolumeIndexMapper.rescaleForConfig(75, 75, 45));
    }

    @Test
    public void rescaleKeepsValueWhenOldLimitUnknownOrNewLimitZero() {
        assertEquals(75, VolumeIndexMapper.rescaleForConfig(75, -1, 150));
        assertEquals(75, VolumeIndexMapper.rescaleForConfig(75, 0, 150));
        assertEquals(0, VolumeIndexMapper.rescaleForConfig(75, 75, 0));
    }

    @Test
    public void loweredPercentStillMapsFullVirtualToNewLimit() {
        // 场景3：steps=50、percent=30、nativeMax=150 -> virtual 49 -> native 45
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(45, mapper.virtualToNative(STREAM_MUSIC, 49, 50, 30));
        assertEquals(49, mapper.nativeToVirtual(STREAM_MUSIC, 45, 50, 30));
    }

    @Test
    public void increasedPercentMapsFullVirtualToFullNative() {
        // 场景2：percent 50 -> 100 后 virtual 49 -> native 150
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(150, mapper.virtualToNative(STREAM_MUSIC, 49, 50, 100));
        assertEquals(49, mapper.nativeToVirtual(STREAM_MUSIC, 150, 50, 100));
    }
}
