package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 唯一下发口的边界（T2-3c / ADR-002）。
 *
 * 2026-09-22 用户口径：只识别不下发的「演练模式」已**整体移除**——不再有模式开关
 * （[ClickDispatch.enableLive] / [ClickDispatch.enableDrill] / 授权页「运行方式」卡都已删除），
 * 程序默认且唯一的行为就是真实点击。因此本用例只锁两件事：
 * 1. 无障碍通道没装 / 被卸载 → 一律拒绝（不可用即停，绝不试探）；
 * 2. 拒绝原因可审计，便于真机核对"为什么没点"。
 */
class ClickDispatchTest {

    private fun request() = ClickRequest(
        decisionId = "fr01-test",
        source = ClickSource.FR_01,
        anchorName = "popup_close_anchor",
        frameX = 1.0,
        frameY = 2.0,
    )

    /** 服务不可用（无注入通道）= 不能点击；卸载后同样如此（ADR-002「不可用即停」）。 */
    @Test
    fun submitWithoutInjectorIsDenied() {
        ClickDispatch.uninstall()

        val verdict = ClickDispatch.submit(
            request(),
            gameForeground = true,
            state = UiState.ACTIVITY_POPUP,
        )

        assertFalse("没有注入通道时不得放行", verdict.allowed)
        assertEquals(ClickDenyReason.NO_INJECTOR, verdict.reason)
    }

    /** 断开即卸载：此后没有任何残留通道（不靠"模式"兜底，靠"通道没了"）。 */
    @Test
    fun uninstallDropsTheOnlyChannel() {
        ClickDispatch.uninstall()

        assertFalse("卸载后没有下发通道", ClickDispatch.isInstalled)
    }
}
