package com.vincenthzr.locationspoofer.utils

import kotlin.math.sin

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
 * lowWobble_k = A × sin(2π·k/P + φ)             // 低频状态起伏，A ≤ 浮动设定
 * fatigueAcc_k = min(secPerKm × 已衰减段数, 90)  // 单调、无随机的体力衰减
 * pace_k      = clamp(basePace + lowWobble_k + fatigueAcc_k + firstKmBias_k)
 * speed_k     = 1000 / pace_k                                // 米/秒（段内匀速）
 * t_k         = t_{k-1} + pace_{k-1}                          // 第 k 公里起始时刻
 * distanceAt(t) = k*1000 + speed_k * (t - t_k)                // 段内匀速
 * elapsedAt(d)  = t_k + (d - k*1000) / speed_k
 * cadence_k   = cadenceBase × (1 - CADENCE_SHARE × 相对降速)
 * stride_k    = speed_k / (cadence_k / 60)
 * stepsAt(d)  = Σ 每段距离 / stride_k                         // 步数 × 步幅 = 距离
 * ```
 *
 * 3.2.1 起把随机性从「疲劳」里彻底剥离：
 * - 疲劳 = 固定斜率、严格单调，用户设「强」就是每公里 +10 秒，可预期；
 * - 浮动 = 低频正弦，天然双侧、有界，不会长时间偏向一侧吞掉疲劳趋势。
 *
 * 全程无随机游走，只有"每公里取一个确定值"，因此两端（App / Hook）用同一
 * seed 计算结果完全一致，不需要跨进程通信。
 */
enum class Fatigue(val level: Int, val secPerKm: Double) {
    /** 不衰减。 */
    OFF(0, 0.0),
    /** 弱：每公里固定 +4 秒。 */
    WEAK(1, 4.0),
    /** 强：每公里固定 +10 秒。 */
    STRONG(2, 10.0);

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

    /** 基准步频：按基准速度算一次，全段共用。 */
    private val baseCadenceSpm: Double =
        (167.0 + (baseSpeedMps - 2.78) * 8.0 + cadenceOffsetSpm).coerceIn(120.0, 210.0)

    /**
     * 第 [k] 公里的步频（步/分钟）。
     *
     * 3.2.1 起降速不再全压在步幅上：按 [CADENCE_SHARE] 把相对降速分摊到步频。
     * 否则目标 App 里步频会是一条二十几分钟纹丝不动的直线（实测恒为 163 步/分），
     * 而真人疲劳时步频与步幅是一起缩的。
     */
    fun cadenceSpm(k: Int): Double {
        val v = speedMps(k)
        val drop = ((baseSpeedMps - v) / baseSpeedMps).coerceIn(-0.2, 0.6)
        return (baseCadenceSpm * (1.0 - CADENCE_SHARE * drop)).coerceIn(120.0, 210.0)
    }

    /**
     * 第 [k] 公里的步幅（米/步）。
     *
     * 永远由速度与步频反解，因此「步数 × 步幅 = 距离」这条强约束不会被单方面改坏。
     */
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
     *
     * 性能：用前缀和缓存。旧实现每次调用都从第 0 段重新循环到当前段（O(k)），
     * 而本方法在 Hook 进程里会被加速度计回调以约 50 Hz 调用，42 km 全马时要循环 42 段，
     * 相当于每秒两千多次 `strideM` 计算。现在改为增量维护 [stepsPrefix]：
     * - 第 k 段跑完整 1000 米后的累计步数存进 `stepsPrefix[k + 1]`；
     * - 查询时只需 `stepsPrefix[k] + 段内剩余距离 / strideM(k)`，O(1)。
     */
    fun stepsAt(distanceM: Double): Double {
        if (distanceM <= 0.0) return 0.0
        val wholeKm = (distanceM / 1000.0).toInt()
        ensureStepsPrefix(wholeKm)
        val km = wholeKm.coerceAtMost(stepsPrefix.size - 1)
        val prefix = stepsPrefix[km]
        val remain = distanceM - km * 1000.0
        if (remain <= 0.0) return prefix
        return prefix + remain / strideM(km)
    }

    // ---------------------------------------------------------------- 内部实现

    /** `stepsPrefix[k]` = 走完前 k 个完整公里（k×1000 米）的累计步数。 */
    private val stepsPrefix = ArrayList<Double>(64).apply { add(0.0) }

    /** 把步数前缀和表补到至少覆盖第 [km] 段。 */
    private fun ensureStepsPrefix(km: Int) {
        while (stepsPrefix.size <= km) {
            val k = stepsPrefix.size - 1
            stepsPrefix.add(stepsPrefix[k] + 1000.0 / strideM(k))
        }
    }

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

            // ① 低频状态起伏：正弦（天然双侧、有界），不再用随机游走
            val wobble = lowWobble(idx)
            wobbles.add(wobble)

            // ② 力竭：固定斜率累加，但**用指数饱和封顶**代替硬截断
            //
            // 旧实现是 (斜率 × 段数).coerceAtMost(90)：跑满 9 段后 Δ 恒为 0，
            // 10 km 之后配速钉死不再变化。真人不会这样，长距离跑者后半程仍在缓慢变慢，
            // 这是很容易被「逐公里配速序列」识别出来的机器特征。
            //
            // 现在改为：线性到 MAX_FATIGUE_ACC_SEC，之后按指数饱和渐近到
            // (MAX + FATIGUE_SOFT_TAIL_SEC)。详见 [fatigueSoftCap]。
            // 前 9 公里与旧实现逐字节一致，用户可预期性完整保留。
            val decayed = if (fatigue == Fatigue.OFF || idx < fatigueStartSegment) {
                0
            } else {
                idx - fatigueStartSegment + 1
            }
            val fatigueAcc = fatigueSoftCap(fatigue.secPerKm * decayed)
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

    /**
     * 疲劳累计值的软封顶：线性到 [MAX_FATIGUE_ACC_SEC]，之后指数饱和渐近。
     *
     * ```
     * f(raw) = raw                                              (raw <= MAX)
     *        = MAX + L × (1 - exp(-(raw - MAX) / L))            (raw >  MAX)
     * ```
     *
     * 三条性质（均有等价 Python 脚本实算验证，见 `SpNext-322-merge/design_softcap.py`）：
     *
     * 1. **导数连续**：`raw = MAX` 处左导数 = 1，右导数 = `L × (1/L) × exp(0)` = 1，
     *    实测相对误差 0.00e+00。不会出现「每公里增量从 10 秒骤降到 3 秒」的折点。
     * 2. **有上界**：`raw → ∞` 时 `f → MAX + L`，即累计衰减趋近 115 秒（L = 25），
     *    极限配速约为基准 +36%，长距离不会掉到荒谬的数值。
     * 3. **始终在变慢**：`f'(raw) = exp(-(raw-MAX)/L) > 0` 恒成立，
     *    所以永远不会出现「配速恒定的平台」——这正是旧实现 `coerceAtMost(90)` 的病根。
     *
     * 线性段（前 9 公里，强档）与旧实现**逐字节相同**，用户对「每公里 +10 秒」的
     * 可预期性完全保留；只有超出的部分从「直接截断」改成「渐近」。
     */
    private fun fatigueSoftCap(rawSec: Double): Double {
        if (rawSec <= 0.0) return 0.0
        if (rawSec <= MAX_FATIGUE_ACC_SEC) return rawSec
        val excess = rawSec - MAX_FATIGUE_ACC_SEC
        return MAX_FATIGUE_ACC_SEC +
            FATIGUE_SOFT_TAIL_SEC * (1.0 - kotlin.math.exp(-excess / FATIGUE_SOFT_TAIL_SEC))
    }

    /**
     * 第 [k] 公里的低频状态起伏（秒），形式为 `A × sin(2π·k/P + φ)`。
     *
     * - **幅度 A**：不超过浮动设定；开了力竭时再压到疲劳斜率的
     *   [WOBBLE_MAX_FATIGUE_RATIO]（0.4）倍以内，保证「设了强衰减就一定看得见变慢」；
     * - **周期 P**：3.5~5.0 公里之间由 seed 决定，模拟状态的自然波动周期；
     * - **相位 φ**：由 seed 决定，因此第一公里也参与浮动（两侧都可能）。
     *
     * 用正弦而不是随机游走，是为了让它有界、零均值、不长时间偏向一侧——
     * 旧实现的正弦化前身（随机游走 + 夹逼）会连续几个公里停在 +15，
     * 把疲劳趋势整段抵消掉，实机上看起来就像「衰减没生效」。
     */
    private fun lowWobble(k: Int): Double {
        if (perKmVariationSec <= 0.0) return 0.0
        val ampLimit = if (fatigue == Fatigue.OFF) {
            perKmVariationSec
        } else {
            minOf(perKmVariationSec, fatigue.secPerKm * WOBBLE_MAX_FATIGUE_RATIO)
        }
        if (ampLimit <= 0.0) return 0.0
        val period = WOBBLE_PERIOD_MIN_KM + WOBBLE_PERIOD_RANGE_KM * uniformUnit(0)
        val phase = TWO_PI * uniformUnit(1)
        return ampLimit * sin(TWO_PI * k / period + phase)
    }

    /** 确定性均匀分布 [0, 1)，按 (seed, 槽位) 生成，两端结果一致。 */
    private fun uniformUnit(slot: Int): Double {
        val h = splitMix64(seed xor (slot.toLong() * GOLDEN_GAMMA))
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

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

        /**
         * 疲劳累计的线性段上限（秒）。累计衰减到这个值之前，每公里严格按档位斜率累加；
         * 超过之后改用指数饱和（见 [fatigueSoftCap]），避免长距离出现恒速平台。
         *
         * 强档 +10 秒/公里 → 第 10 公里处到达本上限。
         */
        const val MAX_FATIGUE_ACC_SEC = 90.0

        /**
         * 软封顶在指数饱和段还能再增加的总量（秒），即渐近上限 = `MAX + 本值`。
         *
         * 取 25 秒的理由（实算数据见 `SpNext-322-merge/design_softcap.py`）：
         * - 10 km 以内与旧版**逐字节一致**，不影响主场景（用户通常跑 5~10 km）；
         * - 11~20 km 区间每公里仍分别 +8.2 / +5.5 / +3.7 / +2.5 / +1.7 … 秒，趋势可见；
         * - 渐近上限 115 秒 → 全马极限配速 7'17"，比基准慢 36%，不至于荒谬；
         * - 旧实现在第 10 km 起 Δ 恒为 0、配速永远钉死在 6'52"，是明显的机器特征。
         */
        const val FATIGUE_SOFT_TAIL_SEC = 25.0
        /** 默认开始掉速的里程（公里）：跑过 2 km 后逐公里变慢 */
        const val DEFAULT_FATIGUE_START_KM = 2.0
        /** 允许的掉速起点范围（公里） */
        const val MIN_FATIGUE_START_KM = 0.5
        const val MAX_FATIGUE_START_KM = 42.0
        /** 配速夹逼范围：0.6~2.2 倍基准配速。 */
        const val MIN_PACE_RATIO = 0.6
        const val MAX_PACE_RATIO = 2.2

        /**
         * 相对降速中分给「步频」的比例（其余给步幅）：0.5 → 步频承担 50%。
         *
         * 旧分支原本是 0.4，按 5'22" → 6'00"（相对降速 10.6%、基准步频 ≈169.6 spm）估算只有
         * −7.2 步/分，擦不到「下降 8~12 步/分」的验收下限。这里取 0.5（≈ −9 步/分），
         * 余下 50% 仍由步幅承担，真人疲劳时两者本来就一起缩。
         */
        const val CADENCE_SHARE = 0.5

        /** 低频浮动幅度上限 = 疲劳斜率 × 该比例，保证疲劳趋势可见。 */
        const val WOBBLE_MAX_FATIGUE_RATIO = 0.4

        /** 低频浮动的周期范围（公里）：3.5~5.0 一个来回。 */
        const val WOBBLE_PERIOD_MIN_KM = 3.5
        const val WOBBLE_PERIOD_RANGE_KM = 1.5

        /** 2π，避免调用处反复声明。 */
        private const val TWO_PI = 6.283185307179586
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
    }
}
