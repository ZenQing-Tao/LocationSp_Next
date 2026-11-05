package com.vincenthzr.locationspoofer.ui.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.vincenthzr.locationspoofer.utils.HeadingMath

/**
 * 手机朝向提供者：用方向传感器算出“手机顶部指向的水平方向”。
 *
 * 用的场景：录制时用户望着手机判断自己正在向哪走，
 * 所以箭头要跟随**手机朝向**，而不是轨迹几何方向。
 *
 * 实现采用 `TYPE_ROTATION_VECTOR`（Android 9+ 的标准方式，无需单独校准），
 * 优先用它算旋转矩阵；若设备不支持，回退到“加速度计算器 + 磁力计算器”的组合方式。
 *
 * 算出的朝向会经过圆形低通滤波（见 [HeadingMath.smoothHeading]），防止罗盘抖动让箭头跳。
 */
class HeadingProvider(private val context: Context) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** 旋转矩阵（来自 TYPE_ROTATION_VECTOR） */
    private val rotationMatrix = FloatArray(9)
    /** 重力向量（来自加速度计算器） */
    private val gravity = FloatArray(3)
    /** 地磁场向量（来自磁力计算器） */
    private val geomagnetic = FloatArray(3)

    private var hasRotation = false
    private var hasGravity = false
    private var hasGeomagnetic = false

    /** 当前朝向（度，正北 0）；传感器未就绪时为 null */
    @Volatile
    var heading: Double? = null
        private set

    /** 上一次平滑后的朝向，用于低通滤比例 */
    private var smoothed: Double? = null

    /** 方向是否可用（传感器已注册且能算出朝向） */
    val isAvailable: Boolean
        get() = rotationSensor != null || (accelerometer != null && magnetometer != null)

    fun start() {
        val sm = sensorManager ?: return
        rotationSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelerometer?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                hasRotation = true
                recompute()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity, GRAVITY_ALPHA)
                hasGravity = true
                recompute()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPass(event.values, geomagnetic, MAGNETIC_ALPHA)
                hasGeomagnetic = true
                recompute()
            }
        }
    }

    private fun recompute() {
        val h = if (hasRotation) {
            // 旋转矢量本身就能给出朝向，不需要再等重力/磁场传感器
            HeadingMath.headingFromRotation(rotationMatrix)
        } else if (hasGravity && hasGeomagnetic) {
            // 回退：加速度计 + 磁力计组合
            val rot = FloatArray(9)
            if (!SensorManager.getRotationMatrix(rot, null, gravity, geomagnetic)) return
            HeadingMath.headingFromRotation(rot)
        } else {
            null
        }
        if (h == null) return
        // 圆形低通滤波：抑制罗盘抖动，同时保留快速转向的响应
        smoothed = HeadingMath.smoothHeading(smoothed, h, SMOOTH_ALPHA)
        heading = smoothed
    }

    private fun lowPass(src: FloatArray, dst: FloatArray, alpha: Float) {
        for (i in dst.indices) {
            dst[i] = if (i < src.size) alpha * dst[i] + (1 - alpha) * src[i] else dst[i]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /** 重力/磁场低通滤系数：越小越平滑 */
        private const val GRAVITY_ALPHA = 0.15f
        private const val MAGNETIC_ALPHA = 0.15f
        /** 朝向平滑系数：0.25 → 转向约 0.25 秒内响应，同时抑制抖动 */
        private const val SMOOTH_ALPHA = 0.25
    }
}
