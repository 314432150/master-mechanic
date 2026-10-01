package com.example.mastermechanic.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我们刚改过屏幕 ⇒ 该来一帧新画面」这份期待的**落空判据**（2026-09-29）。
 *
 * 为什么单独立这条：它现在是「画面采集已停」那道点击闸的**唯一依据**（见
 * `CaptureService.checkFrameExpectation` + `ClickDispatch.framesStalled`）。真机事故形态：
 * 会话重建后 5 分钟没有一帧新画面，程序照着 234 秒前那张画面继续判、继续点，
 * 用户看到的是"换号 / 拜访时很多标志和锚点都认不出"。
 */
class FrameExpectationSignalTest {

    @Test
    fun noExpectationIsNeverOverdue() {
        // 0 = 当前没有期待（没下发过手势）⇒ 永远不算落空。静止页面不出新帧是正常的，
        // 没有期待的情况下"没有新帧"毫无信息量（这条保证不会拿静止页面误判）。
        assertFalse(FrameExpectationSignal.isOverdue(armedAtMs = 0L, nowMs = 999_999_999L))
    }

    @Test
    fun withinTheTimeoutItIsNotYetOverdue() {
        val armed = 1_000L
        assertFalse(FrameExpectationSignal.isOverdue(armed, nowMs = armed))
        assertFalse(
            "差 1ms ⇒ 还没到",
            FrameExpectationSignal.isOverdue(armed, nowMs = armed + FrameExpectationSignal.DEFAULT_TIMEOUT_MS - 1),
        )
    }

    @Test
    fun atOrAfterTheTimeoutItIsOverdue() {
        val armed = 1_000L
        assertTrue(
            "刚好到期限 ⇒ 落空",
            FrameExpectationSignal.isOverdue(armed, nowMs = armed + FrameExpectationSignal.DEFAULT_TIMEOUT_MS),
        )
        assertTrue(
            "真机那次是 5 分钟 ⇒ 当然落空",
            FrameExpectationSignal.isOverdue(armed, nowMs = armed + 300_000L),
        )
    }

    @Test
    fun reachedClearsTheExpectationSoItStopsBeingOverdue() {
        // 收到新帧 ⇒ reached() ⇒ 期待被清掉 ⇒ 不再算落空（自愈：闸会跟着解除）。
        // ⚠ 这里**不读 `arm()` 之后的 armedAtMs**：纯 JVM 单测里 `SystemClock` 是桩（恒返回 0），
        // 读它只会测到那个桩、测不到被测逻辑 —— 行为一律用纯函数 [FrameExpectationSignal.isOverdue] 验。
        FrameExpectationSignal.arm("单测：刚下发一击")
        FrameExpectationSignal.reached()

        assertEquals(0L, FrameExpectationSignal.armedAtMs)
        assertFalse(FrameExpectationSignal.isOverdue(0L, nowMs = 999_999L))
    }

    @Test
    fun timeoutIsThreeSeconds() {
        // 口径钉死：合成器出帧是毫秒级的，3 秒还没来就不是"慢"而是"停了"
        assertTrue(FrameExpectationSignal.DEFAULT_TIMEOUT_MS == 3_000L)
    }

    @Test
    fun panelExpectationsAreNotGateEligibleByDefault() {
        // **红线**：面板展开 / 收起 / 内容变化（`FloatingWindow` 那三处）用的是默认值 ⇒ **不参与硬闸**。
        // 理由（2026-09-28 第 217 条）：授权里选「共享一个应用」时我们的覆盖窗**不在采集内容里**，
        // 面板怎么变都不会来新帧 ⇒ 认它就会误杀正常会话（那次直接被结束会话，用户刚授完权就被要求重授权）。
        // ⇒ 默认必须是"只诊断"，否则这道闸会退化成天天误报 ✗
        FrameExpectationSignal.arm("单测：悬浮窗展开（默认参数 = 面板类）")

        assertFalse("面板类期待默认不许升级成硬闸", FrameExpectationSignal.gateEligible)
    }

    @Test
    fun ourOwnGestureExpectationsAreGateEligible() {
        // 另一面：**我们刚下发过一击 / 滑动**绑的是被采集那个 App 的内容 ⇒ 内容必然变了、出帧是必然的，
        // 与采集模式无关 ⇒ 落空才是真故障，允许升级成硬闸（真机：会话重建后 5 分钟无新帧还在点）
        FrameExpectationSignal.arm("单测：刚下发一击", gateEligible = true)

        assertTrue(FrameExpectationSignal.gateEligible)
    }

    @Test
    fun reachedAlsoClearsTheGateEligibility() {
        // 自愈路径要清干净：新帧到达 ⇒ reached() ⇒ 连"这一份期待能不能闸"也一并作废，
        // 否则上一击留下的标记会被下一份面板期待继承（就是误报的来源）
        FrameExpectationSignal.arm("单测：刚下发一击", gateEligible = true)
        FrameExpectationSignal.reached()

        assertFalse(FrameExpectationSignal.gateEligible)
        assertEquals("", FrameExpectationSignal.reason)
    }
}
