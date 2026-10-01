package com.example.mastermechanic.ui

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.calibration.CalibrationSignals
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolAnchors
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 产物清单的分组与「标志 + 锚点」配对（纯逻辑）。
 *
 * 配对判据 = **元素 ID 相等**（锚点 = 用途名，其余 = `<界面>_e[n]`；2026-09-19 用户口径）：同一个 ID 的标志与锚点
 * 是同一个东西的两面 → 合成一行；ID 不同就是两个东西 → 各占一行，与前缀、像素比对无关。
 */
class CalibrationArtifactGroupsTest {

    private val params = MatchParams(0.85, 0.10, 5)

    private fun upsert(
        current: CalibrationData?,
        id: String,
        state: UiState,
        role: SignalRole = SignalRole.MARKER,
        window: SearchWindow = SearchWindow(0.1, 0.2, 0.5, 0.6),
        size: Pair<Int, Int> = 10 to 8,
        purpose: String? = null,
        friend: String? = null,
        note: String = "",
    ) = CalibrationSignals.upsert(
        current = current,
        id = id,
        state = state,
        window = window,
        template = Template(size.first, size.second, SyntheticImages.pattern(size.first, size.second, 1L)),
        params = params,
        frameWidth = 1000,
        frameHeight = 800,
        role = role,
        purpose = purpose,
        friend = friend,
        note = note,
    )

    @Test
    fun markerAndAnchorWithTheSameIdMergeIntoOneRow() {
        // 同一次框选写出的两条记录**共用同一个 ID** → 一行，行里的 ID 就是那个元素
        var data: CalibrationData? = null
        data = upsert(data, "3f9a1c20", UiState.HALL)
        data = upsert(data, "3f9a1c20", UiState.HALL, SignalRole.ANCHOR)

        val group = CalibrationArtifactGroups.of(data!!).single { it.state == UiState.HALL }
        assertEquals(1, group.elements.size)
        assertEquals("3f9a1c20", group.elements.single().id)
        assertEquals(2, group.elements.single().items.size)
    }

    @Test
    fun differentIdsStayOnSeparateRows() {
        // ID 不同 = 两个不同的东西 → 各占一行（删除粒度才不会错）
        var data: CalibrationData? = null
        data = upsert(data, "aaaaaaaa", UiState.HALL)
        data = upsert(data, "bbbbbbbb", UiState.HALL, SignalRole.ANCHOR)

        val group = CalibrationArtifactGroups.of(data!!).single { it.state == UiState.HALL }
        assertEquals(2, group.elements.size)
    }

    @Test
    fun aLonelyRecordIsStillARow() {
        // 只标了锚点（没标标志）也是合法的一行：另一侧为 null，不是错误状态
        var data: CalibrationData? = null
        data = upsert(data, "farm_exit", UiState.FARM, SignalRole.ANCHOR)
        val element = CalibrationArtifactGroups.of(data!!).single().elements.single()
        assertNull(element.marker)
        assertEquals("farm_exit", element.id)
    }

    @Test
    fun groupsFollowTheFlowOrderAndOnlyCoverStatesInUse() {
        var data: CalibrationData? = null
        data = upsert(data, "popup_close", UiState.ACTIVITY_POPUP)
        data = upsert(data, "farm_exit", UiState.FARM, SignalRole.ANCHOR)
        data = upsert(data, "hall", UiState.HALL)
        assertEquals(
            listOf(UiState.ACTIVITY_POPUP, UiState.HALL, UiState.FARM),
            CalibrationArtifactGroups.of(data!!).map { it.state },
        )
    }

    @Test
    fun rowCountEqualsTheSumOfEveryGroupCount() {
        // 「总条数」必须与各分组标题 / 筛选按钮上的数字**同源**（都数展示行）。分组怎么拆都一样。
        var data: CalibrationData? = null
        data = upsert(data, "hall", UiState.HALL)
        data = upsert(data, "hall", UiState.HALL, SignalRole.ANCHOR)
        data = upsert(data, "farm_exit", UiState.FARM, SignalRole.ANCHOR)
        data = upsert(data, "popup_close", UiState.ACTIVITY_POPUP)
        data = upsert(data, "return_lobby", UiState.SCENE_RETURN_CONFIRM, SignalRole.ANCHOR)

        val groups = CalibrationArtifactGroups.of(data!!)

        // 2026-10-01 起**一行的粒度 = 一条记录**：hall 两条（标志 + 锚点）+ 另外三条各一条 = 5 行
        assertEquals(5, CalibrationArtifactGroups.rowCount(groups))
        assertEquals(
            "总条数必须等于各分组之和（否则用户会看到「总条数对不上分组合计」）",
            groups.sumOf { CalibrationArtifactGroups.rowsOf(it).size },
            CalibrationArtifactGroups.rowCount(groups),
        )
        assertEquals("每个角色一行 ⇒ 总行数恰好等于记录条数", data.signals.size, CalibrationArtifactGroups.rowCount(groups))
    }

    @Test
    fun totalCountIsMarkersPlusAnchors() {
        // 用户 2026-10-01 口径："产物总条数统计为标记+锚点的总条数"。
        // 不是"数了几行碰巧相等"，而是总数**由两个角色各自数出来再相加**（Counts.total 就是这么定义的）
        // —— 将来产物里再多一种角色，这里也不会静默漏算。
        var data: CalibrationData? = null
        data = upsert(data, "hall", UiState.HALL)
        data = upsert(data, "hall", UiState.HALL, SignalRole.ANCHOR)
        data = upsert(data, "farm_exit", UiState.FARM, SignalRole.ANCHOR)
        data = upsert(data, "popup_close", UiState.ACTIVITY_POPUP)
        data = upsert(data, "return_lobby", UiState.SCENE_RETURN_CONFIRM, SignalRole.ANCHOR)

        val groups = CalibrationArtifactGroups.of(data!!)
        val counts = CalibrationArtifactGroups.roleCounts(groups)

        assertEquals(
            "标志数 = 产物里 marker 记录数",
            data.signals.count { it.role == SignalRole.MARKER },
            counts.marker,
        )
        assertEquals(
            "锚点数 = 产物里 anchor 记录数",
            data.signals.count { it.role == SignalRole.ANCHOR },
            counts.anchor,
        )
        assertEquals("总条数 = 标志 + 锚点", counts.marker + counts.anchor, counts.total)
        assertEquals("rowCount 就是「标志 + 锚点」", counts.total, CalibrationArtifactGroups.rowCount(groups))
        assertEquals("也等于产物里的记录总数（一条不漏）", data.signals.size, counts.total)
        assertEquals("用例里的实际数字：标志 2 / 锚点 3", 2 to 3, counts.marker to counts.anchor)
    }

    @Test
    fun rowsPutMarkersBeforeAnchors() {
        // 用户 2026-10-01 口径："同一个状态的产物按先标志后锚点排序"。
        // 不排序时的形状是"按元素交错"（标志、锚点、标志、锚点）—— 分组里一眼数不出有几个标志。
        var data: CalibrationData? = null
        data = upsert(data, "hall_farm", UiState.HALL, SignalRole.ANCHOR)
        data = upsert(data, "hall_settings", UiState.HALL, SignalRole.ANCHOR)
        data = upsert(data, "hall_settings", UiState.HALL)
        data = upsert(data, "hall_farm", UiState.HALL)

        val group = CalibrationArtifactGroups.of(data!!).single { it.state == UiState.HALL }
        val rows = CalibrationArtifactGroups.rowsOf(group)

        assertEquals(
            "组内先排全部标志、再排全部锚点",
            listOf(SignalRole.MARKER, SignalRole.MARKER, SignalRole.ANCHOR, SignalRole.ANCHOR),
            rows.map { it.role },
        )
        // 同一角色内保持原有相对顺序（元素顺序 = 该元素最早那条记录在文件里的位置）：
        // hall_farm 的那条更早（第 1 条写入）⇒ 它的标志排在 hall_settings 的标志前面
        assertEquals(
            listOf("hall_farm", "hall_settings"),
            rows.filter { it.role == SignalRole.MARKER }.map { it.item.entry.id },
        )
        // 排序只影响展示，不影响计数（两个角色各 2 条）
        assertEquals(2, CalibrationArtifactGroups.roleCounts(listOf(group)).marker)
        assertEquals(2, CalibrationArtifactGroups.roleCounts(listOf(group)).anchor)
    }

    @Test
    fun aDoubleRoleElementTakesTwoRowsOneRecordEach() {
        // 2026-10-01 用户口径「标志和锚点分两行显示」：同一个元素的两条记录各占一行，
        // 元素本身仍是**一个**（备注 / 用途按记录各说各的，见 `SignalNames.defaultNoteFor`）。
        var data: CalibrationData? = null
        data = upsert(data, "3f9a1c20", UiState.HALL)
        data = upsert(data, "3f9a1c20", UiState.HALL, SignalRole.ANCHOR)

        val groups = CalibrationArtifactGroups.of(data!!)
        val element = groups.single().elements.single()
        val rows = CalibrationArtifactGroups.rowsOf(groups.single())

        assertEquals("清单里是两行", 2, CalibrationArtifactGroups.rowCount(groups))
        assertEquals("元素仍然只有一个", 1, groups.single().elements.size)
        assertEquals("两行各对应一条记录", 2, element.items.size)
        assertEquals(2, rows.size)
        assertEquals(
            "两行的键必须不同（左滑删除按它索引，否则两条会互相顶掉）",
            2,
            rows.map { it.key }.toSet().size,
        )
        assertEquals(
            "一行标志、一行锚点",
            listOf(SignalRole.MARKER, SignalRole.ANCHOR),
            rows.map { it.role },
        )
    }

    @Test
    fun everyRecordAppearsExactlyOnce() {
        var data: CalibrationData? = null
        data = upsert(data, "hall", UiState.HALL)
        data = upsert(data, "hall", UiState.HALL, SignalRole.ANCHOR)
        data = upsert(data, "farm_exit", UiState.FARM, SignalRole.ANCHOR)
        val items = CalibrationArtifactGroups.of(data!!).flatMap { it.elements }.flatMap { it.items }
        assertEquals(data.signals.size, items.size)
        assertEquals(
            data.signals.map { it.id to it.role }.toSet(),
            items.map { it.entry.id to it.entry.role }.toSet(),
        )
    }

    @Test
    fun deleteGranularityIsTheWholeRow() {
        // 删除粒度 = 一行 = 一个元素（两条一起删）；行的 id 就是删除时要给的那个
        var data: CalibrationData? = null
        data = upsert(data, "3f9a1c20", UiState.HALL)
        data = upsert(data, "3f9a1c20", UiState.HALL, SignalRole.ANCHOR)
        data = upsert(data, "return_lobby", UiState.SCENE_RETURN_CONFIRM, SignalRole.ANCHOR)
        val rows = CalibrationArtifactGroups.of(data!!).flatMap { it.elements }
        assertEquals(2, rows.size)
        assertEquals(
            listOf("3f9a1c20"),
            rows.filter { it.items.size == 2 }.map { it.id },
        )
    }

    @Test
    fun noteAndPurposeSurfaceOnTheRow() {
        var data: CalibrationData? = null
        data = upsert(data, "3f9a1c20", UiState.HALL, role = SignalRole.ANCHOR, purpose = "hall_farm")
        data = upsert(data, "3f9a1c20", UiState.HALL, note = "大厅的农场入口")
        val element = CalibrationArtifactGroups.of(data!!).single().elements.single()

        assertEquals("3f9a1c20", element.id)
        assertEquals("hall_farm", element.purpose)
        assertEquals("大厅的农场入口", element.note)
    }

    @Test
    fun primaryTextPrefersNoteThenPurposeThenSize() {
        // 行的主文本：备注（用户自己写的）> 用途（干什么用的）> 模板尺寸（最后兜底）
        var data: CalibrationData? = null
        data = upsert(data, "3f9a1c20", UiState.HALL, role = SignalRole.ANCHOR, purpose = "hall_farm")
        val purposeOnly = CalibrationArtifactGroups.of(data!!).single().elements.single()
        assertEquals("农场入口", CalibrationArtifactGroups.primaryText(purposeOnly, UiState.HALL))

        val withNote = CalibrationArtifactGroups.of(
            upsert(data, "3f9a1c20", UiState.HALL, note = "左上角那个"),
        ).single().elements.single()
        assertEquals("左上角那个", CalibrationArtifactGroups.primaryText(withNote, UiState.HALL))

        // 既没备注也没用途（活动弹窗的标志）→ 退回尺寸，至少两行之间还有东西可看
        val bare = CalibrationArtifactGroups.of(
            upsert(null, "popup_close", UiState.ACTIVITY_POPUP),
        ).single().elements.single()
        assertEquals("10×8", CalibrationArtifactGroups.primaryText(bare, UiState.ACTIVITY_POPUP))
    }

    @Test
    fun purposeLabelFallsBackToTheRawName() {
        // 用途表里有的查中文；查不到的（手工改过的产物）如实显示原名，不编造
        assertEquals("农场入口", CalibrationArtifactGroups.purposeLabel(UiState.HALL, "hall_farm"))
        assertEquals("made_up", CalibrationArtifactGroups.purposeLabel(UiState.HALL, "made_up"))
    }

    @Test
    fun aLegacyFriendFarmRecordJoinsTheFarmGroup() {
        // 2026-09-21 两个农场合一：旧产物里写着 FRIEND_FARM 的记录（标志 / 返回 / 好友入口）
        // **并入「农场」组** —— 既不再单列一组，也不会从清单里消失（看不见 ≠ 不存在，那更危险）
        var data: CalibrationData? = null
        data = upsert(data, "farm_home", UiState.FARM, role = SignalRole.ANCHOR)
        data = upsert(data, "friend_farm", UiState.FRIEND_FARM)
        data = upsert(data, "friend_farm_exit", UiState.FRIEND_FARM, role = SignalRole.ANCHOR)

        val groups = CalibrationArtifactGroups.of(data!!)

        assertEquals(
            "不应再有「好友的农场」这一组",
            listOf(UiState.FARM),
            groups.map { it.state },
        )
        assertEquals(
            setOf("farm_home", "friend_farm", "friend_farm_exit"),
            groups.single().elements.map { it.id }.toSet(),
        )
        // 一条都不丢（与上一条 everyRecordAppearsExactlyOnce 同一口径）
        assertEquals(data.signals.size, groups.flatMap { it.elements }.flatMap { it.items }.size)
    }

    @Test
    fun aFriendAvatarRowShowsWhoseAvatarItIs() {
        // 2026-09-21 用户口径：带归属好友名的记录要显示成「某某的头像」。
        // 2026-09-23：`friend_avatar` 用途已随农场归属判定撤下 —— 这里只用它当**产物里的字符串**
        // （数据层不查用途表，旧产物里仍可能有这种记录，清单得照样显示对）。
        var data: CalibrationData? = null
        data = upsert(
            data,
            "friend_avatar_1",
            UiState.FARM,
            role = SignalRole.ANCHOR,
            purpose = "friend_avatar",
            friend = "Boss~~喵",
        )
        val element = CalibrationArtifactGroups.of(data!!).single().elements.single()

        assertEquals("Boss~~喵", element.friend)
        assertEquals("Boss~~喵的头像", CalibrationArtifactGroups.primaryText(element, UiState.FARM))
    }

    @Test
    fun aPurposeWithoutAFriendStillShowsThePurposeLabel() {
        // 没写归属好友名的照旧显示用途的**中文名** —— 别把"给带好友名的记录加名字"做成一刀切
        assertEquals(
            "好友入口",
            CalibrationArtifactGroups.purposeText(UiState.FARM, PatrolAnchors.FARM_FRIENDS, null),
        )
        // 用途表里查不到的（旧产物里的 retired 用途，如 `own_avatar`）如实显示**原名**，不编造
        assertEquals(
            "own_avatar",
            CalibrationArtifactGroups.purposeText(UiState.FARM, "own_avatar", null),
        )
    }
}
