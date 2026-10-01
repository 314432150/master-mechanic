package com.example.mastermechanic.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [FrameSignature] 单测（2026-09-28）：采样口径（行跨度 / 步长 / 点数）、与整帧灰度一致、
 * "变了多少"的边界与**无符号比较**回归。
 *
 * 背景：这条测量第一次上真机时比的是**窗口化灰度**（识别窗口外全 0），把三个完全不同的弹窗
 * 算成"只变了 1.4%~3.6%" ⇒ 差点否掉一条可行判据。这里用纯逻辑把口径钉死。
 */
class FrameSignatureTest {

    private fun grayAt(byte: Byte): Int = byte.toInt() and 0xFF

    /** 造一帧 RGBA：像素由 [pixel] 给，A 固定 255；[padding] > 0 时每行末补 [padding] 字节。 */
    private fun frame(
        width: Int,
        height: Int,
        padding: Int = 0,
        pixel: (Int, Int) -> IntArray,
    ): Pair<ByteArray, Int> {
        val rowStride = width * 4 + padding
        val bytes = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = y * rowStride + x * 4
                val (r, g, b) = pixel(x, y).let { Triple(it[0], it[1], it[2]) }
                bytes[p] = r.toByte()
                bytes[p + 1] = g.toByte()
                bytes[p + 2] = b.toByte()
                bytes[p + 3] = 255.toByte()
            }
        }
        return bytes to rowStride
    }

    @Test
    fun sampleCountIsPerRowCeiling() {
        // 宽 10、步长 4 ⇒ 每行 3 点（x=0,4,8）；高 3 ⇒ 共 9 点（最后一点之后不足一步的余数不补点）
        val (bytes, rowStride) = frame(width = 10, height = 3) { _, _ -> intArrayOf(0, 0, 0) }

        val signature = FrameSignature.sample(bytes, width = 10, height = 3, rowStride = rowStride, step = 4)

        assertEquals(9, signature.size)
    }

    @Test
    fun stepOneEqualsFullFrameGray() {
        // 步长 1 ⇒ 逐点采样，结果应与整帧灰度（RgbaToGray.toGray）逐字节相同（含行跨度对齐）
        val (bytes, rowStride) = frame(width = 3, height = 2, padding = 8) { x, y ->
            intArrayOf(40 * x + y, 90 * (x + 1), 200 - 30 * y)
        }

        val signature = FrameSignature.sample(bytes, width = 3, height = 2, rowStride = rowStride, step = 1)
        val full = RgbaToGray.toGray(bytes, width = 3, height = 2, rowStride = rowStride)

        assertArrayEquals(full.pixels, signature)
    }

    @Test
    fun rowStridePaddingIgnored() {
        // 2x1、rowStride = 16（一行仅 8 字节，含 8 字节填充 0x7F）：填充不能被当成像素
        val (bytes, rowStride) = frame(width = 2, height = 1, padding = 8) { x, _ ->
            if (x == 0) intArrayOf(255, 255, 255) else intArrayOf(0, 0, 0)
        }
        bytes[8] = 0x7F // 填充区塞一个"看起来像灰 127"的字节

        val signature = FrameSignature.sample(bytes, width = 2, height = 1, rowStride = rowStride, step = 1)

        assertEquals(255, grayAt(signature[0]))
        assertEquals(0, grayAt(signature[1]))
    }

    @Test
    fun lumaMatchesBt601() {
        val (bytes, _) = frame(width = 3, height = 1) { x, _ ->
            when (x) {
                0 -> intArrayOf(255, 0, 0)
                1 -> intArrayOf(0, 255, 0)
                else -> intArrayOf(0, 0, 255)
            }
        }

        assertEquals(76, grayAt(FrameSignature.lumaAt(bytes, 0)))
        assertEquals(150, grayAt(FrameSignature.lumaAt(bytes, 4)))
        assertEquals(29, grayAt(FrameSignature.lumaAt(bytes, 8)))
    }

    @Test
    fun changedPercentUsesUnsignedBytes() {
        // 回归：亮度存 Byte，跨 128 的"亮 ↔ 暗"必须按无符号比
        val bright = byteArrayOf(130.toByte(), 10)
        val dark = byteArrayOf(126.toByte(), 240.toByte())

        // 130 vs 126 ⇒ 差 4 ⇒ 不算变；10 vs 240 ⇒ 差 230 ⇒ 算变 ⇒ 50%
        assertEquals(50.0, FrameSignature.changedPercent(bright, dark)!!, 0.0001)
    }

    @Test
    fun changedPercentBoundary() {
        val base = byteArrayOf(100.toByte())
        val delta23 = byteArrayOf(123.toByte())
        val delta24 = byteArrayOf(124.toByte())

        // 阈值是"≥ 24"：23 不算、24 算
        assertEquals(0.0, FrameSignature.changedPercent(base, delta23)!!, 0.0001)
        assertEquals(100.0, FrameSignature.changedPercent(base, delta24)!!, 0.0001)
    }

    @Test
    fun changedPercentIdenticalIsZero() {
        val same = ByteArray(1000) { (it % 256).toByte() }

        assertEquals(0.0, FrameSignature.changedPercent(same, same.copyOf())!!, 0.0001)
    }

    @Test
    fun changedPercentDifferentSizeIsUnknown() {
        // 几何变了不可比 ⇒ 必须给 null（不能偷偷当成"没变"）
        assertNull(FrameSignature.changedPercent(ByteArray(10), ByteArray(12)))
        assertNull(FrameSignature.changedPercent(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun sampleRejectsBadGeometry() {
        val bytes = ByteArray(64)

        assertThrows(IllegalArgumentException::class.java) {
            FrameSignature.sample(bytes, width = 4, height = 4, rowStride = 8) // 行跨度 < 4*4
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameSignature.sample(ByteArray(16), width = 4, height = 4, rowStride = 16) // 数据不足
        }
    }
}
