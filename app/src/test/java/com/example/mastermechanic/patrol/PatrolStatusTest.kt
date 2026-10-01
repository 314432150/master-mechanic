package com.example.mastermechanic.patrol

import com.example.mastermechanic.patrol.PatrolFlow.Outcome
import com.example.mastermechanic.patrol.PatrolFlow.Range
import com.example.mastermechanic.patrol.PatrolFlow.Scene
import com.example.mastermechanic.patrol.PatrolFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗要显示的那几行字（T4-5 / FR-05 / FR-06）。
 *
 * 这些是**判定**：显示什么、怎么算"要显示「继续」" —— 一旦算错，用户看到的就是"明明停了却说在等"。
 */
class PatrolStatusTest {

    // ---------------------------------------------------------------- 没在跑

    @Test
    fun withoutASessionNothingIsShown() {
        // 没跑就不显示进度块：面板与 M3 完全一样（不多占一行、也不出现点了没用的「继续」）
        assertNull(PatrolStatus.progressLine(null))
        assertNull(PatrolStatus.statusLine(null, "随便一句话"))
        assertFalse(PatrolStatus.canResume(null))
    }

    // ---------------------------------------------------------------- 进度行

    @Test
    fun theControlRowOnlyExistsWhileTheFlowCanStillBeActedOn() {
        // 2026-10-01 真机 bug：跑完那一刻 `PatrolSession.current` 已清空，但界面为了"能读到结果"
        // 还会显示 ≈30 秒的「已完成」快照 ⇒ 判据一旦用"界面那份显示态"，就会画出一个点了只说
        // "当前没有进行中的流程"的「停止」（用户报："流程显示已完成，可一级菜单还显示停止"）。
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.VISIT_ONLY))

        assertTrue("运行中 ⇒ 摆（「停止」）", PatrolStatus.showsControlRow(running))
        assertTrue(
            "已中止 ⇒ 摆（「继续 + 停止」）",
            PatrolStatus.showsControlRow(running.copy(outcome = Outcome.FAILED, reason = "找不到锚点")),
        )
        assertTrue("已暂停 ⇒ 摆", PatrolStatus.showsControlRow(running.copy(paused = true)))
        // ⚠ 回归钉子：已完成 ⇒ **不摆**（即便调用方误把"界面那份显示态"递进来，也不该再画控制行）
        assertFalse(
            "已完成 ⇒ 不摆（那是结果话，不是还能动手）",
            PatrolStatus.showsControlRow(running.copy(outcome = Outcome.FINISHED)),
        )
        assertFalse("从没跑过 ⇒ 不摆", PatrolStatus.showsControlRow(null))
    }

    @Test
    fun progressLineCarriesStepNumberAndChineseName() {
        val state = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_AND_VISIT))

        assertEquals(Step.LOGOUT, state.step) // 大厅起点 = 第 3 步「退出登录」
        assertEquals("第 3/10 步 · 退出登录", PatrolStatus.progressLine(state))
    }

    @Test
    fun totalStepCountFollowsTheRangeInsteadOfAConstant() {
        // 分母是**本次区间的终点步**：只拜访是 7 → 10，写成"第 7/10 步"才和日志里的步骤号对得上
        assertEquals(
            "第 7/10 步 · 进入农场",
            PatrolStatus.progressLine(requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.VISIT_ONLY))),
        )
        // 只换号到第 6 步为止："还有 4 步"这种话不能说
        assertEquals(
            "第 2/6 步 · 退出农场",
            PatrolStatus.progressLine(requireNotNull(PatrolFlow.start(Scene.FARM, Range.SWITCH_ONLY))),
        )
    }

    // ---------------------------------------------------------------- 状态行

    @Test
    fun runningShowsTheLiveNote() {
        val state = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))

        assertEquals("正在等「大厅」", PatrolStatus.statusLine(state, "正在等「大厅」"))
        // 还没有一句话可说时返回 null（面板会自己兜成「运行中」），不能编一句假的
        assertNull(PatrolStatus.statusLine(state, null))
    }

    @Test
    fun failureOutranksTheLiveNote() {
        // 已经中止了却还在显示"正在等…"，等于把用户往错的方向带
        val failed = failedAt(Step.OPEN_FRIENDS)

        assertEquals(Outcome.FAILED, failed.outcome)
        assertEquals("已中止：好友列表没打开", PatrolStatus.statusLine(failed, "正在等「农场」"))
    }

    @Test
    fun failureWithoutAReasonStillSaysItStopped() {
        // 原因可能是空的（例如构造出来的状态）：也不能显示成"什么都没发生"
        val failed = PatrolFlow.State(
            range = Range.SWITCH_AND_VISIT,
            step = Step.OPEN_FRIENDS,
            outcome = Outcome.FAILED,
        )

        assertEquals("已中止：原因未知", PatrolStatus.statusLine(failed, null))
    }

    @Test
    fun pauseSaysWhyAndHowToGetOut() {
        // FR-05：**不自动继续** —— 暂停那一句必须让人知道"得自己点一下"
        val paused = PatrolFlow.pause(requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY)))

        assertTrue(paused.paused)
        assertEquals("游戏不在前台，已暂停", PatrolStatus.statusLine(paused, "正在等「大厅」"))
    }

    @Test
    fun finishedSaysFinished() {
        val finished = PatrolFlow.advance(
            PatrolFlow.State(range = Range.SWITCH_ONLY, step = Step.LOGIN),
        )

        assertEquals(Outcome.FINISHED, finished.outcome)
        assertEquals("本次执行已完成", PatrolStatus.statusLine(finished, "正在等「大厅」"))
    }

    // ---------------------------------------------------------------- 要不要给「继续」

    @Test
    fun onlyFailedOrPausedCanResume() {
        val running = PatrolFlow.State(range = Range.SWITCH_AND_VISIT, step = Step.ENTER_FARM)
        assertFalse("正常跑着不该出现「继续」", PatrolStatus.canResume(running))

        assertTrue(PatrolStatus.canResume(failedAt(Step.ENTER_FARM)))
        assertTrue(PatrolStatus.canResume(PatrolFlow.pause(running)))
        // 已完成**不算**可继续：跑到终点就是这次执行结束（FR-04），要再来一次是重新点菜单，
        // 而且 CaptureService 会立刻把会话收掉 —— 面板上不该出现一个点了没用的「继续」
        assertFalse(
            PatrolStatus.canResume(PatrolFlow.advance(PatrolFlow.State(Range.SWITCH_ONLY, Step.LOGIN))),
        )
    }

    // ---------------------------------------------------------------- 工具

    /** 同一步连续失败到上限（[PatrolFlow.MAX_RETRIES] 次）→ 中止状态。 */
    private fun failedAt(step: Step): PatrolFlow.State {
        var state = PatrolFlow.State(range = Range.SWITCH_AND_VISIT, step = step)
        repeat(PatrolFlow.MAX_RETRIES) { state = PatrolFlow.fail(state, "好友列表没打开") }
        return state
    }
}
