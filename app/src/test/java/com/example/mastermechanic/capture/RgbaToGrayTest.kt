package com.example.mastermechanic.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RGBA→灰度转换单测（T1-4）：BT.601 已知值、行跨度填充处理、非法输入拒绝。
 */
class RgbaToGrayTest {

    private fun grayAt(byte: Byte): Int = byte.toInt() and 0xFF

    @Test
    fun bt601KnownValues() {
        val pixels = listOf(
            intArrayOf(255, 0, 0), // 红
            intArrayOf(0, 255, 0), // 绿
            intArrayOf(0, 0, 255), // 蓝
            intArrayOf(255, 255, 255), // 白
            intArrayOf(0, 0, 0), // 黑
        )
        val bytes = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { i, p ->
            bytes[i * 4] = p[0].toByte()
            bytes[i * 4 + 1] = p[1].toByte()
            bytes[i * 4 + 2] = p[2].toByte()
            bytes[i * 4 + 3] = 0xFF.toByte()
        }

        val gray = RgbaToGray.toGray(bytes, width = 5, height = 1, rowStride = 20)

        assertEquals(76, grayAt(gray.pixels[0])) // 0.299*255
        assertEquals(150, grayAt(gray.pixels[1])) // 0.587*255
        assertEquals(29, grayAt(gray.pixels[2])) // 0.114*255
        assertEquals(255, grayAt(gray.pixels[3]))
        assertEquals(0, grayAt(gray.pixels[4]))
    }

    @Test
    fun rowStridePaddingSkipped() {
        // 2x2，rowStride = 12（一行 2 像素仅 8 字节，含 4 字节对齐填充）
        // 填充字节统一为 0x7F：若实现忽略 rowStride（按连续布局误读）将读到 127
        val bytes = ByteArray(2 * 12).apply { fill(0x7F.toByte()) }
        // 行 0：红、绿
        bytes[0] = 0xFF.toByte(); bytes[1] = 0; bytes[2] = 0
        bytes[4] = 0; bytes[5] = 0xFF.toByte(); bytes[6] = 0
        // 行 1：蓝、黑
        bytes[12] = 0; bytes[13] = 0; bytes[14] = 0xFF.toByte()
        bytes[16] = 0; bytes[17] = 0; bytes[18] = 0

        val gray = RgbaToGray.toGray(bytes, width = 2, height = 2, rowStride = 12)

        assertEquals(76, grayAt(gray.pixels[0])) // 红
        assertEquals(150, grayAt(gray.pixels[1])) // 绿
        assertEquals(29, grayAt(gray.pixels[2])) // 蓝（误读填充则为 127）
        assertEquals(0, grayAt(gray.pixels[3])) // 黑
    }

    @Test
    fun rejectsInsufficientData() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaToGray.toGray(ByteArray(6), width = 2, height = 1, rowStride = 8)
        }
    }

    @Test
    fun rejectsRowStrideBelowWidth() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaToGray.toGray(ByteArray(16), width = 3, height = 1, rowStride = 8)
        }
    }
}
