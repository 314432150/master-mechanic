package com.example.mastermechanic.ui

import androidx.annotation.StringRes
import com.example.mastermechanic.R
import com.example.mastermechanic.auth.AuthItem

/**
 * 授权项的**显示名**（唯一出处）。
 *
 * ## 为什么要抽出来（2026-10-02）
 *
 * 用户报障那条链上有个反复出现的动作：**看一眼"还缺哪几项"** —— 运行页那张卡要念出来、授权页顶部那句
 * 汇总要念出来、采集卡那句"先办好上面 X"也要念出来。三处以前只有授权页那一处有映射（而且是 private）。
 * ⇒ 现在**一处**映射（这里），三处共用；否则迟早出现"运行页说无障碍、授权页说无障碍服务"的两种说法。
 *
 * 只搬**名字**（其余描述 / 按钮文案 / 状态文案仍留在授权页 —— 它们只有那一处用）。
 * 放在 `ui/` 而不是 `auth/`：`auth/` 是**纯逻辑**模块（要能被 JVM 单测直接跑），不能碰 `R`。
 */
@StringRes
fun authItemLabelRes(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_label
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_label
    AuthItem.RESIDENT -> R.string.auth_resident_label
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_label
}
