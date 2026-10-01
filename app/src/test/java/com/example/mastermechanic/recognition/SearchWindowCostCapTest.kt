package com.example.mastermechanic.recognition

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **成本护栏**（`TemplateMatcher.cappedByCost`，2026-09-29 第 299 条重新接回）。
 *
 * 它存在的理由是一条真机账：固定余量口径（标志 48 / 锚点 64 px/边）**不看计算量**，
 * 大模板配上这点余量后单条一次匹配就要 ~20M 乘加 ⇒ 整轮从 ~0.2s 掉到 ~0.8s
 * （用户报障"识别速度很明显慢了很多"）。护栏接在"给足余量"**之后**，只对超预算的收。
 *
 * 只钉三条**确定成立**的不变量（数值细节不在这里复算 —— 那是 [TemplateMatcher.estimateCoarseOps] 的事）：
 * 没超预算的**原样返回同一个对象**、超预算的**一定变小**、收完**不会吃掉那 32px 安全余量**。
 */
class SearchWindowCostCapTest {

    private val frameW = 3168
    private val frameH = 1440

    /** 以帧中心为中心、尺寸 spanX × spanY 的窗口（比例坐标，随帧尺寸换算）。 */
    private fun window(spanX: Int, spanY: Int): SearchWindow {
        val left = ((frameW - spanX) / 2.0) / frameW
        val top = ((frameH - spanY) / 2.0) / frameH
        return SearchWindow(
            left = left,
            top = top,
            right = left + spanX.toDouble() / frameW,
            bottom = top + spanY.toDouble() / frameH,
        )
    }

    @Test
    fun windowUnderBudgetIsReturnedUntouched() {
        // 不超预算 ⇒ 必须**原样返回同一个对象**：加载期靠 `===` 判断"这条被护栏动过没有"，
        // 也靠它保证"没超预算的信号一丁点行为都不变"
        val under = window(150, 150)

        assertSame(under, TemplateMatcher.cappedByCost(under, 82, 89, frameW, frameH))
    }

    @Test
    fun windowOverBudgetGetsSmaller() {
        val wide = window(1200, 1000)

        val capped = TemplateMatcher.cappedByCost(wide, 248, 80, frameW, frameH)

        val before = TemplateMatcher.estimateCoarseOps(wide, 248, 80, frameW, frameH)
        val after = TemplateMatcher.estimateCoarseOps(capped, 248, 80, frameW, frameH)
        assertTrue("超预算必须真的收小了（$before ⇒ $after）", after < before)
    }

    @Test
    fun neverShrinksBelowTheMinimumMargin() {
        // **余量下限优先于预算**：模板本身就大（800×600）时，收到"四周各 32px"仍会超预算
        // ⇒ 取下限、不硬凑（32px 是本项目实测的安全余量：认不出画面比慢一点更糟）
        val capped = TemplateMatcher.cappedByCost(window(1400, 1200), 800, 600, frameW, frameH)

        val bounds = capped.pixelBounds(frameW, frameH)
        // 允许 ±1px 的取整误差（`pixelBounds` 用 floor/ceil，见它的注释）
        val floor = SearchWindow.MIN_ABSOLUTE_MARGIN_PX.toInt() - 1
        assertTrue("横向余量不得低于下限", (bounds.x1 - bounds.x0 - 800) / 2 >= floor)
        assertTrue("纵向余量不得低于下限", (bounds.y1 - bounds.y0 - 600) / 2 >= floor)
    }
}
