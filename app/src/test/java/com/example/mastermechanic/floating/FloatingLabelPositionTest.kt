package com.example.mastermechanic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 状态标签的位置与拖动（2026-09-28；纯逻辑）。
 *
 * 三条要钉死的判定：**默认在顶部正中心**、**拖到哪都留在屏内**、**重开 / 转屏后回得去**。
 * 这些算错的后果都很直观：标签跑到屏外（用户以为没做出来）、或压在状态栏上（下拉通知栏抢触摸）。
 */
class FloatingLabelPositionTest {

    // 横屏 3168x1440（与真机采集帧同尺寸，但这里只是"一屏的尺寸"，不代表任何硬编码）
    private val screenW = 3168
    private val screenH = 1440
    private val labelW = 600
    private val labelH = 84
    private val inset = 60

    @Test
    fun defaultPositionIsTopCenter() {
        // 顶部正中心（用户口径的原话位置）：左右留白相等、纵向在状态栏之下一个边距
        val x = FloatingLabelPosition.topCenterX(screenW, labelW)

        assertEquals((screenW - labelW) / 2, x)
        assertEquals(screenW - labelW - x, x)
        assertEquals(inset + 12, FloatingLabelPosition.topY(inset, 12))
    }

    @Test
    fun dragKeepsTheLabelOnScreen() {
        // 往右下角拖一万像素：必须停在"右下角贴边"，不能跑出屏
        val far = FloatingLabelPosition.dragTo(
            startX = 100, startY = 200, dx = 10_000, dy = 10_000,
            screenWidth = screenW, screenHeight = screenH,
            labelWidth = labelW, labelHeight = labelH, topInset = inset,
        )

        assertEquals(screenW - labelW, far.x)
        assertEquals(screenH - labelH, far.y)

        // 往左上角拖：横向到 0，纵向到**状态栏之下**（不许压到状态栏上）
        val near = FloatingLabelPosition.dragTo(
            startX = 100, startY = 200, dx = -10_000, dy = -10_000,
            screenWidth = screenW, screenHeight = screenH,
            labelWidth = labelW, labelHeight = labelH, topInset = inset,
        )

        assertEquals(0, near.x)
        assertEquals(inset, near.y)
    }

    @Test
    fun dragIsRelativeToThePressPositionNotTheLastFrame() {
        // "按下时的位置 + 总位移"：拖到边缘再拖回来，落点必须与"从来没拖出去过"一致
        val there = FloatingLabelPosition.dragTo(
            startX = 500, startY = 300, dx = 99_999, dy = 0,
            screenWidth = screenW, screenHeight = screenH,
            labelWidth = labelW, labelHeight = labelH, topInset = inset,
        )
        val back = FloatingLabelPosition.dragTo(
            startX = 500, startY = 300, dx = 0, dy = 0,
            screenWidth = screenW, screenHeight = screenH,
            labelWidth = labelW, labelHeight = labelH, topInset = inset,
        )

        assertEquals(screenW - labelW, there.x)
        assertEquals(500, back.x)
    }

    @Test
    fun ratiosRoundTripOnTheSameScreen() {
        val ratios = FloatingLabelPosition.toRatios(x = 700, y = 300, screenWidth = screenW, screenHeight = screenH)

        val restored = FloatingLabelPosition.fromRatios(
            ratios, screenWidth = screenW, screenHeight = screenH,
            labelWidth = labelW, labelHeight = labelH, topInset = inset,
        )

        assertEquals(700.0, requireNotNull(restored).x.toDouble(), 1.0)
        assertEquals(300.0, restored.y.toDouble(), 1.0)
    }

    @Test
    fun ratiosAreClampedAfterRotation() {
        // 用户把标签拖到竖屏右下角（比例 0.8/0.9）；切到横屏后那个像素位置已经在屏外 ⇒ 必须钳回来
        // （比例是 Float，还原时会有一像素级的误差 ⇒ 这里按 1px 容差断言）
        val restored = FloatingLabelPosition.fromRatios(
            FloatingLabelPosition.Ratios(0.8f, 0.9f),
            screenWidth = 1000, screenHeight = 500,
            labelWidth = labelW, labelHeight = labelH, topInset = inset,
        )

        assertEquals(1000 - labelW, requireNotNull(restored).x)
        assertEquals(500 - labelH, restored.y)
    }

    @Test
    fun noStoredRatiosMeansDefaultPosition() {
        // 没拖过 ⇒ 交给调用方走"顶部居中"（这里给 null 就是那个意思）
        assertNull(
            FloatingLabelPosition.fromRatios(
                null, screenWidth = screenW, screenHeight = screenH,
                labelWidth = labelW, labelHeight = labelH, topInset = inset,
            ),
        )
        // 坏值（NaN）同样当作没存过，而不是算出一个 NaN 坐标
        assertNull(
            FloatingLabelPosition.fromRatios(
                FloatingLabelPosition.Ratios(Float.NaN, 0.1f),
                screenWidth = screenW, screenHeight = screenH,
                labelWidth = labelW, labelHeight = labelH, topInset = inset,
            ),
        )
    }

    @Test
    fun widerTextKeepsTheCenterAndStaysOnScreen() {
        // 文案变长（"待命" → "第 3/10 步 · 换号拜访"）时以**中心**为准：默认居中态仍然居中
        val center = FloatingLabelPosition.topCenterX(screenW, 300) + 150
        val newX = FloatingLabelPosition.keepCenterX(center, newWidth = 600, screenWidth = screenW)

        assertEquals((screenW - 600) / 2, newX)

        // 贴着右缘时变长：往左长（不许被屏外截掉）
        val rightEdge = screenW - labelW
        val grownAtEdge = FloatingLabelPosition.keepCenterX(
            centerX = rightEdge + labelW / 2, newWidth = labelW + 400, screenWidth = screenW,
        )

        assertEquals(screenW - (labelW + 400), grownAtEdge)
    }

    @Test
    fun labelWiderThanTheScreenIsPinnedToZero() {
        // 极端窄屏 + 长文案：不能算出负数 x（那会让窗口一部分跑到屏外）
        assertEquals(0, FloatingLabelPosition.clampX(x = 100, screenWidth = 300, labelWidth = 600))
        assertEquals(0, FloatingLabelPosition.keepCenterX(centerX = 10, newWidth = 600, screenWidth = 300))
    }

    @Test
    fun defaultAtIsTopCenterAndIndependentOfTextWidth() {
        // 用户 2026-10-01 口径（菜单「更多」→「重置提示位置」）：重置后**垂直靠近屏幕顶部、
        // 水平居中，且不受文字内容长度变化影响**。三件事一起钉住。
        val margin = 12
        val narrow = FloatingLabelPosition.defaultAt(
            screenWidth = screenW, topInset = inset, labelWidth = 300, marginPx = margin,
        )
        val wide = FloatingLabelPosition.defaultAt(
            screenWidth = screenW, topInset = inset, labelWidth = 900, marginPx = margin,
        )

        // ① 垂直：状态栏之下一个边距，**与宽度无关**（换文案不动纵向）
        assertEquals(inset + margin, narrow.y)
        assertEquals(narrow.y, wide.y)
        // ② 水平：两种宽度都居中（左右留白相等）
        assertEquals(screenW - 300 - narrow.x, narrow.x)
        assertEquals(screenW - 900 - wide.x, wide.x)
        // ③ 换文案（300 → 900）后无论走哪条路都仍居中：
        //    默认口径重算 与 "以旧中心对齐"（keepCenterX）必须给出同一个 x
        assertEquals(FloatingLabelPosition.topCenterX(screenW, 900), wide.x)
        assertEquals(wide.x, FloatingLabelPosition.keepCenterX(screenW / 2, newWidth = 900, screenWidth = screenW))
    }
}
