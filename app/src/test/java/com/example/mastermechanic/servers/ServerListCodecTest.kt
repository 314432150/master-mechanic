package com.example.mastermechanic.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器清单编解码单测（M3-T3-9 / FR-10 / ADR-006）：往返一致、确定性、严格拒绝、
 * "可留空字段留空"能原样往返（空字段不能被挤掉或被当成缺字段），以及 **v1 文件仍可读**
 * （2026-09-14 追加平台 + 区号后写 v2，旧文件读进来这两位为空）。
 */
class ServerListCodecTest {

    private val a = ServerEntry(ServerPlatform.WECHAT, "392", "微信392区", "阿明", "Lv.50")
    private val b = ServerEntry(ServerPlatform.QQ, "418", "QQ418区", "小美") // 等级留空
    private val c = ServerEntry(serverName = "777区") // 平台 / 区号 / 角色名 / 等级都留空

    @Test
    fun encodeThenDecodeRoundTripKeepsOrderAndFields() {
        val list = ServerList(listOf(a, b, c))
        assertEquals(list, ServerListCodec.decode(ServerListCodec.encode(list)))
    }

    @Test
    fun encodeIsDeterministicAndWritesCurrentVersion() {
        val list = ServerList(listOf(a, b))
        assertEquals(ServerListCodec.encode(list), ServerListCodec.encode(list))
        // 写入一律是当前版本（v2 = 平台 + 区号 + 区服名称 + 角色名 + 等级）
        val text = ServerListCodec.encode(list)
        assertTrue(text.contains("version=2"))
        assertTrue(text.contains("item=wechat|392|微信392区|阿明|Lv.50"))
    }

    @Test
    fun encodeKeepsAllSeparatorsEvenWhenOptionalFieldsAreEmpty() {
        val text = ServerListCodec.encode(ServerList(listOf(c)))
        // 五个字段总是写出：空字段靠"分隔符仍照写"来定位
        assertTrue(text.contains("item=||777区||"))
        assertEquals(ServerList(listOf(c)), ServerListCodec.decode(text))
    }

    @Test
    fun decodeAcceptsCommentsBlankLinesAndHeaderOrder() {
        val text = """
            # 随手写的注释
            
            version=2
            format=mm-servers
            item=wechat|392|微信392区|阿明|Lv.50
        """.trimIndent()
        assertEquals(ServerList(listOf(a)), ServerListCodec.decode(text))
    }

    @Test
    fun decodeStillReadsV1AndLeavesPlatformAndServerNoEmpty() {
        // 旧文件（三字段）不算损坏：平台与区号读进来就是"没填"
        val v1 = "format=mm-servers\nversion=1\nitem=微信392区|阿明|Lv.50\n"
        assertEquals(
            ServerList(listOf(ServerEntry(serverName = "微信392区", characterName = "阿明", level = "Lv.50"))),
            ServerListCodec.decode(v1),
        )
        // v1 里字段个数仍按 3 个算（少了 / 多了都拒收）
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=1\nitem=微信392区|阿明|Lv.50|多一个\n")
            }.message!!.contains("4 个"),
        )
    }

    @Test
    fun emptyListSurvivesRoundTrip() {
        assertEquals(ServerList.EMPTY, ServerListCodec.decode(ServerListCodec.encode(ServerList.EMPTY)))
    }

    @Test
    fun rejectsWrongFormatTagOrVersion() {
        // 别的文件 / 被改过
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("format=mm-patrol\nversion=2\n")
        }
        assertTrue(e1.message!!.contains("格式标记不符"))
        // 缺 format
        val e2 = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("version=2\n")
        }
        assertTrue(e2.message!!.contains("格式标记不符"))
        // 版本不受支持（可读 1 / 2） / 非法
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=3\n")
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
    fun rejectsUnknownPlatformCode() {
        // 平台只认 wechat / qq（或留空）：自己打的"wx"不静默当空，直接拒收并说清支持什么
        val e = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("format=mm-servers\nversion=2\nitem=wx|392|微信392区|阿明|\n")
        }
        assertTrue(e.message!!.contains("平台只支持"))
        assertTrue(e.message!!.contains("wechat"))
    }

    @Test
    fun rejectsStructuralProblemsWithLineNumber() {
        // 不是 key=value
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("format=mm-servers\nversion=2\n这不是清单\n")
        }
        assertTrue(e1.message!!.contains("第 3 行"))
        // 未知键（别的版本写进来的字段 → 必须暴露，不静默忽略）
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=2\nlevel=50\n")
            }.message!!.contains("未知键"),
        )
        // 重复的 format / version
        assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("format=mm-servers\nformat=mm-servers\nversion=2\n")
        }
    }

    @Test
    fun rejectsItemLinesWithWrongFieldCount() {
        // 只写了一个字段（四个分隔符全缺）
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=2\nitem=微信392区\n")
            }.message!!.contains("应为 5 个字段"),
        )
        // 写成 v1 那种三字段（升级后手改回来的旧行）→ 拒收并指出该写几个
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=2\nitem=微信392区|阿明|Lv.50\n")
            }.message!!.contains("应为 5 个字段"),
        )
        // 多出一个分隔符 = 末尾字段里含分隔符 → 字段个数不对，照样拒收
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ServerListCodec.decode("format=mm-servers\nversion=2\nitem=wechat|392|微信392区|阿明|Lv|50\n")
            }.message!!.contains("实际 6 个"),
        )
    }

    @Test
    fun rejectsBlankServerName() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode("format=mm-servers\nversion=2\nitem=wechat|392||阿明|Lv.50\n")
        }
        assertTrue(e.message!!.contains("区服名称非法"))
    }

    @Test
    fun rejectsDuplicatedServerNameWithLineNumber() {
        // 文件里同一区服出现两次（手动改坏 / 旧文件）→ 拒收并指出行号，不静默去重
        // （平台与区号不同也一样算重名：判重只认区服名称）
        val e = assertThrows(IllegalArgumentException::class.java) {
            ServerListCodec.decode(
                "format=mm-servers\nversion=2\n" +
                    "item=wechat|392|微信392区|阿明|Lv.50\n" +
                    "item=qq|999|微信392区|阿强|\n",
            )
        }
        assertTrue(e.message!!.contains("第 4 行"))
        assertTrue(e.message!!.contains("只能有一条"))
    }
}
