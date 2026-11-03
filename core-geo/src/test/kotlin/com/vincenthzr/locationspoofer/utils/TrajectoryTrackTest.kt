package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹回放的数学不变量。
 *
 * 验收重点：
 * 1) 位置按时间轴插值，且段内线性；
 * 2) 速度由相邻点距离/时间差反推，与位置自洽；
 * 3) 过滤规则能丢掉跳点、冗余点，但保留转角点。
 */
class TrajectoryTrackTest {

    private val start = LatLng(30.0, 120.0)

    /** 正北方向每秒 2 米，共 10 秒 */
    private fun straightTrack(): TrajectoryTrack {
        val pts = (0..10).map { s ->
            val p = GeoMath.destination(start, 0.0, s * 2.0)
            TrajectoryPoint(p.lat, p.lng, s.toDouble())
        }
        return TrajectoryTrack(pts)
    }

    @Test
    fun `duration and distance are derived from points`() {
        val t = straightTrack()
        assertEquals(10.0, t.durationSec, 1e-9)
        assertEquals(20.0, t.totalDistanceM, 0.05)
    }

    @Test
    fun `position interpolates linearly inside a segment`() {
        val t = straightTrack()
        // t=5s 应在 10 米处；t=5.5s 应在 11 米处
        assertEquals(10.0, GeoMath.distance(start, t.positionAt(5.0)), 0.02)
        assertEquals(11.0, GeoMath.distance(start, t.positionAt(5.5)), 0.02)
    }

    @Test
    fun `position clamps outside the track`() {
        val t = straightTrack()
        assertEquals(0.0, GeoMath.distance(start, t.positionAt(-5.0)), 0.02)
        assertEquals(20.0, GeoMath.distance(start, t.positionAt(999.0)), 0.05)
    }

    @Test
    fun `speed equals distance over time of the segment`() {
        val t = straightTrack()
        // 匀速 2 m/s
        for (s in listOf(0.5, 3.3, 7.9)) {
            assertEquals(2.0, t.speedAt(s), 1e-6)
        }
    }

    @Test
    fun `speed follows the varying pace of the recording`() {
        // 前 5 秒每秒 1 米，后 5 秒每秒 4 米
        val pts = ArrayList<TrajectoryPoint>()
        var d = 0.0
        for (s in 0..10) {
            if (s > 0) d += if (s <= 5) 1.0 else 4.0
            val p = GeoMath.destination(start, 0.0, d)
            pts.add(TrajectoryPoint(p.lat, p.lng, s.toDouble()))
        }
        val t = TrajectoryTrack(pts)
        assertEquals(1.0, t.speedAt(2.0), 1e-6)
        assertEquals(4.0, t.speedAt(7.0), 1e-6)
    }

    @Test
    fun `timeScale maps track duration onto the target duration`() {
        val t = straightTrack() // 原始 10 秒
        assertEquals(2.0, t.timeScaleFor(20.0), 1e-9)
        assertEquals(0.5, t.timeScaleFor(5.0), 1e-9)
    }

    @Test
    fun `filter drops points that jump faster than the speed limit`() {
        val raw = listOf(
            TrajectoryPoint(start.lat, start.lng, 0.0),
            // 1 秒内跑 100 米 → 100 m/s，远超上限，应丢弃
            TrajectoryPoint(GeoMath.destination(start, 0.0, 100.0).lat, GeoMath.destination(start, 0.0, 100.0).lng, 1.0),
            TrajectoryPoint(GeoMath.destination(start, 0.0, 6.0).lat, GeoMath.destination(start, 0.0, 6.0).lng, 3.0)
        )
        val out = TrajectoryTrack.filter(raw)
        assertEquals(2, out.size)
        assertEquals(6.0, GeoMath.distance(start, LatLng(out[1].lat, out[1].lng)), 0.05)
    }

    @Test
    fun `filter drops redundant close points on a straight line`() {
        // 每米一个点，最小距离 3 米 → 应被压成少量点
        val raw = (0..30).map { m ->
            val p = GeoMath.destination(start, 0.0, m.toDouble())
            TrajectoryPoint(p.lat, p.lng, m.toDouble())
        }
        val out = TrajectoryTrack.filter(raw)
        assertTrue("out=${out.size}", out.size <= 12)
        assertTrue("out=${out.size}", out.size >= 2)
    }

    @Test
    fun `filter keeps the turning point even when distance is small`() {
        // 直行 10 米后急转 90 度走 3 米：转角点必须保留
        val a = GeoMath.destination(start, 0.0, 10.0)
        val turn = GeoMath.destination(a, 90.0, 1.0) // 离上一点很近，但方向变了
        val raw = listOf(
            TrajectoryPoint(start.lat, start.lng, 0.0),
            TrajectoryPoint(a.lat, a.lng, 5.0),
            TrajectoryPoint(turn.lat, turn.lng, 6.0)
        )
        val out = TrajectoryTrack.filter(raw)
        assertEquals(3, out.size)
    }

    @Test
    fun `filter respects the accuracy callback`() {
        val pts = (0..6).map { s ->
            val p = GeoMath.destination(start, 0.0, s * 10.0)
            TrajectoryPoint(p.lat, p.lng, s.toDouble())
        }
        // 第 3 个点精度很差（80m），应被丢弃
        val bad = setOf(3)
        val out = TrajectoryTrack.filter(
            raw = pts,
            accuracyOf = { p -> if (pts.indexOf(p) in bad) 80f else 5f }
        )
        assertEquals(6, out.size)
        assertTrue(out.none { it.tOffsetSec == 3.0 })
    }

    @Test
    fun `identical timestamps do not crash`() {
        val pts = listOf(
            TrajectoryPoint(start.lat, start.lng, 0.0),
            TrajectoryPoint(start.lat, start.lng, 0.0),
            TrajectoryPoint(GeoMath.destination(start, 0.0, 5.0).lat, GeoMath.destination(start, 0.0, 5.0).lng, 2.0)
        )
        val t = TrajectoryTrack(pts)
        // 同时刻段速度退化为 0（无位移）或下一段，不应抛异常
        assertTrue(t.speedAt(0.0) >= 0.0)
        assertEquals(2.0, t.durationSec, 1e-9)
    }
}
