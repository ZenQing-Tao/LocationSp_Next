package com.vincenthzr.locationspoofer.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.vincenthzr.locationspoofer.data.db.TrajectoryEntity
import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.data.model.TrajectoryListItem
import com.vincenthzr.locationspoofer.data.model.TrajectoryStats
import com.vincenthzr.locationspoofer.ui.service.TrajectoryRecordService
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.TrajectoryPoint
import com.vincenthzr.locationspoofer.utils.TrajectoryGeometry
import com.vincenthzr.locationspoofer.utils.TrajectoryTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

// 轨迹录制：真实 GPS 采样 → 存库 → 列表 / 删除。
//
// 录制期间必须关闭模拟定位，否则采到的是模拟值。开始录制前会先停掉模拟并提示用户，
// 录制结束后**不自动恢复**模拟状态（避免“以为停了其实还在跑”这类意外）。

/** 落库用的紧凑 JSON 结构：{"p":[[lat,lng,t],...]} */
@Serializable
private data class TrajectoryPayload(val p: List<List<Double>>)

private val payloadJson = Json { ignoreUnknownKeys = true }

internal fun MainViewModel.startTrajectoryRecording(context: Context) {
    if (_uiState.value.isTrajectoryRecording) return

    viewModelScope.launch {
        // 1) 强制关闭模拟：否则录到的是模拟轨迹
        val wasSpoofing = _uiState.value.isSpoofingActive
        if (wasSpoofing) {
            try {
                locationRepository.stopSpoofing(context)
                _uiState.update { it.copy(isSpoofingActive = false) }
            } catch (_: Throwable) {
            }
            Toast.makeText(context, context.getString(R.string.trajectory_spoofing_stopped), Toast.LENGTH_LONG).show()
        }

        // 2) 拉起前台录制服务
        val intent = Intent(context, TrajectoryRecordService::class.java).apply {
            action = TrajectoryRecordService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Throwable) {
            Toast.makeText(context, context.getString(R.string.trajectory_start_failed), Toast.LENGTH_LONG).show()
            return@launch
        }

        _uiState.update { it.copy(isTrajectoryRecording = true, trajectoryStats = TrajectoryStats()) }
        refreshTrajectoryStats()
    }
}

/** 停服务 → 取采样 → 过滤 → 弹出命名由调用方处理（这里先存为临时名，再走重命名） */
internal fun MainViewModel.stopTrajectoryRecording(context: Context) {
    if (!_uiState.value.isTrajectoryRecording) return

    val intent = Intent(context, TrajectoryRecordService::class.java).apply {
        action = TrajectoryRecordService.ACTION_STOP
    }
    try {
        context.startService(intent)
    } catch (_: Throwable) {
    }

    viewModelScope.launch {
        // 等服务把最后的采样落进内存（stopSelf 是异步的）
        delay(300)
        val raw = TrajectoryRecordService.toTrajectoryPoints()
        val filtered = TrajectoryTrack.filter(
            raw = raw,
            accuracyOf = {
                // 精度信息按时间就近匹配：采集阶段已丢弃 >50m 的点，这里不再二次过滤
                null
            }
        )
        _uiState.update { it.copy(isTrajectoryRecording = false, trajectoryStats = TrajectoryStats()) }

        if (filtered.size < 2) {
            Toast.makeText(context, context.getString(R.string.trajectory_too_short), Toast.LENGTH_LONG).show()
            return@launch
        }
        // 不直接落库：交给界面弹命名框，用户确认后再保存
        _uiState.update { it.copy(pendingTrajectoryPoints = filtered) }
    }
}

private fun MainViewModel.defaultTrajectoryName(): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
    return fmt.format(java.util.Date())
}

/** 把轨迹点写入数据库，并刷新列表 */
internal fun MainViewModel.saveTrajectory(
    context: Context,
    name: String,
    points: List<TrajectoryPoint>
) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val track = TrajectoryTrack(points)
            val payload = TrajectoryPayload(points.map { listOf(it.lat, it.lng, it.tOffsetSec) })
            val json = payloadJson.encodeToString(TrajectoryPayload.serializer(), payload)
            val entity = TrajectoryEntity(
                name = name.ifBlank { defaultTrajectoryName() },
                pointsJson = json,
                durationSec = track.durationSec,
                distanceM = track.totalDistanceM,
                pointCount = points.size
            )
            locationRepository.insertTrajectory(entity)

            // 双写：同时写进路线库（saved_routes），这样录完的轨迹就是一条普通路线，
            // 在路线库里点一下即可像普通路线一样选配速、循环/折返地模拟。
            // 写 Room 表而不是 SharedPreferences —— 路线库界面读的是 Room 那边。
            val smoothed = TrajectoryTrack(points).smooth()
            // 开关开启时：起终点相近则把终点连回起点形成闭环，模拟时按循环走而不是折返
            val closed = if (_uiState.value.autoCloseTrajectoryLoop) {
                TrajectoryGeometry.closeLoopIfNear(smoothed)
            } else {
                smoothed
            }
            val routePoints = closed.map { RoutePoint(it.lat, it.lng) }
            if (routePoints.size >= 2) {
                locationRepository.insertTrajectoryRoute(entity.name, routePoints)
            }

            refreshTrajectoryStats()
            // 通知界面：已保存到路线库（路线页会据此跳过去）
            _uiState.update { it.copy(trajectorySavedCounter = it.trajectorySavedCounter + 1) }
            launch(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.trajectory_saved_toast), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            launch(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.trajectory_save_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

/** 丢弃待命名轨迹（用户取消命名时调用） */
internal fun MainViewModel.discardPendingTrajectory() {
    _uiState.update { it.copy(pendingTrajectoryPoints = emptyList()) }
}

internal fun MainViewModel.deleteTrajectory(id: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            locationRepository.deleteTrajectoryById(id)
            refreshTrajectoryStats()
        } catch (_: Throwable) {
        }
    }
}

/** 刷新轨迹列表，并在录制中刷新实时统计 */
internal fun MainViewModel.refreshTrajectoryStats() {
    viewModelScope.launch(Dispatchers.IO) {
        val list = try {
            locationRepository.getAllTrajectories()
        } catch (_: Throwable) {
            emptyList()
        }
        val items = list.map {
            TrajectoryListItem(it.id, it.name, it.pointCount, it.distanceM, it.durationSec)
        }
        _uiState.update { it.copy(savedTrajectories = items) }
    }
}

/** 录制中：每秒把服务里的采样换算成距离/时长/点数 */
internal fun MainViewModel.pollRecordingStats() {
    viewModelScope.launch {
        while (isActive) {
            if (_uiState.value.isTrajectoryRecording) {
                val pts = TrajectoryRecordService.toTrajectoryPoints()
                var dist = 0.0
                for (i in 1 until pts.size) {
                    dist += GeoMath.distance(
                        CoordinateUtils.LatLng(pts[i - 1].lat, pts[i - 1].lng),
                        CoordinateUtils.LatLng(pts[i].lat, pts[i].lng)
                    )
                }
                val dur = if (pts.isEmpty()) 0.0 else pts.last().tOffsetSec
                val awaiting = TrajectoryRecordService.awaitingOrigin
                _uiState.update {
                    it.copy(
                        trajectoryStats = TrajectoryStats(
                            pointCount = pts.size,
                            distanceM = dist,
                            durationSec = dur
                        ),
                        awaitingTrajectoryOrigin = awaiting
                    )
                }
            }
            delay(1000)
        }
    }
}

/** 从库里读出一条完整轨迹（回放时用） */
suspend fun MainViewModel.loadTrajectoryPoints(id: Long): List<TrajectoryPoint> {
    val entity = locationRepository.getTrajectoryById(id) ?: return emptyList()
    return try {
        val payload = payloadJson.decodeFromString(TrajectoryPayload.serializer(), entity.pointsJson)
        payload.p.mapNotNull { row ->
            if (row.size >= 3) TrajectoryPoint(row[0], row[1], row[2]) else null
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
