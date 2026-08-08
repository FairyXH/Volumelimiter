package io.github.fairyxh.volumelimiter.hook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.fairyxh.volumelimiter.config.PreferenceStorage;

/**
 * 虚拟音量阶数映射器：只负责“用户虚拟音量空间”和“Android 真实音量空间”之间的转换，
 * 不做任何状态同步或配置变化重投影。
 *
 * <p>用户空间：0..N-1（N 阶，例如 50 阶 → 0..49），仅用于 UI 显示和音量键步进。
 * 真实空间：0..nativeMaxIndex（例如 0..160），是 AudioService 内部 / AudioFlinger
 * 实际使用的索引。最大音量百分比通过真实上限 nativeLimit 表达：
 *
 * <pre>
 * nativeLimit = nativeMaxIndex * maxVolumePercent / 100
 * </pre>
 *
 * <p>最终映射为 virtual 0..N-1 ↔ native 0..nativeLimit，因此“满格”（N-1）恰好对应
 * maxVolumePercent 的真实音量（例如 50 阶 + 50% 限制 + nativeMax=150 →
 * virtual 49 ↔ native 75）。映射为线性近似；项目未引入音量曲线表时优先保证
 * 逻辑正确与跨版本兼容。
 *
 * <p>虚拟空间的最大允许索引（例如 60% 限制下 virtual 49 → 29）由
 * {@link #virtualLimitIndex(int, int)} 计算，由调用方（VolumeStepsHook）在 set/adjust
 * 入口执行；本类只提供计算，不改变映射公式本身。
 */
public final class VolumeIndexMapper {
    /** 每个音频流缓存的真实 native 最大索引（AudioSystem.getMaxVolumeIndex 原值）。 */
    private final Map<Integer, Integer> nativeMaxByStream = new ConcurrentHashMap<>();

    /** 保存真实最大索引；<=0 的无效值不缓存。 */
    public void rememberNativeMax(int streamType, int nativeMax) {
        if (nativeMax > 0) {
            nativeMaxByStream.put(streamType, nativeMax);
        }
    }

    /** 真实 native 最大索引；未缓存时返回 -1。 */
    public int nativeMaxIndex(int streamType) {
        Integer value = nativeMaxByStream.get(streamType);
        return value == null ? -1 : value;
    }

    /** 虚拟阶数是否激活（steps >= 1）。 */
    public boolean isActive(int steps) {
        return steps >= PreferenceStorage.MIN_VOLUME_STEPS;
    }

    /** 用户空间最大索引（0 基），未配置时返回 -1。 */
    public int virtualMaxIndex(int steps) {
        return VolumeStepsHook.customMaxIndex(steps);
    }

    /**
     * 虚拟空间最大允许索引：round(virtualMaxIndex * percent / 100)，钳制在 0..virtualMaxIndex。
     * 例如 50 阶 + 60% → round(49 * 0.6) = 29。阶数未激活时返回 -1。
     */
    public int virtualLimitIndex(int steps, int percent) {
        int vMax = virtualMaxIndex(steps);
        if (!isActive(steps) || vMax <= 0) {
            return -1;
        }
        long allowed = Math.round((double) vMax * clampPercent(percent) / 100.0);
        return (int) Math.max(0L, Math.min(vMax, allowed));
    }

    /**
     * 真实空间上限：nativeMaxIndex * percent / 100（向上取整，保留至少 1 档）。
     * 真实最大值未缓存时返回 -1，调用方应视为映射不可用。
     */
    public int nativeLimit(int streamType, int steps, int percent) {
        int realMax = nativeMaxIndex(streamType);
        if (!isActive(steps) || realMax <= 0) {
            return -1;
        }
        return calculateLimitedIndex(realMax, percent);
    }

    /**
     * 虚拟索引 → 真实索引：
     * round(virtualIndex * nativeLimit / virtualMaxIndex)，钳制在 0..nativeLimit。
     * 阶数未激活或真实最大值未知时原样返回，保证直通。
     */
    public int virtualToNative(int streamType, int virtualIndex, int steps, int percent) {
        int vMax = virtualMaxIndex(steps);
        int limit = nativeLimit(streamType, steps, percent);
        if (!isActive(steps) || vMax <= 0 || limit < 0) {
            return virtualIndex;
        }
        if (limit == 0) {
            return 0;
        }
        long mapped = Math.round((double) virtualIndex * limit / vMax);
        return (int) Math.max(0L, Math.min(limit, mapped));
    }

    /**
     * 真实索引 → 虚拟索引：
     * round(nativeIndex * virtualMaxIndex / nativeLimit)，钳制在 0..virtualMaxIndex。
     * 阶数未激活或真实最大值未知时原样返回，保证直通。
     */
    public int nativeToVirtual(int streamType, int nativeIndex, int steps, int percent) {
        int vMax = virtualMaxIndex(steps);
        int limit = nativeLimit(streamType, steps, percent);
        if (!isActive(steps) || vMax <= 0 || limit < 0) {
            return nativeIndex;
        }
        if (limit == 0) {
            return 0;
        }
        long mapped = Math.round((double) nativeIndex * vMax / limit);
        return (int) Math.max(0L, Math.min(vMax, mapped));
    }

    /** 百分比 → 真实空间上限（与 VolumeAdjustHook 相同的溢出安全向上取整语义）。 */
    static int calculateLimitedIndex(int systemMaximum, int percent) {
        if (systemMaximum <= 0 || percent <= 0) {
            return 0;
        }
        long scaled = (long) systemMaximum * Math.min(percent, 100);
        return (int) Math.min(systemMaximum, Math.max(1L, (scaled + 99L) / 100L));
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
