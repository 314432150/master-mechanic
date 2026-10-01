package com.example.mastermechanic.servers

import com.example.mastermechanic.recognition.NameCandidate

/**
 * **添加这一屏的服务器**：把"当前选服页读到的文字"解析成服务器清单条目，再与既有清单合并（2026-09-24）。
 *
 * ## 屏幕结构（真机截图 + 真机 OCR 原文对照，2026-09-24 / 2026-09-25）
 *
 * ```
 * 上次登录 …                                ◆ 微信56区 白色死神   ← 表头"当前区服"药丸：**也是名字行**
 * ┌────────────────────────┬────────────────────────┐
 * │ ◆微信392区 发条权杖        │ ◆微信424区 海鹰之羽        │  ← 视觉行 1
 * │  ( Lv.9  🏅 卧龙山胡萝卜 ) │  ( Lv.6  🏅 卧龙山草莓 )   │
 * ├────────────────────────┼────────────────────────┤
 * │ ◆微信395区 莲动渔舟        │ ◆微信415区 碧海之眼        │  ← 视觉行 2
 * │  ( Lv.25 🏅 卧龙山小麦 )   │  ( Lv.6  🏅 卧龙山大蒜 )   │
 * └────────────────────────┴────────────────────────┘
 * ```
 *
 * 一个视觉行 = 一台：**名字行**（平台 + 区号 + 「区」 + 区服名）+ **详情行**（等级 + 角色名，中间那个
 * 图标 OCR 多半读成噪声或丢掉）。**两列网格**（一屏 12 台，两列一起滚）⇒ 一次只能抓
 * **当前这一屏看得见**的（想全，滚一段再点一次），报告里会写明，不假装抓到了全部。
 * 清单顺序 = 游戏里"看"的顺序：视觉行自上而下、行内自左而右（见 [orderByGrid]，这是真机报障
 * "顺序和游戏不一致"的修法）。
 *
 * ## 三条口径
 *
 * 1. **只要区服名，其余字段"能读就读"**：区服名是唯一参与定位的字段（红线 3），必须来自
 *    名字行里「区」之后那一段；平台 / 区号 / 等级 / 角色名读不到就留空 ——
 *    `ServerEntry` 本来就把它们定义为可空字段（只帮用户认，不参与定位）。
 *    等级**只收数字**（`Lv.6` → `6`）：`Lv.` 是清单页的显示标签（用户口径 2026-09-25）。
 * 2. **详情按"行带"配对**（不按行序、也不只认最挨着的那一条）：详情常被 OCR 切成几条
 *    （`( Lv.6` + `卧龙山土豆)`）⇒ 取"名字行下方、同列、到下一个名字行之前"的**全部**候选
 *    （[detailBand]），等级取第一个 `Lv.x`、角色名取多数读数里那一段。
 * 3. **合并只做加法**：新名字 → 追加；已存在 → **只在它是空值时才补**（用户填过的字段一个字都不改），
 *    并且**同名不去重成一条**（`ServerList` 的唯一性是构造时不变量，重复名字根本加不进去）。
 *
 * 纯逻辑（JVM 可测）：输入是识别层给的候选（含位置），输出是清单条目与合并结论，
 * 不碰安卓、不碰盘 —— 落盘与提示在采集层（`FriendListOcrTryout.captureServers`）。
 */
object ServerListCapture {

    /** 名字行：`微信395区 莲动渔舟` / `微信415区碧海之眼`（OCR 可能不加空格）/ `QQ56区白色死神`。 */
    private val NAME_LINE = Regex("""(微信|QQ|qq)\s*(\d+)\s*区\s*(.+)$""")

    /** 详情行里的等级：`Lv.25` / `Lv25` / `lv.6` 都认（游戏里也可能写「王者」这类，那就读不到，留空）。 */
    private val LEVEL = Regex("""Lv\.?\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** 一行里最长的连续中文片段（≥2 字）：角色名；顺带甩掉 OCR 混进来的图标噪声（`T`/`H`/`0`）。 */
    private val CJK_RUN = Regex("""[\u4e00-\u9fa5]{2,}""")

    /**
     * 详情带最多往下取几行高（[nextNameY] 的兜底）。
     *
     * 真机上一台服务器占**两行**（名字行 + 详情行），给 3 行高是留余量（行高按识别框算，略有出入）；
     * 再远就不敢当这一行的详情了 —— 宁可留空，也不把下一台的角色名扣到这一台上。
     */
    private const val BAND_MAX_ROWS = 3

    /**
     * "同一个视觉行"的 y 容差 = 行高 × 它（见 [orderByGrid]）。
     *
     * 两列网格里同一行的左右两个文字框 y 会差几个像素（字高 / 基线不同）；而相邻两行的间距
     * 是"一整行"（真机 ≈200px）。取 1.5 倍行高（≈60px）两头都有余量。
     */
    private const val BAND_Y_TOLERANCE_RATIO = 1.5

    /**
     * 这一行是不是"服务器名字行"（`微信395区 莲动渔舟`）——供调用方决定**要不要逐行复核**。
     *
     * 公开出来的理由：判据只写一处。采集层拿着它去挑"复核哪些行"（`FriendListOcrTryout.captureServers`），
     * 与解析用的是同一个正则，不会出现"复核了不该复核的 / 漏了该复核的"。
     */
    fun looksLikeServerNameLine(text: String): Boolean = NAME_LINE.containsMatchIn(text)

    /**
     * 解析读到的一屏文字 → 服务器条目（按"名字行"在屏幕上的先后，见 [bestPerRow]）。
     *
     * 两处"同一台服务器被读到多次"的归并都在这里做（都不猜，只是**取更完整的那次读数**）：
     * ① **同一行被读了多遍**（整屏分块重叠 / 逐行复核）⇒ [bestPerRow] 取**名字最长**的那个读法；
     * ② 同一名字被读成多条 ⇒ 取字段更全的那条。
     *
     * ## 详情（等级 / 角色名）按**行带**取，不再"只认紧挨着的那一条"
     *
     * 真机实录（2026-09-25 用户报障「战利品没读到角色名」）：`微信426区 战利品` 的详情被 OCR
     * **切成两条** —— `( Lv.6` 与 `卧龙山土豆)`。按"一条详情配一条名字"的老做法，角色名整条丢掉
     * （等级和角色名**不在同一条候选**里，而配对只看了带 `Lv` 的那条）。
     * ⇒ 现在取的是**这一行下方、同列、直到下一个名字行之前**的全部候选（[detailBand]）：
     * 等级取其中**第一个**（y 最小的）`Lv.x` 的**数字**，角色名取其中出现最多的中文片段。
     *
     * ## 等级只存**数字**（2026-09-25 用户口径）
     *
     * 采集时把 `Lv.6` 存成 `6`，`Lv.` 由清单页当**显示标签**补（[com.example.mastermechanic.ui.ServerListLabels]）
     * ⇒ 数据里只有数字，展示才带前缀，编辑框里填的也是数字（用户口径：读的是"数字"，"Lv." 是辅助 label）。
     */
    fun parse(candidates: List<NameCandidate>): List<ServerEntry> {
        val rows = bestPerRow(candidates.mapNotNull { parseNameLine(it) })
        if (rows.isEmpty()) return emptyList()
        val bands = rows.associateWith { name -> detailBand(name, rows, candidates) }
        // **同名多行只留一条**：真机版式里表头右侧那颗"当前区服"药丸（`◆ 微信56区 白色死神`）也是名字行，
        // 它比列表里的同一行**更靠上** ⇒ 不留它（否则 56 会排到第一位 —— 这就是真机报障的现场）。
        // 判据：先比字段多（列表里那一行有等级 / 角色名），再比**更靠下**的那个。
        val kept = rows.groupBy { it.serverName }.values.map { group ->
            group.maxWithOrNull(
                compareBy({ fieldCount(entryOf(it, bands.getValue(it))) }, { it.candidate.y }),
            )!!
        }
        return orderByGrid(kept).map { name -> entryOf(name, bands.getValue(name)) }
    }

    /** 一条名字行 + 它的详情带 → 清单条目（等级只收数字，见类注释）。 */
    private fun entryOf(name: NameRow, band: List<NameCandidate>): ServerEntry = ServerEntry.of(
        platform = name.platform,
        serverNo = name.serverNo,
        serverName = name.serverName,
        characterName = roleOf(band.map { it.text }),
        level = band.asSequence()
            .sortedBy { it.y }
            .mapNotNull { LEVEL.find(it.text)?.groupValues?.get(1) }
            .firstOrNull()
            .orEmpty(),
    )

    /**
     * **按游戏里的"看"的顺序排**：先把 y 相近的行聚成"视觉行"，行内再按 x 从左到右。
     *
     * 为什么不能直接按 `y` 排（真机实测，2026-09-25 用户截图 + 日志对照）：
     * 版式是**两列网格**（一次看得见 12 台），左右两列里**同一个视觉行**的文字框 y 并不相等 ——
     * 右列的框常常比左列高几个像素 ⇒ 直接按 y 排会变成"右列插到左列前面"，
     * 清单里的顺序就成了 `395, 415, 425, 427, 426, 418, 408…`（真机原样，用户报障"和游戏不一致"）。
     *
     * 容差取 **1.5 倍行高**：同一视觉行里的偏差是"几个像素"，而相邻两行之间隔着一整行（≈200px）
     * ⇒ 这个阈值两头都稳。行内按 x 排 ⇒ 左→右；视觉行之间按 y 排 ⇒ 上→下。
     */
    private fun orderByGrid(rows: List<NameRow>): List<NameRow> {
        val bands = ArrayList<MutableList<NameRow>>()
        var bandTop = Int.MIN_VALUE
        rows.sortedBy { it.candidate.y }.forEach { row ->
            val tolerance = maxOf(row.candidate.height, 1) * BAND_Y_TOLERANCE_RATIO
            if (bands.isEmpty() || row.candidate.y - bandTop > tolerance) {
                bands += mutableListOf(row)
                bandTop = row.candidate.y
            } else {
                bands.last() += row
            }
        }
        return bands.flatMap { band -> band.sortedBy { it.candidate.x } }
    }

    /**
     * **同一行的多种读法只留一条：取名字最长的那个**（2026-09-25 真机加）。
     *
     * 真机实录（`mm-log-20260925`）：同一屏分块 + 逐行复核会各读一次，而 OCR 的错法**以"少读"为主**
     * —— `微信395区 莲动渔舟` 被读成 `微信395区 莲动渔`（丢末字）。两次读数里只要有一次读全，
     * 取更长的那个就把它救回来了；反之若两次都缺字，**仍然按读到的那几个字收**（不猜、不补字，红线 3）。
     *
     * **判"同一行"只看位置**（y 相近 + 横向范围有交集）：左右两列在同一 y 上互不重叠 ⇒ 不会串行；
     * 长度相同而内容不同的（`莲动渔舟` vs `莲动渔周`）**不合并**，交给 [merge] 的区号护栏去拦。
     */
    private fun bestPerRow(rows: List<NameRow>): List<NameRow> {
        val chosen = ArrayList<NameRow>(rows.size)
        rows.sortedWith(compareBy({ it.candidate.y }, { it.candidate.x })).forEach { row ->
            val same = chosen.indexOfFirst { sameLine(it.candidate, row.candidate) }
            if (same < 0) {
                chosen += row
            } else if (row.serverName.length > chosen[same].serverName.length) {
                chosen[same] = row
            }
        }
        return chosen
    }

    /** 两条候选是不是"同一行"：y 偏差不超过一行高、且横向范围有交集（左右列因此不会互串）。 */
    private fun sameLine(a: NameCandidate, b: NameCandidate): Boolean {
        val tolerance = maxOf(a.height, b.height)
        if (kotlin.math.abs(a.y - b.y) > tolerance) return false
        val overlap = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
        return overlap > 0
    }

    /**
     * 与既有清单合并（**只做加法 + 一道"区号护栏"**）。
     *
     * - 新名字 → 追加；
     * - 已存在的条目 → **只在空字段上补全**（用户填过的一个字都不改）；
     * - **同平台同区号、但名字不同 ⇒ 一律不新增**，只进 [Merge.conflicts] 由报告摊给用户看
     *   （见 [Conflict] 的说明：这是"读缺字 / 读错字"最典型的现场）。
     */
    fun merge(existing: ServerList, captured: List<ServerEntry>): Merge {
        var current = existing
        val added = mutableListOf<ServerEntry>()
        val filled = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val conflicts = mutableListOf<Conflict>()
        captured.forEach { entry ->
            val index = current.indexOfServerName(entry.serverName)
            if (index >= 0) {
                val old = current.entries[index]
                val merged = old.copy(
                    platform = old.platform ?: entry.platform,
                    serverNo = old.serverNo.ifEmpty { entry.serverNo },
                    characterName = old.characterName.ifEmpty { entry.characterName },
                    level = old.level.ifEmpty { entry.level },
                )
                if (merged == old) {
                    skipped += entry.serverName
                } else {
                    current = current.update(index, merged)
                    filled += entry.serverName
                }
                return@forEach
            }
            // ★ 区号护栏（2026-09-25 真机事故后加）：同一个平台 + 同一个区号**只可能有一台小号**
            //（用户口径"一个区服只有一个小号"，`ServerList` 的唯一性就是按这个定的）。
            // 所以"名字不同但区号相同"⇒ 不是新服务器，而是**同一台的另一种读法**（多为 OCR 少读/错读一个字）。
            // 这种情况**绝不新增**（真机后果：清单里同时躺了 `395|莲动渔舟` 与 `395|莲动渔`，
            // 顺序轮换选中缺字那条 ⇒ 第 5 步在选服页永远找不到它、流程中止）。
            val clash = if (entry.hasServerNo) {
                current.entries.firstOrNull {
                    it.hasServerNo && it.serverNo == entry.serverNo &&
                        it.platform == entry.platform && it.serverName != entry.serverName
                }
            } else {
                null
            }
            if (clash != null) {
                conflicts += Conflict(entry, clash)
            } else {
                current = current.add(entry)
                added += entry
            }
        }
        return Merge(list = current, added = added, filled = filled, skipped = skipped, conflicts = conflicts)
    }

    /** 解析出的一条"名字行"（内部用：把位置与字段绑在一起，便于配对详情行）。 */
    private class NameRow(
        val candidate: NameCandidate,
        val platform: ServerPlatform?,
        val serverNo: String,
        val serverName: String,
    )

    /** 解析一条名字行；不是名字行（不含「区」的结构）返回 null。 */
    private fun parseNameLine(candidate: NameCandidate): NameRow? {
        val match = NAME_LINE.find(candidate.text) ?: return null
        val platform = when (match.groupValues[1]) {
            "微信" -> ServerPlatform.WECHAT
            else -> ServerPlatform.QQ
        }
        val serverNo = match.groupValues[2]
        // 名字 = 「区」之后那一段；万一同一行还粘着括号 / 详情，截到那里为止
        val raw = match.groupValues[3]
        val name = raw.substringBefore('(').substringBefore('（').trim()
        if (name.isEmpty() || !ServerList.isValidServerName(name)) return null
        return NameRow(candidate, platform, serverNo, name)
    }

    /**
     * **一行的"详情带"**：名字行**下方**、**同列**（横向与它相交）、且不超过下一个名字行的那些候选。
     *
     * 这样切的好处：
     * - 详情被 OCR 切成两三条（`( Lv.6` / `卧龙山土豆)`）也照样全收 —— 战利品那次就是这种；
     * - 不会串到**左右相邻那一列**的服务器上（同 y 不同列，横向不相交）；
     * - 不会把上面那几行表头（`我的服务器` / `上次登录 …`）当详情：它们在名字行**之上**。
     */
    private fun detailBand(
        name: NameRow,
        nameLines: List<NameRow>,
        all: List<NameCandidate>,
    ): List<NameCandidate> {
        val row = name.candidate
        val lower = row.y + row.height / 2
        val upper = nextNameY(name, nameLines, row)
        return all.filter { candidate ->
            candidate.y > lower &&
                candidate.y <= upper &&
                !NAME_LINE.containsMatchIn(candidate.text) &&
                horizontalOverlap(candidate, row)
        }
    }

    /** 同列的**下一个名字行**的 y；没有下一个就用"三行高"兜住（再远就不敢当这一行的详情了）。 */
    private fun nextNameY(name: NameRow, nameLines: List<NameRow>, row: NameCandidate): Int {
        val cap = row.y + maxOf(row.height, 1) * BAND_MAX_ROWS
        val next = nameLines
            .filter { other ->
                other !== name && other.candidate.y > row.y && horizontalOverlap(other.candidate, row)
            }
            .minOfOrNull { it.candidate.y }
        return minOf(next ?: cap, cap)
    }

    /** 两条候选的横向范围有没有交集（用来判"是不是同一列"）。 */
    private fun horizontalOverlap(a: NameCandidate, b: NameCandidate): Boolean =
        minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x) > 0

    /**
     * 从详情带的**多条**候选里挑角色名：**出现次数最多**的中文片段，同票取更长的那个。
     *
     * 为什么按"出现次数"：同一个角色名常被读到两三次（分块重叠 + 逐行复核），哪次更干净无法判定，
     * 但**多数读数一致**的那一段最可信；同票再取长的（OCR 的错法以"少读"为主）。
     * 读不到就留空 —— 不猜、不补字（红线 3）。
     */
    private fun roleOf(texts: List<String>): String {
        val runs = texts
            .flatMap { CJK_RUN.findAll(it).map { match -> match.value } }
            .filterNot { it == "微信" || it == "区" }
        if (runs.isEmpty()) return ""
        return runs.groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy({ it.value }, { it.key.length }))
            ?.key
            .orEmpty()
    }

    private fun fieldCount(entry: ServerEntry): Int =
        (if (entry.hasPlatform) 1 else 0) +
            (if (entry.hasServerNo) 1 else 0) +
            (if (entry.hasCharacterName) 1 else 0) +
            (if (entry.hasLevel) 1 else 0)

    /**
     * **同区号冲突**：读到的这一条与清单里"同平台 + 同区号"的那一条名字不同。
     *
     * 为什么不当作新服务器加进去（2026-09-25 真机事故的口径）：同一个平台 + 同一个区号**只有一台小号**
     * （用户口径，也是 `ServerList` 唯一性的由来）。名字不同 ⇒ 只能是"同一台的另一种读法"，
     * 而 OCR 读写错的概率远高于"游戏里凭空多出一台同区号的小号"。
     * 真机后果就摆在清单里：`395|莲动渔舟` 与 `395|莲动渔` 并存 ⇒ 顺序轮换选中缺字那条，
     * 第 5 步在选服页**永远找不到**它。所以：**拦下来、报告里摊开、由人确认**（要真要加，手动加）。
     */
    data class Conflict(val captured: ServerEntry, val existing: ServerEntry)

    /** 合并结论（报告文案与单测都看它）。 */
    data class Merge(
        /** 合并后的清单（**已可直接落盘**：唯一性由 `ServerList` 保证）。 */
        val list: ServerList,
        /** 新增的条目（按读到的顺序）。 */
        val added: List<ServerEntry>,
        /** 已存在、但这次把空字段补上的名字。 */
        val filled: List<String>,
        /** 已存在且字段齐全、这次什么都没动的名字。 */
        val skipped: List<String>,
        /** 同区号但名字不同的读数：**没有新增**，等用户复核（见 [Conflict]）。 */
        val conflicts: List<Conflict> = emptyList(),
    )
}
