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

    // ==============================================================
    // 3.2.1：箭头大小固定，不随地图缩放忽大忽小
    //
    // 实机问题：刚开录时地图被 fitBounds 拉得很近，2 米的箭头只有几个像素（几乎看不见）；
    // 走远后视野拉大，同一段 2 米又变得巨大，把轨迹整段盖住。
    // ==============================================================

    @Test
    fun `meters per pixel halves with every zoom level`() {
        val lat = 30.0
        val a = TrajectoryGeometry.metersPerPixel(16.0, lat)
        val b = TrajectoryGeometry.metersPerPixel(17.0, lat)
        assertEquals(a / 2.0, b, 1e-6)
    }

    @Test
    fun `arrow length keeps the same screen size at any zoom`() {
        val lat = 30.0
        val pixels = 34.0
        // 缩放级别越高，同样像素数对应的地面距离越短
        val near = TrajectoryGeometry.arrowLengthForPixels(18.0, lat, pixels)
        val far = TrajectoryGeometry.arrowLengthForPixels(15.0, lat, pixels)
        assertTrue("near=$near far=$far", near < far)
        // 换算回像素后应当一致（这是「屏幕大小恒定」的直接定义）
        val pxNear = near / TrajectoryGeometry.metersPerPixel(18.0, lat)
        val pxFar = far / TrajectoryGeometry.metersPerPixel(15.0, lat)
        assertEquals(pixels, pxNear, 1e-6)
        assertEquals(pixels, pxFar, 1e-6)
    }

    @Test
    fun `arrow length falls back to a fixed value when zoom is unknown`() {
        val fallback = TrajectoryGeometry.arrowLengthForPixels(null, 30.0, 34.0, fallbackM = 2.0)
        assertEquals(2.0, fallback, 1e-9)
    }

    @Test
    fun `arrow length stays within sane bounds`() {
        val lat = 30.0
        // 极端缩放级别也不能产生荒谬的长度
        val huge = TrajectoryGeometry.arrowLengthForPixels(2.0, lat, 34.0)
        assertTrue("huge=$huge", huge <= 500.0)
        val tiny = TrajectoryGeometry.arrowLengthForPixels(22.0, lat, 34.0)
        assertTrue("tiny=$tiny", tiny >= 0.5)
    }
}
