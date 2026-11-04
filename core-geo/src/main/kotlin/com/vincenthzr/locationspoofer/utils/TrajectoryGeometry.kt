package com.vincenthzr.locationspoofer.utils

import kotlin.math.abs

/**
 * 轨迹几何工具：闭合修正与朝向计算。
 *
 * 放在 core-geo 是为了让 App 端与测试都能直接用，且不依赖 Android 框架。
 */
object TrajectoryGeometry {

    /**
     * 起终点接近时，把终点连回起点形成闭环。
     *
     * 背景：路线模式里，非闭环路线的收尾规则是“到终点后原路折返”。但真人绕一圈回到起点附近时，
     * 更符合直觉的是“从终点直接走回起点，然后继续循环”——也就是把轨迹当成闭环。
     * 直接折返会在“终点 → 起点”之间拉出一条横穿地图的直线，看起来很假。
     *
     * @param points 轨迹点（按时间顺序）
     * @param thresholdM 首尾距离小于该值时视为“在附近”，进行闭合
     * @return 闭合后的点序列；若不需要闭合则原样返回
     */
    fun closeLoopIfNear(
        points: List<TrajectoryPoint>,
        thresholdM: Double = DEFAULT_CLOSE_THRESHOLD_M
    ): List<TrajectoryPoint> {
        if (points.size < 3) return points
        val first = points.first()
        val last = points.last()
        val gap = GeoMath.distance(
            CoordinateUtils.LatLng(first.lat, first.lng),
            CoordinateUtils.LatLng(last.lat, last.lng)
        )
        if (gap > thresholdM) return points
        // 已经天然闭合（末点就是起点）就不用再加
        if (gap <= 0.5) return points
        // 末尾追加起点坐标：路线库会据此判定为闭环，模拟时按循环走
        val out = ArrayList<TrajectoryPoint>(points.size + 1)
        out.addAll(points)
        out.add(TrajectoryPoint(first.lat, first.lng, last.tOffsetSec))
        return out
    }

    /**
     * 计算某个时刻的行进方向（度，正北为 0，顺时针）。
     *
     * 优先用 GPS 自带的 bearing（稳定、不受抖动影响）；
     * 没有 bearing 时退化为“最近两点连线方向”，取前一小段的平均以抵消抖动。
     */
    fun headingAt(
        points: List<TrajectoryPoint>,
        index: Int,
        gpsBearingDeg: Double? = null
    ): Double {
        if (gpsBearingDeg != null && !gpsBearingDeg.isNaN()) return (gpsBearingDeg + 360.0) % 360.0
        if (points.size < 2) return 0.0
        val i = index.coerceIn(1, points.size - 1)
        // 从当前位置往前回溯若干点，取平均方向，抵消 GPS 抖动
        val window = 3
        var sx = 0.0
        var sy = 0.0
        var n = 0
        var k = i
        while (k > 0 && n < window) {
            val a = points[k - 1]
            val b = points[k]
            val dx = (b.lng - a.lng) * 111320.0 * kotlin.math.cos(Math.toRadians(b.lat))
            val dy = b.lat - a.lat
            if (abs(dx) > 0.01 || abs(dy) > 0.01) {
                sx += dx
                sy += dy
                n++
            }
            k--
        }
        if (n == 0) return 0.0
        return (Math.toDegrees(kotlin.math.atan2(sx, sy)) + 360.0) % 360.0
    }

    /**
     * 以 [center] 为顶点、按 [headingDeg] 方向生成一个 V 形箭头的两个端点。
     *
     * 地图层的 marker 是位图、无法旋转，所以箭头改用两条短折线拼成 V 字形，
     * 这样高德/百度/Google/OSM 四个引擎都能一致显示。
     *
     * @param lengthM 箭头长度（米）
     * @param spreadDeg V 形的张角（度，总角度）
     */
    fun arrowPoints(
        centerLat: Double,
        centerLng: Double,
        headingDeg: Double,
        lengthM: Double = DEFAULT_ARROW_LENGTH_M,
        spreadDeg: Double = DEFAULT_ARROW_SPREAD_DEG
    ): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val half = spreadDeg / 2.0
        val left = destinationFrom(centerLat, centerLng, headingDeg - half, lengthM)
        val right = destinationFrom(centerLat, centerLng, headingDeg + half, lengthM)
        val leftPair = listOf(centerLat to centerLng, left.first to left.second)
        val rightPair = listOf(centerLat to centerLng, right.first to right.second)
        return leftPair to rightPair
    }

    private fun destinationFrom(
        lat: Double,
        lng: Double,
        bearingDeg: Double,
        meters: Double
    ): Pair<Double, Double> {
        val p = GeoMath.destination(CoordinateUtils.LatLng(lat, lng), bearingDeg, meters)
        return p.lat to p.lng
    }

    /** 起终点接近多少米内视为“在附近”，触发闭合 */
    const val DEFAULT_CLOSE_THRESHOLD_M = 15.0

    /** 箭头长度（米） */
    const val DEFAULT_ARROW_LENGTH_M = 6.0

    /** V 形箭头张角（度） */
    const val DEFAULT_ARROW_SPREAD_DEG = 40.0
}
