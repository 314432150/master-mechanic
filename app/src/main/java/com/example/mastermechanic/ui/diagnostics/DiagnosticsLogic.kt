package com.example.mastermechanic.ui.diagnostics

/**
 * 「诊断」页的判据（M5-U6 第二段，2026-10-03）——**纯逻辑**，便于 JVM 单测。
 *
 * 定位（用户三次意见后定稿，与「设置」页分工）：**诊断 = 看问题**（只有只读读数，没有任何改动入口，
 * 要改去「设置」/「授权与权限」）；**设置 = 改东西**。
 *
 * 所以本页只有一件事：把现场读出来，并且与 `MM-Capture` 的日志**同源**（耗时读数由采集侧在写那条日志的
 * 同一个位置 publish，见 `capture/FrameCostSignal`）⇒ 排障时"页面看到的"与"日志里那行"必然对得上。
 */
object DiagnosticsLogic {

    /**
     * NFR-01 的 P95 上限（毫秒）：与 `MM-Capture` 日志那行末尾写的"NFR-01 阈值"**同一个数**。
     *
     * ⚠ 本页**不新造阈值**：只判"在 20ms 线内 / 已超线"，中间不再插一档"偏慢"——
     * 那种档位没有来源、也无处标定（同"动画下限别凭一次样本往松调"那条教训）。
     */
    const val NFR_P95_MS = 20.0

    /** 耗时读数的三档。 */
    enum class CostLevel {
        /** 还没有读数（没攒够一窗 / 会话已结束）——**不拿旧值冒充现在**。 */
        UNKNOWN,

        /** 在 NFR-01 的 20 毫秒线内。 */
        OK,

        /** 已超线（这时"帧来得慢"会直接影响识别与点击的时机）。 */
        OVER,
    }

    fun costLevel(p95Ms: Double?): CostLevel = when {
        p95Ms == null -> CostLevel.UNKNOWN
        p95Ms > NFR_P95_MS -> CostLevel.OVER
        else -> CostLevel.OK
    }
}
