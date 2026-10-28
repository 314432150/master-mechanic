package com.example.mastermechanic.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mastermechanic.R
import com.example.mastermechanic.auth.AccessibilitySelfHeal
import com.example.mastermechanic.auth.AuthGuide
import com.example.mastermechanic.auth.AuthorizationChecks
import com.example.mastermechanic.auth.CaptureSessionState
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.ui.AuthorizationRoute
import com.example.mastermechanic.ui.CalibrationRoute
import com.example.mastermechanic.ui.diagnostics.DiagnosticsRoute
import com.example.mastermechanic.ui.help.HelpScreen
import com.example.mastermechanic.ui.settings.AppSettingsRoute
import com.example.mastermechanic.ui.FriendListRoute
import com.example.mastermechanic.ui.ServerListRoute
import com.example.mastermechanic.ui.run.RunRoute
import com.example.mastermechanic.ui.settings.VisitSettingsRoute
import kotlinx.coroutines.launch

/**
 * **App 的壳**（M5-U1 导航骨架；2026-10-01 用户拍板引入 Navigation Compose）。
 *
 * ```
 * ModalNavigationDrawer         ← 系统级入口（授权与权限 / 诊断 / 设置 / 帮助·关于）
 * └─ Scaffold
 *    ├─ TopAppBar               ← 只给"还没有自己页头"的目的地（见 [SHELL_APP_BAR_ROUTES]）
 *    ├─ NavHost                 ← 页面切换与返回栈（替掉旧 `MainActivity` 的 `when(screen)`）
 *    └─ NavigationBar           ← 一级：运行 / 拜访设置 / 账号与好友 / 标定
 * ```
 *
 * ## 口径的演变（写在这里免得被当成 bug）
 *
 * 1. U1 换壳时，四个旧页**各自都带着自己的页头** ⇒ 壳再套一层标题就是**双层标题栏** ✗，
 *    所以 [SHELL_APP_BAR_ROUTES] 当时只给 5 个目的地。**U2 / U4 / U5 / U6 已陆续把页头收进壳里**
 *    （运行 → 标定 → 清单 → 授权），⇒ **2026-10-25 起它恒为全集**（每个目的地都由壳出标题 + 抽屉按钮）。
 * 2. U1 时 `运行` 与抽屉 `授权与权限` 指向同一个 `AuthorizationRoute`。U2 起「运行」长成自己的页
 *    （状态卡 + 停止/继续），两者已经没有关系。
 *
 * ## 返回行为
 *
 * 返回键由 Navigation 自己处理：非落地页**逐级回退**，到 [Routes.START] 才退出 App ——
 * 与换壳前那条 `BackHandler` 的口径一致（UX 审核 2026-09-15 的 P0：子页按返回绝不能直接退出）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterMechanicApp(
    resumeTick: Int,
    reauthTick: Int,
    onReauthHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    /**
     * 「账号与好友」当前选中的 Tab（0 = 区服清单 / 1 = 好友清单）。
     *
     * 提到壳这一层是因为**「新增」按钮的文案随 Tab 变**（`TopAppBar.actions` 里那条）。
     * ⚠ 2026-10-25 之前它还有一个用途：授权页那两颗「服务器 / 好友」入口按钮要落到各自的 Tab；
     * 那两颗已随 U6 收口删除 ⇒ 现在只有 TabRow 自己在改它。
     */
    var accountsTab by rememberSaveable { mutableIntStateOf(0) }

    /**
     * **「新增」请求的计数**（M5-U4）：壳的标题栏那个按钮点一次 +1 ⇒ 对应页面的 `LaunchedEffect` 接住并打开新增。
     *
     * 为什么用"计数"而不是布尔：连点两次要各打开一次；而页面自己关掉后**不会**因为"值还是 true"再弹一遍。
     */
    var accountsAddTick by rememberSaveable { mutableIntStateOf(0) }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    /**
     * **全屏态**（M5-U5）：标定页开着全屏工作台时，壳要把**标题栏与底栏一起收起来**
     * （框选要占满整屏、底下不能有东西被误触）。
     *
     * 由 `CalibrationRoute` 用 `onFullScreenChange` 上报 —— 页面只**报告状态**，
     * "现在有哪些栏、该不该收"由壳决定（页面的 `Scaffold` 只管自己的 Snackbar）。
     */
    var calibrationFullScreen by remember { mutableStateOf(false) }
    val fullScreenMode = currentRoute == Routes.CALIBRATION && calibrationFullScreen

    /**
     * **App 内发起的"建立 / 重建采集会话"请求**（2026-10-02 用户："画面样本采集模块，若没有开启采集，
     * 应当直接提供一键开启采集的入口，以免反复切换上下文"）。
     *
     * 复用的就是 U0 那条已经打通的通路：悬浮窗菜单把 App 拉到前台并带一个 `reauthTick`
     * ⇒ 授权页**自动**拉起系统采集授权弹窗（[AuthorizationRoute] 里的 `LaunchedEffect`）。
     * 这里让**页面内**也能发起同一条请求 ⇒ 用户不必"自己跑去授权页 → 找按钮 → 再点一次"。
     *
     * 为什么要跟 Activity 那个计数相加：两者是**同一种请求**的两种来源（Intent 带来 / App 内点出来），
     * 授权页只认一个"待处理计数"；[onReauthHandled] 兑现后两边一起清零。
     */
    var localReauthTick by remember { mutableIntStateOf(0) }
    val effectiveReauthTick = reauthTick + localReauthTick

    // **一键重新授权采集**（M5-U0 那段口径）：悬浮窗菜单把 App 拉到前台并带上标记 ⇒
    // 这里只负责"**到授权页**"，系统弹窗由 `AuthorizationRoute` 在那一页拉起（授权是它的职责，壳不抢）。
    // ⚠ 只以 [reauthTick] 为键：并进别的键会在每次回前台重弹一次（`MainActivity` 里记着这个坑）。
    LaunchedEffect(reauthTick) {
        if (reauthTick > 0) navController.navigateTo(Routes.AUTH)
    }

    fun go(route: String) {
        navController.navigateTo(route)
    }

    /** 一键建立 / 重建采集会话：**先到授权页**（系统弹窗只能由 Activity 拉起），再由它自动弹出。 */
    fun requestReauth() {
        localReauthTick++
        go(Routes.AUTH)
    }

    /**
     * **启动时缺授权 ⇒ 直接落在「授权与权限」**（M5-U6 的验收项，2026-10-25 才补上实现 ——
     * 此前只有计划与 `Routes.AUTH` 的注释写着"首次启动缺授权时自动进这里"，代码里没有）。
     *
     * 判据取 [AuthGuide.blockingCapture]（= 无障碍 / 常驻守护 / 通知里还缺的），**刻意不看屏幕采集**：
     * 采集是**会话制**授权（ADR-001），每次冷启动都必然"缺" ⇒ 拿它当触发条件等于每次打开 App
     * 都被拽到授权页。而"前面三项缺"才是真的跑不起来，那时把引导页端到用户面前，比让他自己
     * 去发现左上角那个抽屉按钮有用。
     *
     * 只做一次（[autoAuthDone] 存 `rememberSaveable`）：从系统设置页返回、切页回来都不该再拽一次。
     */
    var autoAuthDone by rememberSaveable { mutableStateOf(false) }
    val appContext = LocalContext.current
    LaunchedEffect(Unit) {
        // **无障碍自愈**（2026-10-25）：冷启动时若发现无障碍被关掉（装机 / 系统清理 / 用户手动关），
        // 自己能开就自己开。只在**冷启动**做一次 —— 用户主动关掉后切回来，我们不抢（见 `AccessibilitySelfHeal`）。
        // 放在自动跳转之前：若它成功了，紧接着那次授权检查就会看到「已开启」。
        AccessibilitySelfHeal.ensureEnabled(appContext)
        if (autoAuthDone) return@LaunchedEffect
        autoAuthDone = true
        val statuses = AuthorizationChecks.collect(
            appContext,
            if (CaptureSessionSignal.isActive) {
                CaptureSessionState.GRANTED
            } else {
                CaptureSessionState.NOT_GRANTED
            },
        )
        if (AuthGuide.blockingCapture(statuses).isNotEmpty()) navController.navigateTo(Routes.AUTH)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 12.dp),
                )
                DrawerDestination.entries.forEach { destination ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(destination.labelRes)) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        selected = currentRoute == destination.route,
                        onClick = {
                            scope.launch { drawerState.close() }
                            go(destination.route)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                // 全屏态连标题栏一起收（见 [calibrationFullScreen]）
                if (currentRoute in SHELL_APP_BAR_ROUTES && !fullScreenMode) {
                    val titleRes = titleResOf(currentRoute)
                    TopAppBar(
                        title = { if (titleRes != null) Text(stringResource(titleRes)) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.nav_open_drawer),
                                )
                            }
                        },
                        actions = {
                            // M5-U4（ADR-009 决策五）：列表页的**「新增」统一住在壳的标题栏**（一处一个）。
                            // 两个 Tab 都已收编 ⇒ 按当前 Tab 决定加服务器还是加好友。
                            if (currentRoute == Routes.ACCOUNTS) {
                                TextButton(onClick = { accountsAddTick++ }) {
                                    Text(
                                        stringResource(
                                            if (accountsTab == 0) R.string.server_add else R.string.friend_add,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
            },
            bottomBar = {
                // 全屏态连底栏也收（同上）：框选时底下压着一排导航，容易误触
                if (!fullScreenMode) {
                    NavigationBar {
                        TopLevelDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = { go(destination.route) },
                                // 图标不带 contentDescription：底栏每一项都有文字标签，读屏念标签即可
                                // （再给图标一个描述会念两遍；V6 的实测在真机走查里确认）。
                                icon = { Icon(destination.icon, contentDescription = null) },
                                // **底栏一律短名**（2 字：运行 / 拜访 / 清单 / 标定）；页面标题才是全名 ——
                                // 用户 2026-10-01："「拜访设置」「账号与好友」作为导航栏太长了"。
                                label = { Text(stringResource(destination.shortLabelRes)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.START,
                modifier = Modifier.padding(innerPadding),
            ) {
                // 一级：运行 —— **U2 起是本页自己的内容**（状态卡 + 停止/继续；不含"发起执行"，用户拍板）
                composable(Routes.RUN) {
                    RunRoute(
                        resumeTick = resumeTick,
                        // ⚠ 这两条**不是同一个动作**（2026-10-02 用户报障后拆开，原来都接 `requestReauth`）：
                        // ①「去「授权与权限」」= **只导航**：缺项可能有无障碍 / 守护，而采集一授权就会切到游戏，
                        //   先拉采集就等于把用户送走、还得为别的事切回来 ✗；
                        // ②「重新授权采集」/「建立采集会话」= **修采集**，用户此刻就是要重建会话 ⇒ 直接拉弹窗。
                        onOpenAuth = { go(Routes.AUTH) },
                        onRequestCapture = { requestReauth() },
                    )
                }
                // 抽屉：授权与权限 —— U6 收口后是**引导式四步**（标题栏由壳出，页内不再有跳转按钮）
                composable(Routes.AUTH) {
                    AuthorizationDestination(
                        resumeTick = resumeTick,
                        // 两个来源合流（Intent 带来的 + App 内点出来的），见 [localReauthTick]
                        reauthTick = effectiveReauthTick,
                        // 兑现后**两边一起清零**：不清本地这份，切走再切回授权页会又弹一次
                        onReauthHandled = {
                            localReauthTick = 0
                            onReauthHandled()
                        },
                    )
                }
                // 一级：拜访设置 —— U3 起是本页自己的内容（区服策略 + 好友 ⇒ **只保存，不发起**）
                composable(Routes.VISIT_SETTINGS) {
                    VisitSettingsRoute(resumeTick = resumeTick)
                }
                // 一级：账号与好友 —— 两个 Tab，复用手上真正的两个清单页
                composable(Routes.ACCOUNTS) {
                    AccountsDestination(
                        resumeTick = resumeTick,
                        addTick = accountsAddTick,
                        tab = accountsTab,
                        onTabChange = { accountsTab = it },
                    )
                }
                // 一级：标定（M5-U5 起**壳出标题栏**：入口页有「标定 + 抽屉按钮」，页内不再有返回；
                // 进全屏工作台时页面会上报 `onFullScreenChange(true)` ⇒ 壳把两个栏都收起来）
                composable(Routes.CALIBRATION) {
                    CalibrationRoute(
                        resumeTick = resumeTick,
                        onFullScreenChange = { calibrationFullScreen = it },
                        // 「画面样本采集」卡上没有会话时的那颗主按钮（一键建立，别再让用户自己跑授权页）
                        onRequestReauth = { requestReauth() },
                    )
                }
                // 抽屉：诊断（M5-U6 第二段：**只读读数** 画面 / 当前画面 / 单帧耗时 / 日志，数值与日志同源）
                composable(Routes.DIAGNOSTICS) { DiagnosticsRoute(resumeTick = resumeTick) }
                // 抽屉：设置 —— **已落地第一段**（识别参数：2026-10-02 从标定页搬来，用户"改东西归设置"）
                composable(Routes.SETTINGS) { AppSettingsRoute(resumeTick = resumeTick) }
                // 抽屉：帮助（M5-U6 第二段：术语与流程，一组一句）
                composable(Routes.HELP) { HelpScreen() }
            }
        }
    }
}

/**
 * 一级 / 抽屉之间切换的**统一导航动作**。
 *
 * `popUpTo(落地页) + saveState + restoreState + launchSingleTop` 是 Navigation 官方那套
 * "底部导航"姿势：同一项连点不会在栈里堆副本，来回切会恢复各自的滚动位置与输入状态。
 */
private fun NavHostController.navigateTo(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 壳上给标题栏的目的地：**2026-10-25 起是全集**（见 [MasterMechanicApp] 顶部说明）。
 *
 * 收编顺序（每一步都顺带删掉了页内标题 / 返回按钮，避免双层）：
 * U2「运行」→ U4「清单」→ U5「标定」→ **U6「授权与权限」**（本次；此前它只剩正文里一行大标题，
 * 于是那页**没有左上角抽屉按钮** —— 进了那页就打不开抽屉，只能靠底栏或系统返回）。
 */
private val SHELL_APP_BAR_ROUTES = Routes.all.toSet()

/** 抽屉「授权与权限」的接线（U6 收口后它只接采集重授权那条通道）。 */
@Composable
private fun AuthorizationDestination(
    resumeTick: Int,
    reauthTick: Int,
    onReauthHandled: () -> Unit,
) {
    AuthorizationRoute(
        resumeTick = resumeTick,
        reauthTick = reauthTick,
        onReauthHandled = onReauthHandled,
    )
}

/** 「账号与好友」：两个 Tab + 现有的两个清单页（U4 会把行内操作与说明改成 Sheet / 折叠）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountsDestination(
    resumeTick: Int,
    addTick: Int,
    tab: Int,
    onTabChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { onTabChange(0) },
                text = { Text(stringResource(R.string.nav_tab_servers)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { onTabChange(1) },
                text = { Text(stringResource(R.string.nav_tab_friends)) },
            )
        }
        when (tab) {
            // 两个清单页都已收编：标题栏与「新增」都由壳给（页内不再有返回 / 新增）
            0 -> ServerListRoute(resumeTick = resumeTick, addTick = addTick)
            else -> FriendListRoute(resumeTick = resumeTick, addTick = addTick)
        }
    }
}

// ⚠ U6 第二段后已无占位页（诊断 / 帮助都换成真页，「设置」在 U3 已落地）⇒ StubDestination 与
// nav_stub_* 文案已删除。留个口供将来：用户明确反对"点进去什么都没有"，宁可当场写清楚也不留空白。
