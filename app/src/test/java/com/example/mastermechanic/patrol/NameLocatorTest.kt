package com.example.mastermechanic.patrol

import com.example.mastermechanic.patrol.NameLocator.Candidate
import com.example.mastermechanic.patrol.NameLocator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 名称定位：全等才命中；0 个或 ≥2 个都算未找到（FR-04 硬性要求 3）。 */
class NameLocatorTest {

    private fun candidates(vararg names: String): List<Candidate<Int>> =
        names.mapIndexed { index, name -> Candidate(name, index) }

    @Test
    fun findsTheUniqueMatch() {
        val result = NameLocator.locate("龙腾", candidates("卧龙山", "龙腾", "风云"))
        assertTrue(result is Result.Found)
        assertEquals(1, (result as Result.Found).payload)
    }

    @Test
    fun missingNameIsNotFound() {
        val result = NameLocator.locate("龙腾", candidates("卧龙山", "风云"))
        assertTrue(result is Result.NotFound)
        assertTrue((result as Result.NotFound).detail.contains("没找到"))
    }

    @Test
    fun duplicateNamesAreNotFoundNotTheFirstOne() {
        // 不唯一 → 未找到。**绝不能"看到第一个就点"** —— 那就是误点
        val result = NameLocator.locate("龙腾", candidates("龙腾", "龙腾"))
        assertTrue(result is Result.NotFound)
        assertTrue((result as Result.NotFound).detail.contains("2 个"))
    }

    @Test
    fun matchingIsExactNotFuzzy() {
        // 不做前缀、不做包含：配置写"龙腾"却点成"龙腾盛世"，是进错区服那一类事故
        assertTrue(NameLocator.locate("龙腾", candidates("龙腾盛世")) is Result.NotFound)
        assertTrue(NameLocator.locate("龙腾盛世", candidates("龙腾")) is Result.NotFound)
    }

    @Test
    fun trimsEndsButKeepsInnerSpaces() {
        val result = NameLocator.locate("星 月晚", candidates(" 星 月晚 "))
        assertTrue(result is Result.Found)
        // 中间空格被删掉的名字**不该**被当成命中（游戏里那个名字确实带空格）
        assertTrue(NameLocator.locate("星月晚", candidates("星 月晚")) is Result.NotFound)
    }

    @Test
    fun emptyTargetIsRejected() {
        assertTrue(runCatching { NameLocator.locate("  ", candidates("龙腾")) }.isFailure)
    }

    @Test
    fun searchingAcrossPagesStillRejectsDuplicates() {
        // 同一个名字在两屏各出现一次 —— 合起来看仍然不唯一，不能点
        val page1 = listOf(Candidate("龙腾", 0))
        val page2 = listOf(Candidate("风云", 1), Candidate("龙腾", 2))
        assertTrue(NameLocator.locateAcross(listOf(page1, page2), "龙腾") is Result.NotFound)
    }

    @Test
    fun findsNameThatOnlyAppearsOnALaterPage() {
        val page1 = listOf(Candidate("卧龙山", 0))
        val page2 = listOf(Candidate("龙腾", 1))
        val result = NameLocator.locateAcross(listOf(page1, page2), "龙腾")
        assertTrue(result is Result.Found)
        assertEquals(1, (result as Result.Found).payload)
    }

    @Test
    fun scrollLimitIsPinned() {
        // FR-04 硬性要求 4：滚动次数设上限，超出即中止（这个常量是给调用方的兜底）
        assertEquals(10, NameLocator.MAX_SCROLLS)
    }
}
