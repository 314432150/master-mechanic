package com.example.mastermechanic.decision

import com.example.mastermechanic.calibration.CalibrationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归属状态默认信号名单测（T1-5e）：选中状态自动填入信号名称框的取值口径。
 *
 * 默认名仅供标定输入便利（可随后手改），不参与识别；须为非空合法信号名且互不相同。
 * FR-02 已移除（2026-09-13）→ 候选状态不含新手引导 / 新手大厅，声明首位为 FR-01 的活动弹窗。
 */
class UiStateDefaultNameTest {

    @Test
    fun candidateDefaultNamesAreValidAndNonBlank() {
        val candidates = UiState.entries.filter { it.isCandidate }
        assertEquals(UiState.entries.size - 1, candidates.size)
        candidates.forEach { state ->
            assertTrue(
                "状态 ${state.name} 的默认名不得为空",
                state.defaultSignalName.isNotBlank(),
            )
            assertTrue(
                "状态 ${state.name} 的默认名须为合法信号名",
                CalibrationData.isValidName(state.defaultSignalName),
            )
        }
    }

    @Test
    fun candidateDefaultNamesAreDistinct() {
        val names = UiState.entries.filter { it.isCandidate }.map { it.defaultSignalName }
        assertEquals("默认名不得重复", names.size, names.toSet().size)
    }

    @Test
    fun unknownHasNoDefaultName() {
        assertEquals("", UiState.UNKNOWN.defaultSignalName)
    }

    @Test
    fun activityPopupStaysFirstAsHighestPriority() {
        // 声明顺序 = 多状态同时命中时的候选优先级：FR-01 活动弹窗必须在首位（§2.1）
        assertEquals(UiState.ACTIVITY_POPUP, UiState.entries.first())
    }

    @Test
    fun removedTutorialStatesAreAbsent() {
        // FR-02 已移除（2026-09-13）：标定页归属状态列表不得再出现新手引导 / 新手大厅
        val labels = UiState.entries.map { it.label }
        assertTrue("不得含「新手引导」", "新手引导" !in labels)
        assertTrue("不得含「新手大厅」", "新手大厅" !in labels)
        val names = UiState.entries.map { it.defaultSignalName }
        assertTrue("不得含 tutorial 默认名", names.none { it.startsWith("tutorial_") })
    }

    @Test
    fun launchPageKeepsFieldProvenName() {
        // T1-5 真机标定产物既有约定（launch_start），默认名保持一致
        assertEquals("launch_start", UiState.LAUNCH_PAGE.defaultSignalName)
    }
}
