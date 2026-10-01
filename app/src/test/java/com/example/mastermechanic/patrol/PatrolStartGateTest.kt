package com.example.mastermechanic.patrol

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolRequestSignal.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「现在能不能启动一次跑号」的判据（M4-T4-4d）。
 *
 * 这是**真机上第一个卡住的地方**（2026-09-20）：菜单把请求发出去、消费方只写日志，
 * 用户看到的是"点了完全没反应"。所以这些用例钉的不只是判定，还有**用户能看到的原因**。
 */
class PatrolStartGateTest {

    // ---------------------------------------------------------------- 菜单项 → 区间

    @Test
    fun onlyTheStartKindsMapToARange() {
        assertEquals(PatrolFlow.Range.SWITCH_AND_VISIT, PatrolStartGate.rangeFor(Kind.VISIT_PRESET))
        assertEquals(PatrolFlow.Range.SWITCH_ONLY, PatrolStartGate.rangeFor(Kind.SWITCH_NEXT))
        assertEquals(PatrolFlow.Range.SWITCH_ONLY, PatrolStartGate.rangeFor(Kind.SWITCH_SERVER))
        assertEquals(PatrolFlow.Range.VISIT_ONLY, PatrolStartGate.rangeFor(Kind.VISIT_ONLY))
        // 「继续」「停止」不是"启动"，不需要起点 —— 它们永远不该被起点判定拦住
        assertNull(PatrolStartGate.rangeFor(Kind.RESUME))
        assertNull(PatrolStartGate.rangeFor(Kind.STOP))
    }

    @Test
    fun resumeAndStopAreNeverBlocked() {
        for (state in UiState.entries) {
            assertNull("「继续」不该被起点拦住（$state）", PatrolStartGate.blockReason(state, Kind.RESUME))
            assertNull("「停止」不该被起点拦住（$state）", PatrolStartGate.blockReason(state, Kind.STOP))
        }
    }

    // -------------------------------------------- 已在目标好友的农场（2026-09-21 加 / 2026-09-23 删）

    @Test
    fun startingFromAnyFarmIsAllowedNowThatFarmIdentityIsGone() {
        // 2026-09-21 这里曾有一条拦截：「已经在「X」的农场了，不用再拜访」。
        // 2026-09-23 用户拍板**把农场归属判定整块删掉**（自己的农场与好友的农场换号 / 拜访路径完全一样，
        // 不需要知道"这是谁的农场"）⇒ 那条拦截随之取消：停在**任何**农场都照常起步，
        // 流程从第 8 步（打开好友列表）走一遍，点错由第 9 步的名称定位保证（红线 3）。
        for (state in listOf(UiState.FARM, UiState.FRIEND_FARM)) {
            assertNull(
                "$state 上起步不该再被拦",
                PatrolStartGate.blockReason(uiState = state, kind = Kind.VISIT_ONLY),
            )
        }
    }

    // ---------------------------------------------------------------- 画面刚变、滞回还没跟上（2026-09-22 真机）

    @Test
    fun aFreshHitPassesEvenWhileTheHysteresisStillSaysTheOldScreen() {
        // 真机实录（mm-log-20260922.txt）：
        //   02:24:16.368  待命期画面判定：当前「好友列表」｜本轮命中：农场
        //   02:24:16.647  状态转移: 好友列表 -> 农场（连续 2 次命中（进入））
        // 用户在这 0.3 秒的窗口里点了菜单 → 被误拦"现在停在「好友列表」"
        //（他的原话："我是在农场点击的只拜访"）。起点判定要看"最近一轮命中的画面"。
        assertNull(
            "刚退出好友列表：status 还停在旧屏，但农场已经命中 ⇒ 必须放行",
            PatrolStartGate.blockReason(
                uiState = UiState.FRIEND_LIST,
                kind = Kind.VISIT_ONLY,
                recentHits = setOf(UiState.FARM),
            ),
        )
        // 「换号」同理：刚回到大厅 / 停在启动页都能起步
        assertNull(
            PatrolStartGate.blockReason(
                uiState = UiState.FRIEND_LIST,
                kind = Kind.SWITCH_NEXT,
                recentHits = setOf(UiState.HALL),
            ),
        )
    }

    @Test
    fun recentHitsDoNotInventAStartOutOfThinAir() {
        // 反向：最近一轮命中的**都不是**起点画面 ⇒ 照旧拦下（不能因为"有命中"就放行）
        val reason = PatrolStartGate.blockReason(
            uiState = UiState.FRIEND_LIST,
            kind = Kind.VISIT_ONLY,
            recentHits = setOf(UiState.FRIEND_LIST, UiState.HALL_SETTINGS),
        )

        assertNotNull("命中的还是非起点画面 ⇒ 仍然拦", reason)
    }

    // ---------------------------------------------------------------- 起点成立的画面

    @Test
    fun theFiveStartScreensPassForTheirRange() {
        // 这五屏就是 PatrolScenes.startStates；「只拜访」只在游戏内的三屏成立
        assertNull(PatrolStartGate.blockReason(UiState.FARM, Kind.VISIT_ONLY))
        assertNull(PatrolStartGate.blockReason(UiState.FRIEND_FARM, Kind.VISIT_ONLY))
        assertNull(PatrolStartGate.blockReason(UiState.HALL, Kind.VISIT_ONLY))
        // 启动页 / 选服页还能启动"换号"（第 4 / 5 步），但启动不了"只拜访"（人还没进游戏）
        assertNull(PatrolStartGate.blockReason(UiState.LAUNCH_PAGE, Kind.SWITCH_NEXT))
        assertNull(PatrolStartGate.blockReason(UiState.SERVER_SELECT, Kind.SWITCH_NEXT))
        assertNotNull(PatrolStartGate.blockReason(UiState.LAUNCH_PAGE, Kind.VISIT_ONLY))
        assertNotNull(PatrolStartGate.blockReason(UiState.SERVER_SELECT, Kind.VISIT_ONLY))
    }

    // ---------------------------------------------------------------- 三种不成立，各说各的原因

    @Test
    fun everyBlockedCaseExplainsItself() {
        // ① 认不出来（未标定 / 画面不在清单里）
        val unknown = PatrolStartGate.blockReason(UiState.UNKNOWN, Kind.VISIT_ONLY)
        assertNotNull(unknown)
        assertNotNull("原因里要出现『认不出』：$unknown", unknown!!.contains("认不出"))

        // ② 弹窗挡住画面（等 FR-01 关掉，不是用户的错）
        val blocked = PatrolStartGate.blockReason(UiState.ACTIVITY_POPUP, Kind.VISIT_ONLY)
        assertNotNull(blocked)
        assertNotNull("原因里要说清是弹窗：$blocked", blocked!!.contains("弹窗"))

        // ③ 画面清楚但不在起点表里（要用户先退出来）
        val notAStart = PatrolStartGate.blockReason(UiState.FRIEND_LIST, Kind.VISIT_ONLY)
        assertNotNull(notAStart)
        assertNotNull("原因里要点出当前画面：$notAStart", notAStart!!.contains(UiState.FRIEND_LIST.label))
    }
}
