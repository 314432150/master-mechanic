package com.example.mastermechanic.action

/**
 * 手势注入通道（T2-3c）：安卓侧由无障碍服务实现（`AccessibilityGestureInjector`），
 * 单测用假实现。返回 false = **服务当时无法接受**（服务不可用 / 已达手势上限），
 * 此时点击视为未发生（ADR-002「派发失败按点击未完成处理」，不盲重试）。
 */
fun interface GestureInjector {
    /**
     * 下发一次点击。[onResult] 在手势结束时回调（完成 = true，被系统取消 = false）。
     * [frameX] / [frameY] 为运行帧像素坐标。
     */
    fun click(frameX: Double, frameY: Double, onResult: (Boolean) -> Unit): Boolean
}

/**
 * 点击转发层（T2-3c，ADR-002 的**唯一实现点**）：所有调用方（FR-01、巡查主流程、悬浮窗菜单）
 * 只能经这里下发点击，门禁 / 间隔 / 串行 / 审计四件事都由它承担。
 *
 * 调用方拿到的只是"允许下发"的判定 + 异步的手势结果事件；**动作是否生效必须由调用方自己验证**
 * （红线 5：动作后未验证，不得执行下一步）——本层不代替步骤验证。
 */
class ClickForwarder(
    private val gate: ClickGate,
    private val injector: GestureInjector,
    private val clock: () -> Long,
    private val audit: (ClickEvent) -> Unit,
) {

    /**
     * 提交一次点击请求：先过门禁（拒绝也留审计），允许则下发并登记在途。
     * 返回的判定表示"这一枪是否打出去了"；点没点中、动作有没有生效，由后续状态核对决定。
     */
    fun submit(request: ClickRequest, environment: ClickEnvironment): ClickVerdict {
        val now = clock()
        val verdict = gate.decide(environment, now)
        audit(ClickEvent.Decided(auditOf(request, environment, verdict, now)))
        if (!verdict.allowed) return verdict

        gate.onGestureStarted(request.decisionId)
        val accepted = injector.click(request.frameX, request.frameY) { completed ->
            gate.onGestureFinished(clock())
            audit(
                ClickEvent.GestureEnded(
                    decisionId = request.decisionId,
                    source = request.source,
                    anchorName = request.anchorName,
                    completed = completed,
                    nowMs = clock(),
                ),
            )
        }
        if (!accepted) {
            // 同步失败：手势根本没开始，解除在途（保守地按此刻起算间隔，宁可多等 300ms）
            gate.onGestureFinished(clock())
        }
        return verdict
    }

    private fun auditOf(
        request: ClickRequest,
        environment: ClickEnvironment,
        verdict: ClickVerdict,
        nowMs: Long,
    ): ClickAudit = ClickAudit(
        decisionId = request.decisionId,
        source = request.source,
        anchorName = request.anchorName,
        frameX = request.frameX.toInt(),
        frameY = request.frameY.toInt(),
        mode = environment.mode,
        state = environment.state,
        foreground = environment.gameForeground,
        allowed = verdict.allowed,
        reason = verdict.reason,
        nowMs = nowMs,
    )
}
