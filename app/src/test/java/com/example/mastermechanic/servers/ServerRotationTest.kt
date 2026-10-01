package com.example.mastermechanic.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
}
