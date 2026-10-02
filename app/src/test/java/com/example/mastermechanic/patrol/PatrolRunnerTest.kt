package com.example.mastermechanic.patrol

import com.example.mastermechanic.action.ClickSource
import com.example.mastermechanic.decision.AnchorHit
import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跑号每轮编排（M4-T4-4a，纯逻辑）。
 *
 * 钉住的是"**一帧进来该干什么**"：等 / 点 / 进下一步 / 停下并说明原因。
 * 之所以值得单测：编排判错的表现是**乱点**（红线 5），而乱点在真机上很难复现与定位。
 */
class PatrolRunnerTest {

    private fun hit(name: String) = AnchorHit(
        name = name,
        score = 0.92,
        frameX = 640.0,
        frameY = 360.0,
        calibrationX = 640.0,
        calibrationY = 360.0,
    )

    private fun input(
        state: UiState,
        anchors: List<AnchorHit> = emptyList(),
        nowMs: Long = 10_000L,
        lastClickAtMs: Long = 0L,
        foreground: Boolean = true,
        framesStalled: Boolean = false,
        popupHit: Boolean = false,
        popupConfirmed: Boolean = false,
        targetFriend: String? = null,
        targetServer: String? = null,
        namePlan: PatrolRunner.NamePlan = PatrolRunner.NamePlan.Waiting,
    ) = PatrolRunner.RoundInput(
        foreground = foreground,
        framesStalled = framesStalled,
        state = state,
        popupHit = popupHit,
        popupConfirmed = popupConfirmed,
        anchors = anchors,
        nowMs = nowMs,
        lastClickAtMs = lastClickAtMs,
        targetFriend = targetFriend,
        targetServer = targetServer,
        namePlan = namePlan,
        decisionId = "patrol-1",
    )

    /** 从「启动页」开始的换号 + 拜访：第 1 步判定的起点是第 4 步（换区）。 */
    private fun startSwitch(): PatrolFlow.State =
        PatrolFlow.start(PatrolFlow.Scene.BOOT, PatrolFlow.Range.SWITCH_AND_VISIT)!!

    @Test
    fun withoutARunningFlowNothingHappens() {
        val result = PatrolRunner.onRound(null, input(UiState.LAUNCH_PAGE))
        assertNull(result.state)
        assertNull(result.request)
        assertNull(result.note)
    }

    @Test
    fun theStepActionIsClickedWhenTheScreenIsReady() {
        // 第 4 步（换区）在启动页：动作存在、锚点定位到了 → 点它，并说清楚"之后应看到什么"
        val result = PatrolRunner.onRound(
            startSwitch(),
            input(UiState.LAUNCH_PAGE, anchors = listOf(hit(PatrolAnchors.LAUNCH_SWITCH))),
        )

        val request = requireNotNull(result.request)
        assertEquals(ClickSource.PATROL_STEP, request.source)
        assertEquals(PatrolAnchors.LAUNCH_SWITCH, request.anchorName)
        assertEquals(640.0, request.frameX, 0.0)
        assertTrue("应说明这一击的验证目标：${result.note}", result.note.orEmpty().contains("服务器列表"))
        // 记下这一击的时刻，后面"等画面切换"才有时长可算
        assertEquals(10_000L, result.state?.lastActionAtMs)
    }

    @Test
    fun theTargetScreenAdvancesTheStep() {
        // 换成服务器列表 = 第 4 步验证通过 → 进第 5 步（选择区服）
        val result = PatrolRunner.onRound(startSwitch(), input(UiState.SERVER_SELECT))

        assertNull(result.request)
        assertEquals(PatrolFlow.Step.PICK_SERVER, result.state?.step)
        assertTrue(result.note.orEmpty().contains("第 4 步通过"))
    }

    @Test
    fun clicksAreSpacedAtLeastTheRequiredGap() {
        // 上一次点击才过去 100ms → 这一轮不点（真正下发前的 ClickGate 还有一层，这里先自己不提）
        val result = PatrolRunner.onRound(
            startSwitch(),
            input(
                UiState.LAUNCH_PAGE,
                anchors = listOf(hit(PatrolAnchors.LAUNCH_SWITCH)),
                nowMs = 10_100L,
                lastClickAtMs = 10_000L,
            ),
        )

        assertNull(result.request)
        assertTrue(result.note.orEmpty().contains("点击间隔"))
    }

    @Test
    fun aPopupOnScreenPausesTheStep() {
        // 弹窗盖着时点了也是白点 → 等 FR-01 关掉（哪怕只是本轮原始命中）
        val result = PatrolRunner.onRound(
            startSwitch(),
            input(UiState.LAUNCH_PAGE, anchors = listOf(hit(PatrolAnchors.LAUNCH_SWITCH)), popupHit = true),
        )

        assertNull(result.request)
        assertTrue(result.note.orEmpty().contains("等弹窗关掉"))
        assertEquals(PatrolFlow.Step.SWITCH_SERVER, result.state?.step)
    }

    @Test
    fun theFarmEntryWaitsOutTheLoginOverlayWindow() {
        // 真机两次事故（2026-10-01，同一落点 `hall_farm` 帧点 945,1083）：
        // 01:42 那一枪被活动弹层**吞掉**（白等 15 秒中止）；03:26 那一枪把游戏**点进了活动页**
        // （没标定 ⇒ 认不出来 ⇒ 只能如实中止，整轮废掉）。⇒ 第 7 步刚进大厅时先让路 2.5 秒。
        val started = requireNotNull(
            PatrolFlow.start(PatrolFlow.Scene.LOBBY, PatrolFlow.Range.VISIT_ONLY, 1_000L),
        )
        assertEquals(PatrolFlow.Step.ENTER_FARM, started.step)

        // ① 还在让路窗口里（进入第 7 步后 0.5 秒）：锚点明明在，也不许点
        val waiting = PatrolRunner.onRound(
            started,
            input(UiState.HALL, anchors = listOf(hit(PatrolAnchors.HALL_FARM)), nowMs = 1_500L),
        )
        assertNull(waiting.request)
        assertTrue(waiting.note.orEmpty().contains("先让它稳"))

        // ② 窗口过了（+2.6 秒）：照常点，且不是失败、不烧重试
        val clicked = PatrolRunner.onRound(
            started,
            input(UiState.HALL, anchors = listOf(hit(PatrolAnchors.HALL_FARM)), nowMs = 1_000L + 2_600L),
        )
        assertEquals(PatrolAnchors.HALL_FARM, clicked.request?.anchorName)
        assertEquals(0, clicked.state?.retries)
    }

    @Test
    fun aClickSwallowedByAPopupIsVoided() {
        // 真机（2026-10-01 01:42）：第 7 步点「农场入口」后约 1 秒弹出活动弹窗，弹窗关掉后画面**回到大厅**
        // ⇒ 那一枪其实被弹窗吞了。⑤ 的口径是"本步下过击 ⇒ 只等期望画面、不补点" ⇒ 只能干等 15 秒后中止
        // （用户看到的就是"卡在进入农场、最后停在大厅"）。
        val afterClick = PatrolFlow.acted(startSwitch(), 5_000L, UiState.LAUNCH_PAGE)
        assertTrue("前提：这一步已经点过一下", afterClick.lastActionAtMs > 0)

        val result = PatrolRunner.onRound(
            afterClick,
            input(UiState.LAUNCH_PAGE, popupConfirmed = true, nowMs = 6_000L),
        )

        assertNull(result.request)
        assertTrue(result.note.orEmpty().contains("等弹窗关掉"))
        assertEquals("那一枪作废 ⇒ 弹窗关掉后会重新定位、重新提议", 0L, result.state?.lastActionAtMs)
        assertNull("点击那一刻的画面也一起清掉（否则观察窗口会拿它比）", result.state?.stateAtLastAction)
    }

    @Test
    fun aMissingAnchorBeforeClickingWaitsAndIsNeverChargedAsARetry() {
        // 本步**还没点过**、锚点却定位不到（未标定 / 这一屏上本来就没有它）⇒ **等，不烧重试次数**；
        // 到本步预算（STEP_TIMEOUT_MS）才如实中止并说明等了多久（2026-09-24 口径：没等到 ≠ 失败）
        val waiting = PatrolRunner.onRound(
            startSwitch(),
            input(UiState.LAUNCH_PAGE, nowMs = 10_000L),
        )
        assertNull(waiting.request)
        assertEquals("没等到不算重试次数", 0, waiting.state?.retries)
        assertNull("没到预算不许给原因 / 不许中止", waiting.state?.reason)
        assertTrue(waiting.note.orEmpty().contains("没定位到"))
        assertTrue(waiting.note.orEmpty().contains("继续等"))

        val timedOut = PatrolRunner.onRound(
            startSwitch().copy(stepEnteredAtMs = 1_000L),
            input(UiState.LAUNCH_PAGE, nowMs = 1_000L + PatrolFlow.STEP_TIMEOUT_MS),
        )
        assertEquals(PatrolFlow.Outcome.FAILED, timedOut.state?.outcome)
        assertTrue(timedOut.state?.reason.orEmpty().contains("秒"))
    }

    @Test
    fun afterClickingItOnlyWaitsForTheExpectedScreenAndNeverRelocates() {
        // **口径 A（2026-09-24）**：本步已经下过击 ⇒ 接下来**只判定期望画面** ——
        // 不许再去定位那个锚点、更不许补点。真机依据见 PatrolRunner.waitOrAbort 的说明
        // （点「确认退出」后游戏重启 4.9 秒，旧逻辑靠"再定位 + 再点"在 3 秒内烧完 3 次重试
        //   ⇒ 在「启动页」出现前 1.4 秒中止）。
        val withinWindow = PatrolRunner.onRound(
            PatrolFlow.acted(startSwitch(), 9_500L),
            input(UiState.LAUNCH_PAGE, nowMs = 10_000L),
        )
        assertNull(withinWindow.request)
        assertTrue(withinWindow.note.orEmpty().contains("刚点过"))

        // 窗口过了（>2s）画面还没换 ⇒ **仍然只是等**，而且**根本不去看锚点**：
        // note 里是"刚点过"而不是"没定位到" —— 这就是与旧行为的区别（旧行为会重新定位并补点）
        val pastWindow = PatrolRunner.onRound(
            PatrolFlow.acted(startSwitch(), 5_000L),
            input(UiState.LAUNCH_PAGE, nowMs = 10_000L),
        )
        assertNull(pastWindow.request)
        assertEquals(0, pastWindow.state?.retries)
        assertNull(pastWindow.state?.reason)
        assertTrue(pastWindow.note.orEmpty().contains("刚点过"))
        assertFalse(
            "点了之后就只等期望画面，不再重新定位锚点（口径 A）",
            pastWindow.note.orEmpty().contains("没定位到"),
        )

        // 到预算 → 如实中止，并说明等了多久
        // ⚠ 2026-10-03 乙方案：耐心从**动手那一刻**（5_000）起算，不是从进入步骤（1_000）⇒
        // 到点时刻要按 5_000 + 预算 算，否则这一轮会**不够预算**（真机 2026-10-03 就这样白等过）。
        val timedOut = PatrolRunner.onRound(
            PatrolFlow.acted(startSwitch().copy(stepEnteredAtMs = 1_000L), 5_000L),
            input(UiState.LAUNCH_PAGE, nowMs = 5_000L + PatrolFlow.STEP_TIMEOUT_MS),
        )
        assertEquals(PatrolFlow.Outcome.FAILED, timedOut.state?.outcome)
        assertTrue(timedOut.state?.reason.orEmpty().contains("秒"))
    }

    @Test
    fun justClickedWaitsInsteadOfClickingAgain() {
        // 2026-09-20 真机缺陷回归：大厅→农场那一击之后，画面还在过渡、锚点还挂在原位，
        // 300ms 的点击间隔一到就又点了一次（560ms 内两枪）→ 观察窗口内必须**一律等**，不许连点
        val state = PatrolFlow.acted(startSwitch(), 9_500L)

        val result = PatrolRunner.onRound(
            state,
            input(UiState.LAUNCH_PAGE, anchors = listOf(hit(PatrolAnchors.LAUNCH_SWITCH)), nowMs = 10_000L),
        )

        assertNull("刚点过就不许再点（连点是 NFR-05 的风险）", result.request)
        assertTrue(result.note.orEmpty().contains("刚点过"))
        assertEquals("等待不消耗重试次数", 0, result.state?.retries)
    }

    /**
     * 第 3 步（退出登录）是**三击链**：大厅 → 设置页 → 确认框 → 启动页，
     * 所以"点完第一击、画面换成设置页"时，这一步**还有动作要做**（点「退出登录」）——
     * 这正是测"观察窗口提前结束"最合适的例子（真机 00:23 / 00:38 两轮演示都是这条链）。
     *
     * 注意：换到的画面**不能**是该击的验证目标，否则会走"验证通过 → 进下一步"那条路（不产生点击）。
     * 第 3 步整体的验证目标是最后一击之后的「启动页」，所以中途两屏都安全。
     */
    private fun atLoggingOut(clickedState: UiState, lastActionAtMs: Long = 9_500L): PatrolFlow.State =
        PatrolFlow.State(
            range = PatrolFlow.Range.SWITCH_AND_VISIT,
            step = PatrolFlow.Step.LOGOUT,
            lastActionAtMs = lastActionAtMs,
            stateAtLastAction = clickedState,
        )

    @Test
    fun aConfirmedScreenChangeEndsTheObservationWindowEarly() {
        // 2026-09-21 真机提速：画面已确实换到「好友农场」，而这一步在那里也能动手 → 不必干等满 2s。
        // （真机原来每步白等 ≈1.1~1.4s，用户反馈"整个流程还是有点慢"的直接来源。）
        val result = PatrolRunner.onRound(
            atLoggingOut(clickedState = UiState.FARM),
            input(
                UiState.HALL_SETTINGS, // 点完「设置入口」→ 画面换成设置页，这一步还有第二击
                anchors = listOf(hit(PatrolAnchors.SETTINGS_LOGOUT)),
                nowMs = 10_000L, // 距上一击仅 0.5s，远在 2s 窗口内
                lastClickAtMs = 9_500L,
            ),
        )

        val request = requireNotNull(result.request) // 画面确实换了另一屏 → 允许提前结束观察窗口
        assertEquals(PatrolAnchors.SETTINGS_LOGOUT, request.anchorName)
        assertTrue("真机日志要能看出窗口被提前结束：${result.note}", result.note.orEmpty().contains("提前结束"))
        assertEquals("点击后重新记为这一刻的画面", UiState.HALL_SETTINGS, result.state?.stateAtLastAction)
    }

    @Test
    fun theObservationWindowStaysFullWhileTheScreenHasNotChanged() {
        // 画面还是点击时那一屏（可能点歪了、也可能还没生效）→ 照旧等满窗口（防连点）
        val result = PatrolRunner.onRound(
            atLoggingOut(clickedState = UiState.HALL), // 点击那一刻就在大厅
            input(
                UiState.HALL, // 画面还是大厅（可能点歪了、也可能还没生效）
                anchors = listOf(hit(PatrolAnchors.HALL_SETTINGS)),
                nowMs = 10_000L,
                lastClickAtMs = 9_500L,
            ),
        )

        assertNull(result.request)
        assertTrue(result.note.orEmpty().contains("刚点过"))
    }

    @Test
    fun aScreenChangeToSomewhereThisStepCannotActStillWaits() {
        // 换到的是"这一刻不能动手"的画面（如农场）→ **不提前放行**：
        // 若只要求"画面变了"，点歪时也会立刻放行，失败会被提前记进重试；带上"有事可做"才只快在成功那条路上。
        // （不能用「启动页」举例：它是第 3 步的验证目标，会直接判"这一步通过"而不是继续点）
        val result = PatrolRunner.onRound(
            atLoggingOut(clickedState = UiState.HALL),
            input(
                UiState.FARM,
                anchors = listOf(hit(PatrolAnchors.FARM_EXIT)),
                nowMs = 10_000L,
                lastClickAtMs = 9_500L,
            ),
        )

        assertNull(result.request)
        assertTrue(result.note.orEmpty().contains("刚点过"))
    }

    @Test
    fun unknownScreenStillWaitsForTheLoadingToFinish() {
        // 加载中什么也判不出来 → 交给「画面还没看清」的耐心逻辑（等，不计失败）
        val result = PatrolRunner.onRound(
            atLoggingOut(clickedState = UiState.HALL),
            input(UiState.UNKNOWN, nowMs = 10_000L, lastClickAtMs = 9_500L),
        )

        assertNull(result.request)
        assertTrue(result.note.orEmpty().contains("刚点过"))
    }

    @Test
    fun withoutARecordedClickScreenTheWindowStaysFixed() {
        // 没记下"点击那一刻的画面"（纯逻辑调用方 / 离线重跑）→ 退化为老行为：窗口内一律等
        val anonymous = atLoggingOut(clickedState = UiState.HALL).copy(stateAtLastAction = null)

        val result = PatrolRunner.onRound(
            anonymous,
            input(
                UiState.HALL_SETTINGS,
                anchors = listOf(hit(PatrolAnchors.SETTINGS_LOGOUT)),
                nowMs = 10_000L,
                lastClickAtMs = 9_500L,
            ),
        )

        assertNull(result.request)
        assertTrue(result.note.orEmpty().contains("刚点过"))
    }

    @Test
    fun unknownScreenDoesNotBurnRetries() {
        // 画面没看清（多半在加载）= "还没轮到判定成败"：只等，不消耗重试（2026-09-20 修）
        val state = PatrolFlow.State(
            range = PatrolFlow.Range.VISIT_ONLY,
            step = PatrolFlow.Step.ENTER_FARM,
            stepEnteredAtMs = 1_000L,
        )

        val result = PatrolRunner.onRound(state, input(UiState.UNKNOWN, nowMs = 10_000L))

        assertNull(result.request)
        assertEquals(0, result.state?.retries)
        assertTrue("要说清是在等而不是卡住：${result.note}", result.note.orEmpty().contains("还没看清"))
    }

    @Test
    fun aTimeoutSaysTheFactsAndDoesNotGuessTheCause() {
        // 2026-09-30 用户口径（真机验收问题 A）："**不需要过于明确的指引 —— 核心都是长时间识别不到
        // 待识别元素，用户自己能看到画面的变化**" ⇒ 中止原因只说三件事实：
        // 等了多久 / 这一步在等哪一屏 / 现在看到的是什么；**不猜成因**（不写"多半是风控下线 / 请手动登录"）。
        val state = PatrolFlow.State(
            range = PatrolFlow.Range.SWITCH_AND_VISIT,
            step = PatrolFlow.Step.LOGOUT,
            stepEnteredAtMs = 1_000L,
        )

        val result = PatrolRunner.onRound(
            state,
            input(UiState.UNKNOWN, nowMs = 1_000L + PatrolFlow.LOGOUT_TIMEOUT_MS),
        )

        assertEquals(PatrolFlow.Outcome.FAILED, result.state?.outcome)
        val reason = result.state?.reason.orEmpty()
        assertTrue("要说清等了多久：$reason", reason.contains("60 秒"))
        assertTrue(
            "要说清在等哪一屏，且取**真实验证目标**（第 3 步是「启动页」，不是动作名「退出登录」）：$reason",
            reason.contains("期望「启动页」") && !reason.contains("期望「退出登录」"),
        )
        assertTrue("要说清现在看到的是什么：$reason", reason.contains("认不出来"))
        assertFalse(
            "不猜成因、不写具体处置（成因很多：加载 / 风控 / 走错屏 / 采集停更，猜错就把人带偏）：$reason",
            reason.contains("风控") || reason.contains("授权") || reason.contains("手动"),
        )
        // 日志那一行与菜单里那一行同源（同一句，不各自演化）
        assertTrue("日志要能一眼看出是这一步没识别出来：${result.note}", result.note.orEmpty().contains("仍未识别出"))
    }

    @Test
    fun stalledFramesFreezeTheStepClockInsteadOfAborting() {
        // 2026-09-30 用户口径："防止误判导致影响流程" ⇒ 收不到新帧（门禁会拒掉所有点击）期间，
        // **这一步的计时暂停**：什么都不判、什么都不点（连"验证通过就推进"也不做 —— 那是旧画面上的结论）。
        val expired = startSwitch().copy(stepEnteredAtMs = 1_000L)
        val nowMs = 1_000L + PatrolFlow.STEP_TIMEOUT_MS + 5_000L // 预算早就用光了

        val frozen = PatrolRunner.onRound(
            expired,
            input(
                UiState.LAUNCH_PAGE,
                anchors = listOf(hit(PatrolAnchors.LAUNCH_SWITCH)),
                nowMs = nowMs,
                framesStalled = true,
            ),
        )

        assertNull("看不见画面时绝不点", frozen.request)
        assertEquals("仍然在跑（不许因为收不到帧就中止）", PatrolFlow.Outcome.RUNNING, frozen.state?.outcome)
        assertEquals("这一步的计时顺延到此刻 ⇒ 闹钟暂停", nowMs, frozen.state?.stepEnteredAtMs)
        assertTrue("要说清为什么不动：${frozen.note}", frozen.note.orEmpty().contains("画面久未更新"))

        // 画面回来（framesStalled = false）⇒ 从顺延后的时刻重新算：又是完整预算
        val resumed = PatrolRunner.onRound(
            requireNotNull(frozen.state),
            input(
                UiState.LAUNCH_PAGE,
                anchors = listOf(hit(PatrolAnchors.LAUNCH_SWITCH)),
                nowMs = nowMs + 1_000L,
            ),
        )
        assertNull("还没到新的预算 ⇒ 不许中止", resumed.state?.reason)
        assertEquals(PatrolFlow.Outcome.RUNNING, resumed.state?.outcome)
    }

    @Test
    fun aDeniedClickDoesNotMakeTheStepWaitForTheScreenToChange() {
        // 2026-09-30（progress 第 325 条 b）：一击**被门禁拒掉**之后，采集层用
        // [PatrolFlow.withoutPendingAction] 把"已点过"标记撤回 ⇒ 下一轮应当**重新提议**，
        // 而不是走进"刚点过 ⇒ 只等期望画面"白等一整个步骤预算
        //（真机实录：落点被误判"屏外"静默拒绝 ⇒ 白等 16 秒后中止）。
        val clicked = PatrolFlow.acted(startSwitch(), 9_500L, UiState.LAUNCH_PAGE)
        val arguments = input(
            UiState.LAUNCH_PAGE,
            anchors = listOf(hit(PatrolAnchors.LAUNCH_SWITCH)),
            nowMs = 10_000L,
            lastClickAtMs = 9_500L,
        )

        // 对照：带着"已点过"标记 ⇒ 只等，不许再点（口径 A，2026-09-24）
        val waiting = PatrolRunner.onRound(clicked, arguments)
        assertNull("已点过 ⇒ 不补点", waiting.request)
        assertTrue("并且要说清是在等画面切换：${waiting.note}", waiting.note.orEmpty().contains("刚点过"))

        // 撤回标记之后 ⇒ 这一轮又能提议点击（间隔也够）
        val again = PatrolRunner.onRound(PatrolFlow.withoutPendingAction(clicked), arguments)
        assertTrue("被拒之后必须能重新提议（否则白等一个步骤预算）", again.request != null)
    }

    @Test
    fun waitingTooLongAbortsAndSaysHowLongItWaited() {
        // 时间预算兜底：等超了就中止并说明等了多久（不静默卡住）
        // ⚠ 2026-10-03：第 7 步（进入农场）**有自己的 20s 预算**（实测加载 8.7s），这里跟着改用
        // [PatrolFlow.FARM_ENTER_TIMEOUT_MS]；用旧的 15s 会与新常量打架（15s < 20s ⇒ 不会超时）。
        val state = PatrolFlow.State(
            range = PatrolFlow.Range.VISIT_ONLY,
            step = PatrolFlow.Step.ENTER_FARM,
            stepEnteredAtMs = 1_000L,
        )
        val nowMs = 1_000L + PatrolFlow.FARM_ENTER_TIMEOUT_MS

        val result = PatrolRunner.onRound(state, input(UiState.UNKNOWN, nowMs = nowMs))

        assertEquals(PatrolFlow.Outcome.FAILED, result.state?.outcome)
        assertTrue(
            "原因要能被用户读懂：${result.state?.reason}",
            result.state?.reason.orEmpty().contains("秒"),
        )
    }

    @Test
    fun theAbortReasonSaysWhichPatienceClockWasUsed() {
        // 乙方案（2026-10-03）：耐心有两种起算点，中止原因**必须读得出是哪一种** ——
        // 否则真机上看到"等了 15 秒"分不清是"等了 15 秒还没动手"还是"点下去 15 秒画面还没来"。
        val notYetActed = PatrolRunner.onRound(
            PatrolFlow.State(
                range = PatrolFlow.Range.SWITCH_AND_VISIT,
                step = PatrolFlow.Step.OPEN_FRIENDS,
                stepEnteredAtMs = 1_000L,
            ),
            // 第 8 步（打开好友列表）站在「农场」上、锚点没给 ⇒ 必定走 ⑨「没定位到 → 等 / 到预算中止」那条路。
            // ⚠ 刻意**不用第 5 步**（按名称定位那两步在 `NamePlan.Waiting` 时**只说一句就返回**、不查预算，
            //   见 PatrolRunner ⑦）—— 那样断言不到中止原因。
            input(UiState.FARM, nowMs = 1_000L + PatrolFlow.STEP_TIMEOUT_MS),
        ).state!!
        assertTrue(
            "还没动手 ⇒ 老口径那句：${notYetActed.reason}",
            notYetActed.reason.orEmpty().contains("等了 ${PatrolFlow.STEP_TIMEOUT_MS / 1000} 秒"),
        )
        assertFalse(
            "没动手时不该说成『点下去之后』",
            notYetActed.reason.orEmpty().contains("点下去之后"),
        )

        val acted = PatrolFlow.acted(
            PatrolFlow.State(
                range = PatrolFlow.Range.SWITCH_AND_VISIT,
                step = PatrolFlow.Step.ENTER_FARM,
                stepEnteredAtMs = 1_000L,
            ),
            5_000L,
        )
        val afterAction = PatrolRunner.onRound(
            acted,
            input(UiState.UNKNOWN, nowMs = 5_000L + PatrolFlow.FARM_ENTER_TIMEOUT_MS),
        ).state!!
        assertTrue(
            "动过手 ⇒ 要说清是从点击那刻起算的：${afterAction.reason}",
            afterAction.reason.orEmpty().contains("点下去之后等了 ${PatrolFlow.FARM_ENTER_TIMEOUT_MS / 1000} 秒"),
        )
        // 复刻当天那次：进入步骤 7.5 秒才动手、游戏再加载 8.7 秒 ⇒ 旧口径 16.3s 中止，新口径 20s 内通过
        val stillWaiting = PatrolRunner.onRound(
            acted,
            input(UiState.UNKNOWN, nowMs = 5_000L + 8_700L),
        ).state!!
        assertEquals(
            "复刻 2026-10-03 那次：点下去 8.7 秒时**不该**再判超时（真机就是在命中前 1.24 秒中止的）",
            PatrolFlow.Outcome.RUNNING,
            stillWaiting.outcome,
        )
    }

    @Test
    fun aWrongScreenWaitsUntilTheStepBudgetThenStops() {
        // **2026-09-24 口径修正**：\"没等到画面\"**不再烧重试次数** —— 它与"再等一轮"是同一件事
        // （每一轮都会重新判定），所以次数上限在这里没有额外价值，只保留耐心；
        // 到本步时间预算才中止并说明等了多久（FR-05：不静默重试整条流程）。
        // 真机依据：点「确认退出」后游戏重启 4.9 秒，旧逻辑 3 次重试在 3 秒内烧完
        // ⇒ 在「启动页」出现前 1.4 秒中止（见 PatrolRunner.waitOrAbort）。
        val state = startSwitch().copy(stepEnteredAtMs = 1_000L)
        var current = state
        repeat(PatrolFlow.MAX_RETRIES + 2) {
            current = PatrolRunner.onRound(
                PatrolFlow.acted(current, 0L),
                input(UiState.FARM, nowMs = 10_000L),
            ).state!!
        }

        assertNull("等多久与次数无关：没到预算就不许中止（reason 仍为空）", current.reason)
        assertEquals("没等到不算重试次数", 0, current.retries)

        // 到预算 → 中止，原因里带上等了多久
        val timedOut = PatrolRunner.onRound(
            PatrolFlow.acted(current, 0L),
            input(UiState.FARM, nowMs = 1_000L + PatrolFlow.STEP_TIMEOUT_MS),
        ).state!!
        assertEquals(PatrolFlow.Outcome.FAILED, timedOut.outcome)
        assertTrue(timedOut.reason.orEmpty().contains("秒"))

        // 中止之后即使画面变对了也不再自己动
        val afterFail = PatrolRunner.onRound(timedOut, input(UiState.SERVER_SELECT))
        assertNull(afterFail.request)
        assertEquals(PatrolFlow.Outcome.FAILED, afterFail.state?.outcome)
    }

    @Test
    fun leavingTheForegroundPausesAndNeverResumesByItself() {
        val state = startSwitch()
        val paused = PatrolRunner.onRound(state, input(UiState.LAUNCH_PAGE, foreground = false)).state!!
        assertTrue(paused.paused)

        // 回前台：仍然暂停（FR-05：回前台不自动继续，等用户点「继续」）
        val backResult = PatrolRunner.onRound(paused, input(UiState.LAUNCH_PAGE))
        val back = backResult.state!!
        assertTrue(back.paused)
        assertNull(backResult.request)

        val resumed = PatrolFlow.resume(back)
        assertTrue(resumed.isRunning)
        assertNull(
            "解除暂停后应能正常点：${PatrolRunner.onRound(resumed, input(UiState.LAUNCH_PAGE, anchors = listOf(hit(PatrolAnchors.LAUNCH_SWITCH)))).note}",
            resumed.reason,
        )
    }

    // ------------------------------------------- 第 5 步：按名称定位（2026-09-24 接线）

    /** 第 5 步（选区服）：动作所在画面是选服页，点的是"识别给出的名字框本身"。 */
    private fun atPickingServer(): PatrolFlow.State =
        PatrolFlow.State(
            range = PatrolFlow.Range.SWITCH_AND_VISIT,
            step = PatrolFlow.Step.PICK_SERVER,
        )

    @Test
    fun theFifthStepClicksTheServerNameOnceThePlanIsReady() {
        // 采集层在两列候选里按名称定位到目标区服 → 编排层只管点；那一屏**没有锚点**，
        // 所以审计里用说明性名字 [PatrolAnchors.SERVER_NAME]（不是标定项）
        val result = PatrolRunner.onRound(
            atPickingServer(),
            input(
                UiState.SERVER_SELECT,
                targetServer = "碧海之眼",
                namePlan = PatrolRunner.NamePlan.Click(
                    frameX = 700.0,
                    frameY = 900.0,
                    detail = "区服名「碧海之眼」在 (700, 900)",
                ),
            ),
        )

        val request = requireNotNull(result.request)
        assertEquals(PatrolAnchors.SERVER_NAME, request.anchorName)
        assertEquals(700.0, request.frameX, 0.0)
        assertEquals(900.0, request.frameY, 0.0)
        assertEquals("点击后要记画面（观察窗口据此提前结束）", UiState.SERVER_SELECT, result.state?.stateAtLastAction)
        assertTrue("日志要说清点的是哪个区服：${result.note}", result.note.orEmpty().contains("碧海之眼"))
    }

    @Test
    fun theFifthStepStopsWithTheReasonWhenThePlanFailed() {
        // 定位不出来（没框区域 / 两列没对上 / 没找到那个区服名）→ 停下并原样带出原因，不猜位置
        val result = PatrolRunner.onRound(
            atPickingServer(),
            input(
                UiState.SERVER_SELECT,
                targetServer = "碧海之眼",
                namePlan = PatrolRunner.NamePlan.Failed("没找到「碧海之眼」"),
            ),
        )

        assertNull("定位失败绝不点", result.request)
        assertTrue(result.state?.reason.orEmpty().contains("没找到「碧海之眼」"))
        assertTrue(result.note.orEmpty().contains("停下"))
    }

    @Test
    fun theFifthStepScrollsTheListInsteadOfClickingWhenTheTargetIsOnAnotherScreen() {
        // 2026-09-29（用户报障："区服名在第二屏 ⇒ 报找不到而中止"）：这一屏没有目标时，
        // 采集层的扫描器给的是 **Scroll** —— 编排层要下发**滑动**（不是点击），
        // 而且**绝不能调 `PatrolFlow.acted`**：那会把"滚了一屏"记成"这一步已经动过手"，
        // 采集层随之停止读文字，扫描当场卡死。
        val result = PatrolRunner.onRound(
            atPickingServer(),
            input(
                UiState.SERVER_SELECT,
                targetServer = "碧海之眼",
                namePlan = PatrolRunner.NamePlan.Scroll(
                    fromFrameX = 900.0,
                    fromFrameY = 1000.0,
                    toFrameX = 900.0,
                    toFrameY = 400.0,
                    durationMs = 300L,
                    detail = "向下滚一屏继续找（手指向上划）",
                ),
            ),
        )

        assertNull("滚动这一轮不点", result.request)
        val swipe = requireNotNull(result.swipe)
        assertEquals(PatrolAnchors.SERVER_LIST_AREA, swipe.anchorName)
        assertEquals(900.0, swipe.fromFrameX, 0.0)
        assertEquals(1000.0, swipe.fromFrameY, 0.0)
        assertEquals(400.0, swipe.toFrameY, 0.0)
        assertEquals(300L, swipe.durationMs)
        assertEquals("滚动不算'已动手'", 0L, result.state?.lastActionAtMs)
        assertTrue("要说清在滚列表：${result.note}", result.note.orEmpty().contains("目标不在这一屏"))
    }

    @Test
    fun theFifthStepWaitsWithoutBurningRetriesWhileThePlanIsNotReady() {
        // 还没算（画面还不是选服页）→ 等，**不算失败、不消耗重试**（与第 9 步同口径）
        val result = PatrolRunner.onRound(
            atPickingServer(),
            input(UiState.SERVER_SELECT, targetServer = "碧海之眼"),
        )

        assertNull(result.request)
        assertNull("等不该记失败", result.state?.reason)
        assertEquals(0, result.state?.retries)
        assertTrue("要说清在找哪个区服：${result.note}", result.note.orEmpty().contains("碧海之眼"))
    }

    // ------------------------------------------- 第 9 步：按名称定位（2026-09-21 落地）

    @Test
    fun theNinthStepClicksTheRowIconOnceThePlanIsReady() {
        // 采集层定位好了（名字那一行 → 行尾拜访图标）→ 编排层只管点，且点的必须是**行尾图标锚点**
        val result = PatrolRunner.onRound(
            atVisiting(),
            input(
                UiState.FRIEND_LIST,
                targetFriend = "阿娜雅",
                namePlan = PatrolRunner.NamePlan.Click(
                    frameX = 1324.0,
                    frameY = 1450.0,
                    detail = "名字在 y=1440，图标在 (1324, 1450)",
                ),
            ),
        )

        val request = requireNotNull(result.request)
        assertEquals("点的必须是行尾拜访图标（名字与「申请」都不点）", PatrolAnchors.FRIEND_VISIT, request.anchorName)
        assertEquals(1324.0, request.frameX, 0.0)
        assertEquals(1450.0, request.frameY, 0.0)
        assertEquals("点击后要记画面（观察窗口据此提前结束）", UiState.FRIEND_LIST, result.state?.stateAtLastAction)
        assertTrue("日志要说清点了谁那一行：${result.note}", result.note.orEmpty().contains("阿娜雅"))
    }

    @Test
    fun theNinthStepStopsWithTheReasonWhenThePlanFailed() {
        // 定位不出来（没样本 / 没找到那一行 / 行内没图标）→ 停下并原样带出原因，不猜位置
        val result = PatrolRunner.onRound(
            atVisiting(),
            input(
                UiState.FRIEND_LIST,
                targetFriend = "阿娜雅",
                namePlan = PatrolRunner.NamePlan.Failed("没有「阿娜雅」的名字样本"),
            ),
        )

        assertNull("定位失败绝不点", result.request)
        assertTrue(result.state?.reason.orEmpty().contains("没有「阿娜雅」的名字样本"))
        assertTrue(result.note.orEmpty().contains("停下"))
    }

    @Test
    fun theNinthStepWaitsWhileThePlanIsNotReadyYet() {
        // 还没算（画面还不是好友列表）→ 等，**不算失败、不消耗重试**
        val result = PatrolRunner.onRound(
            atVisiting(),
            input(UiState.FRIEND_LIST, targetFriend = "阿娜雅"),
        )

        assertNull(result.request)
        assertNull("等不该记失败", result.state?.reason)
        assertTrue(result.note.orEmpty().contains("阿娜雅"))
    }

    // ---------------------------------------------------------------- 第 9 步的完成判据（2026-09-23 简化）

    private fun atVisiting(): PatrolFlow.State =
        PatrolFlow.State(
            range = PatrolFlow.Range.SWITCH_AND_VISIT,
            step = PatrolFlow.Step.VISIT_FRIEND,
        )

    @Test
    fun theNinthStepPassesAsSoonAsTheFarmShowsUp() {
        // 2026-09-21 曾在"看到农场"之上再叠一层"精确到人"的验证（靠农场身份判据：头像 / 农场名）。
        // 2026-09-23 用户拍板**整块删掉**：流程的结束节点就是**在好友列表点下「拜访」**，
        // 自己的农场与好友的农场换号 / 拜访路径完全一样 ⇒ 第 9 步的完成判据回到"看到农场画面"。
        // 代价如实记录：**点错人不再有回头拦截**（点的是哪一行由第 9 步的名称定位保证，红线 3）。
        val result = PatrolRunner.onRound(atVisiting(), input(UiState.FARM, targetFriend = "Boss~~喵"))

        // 推进到第 10 步（"完成"这一步的收尾由终点逻辑处理，见 reachingTheEndOfTheRangeFinishes）
        assertEquals(PatrolFlow.Step.DONE, result.state?.step)
        assertNull(result.state?.reason)
    }

    @Test
    fun reachingTheEndOfTheRangeFinishes() {
        // 「只换号」的终点是第 6 步（登录进大厅）：看到大厅即完成
        val state = PatrolFlow.State(
            range = PatrolFlow.Range.SWITCH_ONLY,
            step = PatrolFlow.Step.LOGIN,
        )
        val result = PatrolRunner.onRound(state, input(UiState.HALL))

        assertTrue(result.finished)
        assertEquals(PatrolFlow.Outcome.FINISHED, result.state?.outcome)
        assertEquals("已完成", result.note)
    }

    @Test
    fun stepsOutsideTheRangeAreSkipped() {
        // 「只拜访」从第 7 步起：万一状态机停在第 4 步（不该发生），也不执行、不验证，直接跳过
        val state = PatrolFlow.State(
            range = PatrolFlow.Range.VISIT_ONLY,
            step = PatrolFlow.Step.SWITCH_SERVER,
        )
        val result = PatrolRunner.onRound(state, input(UiState.LAUNCH_PAGE))

        assertNull(result.request)
        assertEquals(PatrolFlow.Step.PICK_SERVER, result.state?.step)
        assertTrue(result.note.orEmpty().contains("跳过"))
    }
}
