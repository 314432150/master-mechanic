package com.example.mastermechanic.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.mastermechanic.R
import com.example.mastermechanic.action.ClickDispatch
import com.example.mastermechanic.action.ClickMode
import com.example.mastermechanic.auth.AuthItem
import com.example.mastermechanic.auth.AuthState
import com.example.mastermechanic.auth.AuthStatus
import com.example.mastermechanic.auth.AuthorizationChecks
import com.example.mastermechanic.auth.AuthorizationSummary
import com.example.mastermechanic.auth.CaptureSessionState
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.floating.FloatingPositionStore
import com.example.mastermechanic.floating.FloatingPositions
import com.example.mastermechanic.floating.FloatingScreen
import com.example.mastermechanic.floating.FloatingSide
import com.example.mastermechanic.floating.LabelPosition
import com.example.mastermechanic.service.CaptureService
import com.example.mastermechanic.service.ResidentService
import com.example.mastermechanic.settings.AppPreferencesStore
import com.example.mastermechanic.ui.theme.MasterMechanicTheme
import kotlinx.coroutines.delay

/**
 * 授权流入口（M0-T0-2）：展示各关键授权状态，并提供一键前往授予。
 *
 * @param resumeTick 每次回到前台自增，用于从系统设置页返回后刷新状态。
 * @param onOpenCalibration 进入「识别标定」页（T1-5b）。
 * @param onOpenPatrolConfig 进入「巡查配置」页（M3-T3-3，FR-03）。
 */
@Composable
fun AuthorizationRoute(
    resumeTick: Int,
    onOpenCalibration: () -> Unit,
    onOpenPatrolConfig: () -> Unit,
) {
    val context = LocalContext.current
    var captureActive by remember { mutableStateOf(CaptureSessionSignal.isActive) }
    var refreshTick by remember { mutableStateOf(0) }
    var residentRunning by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var reconcileTick by remember { mutableStateOf(0) }

    val statuses = remember(resumeTick, captureActive, refreshTick) {
        AuthorizationChecks.collect(
            context,
            if (captureActive) CaptureSessionState.GRANTED else CaptureSessionState.NOT_GRANTED,
        )
    }

    fun refreshRuntime() {
        residentRunning = ResidentService.isRunning(context)
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        captureActive = CaptureSessionSignal.isActive
    }

    // 采集会话状态以真实信号为准（T1-2）：会话建立 / 终止（含系统侧）均实时反映到界面
    DisposableEffect(Unit) {
        val listener: (CaptureSessionStatus) -> Unit = {
            captureActive = it == CaptureSessionStatus.ACTIVE
        }
        CaptureSessionSignal.addListener(listener)
        onDispose { CaptureSessionSignal.removeListener(listener) }
    }

    // 进入 / 回到前台时刷新运行状态（含从系统设置页返回）
    LaunchedEffect(resumeTick) { refreshRuntime() }

    // 启动 / 停止后乐观更新，再延迟校正一次（服务启停为异步操作）
    LaunchedEffect(reconcileTick) {
        if (reconcileTick > 0) {
            delay(400)
            refreshRuntime()
        }
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // 授权凭证不落盘、不复用（ADR-001）；授权通过即建立真实采集会话（T1-2）
            CaptureService.start(context, result.resultCode, data)
        }
        refreshTick++
        reconcileTick++
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    AuthorizationScreen(
        statuses = statuses,
        residentRunning = residentRunning,
        notificationsEnabled = notificationsEnabled,
        onOpenAccessibilitySettings = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onRequestCapture = {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            captureLauncher.launch(manager.createScreenCaptureIntent())
        },
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onStartResident = {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ResidentService::class.java),
            )
            residentRunning = true // 乐观更新，400ms 后由 reconcileTick 校正
            reconcileTick++
        },
        onStopResident = {
            context.stopService(Intent(context, ResidentService::class.java))
            residentRunning = false // 乐观更新，400ms 后由 reconcileTick 校正
            reconcileTick++
        },
        onStopCapture = {
            context.stopService(Intent(context, CaptureService::class.java))
            reconcileTick++ // 服务停止后本页状态延迟校正
        },
        onOpenCalibration = onOpenCalibration,
        onOpenPatrolConfig = onOpenPatrolConfig,
    )
}

@Composable
fun AuthorizationScreen(
    statuses: List<AuthStatus>,
    residentRunning: Boolean,
    notificationsEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestCapture: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStartResident: () -> Unit,
    onStopResident: () -> Unit,
    onStopCapture: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenPatrolConfig: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.auth_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.auth_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            statuses.forEach { status ->
                AuthorizationCard(
                    status = status,
                    onAction = when (status.item) {
                        AuthItem.ACCESSIBILITY -> onOpenAccessibilitySettings
                        AuthItem.SCREEN_CAPTURE -> onRequestCapture
                        AuthItem.NOTIFICATIONS -> onRequestNotifications
                    },
                    trailing = if (
                        status.item == AuthItem.SCREEN_CAPTURE && status.state == AuthState.GRANTED
                    ) {
                        {
                            OutlinedButton(onClick = onStopCapture) {
                                Text(text = stringResource(R.string.auth_capture_action_stop))
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            SummaryText(statuses = statuses)
            ResidentCard(
                running = residentRunning,
                notificationsEnabled = notificationsEnabled,
                onStart = onStartResident,
                onStop = onStopResident,
            )
            RunModeCard()
            FloatingCard()
            OutlinedButton(
                onClick = onOpenCalibration,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.auth_open_calibration))
            }
            OutlinedButton(
                onClick = onOpenPatrolConfig,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.auth_open_patrol_config))
            }
        }
    }
}

@Composable
private fun AuthorizationCard(
    status: AuthStatus,
    onAction: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(itemLabel(status.item)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(itemStatusText(status)),
                    style = MaterialTheme.typography.labelLarge,
                    color = when (status.state) {
                        AuthState.GRANTED -> MaterialTheme.colorScheme.primary
                        AuthState.MISSING -> MaterialTheme.colorScheme.error
                        AuthState.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = stringResource(itemDescription(status.item)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.state == AuthState.MISSING) {
                Spacer(modifier = Modifier.height(2.dp))
                Button(onClick = onAction) {
                    Text(text = stringResource(itemActionText(status.item)))
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.height(2.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun ResidentCard(
    running: Boolean,
    notificationsEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.resident_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        if (running) R.string.resident_status_running else R.string.resident_status_stopped
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = stringResource(R.string.resident_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (running && !notificationsEnabled) {
                Text(
                    text = stringResource(R.string.resident_notify_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            if (running) {
                OutlinedButton(onClick = onStop) {
                    Text(text = stringResource(R.string.resident_action_stop))
                }
            } else {
                Button(onClick = onStart) {
                    Text(text = stringResource(R.string.resident_action_start))
                }
            }
        }
    }
}

/**
 * 运行方式卡片（T1-7 引入；T2-6 开放实点开关，对应 B5）：展示当前模式并允许用户切换。
 *
 * **开启实点必须二次确认**（默认拒绝）：实点会让程序真的点击游戏界面，误触代价不可逆。
 * **三条安全边界**（默认一律回到演练，实点只能由用户在本次会话内显式开启）：
 * 进程启动 = 演练；无障碍服务断开 / 被中断（[ClickDispatch.uninstall]）= 回演练；
 * 采集会话（重）建立（[com.example.mastermechanic.service.CaptureService]）= 回演练。
 *
 * **2026-09-14 补充**：授予采集权限会把用户带到游戏，用户**没法在授权后再切回来开实点**
 * → 增设显式偏好「建立采集会话后保留运行方式」（默认关）。关着 = 上面的第三条边界照旧；
 * 打开 = 保留用户上次的选择（用户自己承担"每次会话都要重新确认"的取舍）。
 */
@Composable
private fun RunModeCard() {
    val context = LocalContext.current
    val mode by ClickDispatch.modeFlow.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    val live = mode == ClickMode.LIVE
    var keepOnSession by remember {
        mutableStateOf(
            AppPreferencesStore.loadOrRecover(context).preferences.keepClickModeOnSessionStart,
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.run_mode_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        if (live) R.string.run_mode_live else R.string.run_mode_dry_run,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (live) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Text(
                text = stringResource(
                    if (live) R.string.run_mode_live_desc else R.string.run_mode_drill_desc,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.run_mode_live_switch),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = live,
                    onCheckedChange = { checked ->
                        if (checked) {
                            confirming = true // 开启先确认；取消 = 保持演练（默认拒绝）
                        } else {
                            ClickDispatch.enableDrill()
                        }
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.run_mode_keep_on_session),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.run_mode_keep_on_session_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = keepOnSession,
                    onCheckedChange = { checked ->
                        keepOnSession = checked
                        AppPreferencesStore.save(
                            context,
                            AppPreferencesStore.loadOrRecover(context).preferences.copy(
                                keepClickModeOnSessionStart = checked,
                            ),
                        )
                    },
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.run_mode_live_confirm_title)) },
            text = { Text(stringResource(R.string.run_mode_live_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        ClickDispatch.enableLive()
                        confirming = false
                    },
                ) {
                    Text(stringResource(R.string.run_mode_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.run_mode_confirm_cancel))
                }
            },
        )
    }
}

/**
 * 悬浮窗位置卡（M3-T3-4 / T3-7 / FR-07）：展示两个部件各自的位置 + 「重置到默认位置」。
 *
 * 为什么要有这个入口：FR-07 要求位置"始终可被找回"——正常情况拖回来即可，
 * 但极端情况（部件被拖到难以触达处 / 换了分辨率）需要一个不依赖悬浮窗本身的自救入口。
 * 只在 MM 前台可见（悬浮窗只在游戏前台显示，此时不可见），重置在下次挂载时生效。
 */
@Composable
private fun FloatingCard() {
    val context = LocalContext.current
    var positions by remember {
        mutableStateOf(FloatingPositionStore.loadOrNull(context) ?: FloatingPositions.DEFAULT)
    }
    var message by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.auth_floating_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.auth_floating_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.auth_floating_current,
                    stringResource(sideLabel(positions.handle.side)),
                    stringResource(labelPositionLabel(positions.label)),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(
                onClick = {
                    val screen = FloatingScreen.spec(context)
                    FloatingPositionStore.reset(context, screen.width, screen.height)
                    positions = FloatingPositions.DEFAULT
                    message = context.getString(R.string.auth_floating_reset_done)
                },
            ) {
                Text(text = stringResource(R.string.auth_floating_reset))
            }
        }
    }
}

@StringRes
private fun sideLabel(side: FloatingSide): Int = when (side) {
    FloatingSide.LEFT -> R.string.floating_side_left
    FloatingSide.RIGHT -> R.string.floating_side_right
}

@StringRes
private fun labelPositionLabel(position: LabelPosition): Int =
    if (position == LabelPosition.DEFAULT) {
        R.string.floating_label_default
    } else {
        R.string.floating_label_custom
    }

@Composable
private fun SummaryText(statuses: List<AuthStatus>) {
    if (AuthorizationSummary.isAllReady(statuses)) {
        Text(
            text = stringResource(R.string.auth_summary_ready),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        val missingLabels = AuthorizationSummary.missingItems(statuses)
            .map { stringResource(itemLabel(it)) }
        Text(
            text = stringResource(R.string.auth_summary_missing, missingLabels.joinToString("、")),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@StringRes
private fun itemLabel(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_label
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_label
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_label
}

@StringRes
private fun itemDescription(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_desc
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_desc
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_desc
}

@StringRes
private fun itemActionText(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_action
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_action
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_action
}

@StringRes
private fun itemStatusText(status: AuthStatus): Int = when (status.item) {
    AuthItem.ACCESSIBILITY -> if (status.state == AuthState.GRANTED) {
        R.string.auth_status_enabled
    } else {
        R.string.auth_status_disabled
    }
    AuthItem.SCREEN_CAPTURE -> if (status.state == AuthState.GRANTED) {
        R.string.auth_capture_status_granted
    } else {
        R.string.auth_capture_status_missing
    }
    AuthItem.NOTIFICATIONS -> when (status.state) {
        AuthState.GRANTED -> R.string.auth_notifications_status_granted
        AuthState.MISSING -> R.string.auth_notifications_status_missing
        AuthState.NOT_APPLICABLE -> R.string.auth_status_not_applicable
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthorizationScreenPreview() {
    MasterMechanicTheme {
        AuthorizationScreen(
            statuses = listOf(
                AuthStatus(AuthItem.ACCESSIBILITY, AuthState.GRANTED),
                AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.MISSING),
                AuthStatus(AuthItem.NOTIFICATIONS, AuthState.MISSING),
            ),
            residentRunning = false,
            notificationsEnabled = true,
            onOpenAccessibilitySettings = {},
            onRequestCapture = {},
            onRequestNotifications = {},
            onStartResident = {},
            onStopResident = {},
            onStopCapture = {},
            onOpenCalibration = {},
            onOpenPatrolConfig = {},
        )
    }
}
