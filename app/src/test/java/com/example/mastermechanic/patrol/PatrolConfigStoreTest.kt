package com.example.mastermechanic.patrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 巡查配置存取单测（M3-T3-2 / T3-3 / ADR-006）：核心实现只吃 [File]，
 * 故"不存在 → null / 损坏 → 抛错 / 覆盖写 / 不留临时文件"全部可在 JVM 覆盖，无需安卓框架。
 */
class PatrolConfigStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("mm-patrol-store").toFile()

    private val a = PatrolItem("392区", "阿明")
    private val b = PatrolItem("418区", "小美")

    @Test
    fun saveThenLoadRoundTrip() {
        val file = File(tempDir(), "patrol/config.txt")
        PatrolConfigStore.save(file, PatrolConfig(listOf(a, b)))
        assertTrue(file.isFile)
        assertEquals(PatrolConfig(listOf(a, b)), PatrolConfigStore.load(file))
    }

    @Test
    fun loadMissingFileReturnsNull() {
        assertNull(PatrolConfigStore.load(File(tempDir(), "patrol/config.txt")))
    }

    @Test
    fun loadCorruptedFileThrowsInsteadOfSilentlyDegrading() {
        val file = File(tempDir(), "patrol/config.txt")
        file.parentFile?.mkdirs()
        // ① 格式标记不符（别的文件 / 被改过）
        file.writeText("format=mm-something-else\nversion=1\n", Charsets.UTF_8)
        val e1 = assertThrows(IllegalArgumentException::class.java) { PatrolConfigStore.load(file) }
        assertTrue(e1.message!!.contains("格式标记不符"))
        // ② 结构都不成立（随手写的内容）
        file.writeText("这不是巡查配置\n", Charsets.UTF_8)
        val e2 = assertThrows(IllegalArgumentException::class.java) { PatrolConfigStore.load(file) }
        assertTrue(e2.message!!.contains("第 1 行"))
    }

    @Test
    fun saveOverwritesExistingContentAndLeavesNoTempFile() {
        val file = File(tempDir(), "patrol/config.txt")
        PatrolConfigStore.save(file, PatrolConfig(listOf(a, b)))
        PatrolConfigStore.save(file, PatrolConfig(listOf(b))) // 覆盖写（含"整体清空"这类缩小写）
        assertEquals(PatrolConfig(listOf(b)), PatrolConfigStore.load(file))
        // 临时文件必须已被 rename 消费掉，不残留
        assertFalse(File(file.parentFile, "config.txt.tmp").exists())
    }

    @Test
    fun emptyConfigSurvivesRoundTrip() {
        val file = File(tempDir(), "patrol/config.txt")
        PatrolConfigStore.save(file, PatrolConfig.EMPTY)
        assertEquals(PatrolConfig.EMPTY, PatrolConfigStore.load(file))
    }
}
