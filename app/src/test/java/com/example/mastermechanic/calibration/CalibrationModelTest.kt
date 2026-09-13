package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标定产物模型单测（T1-5a）：构造守卫（构造即合法）与「产物 → 识别循环」接入。
 */
class CalibrationModelTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)
    private val fullWindow = SearchWindow(0.0, 0.0, 1.0, 1.0)

    private fun template(seed: Long): Template =
        Template(10, 8, SyntheticImages.pattern(10, 8, seed))

    private fun signal(name: String = "farm_qr"): CalibrationData.SignalEntry =
        CalibrationData.SignalEntry(name, fullWindow, listOf(template(1)))

    private fun data(
        signals: List<CalibrationData.SignalEntry> = listOf(signal()),
        rules: List<CalibrationData.StateRule> = listOf(CalibrationData.StateRule(UiState.FARM, listOf("farm_qr"))),
    ): CalibrationData = CalibrationData(100, 80, params, signals, rules)

    @Test
    fun rejectsStructurallyInvalidData() {
        // 空信号集
        assertThrows(IllegalArgumentException::class.java) { data(signals = emptyList(), rules = emptyList()) }
        // 信号名重复
        assertThrows(IllegalArgumentException::class.java) { data(signals = listOf(signal(), signal())) }
        // 规则引用不存在的信号
        assertThrows(IllegalArgumentException::class.java) {
            data(rules = listOf(CalibrationData.StateRule(UiState.HALL, listOf("ghost"))))
        }
        // 同一状态两条规则
        assertThrows(IllegalArgumentException::class.java) {
            data(
                signals = listOf(signal(), signal("hall_entry")),
                rules = listOf(
                    CalibrationData.StateRule(UiState.FARM, listOf("farm_qr")),
                    CalibrationData.StateRule(UiState.FARM, listOf("hall_entry")),
                ),
            )
        }
    }

    @Test
    fun rejectsRoleMismatchAndRecordWithoutHomeState() {
        val anchor = CalibrationData.SignalEntry(
            "popup_close_x",
            fullWindow,
            listOf(template(2)),
            SignalRole.ANCHOR,
        )
        // 锚点被写进 state= 行（角色不符）
        assertThrows(IllegalArgumentException::class.java) {
            data(
                signals = listOf(anchor),
                rules = listOf(CalibrationData.StateRule(UiState.ACTIVITY_POPUP, listOf("popup_close_x"))),
            )
        }
        // 标志被写进 anchor= 行（角色不符）
        assertThrows(IllegalArgumentException::class.java) {
            data(rules = listOf(CalibrationData.StateRule(UiState.ACTIVITY_POPUP, emptyList(), listOf("farm_qr"))))
        }
        // 锚点没有归属状态 → 永远不会被启用，构造即拒绝
        assertThrows(IllegalArgumentException::class.java) { data(signals = listOf(anchor), rules = emptyList()) }
    }

    @Test
    fun allowsMarkerWithoutRuleForOfflineReplay() {
        // 离线回放工具构造"只有信号、没有规则"的产物（T1-13 起沿用）：标志允许暂不归属
        val product = data(rules = emptyList())

        assertEquals(1, product.toLoop(ExpectedSignals.ALL).signalCount)
        assertNull(product.stateOf("farm_qr"))
    }

    @Test
    fun anchorOnlyRuleIsAllowedAndDoesNotFeedLoop() {
        val product = CalibrationData(
            frameWidth = 100,
            frameHeight = 80,
            params = params,
            signals = listOf(
                signal(),
                CalibrationData.SignalEntry(
                    "popup_close_x",
                    fullWindow,
                    listOf(template(2)),
                    SignalRole.ANCHOR,
                ),
            ),
            stateRules = listOf(
                CalibrationData.StateRule(UiState.FARM, listOf("farm_qr")),
                CalibrationData.StateRule(UiState.ACTIVITY_POPUP, emptyList(), listOf("popup_close_x")),
            ),
        )

        // 锚点不进识别循环；按当前状态启用只认自己状态声明的锚点
        assertEquals(1, product.toLoop(ExpectedSignals.ALL).signalCount)
        assertEquals(listOf("popup_close_x"), product.anchorsFor(UiState.ACTIVITY_POPUP))
        assertEquals(emptyList<String>(), product.anchorsFor(UiState.HALL))
        assertEquals(listOf("popup_close_x"), product.anchorSpecs(UiState.ACTIVITY_POPUP).map { it.name })
    }

    @Test
    fun rejectsInvalidSignalNames() {
        listOf("bad|name", "bad,name", "bad=name", "bad\nname", "", " ").forEach { name ->
            assertFalse("应拒绝：$name", CalibrationData.isValidName(name))
        }
        assertTrue(CalibrationData.isValidName("farm_qr-1"))
        assertFalse(CalibrationData.isValidName("x".repeat(41)))
    }

    @Test
    fun toLoopExposesSignalCountAndStartsUnknown() {
        val loop = data().toLoop()
        assertEquals(1, loop.signalCount)
        assertEquals(UiState.UNKNOWN, loop.state)
    }

    @Test
    fun toLoopMatchesSyntheticTargetAndEntersState() {
        // 产物 → 循环 → 合成画面命中（模板即从该画面裁剪，标定语义的最小闭环）
        val image = SyntheticImages.background(200, 150, seed = 301)
        val patch = SyntheticImages.pattern(14, 11, seed = 302)
        SyntheticImages.drawPattern(image, 40, 50, 14, 11, patch)
        val data = CalibrationData(
            frameWidth = 200,
            frameHeight = 150,
            params = params,
            signals = listOf(
                CalibrationData.SignalEntry("farm_qr", fullWindow, listOf(SyntheticImages.crop(image, 40, 50, 14, 11))),
            ),
            stateRules = listOf(CalibrationData.StateRule(UiState.FARM, listOf("farm_qr"))),
        )

        // T2-1：默认按 FR-01 弹窗阶段注入期望集合；本用例验模板闭环 → 显式声明全集（演练口径）
        val loop = data.toLoop(ExpectedSignals.ALL)
        assertEquals(UiState.UNKNOWN, loop.process(image, isForeground = true).state)
        assertEquals(UiState.FARM, loop.process(image, isForeground = true).transition!!.to)
    }
}
