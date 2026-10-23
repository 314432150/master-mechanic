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
        // 第 1 步的两种落点都要搜：活动中心（等就绪，等不到 ⇒ 点返回）/ 大厅
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
    fun neededAnchorsAlwaysCoverWhatTheStepClicksAndVerifies() {
        // 提速刀（2026-10-22）：采集层只定位这一步要用的锚点。
        // 漏掉 `action.anchor` ⇒ 该点的一直点不到；漏掉 `expectAnchor` ⇒ 同屏验证永远不通过（会白等到超时）。
        for (step in BadgeFlow.Step.entries) {
            for (action in BadgeScenes.actions(step)) {
                val need = BadgeScenes.neededAnchors(step, action.state)
                assertTrue("$step 在 ${action.state} 上没把要点的「${action.anchor}」列进定位清单", action.anchor in need)
                action.expectAnchor?.let {
                    assertTrue("$step 没把验证用的「$it」列进定位清单", it in need)
                }
            }
        }
    }

    @Test
    fun neededAnchorsForJudgmentStepsAreExactlyTheirCriteria() {
        // 就绪锚点跟着 readyAnchors 走（2026-10-23 起只含「分享立牌」：当日已提交时「获取立牌」不显示）；
        // 判定步还要能点「立牌活动入口」自救（假就绪时唯一出路）
        val branch = BadgeScenes.neededAnchors(BadgeFlow.Step.DECIDE_BRANCH, UiState.ACTIVITY_CENTER)
        assertEquals(
            BadgeScenes.readyAnchors + BadgeAnchors.BADGE_EMPTY_SLOT + BadgeAnchors.BADGE_ACTIVITY_ENTRY,
            branch,
        )
        // 画面不是判定步所在的那一屏 ⇒ 一条都不定位（编排器本来也不会在那儿动手）
        assertEquals(emptySet<String>(), BadgeScenes.neededAnchors(BadgeFlow.Step.DECIDE_BRANCH, UiState.HALL))

        assertEquals(
            setOf(BadgeAnchors.BADGE_SHARE),
            BadgeScenes.neededAnchors(BadgeFlow.Step.PICK_CARD, UiState.BADGE_MY),
        )
        // 读取步只看 OCR 区域，不需要定位任何锚点（原先每轮白扫 2 条 ≈128ms）
        assertEquals(emptySet<String>(), BadgeScenes.neededAnchors(BadgeFlow.Step.READ_CARD, UiState.BADGE_SHARE))
        assertEquals(emptySet<String>(), BadgeScenes.neededAnchors(BadgeFlow.Step.READ_FIRST, UiState.BADGE_SHARE))
    }

    @Test
    fun neededAnchorsStayWithoutTheOtherStatesOfTheSameStep() {
        // CLAIM_FINISH 有两个动作（继续 / 关闭），但同一轮只可能站在一个画面上：
        // 清单里不该出现另一条（那正是"白扫"的来源）。两条都要验证的是"点完回活动中心"，与锚点无关。
        val need = BadgeScenes.neededAnchors(BadgeFlow.Step.CLAIM_FINISH, UiState.BADGE_CLAIM)
        assertEquals(
            setOf(BadgeAnchors.BADGE_CONTINUE, BadgeAnchors.BADGE_CLAIM_CLOSE),
            need,
        )
        assertEquals(
            setOf(BadgeAnchors.HALL_ACTIVITY),
            BadgeScenes.neededAnchors(BadgeFlow.Step.ENTER_ACTIVITY, UiState.HALL),
        )
    }

    @Test
    fun entryStepAtTheActivityCenterAsksForTheReadinessAnchors() {
        // 2026-10-22 两屏合并：第 1 步要靠"等不到「获取立牌」"判进错页 ⇒ 这两条入口锚点必须每轮定位，
        // 否则就绪判定永远为假、10 秒后必被踢回大厅（等于每进一次都多跑一个来回）
        val need = BadgeScenes.neededAnchors(BadgeFlow.Step.ENTER_ACTIVITY, UiState.ACTIVITY_CENTER)
        assertTrue(need.containsAll(BadgeScenes.readyAnchors))
        assertTrue("点返回要用它", BadgeAnchors.BADGE_ACTIVITY_EXIT in need)
    }

    @Test
    fun calibrationGapsNameTheMissingAnchorBeforeTheRunStars() {
        // 2026-10-22 用户误删「分享立牌」锚点：任务照跑、卡 20 秒才中止。现在启动前就要说清。
        // 这里给一份"只有获取立牌、没有分享立牌"的产物，缺口清单必须点名到用途。
        val complete = (BadgeScenes.requiredAnchorsByState.keys + BadgeScenes.requiredRegionAnchors.keys)
            .associateWith { state ->
                BadgeScenes.requiredAnchorsByState[state].orEmpty() +
                    BadgeScenes.requiredRegionAnchors[state].orEmpty()
            }
        val withoutShareEntry = complete.mapValues { (state, names) ->
            if (state == UiState.ACTIVITY_CENTER) names - BadgeAnchors.BADGE_SHARE_ENTRY else names
        }

        val gaps = BadgeScenes.calibrationGaps(
            anchorsOf = { state -> withoutShareEntry[state].orEmpty() },
            hasMarker = { true },
            anchorLabel = BadgeAnchors::purposeLabel,
        )

        assertTrue("缺口里必须点名「分享立牌」：$gaps", gaps.any { "分享立牌" in it })
        assertTrue("可以说清是哪一屏：$gaps", gaps.any { UiState.ACTIVITY_CENTER.label in it })
        assertEquals("只报一条（其余都齐）：$gaps", 1, gaps.size)
    }

    @Test
    fun calibrationGapsAlsoReportMissingMarkersAndOcrRegions() {
        val noMarkers = BadgeScenes.calibrationGaps(
            anchorsOf = { state -> (BadgeScenes.requiredAnchorsByState[state].orEmpty() +
                BadgeScenes.requiredRegionAnchors[state].orEmpty()) },
            hasMarker = { false },
        )
        assertTrue("每一条屏都要报缺标志：$noMarkers", noMarkers.size >= 4)
        assertTrue(noMarkers.any { "界面标志" in it })

        // 区域锚点（OCR）缺一条也要报：缺了读不出分 / 名称 / 分享码
        val noTextArea = BadgeScenes.calibrationGaps(
            anchorsOf = { state ->
                (BadgeScenes.requiredAnchorsByState[state].orEmpty() +
                    BadgeScenes.requiredRegionAnchors[state].orEmpty()) - BadgeAnchors.BADGE_SHARE_TEXT_AREA
            },
            hasMarker = { true },
            anchorLabel = BadgeAnchors::purposeLabel,
        )
        assertTrue("缺「立牌分享码」区域要报：$noTextArea", noTextArea.any { "立牌分享码" in it })

        // 产物齐 ⇒ 没有缺口（这条同时守住"别误报"）
        val none = BadgeScenes.calibrationGaps(
            anchorsOf = { state ->
                BadgeScenes.requiredAnchorsByState[state].orEmpty() +
                    BadgeScenes.requiredRegionAnchors[state].orEmpty()
            },
            hasMarker = { true },
        )
        assertEquals("产物齐全时不许报缺口：$none", emptyList<String>(), none)
    }

    /**
     * 就绪判据只看「分享立牌」（2026-10-23 用户纠正）。
     *
     * 依据：当日立牌**已提交**时「获取立牌」按钮**不显示** ⇒ 拿它当准入门会永远不就绪
     * （真机就是这样卡在第 1 步、10 秒后被踢回大厅再中止）。
     */
    @Test
    fun activityIsReadyAsSoonAsTheShareEntryIsLocated() {
        assertTrue(BadgeScenes.activityReady(setOf(BadgeAnchors.BADGE_SHARE_ENTRY)))
        // 只有「获取立牌」不算就绪：那个按钮可能本来就不在，不能反推页面已可交互
        assertFalse(BadgeScenes.activityReady(setOf(BadgeAnchors.BADGE_CLAIM_ENTRY)))
        assertFalse(BadgeScenes.activityReady(emptySet()))
    }
}
