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
     * **只用轨迹几何，不用 GPS 的 `bearing` 字段。**
     * 原因：`Location.bearing` 是“移动方向”，在步行这种低速场景下极不稳定——
     * 静止或慢速时系统会保留上一次的旧值、或直接报 0，结果就是箭头指向错误。
     * 轨迹点连线反映的是“你实际走过的方向”，没有这个问题。
     *
     * 取法：从当前位置往前回溯最多 2 段取平均，既能抗抖动又能跟上转弯。
     *
     * @param gpsBearingDeg 录制端传入的原始 bearing；为了兼容旧调用保留参数，
     *   **但不参与计算**（见上面的原因）。
     */
    fun headingAt(
        points: List<TrajectoryPoint>,
        index: Int,
        @Suppress("UNUSED_PARAMETER") gpsBearingDeg: Double? = null
    ): Double {
        if (points.size < 2) return 0.0
        val i = index.coerceIn(1, points.size - 1)
        val window = 2
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
        // atan2(东向分量, 北向分量) = 从正北顺时针的角度
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

    /**
     * Web 墨卡托下，某缩放级别、某纬度处**一个屏幕像素**对应的地面距离（米）。
     *
     * 用途：把「箭头要占多少像素」换算成「箭头要画多少米」，
     * 从而让箭头在屏幕上大小恒定，不随地图缩放忽大忽小。
     *
     * @param zoom 地图缩放级别（各引擎口径略有差异，见各适配器注释）
     * @param lat 箭头所在纬度（墨卡托纬度方向随纬度收缩）
     */
    fun metersPerPixel(zoom: Double, lat: Double): Double {
        val z = zoom.coerceIn(2.0, 22.0)
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val mpp = 156543.03392804097 * Math.cos(latRad) / Math.pow(2.0, z)
        return mpp.coerceIn(0.05, 5000.0)
    }

    /**
     * 让箭头在屏幕上保持固定像素大小所需的**地面长度（米）**。
     *
     * 旧实现固定用 [DEFAULT_ARROW_LENGTH_M]（2 米）画箭头，
     * 于是：
     * - 刚开始录制、只有几个点时，`fitBounds` 会把地图拉得很近（高 zoom），
     *   2 米在屏幕上只有几个像素 → 箭头小得几乎看不见；
     * - 走了一段后 `fitBounds` 把视野拉远（低 zoom），
     *   同样 2 米在屏幕上又变得巨大 → 箭头盖住整条轨迹。
     *
     * 现在按当前缩放级别反算长度：地图放大时箭头变短（米数变小），
     * 缩小的时候变长，屏幕上的观感大小保持不变。
     *
     * @param zoom 当前地图缩放级别；为 null（引擎未提供）时退回固定长度
     * @param lat 箭头所在纬度
     * @param pixels 期望的屏幕像素长度
     * @param fallbackM 引擎拿不到缩放级别时的兜底长度（米）
     */
    fun arrowLengthForPixels(
        zoom: Double?,
        lat: Double,
        pixels: Double,
        fallbackM: Double = DEFAULT_ARROW_LENGTH_M
    ): Double {
        if (zoom == null) return fallbackM
        return (pixels * metersPerPixel(zoom, lat)).coerceIn(0.5, 500.0)
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
    const val DEFAULT_ARROW_LENGTH_M = 2.0

    /** V 形箭头张角（度） */
    const val DEFAULT_ARROW_SPREAD_DEG = 30.0
}
