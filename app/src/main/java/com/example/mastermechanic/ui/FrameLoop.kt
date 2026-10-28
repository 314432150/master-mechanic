package com.example.mastermechanic.ui

/**
 * 标定工作台**首尾循环**的纯索引换算（2026-10-25 用户需求：
 * "帧主图和帧列表，支持最后一帧和第一帧滑动时循环切换"）。
 *
 * ## 为什么不真的在两端各复制一份
 *
 * 复制首尾（`[n-1] + 0..n-1 + [0]`，滑到副本上再"瞬移"回原位）是另一条常见做法，但：
 * ① 瞬移必须在滚动**停稳那一刻**做，拖到一半就会被手指接着拉回去（手感像被卡住）；
 * ② 帧条里的缩略图带"上滑删除"，同一帧出现两份 ⇒ 删了一份另一份还挂着。
 *
 * 这里改用**虚拟索引**：页码 / 条目下标都跑在一个大整数区间上，取帧时 `modulo` 回真实下标。
 * 锚点 [ANCHOR] 放在区间中部 ⇒ 一次会话里滑不到边界（99 帧要滑 662 圈），而
 * "最后一帧往后 → 第一帧"与"普通翻页"在实现上是同一条路径（页码 +1），不存在特判。
 *
 * ## 为什么单独拿出来（与 [FrameThumbSwipe] 同一个理由）
 *
 * 手势本身只能真机核；但"第几页对应第几帧""从当前页去哪一帧最近"是纯算术 ⇒ 一律放进
 * JVM 单测（[com.example.mastermechanic.ui.FrameLoopTest]），不靠真机试。
 */
internal object FrameLoop {

    /**
     * 虚拟索引锚点：`ANCHOR + 0` 就是第 0 帧。
     *
     * 取 2^16（65536）：远离 0 与 `Int.MAX_VALUE` 两端，往前往后都能滑很久；
     * 同时小到 `ANCHOR × 2` 仍在 Int 范围内（`nearestPage` 会加减小半个圈，不会溢出）。
     */
    const val ANCHOR = 1 shl 16

    /**
     * 虚拟条目数（帧条条目数 / 主图页数）。
     *
     * **1 帧（或空池）不循环**：同一张图无限重复没有意义，也会让"这条到底有几帧"更难读
     * ⇒ 保持有限，滑动照旧到头即止。
     */
    fun virtualCount(count: Int): Int = if (count <= 1) count.coerceAtLeast(0) else Int.MAX_VALUE

    /** 第 [index] 帧对应的虚拟页码 / 条目下标。 */
    fun pageOf(index: Int, count: Int): Int = if (count <= 1) 0 else ANCHOR + index

    /** 虚拟页码 → 真实下标（`0..count-1`）；空池按 0 处理（调用方不应在空池上取帧）。 */
    fun indexOf(page: Int, count: Int): Int {
        if (count <= 1) return 0
        val offset = page - ANCHOR
        return ((offset % count) + count) % count
    }

    /**
     * 离 [currentPage] 最近的一个"第 [index] 帧"的虚拟页码（最多挪半圈）。
     *
     * 两处都要用它：① 缩略图条跟着选中帧走 —— 用最短路径，首尾相接时才不会倒着划过整条；
     * ② 点缩略图跨到另一帧 —— 主图同样走最短的那条（跨半条边界的帧就是"往回一格"）。
     */
    fun nearestPage(currentPage: Int, index: Int, count: Int): Int {
        if (count <= 1) return pageOf(index, count)
        var delta = index - indexOf(currentPage, count)
        if (delta > count / 2) delta -= count
        if (delta < -(count / 2)) delta += count
        return currentPage + delta
    }
}
