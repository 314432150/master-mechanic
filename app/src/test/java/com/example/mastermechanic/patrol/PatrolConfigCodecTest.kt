package com.example.mastermechanic.patrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 巡查配置编解码单测（M3-T3-2 / FR-03 / ADR-006）：往返一致（含顺序与重复区服）、
 * 确定性、空配置合法，以及各类非法文本一律拒绝且消息带行号。
 */
class PatrolConfigCodecTest {

    private val a = PatrolItem("392区", "阿明")
    private val b = PatrolItem("418区", "小美")
    private val c = PatrolItem("392区", "阿强")

    private fun decode(text: String): PatrolConfig = PatrolConfigCodec.decode(text)

    @Test
    fun roundTripKeepsOrderAndDuplicates() {
        val config = PatrolConfig(listOf(a, b, c))
        val text = PatrolConfigCodec.encode(config)
        assertEquals(config, decode(text))
        // 顺序 = 执行顺序，逐行写出
        val itemLines = text.lines().filter { it.startsWith("item=") }
        assertEquals(listOf("item=392区|阿明", "item=418区|小美", "item=392区|阿强"), itemLines)
    }

    @Test
    fun emptyConfigIsEncodableAndDecodable() {
        val text = PatrolConfigCodec.encode(PatrolConfig.EMPTY)
        assertTrue(text.contains("format=mm-patrol"))
        assertTrue(text.contains("version=1"))
        assertTrue(decode(text).isEmpty)
    }

    @Test
    fun encodingIsDeterministic() {
        val config = PatrolConfig(listOf(a, b))
        assertEquals(PatrolConfigCodec.encode(config), PatrolConfigCodec.encode(config))
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val text = """
            # 手工编辑过的配置（注释与空行应被忽略）
            format=mm-patrol

            version=1
            item=392区|阿明
        """.trimIndent()
        assertEquals(listOf(a), decode(text).items)
    }

    @Test
    fun rejectsWrongFormatTag() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-something\nversion=1\n")
        }
        assertTrue(e.message!!.contains("格式标记不符"))
    }

    @Test
    fun rejectsMissingVersionAndUnsupportedVersion() {
        assertThrows(IllegalArgumentException::class.java) { decode("format=mm-patrol\n") }
        val e = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nversion=99\n")
        }
        assertTrue(e.message!!.contains("版本不受支持"))
    }

    @Test
    fun rejectsUnknownKeyWithLineNumber() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nversion=1\nwhatever=1\n")
        }
        assertTrue(e.message!!.contains("第 3 行"))
        assertTrue(e.message!!.contains("未知键"))
    }

    @Test
    fun rejectsItemWithoutSeparatorOrWithEmptyName() {
        // 缺分隔符
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nversion=1\nitem=只有区服\n")
        }
        // 区服名为空
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nversion=1\nitem=|阿明\n")
        }
        assertTrue(e1.message!!.contains("区服名称非法"))
        // 好友名为空（FR-03「均不得为空」）
        val e2 = assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nversion=1\nitem=392区|\n")
        }
        assertTrue(e2.message!!.contains("目标好友名称非法"))
    }

    @Test
    fun rejectsDuplicateFormatOrVersionLines() {
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nformat=mm-patrol\nversion=1\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nversion=1\nversion=1\n")
        }
    }

    @Test
    fun rejectsMissingKeyValueStructure() {
        assertThrows(IllegalArgumentException::class.java) {
            decode("format=mm-patrol\nversion=1\n这是随手写的一行\n")
        }
    }
}
