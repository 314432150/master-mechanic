package com.example.mastermechanic.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
import com.example.mastermechanic.auth.AuthItem
import com.example.mastermechanic.auth.AuthState
import com.example.mastermechanic.auth.AuthStatus
import com.example.mastermechanic.auth.AuthorizationChecks
import com.example.mastermechanic.auth.AuthorizationSummary
import com.example.mastermechanic.auth.CaptureSessionState
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.service.CaptureService
import com.example.mastermechanic.service.ResidentService
import com.example.mastermechanic.ui.theme.MasterMechanicTheme
import com.example.mastermechanic.ui.theme.Warning40
import kotlinx.coroutines.delay

/**
 * 授权流入口（M0-T0-2）：展示各关键授权状态，并提供一键前往授予。
 *
 * @param resumeTick 每次回到前台自增，用于从系统设置页返回后刷新状态。
 * @param onOpenCalibration 进入「识别标定」页（T1-5b）。
 * @param onOpenServerList 进入「服务器清单」页（M3-T3-9，FR-10）。
 * @param onOpenFriendList 进入「好友清单」页（M3-T3-8，FR-07「拜访」三级列表的数据源）。
 */
@Composable
fun AuthorizationRoute(
    resumeTick: Int,
    onOpenCalibration: () -> Unit,
    onOpenServerList: () -> Unit,
    onOpenFriendList: () -> Unit,
) {
    val context = LocalContext.current
    var captureActive by remember { mutableStateOf(CaptureSessionSignal.isActive) }
    var refreshTick by remember { mutableStateOf(0) }
    var residentRunning by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var reconcileTick by remember { mutableStateOf(0) }

    // residentRunning 也在 key 里：守护起停会改变"是否全部就绪"（2026-09-20 起它是必备项）
    val statuses = remember(resumeTick, captureActive, refreshTick, residentRunning) {
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

    // **重装后无障碍状态会"晚一点才对"**（T4-8，2026-09-24 用户报：打包重装后系统侧已开启，App 内却显示
    // 「去开启」；进一次系统设置再返回就变"已开启"）。⇒ 不是缺"回到前台重算"的挂点（`resumeTick` 那条
    // 一直在），而是**重装后系统那份"已启用无障碍服务"列表短暂未就绪**，而界面只在 resume 那一刻查了一次。
    // 处置：resume 后**补几次查询**（400ms / +800ms / +800ms），拿到 GRANTED 立刻停；三次都不行就如实保持 MISSING
    // （与 `reconcileTick` 那条 400ms 校正同一套路，不引入新的生命周期观察方式）。
    LaunchedEffect(resumeTick) {
        repeat(3) { attempt ->
            delay(if (attempt == 0) 400L else 800L)
            refreshTick++
            if (AuthorizationChecks.accessibilityState(context) == AuthState.GRANTED) return@LaunchedEffect
        }
    }

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
            // minSdk 34 起通知权限始终需要运行时申请（不再有"低版本无需申请"那条分支）
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        onOpenServerList = onOpenServerList,
        onOpenFriendList = onOpenFriendList,
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
    onOpenServerList: () -> Unit,
    onOpenFriendList: () -> Unit,
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
                        // 常驻守护（2026-09-20 起为必备项）：未启动时这张卡的按钮就是"启动守护"
                        AuthItem.RESIDENT -> onStartResident
                        AuthItem.NOTIFICATIONS -> onRequestNotifications
                    },
                    hint = if (
                        status.item == AuthItem.RESIDENT &&
                        status.state == AuthState.GRANTED &&
                        !notificationsEnabled
                    ) {
                        stringResource(R.string.resident_notify_warning)
                    } else {
                        null
                    },
                    trailing = when {
                        status.item == AuthItem.SCREEN_CAPTURE && status.state == AuthState.GRANTED -> {
                            {
                                OutlinedButton(onClick = onStopCapture) {
                                    Text(text = stringResource(R.string.auth_capture_action_stop))
                                }
                            }
                        }

                        status.item == AuthItem.RESIDENT && status.state == AuthState.GRANTED -> {
                            {
                                OutlinedButton(onClick = onStopResident) {
                                    Text(text = stringResource(R.string.resident_action_stop))
                                }
                            }
                        }

                        else -> null
                    },
                )
            }
            SummaryText(statuses = statuses)
            OutlinedButton(
                onClick = onOpenCalibration,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.auth_open_calibration))
            }
            OutlinedButton(
                onClick = onOpenServerList,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.auth_open_server_list))
            }
            OutlinedButton(
                onClick = onOpenFriendList,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.auth_open_friend_list))
            }
        }
    }
}

@Composable
private fun AuthorizationCard(
    status: AuthStatus,
    onAction: () -> Unit,
    /** 附加提醒（如"常驻守护在跑但通知不可见"）：红字，排在描述之后。 */
    hint: String? = null,
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
                    },
                )
            }
            Text(
                text = stringResource(itemDescription(status.item)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                // **提示 / 隐患用琥珀，不用红**（用户 2026-10-01 口径："提示类信息不应该使用红色"）：
                // 这条说的是"守护还在跑、但有隐患"，不是"当前不可用" —— 红色会读成"坏了"。
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning40,
                )
            }
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
    AuthItem.RESIDENT -> R.string.auth_resident_label
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_label
}

@StringRes
private fun itemDescription(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_desc
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_desc
    AuthItem.RESIDENT -> R.string.resident_desc
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_desc
}

@StringRes
private fun itemActionText(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_action
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_action
    AuthItem.RESIDENT -> R.string.resident_action_start
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
    AuthItem.RESIDENT -> if (status.state == AuthState.GRANTED) {
        R.string.resident_status_running
    } else {
        R.string.resident_status_stopped
    }
    AuthItem.NOTIFICATIONS -> when (status.state) {
        AuthState.GRANTED -> R.string.auth_notifications_status_granted
        AuthState.MISSING -> R.string.auth_notifications_status_missing
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
            onOpenServerList = {},
            onOpenFriendList = {},
        )
    }
}
