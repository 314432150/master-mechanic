package com.example.mastermechanic.patrol

import com.example.mastermechanic.decision.UiState

/**
 * 「现在能不能启动一次跑号」的**单一判据**（M4-T4-4d，纯逻辑）。
 *
 * ## 为什么要有这一层
 *
 * 起点判定原本只写在 [PatrolRequestConsumer] 里，而它**只写日志、没有任何界面出口** ——
 * 用户点「只拜访」看到的是一次"完全没反应"（菜单照常收起、底部还弹了一句无关的请求文案）。
 * 2026-09-20 真机第一次跑号就是这么卡住的。
 *
 * 现在这条判据被**两处共用**：
 * - 悬浮窗（主线程，广播**之前**）：不成立就**不发出请求**，改在菜单里挂原因条（FR-07：失败保持展开并说明）；
 * - [PatrolRequestConsumer]（兜底）：万一广播与判定之间有变化，第二次拦下并写日志。
 *
 * 判据只有一处（本文件），不会出现"菜单说能启动、消费方说不能"的漂移。
 */
object PatrolStartGate {

    /**
     * 判定用的画面：**滞回结论 ∪ 最近一轮原始命中**里，**能当起点**的最优先那一个。
     *
     * 为什么要并起来：见 [blockReason] 的 `recentHits` 参数。
     * 为什么取"最优先"而不是"随便哪个能当起点的"：与状态机的候选口径一致
     * （[UiState] 声明顺序 = 优先级），否则同一份输入会得出两种结论。
     *
     * 一个能当起点的都没有时**原样返回 [uiState]**：后面的分支要如实报告"停在哪个界面"。
     */
    fun effectiveState(uiState: UiState, recentHits: Set<UiState>): UiState =
        (recentHits + uiState)
            .filter { it in PatrolScenes.startStates }
            .minByOrNull { it.ordinal }
            ?: uiState

    /**
     * 这个菜单项对应哪条执行区间；**null = 它不是"启动流程"的项**（「继续」「停止」走别的路）。
     *
     * 菜单项 ↔ 区间的映射只在这里写一次（原来在 [PatrolRequestConsumer] 的 `when` 里）。
     */
    fun rangeFor(kind: PatrolRequestSignal.Kind): PatrolFlow.Range? = when (kind) {
        PatrolRequestSignal.Kind.VISIT_PRESET -> PatrolFlow.Range.SWITCH_AND_VISIT
        PatrolRequestSignal.Kind.SWITCH_NEXT,
        PatrolRequestSignal.Kind.SWITCH_SERVER,
            -> PatrolFlow.Range.SWITCH_ONLY

        PatrolRequestSignal.Kind.VISIT_ONLY -> PatrolFlow.Range.VISIT_ONLY
        // 「继续」从失败那一步接着来、「停止」什么都不启动 —— 两者都不需要起点
        PatrolRequestSignal.Kind.RESUME, PatrolRequestSignal.Kind.STOP -> null
    }

    /**
     * 现在能不能启动；**null = 可以**，否则是**给人看的原因**（菜单直接把它挂到原因条上）。
     *
     * 三种不成立的情形分开说（用户能看到区别，排障也能一眼分清）：
     * - 弹窗挡住了画面 → 等 FR-01 关掉弹窗，**不是用户的错**；
     * - 认不出来 → 识别没结论（未标定 / 画面不在清单里）；
     * - 画面清楚但不在起点表里（好友列表 / 设置页 / 确认框）→ 要用户先退出来。
     *
     * **2026-09-23 删掉第四种**（"已经站在目标好友的农场里"）：那需要判"这是谁的农场"，
     * 而农场归属判定已按用户口径整块移除（自己的农场与好友的农场换号 / 拜访路径一样）。
     */
    fun blockReason(
        uiState: UiState,
        kind: PatrolRequestSignal.Kind,
        /**
         * **最近一轮命中的画面**（未经滞回，`UiStateSignal.recentHits`）。
         *
         * 滞回要连续 2 轮命中才转移 ⇒「画面已经变了、
         * 但识别结论还停在上一屏」的窗口客观存在。真机实录（`mm-log-20260922.txt`）：
         * `02:24:16.368 待命期画面判定：当前「好友列表」｜本轮命中：农场`
         * —— 农场**已经命中**，`status` 要到 0.3 秒后才跟上；用户正好在这段里点了菜单，
         * 被拦"现在停在「好友列表」"（他的原话是"我是在农场点击的只拜访"）。
         *
         * 所以判定时把两份并起来看，取其中**能当起点**的最优先画面为准（见 [effectiveState]）。
         */
        recentHits: Set<UiState> = emptySet(),
    ): String? {
        val range = rangeFor(kind) ?: return null
        val uiState = effectiveState(uiState, recentHits)
        return when (val reading = PatrolScenes.read(uiState)) {
            is PatrolScenes.Reading.Blocked ->
                "现在看不清画面：${reading.reason}"

            PatrolScenes.Reading.Unrecognized ->
                "认不出当前画面（识别还没结论）：请先回到农场 / 大厅 / 启动页再点"

            is PatrolScenes.Reading.Ready -> {
                if (PatrolFlow.firstStep(reading.scene, range) == null) {
                    // 尾句是给"刚离开那一屏、识别还没跟上"的情况（滞回要连续几轮才转移，真机 2026-09-22 报障）：
                    // 用户已经把界面退出来了，但这一两秒内判定还停在旧画面 —— 说清楚比让他反复点好
                    "现在停在「${uiState.label}」，从这里起步不了 —— " +
                        "请先回到农场 / 大厅 / 启动页（若画面其实已经变了，等一两秒再点）"
                } else {
                    null
                }
            }
        }
    }
}
