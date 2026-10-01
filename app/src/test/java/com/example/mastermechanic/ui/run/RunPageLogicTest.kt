package com.example.mastermechanic.ui.run

import com.example.mastermechanic.auth.AuthItem
import com.example.mastermechanic.auth.AuthState
import com.example.mastermechanic.auth.AuthStatus
import com.example.mastermechanic.patrol.PatrolFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「运行」页的判据（M5-U2）：**画面这一项从最严重往下说**；**停止 / 继续与悬浮窗控制行同一条判据**。
 *
 * ⚠ 本用例只能验纯逻辑（做得到的话就别写界面测试）：
 * 「三块读数与悬浮窗说同一句话」这件事靠的是**数据源同一份**（本页不持有状态），
 * 那条要在真机上对着悬浮窗和本页一起看 —— 见 `docs/verification/m5/README.md` 的 U2 一节。
 */
class RunPageLogicTest {

    @Test
    fun theMostSevereFrameProblemWins() {
        // 顺序：会话不在 > 没被投喂 > 停更 > 正常。**三种毛病的处置不一样**，说错了用户就白折腾：
        // 「会话不在」时让他"再等等看"是没用的（此时必然同时"停更"，但停更不是根因）。
        assertEquals(
            "会话都没了，还去说'停更 N 秒'会把用户引向'再等等'",
            RunPageLogic.FrameState.SESSION_DOWN,
            RunPageLogic.frameState(captureActive = false, starved = true, stale = true),
        )
        assertEquals(
            "这次授权一帧都没被投喂：处置是重开 App（同进程重建常常救不回来）",
            RunPageLogic.FrameState.NEVER_FED,
            RunPageLogic.frameState(captureActive = true, starved = true, stale = true),
        )
        assertEquals(
            "只是停更：先重新授权采集，或让游戏画面动一下",
            RunPageLogic.FrameState.STALE,
            RunPageLogic.frameState(captureActive = true, starved = false, stale = true),
        )
        assertEquals(
            "一切正常",
            RunPageLogic.FrameState.LIVE,
            RunPageLogic.frameState(captureActive = true, starved = false, stale = false),
        )
    }

    @Test
    fun buttonsFollowTheSameRuleAsTheFloatingControlRow() {
        // 判据借的是 PatrolStatus 的那两个方法（"能不能按"的唯一出处）—— 这里钉住它确实透传，
        // 而不是页面自己另写一套（另写就会出现"App 显示能按、按了却说没有流程"）。
        val idle = RunPageLogic.buttons(null)
        assertFalse("没有流程时不该出现「停止」（2026-09-22 用户口径）", idle.stop)
        assertFalse("更没有「继续」", idle.resume)

        val running = PatrolFlow.State(range = PatrolFlow.Range.SWITCH_AND_VISIT, step = PatrolFlow.Step.ENTER_FARM)
        val runningButtons = RunPageLogic.buttons(running)
        assertTrue("跑着就要能停", runningButtons.stop)
        assertFalse("跑着不摆「继续」（点了没用会让人以为卡住）", runningButtons.resume)

        val paused = PatrolFlow.pause(running)
        assertEquals("暂停：继续 + 停止", RunPageLogic.Buttons(stop = true, resume = true), RunPageLogic.buttons(paused))

        assertEquals(
            "中止：继续 + 停止（从失败那一步接着来）",
            RunPageLogic.Buttons(stop = true, resume = true),
            RunPageLogic.buttons(failedAt(PatrolFlow.Step.OPEN_FRIENDS)),
        )

        val finished = PatrolFlow.advance(PatrolFlow.State(range = PatrolFlow.Range.SWITCH_ONLY, step = PatrolFlow.Step.LOGIN))
        assertEquals(
            "已完成：两个按钮都不给（这次执行结束了，要再来一次是重新点菜单）",
            RunPageLogic.Buttons(stop = false, resume = false),
            RunPageLogic.buttons(finished),
        )
    }

    @Test
    fun authSummarySaysHowManyAreStillMissing() {
        val allReady = AuthItem.entries.map { AuthStatus(it, AuthState.GRANTED) }
        assertTrue("四项全给 ⇒ 就绪", RunPageLogic.authReady(allReady))
        assertEquals(0, RunPageLogic.missingAuthCount(allReady))

        val missingTwo = listOf(
            AuthStatus(AuthItem.ACCESSIBILITY, AuthState.GRANTED),
            AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.MISSING),
            AuthStatus(AuthItem.RESIDENT, AuthState.MISSING),
            AuthStatus(AuthItem.NOTIFICATIONS, AuthState.GRANTED),
        )
        assertFalse("缺两项不能说成就绪", RunPageLogic.authReady(missingTwo))
        assertEquals("要说得出缺几项", 2, RunPageLogic.missingAuthCount(missingTwo))
    }

    /** 同一步连续失败到上限 ⇒ 中止状态（与 `PatrolStatusTest.failedAt` 同一个造法）。 */
    private fun failedAt(step: PatrolFlow.Step): PatrolFlow.State {
        var state = PatrolFlow.State(range = PatrolFlow.Range.SWITCH_AND_VISIT, step = step)
        repeat(PatrolFlow.MAX_RETRIES) { state = PatrolFlow.fail(state, "好友列表没打开") }
        return state
    }
}
