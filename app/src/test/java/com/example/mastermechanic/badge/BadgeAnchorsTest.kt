package com.example.mastermechanic.badge

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BadgeAnchors]（临时需求 temp/badge-share-1021）：立牌流程锚点契约的自洽性。
 *
 * 与 `PatrolAnchorsTest` 同一口径：用途 = 实际要用的锚点（同一份事实）、
 * 名字合法、标签非空且同屏内不重复。巡查处那几条大不变量合并 `BadgeAnchors.usageByState`
 * 后一起钉（见 `PatrolAnchorsTest.purposesCoverExactlyTheAnchorsUsedOnEachState`）。
 */
class BadgeAnchorsTest {

    @Test
    fun usageCoversExactlyThePurposesPerState() {
        for ((state, used) in BadgeAnchors.usageByState) {
            assertEquals(
                "$state 的用途必须与实际要用的锚点一一对齐",
                used,
                BadgeAnchors.purposesFor(state).map { it.name }.toSet(),
            )
        }
    }

    @Test
    fun purposeNamesAreValidIdsAndLabelsAreDistinctWithinAState() {
        for (state in UiState.entries) {
            val purposes = BadgeAnchors.purposesFor(state)
            assertEquals(
                "$state 的用途标签不能重",
                purposes.size,
                purposes.map { it.label }.distinct().size,
            )
            for (purpose in purposes) {
                assertTrue(purpose.name, CalibrationData.isValidId(purpose.name))
                assertTrue(purpose.label, purpose.label.isNotBlank())
            }
        }
    }

    @Test
    fun nonBadgeStatesHaveNoBadgePurposes() {
        // 立牌用途只许出现在流程那几屏 + 借用的 HALL（活动入口）
        val allowed = BadgeAnchors.usageByState.keys
        for (state in UiState.entries) {
            if (state in allowed) continue
            assertEquals("$state 不该有立牌用途", emptyList<String>(), BadgeAnchors.purposesFor(state))
        }
    }

    @Test
    fun shareTextAreaIsRegionOnly() {
        // 分享码文本区只圈区域（文字识别输入），不是可点击目标
        assertEquals(
            setOf(BadgeAnchors.BADGE_SHARE_TEXT_AREA),
            BadgeAnchors.regionAnchors,
        )
    }
}
