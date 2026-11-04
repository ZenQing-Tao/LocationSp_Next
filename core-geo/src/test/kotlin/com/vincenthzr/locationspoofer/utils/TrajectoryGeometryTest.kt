package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹几何：闭合修正与朝向计算。
 */
class TrajectoryGeometryTest {

    private val start = LatLng(30.0, 120.0)

    private fun p(offsetMeters: Double, t: Double): TrajectoryPoint {
        val q = GeoMath.destination(start, 0.0, offsetMeters)
        return TrajectoryPoint(q.lat, q.lng, t)
    }

    @Test
    fun `closeLoop adds the start point when end is near start`() {
        // 走 100m 后回到起点附近（5m）
        val pts = listOf(p(0.0, 0.0), p(50.0, 10.0), p(100.0, 20.0), p(105.0, 30.0))
        val closed = TrajectoryGeometry.closeLoopIfNear(pts, thresholdM = 15.0)
        assertEquals(pts.size + 1, closed.size)
        assertEquals(pts.first().lat, closed.last().lat, 1e-9)
        assertEquals(pts.first().lng, closed.last().lng, 1e-9)
    }

    @Test
    fun `closeLoop keeps the track when end is far from start`() {
        val pts = listOf(p(0.0, 0.0), p(50.0, 10.0), p(100.0, 20.0))
        val closed = TrajectoryGeometry.closeLoopIfNear(pts, thresholdM = 15.0)
        assertEquals(pts.size, closed.size)
    }

    @Test
    fun `closeLoop is idempotent for an already closed track`() {
        val pts = listOf(p(0.0, 0.0), p(50.0, 10.0), p(0.0, 20.0))
        val closed = TrajectoryGeometry.closeLoopIfNear(pts, thresholdM = 15.0)
        assertEquals(pts.size, closed.size)
    }

    @Test
    fun `closeLoop needs at least three points`() {
        val pts = listOf(p(0.0, 0.0), p(1.0, 1.0))
        assertEquals(2, TrajectoryGeometry.closeLoopIfNear(pts, thresholdM = 15.0).size)
    }

    @Test
    fun `heading falls back to the direction between points`() {
        // 一直向北走：方向应为 0 度
        val pts = listOf(p(0.0, 0.0), p(10.0, 1.0), p(20.0, 2.0), p(30.0, 3.0))
        val heading = TrajectoryGeometry.headingAt(pts, pts.size - 1, gpsBearingDeg = null)
        assertTrue("heading=$heading", heading < 5.0 || heading > 355.0)
    }

    @Test
    fun `heading prefers gps bearing when available`() {
        val pts = listOf(p(0.0, 0.0), p(10.0, 1.0))
        val heading = TrajectoryGeometry.headingAt(pts, 1, gpsBearingDeg = 123.0)
        assertEquals(123.0, heading, 1e-9)
    }

    @Test
    fun `arrow points are ahead of the center along the heading`() {
        val (left, right) = TrajectoryGeometry.arrowPoints(30.0, 120.0, 0.0, lengthM = 6.0, spreadDeg = 40.0)
        assertEquals(2, left.size)
        assertEquals(2, right.size)
        // 箭头顶点就是当前位置
        assertEquals(30.0, left[0].first, 1e-9)
        assertEquals(120.0, left[0].second, 1e-9)
        // 向北 6m（±20° 张角）的横向分量很小，纵向接近 6m
        val dLeft = GeoMath.distance(LatLng(left[0].first, left[0].second), LatLng(left[1].first, left[1].second))
        assertEquals(6.0, dLeft, 0.2)
        // 左右两支对称
        val dRight = GeoMath.distance(LatLng(right[0].first, right[0].second), LatLng(right[1].first, right[1].second))
        assertEquals(dLeft, dRight, 1e-6)
    }
}
