package com.example.mastermechanic

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.example.mastermechanic.ui.nav.MasterMechanicApp
import com.example.mastermechanic.ui.theme.MasterMechanicTheme

/**
 * 唯一的 Activity：**只负责"壳之外"的事**（生命周期计次 + 「一键重新授权采集」那条 Intent），
 * 界面结构全部交给 [MasterMechanicApp]。
 *
 * 2026-10-01（M5-U1）之前这里是 `when (screen)` + `rememberSaveable` 的整数编号地狱
 * （0 授权与运行 / 1 标定 / **2 空号** / 3 服务器清单 / 4 好友清单）—— 现在那套换成了 Navigation：
 * 目的地名字在 `ui/nav/Routes.kt`，返回栈由框架维护（见 `MasterMechanicApp` 顶部说明）。
 */
class MainActivity : ComponentActivity() {

    /** 每次回到前台自增：从系统设置页返回后需要刷新授权状态（M0-T0-2）。 */
    private val resumeTick = mutableIntStateOf(0)

    /**
     * **一键重新授权采集**（2026-10-01 用户口径，M5 期间插入；>0 = 有一个待处理的请求）。
     *
     * 来源：悬浮窗菜单那一行（`FloatingWindow.onRequestReauthorize`）—— 它带着 [EXTRA_REAUTH_CAPTURE]
     * 把本页拉到前台，这里记账；**授权页把它拉起系统弹窗之后回调清零**（`onReauthHandled`）。
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
        // **窗口底色对齐动态取色**（2026-10-25 用户报："关闭通知后返回 app 会先出现一帧白屏"）。
        // 主题里那块 `windowBackground` 只能写死一个近似值；这里按**本机实际配色**再覆盖一次，
        // 于是"窗口已显示、Compose 还没画第一帧"的那一瞬间与第一帧同色，看不出切换。
        //
        // ⚠ 必须取 `background` 而**不是** `surface`（2026-10-26 用户报"白屏变灰屏"）：
        // `Scaffold` 的默认容器色是 `colorScheme.background`，而 surface 在动态取色下通常**更深一点**
        // ⇒ 用 surface 会得到"灰一下"。
        runCatching {
            val night = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val scheme = if (night) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
        }
        enableEdgeToEdge()
        // 本页**可能**就是被"一键重新授权"那条 Intent 唤起 / 重建的（另一条路见 [onNewIntent]）
        if (intent?.getBooleanExtra(EXTRA_REAUTH_CAPTURE, false) == true) pendingReauth.intValue++
        setContent {
            MasterMechanicTheme {
                MasterMechanicApp(
                    resumeTick = resumeTick.intValue,
                    reauthTick = pendingReauth.intValue,
                    onReauthHandled = { pendingReauth.intValue = 0 },
                )
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
