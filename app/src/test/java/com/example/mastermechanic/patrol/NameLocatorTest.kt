package com.example.mastermechanic.patrol

import com.example.mastermechanic.patrol.NameLocator.Candidate
import com.example.mastermechanic.patrol.NameLocator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名称定位：两条真实路径**都是"去空白 → 取语义字段 → 全等"**（2026-09-24 用户拍板，口径统一）。
 *
 * - [NameLocator.locateRealmName]：区服（第 5 步）—— 取第一个「区」**之后**那一段；
 *   **0 个 / ≥2 个都算未找到**（FR-04 硬性要求 3，进错区服不可逆）；
 * - [NameLocator.friendRemarkHits]：好友（第 9 步）—— 取括号里的**备注名**，返回**全部命中**
 *   （多个同名 ⇒ 由编排层取最上面那条，2026-09-30 用户口径，见 `requirements.md`「口径修订十」）；
 * - [NameLocator.locate]：上面两条共用的**底层原语**（整条候选文本全等），本文件第一段直接钉它。
 *
 * ⚠ 曾经的"包含"与"忽略符号"两档已于 2026-09-24 删除：包含会把**半行**当整行命中
 * （`微信399区 龙腾盛世` ⊇ `龙腾`），而忽略符号会把两个不同名字变成同一个 ⇒ 误判唯一 ⇒ 点错。
 * 现在读歪一个字就是"未找到 ⇒ 停下"（红线 3）—— **这条 2026-09-30 也没有放宽**。
 */
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
        // 中间空格被删掉的名字**不该**被当成命中（底层原语只 trim；去空白是两条真实路径的输入形态）
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
        // 2026-10-01 用户拍板：由 10 改为 **5 屏**（与 ServerListScan.MAX_SCROLLS 同一口径）
        assertEquals(5, NameLocator.MAX_SCROLLS)
    }

    // ---------- 区服（第 5 步）：取「区」之后 + 全等（T4-9，2026-09-24 用户口径方案 A） ----------

    @Test
    fun theRealmNameIsTakenFromAfterTheFirstAreaMarker() {
        // 真机屏幕行：`微信426区 战利品`，而清单里配的是区服名 `战利品` ⇒ 取「区」之后那一段再全等
        val rows = candidates("微信426区 战利品", "微信408区 羽箭破空")
        assertEquals(0, (NameLocator.locateRealmName("战利品", rows) as Result.Found).payload)
    }

    @Test
    fun theWholeLineIsNeverAHit() {
        // 拿整行（或区号那半截）去配都不命中：比的是「区」之后那一段
        val rows = candidates("微信426区 战利品")
        assertTrue(NameLocator.locateRealmName("微信426区 战利品", rows) is Result.NotFound)
        assertTrue(NameLocator.locateRealmName("微信426区", rows) is Result.NotFound)
    }

    @Test
    fun shortRealmNamesAreSafeBecauseTheMatchIsExact() {
        // **不用"最小长度"闸的理由**：区服名有 2 / 3 字的（真机 `战利品`），长度闸会把合法名字一起误杀；
        // 全等的判别力来自"名字段必须逐字相同" —— 想防的误命中（`龙腾` 撞 `龙腾盛世`）由它自己防住
        val rows = candidates("微信399区 龙腾", "微信398区 龙腾盛世")
        assertEquals(0, (NameLocator.locateRealmName("龙腾", rows) as Result.Found).payload)
        assertEquals(1, (NameLocator.locateRealmName("龙腾盛世", rows) as Result.Found).payload)
        assertTrue(NameLocator.locateRealmName("龙腾世", rows) is Result.NotFound)
        assertTrue(NameLocator.locateRealmName("战", candidates("微信426区 战利品")) is Result.NotFound)
    }

    @Test
    fun realmSpacesAreDroppedOnBothSides() {
        // 真机同一批里 `微信395区 莲动渔舟`（区号后有空格）与 `微信56区白色死神`（没有）并存
        // ⇒ 不统一去空白，就会出现"有的行能对上、有的行对不上"
        val rows = candidates("微信395区 莲动渔舟")
        assertTrue(NameLocator.locateRealmName("莲动渔舟", rows) is Result.Found)
        assertTrue("两边都去空白 ⇒ 目标里的空格也不影响", NameLocator.locateRealmName("莲 动渔 舟", rows) is Result.Found)
        assertTrue(NameLocator.locateRealmName("白色死神", candidates("微信56区白色死神")) is Result.Found)
    }

    @Test
    fun aTruncatedRealmNameStopsInsteadOfClickingAnotherServer() {
        // 真机 OCR 少读一截（`微信424区 海鹰之羽` → `海鹰之`）⇒ **不命中 ⇒ 停下**（红线 3），
        // 由日志给出"读到的字段 vs 目标"的证据（不靠放宽成包含去凑命中）
        val rows = candidates("微信424区 海鹰之")
        assertTrue(NameLocator.locateRealmName("海鹰之羽", rows) is Result.NotFound)
    }

    @Test
    fun twoRowsWithTheSameRealmNameStop() {
        val rows = candidates("微信395区 莲动渔舟", "微信396区 莲动渔舟")
        val result = NameLocator.locateRealmName("莲动渔舟", rows)
        assertTrue(result is Result.NotFound)
        assertTrue((result as Result.NotFound).detail.contains("2 个"))
    }

    @Test
    fun blankRealmTargetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NameLocator.locateRealmName("   ", candidates("微信426区 战利品"))
        }
    }

    // ---------- 好友（第 9 步）：取括号内备注 + 全等；**多个同名 ⇒ 全部返回**（挑哪条在编排层） ----------

    @Test
    fun theFriendIsMatchedByTheRemarkInsideTheParentheses() {
        val rows = listOf(Candidate("克克洛儿(阿娜雅)", "克克洛儿"))
        val hits = NameLocator.friendRemarkHits("阿娜雅", rows)
        assertEquals(listOf("克克洛儿"), hits.map { it.payload })
    }

    @Test
    fun aFullWidthParenthesisWorksToo() {
        val rows = listOf(Candidate("克克洛儿（阿娜雅）", 1))
        assertEquals(1, NameLocator.friendRemarkHits("阿娜雅", rows).size)
    }

    @Test
    fun theNicknameOutsideTheParenthesesIsNoLongerAHit() {
        // 2026-09-24 口径：配的名字 = 游戏中的**备注名** ⇒ 拿括号前的昵称去配不再命中。
        // 这是**有意收紧**：宁可停下（用户改成备注名即可），也不靠"包含"把半行当整行命中。
        val rows = listOf(Candidate("克克洛儿(阿娜雅)", 1))
        assertTrue(NameLocator.friendRemarkHits("克克洛儿", rows).isEmpty())
    }

    @Test
    fun aRowWithoutParenthesesNeverMatches() {
        // 括号没读出来（或这一行本来就没有备注）⇒ 取不到字段 ⇒ **不命中 ⇒ 停下**，绝不拿整行去比
        val rows = candidates("克克洛儿", "搜好友", "拿取提醒")
        assertTrue(NameLocator.friendRemarkHits("克克洛儿", rows).isEmpty())
        assertTrue(NameLocator.friendRemarkHits("搜好友", rows).isEmpty())
    }

    @Test
    fun twoAccountsSharingOneRemarkAreBothReturned() {
        // 真机原样：阿娜雅是这位好友**两个小号**共用的备注（昵称分别是克克雨儿 / 克克洛儿）。
        // **2026-09-30 口径变更**（用户："当屏幕上识别到多个相同的好友时，去最上面的那个"）：
        // 这个原语**不再判"不唯一"**，而是把两条都交出去 —— 由 `NameLocating.locateFriend`
        // （它拿得到坐标）按 y 取最上面那条。
        val rows = listOf(
            Candidate("克克雨儿(阿娜雅)", 1),
            Candidate("克克洛儿(阿娜雅)", 2),
        )
        assertEquals(listOf(1, 2), NameLocator.friendRemarkHits("阿娜雅", rows).map { it.payload })
    }

    @Test
    fun aMisreadRemarkIsNotFoundNotTheWrongRow() {
        // 反向护栏（这条**没有**放宽）：另一位好友的备注被读成「阿哪雅」⇒ 备注字段与目标不等 ⇒ 未命中
        val rows = listOf(
            Candidate("新克克雨儿(阿哪雅)", 1),
            Candidate("价克克洛儿(阿哪雅)", 2),
        )
        assertTrue(NameLocator.friendRemarkHits("阿娜雅", rows).isEmpty())
    }

    @Test
    fun straySpacesInsideTheRemarkAreTolerated() {
        // OCR 常在字间插空格 / 换行；两边都去空白，否则"明明在这一行"会判成没找到
        val rows = listOf(Candidate("克克 雨儿(阿\n娜雅)", 1))
        assertEquals(1, NameLocator.friendRemarkHits("阿娜雅", rows).size)
    }

    @Test
    fun onlyTheLastParenthesesCountAsTheRemark() {
        // 昵称自己可能带括号 ⇒ 取**最后一个**左括号（与 FriendListOcrProbe.remarkIn 同一条规则）
        val rows = listOf(Candidate("小号(测试)(阿娜雅)", 1))
        assertEquals(1, NameLocator.friendRemarkHits("阿娜雅", rows).size)
        assertTrue(NameLocator.friendRemarkHits("测试", rows).isEmpty())
    }

    @Test
    fun blankRemarkTargetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NameLocator.friendRemarkHits("   ", listOf(Candidate("克克雨儿(阿娜雅)", 1)))
        }
    }
}
