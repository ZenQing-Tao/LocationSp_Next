package com.vincenthzr.locationspoofer.ui.screen

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.components.AppMapController
import com.vincenthzr.locationspoofer.ui.components.AppMapMarker
import com.vincenthzr.locationspoofer.ui.components.AppMapView
import com.vincenthzr.locationspoofer.ui.components.MarkerType
import com.vincenthzr.locationspoofer.utils.TrajectoryGeometry
import com.vincenthzr.locationspoofer.utils.TrajectoryPoint
import com.vincenthzr.locationspoofer.viewmodel.isDomesticEnvironment
import com.vincenthzr.locationspoofer.ui.service.TrajectoryRecordService
import com.vincenthzr.locationspoofer.ui.service.HeadingProvider
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.startTrajectoryRecording
import com.vincenthzr.locationspoofer.viewmodel.stopTrajectoryRecording
import com.vincenthzr.locationspoofer.viewmodel.deleteTrajectory
import com.vincenthzr.locationspoofer.viewmodel.saveTrajectory
import com.vincenthzr.locationspoofer.viewmodel.discardPendingTrajectory
import com.vincenthzr.locationspoofer.viewmodel.pollRecordingStats
import com.vincenthzr.locationspoofer.viewmodel.setAutoCloseTrajectoryLoop
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/**
 * 轨迹录制页。
 *
 * 录制流程：点“开始录制” → 强制关闭模拟定位（提示用户）→ 前台服务采集真实 GPS →
 * 点“停止” → 命名保存。轨迹与普通路线分开存放，回放时按时间轴插值。
 */
@Composable
fun TrajectoryRecordScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean = isSystemInDarkTheme(),
    onClose: () -> Unit,
    /** 跳到路线页并打开路线库弹窗 */
    onGoToRouteLibrary: () -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current

    // 进入页面时启动“每秒刷新录制统计”的轮询（ViewModel 内部按录制状态开关）
    LaunchedEffect(Unit) { viewModel.pollRecordingStats() }

    // 方向传感器：箭头跟随手机朝向（而不是轨迹几何方向）
    val headingProvider = remember { HeadingProvider(context) }
    DisposableEffect(Unit) {
        headingProvider.start()
        onDispose { headingProvider.stop() }
    }
    val recording = uiState.isTrajectoryRecording
    // 地图：录制时实时显示已走过的轨迹与当前位置
    var mapController by remember { mutableStateOf<AppMapController?>(null) }
    var liveMarker by remember { mutableStateOf<AppMapMarker?>(null) }
    // 已录点数的版本号：变化时重画折线（每秒由轮询递增）
    var drawnPointCount by remember { mutableStateOf(0) }
    // 已录点数的版本号：变化时重画折线

    // 每秒把服务里最新的采样画到地图上
    LaunchedEffect(uiState.trajectoryStats.pointCount) {
        val controller = mapController ?: return@LaunchedEffect
        val samples = TrajectoryRecordService.rawPoints
        if (samples.isEmpty()) {
            controller.clear()
            liveMarker = null
            drawnPointCount = 0
            return@LaunchedEffect
        }
        // 点数没变就不用重画（避免每秒闪烁）
        if (samples.size != drawnPointCount) {
            controller.clear()
            val pts = samples.map { it.lat to it.lng }
            if (pts.size >= 2) {
                controller.addPolyline(pts, ACCENT_ORANGE, 8f)
            }
            val first = samples.first()
            controller.addMarker(first.lat, first.lng, "", MarkerType.GREEN)
            val last = samples.last()
            controller.addMarker(last.lat, last.lng, "", MarkerType.RED)
            liveMarker = controller.addMarker(last.lat, last.lng, "", MarkerType.ORANGE)
            // 绿色 V 形箭头：指向**手机朝向**（地图标记是位图、无法旋转，故用两条折线拼成 V 字）
            val heading = headingProvider.heading
                ?: TrajectoryGeometry.headingAt(
                    points = samples.map { TrajectoryPoint(it.lat, it.lng, it.tOffsetSec) },
                    index = samples.size - 1
                )
            val arrowPair = TrajectoryGeometry.arrowPoints(last.lat, last.lng, heading)
            controller.addPolyline(arrowPair.first, ARROW_COLOR, 5f)
            controller.addPolyline(arrowPair.second, ARROW_COLOR, 5f)
            controller.addPolyline(arrowPair.second, ARROW_COLOR, 5f)
            // 视野跟随已录范围
            controller.fitBounds(pts, 60)
            drawnPointCount = samples.size
        } else if (liveMarker != null) {
            val last = samples.last()
            liveMarker?.setPosition(last.lat, last.lng)
        }
    }

    val stats = uiState.trajectoryStats
    // stringResource 只能在 Composable 上下文取，这里先取好，供按钮回调里使用
    val defaultName = stringResource(R.string.trajectory_default_name)

    // 录制结束 → 弹出命名框（输入框内容本地保存，确认后才落库）
    var naming by remember { mutableStateOf(false) }
    var savedToLibrary by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    LaunchedEffect(uiState.pendingTrajectoryPoints.size) {
        if (uiState.pendingTrajectoryPoints.isNotEmpty() && !naming) {
            nameInput = ""
            naming = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .noRippleClickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.trajectory_page_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                AppMapView(
                    mapEngine = uiState.mapEngine,
                    isDomestic = viewModel.isDomesticEnvironment(),
                    modifier = Modifier.fillMaxSize(),
                    onMapReady = { controller ->
                        mapController = controller
                        controller.disableUiControls()
                        val samples = TrajectoryRecordService.rawPoints
                        if (samples.isNotEmpty()) {
                            controller.fitBounds(samples.map { it.lat to it.lng }, 60)
                        } else {
                            // 还没定位到原点：先回到当前位置（等 GPS 首帧到达），
                            // 否则地图会停在默认的“世界视野”，用户看不到自己在哪。
                            val lat = uiState.latitudeInput.toDoubleOrNull()
                            val lng = uiState.longitudeInput.toDoubleOrNull()
                            if (lat != null && lng != null) {
                                controller.moveCamera(lat, lng, 17f)
                            }
                        }
                    }
                )
            }

            // 地图：实时显示正在录制的轨迹（录制前显示空地图，录制后可见已走路径）
            MiuixCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                insideMargin = PaddingValues(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (recording) AccentGreen.copy(alpha = 0.14f) else AccentBlue.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.MyLocation,
                            null,
                            tint = if (recording) AccentGreen else AccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                uiState.awaitingTrajectoryOrigin ->
                                    stringResource(R.string.trajectory_locating_origin)
                                recording -> stringResource(R.string.trajectory_recording_status)
                                else -> stringResource(R.string.trajectory_idle_status)
                            },
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(
                                R.string.trajectory_stats_format,
                                stats.pointCount,
                                String.format(java.util.Locale.US, "%.0f", stats.distanceM),
                                formatDuration(stats.durationSec)
                            ),
                            color = AppColors.textSecondary(isDark),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!recording) {
                        PrimaryButton(
                            text = stringResource(R.string.trajectory_start),
                            tint = AccentGreen,
                            modifier = Modifier.weight(1f),
                            enabled = true
                        ) { viewModel.startTrajectoryRecording(context) }
                    } else {
                        PrimaryButton(
                            text = stringResource(R.string.trajectory_stop),
                            tint = AccentGreen,
                            modifier = Modifier.weight(1f),
                            enabled = true,
                            icon = Icons.Rounded.Stop
                        ) { viewModel.stopTrajectoryRecording(context) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.trajectory_record_hint),
                    color = AppColors.textSecondary(isDark),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )

            // 新增：录制前的选项（起终点相近时自动闭合）
            MiuixCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                insideMargin = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.trajectory_close_loop_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.trajectory_close_loop_desc),
                            fontSize = 11.5.sp,
                            color = AppColors.textSecondary(isDark),
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = uiState.autoCloseTrajectoryLoop,
                        onCheckedChange = { viewModel.setAutoCloseTrajectoryLoop(it) }
                    )
                }
            }
            }

            // 已保存轨迹
            Text(
                text = stringResource(R.string.trajectory_saved_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (uiState.savedTrajectories.isEmpty()) {
                MiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.trajectory_empty),
                        color = AppColors.textSecondary(isDark),
                        fontSize = 13.sp
                    )
                }
            } else {
                uiState.savedTrajectories.forEach { item ->
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        insideMargin = PaddingValues(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = stringResource(
                                        R.string.trajectory_item_format,
                                        item.pointCount,
                                        String.format(java.util.Locale.US, "%.0f", item.distanceM),
                                        formatDuration(item.durationSec)
                                    ),
                                    color = AppColors.textSecondary(isDark),
                                    fontSize = 12.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .noRippleClickable { viewModel.deleteTrajectory(item.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    stringResource(R.string.trajectory_delete),
                                    tint = AccentGreen.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (naming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                naming = false
                viewModel.discardPendingTrajectory()
            },
            title = { Text(stringResource(R.string.trajectory_name_title)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.trajectory_name_hint)) }
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val pts = uiState.pendingTrajectoryPoints
                    naming = false
                    viewModel.saveTrajectory(
                        context,
                        nameInput.ifBlank { defaultName },
                        pts
                    )
                    savedToLibrary = true
                    savedToLibrary = true
                    viewModel.discardPendingTrajectory()
                }) { Text(stringResource(R.string.trajectory_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    naming = false
                    viewModel.discardPendingTrajectory()
                }) { Text(stringResource(R.string.trajectory_delete)) }
            }
        )
    }

    // 轨迹已保存到路线库：提示用户，并给出跳转到路线库的入口
    if (savedToLibrary) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { savedToLibrary = false },
            title = { Text(stringResource(R.string.trajectory_saved_to_route_library)) },
            text = {
                Text(
                    text = stringResource(R.string.trajectory_saved_to_route_library),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    savedToLibrary = false
                    onGoToRouteLibrary()
                }) { Text(stringResource(R.string.trajectory_go_to_library)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { savedToLibrary = false }) {
                    Text(stringResource(R.string.trajectory_not_saved))
                }
            }
        )
    }

    // 轨迹已保存到路线库：提示用户，并给出跳转到路线库的入口
    if (savedToLibrary) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { savedToLibrary = false },
            title = { Text(stringResource(R.string.trajectory_saved_to_route_library)) },
            text = {
                Text(
                    text = stringResource(R.string.trajectory_saved_to_route_library),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    savedToLibrary = false
                    onGoToRouteLibrary()
                }) { Text(stringResource(R.string.trajectory_go_to_library)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { savedToLibrary = false }) {
                    Text(stringResource(R.string.trajectory_not_saved))
                }
            }
        )
    }
}

private const val ACCENT_ORANGE = 0xFFFF8A00.toInt()

/** 朝向箭头颜色（绿色，与橙色轨迹区分） */
private const val ARROW_COLOR = 0xFF00B578.toInt()
/** 朝向箭头颜色（绿色，与橙色轨迹区分） */

@Composable
private fun PrimaryButton(
    text: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) tint else tint.copy(alpha = 0.3f))
            .noRippleClickable { if (enabled) onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 秒 → “5分12秒” / “1时03分” */
private fun formatDuration(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d时%02d分", h, m)
    else String.format(java.util.Locale.US, "%d分%02d秒", m, s)
}
