package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.DetectionRecord
import com.example.mastermechanic.recognition.MatchPeak
import com.example.mastermechanic.recognition.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信号—状态映射单测（T1-4）：一个状态可由多个信号构成（§2.1「任一命中即为大厅」）。
 *
 * 计入口径（2026-09-29 起，见 [SignalStateMapping.resolve]）：**标志只问"在不在"** ——
 * 命中即在场；"不可信但最高分够高（≥ [DetectionRecord.PRESENCE_SCORE]）"**也算在场**
 * （位置歧义不影响"在不在"）。要点击的锚点不受影响，仍只看 `matched`（本文件末尾钉住这一点）。
 */
class SignalStateMappingTest {

    private val mapping = SignalStateMapping(
        listOf(
            SignalStateMapping.Rule(UiState.ACTIVITY_POPUP, setOf("弹窗关闭")),
            SignalStateMapping.Rule(UiState.HALL, setOf("对战入口", "排位入口", "农场入口")),
        ),
    )

    private fun matched(name: String) =
        DetectionRecord(name, Verdict.MATCHED, MatchPeak(0.95, 1, 1), null)

    private fun record(name: String, verdict: Verdict) =
        DetectionRecord(name, verdict, null, null)

    /** 「多处高分 ⇒ 不可信」那种记录：最高分 + 一个竞争位置。 */
    private fun unreliable(name: String, best: Double, competitor: Double) =
        DetectionRecord(name, Verdict.UNRELIABLE, MatchPeak(best, 1, 1), MatchPeak(competitor, 9, 9))

    @Test
    fun anySignalHitMakesStateHit() {
        val states = mapping.resolve(listOf(matched("农场入口")))
        assertEquals(setOf(UiState.HALL), states)
    }

    @Test
    fun ignoresNotMatchedAndUnreliable() {
        val states = mapping.resolve(
            listOf(
                record("农场入口", Verdict.NOT_MATCHED),
                record("弹窗关闭", Verdict.UNRELIABLE),
            ),
        )
        assertTrue(states.isEmpty())
    }

    @Test
    fun aMarkerWithATopScoreCountsEvenIfAPlaceAlsoLooksLikeIt() {
        // 2026-09-29 用户报障：在"新手引导页"框选「开局送英雄!」当标志，写入试读是
        // `⚠ 不可信（最高 1.00，但竞争位置 (204, 38) 也有 0.90）` ⇒ 旧口径把它整条废掉
        // ⇒ 新手引导页永远认不出（FR-01/FR-02 也关不掉它）。现在：最高分够高 ⇒ 算在场。
        val states = mapping.resolve(
            listOf(unreliable("农场入口", best = 1.00, competitor = 0.90)),
        )
        assertEquals(setOf(UiState.HALL), states)
    }

    @Test
    fun aMarkerWhoseBestIsOnlySlightlyAboveTheLineIsStillRefused() {
        // 位置歧义**依然**是拒绝的理由 —— 只有当最高分"是它本人"（≥ PRESENCE_SCORE）时才放行
        val states = mapping.resolve(
            listOf(unreliable("农场入口", best = 0.90, competitor = 0.86)),
        )
        assertTrue(states.isEmpty())
    }

    @Test
    fun presenceScoreBoundaryIsPinned() {
        val justEnough = mapping.resolve(
            listOf(unreliable("农场入口", best = DetectionRecord.PRESENCE_SCORE, competitor = 0.90)),
        )
        assertEquals("刚好到线 ⇒ 在场", setOf(UiState.HALL), justEnough)
        val justBelow = mapping.resolve(
            listOf(
                unreliable("农场入口", best = DetectionRecord.PRESENCE_SCORE - 0.001, competitor = 0.90),
            ),
        )
        assertTrue("差一点点 ⇒ 不算在场", justBelow.isEmpty())
    }

    @Test
    fun theClickPathIsStillStrict() {
        // **这一条是这次的护栏**：标志放宽了，但"要点击"的锚点不许跟着放宽 ——
        // AnchorLocator 只看 `matched`，所以"不可信但最高分很高"的锚点**仍然点不出去**
        val record = unreliable("农场入口", best = 1.00, competitor = 0.90)
        assertTrue("标志语义：在场", record.present)
        assertFalse("锚点语义：不许点（位置歧义一票否决）", record.matched)
    }

    @Test
    fun severalStatesResolvedTogether() {
        val states = mapping.resolve(listOf(matched("弹窗关闭"), matched("农场入口")))
        assertEquals(setOf(UiState.ACTIVITY_POPUP, UiState.HALL), states)
    }

    @Test
    fun unknownCannotBeRuleTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalStateMapping(listOf(SignalStateMapping.Rule(UiState.UNKNOWN, setOf("任意"))))
        }
    }

    @Test
    fun ruleRequiresAtLeastOneSignalName() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalStateMapping(listOf(SignalStateMapping.Rule(UiState.FARM, emptySet())))
        }
    }
}
