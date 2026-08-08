package io.github.fairyxh.volumelimiter.hook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VolumeIndexMapperTest {
    private static final int STREAM_MUSIC = 3;
    private static final int NATIVE_MAX = 160;

    private VolumeIndexMapper mapperWithNativeMax() {
        VolumeIndexMapper mapper = new VolumeIndexMapper();
        mapper.rememberNativeMax(STREAM_MUSIC, NATIVE_MAX);
        return mapper;
    }

    @Test
    public void nativeMaxIndexStaysUnchangedRegardlessOfPrecision() {
        // 50 精度 / 100 精度：真实最大值仍为 160，禁止 x/50、x/100
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(160, mapper.nativeMaxIndex(STREAM_MUSIC));
        assertEquals(160, mapper.nativeMaxIndex(STREAM_MUSIC));
    }

    @Test
    public void nativeLimitAtOneHundredPercentEqualsNativeMax() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(160, mapper.nativeLimit(STREAM_MUSIC, 100));
    }

    @Test
    public void nativeLimitAtSeventyPercent() {
        // nativeMax=160、限制 70% -> nativeLimit=112，显示 112/160
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(112, mapper.nativeLimit(STREAM_MUSIC, 70));
    }

    @Test
    public void nativeLimitScalesAndKeepsAtLeastOneStep() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(80, mapper.nativeLimit(STREAM_MUSIC, 50));
        assertEquals(2, mapper.nativeLimit(STREAM_MUSIC, 1));
        assertEquals(0, mapper.nativeLimit(STREAM_MUSIC, 0));
    }

    @Test
    public void adjustStepScalesWithPrecision() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        // 160/50 = 3.2 -> 约 3
        assertEquals(3, mapper.adjustStep(STREAM_MUSIC, 50));
        // 160/100 = 1.6 -> 约 2
        assertEquals(2, mapper.adjustStep(STREAM_MUSIC, 100));
        // 160/1 -> 一次到满
        assertEquals(160, mapper.adjustStep(STREAM_MUSIC, 1));
    }

    @Test
    public void adjustStepDisabledWhenPrecisionOffOrNativeMaxUnknown() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(-1, mapper.adjustStep(STREAM_MUSIC, 0));
        VolumeIndexMapper unknown = new VolumeIndexMapper();
        assertEquals(-1, unknown.adjustStep(STREAM_MUSIC, 50));
    }

    @Test
    public void adjustStepNeverBelowOne() {
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(1, VolumeIndexMapper.calculateAdjustStep(15, 50));
        assertEquals(1, VolumeIndexMapper.calculateAdjustStep(1, 100));
    }

    @Test
    public void limitAndStepAreIndependent() {
        // 限制只影响上限，精度只影响步长：满音量最大值与精度无关
        VolumeIndexMapper mapper = mapperWithNativeMax();
        assertEquals(112, mapper.nativeLimit(STREAM_MUSIC, 70));
        assertEquals(3, mapper.adjustStep(STREAM_MUSIC, 50));
        assertEquals(2, mapper.adjustStep(STREAM_MUSIC, 100));
    }
}
