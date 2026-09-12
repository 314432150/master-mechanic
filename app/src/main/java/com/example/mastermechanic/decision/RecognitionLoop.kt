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
    /**
     * 按状态启用信号子集（T1-10g）：null = 每轮全扫（旧行为 / 未标定）。
     * 子集外的信号**不产生判定记录**，状态机不会因"没搜"而累计离开计数。
     */
    private val selector: ActiveSignalSelector? = null,
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

    /** 前台轮计数（供子集兜底全扫周期使用）。 */
    private var round = 0

    /** 上一轮当前状态是否未命中（疑似正在转移 → 下一轮全扫以发现新状态）。 */
    private var probing = false

    /** 当前界面状态（滞回结论）。 */
    val state: UiState get() = stateMachine.current

    /** 处理一轮：前台时执行「选子集 → 方向归一 → 检测 → 映射 → 状态机」；非前台时冻结（不产生判定数据）。 */
    fun process(gray: GrayImage, isForeground: Boolean): RoundResult {
        val active = if (isForeground) selector?.select(stateMachine.current, probing, round) else null
        val records = if (isForeground) detectWithFallback(gray, active) else emptyList()
        val hits = if (isForeground) mapping.resolve(records) else emptySet()
        val transition = stateMachine.update(hits, foreground = isForeground)
        if (isForeground) {
            probing = stateMachine.current != UiState.UNKNOWN && stateMachine.current !in hits
            round++
        }
        return RoundResult(
            records = records,
            hits = hits,
            transition = transition,
            state = stateMachine.current,
            frozen = !isForeground,
            searched = if (isForeground && !scanAllThisRound) active else null,
        )
    }

    /** 上一轮是否触发了补扫（决定本轮 [RoundResult.searched] 是否记为全扫）。 */
    private var scanAllThisRound = false

    /**
     * 子集匹配的**按需补扫**（T1-10g）：先按子集判定；若本轮**当前状态未命中**
     * （子集已包含当前状态的信号，说明画面可能已切换），则在同一轮补扫其余信号。
     *
     * 这样既保留了"稳定态只搜子集"的成本收益，又让转移当轮的信息量与全扫**完全一致**——
     * 状态序列不因子集而延迟（否则会晚一轮才发现新状态）。补扫只在未命中轮发生。
     */
    private fun detectWithFallback(gray: GrayImage, subset: Set<String>?): List<DetectionRecord> {
        val detector = this.detector ?: return emptyList()
        scanAllThisRound = false
        if (subset == null) {
            scanAllThisRound = true
            return detector.detect(normalized(gray, null), null)
        }
        val first = detector.detect(normalized(gray, subset), subset)
        val missedCurrent = stateMachine.current != UiState.UNKNOWN &&
            stateMachine.current !in mapping.resolve(first)
        if (!missedCurrent) return first
        val rest = signals.map { it.name }.toSet() - subset
        if (rest.isEmpty()) return first
        scanAllThisRound = true
        return first + detector.detect(normalized(gray, null), rest)
    }

    /**
     * 运行帧 → 标定画布几何（T1-11c）：无几何或画面区一致时原样返回（零开销快路径）。
     * 归一后判定输入恒处于标定几何，模板 / 窗口 / 阈值 / 判定语义均不变。
     */
    private fun normalized(gray: GrayImage, active: Set<String>?): GrayImage {
        val canvas = geometry?.mappingFor(gray.width, gray.height) ?: return gray
        if (canvas.isIdentity) return gray
        // 只归一本轮真正要判定的信号窗口（子集模式下更省，判定语义不变）
        val regions = signals
            .filter { active == null || it.name in active }
            .map { it.window.pixelBounds(canvas.calibrationWidth, canvas.calibrationHeight) }
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
    /**
     * 本轮实际参与匹配的信号名集合（T1-10g）：null = 全扫（未知 / 转移探测 / 周期兜底 / 未启用子集）。
     * 供运行日志与审计使用，便于确认"这一轮到底搜了哪些信号"。
     */
    val searched: Set<String>? = null,
) {
    /** 本轮无状态变化（可用于下调检测频率）。 */
    val stable: Boolean get() = !frozen && transition == null
}
