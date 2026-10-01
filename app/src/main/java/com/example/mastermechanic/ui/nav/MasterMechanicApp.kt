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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mastermechanic.R
import com.example.mastermechanic.ui.AuthorizationRoute
import com.example.mastermechanic.ui.CalibrationRoute
import com.example.mastermechanic.ui.diagnostics.DiagnosticsRoute
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
 * ## 这张卡的口径是"**行为不变，仅换壳**"
 *
 * 所以 U1 阶段有两处**有意的过渡态**，都写在这里免得被当成 bug：
 *
 * 1. **壳上的标题栏只出现在 5 个目的地**（占位页 + 账号与好友）：现有四页
 *    （授权与运行 / 标定 / 两个清单）**各自都带着自己的页头**（`Scaffold` 只挂 Snackbar，
 *    标题与返回键是页面内容的一部分）⇒ 壳再套一层标题就是**双层标题栏** ✗。
 *    U2 / U5 / U6 重写那些页面时会把它们的页头**收进壳里**，那时 [SHELL_APP_BAR_ROUTES] 就恒为全集。
 * 2. **`运行` 与抽屉里的 `授权与权限` 现在指向同一个 `AuthorizationRoute`**（旧编号 0 那一页）⇒
 *    两处看起来一样。这是刻意的：U1 不许改页面内容，而"运行"页（U2）与"授权"页（U6）会各自长出来。
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
     * 提升到壳这一层，是因为**授权页有两个入口按钮**分别要落到不同的 Tab
     * （`onOpenServerList` / `onOpenFriendList`）—— 换壳前它们就是两个不同的 `screen` 编号。
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

    // **一键重新授权采集**（M5-U0 那段口径）：悬浮窗菜单把 App 拉到前台并带上标记 ⇒
    // 这里只负责"**到授权页**"，系统弹窗由 `AuthorizationRoute` 在那一页拉起（授权是它的职责，壳不抢）。
    // ⚠ 只以 [reauthTick] 为键：并进别的键会在每次回前台重弹一次（`MainActivity` 里记着这个坑）。
    LaunchedEffect(reauthTick) {
        if (reauthTick > 0) navController.navigateTo(Routes.AUTH)
    }

    fun go(route: String) {
        navController.navigateTo(route)
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
                    RunRoute(resumeTick = resumeTick, onOpenAuth = { go(Routes.AUTH) })
                }
                // 抽屉：授权与权限 —— 同样先接现有授权页（U6 会拆成引导式四步）
                composable(Routes.AUTH) {
                    AuthorizationDestination(
                        resumeTick = resumeTick,
                        reauthTick = reauthTick,
                        onReauthHandled = onReauthHandled,
                        onOpenCalibration = { go(Routes.CALIBRATION) },
                        onOpenAccounts = { tab -> accountsTab = tab; go(Routes.ACCOUNTS) },
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
                    )
                }
                // 抽屉：诊断 —— **已落地第一段**（识别参数；2026-10-02 从标定页搬来：那里"要滑到底"）
                composable(Routes.DIAGNOSTICS) { DiagnosticsRoute(resumeTick = resumeTick) }
                // 抽屉：设置 / 帮助（U6 建设）
                composable(Routes.SETTINGS) { StubDestination(R.string.nav_stub_settings) }
                composable(Routes.HELP) { StubDestination(R.string.nav_stub_help) }
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

/** 壳上给标题栏的目的地（见 [MasterMechanicApp] 顶部说明第 1 条）。 */
private val SHELL_APP_BAR_ROUTES = setOf(
    // U2 起「运行」是新建的页面（自己不带页头）⇒ 按 ADR-009 决策五，壳给它标题栏（抽屉按钮 + 标题）
    Routes.RUN,
    // U5 起「标定」也收编：入口页由壳出标题（用户 2026-10-01："标定页顶部为什么没有标题栏"）＋
    // 抽屉按钮；页内那行「返回授权页」已删。全屏工作台时壳整体收起（见 [calibrationFullScreen]）。
    Routes.CALIBRATION,
    Routes.VISIT_SETTINGS,
    Routes.ACCOUNTS,
    Routes.DIAGNOSTICS,
    Routes.SETTINGS,
    Routes.HELP,
)

/** 运行 / 授权两处共用的接线（同一份 `AuthorizationRoute`，参数一模一样）。 */
@Composable
private fun AuthorizationDestination(
    resumeTick: Int,
    reauthTick: Int,
    onReauthHandled: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenAccounts: (Int) -> Unit,
) {
    AuthorizationRoute(
        resumeTick = resumeTick,
        reauthTick = reauthTick,
        onReauthHandled = onReauthHandled,
        onOpenCalibration = onOpenCalibration,
        onOpenServerList = { onOpenAccounts(0) },
        onOpenFriendList = { onOpenAccounts(1) },
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

/**
 * **占位页**（U1 阶段）：把"这一页以后是什么"写在正中间。
 *
 * 为什么不留空白：空白会被当成 bug（用户看到底部导航能点、点进去啥都没有，第一反应是"坏了"），
 * 而写一句话既说明它是**在建**，也说清建成什么样。U3 / U6 各自替换掉自己的那一个。
 */
@Composable
private fun StubDestination(@StringRes bodyRes: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(bodyRes))
    }
}
