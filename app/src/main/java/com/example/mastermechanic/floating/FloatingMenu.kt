package com.example.mastermechanic.floating

import com.example.mastermechanic.preset.ServerChoice
import com.example.mastermechanic.preset.VisitPreset

/**
 * 悬浮窗菜单的**纯逻辑**：层级、状态转移、设置页草稿、面板尺寸判定。
 *
 * 抽出来是因为这些事**不需要安卓环境就能验**（与 `ServerListGestures`、`ServerListLabels` 同一套路）：
 * 菜单**画**成什么样只能在真机上看，但"点哪一项进哪一层""失败要不要收起""草稿怎么变""列表装不装得下"
 * 都是纯判定，一律进 JVM 单测（[FloatingMenuTest]）。
 *
 * 口径（2026-09-16，用户口径「移除跑号 / 巡查」+「配置必须能在悬浮窗里做」）：
 * - 一级四项 = **拜访（按预设直接执行）/ 换号 / 设置 / 停止**；
 * - 「设置」进的是**预设编辑页**：服务器策略（下一个 / 指定区服）+ 好友，改的是**草稿**，
 *   点「保存」才落盘、取消即丢弃 —— 用户不该为了改个好友就先退出游戏去 App 里改；
 * - 收起即清层级栈与草稿（下次展开回到根）；**失败保持展开并给原因**（不隐藏、不静默）。
 */
internal object FloatingMenu {

    /** 面板与屏幕边缘之间留的边距（上下各一份）。 */
    const val PANEL_MARGIN_DP = 8

    /** 面板宽：横竖一致（横屏虽然更宽，但面板越宽吞掉的游戏触摸越多）。 */
    const val PANEL_WIDTH_DP = 208

    /** 一级功能行高：48dp 是 Material 最小触摸目标。 */
    const val ROW_HEIGHT_DP = 48

    /**
     * 列表行的行高：**28dp**。
     *
     * 沿革：64（按"服务器一条有三行信息"定）→ 48 → 40 → 32 → 20 → 28dp（20 实测太挤）。
     * 同时是**列表限高计算**的输入。
     */
    const val PICK_ROW_HEIGHT_DP = 28

    /** 面包屑（返回）行高：与功能行同高，它自己也是一个可点目标。 */
    const val BREADCRUMB_HEIGHT_DP = 48

    /** 菜单层级。 */
    enum class Level {
        /** 根：拜访 / 换号 / 设置 / 停止。 */
        ROOT,

        /** 换号 → 选服务器（**执行型**：点一个就换过去）。 */
        PICK_SERVER,

        /** 设置页（预设编辑，改的是草稿）。 */
        CONFIG,

        /** 设置页 → 选"固定区服"。 */
        CONFIG_SERVER,

        /** 设置页 → 选好友。 */
        CONFIG_FRIEND,
    }

    /** 一级项（顺序即面板上的行序）。 */
    enum class Group {
        /** 换号：只换号（FR-04「只换号」区间，到第 6 步）。 */
        SWITCH,

        /** 拜访：**跳过换号**，在当前区服直接去见好友（FR-04「只拜访」区间）。 */
        VISIT,

        /** 换号 + 拜访：整条走完（FR-04「换号 + 拜访」区间，到第 10 步）。 */
        SWITCH_AND_VISIT,

        /** 拜访规则：在悬浮窗内编辑预设。 */
        SETTING,

        /** 停止。 */
        STOP,
    }

    /**
     * 菜单状态。
     *
     * @param reason 原因条内容（null = 不显示）：**失败时保持展开并把原因挂在这里**。
     * @param draft 设置页的**草稿**：只在这几层里有效，保存前的临时值，取消/收起即丢弃。
     */
    data class State(
        val level: Level = Level.ROOT,
        val reason: String? = null,
        val draft: VisitPreset? = null,
    ) {
        /** 设置页是否处于"正在编辑"状态（决定是否显示保存行）。 */
        val isEditing: Boolean get() = draft != null &&
            (level == Level.CONFIG || level == Level.CONFIG_SERVER || level == Level.CONFIG_FRIEND)
    }

    /** 哪一项**有下一级**；没有下一级 → null（点它就是直接执行）。 */
    fun subLevelOf(group: Group): Level? = when (group) {
        Group.SWITCH -> Level.PICK_SERVER
        Group.SETTING -> Level.CONFIG
        // 只拜访 / 换号拜访 / 停止都是「点一次直接执行」
        Group.VISIT, Group.SWITCH_AND_VISIT, Group.STOP -> null
    }

    /** 点某一项：有下一级 → 进下一级（清掉上一次的原因）；没有 → 状态不变（调用方执行动作）。 */
    fun openGroup(state: State, group: Group): State {
        val next = subLevelOf(group) ?: return state
        return state.copy(level = next, reason = null)
    }

    /** 打开设置页：以**当前预设**为草稿（改到一半取消 = 什么都没变）。 */
    fun openConfig(state: State, current: VisitPreset): State =
        state.copy(level = Level.CONFIG, reason = null, draft = current)

    /**
     * 从"选服务器"那一层里选「顺序轮换」→ 回设置页，策略切回 NEXT 并**清掉具体区服名**
     * （别留着上次的值误导：界面上会显示"服务器：顺序轮换"，但底下还存着个名字就说不清了）。
     */
    fun draftNextServer(state: State): State = state.copy(
        level = Level.CONFIG,
        reason = null,
        draft = state.draft?.copy(serverChoice = ServerChoice.NEXT, serverName = ""),
    )

    /** 进"选好友 / 选区服"的列表层。 */
    fun openConfigPick(state: State, level: Level): State = state.copy(level = level, reason = null)

    /** 从列表里选定好友 → 回设置页。 */
    fun draftFriend(state: State, friendName: String): State =
        state.copy(level = Level.CONFIG, reason = null, draft = state.draft?.copy(friendName = friendName))

    /** 从列表里选定区服 → 回设置页（顺带把策略切成"指定"）。 */
    fun draftServer(state: State, serverName: String): State = state.copy(
        level = Level.CONFIG,
        reason = null,
        draft = state.draft?.copy(serverChoice = ServerChoice.FIXED, serverName = serverName),
    )

    /**
     * 保存成功：**回到上级（根）菜单**并清掉草稿（2026-09-16 用户口径：保存后回到上一级，而不是把面板收掉）。
     * [message] 会作为一行提示留在根菜单上，让用户看得见"确实存进去了"。
     */
    fun configSaved(state: State, message: String? = null): State = State(reason = message)

    /** 返回（面包屑 / 返回键）：一律回根，草稿与原因一起丢弃。 */
    fun back(state: State): State = State()

    /** 收起面板：层级栈与草稿一并清空 —— 下次展开回到根。 */
    fun collapsed(): State = State()

    /** 失败 / 当前不可用：**停在当前层级**并把原因挂上（不收起、不隐藏、不静默，草稿保留）。 */
    fun fail(state: State, reason: String): State = state.copy(reason = reason)

    // ---------------------------------------------------------------- 尺寸（都用 dp，不碰像素与设备参数）

    /** 面板高度上限：屏高减去上下留白（横屏 360dp → 344dp）。 */
    fun panelMaxHeight(screenHeightDp: Int): Int = maxOf(0, screenHeightDp - PANEL_MARGIN_DP * 2)

    /**
     * 列表可视区的高度上限：面板上限扣掉"面板自己的其他部分"。
     * [chromeDp] 由调用方按实际布局算好传进来 —— 本文件不猜布局。
     */
    fun listMaxHeight(screenHeightDp: Int, chromeDp: Int): Int =
        maxOf(0, panelMaxHeight(screenHeightDp) - chromeDp)

    /** 列表实际高度：按内容取用，超过上限才被限住（限住了就滚动）。 */
    fun listHeight(rowCount: Int, rowHeightDp: Int, maxHeightDp: Int): Int =
        minOf(rowCount * rowHeightDp, maxHeightDp)

    /** 要不要滚动：内容比可视区高才滚。**不做分页**——分页要多一次确认，成本更高。 */
    fun needsScroll(rowCount: Int, rowHeightDp: Int, maxHeightDp: Int): Boolean =
        rowCount * rowHeightDp > maxHeightDp

    /** 可视区内能放几行（至少 1 行，别算出 0 让列表凭空消失）。 */
    fun visibleRowCount(maxHeightDp: Int, rowHeightDp: Int): Int =
        maxOf(1, maxHeightDp / rowHeightDp)
}
