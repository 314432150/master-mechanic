package com.example.mastermechanic.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 好友清单编解码单测（ADR-006）：**往返一致** + **严格拒收**（每条消息带行号，不给"看着像成功"的静默兼容）。
 */
class FriendListCodecTest {

    private fun list(vararg names: String) = FriendList(names.map { FriendEntry(it) })

    @Test
    fun roundTripKeepsOrderAndExactsNames() {
        val friends = list("阿明", "卧龙山 小趴菜", "张三")
        val decoded = FriendListCodec.decode(FriendListCodec.encode(friends))
        assertEquals(listOf("阿明", "卧龙山 小趴菜", "张三"), decoded.entries.map { it.name })
        // 确定性：同样的数据两次编码必须逐字节相同（diff 时可比对）
        assertEquals(FriendListCodec.encode(friends), FriendListCodec.encode(friends))
    }

    @Test
    fun emptyListRoundTrips() {
        val decoded = FriendListCodec.decode(FriendListCodec.encode(FriendList.EMPTY))
        assertTrue(decoded.isEmpty)
    }

    @Test
    fun commentsAndBlankLinesAreIgnored() {
        val text = """
            # 手写的说明
            format=mm-friends
            version=1

            item=阿明
            # item=注释掉的一条
            item=张三
        """.trimIndent()
        assertEquals(listOf("阿明", "张三"), FriendListCodec.decode(text).entries.map { it.name })
    }

    @Test
    fun rejectsWrongFormatTag() {
        val error = runCatching {
            FriendListCodec.decode("format=mm-patrol\nversion=1\nitem=阿明")
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("格式标记不符") == true)
    }

    @Test
    fun rejectsMissingOrUnsupportedVersion() {
        assertTrue(
            runCatching { FriendListCodec.decode("format=mm-friends\nitem=阿明") }
                .exceptionOrNull()?.message?.contains("缺少 version") == true,
        )
        assertTrue(
            runCatching { FriendListCodec.decode("format=mm-friends\nversion=2\nitem=阿明") }
                .exceptionOrNull()?.message?.contains("版本不受支持") == true,
        )
    }

    @Test
    fun rejectsBlankOrIllegalName() {
        // 空名字（"item=" 后面什么都没有）→ 必须拒收，不能当成"一条空记录"读进来
        assertTrue(
            runCatching { FriendListCodec.decode("format=mm-friends\nversion=1\nitem=") }
                .exceptionOrNull()?.message?.contains("好友名称非法") == true,
        )
        val separator = runCatching {
            FriendListCodec.decode("format=mm-friends\nversion=1\nitem=阿明|备用")
        }.exceptionOrNull()
        assertTrue(separator?.message?.contains("好友名称非法") == true)
    }

    @Test
    fun rejectsDuplicateNamesAndPointsAtTheLine() {
        val error = runCatching {
            FriendListCodec.decode("format=mm-friends\nversion=1\nitem=阿明\nitem=张三\nitem=阿明")
        }.exceptionOrNull()
        // 第 5 行才是撞名的那一条（第 1 行是注释行上面的 content）→ 行号要能用肉眼找到
        assertTrue(error?.message?.contains("第 5 行") == true)
        assertTrue(error?.message?.contains("同一个好友只能有一条") == true)
    }

    @Test
    fun rejectsUnknownKey() {
        assertTrue(
            runCatching { FriendListCodec.decode("format=mm-friends\nversion=1\nnote=随手写的") }
                .exceptionOrNull()?.message?.contains("未知键") == true,
        )
    }
}
