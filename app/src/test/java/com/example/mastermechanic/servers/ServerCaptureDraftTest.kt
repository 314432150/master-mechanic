package com.example.mastermechanic.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **采集复核草稿**的单测（2026-09-25 用户口径：先看后写、确认后才导入）。
 *
 * 钉住四件事：
 * ① 「再读一屏」是**累积**（同名只留字段更全的那条，顺序保持先来后到）；
 * ② **同一区号的多种读数归成一条**（不是两台服务器），默认选"与清单同名的读数"或"保留清单里的那条"；
 * ③ 逐条结论（＋新增 / ↻补全 / ＝已存在 / ⚠不写入）与 [ServerListCapture.merge] 对得上，
 *    而且**只写用户采纳的那批**（[ServerCaptureDraft.Review.toWrite]）；
 * ④ "无改动"时说得出来（`writesAnything` = false）。
 */
class ServerCaptureDraftTest {

    private fun entry(
        name: String,
        no: String = "",
        role: String = "",
        level: String = "",
    ) = ServerEntry(ServerPlatform.WECHAT, no, name, role, level)

    @Test
    fun plusAccumulatesScreensAndKeepsTheRicherReading() {
        // 第一屏读到两台（其中一台只有名字），第二屏又读到同一台（这次带全字段）+ 新的一台
        val first = ServerCaptureDraft(listOf(entry("莲动渔舟", no = "395"), entry("碧海之眼", no = "415")))
        val second = first.plus(
            listOf(
                entry("莲动渔舟", no = "395", role = "卧龙山小麦", level = "25"),
                entry("航海广场", no = "427", role = "卧龙山蘑菇", level = "6"),
            ),
        )

        // 顺序保持先来后到（= 游戏里的顺序），同名那条被"字段更全"的读数替换
        assertEquals(listOf("莲动渔舟", "碧海之眼", "航海广场"), second.rows.map { it.serverName })
        assertEquals("卧龙山小麦", second.rows.first().characterName)
        assertEquals("25", second.rows.first().level)
    }

    @Test
    fun sameServerNoWithDifferentReadingsBecomesOneItemWithOptions() {
        // ★ 用户口径（2026-09-25）：同一个区号读成两种名字 ⇒ **合并成一条**，给单选，
        // 而不是当成两台服务器（真机现场：`漠地绿洲` 与 `漢地绿洲` 同是 409 区）。
        val existing = ServerList(listOf(entry("漠地绿洲", no = "409", role = "卧龙山蓝莓", level = "6")))
        val draft = ServerCaptureDraft(
            listOf(
                entry("漠地绿洲", no = "409", role = "卧龙山蓝莓", level = "6"),
                entry("漢地绿洲", no = "409", role = "卧龙山蓝莓", level = "6"),
            ),
        )

        val review = draft.review(existing)

        assertEquals("同区号只算一条", 1, review.items.size)
        val item = review.items.single()
        assertTrue("「服务器名」有得选就要画单选", item.needsNameChoice)
        assertEquals(
            listOf("漠地绿洲", "漢地绿洲").sorted(),
            item.nameOptions.sorted(),
        )
        // 默认选**最后读到**的那条（用户刚对着它读过；名字一样长时不再有别的线索）。
        // 它恰好与清单里同名 ⇒ "已存在"，不写入。
        assertEquals("漠地绿洲", item.chosen.serverName)
        assertEquals(ServerCaptureDraft.Status.SAME, item.status)
        assertFalse(review.writesAnything)
    }

    @Test
    fun choosingAReadingDecidesWhatGetsWrittenAndConflictsAreReported() {
        val existing = ServerList(listOf(entry("漠地绿洲", no = "409")))
        val draft = ServerCaptureDraft(
            listOf(entry("漢地绿洲", no = "409", role = "卧龙山蓝莓", level = "6")),
        )
        val only = draft.review(existing)

        // 候选**只列读到的写法**（清单里已有的那条不进候选，见 2026-09-28 用户口径）
        val item = only.items.single()
        assertEquals(listOf("漢地绿洲"), item.nameOptions)
        assertFalse("默认就是唯一那条读数", item.needsNameChoice)
        assertEquals(ServerCaptureDraft.Status.CONFLICT, item.status)
        assertEquals(listOf("漢地绿洲"), only.toWrite.map { it.serverName })
        // 同区号不同名字 ⇒ 区号护栏拦下（merge 里能查到，写入后由界面/日志如实报一句）
        assertEquals(1, only.conflicts.size)
        assertFalse(only.writesAnything)
    }

    @Test
    fun reviewStampsEveryRowAgainstTheExistingList() {
        val existing = ServerList(
            listOf(
                entry("莲动渔舟", no = "395", role = "卧龙山小麦", level = "25"), // 齐全 ⇒ 已存在
                entry("碧海之眼", no = "415"), // 角色名/等级空着 ⇒ 这次补上
            ),
        )
        val draft = ServerCaptureDraft(
            listOf(
                entry("莲动渔舟", no = "395", role = "卧龙山小麦", level = "25"),
                entry("碧海之眼", no = "415", role = "卧龙山大蒜", level = "6"),
                entry("机仆阵列", no = "428", role = "卧龙山番茄", level = "6"), // 新的一台
            ),
        )

        val review = draft.review(existing)

        assertEquals(
            listOf(
                ServerCaptureDraft.Status.SAME,
                ServerCaptureDraft.Status.FILL,
                ServerCaptureDraft.Status.NEW,
            ),
            review.items.map { it.status },
        )
        assertEquals(1, review.added)
        assertEquals(1, review.filled)
        assertEquals(1, review.same)
        assertTrue("有新增/补全 ⇒ 写入按钮是有意义的", review.writesAnything)
        // toWrite = **采纳的读数全都在**（含"已存在"那条 —— 它交给 merge 的"只补空字段"判据去定夺，
        // 效果由 added / filled / same 反映，复核页显示的正是这三个数）
        assertEquals(listOf("莲动渔舟", "碧海之眼", "机仆阵列"), review.toWrite.map { it.serverName })
    }

    @Test
    fun reviewSaysNothingToWriteWhenEverythingIsAlreadyKnown() {
        val existing = ServerList(listOf(entry("莲动渔舟", no = "395", role = "卧龙山小麦", level = "25")))
        val review = ServerCaptureDraft(listOf(entry("莲动渔舟", no = "395", role = "卧龙山小麦", level = "25")))
            .review(existing)

        assertEquals(ServerCaptureDraft.Status.SAME, review.items.single().status)
        assertFalse("全部已存在 ⇒ 按钮不该说'写入 N 条'", review.writesAnything)
    }

    @Test
    fun everyReadServerGetsExactlyOneItem() {
        // 复核页要求"每个区号都看得见"（摘要里的条数 = 分组条数）
        val draft = ServerCaptureDraft((1..12).map { entry("服$it", no = "4$it") })
        val review = draft.review(ServerList.EMPTY)

        assertEquals(12, review.items.size)
        assertEquals(12, review.added)
        assertEquals(0, review.same)
        assertTrue(review.items.all { it.status == ServerCaptureDraft.Status.NEW })
    }

    @Test
    fun rowsWithoutServerNoStayInTheirOwnGroups() {
        // 区号没读到就没法判"是不是同一台" ⇒ 各自成组（**不合并**：不能拿名字去猜）
        val draft = ServerCaptureDraft(listOf(entry("莲动渔舟"), entry("碧海之眼")))
        val review = draft.review(ServerList.EMPTY)

        assertEquals(2, review.items.size)
        assertTrue(review.items.none { it.hasChoices })
    }

    @Test
    fun serverNameAndCharacterNameAreChosenIndependently() {
        // ★ 用户口径（2026-09-28）：同一张卡片里「服务器名」与「用户名」**各选各的**
        // —— 一次采集里两个字段可能分别被读错，而用户要的往往是"这条的名字 + 那条的用户名"。
        val draft = ServerCaptureDraft(
            listOf(
                entry("碧海之眼", no = "415", role = "卧龙山蓝莓", level = "6"),
                entry("碧海之瞳", no = "415", role = "卧龙山蓝梅", level = "6"),
            ),
        )
        val first = draft.review(ServerList.EMPTY).items.single()
        assertEquals(listOf("碧海之眼", "碧海之瞳"), first.nameOptions)
        assertEquals(listOf("卧龙山蓝莓", "卧龙山蓝梅"), first.characterOptions)

        // 名字取第一条读数、用户名取第二条读数 —— 混着选是允许的
        val choices = mapOf(
            ServerCaptureDraft.choiceKey(first.key, ServerCaptureDraft.Field.NAME) to "碧海之眼",
            ServerCaptureDraft.choiceKey(first.key, ServerCaptureDraft.Field.CHARACTER) to "卧龙山蓝梅",
        )
        val mixed = draft.review(ServerList.EMPTY, choices)
        assertEquals("碧海之眼", mixed.items.single().chosen.serverName)
        assertEquals("卧龙山蓝梅", mixed.items.single().chosen.characterName)
        assertEquals("等级取选中名字那一次读到的", "6", mixed.items.single().chosen.level)
        assertEquals(listOf("碧海之眼"), mixed.toWrite.map { it.serverName })
    }

    @Test
    fun characterDefaultsToTheReadingOfTheChosenName() {
        // 没挑用户名时它跟着**选中的那条读数**走（同一次读到的两个字段最可能互相对应）
        val draft = ServerCaptureDraft(
            listOf(
                entry("碧海之眼", no = "415", role = "卧龙山蓝莓"),
                entry("碧海之瞳", no = "415", role = "卧龙山蓝梅"),
            ),
        )
        // 默认选**最后读到**的那条（两个名字一样长 ⇒ 没有别的线索）
        val only = draft.review(ServerList.EMPTY).items.single()
        assertEquals("碧海之瞳", only.chosen.serverName)
        assertEquals("卧龙山蓝梅", only.chosen.characterName)

        // 把名字改选成第一条读数 ⇒ 用户名**跟着它走**（那一次读数里两个字段最可能互相对应）
        val choices = mapOf(
            ServerCaptureDraft.choiceKey(only.key, ServerCaptureDraft.Field.NAME) to "碧海之眼",
        )
        val switched = draft.review(ServerList.EMPTY, choices).items.single()
        assertEquals("碧海之眼", switched.chosen.serverName)
        assertEquals("卧龙山蓝莓", switched.chosen.characterName)
    }

    @Test
    fun choiceOfAValueThatWasNotReadFallsBackToTheDefault() {
        // 挑了一个**这批读数里没有的值**（比如上一屏的读数、或界面状态过期）⇒ 退回默认，不猜
        val draft = ServerCaptureDraft(listOf(entry("碧海之眼", no = "415", role = "卧龙山蓝莓")))
        val item = draft.review(ServerList.EMPTY).items.single()
        val choices = mapOf(
            ServerCaptureDraft.choiceKey(item.key, ServerCaptureDraft.Field.NAME) to "碧海之瞳",
        )
        assertEquals("碧海之眼", draft.review(ServerList.EMPTY, choices).items.single().chosen.serverName)
    }
}
