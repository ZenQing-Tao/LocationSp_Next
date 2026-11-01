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
 * fatigue_k = 0                                        (k < 起始段)
 *           = min(sum(decay_i), 90s)                    (k >= 起始段)
 * speed_k = 1000 / pace_k                                          // 米/秒
 * t_k     = t_{k-1} + pace_{k-1}                                   // 第 k 公里起始时刻
 * distanceAt(t) = k*1000 + speed_k * (t - t_k)                     // 段内匀速
 * elapsedAt(d)  = t_k + (d - k*1000) / speed_k
 * stepsAt(d)    = Σ 每段距离 / stride_k,  stride_k = speed_k / (cadence_k/60)
 * ```
 * 全程无随机游走，只有"每公里取一个确定值"，因此两端（App / Hook）用同一
 * seed 计算结果完全一致，不需要跨进程通信。
 */
enum class Fatigue(val level: Int, val minSecPerKm: Double, val maxSecPerKm: Double) {
    /** 不衰减。 */
    OFF(0, 0.0, 0.0),

    /** 弱：每公里掉 3~8 秒。 */
    WEAK(1, 3.0, 8.0),

    /** 强：每公里掉 5~15 秒。 */
    STRONG(2, 5.0, 15.0);

    companion object {
        /** fatigue_level：0=OFF，1=WEAK，2=STRONG，其他值落到 OFF。 */
        fun fromLevel(level: Int): Fatigue = entries.firstOrNull { it.level == level } ?: OFF
    }
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
    /**
     * 开始掉速的里程（公里，绝对量）。跑过这个公里数后，接下来完整的每公里开始衰减。
     *
     * 旧实现用的是“路线总长的 40%”，但路线模式是循环/折返的、没有终点，
     * “全程”在循环模式下没有物理意义（同样开“强”，换条路线衰减起点就变）。
     * 真实跑步的疲劳取决于已跑里程，因此改为绝对公里数。
     */
    private val fatigueStartKm: Double = DEFAULT_FATIGUE_START_KM,
    /** 随机种子，调用方统一传路线开始时间戳。 */
    private val seed: Long = 0L,
    /** 步频基准偏移（0 表示使用速度线性映射）。 */
    private val cadenceOffsetSpm: Double = 0.0
) {

    init {
        require(baseSpeedMps > 0.0) { "baseSpeedMps must be > 0" }
    }

    private val basePaceSec: Double = 1000.0 / baseSpeedMps

    /** 第一个需要衰减的公里段（idx >= 此值）；N=2.0 时第 3 公里（idx=2）开始掉速 */
    private val fatigueStartSegment: Int =
        kotlin.math.ceil(fatigueStartKm).toInt().coerceAtLeast(0)

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

    /**
     * 找出 [elapsedSec] 落在第几公里（k 从 0 开始）。
     *
     * 关键点：公里表必须能按需向后生长。旧实现把 `k` 初始化为 `paces.size - 1`，
     * 而循环条件里带 `k == paces.size - 1` 的短路分支，导致条件恒真、循环体永不执行、
     * `ensure(k + 1)` 永不调用——公里表永远停在 1 段，于是第 1 公里之后的
     * 每公里浮动、力竭衰减、首公里偏快全部不生效（表现是“设了也没用”）。
     *
     * 现在改为：先按需把表补到覆盖 elapsedSec，再用二分定位落在哪一段。
     */
    private fun kmIndexAt(elapsedSec: Double): Int {
        ensure(0) // 保证 starts 至少有第 0 段起点，避免空表越界
        // 表尚未覆盖 elapsedSec 时向后生长：第 k 段的结束时刻是 starts[k] + paces[k]
        while (true) {
            val last = paces.size - 1
            if (elapsedSec < starts[last] + paces[last]) break
            ensure(last + 1)
        }
        // 二分：找到最大的 k 使 starts[k] <= elapsedSec
        var lo = 0
        var hi = paces.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= elapsedSec) lo = mid else hi = mid - 1
        }
        return lo
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
            val decay = if (fatigue == Fatigue.OFF || idx < fatigueStartSegment) {
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
        /**
         * 从下发配置原始值构造，App 与 Hook 两端都走这里，
         * 保证同一组参数得到完全一致的曲线。
         * 旧版 App 不传新字段时传默认值（0/OFF/0/0），退化为严格匀速。
         */
        fun fromConfig(
            baseSpeedMps: Double,
            paceVariationSec: Double = 0.0,
            fatigueLevel: Int = 0,
            firstKmBiasSec: Double = 0.0,
            fatigueStartKm: Double = DEFAULT_FATIGUE_START_KM,
            seed: Long = 0L
        ): PaceProfile = PaceProfile(
            baseSpeedMps = baseSpeedMps,
            perKmVariationSec = paceVariationSec,
            fatigue = Fatigue.fromLevel(fatigueLevel),
            firstKmBiasSec = firstKmBiasSec,
            fatigueStartKm = fatigueStartKm,
            seed = seed
        )

        const val DEFAULT_VARIATION_SEC = 5.0
        const val DEFAULT_FIRST_KM_BIAS_SEC = -3.0
        const val MAX_FATIGUE_ACC_SEC = 90.0
        /** 默认开始掉速的里程（公里）：跑过 2 km 后逐公里变慢 */
        const val DEFAULT_FATIGUE_START_KM = 2.0
        /** 允许的掉速起点范围（公里） */
        const val MIN_FATIGUE_START_KM = 0.5
        const val MAX_FATIGUE_START_KM = 42.0
        /** 配速夹逼范围：0.6~2.2 倍基准配速。 */
        const val MIN_PACE_RATIO = 0.6
        const val MAX_PACE_RATIO = 2.2
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        private const val FATIGUE_SALT = 7919
    }
}
