package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.AnchorLocator
import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.PopupPhaseExpectedSignals
import com.example.mastermechanic.decision.RecognitionLoop
import com.example.mastermechanic.decision.SignalStateMapping
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.CanvasGeometry
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalSpec
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
     * 一条记录：名称 + 搜索窗口 + 一个或多个模板（同一记录可有多种样式，ADR-003）+ **角色**。
     * 角色决定它在判决里的用途：标志用于状态判定、锚点用于给出点击点（T2-3）。
     */
    class SignalEntry(
        val name: String,
        val window: SearchWindow,
        val templates: List<Template>,
        val role: SignalRole = SignalRole.MARKER,
    ) {
        init {
            require(name.isNotBlank()) { "信号名称不得为空" }
            require(isValidName(name)) { "信号名称含保留字符或超长：$name" }
            require(templates.isNotEmpty()) { "信号「$name」至少需要一个模板" }
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
        val names = signals.map { it.name }
        require(names.distinct().size == names.size) { "信号名不得重复：$names" }
        require(stateRules.map { it.state }.distinct().size == stateRules.size) { "同一状态至多一条规则" }
        val roleByName = signals.associate { it.name to it.role }
        stateRules.forEach { rule ->
            rule.signalNames.forEach { name ->
                val role = roleByName[name]
                    ?: throw IllegalArgumentException("规则「${rule.state}」引用了不存在的信号：$name")
                require(role == SignalRole.MARKER) { "规则「${rule.state}」把锚点「$name」当成了标志（角色不符）" }
            }
            rule.anchorNames.forEach { name ->
                val role = roleByName[name]
                    ?: throw IllegalArgumentException("规则「${rule.state}」引用了不存在的锚点：$name")
                require(role == SignalRole.ANCHOR) { "规则「${rule.state}」把标志「$name」当成了锚点（角色不符）" }
            }
        }
        // 每条记录至多归属一个状态（标志进 state= 行、锚点进 anchor= 行）
        val referenced = stateRules.flatMap { it.signalNames + it.anchorNames }
        require(referenced.distinct().size == referenced.size) { "同一条记录被多处引用：$referenced" }
        // 锚点**必须**有归属状态：没有归属就永远不会被启用（T2-3「按当前状态启用」），静默留着等于埋坑。
        // 标志允许暂不归属：离线回放工具会构造"只有信号、没有规则"的产物（T1-13 起沿用），
        // 这类标志只参与匹配、不参与状态判定。
        signals.filter { it.role == SignalRole.ANCHOR }.forEach { anchor ->
            require(anchor.name in referenced) { "锚点「${anchor.name}」缺少归属状态（须进 anchor= 行）" }
        }
    }

    /**
     * 产物 → 识别循环（T1-4 编排）：标定产物是识别能力的唯一参数来源。
     * 画布几何（T1-11c）同源建立：以产物记录的标定帧尺寸为准，运行帧与之不同几何时按「画面区」归一。
     *
     * 期望集合（T2-1）：生产路径注入 [PopupPhaseExpectedSignals]——守护待命只搜「启动页」，
     * 命中启动页才进入弹窗期（{启动页, 选择服务器, 活动弹窗, 大厅}），命中大厅即退出。
     * 演练 / 回归（离线重跑）传 [ExpectedSignals.ALL] 显式声明全集，即"每轮搜产物里的全部信号"。
     *
     * @param expectedSignals 期望集合来源；默认按 FR-01 弹窗阶段口径构造
     */
    fun toLoop(expectedSignals: ExpectedSignals? = null): RecognitionLoop {
        // 只有标志参与状态判定：锚点不进映射、不进期望集合（T2-3）——它们由 AnchorLocator 按当前状态另外定位。
        val rules = stateRules.filter { it.signalNames.isNotEmpty() }
            .map { SignalStateMapping.Rule(it.state, it.signalNames.toSet()) }
        return RecognitionLoop(
            signals = markerSpecs(),
            params = params,
            mapping = SignalStateMapping(rules),
            geometry = CanvasGeometry.of(frameWidth, frameHeight),
            expectedSignals = expectedSignals ?: PopupPhaseExpectedSignals.fromRules(rules),
        )
    }

    /**
     * 产物 → 锚点定位器（T2-3b）：按状态汇总锚点规格；几何与识别循环同源（同一标定帧尺寸）。
     * 没标锚点的产物得到空定位器（[AnchorLocator.isEmpty] 为真），运行日志据此说明"没标锚点"。
     */
    fun toAnchorLocator(): AnchorLocator = AnchorLocator(
        anchorsByState = stateRules.associate { it.state to anchorSpecs(it.state) },
        params = params,
        geometry = CanvasGeometry.of(frameWidth, frameHeight),
    )

    /** 记录角色；名称不存在返回 null。 */
    fun roleOf(name: String): SignalRole? = signals.firstOrNull { it.name == name }?.role

    /** 记录的归属状态（标志与锚点合并查询）；未归属返回 null。 */
    fun stateOf(name: String): UiState? =
        stateRules.firstOrNull { name in it.signalNames || name in it.anchorNames }?.state

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
        val byName = signals.associateBy { it.name }
        return anchorsFor(state).map { name ->
            spec(requireNotNull(byName[name]) { "锚点「$name」不在产物里（构造校验应已拦截）" })
        }
    }

    private fun spec(entry: SignalEntry): SignalSpec = SignalSpec(entry.name, entry.window, entry.templates)

    companion object {

        /** 产物文本格式用作行内分隔的字符；信号名不得包含，保证行格式可解析。 */
        private const val RESERVED_CHARS = "|=,\n\r"

        /** 信号名上限（防御超长行；正常名称远小于此值）。 */
        private const val MAX_NAME_LENGTH = 40

        fun isValidName(name: String): Boolean =
            name.isNotBlank() && name.length <= MAX_NAME_LENGTH && name.none { it in RESERVED_CHARS }
    }
}
