package com.example.mastermechanic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 悬浮窗位置存取单测（M3-T3-4 / T3-7 / ADR-006）：核心只吃 [File]（JVM 可测）。
 *
 * 重点锁定 ADR-006 的"位置类数据分流"：**缺失 / 损坏 → 回落默认并重写文件**，
 * 同时把原因带回给调用方写日志（丢失无后果，但不得静默）。
 */
class FloatingPositionStoreTest {

    private val screenWidth = 1440
    private val screenHeight = 3168

    private val custom = FloatingPositions(
        handle = FloatingPosition(FloatingSide.LEFT, 0.5),
        label = LabelPosition(0.25, 0.75),
    )

    private fun tempFile(): File =
        File(Files.createTempDirectory("mm-floating-store").toFile(), "floating/window.txt")

    @Test
    fun saveThenLoadRoundTripWithoutRecovery() {
        val file = tempFile()
        FloatingPositionStore.save(file, custom, screenWidth, screenHeight)
        val result = FloatingPositionStore.loadOrRecover(file, screenWidth, screenHeight)
        assertEquals(custom, result.positions)
        assertNull(result.recoveredReason)
        assertFalse(File(file.parentFile, "window.txt.tmp").exists())
    }

    @Test
    fun version1FileIsStillReadable() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText(
            "format=mm-floating\nversion=1\nside=left\ny-ratio=0.2\nscreen=1440x3168\n",
            Charsets.UTF_8,
        )
        val result = FloatingPositionStore.loadOrRecover(file, screenWidth, screenHeight)
        // 旧文件不算"损坏"：不触发回落，手柄照读、标签取默认
        assertNull(result.recoveredReason)
        assertEquals(FloatingPosition(FloatingSide.LEFT, 0.2), result.positions.handle)
        assertEquals(LabelPosition.DEFAULT, result.positions.label)
    }

    @Test
    fun missingFileFallsBackToDefaultAndRewrites() {
        val file = tempFile()
        val result = FloatingPositionStore.loadOrRecover(file, screenWidth, screenHeight)
        assertEquals(FloatingPositions.DEFAULT, result.positions)
        assertTrue(result.recoveredReason!!.contains("不存在"))
        // 回落必须顺手重建文件，否则每轮都"缺失"（日志噪音 + 位置永远不生效）
        assertTrue(file.isFile)
        assertNull(FloatingPositionStore.loadOrRecover(file, screenWidth, screenHeight).recoveredReason)
    }

    @Test
    fun corruptedFileFallsBackToDefaultAndRewrites() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText("handle-side=nonsense\n", Charsets.UTF_8)
        val result = FloatingPositionStore.loadOrRecover(file, screenWidth, screenHeight)
        assertEquals(FloatingPositions.DEFAULT, result.positions)
        assertTrue(result.recoveredReason!!.contains("不可用"))
        assertNull(FloatingPositionStore.loadOrRecover(file, screenWidth, screenHeight).recoveredReason)
    }

    @Test
    fun resetWritesDefaultPositions() {
        val file = tempFile()
        FloatingPositionStore.save(file, custom, screenWidth, screenHeight)
        FloatingPositionStore.save(file, FloatingPositions.DEFAULT, screenWidth, screenHeight)
        val result = FloatingPositionStore.loadOrRecover(file, screenWidth, screenHeight)
        assertEquals(FloatingPositions.DEFAULT, result.positions)
        assertNull(result.recoveredReason)
    }

    @Test
    fun loadOrNullDoesNotCreateOrRepair() {
        val missing = tempFile()
        assertNull(FloatingPositionStore.loadOrNull(missing))
        assertFalse(missing.exists())

        val broken = tempFile()
        broken.parentFile?.mkdirs()
        broken.writeText("垃圾内容\n", Charsets.UTF_8)
        assertNull(FloatingPositionStore.loadOrNull(broken))
        // 界面只读；不写盘（修复由挂载路径负责）
        assertEquals("垃圾内容\n", broken.readText(Charsets.UTF_8))
    }
}
