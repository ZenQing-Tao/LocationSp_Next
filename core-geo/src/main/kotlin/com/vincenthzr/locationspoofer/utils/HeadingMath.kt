package com.vincenthzr.locationspoofer.utils

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
     * 由旋转矩阵与重力向量算出“手机顶部指向的水平方向”。
     *
     * @param rotationMatrix 3x3 旋转矩阵（SensorManager.getRotationMatrix 给出）
     * @param gravity 重力向量（加速度计算器）
     * @param geomagnetic 地磁场向量（磁力计算器）
     * @param magneticDeclination 地磁偏角（度）
     * @return 朝向（度）；无法计算时返回 null
     */
    fun headingFromRotation(
        rotationMatrix: FloatArray,
        gravity: FloatArray,
        geomagnetic: FloatArray,
        magneticDeclination: Double = 0.0
    ): Double? {
        // out = R * gravity，R 为 3x3 旋转矩阵（行主序存储）
        val gx = gravity[0]; val gy = gravity[1]; val gz = gravity[2]
        val o0 = rotationMatrix[0] * gx + rotationMatrix[1] * gy + rotationMatrix[2] * gz
        val o3 = rotationMatrix[3] * gx + rotationMatrix[4] * gy + rotationMatrix[5] * gz
        val o6 = rotationMatrix[6] * gx + rotationMatrix[7] * gy + rotationMatrix[8] * gz
        // o6 = 水平方向的东向分量，o3 = 北向分量
        val east = o6.toDouble()
        val north = o3.toDouble()
        if (abs(east) < 1e-7 && abs(north) < 1e-7) return null
        val heading = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
        return (heading + magneticDeclination + 360.0) % 360.0
    }

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
