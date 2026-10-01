package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
                id = "farm_qr",
                window = SearchWindow(0.6, 0.7, 0.85, 0.95),
                templates = listOf(template(101), template(102)),
                // v3 的两个新字段：用途（给机器看）与备注（给人看，可空）
                purpose = null,
                note = "农场里的二维码",
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
            assertEquals(a.id, b.id)
            assertEquals(a.role, b.role)
            assertEquals(a.purpose, b.purpose)
            assertEquals(a.note, b.note)
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
        // 只读 v3 / v4，故用一个不存在的版本来验证"版本不受支持"
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace("version=4", "version=99"))
        }
    }

    @Test
    fun olderVersionsAreRefusedWithAnActionableMessage() {
        // 2026-09-19 用户口径：旧产物不再兼容（要删掉重标）→ 报错必须说清"怎么办"，
        // 不能只甩一句"版本不受支持"让人猜
        val good = CalibrationCodec.encode(sampleData())
        val error = assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace("version=4", "version=2"))
        }
        assertTrue("应提示处理办法，实际：${error.message}", error.message.orEmpty().contains("删除产物"))
    }

    @Test
    fun roundTripKeepsPurposeAndNote() {
        val original = CalibrationData(
            frameWidth = 800,
            frameHeight = 600,
            params = params,
            signals = listOf(
                CalibrationData.SignalEntry(
                    id = "hall_farm",
                    window = SearchWindow(0.7, 0.02, 0.95, 0.08),
                    templates = listOf(template(301)),
                    role = SignalRole.ANCHOR,
                    purpose = "hall_farm",
                    note = "大厅的农场入口",
                ),
            ),
            stateRules = listOf(CalibrationData.StateRule(UiState.HALL, emptyList(), listOf("hall_farm"))),
        )

        val decoded = CalibrationCodec.decode(CalibrationCodec.encode(original))

        assertEquals("hall_farm", decoded.purposeOf("hall_farm"))
        assertEquals("大厅的农场入口", decoded.noteOf("hall_farm"))
        assertEquals("hall_farm", decoded.anchorIdFor(UiState.HALL, "hall_farm"))
        // 文本里能看到两列（v4 六段式）：ID|角色|窗口|用途|归属好友|备注
        assertTrue(CalibrationCodec.encode(original).contains("signal=hall_farm|anchor|"))
        assertTrue(CalibrationCodec.encode(original).contains("|hall_farm||大厅的农场入口"))
    }

    @Test
    fun roundTripKeepsTheFriendNameEvenWithSpecialCharacters() {
        // 2026-09-21 农场合并：好友头像模板要记住"这是谁的"，而好友名可能含空格与特殊字符
        // （真机实例 `Boss~~喵`）—— 所以它**不能**当用途名，只能单列一段
        val original = CalibrationData(
            frameWidth = 800,
            frameHeight = 600,
            params = params,
            signals = listOf(
                CalibrationData.SignalEntry(
                    id = "friend_avatar_1",
                    window = SearchWindow(0.02, 0.02, 0.10, 0.08),
                    templates = listOf(template(401)),
                    role = SignalRole.ANCHOR,
                    purpose = "friend_avatar",
                    friend = "Boss~~喵",
                    note = "好友头像",
                ),
                CalibrationData.SignalEntry(
                    id = "friend_avatar_2",
                    window = SearchWindow(0.02, 0.02, 0.10, 0.08),
                    templates = listOf(template(402)),
                    role = SignalRole.ANCHOR,
                    purpose = "friend_avatar",
                    friend = "折翼白鸽",
                    note = "好友头像",
                ),
            ),
            stateRules = listOf(
                CalibrationData.StateRule(
                    UiState.FARM,
                    emptyList(),
                    listOf("friend_avatar_1", "friend_avatar_2"),
                ),
            ),
        )

        val decoded = CalibrationCodec.decode(CalibrationCodec.encode(original))

        // 多位好友共存（同用途、不同归属）：这正是"用途名 + 归属好友名"两段分开的意义
        assertEquals("friend_avatar_1", decoded.anchorIdFor(UiState.FARM, "friend_avatar", "Boss~~喵"))
        assertEquals("friend_avatar_2", decoded.anchorIdFor(UiState.FARM, "friend_avatar", "折翼白鸽"))
        // 全等才取：名字差一点就是"这位没标过"，不挑最像的（红线 3）
        assertNull(decoded.anchorIdFor(UiState.FARM, "friend_avatar", "Boss~喵"))
        assertNull(decoded.anchorIdFor(UiState.FARM, "friend_avatar", "没标过的人"))
    }

    @Test
    fun v3ArtifactsAreStillReadableAndHaveNoFriendName() {
        // v4 只是多了一段可空的「归属好友」⇒ v3 产物**照样能读**（缺这一段 = 没写归属好友），
        // 不必为了新增字段让用户把已标定的内容全部重做
        val v4 = CalibrationCodec.encode(sampleData())
        val v3 = v4.replace("version=4", "version=3").let { text ->
            // 去掉「归属好友」那一段，还原成 v3 的五段式
            text.replace(Regex("signal=([^|]+)\\|([^|]+)\\|([^|]+)\\|([^|]*)\\|([^|]*)\\|([^\\n]*)")) { m ->
                "signal=${m.groupValues[1]}|${m.groupValues[2]}|${m.groupValues[3]}|" +
                    "${m.groupValues[4]}|${m.groupValues[6]}"
            }
        }

        val decoded = CalibrationCodec.decode(v3)
        assertEquals(2, decoded.signals.size)
        assertTrue("v3 读出来的记录没有归属好友名", decoded.signals.all { it.friend == null })
    }

    @Test
    fun rejectsWrongSegmentCountAndUnknownRole() {
        val good = CalibrationCodec.encode(sampleData())
        // 三段式（旧格式）直接拒绝：v4 只认六段（v3 认五段，见 v3ArtifactsAreStillReadable...）
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(
                good.replace(
                    Regex("signal=([^|]+)\\|marker\\|([^|]+)\\|[^|]*\\|[^\\n]*"),
                    "signal=$1|marker|$2",
                ),
            )
        }
        // 未知角色 token
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace("signal=farm_qr|marker|", "signal=farm_qr|ghost|"))
        }
    }

    @Test
    fun roundTripPreservesRolesAndAnchorState() {
        val original = CalibrationData(
            frameWidth = 800,
            frameHeight = 600,
            params = params,
            signals = listOf(
                CalibrationData.SignalEntry(
                    "popup_close",
                    SearchWindow(0.6, 0.55, 0.95, 0.6),
                    listOf(template(201)),
                ),
                CalibrationData.SignalEntry(
                    "popup_close_x",
                    SearchWindow(0.8, 0.4, 0.9, 0.45),
                    listOf(template(202)),
                    SignalRole.ANCHOR,
                ),
            ),
            stateRules = listOf(
                CalibrationData.StateRule(
                    UiState.ACTIVITY_POPUP,
                    listOf("popup_close"),
                    listOf("popup_close_x"),
                ),
            ),
        )

        val decoded = CalibrationCodec.decode(CalibrationCodec.encode(original))

        assertEquals(SignalRole.MARKER, decoded.roleOf("popup_close"))
        assertEquals(SignalRole.ANCHOR, decoded.roleOf("popup_close_x"))
        assertEquals(UiState.ACTIVITY_POPUP, decoded.stateOf("popup_close_x"))
        // 按当前状态启用：只有该状态声明的锚点在册，别的状态一律空（不接受兜底）
        assertEquals(listOf("popup_close_x"), decoded.anchorsFor(UiState.ACTIVITY_POPUP))
        assertEquals(emptyList<String>(), decoded.anchorsFor(UiState.HALL))
        assertEquals(listOf("popup_close_x"), decoded.anchorSpecs(UiState.ACTIVITY_POPUP).map { it.name })
        // 锚点不进识别循环（不参与状态判定与期望集合）
        assertEquals(listOf("popup_close"), decoded.markerSpecs().map { it.name })
        assertEquals(1, decoded.toLoop(ExpectedSignals.ALL).signalCount)
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
            CalibrationCodec.decode(
                good.replaceFirst(
                    Regex("template=farm_qr\\|marker\\|12\\|9\\|[A-Za-z0-9+/=]+"),
                    "template=farm_qr|marker|12|9|!!!!",
                ),
            )
        }
        // 像素数与声明尺寸不符
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(
                good.replaceFirst(
                    Regex("template=farm_qr\\|marker\\|12\\|9\\|"),
                    "template=farm_qr|marker|12|11|",
                ),
            )
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
        // v4 六段式：ID|角色|窗口|用途|归属好友|备注（后三段本篇为空 → 结尾三个空段）
        val signalLine = "signal=farm_qr|marker|0.600000,0.700000,0.850000,0.950000|||"
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace(signalLine, "$signalLine\n$signalLine"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace(signalLine, "signal=farm_qr|marker|0.9,0.7,0.6,0.95||"))
        }
        // 备注里的竖线会把行读坏 → 严格拒绝（不猜）
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCodec.decode(good.replace(signalLine, "signal=farm_qr|marker|0.6,0.7,0.85,0.95||a|b"))
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
