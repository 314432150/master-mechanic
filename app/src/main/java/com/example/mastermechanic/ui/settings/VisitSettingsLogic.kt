package com.example.mastermechanic.ui.settings

import com.example.mastermechanic.preset.ServerChoice
import com.example.mastermechanic.preset.VisitPreset

/**
 * **「拜访设置」页的判据**（M5-U3，2026-10-01）——**纯逻辑，可 JVM 单测**。
 *
 * ## 这一页只做一件事：编辑并保存 `preset/visit.txt`
 *
 * **不发起任何流程**（用户 2026-10-01 拍板："U3 只负责保存，要跑就回游戏用悬浮窗点"）。
 * 这与"要游戏在前台才能做的动作，入口留在游戏里"是同一条口径 —— 保存是"准备"，不是"执行"。
 *
 * ## 与悬浮窗那一层共用同一份判据
 *
 * 校验**不在这里另写一套**：能不能执行由 [VisitPreset.isReady] 说了算（好友必填；
 * 固定区服时区服名也必填）。本对象只把它翻成"**该说哪一句**"（[Blocked]），
 * 好让界面把原因摊给用户 —— 两边各写一份校验，迟早出现"悬浮窗说没配好、App 说配好了"。
 */
object VisitSettingsLogic {

    /** 保存前的拦截原因（[NONE] = 可以保存）。 */
    enum class Blocked {
        NONE,

        /** 还没选拜访好友（好友必填 —— 不知道去见谁，这条预设没有意义）。 */
        FRIEND_MISSING,

        /** 策略是「固定区服」但没选区服（顺序轮换不需要选，那是合法策略，不是"没选"）。 */
        SERVER_MISSING,
    }

    /**
     * 现在能不能保存；不能则说清是哪一项。
     *
     * ⚠ **与 [VisitPreset.isReady] 必须一致**（有单测钉住这条不变量）：这里判出来的 `NONE`
     * 必须等价于"`isReady` 为真"。两处若漂移，就会出现"界面说可以保存、保存下去却跑不了"。
     */
    fun blockedReason(preset: VisitPreset): Blocked = when {
        preset.friendName.isEmpty() -> Blocked.FRIEND_MISSING
        preset.serverChoice == ServerChoice.FIXED && preset.serverName.isEmpty() ->
            Blocked.SERVER_MISSING

        else -> Blocked.NONE
    }

    /** 草稿 → 预设：去首尾空白后交给 [VisitPreset.of] 做格式校验（界面不该因为多敲一个空格就拒收）。 */
    fun draft(serverChoice: ServerChoice, serverName: String, friendName: String): VisitPreset =
        VisitPreset.of(serverChoice = serverChoice, serverName = serverName, friendName = friendName)

    /**
     * 切换策略时草稿怎么变。
     *
     * **保留用户已经选过的区服名**：从「固定区服」切到「顺序轮换」再切回来，不该逼他重选一次
     * （切过去时那个名字**不参与执行** —— [VisitPreset.isReady] 在 NEXT 下不看它 ——
     * 所以留着它没有任何风险，只是省一步操作）。
     */
    fun withChoice(preset: VisitPreset, serverChoice: ServerChoice): VisitPreset =
        preset.copy(serverChoice = serverChoice)
}
