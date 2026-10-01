package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.AnchorLocator
import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.PopupWatchExpectedSignals
import com.example.mastermechanic.decision.RecognitionLoop
import com.example.mastermechanic.decision.SignalStateMapping
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.TemplateMatcher
import com.example.mastermechanic.recognition.Template

/**
 * 标定记录的角色（T2-3，§2.1）：同一份产物里的记录分两类，**与「界面标志」并列的独立角色**。
 *
 * - [MARKER] 界面标志：参与状态判定（标志 → 状态映射、期望集合），只回答"这是哪个界面"；
 * - [ANCHOR] 动作锚点：只回答"点哪里"，按**当前状态**启用，命中位置即点击点；不参与状态判定。
 *
 * 同一视觉元素若既要判状态又要点击，写**两条独立记录**（一条标志、一条锚点）——一条记录只有一个角色，
 * 合并会让两种语义互相污染（§2.1）。两种角色的**归属状态**都在 `StateRule` 里声明。
 */
enum class SignalRole(val token: String, val label: String) {
    MARKER("marker", "标志"),
    ANCHOR("anchor", "锚点"),
    ;

    companion object {
        /** 文本 token → 角色；未知返回 null（由调用方决定报错口径）。 */
        fun fromToken(token: String): SignalRole? = entries.firstOrNull { it.token == token }
    }
}

/**
 * 标定产物数据模型（T1-5a，ADR-003）：在目标设备上采集的「模板 + 搜索窗口 + 阈值」全套配置。
 *
 * 校验全部在构造时完成（构造即合法）：编解码器与标定界面共用本模型，非法数据无法进入产物。
 * 识别所用的模板 / 窗口 / 参数只能来自本产物——代码不含任何与开发设备绑定的
 * 分辨率、密度、位置或阈值（红线 4、§5-5）。
 */
class CalibrationData(
    /** 标定时的帧尺寸（像素）。仅记录与运行帧对照告警，不参与任何换算（窗口按比例）。 */
    val frameWidth: Int,
    val frameHeight: Int,
    val params: MatchParams,
    val signals: List<SignalEntry>,
    val stateRules: List<StateRule>,
) {

    /**
     * 一条记录：**元素 ID** + 搜索窗口 + 一个或多个模板（同一记录可有多种样式，ADR-003）+ **角色**
     * + **用途**（仅锚点）+ **备注**（给人看）。
     *
     * 角色决定它在判决里的用途：标志用于状态判定、锚点用于给出点击点（T2-3）。
     *
     * ## ID 是「元素」而不是「人取的名字」（2026-09-19 用户口径）
     *
     * [id] 由程序按**语义**取（[CalibrationSignals.targetFor]）：有用途的锚点**就是用途名**（`hall_farm`），
     * 其余（标志 / 活动弹窗的关闭控件）取该界面下一个槽位 `<界面>_e[n]`。用户不必关心"这条叫什么"。
     * 换来三件事：
     * 1. **不会撞名**：以前按"状态默认名 + 序号"命名，序号取决于标定顺序，同一个界面上的两个控件
     *    （大厅的退出登录 / 农场入口）分不出谁是谁；
     * 2. **关联不再靠名字**：同一个元素的「标志 + 锚点」**共用同一个 [id]**，
     *    "这两条是同一个东西的两面"从"名字看起来像一对"变成**直接相等**；
     * 3. **重标即覆盖**：同一个用途再标一次 → 同一个 ID → 覆盖旧记录，不会留下 `xxx2`
     *    （曾经用"模板像素的 MD5"当 ID，被实测否掉：**人不可能每次都框出一样的像素**）。
     *
     * [purpose] 是**给机器看的契约**（[com.example.mastermechanic.patrol.PatrolAnchors] 按它找锚点），
     * [note] 是**给人看的自由文本**（可空、**允许重复**，怎么填都不影响识别与跑号）。
     * 取名（"叫什么"）与用途（"干什么用"）分开，是因为一个界面常有多个要点的控件，
     * 而"名字"本身对用户没有意义。
     *
     * ## 唯一性
     *
     * **同一 [id] 至多两条记录（每个角色一条）**：一次框选同时勾了标志与锚点时写两条，ID 相同、角色不同。
     */
    data class SignalEntry(
        val id: String,
        val window: SearchWindow,
        val templates: List<Template>,
        val role: SignalRole = SignalRole.MARKER,
        val purpose: String? = null,
        /**
         * **归属好友名**（产物 v4 起，2026-09-21）：这条锚点模板是**哪位好友**的。
         *
         * 为什么单列一个字段、不直接用用途名：好友名是用户手写的开放集，
         * 可能含空格与特殊字符（真机实例 `Boss~~喵`），**不能当用途名**（用途名必须是合法 ID），
         * 而"同用途覆盖"的规则又会让多位好友互相顶掉 ⇒ 只能由本字段承载"这是谁的"。
         *
         * 目前只用于好友农场左上角的**好友头像**模板（`friend_avatar`）：运行时按目标好友名
         * **精确取**模板（不挑最像的，红线 3），据此确认"已到达指定好友的农场"。
         */
        val friend: String? = null,
        val note: String = "",
    ) {
        init {
            require(isValidId(id)) { "记录 ID 含保留字符或超长：$id" }
            require(templates.isNotEmpty()) { "记录「$id」至少需要一个模板" }
            require(purpose == null || role == SignalRole.ANCHOR) { "只有锚点能带用途：$id" }
            require(purpose == null || isValidId(purpose)) { "用途名含保留字符或超长：$purpose" }
            require(friend == null || role == SignalRole.ANCHOR) { "只有锚点能带归属好友名：$id" }
            require(friend == null || isValidFriendName(friend)) { "归属好友名非法：$friend" }
            require(isValidNote(note)) { "备注含保留字符或超长：$note" }
        }
    }

    /**
     * 一条归属规则（§2.1：一个状态可由多个标志支撑，任一命中即该状态命中）：
     * 声明该状态下的**界面标志**（参与状态判定）与**动作锚点**（按当前状态启用，只回答点哪里）。
     */
    class StateRule(
        val state: UiState,
        val signalNames: List<String>,
        val anchorNames: List<String> = emptyList(),
    ) {
        init {
            require(state != UiState.UNKNOWN) { "「未知」没有标志，不能作为映射目标" }
            require(signalNames.isNotEmpty() || anchorNames.isNotEmpty()) { "规则至少包含一个标志或一个锚点" }
            require(signalNames.distinct().size == signalNames.size) { "规则内标志名不得重复" }
            require(anchorNames.distinct().size == anchorNames.size) { "规则内锚点名不得重复" }
        }
    }

    init {
        require(frameWidth > 0 && frameHeight > 0) { "标定帧尺寸必须为正：${frameWidth}x$frameHeight" }
        require(signals.isNotEmpty()) { "产物至少需要一个信号（空产物无意义）" }
        val keys = signals.map { it.id to it.role }
        require(keys.distinct().size == keys.size) { "同一 ID 的同一角色不得重复：$keys" }
        require(stateRules.map { it.state }.distinct().size == stateRules.size) { "同一状态至多一条规则" }
        // 一个 ID 可以有**两条**记录（标志 + 锚点，§2.1），所以是「ID → 角色集合」而不是一一对应：
        // 用单值映射会把同一个元素的两面挤成一条，反过来把自己的记录判成"角色不符"。
        val rolesById = signals.groupBy({ it.id }, { it.role })
        stateRules.forEach { rule ->
            rule.signalNames.forEach { id ->
                val roles = rolesById[id]
                    ?: throw IllegalArgumentException("规则「${rule.state}」引用了不存在的记录：$id")
                require(SignalRole.MARKER in roles) { "规则「${rule.state}」把锚点「$id」当成了标志（角色不符）" }
            }
            rule.anchorNames.forEach { id ->
                val roles = rolesById[id]
                    ?: throw IllegalArgumentException("规则「${rule.state}」引用了不存在的锚点：$id")
                require(SignalRole.ANCHOR in roles) { "规则「${rule.state}」把标志「$id」当成了锚点（角色不符）" }
            }
        }
        // 每条记录至多归属一个状态（标志进 state= 行、锚点进 anchor= 行）；
        // 同一 ID 的标志与锚点是**两条记录**，各归一个状态不算重复引用。
        val referenced = stateRules.flatMap { rule ->
            rule.signalNames.map { it to SignalRole.MARKER } + rule.anchorNames.map { it to SignalRole.ANCHOR }
        }
        require(referenced.distinct().size == referenced.size) { "同一条记录被多处引用：$referenced" }
        // 锚点**必须**有归属状态：没有归属就永远不会被启用（T2-3「按当前状态启用」），静默留着等于埋坑。
        // 标志允许暂不归属：离线回放工具会构造"只有信号、没有规则"的产物（T1-13 起沿用），
        // 这类标志只参与匹配、不参与状态判定。
        signals.filter { it.role == SignalRole.ANCHOR }.forEach { anchor ->
            require(referenced.any { it.first == anchor.id }) { "锚点「${anchor.id}」缺少归属状态（须进 anchor= 行）" }
        }
    }

    /**
     * 产物 → 识别循环（T1-4 编排）：标定产物是识别能力的唯一参数来源。
     * 画布几何（T1-11c）同源建立：以产物记录的标定帧尺寸为准，运行帧与之不同几何时按「画面区」归一。
     *
     * 期望集合（T2-5）：生产路径注入 [PopupWatchExpectedSignals]——**每轮只搜活动弹窗自己的标志记录**。
     * 不再用「命中启动页/大厅」推断弹窗是否存在：弹窗一定在大厅之后出现，而程序可能未及时识别到大厅
     * （大厅一闪即被弹窗盖住），依赖该推断会永久错过弹窗（详见 [PopupWatchExpectedSignals] 类文档）。
     * 演练 / 回归（离线重跑）传 [ExpectedSignals.ALL] 显式声明全集，即"每轮搜产物里的全部信号"。
     *
     * @param expectedSignals 期望集合来源；默认按 FR-01 弹窗守护口径构造
     */
    /**
     * **把每条记录的搜索窗口重算成「固定像素余量」**（2026-09-29，与写入端同口径：第 288 / 289 条）。
     *
     * 规则只有一条 —— **只收"可证居中"的轴**：
     * - **可证居中**（`窗口 ≥ 3 × 模板`，见 [fixedMarginWindow] 的证明）：把该轴收到 `模板 + 2 × 余量`
     *   （标志 [SelectionWindow.MARKER_MARGIN_PX] = 48 / 锚点 = 64 px/边）；
     * - **不可证**：那一轴**一格都不动**（旧实现可能是"各边独立裁剪" ⇒ 选区偏心 ⇒ 朝中心收会把元素挤出去）；
     * - 「只圈区域」的锚点（见 `exemptPurposes`）：整条不动。
     *
     * ## 为什么要这条（用户 2026-09-29 口径："旧的记录不用管…你能否直接修改"）
     *
     * 写入端口径已改成"四周各固定像素"，但**产物里已写下的旧窗口不会自己变**
     * ⇒ 旧记录仍按"外扩 1 倍选区"付费（真机：`hall_farm` 124M ≈ 800ms、`tutorial_hall_e1` 108M）。
     * 本条让**旧记录也吃到新口径**，而用户不必逐条重框。
     *
     * ## 为什么放在加载期、且**不改产物**
     *
     * 与其它运行时变换同一处（`CalibrationStore.loadForRuntime`）：产物文件仍是"标定那一刻"的忠实记录，
     * 变的只是运行时取值 ⇒ 口径随时可调、也能一键回退；用户想"把新口径写进产物"只需重框一次。
     *
     * @param exemptPurposes **不参与重算**的锚点用途（[purposeOf] 的口径，2026-09-22）：只圈区域、
     *   不做点击目标的那几条（「列表区域」/「好友名称列」）。它们不是"在窗口内找元素"，
     *   而是"把这一片送去读文字"，改窗口会让"标定页上看到的框"与"运行时用的框"不是一个东西。
     *   **必填、故意不给默认值**，理由与 `toAnchorLocator` 的 `nonLocatable` 同源：漏传是静默的。
     */
    fun tightenedWindows(exemptPurposes: Set<String>): CalibrationData = CalibrationData(
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        params = params,
        // 「只圈区域」的锚点**不参与**（2026-09-22 用户口径）：它们不是"在窗口内找元素"，
        // 而是"把这一片送去读文字 / 灰度化"，**用户框多大就该用多大**。
        // 隐式改动的后果：他在标定页上看到的框与运行时用的框不是同一个 ——
        // 真机实录「列表区域」框 941×1533、运行时只用 565×921，而屏上没有任何地方看得出这件事。
        signals = signals.map {
            if (purposeOf(it.id) in exemptPurposes) it else costCapped(fixedMarginWindow(it))
        },
        stateRules = stateRules,
    )

    /**
     * **成本护栏**（2026-09-29 重新接回，见 `docs/progress.md` 第 299 条）。
     *
     * ## 为什么必须接在 [fixedMarginWindow] **之后**
     *
     * 固定余量口径（标志 48 / 锚点 64 px/边）**不看计算量**：小模板拿到这点余量没问题，
     * 但**大模板**（例：248×80）配上同样余量后，单条一次匹配就要 ~20M 乘加 ⇒ 真机实测整轮从 ~0.2s
     * 掉到 ~0.8s（`frame管线统计`：10 秒只处理 13 帧；用户报障"**识别速度很明显慢了很多**"）。
     * ⇒ 先按用户口径给足余量，**再**按预算朝窗口中心收窄兜底（反过来会把余量算进预算里，白收一遍）。
     *
     * 硬边界与第 283 条一致：只收不放；**永不收到"模板四周不足 32px"**（32px 是本项目实测的安全余量：
     * `hall_settings` 的命中位置比窗口中心偏 ≈20px，8px 会把它挤出窗口）。
     */
    private fun costCapped(entry: SignalEntry): SignalEntry {
        val biggest = entry.templates.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: return entry
        val capped = TemplateMatcher.cappedByCost(
            window = entry.window,
            templateWidth = biggest.width,
            templateHeight = biggest.height,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
        return if (capped === entry.window) entry else entry.copy(window = capped)
    }

    /**
     * 一条记录的**固定余量重算**（见 [tightenedWindows]）：只收"可证居中"的轴，
     * 目标是 `模板 + 2 × max(角色余量, MIN_ABSOLUTE_MARGIN_PX)`；两轴都不可证 ⇒ 原样返回同一个对象。
     *
     * ## `窗口 ≥ 3 × 模板` 为什么能证明"选区居中"（两代写入实现都成立）
     *
     * 写入端的余量**两侧一样多**：现实现（[SelectionWindow.forSelection]）是"对称取小"，
     * 早期实现是"从 1 倍选区出发、各边独立裁剪"。于是：
     * - **没有被帧边裁掉** ⇒ 两侧余量都 = 1 倍选区 = 1 倍模板 ⇒ 比例**恰好 3.00**，且**选区在窗口正中** ✓；
     * - **被裁掉** ⇒ 比例必然 < 3（一侧有余量、另一侧没有）⇒ **不碰** ✓。
     * ⇒ 判据 `≥ 3 ×` 是"可证居中"的**充分条件**，用它才敢朝窗口中心收 ——
     * 真机两次教训（第 269 / 284 条）都出在"对**偏心**的窗口朝中心收"：
     * `最高分 0.25`（全图唯一那行字搜不到）、`锚点「farm_exit」没定位到…等了 15 秒`。
     */
    private fun fixedMarginWindow(entry: SignalEntry): SignalEntry {
        val template = entry.templates.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: return entry
        val bounds = entry.window.pixelBounds(frameWidth, frameHeight)
        val spanX = bounds.x1 - bounds.x0
        val spanY = bounds.y1 - bounds.y0
        val provableX = spanX >= template.width * PROVABLE_CENTER_RATIO
        val provableY = spanY >= template.height * PROVABLE_CENTER_RATIO
        if (!provableX && !provableY) return entry
        // 余量按角色给（锚点更大：认不出 = 整步停摆），但不低于项目实测的安全下限 32px
        val margin = maxOf(SelectionWindow.marginPxFor(entry.role).toDouble(), SearchWindow.MIN_ABSOLUTE_MARGIN_PX)
        // **直接按像素算**，不走"按比例收窄"那条路（早先那套是"半宽 × 系数"，且系数下限 0.2 ⇒
        // 目标比"原窗口的 1/5"还小时会被顶回来，收不到固定余量 —— 2026-09-29 踩过；那套 API 已删）。
        // 两个轴各自决定：可证 ⇒ 目标半宽 = 模板半宽 + 余量；不可证 ⇒ 原样（只收紧不放大）。
        val centerX = (bounds.x0 + bounds.x1) / 2.0
        val centerY = (bounds.y0 + bounds.y1) / 2.0
        val halfWidth = if (provableX) minOf(template.width / 2.0 + margin, spanX / 2.0) else spanX / 2.0
        val halfHeight = if (provableY) minOf(template.height / 2.0 + margin, spanY / 2.0) else spanY / 2.0
        val window = SearchWindow(
            left = ((centerX - halfWidth) / frameWidth).coerceIn(0.0, 1.0),
            top = ((centerY - halfHeight) / frameHeight).coerceIn(0.0, 1.0),
            right = ((centerX + halfWidth) / frameWidth).coerceIn(0.0, 1.0),
            bottom = ((centerY + halfHeight) / frameHeight).coerceIn(0.0, 1.0),
        )
        return if (window == entry.window) entry else entry.copy(window = window)
    }

    /**
     * 让「好友的农场」这个状态**彻底退休**（2026-09-21）：**清空它名下的全部记录**（锚点 + 标志）
     * 并移除它的规则。
     *
     * 判据（真机帧池交叉取证 `FarmMergeProbeTest` → `out/t4-3-farm-merge-cross.txt`）：
     *
     * 1. **锚点删除**：农场的 `farm_exit` / `farm_friends` 在**好友农场**帧上 **8/8 命中**
     *    （0.9625~0.9999）⇒ 好友农场那两条纯属重复；
     * 2. **标志删除**（2026-09-21 用户拍板）：它框的是「回家」按钮，而该按钮用户已在农场组重框过一次，
     *    **只作身份判据锚点**（不点它，也不拿它判画面）⇒ 迁移过来只会让清单里出现两行「回家」。
     *    农场改用**两个农场画面都有的「社交」按钮**作标志 —— 一条标志覆盖两边，
     *    好友农场那条标志因此不再需要。
     *
     * ## 由此确定的分层
     *
     * - **标志**（`RecognitionLoop`）：回答"这是哪一屏" ⇒ 「农场」；
     * - ~~**身份判据锚点**（`AnchorLocator` → `FarmIdentity`）：回答"这是**谁的**农场"~~
     *   —— **2026-09-23 用户拍板整块删除**：两个农场的换号 / 拜访路径完全一样，不需要知道是谁的。
     *
     * **唯一兜底**：清完之后农场名下一**条标志都没有**（连"这是农场"都判不出）⇒ 留下好友农场那条
     * 标志改归农场，绝不静默丢弃。
     *
     * 幂等：没有该状态（或已迁移过）时原样返回。
     */
    fun retireFriendFarm(): CalibrationData {
        val rule = stateRules.firstOrNull { it.state == UiState.FRIEND_FARM } ?: return this
        val farmHasMarker =
            stateRules.firstOrNull { it.state == UiState.FARM }?.signalNames.orEmpty().isNotEmpty()

        // 农场名下还有别的标志 ⇒ 整包清除；否则**只删锚点、留下标志**（改归属，见兜底）
        val dropped = if (farmHasMarker) {
            (rule.anchorNames + rule.signalNames).toSet()
        } else {
            rule.anchorNames.toSet()
        }
        val moved = if (farmHasMarker) emptyList() else rule.signalNames

        val signals = signals.filterNot { it.id in dropped }
        if (signals.isEmpty()) return this // 极端情况：删光了就什么都不做，交给调用方处理

        val rules = buildList {
            var sawFarm = false
            stateRules.forEach { current ->
                if (current.state == UiState.FRIEND_FARM) return@forEach // 移除这条规则
                if (current.state == UiState.FARM) {
                    sawFarm = true
                    add(
                        StateRule(
                            state = current.state,
                            signalNames = (current.signalNames + moved).distinct(),
                            anchorNames = current.anchorNames,
                        ),
                    )
                } else {
                    add(current)
                }
            }
            // 农场此前没有规则 → 新建一条接住留下的标志
            if (!sawFarm && moved.isNotEmpty()) add(StateRule(UiState.FARM, moved))
        }
        return CalibrationData(frameWidth, frameHeight, params, signals, rules)
    }

    fun toLoop(expectedSignals: ExpectedSignals? = null): RecognitionLoop {
        // 只有标志参与状态判定：锚点不进映射、不进期望集合（T2-3）——它们由 AnchorLocator 按当前状态另外定位。
        val rules = stateRules.filter { it.signalNames.isNotEmpty() }
            .map { SignalStateMapping.Rule(it.state, it.signalNames.toSet()) }
        return RecognitionLoop(
            signals = markerSpecs(),
            params = params,
            mapping = SignalStateMapping(rules),
            expectedSignals = expectedSignals ?: PopupWatchExpectedSignals.fromRules(rules),
        )
    }

    /**
     * 产物 → 锚点定位器（T2-3b）：按状态汇总锚点规格；几何与识别循环同源（同一标定帧尺寸）。
     * 没标锚点的产物得到空定位器（[AnchorLocator.isEmpty] 为真），运行日志据此说明"没标锚点"。
     *
     * @param nonLocatable **只圈区域、不当点击目标**的锚点名（如「列表区域」）。**必填、故意不给默认值**：
     *   漏传会让大块区域锚点跟着一起做模板匹配，帧线程单轮停摆 20 秒以上（2026-09-22 真机实录，
     *   根因与实测见 [AnchorLocator] 的构造参数说明）。调用方给 `PatrolAnchors.nonLocatableAnchors`。
     */
    fun toAnchorLocator(nonLocatable: Set<String>): AnchorLocator = AnchorLocator(
        anchorsByState = stateRules.associate { it.state to anchorSpecs(it.state) },
        params = params,
        nonLocatable = nonLocatable,
    )

    /** 记录角色；ID 不存在返回 null。 */
    fun roleOf(id: String): SignalRole? = signals.firstOrNull { it.id == id }?.role

    /** 记录的归属状态（标志与锚点合并查询）；未归属返回 null。 */
    fun stateOf(id: String): UiState? =
        stateRules.firstOrNull { id in it.signalNames || id in it.anchorNames }?.state

    /**
     * 记录的备注（给人看的自由文本）；ID 不存在返回空串。
     *
     * [role] 非空时读**那一条记录**的备注 —— 标志与锚点可以不同（2026-10-01 用户口径"锚点的备注用锚点用途"）：
     * 同一个元素里，标志写"「大厅」的界面标志"、锚点写"设置入口"，两条记录各说各的。
     * 该角色的记录不存在时退回"同 ID 的第一条"（同一个元素的两条记录本来共享一份备注的旧口径仍然成立）。
     */
    fun noteOf(id: String, role: SignalRole? = null): String =
        (
            signals.firstOrNull { it.id == id && it.role == role } ?: signals.firstOrNull { it.id == id }
            )?.note.orEmpty()

    /**
     * 记录的**用途**（锚点的契约名，[com.example.mastermechanic.patrol.PatrolAnchors] 按它找锚点）；
     * ID 不是锚点或没写用途 → null。
     */
    fun purposeOf(id: String): String? =
        signals.firstOrNull { it.id == id && it.role == SignalRole.ANCHOR }?.purpose

    /**
     * 记录的**归属好友名**（锚点的"这是谁的"）；不是锚点或没写 → null。
     *
     * 与 [purposeOf] 的区别：用途是**契约名**（`friend_avatar`，代码认它），
     * 归属好友名是**用户写的名字**（`Boss~~喵`），多位好友各一条、靠它区分。
     */
    fun friendOf(id: String): String? =
        signals.firstOrNull { it.id == id && it.role == SignalRole.ANCHOR }?.friend

    /**
     * 某状态下、**用途 = [purpose] 且归属好友名全等 [friend]** 的锚点 ID；没有 → null。
     *
     * 全等是关键：**绝不挑"最像的"**（红线 3）——找不到就是"这位没标过头像"，
     * 结论是"认不出"，而不是拿别人的头像去凑。
     */
    fun anchorIdFor(state: UiState, purpose: String, friend: String): String? =
        anchorsFor(state).firstOrNull { purposeOf(it) == purpose && friendOf(it) == friend }

    /**
     * 某状态下、某**用途**的锚点 ID；没有该用途的锚点返回 null
     * （跑号据此判断"这个用途还没标定"，而不是拿别的锚点去点）。
     */
    fun anchorIdFor(state: UiState, purpose: String): String? =
        anchorsFor(state).firstOrNull { purposeOf(it) == purpose }

    /**
     * 归属好友名改名（2026-09-22 用户口径：好友改名要同步下游）：把 [oldName] 名下的记录**全部**改挂到
     * [newName]；没有记录挂在这个名字下则**原样返回**（调用方据此决定要不要落盘）。
     *
     * 两条口径与"改名"这个动作的语义绑死：
     * 1. **只改归属名，模板 / 窗口 / 用途一概不动** —— 改名 = 同一个人换了名字，头像本身没变。
     *    （反面理解"当成换了个人"会连模板一起删掉，等于逼用户重新标定。）
     * 2. **全等匹配**（红线 3 的同一口径）：不做"最像的"猜测。
     */
    fun renamedFriend(oldName: String, newName: String): CalibrationData {
        if (oldName == newName || signals.none { it.friend == oldName }) return this
        return CalibrationData(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            params = params,
            signals = signals.map { if (it.friend == oldName) it.copy(friend = newName) else it },
            stateRules = stateRules,
        )
    }

    /**
     * 一条记录**给人读的完整描述**（2026-09-19 用户口径：日志里的产物要能直接读懂）：
     *
     * ```
     * hall_farm ｜ 锚点 ｜ 大厅 ｜ 用途 hall_farm ｜ 备注 左上角那个 ｜ 模板 131×37 ｜ 窗口 (610,1744)-(1120,1928)
     * ```
     *
     * 刻意把**所有字段**都摊平在一行里：日志检索的人手上只有一个 ID（如 `MM-Click 锚点: hall_farm`），
     * 要能一眼看出它是哪个界面、哪个角色、干什么用、长什么样，不必回标定页翻。
     */
    fun describe(entry: SignalEntry): String {
        val stateLabel = stateOf(entry.id)?.label ?: "未归属"
        val template = entry.templates.first()
        val px = entry.window.pixelBounds(frameWidth, frameHeight)
        return buildString {
            append("${entry.id} ｜ ${entry.role.label} ｜ $stateLabel")
            entry.purpose?.let { append(" ｜ 用途 $it") }
            if (entry.note.isNotBlank()) append(" ｜ 备注 ${entry.note}")
            append(" ｜ 模板 ${template.width}×${template.height}")
            if (entry.templates.size > 1) append("（${entry.templates.size} 条样式）")
            append(" ｜ 窗口 (${px.x0},${px.y0})-(${px.x1},${px.y1})")
        }
    }

    /** 标志规格（识别循环的输入；顺序 = 产物声明顺序）。 */
    fun markerSpecs(): List<SignalSpec> =
        signals.filter { it.role == SignalRole.MARKER }.map { spec(it) }

    /**
     * 按当前状态启用：该状态声明的锚点名。
     * 无规则或该状态没有锚点 → 空列表，即"这个界面没有要点的东西"（不接受调用方兜底到别的状态）。
     */
    fun anchorsFor(state: UiState): List<String> =
        stateRules.firstOrNull { it.state == state }?.anchorNames.orEmpty()

    /** 按当前状态启用的锚点规格（顺序 = 规则声明顺序），供锚点定位使用。 */
    fun anchorSpecs(state: UiState): List<SignalSpec> {
        // 必须按角色先筛：一个 ID 可能同时有标志与锚点两条记录，各自模板/窗口都可能不同
        val byId = signals.filter { it.role == SignalRole.ANCHOR }.associateBy { it.id }
        return anchorsFor(state).map { id ->
            spec(requireNotNull(byId[id]) { "锚点「$id」不在产物里（构造校验应已拦截）" })
        }
    }

    private fun spec(entry: SignalEntry): SignalSpec = SignalSpec(entry.id, entry.window, entry.templates)

    companion object {

        /** 产物文本格式用作行内分隔的字符；ID / 用途 / 备注都不得包含，保证行格式可解析。 */
        private const val RESERVED_CHARS = "|=,\n\r"

        /**
         * **"选区居中可证"的比值门槛**（[fixedMarginWindow] 用）：窗口某方向的像素跨度 ≥ 模板对应边 × 它。
         *
         * 取 3 = 写入端"各方向外扩 1 倍选区"的契约（见 `SelectionWindow`）：
         * 两侧余量都完整落下时比例恰好 3.00 且选区居中；被帧边裁掉时必然 < 3（⇒ 不碰）。
         * ⚠ 改这个数等于改"敢不敢对老记录动手"的前提，别为了让更多记录被收而调小。
         */
        private const val PROVABLE_CENTER_RATIO = 3

        /** ID / 用途名上限（防御超长行；正常值远小于此值）。 */
        private const val MAX_NAME_LENGTH = 40

        /** 备注上限（自由文本；够写一句人话，又不至于把产物撑肿）。 */
        const val MAX_NOTE_LENGTH = 60

        /**
         * ID（元素槽位名 / 用途名）是否可写进产物。
         *
         * **刻意不校验形状**（不要求"必须是某个前缀 / 某个长度"）：ID 由 [CalibrationSignals.targetFor]
         * 按语义生成（用途名如 `hall_farm`、槽位名如 `activity_popup_e1`），而**读取旧产物时 ID 就是当年的名字**
         * （`popup_close_anchor`）—— 只认某一种形状会让已标定的内容全部作废。这里只拦"写坏行格式"。
         */
        fun isValidId(id: String): Boolean =
            id.isNotBlank() && id.length <= MAX_NAME_LENGTH && id.none { it in RESERVED_CHARS }

        /** 备注是否可写进产物：可空、可重复，但不能带行格式保留字符（含换行）。 */
        fun isValidNote(note: String): Boolean =
            note.length <= MAX_NOTE_LENGTH && note.none { it in RESERVED_CHARS }

        /**
         * **归属好友名**是否可写进产物（2026-09-21）。
         *
         * 与 ID（[isValidId]）**刻意不同**：好友名是用户手写的开放集，允许空格与特殊字符
         * （真机实例 `Boss~~喵`），所以**不要求它是合法 ID**；只拦"写坏行格式"的那几个字符
         * （`|` / 换行）与长度，与 FR-10 对区服名 / 好友名的校验口径一致。
         */
        fun isValidFriendName(friend: String): Boolean =
            friend.isNotBlank() && friend.length <= MAX_NOTE_LENGTH && friend.none { it in RESERVED_CHARS }
    }
}
