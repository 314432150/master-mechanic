package com.example.mastermechanic.patrol

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.PixelBounds
import kotlin.math.sqrt

/**
 * **好友列表文字识别试读探针**（2026-09-23 用户要求：验证 OCR 能不能正确读出好友名）。
 *
 * ## 为什么需要它
 *
 * 文字识别的链路（区域 → 引擎 → **取语义字段 + 全等**）**只能在真机上跑**：引擎是 ML Kit（安卓）。
 * 于是"改完算法到底有没有变好"过去只能靠**跑一遍完整跑号**去撞第 9 步 —— 成本极高、
 * 还夹着一堆无关变量（进不进得去好友列表、网络、账号状态）。
 *
 * 本探针把这件事拆小：**在标定工作台上对着某一帧当场读一次**，把
 * "读到什么原文 / 匹配看的是哪些候选 / 配置里的名字对上了没"一次列全。
 * 帧是死的 ⇒ 同一帧反复试、结果可比；算法一改就能立刻复查。
 *
 * ## 与跑号的关系（口径必须同源，否则试读没意义）
 *
 * 输入区域、去重、匹配**全部复用生产那一套**：
 * - 区域：[NameLocating.visitRanges]（列表区域 × 好友名称列）—— 与
 *   `CaptureService.namePlanOf` ① 逐句同源（那里是权威实现，这里只是把同一套推导
 *   搬到"帧池里的某一帧"上；运行帧方向不同时的换算由 `AnchorLocator.windowInFrame` 统一处理）；
 * - 候选：`NameLocating.collapsed`（同一行的"整行 vs 行内词"只留一个）—— 匹配真正吃的是它；
 * - 匹配：[NameLocating.locateFriend]（**取括号里的备注名 + 全等**，2026-09-24 口径）—— 生产同款。
 *
 * 本对象**不碰安卓**（帧解码、引擎调用在 UI 侧的 runner 里），所以可以在 JVM 单测里钉住口径。
 */
object FriendListOcrProbe {

    /** 日志 / 取证用 TAG（`MmLog` 会双写 logcat 与应用私有文件，事后可拉取）。 */
    const val TAG = "MM-OcrProbe"

    /**
     * 试读的单次等待上限：**故意比生产的 1 秒宽松**。
     *
     * 探针要回答的第一个问题是"这些字到底读不读得出来"，超时上限卡死了就什么都答不出来；
     * 而"读一次要多久"由探针**单独报耗时**（[Report.elapsedMs]）—— 那个数字才是判断
     * 生产那 1 秒够不够的依据。
     */
    const val TIMEOUT_MS = 5_000L

    /** 试读的输入区域；[Region.Missing] 的每种原因都要能在界面上说清"该去改什么"。 */
    sealed interface Region {
        /**
         * @param bounds **真正送去识别的区域**（= 列表区域 ∩ 名称列的横向）
         * @param clippedByColumn 有没有框过「好友名称列」（false = 整片列表区域都送去读）
         * @param listArea 「列表区域」自己框出来的范围（**原始值**）
         * @param nameColumn 「好友名称列」自己框出来的范围（没框过 → null）
         *
         * [listArea] / [nameColumn] 是**诊断用**的：光看 [bounds]（交集）分不清"是哪个框不对"——
         * 2026-09-23 真机就踩过：名称列被框成"屏幕中线→右边缘"，交集等于没裁，只看交集会误判成代码没生效。
         * 把两个原始框一起报出来，一眼就能看出该去重框哪一个。
         */
        data class Ok(
            val bounds: PixelBounds,
            val clippedByColumn: Boolean,
            val listArea: PixelBounds,
            val nameColumn: PixelBounds?,
        ) : Region

        data class Missing(val reason: Reason) : Region
    }

    enum class Reason {
        /** 产物里没有「列表区域」锚点 —— 没标定，不是"读不出来"。 */
        NO_LIST_AREA,

        /** 锚点在，但窗口换算不出有效矩形。 */
        EMPTY_WINDOW,

        /** 「好友名称列」与「列表区域」横向没有交集（两个框对不上）。 */
        EMPTY_AFTER_CLIP,
    }

    /**
     * 由产物推导试读的输入区域（= 跑号第 9 步那一块）。
     *
     * @param frameWidth / [frameHeight] 被试读那一帧的像素尺寸（帧池里的原始尺寸，不是预览缩略图）
     */
    fun region(data: CalibrationData, frameWidth: Int, frameHeight: Int): Region {
        val locator = data.toAnchorLocator(PatrolAnchors.nonLocatableAnchors)
        val areaId = data.anchorIdFor(UiState.FRIEND_LIST, PatrolAnchors.FRIEND_LIST_AREA)
            ?: return Region.Missing(Reason.NO_LIST_AREA)
        val listArea = locator.windowInFrame(frameWidth, frameHeight, UiState.FRIEND_LIST, areaId)
            ?: return Region.Missing(Reason.EMPTY_WINDOW)
        // 「好友名称列」可选（与生产同口径）：没框过就退回整片列表区域，由界面明说
        val nameColumn = data.anchorIdFor(UiState.FRIEND_LIST, PatrolAnchors.FRIEND_LIST_NAME_COLUMN)
            ?.let { id -> locator.windowInFrame(frameWidth, frameHeight, UiState.FRIEND_LIST, id) }
        val region = NameLocating.visitRanges(listArea, nameColumn).ocrRegion
        if (region.isEmpty) return Region.Missing(Reason.EMPTY_AFTER_CLIP)
        return Region.Ok(
            bounds = region,
            clippedByColumn = nameColumn != null,
            listArea = listArea,
            nameColumn = nameColumn,
        )
    }

    /** 一个名字在本屏候选里的匹配结论（[detail] 未命中时是 [NameLocator] 给的原文原因）。 */
    data class Match(
        val name: String,
        val found: Boolean,
        val detail: String,
    )

    /**
     * 单个名字 → 匹配结论（**生产同款**：取括号内备注 + 全等；**多个同名 ⇒ 取最上面那一个**，2026-09-30）。
     *
     * 2026-09-24 口径（`docs/progress.md` 第 183 条）：配的名字 = 游戏中的**备注名**，
     * 所以只比括号里那一段。读歪一个字、或括号没读出来，都是"未命中 ⇒ 停下"（红线 3）——
     * 不再有"包含"或"忽略符号"这种能凑出命中的档。
     * ⚠ 2026-09-30 起"同名多行"不再算未命中（走 [NameLocating.locateFriend]，取最上面），
     * 所以这个探针与生产的一致性靠**直接调用同一个函数**保证（见下）。
     */
    fun match(name: String, candidates: List<NameCandidate>): Match =
        when (val found = NameLocating.locateFriend(name, listOf(candidates))) {
            is NameLocator.Result.Found -> Match(name, found = true, detail = "")
            is NameLocator.Result.NotFound -> Match(name, found = false, detail = found.detail)
        }

    /** 逐个名字跑同一套匹配（顺序与入参一致：界面按清单顺序列，便于逐条对账）。 */
    fun matchAll(names: List<String>, candidates: List<NameCandidate>): List<Match> =
        names.map { match(it, candidates) }

    /** 匹配真正吃到的候选文本（同行去重后）—— 用来看"引擎读出来的哪一条会被拿来比"。 */
    fun candidateTexts(candidates: List<NameCandidate>): List<String> =
        NameLocating.collapsed(candidates).map { it.name }

    /**
     * 取"好友昵称(备注名)"里**括号中的备注名**（2026-09-23 用户口径：备注由自己填，可规避特殊字符）。
     *
     * ## 用途：**它就是匹配规则本身**（2026-09-24 起）
     *
     * 游戏里好友行显示成 `昵称(备注名)`。备注名是用户自己填的 ⇒ 可以刻意选引擎读得准的字，
     * 从根上绕开"生僻字符被形近替换"（`细细๓听` 的 `๓` 被读成 `s`）。
     *
     * 第 9 步的匹配 = **取这一段 + 与配置的名字全等**（`NameLocator.remarkOf` 与本函数的取值规则
     * 逐条一致）；报告里把这一段单独标出来，是为了让用户一眼确认
     * "我在好友清单里填的备注名，屏幕上真的读出来了没"。
     *
     * ## 取值规则（宽松，因为引擎会把括号读坏）
     *
     * - 认 **ASCII `()` 与全角 `（）`** 两种括号；
     * - 取**最后一个**左括号（昵称自己可能带括号，备注总在最后那段）；
     * - **允许缺右括号**（读到行尾即止）；
     * - 内容去空白后为空 → null（`()` 这种空括号不算备注）。
     *
     * @return 备注名；null = 这一行没有括号／括号里是空的
     */
    fun remarkIn(text: String): String? {
        val open = maxOf(text.lastIndexOf('('), text.lastIndexOf('（'))
        if (open < 0) return null
        val close = text.indexOfFirstFrom(open + 1) { it == ')' || it == '）' }
        val content = if (close < 0) text.substring(open + 1) else text.substring(open + 1, close)
        return content.filterNot { it.isWhitespace() }.ifEmpty { null }
    }

    /** 从 [from] 起第一个满足 [predicate] 的下标；没有 → -1（避免为一行文字引入正则）。 */
    private inline fun String.indexOfFirstFrom(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return -1
    }

    /**
     * 「近乎全平」的标准差阈值：一块**纯色**区域的灰度标准差接近 0（压缩噪声也就个位数），
     * 而**任何有文字**的截图都远高于这个值（真机名字行那块实测标准差 30+）。
     *
     * 它是**诊断提示**，不是判定依据 —— 读数照旧只看"引擎读到了什么"，这里只回答
     * "读不到"属于哪一类：区域框错 / 这一帧是空的 / 字太小。
     */
    private const val FLAT_STDDEV = 5.0

    /** [Region.Ok.bounds] 这块像素的粗统计（诊断"读不到"是哪一种用的）。 */
    data class Stats(val mean: Double, val stdDev: Double) {
        /** 近乎全平 ⇒ 这一块**根本没有内容**（帧是空的 / 黑屏），与"字太小读不出"是两回事。 */
        val flat: Boolean get() = stdDev < FLAT_STDDEV
    }

    /**
     * 算 [bounds] 内的灰度均值与标准差。
     *
     * ## 为什么要它（2026-09-23 真机第二次"读不到"）
     *
     * "一行文字都没读到"有三种完全不同的原因，而报告里当时分不出来：
     * ① 区域框错（框到空白处）；② **这一帧本身是空的**（采集会话与屏幕方向不匹配、黑屏）；
     * ③ 字太小/太糊。第二种在日志里的特征很隐蔽 —— 连模板匹配的分数都会一起消失
     * （`findPeaks` 对全平窗口返回空 ⇒ 日志打 `-`），只能靠翻帧管线日志看出来。
     * 把这一块的均值/标准差直接印在报告里，一眼就能定性。
     *
     * 越界部分按帧界裁剪（区域理论上已被裁剪过，这里是防御性的）。
     */
    fun stats(image: GrayImage, bounds: PixelBounds): Stats {
        val x0 = bounds.x0.coerceIn(0, image.width)
        val x1 = bounds.x1.coerceIn(x0, image.width)
        val y0 = bounds.y0.coerceIn(0, image.height)
        val y1 = bounds.y1.coerceIn(y0, image.height)
        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        for (y in y0 until y1) {
            val row = y * image.width
            for (x in x0 until x1) {
                val value = (image.pixels[row + x].toInt() and 0xFF).toDouble()
                sum += value
                sumSq += value * value
                count++
            }
        }
        if (count == 0) return Stats(0.0, 0.0)
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        return Stats(mean, sqrt(variance))
    }

    /**
     * 说明（T4-6，2026-09-23）：这里原先还有 `pictureArea` / `isInside` 两个方法 ——
     * 它们把"帧内真正有画面的那一条带"（画面区）印进试读报告，并判断读取区域是否整块落在其中。
     *
     * 它们随**画布方向归一**一起拆除了：画面的"一条带 + 上下留白"是"帧与屏幕几何不一致"的产物，
     * 而转屏现在由 `VirtualDisplay.resize()` 跟进 ⇒ **帧恒与屏幕同向同尺寸**、画面铺满整帧。
     * 于是"区域落在留白上"这一类失败模式**从根上消失**；报告里保留的 [stats]（区域质检）
     * 与整帧质检仍能把"读到 0 行"分成 **框错 / 帧本身是黑的**。
     */
}
