package com.example.mastermechanic.recognition

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 归一化互相关（NCC）模板匹配（ADR-003）：输入帧数据 → 输出相似度分数与位置。
 *
 * - 只在 [SearchWindow]（相对比例，随帧尺寸换算）内扫描；
 * - 分数对亮度 / 对比度归一化（±线性变换不改变结果），对局部遮罩与背景动态有容忍；
 * - 纯数值计算、固定遍历与排序顺序，同输入 + 同参数必然同结果（§5-4）；
 * - 不做运行期多尺度：匹配尺度由标定确定。
 *
 * 性能结构（T1-10a）：朴素全窗匹配为「位置数 × 模板像素」量级（约 1e8 次乘加 / 帧），
 * 在目标设备上无法满足 NFR-01（< 20ms）。现改为两级匹配：
 * 1. 粗搜：按固定网格步进 [COARSE_STEP] 扫描，打分用「采样模板」（[SampleView] 按
 *    交错步长抽取，约 1/2 像素量）与窗口前缀和统计——粗搜分数为近似值，仅用于排序筛点，
 *    不参与判定；
 * 2. 精搜：取粗搜分数高的 [COARSE_TOP_K] 个代表网格点（互距 ≥ 网格步长），在其
 *    [COARSE_STEP]/2 邻域内以全精度重算——任一帧位置到最近网格点距离不超过该半径，
 *    真峰必然被覆盖；
 * 3. 候选分数全部为全精度值（与穷举实现的分数一致，阈值 / 差距判定输入不变）。
 *
 * 采样与网格步进为算法固定特性（与设备无关，不引入设备绑定参数，红线 4 不破）。
 */
object TemplateMatcher {

    /** 方差下限：低于此值的位置无相关性可言（近恒定区域），分数记 0。 */
    private const val VARIANCE_EPS = 1e-6

    /** 粗搜网格步进（像素）：固定算法参数；步进 2 保证与真峰错位 ≤ 1px 时相关性仍显著。 */
    private const val COARSE_STEP = 2

    /** 参与精搜的粗搜代表点数量上限：覆盖「真峰 + 若干竞争区域」场景（实验值）。 */
    private const val COARSE_TOP_K = 64

    /**
     * 估算一次 [findPeaks] 的**粗搜乘加次数**（成本模型的**唯一实现**，供排障日志与"该不该重框"的判断共用）。
     *
     * 口径严格对齐实现：位置数与 [gridPositions] 同步长（[COARSE_STEP]，末点必被计入），
     * 每位置采样点数与 [SampleView] 同口径（交错半采样 ⇒ 约 `w·h/2`）。
     * 精搜（`COARSE_TOP_K × 9` 个位置的全精度重算）与它相比恒小两个数量级，故只算粗搜。
     *
     * ## 怎么用它（真机实测：≈ 6~10ms / 百万次乘加）
     *
     * 排查"识别变慢 / 流程超时"时，拿 `信号耗时统计` 里最慢那条的**模板尺寸与窗口尺寸**套这个公式：
     * ```
     * tutorial_guide_e1: 模板 155×168、窗口 466×487 ⇒ 3.25e8 ⇒ 实测 2064ms  ✓ 与实测吻合
     * tutorial_hall_e1:  模板 248×80、 窗口 744×167 ⇒ 1.09e8 ⇒ 实测  766ms  ✓
     * activity_popup_e1: 模板  82×89、 窗口 147×154 ⇒ 3.97e6 ⇒ 实测   70ms  ✓
     * ```
     * ⚠ 2026-09-29 之后窗口余量固定（标志 48 / 锚点 64 px/边）⇒ 位置数恒定（≈49×49），
     * 于是**成本正比于模板面积** —— 想变快就只剩"把模板框小"这一个杠杆
     * （真机：框小一半约快一半）。见 `CalibrationStore.logStillHeavy` 与 `docs/progress.md` 第 288 条。
     */
    fun estimateCoarseOps(
        window: SearchWindow,
        templateWidth: Int,
        templateHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Long {
        if (templateWidth <= 0 || templateHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return 0L
        val bounds = window.pixelBounds(frameWidth, frameHeight)
        val spanX = bounds.x1 - bounds.x0
        val spanY = bounds.y1 - bounds.y0
        if (spanX < templateWidth || spanY < templateHeight) return 0L
        val positionsX = (spanX - templateWidth) / COARSE_STEP + 1
        val positionsY = (spanY - templateHeight) / COARSE_STEP + 1
        val samples = (templateWidth.toLong() * templateHeight + 1) / 2
        return positionsX.toLong() * positionsY * samples
    }

    /**
     * **单条信号单轮匹配的粗搜乘加上限**（成本护栏预算）。
     *
     * 8M ≈ 单条 50~80ms（实测 ≈6~10ms / 百万次乘加）。健康信号都远低于它
     * （`activity_popup` ≈4M、`launch_login` ≈3M），只有"窗口相对模板大得离谱"的那几条才会吃到这一刀。
     *
     * 为什么要有它：固定余量口径（标志 48 / 锚点 64 px/边）**不看计算量** —— 大模板配上这点余量后
     * 单条就要 ~20M（真机：整轮从 ~0.2s 掉到 ~0.8s，用户报障"识别速度很明显慢了很多"）
     * ⇒ 固定余量给足之后，再按这个预算兜一道（见 `CalibrationData.costCapped`）。
     */
    const val MATCH_OPS_BUDGET = 8_000_000L

    /**
     * **成本护栏**：把 [window] 朝**自己的中心**收窄到"粗搜乘加 ≤ [maxOps]"，但**永不收到
     * "模板四周不足 [SearchWindow.MIN_ABSOLUTE_MARGIN_PX] 像素"**；不超预算时**原样返回同一个对象**。
     *
     * 两条硬边界：① **只收紧、不放大**（半宽只在 `[模板半宽+32px, 现有半宽]` 之间走）；
     * ② **余量下限优先于预算** —— 模板本身就大时到下限仍超预算 ⇒ 取下限、不硬凑
     * （32px 是本项目真机实测的安全余量：`hall_settings` 命中位置比窗口中心偏 ≈20px，8px 会把它挤出窗口）。
     * 两个轴按**同一比例**收（尽量保形状）⇒ 不改变"框在哪就搜哪"的语义，也不动产物文件。
     */
    fun cappedByCost(
        window: SearchWindow,
        templateWidth: Int,
        templateHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        maxOps: Long = MATCH_OPS_BUDGET,
    ): SearchWindow {
        if (frameWidth <= 0 || frameHeight <= 0 || templateWidth <= 0 || templateHeight <= 0) return window
        if (estimateCoarseOps(window, templateWidth, templateHeight, frameWidth, frameHeight) <= maxOps) {
            return window
        }
        val bounds = window.pixelBounds(frameWidth, frameHeight)
        val centerX = (bounds.x0 + bounds.x1) / 2.0
        val centerY = (bounds.y0 + bounds.y1) / 2.0
        val minHalfWidth = templateWidth / 2.0 + SearchWindow.MIN_ABSOLUTE_MARGIN_PX
        val minHalfHeight = templateHeight / 2.0 + SearchWindow.MIN_ABSOLUTE_MARGIN_PX
        val maxHalfWidth = (bounds.x1 - bounds.x0) / 2.0
        val maxHalfHeight = (bounds.y1 - bounds.y0) / 2.0
        if (minHalfWidth >= maxHalfWidth && minHalfHeight >= maxHalfHeight) return window

        fun windowAt(ratio: Double): SearchWindow {
            val halfWidth = minHalfWidth + (maxHalfWidth - minHalfWidth) * ratio
            val halfHeight = minHalfHeight + (maxHalfHeight - minHalfHeight) * ratio
            return SearchWindow(
                left = ((centerX - halfWidth) / frameWidth).coerceIn(0.0, 1.0),
                top = ((centerY - halfHeight) / frameHeight).coerceIn(0.0, 1.0),
                right = ((centerX + halfWidth) / frameWidth).coerceIn(0.0, 1.0),
                bottom = ((centerY + halfHeight) / frameHeight).coerceIn(0.0, 1.0),
            )
        }

        // 二分找"估算量刚好 ≤ 预算"的最大比例（估算量随比例单调不减）。固定 24 次 ⇒ 确定性（§5-4）。
        var lo = 0.0
        var hi = 1.0
        repeat(24) {
            val mid = (lo + hi) / 2
            val ops = estimateCoarseOps(
                windowAt(mid), templateWidth, templateHeight, frameWidth, frameHeight,
            )
            if (ops <= maxOps) lo = mid else hi = mid
        }
        return windowAt(lo)
    }

    /**
     * 峰值抑制半径的分母：**短边 / 此值**（见 [effectiveSuppressRadius]）。
     *
     * 取 3 的依据是真机实录（2026-09-29，`139×74` 的锚点模板，帧 3168×1440）：
     * ```
     * 抑制半径 5px（当时口径）:  最高 0.9752 @ (74,13) / 次高 0.9033 @ (76,13)  ← 仅相距 2px
     * 抑制半径 40px:             最高 0.9752 @ (74,13) / 次高 0.4213 @ (60,13)  ← 真正的别处
     * ```
     * ⇒ 那个"次高分"根本不是别处，而是**同一个峰的肩膀**（NCC 峰顶平坦，模板越大越平）；
     * 而它 ≥ 命中线 0.85 ⇒ `judge` 判「多处高分」⇒ **一条实际匹配到 0.975 的锚点被判成认不出**。
     * 短边 / 3 = 24px 已足以吃掉这个肩宽（实测肩部在 12px 内仍 ≥ 0.85），
     * 而画面里**真正重复出现的图案**相距都在几百像素量级 ⇒ 仍会被当成竞争位置（红线 7 不破）。
     */
    const val PEAK_SUPPRESS_DIVISOR = 3

    /**
     * **实际使用的峰值抑制半径** = max([MatchParams.peakMinDistance], 模板短边 / [PEAK_SUPPRESS_DIVISOR])。
     *
     * 为什么不直接用标定里的 [MatchParams.peakMinDistance]：那是个**与模板尺寸无关**的固定像素数（产物里 5），
     * 而 NCC 峰顶的**平坦程度随模板变大而变宽** —— 大模板用 5px 抑制，等于把"自己身边的肩部"
     * 当成了"第二处位置"（真机实测见 [PEAK_SUPPRESS_DIVISOR]）。这是**设备无关**的算法特性
     * （只与模板尺寸有关，不引入设备绑定参数，红线 4 不破）。
     */
    fun effectiveSuppressRadius(params: MatchParams, templateWidth: Int, templateHeight: Int): Int {
        if (templateWidth <= 0 || templateHeight <= 0) return params.peakMinDistance
        return maxOf(params.peakMinDistance, minOf(templateWidth, templateHeight) / PEAK_SUPPRESS_DIVISOR)
    }

    /**
     * **分段计时探针**（N6-1，2026-10-03）：把 [findPeaks] 的墙钟拆成五段，回答
     * **"单条信号那 60~80ms 到底花在哪"**。
     *
     * ## 为什么需要它
     *
     * 成本模型（[estimateCoarseOps]）只算**粗搜乘加**：健康标志 3~8M ops ⇒ 按 6~10ms/百万 应该是
     * 21~56ms，而**真机实测 60~80ms**（逐信号 P95，见 `MM-Capture: 信号耗时统计`）⇒
     **有 2~3 倍的耗时不在这个模型里**。不先归因，后面任何提速手段（重框 / 采样 / 提并行度）
     **都是在猜**。
     *
     * ## 契约
     *
     * - **默认 `null` ⇒ 生产零开销**：每段只多一次空判断，不改变任何判定值、结果与顺序；
     * - 只**观测**，不许改任何行为（探针拿到的是已算完的分段耗时）；
     * - 段名固定为 [PHASE_PREFIX] / [PHASE_COARSE] / [PHASE_PICK] / [PHASE_REFINE] / [PHASE_SUPPRESS]，
     *   顺序即执行顺序；段和应约等于整段墙钟（探针自检会断言这一点）。
     */
    interface MatchCostProbe {
        fun phase(name: String, ms: Double, note: String = "")
    }

    const val PHASE_PREFIX = "prefix"
    const val PHASE_COARSE = "coarse"
    const val PHASE_PICK = "pick"
    const val PHASE_REFINE = "refine"
    const val PHASE_SUPPRESS = "suppress"

    /**
     * 在 [image] 的 [window] 范围内扫描 [template]，返回候选峰值（分数降序、彼此分离）。
     *
     * 峰值 = 经非极大抑制的局部最优位置：抑制半径 [effectiveSuppressRadius] 内的
     * 候选视为同一处、只保留最高分——最高与次高分构成「竞争位置」审计基础（§5-1/§5-2）。
     *
     * [probe] 只做分段计时（N6-1 归因用），默认 null。
     */
    fun findPeaks(
        image: GrayImage,
        template: Template,
        window: SearchWindow,
        params: MatchParams,
        probe: MatchCostProbe? = null,
    ): List<MatchPeak> {
        if (template.centeredSumSq <= VARIANCE_EPS) return emptyList() // 常量模板：无有效纹理

        val bounds = window.pixelBounds(image.width, image.height)
        val maxX = bounds.x1 - template.width
        val maxY = bounds.y1 - template.height
        if (maxX < bounds.x0 || maxY < bounds.y0) return emptyList() // 窗口装不下模板

        val prefixStarted = System.nanoTime()
        val prefix = WindowPrefixSums.build(image, bounds)
        val sample = SampleView.of(template, image.width)
        val area = template.width * template.height
        probe?.phase(PHASE_PREFIX, msSince(prefixStarted), "窗口 ${bounds.x1 - bounds.x0}×${bounds.y1 - bounds.y0}")

        // 粗搜：固定网格 + 采样打分（近似值，仅用于挑选精搜邻域）
        val coarseStarted = System.nanoTime()
        val xs = gridPositions(bounds.x0, maxX)
        val ys = gridPositions(bounds.y0, maxY)
        val coarse = ArrayList<MatchPeak>(xs.size * ys.size)
        for (y in ys) {
            for (x in xs) {
                val score = if (sample.sumSq > VARIANCE_EPS) {
                    coarseScore(image, sample, prefix, bounds, area, x, y)
                } else {
                    // 采样点无纹理的退化模板（罕见）：粗搜直接全精度保底
                    exactScore(image, template, prefix, bounds, x, y)
                }
                if (score > 0.0) coarse.add(MatchPeak(score, x, y))
            }
        }
        val coarseMs = msSince(coarseStarted)
        probe?.phase(PHASE_COARSE, coarseMs, "${xs.size}×${ys.size} 位置")
        if (coarse.isEmpty()) return emptyList()
        // 代表点贪心：若直接取前 K 名，同一峰簇的邻近网格点会占满名额、漏掉远处的
        // 次强峰（多峰 / 竞争位置场景）；互距约束保证各峰簇均有代表点进入精搜。
        val pickStarted = System.nanoTime()
        val anchors = selectRepresentatives(coarse, maxOf(params.peakMinDistance, COARSE_STEP), COARSE_TOP_K)
        probe?.phase(PHASE_PICK, msSince(pickStarted), "候选 ${coarse.size} → 锚点 ${anchors.size}")

        // 精搜：锚点邻域内全精度重算（候选分数与穷举实现一致）
        val refineStarted = System.nanoTime()
        val radius = COARSE_STEP / 2
        val candidates = ArrayList<MatchPeak>(anchors.size * (2 * radius + 1) * (2 * radius + 1))
        for (anchor in anchors) {
            val x0 = (anchor.x - radius).coerceAtLeast(bounds.x0)
            val x1 = (anchor.x + radius).coerceAtMost(maxX)
            val y0 = (anchor.y - radius).coerceAtLeast(bounds.y0)
            val y1 = (anchor.y + radius).coerceAtMost(maxY)
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val score = exactScore(image, template, prefix, bounds, x, y)
                    if (score > 0.0) candidates.add(MatchPeak(score, x, y))
                }
            }
        }
        probe?.phase(PHASE_REFINE, msSince(refineStarted), "锚点 ${anchors.size} × 邻域 ${(2 * radius + 1) * (2 * radius + 1)}")

        val suppressStarted = System.nanoTime()
        val peaks = suppressPeaks(candidates, effectiveSuppressRadius(params, template.width, template.height))
        probe?.phase(PHASE_SUPPRESS, msSince(suppressStarted), "候选 ${candidates.size} → 峰值 ${peaks.size}")
        return peaks
    }

    private fun msSince(startedNs: Long): Double = (System.nanoTime() - startedNs) / 1_000_000.0

    /**
     * 覆盖 [from, to] 的粗搜网格点：步长 ≤ [COARSE_STEP]、末点必为 [to]、
     * 相邻间距 ≤ [COARSE_STEP]——保证任一位置到最近网格点距离 ≤ [COARSE_STEP]/2（精搜半径）。
     */
    private fun gridPositions(from: Int, to: Int): IntArray {
        val points = ArrayList<Int>((to - from) / COARSE_STEP + 2)
        var v = from
        while (v <= to) {
            points.add(v)
            v += COARSE_STEP
        }
        if (points.last() != to) points.add(to)
        return points.toIntArray()
    }

    /**
     * 从粗搜候选中选 [limit] 个「代表点」：按分数降序（同分按 y、x 升序）贪心遍历，
     * 与已选点切比雪夫距离 < [minSpacing] 的候选跳过——保证多峰场景下各峰簇均有代表点
     * 进入精搜，不被单一峰簇的邻近点挤占全部名额。
     */
    private fun selectRepresentatives(coarse: List<MatchPeak>, minSpacing: Int, limit: Int): List<MatchPeak> {
        val sorted = coarse.sortedWith(
            compareByDescending<MatchPeak> { it.score }.thenBy { it.y }.thenBy { it.x },
        )
        val picked = ArrayList<MatchPeak>(limit)
        for (candidate in sorted) {
            if (picked.size >= limit) break
            val near = picked.any {
                abs(it.x - candidate.x) < minSpacing && abs(it.y - candidate.y) < minSpacing
            }
            if (!near) picked.add(candidate)
        }
        return picked
    }

    /**
     * 粗搜分数：分子为采样点上的 Σ(centered·p)，分母为 采样点 Σ(centered²) × 全窗口方差
     * （窗口统计量取自整数前缀和，精确）。与全精度分数同向、量级略低（采样稀疏所致），
     * 只用于排序；**不参与阈值 / 差距判定**。
     */
    private fun coarseScore(
        image: GrayImage,
        sample: SampleView,
        prefix: WindowPrefixSums,
        bounds: PixelBounds,
        area: Int,
        x: Int,
        y: Int,
    ): Double {
        val variance = windowVariance(prefix, bounds, x, y, sample.fullWidth, sample.fullHeight, area)
        if (variance / area < VARIANCE_EPS) return 0.0
        var dot = 0.0
        var rowStart = y * image.width + x
        val offsets = sample.offsets
        val centered = sample.centeredAt
        for (i in offsets.indices) {
            dot += centered[i] * (image.pixels[rowStart + offsets[i]].toInt() and 0xFF)
        }
        return dot / sqrt(sample.sumSq * variance)
    }

    /**
     * 位置 (x, y) 处模板与帧窗口的全精度 NCC 分数；帧窗口近乎恒定记 0（排除除零与噪声放大）。
     * 帧侧统计量（和 / 平方和）取自整数前缀和，仅分子逐像素累加。
     */
    private fun exactScore(
        image: GrayImage,
        template: Template,
        prefix: WindowPrefixSums,
        bounds: PixelBounds,
        x: Int,
        y: Int,
    ): Double {
        val area = template.width * template.height
        val variance = windowVariance(prefix, bounds, x, y, template.width, template.height, area)
        if (variance / area < VARIANCE_EPS) return 0.0
        val centered = template.centered
        var dot = 0.0
        var rowStart = y * image.width + x
        var ti = 0
        for (ty in 0 until template.height) {
            var i = rowStart
            for (tx in 0 until template.width) {
                dot += centered[ti] * (image.pixels[i].toInt() and 0xFF)
                i++
                ti++
            }
            rowStart += image.width
        }
        // dot = Σ t'·p；因 Σt' = 0，它等于 Σ t'·(p − p̄)，即 NCC 分子
        return dot / sqrt(template.centeredSumSq * variance)
    }

    /** 帧窗口的未归一化方差（Σ(p − p̄)² = Σp² − (Σp)²/n；整数前缀和精确累加）。 */
    private fun windowVariance(
        prefix: WindowPrefixSums,
        bounds: PixelBounds,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        area: Int,
    ): Double {
        val rx = x - bounds.x0
        val ry = y - bounds.y0
        val sumP = prefix.rectSum(rx, ry, width, height)
        val sumP2 = prefix.rectSumSq(rx, ry, width, height)
        return sumP2.toDouble() - sumP.toDouble() * sumP / area
    }

    /**
     * 贪心非极大抑制：按分数降序（同分按 y、x 升序）依次选取峰值，
     * 抑制其 [minDistance] 半径内的其余候选。确定性 tie-break 保证可复现（§5-4）。
     */
    fun suppressPeaks(candidates: List<MatchPeak>, minDistance: Int): List<MatchPeak> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedWith(
            compareByDescending<MatchPeak> { it.score }.thenBy { it.y }.thenBy { it.x }
        )
        val picked = ArrayList<MatchPeak>()
        for (candidate in sorted) {
            val suppressed = picked.any {
                abs(it.x - candidate.x) < minDistance && abs(it.y - candidate.y) < minDistance
            }
            if (!suppressed) picked.add(candidate)
        }
        return picked
    }
}

/**
 * 窗口区前缀和（summed-area table；整数精确，T1-10a）：O(1) 查询任意子矩形的
 * 像素和 / 平方和，替换 NCC 帧侧统计量的逐像素累加。坐标以搜索窗口左上角为原点。
 */
internal class WindowPrefixSums(
    private val stride: Int,
    private val sum: LongArray,
    private val sumSq: LongArray,
) {

    /** 相对窗口坐标矩形 [rx, rx+w) × [ry, ry+h) 的像素和。 */
    fun rectSum(rx: Int, ry: Int, w: Int, h: Int): Long =
        sum[(ry + h) * stride + rx + w] - sum[ry * stride + rx + w] -
            sum[(ry + h) * stride + rx] + sum[ry * stride + rx]

    /** 同上，平方和。 */
    fun rectSumSq(rx: Int, ry: Int, w: Int, h: Int): Long =
        sumSq[(ry + h) * stride + rx + w] - sumSq[ry * stride + rx + w] -
            sumSq[(ry + h) * stride + rx] + sumSq[ry * stride + rx]

    companion object {
        /** 构建覆盖 [bounds] 区域的前缀和（行主序，(cols+1) × (rows+1)）。 */
        fun build(image: GrayImage, bounds: PixelBounds): WindowPrefixSums {
            val cols = bounds.x1 - bounds.x0
            val rows = bounds.y1 - bounds.y0
            val stride = cols + 1
            val sum = LongArray((rows + 1) * stride)
            val sumSq = LongArray((rows + 1) * stride)
            for (ry in 1..rows) {
                var rowSum = 0L
                var rowSumSq = 0L
                val srcRow = (bounds.y0 + ry - 1) * image.width + bounds.x0
                val up = (ry - 1) * stride
                val cur = ry * stride
                for (rx in 1..cols) {
                    val p = image.pixels[srcRow + rx - 1].toInt() and 0xFF
                    rowSum += p
                    rowSumSq += (p * p).toLong()
                    sum[cur + rx] = sum[up + rx] + rowSum
                    sumSq[cur + rx] = sumSq[up + rx] + rowSumSq
                }
            }
            return WindowPrefixSums(stride, sum, sumSq)
        }
    }
}

/**
 * 采样模板视图（T1-10a）：按固定采样步长 [STEP] 抽取模板采样点，
 * 供粗搜打分使用（偏移 + centered 值 + Σcentered²）。
 */
internal class SampleView(
    /** 采样点相对模板左上角的帧内线性偏移（ty × 帧宽 + tx）。 */
    val offsets: IntArray,
    /** 采样点的模板灰度值，已按**采样子集自身**去均值（ΣcenteredAt = 0）。 */
    val centeredAt: DoubleArray,
    /** Σ centeredAt²（粗搜分母的模板项）。 */
    val sumSq: Double,
    val fullWidth: Int,
    val fullHeight: Int,
) {
    companion object {
        /** 采样步长：交错半采样（约 1/2 像素量参与粗搜）。 */
        private const val STEP = 2

        /**
         * 交错半采样（T1-10a）：偶行取偶列、奇行取奇列——纯「偶偶」抽取会整列漏掉
         * 奇列上的 1px 周期特征（细线等）；交错后任一 2×2 邻域至少命中 1 个采样点。
         *
         * 去均值按**采样子集自身**计算（ΣcenteredAt = 0 精确）：若沿用全模板均值，
         * 采样点 Σc ≠ 0 会给粗搜分子引入 t̄ · Σc 系统性偏移项，破坏排序可信度。
         */
        fun of(template: Template, imageWidth: Int): SampleView {
            var count = 0
            for (ty in 0 until template.height) {
                var tx = ty % STEP
                while (tx < template.width) {
                    count++
                    tx += STEP
                }
            }
            val offsets = IntArray(count)
            val values = DoubleArray(count)
            var k = 0
            for (ty in 0 until template.height) {
                var tx = ty % STEP
                while (tx < template.width) {
                    offsets[k] = ty * imageWidth + tx
                    values[k] = (template.pixels[ty * template.width + tx].toInt() and 0xFF).toDouble()
                    k++
                    tx += STEP
                }
            }
            var mean = 0.0
            for (v in values) mean += v
            mean /= count
            var sq = 0.0
            for (i in 0 until count) {
                val c = values[i] - mean
                values[i] = c
                sq += c * c
            }
            return SampleView(offsets, values, sq, template.width, template.height)
        }
    }
}
