package com.example.mastermechanic.servers

import com.example.mastermechanic.patrol.PatrolFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * 服务器「顺序轮换」游标单测（M4-T4-9 补齐，2026-09-24）：
 * 纯逻辑（下一个是谁 + 三种"从头开始"的情形）与文件读写分开覆盖，全部 JVM 可跑。
 */
class ServerRotationTest {

    private fun tempDir(): File = Files.createTempDirectory("mm-server-rotation").toFile()

    private val first = ServerEntry(ServerPlatform.WECHAT, "392", "发条权杖", "阿明", "Lv.9")
    private val second = ServerEntry(serverName = "莲动渔舟")
    private val third = ServerEntry(serverName = "碧海之眼")
    private val list = ServerList(listOf(first, second, third))

    @Test
    fun emptyListHasNoNext() {
        assertNull("清单为空就没有「下一个」", ServerRotation.nextOf(ServerList.EMPTY, "莲动渔舟"))
    }

    @Test
    fun noCursorStartsFromTheFirstEntry() {
        // 第一次跑（还没记过"当前在哪"）⇒ 从清单第一条开始
        assertEquals(first, ServerRotation.nextOf(list, null))
    }

    @Test
    fun unknownCursorFallsBackToTheFirstEntry() {
        // 游标那个名字已经不在清单里（被删 / 改名）⇒ 从第一条开始，绝不按旧下标硬套
        assertEquals(first, ServerRotation.nextOf(list, "已经被删掉的区服"))
    }

    @Test
    fun cursorMovesToTheNextEntry() {
        assertEquals(second, ServerRotation.nextOf(list, "发条权杖"))
    }

    @Test
    fun lastEntryWrapsAroundToTheFirst() {
        // "轮流"的语义就是转圈：末条的下一个是第一条
        assertEquals(first, ServerRotation.nextOf(list, "碧海之眼"))
    }

    @Test
    fun cursorFileRoundTripAndOverwrite() {
        val file = File(tempDir(), "servers/cursor.txt")
        assertNull("没有文件 = 没有游标", ServerRotation.loadCursor(file))

        ServerRotation.saveCursor(file, "莲动渔舟")
        assertEquals("莲动渔舟", ServerRotation.loadCursor(file))

        ServerRotation.saveCursor(file, "碧海之眼") // 覆盖写
        assertEquals("碧海之眼", ServerRotation.loadCursor(file))
        assertFalse("临时文件必须已被 rename 消费掉", File(file.parentFile, "cursor.txt.tmp").exists())
    }

    @Test
    fun corruptedCursorFileMeansNoCursor() {
        // 游标是**派生状态**（不是用户数据）：坏了就当作"没有游标"（从第一条开始），
        // 但**不静默**——loadCursor 里留一行告警。这里断言"不抛、不挡流程"。
        val file = File(tempDir(), "servers/cursor.txt")
        file.parentFile?.mkdirs()
        file.writeText("随便写点什么\n", Charsets.UTF_8)
        assertNull(ServerRotation.loadCursor(file))

        file.writeText("format=mm-server-cursor\nversion=1\nserverName=\n", Charsets.UTF_8)
        assertNull("空名字等于没有游标", ServerRotation.loadCursor(file))
    }

    @Test
    fun resolveNextReadsListAndCursorAndSaysWhy() {
        val dir = tempDir()
        val listFile = File(dir, "servers/list.txt")
        val cursorFile = File(dir, "servers/cursor.txt")
        ServerListStore.save(listFile, list)

        // ① 第一次跑：没有游标 ⇒ 清单第一条（= 发条权杖），并在 detail 里说清"从第一条开始"
        val firstResult = ServerRotation.resolveNext(listFile, cursorFile)
        assertTrue("应当是 Next：$firstResult", firstResult is ServerRotation.Resolution.Next)
        val firstPick = firstResult as ServerRotation.Resolution.Next
        assertEquals("发条权杖", firstPick.serverName)
        assertTrue("应说明原因：${firstPick.detail}", firstPick.detail.contains("第一条"))

        // ② 上次换到过「莲动渔舟」⇒ 取它的下一条
        ServerRotation.saveCursor(cursorFile, "莲动渔舟")
        val nextPick = ServerRotation.resolveNext(listFile, cursorFile) as ServerRotation.Resolution.Next
        assertEquals("碧海之眼", nextPick.serverName)
        assertTrue("应带上上一条是谁：${nextPick.detail}", nextPick.detail.contains("莲动渔舟"))

        // ③ 上次换到的是末条 ⇒ 回绕到第一条
        ServerRotation.saveCursor(cursorFile, "碧海之眼")
        val wrapped = ServerRotation.resolveNext(listFile, cursorFile) as ServerRotation.Resolution.Next
        assertEquals("发条权杖", wrapped.serverName)
        assertTrue("应说明是回绕：${wrapped.detail}", wrapped.detail.contains("回绕"))
    }

    @Test
    fun resolveNextReportsMissingAndEmptyList() {
        val dir = tempDir()
        val listFile = File(dir, "servers/list.txt")
        val cursorFile = File(dir, "servers/cursor.txt")

        // 清单还没建 ⇒ Failed（如实说原因，不当作"没有下一个"）
        val missing = ServerRotation.resolveNext(listFile, cursorFile)
        assertTrue(missing is ServerRotation.Resolution.Failed)
        assertTrue(
            "应说明是缺清单：${(missing as ServerRotation.Resolution.Failed).reason}",
            missing.reason.contains("还没有服务器清单"),
        )

        // 清单存在但为空 ⇒ EmptyList（"没有下一个"是明确的结论，不是错误）
        ServerListStore.save(listFile, ServerList.EMPTY)
        assertEquals(ServerRotation.Resolution.EmptyList, ServerRotation.resolveNext(listFile, cursorFile))
    }

    // ---------------------------------------------------------------- U8：跨日重置 / 推进判据

    private val day1 = LocalDate.of(2026, 10, 19)
    private val day2 = LocalDate.of(2026, 10, 20)

    @Test
    fun theStepNumberMatchesTheFlow() {
        // 本文件不依赖 patrol 包（纯数据层），所以"第 5 步"是常量 —— 靠这条钉住两边一致。
        // 漂移的后果很隐蔽：判据里的 5 与 Step.PICK_SERVER 对不上 ⇒ 轮换与手选**都不再推进游标**。
        assertEquals(
            "ServerRotation.PICK_SERVER_STEP 必须等于 PatrolFlow.Step.PICK_SERVER.number",
            PatrolFlow.Step.PICK_SERVER.number,
            ServerRotation.PICK_SERVER_STEP,
        )
    }

    @Test
    fun cursorWrittenOnAnotherDayCountsAsNoCursor() {
        // U8-4：跨日 ⇒ 从清单第一条开始，并把"跨日重置"写进说明（进日志）。
        val dir = tempDir()
        val listFile = File(dir, "servers/list.txt")
        val cursorFile = File(dir, "servers/cursor.txt")
        ServerListStore.save(listFile, list)

        ServerRotation.saveCursor(cursorFile, "莲动渔舟", on = day1)

        // 同一天：照常取它的下一条
        val sameDay = ServerRotation.resolveNext(listFile, cursorFile, today = day1)
        assertTrue("应当是 Next：$sameDay", sameDay is ServerRotation.Resolution.Next)
        assertEquals("碧海之眼", (sameDay as ServerRotation.Resolution.Next).serverName)

        // 跨日：游标视为空 ⇒ 清单第一条
        val nextDay = ServerRotation.resolveNext(listFile, cursorFile, today = day2)
        assertTrue("应当是 Next：$nextDay", nextDay is ServerRotation.Resolution.Next)
        val pick = nextDay as ServerRotation.Resolution.Next
        assertEquals("发条权杖", pick.serverName)
        assertTrue("应写明跨日重置：${pick.detail}", pick.detail.contains("跨日重置"))
        assertTrue("应说明从第一条开始：${pick.detail}", pick.detail.contains("第一条"))

        // 只看游标侧：名字为空、原因说得清
        val read = ServerRotation.readCursor(cursorFile, today = day2)
        assertNull("跨日 ⇒ 名字不返回", read.serverName)
        assertTrue("应说明原因：${read.detail}", read.detail?.contains("跨日") == true)
    }

    @Test
    fun twoRunsOnTheSameDayKeepAdvancing() {
        // U8-5：同一天内游标照常推进 —— 日期判定绝不能把它误重置（那会让"轮流"永远停在第一条）。
        val dir = tempDir()
        val listFile = File(dir, "servers/list.txt")
        val cursorFile = File(dir, "servers/cursor.txt")
        ServerListStore.save(listFile, list)

        val first = ServerRotation.resolveNext(listFile, cursorFile, today = day1)
            as ServerRotation.Resolution.Next
        assertEquals("发条权杖", first.serverName)
        ServerRotation.saveCursor(cursorFile, first.serverName, on = day1)

        val second = ServerRotation.resolveNext(listFile, cursorFile, today = day1)
            as ServerRotation.Resolution.Next
        assertEquals("莲动渔舟", second.serverName)
        ServerRotation.saveCursor(cursorFile, second.serverName, on = day1)

        val third = ServerRotation.resolveNext(listFile, cursorFile, today = day1)
            as ServerRotation.Resolution.Next
        assertEquals("碧海之眼", third.serverName)
    }

    @Test
    fun legacyCursorWithoutADateIsTreatedAsNoCursor() {
        // U8 之前写的游标没有日期行：分不清是不是今天写的 ⇒ 当作没有游标（代价只是多从第一条起一次）
        val file = File(tempDir(), "servers/cursor.txt")
        file.parentFile?.mkdirs()
        file.writeText("format=mm-server-cursor\nversion=1\nserverName=莲动渔舟\n", Charsets.UTF_8)

        assertNull(ServerRotation.loadCursor(file))
        assertTrue(
            "应说明是旧版没有日期：${ServerRotation.readCursor(file).detail}",
            ServerRotation.readCursor(file).detail?.contains("没有日期") == true,
        )
    }

    @Test
    fun aCorruptedDateIsTreatedAsNoCursor() {
        // 有日期行但读不出来（手改 / 截断）：不拿它去轮换，并如实说原因（不静默）
        val file = File(tempDir(), "servers/cursor.txt")
        file.parentFile?.mkdirs()
        file.writeText(
            "format=mm-server-cursor\nversion=2\nserverName=莲动渔舟\nsavedOn=2026-13-40\n",
            Charsets.UTF_8,
        )

        assertNull(ServerRotation.loadCursor(file))
        assertTrue(
            "应说明日期读不出来：${ServerRotation.readCursor(file).detail}",
            ServerRotation.readCursor(file).detail?.contains("读不出来") == true,
        )
    }

    @Test
    fun cursorAdvancesOnlyWhenTheServerPickReallySucceeded() {
        // U8-8：判据**不看来路**（轮换算出来的 / 手选的都一样），只看"第 5 步真的走出去了 + 目标名非空"。
        val pick = ServerRotation.PICK_SERVER_STEP
        assertTrue("走出第 5 步 ⇒ 推进", ServerRotation.switchedTo(pick, pick + 1, "莲动渔舟"))
        assertFalse("还在第 5 步（没换成功）⇒ 不推进", ServerRotation.switchedTo(pick, pick, "莲动渔舟"))
        assertFalse("流程结束（中止）⇒ 不推进", ServerRotation.switchedTo(pick, null, "莲动渔舟"))
        assertFalse("没有目标名 ⇒ 不推进", ServerRotation.switchedTo(pick, pick + 1, null))
        assertFalse("空名字 ⇒ 不推进", ServerRotation.switchedTo(pick, pick + 1, "  "))
        assertFalse(
            "不是从选服那一步出来的 ⇒ 不推进",
            ServerRotation.switchedTo(pick - 1, pick + 1, "莲动渔舟"),
        )
    }
}
