package com.example.mastermechanic.badge

/**
 * 「立牌池」展示层的纯逻辑（temp/badge-share-1021）：排序、top 3 截断、展开判定。
 *
 * 悬浮窗只按这里的结论 `addView`（画成什么样在 `FloatingWindow.renderBadgePool`，
 * 真机才看得到；"哪几行、什么顺序"是纯判定，一律进 JVM 单测）。
 *
 * 口径：**分数倒序**；默认 top 3，「展开全部」才给全部（2026-10-21 用户）。
 * 每行显示 = **区服名 · 分数 · 分享码**（2026-10-22 用户口径），行尾「复制」；
 * 复制内容 = `【立牌名称+分享码】价值N分`（[Row.copyText]，用户给定格式）。
 * 同分怎么排用户没说 ⇒ 取确定性顺序（区服名、再分享码升序）：验收要能逐条对照，不许一跑一个样。
 */
object BadgePoolView {

    /** 默认展示条数。 */
    const val TOP_COUNT = 3

    /**
     * 面板上的一行。
     *
     * 显示内容三次调整（2026-10-22，全是用户口径）：
     * ① 「立牌名称 + 分数」→ ② 「分数 + 分享码」（名字不上屏）→
     * ③ 当前：**「区服名 · 分数 · 分享码」** —— 区服名回来了：池子里同一张牌可能在几个号上都有，
     * 用户要能看出这条是哪个号收的（决定用哪个码去提交）；立牌名仍不上屏（复制内容与读屏播报里有）。
     */
    data class Row(
        val serverName: String,
        val cardName: String,
        val code: String,
        val score: Int,
    ) {
        /** 行上显示的文字：**区服名 + 分数 + 分享码**（2026-10-22 用户口径）。 */
        val displayText: String get() = "$serverName · ${score}分 · $code"

        /**
         * **复制到剪贴板的内容**（2026-10-22 用户给定格式，逐字对齐）：
         * ```
         * 【曹操顶级立牌WD6HD5】价值948分
         * ```
         * = 「【」+ 立牌名称 + 分享码 + 「】价值」+ 分数 + 「分」，**中间没有空格、没有服务器名**。
         */
        val copyText: String get() = "【$cardName$code】价值${score}分"
    }

    /**
     * 池子顶部的统计（2026-10-23 用户口径）：**总数 + 最高分**。
     *
     * 为什么放在纯逻辑里：数字错了比不显示更糟（用户拿它对"今天收了多少张"），
     * 所以"数哪些、取哪个分"必须能逐条断言。空池子 ⇒ [maxScore] 为 null（界面换成"还没有"那句）。
     */
    data class Summary(val total: Int, val maxScore: Int?)

    /** 统计当日（[BadgeData] 已是"按今天过滤过"的）记录数与最高分。 */
    fun summary(data: BadgeData): Summary =
        Summary(total = data.records.size, maxScore = data.records.maxOfOrNull { it.score })

    /** 分数倒序（同分按区服名、再按分享码升序 —— 纯粹为了确定性）。 */
    fun byScoreDesc(records: List<BadgeRecord>): List<BadgeRecord> =
        records.sortedWith(
            compareByDescending<BadgeRecord> { it.score }
                .thenBy { it.serverName }
                .thenBy { it.code },
        )

    /** 当前该显示哪几行：未展开只给 top [TOP_COUNT]，展开给全部。 */
    fun rows(data: BadgeData, expanded: Boolean): List<Row> {
        val sorted = byScoreDesc(data.records)
        val visible = if (expanded) sorted else sorted.take(TOP_COUNT)
        return visible.map { Row(it.serverName, it.cardName, it.code, it.score) }
    }

    /** 「展开全部 / 收起」行要不要出现：超过 top 数才需要（3 条以内没得可展开）。 */
    fun showExpandToggle(recordCount: Int): Boolean = recordCount > TOP_COUNT
}
