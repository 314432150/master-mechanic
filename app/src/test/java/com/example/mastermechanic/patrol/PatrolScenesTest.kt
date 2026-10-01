package com.example.mastermechanic.patrol

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolFlow.Step
import com.example.mastermechanic.patrol.PatrolScenes.Check
import com.example.mastermechanic.patrol.PatrolScenes.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4-2：识别结论 → 跑号场景 / 每步验证。 */
class PatrolScenesTest {

    /**
     * 起点画面的清单必须**恰好**是"读得出画面、且在起点表里有出路"的那些
     * （2026-09-20 真机死锁就是这条不成立：待命期不搜起点画面 → 识别恒「未知」→ 永远启动不了）。
     *
     * 它把两处不能漂移的事实钉在一起：`startStates`（待命期搜哪些标志）
     * 与 `PatrolFlow.firstStep`（起点表）。以后加了新状态却忘了同步，这条会红。
     */
    @Test
    fun startStatesAreExactlyTheScreensThatCanStartSomeRange() {
        val canStart = UiState.entries.filter { state ->
            val scene = (PatrolScenes.read(state) as? PatrolScenes.Reading.Ready)?.scene ?: return@filter false
            PatrolFlow.Range.entries.any { PatrolFlow.firstStep(scene, it) != null }
        }.toSet()

        assertEquals(canStart, PatrolScenes.startStates)
    }

    // ---------------------------------------------------------------- 场景读取

    @Test
    fun readMapsEveryStateToItsScene() {
        assertEquals(Reading.Ready(PatrolFlow.Scene.BOOT), PatrolScenes.read(UiState.LAUNCH_PAGE))
        assertEquals(Reading.Ready(PatrolFlow.Scene.SERVER_LIST), PatrolScenes.read(UiState.SERVER_SELECT))
        assertEquals(Reading.Ready(PatrolFlow.Scene.LOBBY), PatrolScenes.read(UiState.HALL))
        assertEquals(Reading.Ready(PatrolFlow.Scene.FARM), PatrolScenes.read(UiState.FARM))
        // 2026-09-21 合并：好友的农场读成同一个 Scene（2026-09-23 起也不再判"是谁的"）
        assertEquals(Reading.Ready(PatrolFlow.Scene.FARM), PatrolScenes.read(UiState.FRIEND_FARM))
    }

    @Test
    fun screensOutsideTheStartTableReadAsNotAStart() {
        // 好友列表 / 设置页 / 退出登录确认框 / 返回场景确认框：画面清楚，但起点表里没有它们
        for (state in listOf(
            UiState.FRIEND_LIST,
            UiState.HALL_SETTINGS,
            UiState.LOGOUT_CONFIRM,
            UiState.SCENE_RETURN_CONFIRM,
        )) {
            assertEquals("$state 应读成「不是起点」", Reading.Ready(PatrolFlow.Scene.NOT_A_START), PatrolScenes.read(state))
            assertNull(PatrolFlow.firstStep(PatrolFlow.Scene.NOT_A_START, PatrolFlow.Range.SWITCH_AND_VISIT))
            assertNull(PatrolFlow.firstStep(PatrolFlow.Scene.NOT_A_START, PatrolFlow.Range.VISIT_ONLY))
        }
    }

    @Test
    fun popupIsBlockedNotUnrecognized() {
        // 弹窗遮挡 = 现在判不了，等 FR-01 关掉就好；把它当成"认不出来"会白白中止一次跑号
        val reading = PatrolScenes.read(UiState.ACTIVITY_POPUP)
        assertTrue(reading is Reading.Blocked)
        assertTrue((reading as Reading.Blocked).reason.contains("FR-01"))
    }

    @Test
    fun tutorialScreensAreBlockedAndNameTheirHandler() {
        // 2026-09-29 恢复 FR-02：新手引导 / 新手大厅同样"等自动关闭点掉"，理由里必须点名 FR-02
        // （面板上就是照这句话告诉用户"不用管，程序会处理"）
        for (state in listOf(UiState.TUTORIAL_GUIDE, UiState.TUTORIAL_HALL)) {
            val reading = PatrolScenes.read(state)
            assertTrue("$state 应读成被遮挡", reading is Reading.Blocked)
            assertTrue(
                "$state 的理由要点名 FR-02，实际：${(reading as Reading.Blocked).reason}",
                reading.reason.contains("FR-02"),
            )
        }
    }

    @Test
    fun unknownIsUnrecognized() {
        assertEquals(Reading.Unrecognized, PatrolScenes.read(UiState.UNKNOWN))
    }

    @Test
    fun everyRecognizableStateReadsAsReady() {
        // §2.1 的每个界面都要能读成场景；「未知」与**三个遮挡屏**是仅有的例外
        for (state in UiState.entries) {
            val reading = PatrolScenes.read(state)
            when (state) {
                UiState.UNKNOWN -> assertEquals(Reading.Unrecognized, reading)
                in UiState.guardedOverlays -> assertTrue("$state 应读成被遮挡，实际 $reading", reading is Reading.Blocked)
                else -> assertTrue("$state 应能读成场景，实际 $reading", reading is Reading.Ready)
            }
        }
    }

    @Test
    fun guardedOverlaysAreExactlyTheThreeScreensTheCloseLoopHandles() {
        // 这份集合是"看到就自动点掉"的唯一口径（三处必须一致：恒定搜索 / 灰窗集 / 闭环触发）。
        // 顺序 = 声明顺序 = 出现顺序（2026-09-29 用户口径："新手两屏出现在活动弹窗之前"）
        assertEquals(
            listOf(UiState.TUTORIAL_GUIDE, UiState.TUTORIAL_HALL, UiState.ACTIVITY_POPUP),
            UiState.guardedOverlays,
        )
    }

    // ---------------------------------------------------------------- 标定页的展示顺序（用户口径 2026-09-19）

    @Test
    fun calibrationOrderCoversEveryStateExactlyOnce() {
        // 2026-09-21：两个农场合一 ⇒ 「好友的农场」不再是**可新建**的界面（它就是农场），
        // 从下拉里撤掉；产物里已经写着它的记录照旧能读（清单并入「农场」组）
        assertEquals(
            UiState.entries.filter { it.isCandidate && it != UiState.FRIEND_FARM }.toSet(),
            PatrolScenes.calibrationOrder.toSet(),
        )
        assertEquals(
            "不得重复",
            PatrolScenes.calibrationOrder.size,
            PatrolScenes.calibrationOrder.toSet().size,
        )
    }

    @Test
    fun calibrationOrderPutsTheExceptionScreensFirst() {
        // 三个"异常屏"不属于流程任何一站、又最容易被漏标 ⇒ 放在横滑行最可见端，
        // 且按**出现顺序**（2026-09-29 用户口径："标定时新手两屏也排到最前面"）
        assertEquals(
            listOf(UiState.TUTORIAL_GUIDE, UiState.TUTORIAL_HALL, UiState.ACTIVITY_POPUP),
            PatrolScenes.calibrationOrder.take(3),
        )
        // 第一个流程站必须紧随其后（标定页那条竖分隔线就画在它前面，见 CalibrationScreen）
        assertEquals(UiState.LAUNCH_PAGE, PatrolScenes.calibrationOrder[3])
    }

    @Test
    fun calibrationOrderFollowsTheFlow() {
        val order = PatrolScenes.calibrationOrder
        fun before(a: UiState, b: UiState) = order.indexOf(a) < order.indexOf(b)
        assertTrue(before(UiState.LAUNCH_PAGE, UiState.SERVER_SELECT))
        assertTrue(before(UiState.SERVER_SELECT, UiState.HALL))
        assertTrue(before(UiState.HALL, UiState.HALL_SETTINGS)) // 设置页从大厅进
        assertTrue(before(UiState.HALL_SETTINGS, UiState.LOGOUT_CONFIRM)) // 确认框从设置页弹
        assertTrue(before(UiState.HALL, UiState.FARM))
        assertTrue(before(UiState.FARM, UiState.SCENE_RETURN_CONFIRM)) // 返回确认框从农场弹
        assertTrue(before(UiState.SCENE_RETURN_CONFIRM, UiState.FRIEND_LIST))
        assertTrue(
            "好友的农场已并入农场，不再单列（否则会让人以为还有第二个农场要标）",
            UiState.FRIEND_FARM !in PatrolScenes.calibrationOrder,
        )
    }

    @Test
    fun calibrationOrderIsNotTheDeclarationOrder() {
        // 两件事必须分开：枚举声明顺序 = 多状态同时命中时的**优先级**（功能，不可动）；
        // 展示顺序 = 标定页按**流程顺序**排（好用）。合并它们就会悄悄改掉识别优先级。
        val declaration = UiState.entries.filter { it.isCandidate }
        assertNotEquals(declaration, PatrolScenes.calibrationOrder)
        // 具体差异：声明顺序里"设置页 / 确认框"必须在大厅**之前**（叠在大厅上的要赢），展示顺序里则相反
        assertTrue(declaration.indexOf(UiState.HALL_SETTINGS) < declaration.indexOf(UiState.HALL))
        assertTrue(PatrolScenes.calibrationOrder.indexOf(UiState.HALL_SETTINGS) > PatrolScenes.calibrationOrder.indexOf(UiState.HALL))
    }

    // ---------------------------------------------------------------- 每步验证目标

    @Test
    fun expectedStateFollowsTheRequirementTable() {
        assertEquals(UiState.HALL, PatrolScenes.expectedState(Step.LEAVE_FARM))
        assertEquals(UiState.LAUNCH_PAGE, PatrolScenes.expectedState(Step.LOGOUT))
        assertEquals(UiState.SERVER_SELECT, PatrolScenes.expectedState(Step.SWITCH_SERVER))
        // 第 5 步验证的是"**离开**服务器列表、回到启动页"
        assertEquals(UiState.LAUNCH_PAGE, PatrolScenes.expectedState(Step.PICK_SERVER))
        assertEquals(UiState.HALL, PatrolScenes.expectedState(Step.LOGIN))
        assertEquals(UiState.FARM, PatrolScenes.expectedState(Step.ENTER_FARM))
        assertEquals(UiState.FRIEND_LIST, PatrolScenes.expectedState(Step.OPEN_FRIENDS))
        // 合并后：第 9 步验证"到了农场"（FRIEND_FARM 由 PatrolScenes.isSameScene 等价通过）。
        // 2026-09-23 起不再叠"是不是**指定**好友的农场"那一层（农场归属判定整体删除）
        assertEquals(UiState.FARM, PatrolScenes.expectedState(Step.VISIT_FRIEND))
    }

    @Test
    fun stepsWithoutVerificationTargetAreTheTwoDocumentedOnes() {
        assertNull(PatrolScenes.expectedState(Step.CHECK_START)) // 起点确认不靠"看到什么"
        assertNull(PatrolScenes.expectedState(Step.DONE)) // 第 10 步：无动作，无需验证
    }

    @Test
    fun everyStepHasAnAnswer() {
        for (step in Step.entries) {
            PatrolScenes.expectedState(step) // 不抛异常即"每步都表了态"
        }
    }

    // ---------------------------------------------------------------- 验证判定

    @Test
    fun checkPassesWhenTheExpectedStateIsConfirmed() {
        for (step in Step.entries) {
            val expected = PatrolScenes.expectedState(step) ?: continue
            assertEquals("第 ${step.number} 步", Check.Passed, PatrolScenes.check(step, expected))
        }
    }

    @Test
    fun checkWaitsWhileStillSomewhereElse() {
        val waiting = PatrolScenes.check(Step.LOGIN, UiState.FARM)
        assertTrue(waiting is Check.Waiting)
        val detail = (waiting as Check.Waiting).detail
        assertTrue(detail.contains("大厅")) // 期望什么
        assertTrue(detail.contains("农场")) // 现在看到什么 —— 等待原因要能直接读出来
    }

    @Test
    fun loginStepPassesAsSoonAsAnOverlayProvesWeAreInGame() {
        // 2026-09-30 用户口径：新手引导 / 新手大厅 / 活动弹窗**只会出现在进入游戏之后**（引导最早）
        // ⇒ 它们出现 = **已经进游戏了** ⇒ 第 6 步（登录游戏）**提前判完成**。
        // ⚠ 它们是"证据"不是"关卡"（都不必然出现）⇒ 别的步骤照旧等自己的期望画面（见下一条断言）。
        assertTrue(PatrolScenes.check(Step.LOGIN, UiState.ACTIVITY_POPUP) is Check.Passed)
        assertTrue(PatrolScenes.check(Step.LOGIN, UiState.TUTORIAL_GUIDE) is Check.Passed)
        assertTrue(PatrolScenes.check(Step.LOGIN, UiState.TUTORIAL_HALL) is Check.Passed)
        // 这条**只给第 6 步**：对其它步骤，遮挡屏仍然是"等"（原判据保留）
        assertTrue(PatrolScenes.check(Step.OPEN_FRIENDS, UiState.ACTIVITY_POPUP) is Check.Waiting)
    }

    @Test
    fun checkWaitsWhenTheScreenCannotBeRecognized() {
        // 认不出来不是"这步失败了"：不补点、不跳步，等调用方的等待上限说话
        val waiting = PatrolScenes.check(Step.OPEN_FRIENDS, UiState.UNKNOWN)
        assertTrue(waiting is Check.Waiting)
        assertTrue((waiting as Check.Waiting).detail.contains("认不出来"))
    }

    @Test
    fun checkPassesForStepsWithoutATarget() {
        assertEquals(Check.Passed, PatrolScenes.check(Step.DONE, UiState.UNKNOWN))
        assertEquals(Check.Passed, PatrolScenes.check(Step.DONE, UiState.ACTIVITY_POPUP))
    }

    // ---------------------------------------------------------------- 动作所在状态

    @Test
    fun actionStatesAreTheStatesWhereTheButtonLives() {
        // 退出农场要两击：先点「返回」，再到返回场景确认框点「返回大厅」
        // 2026-09-21 合并：两个农场共用一个返回入口，所以只剩 FARM 一处
        assertEquals(
            listOf(UiState.FARM, UiState.SCENE_RETURN_CONFIRM),
            PatrolScenes.actionStates(Step.LEAVE_FARM),
        )
        // 退出登录要三次点击、各自的确认画面不同（§2.1 的 设置页 / 退出登录确认框）
        assertEquals(
            listOf(UiState.HALL, UiState.HALL_SETTINGS, UiState.LOGOUT_CONFIRM),
            PatrolScenes.actionStates(Step.LOGOUT),
        )
        assertEquals(listOf(UiState.LAUNCH_PAGE), PatrolScenes.actionStates(Step.SWITCH_SERVER))
        assertEquals(listOf(UiState.SERVER_SELECT), PatrolScenes.actionStates(Step.PICK_SERVER))
        assertEquals(listOf(UiState.LAUNCH_PAGE), PatrolScenes.actionStates(Step.LOGIN))
        assertEquals(listOf(UiState.HALL), PatrolScenes.actionStates(Step.ENTER_FARM))
        // 同上：打开好友列表也合并成一处
        assertEquals(listOf(UiState.FARM), PatrolScenes.actionStates(Step.OPEN_FRIENDS))
        assertEquals(listOf(UiState.FRIEND_LIST), PatrolScenes.actionStates(Step.VISIT_FRIEND))
    }

    @Test
    fun theLastActionOfAStepEndsWhereTheVerificationExpects() {
        // 两步数据不能各说各的：FR-04 的验证目标 = 点击链最后一击做完之后该看到的画面
        for (step in Step.entries) {
            val actions = PatrolAnchors.actions(step)
            if (actions.isEmpty()) continue
            assertEquals(
                "第 ${step.number} 步最后一步动作的 expect 与 expectedState 不一致",
                PatrolScenes.expectedState(step),
                actions.last().expect,
            )
        }
    }

    @Test
    fun aStepWithASingleActionIsVerifiedByThatActionsExpectation() {
        // 只有一击的步骤（多数步骤）：动作的 expect 就是验证目标本身
        for (step in Step.entries) {
            val actions = PatrolAnchors.actions(step)
            if (actions.size != 1) continue
            assertEquals(PatrolScenes.expectedState(step), actions.single().expect)
        }
    }

    @Test
    fun multiClickStepStaysWaitingUntilTheLastExpectation() {
        // 第 3 步的三次点击：走到设置页 / 确认框都还**不算通过**（否则等于盲点）
        assertTrue(PatrolScenes.check(Step.LOGOUT, UiState.HALL_SETTINGS) is Check.Waiting)
        assertTrue(PatrolScenes.check(Step.LOGOUT, UiState.LOGOUT_CONFIRM) is Check.Waiting)
        assertEquals(Check.Passed, PatrolScenes.check(Step.LOGOUT, UiState.LAUNCH_PAGE))
    }

    @Test
    fun waitingDetailOfAMultiClickStepSaysWhatToClickNext() {
        // 日志里要能直接读出"这一步还没做完 + 现在该点哪"，否则真机排障只能猜
        val waiting = PatrolScenes.check(Step.LOGOUT, UiState.HALL_SETTINGS)
        val detail = (waiting as Check.Waiting).detail
        assertTrue(detail.contains(PatrolAnchors.SETTINGS_LOGOUT))
        assertTrue(detail.contains("退出登录确认框"))
    }

    @Test
    fun noActionIsAttachedToTheCheckStartAndDoneSteps() {
        assertTrue(PatrolScenes.actionStates(Step.CHECK_START).isEmpty())
        assertTrue(PatrolScenes.actionStates(Step.DONE).isEmpty())
    }

    @Test
    fun actionStatesNeverOverlapTheExpectedState() {
        // 一旦重叠，"确认状态 == 预期状态"就意味着点完立刻自证通过 —— 验证会退化成空转
        for (step in Step.entries) {
            val expected = PatrolScenes.expectedState(step) ?: continue
            assertFalse(
                "第 ${step.number} 步的动作状态不该包含它自己的验证目标（$expected）",
                expected in PatrolScenes.actionStates(step),
            )
        }
    }

    @Test
    fun unknownIsNeverAnActionState() {
        // 状态未知时零点击（红线 1）：谁都不许把「未知」当成"可以动手"的状态
        for (step in Step.entries) {
            assertFalse(UiState.UNKNOWN in PatrolScenes.actionStates(step))
        }
    }

    // ---------------------------------------------------------------- 每轮实际要搜哪些画面（2026-10-01 收窄）

    @Test
    fun watchStatesFollowTheChainPosition() {
        // 第 3 步的链条：大厅 →（点设置入口）→ 设置页 →（点退出登录）→ 确认框 →（点确定）→ 启动页
        // 站在大厅时只要搜「大厅 + 设置页」；站在设置页时只要搜「设置页 + 确认框」……
        assertEquals(
            setOf(UiState.HALL, UiState.HALL_SETTINGS),
            PatrolScenes.watchStatesFor(Step.LOGOUT, UiState.HALL),
        )
        assertEquals(
            setOf(UiState.HALL_SETTINGS, UiState.LOGOUT_CONFIRM),
            PatrolScenes.watchStatesFor(Step.LOGOUT, UiState.HALL_SETTINGS),
        )
        assertEquals(
            setOf(UiState.LOGOUT_CONFIRM, UiState.LAUNCH_PAGE),
            PatrolScenes.watchStatesFor(Step.LOGOUT, UiState.LOGOUT_CONFIRM),
        )
        // 到了验证目标（启动页）⇒ 该屏上这一步没有要动手的地方 ⇒ 只搜它自己
        assertEquals(
            setOf(UiState.LAUNCH_PAGE),
            PatrolScenes.watchStatesFor(Step.LOGOUT, UiState.LAUNCH_PAGE),
        )
    }

    @Test
    fun unknownFallsBackToTheWholeStepChain() {
        // 认不出画面 ⇒ 必须放宽回整步集合（否则"真实画面不在收窄后的集合里"会一直认不出、白等到超时）。
        // 这是收窄的**自愈阀**：窄 → 认不出 → 宽 → 认出 → 再窄，代价只有一轮。
        for (step in Step.entries) {
            val chain = PatrolScenes.actionStates(step) + listOfNotNull(PatrolScenes.expectedState(step))
            assertEquals(chain.toSet(), PatrolScenes.watchStatesFor(step, UiState.UNKNOWN))
        }
    }

    @Test
    fun stepsWithoutAChainKeepTheGuardianWording() {
        // 第 1 步（确认起点）/ 第 10 步（完成）没有链条 ⇒ **空集**（= 沿用"守护集合"口径）。
        // 这里不能收成 `{当前屏}`：那会把"能当起点的五屏"收成一个，用户在别处点菜单就认不出起点。
        for (step in Step.entries) {
            val chain = PatrolScenes.actionStates(step) + listOfNotNull(PatrolScenes.expectedState(step))
            if (chain.isEmpty()) {
                for (state in UiState.entries) {
                    assertTrue(
                        "第 ${step.number} 步没有链条 ⇒ 必须是空集（沿用守护口径）",
                        PatrolScenes.watchStatesFor(step, state).isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun narrowedWatchNeverAddsAnythingBeyondCurrentAndNextScreen() {
        // 不变量：收窄后搜的画面 ⊆ {当前屏} ∪ {当前屏的下一屏} ∪ 本步链条 —— 且**永不包含「未知」**。
        // （"⊆ 链条"不成立且不该成立：用户可能自己走到了链外的界面，那时仍要看得见它。）
        for (step in Step.entries) {
            val chain = (PatrolScenes.actionStates(step) + listOfNotNull(PatrolScenes.expectedState(step))).toSet()
            if (chain.isEmpty()) continue
            for (state in UiState.entries) {
                val watching = PatrolScenes.watchStatesFor(step, state)
                val allowed = chain + state + listOfNotNull(PatrolAnchors.actionFor(step, state)?.expect)
                assertTrue(
                    "第 ${step.number} 步 / $state 收窄后搜到了不该搜的画面：$watching ⊄ $allowed",
                    allowed.containsAll(watching),
                )
                assertFalse(UiState.UNKNOWN in watching)
                assertFalse("收窄不该返回空集（那只留给第 1/10 步）", watching.isEmpty())
            }
        }
    }
}
