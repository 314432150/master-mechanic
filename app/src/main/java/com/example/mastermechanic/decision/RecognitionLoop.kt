package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.DetectionRecord
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.PixelBounds
import com.example.mastermechanic.recognition.SignalDetector
import com.example.mastermechanic.recognition.SignalSpec
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 识别循环编排（T1-4）：每轮「帧数据 → 方向归一 → 判定记录 → 状态命中 → 滞回状态机」的决策链。
 *
 * - 非前台轮（§1.2「不识别」、FR-09「不消耗连续计数」）：不产生判定数据，
 *   状态机以冻结方式接收该轮（不消耗任何计数）；
 * - 信号 / 参数 / 映射均来自标定产物（T1-5）；标定前以 [uncalibrated] 空配置运行，
 *   用于真机验证链路与日志（不产生任何判定）；
 * - **几何前提（T4-6，2026-09-23）**：运行帧与标定帧**几何恒相同** —— 转屏由 `VirtualDisplay.resize()`
 *   跟进（帧恒与屏幕同向），原先的"按画面区归一"（T1-11c）已整体拆除。几何一旦不符
 *   （例如产物方向与本次会话不一致），由采集层**按「未标定」处理（不判、不点）**，不在这里补救；
 * - **期望集合驱动**（T2-1）：每轮只搜 [expectedSignals] 注入的信号名，**不补扫、不外扩、无周期兜底**；
 *   空集 = 不搜（该轮对状态机如同不存在）。搜索集合与判定结果由 [RoundResult] 带出，供运行日志审计；
 * - 全部依赖为纯逻辑（recognition / decision 包），本类可在 JVM 离线重跑（§5-4）。
 */
class RecognitionLoop(
    private val signals: List<SignalSpec>,
    params: MatchParams,
    private val mapping: SignalStateMapping,
    /**
     * 期望集合来源（T2-1）：每轮只搜它注入的信号名，空集 = 不搜；搜索集合外的信号
     * **不产生判定记录**，状态机不会因"没搜"而累计离开计数。
     *
     * 默认 [ExpectedSignals.ALL] = **显式声明全集**（M1 演练 / 回归口径）；生产路径由
     * `CalibrationData.toLoop` 注入弹窗守护来源（[PopupWatchExpectedSignals]）。
     */
    private val expectedSignals: ExpectedSignals = ExpectedSignals.ALL,
    /**
     * **并行匹配度**（2026-09-24 新增）：一轮里各条信号是**互相独立**的纯计算（各自在自己的
     * 搜索窗口里做 NCC），把它们分到几个线程上跑，墙钟耗时按并行度下降。
     *
     * 为什么可以这么做（不破任何既有口径）：
     * - **判定语义零漂移**：每个信号的匹配不读也不写别的信号的状态，分线程只改"谁先算"，
     *   不改"算出什么"——结果按**信号声明顺序**收集，顺序与串行完全一致（§5-4 确定性）；
     * - **不改识别参数**：采样步长 / 网格步进 / 锚点数 / 阈值一个都没动（红线 4 与"判定语义不变"）；
     * - **不改节流**：节流管的是"两轮之间歇多久"，本项只压"一轮干多久"，两者互不干扰。
     *
     * 取值 [DEFAULT_MATCH_PARALLELISM]：真机 8 核，留出游戏与帧线程；≤ 1 或无信号时**不起线程池**，
     * 走原来的纯串行路径（离线回归 / 单测里用它做「并行 vs 串行」对照）。
     */
    matchParallelism: Int = DEFAULT_MATCH_PARALLELISM,
    /**
     * **窗口未变 ⇒ 复用上一轮结论**（2026-09-24 第二刀，压单轮识别耗时）。
     *
     * 判据是**精确的**：某条信号的**搜索窗口内像素逐字节相同**、且模板 / 参数 / 窗口都没变
     * ⇒ 匹配是纯函数 ⇒ 结论必然与上次一字不差。比对的是"判定真正读到的那块像素"（不是整帧），
     * 所以"画面别处在动、我们窗口不动"时同样能复用；反之窗口一变就实算，**绝不猜**。
     *
     * 为什么值得单开一刀：静止兜底轮重放的本来就是缓存副本，窗口一个字节都不会变 ⇒ 那几轮几乎白送。
     *
     * ⚠ **只在真的算过之后才把「快照 + 记录」成对存下**：否则匹配抛异常时会留下
     * "新快照配旧记录"，下一轮就会拿着旧结论当真（静默错误，本项目最忌讳的一类）。
     *
     * 复用的信号**不产生耗时样本**（它这轮确实没花时间），也不改写判定记录。
     */
    private val reuseUnchangedWindows: Boolean = DEFAULT_REUSE_UNCHANGED_WINDOWS,
) {

    /** 本轮搜索集合的计算：期望集合 ∩ 已标定信号（剔除外未标定名；空集 = 不搜）。 */
    private val selector = ActiveSignalSelector(signals.map { it.name }.toSet())

    /** 标定信号数量（0 = 未标定，运行日志据此明示）。 */
    val signalCount: Int = signals.size

    private val detector: SignalDetector? =
        if (signals.isEmpty()) null else SignalDetector(signals, params)

    /**
     * 匹配线程池（仅当 [matchParallelism] > 1 且真有信号时才建）。
     *
     * 线程是 **daemon + 空闲 2 秒即回收**（`allowCoreThreadTimeOut`）：不跑识别时**不留常驻线程**，
     * 识别循环实例被丢弃也不会吊住进程（本项目对"待命期不吃电"有要求）。
     */
    private val matchPool: ExecutorService? = if (matchParallelism > 1 && signals.size > 1) {
        val counter = AtomicInteger(1)
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "$MATCH_THREAD_NAME-${counter.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
        ThreadPoolExecutor(
            matchParallelism,
            matchParallelism,
            MATCH_THREAD_KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            factory,
        ).apply { allowCoreThreadTimeOut(true) }
    } else {
        null
    }

    /**
     * 每个信号「上次真算时它的窗口像素」+「照那份像素算出来的判定记录」——
     * **成对更新、只在算完之后更新**（见 [reuseUnchangedWindows] 的告警）。仅帧线程访问。
     */
    private val windowSnapshots = HashMap<String, ByteArray>()
    private val cachedRecords = HashMap<String, DetectionRecord>()

    /** 本轮**复用了上一轮结论**的信号名（观测用；供运行日志说明"这轮为什么快"）。仅帧线程访问。 */
    var lastReusedSignals: Set<String> = emptySet()
        private set

    /**
     * 识别所需的最小像素区域（**运行帧坐标系**）：采集层据此仅转换该区域的灰度（T1-10b）——
     * 判定只读取窗口内像素，区域外置零与全帧转换在判定上等价。
     *
     * T4-6 起运行帧与标定帧同几何，窗口比例直接按运行帧尺寸换算即可（原先还要经一道
     * "标定坐标 → 画面区 → 运行帧"的映射，那是为方向归一准备足够源像素用的）。
     * 未标定（空信号）时返回空列表。
     */
    fun windowRegions(frameWidth: Int, frameHeight: Int): List<PixelBounds> =
        signals.map { it.window.pixelBounds(frameWidth, frameHeight) }

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
     *
     * ⚠ **2026-09-24 起口径细化**：样本只为「这一轮**真正做了匹配**的信号」产生 ——
     * 被「窗口未变即复用」省掉的信号**没有样本**（它这轮确实没花时间；给它记 0 会把"信号有多贵"
     * 这份统计稀释成假象）。所以"样本集合 = 搜索集合 − 复用集合"。
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
        // 冻结轮不搜索：清空耗时样本与复用记录，避免调用方读到上一轮的陈旧值（T1-13b）
        if (!isForeground) {
            lastSignalCostMs = emptyMap()
            lastReusedSignals = emptySet()
        }
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
            reusedSignals = if (isForeground) lastReusedSignals else emptySet(),
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
     *
     * **窗口未变则复用上一轮结论**（2026-09-24 第二刀，见 [reuseUnchangedWindows]）：
     * 复用的信号**不产生耗时样本**（它这一轮确实没花时间），也不计入"这轮算了几条"。
     */
    private fun detectRound(gray: GrayImage, subset: Set<String>): List<DetectionRecord> {
        val detector = this.detector ?: return emptyList()
        if (subset.isEmpty()) {
            lastSignalCostMs = emptyMap()
            lastReusedSignals = emptySet()
            return emptyList()
        }
        val target = gray // T4-6：不再归一（运行帧与标定帧同几何），直接在本帧上判定
        val active = signals.filter { it.name in subset }
        // ① 逐信号比对**窗口像素**，逐字节相同 ⇒ 结论必然与上次一致（同窗口 × 同模板 × 同参数 = 纯函数）
        val reused = LinkedHashSet<String>()
        val pending = ArrayList<PendingSignal>(active.size)
        for (signal in active) {
            val snapshot = if (reuseUnchangedWindows) windowSnapshot(target, signal) else null
            val previous = windowSnapshots[signal.name]
            val canReuse = snapshot != null && previous != null &&
                snapshot.contentEquals(previous) && cachedRecords.containsKey(signal.name)
            if (canReuse) {
                reused += signal.name
            } else {
                pending += PendingSignal(signal, snapshot)
            }
        }
        // ② 真算的按（串行 / 并行）匹配；耗时样本只记这些（复用的那几条**没有样本**，免得把统计拉成假象）
        val computed = matchSignals(detector, target, pending)
        // ③ 只在**算完之后**才把快照与记录成对存下：否则匹配抛异常时会出现"新快照配旧记录"（复用错误结论）
        pending.forEach { item ->
            val snapshot = item.snapshot ?: return@forEach
            val result = computed[item.signal.name] ?: return@forEach
            windowSnapshots[item.signal.name] = snapshot
            cachedRecords[item.signal.name] = result.record
        }
        lastReusedSignals = reused
        lastSignalCostMs = LinkedHashMap<String, Double>().apply {
            pending.forEach { item -> computed[item.signal.name]?.let { put(item.signal.name, it.costMs) } }
        }
        // ④ 输出顺序恒等于信号声明顺序（复用与实算混在一起也一样）
        return active.map { signal ->
            (if (signal.name in reused) cachedRecords[signal.name] else computed[signal.name]?.record)
                ?: error("信号「${signal.name}」既没复用也没匹配结果")
        }
    }

    /** 待匹配的信号 + 它这一轮的窗口快照（快照 [PendingSignal.snapshot] 为空 = 窗口取不到，按实算处理）。 */
    private data class PendingSignal(val signal: SignalSpec, val snapshot: ByteArray?)

    /** 单条信号的匹配结果 + 其**纯匹配耗时**（并行分支里在线程内计时，口径与串行一致）。 */
    private data class MatchedSignal(val name: String, val costMs: Double, val record: DetectionRecord)

    /** 匹配一撮信号：单条或串行时直接算；多条且开了并行时按声明顺序收集（见 [matchPool]）。 */
    private fun matchSignals(
        detector: SignalDetector,
        gray: GrayImage,
        pending: List<PendingSignal>,
    ): Map<String, MatchedSignal> {
        val out = LinkedHashMap<String, MatchedSignal>(pending.size)
        if (pending.isEmpty()) return out
        val pool = matchPool
        if (pool == null || pending.size <= 1) {
            pending.forEach { out[it.signal.name] = timedDetect(detector, gray, it.signal) }
            return out
        }
        // 并行分支（2026-09-24）：一条信号一个任务，**按信号声明顺序收集**——
        // 谁先算完不影响结果与顺序（确定性由收集顺序保证，不靠线程调度）。
        val tasks = pending.map { item -> pool.submit(Callable { timedDetect(detector, gray, item.signal) }) }
        tasks.forEach { task ->
            val matched = try {
                task.get()
            } catch (e: ExecutionException) {
                // 匹配本身不该抛（纯计算）；真抛了就如实往上传（不吞、也不换成"未命中"）
                throw (e.cause as? RuntimeException ?: RuntimeException(e.cause))
            }
            out[matched.name] = matched
        }
        return out
    }

    private fun timedDetect(detector: SignalDetector, gray: GrayImage, signal: SignalSpec): MatchedSignal {
        val startedNs = System.nanoTime()
        val record = detector.detectSignal(gray, signal)
        return MatchedSignal(signal.name, (System.nanoTime() - startedNs) / 1_000_000.0, record)
    }

    /**
     * 取某条信号**搜索窗口**内的像素快照（行主序紧凑拷贝）。
     *
     * 这是"窗口未变即复用"的判据（见 [reuseUnchangedWindows]）：比对的是**判定真正读到的那块像素**，
     * 不是整帧 —— 大厅里动画在别处动、我们窗口不动时同样能复用。
     * 窗口取不到（空窗口 / 越界）返回 null ⇒ 该信号按"没快照"处理，一律实算（不猜）。
     */
    private fun windowSnapshot(gray: GrayImage, signal: SignalSpec): ByteArray? {
        val bounds = signal.window.pixelBounds(gray.width, gray.height)
        val width = bounds.x1 - bounds.x0
        val height = bounds.y1 - bounds.y0
        if (width <= 0 || height <= 0) return null
        val snapshot = ByteArray(width * height)
        var offset = 0
        for (y in bounds.y0 until bounds.y1) {
            System.arraycopy(gray.pixels, y * gray.width + bounds.x0, snapshot, offset, width)
            offset += width
        }
        return snapshot
    }

    companion object {

        /**
         * 匹配并行度默认值（2026-09-24）：真机 8 核，**留出游戏与帧线程**，取 3。
         *
         * 为什么是 3 而不是"核数 - 1"：
         * - 手机同时还在跑游戏（它才是主角）；本项目对"不跟游戏抢资源"有明确要求；
         * - 一轮里信号数量本来就不多（待命期 6~7 条，跑号期 1~2 条），3 个 worker 已能把
         *   待命期那一轮（真机实测 180~380ms）压到约 1/2~1/3；
         * - 定值而非按核数自适应，避免"同一产物在不同设备上跑出不同调度"这类难查差异
         *   （并行不改判定结果，但定值让排障时的耗时对照更可比）。
         */
        const val DEFAULT_MATCH_PARALLELISM = 3

        /**
         * 「窗口未变即复用」默认开关（2026-09-24 第二刀）。
         *
         * 默认**开**：判据是逐字节相同的窗口像素 + 不变的模板/参数 ⇒ 结论必然相同（纯函数），
         * 属"零漂移"的省时手段；设 false 可做 A/B 对照（离线探针里比"开 / 关"的耗时与一致性）。
         */
        const val DEFAULT_REUSE_UNCHANGED_WINDOWS = true

        /** 匹配线程空闲回收时长（daemon 线程；不跑识别时不留常驻线程）。 */
        private const val MATCH_THREAD_KEEP_ALIVE_MS = 2_000L

        /** 匹配线程名前缀（`MM-Match-N`，真机排障时按名前缀筛日志/看线程列表）。 */
        private const val MATCH_THREAD_NAME = "MM-Match"

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
    /**
     * 本轮**直接复用上一轮结论**的信号名（2026-09-24 第二刀）——这些信号这一轮没重新匹配，
     * 因为它们的搜索窗口像素与上次**逐字节相同**（匹配是纯函数 ⇒ 结论必然相同）。
     *
     * 供运行日志说明"这轮为什么快"；**不是判定结论的替代品**：[records] 里照样有它们的完整记录。
     */
    val reusedSignals: Set<String> = emptySet(),
) {
    /**
     * 本轮无状态变化。
     *
     * 注意：**不再作为节流降档判据**（T1-13）——「没有转移」不等于「达到了预期」，
     * 二者之间那段"白等预期"的时间恰恰最该保持快档。降档请用 [settled]。
     */
    val stable: Boolean get() = !frozen && transition == null
}
