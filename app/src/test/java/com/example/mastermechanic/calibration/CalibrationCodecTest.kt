package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 标定产物编解码单测（T1-5a）：文本往返保真、确定性、非法输入严格拒绝，
 * 以及「模板提取 → 产物 → 文本往返 → 识别循环命中」的全链路。
 */
class CalibrationCodecTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)

    private fun template(seed: Long, width: Int = 12, height: Int = 9): Template =
        Template(width, height, SyntheticImages.pattern(width, height, seed))

    private fun sampleData(): CalibrationData = CalibrationData(
        frameWidth = 800,
        frameHeight = 600,
        params = params,
        signals = listOf(
            CalibrationData.SignalEntry(
                "farm_qr",
                SearchWindow(0.6, 0.7, 0.85, 0.95),
                listOf(template(101), template(102)),
            ),
            CalibrationData.SignalEntry(
                "hall_entry",
                SearchWindow(0.1, 0.2, 0.3, 0.4),
                listOf(template(103)),
            ),
        ),
        stateRules = listOf(
            CalibrationData.StateRule(UiState.FARM, listOf("farm_qr")),
            CalibrationData.StateRule(UiState.HALL, listOf("hall_entry")),
        ),
    )

    @Test
    fun roundTripPreservesAllFields() {
        val original = sampleData()
        val decoded = CalibrationCodec.decode(CalibrationCodec.encode(original))

        assertEquals(original.frameWidth, decoded.frameWidth)
        assertEquals(original.frameHeight, decoded.frameHeight)
        assertEquals(original.params, decoded.params)
        assertEquals(original.signals.size, decoded.signals.size)
        original.signals.zip(decoded.signals).forEach { (a, b) ->
            assertEquals(a.name, b.name)
            assertEquals(a.window, b.window)
            assertEquals(a.templates.size, b.templates.size)
            a.templates.zip(b.templates).forEach { (ta, tb) ->
                assertEquals(ta.width, tb.width)
                assertEquals(ta.height, tb.height)
                assertArrayEquals(ta.pixels, tb.pixels)
            }
        }
        assertEquals(
            original.stateRules.map { it.state to it.signalNames },
            decoded.stateRules.map { it.state to it.signalNames },
        )
    }

    @Test
    fun encodingIsDeterministic() {
        val data = sampleData()
        val text = CalibrationCodec.encode(data)
        assertEquals(text, CalibrationCodec.encode(data))
        assertEquals(text, CalibrationCodec.encode(CalibrationCodec.decode(text)))
    }

    @Test
    fun decodeToleratesCommentsAndBlankLines() {
        val text = "# 注释\n\n" + CalibrationCodec.encode(sampleData()) + "\n# 尾注\n"
        assertEquals(2, CalibrationCodec.decode(text).signals.size)
    }

    @Test
    fun rejectsWrongFormatOrVersion() {
        val good = CalibrationCodec.encode(sampleData())
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace("format=mm-calibration", "format=other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace("version=1", "version=2"))
        }
    }

    @Test
    fun rejectsUnknownKeyAndMissingStructure() {
        val good = CalibrationCodec.encode(sampleData())
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode("$good\nmystery=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode("$good\n没有等号的一行")
        }
    }

    @Test
    fun rejectsInvalidTemplatePayload() {
        val good = CalibrationCodec.encode(sampleData())
        // Base64 损坏
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replaceFirst(Regex("template=farm_qr\\|12\\|9\\|[A-Za-z0-9+/=]+"), "template=farm_qr|12|9|!!!!"))
        }
        // 像素数与声明尺寸不符
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replaceFirst(Regex("template=farm_qr\\|12\\|9\\|"), "template=farm_qr|12|11|"))
        }
    }

    @Test
    fun rejectsUndefinedReference() {
        val good = CalibrationCodec.encode(sampleData())
        // 规则引用不存在的信号
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace("state=FARM|farm_qr", "state=FARM|missing_signal"))
        }
        // 模板挂到未定义的信号
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(
                good.replace("template=farm_qr|", "template=ghost|"),
            )
        }
    }

    @Test
    fun rejectsUnknownStateOrUnknownAsTarget() {
        val good = CalibrationCodec.encode(sampleData())
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace("state=FARM|", "state=NO_SUCH_STATE|"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace("state=FARM|", "state=UNKNOWN|"))
        }
    }

    @Test
    fun rejectsDuplicateDefinitionsAndBadWindow() {
        val good = CalibrationCodec.encode(sampleData())
        val signalLine = "signal=farm_qr|0.600000,0.700000,0.850000,0.950000"
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace(signalLine, "$signalLine\n$signalLine"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace(signalLine, "signal=farm_qr|0.9,0.7,0.6,0.95"))
        }
    }

    @Test
    fun extractEncodeDecodeThenLoopMatches() {
        // 全链路（T1-5 主线）：RGBA 帧 → 模板提取 → 产物 → 文本往返 → 识别循环连续 2 轮进入「农场」
        val grayFrame = SyntheticImages.background(240, 180, seed = 201)
        val patch = SyntheticImages.pattern(16, 12, seed = 202)
        SyntheticImages.drawPattern(grayFrame, 50, 60, 16, 12, patch)
        val rgba = SyntheticRgba.fromGray(grayFrame.pixels, 240, 180)
        val extracted = TemplateExtractor.extract(rgba, 240, 180, 240 * 4, 50, 60, 66, 72)

        val data = CalibrationData(
            frameWidth = 240,
            frameHeight = 180,
            params = params,
            signals = listOf(
                CalibrationData.SignalEntry("farm_qr", SearchWindow(0.0, 0.0, 1.0, 1.0), listOf(extracted)),
            ),
            stateRules = listOf(CalibrationData.StateRule(UiState.FARM, listOf("farm_qr"))),
        )
        // T2-1：默认按 FR-01 弹窗阶段注入期望集合；本用例验编解码全链路 → 显式声明全集（演练口径）
        val loop = CalibrationCodec.decode(CalibrationCodec.encode(data)).toLoop(ExpectedSignals.ALL)

        assertEquals(1, loop.signalCount)
        val first = loop.process(grayFrame, isForeground = true)
        assertEquals(UiState.UNKNOWN, first.state)
        val second = loop.process(grayFrame, isForeground = true)
        assertEquals(UiState.FARM, second.transition!!.to)
    }
}
