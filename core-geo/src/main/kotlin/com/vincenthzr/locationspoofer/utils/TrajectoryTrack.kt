package com.vincenthzr.locationspoofer.utils

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 轨迹点：坐标 + 相对录制起点的秒数。
 *
 * 与普通“路线点”的区别：普通路线只知道路怎么走（位置序列），
 * 轨迹还知道“当时怎么走”——每点带一个相对时间，回放时按时间轴插值，
 * 速度由相邻点的距离/时间差反推，因此位置与速度天然自洽。
 */
data class TrajectoryPoint(
    val lat: Double,
    val lng: Double,
    /** 相对录制起点的秒数，单调不减 */
    val tOffsetSec: Double
)

/**
 * 一条录制下来的轨迹。
 *
 * 只保存“位置 + 时刻”，不保存速度：速度可由相邻两点反推，既省一份数据，
 * 也能容忍 GPS 的抖动（抖动会被相邻点平均掉）。
 */
class TrajectoryTrack(val points: List<TrajectoryPoint>) {

    init {
        require(points.size >= 2) { "a trajectory needs at least two points" }
    require(points.zipWithNext().all { (a, b) -> b.tOffsetSec >= a.tOffsetSec }) {
            "trajectory timestamps must be non-decreasing"
        }
    }

    /** 整条轨迹的时长（秒） */
    val durationSec: Double get() = points.last().tOffsetSec - points.first().tOffsetSec

    /** 总路程（米），按相邻点累加 */
    val totalDistanceM: Double
        get() {
            var sum = 0.0
            for (i in 1 until points.size) {
                sum += GeoMath.distance(
                    CoordinateUtils.LatLng(points[i - 1].lat, points[i - 1].lng),
                    CoordinateUtils.LatLng(points[i].lat, points[i].lng)
                )
            }
            return sum
        }

    /**
     * 按时间轴插值出 [elapsedSec] 时刻的位置。
     *
     * 超出范围时钳到首尾（轨迹结束后停在终点）。
     */
    fun positionAt(elapsedSec: Double): CoordinateUtils.LatLng {
        if (elapsedSec <= points.first().tOffsetSec) {
            return CoordinateUtils.LatLng(points.first().lat, points.first().lng)
        }
        val last = points.last()
        if (elapsedSec >= last.tOffsetSec) {
            return CoordinateUtils.LatLng(last.lat, last.lng)
        }
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].tOffsetSec <= elapsedSec) lo = mid else hi = mid - 1
        }
        val a = points[lo]
        val b = points[min(lo + 1, points.size - 1)]
        val span = b.tOffsetSec - a.tOffsetSec
        val ratio = if (span <= 0.0) 0.0 else (elapsedSec - a.tOffsetSec) / span
        return CoordinateUtils.LatLng(
            a.lat + (b.lat - a.lat) * ratio,
            a.lng + (b.lng - a.lng) * ratio
        )
    }

    /**
     * 按时间轴插值出 [elapsedSec] 时刻的速度（米/秒）。
     *
     * 取包含该时刻的那一段，用该段的平均速度；段首速度未知时退化为后一段。
     * 这样得到的是分段常数速度——与真实运动相比更规整，也便于目标 App 稳定反推配速。
     */
    fun speedAt(elapsedSec: Double): Double {
        if (points.size < 2) return 0.0
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].tOffsetSec <= elapsedSec) lo = mid else hi = mid - 1
        }
        val a = points[lo]
        val b = points[min(lo + 1, points.size - 1)]
        val dt = b.tOffsetSec - a.tOffsetSec
        if (dt <= 0.0) {
            // 两个点同一时刻：退化为下一段
            val c = points[min(lo + 2, points.size - 1)]
            val dt2 = c.tOffsetSec - b.tOffsetSec
            if (dt2 <= 0.0) return 0.0
            return segmentSpeed(b, c, dt2)
        }
        return segmentSpeed(a, b, dt)
    }

    private fun segmentSpeed(a: TrajectoryPoint, b: TrajectoryPoint, dt: Double): Double {
        val d = GeoMath.distance(
            CoordinateUtils.LatLng(a.lat, a.lng),
            CoordinateUtils.LatLng(b.lat, b.lng)
        )
        return d / dt
    }

    /**
     * 把轨迹按 [targetDurationSec] 拉伸/压缩后的“时间映射系数”。
     *
     * 轨迹时间优先：配速档位决定整条轨迹要跑多久，轨迹内部各段的相对时长保持不变。
     * 系数 = 目标时长 / 原始时长；回放时用 原始时刻 × 系数 去查位置。
     */
    fun timeScaleFor(targetDurationSec: Double): Double {
        val d = durationSec
        if (d <= 0.0) return 1.0
        return targetDurationSec / d
    }

    /**
     * 采样过滤：把原始 GPS 点收敛成适合回放的轨迹点。
     *
     * 过滤规则（针对真实 GPS 的漂移与跳点）：
     * 1. 精度差（accuracy > [maxAccuracyM]）的点直接丢弃；
     * 2. 与上一点距离过近（< [minDistanceM]）且转向不大时丢弃，避免冗余点；
     * 3. 瞬时速度超过 [maxSpeedMps] 的点丢弃（真实跑步/骑行不该这么快，多半是跳点）；
     * 4. 时间必须单调不减。
     *
     * 返回过滤后的点；不足两点时返回空列表（无法构成轨迹）。
     */
    companion object {
        const val DEFAULT_MIN_DISTANCE_M = 3.0
        const val DEFAULT_MAX_ACCURACY_M = 30.0
        const val DEFAULT_MAX_SPEED_MPS = 12.0
        const val DEFAULT_TURN_THRESHOLD_DEG = 20.0

        fun filter(
            raw: List<TrajectoryPoint>,
            minDistanceM: Double = DEFAULT_MIN_DISTANCE_M,
            maxAccuracyM: Double = DEFAULT_MAX_ACCURACY_M,
            maxSpeedMps: Double = DEFAULT_MAX_SPEED_MPS,
            turnThresholdDeg: Double = DEFAULT_TURN_THRESHOLD_DEG,
            accuracyOf: (TrajectoryPoint) -> Double? = { null }
        ): List<TrajectoryPoint> {
            if (raw.isEmpty()) return emptyList()
            val out = ArrayList<TrajectoryPoint>()
            for (p in raw) {
                // 精度过滤：拿不到精度信息时（null）不丢弃
                val acc = accuracyOf(p)
                if (acc != null && acc > maxAccuracyM) continue
                // 时间单调
                if (out.isNotEmpty() && p.tOffsetSec < out.last().tOffsetSec) continue
                if (out.isNotEmpty()) {
                    val prev = out.last()
                    val d = GeoMath.distance(
                        CoordinateUtils.LatLng(prev.lat, prev.lng),
                        CoordinateUtils.LatLng(p.lat, p.lng)
                    )
                    val dt = p.tOffsetSec - prev.tOffsetSec
                    // 速度异常（跳点）直接丢弃
                    if (dt > 0.0 && d / dt > maxSpeedMps) continue
                    // 距离太近：只有转向明显时才值得保留（转角处需要保留一个点）
                    if (d < minDistanceM) {
                        if (out.size < 2) continue
                        val prev2 = out[out.size - 2]
                        val bearing = GeoMath.bearing(
                            CoordinateUtils.LatLng(prev2.lat, prev2.lng),
                            CoordinateUtils.LatLng(prev.lat, prev.lng)
                        )
                        val bearing2 = GeoMath.bearing(
                            CoordinateUtils.LatLng(prev.lat, prev.lng),
                            CoordinateUtils.LatLng(p.lat, p.lng)
                        )
                        val diff = abs(bearing - bearing2) % 360f
                        if (diff > 180f) diff = 360f - diff
                        if (diff < turnThresholdDeg) continue
                    }
                }
                out.add(p)
            }
            return out
        }
    }
}
