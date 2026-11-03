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
import com.vincenthzr.locationspoofer.ui.service.TrajectoryRecordService
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
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current

    // 进入页面时启动“每秒刷新录制统计”的轮询（ViewModel 内部按录制状态开关）
    LaunchedEffect(Unit) { viewModel.pollRecordingStats() }

    val recording = uiState.isTrajectoryRecording
    val stats = uiState.trajectoryStats
    // stringResource 只能在 Composable 上下文取，这里先取好，供按钮回调里使用
    val defaultName = stringResource(R.string.trajectory_default_name)

    // 录制结束 → 弹出命名框（输入框内容本地保存，确认后才落库）
    var naming by remember { mutableStateOf(false) }
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
            // 录制控制卡
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
                            text = if (recording) {
                                stringResource(R.string.trajectory_recording_status)
                            } else {
                                stringResource(R.string.trajectory_idle_status)
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
}

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
