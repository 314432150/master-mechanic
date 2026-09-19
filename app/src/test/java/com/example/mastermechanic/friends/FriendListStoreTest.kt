package com.example.mastermechanic.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 好友清单存取单测：核心只吃 [File]，所以这些语义在 JVM 上就能验完整
 * （不存在 → null、覆盖写、删除、解析失败抛错 = **不静默降级**）。
 */
class FriendListStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("mm-friends-test").toFile()

    private fun tempFile(): File = File(tempDir(), "list.txt")

    private fun list(vararg names: String) = FriendList(names.map { FriendEntry(it) })

    @Test
    fun missingFileLoadsAsNull() {
        assertNull(FriendListStore.load(tempFile()))
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val file = tempFile()
        FriendListStore.save(file, list("阿明", "张三"))
        assertEquals(listOf("阿明", "张三"), FriendListStore.load(file)?.entries?.map { it.name })
    }

    @Test
    fun saveOverwritesThePreviousContentCompletely() {
        val file = tempFile()
        FriendListStore.save(file, list("阿明", "张三", "李四"))
        FriendListStore.save(file, list("王五"))
        // 覆盖写必须**整个替换**，不能留下上一次的旧条目
        assertEquals(listOf("王五"), FriendListStore.load(file)?.entries?.map { it.name })
    }

    @Test
    fun emptyListCanBeSavedAndReadBack() {
        val file = tempFile()
        FriendListStore.save(file, FriendList.EMPTY)
        assertEquals(0, FriendListStore.load(file)?.size)
    }

    @Test
    fun corruptFileThrowsInsteadOfReturningEmpty() {
        val file = tempFile()
        file.writeText("format=mm-friends\nversion=1\nitem=阿明\nitem=阿明\n", Charsets.UTF_8)
        // 损坏不静默降级（ADR-006）：清单丢了顶多是重录，静默变空会让用户以为"真的只有这些"
        assertTrue(runCatching { FriendListStore.load(file) }.isFailure)
    }

    @Test
    fun deleteRemovesTheFile() {
        val file = tempFile()
        FriendListStore.save(file, list("阿明"))
        assertTrue(file.delete())
        assertNull(FriendListStore.load(file))
    }

    @Test
    fun halfWrittenFilesAreNotLeftBehind() {
        val file = tempFile()
        FriendListStore.save(file, list("阿明"))
        val leftovers = file.parentFile?.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        // 临时文件落地后必须被 rename 掉：半写文件一旦被读到就是"清单自己坏了一半"
        assertTrue(leftovers.isEmpty())
    }
}
