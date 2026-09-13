package com.example.mastermechanic.settings

/**
 * 应用级偏好（ADR-006 同一套存储：私有目录文本 + 纯函数编解码 + 临时文件 rename）。
 *
 * 与「巡查配置」「悬浮窗位置」并列的第三类**用户偏好**——不是业务数据，而是"程序该按什么习惯运行"。
 */
data class AppPreferences(
    /**
     * 采集会话（重）建立后，**是否保留用户已选择的运行方式**。
     *
     * - `false`（默认）：回到**演练**——这是 T2-6 立的安全默认（每次会话开始都要求用户重新明确"这次实点"）；
     * - `true`：保持用户上次的选择（实点也保持）。
     *
     * 为什么需要这一项（2026-09-14 用户反馈）：授予采集权限时系统会跳到游戏，
     * 用户**无法在授权后再切回本体去开实点**——若每次都强制回演练，就变成"必须先开会话、再切回来开实点"，
     * 与操作顺序相反。给一个显式开关，把"要不要每次都确认"交给用户，默认仍然是最安全的"每次回演练"。
     */
    val keepClickModeOnSessionStart: Boolean = false,
) {

    companion object {
        val DEFAULT = AppPreferences()
    }
}
