package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolAnchors
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        // 帧尺寸可指定：窗口收紧的**绝对余量下限**按像素定义（`MIN_ABSOLUTE_MARGIN_PX`），
        // 小帧（100×80）下它会把每个窗口都兜满 —— 要验"比例收紧"就得用真机量级的帧。
        frameWidth: Int = 100,
        frameHeight: Int = 80,
    ): CalibrationData = CalibrationData(frameWidth, frameHeight, params, signals, rules)

    /** 一个"两个农场各有一套"的旧产物：农场（标志 + 返回 + 回家锚点）、好友农场（标志 + 返回）。 */
    private fun withBothFarms(homeAnchored: Boolean = true): CalibrationData {
        val signals = mutableListOf(
            signal("farm_e1"),
            signal("friend_farm_e1"),
            CalibrationData.SignalEntry("farm_exit", fullWindow, listOf(template(3)), SignalRole.ANCHOR),
            CalibrationData.SignalEntry("friend_farm_exit", fullWindow, listOf(template(4)), SignalRole.ANCHOR),
        )
        if (homeAnchored) {
            // 农场组下多挂一条锚点（这里只关心"农场那组有几条锚点"，用途名本身不重要）。
            // 2026-09-23：`own_avatar` 用途已随农场归属判定整块撤下；测试里仍拿它当**产物里可能出现的
            // 字符串**用（数据层不查用途表，只认字符串），所以写字面量而不是引用常量。
            signals += CalibrationData.SignalEntry(
                "own_avatar_1",
                fullWindow,
                listOf(template(5)),
                SignalRole.ANCHOR,
                purpose = "own_avatar",
            )
        }
        return data(
            signals = signals,
            rules = listOf(
                CalibrationData.StateRule(
                    UiState.FARM,
                    listOf("farm_e1"),
                    listOf("farm_exit") + if (homeAnchored) listOf("own_avatar_1") else emptyList(),
                ),
                CalibrationData.StateRule(
                    UiState.FRIEND_FARM,
                    listOf("friend_farm_e1"),
                    listOf("friend_farm_exit"),
                ),
            ),
        )
    }

    @Test
    fun retiringTheFriendFarmClearsEverythingUnderIt() {
        // 2026-09-21：好友农场的锚点（冗余，8/8 交叉命中）与标志（框的是「回家」按钮，
        // 而农场组已重框它作身份判据锚点）**全部清除** ⇒ 清单里不会再出现两行「回家」。
        // 农场改用两个画面都有的「社交」按钮作标志，一条覆盖两边。
        val migrated = withBothFarms().retireFriendFarm()

        assertTrue(
            "产物里不再有「好友的农场」",
            migrated.stateRules.none { it.state == UiState.FRIEND_FARM },
        )
        assertTrue("好友农场的标志被清除", migrated.signals.none { it.id == "friend_farm_e1" })
        assertTrue("好友农场的冗余锚点被清除", migrated.signals.none { it.id == "friend_farm_exit" })

        val farm = migrated.stateRules.single { it.state == UiState.FARM }
        assertEquals(
            "农场的标志不受影响",
            listOf("farm_e1"),
            farm.signalNames,
        )
        assertEquals(
            "农场的身份判据锚点不受影响（头像，仍是锚点）",
            listOf("own_avatar_1"),
            farm.anchorNames.filter { it != "farm_exit" },
        )
    }

    @Test
    fun retiringKeepsTheMarkerWhenTheFarmHasNoOtherMarker() {
        // 唯一兜底：农场名下一条标志都没有（清完就判不出"这是农场"）⇒ 只删锚点、留下标志改归农场
        val noFarmMarker = data(
            signals = listOf(
                signal("friend_farm_e1"),
                CalibrationData.SignalEntry("farm_exit", fullWindow, listOf(template(3)), SignalRole.ANCHOR),
                CalibrationData.SignalEntry("friend_farm_exit", fullWindow, listOf(template(4)), SignalRole.ANCHOR),
            ),
            rules = listOf(
                CalibrationData.StateRule(UiState.FARM, emptyList(), listOf("farm_exit")),
                CalibrationData.StateRule(
                    UiState.FRIEND_FARM,
                    listOf("friend_farm_e1"),
                    listOf("friend_farm_exit"),
                ),
            ),
        )

        val migrated = noFarmMarker.retireFriendFarm()

        assertTrue(migrated.stateRules.none { it.state == UiState.FRIEND_FARM })
        assertTrue("冗余锚点照旧清除", migrated.signals.none { it.id == "friend_farm_exit" })
        assertTrue("标志被保留（绝不静默丢弃）", migrated.signals.any { it.id == "friend_farm_e1" })
        assertEquals(UiState.FARM, migrated.stateOf("friend_farm_e1"))
    }

    @Test
    fun retiringIsIdempotent() {
        // 迁移要写回文件，而写回可能被重复触发 —— 第二次必须是"什么都没发生"
        val once = withBothFarms().retireFriendFarm()
        val twice = once.retireFriendFarm()

        assertEquals(once, twice)
    }

    @Test
    fun aProductWithoutTheFriendFarmIsUntouched() {
        val plain = data()
        assertTrue("没有该状态时原样返回", plain.retireFriendFarm() === plain)
    }

    @Test
    fun retiringKeepsTheMarkerWhenTheFarmHasNoRuleYet() {
        // 农场此前连规则都没有（没标过农场标志）：搬过来的标志必须新建一条规则接住，不能丢
        val onlyFriendFarm = data(
            signals = listOf(signal("friend_farm_e1")),
            rules = listOf(CalibrationData.StateRule(UiState.FRIEND_FARM, listOf("friend_farm_e1"))),
        )

        val migrated = onlyFriendFarm.retireFriendFarm()

        assertEquals(UiState.FARM, migrated.stateOf("friend_farm_e1"))
        assertEquals(1, migrated.signals.size)
    }

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
    fun fixedMarginIsAppliedPerAxisEvenForTinyTemplates() {
        // 2026-09-29 新口径（第 288 / 289 条）：窗口 = **模板 + 每边固定余量**（标志 48 px）——
        // 小模板因此拿到**更大**的容差（旧口径下只有 ±9px），大模板的窗口不再爆炸。
        val tiny = Template(10, 6, SyntheticImages.pattern(10, 6, 1))
        val margin = SelectionWindow.MARKER_MARGIN_PX.toDouble()
        val frameW = 1440
        val frameH = 3168

        // ① 窗口 30×18 px（可证居中）⇒ 目标 106×102 比它**宽** ⇒ 只收紧不放大 ⇒ **原样**
        val window3x = SearchWindow(
            left = (720 - 15.0) / frameW,
            top = (1584 - 9.0) / frameH,
            right = (720 + 15.0) / frameW,
            bottom = (1584 + 9.0) / frameH,
        )
        val tiny1 = data(
            signals = listOf(CalibrationData.SignalEntry("launch_switch", window3x, listOf(tiny))),
            rules = emptyList(),
            frameWidth = frameW,
            frameHeight = frameH,
        )
        val clamped = tiny1.tightenedWindows(emptySet()).signals.single().window
            .pixelBounds(frameW, frameH)
        assertEquals("目标宽于原窗口 ⇒ 原样（不放大）", 30, clamped.x1 - clamped.x0)
        assertEquals(18, clamped.y1 - clamped.y0)

        // ② 窗口 200×160 px（可证居中）⇒ 收到 模板 + 两侧各 48px
        val wide = SearchWindow(
            left = (720 - 100.0) / frameW,
            top = (1584 - 80.0) / frameH,
            right = (720 + 100.0) / frameW,
            bottom = (1584 + 80.0) / frameH,
        )
        val tiny2 = data(
            signals = listOf(CalibrationData.SignalEntry("launch_switch", wide, listOf(tiny))),
            rules = emptyList(),
            frameWidth = frameW,
            frameHeight = frameH,
        )
        val bounds = tiny2.tightenedWindows(emptySet()).signals.single().window
            .pixelBounds(frameW, frameH)
        val width = bounds.x1 - bounds.x0
        val height = bounds.y1 - bounds.y0

        assertEquals("横向 = 模板(10) + 两侧各 $margin", 10 + 2 * margin, width.toDouble(), 1.0)
        assertEquals("纵向 = 模板(6) + 两侧各 $margin", 6 + 2 * margin, height.toDouble(), 1.0)
        assertTrue("仍比原始窗口小", width < 200 && height < 160)
    }

    @Test
    fun fixedMarginIsAPixelValueNotAFactorOfTheSelection() {
        // 新口径的核心：窗口**不再**随选区大小按比例走 —— 360×720 的窗口与 200×160 的窗口
        // （同一个模板）收到**同一个尺寸**（模板 + 2×48），中心不动。
        val big = SearchWindow(0.05, 0.05, 0.95, 0.95)
        val frameW = 400
        val frameH = 800
        val product = data(
            signals = listOf(CalibrationData.SignalEntry("server_select_e1", big, listOf(template(1)))),
            rules = emptyList(),
            frameWidth = frameW,
            frameHeight = frameH,
        )

        val tightened = product.tightenedWindows(emptySet()).signals.single().window
        val px = tightened.pixelBounds(frameW, frameH)
        val cx = (big.left + big.right) / 2
        val cy = (big.top + big.bottom) / 2

        assertEquals("中心不动", cx, (tightened.left + tightened.right) / 2, 1e-12)
        assertEquals("中心不动", cy, (tightened.top + tightened.bottom) / 2, 1e-12)
        assertEquals("横向 = 模板(10) + 2×48", 106.0, (px.x1 - px.x0).toDouble(), 1.0)
        assertEquals("纵向 = 模板(8) + 2×48", 104.0, (px.y1 - px.y0).toDouble(), 1.0)
    }

    @Test
    fun onlyProvablyCentredAxesAreTouched() {
        // **按轴**：`窗口 ≥ 3× 模板` 才可证"选区居中"（写入契约）⇒ 才敢收。
        // 真机教训（第 284 条）：`farm_exit` 纵向只有 2.28 倍（贴画面边被夹过）⇒ 收它会把元素挤出窗口
        //（"农场左上角的返回按钮认不出来了"）。这里用一个"横向没有余量、纵向余量很大"的窗口钉住这条。
        val noHeadroomX = SearchWindow(0.495, 0.4, 0.505, 0.6)
        val product = data(
            signals = listOf(
                CalibrationData.SignalEntry("server_select_e1", noHeadroomX, listOf(template(200))),
            ),
            rules = emptyList(),
            frameWidth = 1440,
            frameHeight = 3168,
        )

        val tightened = product.tightenedWindows(emptySet()).signals.single().window
        val px = tightened.pixelBounds(1440, 3168)

        assertEquals("横向不可证（16 < 3×10）⇒ 一格不动", 16, px.x1 - px.x0)
        assertEquals("纵向可证（633 ≥ 3×8）⇒ 收到 模板(8) + 2×48", 104.0, (px.y1 - px.y0).toDouble(), 1.0)
    }

    @Test
    fun regionOnlyAnchorsAreNeverTouchedButClickTargetsAreRescaled() {
        // 2026-09-22 用户口径：只圈区域、不做点击目标的锚点（「列表区域」/「好友名称列」）
        // **用户框多大就用多大** —— 运行时改动会让"标定页上看到的框"与"运行时用的框"不是同一个，
        // 而屏上任何地方都看不出这件事（真机实录：框 941×1533，运行时却只用 565×921）。
        val wide = SearchWindow(0.0, 0.0, 1.0, 1.0)
        val button = SearchWindow(0.1, 0.1, 0.9, 0.9)
        val area = CalibrationData.SignalEntry(
            "friend_list_area",
            wide,
            listOf(template(1)),
            SignalRole.ANCHOR,
            purpose = PatrolAnchors.FRIEND_LIST_AREA,
        )
        val column = CalibrationData.SignalEntry(
            "friend_list_name_column",
            wide,
            listOf(template(2)),
            SignalRole.ANCHOR,
            purpose = PatrolAnchors.FRIEND_LIST_NAME_COLUMN,
        )
        val visit = CalibrationData.SignalEntry(
            "friend_visit",
            button,
            listOf(template(3)),
            SignalRole.ANCHOR,
            purpose = PatrolAnchors.FRIEND_VISIT,
        )
        val product = data(
            signals = listOf(area, column, visit),
            rules = listOf(
                CalibrationData.StateRule(
                    UiState.FRIEND_LIST,
                    signalNames = emptyList(),
                    anchorNames = listOf("friend_list_area", "friend_list_name_column", "friend_visit"),
                ),
            ),
            // 真机量级的帧：48/64px 的固定余量在小帧（100×80）下会把每个窗口都兜满，看不出差别
            frameWidth = 1440,
            frameHeight = 3168,
        )

        val runtime = product.tightenedWindows(PatrolAnchors.nonLocatableAnchors).signals
            .associateBy { it.id }

        // 原样**同一个对象**（不是"缩了但没变小"）：这是这条口径最强的表述
        assertSame("区域锚点整体原样保留", area, runtime.getValue("friend_list_area"))
        assertSame("名称列同属「只圈区域」那一类", column, runtime.getValue("friend_list_name_column"))
        assertTrue(
            "例外不能扩到点击目标：它的窗口照旧重算（否则又要回到大窗口那条老路）",
            runtime.getValue("friend_visit").window.right < button.right,
        )
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
    fun rejectsIdsThatWouldBreakTheArtifactText() {
        // ID 现在由程序按语义算（用途名 / `<界面>_e[n]`），形状不用校验；但"能原样写进产物文本"必须保证
        listOf("bad|id", "bad,id", "bad=id", "bad\nid", "", " ").forEach { id ->
            assertFalse("应拒绝：$id", CalibrationData.isValidId(id))
        }
        assertTrue(CalibrationData.isValidId("3f9a1c20"))
        // 老产物里的名字也是 ID（读取兼容）→ 这种形状必须放行
        assertTrue(CalibrationData.isValidId("popup_close_anchor"))
        assertFalse(CalibrationData.isValidId("x".repeat(41)))
    }

    @Test
    fun rejectsNotesThatWouldBreakTheArtifactText() {
        // 备注是自由文本：可空、可重复、可中文，但不能带行格式保留字符
        assertTrue(CalibrationData.isValidNote(""))
        assertTrue(CalibrationData.isValidNote("大厅的农场入口"))
        assertFalse(CalibrationData.isValidNote("a|b"))
        assertFalse(CalibrationData.isValidNote("a\nb"))
        assertFalse(CalibrationData.isValidNote("x".repeat(CalibrationData.MAX_NOTE_LENGTH + 1)))
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

/**
 * 好友改名后同步产物里的**归属好友名**（2026-09-22 用户口径）。
 *
 * 运行时按"目标好友名"**精确取**头像模板（红线 3）→ 名字对不上就取不到模板、认不出"这是谁的农场"，
 * 而产物本身完全合法。所以「改哪些 / 不动哪些」必须钉死。
 */
class CalibrationFriendRenameTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)
    private val window = SearchWindow(0.0, 0.0, 1.0, 1.0)

    // `friend_avatar` 用途已随农场归属判定撤下（2026-09-23）：这里只用它当**产物里的字符串**，
    // 目的是继续覆盖"改名要同步产物里的归属好友名"这条数据层行为（旧产物里仍可能有这种记录）。
    private fun avatar(id: String, friend: String, seed: Long) = CalibrationData.SignalEntry(
        id = id,
        window = window,
        templates = listOf(Template(10, 8, SyntheticImages.pattern(10, 8, seed))),
        role = SignalRole.ANCHOR,
        purpose = "friend_avatar",
        friend = friend,
    )

    /** 两位好友各一条头像锚点 + 一条标志（标志没有归属好友名）。 */
    private fun product(): CalibrationData = CalibrationData(
        frameWidth = 100,
        frameHeight = 80,
        params = params,
        signals = listOf(
            CalibrationData.SignalEntry("farm_e1", window, listOf(Template(10, 8, SyntheticImages.pattern(10, 8, 9)))),
            avatar("friend_avatar_1", "阿娜雅", 1),
            avatar("friend_avatar_2", "Boss~~喵", 2),
        ),
        stateRules = listOf(
            CalibrationData.StateRule(
                UiState.FARM,
                listOf("farm_e1"),
                listOf("friend_avatar_1", "friend_avatar_2"),
            ),
        ),
    )

    @Test
    fun renamingFollowsOnlyThatFriendsRecord() {
        val renamed = product().renamedFriend("阿娜雅", "克克雨儿")

        assertEquals("克克雨儿", renamed.friendOf("friend_avatar_1"))
        assertEquals("别人的那条一个字都不动", "Boss~~喵", renamed.friendOf("friend_avatar_2"))
    }

    @Test
    fun renamingTouchesNothingButTheName() {
        // 改名 = 同一个人换名字：模板 / 窗口 / 用途 / ID 全部原样（划过的框仍然有效，
        // 否则等于逼用户重新标定一次）
        val before = product()
        val after = before.renamedFriend("阿娜雅", "克克雨儿")
        val origin = before.signals.single { it.id == "friend_avatar_1" }
        val now = after.signals.single { it.id == "friend_avatar_1" }

        assertEquals(origin.templates, now.templates)
        assertEquals(origin.window, now.window)
        assertEquals(origin.purpose, now.purpose)
        assertEquals(origin.role, now.role)
        assertEquals(origin.note, now.note)
        // 归属规则也不重建：改的只是"这是谁"
        assertSame(before.stateRules, after.stateRules)
    }

    @Test
    fun untouchedWhenNobodyMatches() {
        val before = product()
        assertSame(before, before.renamedFriend("不存在的人", "克克雨儿"))
    }

    @Test
    fun sameNameIsNoOp() {
        val before = product()
        assertSame(before, before.renamedFriend("阿娜雅", "阿娜雅"))
    }

    @Test
    fun markersAreNeverMatchedByName() {
        // 归属好友名的键是 `friend` 字段，**不是**记录 ID：拿标志的 ID 当旧名不该命中任何一条
        val before = product()
        assertSame(before, before.renamedFriend("farm_e1", "克克雨儿"))
    }

    @Test
    fun invalidNewNameIsRejectedByTheModelNotSilentlyWritten() {
        // 名字要能原样写进产物文本；写不进去的名字在这里就抛错（调用方转成"同步失败"提示，不静默吞）
        assertThrows(IllegalArgumentException::class.java) {
            product().renamedFriend("阿娜雅", "阿|雅")
        }
    }
}
