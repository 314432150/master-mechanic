package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * 实点开关的安全边界（T2-6 / B5）。
 *
 * 锁定的口径：**实点只能由用户在本次会话内显式开启**——进程启动、无障碍服务断开、采集会话（重）建立
 * 三条路径都必须回到演练。任一条失守都可能让程序在用户不知情时真的点击游戏界面（红线 1 / 2 的代价不可逆）。
 */
class ClickDispatchTest {

    @Before
    fun drillFirst() {
        ClickDispatch.enableDrill()
    }

    @Test
    fun enableLiveSwitchesModeAndFlow() {
        ClickDispatch.enableLive()
        assertEquals("实点开关生效", ClickMode.LIVE, ClickDispatch.mode)
        assertEquals("UI 观察的模式流同步", ClickMode.LIVE, ClickDispatch.modeFlow.value)
    }

    @Test
    fun enableDrillSwitchesBack() {
        ClickDispatch.enableLive()
        ClickDispatch.enableDrill()
        assertEquals(ClickMode.DRILL, ClickDispatch.mode)
        assertEquals(ClickMode.DRILL, ClickDispatch.modeFlow.value)
    }

    /** 边界 b：服务断开 / 被中断 / 销毁 → 卸载并回演练（此后所有点击请求一律被拒）。 */
    @Test
    fun uninstallDropsInjectorAndFallsBackToDrill() {
        ClickDispatch.enableLive()
        ClickDispatch.uninstall()
        assertEquals("服务断开后必须回到演练", ClickMode.DRILL, ClickDispatch.mode)
        assertFalse("卸载后没有下发通道", ClickDispatch.isInstalled)
    }

    /** 边界 c 的效果：采集会话（重）建立时 CaptureService 会调用 enableDrill()。 */
    @Test
    fun sessionStartResetFallsBackToDrill() {
        ClickDispatch.enableLive()
        ClickDispatch.enableDrill() // = CaptureService 在会话转为 ACTIVE 时调用的那一行
        assertEquals("重新建立采集会话后不得保持实点", ClickMode.DRILL, ClickDispatch.mode)
    }

    /** 服务不可用（无注入通道）= 不能点击：拒绝原因可审计（ADR-002「不可用即停」）。 */
    @Test
    fun submitWithoutInjectorIsDenied() {
        ClickDispatch.enableLive()
        ClickDispatch.uninstall()
        val verdict = ClickDispatch.submit(
            ClickRequest(
                decisionId = "fr01-test",
                source = ClickSource.FR_01,
                anchorName = "popup_close_anchor",
                frameX = 1.0,
                frameY = 2.0,
            ),
            gameForeground = true,
            state = UiState.ACTIVITY_POPUP,
        )
        assertFalse("没有注入通道时不得放行", verdict.allowed)
        assertEquals(ClickDenyReason.NO_INJECTOR, verdict.reason)
    }
}
