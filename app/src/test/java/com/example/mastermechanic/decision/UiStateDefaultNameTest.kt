package com.example.mastermechanic.decision

import com.example.mastermechanic.calibration.CalibrationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归属状态默认信号名单测（T1-5e）：选中状态自动填入信号名称框的取值口径。
 *
 * 默认名仅供标定输入便利（可随后手改），不参与识别；须为非空合法信号名且互不相同，
 * 拆分后的「新手大厅」「新手引导」各自独立命名。
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
    fun tutorialStatesAreSplitWithDistinctNames() {
        assertEquals("tutorial_hall", UiState.TUTORIAL_HALL.defaultSignalName)
        assertEquals("tutorial_guide", UiState.TUTORIAL_GUIDE.defaultSignalName)
        // 声明顺序 = 多状态同时命中时的候选优先级：新手大厅在前
        assertTrue(UiState.TUTORIAL_HALL.ordinal < UiState.TUTORIAL_GUIDE.ordinal)
    }

    @Test
    fun launchPageKeepsFieldProvenName() {
        // T1-5 真机标定产物既有约定（launch_start），默认名保持一致
        assertEquals("launch_start", UiState.LAUNCH_PAGE.defaultSignalName)
    }
}
