package com.example.mastermechanic.patrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 5 步判据的单测（2026-09-25 用户口径：**区号为主、区名为辅**）。
 *
 * 行样例照抄真机：`微信395区 莲动渔舟`；清单 = 目标「莲动渔舟 / 395」+ 其它条目（含 `云海航线 / 425`），
 * 用来验证"这一行的名字是不是**别的**区服"这类矛盾检查。
 */
class RealmPickDecisionTest {

    private val lotus = RealmPickDecision.Entry(name = "莲动渔舟", number = "395")
    private val others = listOf(
        RealmPickDecision.Entry(name = "云海航线", number = "425"),
        RealmPickDecision.Entry(name = "火鹰重炮", number = "418"),
    )

    private fun row(
        line: String,
        centerX: Double = 100.0,
        centerY: Double = 200.0,
    ) = RealmPickDecision.Row(line = line, centerX = centerX, centerY = centerY)

    private fun hit(result: RealmPickDecision.Result): RealmPickDecision.Result.Hit {
        assertTrue("应当是命中，实际是 $result", result is RealmPickDecision.Result.Hit)
        return result as RealmPickDecision.Result.Hit
    }

    private fun miss(result: RealmPickDecision.Result): String {
        assertTrue("应当是拒识，实际是 $result", result is RealmPickDecision.Result.Miss)
        return (result as RealmPickDecision.Result.Miss).detail
    }

    @Test
    fun nameAndNumberBothComeFromTheSameLine() {
        val read = row("微信395区 莲动渔舟")
        assertEquals("莲动渔舟", read.name)
        assertEquals("395", read.number)
        assertNull("取不到数字 ⇒ 主判据对这一行不适用", row("选择服务器").number)
    }

    @Test
    fun numberIsThePrimaryJudgement() {
        // 区号命中 + 名字全等 ⇒ 最强
        val both = hit(RealmPickDecision.decide(listOf(row("微信395区 莲动渔舟")), lotus, others))
        assertEquals(RealmPickDecision.Via.NUMBER_AND_NAME, both.via)

        // 区号命中、名字**读错**（不像任何人）⇒ 区号为主，照样采纳
        val garbled = hit(RealmPickDecision.decide(listOf(row("微信395区 莲动渔〗")), lotus, others))
        assertEquals(RealmPickDecision.Via.NUMBER, garbled.via)
    }

    @Test
    fun numberRescuesARowWhoseNameLostOneCharacter() {
        // 真机形态 `莲动渔舟 → 莲动渔`：区号对 ⇒ 采纳（名字只作辅证）
        val result = hit(RealmPickDecision.decide(listOf(row("微信395区 莲动渔")), lotus, others))
        assertEquals(RealmPickDecision.Via.NUMBER, result.via)
    }

    @Test
    fun numberHitIsRefusedWhenTheNameBelongsToAnotherServer() {
        // 区号说是 395，名字却是清单里**另一个**区服（云海航线）⇒ 真矛盾 ⇒ 不猜
        val detail = miss(RealmPickDecision.decide(listOf(row("微信395区 云海航线")), lotus, others))
        assertTrue("要说清是矛盾、并点名是谁：$detail", detail.contains("矛盾") && detail.contains("云海航线"))
    }

    @Test
    fun twoRowsWithTheSameNumberAreRefused() {
        val detail = miss(
            RealmPickDecision.decide(
                listOf(row("微信395区 莲动渔", centerY = 200.0), row("微信395区 莲动渔", centerY = 260.0)),
                lotus,
                others,
            ),
        )
        assertTrue("应说明分不清：$detail", detail.contains("分不清"))
    }

    @Test
    fun exactNameIsTheFallbackWhenTheNumberIsUnavailable() {
        // 区号读不到（这一行只有名字）⇒ 退回旧口径：区名全等 + 唯一
        val result = hit(RealmPickDecision.decide(listOf(row("莲动渔舟")), lotus, others))
        assertEquals(RealmPickDecision.Via.NAME, result.via)
    }

    @Test
    fun exactNameIsRefusedWhenTheNumberPointsToAnotherServer() {
        // 名字全等，但同一行的区号是清单里别人的号 ⇒ 真矛盾 ⇒ 不猜
        val detail = miss(RealmPickDecision.decide(listOf(row("微信425区 莲动渔舟")), lotus, others))
        assertTrue("要说清是矛盾：$detail", detail.contains("矛盾"))
    }

    @Test
    fun exactNameStillWinsWhenTheNumberIsMerelyMisread() {
        // 区号读错一位（39S → 39），而那个号**不在清单里** ⇒ 名字仍然可信（旧行为不变）
        val result = hit(RealmPickDecision.decide(listOf(row("微信39S区 莲动渔舟")), lotus, others))
        assertEquals(RealmPickDecision.Via.NAME, result.via)
    }

    @Test
    fun nearMissNameIsNotAccepted() {
        // 区名"少读一个字"而区号又没读到时**不放宽**：两个字段都只有软证据 ⇒ 停下
        val detail = miss(RealmPickDecision.decide(listOf(row("莲动渔")), lotus, others))
        assertTrue("应说明没找到：$detail", detail.contains("都没在屏幕上找到"))
    }

    @Test
    fun twoRowsWithTheSameNameAreRefused() {
        val detail = miss(
            RealmPickDecision.decide(
                listOf(row("莲动渔舟", centerY = 200.0), row("莲动渔舟", centerY = 260.0)),
                lotus,
                others,
            ),
        )
        assertTrue("应说明分不清：$detail", detail.contains("分不清"))
    }

    @Test
    fun nothingMatchesIsAMiss() {
        val detail = miss(RealmPickDecision.decide(listOf(row("微信425区 云海航线")), lotus, others))
        assertTrue("应说明没找到：$detail", detail.contains("都没在屏幕上找到"))
    }

    @Test
    fun entryWithoutNumberFallsBackToName() {
        // 清单里这一条没配区号 ⇒ 主判据不适用，只走区名（与旧行为一致）
        val noNumber = RealmPickDecision.Entry(name = "莲动渔舟", number = "")
        val result = hit(RealmPickDecision.decide(listOf(row("微信395区 莲动渔舟")), noNumber, others))
        assertEquals(RealmPickDecision.Via.NAME, result.via)
    }

    @Test
    fun withinOneEditAcceptsOnlySingleCharacterMistakes() {
        assertTrue(RealmPickDecision.withinOneEdit("莲动渔", "莲动渔舟"))
        assertTrue(RealmPickDecision.withinOneEdit("义鹰重炮", "火鹰重炮"))
        assertTrue(RealmPickDecision.withinOneEdit("莲动渔舟", "莲动渔舟"))
        assertTrue("完全不相关 ⇒ 不算手误", !RealmPickDecision.withinOneEdit("云海航线", "莲动渔舟"))
        assertTrue("短名字差两个字 ⇒ 不算", !RealmPickDecision.withinOneEdit("战利", "战利品舱"))
    }
}
