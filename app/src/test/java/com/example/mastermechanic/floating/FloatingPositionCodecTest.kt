package com.example.mastermechanic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗位置编解码单测（M3-T3-4 / T3-7 / ADR-006）：
 * v3（**只存状态标签**）往返一致、确定性；v1 / v2 可读且**手柄位置一律取布局常量**；
 * 各类非法文本一律拒绝且带行号；旧格式可被识别出来做迁移（[FloatingPositionCodec.needsRewrite]）。
 */
class FloatingPositionCodecTest {

    private val positions = FloatingPositions(LabelPosition(0.5, 1.0))

    private fun decode(text: String): FloatingPositions = FloatingPositionCodec.decode(text)

    @Test
    fun roundTripKeepsLabelAndNeverWritesHandle() {
        val text = FloatingPositionCodec.encode(positions, 1440, 3168)
        assertEquals(positions, decode(text))
        assertTrue(text.contains("version=3"))
        assertTrue(text.contains("label-x-ratio=0.500000"))
        assertTrue(text.contains("label-y-ratio=1.000000"))
        assertTrue(text.contains("screen=1440x3168"))
        // 手柄位置是布局常量：**不再落盘**（写过它的代价：旧值一直压住新默认值）
        assertFalse(text.contains("handle"))
        assertFalse(text.contains("side="))
    }

    @Test
    fun encodingIsDeterministic() {
        assertEquals(
            FloatingPositionCodec.encode(positions, 1440, 3168),
            FloatingPositionCodec.encode(positions, 1440, 3168),
        )
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val text = """
            # 手工改过的位置文件
            format=mm-floating

            version=3
            label-x-ratio=0.25
            label-y-ratio=0.75
            screen=1080x1920
        """.trimIndent()
        assertEquals(FloatingPositions(LabelPosition(0.25, 0.75)), decode(text))
    }

    @Test
    fun readsVersion2ButIgnoresStoredHandlePosition() {
        // v2（曾经会把手柄位置写进文件）→ **手柄字段只校验、取值一律忽略**，标签位置保留。
        // 这条是"手柄出现在旧位置、只能靠重置找回"的回归用例。
        val text = """
            format=mm-floating
            version=2
            handle-side=left
            handle-y-ratio=0.123456
            label-x-ratio=0.25
            label-y-ratio=0.75
            screen=1440x3168
        """.trimIndent()
        val decoded = decode(text)
        assertEquals(FloatingPosition.DEFAULT, decoded.handle)
        assertEquals(FloatingSide.RIGHT, decoded.handle.side)
        assertEquals(LabelPosition(0.25, 0.75), decoded.label)
    }

    @Test
    fun readsVersion1AndUsesDefaults() {
        // v1（T3-4 写的文件）只有手柄位置（现在已无意义）+ 无标签 → 全部取默认
        val text = """
            format=mm-floating
            version=1
            side=left
            y-ratio=0.2
            screen=1440x3168
        """.trimIndent()
        val decoded = decode(text)
        assertEquals(FloatingPosition.DEFAULT, decoded.handle)
        assertEquals(LabelPosition.DEFAULT, decoded.label)
    }

    @Test
    fun needsRewriteFlagsLegacyOrBrokenFiles() {
        assertTrue(FloatingPositionCodec.needsRewrite("format=mm-floating\nversion=1\nside=left\ny-ratio=0.2\nscreen=1x1\n"))
        assertTrue(FloatingPositionCodec.needsRewrite("format=mm-floating\nversion=2\nhandle-side=left\nhandle-y-ratio=0.2\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n"))
        assertTrue(FloatingPositionCodec.needsRewrite("format=mm-floating\nlabel-x-ratio=0.5\n"))
        assertFalse(
            FloatingPositionCodec.needsRewrite(
                FloatingPositionCodec.encode(positions, 1440, 3168),
            ),
        )
    }

    @Test
    fun rejectsWrongFormatOrVersion() {
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nversion=3\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertTrue(e1.message!!.contains("格式标记不符"))
        val e2 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=9\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertTrue(e2.message!!.contains("版本不受支持"))
    }

    @Test
    fun rejectsMissingRequiredLinesInV3() {
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=3\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        val e = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=3\nlabel-x-ratio=0.5\nscreen=1x1\n")
        }
        assertTrue(e.message!!.contains("label-y-ratio"))
    }

    @Test
    fun rejectsHandleFieldsInV3() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=3\nhandle-side=left\nhandle-y-ratio=0.5\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertTrue(e.message!!.contains("手柄"))
    }

    @Test
    fun rejectsMixedVersionKeys() {
        // v1 里出现 v2 的键 / v2 里出现 v1 的键：说明文本被改过，按"猜字段"拒绝
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\nside=left\ny-ratio=0.5\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertTrue(e1.message!!.contains("v1"))
        val e2 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=2\nside=left\nhandle-side=left\nhandle-y-ratio=0.5\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertTrue(e2.message!!.contains("v1"))
    }

    @Test
    fun rejectsMissingRequiredLinesInV1AndV2() {
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=1\ny-ratio=0.5\nscreen=1x1\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=2\nhandle-y-ratio=0.5\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=2\nhandle-side=left\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
    }

    @Test
    fun rejectsIllegalValuesWithLineNumber() {
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=2\nhandle-side=bottom\nhandle-y-ratio=0.5\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertTrue(e1.message!!.contains("第 3 行"))
        assertTrue(e1.message!!.contains("手柄停靠侧取值非法"))

        val e2 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=3\nlabel-x-ratio=1.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertTrue(e2.message!!.contains("比例必须在 0..1"))

        val e3 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=3\nlabel-x-ratio=abc\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertTrue(e3.message!!.contains("比例非数字"))

        val e4 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=3\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=oops\n")
        }
        assertTrue(e4.message!!.contains("屏幕尺寸应为"))

        val e5 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=3\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\nextra=1\n")
        }
        assertTrue(e5.message!!.contains("未知键"))
    }

    @Test
    fun rejectsDuplicateLines() {
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nformat=mm-floating\nversion=3\nlabel-x-ratio=0.5\nlabel-y-ratio=1\nscreen=1x1\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-floating\nversion=3\nlabel-x-ratio=0.5\nlabel-x-ratio=0.6\nlabel-y-ratio=1\nscreen=1x1\n")
        }
    }
}
