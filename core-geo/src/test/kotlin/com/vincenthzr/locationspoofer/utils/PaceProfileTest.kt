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