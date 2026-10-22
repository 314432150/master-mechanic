package com.example.mastermechanic.badge

import com.example.mastermechanic.servers.ServerEntry
import com.example.mastermechanic.servers.ServerList
import com.example.mastermechanic.servers.ServerPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-23 用户报障：已在该区服就别再重登。这里钉住"判不出就换号"的六条分支。 */
class CurrentServerTest {

    private val list = ServerList(
        listOf(
            ServerEntry.of(ServerPlatform.WECHAT, "392", "发条权杖", "卧龙胡萝卜", "9"),
            ServerEntry.of(ServerPlatform.WECHAT, "410", "黑砂流瀑", "日卧龙山香蕉", "6"),
            ServerEntry.of(ServerPlatform.WECHAT, "420", "奇迹瀑布", "卧龙向日葵", "6"),
        ),
    )

    @Test
    fun sameNicknameMeansWeAreAlreadyThere() {
        val verdict = CurrentServer.judge("卧龙胡萝卜", list, "发条权杖")
        assertTrue(verdict.toString(), verdict is CurrentServer.Verdict.Same)
        assertEquals("发条权杖", (verdict as CurrentServer.Verdict.Same).serverName)
    }

    @Test
    fun nicknameOfAnotherServerSaysWhereWeAre() {
        val verdict = CurrentServer.judge("卧龙向日葵", list, "发条权杖")
        assertTrue(verdict.toString(), verdict is CurrentServer.Verdict.Elsewhere)
        assertEquals(
            "奇迹瀑布",
            (verdict as CurrentServer.Verdict.Elsewhere).current,
        )
    }

    @Test
    fun unknownNicknameIsStillNotTheTarget() {
        // 清单里没这个名字 ⇒ 肯定不在目标区服（要换号），但说不出"现在在哪个区服"
        val verdict = CurrentServer.judge("查无此人", list, "发条权杖")
        assertTrue(verdict.toString(), verdict is CurrentServer.Verdict.Elsewhere)
        assertEquals(null, (verdict as CurrentServer.Verdict.Elsewhere).current)
    }

    @Test
    fun ocrSpacesAreIgnoredButMissingCharactersAreNot() {
        // OCR 常在字与字之间插空格 ⇒ 归一后仍相等
        assertTrue(CurrentServer.judge("卧龙 胡萝卜", list, "发条权杖") is CurrentServer.Verdict.Same)
        // 少读一个字**不做**前缀匹配（那等于猜）：按"不是目标"处理 ⇒ 照常换号
        val short = CurrentServer.judge("卧龙胡萝", list, "发条权杖")
        assertTrue(short.toString(), short is CurrentServer.Verdict.Elsewhere)
        assertEquals(null, (short as CurrentServer.Verdict.Elsewhere).current)
    }

    @Test
    fun emptyNicknameIsUnknownNotDifferent() {
        // 读不到文字 ≠ "不在这个区服"：要说清是"判不出来"（日志原因不同）
        assertTrue(CurrentServer.judge("   ", list, "发条权杖") is CurrentServer.Verdict.Unknown)
    }

    @Test
    fun targetWithoutCharacterNameIsUnknown() {
        // 目标那条没填角色名 ⇒ 无从比对；绝不能因为"昵称不像"就断定不在该区服
        val noName = ServerList(listOf(ServerEntry.of(ServerPlatform.WECHAT, "392", "发条权杖")))
        assertTrue(CurrentServer.judge("卧龙胡萝卜", noName, "发条权杖") is CurrentServer.Verdict.Unknown)
    }

    @Test
    fun duplicateCharacterNamesAreAmbiguous() {
        // 两个区服的小号同名（清单允许）⇒ 分不出是哪个，不猜
        val dup = ServerList(
            listOf(
                ServerEntry.of(ServerPlatform.WECHAT, "392", "发条权杖", "卧龙胡萝卜"),
                ServerEntry.of(ServerPlatform.WECHAT, "410", "黑砂流瀑", "卧龙胡萝卜"),
            ),
        )
        val verdict = CurrentServer.judge("卧龙胡萝卜", dup, "发条权杖")
        assertTrue(verdict.toString(), verdict is CurrentServer.Verdict.Unknown)
        assertTrue((verdict as CurrentServer.Verdict.Unknown).reason.contains("都叫"))
    }

    @Test
    fun targetMissingFromListIsUnknown() {
        assertTrue(CurrentServer.judge("卧龙胡萝卜", list, "不存在的区服") is CurrentServer.Verdict.Unknown)
    }

    @Test
    fun normalizeOnlyDropsWhitespace() {
        // 只去空白：全角空格也去，其它字符一个都不动（不做形近字纠正）
        assertEquals("卧龙胡萝卜", CurrentServer.normalize("　卧龙 胡萝卜 "))
        assertEquals("AA5", CurrentServer.normalize("A A 5"))
    }
}
