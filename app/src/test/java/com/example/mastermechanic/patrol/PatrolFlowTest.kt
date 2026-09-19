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

/** FR-04 状态机：起点判定、区间、逐步推进、失败重试、继续。 */
class PatrolFlowTest {

    // ---------------------------------------------------------------- 起点 → 起始步骤

    @Test
    fun switchRangeStartsDependsOnScene() {
        assertEquals(Step.SWITCH_SERVER, PatrolFlow.firstStep(Scene.BOOT, Range.SWITCH_ONLY))
        assertEquals(Step.PICK_SERVER, PatrolFlow.firstStep(Scene.SERVER_LIST, Range.SWITCH_ONLY))
        assertEquals(Step.LOGOUT, PatrolFlow.firstStep(Scene.LOBBY, Range.SWITCH_ONLY))
        assertEquals(Step.LEAVE_FARM, PatrolFlow.firstStep(Scene.FARM, Range.SWITCH_ONLY))
        assertEquals(Step.LEAVE_FARM, PatrolFlow.firstStep(Scene.FRIEND_FARM, Range.SWITCH_ONLY))
    }

    @Test
    fun visitOnlySkipsAllSwitchSteps() {
        // 只拜访：从第 7 步起，跳过全部换号步骤
        assertEquals(Step.ENTER_FARM, PatrolFlow.firstStep(Scene.LOBBY, Range.VISIT_ONLY))
        // 已经在农场（自己的 / 别人的）→ 第 7 步的「进入农场」没有入口，直接开好友列表
        assertEquals(Step.OPEN_FRIENDS, PatrolFlow.firstStep(Scene.FARM, Range.VISIT_ONLY))
        assertEquals(Step.OPEN_FRIENDS, PatrolFlow.firstStep(Scene.FRIEND_FARM, Range.VISIT_ONLY))
    }

    @Test
    fun visitOnlyRejectsScenesThatAreNotInGameYet() {
        // 只拜访却还停在启动页 / 选服页 → 起点不成立，必须中止（不猜）
        assertNull(PatrolFlow.firstStep(Scene.BOOT, Range.VISIT_ONLY))
        assertNull(PatrolFlow.firstStep(Scene.SERVER_LIST, Range.VISIT_ONLY))
    }

    @Test
    fun unknownSceneNeverStarts() {
        // 认不出画面 → 一发点击都不发（红线 2）
        for (range in Range.entries) {
            assertNull(PatrolFlow.firstStep(Scene.UNKNOWN, range))
            assertNull(PatrolFlow.start(Scene.UNKNOWN, range))
        }
    }

    @Test
    fun switchAndVisitUsesTheSwitchStartPoint() {
        assertEquals(Step.LEAVE_FARM, PatrolFlow.firstStep(Scene.FRIEND_FARM, Range.SWITCH_AND_VISIT))
        assertEquals(Step.PICK_SERVER, PatrolFlow.firstStep(Scene.SERVER_LIST, Range.SWITCH_AND_VISIT))
    }

    // ---------------------------------------------------------------- 区间

    @Test
    fun rangesCoverTheRightSteps() {
        assertTrue(PatrolFlow.inRange(Step.LOGIN, Range.SWITCH_ONLY))
        assertFalse(PatrolFlow.inRange(Step.ENTER_FARM, Range.SWITCH_ONLY))
        assertTrue(PatrolFlow.inRange(Step.OPEN_FRIENDS, Range.VISIT_ONLY))
        assertFalse(PatrolFlow.inRange(Step.PICK_SERVER, Range.VISIT_ONLY))
        assertTrue(PatrolFlow.inRange(Step.PICK_SERVER, Range.SWITCH_AND_VISIT))
        assertTrue(PatrolFlow.inRange(Step.VISIT_FRIEND, Range.SWITCH_AND_VISIT))
    }

    @Test
    fun lastStepMatchesRange() {
        assertEquals(Step.LOGIN, PatrolFlow.lastStep(Range.SWITCH_ONLY))
        assertEquals(Step.DONE, PatrolFlow.lastStep(Range.VISIT_ONLY))
        assertEquals(Step.DONE, PatrolFlow.lastStep(Range.SWITCH_AND_VISIT))
    }

    // ---------------------------------------------------------------- 推进

    @Test
    fun advanceWalksStepByStep() {
        var state = PatrolFlow.start(Scene.LOBBY, Range.SWITCH_AND_VISIT)!! // 从退出登录开始
        assertEquals(Step.LOGOUT, state.step)
        state = PatrolFlow.advance(state)
        assertEquals(Step.SWITCH_SERVER, state.step)
        state = PatrolFlow.advance(state)
        assertEquals(Step.PICK_SERVER, state.step)
    }

    @Test
    fun switchOnlyFinishesAtLoginNotAtVisit() {
        // 只换号：第 6 步验证通过就算完成 —— 不该继续去点农场 / 好友
        var state = PatrolFlow.State(range = Range.SWITCH_ONLY, step = Step.LOGIN)
        state = PatrolFlow.advance(state)
        assertEquals(Outcome.FINISHED, state.outcome)
        assertEquals(Step.LOGIN, state.step)
    }

    @Test
    fun fullRangeFinishesAtDone() {
        var state = PatrolFlow.State(range = Range.SWITCH_AND_VISIT, step = Step.VISIT_FRIEND)
        state = PatrolFlow.advance(state)
        assertEquals(Step.DONE, state.step)
        state = PatrolFlow.advance(state)
        assertEquals(Outcome.FINISHED, state.outcome)
    }

    @Test
    fun advanceDoesNothingAfterFinishOrFail() {
        val finished = PatrolFlow.State(range = Range.SWITCH_ONLY, step = Step.LOGIN, outcome = Outcome.FINISHED)
        assertEquals(finished, PatrolFlow.advance(finished))
    }

    // ---------------------------------------------------------------- 失败 / 重试 / 继续

    @Test
    fun failRetriesTheSameStepThenGivesUpAtTheThirdTime() {
        var state = PatrolFlow.State(range = Range.SWITCH_AND_VISIT, step = Step.PICK_SERVER)
        state = PatrolFlow.fail(state, "没找到区服「龙腾」")
        assertEquals(1, state.retries)
        assertEquals(Step.PICK_SERVER, state.step) // 原地重试，不跳步
        assertTrue(state.isRunning)
        state = PatrolFlow.fail(state, "没找到区服「龙腾」")
        assertTrue(state.isRunning)
        state = PatrolFlow.fail(state, "没找到区服「龙腾」")
        assertEquals(Outcome.FAILED, state.outcome)
        assertEquals("没找到区服「龙腾」", state.reason)
    }

    @Test
    fun advancingClearsTheRetryCount() {
        var state = PatrolFlow.State(range = Range.SWITCH_AND_VISIT, step = Step.LOGIN)
        state = PatrolFlow.fail(state, "还在加载")
        assertEquals(1, state.retries)
        state = PatrolFlow.advance(state)
        assertEquals(0, state.retries)
        assertNull(state.reason)
    }

    @Test
    fun resumeRestartsFromTheFailedStep() {
        // FR-05：用户点「继续」→ 从失败那一步重试，**不重跑前面的步骤**
        var state = PatrolFlow.State(range = Range.SWITCH_AND_VISIT, step = Step.OPEN_FRIENDS)
        repeat(3) { state = PatrolFlow.fail(state, "好友列表没打开") }
        assertEquals(Outcome.FAILED, state.outcome)
        val resumed = PatrolFlow.resume(state)
        assertEquals(Outcome.RUNNING, resumed.outcome)
        assertEquals(Step.OPEN_FRIENDS, resumed.step)
        assertEquals(0, resumed.retries)
    }

    @Test
    fun resumeOnARunningStateChangesNothing() {
        val running = PatrolFlow.State(range = Range.VISIT_ONLY, step = Step.VISIT_FRIEND)
        assertEquals(running, PatrolFlow.resume(running))
    }

    // ---------------------------------------------------------------- 常量（对应 FR-04 硬性要求）

    @Test
    fun hardLimitsMatchTheRequirements() {
        assertEquals(3, PatrolFlow.MAX_RETRIES) // 硬性要求 5：重试不超过 3 次
        assertEquals(300L, PatrolFlow.CLICK_GAP_MS) // 硬性要求 8 / NFR-06：相邻点击 ≥300ms
    }
}
