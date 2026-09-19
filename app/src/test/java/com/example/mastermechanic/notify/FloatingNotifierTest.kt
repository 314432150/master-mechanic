package com.example.mastermechanic.notify

import com.example.mastermechanic.patrol.PatrolRequestSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Toast 文案生成（即 [PatrolRequestSignal.Request.briefText] 的语义）的纯逻辑断言。
 *
 * `Toast.show()` 本身的渲染不在 JVM 范围 —— 测的就是"这条文案长啥样"，至于它真的弹不弹
 * 出来，是真机要看的（[FloatingNotifier] 是 Android 单例，依赖 ApplicationContext，
 * 纯逻辑全在 [PatrolRequestSignal.Request.briefText] 里）。
 */
class FloatingNotifierTest {

    private fun req(
        kind: PatrolRequestSignal.Kind,
        serverName: String? = null,
        friendName: String? = null,
    ) = PatrolRequestSignal.Request(
        kind = kind,
        nowMs = 0L,
        serverName = serverName,
        friendName = friendName,
    )

    @Test
    fun briefText_isActionOnlyWhenNoTarget() {
        // 没有目标区服 / 好友：就只显示动作名
        assertEquals("换号 → 下一个", req(PatrolRequestSignal.Kind.SWITCH_NEXT).briefText())
        assertEquals("停止", req(PatrolRequestSignal.Kind.STOP).briefText())
        assertEquals("只拜访", req(PatrolRequestSignal.Kind.VISIT_ONLY).briefText())
    }

    @Test
    fun briefText_includesFriendOnly() {
        // 只有好友（最常用路径："换号拜访 → 星月晚"）
        assertEquals(
            "换号拜访：星月晚",
            req(PatrolRequestSignal.Kind.VISIT_PRESET, friendName = "星月晚").briefText(),
        )
        assertEquals(
            "只拜访：阿明",
            req(PatrolRequestSignal.Kind.VISIT_ONLY, friendName = "阿明").briefText(),
        )
    }

    @Test
    fun briefText_includesServerOnly() {
        // 只有服务器（"换号 → 龙腾"）：不显示好友是因为没填
        assertEquals(
            "换号：龙腾",
            req(PatrolRequestSignal.Kind.SWITCH_SERVER, serverName = "龙腾").briefText(),
        )
    }

    @Test
    fun briefText_includesServerAndFriend() {
        // 两者都有：服务器在前、好友在后，中间用「→」（2026-09-19 用户口径：`·` 分不清哪个是区服、哪个是人）
        assertEquals(
            "换号拜访：龙腾 → 星月晚",
            req(PatrolRequestSignal.Kind.VISIT_PRESET, serverName = "龙腾", friendName = "星月晚")
                .briefText(),
        )
    }

    @Test
    fun briefText_treatsEmptyStringsAsNoTarget() {
        // 空串按"没填"处理（VisitPreset 允许 serverName="" 表示 NEXT + 不指定）
        // VISIT_PRESET + 空 serverName + 好友 → "换号拜访：星月晚"（不混进 serverName）
        assertEquals(
            "换号拜访：星月晚",
            req(PatrolRequestSignal.Kind.VISIT_PRESET, serverName = "", friendName = "星月晚")
                .briefText(),
        )
        // SWITCH_SERVER + 空 serverName + 空 friendName → 只有动作名（不显示空区服名 / 好友名）
        assertEquals(
            "换号",
            req(PatrolRequestSignal.Kind.SWITCH_SERVER, serverName = "", friendName = "")
                .briefText(),
        )
    }
}