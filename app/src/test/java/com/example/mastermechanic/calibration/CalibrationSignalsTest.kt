package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标定产物直接写入单测（T1-5l，纯逻辑）：覆盖写入 / 覆盖同名 / 状态重归属 / 几何校验 /
 * 删除与删空 / 参数编辑 / 参数解析，同状态多条记录的命名（T2-2 方案 A），
 * 以及随草稿模型迁入的选区 → 搜索窗口策略。
 *
 * 全部以帧比例与帧像素表达，不含任何设备绑定值。
 */
class CalibrationSignalsTest {

    private val defaultParams = MatchParams(0.85, 0.10, 5)

    private fun template(w: Int = 10, h: Int = 8, seed: Long = 1L): Template =
        Template(w, h, SyntheticImages.pattern(w, h, seed))

    private fun window(left: Double = 0.1, top: Double = 0.2, right: Double = 0.5, bottom: Double = 0.6) =
        SearchWindow(left, top, right, bottom)

    private fun upsert(
        current: CalibrationData?,
        name: String,
        state: UiState,
        window: SearchWindow = window(),
        template: Template = template(),
        params: MatchParams = defaultParams,
        frameWidth: Int = 1000,
        frameHeight: Int = 800,
        role: SignalRole = SignalRole.MARKER,
    ): CalibrationData = CalibrationSignals.upsert(
        current = current,
        name = name,
        state = state,
        window = window,
        template = template,
        params = params,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        role = role,
    )

    // --- 角色（T2-3）：标志与锚点分流，同一状态可两者都有 ---

    @Test
    fun upsertKeepsMarkersAndAnchorsInSeparateRuleLists() {
        var data = upsert(null, "popup_close", UiState.ACTIVITY_POPUP)
        data = upsert(data, "popup_close_x", UiState.ACTIVITY_POPUP, role = SignalRole.ANCHOR)

        val rule = data.stateRules.single()
        assertEquals(UiState.ACTIVITY_POPUP, rule.state)
        assertEquals(listOf("popup_close"), rule.signalNames)
        assertEquals(listOf("popup_close_x"), rule.anchorNames)
        assertEquals(listOf("popup_close_x"), data.anchorsFor(UiState.ACTIVITY_POPUP))
        // 锚点不参与状态判定：识别循环只吃标志
        assertEquals(listOf("popup_close"), data.markerSpecs().map { it.name })
        assertEquals(1, data.toLoop(ExpectedSignals.ALL).signalCount)
    }

    @Test
    fun upsertReassignsRoleOfSameName() {
        val asMarker = upsert(null, "popup_close_x", UiState.ACTIVITY_POPUP)
        val asAnchor = upsert(asMarker, "popup_close_x", UiState.ACTIVITY_POPUP, role = SignalRole.ANCHOR)

        assertEquals(SignalRole.ANCHOR, asAnchor.roleOf("popup_close_x"))
        assertEquals(emptyList<String>(), asAnchor.stateRules.single().signalNames)
        assertEquals(listOf("popup_close_x"), asAnchor.anchorNamesForTest())
    }

    @Test
    fun defaultNameForDistinguishesAnchorFromMarker() {
        assertEquals(
            "popup_close",
            CalibrationSignals.defaultNameFor(UiState.ACTIVITY_POPUP, SignalRole.MARKER),
        )
        assertEquals(
            "popup_close_anchor",
            CalibrationSignals.defaultNameFor(UiState.ACTIVITY_POPUP, SignalRole.ANCHOR),
        )
    }

    @Test
    fun markerAndAnchorOfSameStateAreWrittenAsSeparateRecords() {
        // 同一元素两种角色 = 两条记录（§2.1）：靠角色默认名分流，互不覆盖
        var data = upsert(
            null,
            CalibrationSignals.defaultNameFor(UiState.ACTIVITY_POPUP, SignalRole.MARKER),
            UiState.ACTIVITY_POPUP,
        )
        data = upsert(
            data,
            CalibrationSignals.defaultNameFor(UiState.ACTIVITY_POPUP, SignalRole.ANCHOR),
            UiState.ACTIVITY_POPUP,
            role = SignalRole.ANCHOR,
        )

        assertEquals(listOf("popup_close"), data.stateRules.single().signalNames)
        assertEquals(listOf("popup_close_anchor"), data.anchorNamesForTest())
        assertEquals(SignalRole.ANCHOR, data.roleOf("popup_close_anchor"))
    }

    @Test
    fun anchorDefaultNameStillAvoidsCollisionWithExistingRecords() {
        // 锚点默认名也可能已被占用（例如先误标成标志）→ 仍走序号追加，保证产物内名称唯一
        val data = upsert(null, "popup_close_anchor", UiState.ACTIVITY_POPUP)
        assertEquals(
            "popup_close_anchor2",
            CalibrationSignals.nextName(
                data,
                CalibrationSignals.defaultNameFor(UiState.ACTIVITY_POPUP, SignalRole.ANCHOR),
            ),
        )
    }

    /** 便利断言：唯一规则的锚点列表（避免测试里反复写 `stateRules.single().anchorNames`）。 */
    private fun CalibrationData.anchorNamesForTest(): List<String> = stateRules.single().anchorNames

    // --- 一次框选写多个角色（T2-3g）：同一个元素既是标志又是锚点 → 一次写两条记录 ---

    @Test
    fun orderedRolesFollowsMarkerThenAnchor() {
        // 顺序固定（标志 → 锚点）：界面排布、消息文本与命名分配都依赖它，不能随集合迭代顺序变
        assertEquals(
            listOf(SignalRole.MARKER, SignalRole.ANCHOR),
            CalibrationSignals.orderedRoles(setOf(SignalRole.ANCHOR, SignalRole.MARKER)),
        )
        assertEquals(listOf(SignalRole.ANCHOR), CalibrationSignals.orderedRoles(setOf(SignalRole.ANCHOR)))
        assertEquals(emptyList<SignalRole>(), CalibrationSignals.orderedRoles(emptySet()))
    }

    @Test
    fun namesForBothRolesGivesOneNamePerRole() {
        val planned = CalibrationSignals.namesFor(null, UiState.ACTIVITY_POPUP, SignalRole.entries.toSet())
        assertEquals(
            listOf(SignalRole.MARKER to "popup_close", SignalRole.ANCHOR to "popup_close_anchor"),
            planned,
        )
    }

    @Test
    fun namesForAvoidsExistingNamesAndNamesWithinTheBatch() {
        val occupiedMarker = upsert(null, "popup_close", UiState.ACTIVITY_POPUP)
        assertEquals(
            listOf(
                SignalRole.MARKER to "popup_close2",
                SignalRole.ANCHOR to "popup_close_anchor",
            ),
            CalibrationSignals.namesFor(occupiedMarker, UiState.ACTIVITY_POPUP, SignalRole.entries.toSet()),
        )

        // 连锚点默认名也被占用（例如先误标成标志）：两条各自让位，批内不重名
        val both = upsert(occupiedMarker, "popup_close_anchor", UiState.ACTIVITY_POPUP)
        val planned = CalibrationSignals.namesFor(both, UiState.ACTIVITY_POPUP, SignalRole.entries.toSet())
        assertEquals(listOf("popup_close2", "popup_close_anchor2"), planned.map { it.second })
        assertEquals(2, planned.map { it.second }.toSet().size)
    }

    @Test
    fun namesForRejectsUnknownStateAndEmptyRoles() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationSignals.namesFor(null, UiState.UNKNOWN, setOf(SignalRole.MARKER))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationSignals.namesFor(null, UiState.ACTIVITY_POPUP, emptySet())
        }
    }

    @Test
    fun writingBothRolesOnceYieldsTwoRecordsSharingGeometry() {
        // 一次框选写「标志 + 锚点」：两条记录共用本次提取的模板与搜索窗口（只解码 / 提取一次），角色各自成立
        val sharedWindow = window(0.6, 0.55, 0.95, 0.6)
        val sharedTemplate = template(w = 12, h = 9, seed = 7L)
        var data: CalibrationData? = null
        CalibrationSignals.namesFor(null, UiState.ACTIVITY_POPUP, SignalRole.entries.toSet())
            .forEach { (role, name) ->
                data = upsert(
                    data,
                    name,
                    UiState.ACTIVITY_POPUP,
                    window = sharedWindow,
                    template = sharedTemplate,
                    role = role,
                )
            }
        val written = requireNotNull(data)

        assertEquals(listOf("popup_close", "popup_close_anchor"), written.signals.map { it.name })
        assertEquals(listOf(sharedWindow, sharedWindow), written.signals.map { it.window })
        assertEquals(listOf(sharedTemplate, sharedTemplate), written.signals.map { it.templates.single() })
        assertEquals(1, written.stateRules.size)
        assertEquals(listOf("popup_close"), written.stateRules.single().signalNames)
        assertEquals(listOf("popup_close_anchor"), written.stateRules.single().anchorNames)
        // 锚点仍不进状态判定（锚点只用于点击）
        assertEquals(listOf("popup_close"), written.markerSpecs().map { it.name })
    }

    @Test
    fun countOfCountsPerRoleInsteadOfMixing() {
        var data = upsert(null, "popup_close", UiState.ACTIVITY_POPUP)
        data = upsert(data, "popup_close_x", UiState.ACTIVITY_POPUP, role = SignalRole.ANCHOR)

        assertEquals(1, CalibrationSignals.countOf(data, UiState.ACTIVITY_POPUP, SignalRole.MARKER))
        assertEquals(1, CalibrationSignals.countOf(data, UiState.ACTIVITY_POPUP, SignalRole.ANCHOR))
        // 该状态还没有规则 = 0（写入提示不抛异常）
        assertEquals(0, CalibrationSignals.countOf(data, UiState.FARM, SignalRole.MARKER))
    }

    // --- 覆盖写入：首条 / 同名 / 状态重归属 ---

    @Test
    fun upsertCreatesArtifactFromNothing() {
        val data = upsert(null, "hall", UiState.HALL)

        assertEquals(1000, data.frameWidth)
        assertEquals(800, data.frameHeight)
        assertEquals(defaultParams, data.params)
        assertEquals(1, data.signals.size)
        assertEquals("hall", data.signals[0].name)
        assertEquals(1, data.signals[0].templates.size)
        assertEquals(1, data.stateRules.size)
        assertEquals(UiState.HALL, data.stateRules[0].state)
        assertEquals(listOf("hall"), data.stateRules[0].signalNames)
    }

    @Test
    fun upsertReplacesSameNameEntirely() {
        val first = upsert(null, "hall", UiState.HALL, template = template(w = 10, h = 8, seed = 1L))
        val second = upsert(
            first,
            "hall",
            UiState.HALL,
            window = window(0.3, 0.3, 0.4, 0.4),
            template = template(w = 20, h = 12, seed = 2L),
        )

        // 同名信号整体替换：不追加模板、不合并窗口、不重复信号
        assertEquals(1, second.signals.size)
        assertEquals(1, second.signals[0].templates.size)
        assertEquals(20, second.signals[0].templates[0].width)
        assertEquals(12, second.signals[0].templates[0].height)
        assertEquals(window(0.3, 0.3, 0.4, 0.4), second.signals[0].window)
    }

    @Test
    fun upsertReassignsStateOfSameName() {
        val first = upsert(null, "hall", UiState.HALL)
        val second = upsert(first, "hall", UiState.FARM)

        assertEquals(1, second.stateRules.size)
        assertEquals(UiState.FARM, second.stateRules[0].state)
        assertEquals(UiState.FARM, CalibrationSignals.stateOf(second, "hall"))
    }

    @Test
    fun upsertGroupsRulesByFirstAppearanceOrder() {
        var data = upsert(null, "hall_a", UiState.HALL)
        data = upsert(data, "farm_a", UiState.FARM)
        data = upsert(data, "hall_b", UiState.HALL)

        assertEquals(listOf(UiState.HALL, UiState.FARM), data.stateRules.map { it.state })
        assertEquals(listOf("hall_a", "hall_b"), data.stateRules[0].signalNames)
        assertEquals(listOf("farm_a"), data.stateRules[1].signalNames)
    }

    @Test
    fun upsertKeepsOtherSignalsAndParams() {
        val first = upsert(null, "hall", UiState.HALL)
        val second = upsert(first, "farm", UiState.FARM, params = MatchParams(0.9, 0.2, 7))

        assertEquals(listOf("hall", "farm"), second.signals.map { it.name })
        assertEquals(MatchParams(0.9, 0.2, 7), second.params)
    }

    // --- 同状态多条记录（T2-2 方案 A：默认名 + 自动序号，追加不覆盖） ---

    @Test
    fun nextNameUsesBaseWhenUnoccupied() {
        assertEquals("popup_close", CalibrationSignals.nextName(null, "popup_close"))
        val data = upsert(null, "hall", UiState.HALL)
        assertEquals("popup_close", CalibrationSignals.nextName(data, "popup_close"))
    }

    @Test
    fun nextNameAppendsSequenceSkippingOccupiedNames() {
        var data = upsert(null, "popup_close", UiState.ACTIVITY_POPUP)
        assertEquals("popup_close2", CalibrationSignals.nextName(data, "popup_close"))

        data = upsert(data, "popup_close2", UiState.ACTIVITY_POPUP)
        assertEquals("popup_close3", CalibrationSignals.nextName(data, "popup_close"))

        // 序号从 2 起逐位取第一个空闲名（手工产物缺了 3 时会补 3，不复用被占的 4）
        data = upsert(data, "popup_close4", UiState.ACTIVITY_POPUP)
        assertEquals("popup_close3", CalibrationSignals.nextName(data, "popup_close"))
    }

    @Test
    fun nextNameAvoidsNamesHeldByOtherStates() {
        // 信号名在产物内全局唯一：默认名被别的状态占用时同样让位（防产物校验失败）
        val data = upsert(null, "popup_close", UiState.HALL)
        assertEquals("popup_close2", CalibrationSignals.nextName(data, "popup_close"))
    }

    @Test
    fun nextNameRejectsInvalidBase() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationSignals.nextName(null, "bad|name")
        }
    }

    @Test
    fun appendingStylesKeepsSingleRuleAndSeparateWindows() {
        var data = upsert(
            null,
            CalibrationSignals.nextName(null, "popup_close"),
            UiState.ACTIVITY_POPUP,
            window = window(0.6, 0.55, 0.95, 0.6),
        )
        data = upsert(
            data,
            CalibrationSignals.nextName(data, "popup_close"),
            UiState.ACTIVITY_POPUP,
            window = window(0.85, 0.02, 0.97, 0.08),
        )

        // 同一状态的多条记录合成一条规则（任一命中即该状态命中），但各自保留自己的搜索窗口
        assertEquals(1, data.stateRules.size)
        assertEquals(UiState.ACTIVITY_POPUP, data.stateRules[0].state)
        assertEquals(listOf("popup_close", "popup_close2"), data.stateRules[0].signalNames)
        assertEquals(listOf("popup_close", "popup_close2"), data.signals.map { it.name })
        assertEquals(window(0.6, 0.55, 0.95, 0.6), data.signals[0].window)
        assertEquals(window(0.85, 0.02, 0.97, 0.08), data.signals[1].window)
    }

    // --- 几何校验与非法输入（T1-5l ⑥） ---

    @Test
    fun geometryErrorIsNullWithoutArtifactOrWhenEqual() {
        assertNull(CalibrationSignals.geometryError(null, 1000, 800))
        val data = upsert(null, "hall", UiState.HALL)
        assertNull(CalibrationSignals.geometryError(data, 1000, 800))
    }

    @Test
    fun geometryErrorReportsMismatch() {
        val data = upsert(null, "hall", UiState.HALL)
        val error = CalibrationSignals.geometryError(data, 800, 1000)
        assertNotNull(error)
        assertTrue("提示应含两侧几何：$error", error!!.contains("1000x800") && error.contains("800x1000"))
    }

    @Test
    fun upsertRejectsGeometryMismatch() {
        val data = upsert(null, "hall", UiState.HALL)
        assertThrows(IllegalArgumentException::class.java) {
            upsert(data, "farm", UiState.FARM, frameWidth = 800, frameHeight = 1000)
        }
    }

    @Test
    fun upsertRejectsUnknownStateAndNonPositiveFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            upsert(null, "hall", UiState.UNKNOWN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            upsert(null, "hall", UiState.HALL, frameWidth = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            upsert(null, "hall", UiState.HALL, frameHeight = -1)
        }
    }

    // --- 删除：单条 / 删空 / 不存在 ---

    @Test
    fun removeDropsSignalAndItsRule() {
        var data = upsert(null, "hall_a", UiState.HALL)
        data = upsert(data, "hall_b", UiState.HALL)
        data = upsert(data, "farm", UiState.FARM)

        val remaining = CalibrationSignals.remove(data, "hall_a")

        assertNotNull(remaining)
        assertEquals(listOf("hall_b", "farm"), remaining!!.signals.map { it.name })
        // 只剩一条 HALL 信号与一条 FARM 信号 → 两条规则（信号名不得残留被删的那条）
        assertEquals(listOf(UiState.HALL, UiState.FARM), remaining.stateRules.map { it.state })
        assertEquals(listOf("hall_b"), remaining.stateRules[0].signalNames)
        assertEquals(listOf("farm"), remaining.stateRules[1].signalNames)
    }

    @Test
    fun removeLastSignalReturnsNull() {
        val data = upsert(null, "hall", UiState.HALL)
        assertNull(CalibrationSignals.remove(data, "hall"))
    }

    @Test
    fun removeMissingNameIsIdempotent() {
        val data = upsert(null, "hall", UiState.HALL)
        val same = CalibrationSignals.remove(data, "farm")
        assertEquals(data.signals.map { it.name }, same!!.signals.map { it.name })
    }

    // --- 参数编辑与解析 ---

    @Test
    fun withParamsReturnsNullWithoutArtifact() {
        assertNull(CalibrationSignals.withParams(null, defaultParams))
    }

    @Test
    fun withParamsReturnsNullWhenUnchanged() {
        val data = upsert(null, "hall", UiState.HALL)
        assertNull(CalibrationSignals.withParams(data, defaultParams))
    }

    @Test
    fun withParamsUpdatesOnlyParams() {
        val data = upsert(null, "hall", UiState.HALL)
        val updated = CalibrationSignals.withParams(data, MatchParams(0.7, 0.05, 3))

        assertNotNull(updated)
        assertEquals(MatchParams(0.7, 0.05, 3), updated!!.params)
        assertEquals(data.signals.map { it.name }, updated.signals.map { it.name })
        assertEquals(data.frameWidth, updated.frameWidth)
    }

    @Test
    fun parseParamsAcceptsValidAndRejectsInvalid() {
        assertEquals(defaultParams, CalibrationSignals.parseParams("0.85", "0.10", "5"))
        assertEquals(defaultParams, CalibrationSignals.parseParams(" 0.85 ", "0.10", "5"))
        assertNull(CalibrationSignals.parseParams("abc", "0.10", "5"))
        assertNull(CalibrationSignals.parseParams("1.5", "0.10", "5")) // 命中线超出 (0,1]
        assertNull(CalibrationSignals.parseParams("0.9", "0.10", "0")) // 峰值间距 ≥ 1
        assertNull(CalibrationSignals.parseParams("", "", ""))
    }

    @Test
    fun defaultParamsMatchDefaultTexts() {
        assertEquals(
            CalibrationSignals.parseParams(
                CalibrationSignals.DEFAULT_THRESHOLD_TEXT,
                CalibrationSignals.DEFAULT_MARGIN_TEXT,
                CalibrationSignals.DEFAULT_PEAK_DISTANCE_TEXT,
            ),
            CalibrationSignals.defaultParams(),
        )
    }

    @Test
    fun stateOfReturnsNullForUnknownSignal() {
        val data = upsert(null, "hall", UiState.HALL)
        assertEquals(UiState.HALL, CalibrationSignals.stateOf(data, "hall"))
        assertNull(CalibrationSignals.stateOf(data, "farm"))
    }

    // --- 选区 → 搜索窗口（原 CalibrationDraftTest 用例迁入） ---

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
}
