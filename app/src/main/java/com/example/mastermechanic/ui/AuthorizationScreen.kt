package com.example.mastermechanic.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.mastermechanic.R
import com.example.mastermechanic.auth.AUTHORIZATION_DISPLAY_ORDER
import com.example.mastermechanic.auth.AuthGuide
import com.example.mastermechanic.auth.AuthItem
import com.example.mastermechanic.auth.AuthState
import com.example.mastermechanic.auth.AuthStatus
import com.example.mastermechanic.auth.AuthorizationChecks
import com.example.mastermechanic.auth.CaptureSessionState
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.service.CaptureService
import com.example.mastermechanic.service.ResidentService
import com.example.mastermechanic.ui.theme.MasterMechanicTheme
import com.example.mastermechanic.ui.theme.Success40
import com.example.mastermechanic.ui.theme.Warning40
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 日志标签（2026-10-02 起本页也留痕：采集为什么被挡住要在日志里读得出来）。
 *
 * ⚠ 名字**必须**带前缀：同包 `CalibrationRoute.kt` 里已经有一个 `internal const val TAG`，
 * 这里再声明一个同名的顶层 `TAG` 就是 `Conflicting declarations`（U5 拆文件时踩过同一类坑：
 * 顶层声明放宽可见性 / 重名都会在同包内撞车）。
 */
private const val AUTH_LOG_TAG = "MM-Auth"

/**
 * **授权通过后，先把本 App 移出屏幕，再等这么久才建立采集会话**（2026-10-20，真机根因处置 A）。
 *
 * ## 为什么（`mm-log-20261020` 三次会话实录）
 *
 * vivo V2463A / Android 16 上，**采集画面里一旦出现本 App 自己的窗口，平台就会在几秒内停止投喂**：
 * `onImageAvailable` 回调彻底归零，而 `onCapturedContentResize` 照来（会话没死，是合成器不喂了）。
 *
 * | 会话建立那一刻的前台 | 存活 |
 * | --- | --- |
 * | 本 App 授权页 | 0.6s / 1.2s 后零帧（01:07 / 01:18 两次） |
 * | 系统安装器（本 App 被完全遮住） | 7 分钟，直到 01:17:59 本 App 重新回到屏幕上 |
 *
 * ⇒ 授权一通过就 `moveTaskToBack`，等屏幕切走（动画 ~300ms）再交给 [CaptureService] 建镜像。
 * 1 秒是"够切走、又不至于让一次性授权凭证等太久"的折中（Android 14+ 要求拿到凭证后尽快创建）。
 *
 * ⚠ 只有**建会话那一刻**本 App 在屏前才会掉进不可恢复的零帧。**运行期回 App 不致命**（2026-10-20 真机反向验证：
 * 回 App 期间断流 38 秒、切回游戏自动恢复，未触发自救、无需重新授权）—— 别被"断流"这三个字误导去做多余的重建。
 */
private const val CAPTURE_START_HOLD_MS = 1000L

/**
 * 从任一 `Context` 里挖出宿主 Activity（`LocalContext` 可能是 `ContextThemeWrapper` 包一层的）。
 *
 * 只用于 [CAPTURE_START_HOLD_MS] 那条"把自己移出屏幕"：拿不到就当没做成并如实记日志，
 * 不允许因此静默跳过采集建立（采集仍然要建，只是少了这层保护）。
 */
private fun Context.hostActivity(): Activity? {
    var cursor: Context = this
    while (cursor is android.content.ContextWrapper) {
        if (cursor is Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}

/**
 * 授权流入口（M0-T0-2）：展示各关键授权状态，并提供一键前往授予。
 *
 * @param resumeTick 每次回到前台自增，用于从系统设置页返回后刷新状态。
 * @param reauthTick **一键重新授权采集**的待处理请求（>0 = 有一条；见 [MainActivity.EXTRA_REAUTH_CAPTURE]）：
 *   悬浮窗菜单点「⟳ 重新授权采集」⇒ App 被拉到前台并带上它 ⇒ 本页**自动**拉起系统采集授权弹窗。
 * @param onReauthHandled 本页已经把请求兑现（弹窗已拉起）⇒ 通知调用方清零，避免切页回来又弹一次。
 *
 * ⚠ 2026-10-25（M5-U6 收口）：本页**不再有**「识别标定 / 服务器清单 / 好友清单」三颗跳转按钮 ——
 * 换成壳的底栏与抽屉之后，那三颗与导航完全重复（换壳前它们是那些页面**唯一**的入口，所以留了三颗）。
 */
@Composable
fun AuthorizationRoute(
    resumeTick: Int,
    reauthTick: Int,
    onReauthHandled: () -> Unit,
) {
    val context = LocalContext.current
    var captureActive by remember { mutableStateOf(CaptureSessionSignal.isActive) }
    var refreshTick by remember { mutableStateOf(0) }
    var residentRunning by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var reconcileTick by remember { mutableStateOf(0) }

    /**
     * **页面可见期间的轻量轮询**（2026-10-19 补）。
     *
     * 原处置是"resume 后补查 3 次"（见下方注释），但那假设了"状态变化一定伴随一次 resume"。
     * 真机复现：**就停在这一页**，从系统侧把本应用的无障碍摘掉 / 装回，界面纹丝不动 ——
     * 而用户报的正是这条路（重装后系统已授权、App 内却写「去开启」；进一次系统设置再返回才对，
     * 因为返回触发了 resume）。系统那份"已启用服务"列表是**异步**就绪的，只握一个挂点必然漏。
     * ⇒ 页面开着时每秒重算一次；`collect` 是几次普通 binder 调用，视觉不变时 Compose 不会重绘。
     */
    var pollTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            pollTick++
        }
    }

    /**
     * **自动拉起采集被挡住**时的说明（2026-10-02 用户报障后加）。
     *
     * 报障原文："点击『去授权与权限』的逻辑有问题，目前是直接拉起采集授权操作，**一同意就跳转到游戏了**，
     * 此时**无障碍和守护可能还没启动**，又要切换回 app 来启动这两项。"
     * ⇒ 采集**永远是最后一步**（判据 [AuthGuide]）：前面还有缺项时**不**拉系统弹窗，
     * 改为把这句话摆在页面上 —— 用户刚点了一下，必须当场得到回应（静默 = "点了没反应" ✗）。
     */
    var blockedNote by remember { mutableStateOf<String?>(null) }

    // residentRunning 也在 key 里：守护起停会改变"是否全部就绪"（2026-09-20 起它是必备项）；
    // pollTick = 每秒一次的自愈，见上方注释
    val statuses = remember(resumeTick, captureActive, refreshTick, residentRunning, pollTick) {
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
    // ⚠ 2026-10-19 补：这段只是"回到前台后的加速"，**不能当唯一挂点** —— 页面不离开时（就停在这一页等
    // 系统把服务装回来）它一次都不会再跑。兜底是上面的 [pollTick] 每秒轮询。
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

    val captureScope = rememberCoroutineScope()
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // 授权凭证不落盘、不复用（ADR-001）；授权通过即建立真实采集会话（T1-2）
            //
            // **先把自己移出屏幕再建会话**（2026-10-20，见 [CAPTURE_START_HOLD_MS]）：
            // 建会话那一刻屏幕上是本 App 的话，vivo 会在 1 秒内停掉投喂（当天三次会话实录）。
            val code = result.resultCode
            val moved = context.hostActivity()?.moveTaskToBack(true) ?: false
            MmLog.i(
                AUTH_LOG_TAG,
                "采集授权已通过：先把本 App 移出屏幕（${if (moved) "成功" else "未生效：context 不是 Activity"}）" +
                    "，${CAPTURE_START_HOLD_MS}ms 后再建立会话",
            )
            captureScope.launch {
                delay(CAPTURE_START_HOLD_MS)
                CaptureService.start(context, code, data)
            }
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
        // 加入电池优化白名单（2026-10-25）：先试**直达确认框**（需要清单里的 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS），
        // 有些 ROM / 策略下这个 Intent 会被拦 ⇒ 退回"电池优化列表"，让用户自己找到本应用。
        onRequestBatteryWhitelist = {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            val ok = runCatching { context.startActivity(direct) }.isSuccess
            if (!ok) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                MmLog.w(AUTH_LOG_TAG, "电池白名单：直达确认框不可用，已退回到电池优化列表页")
            }
        },
    )
}

@Composable
fun AuthorizationScreen(
    statuses: List<AuthStatus>,
    notificationsEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestCapture: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStartResident: () -> Unit,
    onStopResident: () -> Unit,
    onStopCapture: () -> Unit,
    /**
     * 把本应用**加进电池优化白名单**（2026-10-25 用户口径："保持后台运行"）。
     *
     * 与其它系统权限同一条纪律：我们只能把系统的确认框端到用户面前，真正的开关在系统设置里
     * （移除更是只能去系统页）⇒ 未授权给「去加入」，已加入只报状态。
     */
    onRequestBatteryWhitelist: () -> Unit,
    /** 自动拉起采集被**判据**挡住时的说明（见 [AuthGuide]）；null = 没有这回事。 */
    blockedNote: String? = null,
) {
    // **采集前面还缺哪些项**（2026-10-02）：采集那一行要写清"先办什么"，否则用户点下去
    // 就会离开 App（画面切到游戏），回来还得为上面几项再切一趟。
    // ⚠ 用 `map`（inline）取标签：`joinToString` 不是 inline，里面**不能**调 `stringResource`。
    val blockers = AuthGuide.blockingCapture(statuses)
    val blockerLabels = blockers.map { stringResource(authItemLabelRes(it)) }.joinToString("、")

    // **引导式四步**（M5-U6 收口，2026-10-25）：一屏只说"下一步该办什么"，办完自动推进到下一步。
    // 顺序口径**不在这里**，在 `AuthGuide.nextStep` / `AUTHORIZATION_DISPLAY_ORDER`（采集永远最后）——
    // 那两份有单测钉着，页面只负责照它渲染（别在这儿再抄一份顺序）。
    val next = AuthGuide.nextStep(statuses)
    val byItem = statuses.associateBy { it.item }
    // 两句话先取出来：下面 `hintOf` 是普通函数，非组合上下文里不能调 `stringResource`。
    val residentNotifyWarning = stringResource(R.string.resident_notify_warning)
    val captureLastHint = stringResource(R.string.auth_capture_last_hint, blockerLabels)

    fun actionOf(item: AuthItem): () -> Unit = when (item) {
        AuthItem.ACCESSIBILITY -> onOpenAccessibilitySettings
        AuthItem.SCREEN_CAPTURE -> onRequestCapture
        AuthItem.RESIDENT -> onStartResident
        AuthItem.NOTIFICATIONS -> onRequestNotifications
        AuthItem.BATTERY -> onRequestBatteryWhitelist
    }

    /** 附加提醒：琥珀色（用户 2026-10-01 口径"提示类信息不该用红色"）。 */
    fun hintOf(status: AuthStatus): String? = when {
        status.item == AuthItem.RESIDENT &&
            status.state == AuthState.GRANTED &&
            !notificationsEnabled -> residentNotifyWarning

        status.item == AuthItem.SCREEN_CAPTURE &&
            status.state == AuthState.MISSING &&
            blockers.isNotEmpty() -> captureLastHint

        else -> null
    }

    /**
     * **能由我们停**的两项（守护 / 采集）—— 它们行内是开关，这就是"关"那一半的落点。
     *
     * 非 null 同时也意味着"这一项的两个方向都由我们写" ⇒ [AuthRowTrailing] 据此决定用开关。
     * 无障碍 / 通知**没有**这一项：系统权限 App 内撤不了（只能去系统设置），给个按了不生效的控制更糟。
     */
    fun stopOf(item: AuthItem): (() -> Unit)? = when (item) {
        AuthItem.SCREEN_CAPTURE -> onStopCapture
        AuthItem.RESIDENT -> onStopResident
        AuthItem.ACCESSIBILITY, AuthItem.NOTIFICATIONS, AuthItem.BATTERY -> null
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 与清单页同一套：**inset 只由壳消费一次**（这个 `Scaffold` 只为给页面一个落脚容器，
        // 系统栏若再算一遍 ⇒ 顶部多出一段空白 ✗；2026-10-02 全库排查时补齐）。
        contentWindowInsets = WindowInsets(0.dp),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // 折叠屏展开 / 平板上别把一行拉成一米宽（UX 评审 2026-10-25）
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                // 16 → 12（2026-10-25 收紧）：五步之后总高要留出余量，
                // 间距按段收 4dp，肉眼几乎看不出，但四段就是 16dp
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 页内**不再有大标题**（2026-10-25，M5-U6 收口）：标题由壳出（`nav_auth` = 授权与权限），
                // 再写一行「授权状态」就是双层标题（U1 那次过渡态的口径正好反过来）。
                // 步数**从顺序表算**，不写死在文案里（加电池白名单时"四步"当场过期过一次）。
                Text(
                    text = stringResource(R.string.auth_subtitle, AUTHORIZATION_DISPLAY_ORDER.size),
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
                AuthStepper(statuses = statuses, next = next)
                // 摘要行**只在全部就绪时**出现（UX 评审 2026-10-25）：之前那句「下一步：屏幕采集」
                // 与进度条 + 展开卡说的是同一件事，一屏里把步骤名说了三遍。四个都好了才值得说一句，
                // 因为那时页面没有主按钮、需要一句"确实没事可办了"。
                if (next == null) {
                    Text(
                        text = stringResource(R.string.auth_summary_ready),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // **当前步**：展开卡（描述 + 该办什么 + 唯一的主按钮）。
                // 当前步必然是 MISSING（`next` 的定义）⇒ 这张卡不需要"停止"。
                val current = next?.let { byItem[it] }
                if (current != null) {
                    AuthorizationCard(
                        status = current,
                        onAction = actionOf(current.item),
                        hint = hintOf(current),
                    )
                }
                // **其余三步**：同一张卡里用分隔线排（它们是一张表的行，不是三张独立的卡）。
                // 行尾控件的形态由 `AuthRowTrailing` 按"这一项的两端是不是都由我们写"决定。
                val others = AUTHORIZATION_DISPLAY_ORDER
                    .filter { it != next }
                    .mapNotNull { byItem[it] }
                if (others.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            others.forEachIndexed { index, status ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                                AuthStepRow(
                                    status = status,
                                    hint = hintOf(status),
                                    onAction = actionOf(status.item),
                                    // **不做"已就绪才给"**：开关的两个方向都要有落点
                                    // （关→开 = 启动 / 拉采集弹窗；开→关 = 停止）。系统权限这里是 null。
                                    onStop = stopOf(status.item),
                                )
                            }
                        }
                    }
                }
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
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        // 内边距 16 → 12、段距 6 → 4（2026-10-25 收紧）：这一步是"最占地"的一块，
        // 五步之后它决定了最后一行会不会被挤到第二屏
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 展开卡的首行与下面那些行**共用同一个部件**：名字 + 行尾状态。
            // 当前步必然是 MISSING ⇒ 行尾就是一句「未授权」（红色 = L1）；动作由下面那颗通栏主按钮承担。
            // 卡内没有可点控件（动作在下面的通栏按钮）⇒ 首行不需要 48dp 的点击区，40 够
            AuthStepHeader(
                status = status,
                nameStyle = MaterialTheme.typography.titleMedium,
                minHeight = CARD_HEADER_MIN_HEIGHT,
                trailing = {
                    Text(
                        text = stringResource(R.string.auth_chip_missing),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                },
            )
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
        }
    }
}

/**
 * **四步进度条**（M5-U6 收口，2026-10-25）：四个圆点从左到右 = 屏幕采集永远在最后。
 *
 * 办完的打勾并染主色、当前这步描浅色底、后面的保持灰 —— 用户一眼看出"一共四步、我在第几步"，
 * 而不是面对四张平铺的卡自己判断先后。**顺序不在这里写死**：取 `AUTHORIZATION_DISPLAY_ORDER`。
 */
@Composable
private fun AuthStepper(statuses: List<AuthStatus>, next: AuthItem?) {
    val byItem = statuses.associateBy { it.item }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AUTHORIZATION_DISPLAY_ORDER.forEachIndexed { index, item ->
            val granted = byItem[item]?.state == AuthState.GRANTED
            val current = item == next
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                granted -> MaterialTheme.colorScheme.primary
                                current -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (granted) "✓" else "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (granted) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    // **短名**（无障碍 / 守护 / 通知 / 电池 / 采集）：五列时每列只有 ~66dp，
                    // 全名"电池优化白名单"会折成"电池优化白 / 名单"、把整条撑高一倍（2026-10-25 实测截图）
                    text = stringResource(stepperLabelRes(item)),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        granted -> MaterialTheme.colorScheme.primary
                        current -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // 短名在 2.0× 字号下仍需换行兜底（宁可两行，不可硬切）
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 行高下限：行内可能是开关（48dp 点击区）或按钮，统一留够高度。 */
private val STEP_ROW_MIN_HEIGHT = 48.dp

/** 展开卡首行的下限：卡内**没有**可点控件（动作在下面的通栏按钮），40dp 足够，省 8dp。 */
private val CARD_HEADER_MIN_HEIGHT = 40.dp

/** 折叠屏展开 / 平板上别把一行拉成一米宽。 */
private val MAX_CONTENT_WIDTH = 600.dp

/**
 * **行首**：名字（占满剩余）｜行尾控件槽（[trailing]，靠右）。展开卡与其余行都走它。
 *
 * 语义：整行**合并成一个读屏节点** ⇒ 念作「常驻守护，开关，已开启」/「通知，已开启」，
 * 而不是先念一个孤立的名字、再念一个不知道属于谁的状态。
 */
@Composable
private fun AuthStepHeader(
    status: AuthStatus,
    nameStyle: TextStyle,
    /** 行高下限：行内有开关/按钮时用 [STEP_ROW_MIN_HEIGHT]（保 48dp 点击区），卡内用 [CARD_HEADER_MIN_HEIGHT]。 */
    minHeight: Dp = STEP_ROW_MIN_HEIGHT,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(authItemLabelRes(status.item)),
            style = nameStyle,
            // 两行而不是一行 + 省略号（UX 评审：5 字名称在 2.0× 需 140dp，折两行比切成
            // "无障碍服…" 强；行内明明还有空）
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/**
 * **行尾控件的分配规则**（2026-10-25 用户定稿）—— 判据是「**这个状态的两端是不是都由我们写**」：
 *
 * | 项 | 未授权 | 已授权 | 为什么 |
 * | --- | --- | --- | --- |
 * | 常驻守护 / 屏幕采集 | **`Switch` 关**（拨到开 = 启动守护 / 拉采集弹窗） | **`Switch` 开**（拨到关 = 停止） | 两个方向都由我们执行 ⇒ 真开关；停的入口也留在这儿 |
 * | 无障碍 / 通知 | **按钮「去开启 / 去授权」** | **纯状态文本「已开启」**（绿、无控件） | 系统持有：App 既撤不了也开不了；已授权时确实**无事可做**，再摆一颗按钮就是骗人手滑 |
 *
 * 系统权限**未授权**为什么用按钮而不是开关：开关的关态是"**邀请你去打开**"，而这一拨只会把人带到
 * 系统设置页/权限弹窗（App 自己开不了）—— 预期当场落空。按钮（"去开启"）没有这个歧义：
 * 它承诺的就是"带你去"。
 *
 * ⚠ 2026-10-25 曾把「守护已开启」也改成只报状态（与系统权限一致），用户随即要求改回 ——
 * 因为那样**这一页就没有停守护的入口**了（只能「设置 → 退出 App」）。别再把那条"一致性"改回来。
 */
@Composable
private fun AuthRowTrailing(
    status: AuthStatus,
    onAction: () -> Unit,
    onStop: (() -> Unit)?,
) {
    val granted = status.state == AuthState.GRANTED
    when {
        // ① 我们能起停的两项：开关即状态，也即动作（含"停"这个出口）
        onStop != null -> Switch(
            checked = granted,
            onCheckedChange = { want ->
                if (want) onAction() else onStop()
            },
        )

        // ② 系统权限已授权：只报一句状态（没有可做的事）。
        // 电池白名单用「已加入」——"已开启"配"白名单"读不通
        granted && status.item == AuthItem.BATTERY -> StatusText(R.string.auth_chip_whitelisted)

        granted -> StatusText(R.string.auth_chip_enabled)

        // ③ 系统权限未授权：一颗"带你去"的按钮
        else -> TextButton(onClick = onAction) {
            Text(
                text = stringResource(systemActionRes(status.item)),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/** 行尾的"只有状态"形态：绿字（L4）、不可点、与左侧名称同一基线上。 */
@Composable
private fun StatusText(@StringRes res: Int) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.labelLarge,
        color = Success40,
        maxLines = 1,
        modifier = Modifier.padding(end = 4.dp),
    )
}

/**
 * **步进条专用短名**：只用来"认位置"，全名在行内（`authItemLabelRes`）。
 *
 * 五列时每列 ~66dp，全名「电池优化白名单」会折两行、把整条撑高一倍（2026-10-25 真机截图实测）。
 * 两个映射分开放是刻意的：行内需要说得清"是什么"，步进条只需要"第几个"。
 */
@StringRes
private fun stepperLabelRes(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_step_accessibility
    AuthItem.RESIDENT -> R.string.auth_step_resident
    AuthItem.NOTIFICATIONS -> R.string.auth_step_notifications
    AuthItem.BATTERY -> R.string.auth_step_battery
    AuthItem.SCREEN_CAPTURE -> R.string.auth_step_capture
}

/** 系统权限的「去办」文案（只有它们会走到按钮分支）。 */
@StringRes
private fun systemActionRes(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_row_action_enable
    AuthItem.NOTIFICATIONS -> R.string.auth_row_action_authorize
    AuthItem.BATTERY -> R.string.auth_row_action_battery
    // 守护 / 采集在自己的状态由我们起停 ⇒ 行内是开关，走不到这里（保留分支只为 when 穷尽）
    AuthItem.RESIDENT, AuthItem.SCREEN_CAPTURE -> R.string.auth_row_action_enable
}

/**
 * **非当前步**的一行（外面那张卡由调用方给，这里只排一行内容）。
 *
 * 行尾只有一个控件，形态按 [AuthRowTrailing] 的规则决定（开关 / 状态文本 / 去办按钮）。
 */
@Composable
private fun AuthStepRow(
    status: AuthStatus,
    hint: String?,
    onAction: () -> Unit,
    onStop: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AuthStepHeader(
            status = status,
            nameStyle = MaterialTheme.typography.titleSmall,
            trailing = { AuthRowTrailing(status = status, onAction = onAction, onStop = onStop) },
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = Warning40,
            )
        }
    }
}

@StringRes
private fun itemDescription(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_desc
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_desc
    AuthItem.RESIDENT -> R.string.resident_desc
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_desc
    AuthItem.BATTERY -> R.string.auth_battery_desc
}

@StringRes
private fun itemActionText(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_action
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_action
    AuthItem.RESIDENT -> R.string.resident_action_start
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_action
    AuthItem.BATTERY -> R.string.auth_battery_action
}

@Preview(showBackground = true)
@Composable
private fun AuthorizationScreenPreview() {
    MasterMechanicTheme {
        // 预览取"第二步还没办"的样子：第一步打勾、第二步是当前卡、后两步是一行摘要
        AuthorizationScreen(
            statuses = listOf(
                AuthStatus(AuthItem.ACCESSIBILITY, AuthState.GRANTED),
                AuthStatus(AuthItem.RESIDENT, AuthState.MISSING),
                AuthStatus(AuthItem.NOTIFICATIONS, AuthState.GRANTED),
                AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.MISSING),
            ),
            notificationsEnabled = true,
            onOpenAccessibilitySettings = {},
            onRequestCapture = {},
            onRequestNotifications = {},
            onStartResident = {},
            onStopResident = {},
            onStopCapture = {},
            onRequestBatteryWhitelist = {},
        )
    }
}
