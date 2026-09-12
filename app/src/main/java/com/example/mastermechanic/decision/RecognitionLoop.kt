package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.CanvasGeometry
import com.example.mastermechanic.recognition.DetectionRecord
import com.example.mastermechanic.recognition.FrameNormalizer
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.PixelBounds
import com.example.mastermechanic.recognition.SignalDetector
import com.example.mastermechanic.recognition.SignalSpec

/**
 * 识别循环编排（T1-4）：每轮「帧数据 → 方向归一 → 判定记录 → 状态命中 → 滞回状态机」的决策链。
 *
 * - 非前台轮（§1.2「不识别」、FR-09「不消耗连续计数」）：不产生判定数据，
 *   状态机以冻结方式接收该轮（不消耗任何计数）；
 * - 信号 / 参数 / 映射均来自标定产物（T1-5）；标定前以 [uncalibrated] 空配置运行，
 *   用于真机验证链路与日志（不产生任何判定）；
 * - 方向归一（T1-11c）：运行帧与标定帧几何不一致时按「画面区」换算到标定几何后再判定，
 *   使同一标定产物在竖 / 横画布会话下均可用（模型与实测见
 *   `docs/recognition/research/research-canvas-orientation.md`）；
 * - 全部依赖为纯逻辑（recognition / decision 包），本类可在 JVM 离线重跑（§5-4）。
 */
class RecognitionLoop(
    private val signals: List<SignalSpec>,
    params: MatchParams,
    private val mapping: SignalStateMapping,
    /**
     * 画布几何（T1-11c）：把运行帧按「画面区」归一到标定画布几何后再判定。
     * null = 不做方向适配（视运行帧与标定帧同几何），仅供未标定与同几何测试使用。
     */
    private val geometry: CanvasGeometry? = null,
) {

    /** 标定信号数量（0 = 未标定，运行日志据此明示）。 */
    val signalCount: Int = signals.size

    private val detector: SignalDetector? =
        if (signals.isEmpty()) null else SignalDetector(signals, params)

    /**
     * 识别所需的最小像素区域（**运行帧坐标系**）：采集层据此仅转换该区域的灰度（T1-10b）——
     * 判定只读取窗口内像素，区域外置零与全帧转换在判定上等价。
     *
     * 有画布几何时（T1-11c）：窗口按**标定坐标**换算后再映射到运行帧（可能比窗口本身大），
     * 使归一化有足够的源像素可采样；无几何时按窗口比例直接换算到运行帧（同几何假设）。
     * 未标定（空信号）时返回空列表。
     */
    fun windowRegions(frameWidth: Int, frameHeight: Int): List<PixelBounds> {
        val canvas = geometry?.mappingFor(frameWidth, frameHeight)
            ?: return signals.map { it.window.pixelBounds(frameWidth, frameHeight) }
        return signals.map { signal ->
            canvas.sourceBounds(signal.window.pixelBounds(canvas.calibrationWidth, canvas.calibrationHeight))
        }
    }

    private val stateMachine = UiStateMachine()

    /** 当前界面状态（滞回结论）。 */
    val state: UiState get() = stateMachine.current

    /** 处理一轮：前台时执行「方向归一 → 检测 → 映射 → 状态机」；非前台时冻结（不产生判定数据）。 */
    fun process(gray: GrayImage, isForeground: Boolean): RoundResult {
        val records = if (isForeground) detector?.detect(normalized(gray)).orEmpty() else emptyList()
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

    /**
     * 运行帧 → 标定画布几何（T1-11c）：无几何或画面区一致时原样返回（零开销快路径）。
     * 归一后判定输入恒处于标定几何，模板 / 窗口 / 阈值 / 判定语义均不变。
     */
    private fun normalized(gray: GrayImage): GrayImage {
        val canvas = geometry?.mappingFor(gray.width, gray.height) ?: return gray
        if (canvas.isIdentity) return gray
        val regions = signals.map {
            it.window.pixelBounds(canvas.calibrationWidth, canvas.calibrationHeight)
        }
        return FrameNormalizer.normalize(gray, canvas, regions)
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
