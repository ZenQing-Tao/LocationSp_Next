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

        /** 一条原始 GPS 采样（含精度，精度为 NaN 表示未知） */
        data class RawSample(
            val lat: Double,
            val lng: Double,
            val tOffsetSec: Double,
            val accuracyM: Float
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

        // GPS 与网络定位都注册：室内可能只有网络定位，室外 GPS 更准
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
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
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.NaN
        // 精度太差（>50m）的定位不可信，直接丢弃
        if (!accuracy.isNaN() && accuracy > MAX_ACCURACY_M) return
        // 时间戳用系统当前时间（与 startTimestamp 同源），不依赖 provider 的时间
        val t = (System.currentTimeMillis() - startTimestamp) / 1000.0
        rawPoints.add(RawSample(location.latitude, location.longitude, t, accuracy))
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
