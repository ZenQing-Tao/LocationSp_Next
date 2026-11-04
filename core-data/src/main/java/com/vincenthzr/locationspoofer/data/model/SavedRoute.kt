package com.vincenthzr.locationspoofer.data.model

data class SavedRoute(
    val name: String,
    val points: List<RoutePoint>,
    /**
     * 是否来自 GPS 轨迹录制（而非手动选点或摇杆录制）。
     * 模拟行为两者完全一致，只是来源不同；这个标记只用于在路线库里给用户一个区分提示。
     */
    val isTrajectory: Boolean = false
)
