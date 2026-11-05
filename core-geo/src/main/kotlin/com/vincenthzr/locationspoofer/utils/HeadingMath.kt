package com.vincenthzr.locationspoofer.utils

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 手机朝向（正北为 0，顺时针）的纯数学计算。
 *
 * 放在 core-geo 是因为这里不能用 Android API（core-geo 是纯 JVM 模块），
 * 所以矩阵乘法用手写的实现，而不是 android.opengl.Matrix。
 */
object HeadingMath {

    /**
     * 由旋转矩阵算出“手机顶部指向的水平方向”。
     *
     * 旋转矩阵 R（`SensorManager.getRotationMatrix` / `getRotationMatrixFromVector` 给出）
     * 把设备坐标系旋转到世界坐标系；世界系为 X=东、Y=北、Z=天顶。
     * 因此设备 +Y 轴（屏幕上方）在世界系里的分量就是 R 的**第二列**：
     * `R[1]` = 东向分量，`R[4]` = 北向分量。
     *
     * **注意**：不能拿 `R × gravity` 去算方向。重力向量的设备系表示几乎恒定在 +Z，
     * 乘完得到的永远是世界天顶方向，与手机朝向无关——那样算出来的角度是死值，
     * 箭头就会固定在屏幕上不转（v3.2.0 实测踩过这个坑）。
     *
     * @param rotationMatrix 3x3 旋转矩阵（行主序）
     * @param magneticDeclination 地磁偏角（度）
     * @return 朝向（度）；设备接近竖直（水平投影过小）或数值无效时返回 null
     */
    fun headingFromRotation(
        rotationMatrix: FloatArray,
        magneticDeclination: Double = 0.0
    ): Double? {
        if (rotationMatrix.size < 9) return null
        // 设备 +Y 轴投影到水平面的东/北分量
        val east = rotationMatrix[1].toDouble()
        val north = rotationMatrix[4].toDouble()
        // 水平投影太小说明手机接近竖直举着（屏幕朝你看），此时“顶部方向”无意义
        if (hypot(east, north) < MIN_HORIZONTAL_COMPONENT) return null
        val heading = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
        return (heading + magneticDeclination + 360.0) % 360.0
    }

    /** 水平投影长度低于该值时认为手机接近竖直，朝向不可信 */
    private const val MIN_HORIZONTAL_COMPONENT = 0.20

    /**
     * 纯位置解算：目标点相对起点的方向（度）。
     */
    fun bearingBetween(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLng = Math.toRadians(toLng - fromLng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 两个方向的小角差（度，0~180） */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return min(d, 360.0 - d)
    }

    /**
     * 圆形平滑（低通滤波）：把方向速度与上一次的方向按比例混合，抑刺吼保驼监器的抖动。
     *
     * @param alpha 新方向占比重，越小越平滑（径向差别时限制快速反应）
     */
    fun smoothHeading(current: Double?, target: Double, alpha: Double): Double {
        if (current == null) return target
        val diff = ((target - current + 540.0) % 360.0) - 180.0
        return ((current + diff * alpha) % 360.0 + 360.0) % 360.0
    }
}
