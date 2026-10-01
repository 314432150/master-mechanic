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
 * 口径（2026-09-16，用户口径「移除跑号 / 巡查」+「配置必须能在悬浮窗里做」；
 * 2026-09-30 用户口径再精简一次）：
 * - 一级 = **换号（[Level.PICK_SERVER]）/ 拜访（[Level.PICK_FRIEND]）/ 换号+拜访（整行执行，
 *   行尾 ⚙ 进 [Level.CONFIG]）/ 更多（[Level.MORE]）/ 返回 App**，
 *   另有"有流程才出现"的进度块（顶）与控制行（底）；
 * - **预设编辑页**（[Level.CONFIG]，服务器策略「顺序轮换 / 指定区服」+ 好友）改的是**草稿**，
 *   点「保存」才落盘、取消即丢弃 —— 用户不该为了改个好友就先退出游戏去 App 里改；
 *   2026-09-30 起它的入口不再单占根菜单一行，而是「换号+拜访」**行尾的 ⚙**
 *   （原 `Group.SETTING` 与「拜访规则」行同日撤掉：那份预设只被「换号+拜访」用）；
 * - **判据：一级放的是"用户此刻想干的事"，不按实现归类**（2026-09-25 用户口径）——
 *   「换号」这一层只留"选一个区服切过去"；**导入服务器是维护清单数据，不在它下面**，
 *   而是收在「更多」里；
 * - 收起即清层级栈与草稿（下次展开回到根）；**失败保持展开并给原因**（不隐藏、不静默）。
 */
internal object FloatingMenu {

    /** 面板与屏幕边缘之间留的边距（上下各一份）。 */
    const val PANEL_MARGIN_DP = 8

    /** 面板宽：横竖一致（横屏虽然更宽，但面板越宽吞掉的游戏触摸越多）。 */
    const val PANEL_WIDTH_DP = 208

    /**
     * **复核页**（采集确认页）的宽度口径（2026-09-28 用户口径："将添加服务器列表悬浮窗改为半屏宽"）。
     *
     * 只有这一页有**确定宽度**：其余层级仍是"内容撑多宽就多宽"，而复核页是两列卡片 + 一行三个按钮，
     * 得先有宽度才能让两列严格等宽、三个按钮均分并对齐网格。下限 [REVIEW_MIN_WIDTH_DP] 是窄屏兜底。
     */
    const val REVIEW_MIN_WIDTH_DP = 300
    const val REVIEW_COLUMNS = 2

    /** 复核页里列与列 / 按钮与按钮之间的间隙（与 `FloatingWindow.CARD_GAP_DP` 同一值）。 */
    const val COLUMN_GAP_DP = 4

    /** 复核页底部动作按钮的个数（写入 / 放弃 / 再读一屏）。 */
    const val REVIEW_ACTION_COUNT = 3

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
        /** 根：换号 / 只拜访 / 换号拜访 / 设置 / 试读这一屏 / 添加这一屏的服务器 / 返回 App。 */
        ROOT,

        /** 换号 → 选服务器（**执行型**：点一个就换过去）。 */
        PICK_SERVER,

        /**
         * 只拜访 → 选好友（2026-09-30 加，**执行型**：点一个就去）。
         *
         * 原来「只拜访」是**点一行就按预设直接去**（2026-09-30 用户口径改掉）：
         * 预设里只有一个好友，而手上常常想临时去别人家 ⇒ 改成与「换号」对称的"选一个再走"。
         */
        PICK_FRIEND,

        /**
         * 「更多」层（2026-09-30 加，用户口径）：**低频维护类**动作的收纳处 ——
         * 主菜单只留高频（换号 / 拜访 / 换号拜访 / 拜访规则 / 更多 / 停止），
         * 「导入服务器」「试读这一屏」这类不启动流程、只维护数据的项挪进来。
         */
        MORE,

        /** 设置页（预设编辑，改的是草稿）。 */
        CONFIG,

        /** 设置页 → 选"固定区服"。 */
        CONFIG_SERVER,

        /** 设置页 → 选好友。 */
        CONFIG_FRIEND,

        /**
         * 「试读好友名」的结果页（2026-09-23）：**整块面板只放一份可滚动的报告 + 一枚返回**。
         *
         * 为什么单开一层：报告十几行、面板宽只有 208dp，挤进根菜单会把面板顶破屏幕、也看不清；
         * 单独一层能把面板高度全让给它（并且跳过了"根菜单才重画"那条限制，见 `FloatingWindow.refreshStatus`）。
         */
        OCR_RESULT,
    }

    /** 一级项（顺序即面板上的行序）。 */
    enum class Group {
        /** 换号：只换号（FR-04「只换号」区间，到第 6 步）。 */
        SWITCH,

        /** 拜访：**跳过换号**，在当前区服直接去见好友（FR-04「只拜访」区间）。 */
        VISIT,

        /**
         * 换号 + 拜访：整条走完（FR-04「换号 + 拜访」区间，到第 10 步）。
         *
         * **点整行 = 立刻按预设执行**（没有下一级）；改服务器 / 好友的入口是**行尾那颗 ⚙**
         * （2026-09-30 用户口径）—— 它进的是 [Level.CONFIG]，**不经过本枚举**
         * （原来那条 `Group.SETTING` 与根菜单的「拜访规则」行同日撤掉）。
         */
        SWITCH_AND_VISIT,

        /** 更多：低频维护类动作（导入服务器 / 暂停自动关弹窗 / 退出…）。 */
        MORE,

        /** 停止。 */
        STOP,
    }

    /**
     * 菜单状态。
     *
     * @param reason 失败原因条（null = 不显示）：**失败 / 被拒时保持展开并把原因挂在这里**，界面**红色**。
     * @param notice 提示条（null = 不显示）：**只是告诉用户一件事**（回执 / 说明），界面**中性色**；
     *   与 [reason] 共用同一条显示位、二者互斥（见 [notice] 与 [fail]）。
     * @param draft 设置页的**草稿**：只在这几层里有效，保存前的临时值，取消/收起即丢弃。
     */
    data class State(
        val level: Level = Level.ROOT,
        val reason: String? = null,
        val notice: String? = null,
        val draft: VisitPreset? = null,
    ) {
        /** 设置页是否处于"正在编辑"状态（决定是否显示保存行）。 */
        val isEditing: Boolean get() = draft != null &&
            (level == Level.CONFIG || level == Level.CONFIG_SERVER || level == Level.CONFIG_FRIEND)
    }

    /** 哪一项**有下一级**；没有下一级 → null（点它就是直接执行）。 */
    fun subLevelOf(group: Group): Level? = when (group) {
        Group.SWITCH -> Level.PICK_SERVER
        // 2026-09-30：只拜访改成**先选好友**（与换号对称）
        Group.VISIT -> Level.PICK_FRIEND
        Group.MORE -> Level.MORE
        // 换号+拜访（整行=执行，参数走行尾 ⚙）/ 停止仍是「点一次直接执行」
        Group.SWITCH_AND_VISIT, Group.STOP -> null
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
     * 进「试读好友名」结果页：清掉上一次的原因条（报告自己会说明一切）。
     *
     * 报告本身**不进 [State]** —— 它是一大段异步到货的文本，只给这一层看；
     * 存在 `FriendListOcrSignal` 里（帧线程写、悬浮窗轮询读），本层只负责"把面板让给它"。
     */
    fun openOcrResult(state: State): State = state.copy(level = Level.OCR_RESULT, reason = null)

    /**
     * 保存成功：**回到上级（根）菜单**并清掉草稿（2026-09-16 用户口径：保存后回到上一级，而不是把面板收掉）。
     * [message] 会作为一行提示留在根菜单上，让用户看得见"确实存进去了"。
     */
    fun configSaved(state: State, message: String? = null): State = State(reason = message)

    /** 返回（面包屑 / 返回键）：一律回根，草稿与原因一起丢弃。 */
    fun back(state: State): State = State()

    /**
     * **（2026-09-25 已删）** 原来这里有个 `backToLevel(state, level)`：让「一键添加服务器」的结果页
     * 返回到它自己的上一级（「换号 → 选服务器」）。
     *
     * 删掉的原因：那个动作**入口已搬到根菜单**（用户口径"添加服务器不属于换号的下级动作"）⇒
     * "上一级"就是根，既有 [back] 已经满足，再留一个"能回到任意层级"的入口只会让人以为还有别的层级关系。
     */

    /**
     * 这一页是不是**采集型**（读数页 + 最近一次请求是"读整屏并写清单"）——
     * 2026-09-25 用户两次报障后定的口径。
     *
     * 覆盖的模式（判断在 `FriendListOcrSignal.isCaptureFlow`）：**「添加这一屏的服务器」** ——
     * 它的操作流是"读一屏 → 在游戏里滚一屏 → 再读一屏"，
     * 所以那个结果页有两个特殊待遇：
     *
     * 1. **不因"点面板之外"收起**：`FLAG_WATCH_OUTSIDE_TOUCH` 送来的 `ACTION_OUTSIDE` 分不清
     *    "点击外部"与"在游戏里滑动" —— 用户一滚屏，面板就被当成"点了外面"收掉（真机日志：
     *    `点了菜单面板之外，已收起` ⇒ `悬浮窗已收起（操作成功结束）`）。这一页本来就是**要你对着
     *    游戏画面干活**的页，滚屏是正常操作，不能算"点外部"。
     * 2. **收起后再展开要回到这一页**（而不是回根菜单）：无论因为哪种路径收起，用户回来时
     *    都还想点「再读一屏」——回根就等于把那个按钮藏了。
     *
     * 判据 = "停在读数页（[Level.OCR_RESULT]）**且**最近一次请求是采集型"：
     * 试读（好友名 / 服务器名）的结果页不享受这两条待遇（它没有"再读一屏"这条流）。
     *
     * ⚠ `captureMode` 由调用方从 `FriendListOcrSignal.isCaptureFlow` 得出（别再手写
     * `mode == CAPTURE_SERVERS`：2026-09-25 采集区号就是漏了那个等式，一滚屏面板就没了）。
     */
    fun isCaptureResultPage(state: State, captureMode: Boolean): Boolean =
        captureMode && state.level == Level.OCR_RESULT

    /** 回到「添加这一屏的服务器」读数页（保留层级，只清掉可能挂着的原因条 / 提示条）。 */
    fun resumeCaptureResult(state: State): State = state.copy(reason = null, notice = null)

    /** 收起面板：层级栈与草稿一并清空 —— 下次展开回到根。 */
    fun collapsed(): State = State()

    /** 失败 / 当前不可用：**停在当前层级**并把原因挂上（不收起、不隐藏、不静默，草稿保留）。 */
    fun fail(state: State, reason: String): State = state.copy(reason = reason, notice = null)

    /**
     * **提示 / 回执**（2026-10-01 用户口径："提示类信息不应该使用红色"）。
     *
     * 与 [fail] 是**同一条显示通道**（都停在当前层级、把那句话挂在菜单底部），区别只在**措辞的语义**：
     * - [fail]：**你被拒了 / 当前不可用** ⇒ 红（要你去看、去处理）；
     * - 本函数：**只是告诉你一件事**（"提示位置已重置"、"当前没有进行中的巡查"）⇒ 中性色。
     *
     * 两者互斥（同一条位置）：挂提示会清掉失败原因，反之亦然 —— 否则用户会同时看到
     * "刚才那件事失败了"和"这件事已经做完"，不知道哪句是当前状态。
     */
    fun notice(state: State, text: String): State = state.copy(reason = null, notice = text)

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

    // ---------------------------------------------------------------- 复核页（两列卡片 + 半屏宽）

    /**
     * **采集复核页的面板宽度 = 半屏**（2026-09-28 用户口径："将添加服务器列表悬浮窗改为半屏宽"）。
     *
     * 为什么定死半屏而不是继续"内容撑多宽就多宽"：这一页是**两列卡片 + 一行三个按钮**，
     * 内容宽度由卡片数决定、随时会变；而两列要真正对齐、按钮要均分，就得先有一个确定的宽度。
     * 半屏正好：游戏那条服务器列表本身就是两列，复核页用同样的读法看起来最顺。
     *
     * 下限 [REVIEW_MIN_WIDTH_DP] 给窄屏兜底（真机横屏 792dp → 半屏 396dp）。
     */
    fun reviewPanelWidth(screenWidthDp: Int): Int =
        maxOf(REVIEW_MIN_WIDTH_DP, screenWidthDp / 2)

    /** 复核页**内容区**宽度：面板宽扣掉它自己的左右内边距（[paddingDp] 由调用方按实际布局传，本文件不猜）。 */
    fun reviewInnerWidth(screenWidthDp: Int, paddingDp: Int): Int =
        maxOf(0, reviewPanelWidth(screenWidthDp) - paddingDp * 2)

    /**
     * 复核页里**一张卡片**的宽度：内容区**两等分**（去掉列间隙）。
     *
     * 卡片宽是"半屏"的函数而不是常量：面板定了宽，两列才永远严格等宽（真机横屏 ⇒ ≈192dp）。
     */
    fun reviewCardWidth(screenWidthDp: Int, paddingDp: Int): Int {
        val inner = reviewInnerWidth(screenWidthDp, paddingDp)
        return maxOf(0, (inner - COLUMN_GAP_DP * (REVIEW_COLUMNS - 1)) / REVIEW_COLUMNS)
    }

    /** 复核页底部**一个动作按钮**的宽度：内容区**三等分**（去掉按钮之间的间隙）。 */
    fun reviewActionWidth(screenWidthDp: Int, paddingDp: Int): Int {
        val inner = reviewInnerWidth(screenWidthDp, paddingDp)
        return maxOf(0, (inner - COLUMN_GAP_DP * (REVIEW_ACTION_COUNT - 1)) / REVIEW_ACTION_COUNT)
    }

    // ---------------------------------------------------------------- 「关弹窗受阻」的原因（纯逻辑）

    /**
     * 根菜单要不要显示「关弹窗受阻」那一行；返回要显示的原因，null = 不显示。
     *
     * 用户口径（2026-09-29）："**把「关弹窗受阻」的原因也显示在悬浮窗菜单里**"。
     * 背景：顶部标签只能放 12 个字（[FloatingLabelText.MAX_CHARS]）⇒ 那里只能写「关弹窗受阻」，
     * 而"为什么关不掉"一直只落在日志里 ⇒ 玩家在游戏里**根本看不到原因**，只能看着弹窗不消失。
     *
     * 这里只做"要不要显示"这一层（纯逻辑，可单测）：**空/纯空白 ⇒ 不显示**（否则菜单里会出现一行
     * 只有冒号没有内容的垃圾行）；文案拼装（前缀 + 原因）留给 `FloatingWindow`（那里才有 Context）。
     */
    fun popupBlockedNotice(reason: String?): String? = reason?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- 「重新授权采集」入口（纯逻辑）

    /**
     * 根菜单要不要显示「**⟳ 重新授权采集**」那一行。
     *
     * 用户口径（2026-10-01，M5 期间插入）：**"在悬浮窗菜单里插一个「一键重新授权采集」入口"** ——
     * 起因是帧流静默停更（[FrameFreshness]）时，玩家在游戏里只能看着一轮跑号卡住，
     * 得"收起悬浮窗 → 回 App → 找授权页 → 点建立采集"四步才回到游戏。
     *
     * 为什么**不是常驻行**：菜单每多一行面板就高一截，而**面板长高会让正在点的行整体位移** ⇒
     * 误点相邻项（见 [ROW_HEIGHT_DP] 与 `FloatingWindow.controlRow` 那段说明）。
     * 下面三个判据恰好就是"此刻眼睛 / 会话已经出问题"，那时多一行才值得：
     * - ① **采集会话没了**（用户从系统侧停掉 / 系统回收）：程序什么都做不了，重建采集是**唯一**出路；
     * - ② **画面久未更新**（[FrameFreshness.isStale]）：识别读的是重放的旧帧 ⇒ 老处置只能写"回 App 重建"；
     * - ③ **这次授权没被投喂**（[FrameFreshness.isStarved]）：入口照给（用户可能还想再试一次），
     *   但原因行会说清"**只重新授权通常没用、要换进程**"（2026-10-01 真机实证）。
     *
     * 正常跑着时三个都为假 ⇒ 面板与从前**一模一样**（零位移、零额外行）。
     * （另有「更多」层里的一条**常驻**同类入口：改过标定产物之后"下次建立采集会话时生效"
     * 需要一条稳定的重建通路，见 `FloatingWindow.renderMore`。）
     */
    fun shouldOfferReauthorize(
        captureActive: Boolean,
        frameStale: Boolean,
        frameStarved: Boolean,
    ): Boolean = !captureActive || frameStale || frameStarved
}
