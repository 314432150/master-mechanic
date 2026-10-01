package com.example.mastermechanic.ui

import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolAnchors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标定页「锚点用途」的开关与取值（T4-3a，纯逻辑）。
 *
 * 为什么值得单独钉住：用途名就是跑号代码要找的那个锚点名，**该选而未选**时如果放行，
 * 产物里会写出一条跑号永远找不到的记录（错误要到跑号时才暴露，那时排查成本高得多）。
 * 所以判据必须在标定当场就拦。
 *
 * 2026-09-19 晚：曾经还有一档「写入到」（让用户在已有元素里挑"覆盖谁"），已按用户口径移除——
 * 有用途的锚点**默认覆盖同用途**，其余**默认新增**，不再让人选。
 */
class CalibrationPurposeGateTest {

    private val anchorOnly = setOf(SignalRole.ANCHOR)
    private val markerOnly = setOf(SignalRole.MARKER)

    @Test
    fun purposeIsRequiredWhenTheScreenHasClickableControls() {
        assertTrue(requiresAnchorPurpose(UiState.HALL, anchorOnly))
        assertTrue(requiresAnchorPurpose(UiState.LAUNCH_PAGE, anchorOnly))
        assertTrue(requiresAnchorPurpose(UiState.FARM, anchorOnly))
        // 2026-09-21 合并：好友的农场不再是独立的用途表（与 FARM 共用），
        // 所以勾锚点时**不会**要求选用途——旧产物里写着 FRIEND_FARM 的规则照样能用
        assertFalse(requiresAnchorPurpose(UiState.FRIEND_FARM, anchorOnly))
        // 退出登录途经的两个界面各只有一个可点控件，同样按用途取名（否则代码按 settings_logout 找不到）
        assertTrue(requiresAnchorPurpose(UiState.HALL_SETTINGS, anchorOnly))
        assertTrue(requiresAnchorPurpose(UiState.LOGOUT_CONFIRM, anchorOnly))
    }

    @Test
    fun purposeIsNotRequiredForMarkersOrScreensWithoutControls() {
        // 只写标志时不涉及锚点名
        assertFalse(requiresAnchorPurpose(UiState.HALL, markerOnly))
        // 活动弹窗只有一个关闭控件 → 沿用旧的 xxx_anchor 自动名（既有产物不动）
        assertFalse(requiresAnchorPurpose(UiState.ACTIVITY_POPUP, anchorOnly))
        assertFalse(requiresAnchorPurpose(UiState.UNKNOWN, anchorOnly))
        // 归属状态还没选：先报"选归属状态"，不必再多报一条
        assertFalse(requiresAnchorPurpose(null, anchorOnly))
    }

    @Test
    fun singlePurposeScreenNeedsNoTapFromTheUser() {
        // 没有第二种选择时点一下纯属多余：直接采用那一个
        assertEquals(
            PatrolAnchors.SETTINGS_LOGOUT,
            effectiveAnchorPurpose(UiState.HALL_SETTINGS, anchorOnly, chosen = null),
        )
        assertEquals(
            PatrolAnchors.LOGOUT_CONFIRM_OK,
            effectiveAnchorPurpose(UiState.LOGOUT_CONFIRM, anchorOnly, chosen = null),
        )
    }

    @Test
    fun multiPurposeScreenUsesWhateverTheUserPicked() {
        assertEquals(PatrolAnchors.HALL_FARM, effectiveAnchorPurpose(UiState.HALL, anchorOnly, "hall_farm"))
        assertEquals(
            PatrolAnchors.LAUNCH_LOGIN,
            effectiveAnchorPurpose(UiState.LAUNCH_PAGE, anchorOnly, "launch_login"),
        )
    }

    @Test
    fun multiPurposeScreenWithoutAChoiceWritesNothing() {
        // 关键的一条：没选用途 → 不能写（否则会退回序号名，跑号时按名找不到）
        assertNull(effectiveAnchorPurpose(UiState.HALL, anchorOnly, chosen = null))
        assertNull(effectiveAnchorPurpose(UiState.FARM, anchorOnly, chosen = null))
    }

    @Test
    fun withoutTheAnchorRoleThereIsNoPurpose() {
        assertNull(effectiveAnchorPurpose(UiState.HALL, markerOnly, chosen = "hall_farm"))
        assertNull(effectiveAnchorPurpose(UiState.HALL, emptySet(), chosen = "hall_farm"))
    }

    @Test
    fun screensWithoutPurposesFallBackToTheOldNaming() {
        // 返回 null = 不走用途 → 写库时落到该界面的元素槽位（`activity_popup_e1`）
        assertNull(effectiveAnchorPurpose(UiState.ACTIVITY_POPUP, anchorOnly, chosen = null))
        assertNull(effectiveAnchorPurpose(null, anchorOnly, chosen = null))
    }

}
