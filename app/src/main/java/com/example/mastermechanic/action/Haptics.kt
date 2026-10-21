package com.example.mastermechanic.action

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import com.example.mastermechanic.log.MmLog

/**
 * **流程开始 / 结束的震动反馈**（2026-10-20 用户口径："在流程开始和结束时分别增加一个震动反馈"）。
 *
 * ## 为什么用震动，而不是再加一条提示
 *
 * 跑号期间用户的眼睛在**游戏画面**上（不是悬浮窗）：顶部标签能说清"在做哪一步"，但"**什么时候
 * 开始 / 结束了**"是个瞬时事件，扫一眼未必正好在看。震动走的是触觉通道，不占视觉，也不用回到 App。
 *
 * ## 两种节奏为什么不同
 *
 * - 开始：**一下短的**（45ms）—— "它动了"；
 * - 结束：**两下**（60ms / 间隔 90ms / 60ms）—— 一次成功的执行结束，值得一个明确的收尾。
 *   两者节奏不同，用户不必抬头就能分出"刚开始"还是"已结束"。
 *
 * ## 不做的事（刻意）
 *
 * - **不响**、不读屏：提示音的优先级早被用户砍过（底部浮窗提示 2026-09-29 已移除），别再往里加回来；
 * - **失败不加额外节奏**：中止也走"结束"那条（两下）—— 失败的具体原因由顶部标签常驻显示
 *   （`FloatingLabelText` 的 `failed` 文案），震动只负责"这一轮结束了"这个事实；
 * - **不落盘、不做开关**：先按用户口径给上；若后续嫌吵再加设置项（见 `docs/progress.md`）。
 *
 * ## 实现细节
 *
 * `VibrationEffect.createWaveform`（API 26+，本项目 minSdk 34）在 `amplitudes = null` 时用设备默认
 * 强度 —— 比写死 255 更稳：有些机型的马达不支持自定义强度，写死反而会静音。`hasVibrator()` 为假
 * （少数平板 / 模拟器）时安静跳过，不抛异常、不写错误日志。
 */
object Haptics {

    private const val TAG = "MM-Haptics"

    /** 开始：一下短震（ms）。 */
    private val START_TIMINGS = longArrayOf(0L, 45L)

    /** 结束：两下（"等一下、再一下"），与开始明显区分。 */
    private val END_TIMINGS = longArrayOf(0L, 60L, 90L, 60L)

    /** 流程开始（用户点了菜单里某一项，且**起点判定通过、真的跑起来了**）。 */
    fun flowStarted(context: Context) = buzz(context, START_TIMINGS, "流程开始")

    /**
     * 流程结束（跑到区间终点 / 中止）。
     *
     * ⚠ 由调用方保证**每次流程只调一次**：
     * - 巡查（换号 / 只拜访 / 换号拜访）：`CaptureService.stepPatrol` 里分别挂在
     *   `outcome.finished` 与"中止原因首次记录"两个分支上；
     * - 搜集立牌（2026-10-22 补）：`CaptureService.announceBadgeEndedOnce`（按 `BadgeSession` 代次去重，
     *   因为结束后的任务快照会一直挂着、帧线程每轮都会走进 `stepBadge`）。
     *
     * 两者都不在用户手点「停止」时调 —— 那一下本来就有按钮反馈。
     */
    fun flowEnded(context: Context) = buzz(context, END_TIMINGS, "流程结束")

    private fun buzz(context: Context, timings: LongArray, what: String) {
        val vibrator = runCatching {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        }.getOrNull()
        if (vibrator == null || !vibrator.hasVibrator()) {
            // 没有马达（平板 / 模拟器）：安静跳过。写 info 而不是 warn —— 这不是故障。
            MmLog.i(TAG, "$what：本机没有可用的振动器，跳过")
            return
        }
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        }.onFailure {
            // 个别机型把振动权限交给系统开关（勿扰 / 省电）⇒ 这里失败不影响任何流程，只留痕
            MmLog.w(TAG, "$what：震动下发失败（${it.javaClass.simpleName}：${it.message}）")
        }
    }
}
