package com.example.mastermechanic.service

import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.action.ClickEvent

/**
 * 点击审计日志（T2-3c，NFR-05）：每次判定（含**拒绝**）与每次手势结束都留一条，
 * 真机核对"为什么没点 / 间隔是否满足 300ms"全靠它。
 *
 * TAG 统一为 [TAG]（`MM-Click`），与其余 `MM-*` 日志同族，便于一条命令捞全。
 */
object ClickAuditLog {

    const val TAG = "MM-Click"

    fun write(event: ClickEvent) {
        when (event) {
            is ClickEvent.Decided -> {
                val audit = event.audit
                val verdict = if (audit.allowed) "下发" else "拒绝（${audit.reason?.label ?: "原因未知"}）"
                MmLog.i(
                    TAG,
                    "点击$verdict｜来源: ${audit.source.label}｜锚点: ${audit.anchorName}" +
                        "｜点: (${audit.frameX}, ${audit.frameY})｜状态: ${audit.state.label}" +
                        "｜前台: ${audit.foreground}｜模式: ${audit.mode.label}｜判定: ${audit.decisionId}",
                )
            }

            is ClickEvent.GestureEnded -> MmLog.i(
                TAG,
                "手势结束: ${if (event.completed) "完成" else "被取消"}｜锚点: ${event.anchorName}" +
                    "｜判定: ${event.decisionId}",
            )
        }
    }
}
