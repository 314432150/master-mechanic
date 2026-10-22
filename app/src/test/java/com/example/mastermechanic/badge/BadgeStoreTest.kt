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
    fun upsertMergesTheSameCardReadTwiceWithOcrNoise() {
        // 2026-10-22 真机：池里出现两张「刘备顶级立牌」（都是 981 分），实际是同一张牌被读了两次，
        // 两次 OCR 各错一点：名称 项/顶 差一个字，码 QGG4ZS 少读成 GG4Z9。
        // 按码去重挡不住 ⇒ 现在按「同区服 + 同分 + 名称差 ≤1 字 + 码差 ≤2 位」认成同一张。
        val file = tempFile()
        BadgeStore.upsert(file, BadgeRecord("云海航线", "刘备项级立牌", "QGG4ZS", 981, today), today)
        BadgeStore.upsert(file, BadgeRecord("云海航线", "刘备顶级立牌", "GG4Z9", 981, today), today)
        val records = BadgeStore.load(file, today).records
        assertEquals("同一张牌只能留一条", 1, records.size)
        val only = records.single()
        assertEquals("名称取新读的那条", "刘备顶级立牌", only.cardName)
        assertEquals("码取更完整（更长）的那条", "QGG4ZS", only.code)
    }

    @Test
    fun loadMergesDuplicatesAlreadyOnDisk() {
        // 盘上已经存在的重复条目要**当场**看不见（用户打开立牌池就是现在，不等下次写入）。
        // 两条码都合格（6 位、只差 1 位），才是纯粹的"合并"路径 —— 不合格的码走 dropMalformed。
        val file = tempFile()
        BadgeStore.save(
            file,
            BadgeData(
                records = listOf(
                    BadgeRecord("云海航线", "刘备项级立牌", "QGG4ZS", 981, today),
                    BadgeRecord("云海航线", "刘备顶级立牌", "QGG4ZT", 981, today),
                    BadgeRecord("云海航线", "元流之子(女)顶级立牌", "CTTYGL", 518, today),
                ),
            ),
        )
        val records = BadgeStore.load(file, today).records
        assertEquals(2, records.size)
        assertEquals(listOf("QGG4ZT", "CTTYGL"), records.map { it.code })
    }

    @Test
    fun differentCardsThatOnlyLookSimilarStaySeparate() {
        // 反向：同区服、不同分、不同码的两张牌不许被合并（误合并 = 丢一张真的牌）
        val file = tempFile()
        BadgeStore.upsert(file, BadgeRecord("云海航线", "刘备顶级立牌", "QGG4ZS", 981, today), today)
        BadgeStore.upsert(file, BadgeRecord("云海航线", "刘备顶级立牌", "ZZ9999", 518, today), today)
        assertEquals(2, BadgeStore.load(file, today).records.size)
    }

    @Test
    fun sameCardInDifferentServersStaysSeparate() {
        // 两个号各有一张「刘备顶级立牌」：区服不同 ⇒ 各自有价值，不许合并
        val file = tempFile()
        BadgeStore.upsert(file, BadgeRecord("云海航线", "刘备顶级立牌", "QGG4ZS", 981, today), today)
        BadgeStore.upsert(file, BadgeRecord("白色死神", "刘备顶级立牌", "AAAA11", 981, today), today)
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
