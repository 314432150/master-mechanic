package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.DetectionRecord
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SignalDetector
import com.example.mastermechanic.recognition.SignalSpec

/**
 * 识别循环编排（T1-4）：每轮「帧数据 → 判定记录 → 状态命中 → 滞回状态机」的决策链。
 *
 * - 非前台轮（§1.2「不识别」、FR-09「不消耗连续计数」）：不产生判定数据，
 *   状态机以冻结方式接收该轮（不消耗任何计数）；
 * - 信号 / 参数 / 映射均来自标定产物（T1-5）；标定前以 [uncalibrated] 空配置运行，
 *   用于真机验证链路与日志（不产生任何判定）；
 * - 全部依赖为纯逻辑（recognition / decision 包），本类可在 JVM 离线重跑（§5-4）。
 */
class RecognitionLoop(
    signals: List<SignalSpec>,
    params: MatchParams,
    private val mapping: SignalStateMapping,
) {

    /** 标定信号数量（0 = 未标定，运行日志据此明示）。 */
    val signalCount: Int = signals.size

    private val detector: SignalDetector? =
        if (signals.isEmpty()) null else SignalDetector(signals, params)

    private val stateMachine = UiStateMachine()

    /** 当前界面状态（滞回结论）。 */
    val state: UiState get() = stateMachine.current

    /** 处理一轮：前台时执行「检测 → 映射 → 状态机」；非前台时冻结（不产生判定数据）。 */
    fun process(gray: GrayImage, isForeground: Boolean): RoundResult {
        val records = if (isForeground) detector?.detect(gray).orEmpty() else emptyList()
        val hits = if (isForeground) mapping.resolve(records) else emptySet()
        val transition = stateMachine.update(hits, foreground = isForeground)
        return RoundResult(
            records = records,
            hits = hits,
            transition = transition,
            state = stateMachine.current,
            frozen = !isForeground,
        )
    }

    companion object {

        /**
         * 未标定（空信号）循环：T1-5 标定产物就绪前用于真机链路验证——
         * 不产生任何判定，状态保持「未知」，运行日志明示信号数为 0。
         *
         * 占位参数在空信号集下不参与任何匹配计算（无信号即无匹配），
         * 真实参数一律以标定产物为准（§5-5、红线 4）。
         */
        fun uncalibrated(): RecognitionLoop {
            val placeholder = MatchParams(matchThreshold = 1.0, ambiguityMargin = 0.0, peakMinDistance = 1)
            return RecognitionLoop(
                signals = emptyList(),
                params = placeholder,
                mapping = SignalStateMapping(emptyList()),
            )
        }
    }
}

/**
 * 一轮识别循环的处理结果。
 *
 * [frozen] 为真表示该轮因非前台整体跳过（§1.2）；[stable] 用于 NFR-02 自适应节流
 * 判定（冻结轮不参与节流建议）。
 */
data class RoundResult(
    val records: List<DetectionRecord>,
    val hits: Set<UiState>,
    val transition: UiStateTransition?,
    val state: UiState,
    val frozen: Boolean,
) {
    /** 本轮无状态变化（可用于下调检测频率）。 */
    val stable: Boolean get() = !frozen && transition == null
}
