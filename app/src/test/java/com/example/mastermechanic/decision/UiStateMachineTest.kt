package com.example.mastermechanic.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 滞回时序单测（T1-4 验收 A5，§2.2 / FR-09）：
 * 进入（连续 2 次命中）/ 离开（连续 3 次未命中）/ 未知（连续未命中任何标志）/
 * 非前台冻结（不消耗连续计数）/ 优先级与确定性。
 */
class UiStateMachineTest {

    private fun hits(vararg states: UiState): Set<UiState> = states.toSet()

    private fun enterFarm(machine: UiStateMachine) {
        machine.update(hits(UiState.FARM), foreground = true)
        machine.update(hits(UiState.FARM), foreground = true)
        assertEquals(UiState.FARM, machine.current)
    }

    // ---- 进入：连续 2 次命中 ----

    @Test
    fun singleHitDoesNotEnter() {
        val machine = UiStateMachine()
        assertNull(machine.update(hits(UiState.FARM), foreground = true))
        assertEquals(UiState.UNKNOWN, machine.current)
    }

    @Test
    fun consecutiveHitsEnterState() {
        val machine = UiStateMachine()
        assertNull(machine.update(hits(UiState.FARM), foreground = true))
        val transition = machine.update(hits(UiState.FARM), foreground = true)
        assertNotNull(transition)
        assertEquals(UiState.UNKNOWN, transition!!.from)
        assertEquals(UiState.FARM, transition.to)
        assertEquals(UiState.FARM, machine.current)
    }

    @Test
    fun brokenStreakDoesNotEnter() {
        val machine = UiStateMachine()
        machine.update(hits(UiState.FARM), foreground = true)
        machine.update(hits(), foreground = true) // 断链
        machine.update(hits(UiState.FARM), foreground = true)
        assertEquals(UiState.UNKNOWN, machine.current)
    }

    // ---- 离开：连续 3 次未命中 ----

    @Test
    fun twoMissesKeepState() {
        val machine = UiStateMachine()
        enterFarm(machine)
        machine.update(hits(), foreground = true)
        machine.update(hits(), foreground = true)
        assertEquals(UiState.FARM, machine.current)
    }

    @Test
    fun threeMissesLeaveToUnknown() {
        val machine = UiStateMachine()
        enterFarm(machine)
        machine.update(hits(), foreground = true)
        machine.update(hits(), foreground = true)
        val transition = machine.update(hits(), foreground = true)
        assertNotNull(transition)
        assertEquals(UiState.FARM, transition!!.from)
        assertEquals(UiState.UNKNOWN, transition.to)
        assertEquals(UiState.UNKNOWN, machine.current)
    }

    @Test
    fun hitResetsMissStreak() {
        val machine = UiStateMachine()
        enterFarm(machine)
        machine.update(hits(), foreground = true) // 未命中 1
        machine.update(hits(UiState.FARM), foreground = true) // 命中：清零
        machine.update(hits(), foreground = true) // 未命中 1
        machine.update(hits(), foreground = true) // 未命中 2
        assertEquals(UiState.FARM, machine.current)
    }

    // ---- 切换与优先级 ----

    @Test
    fun newStateEnteredOnTwoConsecutiveHits() {
        val machine = UiStateMachine()
        enterFarm(machine)
        machine.update(hits(UiState.HALL), foreground = true)
        val transition = machine.update(hits(UiState.HALL), foreground = true)
        assertNotNull(transition)
        assertEquals(UiState.FARM, transition!!.from)
        assertEquals(UiState.HALL, transition.to)
    }

    @Test
    fun highestPriorityStateWinsWhenSeveralHit() {
        val machine = UiStateMachine()
        machine.update(hits(UiState.FARM, UiState.ACTIVITY_POPUP), foreground = true)
        val transition = machine.update(hits(UiState.FARM, UiState.ACTIVITY_POPUP), foreground = true)
        assertNotNull(transition)
        assertEquals(UiState.ACTIVITY_POPUP, transition!!.to)
    }

    // ---- 未知：连续未命中任何标志 ----

    @Test
    fun staysUnknownWithoutAnyHit() {
        val machine = UiStateMachine()
        repeat(4) { assertNull(machine.update(hits(), foreground = true)) }
        assertEquals(UiState.UNKNOWN, machine.current)
    }

    // ---- 非前台冻结（FR-09：不消耗连续计数）----

    @Test
    fun frozenRoundsDoNotConsumeEnterCounter() {
        val machine = UiStateMachine()
        machine.update(hits(UiState.FARM), foreground = true) // 命中 1
        machine.update(hits(), foreground = false) // 冻结
        machine.update(hits(), foreground = false) // 冻结
        val transition = machine.update(hits(UiState.FARM), foreground = true) // 再命中 1 → 连 2
        assertNotNull(transition)
        assertEquals(UiState.FARM, transition!!.to)
    }

    @Test
    fun frozenRoundsDoNotConsumeLeaveCounter() {
        val machine = UiStateMachine()
        enterFarm(machine)
        machine.update(hits(), foreground = true) // 未命中 1
        machine.update(hits(), foreground = false) // 冻结
        machine.update(hits(), foreground = false) // 冻结
        machine.update(hits(), foreground = true) // 未命中 2
        assertEquals(UiState.FARM, machine.current)
    }

    // ---- 确定性：同序列同结果 ----

    @Test
    fun sameSequenceProducesIdenticalTransitions() {
        val sequence = listOf(
            hits(UiState.FARM), hits(UiState.FARM), hits(),
            hits(UiState.HALL), hits(UiState.HALL),
            hits(), hits(), hits(),
        )
        fun run(): List<UiStateTransition?> {
            val machine = UiStateMachine()
            return sequence.map { machine.update(it, foreground = true) }
        }
        assertEquals(run(), run())
    }
}
