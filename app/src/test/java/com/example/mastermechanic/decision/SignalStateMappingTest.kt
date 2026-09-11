package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.DetectionRecord
import com.example.mastermechanic.recognition.MatchPeak
import com.example.mastermechanic.recognition.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信号—状态映射单测（T1-4）：一个状态可由多个信号构成（§2.1「任一命中即为大厅」），
 * 仅 MATCHED 计入（未命中 / 不可信不算命中，§5-2）。
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
