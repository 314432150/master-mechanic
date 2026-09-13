package com.example.mastermechanic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗位置编解码单测（M3-T3-4 / ADR-006）：往返一致、确定性，以及各类非法文本一律拒绝且带行号。
 */
class FloatingPositionCodecTest {

    private val position = FloatingPosition(FloatingSide.LEFT, 0.123456)

    private fun decode(text: String): FloatingPosition = FloatingPositionCodec.decode(text)

    @Test
    fun roundTripKeepsSideAndRatio() {
        val text = FloatingPositionCodec.encode(position, 1440, 3168)
        assertEquals(position, decode(text))
        assertTrue(text.contains("side=left"))
        assertTrue(text.contains("y-ratio=0.123456"))
        assertTrue(text.contains("screen=1440x3168"))
    }

    @Test
    fun encodingIsDeterministic() {
        assertEquals(
            FloatingPositionCodec.encode(position, 1440, 3168),
            FloatingPositionCodec.encode(position, 1440, 3168),
        )
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val text = """
            # 手工改过的位置文件
            format=mm-floating

            version=1
            side=right
            y-ratio=0.5
            screen=1080x1920
        """.trimIndent()
        assertEquals(FloatingPosition(FloatingSide.RIGHT, 0.5), decode(text))
    }

    @Test
    fun rejectsWrongFormatOrVersion() {
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nversion=1\nside=left\ny-ratio=0.5\nscreen=1x1\n")
        }
        assertTrue(e1.message!!.contains("格式标记不符"))
        val e2 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=9\nside=left\ny-ratio=0.5\nscreen=1x1\n")
        }
        assertTrue(e2.message!!.contains("版本不受支持"))
    }

    @Test
    fun rejectsMissingRequiredLines() {
        // 缺 version
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nside=left\ny-ratio=0.5\nscreen=1x1\n")
        }
        // 缺 side
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\ny-ratio=0.5\nscreen=1x1\n")
        }
        // 缺 y-ratio
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\nside=left\nscreen=1x1\n")
        }
        // 缺 screen
        val e = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\nside=left\ny-ratio=0.5\n")
        }
        assertTrue(e.message!!.contains("screen"))
    }

    @Test
    fun rejectsIllegalValuesWithLineNumber() {
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\nside=bottom\ny-ratio=0.5\nscreen=1x1\n")
        }
        assertTrue(e1.message!!.contains("第 3 行"))
        assertTrue(e1.message!!.contains("停靠侧取值非法"))

        val e2 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\nside=left\ny-ratio=1.5\nscreen=1x1\n")
        }
        assertTrue(e2.message!!.contains("纵向比例必须在 0..1"))

        val e3 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\nside=left\ny-ratio=abc\nscreen=1x1\n")
        }
        assertTrue(e3.message!!.contains("纵向比例非数字"))

        val e4 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\nside=left\ny-ratio=0.5\nscreen=oops\n")
        }
        assertTrue(e4.message!!.contains("屏幕尺寸应为"))

        val e5 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\nside=left\ny-ratio=0.5\nscreen=1x1\nextra=1\n")
        }
        assertTrue(e5.message!!.contains("未知键"))
    }

    @Test
    fun rejectsDuplicateLines() {
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nformat=mm-floating\nversion=1\nside=left\ny-ratio=0.5\nscreen=1x1\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\nside=left\nside=right\ny-ratio=0.5\nscreen=1x1\n")
        }
    }
}
