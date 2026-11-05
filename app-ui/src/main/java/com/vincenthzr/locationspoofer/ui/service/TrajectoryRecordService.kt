package com.vincenthzr.locationspoofer.ui.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.TrajectoryPoint
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 轨迹录制服务：在前台持续读取**真实** GPS，记录“位置 + 时刻”。
 *
 * 录制期间必须保证模拟定位是关闭的——否则拿到的“真实定位”其实是模拟值，
 * 录出来的轨迹就是自己生成的轨迹，没有意义。因此开始录制前会先撤销 mock provider。
 *
 * 服务只负责采集与暂存（内存），落库由 App 端在“停止录制”后完成。
 */
class TrajectoryRecordService : Service() {

    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var listener: LocationListener? = null

    companion object {
        const val ACTION_START = "com.vincenthzr.locationspoofer.action.TRAJECTORY_START"
        const val ACTION_STOP = "com.vincenthzr.locationspoofer.action.TRAJECTORY_STOP"

        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "TrajectoryRecordChannel"

        /** 采样间隔（毫秒） */
        private const val SAMPLE_INTERVAL_MS = 1000L
        /** 最小采样距离（米），太近的点直接丢掉 */
        private const val MIN_DISTANCE_M = 2.0f
        /** 精度上限（米），差于此值的定位不可信 */
        private const val MAX_ACCURACY_M = 50f
        /** 定位最大允许“年龄”：超过这个时间的定位视为陈旧（用来拦 last known location） */
        private const val MAX_LOCATION_AGE_MS = 3_000L
        /** 首点跳变阈值（米）：第二个点比首点远这么多、且时间很短，就认为首点坏了 */
        private const val FIRST_POINT_JUMP_M = 30.0
        /** 首点跳变的判定时间窗（秒） */
        private const val FIRST_POINT_JUMP_WINDOW_SEC = 5.0
        /** 原点定位所需的最大精度（米）：达到这个精度才算不窗 */
        private const val ORIGIN_MAX_ACCURACY_M = 15.0f
        /** 原点定位时，两个连续点的允许差距（米）：小于这个值说明已稳定 */
        private const val ORIGIN_STABLE_DISTANCE_M = 20.0
        /** 原点定位最长等待时间（秒） */
        private const val ORIGIN_TIMEOUT_SEC = 20.0

        @Volatile
        var isRecording: Boolean = false
            private set

        /** 录制过程中的原始采样点（含精度），进程内共享 */
        @Volatile
        var rawPoints: MutableList<RawSample> = CopyOnWriteArrayList()
            private set

        @Volatile
        var startTimestamp: Long = 0L
            private set

        /** 是否已定位到精确原点（开录后等待中，还未开始记录路径） */
        @Volatile
        var awaitingOrigin: Boolean = false
            private set

        /** 一条原始 GPS 采样（含精度，精度为 NaN 表示未知） */
        data class RawSample(
            val lat: Double,
            val lng: Double,
            val tOffsetSec: Double,
            val accuracyM: Float,
            /** 行进方向（度，正北 0，顺时针）；无 bearing 时为 NaN */
            val bearingDeg: Double = Double.NaN,
            /** 是否为开录时定位的原点 */
            val isOrigin: Boolean = false
        )

        fun resetSamples() {
            rawPoints = CopyOnWriteArrayList()
            startTimestamp = 0L
        }

        /** 把原始采样转成轨迹点（精度超限的已在采集阶段丢弃） */
        fun toTrajectoryPoints(): List<TrajectoryPoint> =
            rawPoints.map { TrajectoryPoint(it.lat, it.lng, it.tOffsetSec) }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return
        acquireWakeLock()

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            launchIntent.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.trajectory_recording_title))
            .setContentText(getString(R.string.trajectory_recording_content))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        resetSamples()
        startTimestamp = System.currentTimeMillis()
        isRecording = true
        // 先等一个精确原点，之后才开始记录路径
        awaitingOrigin = true

        // 只用一个 provider 采样：**绝不能同时注册 GPS 和网络定位**。
        // 网络定位是基站推算的粗略位置（可偏差几百米），与精确的 GPS 交替上报时，
        // 路径会在两个位置之间来回跳，每一跳都计入累计距离 —— 实测站着不动能累出 3.7 km。
        // 录制场景本就在户外，GPS 是唯一可靠来源；GPS 不可用时再降级到网络定位。
        val providers = buildList {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (isEmpty() && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = handleLocation(location)
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        listener = l
        var registered = false
        for (p in providers) {
            try {
                if (!locationManager.isProviderEnabled(p)) continue
                locationManager.requestLocationUpdates(p, SAMPLE_INTERVAL_MS, MIN_DISTANCE_M, l)
                registered = true
            } catch (_: Throwable) {
            }
        }
        if (!registered) {
            // 一个 provider 都用不了：权限被拒或定位被关，停止录制并让界面知道
            stopRecording()
        }
    }

    private fun handleLocation(location: Location) {
        if (!isRecording) return

        // ① 丢弃“陈旧”定位：
        // 注册监听后，系统会立即把**上次已知位置**（last known location）推一次，
        // 那可能是几小时前、几公里外的位置。不拦掉就会出现“刚开录就飞出一大段直线”。
        val now = System.currentTimeMillis()
        if (now - location.time > MAX_LOCATION_AGE_MS) return

        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.NaN
        // ② 精度太差（>50m）的定位不可信，直接丢弃
        if (!accuracy.isNaN() && accuracy > MAX_ACCURACY_M) return

        // 坐标必须先统一到 GCJ-02 再参与任何距离计算，
        // 否则 WGS-84 与 GCJ-02 混算会凭空多出 500 米偏差。
        val gcj = CoordinateUtils.wgs84ToGcj02(location.latitude, location.longitude)

        // ③ “先定位原点”阶段：
        // 开录后不立即记录，而是等一个**精确、已稳定**的定位作为原点。
        // GPS 冷启动的第一帧常带几十米误差（但精度值只有 30~40m，能过上面的阈值），
        // 直接当当成第一个点录进来，就会在地图上拉出一条长直线。
        // 判定原点已稳定的条件：精度足够好 ≤ 15m，且与上一个点相差 ≤ 20m（说明已经落到实处，不在漂）。
        if (awaitingOrigin) {
            val good = !accuracy.isNaN() && accuracy <= ORIGIN_MAX_ACCURACY_M
            val stable = rawPoints.isEmpty() || GeoMath.distance(
                CoordinateUtils.LatLng(rawPoints.last().lat, rawPoints.last().lng),
                CoordinateUtils.LatLng(gcj.lat, gcj.lng)
            ) <= ORIGIN_STABLE_DISTANCE_M
            if (good && stable) {
                awaitingOrigin = false
                startTimestamp = now
                rawPoints.clear()
                rawPoints.add(
                    RawSample(gcj.lat, gcj.lng, 0.0, accuracy,
                        if (location.hasBearing()) location.bearing.toDouble() else Double.NaN,
                        isOrigin = true)
                )
                return
            }
            // 超时回退：等不到足够精确的原点时直接开录，
            // 把当前点当作第一个点（isOrigin=false），由 ⑤ 的首点跳变保护接手。
            val waitedSec = (now - startTimestamp) / 1000.0
            if (waitedSec >= ORIGIN_TIMEOUT_SEC) {
                awaitingOrigin = false
                startTimestamp = now
                rawPoints.clear()
                rawPoints.add(
                    RawSample(gcj.lat, gcj.lng, 0.0, accuracy,
                        if (location.hasBearing()) location.bearing.toDouble() else Double.NaN)
                )
                return
            }
            // 还没稳定：只记住最后一个点用于比对，不计入路径
            rawPoints.clear()
            rawPoints.add(
                RawSample(gcj.lat, gcj.lng, 0.0, accuracy,
                    if (location.hasBearing()) location.bearing.toDouble() else Double.NaN)
            )
            return
        }

        // ④ 时间戳用系统当前时间（与 startTimestamp 同源），不依赖 provider 的时间
        val t = (now - startTimestamp) / 1000.0

        // ⑤ 首点跳变保护（备用层）：
        // 若原点定位超时回退到“直接开录”模式，这里再保留一道防线。
        if (rawPoints.size == 1 && !rawPoints[0].isOrigin) {
            val first = rawPoints[0]
            val gap = GeoMath.distance(
                CoordinateUtils.LatLng(first.lat, first.lng),
                CoordinateUtils.LatLng(gcj.lat, gcj.lng)
            )
            val dt = t - first.tOffsetSec
            if (gap > FIRST_POINT_JUMP_M && dt < FIRST_POINT_JUMP_WINDOW_SEC) {
                rawPoints.clear()
            }
        }

        val bearing = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN
        rawPoints.add(RawSample(gcj.lat, gcj.lng, t, accuracy, bearing))
    }

    private fun stopRecording() {
        if (!isRecording) {
            stopForegroundSafely()
            stopSelf()
            return
        }
        isRecording = false
        listener?.let { l ->
            try {
                locationManager.removeUpdates(l)
            } catch (_: Throwable) {
            }
        }
        listener = null
        releaseWakeLock()
        stopForegroundSafely()
        stopSelf()
    }

    private fun stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationSpoofer:Trajectory").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.trajectory_record_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        releaseWakeLock()
        listener?.let { l ->
            try {
                locationManager.removeUpdates(l)
            } catch (_: Throwable) {
            }
        }
        listener = null
        isRecording = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
