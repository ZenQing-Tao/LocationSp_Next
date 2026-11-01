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
        routeLength: Double = 0.0,
        seed: Long = 12345L
    ) = PaceProfile(
        baseSpeedMps = baseSpeed,
        perKmVariationSec = variation,
        fatigue = fatigue,
        firstKmBiasSec = firstBias,
        routeLengthM = routeLength,
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
        val p = profile(variation = 5.0, fatigue = Fatigue.WEAK, firstBias = -3.0, routeLength = 10_000.0)
        for (k in 0..10) {
            val t = p.elapsedAt(k * 1000.0)
            assertEquals(k * 1000.0, p.distanceAt(t), 1e-6)
        }
    }

    @Test
    fun `segment duration equals its pace`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, routeLength = 10_000.0)
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
    fun `weak fatigue decays each kilometer within 3 to 8 seconds`() {
        val p = profile(fatigue = Fatigue.WEAK, routeLength = 10_000.0)
        val startKm = kotlin.math.ceil(10.0 * PaceProfile.FATIGUE_START_FRACTION).toInt()
        for (k in startKm + 1..9) {
            val decay = p.paceSecPerKm(k) - p.paceSecPerKm(k - 1)
            assertTrue("k=$k decay=$decay", decay in 3.0 - 1e-6..8.0 + 1e-6)
        }
        // 累计衰减不超过上限
        assertTrue(p.paceSecPerKm(9) - basePace <= PaceProfile.MAX_FATIGUE_ACC_SEC + 1e-6)
    }

    @Test
    fun `strong fatigue decays each kilometer within 5 to 15 seconds`() {
        val p = profile(fatigue = Fatigue.STRONG, routeLength = 10_000.0)
        val startKm = kotlin.math.ceil(10.0 * PaceProfile.FATIGUE_START_FRACTION).toInt()
        for (k in startKm + 1..9) {
            val decay = p.paceSecPerKm(k) - p.paceSecPerKm(k - 1)
            assertTrue("k=$k decay=$decay", decay in 5.0 - 1e-6..15.0 + 1e-6)
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
        val p = profile(variation = 10.0, fatigue = Fatigue.WEAK, firstBias = -3.0, routeLength = 10_000.0)
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
        val p = profile(variation = 10.0, fatigue = Fatigue.WEAK, firstBias = -3.0, routeLength = 300.0)
        assertEquals(0.0, p.distanceAt(0.0), 0.0)
        assertEquals(0.0, p.stepsAt(0.0), 0.0)
        assertEquals(0.0, p.elapsedAt(0.0), 0.0)
        assertTrue(p.distanceAt(p.elapsedAt(300.0)) - 300.0 < 1e-6)
        assertTrue(p.distanceAt(p.elapsedAt(1500.0)) - 1500.0 < 1e-6)
    }

    @Test
    fun `resume from offset keeps the pace curve continuous`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, routeLength = 5000.0)
        val continuous = p.distanceAt(p.elapsedAt(1200.0) + 90.0)
        val resumed = p.distanceAfter(1200.0, 90.0)
        assertEquals(continuous, resumed, 1e-9)
    }
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
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, routeLength = 10_000.0)
        // 走完 2 km 所需时间 = 前两段的配速之和
        val twoKmSeconds = p.paceSecPerKm(0) + p.paceSecPerKm(1)
        // 用这段时间跑，必须正好落在 2000 米
        assertEquals(2000.0, p.distanceAfter(0.0, twoKmSeconds), 1e-6)
    }

    @Test
    fun `distance after two kilometers matches the sum of segment paces`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, routeLength = 10_000.0)
        val fiveKmSeconds = (0..4).sumOf { p.paceSecPerKm(it) }
        assertEquals(5000.0, p.distanceAfter(0.0, fiveKmSeconds), 1e-6)
    }

    @Test
    fun `later kilometers use their own pace not the first one`() {
        val p = profile(variation = 10.0, fatigue = Fatigue.STRONG, firstBias = -3.0, routeLength = 10_000.0)
        // 第 2 公里的配速必须与第 1 公里不同（否则说明整条曲线退化成单一速度）
        assertNotEquals(p.paceSecPerKm(0), p.paceSecPerKm(1), 1e-9)
        // 且随着力竭累积，第 6 公里必须比第 1 公里慢
        assertTrue(p.paceSecPerKm(6) > p.paceSecPerKm(0))
    }

    @Test
    fun `fatigue only starts after the configured fraction of the route`() {
        val p = profile(fatigue = Fatigue.WEAK, routeLength = 10_000.0)
        val startKm = kotlin.math.ceil(10.0 * PaceProfile.FATIGUE_START_FRACTION).toInt()
        // 起点之前（含）配速保持基准值
        for (k in 0..startKm) {
            assertEquals(basePace, p.paceSecPerKm(k), 1e-9)
        }
        // 起点之后开始变慢
        assertTrue(p.paceSecPerKm(startKm + 1) > basePace)
    }

    @Test
    fun `distance and elapsed time remain mutual inverses over a long route`() {
        val p = profile(variation = 15.0, fatigue = Fatigue.STRONG, firstBias = -3.0, routeLength = 20_000.0)
        var d = 0.0
        while (d <= 20_000.0) {
            assertEquals(d, p.distanceAt(p.elapsedAt(d)), 1e-6)
            d += 250.0
        }
    }

    @Test
    fun `distanceAfter is equivalent to distanceAt shifted by the offset`() {
        val p = profile(variation = 15.0, fatigue = Fatigue.STRONG, firstBias = -3.0, routeLength = 20_000.0)
        var from = 0.0
        while (from <= 15_000.0) {
            val direct = p.distanceAt(p.elapsedAt(from) + 90.0)
            assertEquals(direct, p.distanceAfter(from, 90.0), 1e-9)
            from += 500.0
        }
    }

    @Test
    fun `fromConfig produces the same curve as the explicit constructor`() {
        val explicit = profile(variation = 10.0, fatigue = Fatigue.WEAK, firstBias = -3.0, routeLength = 10_000.0, seed = 4242L)
        val viaFactory = PaceProfile.fromConfig(
            baseSpeedMps = baseSpeed,
            paceVariationSec = 10.0,
            fatigueLevel = Fatigue.WEAK.level,
            firstKmBiasSec = -3.0,
            routeLengthM = 10_000.0,
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
