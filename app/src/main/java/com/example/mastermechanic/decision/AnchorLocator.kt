package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.DetectionRecord
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.PixelBounds
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalDetector
import com.example.mastermechanic.recognition.SignalSpec

/**
 * 动作锚点定位（T2-3b，纯逻辑）：回答"当前这个界面要点哪里"，**只定位当前状态声明的锚点**。
 *
 * - **按当前状态启用**：只有「当前状态」规则的锚点参与匹配；别的状态的锚点这一轮根本不搜——
 *   既省成本，也不会把别界面的控件当成可点目标（红线 1 的"状态未知 → 零点击"由此延续：
 *   「未知」不允许声明锚点）；
 * - **只圈区域的锚点不定位**（[nonLocatable]，2026-09-22 真机修）：它们给文字识别圈输入范围，
 *   不是点击目标；跟着一起匹配会让帧线程整段停摆（根因与实测见构造参数说明）；
 * - **"只要一条"的流程走 [locateFirstHit]**（2026-09-30 提速）：遮挡屏关闭闭环只用第一条命中的锚点
 *   ⇒ 它命中即停，不再把该状态的全部锚点匹配一遍 —— 真机实测"活动弹窗"名下 5 条锚点各 ≈83ms、
 *   每轮白跑 4 条。⚠ [locate] 本身**必须保持全量**（巡逻流程按**名字**取锚点，一屏多条是常态，
 *   当天改错过一次、真机把换号卡在第 6 步，见 [locate] 的注释）；
 * - **点击点 = 匹配区域中心**：判定给出的位置是模板左上角，本类加上模板中心偏移；
 *   一律以**运行帧坐标**给点击层（ADR-002「坐标来自识别判定结果」）；
 * - **不可信按未命中处理**（红线 7）：多处高分 / 分差不足时宁可"这次点不了"，也不点偏；
 * - **几何前提（T4-6，2026-09-23）**：运行帧与标定帧**几何恒相同**（转屏由 `VirtualDisplay.resize()`
 *   跟进）⇒ 标定窗口比例直接按运行帧尺寸换算即可。原先那条"经 `CanvasMapping` 把运行帧归一到标定几何"
 *   的路径（T1-11c）已整体拆除；几何一旦不符，由采集层**按「未标定」处理（不判、不点）**。
 *
 * 本类不产生点击、不感知无障碍服务：点击由转发层（T2-3c）统一执行（红线 6）。
 */
class AnchorLocator(
    anchorsByState: Map<UiState, List<SignalSpec>>,
    private val params: MatchParams,
    /**
     * **只圈区域、不当点击目标**的锚点名（2026-09-22 真机修）：这些锚点**不参与模板定位**。
     *
     * 为什么非要有这一条：产物里「列表区域」这类锚点是**给文字识别圈输入范围**用的
     * （见 `PatrolAnchors.FRIEND_LIST_AREA`），真机框出来是 **431×511** —— 它若跟着
     * 其它锚点一起定位，单轮就要扫这个模板：产物原始窗口 **941×1533**，运行时按
     * `WINDOW_SHRINK_FACTOR = 0.6` 收紧成 **565×921**（真机 dump 与 OCR 的「区域 565x921」一致；
     * 2026-09-24 系数改为 **0.45** ⇒ 同一窗口变成 **423×690**，下面这笔成本账也按同一比例下降）。
     * 按 `TemplateMatcher` 的**两级**匹配算成本（大头在粗搜）：
     * ```
     * 粗搜：网格 ≈(135/2+1)×(411/2+1) ≈ 1.43 万个位置 × 约 11 万个采样点（交错半采样）≈ 1.6e9 次乘加
     * 精搜：64 锚点 × 9 邻域 ≈ 576 个位置 × 22 万像素（全精度）≈ 1.3e8 次乘加
     * ```
     * 同状态下其余 14 条锚点合计 ≤5e7 ⇒ **这一条占单轮匹配成本 97% 以上**
     * （与第二大的锚点也差约 300 倍），"这一轮慢在哪"没有第二个候选。
     * **真机实录（2026-09-22 04:35，画面=好友列表、跑号第 9 步）**，相邻两条统计行：
     * ```
     * 04:35:19.062  窗口 29087ms 接收 164 帧 / 处理 12 帧；识别 12 轮   （≈2.4 秒/轮）
     * 04:35:42.341  窗口 23294ms 接收 1 帧 / 处理 1 帧；识别 1 轮        （单轮 23.3 秒）
     * ```
     * 该窗口内**只取到 1 帧**、只跑 1 轮 ⇒ 23.3 秒就是这一轮。同轮唯一另一处可能拖时间的是
     * 文字识别，而它是**同步**调用且 `Tasks.await(…, 1_000ms)` **到点即返回**（`MlKitNameReader`）
     * ⇒ 最坏约 1 秒，量级对不上。折算 1.6e9 ÷ 23.3s ≈ **7e7 乘加/秒**（朴素循环逐点边界检查 +
     * 采样点跨行访问，低于理想 ALU 上限，符合特征 —— 也说明这条路径后续还有优化空间）。
     * ⇒ 帧线程整段停摆 ⇒ 用户"停止后 3 秒重试"读到**旧结论**被误拦。
     *
     * 待命期不发作的原因也在这里：`current == null` 时早退，根本不进 [locate]。
     *
     * **只影响定位**：这些锚点的窗口**仍然**要纳入灰度转换（[windowRegions]），
     * 否则文字识别读到的是一片全零像素。
     */
    private val nonLocatable: Set<String> = emptySet(),
) {

    /** 该状态声明的**全部**锚点（含 [nonLocatable]）：灰度窗口仍要它们，见 [windowRegions]。 */
    private val allSpecsByState: Map<UiState, List<SignalSpec>> =
        anchorsByState.filterValues { it.isNotEmpty() }

    /** **可定位**（= 可点）的锚点规格：定位只用这一份，[nonLocatable] 已被剔掉。 */
    private val specsByState: Map<UiState, List<SignalSpec>> =
        allSpecsByState
            .mapValues { (_, specs) -> specs.filterNot { it.name in nonLocatable } }
            .filterValues { it.isNotEmpty() }

    init {
        require(UiState.UNKNOWN !in allSpecsByState) {
            "「未知」不能声明锚点：状态未知时零点击（红线 1）"
        }
        allSpecsByState.forEach { (state, specs) ->
            val names = specs.map { it.name }
            require(names.distinct().size == names.size) { "状态「${state.name}」的锚点名重复：$names" }
            specs.forEach { spec ->
                val sizes = spec.templates.map { it.width to it.height }.distinct()
                require(sizes.size == 1) {
                    "锚点「${spec.name}」的多个样式模板尺寸不一致（$sizes）：点击点 = 匹配区域中心，" +
                        "尺寸不一致时中心无从确定——请拆成多条记录（一条一个样式）"
                }
            }
        }
    }

    /**
     * 是否标了任何**可定位**的锚点（运行日志据此区分"没标锚点"与"锚点没命中"）。
     *
     * 只圈区域的那类（[nonLocatable]）不算——它们本来就没有"能不能点"的问题。
     */
    val isEmpty: Boolean = specsByState.isEmpty()

    /** 当前状态可用的**可点**锚点名；该状态未声明 → 空列表（不接受兜底到别的状态）。 */
    fun available(state: UiState): List<String> = specsByState[state].orEmpty().map { it.name }

    /**
     * 定位当前状态的锚点（一帧一次）：命中返回点击点，未命中 / 不可信不返回。
     *
     * **返回该状态全部命中的锚点**（声明顺序）。调用方自己按**名字**取要用的那条 ——
     * "点哪个"属于流程决策（B1），本类不替它挑。
     *
     * ## ⚠ 千万不要把它改成"命中即停"（2026-09-30 真机事故，当天就踩了）
     *
     * 巡逻流程是**按名字**取的：`PatrolRunner` 里 `input.anchors.firstOrNull { it.name == action.anchor }`，
     * 而**一屏声明多条锚点是常态** —— 真机实录：启动页同时有 `launch_switch`（第 4 步用）与
     * `launch_login`（第 6 步用）。当天为了省成本把它改成"命中即停"，第 6 步就再也找不到 `launch_login`：
     * ```
     * 19:58:21~34  跑号: 第 6 步：锚点「launch_login」没定位到（未标定或没命中），继续等（已等 16~29 秒）
     * 19:58:35.070 跑号: 已中止：第 6 步等了 30 秒
     * ```
     * 用户看到的是"**换号卡在选完服务器之后**"（第 5 步刚点完区服，第 6 步该点「登录游戏」却再也点不动）。
     *
     * ⇒ **只要一条**的流程请用 [locateFirstHit]（它单独实现，语义写在那边）。
     */
    fun locate(gray: GrayImage, state: UiState): List<AnchorHit> {
        val specs = specsByState[state].orEmpty()
        if (specs.isEmpty()) return emptyList()
        val detector = SignalDetector(specs, params)
        return specs.mapNotNull { spec -> hitOf(spec, detector.detectSignal(gray, spec)) }
    }

    /**
     * 只定位**第一条命中**的锚点（0 或 1 个）：**专给"只要一条"的流程**（遮挡屏关闭闭环）。
     *
     * ## 为什么它等价于 [locate] 再取首个（2026-09-30 提速）
     *
     * `PopupCloseController` 用的就是 `anchors.firstOrNull()`（产物声明顺序里第一条**命中**的锚点）。
     * 真机实测（`mm-log-20260930.txt`，活动弹窗名下 5 条 X 锚点、每条 ≈83ms）⇒ 那一轮白跑 4 条
     * （≈330ms CPU/轮），而弹窗在屏那段**每轮都要定位** ⇒ 少扫 4 条就是这一刀的全部收益。
     *
     * ## ⚠ 等价性的关键："第一条命中的" ≠ "第一条记录"
     *
     * [locate] 给的是**过滤掉未命中之后**的列表，取第一个就是"声明顺序里第一条命中的"——
     * 所以**第一条没命中时必须继续往下扫**（那条路旧行为会用第二条）。写成"只匹配第一条记录"
     * 是**行为变更**（会漏掉"第一条认不出、第二条认得"的场合）；单测
     * `stopsAtTheFirstHitYetStillScansPastAMiss` 把这两条语义都钉住。
     *
     * ## C′（2026-10-02）：[preferredNames] = "这一屏本轮确认在场的标志名" ⇒ 优先扫它们
     *
     * 一屏可以标**多套样式**（实测活动弹窗有 `activity_popup_e1` / `activity_popup_e2` 两条记录），
     * 而"**上一枪关掉了一个弹窗、紧接着冒出另一个样式的弹窗**"时，必须用**新那套自己的**关闭控件 ——
     * 老那套的锚点在新弹窗上根本不在原处（真机实测：关闭控件那一小块变了 66.6% / 99.3%）。
     *
     * 配对靠的是产物里的**现成不变量**：同一元素的标志与锚点**同名**（`…_e2|marker` 与 `…_e2|anchor`）
     * ⇒ 把"本轮命中的标志名"传进来即可，**不用猜命名规律**。
     *
     * ⚠ 语义边界（空集合时与旧行为**逐字段一致**）：两组内部都保持**声明顺序**，
     * 命中即停那条性质不变（优先组里第一条命中就返回）；优先组一条都不命中时自然落回其余锚点。
     * [locate]（巡逻用的那条）**不受影响**：它仍旧全量、按名字取（见其注释里的真机事故）。
     */
    fun locateFirstHit(
        gray: GrayImage,
        state: UiState,
        preferredNames: Set<String> = emptySet(),
    ): List<AnchorHit> {
        val specs = specsByState[state].orEmpty()
        if (specs.isEmpty()) return emptyList()
        val detector = SignalDetector(specs, params)
        val ordered = if (preferredNames.isEmpty()) {
            specs
        } else {
            specs.filter { it.name in preferredNames } + specs.filter { it.name !in preferredNames }
        }
        for (spec in ordered) {
            val hit = hitOf(spec, detector.detectSignal(gray, spec))
            // 命中即停：这就是"（优先组按声明顺序排最前时）第一条命中的"，空集合时与 [locate] + firstOrNull() 逐字段一致
            if (hit != null) return listOf(hit)
        }
        return emptyList()
    }

    /**
     * 在**指定矩形内**定位某个锚点（M4-T4-3 第 9 步，2026-09-21）：
     * 好友列表先用文字识别找到目标名字所在的那一行，再在**这一行**里找行尾的「拜访」图标。
     *
     * 窗口 = 产物里该锚点的窗口 **∩** [band]（[band] 是**运行帧坐标**）：
     * **横向＝该锚点自己的窗口**（图标在同一列，框一次复用所有行），**纵向＝[band]**（哪一行）。
     *
     * @return 命中且可信 → 点击点；未命中 / 不可信 / 放不下模板 / 该状态没这个锚点 → null。
     */
    fun locateWithin(gray: GrayImage, state: UiState, anchorName: String, band: PixelBounds): AnchorHit? {
        val spec = specsByState[state].orEmpty().firstOrNull { it.name == anchorName } ?: return null
        val own = spec.window.pixelBounds(gray.width, gray.height)
        // 横向=自己的窗口（图标列），纵向=行带（哪一行）
        val x0 = own.x0
        val y0 = band.y0
        val x1 = own.x1
        val y1 = band.y1

        // 交集必须还能放得下模板，否则匹配无从谈起 → 不定位
        val template = spec.templates.first()
        if (x1 - x0 < template.width || y1 - y0 < template.height) return null

        val window = SearchWindow(
            left = x0.toDouble() / gray.width,
            top = y0.toDouble() / gray.height,
            right = x1.toDouble() / gray.width,
            bottom = y1.toDouble() / gray.height,
        )
        val narrowed = SignalSpec(spec.name, window, spec.templates)
        val record = SignalDetector(listOf(narrowed), params).detectSignal(gray, narrowed)
        return hitOf(narrowed, record)
    }

    /**
     * 当前状态锚点所需的最小像素区域（**运行帧坐标系**）：采集层据此把锚点窗口一并纳入灰度转换
     * （T1-10b 同源口径）——漏掉它会让锚点在"只转换了标志窗口"的帧上读到全零像素，静默失败。
     *
     * **含 [nonLocatable] 的那些**（与 [locate] 刻意不同，2026-09-22）：只圈区域的锚点虽然不做定位，
     * 但它的窗口正是文字识别的输入范围——不转换，OCR 读到的就是全零像素。
     */
    fun windowRegions(frameWidth: Int, frameHeight: Int, state: UiState): List<PixelBounds> =
        allSpecsByState[state].orEmpty().map { it.window.pixelBounds(frameWidth, frameHeight) }

    /**
     * 把**某条锚点的窗口**换算成运行帧坐标（M4-T4-3 第 9 步，2026-09-22 真机修）。
     *
     * [namePlanOf] 需要用「列表区域」和「好友名称列」的窗口给 OCR 圈输入范围。T4-6 之后
     * 运行帧与标定帧同几何 ⇒ 窗口比例直接按运行帧尺寸换算；**仍然走本方法**（而不是让调用方各自
     * `pixelBounds`），是为了让"OCR 圈的区域"与"锚点定位搜的区域"继续共用同一个入口。
     *
     * @return 运行帧坐标的像素矩形；锚点不存在或窗口无效 → null。
     */
    fun windowInFrame(frameWidth: Int, frameHeight: Int, state: UiState, anchorName: String): PixelBounds? {
        val spec = allSpecsByState[state].orEmpty().firstOrNull { it.name == anchorName } ?: return null
        return spec.window.pixelBounds(frameWidth, frameHeight)
    }

    private fun hitOf(spec: SignalSpec, record: DetectionRecord): AnchorHit? {
        if (!record.matched) return null
        val best = record.best ?: return null
        // 模板尺寸在构造时已校验一致，取首个即可
        val template = spec.templates.first()
        val clickX = best.x + template.width / 2.0
        val clickY = best.y + template.height / 2.0
        return AnchorHit(
            name = spec.name,
            score = best.score,
            // T4-6 之后运行帧与标定帧同几何 ⇒ 两个坐标恒相同（字段保留：日志与离线复核仍在读）
            frameX = clickX,
            frameY = clickY,
            calibrationX = clickX,
            calibrationY = clickY,
        )
    }
}

/**
 * 一个可点击目标（T2-3b）：锚点名 + 置信度 + 点击点。
 *
 * [frameX] / [frameY] 是**运行帧像素坐标**（点击层直接使用，取整由点击层负责）；
 * [calibrationX] / [calibrationY] 是同一命中的标定坐标，供运行日志与离线复核解释"点从哪来"。
 * T4-6 之后两者恒等（帧与标定同几何），字段保留以便将来几何再变时一眼看出来。
 */
data class AnchorHit(
    val name: String,
    val score: Double,
    val frameX: Double,
    val frameY: Double,
    val calibrationX: Double,
    val calibrationY: Double,
)
