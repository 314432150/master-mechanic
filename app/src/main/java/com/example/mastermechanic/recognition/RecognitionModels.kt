package com.example.mastermechanic.recognition

import kotlin.math.ceil
import kotlin.math.floor

/**
 * 识别层数据类型（T1-3，纯逻辑：不依赖安卓框架，可在 JVM 离线重跑）。
 *
 * 输入帧数据 = [GrayImage]；模板 / 搜索窗口 / 阈值全部来自标定产物（T1-5），
 * 本包不内置任何与具体设备绑定的分辨率、密度、位置或阈值（红线 4、§5-5）。
 */

/** 灰度帧：识别层的画面数据单位（单通道 0..255），与采集实现解耦。 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "帧尺寸必须为正：${width}x${height}" }
        require(pixels.size == width * height) { "像素数 ${pixels.size} 与尺寸 ${width}x${height} 不符" }
    }
}

/**
 * **定点乘加的放大倍数**（N6-5，2026-10-25）：内积热循环把去均值序列换成整数做乘加。
 *
 * 为什么是 4096：量化误差 = `1/(2×scale)` 个灰度级，逐项误差在 `√n` 项上随机累积。
 * 取 256 时在 `n≈5000` 的模板上误差约 **1e-6 量级**（正好压着 `TemplateMatcherOptimizedTest`
 * 的 `SCORE_TOLERANCE = 1e-6`，太险）；4096 把它压到 **~4e-8**，同时
 * `255×4096 = 1.04M` 仍在 Int 内、累加用 Long 不溢出。
 */
internal const val FIXED_SCALE = 4096

/**
 * 标志模板：标定阶段从目标设备画面采集的灰度小块；同一标志可配置多个模板（ADR-003）。
 * 构造时预计算均值与去均值数据（NCC 分子分母的常量项），匹配期间不再重复计算。
 */
class Template(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "模板尺寸必须为正：${width}x${height}" }
        require(pixels.size == width * height) { "像素数 ${pixels.size} 与尺寸 ${width}x${height} 不符" }
    }

    /** 模板像素去均值后的序列（Σ(centered) = 0）。 */
    internal val centered: DoubleArray

    /** Σ(centered²)：NCC 分母的模板项。 */
    internal val centeredSumSq: Double

    /**
     * [centered] 的**定点整数版**（×[FIXED_SCALE]，四舍五入）：内积热循环用它做整数乘加（N6-5）。
     *
     * 为什么值得：原循环每项要一次 `byte→int` + 一次 `int→double` 转换再做 FP 乘加（≈6~7 条指令），
     * 换成立即数与整数乘加 ≈3 条（`i2l` / `smull` / `add`）⇒ 真机整轮识别明显下降
     * （实测见 `docs/progress.md` 2026-10-25 的 N6-5 条目）。精度见 [FIXED_SCALE]。
     */
    internal val centeredFixed: IntArray

    /**
     * `Σ [centeredFixed]`（**不是 0**：定点量化有舍入偏差）。
     *
     * 内积最后要减掉 `帧窗口均值 × 这个和`，把"帧的整体偏亮/偏暗"（DC 项）精确扣掉 ——
     * 不扣的话，舍入偏差会与帧的亮度基准相乘，把分数抬高/压低到 **1e-5** 量级
     * （真机等价性单测实测：1.0000083 vs 1.0000000，超过了 1e-6 容差）。
     */
    internal val centeredFixedSum: Long

    init {
        val n = pixels.size
        var sum = 0.0
        for (i in 0 until n) sum += pixels[i].toInt() and 0xFF
        val mean = sum / n
        val c = DoubleArray(n)
        val fixed = IntArray(n)
        var fixedSum = 0L
        var sq = 0.0
        for (i in 0 until n) {
            val v = (pixels[i].toInt() and 0xFF) - mean
            c[i] = v
            fixed[i] = Math.round(v * FIXED_SCALE).toInt()
            fixedSum += fixed[i]
            sq += v * v
        }
        centered = c
        centeredFixed = fixed
        centeredFixedSum = fixedSum
        centeredSumSq = sq
    }
}

/** 像素范围闭开区间 [x0, x1) × [y0, y1)（由 [SearchWindow] 按帧尺寸换算而来）。 */
data class PixelBounds(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {

    /** 空区域（右侧/下侧不大于左侧/上侧即为空）。 */
    val isEmpty: Boolean get() = x1 <= x0 || y1 <= y0

    /**
     * **只取横向**的交集（2026-09-22，T4-3g）：横向收窄到两框的共同部分，**纵向仍是自己的**。
     *
     * 用法是"在「列表区域」里再裁出一条名称列"（第 9 步的 OCR 输入）：那个"列表区域"（接收者）
     * 负责纵向，`other`（名称列）**只当横向限制器** —— 用户框名称列时**不必**与列表区域上下齐平
     * （只框一行高也行），所以纵向**故意不参与**求交。理由见 `NameLocating.visitRanges`。
     *
     * **横向无交集时不夹取**：[isEmpty] 为真就是真没对上，调用方据此如实报"框得不对"。
     * 夹成零宽区域会把"没有交集"伪装成"有一条空列"，那是猜。
     */
    fun intersectHorizontally(other: PixelBounds): PixelBounds = PixelBounds(
        x0 = maxOf(x0, other.x0),
        y0 = y0,
        x1 = minOf(x1, other.x1),
        y1 = y1,
    )
}

/** 搜索窗口：相对帧尺寸的比例矩形（0..1）。像素范围按帧尺寸换算，因此零设备常量。 */
data class SearchWindow(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0) {
            "比例必须在 0..1：($left, $top, $right, $bottom)"
        }
        require(right >= left && bottom >= top) { "窗口右/下不得小于左/上" }
    }

    /** 换算为像素范围并裁剪到帧内。换算用 floor/ceil，保证确定性（§5-4）。 */
    fun pixelBounds(imageWidth: Int, imageHeight: Int): PixelBounds = PixelBounds(
        x0 = floor(left * imageWidth).toInt().coerceIn(0, imageWidth),
        y0 = floor(top * imageHeight).toInt().coerceIn(0, imageHeight),
        x1 = ceil(right * imageWidth).toInt().coerceIn(0, imageWidth),
        y1 = ceil(bottom * imageHeight).toInt().coerceIn(0, imageHeight),
    )

    companion object {
        /**
         * 收紧后模板四周**至少**保留的搜索余量（像素，2026-09-21；**2026-09-24 由 8 提到 32**）。
         *
         * 为什么是绝对值而不是比例：小模板在比例口径下没救 —— 0.6 倍"小窗口"还是小窗口。
         *
         * **为什么从 8 提到 32**（真机实测，`docs/progress.md` 第 188 条）：收紧是**以窗口中心**收窄的，
         * 而元素在真机上**未必落在标定窗口中心** —— 实测 `hall_settings`（模板 77×79、窗口 231×199）
         * 的命中位置比窗口中心**高 ≈20px**（`MM-Click 帧点 (2817,80)` vs 窗口中心 `(2817.5,99.5)`）。
         * 8px 只够盖住"手抖 1~3px"，盖不住这种 ~20px 的框选偏离 ⇒ 收紧到 0.45 之后**正确位置落到窗口外**
         * ⇒ 大厅永远认不出（`hall_settings` 分数停在 0.365，跑号第 6 步等 15s 超时中止，
         * 而用户明明就站在大厅）。32 = 实测偏离 20px + 12px 安全余量。
         *
         * 代价如实：中小模板的搜索区会回到接近 0.6 的水平（单轮识别 ≈0.16s → 预计 ≈0.3s），
         * 换来的是"框得偏一点也照样找得到"——**宁可慢一点点，也不能认不出画面**。
         * 下限比原窗口还宽时按"只收紧不放大"夹回原窗口（见 [tightened]）。
         *
         * **不是设备绑定常量**：它限制的是"搜索区相对模板的富余"，与分辨率 / 密度无关（红线 4）。
         */
        const val MIN_ABSOLUTE_MARGIN_PX = 32.0
    }
}

/**
 * 匹配参数：全部由调用方（标定产物）提供，代码不内置具体值。
 *
 * - [matchThreshold] 命中线：最高分低于此值即未命中；§5-2 的"多处高分"判据亦以此为准
 *   （竞争位置也达到命中线 → 画面中存在多个可能命中处）；
 * - [ambiguityMargin] 差距线：最高分与竞争分差距小于此值即不可信（§5-2"差距过小"）；
 * - [peakMinDistance] 峰值最小间距：距离小于此值的候选视为同一处（非极大抑制半径）。
 */
data class MatchParams(
    val matchThreshold: Double,
    val ambiguityMargin: Double,
    val peakMinDistance: Int,
) {
    init {
        require(matchThreshold > 0.0 && matchThreshold <= 1.0) { "命中线须在 (0, 1]：$matchThreshold" }
        require(ambiguityMargin >= 0.0) { "差距线不得为负：$ambiguityMargin" }
        require(peakMinDistance >= 1) { "峰值间距至少为 1：$peakMinDistance" }
    }
}

/**
 * **粗搜采样档**（N6-4，2026-10-25）：粗搜"每个候选位置取多少个采样点"与"送几个代表点去精搜"。
 *
 * ## 为什么必须按角色分叉，而不是全局调
 *
 * 粗搜分数**只用于排序**（不参与判定），所以采样变稀只影响"真峰能不能排进前 K"。对**标志**
 * 来说这没关系——它只回答"这个界面在不在"，位置漂 1~2px 无人在意；对**锚点**不行：
 * 锚点要用匹配位置算**点击坐标**（红线 3 的同一件纪律：不挑"最像的"），位置一漂就是点偏。
 * ⇒ 标志走 [SPARSE]，锚点**恒**走 [FULL]。
 *
 * ## 依据（2026-10-25 真机已确认的两条前提）
 *
 * ① 单条信号耗时是**单线程固有成本**、与并行度无关（p1/p3/p6 单条均 74.7/70.0/76.1ms）
 * ⇒ 桌面探针量到的加速比**能迁移**到真机；② 粗搜占单条成本的大头（N6-1 桌面归因 coarse 78.7%）。
 * 但"采样能不能变稀"最终只由**判定不漂**决定（离线门槛 `N6SamplingProbeTest` + 真机逐信号 P95）。
 */
data class CoarseSampling(
    /** 采样步长：2 = 交错半采样（约 `w·h/2` 个采样点），3 = 3×3（约 `w·h/9`）。 */
    val sampleStep: Int,
    /** 送进精搜的代表点上限（每个代表点再在自己的 ±1px 邻域做全精度重算）。 */
    val topK: Int,
) {
    init {
        require(sampleStep >= 2) { "采样步长至少 2（取 1 等于全采样，不是「稀疏」）：$sampleStep" }
        require(topK >= 1) { "代表点至少 1 个：$topK" }
    }

    companion object {
        /** 生产默认，也是**锚点唯一允许的档**：交错半采样 + 64 个代表点。 */
        val FULL = CoarseSampling(sampleStep = 2, topK = 64)

        /** 只稀疏采样、代表点不减（用来把"采样"与"代表点数"两笔收益拆开算）。 */
        val SPARSE_WIDE = CoarseSampling(sampleStep = 3, topK = 64)

        /** 标志的目标档：3×3 采样 + 32 个代表点。 */
        val SPARSE = CoarseSampling(sampleStep = 3, topK = 32)

        /**
         * **标志当前生效的档**（2026-10-25 已翻到 [SPARSE]）。
         *
         * 离线门槛已过（`N6SamplingProbeTest`：真机产物 12 条标志 × 25 帧真机画面，
         * **判定漂移 0/300**、best 分数最大只掉 0.0026、标志整体每帧 167.6 → 108.6ms = ×1.54）。
         * 回退 = 改回 [FULL]，一行；锚点不受影响（它们恒用 [FULL]）。
         */
        val MARKER: CoarseSampling = SPARSE
    }
}

/** 信号规格：一个界面标志 = 名称 + 搜索窗口 + 一个或多个模板。 */
class SignalSpec(
    val name: String,
    val window: SearchWindow,
    val templates: List<Template>,
    /**
     * 粗搜采样档（N6-4）：标志传 [CoarseSampling.MARKER]，锚点**必须**用 [CoarseSampling.FULL]。
     *
     * 默认 [CoarseSampling.FULL] 是刻意的：所有既有构造点（探针 / 单测 / 旧路径）行为一字不变，
     * 分叉只发生在"从产物装配信号"的那两处（`CalibrationData.markerSpecs` / `anchorSpecs`）。
     */
    val sampling: CoarseSampling = CoarseSampling.FULL,
) {
    init {
        require(name.isNotBlank()) { "信号名称不得为空" }
        require(templates.isNotEmpty()) { "信号至少需要一个模板" }
    }
}

/** 候选峰值：相似度分数（置信度）+ 位置（帧像素坐标，为模板左上角）。 */
data class MatchPeak(val score: Double, val x: Int, val y: Int)

/** 判定结论：命中 / 未命中 / 不可信（不可信按未识别处理，§5-2、红线 7）。 */
enum class Verdict { MATCHED, NOT_MATCHED, UNRELIABLE }

/**
 * 判定记录（§5-1）：命中的标志、位置、置信度、同画面最强竞争位置，四项齐全。
 *
 * [best] 为画面最高分候选（未命中时也保留，供审计）；[competitor] 为经峰值抑制后
 * 的次高分离候选，不存在时为 null。
 */
data class DetectionRecord(
    val signalName: String,
    val verdict: Verdict,
    val best: MatchPeak?,
    val competitor: MatchPeak?,
) {
    /** 是否命中（不可信与未命中均不算命中）。**要点击的锚点只认这一条**（位置歧义一票否决，红线 7）。 */
    val matched: Boolean get() = verdict == Verdict.MATCHED

    /**
     * **"这一屏上在不在"**（标志语义；2026-09-29 加）：命中，或"不可信但最高分够高"。
     *
     * 与 [matched] 的分工就是**"判状态"与"点坐标"的分工**：
     * - 标志（判状态）只问"在不在" ⇒ 位置歧义不影响答案，最高分够高就当在场；
     * - 锚点（要点击）必须知道"点哪儿" ⇒ 有竞争位置就宁可不点（[AnchorLocator.hitOf] 只看 [matched]）。
     *
     * 真机成因见 [SignalStateMapping.resolve] 的注释（用户报"新手引导页的标志连当前帧都搜不到"）。
     */
    val present: Boolean
        get() = matched ||
            (verdict == Verdict.UNRELIABLE && (best?.score ?: 0.0) >= PRESENCE_SCORE)

    companion object {
        /**
         * "最高分高到这个程度 ⇒ 即便有竞争位置，也当作**在场**"（取 **0.97**）。
         *
         * 依据（真机写入试读，2026-09-29）：
         * `tutorial_guide_e1｜marker｜试读 ⚠ 不可信（最高 1.00，但竞争位置 (204, 38) 也有 0.90）`
         * —— 1.00 是**像素级满分**（同一个像素阵列），不可能出现在错的地方；0.90 那一侧才是
         * "像但不是"。命中线 0.85 回答的是"够不够像"，0.97 回答的是"是不是它本人"。
         * ⚠ 调低 = 更多歧义被当成在场（更容易认错界面）；调高 = 更多标志回到"认不出"。
         */
        const val PRESENCE_SCORE = 0.97
    }
}
