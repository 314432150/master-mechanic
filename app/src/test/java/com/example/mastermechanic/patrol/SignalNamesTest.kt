package com.example.mastermechanic.patrol

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.calibration.CalibrationSignals
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「标志 / 锚点的中文说法」（2026-10-01 用户口径：悬浮窗与日志里别只甩 `hall_settings` 这种英文名）。
 *
 * 钉住四件事：**备注优先**、**用途标签兜底**、**「界面」的角色再兜底**、**没有产物就退回 ID**；
 * 以及写入端要填的**默认描述**（锚点 = 用途标签；标志 = `「<界面>」的界面标志`）。
 */
class SignalNamesTest {

    private val params = MatchParams(0.85, 0.10, 5)

    private fun upsert(
        current: CalibrationData?,
        id: String,
        state: UiState,
        role: SignalRole,
        purpose: String? = null,
        note: String = "",
        fallbackNote: String = "",
    ): CalibrationData = CalibrationSignals.upsert(
        current = current,
        id = id,
        state = state,
        window = SearchWindow(0.1, 0.2, 0.5, 0.6),
        template = Template(10, 8, SyntheticImages.pattern(10, 8, 1L)),
        params = params,
        frameWidth = 1000,
        frameHeight = 800,
        role = role,
        purpose = purpose,
        note = note,
        fallbackNote = fallbackNote,
    )

    @Test
    fun userNoteWins() {
        val data = upsert(null, "hall_settings", UiState.HALL, SignalRole.ANCHOR, "hall_settings", note = "我自己的话")
        assertEquals("我自己的话", SignalNames.of(data, "hall_settings"))
    }

    @Test
    fun purposeLabelIsTheFallback() {
        // 没有备注 ⇒ 用用途表里的中文标签（`PatrolAnchors.purposesFor(HALL)` 里那一项）
        val data = upsert(null, "hall_settings", UiState.HALL, SignalRole.ANCHOR, "hall_settings")
        assertEquals("设置入口", SignalNames.of(data, "hall_settings"))
    }

    @Test
    fun markerWithoutPurposeFallsBackToStateAndRole() {
        // 纯标志（设置页的槽位标志）：没有备注、也没有用途 ⇒ 至少要能念出"哪一屏的什么"
        val data = upsert(null, "hall_settings_e1", UiState.HALL_SETTINGS, SignalRole.MARKER)
        assertEquals("「设置页」的标志", SignalNames.of(data, "hall_settings_e1"))
    }

    @Test
    fun withoutArtifactTheIdIsAllWeHave() {
        assertEquals("hall_settings", SignalNames.of(null, "hall_settings"))
    }

    @Test
    fun defaultNoteForAnchorsIsThePurposeLabel() {
        assertEquals(
            "设置入口",
            SignalNames.defaultNoteFor(UiState.HALL, SignalRole.ANCHOR, "hall_settings"),
        )
    }

    @Test
    fun defaultNoteForMarkersSaysWhichScreenItRecognises() {
        assertEquals(
            "「设置页」的界面标志",
            SignalNames.defaultNoteFor(UiState.HALL_SETTINGS, SignalRole.MARKER, null),
        )
    }

    @Test
    fun anchorsWithoutPurposeSayWhatTheyDo() {
        // 用户 2026-10-01 第二度订正："新手引导 / 新手大厅 / 活动弹窗的锚点也要改成锚点用途"。
        // 这三屏 `purposesFor` 是**空表**（全屏只有一个控件、自动关闭按状态取它，见其说明）⇒
        // 备注就说清它是"关闭控件"，而不是干巴巴的「界面」的锚点。
        assertEquals(
            "「活动弹窗」的关闭控件",
            SignalNames.defaultNoteFor(UiState.ACTIVITY_POPUP, SignalRole.ANCHOR, null),
        )
        assertEquals(
            "「新手引导」的关闭控件",
            SignalNames.defaultNoteFor(UiState.TUTORIAL_GUIDE, SignalRole.ANCHOR, null),
        )
        assertEquals(
            "「新手大厅」的关闭控件",
            SignalNames.defaultNoteFor(UiState.TUTORIAL_HALL, SignalRole.ANCHOR, null),
        )
        // 有用途表、这条却没选用途 ⇒ 如实说，不替用户猜一个用途
        assertEquals(
            "「大厅」的锚点",
            SignalNames.defaultNoteFor(UiState.HALL, SignalRole.ANCHOR, null),
        )
        // 选了用途 ⇒ 用途标签优先于上面那句
        assertEquals(
            "设置入口",
            SignalNames.defaultNoteFor(UiState.HALL, SignalRole.ANCHOR, "hall_settings"),
        )
    }

    @Test
    fun defaultNoteForIsPerRecordNotPerElement() {
        // 回归（2026-10-01 用户报"产物锚点的备注改错了"）：一次框选同时写标志 + 锚点时，
        // 锚点的备注**不能**是「界面标志」那句 —— 标志没有用途，锚点必须按自己的角色派生。
        assertEquals(
            "「活动弹窗」的关闭控件",
            SignalNames.defaultNoteFor(UiState.ACTIVITY_POPUP, SignalRole.ANCHOR, null),
        )
        assertEquals(
            "「活动弹窗」的界面标志",
            SignalNames.defaultNoteFor(UiState.ACTIVITY_POPUP, SignalRole.MARKER, null),
        )
        // 老规则（只看元素有哪些角色）就是错的来源：它给锚点返回的是标志那句
        assertEquals(
            "「活动弹窗」的界面标志",
            SignalNames.legacyDefaultNoteFor(UiState.ACTIVITY_POPUP, listOf(SignalRole.MARKER, SignalRole.ANCHOR), null),
        )
        // 两版自动文本都在"认得出"的集合里（否则已落盘的旧句子改不掉）
        val history = SignalNames.autoNoteHistory(UiState.ACTIVITY_POPUP, listOf(SignalRole.ANCHOR), null)
        assertTrue("第一版的串味句", "「活动弹窗」的界面标志" in history)
        assertTrue("第二版的兜底句", "「活动弹窗」的锚点" in history)
    }

    @Test
    fun upsertKeepsThePrecedenceHandTypedThenOldThenDefault() {
        // ① 手填的赢过一切
        val typed = upsert(
            null, "hall_settings", UiState.HALL, SignalRole.ANCHOR, "hall_settings",
            note = "手填的", fallbackNote = "默认的",
        )
        assertEquals("手填的", typed.noteOf("hall_settings"))

        // ② 这一次没填 ⇒ 沿用旧备注（重框不该抹掉用户写过的字）
        val reloaded = upsert(
            typed, "hall_settings", UiState.HALL, SignalRole.ANCHOR, "hall_settings",
            fallbackNote = "默认的",
        )
        assertEquals("手填的", reloaded.noteOf("hall_settings"))

        // ③ 旧备注也没有 ⇒ 用默认描述（这一条是 2026-10-01 新增的第三档）
        val fresh = upsert(
            null, "hall_settings", UiState.HALL, SignalRole.ANCHOR, "hall_settings",
            fallbackNote = "设置入口",
        )
        assertEquals("设置入口", fresh.noteOf("hall_settings"))
    }
}
