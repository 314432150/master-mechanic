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
    fun everyRangeSaysWhichFlowItIsInLogs() {
        // 2026-10-03 用户定稿：日志前缀必须带**具体流程名** —— 上一版只写"流程"两个字，
        // 用户问"**怎么区分具体是哪个流程？**"⇒ 菜单上有三种（换号 / 拜访 / 换号+拜访），日志要能对上。
        // 取值标准 = **它到底在做什么**（= [Range] 决定的区间），不是"用户点了哪一行"：
        // 「拜访（预设）」走的就是换号+拜访 ⇒ 日志写「换号+拜访」。
        assertEquals("换号", Range.SWITCH_ONLY.logLabel)
        assertEquals("拜访", Range.VISIT_ONLY.logLabel)
        assertEquals("换号+拜访", Range.SWITCH_AND_VISIT.logLabel)
    }

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
            // ⚠ 2026-10-03：第 7 步（进入农场）有自己的 20s 预算（[FARM_ENTER_TIMEOUT_MS]），
            // 这里必须用它 —— 拿旧的 15s 试，15s < 20s 就不算超时，钉不住"到点即中止"这件事。
            nowMs = 1_000L + PatrolFlow.FARM_ENTER_TIMEOUT_MS,
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
    fun enteringTheFarmGetsItsOwnTwentySecondsAndNobodyElseGetsBigger() {
        // 2026-10-03 真机：第 7 步点下农场入口后**加载 8.7 秒**才被确认（另一次 6.6s）⇒ 15s 只剩 1.7 倍余量。
        // 按项目自己的取法（实测最慢 × 2，第 3/6/9 步都这么定）给到 **20s**。
        // ⚠ **不给 30s**：30s 是第 6 步（登录要启游戏拉资源）的档位；第 7 步的失败模式用户一眼能看出来，
        // 干等 30 秒是纯亏、还会掩盖真故障。
        assertEquals(20_000L, PatrolFlow.FARM_ENTER_TIMEOUT_MS)
        assertEquals(20_000L, PatrolFlow.timeoutFor(Step.ENTER_FARM))

        val entering = PatrolFlow.State(range = Range.SWITCH_AND_VISIT, step = Step.ENTER_FARM, stepEnteredAtMs = 1_000L)
        assertFalse("15s（旧值）不该再把进农场卡成中止", PatrolFlow.timedOut(entering, 1_000L + PatrolFlow.STEP_TIMEOUT_MS))
        assertTrue("到 20s 才算超时", PatrolFlow.timedOut(entering, 1_000L + PatrolFlow.FARM_ENTER_TIMEOUT_MS))

        // 📌 用户 2026-10-03 问过"其他等待期可否统一 30s" ⇒ 不行：这四步点完画面 0.7~2.1 秒就变
        // （真机：第 4 步 0.7s、第 5 步 2.1s），15s 已是 7~15 倍余量，统一只会把真故障的发现时间白拉长一倍
        for (step in listOf(Step.CHECK_START, Step.LEAVE_FARM, Step.SWITCH_SERVER, Step.PICK_SERVER, Step.OPEN_FRIENDS)) {
            assertEquals("$step 不许跟着加长", PatrolFlow.STEP_TIMEOUT_MS, PatrolFlow.timeoutFor(step))
        }
    }

    @Test
    fun thePatienceClockStartsWhenWeActuallyAct() {
        // **乙方案（2026-10-03）**：耐心从"我们最后一次真动手"起算 —— "还没动手的时间不扣耐心"。
        // 复刻当天那次真机：第 7 步 01:03:27.6 进入（准备 7.5s：让路窗 + FR-02 关两层新手屏），
        // 01:03:35.2 才点下去，游戏加载 8.7s ⇒ 旧口径 16.3s > 15s 在命中前 1.24s 中止。
        val entered = 1_000L
        val acted = 8_500L // 进入后 7.5 秒才动手（＝点击那一刻）
        val state = PatrolFlow.State(
            range = Range.SWITCH_AND_VISIT,
            step = Step.ENTER_FARM,
            stepEnteredAtMs = entered,
            lastActionAtMs = acted,
        )

        assertEquals("起算点 = 动手那一刻", acted, PatrolFlow.budgetBase(state))
        assertFalse(
            "从进入步骤算已经等了 15.5 秒，但耐心从动手那刻起算 ⇒ 不算超时",
            PatrolFlow.timedOut(state, entered + PatrolFlow.STEP_TIMEOUT_MS),
        )
        assertTrue("从动手那刻起 20s 到点才超时（覆盖 8.7s 加载 ⇒ 余量 11.3s）", PatrolFlow.timedOut(state, acted + PatrolFlow.FARM_ENTER_TIMEOUT_MS))
        assertFalse("还差 1ms 不算", PatrolFlow.timedOut(state, acted + PatrolFlow.FARM_ENTER_TIMEOUT_MS - 1))
    }

    @Test
    fun beforeActingTheBudgetStillRunsFromStepEntrySoStuckStepsAreFoundFast() {
        // 乙方案的**另一半**：还没动手时（锚点定位不到 / 迟迟不敢点）**仍旧**从进入这一步起算 15s
        // ⇒ "点不动 / 进错界面"这类真故障不会被这条口径惯着 indefinitely 等下去。
        val state = PatrolFlow.State(
            range = Range.SWITCH_ONLY,
            step = Step.OPEN_FRIENDS,
            stepEnteredAtMs = 1_000L,
        )

        assertEquals(1_000L, PatrolFlow.budgetBase(state))
        assertFalse("没到点不超时", PatrolFlow.timedOut(state, 1_000L + PatrolFlow.STEP_TIMEOUT_MS - 1))
        assertTrue("到点即超时", PatrolFlow.timedOut(state, 1_000L + PatrolFlow.STEP_TIMEOUT_MS))
    }

    @Test
    fun aRevokedActionDoesNotBuyExtraPatience() {
        // "没点成"不许被当成"动过手"：门禁拒发 / 被遮挡屏吞掉都会
        // [PatrolFlow.withoutPendingAction] 清掉标记 ⇒ 起点自动回退到进入步骤那一刻。
        val acted = PatrolFlow.acted(
            PatrolFlow.State(range = Range.SWITCH_AND_VISIT, step = Step.ENTER_FARM, stepEnteredAtMs = 1_000L),
            8_500L,
        )
        val revoked = PatrolFlow.withoutPendingAction(acted)

        assertEquals("标记清干净", 1_000L, PatrolFlow.budgetBase(revoked))
        assertTrue(
            "撤销之后仍按进入步骤那一刻判超时（否则一次被拒就能白赚一份 20s 耐心）",
            PatrolFlow.timedOut(revoked, 1_000L + PatrolFlow.FARM_ENTER_TIMEOUT_MS),
        )
    }

    @Test
    fun aRebasedBudgetStartsFreshEvenIfWeActedBeforeIt() {
        // 2026-10-01 既有口径"画面回来 ⇒ 重新起算"必须**盖过**"上一次动手"那个更早的起点：
        // 画面停更期间那一击早已失去上下文（见 [PatrolFlow.rebaseStepBudget]），
        // 顺延后的起点更晚 ⇒ [budgetBase] 取更晚的那个 = 给足一整份预算。
        val rebased = PatrolFlow.rebaseStepBudget(
            PatrolFlow.State(
                range = Range.SWITCH_AND_VISIT,
                step = Step.ENTER_FARM,
                stepEnteredAtMs = 1_000L,
                lastActionAtMs = 2_000L,
            ),
            100_000L,
        )

        assertEquals(100_000L, PatrolFlow.budgetBase(rebased))
        assertFalse("刚恢复不该立刻超时", PatrolFlow.timedOut(rebased, 100_000L))
        assertTrue("但要给足一整份预算", PatrolFlow.timedOut(rebased, 100_000L + PatrolFlow.FARM_ENTER_TIMEOUT_MS))
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
    fun theBudgetRestartsWhenThePictureComesBack() {
        // 2026-10-01 真机缺陷：点「重新授权采集」把采集会话换掉，画面回来的**那一轮**，
        // 「停更期间累计的等待」被当成"这一步等超时"⇒ 立刻中止（文案写"等了 36 秒"，而预算只有 15 秒），
        // 而期望的画面在 1 秒后就出现了 ✗。
        // 口径（`PatrolRunner.RoundInput.framesStalled` 的注释一直这么写）：**画面一回来，
        // 从那时起重新给足这一步的预算** —— 这个函数就是那件事的入口（帧线程解除闸时调用）。
        val running = PatrolFlow.State(
            range = Range.VISIT_ONLY,
            step = Step.OPEN_FRIENDS,
            stepEnteredAtMs = 1_000L,
        )

        val rebased = PatrolFlow.rebaseStepBudget(running, 100_000L)
        assertEquals("预算从「画面回来」这一刻重新起算", 100_000L, rebased.stepEnteredAtMs)
        assertFalse("刚恢复 ⇒ 不该立刻判超时", PatrolFlow.timedOut(rebased, 100_000L))
        assertTrue(
            "但要给足一整份预算",
            PatrolFlow.timedOut(rebased, 100_000L + PatrolFlow.STEP_TIMEOUT_MS),
        )

        // 没在跑的（暂停 / 已中止 / 已完成）不动它 —— 那些状态的预算归「继续」管，别在这里替用户决定
        val paused = running.copy(paused = true)
        assertEquals(paused, PatrolFlow.rebaseStepBudget(paused, 100_000L))
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
