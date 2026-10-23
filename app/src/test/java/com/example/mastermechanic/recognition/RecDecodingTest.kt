package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PP-OCR rec 的纯逻辑（M6 换引擎，2026-10-23）：输入宽度换算 + CTC 贪心解码。
 *
 * 这两件不依赖 ONNX / 安卓框架，所以能在 JVM 单测里钉住；引擎本身要靠真机验证。
 */
class RecDecodingTest {

    // ---- RecInput.width ----

    @Test
    fun widthKeepsTheAspectRatioOfTheCrop() {
        // 真机分数块 98×57 → 48 * 98/57 = 82.5 ⇒ 四舍五入 83（不拉伸字形）
        assertEquals(83, RecInput.width(98, 57))
    }

    @Test
    fun widthIsClampedSoOneLongStripCannotBlowUpTheSession() {
        // 一整条横幅（3168×40）按比例要 3801 宽 ⇒ 必须被压到上限
        assertEquals(RecInput.MAX_WIDTH, RecInput.width(3168, 40))
    }

    @Test(expected = IllegalArgumentException::class)
    fun widthRejectsEmptyCrop() {
        RecInput.width(0, 10)
    }

    // ---- CtcDecode.greedy ----

    /**
     * 假的类别表：0 = 空白，1 = `9`，2 = `6`。
     *
     * 用 `999` 是因为它正是这次换引擎的起因 —— 只要解码这一跳写错（重复没合并 / 空白被拼进去），
     * 结果会变成 `9999` 或带空字符，一眼能看出来。
     */
    private fun label(index: Int): String? = when (index) {
        0 -> null
        1 -> "9"
        2 -> "6"
        else -> null
    }

    private fun logitsOf(steps: IntArray, classes: Int = 3): FloatArray {
        val data = FloatArray(steps.size * classes)
        for ((t, winner) in steps.withIndex()) {
            for (c in 0 until classes) {
                data[t * classes + c] = if (c == winner) 1f else 0f
            }
        }
        return data
    }

    @Test
    fun repeatedFramesCollapseIntoOneCharacter() {
        // 0,9,9,0,9,9,0,9 ⇒ 三个 9
        val logits = logitsOf(intArrayOf(0, 1, 1, 0, 1, 1, 0, 1))
        assertEquals("999", CtcDecode.greedy(logits, 8, 3, ::label))
    }

    @Test
    fun blankBetweenTwoSameCharactersKeepsThemBoth() {
        // 9,0,9 ⇒ `99`（空白是分隔符，不是重复）
        val logits = logitsOf(intArrayOf(1, 0, 1))
        assertEquals("99", CtcDecode.greedy(logits, 3, 3, ::label))
    }

    @Test
    fun blankIsNeverEmitted() {
        val logits = logitsOf(intArrayOf(0, 0, 0, 0))
        assertEquals("", CtcDecode.greedy(logits, 4, 3, ::label))
    }

    @Test
    fun sixAndNineAreDifferentCharacters() {
        val logits = logitsOf(intArrayOf(1, 1, 0, 2, 2))
        assertEquals("96", CtcDecode.greedy(logits, 5, 3, ::label))
    }

    @Test(expected = IllegalArgumentException::class)
    fun greedyRejectsTruncatedLogits() {
        CtcDecode.greedy(FloatArray(4), timeSteps = 3, classes = 3, label = ::label)
    }
}
