package com.vincenthzr.locationspoofer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条录制下来的轨迹。
 *
 * 与 saved_routes 的区别：saved_routes 只存“路怎么走”，回放时按配速曲线走；
 * 这张表存“路 + 时刻”，回放时按原始时间轴插值，能复现起步/加速/停顿这类节奏。
 * 两者刻意分表，互不干扰，也不影响已有路线的数据结构。
 */
@Entity(tableName = "trajectories")
data class TrajectoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 轨迹点 JSON：List<TrajectoryPoint> 的序列化结果 */
    val pointsJson: String = "[]",
    /** 录制时长（秒） */
    val durationSec: Double = 0.0,
    /** 总路程（米） */
    val distanceM: Double = 0.0,
    val pointCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
