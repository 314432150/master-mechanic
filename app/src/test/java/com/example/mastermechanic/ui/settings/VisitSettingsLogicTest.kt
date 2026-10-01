package com.example.mastermechanic.ui.settings

import com.example.mastermechanic.preset.ServerChoice
import com.example.mastermechanic.preset.VisitPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「拜访设置」页的判据（M5-U3）。
 *
 * 最要紧的一条是**不变量**：本页判"能不能保存"的结果必须与 `VisitPreset.isReady` 完全一致
 * —— 两处漂移就会出现"界面说可以保存、保存下去却跑不了"。
 */
class VisitSettingsLogicTest {

    @Test
    fun thePageRefusesExactlyWhatTheLauncherWouldRefuse() {
        val cases = listOf(
            // 顺序轮换不需要区服名（它是**合法策略**，不是"没选"）
            VisitPreset(ServerChoice.NEXT, "", "阿娜雅") to VisitSettingsLogic.Blocked.NONE,
            // 固定区服：区服名必填
            VisitPreset(ServerChoice.FIXED, "莲动渔舟", "阿娜雅") to VisitSettingsLogic.Blocked.NONE,
            // 好友必填（不知道去见谁，这条预设没有意义）
            VisitPreset(ServerChoice.NEXT, "", "") to VisitSettingsLogic.Blocked.FRIEND_MISSING,
            VisitPreset(ServerChoice.FIXED, "莲动渔舟", "") to VisitSettingsLogic.Blocked.FRIEND_MISSING,
            // 切到「固定区服」但还没选：草稿状态是合法的（构造时不抛），只是**不能保存**
            VisitPreset(ServerChoice.FIXED, "", "阿娜雅") to VisitSettingsLogic.Blocked.SERVER_MISSING,
        )
        cases.forEach { (preset, expected) ->
            assertEquals("$preset ⇒ 应判 $expected", expected, VisitSettingsLogic.blockedReason(preset))
            // ★ 不变量：NONE ⇔ isReady（判据只有 `VisitPreset` 那一处，本页不另写一套）
            assertEquals(
                "$preset：本页判「可以保存」必须等价于 isReady",
                preset.isReady,
                VisitSettingsLogic.blockedReason(preset) == VisitSettingsLogic.Blocked.NONE,
            )
        }
    }

    @Test
    fun switchingStrategyKeepsTheServerNameSoTheUserDoesNotReselectIt() {
        val fixed = VisitPreset(ServerChoice.FIXED, "莲动渔舟", "阿娜雅")
        val next = VisitSettingsLogic.withChoice(fixed, ServerChoice.NEXT)
        assertEquals("切到顺序轮换：区服名留着（它不参与执行，留着只省一步）", "莲动渔舟", next.serverName)
        assertTrue("留着不影响可执行性", next.isReady)
        assertEquals("切回来不用重选", "莲动渔舟", VisitSettingsLogic.withChoice(next, ServerChoice.FIXED).serverName)
    }

    @Test
    fun draftTrimsWhitespaceButNothingElse() {
        // 名字会被直接丢给界面定位（红线 3：全等 + 唯一）⇒ 除了首尾空白，**绝不改写**用户输入
        val preset = VisitSettingsLogic.draft(ServerChoice.NEXT, "  ", "  阿娜雅  ")
        assertEquals("阿娜雅", preset.friendName)
        assertEquals("只留空白 ⇒ 等于没选", "", preset.serverName)
        assertTrue(preset.isReady)
    }

    @Test
    fun theNameFormatRulesStillComeFromThePresetItself() {
        // 分隔符 / 换行由 `VisitPreset` 的 init 拦（本页不该自己再判一遍）
        val bad = runCatching { VisitSettingsLogic.draft(ServerChoice.NEXT, "", "阿|娜雅") }
        assertTrue("含分隔符的名字必须被拒（它是跨文件引用键）", bad.isFailure)
        assertFalse(runCatching { VisitSettingsLogic.draft(ServerChoice.NEXT, "", "阿\n娜雅") }.isSuccess)
    }
}
