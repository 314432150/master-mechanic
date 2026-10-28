package com.example.mastermechanic.auth

import android.content.Context

/**
 * **「我刚把用户送去系统页改 X，回来要说一句结果」**（2026-10-26）。
 *
 * ## 为什么不能只靠内存里的"上一次状态"
 *
 * 通知 / 后台运行都只能在系统页里改，改了之后回到 App —— 而**这一趟很容易把 Activity 重建**
 * （真机实测：返回时会看到窗口重画；用户同时报告"没有提示"和"白屏/灰屏"）。一旦进程重建，
 * 内存里的"上一次状态"就没了 ⇒ 首次填表被当成"没有变化"，Toast 永远不弹。
 *
 * 所以把"离开时的状态"落盘：跳转前记一笔，回到页面时读出来比对，**读完即清**（一次性）。
 * 这样无论中间有没有重建，都能给出一次准确回执；没有变化（用户取消了）就静默，不打扰。
 */
object AuthRecheckPending {

    private const val PREFS = "auth_recheck"
    private const val KEY_ITEM = "item"
    private const val KEY_STATE = "state"

    /** 记下"用户正要去系统页处理这一项，离开时它是 [state]"。 */
    fun mark(context: Context, item: AuthItem, state: AuthState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEM, item.name)
            .putString(KEY_STATE, state.name)
            .apply()
    }

    /** 取出并清掉（没有记录就返回 null）。 */
    fun consume(context: Context): Pair<AuthItem, AuthState>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val item = prefs.getString(KEY_ITEM, null) ?: return null
        val state = prefs.getString(KEY_STATE, null)
        prefs.edit().clear().apply()
        val parsedItem = runCatching { AuthItem.valueOf(item) }.getOrNull() ?: return null
        val parsedState = state?.let { runCatching { AuthState.valueOf(it) }.getOrNull() } ?: return null
        return parsedItem to parsedState
    }
}
