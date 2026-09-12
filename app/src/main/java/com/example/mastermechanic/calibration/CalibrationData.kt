package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.ActiveSignalSelector
import com.example.mastermechanic.decision.RecognitionLoop
import com.example.mastermechanic.decision.SignalStateMapping
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.CanvasGeometry
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.Template

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

    /** 一个界面标志：名称 + 搜索窗口 + 一个或多个模板（同一标志可有多种样式，ADR-003）。 */
    class SignalEntry(val name: String, val window: SearchWindow, val templates: List<Template>) {
        init {
            require(name.isNotBlank()) { "信号名称不得为空" }
            require(isValidName(name)) { "信号名称含保留字符或超长：$name" }
            require(templates.isNotEmpty()) { "信号「$name」至少需要一个模板" }
        }
    }

    /** 一条信号—状态映射规则（§2.1：一个状态可由多个标志支撑，任一命中即该状态命中）。 */
    class StateRule(val state: UiState, val signalNames: List<String>) {
        init {
            require(state != UiState.UNKNOWN) { "「未知」没有标志，不能作为映射目标" }
            require(signalNames.isNotEmpty()) { "规则至少包含一个信号名" }
            require(signalNames.distinct().size == signalNames.size) { "规则内信号名不得重复" }
        }
    }

    init {
        require(frameWidth > 0 && frameHeight > 0) { "标定帧尺寸必须为正：${frameWidth}x$frameHeight" }
        require(signals.isNotEmpty()) { "产物至少需要一个信号（空产物无意义）" }
        val names = signals.map { it.name }
        require(names.distinct().size == names.size) { "信号名不得重复：$names" }
        require(stateRules.map { it.state }.distinct().size == stateRules.size) { "同一状态至多一条规则" }
        stateRules.forEach { rule ->
            rule.signalNames.forEach { name ->
                require(name in names) { "规则「${rule.state}」引用了不存在的信号：$name" }
            }
        }
    }

    /**
     * 产物 → 识别循环（T1-4 编排）：标定产物是识别能力的唯一参数来源。
     * 画布几何（T1-11c）同源建立：以产物记录的标定帧尺寸为准，运行帧与之不同几何时按「画面区」归一。
     */
    fun toLoop(): RecognitionLoop {
        val rules = stateRules.map { SignalStateMapping.Rule(it.state, it.signalNames.toSet()) }
        return RecognitionLoop(
            signals = signals.map { SignalSpec(it.name, it.window, it.templates) },
            params = params,
            mapping = SignalStateMapping(rules),
            geometry = CanvasGeometry.of(frameWidth, frameHeight),
            // T1-10g：按状态启用信号子集（附加信号默认空；未搜到的信号不产生判定记录）
            selector = ActiveSignalSelector.fromRules(rules),
        )
    }

    companion object {

        /** 产物文本格式用作行内分隔的字符；信号名不得包含，保证行格式可解析。 */
        private const val RESERVED_CHARS = "|=,\n\r"

        /** 信号名上限（防御超长行；正常名称远小于此值）。 */
        private const val MAX_NAME_LENGTH = 40

        fun isValidName(name: String): Boolean =
            name.isNotBlank() && name.length <= MAX_NAME_LENGTH && name.none { it in RESERVED_CHARS }
    }
}
