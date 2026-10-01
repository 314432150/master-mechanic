package com.example.mastermechanic.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧内容自检的单测（[FrameUniformity]）。
 *
 * 这里钉住三件事：**纯色要判得出来**（黑帧）、**有内容不能误报**（否则又会像转屏看门狗那样误伤）、
 * 以及"只看 G 通道"这条口径确实生效（否则色彩一变就误判成有内容）。
 */
class FrameUniformityTest {

    /** 造一帧 RGBA：4 字节一个像素，用 [fill] 决定每个字节的值。 */
    private fun rgba(bytes: Int, fill: (Int) -> Int): ByteArray =
        ByteArray(bytes) { fill(it).toByte() }

    @Test
    fun `纯色帧要判为全平`() {
        // 全黑（镜像失效时最常见）与全灰（空白画面）都算
        val black = rgba(40_000) { 0 }
        val gray = rgba(40_000) { 90 }

        assertTrue(FrameUniformity.sample(black).flat)
        assertTrue(FrameUniformity.sample(gray).flat)
    }

    @Test
    fun `有内容的帧不能误报`() {
        // 明暗交替：标准差远高于阈值（真实画面必然如此）
        val content = rgba(40_000) { i -> if ((i / 4) % 2 == 0) 20 else 220 }

        val sample = FrameUniformity.sample(content)

        assertFalse("有内容却被判成全平会引发误报告警", sample.flat)
        assertTrue("标准差应当很显著：${sample.stdDev}", sample.stdDev > 50)
    }

    @Test
    fun `只看G通道_红蓝变化不算内容`() {
        // R 从 0 到 255 满幅变化、G 恒为 0 ⇒ 采样点全落在 G 上 ⇒ 判为全平
        // （这是刻意的：只有亮度信息对这个判据有意义，也省掉色彩转换）
        val redVarying = rgba(40_000) { i -> if (i % 4 == 0) (i / 4) % 256 else 0 }

        assertTrue("只看 G：R 变化不影响判定", FrameUniformity.sample(redVarying).flat)
    }

    @Test
    fun `采样点按步长取_数量可预期`() {
        val length = FrameUniformity.SAMPLE_STRIDE_BYTES * 10

        val sample = FrameUniformity.sample(rgba(length) { 7 }, length)

        // 起点 1，步长 4096 ⇒ 落在 [1, length) 内的点数
        assertEquals(10, sample.count)
        assertEquals(7.0, sample.mean, 0.001)
    }

    @Test
    fun `长度为零时不炸`() {
        val sample = FrameUniformity.sample(ByteArray(0), 0)

        assertEquals(0, sample.count)
        assertFalse("没有采样点不能判成「全平」", sample.flat)
    }
}
