package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.DetectionRecord
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.Verdict
import com.example.mastermechanic.recognition.Template
import java.util.Locale

/**
 * 标注选区 → 搜索窗口（T1-5b 起，T1-5l 随草稿模型一并迁至此，纯逻辑）：标定流程采用的窗口起点策略。
 *
 * 窗口 = 选区矩形四周各外扩**固定像素**（[MARKER_MARGIN_PX] / [ANCHOR_MARGIN_PX]），并夹到帧内。
 * 局部窗口既满足性能与防误匹配需要，也是 FR-01「仅右上角局部范围」语义的载体 ——
 * 最终值写入产物（**比例**，随帧尺寸换算），可在产物文本中人工微调。
 *
 * ## 为什么是"固定像素"，而不是旧口径的"各外扩 1 倍选区"（2026-09-29 改，第 288 条）
 *
 * 旧口径（各方向外扩 1 倍选区 ⇒ 窗口 ≈ 3×3）用了很久，但真机把两个毛病都暴露了：
 *
 * 1. **容差必须以像素计**：元素在帧与帧之间漂多少像素，与它**多大无关** ——
 *    9×15 的小按钮在"1 倍"下只有 **±9px**（启动页渲染差 2px 就连 miss 两次），
 *    而 908×174 的大横幅有 **±900px**（纯浪费）。实测漂移量级：`hall_settings` ≈**20px**；
 *    `farm_exit` 从 ±54.5 砍到 ±32.5 就认不出（⇒ 那一类需要 >50px）；
 * 2. **成本是"四次方"的**：匹配成本 ≈ (窗口面积/4) × (模板面积/2)，而窗口 ∝ 选区
 *    ⇒ 成本 ∝ **选区面积的平方**。真机实录：把"开局送英雄!"框成 155×168 ⇒ 单信号 **2064ms/轮**
 *    ⇒ 整轮 2.2s ⇒ 跑号等画面切换超时中止（第 283 条）。
 *
 * ⇒ 现在**余量固定**：小元素拿到的容差反而更大（好事），大元素的窗口不再爆炸（成本回到**线性**）。
 *
 * 两个数字的依据（都在真机上有账）：
 * - [MARKER_MARGIN_PX] = 48（标志）：实测漂移 ≈20px + 余量；标志在**很多状态**下每轮都要搜，成本"每轮都付"；
 * - [ANCHOR_MARGIN_PX] = 64（锚点）：锚点是"定位 + 点击"的落点，认不出 = **整步停摆**
 *   （`farm_exit` 实测需要 >54px）；且它**只在自己那个状态**被定位，成本"偶尔付一次"。
 *
 * 选区**恒在窗口正中**（四周余量相同；贴边时对称取小）—— 这是运行时"按比例收紧"与成本护栏
 * 能安全工作的前提（偏心时朝窗口中心收会把元素挤出窗口，见 [CalibrationData.tightenedWindows]）。
 */
object SelectionWindow {

    /**
     * **标志**四周各外扩的像素（见对象注释"两个数字的依据"）。
     *
     * ⚠ 改这个数之前先想清楚两件事：① 它直接决定"元素偏多少还能搜到"（[CalibrationData] 的
     * `MIN_ABSOLUTE_MARGIN_PX` 只是运行时收紧的**下限**，静态口径由这里定）；
     * ② 它**不随元素大小变**——这正是本次改口径的目的（容差与元素尺寸无关）。
     */
    const val MARKER_MARGIN_PX = 48

    /** **锚点**四周各外扩的像素（比标志大：认不出的代价是整步停摆；见对象注释）。 */
    const val ANCHOR_MARGIN_PX = 64

    /** 一条记录按**角色**该拿多少像素余量（唯一入口，别在调用点写死数字）。 */
    fun marginPxFor(role: SignalRole): Int =
        if (role == SignalRole.ANCHOR) ANCHOR_MARGIN_PX else MARKER_MARGIN_PX

    fun forSelection(
        frameWidth: Int,
        frameHeight: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        marginPx: Int,
    ): SearchWindow {
        requireSelection(frameWidth, frameHeight, x0, y0, x1, y1)
        require(marginPx >= 0) { "搜索窗口余量不能为负：$marginPx" }
        // **对称取小**：两侧留一样多（贴边时余量只能到边为止）⇒ **选区恒在窗口正中**。
        //
        // ⚠ 2026-09-29 两次真机教训合成这一版：
        // 1. 原实现各边独立 `coerceAtMost/AtLeast`（**裁剪**）：贴边时只有贴边那侧被切掉 ⇒ 选区偏心
        //    ⇒ 运行时收紧（朝**窗口中心**收 0.45）会把选区里的模板扫丢 —— 真机实录：
        //    模板 908×174 真实位置 x=2075，收紧后窗口只到 x=2617，放下它需要 x=2983 ⇒ 最高分 **0.25**；
        // 2. 改成"整体平移"后确实不偏心了，但**贴边时窗口仍会很大且不必要地大**；
        //    更关键的是：为了不错扫，当时把收紧整条关掉 ⇒ 搜索面积回到 100% ⇒ 模板匹配涨约 25 倍
        //    ⇒ 用户点"写入"后**卡十几秒**（真机："为什么点了写入标志，还是卡住了"）。
        // ⇒ 口径：余量**对称地取**（两侧一样多，最多 [marginPx]），选区永远在窗口正中。
        val marginXR = minOf(marginPx, x0, frameWidth - x1)
        val marginYR = minOf(marginPx, y0, frameHeight - y1)
        return SearchWindow(
            left = (x0 - marginXR).toDouble() / frameWidth,
            top = (y0 - marginYR).toDouble() / frameHeight,
            right = (x1 + marginXR).toDouble() / frameWidth,
            bottom = (y1 + marginYR).toDouble() / frameHeight,
        )
    }

    /**
     * **区域型锚点**的窗口：**窗口 = 选区本身**（不外扩）。
     *
     * ## 为什么要跟 [forSelection] 分开（2026-09-23 真机定位的根因）
     *
     * 外扩 1 倍选区的那圈余量是为**模板匹配**准备的：元素在不同帧之间会漂移，搜索窗口得留滑动余地。
     * 但「列表区域」/「好友名称列」这两条是**只圈区域、不做模板匹配**的锚点
     * （`PatrolAnchors.nonLocatableAnchors`）：它们的窗口被当作**文字识别的输入区域**，
     * 外扩 1 倍 = 把用户框的"名字那一列"变成 **3 倍宽** ⇒ **永远等于没裁**。
     *
     * 真机实录：用户框了 510×57 的一行名字文字（模板预览完全正确），产物里却写着 1530×172
     * （x 从屏幕中线一直到右边缘）⇒ 与列表区域求交集之后等于列表区域自身，
     * 表现就是"识别到的内容不只有这一列"。加载时的收紧
     * （[CalibrationData.tightenedWindows] 的 `exemptPurposes`）早就豁免了这两条
     * （2026-09-22 用户口径"**用户框多大就用多大**"）—— **写入这一端漏了同一条口径**。
     */
    fun exact(
        frameWidth: Int,
        frameHeight: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): SearchWindow {
        requireSelection(frameWidth, frameHeight, x0, y0, x1, y1)
        return SearchWindow(
            left = x0.toDouble() / frameWidth,
            top = y0.toDouble() / frameHeight,
            right = x1.toDouble() / frameWidth,
            bottom = y1.toDouble() / frameHeight,
        )
    }

    /**
     * 写入路径的**唯一**窗口策略入口：按用途挑外扩还是不外扩。
     *
     * @param purpose 这条记录的锚点用途（标志 / 无用途锚点为 null）
     * @param regionPurposes 区域型用途集合，由调用方给 `PatrolAnchors.nonLocatableAnchors` ——
     *   本包不认识这些用途名，与 [CalibrationData.tightenedWindows] 的 `exemptPurposes` **同一套传参口径**
     *   （故意不设默认值：漏传会让"只圈区域"的锚点又回到 3 倍宽那条老路上，而且是静默的）。
     * @param marginPx 非区域型记录四周各外扩的像素（由调用方按角色给 [marginPxFor]）。
     *   **故意不设默认值**：给默认值就会掩盖"这一条到底按哪个角色定的余量"，
     *   而标志与锚点的余量**本来就不该一样**（见对象注释）。
     */
    fun forPurpose(
        purpose: String?,
        regionPurposes: Set<String>,
        marginPx: Int,
        frameWidth: Int,
        frameHeight: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): SearchWindow = if (purpose != null && purpose in regionPurposes) {
        exact(frameWidth, frameHeight, x0, y0, x1, y1)
    } else {
        forSelection(frameWidth, frameHeight, x0, y0, x1, y1, marginPx)
    }

    private fun requireSelection(frameWidth: Int, frameHeight: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        require(frameWidth > 0 && frameHeight > 0) { "帧尺寸必须为正：${frameWidth}x$frameHeight" }
        require(x0 >= 0 && y0 >= 0 && x1 <= frameWidth && y1 <= frameHeight && x0 < x1 && y0 < y1) {
            "选区 [$x0,$x1)×[$y0,$y1) 超出帧 ${frameWidth}x$frameHeight 或为空"
        }
    }
}

/**
 * 标定产物的直接写入（T1-5l，纯逻辑）：**产物即唯一数据源**，不再有进程内草稿。
 *
 * 真机反馈定稿口径：框选 + 归属状态确定即写产物（框完一条就落盘），同名信号**覆盖**；
 * 因此本对象只提供「覆盖写入 / 删除 / 改参数」三种原子编辑，产物校验仍由 [CalibrationData]
 * 构造时全量执行（构造即合法），写盘由 [CalibrationStore] 负责。
 *
 * 不含任何安卓依赖与设备绑定常量，可在 JVM 离线单测。
 */
object CalibrationSignals {

    /** 参数编辑的默认起点（无产物时使用；识别运行时只使用产物中的数值，红线 4）。 */
    const val DEFAULT_THRESHOLD_TEXT = "0.85"
    const val DEFAULT_MARGIN_TEXT = "0.10"
    const val DEFAULT_PEAK_DISTANCE_TEXT = "5"

    /** 默认参数（无产物且编辑框内容非法时的兜底；与三个 `DEFAULT_*_TEXT` 同源，只用于写入产物）。 */
    fun defaultParams(): MatchParams = requireNotNull(
        parseParams(DEFAULT_THRESHOLD_TEXT, DEFAULT_MARGIN_TEXT, DEFAULT_PEAK_DISTANCE_TEXT),
    ) { "内置默认参数非法（常量应始终合法）" }

    /**
     * 几何一致性检查（T1-5l）：产物已记录的标定帧几何必须与本次标定帧一致。
     *
     * 不一致说明两次标定发生在不同画布几何下（横 / 竖画布会话，T1-11）——窗口按整幅比例记录、
     * 模板按像素记录，混几何会让同一产物内的信号互相矛盾。返回 null = 一致（或尚无产物）。
     */
    fun geometryError(current: CalibrationData?, frameWidth: Int, frameHeight: Int): String? = when {
        current == null -> null
        current.frameWidth != frameWidth || current.frameHeight != frameHeight ->
            "帧 ${frameWidth}x$frameHeight 与产物 ${current.frameWidth}x${current.frameHeight} 几何不一致"
        else -> null
    }

    /**
     * 新增或覆盖一条记录（同名**整体替换**）：模板与搜索窗口取本次标定值，不追加、不合并；
     * 归属状态与角色随之更新（同名换状态 / 换角色无需先删除）。
     *
     * 角色（T2-3）：默认 [SignalRole.MARKER]（界面标志）；标锚点时传 [SignalRole.ANCHOR]——
     * 同一元素两种角色要**两次写入两条记录**，不合并（§2.1）。
     *
     * 非法输入抛 [IllegalArgumentException]（几何不一致 / 未知状态 / 帧尺寸非正），由界面转为提示文本。
     */
    fun upsert(
        current: CalibrationData?,
        id: String,
        state: UiState,
        window: SearchWindow,
        template: Template,
        params: MatchParams,
        frameWidth: Int,
        frameHeight: Int,
        role: SignalRole = SignalRole.MARKER,
        purpose: String? = null,
        /**
         * **归属好友名**（2026-09-21）：这条锚点模板是**哪位好友**的（目前只用于好友头像）。
         *
         * 好友名是开放集（可能含空格与 `~~`），不能当用途名 ⇒ 单列一个字段；
         * 而"同一位好友重框一次"应当**覆盖**他那条（落点由 [targetFor] 按"用途 + 归属好友"定）。
         */
        friend: String? = null,
        note: String = "",
        /**
         * **备注的兜底值**（2026-10-01 用户口径）：用户没填、且旧记录也没备注时用它。
         *
         * 写入端（标定工作台）传的是"这条记录干嘛用的"中文描述 —— 锚点 = 用途标签（「设置入口」），
         * 标志 = `「<界面>」的界面标志`（见 `patrol.SignalNames.defaultNoteFor`）。
         * 默认空串 ⇒ 既有调用点行为不变（仍可能没有备注）。
         */
        fallbackNote: String = "",
    ): CalibrationData {
        require(state != UiState.UNKNOWN) { "「未知」不能作为归属状态" }
        require(frameWidth > 0 && frameHeight > 0) { "标定帧尺寸必须为正：${frameWidth}x$frameHeight" }
        geometryError(current, frameWidth, frameHeight)?.let { throw IllegalArgumentException(it) }

        // 备注是**元素级**（同一 ID 的两条记录共用一份）：取值顺序
        // **用户这次填的 > 旧备注（沿用，否则"重新框一下"会顺手抹掉用户写的字）> [fallbackNote]**。
        //
        // [fallbackNote] 是写入端按"用途 / 界面"派生的**中文功能描述**（2026-10-01 用户口径）：有了它，
        // 标定清单行与悬浮窗提示都念得出"这是大厅的设置入口"，不必靠 `hall_settings` 这种英文 ID 猜。
        val inheritedNote = current?.signals.orEmpty().firstOrNull { it.id == id }?.note.orEmpty()
        val signals = current?.signals.orEmpty().filterNot { it.id == id && it.role == role } +
            CalibrationData.SignalEntry(
                id = id,
                window = window,
                templates = listOf(template),
                role = role,
                purpose = purpose,
                friend = friend,
                note = note.ifBlank { inheritedNote.ifBlank { fallbackNote } },
            )
        return CalibrationData(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            params = params,
            signals = signals,
            stateRules = rebuildRules(signals, current?.stateRules, id to state),
        )
    }

    /**
     * 没有用途的元素的**槽位名前缀**（`activity_popup_e1`、`hall_e2`…）。
     *
     * 叫 `_e`（元素）而不叫 `_marker`：一条槽位**可能同时是标志和锚点**（活动弹窗就是这样），
     * 名字里带 "marker" 会让人以为它只是标志；带编号是因为同一界面可以有多条（多个相似模板）。
     * 后缀也必须与用途名分开 —— 否则"设置页的标志"会和"大厅设置入口这个用途"撞成同一个 ID。
     */
    private const val ELEMENT_SLOT_PREFIX = "_e"

    /**
     * 这次写入落到**哪个元素**（= 记录的元素 ID）——决定"覆盖谁 / 新增一条"（2026-09-19 用户口径）。
     *
     * ## 两条规则，没有第三条
     *
     * | 情形 | 落到哪 | 效果 |
     * | --- | --- | --- |
     * | 勾了**锚点**、且选了 [purpose] | **该用途元素**（`hall_farm`） | 同一个用途再标一次 = **覆盖**（不必框得一样） |
     * | 其余（只写标志 / 锚点没有用途如活动弹窗） | 该界面下一个空的 `<界面>_e[n]` | **新增一条**（允许多条：活动弹窗有多种相似样式） |
     *
     * 为什么这么简单：用户口径（2026-09-19 晚）——
     * ① 「覆盖谁」不该让用户选：锚点用途是唯一的、同一个用途本来就该只有一条记录，
     *    再让用户从一列元素里挑"这次覆盖哪条"既多余又容易选错（**「写入到」整行已移除**）；
     * ② 标志与"活动弹窗的关闭控件"**允许多条**，而它们的名字（`xxx_e2`、`xxx_e3`）天然可区分，
     *    所以默认新增即可 —— 用户原话："活动因为存在多个相似的模板，默认为新增一条记录"。
     *
     * ## 为什么身份不是"像素算出来的 ID"
     *
     * 当天早些时候的版本用图片 MD5 当 ID（理由："同一张图 → 同一个 ID → 重标即覆盖"）。用户实测指出：
     * **人不可能每次都框出一样的像素**——差一个像素 MD5 就变，"覆盖"形同虚设。
     * 所以身份由**语义**决定（用途），像素只决定"长什么样"。
     */
    fun targetFor(
        current: CalibrationData?,
        state: UiState,
        roles: Collection<SignalRole>,
        purpose: String? = null,
        /**
         * 归属好友名（2026-09-21）：勾了锚点、选了用途、且填了它 ⇒ **每位好友一条**。
         *
         * 为什么不能像其它用途那样"落点 = 用途名"：那样同用途只会有一条，多位好友会互相覆盖。
         * 这里的落点是 `friend_avatar_1` / `friend_avatar_2`… 这种**带编号的元素槽位**：
         * 已标过这位好友（同用途 + 同归属好友）→ **覆盖他那条**；没标过 → **新开一条**。
         */
        friend: String? = null,
    ): String {
        require(state != UiState.UNKNOWN) { "「未知」不能作为归属状态" }
        val ordered = orderedRoles(roles)
        require(ordered.isNotEmpty()) { "至少要选一个角色（标志 / 锚点）" }

        // 好友头像：每位好友一条（重框同一位 = 覆盖他那条）
        if (purpose != null && !friend.isNullOrBlank() && SignalRole.ANCHOR in ordered) {
            val existing = current?.signals.orEmpty()
                .firstOrNull { it.purpose == purpose && it.friend == friend }?.id
            if (existing != null) return existing
            val used = current?.signals.orEmpty().mapTo(HashSet()) { it.id }
            val prefix = "${purpose}_"
            var index = 1
            while ("$prefix$index" in used) index++
            return "$prefix$index"
        }

        // 有用途的锚点：落点就是用途本身（覆盖同用途的旧记录）
        if (purpose != null && SignalRole.ANCHOR in ordered) return purpose

        // 其余：该界面下一个空闲槽位（同界面允许多条，编号天然可区分）
        val used = current?.signals.orEmpty().mapTo(HashSet()) { it.id }
        val prefix = state.name.lowercase(Locale.ROOT) + ELEMENT_SLOT_PREFIX
        var index = 1
        while ("$prefix$index" in used) index++
        return "$prefix$index"
    }

    /**
     * 角色的固定顺序（标志 → 锚点）：界面上的排布、产物文本与写入顺序都按这个顺序（确定性）。
     */
    fun orderedRoles(roles: Collection<SignalRole>): List<SignalRole> =
        SignalRole.entries.filter { it in roles }

    /**
     * 一次框选要写的**角色列表**（T2-3g）：同一个元素既要判状态又要点击时勾选两个角色，
     * 一次写入**两条记录**（§2.1：一条记录只有一个角色），两条共用同一 ID、模板与搜索窗口。
     *
     * 只做两件事：排顺序（[orderedRoles]）与拦住空集合 / 「未知」状态（界面已在按钮上拦过一次）。
     * **名字不再参与**——落点由 [targetFor] 按语义决定（2026-09-19 用户口径）。
     */
    fun rolesToWrite(state: UiState, roles: Collection<SignalRole>): List<SignalRole> {
        require(state != UiState.UNKNOWN) { "「未知」不能作为归属状态" }
        val ordered = orderedRoles(roles)
        require(ordered.isNotEmpty()) { "至少要选一个角色（标志 / 锚点）" }
        return ordered
    }

    /**
     * 某状态下某角色的记录数（写入提示「该状态现有…」用；无规则返回 0）。
     * 标志与锚点**分开计数**：混着数会让人误以为同一角色已经标了 N 条。
     */
    fun countOf(data: CalibrationData, state: UiState, role: SignalRole): Int {
        val rule = data.stateRules.firstOrNull { it.state == state } ?: return 0
        return if (role == SignalRole.ANCHOR) rule.anchorNames.size else rule.signalNames.size
    }

    /**
     * **把"只圈区域"锚点的整块模板换成缩略图**（2026-09-29，见 [TemplateExtractor.preview]）。
     *
     * 纯逻辑、**幂等**：只动 [ids] 里那些记录的 [CalibrationData.SignalEntry.templates]，
     * 窗口 / 角色 / 用途 / 归属 / 备注 / 参数 / 标定帧尺寸一律不动；没有可缩的（或已经缩过）
     * ⇒ **原样返回同一个对象**（调用方据此决定"要不要落盘"）。
     *
     * @param ids 不参与匹配的那些锚点名（`PatrolAnchors.nonLocatableAnchors`）
     */
    fun shrinkRegionTemplates(
        current: CalibrationData,
        ids: Set<String>,
        maxSide: Int = TemplateExtractor.PREVIEW_MAX_PX,
    ): CalibrationData {
        if (ids.isEmpty()) return current
        var changed = false
        val signals = current.signals.map { entry ->
            if (entry.id !in ids) return@map entry
            val previews = entry.templates.map { TemplateExtractor.preview(it, maxSide) }
            // `preview` 没缩时就返回**同一个实例** ⇒ 这个比较就是"有没有真的变小"
            if (previews == entry.templates) return@map entry
            changed = true
            entry.copy(templates = previews)
        }
        if (!changed) return current
        return CalibrationData(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            params = current.params,
            signals = signals,
            stateRules = current.stateRules,
        )
    }

    //
    // ⚠ **写入试读（`tryoutText`）已于 2026-09-29 整条移除**（用户拍板："直接把试读这个功能给取消掉"）。
    //
    // 它是"写完后当场拿新模板在这张帧上跑一次匹配、把结论摆在成功提示里"。代价是**一次完整模板匹配**
    // （大模板 × 3 倍窗口要跑好几秒）：不可取消 ⇒ 用户返回列表后它仍在后台跑、连点几次就叠起来
    // 把 CPU 吃满 ⇒ **主线程 5 秒无响应 ⇒ ANR + 黑屏 + 闪退**（真机 05:10 / 05:14 / 05:20 三次）。
    // 用户确认"有些图正常了、有些还是黑屏闪退" ⇒ 这个功能带来的信息**不值这个代价**。
    // **不要再按名字加回来**：要那一类诊断，就把它放到后台线程 + 可取消，且不在写入的关键路径上。
    //

    /**
     * 把一条记录**插回原位**（2026-09-19，供列表页「删除 → 撤销」用）：**同 ID 同角色**已存在则就地替换
     * （等于"什么都没发生"），否则插到 [index]（夹在列表范围内）。
     *
     * 与 [upsert] 的区别只有一个：**保住位置**。撤销要的不只是"这条又回来了"，而是
     * "回到它原来待的那一行"——文件里的书写顺序就是用户看到的分组内顺序。
     */
    fun restore(current: CalibrationData, entry: CalibrationData.SignalEntry, index: Int): CalibrationData {
        val signals = current.signals.toMutableList()
        val existing = signals.indexOfFirst { it.id == entry.id && it.role == entry.role }
        if (existing >= 0) {
            signals[existing] = entry
        } else {
            signals.add(index.coerceIn(0, signals.size), entry)
        }
        return CalibrationData(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            params = current.params,
            signals = signals,
            stateRules = current.stateRules,
        )
    }

    /**
     * 改**备注**（元素级，2026-09-19 用户口径）：同一 ID 的**所有记录**（标志 / 锚点）一起改，
     * 因为列表里它们本来就是一行、备注也只有一份。ID 不存在时返回等价产物（幂等，不报错）。
     *
     * 备注**不参与识别与跑号**：改它不会影响任何匹配结果，改坏了也只是文字不好看。
     */
    fun withNote(current: CalibrationData, id: String, note: String): CalibrationData =
        CalibrationData(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            params = current.params,
            signals = current.signals.map { if (it.id == id) it.copy(note = note) else it },
            stateRules = current.stateRules,
        )

    /**
     * 改**一条记录**的备注（2026-10-01 加）：清单行已按角色拆成两行（标志 / 锚点各一行），
     * 点开哪一行就改哪一行 —— 标志的"「大厅」的界面标志"与锚点的"设置入口"本来就该各说各的，
     * 从锚点那一行进去却把标志那句一起改掉会很莫名。别的记录一律不动。
     */
    fun withNote(current: CalibrationData, id: String, role: SignalRole, note: String): CalibrationData =
        CalibrationData(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            params = current.params,
            signals = current.signals.map {
                if (it.id == id && it.role == role) it.copy(note = note) else it
            },
            stateRules = current.stateRules,
        )

    /**
     * **按新规则补全 / 订正备注**（2026-10-01 用户口径："将旧产物的备注按新规则改一下"、
     * 当天又两度订正："锚点的备注要用锚点用途"、"那三屏的锚点也要改"）。
     *
     * 干两件事，**按记录**逐条判（标志与锚点各算各的）：
     * 1. **补空**：备注为空的按 [defaultFor] 派生（锚点 = 用途标签 / 关闭控件 / `「<界面>」的锚点`；
     *    标志 = `「<界面>」的界面标志`）；
     * 2. **订正**：备注命中 [autoNoteHistory] 的（= 程序历史上自动写下的那些句子）换成新文本 ——
     *    默认规则一天内改了两版，早先落盘的产物里躺着上一版的句子。
     *
     * 三条口径：
     * - **幂等**：补完/订正完再跑一次不改任何东西（返回 0）；
     * - **用户手写的备注永不被覆盖**（既不空、也不在自动文本集合里 ⇒ 一律跳过）；
     * - 归属界面认不出来的记录（[stateOf] 为 null）**跳过**：宁可留空，也别贴一个"「未知」的标志"。
     *
     * @return (新产物, 动了几个元素)；无可动时原样返回 + 0
     */
    fun withDefaultNotes(
        current: CalibrationData,
        defaultFor: (UiState, SignalRole, String?) -> String,
        /** 程序可能自动写过的文本（用来认出"这句不是用户写的"）；默认空集 = 不做订正。 */
        autoNoteHistory: (UiState, Collection<SignalRole>, String?) -> Set<String> = { _, _, _ -> emptySet() },
    ): Pair<CalibrationData, Int> {
        val byId = current.signals.groupBy { it.id }
        val touched = LinkedHashSet<String>()
        val signals = current.signals.map { entry ->
            val state = stateOf(current, entry.id) ?: return@map entry
            val purpose = current.purposeOf(entry.id)
            val ideal = defaultFor(state, entry.role, purpose)
            if (ideal.isBlank() || entry.note.trim() == ideal) return@map entry
            val roles = byId[entry.id].orEmpty().map { it.role }
            val auto = entry.note.isBlank() || entry.note.trim() in autoNoteHistory(state, roles, purpose)
            if (!auto) return@map entry
            touched += entry.id
            entry.copy(note = ideal)
        }
        if (touched.isEmpty()) return current to 0
        return CalibrationData(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            params = current.params,
            signals = signals,
            stateRules = current.stateRules,
        ) to touched.size
    }

    /**
     * 删除一条记录（**ID + 角色**）：剩余为空返回 null（调用方据此删除产物文件——空产物无意义）。
     * 记录不存在时返回等价产物（幂等，不报错）。
     */
    fun remove(current: CalibrationData, id: String, role: SignalRole): CalibrationData? =
        removeAll(current, listOf(id to role))

    /**
     * 删除一个**元素**（同一 ID 的两条记录一起删）——列表页的删除粒度就是"一行"：
     * 标志与锚点是同一个东西的两面，只删其中一面会留下一条谁也说不清来历的记录。
     */
    fun removeElement(current: CalibrationData, id: String): CalibrationData? =
        removeAll(current, current.signals.filter { it.id == id }.map { it.id to it.role })

    private fun removeAll(
        current: CalibrationData,
        keys: List<Pair<String, SignalRole>>,
    ): CalibrationData? {
        val doomed = keys.toSet()
        val signals = current.signals.filterNot { it.id to it.role in doomed }
        if (signals.isEmpty()) return null
        return CalibrationData(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            params = current.params,
            signals = signals,
            stateRules = rebuildRules(signals, current.stateRules, null),
        )
    }

    /** 更新产物参数；**无产物或参数未变化返回 null**（无产物时参数随首个信号写入，避免空产物文件）。 */
    fun withParams(current: CalibrationData?, params: MatchParams): CalibrationData? {
        current ?: return null
        if (current.params == params) return null
        return CalibrationData(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            params = params,
            signals = current.signals,
            stateRules = current.stateRules,
        )
    }

    /** 记录名 → 归属状态（由状态规则反查，标志与锚点通用；产物中必有，缺失返回 null）。 */
    fun stateOf(data: CalibrationData, name: String): UiState? = data.stateOf(name)

    /** 参数文本 → 参数；任一非法返回 null（界面据此提示并拒绝写入）。 */
    fun parseParams(
        thresholdText: String,
        marginText: String,
        minDistanceText: String,
    ): MatchParams? {
        val threshold = thresholdText.trim().toDoubleOrNull() ?: return null
        val margin = marginText.trim().toDoubleOrNull() ?: return null
        val minDistance = minDistanceText.trim().toIntOrNull() ?: return null
        return try {
            MatchParams(threshold, margin, minDistance)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 由记录列表重建状态规则：以既有规则给出各记录的归属状态，[assigned] 覆盖刚写入的那条。
     * 规则顺序按记录首次出现顺序（确定性，产物文本可稳定复现）；
     * 同一状态下按**角色**分流：标志进 `signalNames`、锚点进 `anchorNames`（T2-3）。
     */
    private fun rebuildRules(
        signals: List<CalibrationData.SignalEntry>,
        previous: List<CalibrationData.StateRule>?,
        assigned: Pair<String, UiState>?,
    ): List<CalibrationData.StateRule> {
        val stateOf = HashMap<String, UiState>()
        previous?.forEach { rule ->
            (rule.signalNames + rule.anchorNames).forEach { stateOf[it] = rule.state }
        }
        assigned?.let { (name, state) -> stateOf[name] = state }
        return signals
            .groupBy(
                keySelector = {
                    requireNotNull(stateOf[it.id]) { "记录「${it.id}」缺少归属状态" }
                },
                valueTransform = { it },
            )
            .map { (state, entries) ->
                CalibrationData.StateRule(
                    state = state,
                    signalNames = entries.filter { it.role == SignalRole.MARKER }.map { it.id },
                    anchorNames = entries.filter { it.role == SignalRole.ANCHOR }.map { it.id },
                )
            }
    }
}
