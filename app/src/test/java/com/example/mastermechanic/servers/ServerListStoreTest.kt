package com.example.mastermechanic.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 服务器清单存取单测（M3-T3-9 / ADR-006）：核心实现只吃 [File]，
 * 故"不存在 → null / 损坏 → 抛错 / 覆盖写 / 不留临时文件"全部可在 JVM 覆盖，无需安卓框架。
 */
class ServerListStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("mm-server-list").toFile()

    private val a = ServerEntry(ServerPlatform.WECHAT, "392", "微信392区", "阿明", "Lv.50")
    private val b = ServerEntry(serverName = "418区") // 平台 / 区号 / 角色名 / 等级留空

    @Test
    fun saveThenLoadRoundTrip() {
        val file = File(tempDir(), "servers/list.txt")
        ServerListStore.save(file, ServerList(listOf(a, b)))
        assertTrue(file.isFile)
        assertEquals(ServerList(listOf(a, b)), ServerListStore.load(file))
    }

    @Test
    fun loadMissingFileReturnsNull() {
        assertNull(ServerListStore.load(File(tempDir(), "servers/list.txt")))
    }

    @Test
    fun loadCorruptedFileThrowsInsteadOfSilentlyDegrading() {
        val file = File(tempDir(), "servers/list.txt")
        file.parentFile?.mkdirs()
        // ① 格式标记不符（别的文件 / 被改过）——行内容本身合法，才能走到格式标记这一层
        file.writeText("format=mm-patrol\nversion=2\nitem=wechat|392|微信392区|阿明|Lv.50\n", Charsets.UTF_8)
        val e1 = assertThrows(IllegalArgumentException::class.java) { ServerListStore.load(file) }
        assertTrue(e1.message!!.contains("格式标记不符"))
        // ② 结构都不成立（随手写的内容）
        file.writeText("这不是服务器清单\n", Charsets.UTF_8)
        val e2 = assertThrows(IllegalArgumentException::class.java) { ServerListStore.load(file) }
        assertTrue(e2.message!!.contains("第 1 行"))
    }

    @Test
    fun saveOverwritesExistingContentAndLeavesNoTempFile() {
        val file = File(tempDir(), "servers/list.txt")
        ServerListStore.save(file, ServerList(listOf(a, b)))
        ServerListStore.save(file, ServerList(listOf(b))) // 覆盖写（含"整体清空"这类缩小写）
        assertEquals(ServerList(listOf(b)), ServerListStore.load(file))
        // 临时文件必须已被 rename 消费掉，不残留
        assertFalse(File(file.parentFile, "list.txt.tmp").exists())
    }

    @Test
    fun emptyListSurvivesRoundTrip() {
        val file = File(tempDir(), "servers/list.txt")
        ServerListStore.save(file, ServerList.EMPTY)
        assertEquals(ServerList.EMPTY, ServerListStore.load(file))
    }
}
