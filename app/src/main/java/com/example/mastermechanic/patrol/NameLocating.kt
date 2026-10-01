package com.example.mastermechanic.patrol

import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.PixelBounds

/**
 * 名称定位的**编排**（M4-T4-3 真机部分，纯逻辑）：把文字识别给出的候选，变成"点哪一个、点哪里"。
 *
 * ## 三条硬口径（第 2 条 2026-09-30 按用户口径分岔）
 *
 * 1. **命中判定 = 取语义字段 + 全等**（2026-09-24 用户拍板，两条路径统一，见 [NameLocator]）：
 *    区服取「区」之后那一段、好友取括号里的备注名；**"包含"这一档已删**（它会把半行当成整行命中）；
 * 2. **区服**：0 个或 ≥2 个都算未找到，并且说清是哪种（用户才知道该改什么）；
 *    **好友**（2026-09-30 用户口径）：多个**完全同名** ⇒ **取最上面那一个**（见 [locateFriend] 第 4 条）。
 *    放宽的只有"字段怎么取""同名多行怎么选"，**没有**放宽"读歪了还能不能算命中"；
 * 3. **跨屏合并**：**区服**滚动翻屏后同名会各出现一次，必须**合起来**判唯一（看到第一个就点是 FR-04 要防的误点）；
 *    **好友**的"最上面"只用**当前这一屏**（跨屏帧坐标不可比）。
 *
 * ## 为什么"错了"是安全的
 *
 * 文字识别读错时，几乎不可能恰好把**另一个名字**读成目标串；而目标串又必须是用户清单里配好的名字
 * （FR-10 / 拜访预设）。所以识别出错的表现是 **"未找到 → 停下并提示"**，而不是点错人 / 进错区服
 * （FR-04 红线 #4）。这也是相对"名称样本模板化"最关键的优势（后者是"找不到"变成常态）。
 */
object NameLocating {

    /**
     * 名称定位**是否已启用**（唯一改动点，2026-09-21）。
     *
     * **已置 true（2026-09-21，用户口径：模板路线不靠谱，必须走文字识别）**。
     *
     * 为什么敢开：本链路的失败模式是"**未找到 → 停下并提示**"——
     * 全等 + 唯一（[NameLocator]）意味着读错一个字就匹配不上，而读成**另一个名字**的概率极低
     * （且目标名来自用户清单）；因此"识别不准"的代价是**多停几次**，不是点错人（红线 3 不破）。
     * 精度问题的表现与定位（`MM-NameReader` 那行"读到 N 行"）都会进日志，据此调「列表区域」即可。
     *
     * **第 5 步（选区服）已于 2026-09-24 接线**（`CaptureService` 的两列读 + [locateRealm]），
     * 于是第 5 / 9 步两条按名称定位的路都走文字识别。
     */
    const val ENABLED = true

    /**
     * **区服**的名字定位（第 5 步）：命中判定是"取「区」之后那一段 + 全等"。
     *
     * ## 为什么要先"按行去重"
     *
     * 文字识别会同时给出**整行**（`微信 415区 碧海之眼`）与**行内的词**（`碧海之眼`）两级结果，
     * 两者取完字段都会命中同一个区服名。不去重就会被判成"有两处 → 不唯一"，明明找到了却报找不到。
     * 所以：**同一行里的同名候选只算一个，并取更小的那个框**（词 ⊂ 行，框更小意味着点击点更贴合名字本身）。
     *
     * 不同行的同名候选**不去重**——那正是"分不清是哪一个"（滚动翻屏后重名是常态）。
     *
     * @param pages 每一屏读到的候选（按滚动顺序）；单屏就传一页。
     */
    fun locateRealm(target: String, pages: List<List<NameCandidate>>): NameLocator.Result<NameCandidate> =
        NameLocator.locateAcrossRealmName(pages.map(::collapsed), target)

    /**
     * **好友**的名字定位（第 9 步）：命中判定是"取括号里的**备注名** + 全等"
     * （2026-09-24 用户拍板，见 `docs/progress.md` 第 183 条）。
     *
     * 三段历史一句话说清，免得后人重踩：
     *
     * 1. 最初：拿配置的名字与屏幕文字**全等** → 列表里显示的是 `昵称(微信备注)`，
     *    用户配的是备注 `阿娜雅` ⇒ 永远不命中（真机 `第 9 步停下：没找到「阿娜雅」`）；
     * 2. 2026-09-22：改为**整行包含**（用户原话：*文本识别可能把非文本识别成文本，只要识别出的文本里
     *    含有配置的昵称就算匹配成功*）⇒ 能命中，但**把半行当成整行**、且符号噪声下两个不同名字
     *    可能变成同一个（误判唯一 ⇒ 点错）；
     * 3. **2026-09-24（当前）**：配的名字已改成**游戏中的备注名（无特殊字符）**⇒ 病灶从数据侧消失，
     *    于是收紧为**取括号内备注 + 全等**。判别力来自全等本身：读歪一个字 / 括号没读出来
     *    ⇒ **未找到 ⇒ 停下**（红线 3），不再靠"包含"凑一个命中。
     *
     * ## 4. **2026-09-30 口径变更（用户要求）：多个同名 ⇒ 取最上面那一个**
     *
     * 用户原话："**当屏幕上识别到多个相同的好友时，去最上面的那个。**"（`requirements.md`「口径修订十」）
     * 触发场景就是第 3 条里那个真机原样：同一好友的**多个小号共用同一个备注**
     * （`克克雨儿(阿娜雅)` / `克克洛儿(阿娜雅)`）⇒ 以前停下让用户改目标，现在**直接点最上面那条**。
     *
     * ⚠ **只在"当前这一屏内"比 y**：翻屏后帧坐标不可比（第 2 屏的"最上面"与第 1 屏不是一个空间）
     * ⇒ 逐页看，**碰到第一屏有命中就用那一屏最上面的**（`ServerListScan` 那条"跨屏合并判唯一"的口径
     * 是区服那用的，好友这条不适用）。
     *
     * 放宽的**只有"同名多行怎么选"**：命中判定仍是"取括号内备注 + 全等"（读歪一个字仍然未找到 ⇒ 停下）。
     * 代价如实记录：若两位**不同**好友的备注名恰好相同，取最上面就可能点错人（用户已知并接受）。
     */
    fun locateFriend(target: String, pages: List<List<NameCandidate>>): NameLocator.Result<NameCandidate> {
        for (page in pages) {
            val hits = NameLocator.friendRemarkHits(target, collapsed(page)).map { it.payload }
            if (hits.isNotEmpty()) {
                // 最上面 = y 最小；平手时用 x / 文本兜底，保证结果确定（§5-4）
                return NameLocator.Result.Found(hits.minWith(compareBy({ it.y }, { it.x }, { it.text })))
            }
        }
        return NameLocator.Result.NotFound("没找到「${NameLocator.normalize(target)}」")
    }

    /**
     * 第 9 步要用的**两块横向范围**（都是运行帧坐标，2026-09-22 用户口径）。
     *
     * 为什么一次给两块、而不是只给"识别区域"：**这两个范围不一样，用错会静默失效**。
     * - [ocrRegion] = **「列表区域」按「好友名称列」的左右边界裁一次**（**纵向 = 列表区域**）：
     *   只把名字那一列送去读文字（行首头像 / 装饰不进 OCR，真机实录它们会被读成 `价`/`新`/`愈` 这类前缀）；
     * - [rowX0] / [rowX1] = **列表区域**的左右边界：行带是"整行"，**拜访图标在行尾**，
     *   用名称列的右边界会让这条带里根本没有图标（同类疏漏踩过一次：`x1` 曾写成 `hit.x + hit.width`）。
     *
     * 名称列**没框过**时（老产物）[ocrRegion] 退回整片列表区域：不裁列，但也**不假装裁过** ——
     * 调用方据此在日志里说明"没框「好友名称列」"。
     */
    data class VisitRanges(val ocrRegion: PixelBounds, val rowX0: Int, val rowX1: Int)

    /**
     * 由「列表区域」+（可选的）「好友名称列」算出 [VisitRanges]（纯逻辑，真机与单测同源）。
     *
     * ## 名称列**只提供横向边界**（2026-09-22 用户口径，T4-3g）
     *
     * 曾经是"两个框取**交集**"（纵向也取）：那样框名称列时**必须让它与列表区域上下齐平**，
     * 真机实操很难对准 —— 而纵向本来就不该由用户管。现在**纵向一律取「列表区域」**，
     * 名称列的上下边界**被忽略**：只框一行高、框歪了都不影响，只要**左右**对得上。
     *
     * 保留这条锚点（而不是整个移除）有安全理由：它把行尾文字（层级 / 「申请」/ 图标）挡在 OCR 外，
     * OCR 读到的每一行才等于"名字那一小条" ⇒ 行框高 ≈ 名字高 ⇒ [rowBandOf] 的行带不会串到邻行。
     * 详见 `PatrolAnchors.FRIEND_LIST_NAME_COLUMN`。
     *
     * 横向**没对上**（无交集）时 [VisitRanges.ocrRegion] 的 `isEmpty` 为真 —— 调用方必须据此
     * **如实报"框得不对"**，不许退回整片区域去凑（那就是"猜"，红线 3 的同一口径）。
     */
    fun visitRanges(listArea: PixelBounds, nameColumn: PixelBounds?): VisitRanges = VisitRanges(
        ocrRegion = nameColumn?.let { listArea.intersectHorizontally(it) } ?: listArea,
        rowX0 = listArea.x0,
        rowX1 = listArea.x1,
    )

    /**
     * 一屏的识别结果 → 去重后的候选（整行与行内词只留一个，取更小的框）。
     *
     * **2026-09-23 由 private 放开给探针用**（[FriendListOcrProbe]）：试读要能列给用户看
     * "匹配真正吃到的是哪几条" —— 自己再写一份去重就会与生产漂移，而漂移了试读就没意义。
     * 判定语义没变：只有 [locate] / [locateFriend] 拿它当输入，仍然**只消除同一行的重复**。
     */
    fun collapsed(page: List<NameCandidate>): List<NameLocator.Candidate<NameCandidate>> =
        collapseByRow(page).map { NameLocator.Candidate(it.text, it) }

    /**
     * 同一行的两条候选是否"说的是同一个名字"（[collapseByRow] 的去重依据）。
     *
     * 判定是**互相包含**（先去掉所有空白）：文字识别的两级结果正好是这种关系 ——
     * 整行 `克克雨儿(阿娜雅) 荣耀黄金 离线` ⊇ 行内词 `克克雨儿(阿娜雅)`。
     *
     * 只消除"同一行的整行 vs 行内词"这类重复，**不做跨行去重**：不同行同名恰恰是
     * "分不清是哪一个"，必须如实上报（见 [locate] 的唯一性要求）。
     */
    private fun sameText(a: String, b: String): Boolean {
        val left = a.filterNot { it.isWhitespace() }
        val right = b.filterNot { it.isWhitespace() }
        if (left.isEmpty() || right.isEmpty()) return left == right
        return left.contains(right) || right.contains(left)
    }

    /** 同一行里的同名候选合并成一个（取更小的框）；顺序按 y 排，保证结果确定（§5-4）。 */
    private fun collapseByRow(candidates: List<NameCandidate>): List<NameCandidate> {
        val kept = ArrayList<NameCandidate>()
        candidates.sortedWith(compareBy({ it.y }, { it.x }, { it.text })).forEach { candidate ->
            val twinIndex = kept.indexOfFirst { sameText(it.text, candidate.text) && rowsOverlap(it, candidate) }
            when {
                twinIndex < 0 -> kept += candidate
                area(candidate) < area(kept[twinIndex]) -> kept[twinIndex] = candidate
            }
        }
        return kept
    }

    /** 两条候选是否落在同一行（纵向区间重叠）。 */
    private fun rowsOverlap(a: NameCandidate, b: NameCandidate): Boolean =
        a.y < b.y + b.height && b.y < a.y + a.height

    private fun area(candidate: NameCandidate): Int = candidate.width * candidate.height

    /**
     * 命中项所在的**行带**（运行帧坐标）：给第 9 步"在这一行里找拜访图标"用。
     *
     * **纵向**按名字框高度推导（行高没标过）：上下各扩 1 倍名字高（名字行 + 角色信息行，
     * 真机实测行周期 ≈83px、名字高 ≈15px ⇒ 扩 1 倍足以盖住整行又不串到邻行），夹到帧内。
     * 夹取是保守的：**宁可带窄一点**（配不上就"找不到"），也不跨到隔壁一行。
     *
     * **横向按整行给**（[x0]~[x1]，即「列表区域」的左右边界），**不是名字框的左右**：
     * 拜访图标在**行尾**，与名字隔着一整行（真机同行名字在最左、图标在最右），
     * 只圈住名字的话这条带里根本没有图标 —— 2026-09-21 用户问"要不要把图标列框进列表区域"
     * 时才发现（同日的自查疏漏：`x1` 写成了 `hit.x + hit.width`）。
     *
     * @param x0 整行左边界（运行帧坐标）
     * @param x1 整行右边界（运行帧坐标，排他）
     */
    fun rowBandOf(hit: NameCandidate, x0: Int, x1: Int, frameHeight: Int): PixelBounds {
        val left = x0.coerceAtLeast(0)
        return PixelBounds(
            x0 = left,
            y0 = (hit.y - hit.height).coerceIn(0, frameHeight),
            // 与夹取后的左边界比（不是原始参数）：否则 x0 为负时这里会算出负宽度
            x1 = x1.coerceAtLeast(left),
            y1 = (hit.y + hit.height * 2).coerceIn(0, frameHeight),
        )
    }

    // ---------------------------------------------------------------- 第 9 步跨屏查找（T4-10 刀 1）

    /**
     * **一行好友 → 行键**（好友列表版；区服那条是 [ServerListScan.rowKeyOf]，两者**不能共用**）。
     *
     * 规则本体在纯逻辑 [NameLocator.friendRowKeyOf]（两级：**先括号里**、没有括号才退回括号前并切掉
     * 首尾非文字符号）—— 为什么这么改见那里的真机依据（2026-10-01："搜索早找到了，却卡 55 秒才点下去"：
     * 旧口径拿"括号前"当键，而 OCR 每次把行首图标读成不同符号 ⇒ 键每次都变 ⇒ 复眼永远否决）。
     *
     * ⚠ 也不能用**整行**：整行带在线状态 / 段位这些随时会变的字（同一行两次读出的整行往往不同
     * ⇒ 永远判成"画面动了" ⇒ 扫描再也判不出"已到底"，会一路滑到上限才停）。
     * ⚠ **区服那把键**（`n395` / 名字）也不能共用：区服行有稳定数字（区号），好友行没有。
     *
     * @return 行键；整行读不出有效内容 ⇒ `null`（这一行不参与"画面动没动"的判断）
     */
    fun friendRowKeyOf(candidate: NameCandidate): String? =
        NameLocator.friendRowKeyOf(candidate.text)

    /** 一屏的行键集合（丢掉取不到有效内容的行，见 [friendRowKeyOf]）。 */
    fun friendRowKeysOf(page: List<NameCandidate>): Set<String> =
        page.mapNotNull(::friendRowKeyOf).toSet()

    /**
     * 一屏的 **行键 → 纵坐标**（第 9 步**开火前复眼**用：判"那一行还在原来的纵线上吗"，
     * 判据本体复用 [ServerListScan.stillOnSameRow]）。
     *
     * ⚠ 每一行把**它的全部可接受键**（[NameLocator.friendRowKeyAliasesOf]：括号里 + 括号前去噪）
     * 都放进表里 —— 复眼拿命中那一行的键去查时，**任一把命中即算"还在原地"**。这是为了兜住
     * "窄带里只读到半个括号"的情形（否则正解对不上、又白等一轮，2026-10-01 就是这么卡了 55 秒）。
     *
     * 同一个备注名出现多次（多个小号共用备注，真机原样 `克克雨儿(阿娜雅)` / `克克洛儿(阿娜雅)`）时，
     * 取**最上面**那一个的 y —— 与 [locateFriend] 的"取最上面"口径一致（否则复眼会拿另一个小号的位置
     * 去比，把正确的位置判成"挪了"）。
     */
    fun friendRowYsOf(page: List<NameCandidate>): Map<String, Double> {
        val result = LinkedHashMap<String, Double>()
        page.sortedWith(compareBy({ it.y }, { it.x }, { it.text })).forEach { candidate ->
            NameLocator.friendRowKeyAliasesOf(candidate.text).forEach { key ->
                if (!result.containsKey(key)) result[key] = candidate.y.toDouble()
            }
        }
        return result
    }

    /** 一行的**全部可接受行键**（复眼认身份用；第一个是正解，见 [NameLocator.friendRowKeyAliasesOf]）。 */
    fun friendRowKeyAliasesOf(candidate: NameCandidate): Set<String> =
        NameLocator.friendRowKeyAliasesOf(candidate.text)

    /** 一次列表滑动的几何（运行帧坐标 + 时长）；[PatrolRunner.NamePlan.Scroll] 只差一句说明文案。 */
    data class Drag(
        val fromX: Double,
        val fromY: Double,
        val toX: Double,
        val toY: Double,
        val durationMs: Long,
    )

    /**
     * **列表区域 → 滑动起止点**（跨屏查找用；第 5 步与第 9 步**共用**这一份几何）。
     *
     * ## 方向口径（**极容易写反**，2026-09-29 第一版就写反过）
     *
     * **手指向下划 = 内容下移 = 露出更靠前的条目（回顶）；
     * 手指向上划 = 内容上移 = 露出更靠后的条目（往下找）。**
     *
     * 距离取列表区域的 [edgeRatio] ↔ `1 - edgeRatio`（默认 25%↔75% = **半个区域高**：大致换掉一屏，
     * 又不至于"甩"出去等惯性）；X 取区域**中线**（纵向拖动不会选中某一行）。
     *
     * 参数由调用方传（不在这里写死）：这两个数**只能靠真机调**（不同列表的行高与惯性不同），
     * 归属仍是 `CaptureService` 的常量（第 5 步已经在真机上跑顺了，第 9 步先照用，真机不对再调）。
     */
    fun scrollDragOf(
        listArea: PixelBounds,
        towardsTop: Boolean,
        edgeRatio: Double,
        durationMs: Long,
    ): Drag {
        // ⚠ **防呆**（2026-10-01 真机事故）：行程 = 高 ×（1 - 2×ratio）⇒ ratio **超过 0.5 起止点交叉、
        // 方向直接反过来**（真机实录：把好友的 ratio 从 0.35 加到 0.6，"回顶"那一枪变成手指**向上**划
        // `(2551,853) -> (2551,631)` ⇒ 列表反而往下走，用户报"好友列表根本没有往上移动"）。
        // ⇒ 越界就**当场炸**（测试与首次运行立刻发现），而不是安静地反向划屏幕。
        require(edgeRatio in 0.05..0.45) {
            "edgeRatio 必须在 0.05~0.45：行程 = 高 ×(1-2×ratio)，>0.5 会让起止点交叉、方向反掉（当前 $edgeRatio）"
        }
        val x = listArea.x0 + (listArea.x1 - listArea.x0) / 2.0
        val height = (listArea.y1 - listArea.y0).toDouble()
        val yNear = listArea.y0 + height * edgeRatio
        val yFar = listArea.y0 + height * (1.0 - edgeRatio)
        return if (towardsTop) {
            // 回顶：手指从"靠上"划到"靠下"（内容下移）
            Drag(fromX = x, fromY = yNear, toX = x, toY = yFar, durationMs = durationMs)
        } else {
            // 往下找：手指从"靠下"划到"靠上"（内容上移）
            Drag(fromX = x, fromY = yFar, toX = x, toY = yNear, durationMs = durationMs)
        }
    }
}
