package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 标定流程组装单测（T1-5b，纯逻辑部分）：选区 → 搜索窗口推算、草稿 → 产物组装。
 */
class CalibrationDraftTest {

    @Before
    fun resetDraft() {
        CalibrationDraft.clear()
    }

    @After
    fun cleanupDraft() {
        CalibrationDraft.clear()
    }

    private fun template(seed: Long): Template =
        Template(10, 8, SyntheticImages.pattern(10, 8, seed))

    private fun addDraft(name: String, state: UiState) {
        CalibrationDraft.signals.add(
            CalibrationDraft.DraftSignal(
                state,
                CalibrationData.SignalEntry(name, SearchWindow(0.0, 0.0, 1.0, 1.0), listOf(template(name.hashCode().toLong()))),
            ),
        )
    }

    @Test
    fun windowExpandsBySelectionSizeAndClampsToFrame() {
        val window = SelectionWindow.forSelection(1000, 800, 100, 100, 200, 150)
        assertEquals(0.0, window.left, 1e-9)
        assertEquals(0.0625, window.top, 1e-9) // (100-50)/800
        assertEquals(0.3, window.right, 1e-9) // (200+100)/1000
        assertEquals(0.25, window.bottom, 1e-9) // (150+50)/800

        // 贴角选区：外扩被裁剪到帧界
        val corner = SelectionWindow.forSelection(1000, 800, 0, 0, 50, 40)
        assertEquals(0.0, corner.left, 1e-9)
        assertEquals(0.0, corner.top, 1e-9)
        assertEquals(0.1, corner.right, 1e-9)
        assertEquals(0.1, corner.bottom, 1e-9)
    }

    @Test
    fun windowAlwaysCoversSelectionInPixels() {
        val cases = listOf(
            intArrayOf(100, 100, 200, 150),
            intArrayOf(0, 0, 50, 40),
            intArrayOf(950, 750, 1000, 800),
            intArrayOf(400, 300, 410, 310),
        )
        cases.forEach { (x0, y0, x1, y1) ->
            val bounds = SelectionWindow.forSelection(1000, 800, x0, y0, x1, y1).pixelBounds(1000, 800)
            assertTrue("左界应覆盖：$x0", bounds.x0 <= x0)
            assertTrue("上界应覆盖：$y0", bounds.y0 <= y0)
            assertTrue("右界应覆盖：$x1", bounds.x1 >= x1)
            assertTrue("下界应覆盖：$y1", bounds.y1 >= y1)
        }
    }

    @Test
    fun selectionWindowRejectsInvalidSelection() {
        assertThrows(IllegalArgumentException::class.java) {
            SelectionWindow.forSelection(1000, 800, 200, 100, 200, 150)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionWindow.forSelection(1000, 800, 0, 0, 1001, 150)
        }
    }

    @Test
    fun draftGroupsSignalsByStateIntoRules() {
        addDraft("hall_entry", UiState.HALL)
        addDraft("hall_rank", UiState.HALL)
        addDraft("farm_qr", UiState.FARM)

        val data = CalibrationDraft.toData(1000, 800)
        assertEquals(3, data.signals.size)
        assertEquals(2, data.stateRules.size)
        val hall = data.stateRules.first { it.state == UiState.HALL }
        assertEquals(listOf("hall_entry", "hall_rank"), hall.signalNames)
        assertEquals(listOf("farm_qr"), data.stateRules.first { it.state == UiState.FARM }.signalNames)
    }

    @Test
    fun draftParamTextRoundTripAndRejection() {
        assertNotNull(CalibrationDraft.parseParams())
        CalibrationDraft.matchThresholdText = "abc"
        assertNull(CalibrationDraft.parseParams())
        CalibrationDraft.matchThresholdText = "1.5" // 超出 (0,1]
        assertNull(CalibrationDraft.parseParams())
        CalibrationDraft.matchThresholdText = "0.9"
        CalibrationDraft.peakMinDistanceText = "0"
        assertNull(CalibrationDraft.parseParams())
    }

    @Test
    fun draftToDataFailsWhenParamsInvalid() {
        addDraft("farm_qr", UiState.FARM)
        CalibrationDraft.ambiguityMarginText = "x"
        assertThrows(IllegalArgumentException::class.java) { CalibrationDraft.toData(1000, 800) }
    }

    @Test
    fun draftClearResetsSignalsAndParams() {
        addDraft("farm_qr", UiState.FARM)
        CalibrationDraft.matchThresholdText = "0.7"
        CalibrationDraft.clear()
        assertTrue(CalibrationDraft.signals.isEmpty())
        assertEquals("0.85", CalibrationDraft.matchThresholdText)
    }
}
