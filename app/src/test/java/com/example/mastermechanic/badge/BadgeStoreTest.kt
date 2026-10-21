package com.example.mastermechanic.badge

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BadgeStore]：跨日清空（读侧按今天过滤）、按码 upsert、收集完成标记、坏文件当空。 */
class BadgeStoreTest {

    private fun tempFile(): File =
        File(Files.createTempDirectory("mm-badge-store").toFile(), "records.txt")

    private val today: LocalDate = LocalDate.of(2026, 10, 21)
    private val yesterday: LocalDate = LocalDate.of(2026, 10, 20)

    private fun record(
        server: String = "发条权杖",
        code: String = "1M09G2",
        score: Int = 877,
        on: LocalDate = today,
    ) = BadgeRecord(server, "墨子顶级立牌", code, score, on)

    @Test
    fun loadMissingFileIsEmpty() {
        val data = BadgeStore.load(tempFile(), today)
        assertEquals(BadgeData(), data)
    }

    @Test
    fun upsertThenLoad() {
        val file = tempFile()
        BadgeStore.upsert(file, record(), today)
        val data = BadgeStore.load(file, today)
        assertEquals(listOf(record()), data.records)
    }

    @Test
    fun upsertSameCodeReplaces() {
        val file = tempFile()
        BadgeStore.upsert(file, record(score = 100), today)
        BadgeStore.upsert(file, record(score = 200), today)
        val data = BadgeStore.load(file, today)
        assertEquals(1, data.records.size)
        assertEquals(200, data.records.single().score)
    }

    @Test
    fun upsertKeepsDifferentCodesForSameServer() {
        val file = tempFile()
        BadgeStore.upsert(file, record(code = "AAAA11"), today)
        BadgeStore.upsert(file, record(code = "BBBB22"), today)
        assertEquals(2, BadgeStore.load(file, today).records.size)
    }

    @Test
    fun loadFiltersStaleByDate() {
        val file = tempFile()
        BadgeStore.upsert(file, record(on = yesterday), today = yesterday)
        assertTrue(BadgeStore.load(file, yesterday).records.isNotEmpty())
        // 跨日：昨天的条目读不出来（FR-T4 次日 0 点清数据）
        assertTrue(BadgeStore.load(file, today).records.isEmpty())
    }

    @Test
    fun markCollectedAndHasCollected() {
        val file = tempFile()
        assertFalse(BadgeStore.hasCollected(file, "发条权杖", today))
        BadgeStore.markCollected(file, "发条权杖", today)
        assertTrue(BadgeStore.hasCollected(file, "发条权杖", today))
        assertFalse(BadgeStore.hasCollected(file, "莲动渔舟", today))
    }

    @Test
    fun markCollectedIsIdempotent() {
        val file = tempFile()
        BadgeStore.markCollected(file, "发条权杖", today)
        BadgeStore.markCollected(file, "发条权杖", today)
        assertEquals(1, BadgeStore.load(file, today).collected.size)
    }

    @Test
    fun collectedMarkerExpiresNextDay() {
        val file = tempFile()
        BadgeStore.markCollected(file, "发条权杖", yesterday)
        assertFalse(BadgeStore.hasCollected(file, "发条权杖", today))
    }

    @Test
    fun corruptFileLoadsEmptyWithoutThrowing() {
        val file = tempFile()
        file.writeText("这不是立牌池文件", Charsets.UTF_8)
        assertEquals(BadgeData(), BadgeStore.load(file, today))
    }

    @Test
    fun upsertAfterCorruptFileOverwrites() {
        val file = tempFile()
        file.writeText("坏掉的内容", Charsets.UTF_8)
        BadgeStore.upsert(file, record(), today)
        assertEquals(listOf(record()), BadgeStore.load(file, today).records)
    }
}
