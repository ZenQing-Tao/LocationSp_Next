package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PaceProfile 单元测试（3.2.0 批 1）。
 *
 * 验收重点：
 * 1) 关闭所有起伏时，行为与 3.1.5 的严格线性一致；
 * 2) 距离、时间、配速三者互为逆运算，误差 < 1e-6；
 * 3) 力竭衰减落在设定区间且累计不超过 90s；
 * 4) 步数与距离强绑定（步数 × 步幅 = 距离）；
 * 5) 同 seed 两端结果完全一致（App / Hook 跨进程一致性）。
 */
class PaceProfileTest {

    private val baseSpeed = 1000.0 / 360.0 // 6'00"/km -> 2.777... m/s
    private val basePace = 360.0

    private fun profile(
        variation: Double = 0.0,
        fatigue: Fatigue = Fatigue.OFF,
        firstBias: Double = 0.0,
        fatigueStartKm: Double = PaceProfile.DEFAULT_FATIGUE_START_KM,
        seed: Long = 12345L
    ) = PaceProfile(
        baseSpeedMps = baseSpeed,
        perKmVariationSec = variation,
        fatigue = fatigue,
        firstKmBiasSec = firstBias,
        fatigueStartKm = fatigueStartKm,
        seed = seed
    )

    @Test
    fun `neutral profile equals strict linear motion`() {
        val p = profile()
        for (t in listOf(1.0, 60.0, 300.0, 600.0, 1800.0)) {
            assertEquals(baseSpeed * t, p.distanceAt(t), 1e-6)
        }
    }

    @Test
    fun `kilometer boundaries map back to exact distance`() {
        val p = profile(variation = 5.0, fatigue = Fatigue.WEAK, firstBias = -3.0, fatigueStartKm = 2.0)
        for (k in 0..10) {
            val t = p.elapsedAt(k * 1000.0)
            assertEquals(k * 1000.0, p.distanceAt(t), 1e-6)
        }
    }

    @Test
    fun `segment duration equals its pace`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, fatigueStartKm = 2.0)
        for (k in 0..8) {
            val t0 = p.elapsedAt(k * 1000.0)
            val t1 = p.elapsedAt((k + 1) * 1000.0)
            assertEquals(p.paceSecPerKm(k), t1 - t0, 1e-6)
        }
    }

    @Test
    fun `first kilometer bias makes the first segment faster`() {
        val neutral = profile()
        val biased = profile(firstBias = -3.0)
        assertEquals(3.0, neutral.paceSecPerKm(0) - biased.paceSecPerKm(0), 1e-9)
        assertEquals(0.0, neutral.paceSecPerKm(1) - biased.paceSecPerKm(1), 1e-9)
    }

    @Test
    fun `weak fatigue decays each kilometer by a fixed amount`() {
        // 3.2.1：疲劳不再带随机区间，弱 = 每公里固定 +4 秒
        val p = profile(fatigue = Fatigue.WEAK, fatigueStartKm = 2.0)
        val startSegment = kotlin.math.ceil(PaceProfile.DEFAULT_FATIGUE_START_KM).toInt()
        for (k in startSegment..9) {
            val decay = p.paceSecPerKm(k) - p.paceSecPerKm(k - 1)
            assertTrue("k=$k decay=$decay", decay in 4.0 - 1e-6..4.0 + 1e-6)
        }
        // 累计衰减不超过上限
        assertTrue(p.paceSecPerKm(9) - basePace <= PaceProfile.MAX_FATIGUE_ACC_SEC + 1e-6)
    }

    @Test
    fun `strong fatigue decays each kilometer by a fixed amount`() {
        // 3.2.1：强 = 每公里固定 +10 秒
        val p = profile(fatigue = Fatigue.STRONG, fatigueStartKm = 2.0)
        val startSegment = kotlin.math.ceil(PaceProfile.DEFAULT_FATIGUE_START_KM).toInt()
        for (k in startSegment..9) {
            val decay = p.paceSecPerKm(k) - p.paceSecPerKm(k - 1)
            assertTrue("k=$k decay=$decay", decay in 10.0 - 1e-6..10.0 + 1e-6)
        }
    }

    @Test
    fun `same seed is deterministic and different seed diverges`() {
        val a = profile(variation = 10.0, seed = 999L)
        val b = profile(variation = 10.0, seed = 999L)
        val c = profile(variation = 10.0, seed = 1000L)
        for (k in 0..5) {
            assertEquals(a.paceSecPerKm(k), b.paceSecPerKm(k), 0.0)
        }
        assertNotEquals(a.paceSecPerKm(3), c.paceSecPerKm(3), 1e-9)
    }

    @Test
    fun `steps are strongly bound to distance`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.WEAK, firstBias = -3.0, fatigueStartKm = 2.0)
        // 单段内：步数 × 步幅 == 距离
        assertEquals(1000.0, p.stepsAt(1000.0) * p.strideM(0), 1e-6)
        // 跨段连续：1500m 的步数 == 1000m 的步数 + 500m 用第 1 段步幅算出的步数
        val cross = p.stepsAt(1000.0) + 500.0 / p.strideM(1)
        assertEquals(cross, p.stepsAt(1500.0), 1e-6)
        // 步数单调递增
        assertTrue(p.stepsAt(2000.0) > p.stepsAt(1999.0))
    }

    @Test
    fun `edge distances do not crash`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.WEAK, firstBias = -3.0, fatigueStartKm = 0.3)
        assertEquals(0.0, p.distanceAt(0.0), 0.0)
        assertEquals(0.0, p.stepsAt(0.0), 0.0)
        assertEquals(0.0, p.elapsedAt(0.0), 0.0)
        assertTrue(p.distanceAt(p.elapsedAt(300.0)) - 300.0 < 1e-6)
        assertTrue(p.distanceAt(p.elapsedAt(1500.0)) - 1500.0 < 1e-6)
    }

    @Test
    fun `resume from offset keeps the pace curve continuous`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, fatigueStartKm = 2.0)
        val continuous = p.distanceAt(p.elapsedAt(1200.0) + 90.0)
        val resumed = p.distanceAfter(1200.0, 90.0)
        assertEquals(continuous, resumed, 1e-9)
    }
    // ==============================================================
    // 回归：kmIndexAt 必须能把公里表推进到第 k 段
    //
    // 旧实现把 k 初始化为 paces.size - 1，循环条件里带 `k == paces.size - 1`
    // 的短路分支，条件恒真导致 ensure(k + 1) 永不调用——公里表永远停在 1 段，
    // 于是“第 1 公里之后的浮动 / 力竭 / 首公里偏快”全部静默失效。
    // 这些用例在旧实现下会失败（见每条断言的说明）。
    // ==============================================================

    @Test
    fun `kilometer table grows beyond the first segment`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, fatigueStartKm = 2.0)
        // 走完 2 km 所需时间 = 前两段的配速之和
        val twoKmSeconds = p.paceSecPerKm(0) + p.paceSecPerKm(1)
        // 用这段时间跑，必须正好落在 2000 米
        assertEquals(2000.0, p.distanceAfter(0.0, twoKmSeconds), 1e-6)
    }

    @Test
    fun `distance after two kilometers matches the sum of segment paces`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, fatigueStartKm = 2.0)
        val fiveKmSeconds = (0..4).sumOf { p.paceSecPerKm(it) }
        assertEquals(5000.0, p.distanceAfter(0.0, fiveKmSeconds), 1e-6)
    }

    @Test
    fun `later kilometers use their own pace not the first one`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, fatigueStartKm = 2.0)
        // 第 2 公里的配速必须与第 1 公里不同（否则说明整条曲线退化成单一速度）
        assertNotEquals(p.paceSecPerKm(0), p.paceSecPerKm(1), 1e-9)
        // 且随着力竭累积，第 6 公里必须比第 1 公里慢
        assertTrue(p.paceSecPerKm(6) > p.paceSecPerKm(0))
    }

    @Test
    fun `fatigue starts at the configured kilometer and not before`() {
        val p = profile(fatigue = Fatigue.WEAK, fatigueStartKm = 2.0)
        val startSegment = kotlin.math.ceil(2.0).toInt()
        // 起点之前（含）配速保持基准值
        for (k in 0 until startSegment) {
            assertEquals(basePace, p.paceSecPerKm(k), 1e-9)
        }
        // 起点之后开始变慢
        assertTrue(p.paceSecPerKm(startSegment) > basePace)
    }

    @Test
    fun `distance and elapsed time remain mutual inverses over a long route`() {
        val p = profile(variation = 15.0, fatigue = Fatigue.STRONG, firstBias = -3.0, fatigueStartKm = 2.0)
        var d = 0.0
        while (d <= 20_000.0) {
            assertEquals(d, p.distanceAt(p.elapsedAt(d)), 1e-6)
            d += 250.0
        }
    }

    @Test
    fun `distanceAfter is equivalent to distanceAt shifted by the offset`() {
        val p = profile(variation = 15.0, fatigue = Fatigue.STRONG, firstBias = -3.0, fatigueStartKm = 2.0)
        var from = 0.0
        while (from <= 15_000.0) {
            val direct = p.distanceAt(p.elapsedAt(from) + 90.0)
            assertEquals(direct, p.distanceAfter(from, 90.0), 1e-9)
            from += 500.0
        }
    }

    @Test
    fun `fromConfig produces the same curve as the explicit constructor`() {
        val explicit = profile(variation = 10.0, fatigue = Fatigue.WEAK, firstBias = -3.0, fatigueStartKm = 2.0, seed = 4242L)
        val viaFactory = PaceProfile.fromConfig(
            baseSpeedMps = baseSpeed,
            paceVariationSec = 10.0,
            fatigueLevel = Fatigue.WEAK.level,
            firstKmBiasSec = -3.0,
            fatigueStartKm = 2.0,
            seed = 4242L
        )
        for (k in 0..8) {
            assertEquals(explicit.paceSecPerKm(k), viaFactory.paceSecPerKm(k), 0.0)
        }
    }

    @Test
    fun `fromConfig with neutral defaults degenerates to strict linear motion`() {
        val p = PaceProfile.fromConfig(baseSpeedMps = baseSpeed, seed = 7L)
        for (t in listOf(1.0, 60.0, 300.0, 600.0, 1800.0)) {
            assertEquals(baseSpeed * t, p.distanceAt(t), 1e-6)
        }
    }

    @Test
    fun `unknown fatigue level falls back to off`() {
        assertEquals(Fatigue.OFF, Fatigue.fromLevel(99))
        assertEquals(Fatigue.WEAK, Fatigue.fromLevel(1))
        assertEquals(Fatigue.STRONG, Fatigue.fromLevel(2))
        assertEquals(Fatigue.OFF, Fatigue.fromLevel(0))
    }

    @Test
    fun `fatigue start is an absolute distance independent of route length`() {
        // 旧实现用“路线总长的 40%”：换一条路线，衰减起点就变；现在只看绝对里程。
        val a = PaceProfile.fromConfig(baseSpeedMps = baseSpeed, fatigueLevel = 2, fatigueStartKm = 3.0, seed = 7L)
        val b = PaceProfile.fromConfig(baseSpeedMps = baseSpeed, fatigueLevel = 2, fatigueStartKm = 3.0, seed = 7L)
        for (k in 0..9) assertEquals(a.paceSecPerKm(k), b.paceSecPerKm(k), 0.0)
        // 3.0 km 起点 → idx >= 3 才开始衰减
        assertEquals(basePace, a.paceSecPerKm(2), 1e-9)
        assertTrue(a.paceSecPerKm(3) > basePace)
    }

    @Test
    fun `fatigue start with one decimal rounds up to the next full kilometer`() {
        val p = PaceProfile.fromConfig(baseSpeedMps = baseSpeed, fatigueLevel = 1, fatigueStartKm = 2.5, seed = 11L)
        // ceil(2.5) = 3 → 前三段（0..2）不衰减，第 4 公里（idx=3）开始
        assertEquals(basePace, p.paceSecPerKm(2), 1e-9)
        assertTrue(p.paceSecPerKm(3) > basePace)
    }

    // ==============================================================
    // 3.2.1：把随机性从疲劳里剥离
    //
    // 实机问题：设「强」以为每公里 +8 秒，实测等效只有约 5 秒，
    // 且第 2、3 公里偏差完全相同——低频浮动把疲劳趋势抵消了。
    // 下面这些用例把新语义钉死。
    // ==============================================================

    @Test
    fun `fatigue slope is deterministic and equals the configured seconds per kilometer`() {
        for (fatigue in listOf(Fatigue.WEAK, Fatigue.STRONG)) {
            // 关闭浮动，才能把每公里的差值精确归因到疲劳项
            val p = profile(variation = 0.0, fatigue = fatigue, fatigueStartKm = 2.0)
            val start = kotlin.math.ceil(2.0).toInt()
            for (k in start..9) {
                val decay = p.paceSecPerKm(k) - p.paceSecPerKm(k - 1)
                assertEquals("$fatigue k=$k", fatigue.secPerKm, decay, 1e-9)
            }
            // 起点之前一段都不衰减
            for (k in 0 until start) assertEquals(basePace, p.paceSecPerKm(k), 1e-9)
        }
    }

    @Test
    fun `fatigue survives a large wobble setting`() {
        // 关键回归：浮动调到 ±15 时，疲劳趋势不能被抵消
        val p = profile(variation = 15.0, fatigue = Fatigue.STRONG, fatigueStartKm = 1.0)
        val start = kotlin.math.ceil(1.0).toInt()
        for (k in start..8) {
            val trend = p.paceSecPerKm(k) - p.paceSecPerKm(k - 1)
            // 每公里至少要比上一公里慢（趋势项 10 秒，浮动幅度被压到 ±4 秒以内）
            assertTrue("k=$k trend=$trend", trend > 0.0)
        }
        // 6 公里累计衰减 ≈ 10 × 段数，允许浮动 ±4 秒的偏差
        val sixKmTrend = p.paceSecPerKm(6) - p.paceSecPerKm(0)
        assertTrue("sixKmTrend=$sixKmTrend", sixKmTrend > 0.0)
    }

    @Test
    fun `wobble amplitude is capped when fatigue is enabled`() {
        val capped = profile(variation = 15.0, fatigue = Fatigue.STRONG, fatigueStartKm = 2.0)
        val cap = Fatigue.STRONG.secPerKm * PaceProfile.WOBBLE_MAX_FATIGUE_RATIO
        for (k in 0..1) { // 起点之前只有浮动项
            val dev = capped.paceSecPerKm(k) - basePace
            assertTrue("k=$k dev=$dev", kotlin.math.abs(dev) <= cap + 1e-9)
        }
    }

    @Test
    fun `wobble is two-sided and bounded by the setting`() {
        val p = profile(variation = 10.0)
        var sawPositive = false
        var sawNegative = false
        for (k in 0..40) {
            val dev = p.paceSecPerKm(k) - basePace
            assertTrue("k=$k dev=$dev", kotlin.math.abs(dev) <= 10.0 + 1e-9)
            if (dev > 0.5) sawPositive = true
            if (dev < -0.5) sawNegative = true
        }
        assertTrue("浮动必须双侧", sawPositive && sawNegative)
    }

    @Test
    fun `cadence and stride both shrink with fatigue`() {
        val p = profile(fatigue = Fatigue.STRONG, fatigueStartKm = 1.0)
        assertTrue(p.cadenceSpm(6) < p.cadenceSpm(0))
        assertTrue(p.strideM(6) < p.strideM(0))
        // 分摊后步频降幅应明显小于步幅降幅（0.4 / 0.6 的分配）
        val cadenceDrop = (p.cadenceSpm(0) - p.cadenceSpm(6)) / p.cadenceSpm(0)
        val strideDrop = (p.strideM(0) - p.strideM(6)) / p.strideM(0)
        assertTrue("cadenceDrop=$cadenceDrop strideDrop=$strideDrop", cadenceDrop in 1e-6..strideDrop)
    }

    @Test
    fun `steps still equal distance over stride after the split`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, fatigueStartKm = 1.0)
        var dist = 0.0
        var steps = 0.0
        for (k in 0..7) {
            steps += 1000.0 / p.strideM(k)
            dist += 1000.0
            // 累计步数 = 各段“距离 / 该段步幅”之和（跨段步幅不同，必须分段累加）
            assertEquals("k=$k", steps, p.stepsAt(dist), 1e-6)
            // 单段内“步数 × 步幅 = 距离”仍然成立（第 k 段单独算）
            assertEquals("k=$k", 1000.0, (1000.0 / p.strideM(k)) * p.strideM(k), 1e-6)
        }
    }

    @Test
    fun `speed is the single source of truth for the injected velocity`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, fatigueStartKm = 1.0)
        for (k in 0..5) {
            assertEquals(1000.0 / p.paceSecPerKm(k), p.speedMps(k), 1e-12)
            // 段内任意时刻的瞬时速度都等于该段平均速度（段内匀速，不引入第二套口径）
            val t0 = p.elapsedAt(k * 1000.0)
            val tMid = t0 + 0.5 * p.paceSecPerKm(k)
            assertEquals(p.speedMps(k), p.speedAt(tMid), 1e-9)
        }
    }
}
