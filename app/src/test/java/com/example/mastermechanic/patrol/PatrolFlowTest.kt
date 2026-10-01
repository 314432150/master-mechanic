package com.example.mastermechanic.patrol

import com.example.mastermechanic.decision.UiState
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
        // 2026-09-21 合并：好友的农场也读成 Scene.FARM（见 PatrolScenesTest），
        // 所以"在农场"这一屏只有一种起点：第 2 步退出农场
        assertEquals(Step.LEAVE_FARM, PatrolFlow.firstStep(Scene.FARM, Range.SWITCH_ONLY))
    }

    @Test
    fun visitOnlySkipsAllSwitchSteps() {
        // 只拜访：从第 7 步起，跳过全部换号步骤
        assertEquals(Step.ENTER_FARM, PatrolFlow.firstStep(Scene.LOBBY, Range.VISIT_ONLY))
        // 已经在农场（自己的 / 别人的，合并后同一个 Scene）→ 第 7 步的「进入农场」没有入口，直接开好友列表
        assertEquals(Step.OPEN_FRIENDS, PatrolFlow.firstStep(Scene.FARM, Range.VISIT_ONLY))
    }

    @Test
    fun visitOnlyRejectsScenesThatAreNotInGameYet() {
        // 只拜访却还停在启动页 / 选服页 → 起点不成立，必须中止（不猜）
        assertNull(PatrolFlow.firstStep(Scene.BOOT, Range.VISIT_ONLY))
        assertNull(PatrolFlow.firstStep(Scene.SERVER_LIST, Range.VISIT_ONLY))
    }

    @Test
    fun screensThatAreNotStartPointsNeverStart() {
        // 好友列表 / 设置页 / 退出登录确认框：认得出来，但起点表里没有它们
        // → 中止并让用户先退出来，不做猜测性点击
        for (range in Range.entries) {
            assertNull(PatrolFlow.firstStep(Scene.NOT_A_START, range))
        }
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
        assertEquals(Step.LEAVE_FARM, PatrolFlow.firstStep(Scene.FARM, Range.SWITCH_AND_VISIT))
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

    @Test
    fun resumingAfterAPauseForgetsThePendingClickAndTheElapsedBudget() {
        // 2026-10-01 真机（用户原话："**暂停后切出再切回游戏，点了继续没有用**"）：
        // 暂停前那一击的标记 `lastActionAtMs` 留在状态里 ⇒ 继续后 `PatrolRunner` ⑤
        //（"本步已下过击 ⇒ 只等期望画面、绝不补点"）立刻接手 ⇒ **既不重新定位也不补点** ⇒
        // 干等到这一步的预算耗尽（真机实录：继续后从"已等 28 秒"一路涨到 47 秒+）✗
        // ⇒ 继续时必须① 作废这一击 ② 把预算顺延到此刻（暂停的时长不该算进这一步的耐心）。
        val paused = PatrolFlow.State(
            range = Range.SWITCH_AND_VISIT,
            step = Step.LOGOUT,
            paused = true,
            reason = "游戏不在前台，已暂停",
            stepEnteredAtMs = 1_000L,
            lastActionAtMs = 900L,
            stateAtLastAction = UiState.HALL_SETTINGS,
        )

        val resumed = PatrolFlow.resume(paused, nowMs = 30_000L)

        assertEquals(Outcome.RUNNING, resumed.outcome)
        assertFalse(resumed.paused)
        assertNull(resumed.reason)
        assertEquals("预算顺延到此刻", 30_000L, resumed.stepEnteredAtMs)
        assertEquals("本步已下过击的标记必须作废，否则会死在「只等画面」那一支", 0L, resumed.lastActionAtMs)
        assertNull(resumed.stateAtLastAction)
        assertFalse("顺延后不该立刻判超时", PatrolFlow.timedOut(resumed, 30_000L))
    }

    @Test
    fun resumingAfterAnAbortAlsoForgetsThePendingClick() {
        // 中止多半就发生在"等期望画面"超时的那一刻（那时同样 `lastActionAtMs > 0`）⇒ 继续后若不作废，
        // 一样会死在 ⑤ 分支里。两条来路（暂停 / 中止）必须**一个口径**。
        val failed = PatrolFlow.State(
            range = Range.VISIT_ONLY,
            step = Step.OPEN_FRIENDS,
            outcome = Outcome.FAILED,
            retries = 3,
            reason = "等了 15 秒仍未识别出要等的画面",
            stepEnteredAtMs = 1_000L,
            lastActionAtMs = 900L,
            stateAtLastAction = UiState.FARM,
        )

        val resumed = PatrolFlow.resume(failed, nowMs = 60_000L)

        assertEquals(Outcome.RUNNING, resumed.outcome)
        assertEquals(0, resumed.retries)
        assertEquals(60_000L, resumed.stepEnteredAtMs)
        assertEquals(0L, resumed.lastActionAtMs)
        assertNull(resumed.stateAtLastAction)
    }

    // ---------------------------------------------------------------- 时间预算（2026-09-20 增）

    @Test
    fun eachStepGetsItsOwnTimeBudget() {
        // 预算是**每步独立**的：进入这一步的时刻由 start / advance 打点
        val started = PatrolFlow.start(Scene.LOBBY, Range.VISIT_ONLY, nowMs = 1_000L)!!
        assertEquals(1_000L, started.stepEnteredAtMs)

        val next = PatrolFlow.advance(started, nowMs = 5_000L)

        assertEquals(5_000L, next.stepEnteredAtMs)
        assertFalse(
            "差 1ms 不算超时",
            PatrolFlow.timedOut(next, 5_000L + PatrolFlow.STEP_TIMEOUT_MS - 1),
        )
        assertTrue("到点即算超时", PatrolFlow.timedOut(next, 5_000L + PatrolFlow.STEP_TIMEOUT_MS))
    }

    @Test
    fun deadlineAbortsEvenBeforeRetriesRunOut() {
        // 光有"重试 3 次"不够：加载慢时可能一次都还没重试就该给它时间；到点仍未通过 → 中止
        val state = PatrolFlow.State(
            range = Range.VISIT_ONLY,
            step = Step.ENTER_FARM,
            stepEnteredAtMs = 1_000L,
        )

        val failed = PatrolFlow.fail(
            state,
            "等超时",
            nowMs = 1_000L + PatrolFlow.STEP_TIMEOUT_MS,
        )

        assertEquals(Outcome.FAILED, failed.outcome)
    }

    @Test
    fun theLoginStepGetsAWiderBudgetThanTheOthers() {
        // 2026-09-29 真机两次：第 6 步（点「登录游戏」→ 等「大厅」）等了 **14.2s / 13.4s**，而预算 15s
        // ⇒ 余量只剩 1 秒多。那是**纯游戏加载**（还常附带一个活动弹窗），与应用无关
        // ⇒ 第 6 步单独放宽到 30s（实测最慢的 2 倍余量），其余步骤仍是 15s（保留快速发现问题）。
        val login = PatrolFlow.State(range = Range.SWITCH_ONLY, step = Step.LOGIN, stepEnteredAtMs = 1_000L)

        assertFalse("20s 不该再把登录卡成中止", PatrolFlow.timedOut(login, 1_000L + 20_000L))
        assertTrue("到 30s 才算超时", PatrolFlow.timedOut(login, 1_000L + PatrolFlow.LOGIN_TIMEOUT_MS))

        val picked = PatrolFlow.State(range = Range.SWITCH_ONLY, step = Step.PICK_SERVER, stepEnteredAtMs = 1_000L)
        assertTrue("其它步骤仍是 15s", PatrolFlow.timedOut(picked, 1_000L + PatrolFlow.STEP_TIMEOUT_MS))
    }

    @Test
    fun theLogoutStepGetsItsOwnEvenWiderBudget() {
        // 2026-09-30 真机验收（问题 A）：点下「确认退出」后 **35 秒**仍停在「未知」，而**帧一直在来**
        // （230 / 155 帧每 10 秒 ⇒ 游戏在画、不是采集停更）⇒ 15 秒预算下整条"换号+拜访"被中止。
        // 用户给的领域知识：**频繁退出登录有概率触发系统风控 ⇒ 强制下线**，那条路要人工登录 / 授权
        // ⇒ 自动恢复时间长短不定 ⇒ 与第 6 步同源、给更宽的预算（其余步骤仍保持 15s）。
        val logout = PatrolFlow.State(
            range = Range.SWITCH_AND_VISIT,
            step = Step.LOGOUT,
            stepEnteredAtMs = 1_000L,
        )

        assertEquals(60_000L, PatrolFlow.LOGOUT_TIMEOUT_MS)
        assertFalse(
            "35 秒（真机实测那一刻）不该再把退出登录卡成中止",
            PatrolFlow.timedOut(logout, 1_000L + 35_000L),
        )
        assertTrue("到 60s 才算超时", PatrolFlow.timedOut(logout, 1_000L + PatrolFlow.LOGOUT_TIMEOUT_MS))
    }

    @Test
    fun theVisitFriendStepGetsABudgetForTheWholeSearchChain() {
        // 2026-10-01：第 9 步从"滑屏找人"改成"**先看当前屏；没有就搜索**"（五小步：点「搜好友」→
        // 点搜索框 → 原生写文字 → 提交/收输入法 → 点「搜索」≈ 6~8s），万一写入没进框还要**重写一次**
        // ⇒ 贴着通用的 15s，"结果页还没出来就被中止"几乎必然发生
        // （用户报："点搜索提示请输入文本，然后又去点输入框，继续重复直到时间到达中止阈值"）。
        // ⇒ 这一步单独放宽到 25s；其余步骤不受影响（第 3 / 6 步另有各自的预算）。
        val visit = PatrolFlow.State(
            range = Range.VISIT_ONLY,
            step = Step.VISIT_FRIEND,
            stepEnteredAtMs = 1_000L,
        )

        assertEquals(25_000L, PatrolFlow.FRIEND_TIMEOUT_MS)
        assertEquals(25_000L, PatrolFlow.timeoutFor(Step.VISIT_FRIEND))
        assertFalse(
            "15 秒（旧预算）不该再把搜索链卡成中止",
            PatrolFlow.timedOut(visit, 1_000L + PatrolFlow.STEP_TIMEOUT_MS),
        )
        assertTrue("到 25s 才算超时", PatrolFlow.timedOut(visit, 1_000L + PatrolFlow.FRIEND_TIMEOUT_MS))

        val picking = PatrolFlow.State(range = Range.SWITCH_ONLY, step = Step.PICK_SERVER, stepEnteredAtMs = 1_000L)
        assertTrue("其它步骤仍是 15s", PatrolFlow.timedOut(picking, 1_000L + PatrolFlow.STEP_TIMEOUT_MS))
    }

    @Test
    fun resumeRestartsTheTimeBudgetForTheFailedStep() {
        // 「继续」= 重新给这一步一次完整预算，不拿"之前等了多久"立刻再判超时
        val failed = PatrolFlow.State(
            range = Range.VISIT_ONLY,
            step = Step.ENTER_FARM,
            retries = 3,
            outcome = Outcome.FAILED,
            stepEnteredAtMs = 1_000L,
        )

        val resumed = PatrolFlow.resume(failed, nowMs = 90_000L)

        assertEquals(90_000L, resumed.stepEnteredAtMs)
        assertFalse(PatrolFlow.timedOut(resumed, 90_000L))
    }

    // ---------------------------------------------------------------- 常量（对应 FR-04 硬性要求）

    @Test
    fun hardLimitsMatchTheRequirements() {
        assertEquals(3, PatrolFlow.MAX_RETRIES) // 硬性要求 5：重试不超过 3 次
        assertEquals(300L, PatrolFlow.CLICK_GAP_MS) // 硬性要求 8 / NFR-06：相邻点击 ≥300ms
    }
}
