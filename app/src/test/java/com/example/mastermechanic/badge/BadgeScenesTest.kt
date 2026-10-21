package com.example.mastermechanic.badge

import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BadgeScenes]（临时需求 temp/badge-share-1021）：立牌步骤契约的**不变量**。
 *
 * 这里钉的每一条都对应一次真实事故类型：
 * - 动作没有验证目标 ⇒ 点完不验证（红线 5）；
 * - 动作里出现契约表没声明的锚点 ⇒ 标定页给不了用途、运行时永远找不到（"框了却选不到用途"）；
 * - 判定步混进点击 ⇒ 多实例被随机挑一个（红线 3）。
 */
class BadgeScenesTest {

    @Test
    fun everyClickActionCarriesAVerificationTarget() {
        for (step in BadgeFlow.Step.entries) {
            for (action in BadgeScenes.actions(step)) {
                assertTrue(
                    "$step 的动作「${action.anchor}」既没有期望画面也没有期望锚点 ⇒ 点完不验证",
                    action.expect != null || action.expectAnchor != null,
                )
            }
        }
    }

    @Test
    fun judgmentStepsHaveNoActions() {
        val judgments = listOf(
            BadgeFlow.Step.DECIDE_BRANCH,
            BadgeFlow.Step.PICK_CARD,
            BadgeFlow.Step.READ_FIRST,
            BadgeFlow.Step.READ_CARD,
        )
        for (step in judgments) {
            assertTrue("$step 是判定步，不该有点击动作", BadgeScenes.actions(step).isEmpty())
            assertTrue(BadgeScenes.isJudgmentStep(step))
        }
        assertFalse(BadgeScenes.isJudgmentStep(BadgeFlow.Step.ENTER_ACTIVITY))
    }

    @Test
    fun actionsOnlyUseAnchorsDeclaredForTheirState() {
        for (step in BadgeFlow.Step.entries) {
            for (action in BadgeScenes.actions(step)) {
                val declared = BadgeAnchors.usageByState[action.state].orEmpty()
                assertTrue(
                    "$step 在 ${action.state} 上用了「${action.anchor}」，但该状态的用途表里没有它",
                    action.anchor in declared,
                )
            }
        }
    }

    @Test
    fun usageTableIsExactlyTheActionsPlusRegionAndJudgmentAnchors() {
        val used = mutableMapOf<UiState, MutableSet<String>>()
        for (step in BadgeFlow.Step.entries) {
            for (action in BadgeScenes.actions(step)) {
                used.getOrPut(action.state) { mutableSetOf() } += action.anchor
            }
        }
        for ((state, declared) in BadgeAnchors.usageByState) {
            val extra = declared - used[state].orEmpty()
            // 剩下的只能是：只圈区域的识别锚点 / 只判存在的空槽 / 由判定步（PICK_CARD）给位置的卡片「分享」
            val allowedExtra = BadgeAnchors.regionAnchors +
                BadgeAnchors.BADGE_EMPTY_SLOT +
                BadgeAnchors.BADGE_SHARE
            assertEquals(
                "$state 的用途表里出现了既不在点击链、也不是识别区域/空槽判定的锚点",
                emptySet<String>(),
                extra - allowedExtra,
            )
        }
    }

    @Test
    fun watchStatesNarrowWhenStateIsKnownAndWidenWhenUnknown() {
        val known = BadgeScenes.watchStatesFor(BadgeFlow.Step.ENTER_ACTIVITY, UiState.HALL)
        assertEquals(setOf(UiState.HALL, UiState.ACTIVITY_CENTER), known)

        val unknown = BadgeScenes.watchStatesFor(BadgeFlow.Step.ENTER_ACTIVITY, UiState.UNKNOWN)
        assertEquals(BadgeScenes.allStates, unknown)
        assertTrue(unknown.containsAll(known))
    }

    @Test
    fun unknownStateSearchesTheWholeChainNotJustThisStep() {
        // 真机教训（2026-10-22）：第 11 步点了关闭 X 之后画面直接跳到「活动中心」/「大厅」，
        // 若自愈阀只放到本步链（复制分享 + 我的立牌）⇒ 永远认不出 ⇒ 15 秒后误报中止。
        val unknown = BadgeScenes.watchStatesFor(BadgeFlow.Step.CLOSE_CARD, UiState.UNKNOWN)
        assertTrue("必须包含活动中心", UiState.ACTIVITY_CENTER in unknown)
        assertTrue("必须包含大厅", UiState.HALL in unknown)
        assertTrue("必须包含四个弹框", unknown.containsAll(
            setOf(UiState.BADGE_CLAIM, UiState.BADGE_MY, UiState.BADGE_SHARE),
        ))
    }

    @Test
    fun knownStateStaysNarrowEvenOnTheSameStep() {
        val known = BadgeScenes.watchStatesFor(BadgeFlow.Step.CLOSE_CARD, UiState.BADGE_SHARE)
        // 关闭 X 有两种合法结果：回「我的立牌」，或连它一起关掉直接回「活动中心」（真机 2026-10-22）
        assertEquals(setOf(UiState.BADGE_SHARE, UiState.BADGE_MY, UiState.ACTIVITY_CENTER), known)
    }

    @Test
    fun checkRequiresTheExpectedScreenFirst() {
        val action = BadgeScenes.Action(
            state = UiState.HALL,
            anchor = BadgeAnchors.HALL_ACTIVITY,
            expect = UiState.ACTIVITY_CENTER,
        )
        assertFalse(BadgeScenes.check(action, UiState.HALL, emptySet()))
        assertTrue(BadgeScenes.check(action, UiState.ACTIVITY_CENTER, emptySet()))
    }

    @Test
    fun checkUsesTheAnchorWhenTheScreenDoesNotChange() {
        // 「领取」：画面仍是获取立牌弹框，靠「点击继续」出现判通过
        val action = BadgeScenes.actions(BadgeFlow.Step.CLAIM_TASK).single()
        assertFalse(
            "点之前「点击继续」还没出现 ⇒ 不能算通过（否则会立刻跳步）",
            BadgeScenes.check(action, UiState.BADGE_CLAIM, setOf(BadgeAnchors.BADGE_CLAIM)),
        )
        assertTrue(
            BadgeScenes.check(action, UiState.BADGE_CLAIM, setOf(BadgeAnchors.BADGE_CONTINUE)),
        )
    }

    @Test
    fun claimFinishPrefersContinueThenClose() {
        val actions = BadgeScenes.actions(BadgeFlow.Step.CLAIM_FINISH)
        assertEquals(BadgeAnchors.BADGE_CONTINUE, actions.first().anchor)
        assertEquals(BadgeAnchors.BADGE_CLAIM_CLOSE, actions.last().anchor)
        for (action in actions) assertEquals(UiState.ACTIVITY_CENTER, action.expect)
    }

    @Test
    fun activityIsReadyOnlyWhenBothEntriesAreLocated() {
        assertFalse(BadgeScenes.activityReady(setOf(BadgeAnchors.BADGE_CLAIM_ENTRY)))
        assertFalse(BadgeScenes.activityReady(setOf(BadgeAnchors.BADGE_SHARE_ENTRY)))
        assertTrue(
            BadgeScenes.activityReady(
                setOf(BadgeAnchors.BADGE_CLAIM_ENTRY, BadgeAnchors.BADGE_SHARE_ENTRY),
            ),
        )
    }
}
