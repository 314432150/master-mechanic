package com.example.mastermechanic.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器清单**行手势纯逻辑**单测（M3-T3-9 交互修订第五轮）。
 *
 * 只钉**算术与阈值**：拖动位移落到第几位、左滑压出按钮的比例、多大比例算"吸附到露出"、
 * 按下时是不是落在已露出的按钮上。手势本身（谁抢事件、动画顺不顺）只能在真机上核，单测管不到。
 *
 * 2026-09-14 起口径变了：**被盖住的行自己不动**，露出状态由"按钮盖住行尾的比例"（0 ~ 1）表达，
 * 所以旧的四条"位移量"判据（跟手位移、橡皮筋、越界回弹）**整组作废**，换成下面的比例判据。
 */
class ServerListGesturesTest {

    /** 按 4px/dp 换算（本机 640dpi）——只是让 dp 阈值在测试里好算，不是产品里的设备常量。 */
    private val density = 4f

    /** 露出区宽 88dp → 352px。 */
    private val revealWidth = ServerListGestures.REVEAL_WIDTH_DP * density

    /** 甩动阈值 300dp/s → 1200px/s。 */
    private val flingPx = ServerListGestures.SWIPE_FLING_DP_PER_SECOND * density

    // ---------------------------------------------------------------- 拖动排序

    @Test
    fun dragTargetIndexMovesByWholeRows() {
        // 落位单位 100px，起点第 2 条（下标 1）：往下拖 250px ≈ 2.5 行 → 四舍五入 3 行 → 下标 4
        assertEquals(4, ServerListGestures.dragTargetIndex(1, +250f, 100f, 6))
        // 往下拖 140px ≈ 1.4 行 → 1 行 → 下标 2
        assertEquals(2, ServerListGestures.dragTargetIndex(1, +140f, 100f, 6))
        // 往上拖 120px ≈ 1.2 行 → 1 行 → 下标 0
        assertEquals(0, ServerListGestures.dragTargetIndex(1, -120f, 100f, 6))
        // 没动 → 原地
        assertEquals(1, ServerListGestures.dragTargetIndex(1, 0f, 100f, 6))
        // 半行以内（49px）不换位：避免手指抖一下就换
        assertEquals(1, ServerListGestures.dragTargetIndex(1, +49f, 100f, 6))
    }

    @Test
    fun dragTargetIndexClampsAtBothEnds() {
        // 拖过头 → 夹在首尾（不能越界，也不能"绕回去"）
        assertEquals(0, ServerListGestures.dragTargetIndex(1, -5000f, 100f, 6))
        assertEquals(5, ServerListGestures.dragTargetIndex(1, +5000f, 100f, 6))
        assertEquals(0, ServerListGestures.dragTargetIndex(0, -1f, 100f, 1))
    }

    @Test
    fun dragTargetIndexDegradesSafely() {
        // 落位单位未知（列表布局还没量出来）/ 清单为空 → 原样返回，绝不算出一个非法下标
        assertEquals(2, ServerListGestures.dragTargetIndex(2, +500f, 0f, 5))
        assertEquals(2, ServerListGestures.dragTargetIndex(2, +500f, -1f, 5))
        assertEquals(0, ServerListGestures.dragTargetIndex(0, +500f, 100f, 0))
    }

    // ---------------------------------------------------------------- 网格拖动排序（九轮：列表改多列卡片）

    /** 网格用例的统一口径：3 列、列距 200px、行距 100px（换算成"格"是 1:1，断言里好读）。 */
    private fun gridTarget(from: Int, dx: Float, dy: Float, size: Int): Int =
        ServerListGestures.dragTargetIndexInGrid(
            from = from,
            dragOffsetXPx = dx,
            dragOffsetYPx = dy,
            colPitchPx = 200f,
            rowPitchPx = 100f,
            cols = 3,
            size = size,
        )

    @Test
    fun gridDragMovesByWholeCells() {
        // 起点下标 1 = 第 0 行第 1 列；往右一格 + 往下一格 → 第 1 行第 2 列 = 1 * 3 + 2 = 5
        assertEquals(5, gridTarget(1, +200f, +100f, 9))
        // 只往右一格 → 第 0 行第 2 列
        assertEquals(2, gridTarget(1, +200f, 0f, 9))
        // 只往左一格 → 第 0 行第 0 列
        assertEquals(0, gridTarget(1, -200f, 0f, 9))
        // 半格以内（99px / 49px）不换位：手指抖一下不该换格子
        assertEquals(1, gridTarget(1, +99f, +49f, 9))
        // 跨两行：从下标 1（第 0 行第 1 列）往下拖两格 → 第 2 行第 1 列 = 7
        assertEquals(7, gridTarget(1, 0f, +200f, 9))
    }

    @Test
    fun gridDragClampsColumnInsteadOfWrapping() {
        // 第 0 行第 0 列往左拖过头 → 列夹在 0（**不能"绕行"到上一行的末尾**）
        assertEquals(0, gridTarget(0, -100_000f, 0f, 9))
        // 第 0 行第 2 列往右拖过头 → 列夹在 2（不能绕到下一行行首）
        assertEquals(2, gridTarget(2, +100_000f, 0f, 9))
        // 第 1 行第 1 列（下标 4）往右拖过头 → 仍是同一行最右格（5）
        assertEquals(5, gridTarget(4, +100_000f, 0f, 9))
    }

    @Test
    fun gridDragClampsRowAndIndexAtListEnd() {
        // 3 列 9 条：从 0 往下拖过头 → 最后一行第一个格 = 6
        assertEquals(6, gridTarget(0, 0f, +100_000f, 9))
        // 8 条（最后一行只有 6、7 两个格）：从 5 往右下拖 → 按格子算落到 8，夹回 7
        assertEquals(7, gridTarget(5, +200f, +100f, 8))
        // 往上拖过头 → 行夹在第 0 行，**列不动**（下标 7 在第 1 列 → 落到 1，不是 0）
        assertEquals(1, gridTarget(7, 0f, -100_000f, 8))
    }

    @Test
    fun gridDragDegradesToSingleColumnAndSafely() {
        // 只剩一列时行为与原来的一维拖动一致（横向位移不参与）：往下 250px ≈ 2.5 行 → 3 行 → 下标 4
        assertEquals(
            4,
            ServerListGestures.dragTargetIndexInGrid(1, +500f, +250f, 200f, 100f, cols = 1, size = 6),
        )
        // 列距未知（布局还没量出来）→ 只按纵向算：起点 1 = 第 0 行第 1 列，下一行第 1 列 = 4
        assertEquals(
            4,
            ServerListGestures.dragTargetIndexInGrid(1, +9999f, +100f, 0f, 100f, cols = 3, size = 9),
        )
        // 行距未知 → 原样返回，绝不算出一个非法下标
        assertEquals(1, ServerListGestures.dragTargetIndexInGrid(1, +200f, +500f, 200f, 0f, 3, 9))
        // 空清单 → 0
        assertEquals(0, ServerListGestures.dragTargetIndexInGrid(0, +200f, +500f, 200f, 100f, 3, 0))
    }

    // ---------------------------------------------------------------- 露出比例（按钮盖住行尾多少）

    @Test
    fun revealFractionFollowsFingerAndClamps() {
        // 左滑半个按钮宽 → 盖住一半
        assertEquals(0.5f, ServerListGestures.revealFraction(-176f, 0f, revealWidth), 0.001f)
        // 右滑 → 一律 0（本页只认左滑，按钮不会从行里"退"出来）
        assertEquals(0f, ServerListGestures.revealFraction(+120f, 0f, revealWidth), 0.001f)
        assertEquals(0f, ServerListGestures.revealFraction(0f, 0f, revealWidth), 0.001f)
        // 滑过头 → 夹在 1（按钮全盖住）
        assertEquals(1f, ServerListGestures.revealFraction(-100_000f, 0f, revealWidth), 0.001f)
        // 刚好一个按钮宽 → 1
        assertEquals(1f, ServerListGestures.revealFraction(-revealWidth, 0f, revealWidth), 0.001f)
    }

    @Test
    fun revealFractionFromRevealedDropsWhenDraggedBack() {
        // 已露出（基准 1）再往右拖半个按钮宽 → 只剩一半
        assertEquals(0.5f, ServerListGestures.revealFraction(+176f, 1f, revealWidth), 0.001f)
        // 往右拖满一个按钮宽 → 收起
        assertEquals(0f, ServerListGestures.revealFraction(+revealWidth, 1f, revealWidth), 0.001f)
        // 已露出还继续往左拖 → 仍是 1（不会超过"全盖住"）
        assertEquals(1f, ServerListGestures.revealFraction(-200f, 1f, revealWidth), 0.001f)
        // 拖到一半松手、接着又按下去（基准 0.5）：往上接着算
        assertEquals(0.75f, ServerListGestures.revealFraction(-88f, 0.5f, revealWidth), 0.001f)
    }

    @Test
    fun revealFractionDegradesSafely() {
        // 按钮宽度未知（布局还没量出来）→ 原样返回基准，绝不算出一个非法比例
        assertEquals(0f, ServerListGestures.revealFraction(-500f, 0f, 0f), 0.001f)
        assertEquals(1f, ServerListGestures.revealFraction(-500f, 1f, 0f), 0.001f)
        // 基准本身越界（理论上不会，但传进来也得夹住）
        assertEquals(1f, ServerListGestures.revealFraction(0f, 3f, revealWidth), 0.001f)
        assertEquals(0f, ServerListGestures.revealFraction(0f, -2f, revealWidth), 0.001f)
    }

    // ---------------------------------------------------------------- 松手吸附

    @Test
    fun swipePastHalfSnapsToRevealed() {
        // 盖过按钮宽的一半 → 松手补完剩下的，停在露出位置
        assertTrue(ServerListGestures.swipeRevealsDeleteButton(0.57f, 0f, density))
        // 刚好一半也算
        assertTrue(ServerListGestures.swipeRevealsDeleteButton(0.5f, 0f, density))
        // 差一点 → 回弹收起
        assertFalse(ServerListGestures.swipeRevealsDeleteButton(0.49f, 0f, density))
    }

    @Test
    fun quickFlickRevealsEvenWhenShort() {
        // 快速一划：只滑了 50px（比例 0.14），但甩速 2000px/s = 500dp/s
        assertTrue(ServerListGestures.swipeRevealsDeleteButton(0.14f, -2000f, density))
        // 同样距离但甩得慢 → 回弹
        assertFalse(ServerListGestures.swipeRevealsDeleteButton(0.14f, -400f, density))
        // 连最小比例（0.1 ≈ 8dp）都没到：再快也不算（防"手指扫过行尾"这种误触）
        assertFalse(ServerListGestures.swipeRevealsDeleteButton(0.02f, -100_000f, density))
    }

    @Test
    fun alreadyRevealedCanBeDraggedBackToCollapse() {
        // 已露出、慢慢往右拖到 0.57 → 仍在半程之外 → 保持露出
        assertTrue(ServerListGestures.swipeRevealsDeleteButton(0.57f, 0f, density))
        // 拖回到 0.28（不足半个按钮）→ 收起
        assertFalse(ServerListGestures.swipeRevealsDeleteButton(0.28f, 0f, density))
        // 往右甩一把 → 收起（哪怕比例还够）
        assertFalse(ServerListGestures.swipeRevealsDeleteButton(0.85f, +5000f, density))
    }

    @Test
    fun swipeIgnoresZeroFractionAndSlowFlick() {
        // 比例 0（没压出来）→ 甩得再快也不露出（`revealFraction` 已经保证了这点）
        assertFalse(ServerListGestures.swipeRevealsDeleteButton(0f, -5000f, density))
        // 差一点点到甩动阈值（1199px/s < 1200px/s）→ 不算快甩，仍走"过一半"的主判据
        assertFalse(ServerListGestures.swipeRevealsDeleteButton(0.2f, -(flingPx - 1f), density))
        // 刚好踩到甩动阈值 → 算快甩（有露出就吸附）
        assertTrue(ServerListGestures.swipeRevealsDeleteButton(0.2f, -flingPx, density))
    }

    // ---------------------------------------------------------------- 删除按钮命中区

    @Test
    fun deleteButtonHitAreaOnlyWhenRevealed() {
        val rowWidth = 1000f
        // 行宽 1000px、露出区 352px → 右侧 352px（x ≥ 648）才是按钮
        assertTrue(ServerListGestures.isOnRevealedDeleteButton(700f, rowWidth, revealWidth, revealed = true))
        assertFalse(ServerListGestures.isOnRevealedDeleteButton(600f, rowWidth, revealWidth, revealed = true))
        // 没露出时这块区域属于卡片本身（点它 = 点这一行）
        assertFalse(ServerListGestures.isOnRevealedDeleteButton(700f, rowWidth, revealWidth, revealed = false))
        // 宽度未知 → 不判命中（否则可能把整行都算成按钮）
        assertFalse(ServerListGestures.isOnRevealedDeleteButton(700f, rowWidth, 0f, revealed = true))
        assertFalse(ServerListGestures.isOnRevealedDeleteButton(700f, 0f, revealWidth, revealed = true))
    }
}
