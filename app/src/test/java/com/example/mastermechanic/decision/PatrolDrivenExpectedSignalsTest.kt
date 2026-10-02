package com.example.mastermechanic.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跑号期间的期望集合（M4-T4-4b，纯逻辑）。
 *
 * 钉住两点：**弹窗永远在集合里**（FR-01 不能被跑号挤掉），以及**只加当前步骤要认的画面**（不外扩成全扫）。
 */
class PatrolDrivenExpectedSignalsTest {

    private val rules = listOf(
        SignalStateMapping.Rule(UiState.ACTIVITY_POPUP, setOf("popup_close", "popup_close2")),
        SignalStateMapping.Rule(UiState.HALL, setOf("hall_marker")),
        SignalStateMapping.Rule(UiState.FARM, setOf("farm_marker")),
        SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("launch_marker")),
    )

    private fun subject(
        standbyStates: Set<UiState> = emptySet(),
    ): PatrolDrivenExpectedSignals =
        PatrolDrivenExpectedSignals(
            popup = PopupWatchExpectedSignals.fromRules(rules),
            rules = rules,
            standbyStates = standbyStates,
        )

    @Test
    fun withoutAFlowItIsJustThePopupWatchSet() {
        val expected = subject()
        // 没在跑 → 不加任何东西（与生产默认完全一致）
        assertEquals(setOf("popup_close", "popup_close2"), expected.expected(rules.flatMap { it.signalNames }.toSet()))
    }

    @Test
    fun theStepScreensAreAddedOnTopOfThePopup() {
        val expected = subject()
        expected.watch(listOf(UiState.HALL, UiState.FARM))

        assertEquals(
            setOf("popup_close", "popup_close2", "hall_marker", "farm_marker"),
            expected.expected(rules.flatMap { it.signalNames }.toSet()),
        )
    }

    @Test
    fun thePopupIsNeverDropped() {
        // 跑号时也不能把弹窗挤掉：弹窗会随时冒出来打断步骤（FR-01 恒定要搜）
        val expected = subject()
        expected.watch(listOf(UiState.LAUNCH_PAGE))
        val set = expected.expected(rules.flatMap { it.signalNames }.toSet())
        assertTrue("弹窗记录必须在：${set}", set.containsAll(setOf("popup_close", "popup_close2")))
    }

    @Test
    fun watchingNothingGoesBackToTheGuardSet() {
        val expected = subject()
        expected.watch(listOf(UiState.HALL))
        expected.watch(emptyList())
        assertEquals(
            setOf("popup_close", "popup_close2"),
            expected.expected(rules.flatMap { it.signalNames }.toSet()),
        )
    }

    @Test
    fun standbyAlsoWatchesTheStartScreens() {
        // 待命期（没在跑）必须认得出"用户现在在哪一屏"，否则菜单的起点判定永远读到「未知」
        // —— 2026-09-20 真机上第一次跑号就是被这个死锁卡住的。
        val expected = subject(standbyStates = setOf(UiState.HALL, UiState.FARM))

        assertEquals(
            setOf("popup_close", "popup_close2", "hall_marker", "farm_marker"),
            expected.expected(rules.flatMap { it.signalNames }.toSet()),
        )
    }

    @Test
    fun whileRunningTheStandbyScreensStepAside() {
        // 在跑 → 只看当前步骤要认的画面（起点已定，待命集合让位，成本不叠加）
        val expected = subject(standbyStates = setOf(UiState.HALL, UiState.FARM, UiState.LAUNCH_PAGE))
        expected.watch(listOf(UiState.HALL))

        assertEquals(
            setOf("popup_close", "popup_close2", "hall_marker"),
            expected.expected(rules.flatMap { it.signalNames }.toSet()),
        )
    }

    @Test
    fun uncalibratedScreensContributeNothing() {
        // 画面没标定（规则里没有）→ 不凭空造名字（未标定的名字会被 ActiveSignalSelector 剔除，
        // 但这里连"写进期望"都不会发生）
        val expected = subject()
        expected.watch(listOf(UiState.FRIEND_LIST))
        assertEquals(
            setOf("popup_close", "popup_close2"),
            expected.expected(rules.flatMap { it.signalNames }.toSet()),
        )
    }

    // ===== 2026-10-02 用户提速：弹窗在屏时，流程预期 / 待命起点那一组本轮不搜 =====

    private val allNames = rules.flatMap { it.signalNames }.toSet()

    @Test
    fun whileAnOverlayIsUpTheStepScreensAreNotSearched() {
        // 用户原话："弹窗在屏时一轮为什么要搜 8 个信号这么多" ⇒ 弹窗盖着屏幕时，流程预期的那几屏
        // （启动页 / 选服页 / 农场…）**本来就看不见**，搜了必然不命中 ⇒ 纯浪费。
        // 保留：弹窗标志 + **上一轮还看得见**的那些画面（真机 23:21:00 那轮「命中：活动弹窗、大厅」——
        // 大厅的标志露在弹窗外，弹窗一关就是它让状态当轮就能回落）。
        val expected = subject()
        expected.watch(listOf(UiState.HALL, UiState.FARM))
        expected.onRound(setOf(UiState.ACTIVITY_POPUP, UiState.HALL))

        assertEquals(
            "只留弹窗 + 底下还看得见的大厅；农场那一组本轮不搜",
            setOf("popup_close", "popup_close2", "hall_marker"),
            expected.expected(allNames),
        )
    }

    @Test
    fun whileAnOverlayIsUpWithNothingVisibleBehindItOnlyThePopupIsSearched() {
        val expected = subject(standbyStates = setOf(UiState.HALL, UiState.FARM, UiState.LAUNCH_PAGE))
        expected.onRound(setOf(UiState.ACTIVITY_POPUP))

        assertEquals(setOf("popup_close", "popup_close2"), expected.expected(allNames))
    }

    @Test
    fun asSoonAsTheOverlayIsGoneTheStepScreensComeBack() {
        // 关键：收窄**只持续到下一轮**。弹窗一走就立刻恢复流程预期 —— 否则状态会掉「未知」，
        // 而"未知 ⇒ 零点击"会连**下一个**弹窗都关不掉（真机是弹窗**一串 3 个**、间隔 0.8 秒）。
        val expected = subject()
        expected.watch(listOf(UiState.HALL, UiState.FARM))
        expected.onRound(setOf(UiState.ACTIVITY_POPUP, UiState.HALL))
        assertEquals(setOf("popup_close", "popup_close2", "hall_marker"), expected.expected(allNames))

        expected.onRound(setOf(UiState.HALL)) // 弹窗没了
        assertEquals(
            "恢复成弹窗 + 当前步骤的两屏",
            setOf("popup_close", "popup_close2", "hall_marker", "farm_marker"),
            expected.expected(allNames),
        )
    }

    @Test
    fun theNarrowingNeverDropsThePopupMarkers() {
        // 收窄是为了省时间，**不是**为了少看见弹窗：弹窗标志任何时候都必须在（FR-01 的命根子）
        val expected = subject()
        expected.watch(listOf(UiState.FARM))
        expected.onRound(setOf(UiState.ACTIVITY_POPUP, UiState.FARM))
        val set = expected.expected(allNames)
        assertTrue("弹窗记录必须在：$set", set.containsAll(setOf("popup_close", "popup_close2")))
    }
}
