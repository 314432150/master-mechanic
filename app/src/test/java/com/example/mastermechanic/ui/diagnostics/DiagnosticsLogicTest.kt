package com.example.mastermechanic.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「诊断」页的判据（M5-U6 第二段）：**只判"在 NFR-01 的 50 毫秒线内 / 已超线 / 还没有读数"**。
 *
 * 钉住两件事：① 50.0 就是日志里那个 NFR-01 阈值（2026-10-25 用户重述：20 → 50ms，且"不以更快为目标"；
 * **不另设"偏慢"档**——没有来源的档位不许出现）；
 * ② **没有读数就说"没有"**，不拿旧值冒充现在（会话结束 / 还没攒够一窗都是 UNKNOWN）。
 */
class DiagnosticsLogicTest {

    @Test
    fun theThresholdIsTheNfrOneAndNothingElse() {
        assertEquals(50.0, DiagnosticsLogic.NFR_P95_MS, 1e-9)
    }

    @Test
    fun noReadingMeansUnknown() {
        // null = 没攒够一窗（连续 100 帧）或会话已结束 ⇒ **不显示任何数字**
        assertEquals(DiagnosticsLogic.CostLevel.UNKNOWN, DiagnosticsLogic.costLevel(null))
    }

    @Test
    fun theBoundaryIsFifty() {
        assertEquals(DiagnosticsLogic.CostLevel.OK, DiagnosticsLogic.costLevel(50.0))
        assertEquals(DiagnosticsLogic.CostLevel.OVER, DiagnosticsLogic.costLevel(50.1))
        assertEquals(DiagnosticsLogic.CostLevel.OK, DiagnosticsLogic.costLevel(7.1))
        assertEquals(DiagnosticsLogic.CostLevel.OVER, DiagnosticsLogic.costLevel(186.4))
    }
}

