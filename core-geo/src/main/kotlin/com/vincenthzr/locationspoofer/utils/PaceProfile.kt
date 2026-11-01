package com.vincenthzr.locationspoofer.utils

/**
 * 按公里配速曲线（3.2.0 批 1）。
 *
 * 设计原则：**距离是权威量，速度是距离的导数**。
 * 3.1.x 把随机噪声的积分叠加到距离上，噪声非零均值，几分钟窗口内系统性偏正
 * 20%~30%，实测「设 6'00"、目标 App 测 4'30"」。
 * 本类彻底绕开这一点：配速只在"每公里"这一层做确定性起伏，
 * 段内严格匀速，因此任何"按相邻定位点反推配速"或"对 speed 字段积分"的
 * 目标 App，测出来的都是设定的配速曲线。
 *
 * 数据流：
 * ```
 * pace_k = clamp(basePace + wobble_k + fatigue_k + firstKmBias_k)   // 单位：秒/公里
 * speed_k = 1000 / pace_k                                          // 米/秒
 * t_k     = t_{k-1} + pace_{k-1}                                   // 第 k 公里起始时刻
 * distanceAt(t) = k*1000 + speed_k * (t - t_k)                     // 段内匀速
 * elapsedAt(d)  = t_k + (d - k*1000) / speed_k
 * stepsAt(d)    = Σ 每段距离 / stride_k,  stride_k = speed_k / (cadence_k/60)
 * ```
 * 全程无随机游走，只有"每公里取一个确定值"，因此两端（App / Hook）用同一
 * seed 计算结果完全一致，不需要跨进程通信。
 */
enum class Fatigue(val minSecPerKm: Double, val maxSecPerKm: Double) {
    /** 不衰减。 */
    OFF(0.0, 0.0),

    /** 弱：每公里掉 3~8 秒。 */
    WEAK(3.0, 8.0),

    /** 强：每公里掉 5~15 秒。 */
    STRONG(5.0, 15.0)
}

class PaceProfile(
    /** 基准速度（米/秒）。6'00"/km -> 2.778 m/s */
    private val baseSpeedMps: Double,
    /** 每公里配速浮动幅度（秒），0 / 5 / 10 / 15，默认 5。 */
    private val perKmVariationSec: Double = DEFAULT_VARIATION_SEC,
    /** 体力衰减档位，默认关。 */
    private val fatigue: Fatigue = Fatigue.OFF,
    /** 第一公里偏快（负数=更快），默认 -3 秒。 */
    private val firstKmBiasSec: Double = DEFAULT_FIRST_KM_BIAS_SEC,
    /** 整条路线长度（米），用于确定力竭起始点；<=0 表示未知（则不启用衰减）。 */
    private val routeLengthM: Double = 0.0,
    /** 随机种子，调用方统一传路线开始时间戳。 */
    private val seed: Long = 0L,
    /** 步频基准偏移（0 表示使用速度线性映射）。 */
    private val cadenceOffsetSpm: Double = 0.0
) {

    init {
        require(baseSpeedMps > 0.0) { "baseSpeedMps must be > 0" }
    }

    private val basePaceSec: Double = 1000.0 / baseSpeedMps

    // 懒生成并按公里缓存：长路线（全马 42 km）只会生成 42 个数。
    private val wobbles = ArrayList<Double>(64)
    private val fatigues = ArrayList<Double>(64)
    private val paces = ArrayList<Double>(64)
    private val starts = ArrayList<Double>(64)

    /** 第 [k] 公里的配速（秒/公里），k 从 0 开始。 */
    fun paceSecPerKm(k: Int): Double {
        require(k >= 0) { "k must be >= 0" }
        ensure(k)
        return paces[k]
    }

    /** 第 [k] 公里的速度（米/秒）。 */
    fun speedMps(k: Int): Double = 1000.0 / paceSecPerKm(k)

    /** 第 [k] 公里的步频（步/分钟）。 */
    fun cadenceSpm(k: Int): Double {
        val v = speedMps(k)
        return (167.0 + (v - 2.78) * 8.0 + cadenceOffsetSpm).coerceIn(120.0, 210.0)
    }

    /** 第 [k] 公里的步幅（米/步）。 */
    fun strideM(k: Int): Double = speedMps(k) / (cadenceSpm(k) / 60.0)

    /** 从出发到 [elapsedSec] 秒的累计距离（米）。 */
    fun distanceAt(elapsedSec: Double): Double {
        if (elapsedSec <= 0.0) return 0.0
        val k = kmIndexAt(elapsedSec)
        return k * 1000.0 + speedMps(k) * (elapsedSec - starts[k])
    }

    /** 累计走到 [distanceM] 米所需的秒数。 */
    fun elapsedAt(distanceM: Double): Double {
        if (distanceM <= 0.0) return 0.0
        val k = (distanceM / 1000.0).toInt()
        ensure(k)
        return starts[k] + (distanceM - k * 1000.0) / speedMps(k)
    }

    /**
     * 断点续跑：从 [fromDistanceM] 起再走 [deltaSec] 秒后的累计距离。
     *
     * 暂停/恢复时 `MotionController` 会把已走距离记进 `routeOffset` 并重置起点时间，
     * 调用方必须用本方法（而不是 `distanceAt(deltaSec)`），否则恢复后配速会从头再来、
     * 体力衰减被悄悄重置。
     */
    fun distanceAfter(fromDistanceM: Double, deltaSec: Double): Double {
        if (deltaSec <= 0.0) return fromDistanceM
        return distanceAt(elapsedAt(fromDistanceM) + deltaSec)
    }

    /** [elapsedSec] 时刻的瞬时速度（米/秒）。可直接用于 `Location.speed`，无随机噪声。 */
    fun speedAt(elapsedSec: Double): Double {
        if (elapsedSec <= 0.0) return speedMps(0)
        return speedMps(kmIndexAt(elapsedSec))
    }

    /**
     * 累计走到 [distanceM] 米时的步数（连续值，整数部分是步数）。
     *
     * 按公里分段累加，跨段处步数连续；步数与距离强绑定（步数 × 步幅 = 距离），
     * 目标 App 的"步数/里程"交叉校验不会误判。
     */
    fun stepsAt(distanceM: Double): Double {
        if (distanceM <= 0.0) return 0.0
        var remain = distanceM
        var k = 0
        var steps = 0.0
        while (remain > 0.0) {
            val segment = minOf(remain, 1000.0)
            steps += segment / strideM(k)
            remain -= segment
            k++
        }
        return steps
    }

    // ---------------------------------------------------------------- 内部实现

    private fun kmIndexAt(elapsedSec: Double): Int {
        ensure(0) // 保证 starts 至少有第 0 段起点，避免空表越界
        // 保证 starts 至少生成到能覆盖 elapsedSec 的位置
        var k = (paces.size - 1).coerceAtLeast(0)
        while (!(starts[k] <= elapsedSec && (k == paces.size - 1 || elapsedSec < starts[k + 1]))) {
            if (elapsedSec < starts[k]) {
                // 二分回退（极少发生：只在 elapsedSec 小于最新生成段的起点时）
                var lo = 0
                var hi = k
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (starts[mid] <= elapsedSec) lo = mid + 1 else hi = mid
                }
                return (lo - 1).coerceAtLeast(0)
            }
            ensure(k + 1)
            k++
        }
        return k
    }

    private fun ensure(k: Int) {
        while (paces.size <= k) {
            val idx = paces.size

            // ① 每公里浮动：平滑随机游走，夹在 ±perKmVariationSec
            val prevWobble = if (idx == 0) 0.0 else wobbles[idx - 1]
            val wobbleStep = uniform(idx, -1.0, 1.0) * perKmVariationSec * 0.6
            val wobble = (prevWobble + wobbleStep)
                .coerceIn(-perKmVariationSec, perKmVariationSec)
            wobbles.add(wobble)

            // ② 力竭：从总路程 40% 后开始，每公里从区间内取一个衰减值，累计封顶 90 秒
            val decay = if (fatigue == Fatigue.OFF || routeLengthM <= 0.0 || idx <= fatigueStartKm()) {
                0.0
            } else {
                uniformFatigue(idx, fatigue.minSecPerKm, fatigue.maxSecPerKm)
            }
            val prevFatigue = if (idx == 0) 0.0 else fatigues[idx - 1]
            val fatigueAcc = (prevFatigue + decay).coerceAtMost(MAX_FATIGUE_ACC_SEC)
            fatigues.add(fatigueAcc)

            // ③ 第一公里偏快（只对 k = 0 生效）
            val firstBias = if (idx == 0) firstKmBiasSec else 0.0

            val pace = (basePaceSec + wobble + fatigueAcc + firstBias)
                .coerceIn(basePaceSec * MIN_PACE_RATIO, basePaceSec * MAX_PACE_RATIO)
            paces.add(pace)

            // t_k = t_{k-1} + pace_{k-1}（因为 1000 / speed = pace）
            val start = if (idx == 0) 0.0 else starts[idx - 1] + paces[idx - 1]
            starts.add(start)
        }
    }

    private fun fatigueStartKm(): Int =
        kotlin.math.ceil(routeLengthM / 1000.0 * FATIGUE_START_FRACTION).toInt()

    /** 确定性均匀分布 [-1, 1]，按 (seed, k) 生成，两端结果一致。 */
    private fun uniform(k: Int, min: Double, max: Double): Double {
        val h = splitMix64(seed xor (k.toLong() * GOLDEN_GAMMA))
        val unit = (h ushr 11).toDouble() / (1L shl 53).toDouble() // [0, 1)
        return min + (max - min) * unit
    }

    /** 力竭衰减值，与浮动噪声明文隔离（换一份 seed 域）。 */
    private fun uniformFatigue(k: Int, min: Double, max: Double): Double =
        uniform(k + FATIGUE_SALT, min, max)

    private fun splitMix64(z0: Long): Long {
        var z = z0 + GOLDEN_GAMMA
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    companion object {
        const val DEFAULT_VARIATION_SEC = 5.0
        const val DEFAULT_FIRST_KM_BIAS_SEC = -3.0
        const val MAX_FATIGUE_ACC_SEC = 90.0
        /** 力竭起点：总路程的 40%。 */
        const val FATIGUE_START_FRACTION = 0.4
        /** 配速夹逼范围：0.6~2.2 倍基准配速。 */
        const val MIN_PACE_RATIO = 0.6
        const val MAX_PACE_RATIO = 2.2
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        private const val FATIGUE_SALT = 7919
    }
}
