package com.example.mastermechanic.patrol

import com.example.mastermechanic.patrol.PatrolFlow.Outcome
import com.example.mastermechanic.patrol.PatrolFlow.Range
import com.example.mastermechanic.patrol.PatrolFlow.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * 「刚跑完的结果快照」（2026-09-30，真机验收第 327 条）：
 * 跑完那一刻 `PatrolSession` 被收掉，标签与菜单**一起变空** ⇒ 用户看不到「已完成」。
 *
 * 这里钉三条口径（都是"改了就会出问题"的那种）：
 * 1. **刚跑完能看到**（窗口内），超时看不到；
 * 2. **有新流程接替时永远让位**（不需要谁去清它 —— 这正是"两个来源打架"最容易被写出来的地方）；
 * 3. **会话边界清空**（新会话不该挂着上一段的"已完成"）。
 */
class PatrolResultSignalTest {

    private val finished: PatrolFlow.State = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
        .copy(outcome = Outcome.FINISHED)

    @Before
    fun reset() {
        // 单例信号：每条用例先清干净，避免互相干扰
        PatrolResultSignal.clear()
    }

    @Test
    fun finishedRunStaysVisibleForTheWindowThenGoesAway() {
        PatrolResultSignal.show(finished, nowMs = 1_000)

        assertSame("刚跑完 ⇒ 界面要读到这次结果", finished, PatrolResultSignal.displayState(null, 1_000))
        assertSame(
            "窗口最后一毫秒仍显示（边界）",
            finished,
            PatrolResultSignal.displayState(null, 1_000 + PatrolResultSignal.SHOW_MS),
        )
        assertNull(
            "过窗口 ⇒ 不再显示（一条陈旧的「已完成」长期占着标签就成了噪声）",
            PatrolResultSignal.displayState(null, 1_000 + PatrolResultSignal.SHOW_MS + 1),
        )
    }

    @Test
    fun aRunningFlowAlwaysWinsOverTheSnapshot() {
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
        PatrolResultSignal.show(finished, nowMs = 1_000)

        assertSame(
            "新流程一开始，「已完成」必须让位（同一个窗口内也不许抢）",
            running,
            PatrolResultSignal.displayState(running, 1_100),
        )
    }

    @Test
    fun nothingFinishedMeansNothingToShow() {
        // 刚打开 App / 还没跑过：不能凭空显示一句话（否则用户会以为"跑完了"）
        assertNull(PatrolResultSignal.displayState(null, 5_000))
        assertNull(PatrolResultSignal.recent(5_000))
    }

    @Test
    fun sessionBoundaryClearsTheSnapshot() {
        PatrolResultSignal.show(finished, nowMs = 1_000)
        assertSame(finished, PatrolResultSignal.recent(1_100))

        // 采集会话结束 / 重建（`CaptureService.releaseSession`）⇒ 结果不跨会话
        PatrolResultSignal.clear()
        assertNull("新会话不该挂着上一段的「已完成」", PatrolResultSignal.recent(1_100))
    }
}
