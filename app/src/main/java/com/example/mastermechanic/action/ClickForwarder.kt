package com.example.mastermechanic.action

/**
 * 手势注入通道（T2-3c）：安卓侧由无障碍服务实现（`AccessibilityGestureInjector`），
 * 单测用假实现。返回 false = **服务当时无法接受**（服务不可用 / 已达手势上限），
 * 此时点击视为未发生（ADR-002「派发失败按点击未完成处理」，不盲重试）。
 */
fun interface GestureInjector {
    /**
     * 下发一次点击。[onResult] 在手势结束时回调（完成 = true，被系统取消 = false）。
     * [x] / [y] 为**屏幕坐标**；T4-6 之后帧与屏幕恒同向同尺寸，二者数值相同（见 [ScreenSize]）。
     */
    fun click(x: Double, y: Double, onResult: (Boolean) -> Unit): Boolean

    /**
     * 下发一次**滑动**（按住从 ([fromX],[fromY]) 拖到 ([toX],[toY])，历时 [durationMs]）——
     * 第 5 步「服务器列表逐屏滚动查找」用（2026-09-29 加）。
     *
     * 默认实现返回 **false（接不了）**：老实现 / 单测假件没有滑动能力时，明确失败而不是静默当成成功
     * ——"点了没反应"与"根本没点"必须能分开（红线 5 的口径）。
     */
    fun swipe(
        fromX: Double,
        fromY: Double,
        toX: Double,
        toY: Double,
        durationMs: Long,
        onResult: (Boolean) -> Unit,
    ): Boolean = false
}

/**
 * 点击转发层（T2-3c，ADR-002 的**唯一实现点**）：所有调用方（FR-01、巡查主流程、悬浮窗菜单）
 * 只能经这里下发点击，门禁 / 间隔 / 串行 / 审计四件事都由它承担。
 *
 * 调用方拿到的只是"允许下发"的判定 + 异步的手势结果事件；**动作是否生效必须由调用方自己验证**
 * （红线 5：动作后未验证，不得执行下一步）——本类不代替步骤验证。
 *
 * ## 坐标这一层现在做什么（T4-6，2026-09-23）
 *
 * 调用方给的是**运行帧坐标**（识别判定结果）。转屏由 `VirtualDisplay.resize()` 跟进后
 * **帧恒与屏幕同向同尺寸** ⇒ 帧坐标即屏幕坐标，**换算已整体删除**（原先的 `ClickGeometry`
 * 是为"竖画布会话下帧里画面只占中间一条带"服务的）。这一层现在只剩两件事：
 * ① **越界判定**（[ScreenSize.project]：落点在屏幕外一律不拦下发 —— NFR-05）；
 * ② **拟人化抖动**（[InputJitter]，ADR-002 补记二）。
 * 审计仍同时记**帧点与屏幕点**两个字段：将来几何再变（例如又出现不跟随的路径），日志能一眼看出来。
 */
class ClickForwarder(
    private val gate: ClickGate,
    private val injector: GestureInjector,
    private val clock: () -> Long,
    private val audit: (ClickEvent) -> Unit,
    private val screenSize: () -> ScreenSize = { ScreenSize.UNKNOWN },
    private val jitter: InputJitter = InputJitter(),
) {

    /**
     * 提交一次点击请求：定落点（越界判定）→ **拟人化抖动** → 过门禁（拒绝也留审计）→ 允许则下发并登记在途。
     * 返回的判定表示"这一枪是否打出去了"；点没点中、动作有没有生效，由后续状态核对决定。
     */
    fun submit(request: ClickRequest, environment: ClickEnvironment): ClickVerdict {
        val now = clock()
        val screen = screenSize()
        val point = humanize(screen.project(request.frameX, request.frameY), screen)
        // 定落点 + 抖动之后才能判"这个点在不在屏幕上"，所以把它并进环境一起交给门禁（判定顺序见 ClickGate）
        val resolved = environment.copy(targetOnScreen = point.onScreen)
        val verdict = gate.decide(resolved, now)
        audit(ClickEvent.Decided(auditOf(request, resolved, verdict, now, point)))
        if (!verdict.allowed) return verdict

        gate.onGestureStarted(request.decisionId)
        val accepted = injector.click(point.x, point.y) { completed ->
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

    /**
     * 提交一次**滑动**请求（2026-09-29 加，第 5 步滚列表）：与 [submit] 走**完全同一条**门禁 / 间隔 /
     * 串行 / 审计（红线 6），只是手势换成"按住拖一段"。
     *
     * 起止点都做拟人化抖动与越界判定（**两端都必须在屏内**才算"落点在屏内"）——
     * 终点越界会被门禁拒（而不是画一条跑到屏外的路径）。
     */
    fun submitSwipe(request: SwipeRequest, environment: ClickEnvironment): ClickVerdict {
        val now = clock()
        val screen = screenSize()
        val from = humanize(screen.project(request.fromFrameX, request.fromFrameY), screen)
        val to = humanize(screen.project(request.toFrameX, request.toFrameY), screen)
        val resolved = environment.copy(targetOnScreen = from.onScreen && to.onScreen)
        val verdict = gate.decide(resolved, now)
        audit(ClickEvent.Decided(auditOf(request, resolved, verdict, now, from, to)))
        if (!verdict.allowed) return verdict

        gate.onGestureStarted(request.decisionId)
        val accepted = injector.swipe(from.x, from.y, to.x, to.y, request.durationMs) { completed ->
            gate.onGestureFinished(clock())
            audit(
                ClickEvent.GestureEnded(
                    decisionId = request.decisionId,
                    source = request.source,
                    anchorName = request.anchorName,
                    completed = completed,
                    nowMs = clock(),
                    kind = ClickGestureKind.SWIPE,
                ),
            )
        }
        if (!accepted) {
            // 同 [submit]：同步失败 = 手势根本没开始，解除在途（保守地按此刻起算间隔）
            gate.onGestureFinished(clock())
        }
        return verdict
    }

    /**
     * 拟人化落点（2026-09-21 用户口径，[InputJitter]）：在落点上加**小幅随机偏移**，并夹回屏内。
     *
     * 为什么放在这一层：本层是**唯一下发口**（ADR-002）——放这儿就不存在"哪条路径忘了抖"的可能，
     * 三条来源（FR-01 / 跑号 / 悬浮窗菜单）自动全部覆盖。
     *
     * 不抖的两种情形：① 落点已被判为越界（交给门禁拒绝，不浪费随机数）；② 屏幕尺寸未知
     * （连抖动都无法保证落在屏内，按"不知道就不猜"原样下发）。
     */
    private fun humanize(point: ScreenPoint, screen: ScreenSize): ScreenPoint {
        if (!point.onScreen || !screen.known) return point
        val x = (point.x + jitter.offset()).coerceIn(0.0, (screen.width - 1).toDouble())
        val y = (point.y + jitter.offset()).coerceIn(0.0, (screen.height - 1).toDouble())
        return ScreenPoint(x, y, onScreen = true)
    }

    private fun auditOf(
        request: ClickRequest,
        environment: ClickEnvironment,
        verdict: ClickVerdict,
        nowMs: Long,
        point: ScreenPoint,
    ): ClickAudit = ClickAudit(
        decisionId = request.decisionId,
        source = request.source,
        anchorName = request.anchorName,
        frameX = request.frameX.toInt(),
        frameY = request.frameY.toInt(),
        screenX = point.x.toInt(),
        screenY = point.y.toInt(),
        state = environment.state,
        foreground = environment.gameForeground,
        allowed = verdict.allowed,
        reason = verdict.reason,
        nowMs = nowMs,
    )

    /** 滑动的审计记录：多带一个终点（[ClickAudit.toScreenX] / [ClickAudit.toScreenY]）与手势种类。 */
    private fun auditOf(
        request: SwipeRequest,
        environment: ClickEnvironment,
        verdict: ClickVerdict,
        nowMs: Long,
        from: ScreenPoint,
        to: ScreenPoint,
    ): ClickAudit = ClickAudit(
        decisionId = request.decisionId,
        source = request.source,
        anchorName = request.anchorName,
        frameX = request.fromFrameX.toInt(),
        frameY = request.fromFrameY.toInt(),
        screenX = from.x.toInt(),
        screenY = from.y.toInt(),
        state = environment.state,
        foreground = environment.gameForeground,
        allowed = verdict.allowed,
        reason = verdict.reason,
        nowMs = nowMs,
        toScreenX = to.x.toInt(),
        toScreenY = to.y.toInt(),
        kind = ClickGestureKind.SWIPE,
    )
}
