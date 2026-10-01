package com.example.mastermechanic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.mastermechanic.ui.AuthorizationRoute
import com.example.mastermechanic.ui.CalibrationRoute
import com.example.mastermechanic.ui.FriendListRoute
import com.example.mastermechanic.ui.ServerListRoute
import com.example.mastermechanic.ui.theme.MasterMechanicTheme

class MainActivity : ComponentActivity() {

    /** 每次回到前台自增：从系统设置页返回后需要刷新授权状态（M0-T0-2）。 */
    private val resumeTick = mutableIntStateOf(0)

    /**
     * **一键重新授权采集**（2026-10-01 用户口径，M5 期间插入；>0 = 有一个待处理的请求）。
     *
     * 来源：悬浮窗菜单那一行（`FloatingWindow.onRequestReauthorize`）—— 它带着 [EXTRA_REAUTH_CAPTURE]
     * 把本页拉到前台，这里记账；**授权页把它拉起系统弹窗之后回调清零**（[onReauthHandled]）。
     *
     * 为什么是"计数 + 消费"而不是布尔：
     * ① 连着点两次要各触发一次；② 清零后用户从别的页面切回授权页时**不会**再弹一次
     * —— 这是必须的（系统弹窗只能由用户**主动点的那一次**触发，不能因为"切回这一页"就重弹）。
     */
    private val pendingReauth = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 本页**可能**就是被"一键重新授权"那条 Intent 唤起 / 重建的（另一条路见 [onNewIntent]）
        if (intent?.getBooleanExtra(EXTRA_REAUTH_CAPTURE, false) == true) pendingReauth.intValue++
        setContent {
            MasterMechanicTheme {
                // 页面切换：0 = 授权与运行，1 = 识别标定（T1-5b），
                // 2 = 跑号配置（2026-09-16 已按用户口径移除，编号留空不再使用），
                // 3 = 服务器清单（M3-T3-9），4 = 好友清单（M3-T3-8）
                var screen by rememberSaveable { mutableStateOf(0) }
                // **一键重新授权采集**：不管用户当时在哪一页，都先回到「授权与运行」页
                //（弹窗由 `AuthorizationRoute` 在那个页面上拉起 —— 授权是它的职责，这里不抢）。
                LaunchedEffect(pendingReauth.intValue) {
                    if (pendingReauth.intValue > 0) screen = 0
                }
                // 系统返回 / 边缘侧滑返回：**在子页按返回必须回主页，绝不能退出应用**
                // （UX 审核 2026-09-15 的 P0：此前没有 BackHandler，在清单页按返回键直接退出 App——
                //  录完一堆数据按一下返回就没了，还以为没保存）。
                BackHandler(enabled = screen != 0) { screen = 0 }
                when (screen) {
                    0 -> AuthorizationRoute(
                        resumeTick = resumeTick.intValue,
                        reauthTick = pendingReauth.intValue,
                        onReauthHandled = { pendingReauth.intValue = 0 },
                        onOpenCalibration = { screen = 1 },
                        onOpenServerList = { screen = 3 },
                        onOpenFriendList = { screen = 4 },
                    )
                    1 -> CalibrationRoute(
                        resumeTick = resumeTick.intValue,
                        onBack = { screen = 0 },
                    )
                    3 -> ServerListRoute(
                        resumeTick = resumeTick.intValue,
                        onBack = { screen = 0 },
                    )
                    else -> FriendListRoute(
                        resumeTick = resumeTick.intValue,
                        onBack = { screen = 0 },
                    )
                }
            }
        }
    }

    /**
     * 悬浮窗菜单在本页**已经在栈里**时又点了一次「重新授权采集」。
     *
     * `FLAG_ACTIVITY_CLEAR_TOP` 在标准启动模式下会**重建**本页（走 [onCreate] 那条路），
     * 但"到底重不重建"与厂商实现有关 ⇒ 这里再兜一道，两条路各自触发（**多触发一次的最坏后果只是
     * 多弹一次授权窗；漏触发就是"点了没反应"，后者不可接受**）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REAUTH_CAPTURE, false)) pendingReauth.intValue++
    }

    companion object {
        /**
         * 「一键重新授权采集」的 Intent 标记（悬浮窗菜单 ⇒ 本页 ⇒ `AuthorizationRoute` 自动拉起系统弹窗）。
         *
         * 为什么必须经本页这一趟：**系统采集授权弹窗只能由 Activity 用 `startActivityForResult`
         * 拉起**，而凭证不落盘、只经内存交给 `CaptureService`（ADR-001）—— 悬浮窗是无障碍 Service，
         * 自己没有 result 通道。
         */
        const val EXTRA_REAUTH_CAPTURE = "mastermechanic.reauth_capture"
    }
}
