package com.example.mastermechanic.calibration

import android.content.Context
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.TemplateMatcher
import java.io.File

/**
 * 标定产物存取（T1-5b）：产物文件位于应用私有目录（随应用卸载清除，不跨设备复用）。
 *
 * 写入采用「临时文件 + 重命名」：避免半写文件被后续加载误读（掉电 / 进程被杀场景）。
 */
object CalibrationStore {

    private const val DIR_NAME = "calibration"
    private const val FILE_NAME = "calibration.txt"

    fun artifactFile(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    fun exists(context: Context): Boolean = artifactFile(context).isFile

    /** 保存（覆盖写）；返回产物文件。 */
    fun save(context: Context, data: CalibrationData): File {
        val file = artifactFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(CalibrationCodec.encode(data), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // 重命名失败（个别文件系统限制）：退化为直接覆盖写
            file.writeText(CalibrationCodec.encode(data), Charsets.UTF_8)
            tmp.delete()
        }
        // 写完就通知采集侧就地重载（2026-09-21）：否则要"停采集 / 重开"才生效，
        // 真机上很容易以为"重框了但没变化"（实录：重框前后分数都是 0.578x）
        CalibrationReloadSignal.request()
        return file
    }

    /** 删除产物；返回是否删除成功（不存在时为 false）。 */
    fun delete(context: Context): Boolean = artifactFile(context).delete()

    /**
     * 加载产物（**原始值**，不做任何运行时变换）；文件不存在返回 null；
     * 解析失败抛 [IllegalArgumentException]（不静默降级）。
     *
     * ## 为什么这里**不**做运行时变换（2026-09-21 修的真 bug）
     *
     * 窗口重算（[CalibrationData.tightenedWindows]）是**运行时**的事，而本方法同时被**标定页**（读写产物）使用。
     * 早先它把收紧结果一起返回，于是标定页里的 `current` 是"已收紧"的产物 —— 用户每框一个新元素，
     * `upsert` 都会拿这份收紧值**写回盘**，其它记录的窗口就被**再收紧一次**（×0.6 / 次，
     * 直到撞上最小余量）。真机证据：写入时窗口 117px，几次写入之后产物里只剩 55px，
     * 窗口/模板 从 3.00 掉到 1.41 —— 越标越窄，谁也没动过它。
     *
     * 现在：读盘/写盘一律用原始值；只有**运行时**入口 [loadForRuntime] 才做重算。
     */
    fun load(context: Context): CalibrationData? {
        val file = artifactFile(context)
        if (!file.isFile) return null
        val text = file.readText(Charsets.UTF_8)
        val decoded = try {
            CalibrationCodec.decode(text)
        } catch (e: OutOfMemoryError) {
            // **内存不足要变成"读不出来"，不能让它把调用方打死**（2026-09-29 真机崩溃）：
            // 产物里如果有一条**很大的模板**（例如把整屏 / 整个列表区域框进去，产物 5.4MB），
            // 解码要同时吃下"base64 原文 + 字节数组 + 模板"几份拷贝 —— 真机实录：
            // `Base64.decode` 里申请 3.7MB 失败 ⇒ **帧线程直接崩掉、整个采集会话没了**。
            // 两个调用方（采集侧的两个入口 / 标定页）本来就都按 `IllegalArgumentException` 处理
            // （"这份读不出来就沿用上一份 / 如实提示"）⇒ 这里统一转成它，口径一致、不再有谁被打死。
            throw IllegalArgumentException(
                "标定产物太大 / 内存不足，读不出来（文件 ${text.length / 1024}KB）；" +
                    "请把过大的那一块（例如整屏 / 整个列表区域）重新框小些再写",
            )
        }
        return retireFriendFarm(context, decoded)
    }

    /**
     * 运行时加载（识别 / 定位用）：原始产物 + **收紧搜索窗口**。
     *
     * 采集侧只有这一个入口；界面侧（标定页等）用 [load]，两者读到的**窗口值不同**是刻意的。
     *
     * @param exemptFromShrink **不参与收紧**的锚点用途（2026-09-22 用户口径）：只圈区域、不作为点击目标
     *   的那几条（「列表区域」/「好友名称列」）**用户框多大就用多大**——它们不是"在窗口内找元素"，
     *   而是"把这一片送去读文字"，隐式收 20% 会让标定页上看到的框与运行时用的框不是同一个。
     *   调用方给 `PatrolAnchors.nonLocatableAnchors`。**必填、故意不给默认值**：给了默认值，
     *   漏传就会静默退回"隐式收缩"，而屏上没有任何地方看得出这件事。
     */
    fun loadForRuntime(context: Context, exemptFromShrink: Set<String>): CalibrationData? {
        val raw = load(context) ?: return null
        // **一次性瘦身**（2026-09-29）：把"只圈区域"锚点的**整块模板换成缩略图**。
        // 它们不参与匹配，整块存只是把产物撑大 —— 真机实录产物 5.4MB ⇒ 重载解码 OOM ⇒ 帧线程崩
        // （progress 第 258 条）。瘦身结果**落盘**，否则每次会话都要再解一遍那几 MB（OOM 的来源）。
        // 幂等（`shrinkRegionTemplates` 无事可做时返回同一个对象）⇒ 只会真正写一次。
        val slimmed = CalibrationSignals.shrinkRegionTemplates(raw, exemptFromShrink)
        if (slimmed !== raw) {
            save(context, slimmed)
            MmLog.i(
                TAG,
                "产物瘦身：只圈区域的锚点（${exemptFromShrink.joinToString("、")}）模板换成缩略图" +
                    "（长边 ≤ ${TemplateExtractor.PREVIEW_MAX_PX}px）并重写产物 —— 它们不参与匹配，" +
                    "整块存只会让产物过大（曾把帧线程读崩）",
            )
        }
        val runtime = slimmed.tightenedWindows(exemptFromShrink)
        logWindowShrink(slimmed, runtime)
        logStillHeavy(runtime, exemptFromShrink)
        return runtime
    }

    /**
     * 把"**运行时窗口被重算过**"的记录记一行（2026-09-29）。
     *
     * 为什么必须记（否则将来会花半小时查一个本可以一眼看到的事）：运行时窗口与产物里那份**可能不同** ——
     * [CalibrationData.tightenedWindows] 会把**可证居中**（`窗口 ≥ 3× 模板`）的轴重算成"模板 + 固定余量"
     * （标志 [SelectionWindow.MARKER_MARGIN_PX] / 锚点 [SelectionWindow.ANCHOR_MARGIN_PX] px/边）。
     * 窗口变小意味着"元素在更小的范围内被搜"，若某条信号原本就框得偏，重算后可能认不出
     * （真机踩过的形态：`hall_settings` 命中位置比窗口中心偏 ≈20px；`farm_exit` 需要 >54px）。
     * 这一行能立刻回答"**是不是窗口被收了、收了多少**"，并给出可复算的乘加量（收前 → 收后）。
     *
     * 只列**窗口真的变小**的记录（按收前计算量降序，最多 6 条）——产物加载日志本来就逐条列过
     * 模板与窗口，这里不重复。
     */
    private fun logWindowShrink(raw: CalibrationData, runtime: CalibrationData) {
        val after = runtime.signals.associateBy { it.id to it.role }
        val narrowed = raw.signals.mapNotNull { before ->
            val entry = after[before.id to before.role] ?: return@mapNotNull null
            val biggest = before.templates.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: return@mapNotNull null
            val beforePx = before.window.pixelBounds(raw.frameWidth, raw.frameHeight)
            val afterPx = entry.window.pixelBounds(runtime.frameWidth, runtime.frameHeight)
            if (afterPx.x1 - afterPx.x0 >= beforePx.x1 - beforePx.x0 &&
                afterPx.y1 - afterPx.y0 >= beforePx.y1 - beforePx.y0
            ) {
                return@mapNotNull null
            }
            val opsBefore = TemplateMatcher.estimateCoarseOps(
                before.window, biggest.width, biggest.height, raw.frameWidth, raw.frameHeight,
            )
            val opsAfter = TemplateMatcher.estimateCoarseOps(
                entry.window, biggest.width, biggest.height, runtime.frameWidth, runtime.frameHeight,
            )
            Triple(before.id, opsBefore, opsAfter)
        }.sortedByDescending { it.second }
        if (narrowed.isEmpty()) return
        // 只记**条数与最贵的那一条**（2026-09-29 用户口径"移除为修 bug 埋下的各种不必要日志"）：
        // 逐条列窗口尺寸那版是排查期用的，加载日志本来就逐条列过模板与窗口，重复列只是噪声。
        val worst = narrowed.first()
        MmLog.i(
            TAG,
            "运行时搜索窗口已按预算收窄：${narrowed.size} 条（最贵的是 ${worst.first}，" +
                "${worst.second / 1_000_000}M ⇒ ${worst.third / 1_000_000}M 乘加/轮）——" +
                "基准是固定像素余量（标志 ${SelectionWindow.MARKER_MARGIN_PX} / 锚点 " +
                "${SelectionWindow.ANCHOR_MARGIN_PX} px/边），超预算才收；产物文件不动",
        )
    }

    /**
     * **"重算之后仍然很贵"的排名**（纯逻辑，2026-10-03 从 [logStillHeavy] 里提出来，为了能单测）。
     *
     * ## 排名依据
     *
     * 对每条记录取**最大的那个模板**，用 [TemplateMatcher.estimateCoarseOps] 算**粗搜乘加次数**：
     * `位置数（步进 2px）× 每位置采样（模板面积/2）`，只算粗搜（精搜恒小两个数量级）。
     * 超过 [HEAVY_OPS] 的才算"贵"，按 ops 降序。
     *
     * ## ⚠ 为什么必须分三类（2026-10-03 真机发现：混排会点名错的东西）
     *
     * 原来的排名把三种**根本不是同一种成本**的记录混在一起排，于是"最贵前两名"恰好是
     * **零匹配成本**的"只圈区域"锚点（`server_list_area` 604M / `friend_list_area` 334M）——
     * 它们在 [com.example.mastermechanic.patrol.AnchorLocator] 构造时被排除、识别也只匹配标志
     * ⇒ **一帧都不参与匹配**，框小它们只会在**没有任何提速效果**的前提下把 OCR 输入区域框小。
     * 而真正该框的标志被挤出 `take(6)` ⇒ **用户照着日志干活，框的是错的东西**。
     *
     * | 类 | 判据 | 什么时候匹配 | 重框有没有用 |
     * | --- | --- | --- | --- |
     * | **每轮固定成本** | `role == MARKER` | **每轮**（当前状态期望集合内） | **有用**（成本 ∝ 模板面积） |
     * | **按需成本** | `role == ANCHOR` 且不在 [nonLocatable] | 只有轮到那一步、这一步有事可做时 | 有用，但那一步才跑 |
     * | **零成本** | `id ∈ [nonLocatable]`（"只圈区域"） | **从不** | **没用**（别框） |
     *
     * ⇒ 三类分开报：每轮固定成本那一组才是"该重框标志"的清单；按需那组只作提示；
     * 零成本那组**点名排除**并说明原因（免得用户以为漏报了）。
     */
    data class StillHeavy(
        /** 每轮都匹配的标志（降序）；该重框的就是这组。 */
        val perRound: List<String>,
        /** 按需匹配的动作锚点里最贵的（降序；空 = 没有）。 */
        val onDemand: List<String>,
        /** 被排除的"只圈区域"锚点名（说明为什么它们不在榜里）。 */
        val excluded: List<String>,
    )

    fun stillHeavyRanking(
        runtime: CalibrationData,
        nonLocatable: Set<String>,
        heavyOps: Long = HEAVY_OPS,
        take: Int = 6,
    ): StillHeavy {
        val perRound = ArrayList<Pair<String, Long>>()
        val onDemand = ArrayList<Pair<String, Long>>()
        val excluded = ArrayList<String>()
        for (entry in runtime.signals) {
            val biggest = entry.templates.maxByOrNull { it.width.toLong() * it.height.toLong() }
            if (entry.id in nonLocatable) {
                if (entry.id !in excluded) excluded.add(entry.id)
                continue
            }
            if (biggest == null) continue
            val ops = TemplateMatcher.estimateCoarseOps(
                entry.window, biggest.width, biggest.height, runtime.frameWidth, runtime.frameHeight,
            )
            if (ops < heavyOps) continue
            val text = "${entry.id}（${entry.role.label}，模板 ${biggest.width}×${biggest.height}，${ops / 1_000_000}M）"
            if (entry.role == SignalRole.MARKER) perRound.add(text to ops) else onDemand.add(text to ops)
        }
        return StillHeavy(
            perRound = perRound.sortedByDescending { it.second }.take(take).map { it.first },
            onDemand = onDemand.sortedByDescending { it.second }.take(2).map { it.first },
            excluded = excluded,
        )
    }

    /**
     * 把 [stillHeavyRanking] 的结果打给用户看（真机上"还是慢"时唯一的 actionable 线索）。
     *
     * 为什么要有这一行：重算只能砍"多余的余量"，**砍不动"模板本身太大"** ——
     * 匹配成本 ≈ 位置数 × 模板采样数，固定余量下位置数恒定（≈49×49），
     * 于是成本**正比于模板面积**。真机实录（第 288 条）：`tutorial_guide_e1`（模板 181×51）重算后
     * 仍有 ≈11M、`tutorial_hall_e1`（248×80）≈24M ⇒ 它们只能靠**重框得更小**解决。
     * 不主动报的话，用户只会觉得"还是慢"，而屏上没有任何地方看得出"该重框哪一条"。
     *
     * ⚠ 措辞里的毫秒数是**线性外推**（[MS_PER_MILLION_OPS] ms/百万乘加），只适合当量级看：
     * `TemplateMatcher` 的实测是"大信号 6~10ms/百万、小信号有固定开销"⇒ 小信号会明显高于外推值。
     */
    private fun logStillHeavy(runtime: CalibrationData, nonLocatable: Set<String>) {
        val ranking = stillHeavyRanking(runtime, nonLocatable)
        if (ranking.perRound.isEmpty() && ranking.onDemand.isEmpty() && ranking.excluded.isEmpty()) return
        val sb = StringBuilder()
        if (ranking.perRound.isNotEmpty()) {
            sb.append("每轮都匹配的标志里有 ${ranking.perRound.size} 条单轮 ≥ ${HEAVY_OPS / 1_000_000}M 乘加")
                .append("（≈${HEAVY_OPS / 1_000_000 * MS_PER_MILLION_OPS}ms+，线性外推）⇒ 这是**每轮的固定成本**")
                .append("，只能靠**把标志框小**提速（成本 ∝ 模板面积，框小一半约快一半）：")
                .append(ranking.perRound.joinToString("、"))
        }
        if (ranking.onDemand.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("｜")
            sb.append("按需匹配的动作锚点里最贵的是 ").append(ranking.onDemand.joinToString("、"))
                .append("（只在轮到那一步时才跑，不是每轮固定成本）")
        }
        if (ranking.excluded.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("｜")
            sb.append("已排除「只圈区域、不做模板匹配」的锚点 ").append(ranking.excluded.joinToString("、"))
                .append("：它们从不参与匹配，框小**不会**提速（框小了只会让读文字的范围变小）")
        }
        MmLog.w(TAG, sb.toString())
    }

    /**
     * 一次性迁移（2026-09-21）：让产物里的「好友的农场」退休 ——
     * **冗余锚点删除、标志改归农场**，然后**落盘**，之后产物文件里再没有这个状态。
     *
     * 为什么在这里做而不是只在内存里做：只在内存里做的话，产物文件里始终留着旧状态，
     * 于是"枚举不能删""清单要兼容"这些包袱就永远摘不掉 —— 迁移一次，包袱才算真正卸下。
     *
     * 幂等（[CalibrationData.retireFriendFarm] 没有该状态时原样返回）⇒ 只写一次；
     * 写回用 [save]（临时文件 + 重命名），半写文件不会被后续加载误读。
     */
    private fun retireFriendFarm(context: Context, raw: CalibrationData): CalibrationData {
        val migrated = raw.retireFriendFarm()
        if (migrated === raw) return raw
        save(context, migrated)
        MmLog.i(
            TAG,
            "产物迁移：好友的农场并入农场（记录 ${raw.signals.size} → ${migrated.signals.size} 条，" +
                "其名下锚点与标志全部清除）；此后产物里不再有该状态",
        )
        return migrated
    }

    private const val TAG = "MM-Calibration"

    /**
     * "**重算之后仍然很贵**"的门槛（乘加/轮，2026-09-29）：超过它就建议用户重框（见 [logStillHeavy]）。
     *
     * 取 20M ≈ 150ms（实测 ≈7ms/百万次乘加）：固定余量下位置数恒定（≈49×49），
     * 于是 20M 大致对应"模板面积 ≥ 约 8 万像素"（`400×200` 那种大横幅）。
     */
    private const val HEAVY_OPS = 20_000_000L

    /**
     * **毫秒外推系数**（ms / 百万次乘加）：日志里那句"≈Nms+"用。
     *
     * ⚠ **只适合当量级看**（2026-10-03 注明）：[TemplateMatcher] 的实测是"**大信号** 6~10ms/百万、
     * **小信号**另有固定开销"⇒ 20M 这一档的真实耗时通常**高于**线性外推值。
     * 当初取 7 是大信号实测的中位；宁可低估，也不要让用户以为"20M 也就 140ms、等得起"。
     */
    private const val MS_PER_MILLION_OPS = 7L
}
