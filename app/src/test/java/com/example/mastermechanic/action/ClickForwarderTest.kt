package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点击转发层单测（T2-3c）：下发与审计链、拒绝时绝不动手、同步失败按"未发起"处理、
 * 300ms 间隔由转发层自己保证（调用方无法绕过）。
 */
class ClickForwarderTest {

    private class FakeInjector(private val accepted: Boolean = true) : GestureInjector {
        val calls = mutableListOf<Pair<Double, Double>>()
        private var onResult: ((Boolean) -> Unit)? = null

        override fun click(frameX: Double, frameY: Double, onResult: (Boolean) -> Unit): Boolean {
            calls += frameX to frameY
            this.onResult = onResult
            return accepted
        }

        /** 模拟系统回调：true = 完成，false = 被取消。 */
        fun endGesture(completed: Boolean) {
            onResult?.invoke(completed)
        }
    }

    private val events = mutableListOf<ClickEvent>()
    private var now = 1_000L

    private fun request(
        decisionId: String = "判定-1",
        source: ClickSource = ClickSource.FR_01,
        anchorName: String = "popup_close_x",
        frameX: Double = 300.4,
        frameY: Double = 400.6,
    ) = ClickRequest(decisionId, source, anchorName, frameX, frameY)

    private fun environment(
        mode: ClickMode = ClickMode.LIVE,
        foreground: Boolean = true,
        state: UiState = UiState.ACTIVITY_POPUP,
    ) = ClickEnvironment(foreground, state, mode)

    private fun forwarder(injector: GestureInjector, gate: ClickGate = ClickGate()) =
        ClickForwarder(gate, injector, clock = { now }, audit = events::add)

    @Test
    fun dispatchesAtJudgedPointAndAuditsLifecycle() {
        val injector = FakeInjector()

        val verdict = forwarder(injector).submit(request(), environment())

        assertTrue(verdict.allowed)
        assertEquals(listOf(300.4 to 400.6), injector.calls) // 坐标原样来自判定结果（ADR-002）
        val decided = events[0] as ClickEvent.Decided
        assertTrue(decided.audit.allowed)
        assertEquals("判定-1", decided.audit.decisionId)
        assertEquals(ClickSource.FR_01, decided.audit.source)
        assertEquals("popup_close_x", decided.audit.anchorName)
        assertEquals(300, decided.audit.frameX) // 审计里取整，便于人工核对
        assertEquals(400, decided.audit.frameY)
        assertEquals(UiState.ACTIVITY_POPUP, decided.audit.state)
        assertTrue(decided.audit.foreground)

        injector.endGesture(completed = true)
        val ended = events[1] as ClickEvent.GestureEnded
        assertTrue(ended.completed)
        assertEquals("判定-1", ended.decisionId)
        assertEquals(2, events.size)
    }

    @Test
    fun rejectionIsAuditedAndNeverTouchesInjector() {
        val injector = FakeInjector()

        val verdict = forwarder(injector).submit(request(), environment(state = UiState.UNKNOWN))

        assertFalse(verdict.allowed)
        assertEquals(ClickDenyReason.STATE_UNKNOWN, verdict.reason)
        assertTrue(injector.calls.isEmpty())
        val decided = events.single() as ClickEvent.Decided
        assertFalse(decided.audit.allowed)
        assertEquals(ClickDenyReason.STATE_UNKNOWN, decided.audit.reason)
    }

    @Test
    fun synchronousInjectorFailureLeavesGateIdleAndConservativeSpacing() {
        val gate = ClickGate()
        val injector = FakeInjector(accepted = false)

        val verdict = forwarder(injector, gate).submit(request(), environment())

        assertTrue("门禁允许，但注入通道没接住", verdict.allowed)
        assertTrue("同步失败 = 手势根本没开始，必须解除在途", gate.isIdle)
        assertEquals(1_000L, gate.lastFinishedMs) // 保守地按此刻起算间隔
        assertFalse("没有手势也就没有结束事件", events.any { it is ClickEvent.GestureEnded })
    }

    @Test
    fun spacingIsEnforcedByTheLayerItself() {
        val gate = ClickGate()
        val injector = FakeInjector()
        val forwarder = forwarder(injector, gate)

        assertTrue(forwarder.submit(request(), environment()).allowed)
        injector.endGesture(completed = true) // 手势在 t=1000 结束

        now = 1_299L
        val tooSoon = forwarder.submit(request(decisionId = "判定-2"), environment())
        assertFalse(tooSoon.allowed)
        assertEquals(ClickDenyReason.TOO_SOON, tooSoon.reason)
        assertEquals(1, injector.calls.size)

        now = 1_300L
        assertTrue(forwarder.submit(request(decisionId = "判定-3"), environment()).allowed)
        assertEquals(2, injector.calls.size)
    }

    @Test
    fun cancelIsReportedAsIncomplete() {
        val injector = FakeInjector()
        forwarder(injector).submit(request(), environment())

        injector.endGesture(completed = false)

        assertFalse((events[1] as ClickEvent.GestureEnded).completed)
    }
}
