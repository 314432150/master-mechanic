package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolAnchors
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「仍然很贵」那份排名的口径（2026-10-03）。
 *
 * ## 为什么值得单测
 *
 * 原来的排名把**三种不是同一种成本**的记录混在一起排，而且"最贵前两名"恰好是**零匹配成本**的
 * "只圈区域"锚点（`server_list_area` 604M / `friend_list_area` 334M）—— 真机日志里
 * "只能靠把模板框小来提速 ⇒ …" 点名点的是它们 ⇒ **用户照着框，框的是错的东西**
 * （框小它们不省任何 CPU，只会把读文字的范围变小）。
 *
 * 所以这里钉住三件事：
 * 1. **每轮固定成本**那一组 = 标志（MARKER）⇒ 那才是"该重框"的清单；
 * 2. **按需**那一组 = 动作锚点（ANCHOR，不在 nonLocatable 里）⇒ 提示但不算每轮成本；
 * 3. **零成本的区域型锚点必须被排除**，并把名字带出去（说明为什么没报）。
 */
class StillHeavyRankingTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)

    /**
     * 造一个"贵"的记录：窗口给足、模板给大 ⇒ ops 直接越过门槛。
     *
     * 用真机量级（帧 1440×3168）而不是小帧：ops 与窗口像素数相关，小帧下什么都够不到门槛。
     */
    private fun heavy(
        id: String,
        role: SignalRole,
        templateWidth: Int = 200,
        templateHeight: Int = 200,
        purpose: String? = null,
    ): CalibrationData.SignalEntry = CalibrationData.SignalEntry(
        id,
        SearchWindow(0.0, 0.0, 1.0, 1.0),
        listOf(Template(templateWidth, templateHeight, SyntheticImages.pattern(templateWidth, templateHeight, 7))),
        role,
        purpose = purpose,
    )

    private fun cheap(id: String, role: SignalRole): CalibrationData.SignalEntry = CalibrationData.SignalEntry(
        id,
        SearchWindow(0.0, 0.0, 0.1, 0.1),
        listOf(Template(10, 8, SyntheticImages.pattern(10, 8, 3))),
        role,
    )

    /**
     * 造一份产物。
     *
     * ⚠ `SignalEntry` 构造即校验：**锚点必须出现在某条 `StateRule` 的 `anchorNames` 里**（构造守卫，
     * 见 `CalibrationModelTest`）⇒ 这里按角色自动把归属规则补齐，测试只关心排名口径。
     */
    private fun data(signals: List<CalibrationData.SignalEntry>) = CalibrationData(
        1440,
        3168,
        params,
        signals,
        stateRules = listOf(
            CalibrationData.StateRule(
                UiState.FARM,
                signalNames = signals.filter { it.role == SignalRole.MARKER }.map { it.id },
                anchorNames = signals.filter { it.role == SignalRole.ANCHOR }.map { it.id },
            ),
        ),
    )

    @Test
    fun theRegionTypeAnchorsAreExcludedBecauseTheyNeverMatch() {
        // 真机日志里"最贵两条"就是它们：server_list_area 604M、friend_list_area 334M。
        // 区域型锚点**不参与模板匹配**（AnchorLocator 构造时被排除、识别只匹配标志）
        // ⇒ 绝不能出现在"该重框"的清单里。
        val regionOne = heavy("friend_list_area", SignalRole.ANCHOR, 50, 64, PatrolAnchors.FRIEND_LIST_AREA)
        val regionTwo = heavy("server_list_area", SignalRole.ANCHOR, 64, 28, PatrolAnchors.SERVER_LIST_AREA)

        val ranking = CalibrationStore.stillHeavyRanking(
            data(listOf(regionOne, regionTwo)),
            nonLocatable = setOf("friend_list_area", "server_list_area"),
        )

        assertTrue("零匹配的锚点不许进任何一组", ranking.perRound.isEmpty() && ranking.onDemand.isEmpty())
        assertEquals(
            "但要点名说明为什么没报（免得用户以为漏报）",
            listOf("friend_list_area", "server_list_area"),
            ranking.excluded,
        )
    }

    @Test
    fun onlyMarkersCountAsThePerRoundFixedCost() {
        // "该重框标志"这一组必须只装标志；动作锚点再贵也不是每轮成本（只有轮到那一步才跑）。
        val ranking = CalibrationStore.stillHeavyRanking(
            data(
                listOf(
                    heavy("tutorial_hall_e1", SignalRole.MARKER, 248, 80),
                    heavy("tutorial_guide_e1", SignalRole.MARKER, 181, 51),
                    heavy("farm_friends", SignalRole.ANCHOR, 200, 200, PatrolAnchors.FARM_FRIENDS),
                ),
            ),
            nonLocatable = emptySet(),
        )

        assertEquals(2, ranking.perRound.size)
        assertTrue(
            "每轮固定成本那一组只装标志：${ranking.perRound}",
            ranking.perRound.all { it.startsWith("tutorial_") && it.contains("标志") },
        )
        // 降序：模板面积大的排前面（248×80 = 19840 > 181×51 = 9231）
        assertTrue(
            "按 ops 降序：${ranking.perRound}",
            ranking.perRound.first().startsWith("tutorial_hall_e1"),
        )
        assertEquals(1, ranking.onDemand.size)
        assertTrue(ranking.onDemand.single().startsWith("farm_friends"))
    }

    @Test
    fun signalsBelowTheThresholdDoNotShowUp() {
        // 门槛存在的意义就是"只点名真贵的"⇒ 一条都不够格时不该刷屏（空榜单）。
        val ranking = CalibrationStore.stillHeavyRanking(
            data(listOf(cheap("launch_login", SignalRole.MARKER))),
            nonLocatable = emptySet(),
        )

        assertTrue(ranking.perRound.isEmpty())
        assertTrue(ranking.onDemand.isEmpty())
    }

    @Test
    fun theSmallestTemplateIsNotWhatWeMeasure() {
        // ops 与**最大**那个模板成正比（每条记录只留一张模板参与匹配）⇒
        // 一条"大模板 + 小模板"的记录要按大模板算，不能被小模板拉低。
        val withTwo = CalibrationData.SignalEntry(
            "two_templates",
            SearchWindow(0.0, 0.0, 1.0, 1.0),
            listOf(
                Template(10, 8, SyntheticImages.pattern(10, 8, 1)),
                Template(240, 90, SyntheticImages.pattern(240, 90, 2)),
            ),
            SignalRole.MARKER,
        )

        val ranking = CalibrationStore.stillHeavyRanking(data(listOf(withTwo)), nonLocatable = emptySet())

        assertEquals(1, ranking.perRound.size)
        assertTrue(
            "按大模板报（240×90）：${ranking.perRound}",
            ranking.perRound.single().contains("模板 240×90"),
        )
    }
}
