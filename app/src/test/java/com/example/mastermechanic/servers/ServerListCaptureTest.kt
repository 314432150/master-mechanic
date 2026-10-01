package com.example.mastermechanic.servers

import com.example.mastermechanic.recognition.NameCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「添加这一屏的服务器」解析 / 合并单测（2026-09-24）。
 *
 * 输入样例**照抄真机**：既用 2026-09-24 现场截图里读全的行（`微信395区 莲动渔舟` + `( Lv.25 卧龙山小麦 )`），
 * 也用同一批真机日志里读残的行（`微信395区 莲动渔` 丢末字、`(Lv.25` 没读到角色名、图标混成 `T`/`H`）。
 */
class ServerListCaptureTest {

    /** 造一条候选（x/y 只是位置，配对用）。 */
    private fun line(
        text: String,
        x: Int,
        y: Int,
        width: Int = 120,
        height: Int = 18,
    ) = NameCandidate(text = text, x = x, y = y, width = width, height = height)

    @Test
    fun parsesNameAndDetailLinesIntoEntries() {
        val candidates = listOf(
            line("选择服务器", 160, 20, 120, 24), // 标题行：不是条目
            line("微信395区 莲动渔舟", 160, 110),
            line("( Lv.25 卧龙山小麦 )", 175, 135, 150, 16),
            line("微信415区 碧海之眼", 610, 110),
            line("( Lv.6 卧龙山大蒜 )", 625, 135, 150, 16),
        )

        val entries = ServerListCapture.parse(candidates)

        assertEquals(2, entries.size)
        assertEquals(
            // 等级只收数字（`Lv.` 是清单页的显示标签，用户口径 2026-09-25）
            ServerEntry(ServerPlatform.WECHAT, "395", "莲动渔舟", "卧龙山小麦", "25"),
            entries[0],
        )
        assertEquals(
            ServerEntry(ServerPlatform.WECHAT, "415", "碧海之眼", "卧龙山大蒜", "6"),
            entries[1],
        )
    }

    @Test
    fun columnsInterleavedStillPairDetailsByPosition() {
        // 真机行序是「左名、右名、左详情、右详情」——按"紧跟一行"配对会配错，必须按位置配
        val candidates = listOf(
            line("微信395区 莲动渔舟", 160, 110),
            line("微信415区 碧海之眼", 610, 110),
            line("( Lv.25 卧龙山小麦 )", 175, 135, 150, 16),
            line("( Lv.6 卧龙山大蒜 )", 625, 135, 150, 16),
        )

        val entries = ServerListCapture.parse(candidates)

        assertEquals("卧龙山小麦", entries.first { it.serverName == "莲动渔舟" }.characterName)
        assertEquals("6", entries.first { it.serverName == "碧海之眼" }.level)
    }

    @Test
    fun levelAndRoleSplitAcrossTwoLinesAreBothCaptured() {
        // ★ 2026-09-25 用户报障（`战利品` 没读到角色名）的真机原文：详情被 OCR 切成**两条**
        // —— `( Lv.6` 与 `卧龙山土豆)`。老做法"一条详情配一条名字"只认带 `Lv` 的那条 ⇒ 角色名整条丢掉。
        val candidates = listOf(
            line("微信426区 战利品", 160, 110),
            line("( Lv.6", 175, 135, 40, 16),
            line("卧龙山土豆)", 230, 135, 90, 16),
            // 同一列下面还有一台（它的详情不许被上面这台认领）
            line("微信408区 羽箭破空", 160, 210),
            line("( Lv.6 卧龙山黄瓜 )", 175, 235, 150, 16),
        )

        val entries = ServerListCapture.parse(candidates)

        val loot = entries.first { it.serverName == "战利品" }
        assertEquals("6", loot.level)
        assertEquals("卧龙山土豆", loot.characterName)
        assertEquals("卧龙山黄瓜", entries.first { it.serverName == "羽箭破空" }.characterName)
    }

    @Test
    fun ocrNoiseAndMissingDetailStillYieldTheServerName() {
        // 真机原文（2026-09-24 19:24）：名字丢末字、详情行被切成 `(Lv.25` 与 `卧龙山小` 两条，
        // 还混进图标噪声 `T`。**区服名照收**（它就是屏幕上的样子），其余字段能填多少填多少。
        val candidates = listOf(
            line("微信395区 莲动渔", 160, 110),
            line("(Lv.25", 175, 135, 40, 16),
            line("卧龙山小", 230, 135, 60, 16),
            line("(Lv.9 T卧龙山胡", 610, 135, 120, 16),
        )

        val entries = ServerListCapture.parse(candidates)

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("莲动渔", entry.serverName) // 读成什么就是什么：不猜、不补字（红线 3）
        assertEquals("395", entry.serverNo)
        assertEquals("25", entry.level) // 详情行只要挨着它、带 Lv 就能配上（只收数字）
        // 角色名读残了也**照收**（`卧龙山小`）：以前是整条留空 —— 少一段总比没有强，
        // 而且清单页 / 复核页都能改（用户口径：宁可看到"读到的东西"，也不要空着）
        assertEquals("卧龙山小", entry.characterName)
        // 右边那一列（x 不相交）的那条详情**不许**被认领到这一台头上
        assertTrue(entries.none { it.characterName == "T卧龙山胡" || it.characterName.contains("卧龙山胡") })
    }

    @Test
    fun duplicateServerNameKeepsTheRicherRow() {
        val candidates = listOf(
            line("微信395区 莲动渔舟", 160, 110),
            line("微信395区 莲动渔舟", 160, 110), // 同一行被读两次（不同块）
            line("( Lv.25 卧龙山小麦 )", 175, 135, 150, 16),
        )

        val entries = ServerListCapture.parse(candidates)

        assertEquals("重复名字只留一条", 1, entries.size)
        assertEquals("留下的应当是字段更全的那条", "卧龙山小麦", entries.single().characterName)
    }

    @Test
    fun mergeAddsNewFillsBlanksAndNeverOverwritesUserInput() {
        val existing = ServerList(
            listOf(
                // 用户在 App 里配过：等级自己填过、角色名留空
                ServerEntry(ServerPlatform.WECHAT, "395", "莲动渔舟", "", "Lv.30"),
            ),
        )
        val captured = listOf(
            ServerEntry(ServerPlatform.WECHAT, "395", "莲动渔舟", "卧龙山小麦", "Lv.25"),
            ServerEntry(platform = null, serverNo = "415", serverName = "碧海之眼", characterName = "", level = ""),
            ServerEntry(platform = ServerPlatform.QQ, serverName = "龙腾盛世"),
        )

        val merge = ServerListCapture.merge(existing, captured)

        assertEquals(listOf("碧海之眼", "龙腾盛世"), merge.added.map { it.serverName })
        assertEquals(listOf("莲动渔舟"), merge.filled)
        assertTrue("已有的名字不该重复加进去", merge.skipped.isEmpty())
        assertEquals(3, merge.list.size)
        // 用户填过的等级**不许被覆盖**；空着的角色名补上
        val kept = merge.list.entries.first { it.serverName == "莲动渔舟" }
        assertEquals("Lv.30", kept.level)
        assertEquals("卧龙山小麦", kept.characterName)
    }

    @Test
    fun sameRowReadTwiceKeepsTheLongerName() {
        // 2026-09-25 真机：整屏分块（×2）把 `莲动渔舟` 读成 `莲动渔`，逐行复核（×3）读全了。
        // 两次读数是**同一行**（位置几乎重合）⇒ 取名字更长的那个（取更完整的读数，不是猜）。
        val candidates = listOf(
            line("微信395区 莲动渔", 160, 110, 110),
            line("微信395区 莲动渔舟", 160, 112, 122),
            line("( Lv.25 卧龙山小麦 )", 175, 135, 150, 16),
        )

        val entries = ServerListCapture.parse(candidates)

        assertEquals(1, entries.size)
        assertEquals("莲动渔舟", entries.single().serverName)
    }

    @Test
    fun leftAndRightColumnsOnTheSameRowAreNeverMerged() {
        // 左右两列在同一 y 上：横向范围不重叠 ⇒ 是两条，不许当成"同一行的两种读法"（否则会吃掉一个服）
        val candidates = listOf(
            line("微信395区 莲动渔", 160, 110, 110),
            line("微信415区 碧海之眼", 610, 110, 120),
        )

        val entries = ServerListCapture.parse(candidates)

        assertEquals(listOf("莲动渔", "碧海之眼"), entries.map { it.serverName })
    }

    @Test
    fun gridOrderIsLeftToRightThenNextRow() {
        // ★ 2026-09-25 用户截图 + 真机日志：版式是**两列网格**，同一个视觉行里右列的文字框
        // 常常比左列高几个像素 ⇒ 只按 y 排会排成 `…425, 427, 426, 418, 408…`（用户报障"顺序不一致"）。
        // 正确顺序 = 左→右、然后下一行（上→下）。
        val candidates = listOf(
            line("微信395区 莲动渔舟", 160, 112),
            line("( Lv.25 卧龙山小麦 )", 175, 137, 150, 16),
            line("微信415区 碧海之眼", 610, 108), // 右列比左列高 4px：不许因此插到前面
            line("( Lv.6 卧龙山大蒜 )", 625, 133, 150, 16),
            line("微信425区 云海航线", 160, 312),
            line("( Lv.6 卧龙山玉米 )", 175, 337, 150, 16),
            line("微信56区白色死神", 610, 308),
            line("( Lv.30 卧龙山小当家 )", 625, 333, 150, 16),
        )

        assertEquals(
            listOf("莲动渔舟", "碧海之眼", "云海航线", "白色死神"),
            ServerListCapture.parse(candidates).map { it.serverName },
        )
    }

    @Test
    fun headerPillIsNotTakenAsTheFirstRow() {
        // 表头右侧那颗"当前区服"药丸（`◆微信56区 白色死神`）也是名字行，位置比列表里的同一行**更靠上**
        // ⇒ 它必须不占第一个位置（真机后果：清单里 `白色死神` 排到了第一位）。
        val candidates = listOf(
            line("◆微信56区 白色死神", 900, 30, 200, 20), // 表头药丸
            line("微信395区 莲动渔舟", 160, 112),
            line("( Lv.25 卧龙山小麦 )", 175, 137, 150, 16),
            line("微信56区白色死神", 610, 108),
            line("( Lv.30 卧龙山小当家 )", 625, 133, 150, 16),
        )

        assertEquals(
            listOf("莲动渔舟", "白色死神"),
            ServerListCapture.parse(candidates).map { it.serverName },
        )
        // 留下来的必须是**列表里那一行**（带等级 / 角色名的那条），不是表头药丸
        assertEquals("30", ServerListCapture.parse(candidates).first { it.serverName == "白色死神" }.level)
    }

    @Test
    fun mergeBlocksTheSameServerNoWithADifferentName() {
        // ★ 2026-09-25 真机事故的护栏：`395|莲动渔舟` 已在清单里，这次读到 `395|莲动渔`
        // ⇒ **绝不新增**（同一个平台 + 同一个区号只有一台小号），只报冲突让人复核。
        // 真机后果（护栏前）：清单里两条并存，顺序轮换选中缺字那条 ⇒ 第 5 步永远找不到它。
        val existing = ServerList(listOf(ServerEntry(ServerPlatform.WECHAT, "395", "莲动渔舟", "", "")))
        val captured = ServerListCapture.parse(
            listOf(line("微信395区 莲动渔", 160, 110), line("( Lv.25 卧龙山小麦 )", 175, 135, 150, 16)),
        )

        val merge = ServerListCapture.merge(existing, captured)

        assertTrue("同区号不同名字不得新增", merge.added.isEmpty())
        assertEquals(existing, merge.list)
        assertEquals(1, merge.conflicts.size)
        assertEquals("莲动渔", merge.conflicts.single().captured.serverName)
        assertEquals("莲动渔舟", merge.conflicts.single().existing.serverName)
    }

    @Test
    fun mergeStillAddsWhenTheCapturedRowHasNoServerNo() {
        // 区号没读到就没法用护栏判 ⇒ 按新增处理（报告里会列出来，人一眼能看出不对劲）
        val existing = ServerList(listOf(ServerEntry(ServerPlatform.WECHAT, "395", "莲动渔舟", "", "")))
        val merge = ServerListCapture.merge(existing, listOf(ServerEntry(serverName = "机仆阵列")))

        assertEquals(listOf("机仆阵列"), merge.added.map { it.serverName })
        assertTrue(merge.conflicts.isEmpty())
    }

    @Test
    fun mergeReportsNothingToDoWhenEverythingAlreadyKnown() {
        val existing = ServerList(listOf(ServerEntry(ServerPlatform.WECHAT, "395", "莲动渔舟", "卧龙山小麦", "Lv.25")))
        val merge = ServerListCapture.merge(existing, ServerListCapture.parse(listOf(line("微信395区 莲动渔舟", 160, 110))))

        assertTrue(merge.added.isEmpty())
        assertTrue(merge.filled.isEmpty())
        assertEquals(listOf("莲动渔舟"), merge.skipped)
        assertEquals(existing, merge.list)
    }
}
