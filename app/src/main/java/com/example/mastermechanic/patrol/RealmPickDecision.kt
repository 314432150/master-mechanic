package com.example.mastermechanic.patrol

/**
 * **第 5 步"点哪一行"的判据**（纯逻辑，2026-09-25 用户口径：**区号为主、区名为辅**）。
 *
 * ## 为什么区号当主判据
 *
 * 真机实测（`docs/progress.md` 第 208 条）：同样的一屏里 **数字那一栏 12/12 行全对**，
 * 而汉字区名会**丢字 / 多字 / 形近错**（`莲动渔舟 → 莲动渔`、`海鹰之羽 → 海鹰之`、`火鹰重炮 → 义鹰重炮`）；
 * 并且**区号在微信平台内唯一** ⇒ 它天然是主键，区名只用来消歧与交叉印证。
 *
 * ## 判据（写死在这里，别在各处再写一份）
 *
 * ```
 * ① 区号 == 目标区号 的行 ⇒ 候选（**主判据**）
 *      · 唯一 ⇒ 采纳（名字也全等 ⇒ Via.NUMBER_AND_NAME，否则 Via.NUMBER）
 *      · ≥2 行 ⇒ 拒识（歧义）
 *      · 名字明确指向**清单里别的**区服（且不像目标）⇒ 拒识（两个字段矛盾，不猜）
 * ② 区号没命中 ⇒ 退回**区名全等**（**严格全等，不放宽**）：唯一 ⇒ 采纳（Via.NAME）
 *      · 名字全等、但同一行的区号是**清单里另一条**的区号 ⇒ 拒识（真矛盾）
 *      · 名字全等、区号不在清单里 ⇒ 多半是区号读错一位 ⇒ **名字仍然可信**
 * ③ 都不中 / 都歧义 ⇒ 拒识，由调用方"停下并提示"（红线 3：绝不挑最像的）
 * ```
 *
 * ## 什么没有被放宽
 *
 * - **区名不做"近似命中"**：区号为主以后，"名字少读一个字"由 ① 覆盖（区号对就行）；
 *   两个字段都只有软证据时**一律停下**（不拿"差一个字"去凑）。
 *   `withinOneEdit` 只用在"**这一行是不是别的区服**"这类**矛盾检查**上。
 * - 任何歧义（同一区号/同一名字多行）、任何矛盾（区号指向 A、名字指向 B）⇒ [Result.Miss]。
 *
 * ## 与旧行为的关系
 *
 * 旧判据是"区名全等 + 唯一"（`NameLocating.locateRealm`）—— 它是本判据 ② 的特例：
 * 区号没读到 / 清单没配区号时，走的就是那条路，行为不变。
 */
object RealmPickDecision {

    /** 清单里的一条区服（`serverNo` 与区名）。 */
    data class Entry(val name: String, val number: String)

    /** 屏幕上读到的一行。 */
    data class Row(val line: String, val centerX: Double, val centerY: Double) {

        /** 区名段（口径与 [NameLocator.realmNameOf] 一致：取第一个「区」之后）。 */
        val name: String get() = NameLocator.realmNameOf(line)

        /** 区号（同一行文本里的数字；取不到 = null ⇒ 主判据对这一行不适用）。 */
        val number: String? get() = NameLocator.realmNumberOf(line).ifEmpty { null }
    }

    /** 命中靠的是哪条通道（日志要说清，用户才知道该信谁）。 */
    enum class Via {
        /** **区号为主**：区号全等定下来的（名字没读到 / 读错都不影响）。 */
        NUMBER,

        /** 区号与区名**都对**（最强）。 */
        NUMBER_AND_NAME,

        /** 区号没帮上（没读到 / 读错），靠**区名全等 + 唯一**定下来的（= 旧口径）。 */
        NAME,
    }

    sealed interface Result {
        data class Hit(val row: Row, val via: Via) : Result
        data class Miss(val detail: String) : Result
    }

    /**
     * @param rows 本屏所有名字行的读数
     * @param target 目标条目（来自服务器清单）
     * @param others 清单里**其它**条目（只用于矛盾检查：判断"这一行是不是别的区服"）
     */
    fun decide(rows: List<Row>, target: Entry, others: List<Entry>): Result {
        val wantNo = target.number.trim()
        val wantName = squash(target.name)
        if (wantNo.isEmpty() && wantName.isEmpty()) {
            return Result.Miss("清单里这一条既没区号也没区名（清单读坏了？）")
        }

        // ① 区号为主
        val byNumber = if (wantNo.isEmpty()) emptyList() else rows.filter { it.number == wantNo }
        if (byNumber.size > 1) {
            return Result.Miss("区号「$wantNo」在本屏有 ${byNumber.size} 行，分不清是哪一个（停下就不会点错）")
        }
        if (byNumber.size == 1) {
            val row = byNumber.first()
            conflictWithOthers(row, wantName, others)?.let { return Result.Miss(it) }
            val exact = squash(row.name) == wantName
            return Result.Hit(row, if (exact) Via.NUMBER_AND_NAME else Via.NUMBER)
        }

        // ② 区号没命中 ⇒ 区名全等兜底（严格全等；这是旧口径那一刀）
        val byName = if (wantName.isEmpty()) emptyList() else rows.filter { squash(it.name) == wantName }
        if (byName.size > 1) {
            return Result.Miss("「$wantName」在本屏有 ${byName.size} 行，分不清是哪一个（停下就不会点错）")
        }
        if (byName.size == 1) {
            val row = byName.first()
            val no = row.number
            if (no != null && wantNo.isNotEmpty() && no != wantNo) {
                val elsewhere = others.firstOrNull { it.number.trim() == no }
                if (elsewhere != null) {
                    return Result.Miss(
                        "名字全等「$wantName」，但同一行的区号是「$no」——那正是清单里「${elsewhere.name}」的区号" +
                            "⇒ 两个字段矛盾，不猜",
                    )
                }
                // 那个区号不在清单里 ⇒ 多半是这一行的区号读错了一位 ⇒ 名字仍然可信（记在日志里由调用方打印）
            }
            return Result.Hit(row, Via.NAME)
        }

        return Result.Miss(
            "区号「${wantNo.ifEmpty { "—" }}」与区名「${wantName.ifEmpty { "—" }}」都没在屏幕上找到" +
                if (wantNo.isEmpty()) "（清单里这一条没配区号，只能靠区名）" else "",
        )
    }

    /**
     * 这一行的名字是否明确指向**清单里别的**区服（`others`）—— 而**不像目标**？
     *
     * 是 ⇒ 返回一句"矛盾"说明（调用方据此拒绝）；否则 null。
     *
     * 只用在"区号已经命中"的候选上：**区号说 A、名字说 B**，两个字段指向不同的服务器 ⇒ 不猜。
     * 名字读不出来（空）/ 读成谁也认不出的乱码 ⇒ 不算矛盾（区号为主，照样采纳）。
     */
    private fun conflictWithOthers(row: Row, wantName: String, others: List<Entry>): String? {
        val name = squash(row.name)
        if (name.isEmpty()) return null
        val looksLikeTarget = wantName.isNotEmpty() && withinOneEdit(name, wantName)
        if (looksLikeTarget) return null
        val other = others.firstOrNull {
            val candidate = squash(it.name)
            candidate.isNotEmpty() && (candidate == name || withinOneEdit(name, candidate))
        } ?: return null
        return "区号命中「${row.number}」，但同一行的名字读成「${row.name}」——那像是清单里的「${other.name}」" +
            "⇒ 两个字段矛盾，不猜"
    }

    /** 去空白（与 `NameLocator` 同一口径：OCR 常在字间插空格）。 */
    private fun squash(text: String): String = text.filterNot { it.isWhitespace() }

    /**
     * 两个名字是否"最多差一个字"（插入 / 删除 / 替换，编辑距离 ≤ 1）。
     *
     * ⚠ **只用在矛盾检查里**（判断"这一行的名字是不是别的区服"），**不是**一条名称匹配放宽：
     * 区名要采纳仍然必须**全等**（见类注释）。
     * 名字都很短（2~6 字），DP 成本可忽略。
     */
    internal fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitute = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitute)
            }
            previous = current
        }
        return previous[b.length] <= 1
    }
}
