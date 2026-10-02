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
import androidx.compose.foundation.layout.WindowInsets
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
import com.example.mastermechanic.auth.AuthGuide
import com.example.mastermechanic.auth.AuthItem
import com.example.mastermechanic.auth.AuthState
import com.example.mastermechanic.auth.AuthStatus
import com.example.mastermechanic.auth.AuthorizationChecks
import com.example.mastermechanic.auth.AuthorizationSummary
import com.example.mastermechanic.auth.CaptureSessionState
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.service.CaptureService
import com.example.mastermechanic.service.ResidentService
import com.example.mastermechanic.ui.theme.MasterMechanicTheme
import com.example.mastermechanic.ui.theme.Warning40
import kotlinx.coroutines.delay

/**
 * 日志标签（2026-10-02 起本页也留痕：采集为什么被挡住要在日志里读得出来）。
 *
 * ⚠ 名字**必须**带前缀：同包 `CalibrationRoute.kt` 里已经有一个 `internal const val TAG`，
 * 这里再声明一个同名的顶层 `TAG` 就是 `Conflicting declarations`（U5 拆文件时踩过同一类坑：
 * 顶层声明放宽可见性 / 重名都会在同包内撞车）。
 */
private const val AUTH_LOG_TAG = "MM-Auth"

/**
 * 授权流入口（M0-T0-2）：展示各关键授权状态，并提供一键前往授予。
 *
 * @param resumeTick 每次回到前台自增，用于从系统设置页返回后刷新状态。
 * @param reauthTick **一键重新授权采集**的待处理请求（>0 = 有一条；见 [MainActivity.EXTRA_REAUTH_CAPTURE]）：
 *   悬浮窗菜单点「⟳ 重新授权采集」⇒ App 被拉到前台并带上它 ⇒ 本页**自动**拉起系统采集授权弹窗。
 * @param onReauthHandled 本页已经把请求兑现（弹窗已拉起）⇒ 通知调用方清零，避免切页回来又弹一次。
 * @param onOpenCalibration 进入「识别标定」页（T1-5b）。
 * @param onOpenServerList 进入「服务器清单」页（M3-T3-9，FR-10）。
 * @param onOpenFriendList 进入「好友清单」页（M3-T3-8，FR-07「拜访」三级列表的数据源）。
 */
@Composable
fun AuthorizationRoute(
    resumeTick: Int,
    reauthTick: Int,
    onReauthHandled: () -> Unit,
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

    /**
     * **自动拉起采集被挡住**时的说明（2026-10-02 用户报障后加）。
     *
     * 报障原文："点击『去授权与权限』的逻辑有问题，目前是直接拉起采集授权操作，**一同意就跳转到游戏了**，
     * 此时**无障碍和守护可能还没启动**，又要切换回 app 来启动这两项。"
     * ⇒ 采集**永远是最后一步**（判据 [AuthGuide]）：前面还有缺项时**不**拉系统弹窗，
     * 改为把这句话摆在页面上 —— 用户刚点了一下，必须当场得到回应（静默 = "点了没反应" ✗）。
     */
    var blockedNote by remember { mutableStateOf<String?>(null) }

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

    /**
     * 拉起**系统**的屏幕采集授权弹窗（唯一入口，两处共用：授权页那颗按钮 + 悬浮窗菜单的
     * 「一键重新授权采集」）。用户确认后由 [captureLauncher] 建立真实采集会话。
     *
     * ⚠ 定义必须排在 [captureLauncher] **之后**：局部函数不能前向引用尚未声明的局部 val。
     */
    fun requestCapture() {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    // **一键重新授权采集**（2026-10-01 用户口径，M5 期间插入）：悬浮窗菜单那一行把本页拉到前台并带上
    // [reauthTick] ⇒ 这里**自动**把系统采集授权弹窗拉起来（用户只需在弹窗里选「共享一个应用」+ 选中游戏）。
    //
    // 为什么"必须经 App 这一趟"：系统授权弹窗**只能由 Activity 用 `startActivityForResult` 拉起**，
    // 而凭证不落盘、只经内存交给 `CaptureService`（ADR-001）—— 悬浮窗是无障碍 Service，没有 result 通道。
    // 效果上仍是"一键"：菜单点一下 ⇒ 弹窗直接出现在眼前（原来要"返回 App → 找授权页 → 点建立采集"三步）。
    // FR-08「授权必须由用户在前台界面主动确认」仍然成立：**确认这个动作永远由用户在弹窗里做**，
    // 我们只是把弹窗端到他面前。
    //
    // ⚠ 只以 [reauthTick] 为键，且**兑现后立刻回调清零**（[onReauthHandled]）：
    // 若把它并进 `resumeTick` 这类键，用户刚从授权弹窗返回就又会被弹一次（死循环）；
    // 若不清零，用户切去别的页再切回来也会重弹。
    LaunchedEffect(reauthTick) {
        if (reauthTick <= 0) return@LaunchedEffect
        // 先让本页走到 RESUMED：组合期（onCreate 里）直接 launch 时还没有前台 Activity，弹窗会被丢掉
        delay(250)
        // **采集放最后**（2026-10-02）：按**当下**的授权情况判一次 —— 不用组合期那份快照，
        // 因为用户可能刚从系统设置里开完无障碍返回（那份快照可能是旧的）。
        val fresh = AuthorizationChecks.collect(
            context,
            if (CaptureSessionSignal.isActive) CaptureSessionState.GRANTED else CaptureSessionState.NOT_GRANTED,
        )
        val blockers = AuthGuide.blockingCapture(fresh)
        if (blockers.isEmpty()) {
            blockedNote = null
            requestCapture()
        } else {
            val labels = blockers.map { context.getString(authItemLabelRes(it)) }.joinToString("、")
            blockedNote = context.getString(R.string.auth_capture_not_launched, labels)
            // 日志必须能读出"是**判据**挡住的"（用户排障就是拿日志看时间线：哪一刻该发生什么没发生）
            MmLog.w(
                AUTH_LOG_TAG,
                "采集授权**没有**拉起：还缺 $labels —— 采集留到最后（一授权画面就切到游戏）；已在授权页提示",
            )
            refreshTick++
        }
        onReauthHandled()
    }

    AuthorizationScreen(
        statuses = statuses,
        // 自动拉起被挡住的说明（null = 没有这回事）——页面上要当场说清，不许静默
        blockedNote = blockedNote,
        residentRunning = residentRunning,
        notificationsEnabled = notificationsEnabled,
        onOpenAccessibilitySettings = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onRequestCapture = { requestCapture() },
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
    /** 自动拉起采集被**判据**挡住时的说明（见 [AuthGuide]）；null = 没有这回事。 */
    blockedNote: String? = null,
) {
    // **采集前面还缺哪些项**（2026-10-02）：采集卡上要写清"先办什么"，否则用户在那张卡上按下去
    // 就会离开 App（画面切到游戏），回来还得为上面几项再切一趟。
    // ⚠ 用 `map`（inline）取标签：`joinToString` 不是 inline，里面**不能**调 `stringResource`。
    val blockers = AuthGuide.blockingCapture(statuses)
    val blockerLabels = blockers.map { stringResource(authItemLabelRes(it)) }.joinToString("、")
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 与清单页同一套：**inset 只由壳消费一次**（这个 `Scaffold` 只为给页面一个落脚容器，
        // 系统栏若再算一遍 ⇒ 顶部多出一段空白 ✗；2026-10-02 全库排查时补齐）。
        contentWindowInsets = WindowInsets(0.dp),
    ) { innerPadding ->
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
            // 用户刚点了「去授权与权限 / ⟳ 重新授权采集」，而采集被**判据**挡住 ⇒ 当场说清为什么不弹窗
            // （2026-10-02：把"点了没反应"变成"一句话告诉你先办什么"）
            if (blockedNote != null) {
                Text(
                    text = blockedNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Warning40,
                )
            }
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
                    hint = when {
                        // 守护在跑但通知不可见（2026-10-01 口径：提示类信息用琥珀，不用红）
                        status.item == AuthItem.RESIDENT &&
                            status.state == AuthState.GRANTED &&
                            !notificationsEnabled -> stringResource(R.string.resident_notify_warning)

                        // **采集留到最后**（2026-10-02）：前面还有缺项 ⇒ 这张卡自己说清"先办什么"，
                        // 免得用户在这里按下去就直接离开 App，回来还得为上面几项再切一趟。
                        status.item == AuthItem.SCREEN_CAPTURE &&
                            status.state == AuthState.MISSING &&
                            blockers.isNotEmpty() ->
                            stringResource(R.string.auth_capture_last_hint, blockerLabels)

                        else -> null
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
                    text = stringResource(authItemLabelRes(status.item)),
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
            .map { stringResource(authItemLabelRes(it)) }
        Text(
            text = stringResource(R.string.auth_summary_missing, missingLabels.joinToString("、")),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
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
