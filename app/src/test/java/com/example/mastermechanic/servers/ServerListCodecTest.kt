package com.example.mastermechanic.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器清单编解码单测（M3-T3-9 / FR-10 / ADR-006）：往返一致、确定性、严格拒绝，
 * 以及"可选字段留空"能原样往返（空字段不能被挤掉或被当成缺字段）。
 */
class ServerListCodecTest {

    private val a = ServerEntry("392区", "阿明", "Lv.50")
    private val b = ServerEntry("418区", "小美") // 等级留空
    private val c = ServerEntry("777区") // 角色名与等级都留空

    @Test
    fun encodeThenDecodeRoundTripKeepsOrderAndFields() {
        val list = ServerList(listOf(a, b, c))
        assertEquals(list, ServerListCodec.decode(ServerListCodec.encode(list)))
    }

    @Test
    fun encodeIsDeterministic() {
        val list = ServerList(listOf(a, b))
        assertEquals(ServerListCodec.encode(list), ServerListCodec.encode(list))
    }

    @Test
    fun encodeKeepsBothSeparatorsEvenWhenOptionalFieldsAreEmpty() {
        val text = ServerListCodec.encode(ServerList(listOf(c)))
        // 三个字段总是写出：空字段靠"分隔符仍照写"来定位
        assertTrue(text.contains("item=777区||"))
        assertEquals(ServerList(listOf(c)), ServerListCodec.decode(text))
    }

    @Test
    fun decodeAcceptsCommentsBlankLinesAndHeaderOrder() {
        val text = """
            # 随手写的注释
            
            version=1
            format=mm-servers
            item=392区|阿明|Lv.50
        """.trimIndent()
        assertEquals(ServerList(listOf(a)), ServerListCodec.decode(text))
    }

    @Test
    fun emptyListSurvivesRoundTrip() {
        assertEquals(ServerList.EMPTY, ServerListCodec.decode(ServerListCodec.encode(ServerList.EMPTY)))
    }

    @Test
    fun rejectsWrongFormatTagOrVersion() {
        // 别的文件 / 被改过
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("format=mm-patrol\nversion=1\n")
        }
        assertTrue(e1.message!!.contains("格式标记不符"))
        // 缺 format
        val e2 = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("version=1\n")
        }
        assertTrue(e2.message!!.contains("格式标记不符"))
        // 版本不受支持 / 非法
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=2\n")
            }.message!!.contains("版本不受支持"),
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=x\n")
            }.message!!.contains("版本号非整数"),
        )
        // 缺 version
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\n")
            }.message!!.contains("缺少 version 行"),
        )
    }

    @Test
    fun rejectsStructuralProblemsWithLineNumber() {
        // 不是 key=value
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("format=mm-servers\nversion=1\n这不是清单\n")
        }
        assertTrue(e1.message!!.contains("第 3 行"))
        // 未知键（别的版本写进来的字段 → 必须暴露，不静默忽略）
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=1\nlevel=50\n")
            }.message!!.contains("未知键"),
        )
        // 重复的 format / version
        assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("format=mm-servers\nformat=mm-servers\nversion=1\n")
        }
    }

    @Test
    fun rejectsItemLinesWithMissingOrExtraSeparators() {
        // 缺分隔符（只写了一个字段）
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=1\nitem=392区\n")
            }.message!!.contains("缺分隔符"),
        )
        // 只有一个分隔符（角色名与等级少写一位）
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=1\nitem=392区|阿明\n")
            }.message!!.contains("分隔符只有 1 个"),
        )
        // 多出一个分隔符 = 末尾字段里含分隔符 → 拒收
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=1\nitem=392区|阿明|Lv|50\n")
            }.message!!.contains("等级非法"),
        )
    }

    @Test
    fun rejectsBlankServerName() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("format=mm-servers\nversion=1\nitem=|阿明|Lv.50\n")
        }
        assertTrue(e.message!!.contains("区服名称非法"))
    }
}
