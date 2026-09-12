package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回放样本集编解码单测（T1-6a）：往返保真、确定性、严格拒绝非法输入，
 * 以及样本模型的固有约束（正样本必带目标与位置、负样本必不带）。
 */
class ReplaySetTest {

    private fun sampleText(): String = """
        # 固定批次（测试用）
        format=mm-replay-set
        version=1
        device=vivo V2463A
        scene=启动页 / 选择服务器
        sample=frame-001.png|positive|launch_start|0.300000,0.100000,0.500000,0.150000|启动页「开始游戏」
        sample=frame-002.png|neg-mask|||悬浮窗叠在标志上
        sample=frame-003.png|neg-motion|||背景动画序列
        sample=frame-004.png|neg-similar|||区服列表多个相似项
        sample=frame-005.png|neg-plain|||大厅中不存在目标
    """.trimIndent() + "\n"

    @Test
    fun decodesAllCategories() {
        val set = ReplaySetCodec.decode(sampleText())

        assertEquals("vivo V2463A", set.device)
        assertEquals("启动页 / 选择服务器", set.scene)
        assertEquals(5, set.samples.size)

        val positive = set.samples[0]
        assertEquals("frame-001.png", positive.file)
        assertEquals(ReplayCategory.POSITIVE, positive.category)
        assertEquals("launch_start", positive.signalName)
        assertEquals(0.3, positive.expected!!.left, 1e-9)
        assertEquals(0.1, positive.expected!!.top, 1e-9)
        assertEquals(0.5, positive.expected!!.right, 1e-9)
        assertEquals(0.15, positive.expected!!.bottom, 1e-9)
        assertEquals("启动页「开始游戏」", positive.note)

        val negatives = set.samples.drop(1)
        assertEquals(
            listOf(
                ReplayCategory.NEGATIVE_MASK,
                ReplayCategory.NEGATIVE_MOTION,
                ReplayCategory.NEGATIVE_SIMILAR,
                ReplayCategory.NEGATIVE_PLAIN,
            ),
            negatives.map { it.category },
        )
        negatives.forEach {
            assertNull(it.signalName)
            assertNull(it.expected)
        }
    }

    @Test
    fun roundTripIsDeterministicAndLossless() {
        val original = ReplaySetCodec.decode(sampleText())
        val text = ReplaySetCodec.encode(original)
        assertEquals(text, ReplaySetCodec.encode(original))
        assertEquals(original, ReplaySetCodec.decode(text))
    }

    @Test
    fun decodeToleratesCommentsAndBlankLines() {
        val text = "# 头注\n\n" + sampleText() + "\n# 尾注\n"
        assertEquals(5, ReplaySetCodec.decode(text).samples.size)
    }

    @Test
    fun rejectsUnknownKeyOrCategory() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(sampleText() + "mystery=1\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(sampleText().replace("|neg-mask|", "|mystery|"))
        }
    }

    @Test
    fun rejectsWrongFormatOrVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(sampleText().replace("format=mm-replay-set", "format=other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(sampleText().replace("version=1", "version=2"))
        }
    }

    @Test
    fun rejectsDuplicateFileWithLineNumber() {
        val duplicated = sampleText() + "sample=frame-002.png|neg-plain|||重复文件\n"
        val error = assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(duplicated)
        }
        assertTrue(error.message!!.contains("样本文件重复"))
        assertTrue(error.message!!.contains("第 11 行"))
    }

    @Test
    fun rejectsMalformedSampleLines() {
        // 正样本缺信号名
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(sampleText().replace("|positive|launch_start|", "|positive||"))
        }
        // 正样本矩形越界（比例必须 0..1）
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(
                sampleText().replace("0.300000,0.100000,0.500000,0.150000", "1.200000,0.100000,0.500000,0.150000"),
            )
        }
        // 负样本携带信号名
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(sampleText().replace("|neg-mask|||", "|neg-mask|sig||"))
        }
        // 负样本携带矩形
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(sampleText().replace("|neg-plain|||", "|neg-plain||0.1,0.1,0.2,0.2|"))
        }
        // 字段数不足
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(sampleText().replace("|neg-motion|||背景动画序列", "|neg-motion||"))
        }
    }

    @Test
    fun rejectsBadFileName() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode(sampleText().replace("frame-004.png", "sub/frame-004.png"))
        }
    }

    @Test
    fun rejectsEmptySampleSet() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySetCodec.decode("# 只有头部\nformat=mm-replay-set\nversion=1\n")
        }
    }

    @Test
    fun modelRejectsInconsistentSample() {
        // 负样本带信号名
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySample("f.png", ReplayCategory.NEGATIVE_MASK, "sig", null, "")
        }
        // 正样本缺期望位置
        assertThrows(IllegalArgumentException::class.java) {
            ReplaySample("f.png", ReplayCategory.POSITIVE, "sig", null, "")
        }
    }
}
