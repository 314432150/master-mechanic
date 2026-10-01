package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.SignalNames
import com.example.mastermechanic.recognition.DetectionRecord
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.MatchPeak
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import com.example.mastermechanic.recognition.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        id: String,
        state: UiState,
        window: SearchWindow = window(),
        template: Template = template(),
        params: MatchParams = defaultParams,
        frameWidth: Int = 1000,
        frameHeight: Int = 800,
        role: SignalRole = SignalRole.MARKER,
        purpose: String? = null,
        friend: String? = null,
        note: String = "",
    ): CalibrationData = CalibrationSignals.upsert(
        current = current,
        id = id,
        state = state,
        window = window,
        template = template,
        params = params,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        role = role,
        purpose = purpose,
        friend = friend,
        note = note,
    )

    @Test
    fun friendAvatarsGetOneRecordPerFriendAndReFramingOverwritesThatOne() {
        // 2026-09-21：好友头像**每位好友一条**（同用途、不同归属好友）。
        // 若沿用"落点 = 用途名"，多位好友会互相顶掉；重框同一位又应该覆盖他那条而不是一直新增。
        val window = SearchWindow(0.1, 0.1, 0.2, 0.2)
        var data: CalibrationData? = null

        fun write(friend: String): String {
            val id = CalibrationSignals.targetFor(
                current = data,
                state = UiState.FARM,
                roles = listOf(SignalRole.ANCHOR),
                purpose = "friend_avatar",
                friend = friend,
            )
            data = upsert(
                current = data,
                id = id,
                state = UiState.FARM,
                window = window,
                role = SignalRole.ANCHOR,
                purpose = "friend_avatar",
                friend = friend,
            )
            return id
        }

        val firstBoss = write("Boss~~喵")
        val pigeon = write("折翼白鸽")
        val bossAgain = write("Boss~~喵")

        assertTrue("不同好友各一条：$firstBoss / $pigeon", firstBoss != pigeon)
        assertEquals("同一位好友重框 = 覆盖他那条", firstBoss, bossAgain)

        val product = requireNotNull(data)
        assertEquals(2, product.signals.size)
        assertEquals("Boss~~喵", product.friendOf(firstBoss))
        assertEquals("折翼白鸽", product.friendOf(pigeon))
        // 按"用途 + 归属好友"精确取（全等；差一点都取不到）
        assertEquals(firstBoss, product.anchorIdFor(UiState.FARM, "friend_avatar", "Boss~~喵"))
        assertNull(product.anchorIdFor(UiState.FARM, "friend_avatar", "Boss~喵"))
    }

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
    fun sameIdCanBeBothMarkerAndAnchor() {
        // 同一个元素两种角色 = **两条记录**（§2.1）：ID 相同、角色不同，谁都不覆盖谁
        val asMarker = upsert(null, "popup_close_x", UiState.ACTIVITY_POPUP)
        val both = upsert(asMarker, "popup_close_x", UiState.ACTIVITY_POPUP, role = SignalRole.ANCHOR)

        assertEquals(2, both.signals.size)
        assertEquals(listOf("popup_close_x"), both.stateRules.single().signalNames)
        assertEquals(listOf("popup_close_x"), both.anchorNamesForTest())

        // 同一个 ID **同一个角色**再写一次才是覆盖（这就是"重新框了同一个东西"）
        val again = upsert(
            both,
            "popup_close_x",
            UiState.ACTIVITY_POPUP,
            role = SignalRole.ANCHOR,
            window = window(0.7, 0.1, 0.8, 0.2),
        )
        assertEquals(2, again.signals.size)
        assertEquals(window(0.7, 0.1, 0.8, 0.2), again.signals.single { it.role == SignalRole.ANCHOR }.window)
    }

    @Test
    fun anchorTargetIsThePurposeSoReframingOverwrites() {
        // 人不可能每次都框出相同像素（2026-09-19 用户反馈）→ 落点不能靠像素算。
        // 勾了锚点、选了用途 → 落点就是那个用途：再标一次就是**覆盖**，框得不一样也算同一条。
        assertEquals(
            "hall_farm",
            CalibrationSignals.targetFor(null, UiState.HALL, setOf(SignalRole.ANCHOR), purpose = "hall_farm"),
        )
        val existing = upsert(null, "hall_farm", UiState.HALL, role = SignalRole.ANCHOR, purpose = "hall_farm")
        assertEquals(
            "hall_farm",
            CalibrationSignals.targetFor(existing, UiState.HALL, setOf(SignalRole.ANCHOR), purpose = "hall_farm"),
        )
    }

    @Test
    fun markersAlwaysOpenANewSlot() {
        // 标志没有用途 → 每次都是**新的一条**（同一个界面允许多种样式），名字由程序排：
        // 第一个 `<界面>_marker`，再来一条就是 `_marker2`（名字天然可区分，不会像锚点那样撞名）
        assertEquals("hall_e1", CalibrationSignals.targetFor(null, UiState.HALL, setOf(SignalRole.MARKER)))
        val first = upsert(null, "hall_e1", UiState.HALL)
        assertEquals("hall_e2", CalibrationSignals.targetFor(first, UiState.HALL, setOf(SignalRole.MARKER)))
        val second = upsert(first, "hall_e2", UiState.HALL)
        assertEquals("hall_e3", CalibrationSignals.targetFor(second, UiState.HALL, setOf(SignalRole.MARKER)))
    }

    // ⚠ 写入试读（`CalibrationSignals.tryoutText`）已于 2026-09-29 **整条移除**（用户拍板）：
    // 它是一次完整模板匹配，大模板要跑好几秒 ⇒ 后台叠加把主线程饿死 ⇒ ANR + 黑屏 + 闪退。
    // 本条用例随之删除（不要再把它加回来 —— 见 docs/progress.md 第 277 条）。

    // --- 只圈区域的锚点：模板只留缩略图（2026-09-29）---

    @Test
    fun regionAnchorsKeepOnlyAPreviewTemplate() {
        // 只圈区域的锚点**不参与匹配** ⇒ 整块模板没用却把产物撑大（真机 5.4MB ⇒ 重载 OOM、帧线程崩）
        val big = upsert(
            current = null,
            id = "server_list_area",
            state = UiState.SERVER_SELECT,
            template = Template(2532, 1118, SyntheticImages.pattern(2532, 1118, seed = 3L)),
            role = SignalRole.ANCHOR,
        )

        val shrunk = CalibrationSignals.shrinkRegionTemplates(big, setOf("server_list_area"))

        val entry = shrunk.signals.single()
        assertEquals(64, entry.templates.single().width)
        assertEquals(28, entry.templates.single().height)
        // 除了模板，其余一律不动（窗口 / 角色 / 用途 / 参数 / 标定帧尺寸）
        assertEquals(big.signals.single().window, entry.window)
        assertEquals(SignalRole.ANCHOR, entry.role)
        assertEquals(big.params, shrunk.params)
        assertEquals(big.frameWidth, shrunk.frameWidth)
        assertEquals(big.stateRules, shrunk.stateRules)
    }

    @Test
    fun unrelatedTemplatesAreLeftAlone() {
        // **参与匹配的信号绝不能缩**：缩了像素就变了，模板匹配直接失效
        val data = upsert(
            current = null,
            id = "hall_settings",
            state = UiState.HALL,
            template = Template(80, 80, SyntheticImages.pattern(80, 80, seed = 5L)),
        )
        assertSame(data, CalibrationSignals.shrinkRegionTemplates(data, setOf("server_list_area")))
    }

    @Test
    fun shrinkingIsIdempotentSoTheCallerCanDecideWhetherToSave() {
        // 幂等 ⇒ "缩不动了"时返回**同一个对象**，调用方据此判断"要不要落盘"（一次会话只写一次）
        val big = upsert(
            current = null,
            id = "friend_list_area",
            state = UiState.FRIEND_LIST,
            template = Template(900, 1100, SyntheticImages.pattern(900, 1100, seed = 9L)),
            role = SignalRole.ANCHOR,
        )
        val once = CalibrationSignals.shrinkRegionTemplates(big, setOf("friend_list_area"))

        assertNotSame(big, once)
        assertSame(once, CalibrationSignals.shrinkRegionTemplates(once, setOf("friend_list_area")))
    }

    @Test
    fun activityPopupAnchorsAreAddedOneByOne() {
        // 活动弹窗有多个相似的关闭按钮模板 → 每次框选**新增一条**（不做覆盖）：
        // 它没有用途表，锚点也走槽位编号，标几种样式就有几条
        assertEquals(
            "activity_popup_e1",
            CalibrationSignals.targetFor(null, UiState.ACTIVITY_POPUP, setOf(SignalRole.ANCHOR)),
        )
        val first = upsert(null, "activity_popup_e1", UiState.ACTIVITY_POPUP, role = SignalRole.ANCHOR)
        assertEquals(
            "activity_popup_e2",
            CalibrationSignals.targetFor(first, UiState.ACTIVITY_POPUP, setOf(SignalRole.ANCHOR)),
        )
    }

    @Test
    fun bothRolesOfOneSelectionLandOnTheSameElement() {
        // 一次框选写「标志 + 锚点」：两条记录落到**同一个元素** —— 清单据此把两条认成一行
        val target = CalibrationSignals.targetFor(
            current = null,
            state = UiState.FRIEND_FARM,
            roles = SignalRole.entries.toSet(),
            purpose = "friend_farm_friends",
        )
        var data: CalibrationData? = null
        CalibrationSignals.rolesToWrite(UiState.FRIEND_FARM, SignalRole.entries.toSet()).forEach { role ->
            data = upsert(
                data,
                target,
                UiState.FRIEND_FARM,
                role = role,
                purpose = if (role == SignalRole.ANCHOR) "friend_farm_friends" else null,
            )
        }

        val written = requireNotNull(data)
        assertEquals(listOf(target, target), written.signals.map { it.id })
        assertEquals(listOf(SignalRole.MARKER, SignalRole.ANCHOR), written.signals.map { it.role })
        assertEquals(listOf(target), written.stateRules.single().signalNames)
        assertEquals(listOf(target), written.anchorNamesForTest())
    }

    @Test
    fun writingTheSameElementAgainOverwrites() {
        // 同一个元素再写一次（同角色）→ 整体替换，不会攒出第二条
        val first = upsert(null, "hall_e1", UiState.HALL, window = window(0.1, 0.1, 0.2, 0.2))
        val second = upsert(first, "hall_e1", UiState.HALL, window = window(0.3, 0.3, 0.4, 0.4))

        assertEquals(1, second.signals.size)
        assertEquals(window(0.3, 0.3, 0.4, 0.4), second.signals.single().window)
    }

    @Test
    fun noteIsInheritedWhenTheSameElementIsWrittenAgain() {
        // 备注是元素级的自由文本：重标时没填就沿用旧的，否则"重新框一下"会顺手把用户写的备注抹掉
        val withNote = upsert(null, "hall_e1", UiState.HALL, note = "左上角设置")
        val again = upsert(withNote, "hall_e1", UiState.HALL, window = window(0.2, 0.2, 0.3, 0.3))

        assertEquals("左上角设置", again.signals.single().note)
    }

    @Test
    fun noteAppliesToBothRolesOfTheElement() {
        // 同一元素的两条记录共用一份备注：写一次两条都带上，改一次两条都变
        var data = upsert(null, "hall_farm", UiState.HALL, note = "大厅入口")
        data = upsert(data, "hall_farm", UiState.HALL, role = SignalRole.ANCHOR, note = "大厅入口")
        data = CalibrationSignals.withNote(data, "hall_farm", "换个说法")

        assertEquals(listOf("换个说法", "换个说法"), data.signals.map { it.note })
        assertEquals("换个说法", data.noteOf("hall_farm"))
    }

    @Test
    fun removeElementDeletesBothRolesAtOnce() {
        // 清单页的删除粒度 = 一行 = 一个元素：只删一半会留下一条谁也说不清来历的记录
        var data = upsert(null, "hall_farm", UiState.HALL)
        data = upsert(data, "hall_farm", UiState.HALL, role = SignalRole.ANCHOR)

        assertNull(CalibrationSignals.removeElement(data, "hall_farm"))
        // 删一条（按 ID + 角色）则留下另一条
        var pair = upsert(null, "hall_farm", UiState.HALL)
        pair = upsert(pair, "hall_farm", UiState.HALL, role = SignalRole.ANCHOR)
        val left = requireNotNull(CalibrationSignals.remove(pair, "hall_farm", SignalRole.ANCHOR))
        assertEquals(listOf(SignalRole.MARKER), left.signals.map { it.role })
    }

    @Test
    fun purposeBelongsToAnchorsOnly() {
        // 用途是给机器看的契约（跑号按它找锚点）；标志没有用途
        var data = upsert(null, "hall_farm", UiState.HALL, role = SignalRole.MARKER)
        data = upsert(data, "hall_farm", UiState.HALL, role = SignalRole.ANCHOR, purpose = "hall_farm")

        assertEquals("hall_farm", data.purposeOf("hall_farm"))
        // 按用途找锚点：状态对不上就找不到（不兜底到别的状态）
        assertEquals("hall_farm", data.anchorIdFor(UiState.HALL, "hall_farm"))
        assertNull(data.anchorIdFor(UiState.FARM, "hall_farm"))

        assertThrows(IllegalArgumentException::class.java) {
            // 标志不能带用途
            upsert(null, "hall_farm", UiState.HALL, purpose = "hall_farm")
        }
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
    fun rolesToWriteFollowsMarkerThenAnchorAndRejectsBadInput() {
        // 写入顺序固定（标志 → 锚点）：界面排布、产物文本与消息文本都依赖它
        assertEquals(
            listOf(SignalRole.MARKER, SignalRole.ANCHOR),
            CalibrationSignals.rolesToWrite(UiState.HALL, setOf(SignalRole.ANCHOR, SignalRole.MARKER)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationSignals.rolesToWrite(UiState.UNKNOWN, setOf(SignalRole.MARKER))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationSignals.rolesToWrite(UiState.HALL, emptySet())
        }
    }

    @Test
    fun writingBothRolesOnceYieldsTwoRecordsSharingGeometry() {
        // 一次框选写「标志 + 锚点」：两条记录共用本次提取的模板、搜索窗口与**元素**（只解码 / 提取一次）
        val sharedWindow = window(0.6, 0.55, 0.95, 0.6)
        val sharedTemplate = template(w = 12, h = 9, seed = 7L)
        val id = "activity_popup_e1"
        var data: CalibrationData? = null
        CalibrationSignals.rolesToWrite(UiState.ACTIVITY_POPUP, SignalRole.entries.toSet())
            .forEach { role ->
                data = upsert(
                    data,
                    id,
                    UiState.ACTIVITY_POPUP,
                    window = sharedWindow,
                    template = sharedTemplate,
                    role = role,
                )
            }
        val written = requireNotNull(data)

        assertEquals(listOf(id, id), written.signals.map { it.id })
        assertEquals(listOf(sharedWindow, sharedWindow), written.signals.map { it.window })
        assertEquals(listOf(sharedTemplate, sharedTemplate), written.signals.map { it.templates.single() })
        assertEquals(1, written.stateRules.size)
        assertEquals(listOf(id), written.stateRules.single().signalNames)
        assertEquals(listOf(id), written.stateRules.single().anchorNames)
        // 锚点仍不进状态判定（锚点只用于点击）
        assertEquals(listOf(id), written.markerSpecs().map { it.name })
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
        assertEquals("hall", data.signals[0].id)
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

        assertEquals(listOf("hall", "farm"), second.signals.map { it.id })
        assertEquals(MatchParams(0.9, 0.2, 7), second.params)
    }

    // --- 同状态多条记录（T2-2 方案 A：多种样式各写一条，各有搜索窗口） ---

    @Test
    fun appendingStylesKeepsSingleRuleAndSeparateWindows() {
        // 两张不同样式的关闭按钮图 = 两个不同的 ID（不是"名字加序号"，而是各自身份不同）
        var data = upsert(
            null,
            "popup_close",
            UiState.ACTIVITY_POPUP,
            window = window(0.6, 0.55, 0.95, 0.6),
        )
        data = upsert(
            data,
            "popup_close2",
            UiState.ACTIVITY_POPUP,
            window = window(0.85, 0.02, 0.97, 0.08),
        )

        // 同一状态的多条记录合成一条规则（任一命中即该状态命中），但各自保留自己的搜索窗口
        assertEquals(1, data.stateRules.size)
        assertEquals(UiState.ACTIVITY_POPUP, data.stateRules[0].state)
        assertEquals(listOf("popup_close", "popup_close2"), data.stateRules[0].signalNames)
        assertEquals(listOf("popup_close", "popup_close2"), data.signals.map { it.id })
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

        val remaining = CalibrationSignals.remove(data, "hall_a", SignalRole.MARKER)

        assertNotNull(remaining)
        assertEquals(listOf("hall_b", "farm"), remaining!!.signals.map { it.id })
        // 只剩一条 HALL 信号与一条 FARM 信号 → 两条规则（信号名不得残留被删的那条）
        assertEquals(listOf(UiState.HALL, UiState.FARM), remaining.stateRules.map { it.state })
        assertEquals(listOf("hall_b"), remaining.stateRules[0].signalNames)
        assertEquals(listOf("farm"), remaining.stateRules[1].signalNames)
    }

    @Test
    fun removeLastSignalReturnsNull() {
        val data = upsert(null, "hall", UiState.HALL)
        assertNull(CalibrationSignals.remove(data, "hall", SignalRole.MARKER))
    }

    @Test
    fun removeMissingRecordIsIdempotent() {
        val data = upsert(null, "hall", UiState.HALL)
        val same = CalibrationSignals.remove(data, "farm", SignalRole.MARKER)
        assertEquals(data.signals.map { it.id }, same!!.signals.map { it.id })
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
        assertEquals(data.signals.map { it.id }, updated.signals.map { it.id })
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
    fun windowExpandsByAFixedPixelMargin() {
        // 2026-09-29 新口径：四周各外扩**固定像素**（标志 48 / 锚点 64），**不再**按选区大小外扩。
        // 依据见 `SelectionWindow` 的对象注释（容差该按像素计；旧口径的成本随选区面积平方涨）。
        // ⚠ 像素换算走 `floor/ceil`（比例存的是 double）⇒ 断言留 ±1px。
        val margin = SelectionWindow.MARKER_MARGIN_PX
        val window = SelectionWindow.forSelection(1000, 800, 400, 300, 500, 350, marginPx = margin)
        val bounds = window.pixelBounds(1000, 800)

        assertEquals((400 - margin).toDouble(), bounds.x0.toDouble(), 1.0)
        assertEquals((300 - margin).toDouble(), bounds.y0.toDouble(), 1.0)
        assertEquals((500 + margin).toDouble(), bounds.x1.toDouble(), 1.0)
        assertEquals((350 + margin).toDouble(), bounds.y1.toDouble(), 1.0)
        assertEquals("左余量 = 右余量（选区在窗口正中）", 400 - bounds.x0, bounds.x1 - 500)
    }

    @Test
    fun aTinySelectionGetsTheSamePixelMarginAsABigOne() {
        // **这一条正是改口径的目的**：9×15 的小按钮在旧口径下只有 ±9px 容差（渲染差 2px 就 miss），
        // 而 900×170 的大横幅有 ±900px（纯浪费）。现在两者都拿 48px。
        val margin = SelectionWindow.MARKER_MARGIN_PX
        val tiny = SelectionWindow.forSelection(1000, 800, 100, 100, 109, 115, marginPx = margin)
            .pixelBounds(1000, 800)
        val big = SelectionWindow.forSelection(1000, 800, 50, 600, 950, 770, marginPx = margin)
            .pixelBounds(1000, 800)

        assertEquals("小元素也是 ±48px", margin.toDouble(), (100 - tiny.x0).toDouble(), 1.0)
        assertEquals("大元素也是 ±48px（旧口径这里是 ±900px）", margin.toDouble(), (50 - big.x0).toDouble(), 1.0)
    }

    @Test
    fun windowClampsTheMarginAtFrameEdges() {
        // 贴角 / 贴边 ⇒ 余量**对称地缩**（两侧留一样多）⇒ 窗口 = 选区本身，绝不越出帧，
        // 且**选区恒在窗口正中**（这是运行时收紧与成本护栏能安全工作的前提）。
        val corner = SelectionWindow.forSelection(1000, 800, 0, 0, 50, 40, marginPx = 48)
            .pixelBounds(1000, 800)
        assertEquals(0, corner.x0)
        assertEquals(0, corner.y0)
        assertTrue("右边界到选区为止（floor/ceil 容许 ±1）：${corner.x1}", Math.abs(corner.x1 - 50) <= 1)
        assertTrue("下边界到选区为止：${corner.y1}", Math.abs(corner.y1 - 40) <= 1)

        // 贴右缘：右边只能留 0 ⇒ 左边也对称地留 0
        val flushRight = SelectionWindow.forSelection(1000, 800, 900, 400, 1000, 500, marginPx = 48)
            .pixelBounds(1000, 800)
        assertEquals("右边界贴帧边", 1000, flushRight.x1)
        assertTrue("左边对称地留同样多（0）：${flushRight.x0}", Math.abs(flushRight.x0 - 900) <= 1)
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
            val bounds = SelectionWindow.forSelection(1000, 800, x0, y0, x1, y1, marginPx = 48)
                .pixelBounds(1000, 800)
            assertTrue("左界应覆盖：$x0", bounds.x0 <= x0)
            assertTrue("上界应覆盖：$y0", bounds.y0 <= y0)
            assertTrue("右界应覆盖：$x1", bounds.x1 >= x1)
            assertTrue("下界应覆盖：$y1", bounds.y1 >= y1)
        }
    }

    @Test
    fun anchorGetsMoreMarginThanAMarker() {
        // 锚点比标志多（64 vs 48）：锚点是"定位 + 点击"的落点，认不出 = **整步停摆**；
        // 且它只在自己那个状态被定位（成本偶尔付一次）⇒ 值得给更大容差。
        assertEquals(64, SelectionWindow.marginPxFor(SignalRole.ANCHOR))
        assertEquals(48, SelectionWindow.marginPxFor(SignalRole.MARKER))
        assertTrue(SelectionWindow.marginPxFor(SignalRole.ANCHOR) > SelectionWindow.marginPxFor(SignalRole.MARKER))
    }

    @Test
    fun exactWindowIsTheSelectionItself() {
        // 区域型锚点用：窗口 = 选区，一点余量都不加
        val window = SelectionWindow.exact(1000, 800, 100, 100, 200, 150)
        assertEquals(0.1, window.left, 1e-9)
        assertEquals(0.125, window.top, 1e-9) // 100/800
        assertEquals(0.2, window.right, 1e-9)
        assertEquals(0.1875, window.bottom, 1e-9) // 150/800
    }

    @Test
    fun regionPurposesDoNotGetTheSearchMargin() {
        // 2026-09-23 真机修：「列表区域」/「好友名称列」这类**只圈区域、不做模板匹配**的锚点，
        // 窗口必须**等于**用户框的那一块。它们的外扩余量没有用处（不做模板匹配），
        // 而窗口会被当作**文字识别的输入区域**用 —— 外扩会把"名字那一列"变成更宽 ⇒ 等于没裁。
        // 真机实录：用户框了 510×57 的一行名字文字，产物里却写着 1530×172（x 从屏幕中线到右边缘）。
        val region = setOf("friend_list_area", "friend_list_name_column")
        val narrow = SelectionWindow.forPurpose(
            purpose = "friend_list_name_column",
            regionPurposes = region,
            marginPx = SelectionWindow.ANCHOR_MARGIN_PX,
            frameWidth = 1000, frameHeight = 800,
            x0 = 400, y0 = 300, x1 = 500, y1 = 350,
        )
        assertEquals("左边界就是选区左边界", 0.4, narrow.left, 1e-9)
        assertEquals("右边界就是选区右边界", 0.5, narrow.right, 1e-9)

        // 标志 / 可点锚点拿到的是"四周各固定像素"的搜索窗口（元素会漂移，这条余量是必需的）
        val marker = SelectionWindow.forPurpose(
            purpose = null,
            regionPurposes = region,
            marginPx = SelectionWindow.MARKER_MARGIN_PX,
            frameWidth = 1000, frameHeight = 800,
            x0 = 400, y0 = 300, x1 = 500, y1 = 350,
        )
        assertEquals((400 - 48).toDouble(), marker.pixelBounds(1000, 800).x0.toDouble(), 1.0)
        assertEquals((500 + 48).toDouble(), marker.pixelBounds(1000, 800).x1.toDouble(), 1.0)

        val otherAnchor = SelectionWindow.forPurpose(
            purpose = "friend_visit",
            regionPurposes = region,
            marginPx = SelectionWindow.ANCHOR_MARGIN_PX,
            frameWidth = 1000, frameHeight = 800,
            x0 = 400, y0 = 300, x1 = 500, y1 = 350,
        )
        assertEquals("不在这张豁免表里的锚点（要点的那个）照旧外扩（且拿到锚点余量 64）", 400 - 64, otherAnchor.pixelBounds(1000, 800).x0)
    }

    @Test
    fun selectionWindowRejectsInvalidSelection() {
        assertThrows(IllegalArgumentException::class.java) {
            SelectionWindow.forSelection(1000, 800, 200, 100, 200, 150, marginPx = 48)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionWindow.forSelection(1000, 800, 0, 0, 1001, 150, marginPx = 48)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionWindow.forSelection(1000, 800, 100, 100, 200, 150, marginPx = -1)
        }
    }

    // ---------------------------------------------------------------- 备注补全 / 订正（2026-10-01 用户口径）

    @Test
    fun withDefaultNotesFillsBlankNotesOnly() {
        var data: CalibrationData? = null
        data = upsert(data, "hall_settings", UiState.HALL)
        data = upsert(data, "hall_settings", UiState.HALL, role = SignalRole.ANCHOR, purpose = "hall_settings")
        data = upsert(data, "farm_exit", UiState.FARM, role = SignalRole.ANCHOR, note = "我自己写的")

        // 用**真规则**（`SignalNames`），不另写一套：这样文案变化时测试会跟着变，不会各自为政
        val (filled, count) = CalibrationSignals.withDefaultNotes(data!!, SignalNames::defaultNoteFor)

        assertEquals("只补了 1 个元素（farm_exit 本来就有备注；返回值按**元素**计数，不是记录）", 1, count)
        // **按记录**派生（2026-10-01 订正）：标志写"它是哪一屏的标志"、锚点写用途 ⇒ 两行各说各的
        assertEquals("「大厅」的界面标志", filled.noteOf("hall_settings", SignalRole.MARKER))
        assertEquals("设置入口", filled.noteOf("hall_settings", SignalRole.ANCHOR))
        assertEquals("我自己写的", filled.noteOf("farm_exit"))
    }

    @Test
    fun withDefaultNotesIsIdempotentAndSkipsUnknownStates() {
        var data: CalibrationData? = null
        data = upsert(data, "hall_settings", UiState.HALL)
        data = upsert(data, "hall_settings", UiState.HALL, role = SignalRole.ANCHOR, purpose = "hall_settings")
        // 归属界面认不出来的记录（没有对应 state 规则）⇒ 跳过，不留一个"「未知」的标志"
        val orphan = CalibrationData(
            frameWidth = data!!.frameWidth,
            frameHeight = data.frameHeight,
            params = data.params,
            signals = data.signals + CalibrationData.SignalEntry(
                "orphan_e1",
                data.signals.first().window,
                listOf(template(seed = 9)),
            ),
            stateRules = data.stateRules,
        )

        val (once, first) = CalibrationSignals.withDefaultNotes(orphan, SignalNames::defaultNoteFor)
        assertEquals(1, first)
        assertEquals("", once.noteOf("orphan_e1"))

        val (again, second) = CalibrationSignals.withDefaultNotes(once, SignalNames::defaultNoteFor)
        assertEquals("再跑一次不该改任何东西", 0, second)
        assertEquals("设置入口", again.noteOf("hall_settings", SignalRole.ANCHOR))
    }

    @Test
    fun withDefaultNotesCorrectsTheProgramTextButKeepsHandWrittenOnes() {
        // 程序自动写过的句子（默认规则当天改过两版，两版的句子都要认得出）⇒ 订正成新文本；
        // 用户手写的一律不碰。真机产物里这两版句子都躺着过（用户 2026-10-01 两度报"备注不对"）。
        val v1Crossed = "「活动弹窗」的界面标志" // 第一版：标志 / 锚点串味
        val v2Generic = "「新手引导」的锚点" // 第二版：没用途的锚点一律"「界面」的锚点"
        var data: CalibrationData? = null
        data = upsert(data, "activity_popup_e1", UiState.ACTIVITY_POPUP, role = SignalRole.MARKER, note = v1Crossed)
        data = upsert(data, "activity_popup_e1", UiState.ACTIVITY_POPUP, role = SignalRole.ANCHOR, note = v1Crossed)
        data = upsert(data, "tutorial_guide_e3", UiState.TUTORIAL_GUIDE, role = SignalRole.ANCHOR, note = v2Generic)
        data = upsert(data, "farm_exit", UiState.FARM, role = SignalRole.ANCHOR, note = "我自己写的")

        val (fixed, count) = CalibrationSignals.withDefaultNotes(
            current = data!!,
            defaultFor = SignalNames::defaultNoteFor,
            autoNoteHistory = SignalNames::autoNoteHistory,
        )

        assertEquals("动了两个元素（activity_popup_e1 + tutorial_guide_e3）", 2, count)
        assertEquals("标志那句本来就对，原样留着", v1Crossed, fixed.noteOf("activity_popup_e1", SignalRole.MARKER))
        assertEquals(
            "锚点：串味那句 ⇒ 说清它是关闭控件",
            "「活动弹窗」的关闭控件",
            fixed.noteOf("activity_popup_e1", SignalRole.ANCHOR),
        )
        assertEquals(
            "第二版的兜底句也认得出",
            "「新手引导」的关闭控件",
            fixed.noteOf("tutorial_guide_e3"),
        )
        assertEquals("手写的一律不动", "我自己写的", fixed.noteOf("farm_exit", SignalRole.ANCHOR))
    }
}
