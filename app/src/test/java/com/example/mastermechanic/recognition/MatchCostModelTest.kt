package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.SelectionWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **匹配成本模型**单测（2026-09-29，`docs/progress.md` 第 283 / 288 条）。
 *
 * 这个公式是排障与口径决策的共同基础：`TemplateMatcher.estimateCoarseOps` 用来回答
 * "这一轮为什么慢""固定余量之后成本还随什么涨"。它必须与**实现**同口径，也必须与**真机实测**对得上，
 * 否则所有基于它的结论都是空谈 —— 所以这里把三组真机数据钉成断言。
 */
class MatchCostModelTest {

    /** 真机 `tutorial_guide_e1`（标志）：模板 155×168、产物窗口 (2261,0)-(2727,487) @ 3168×1440。 */
    private val guideWindow = SearchWindow(
        left = 2261.0 / 3168,
        top = 0.0,
        right = 2727.0 / 3168,
        bottom = 487.0 / 1440,
    )

    /** 真机 `activity_popup_e1`（标志）：模板 82×89、窗口 (2606,120)-(2753,274)。 */
    private val healthyWindow = SearchWindow(
        left = 2606.0 / 3168,
        top = 120.0 / 1440,
        right = 2753.0 / 3168,
        bottom = 274.0 / 1440,
    )

    @Test
    fun theModelMatchesTheRealDeviceMeasurements() {
        // 真机实测（`信号耗时统计`）：2064.8ms / 70.5ms；换算 ≈ 6~10ms / 百万次乘加
        // ⇒ 估算必须落在这两个量级上，否则"成本"这个词就没有共同语言了。
        assertEquals(324_979_200L, TemplateMatcher.estimateCoarseOps(guideWindow, 155, 168, 3168, 1440))
        assertEquals(3_973_761L, TemplateMatcher.estimateCoarseOps(healthyWindow, 82, 89, 3168, 1440))
    }

    @Test
    fun costIsProportionalToTemplateAreaOnceTheWindowMarginIsFixed() {
        // **新口径的直接推论**（第 288 / 289 条）：窗口 = 模板 + 每边固定余量（标志 48px）
        // ⇒ 位置数恒为 ≈49×49 ⇒ 成本**正比于模板面积**。
        // ⇒ 想再快只剩一个杠杆：把模板框小（框小一半约快一半）。
        val frameW = 3168
        val frameH = 1440
        val margin = SelectionWindow.MARKER_MARGIN_PX.toDouble()

        fun opsOf(tw: Int, th: Int): Long {
            val window = SearchWindow(
                left = (1000 - (tw / 2.0 + margin)) / frameW,
                top = (700 - (th / 2.0 + margin)) / frameH,
                right = (1000 + (tw / 2.0 + margin)) / frameW,
                bottom = (700 + (th / 2.0 + margin)) / frameH,
            )
            return TemplateMatcher.estimateCoarseOps(window, tw, th, frameW, frameH)
        }

        val small = opsOf(100, 40)
        val double = opsOf(200, 40) // 面积 ×2

        assertTrue("模板面积翻倍 ⇒ 成本也约翻倍（实测 $small ⇒ $double）", double in (small * 2 - small / 4)..(small * 2 + small / 4))
    }

    @Test
    fun aWindowSmallerThanTheTemplateCostsNothing() {
        // 退化输入不该炸（窗口装不下模板 ⇒ 匹配本来就不会跑）
        val tiny = SearchWindow(left = 0.5, top = 0.5, right = 0.51, bottom = 0.51)

        assertEquals(0L, TemplateMatcher.estimateCoarseOps(tiny, 155, 168, 3168, 1440))
        assertEquals(0L, TemplateMatcher.estimateCoarseOps(guideWindow, 0, 0, 3168, 1440))
        assertEquals(0L, TemplateMatcher.estimateCoarseOps(guideWindow, 155, 168, 0, 0))
    }
}
