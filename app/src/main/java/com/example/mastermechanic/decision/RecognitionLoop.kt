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
 * - **期望集合驱动**（T2-1）：每轮只搜 [expectedSignals] 注入的信号名，**不补扫、不外扩、无周期兜底**；
 *   空集 = 不搜（该轮对状态机如同不存在）。搜索集合与判定结果由 [RoundResult] 带出，供运行日志审计；
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
     * 期望集合来源（T2-1）：每轮只搜它注入的信号名，空集 = 不搜；搜索集合外的信号
     * **不产生判定记录**，状态机不会因"没搜"而累计离开计数。
     *
     * 默认 [ExpectedSignals.ALL] = **显式声明全集**（M1 演练 / 回归口径）；生产路径由
     * `CalibrationData.toLoop` 注入弹窗守护来源（[PopupWatchExpectedSignals]）。
     */
    private val expectedSignals: ExpectedSignals = ExpectedSignals.ALL,
) {

    /** 本轮搜索集合的计算：期望集合 ∩ 已标定信号（剔除外未标定名；空集 = 不搜）。 */
    private val selector = ActiveSignalSelector(signals.map { it.name }.toSet())

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

    /**
     * 上一轮各信号的匹配耗时（T1-13b，毫秒）：key = 信号名、value = 该信号的**纯匹配**耗时。
     *
     * 口径与离线探针（`SpeedupProbeTest.profilePerSignalCost`）一致：**不含灰度转换与方向归一**
     * （两者是轮级固定开销，不分摊到单个信号），故真机数值与桌面数值可直接对比；
     * 也不含节流等待与全扫兜底（一轮搜几个信号就产生几个样本）。非前台轮为空。
     *
     * 刻意**不放进 [RoundResult]**：耗时是「测量结果」而非「判定结论」，塞进数据类会破坏
     * 「同一输入序列产生完全相同的 RoundResult」这一既有不变式（单测 `sameSequenceProducesIdenticalResults`）。
     * 仅帧线程访问。
     */
    var lastSignalCostMs: Map<String, Double> = emptyMap()
        private set

    /** 当前界面状态（滞回结论）。 */
    val state: UiState get() = stateMachine.current

    /**
     * 处理一轮：前台时执行「取期望集合 → 求搜索集合 → 方向归一 → 检测 → 映射 → 状态机 → 阶段推进」；
     * 非前台时冻结（不产生判定数据）。搜索集合为空（期望为空 / 全部期望名未标定）时本轮等同不搜。
     */
    fun process(gray: GrayImage, isForeground: Boolean): RoundResult {
        // T2-1：本轮搜索集合 = 注入的期望集合 ∩ 已标定信号（空集 = 不搜）；冻结轮不注入也不搜索
        val active = if (isForeground) {
            selector.select(expectedSignals.expected(selector.known))
        } else {
            emptySet()
        }
        // 冻结轮不搜索：清空耗时样本，避免调用方读到上一轮的陈旧值（T1-13b）
        if (!isForeground) lastSignalCostMs = emptyMap()
        val records = if (isForeground) detectRound(gray, active) else emptyList()
        val hits = if (isForeground) mapping.resolve(records) else emptySet()
        val transition = stateMachine.update(hits, foreground = isForeground)
        // 阶段推进（T2-1）：用本轮**原始命中**而非滞回结论——若等确认（连续 2 次）才注入，
        // 刚弹出来的弹窗在本轮与下一轮都不会被搜索；提前注入的代价只是多搜几个已标定信号
        if (isForeground) expectedSignals.onRound(hits)
        // T1-13 降档判据「确实稳定」：预期（当前状态）本轮命中 ∧ 无外部候选累积 ∧ 无转移。
        // 三者之外的任何情况（未命中 / 别的状态在累积 / 刚转移）都意味着"画面正在变或还没变到预期"，
        // 正是最该保持快档的时刻——历史实现用 transition == null 判稳定，会把"白等预期"错当稳定而降频。
        val settled = isForeground &&
            stateMachine.current != UiState.UNKNOWN &&
            stateMachine.current in hits &&
            !stateMachine.hasForeignCandidate &&
            transition == null
        return RoundResult(
            records = records,
            hits = hits,
            transition = transition,
            state = stateMachine.current,
            frozen = !isForeground,
            searched = active,
            settled = settled,
        )
    }

    /**
     * 本轮检测（T2-1）：只匹配给定集合，**不补扫、不外扩、无周期兜底**；空集直接不搜。
     *
     * 历史（T1-10g）在"当前状态未命中"时会在同一轮补扫其余信号以发现新状态；T1-13 取消该机制，
     * T2-1 进一步取消"状态未知即全扫"——真实巡查由流程驱动、每步有明确预期，
     * "发现新状态"不是识别层的职责，补扫只是多余的尖峰成本。
     *
     * 空集时**不产生判定记录与耗时样本**（否则调用方会把"没搜"读成"匹配得很快"）。
     */
    private fun detectRound(gray: GrayImage, subset: Set<String>): List<DetectionRecord> {
        val detector = this.detector ?: return emptyList()
        if (subset.isEmpty()) {
            lastSignalCostMs = emptyMap()
            return emptyList()
        }
        val target = normalized(gray, subset)
        // T1-13b 逐信号计时：把「一轮搜 N 个信号」拆成 N 个独立样本，回答"每步（每条信号）真正要花多久"。
        // 逐个调用 detectSignal 与 detect(target, subset) 完全等价（后者本就是同顺序逐个判定），判定语义不变。
        val costs = LinkedHashMap<String, Double>()
        val records = signals.filter { it.name in subset }.map { signal ->
            val startedNs = System.nanoTime()
            val record = detector.detectSignal(target, signal)
            costs[signal.name] = (System.nanoTime() - startedNs) / 1_000_000.0
            record
        }
        lastSignalCostMs = costs
        return records
    }

    /**
     * 运行帧 → 标定画布几何（T1-11c）：无几何或画面区一致时原样返回（零开销快路径）。
     * 归一后判定输入恒处于标定几何，模板 / 窗口 / 阈值 / 判定语义均不变。
     */
    private fun normalized(gray: GrayImage, active: Set<String>): GrayImage {
        val canvas = geometry?.mappingFor(gray.width, gray.height) ?: return gray
        if (canvas.isIdentity) return gray
        // 只归一本轮真正要判定的信号窗口（期望集合模式下更省，判定语义不变）
        val regions = signals
            .filter { it.name in active }
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
     * 本轮实际参与匹配的信号名集合（T2-1：= 注入的期望集合 ∩ 已标定信号）。
     *
     * **空集 = 本轮不搜**（期望为空 / 期望名全部未标定 / 冻结轮）。供运行日志与审计使用，
     * 便于确认"这一轮到底搜了哪些信号"。
     */
    val searched: Set<String> = emptySet(),
    /**
     * 「确实稳定」（T1-13，NFR-02 长间隔的**唯一**判据）：本轮预期（当前状态）命中、
     * 无外部候选累积、且无转移。为真时才允许降到长间隔。
     */
    val settled: Boolean = false,
) {
    /**
     * 本轮无状态变化。
     *
     * 注意：**不再作为节流降档判据**（T1-13）——「没有转移」不等于「达到了预期」，
     * 二者之间那段"白等预期"的时间恰恰最该保持快档。降档请用 [settled]。
     */
    val stable: Boolean get() = !frozen && transition == null
}
