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
     * 在 [image] 的 [window] 范围内扫描 [template]，返回候选峰值（分数降序、彼此分离）。
     *
     * 峰值 = 经非极大抑制的局部最优位置：抑制半径 [MatchParams.peakMinDistance] 内的
     * 候选视为同一处、只保留最高分——最高与次高分构成「竞争位置」审计基础（§5-1/§5-2）。
     */
    fun findPeaks(image: GrayImage, template: Template, window: SearchWindow, params: MatchParams): List<MatchPeak> {
        if (template.centeredSumSq <= VARIANCE_EPS) return emptyList() // 常量模板：无有效纹理

        val bounds = window.pixelBounds(image.width, image.height)
        val maxX = bounds.x1 - template.width
        val maxY = bounds.y1 - template.height
        if (maxX < bounds.x0 || maxY < bounds.y0) return emptyList() // 窗口装不下模板

        val prefix = WindowPrefixSums.build(image, bounds)
        val sample = SampleView.of(template, image.width)
        val area = template.width * template.height

        // 粗搜：固定网格 + 采样打分（近似值，仅用于挑选精搜邻域）
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
        if (coarse.isEmpty()) return emptyList()
        // 代表点贪心：若直接取前 K 名，同一峰簇的邻近网格点会占满名额、漏掉远处的
        // 次强峰（多峰 / 竞争位置场景）；互距约束保证各峰簇均有代表点进入精搜。
        val anchors = selectRepresentatives(coarse, maxOf(params.peakMinDistance, COARSE_STEP), COARSE_TOP_K)

        // 精搜：锚点邻域内全精度重算（候选分数与穷举实现一致）
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
        return suppressPeaks(candidates, params.peakMinDistance)
    }

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
