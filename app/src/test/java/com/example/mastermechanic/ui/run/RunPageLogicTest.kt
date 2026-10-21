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
    fun thePageOnlyStopsAndPointsAtTheRightPlaceToContinue() {
        // 判据借的是 PatrolStatus 的那两个方法（"能不能按"的唯一出处）—— 这里钉住它确实透传，
        // 而不是页面自己另写一套（另写就会出现"App 显示能按、按了却说没有流程"）。
        //
        // ⚠ **「继续」在本页不是按钮、只是一句指路**（用户 2026-10-01 真机："在 App 里出现继续按钮
        // 没有意义，因为游戏不在前台"）：跑号要游戏在前台才成立，而在 App 里按「继续」时前台正是我们自己
        // ⇒ 那一枪要么被门禁拦下、要么立刻再暂停一次。入口留在游戏（与"发起只在悬浮窗"同一条口径）。
        val idle = RunPageLogic.actions(null)
        assertFalse("没有流程时不该出现「停止」（2026-09-22 用户口径）", idle.stop)
        assertFalse("也没有「该回游戏继续」这回事", idle.resumeInGame)

        val running = PatrolFlow.State(range = PatrolFlow.Range.SWITCH_AND_VISIT, step = PatrolFlow.Step.ENTER_FARM)
        val runningActions = RunPageLogic.actions(running)
        assertTrue("跑着就要能停", runningActions.stop)
        assertFalse("跑着也不用指路", runningActions.resumeInGame)

        val paused = PatrolFlow.pause(running)
        assertEquals(
            "暂停：能停 + 要提示「回游戏点继续」",
            RunPageLogic.Actions(stop = true, resumeInGame = true),
            RunPageLogic.actions(paused),
        )

        assertEquals(
            "中止：同样能停 + 指路（从失败那一步接着来）",
            RunPageLogic.Actions(stop = true, resumeInGame = true),
            RunPageLogic.actions(failedAt(PatrolFlow.Step.OPEN_FRIENDS)),
        )

        val finished = PatrolFlow.advance(
            PatrolFlow.State(range = PatrolFlow.Range.SWITCH_ONLY, step = PatrolFlow.Step.LOGIN),
        )
        assertEquals(
            "已完成：什么都不给（这次执行结束了，要再来一次是重新点菜单）",
            RunPageLogic.Actions(stop = false, resumeInGame = false),
            RunPageLogic.actions(finished),
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
        // 2026-10-02 用户："把名字也写上" ⇒ 不只报数，还要报**是哪几项**（顺序 = 授权页自上而下）
        assertEquals(
            "界面才念得出「还缺 2 项：屏幕采集、常驻守护」",
            listOf(AuthItem.SCREEN_CAPTURE, AuthItem.RESIDENT),
            RunPageLogic.missingAuthItems(missingTwo),
        )
    }

    @Test
    fun theAuthCardOnlyFiresCaptureWhenNothingElseIsMissing() {
        // **2026-10-02 用户报障**："点击『去授权与权限』的逻辑有问题，目前是**直接拉起采集授权操作**，
        // 一同意就跳转到游戏了，此时**无障碍和守护可能还没启动**，又要切换回 app 来启动这两项。"
        // ⇒ 判据：采集前面还有缺项 ⇒ **只导航**；**只剩采集** ⇒ 才一键拉起。
        val capturePlusAccessibility = listOf(
            AuthStatus(AuthItem.ACCESSIBILITY, AuthState.MISSING),
            AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.MISSING),
            AuthStatus(AuthItem.RESIDENT, AuthState.GRANTED),
            AuthStatus(AuthItem.NOTIFICATIONS, AuthState.GRANTED),
        )
        assertEquals(
            "无障碍还没开 ⇒ 这一下只许导航（否则用户被送去游戏，还得切回来开无障碍）",
            RunPageLogic.AuthAction.OPEN_AUTH,
            RunPageLogic.authAction(capturePlusAccessibility),
        )
        assertEquals(
            "挡着采集的项要说得出是哪一个（界面提示要用）",
            listOf(AuthItem.ACCESSIBILITY),
            RunPageLogic.captureBlockers(capturePlusAccessibility),
        )

        val onlyCapture = capturePlusAccessibility.map {
            if (it.item == AuthItem.ACCESSIBILITY) it.copy(state = AuthState.GRANTED) else it
        }
        assertEquals(
            "只剩采集 ⇒ 一键建立会话（点完就该去游戏了）",
            RunPageLogic.AuthAction.CAPTURE,
            RunPageLogic.authAction(onlyCapture),
        )

        val allReady = AuthItem.entries.map { AuthStatus(it, AuthState.GRANTED) }
        assertEquals(
            "四项都好 ⇒ 按钮退回「去授权页」（本页不提供第二个发起点）",
            RunPageLogic.AuthAction.OPEN_AUTH,
            RunPageLogic.authAction(allReady),
        )
    }

    /** 同一步连续失败到上限 ⇒ 中止状态（与 `PatrolStatusTest.failedAt` 同一个造法）。 */
    private fun failedAt(step: PatrolFlow.Step): PatrolFlow.State {
        var state = PatrolFlow.State(range = PatrolFlow.Range.SWITCH_AND_VISIT, step = step)
        repeat(PatrolFlow.MAX_RETRIES) { state = PatrolFlow.fail(state, "好友列表没打开") }
        return state
    }
}
