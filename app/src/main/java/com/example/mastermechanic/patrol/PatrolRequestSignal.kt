package com.example.mastermechanic.patrol

import com.example.mastermechanic.log.MmLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 「用户在悬浮窗菜单上发起的请求」事件（FR-07 菜单出口）。
 *
 * 菜单只把用户的选择**广播出去并留痕**，自身不做任何动作 —— 真正的换号 / 拜访流程属 M4。
 * 因此 **M3 全程不产生任何游戏点击**（红线 2：真正的点击由 M4 的步骤产生）。
 *
 * 2026-09-16 变更（用户口径）：**跑号 / 巡查已移除**，出口只保留
 * 「一键拜访（按预设）」与「换号 / 拜访开关 / 停止」。
 *
 * 回调发生在调用线程（悬浮窗主线程）；订阅方自行编组。
 */
object PatrolRequestSignal {

    const val TAG = "MM-Patrol"

    /** 用户请求的种类（每一种都对应菜单上的一次明确点击）。 */
    enum class Kind(
        /** 详细日志用：完整描述（用于 [MmLog] 留痕 / 排查）。 */
        val logText: String,
        /** Toast / 状态条用：**精简文案**（2026-09-17 用户口径"精简语义，避免太长"）。 */
        val briefText: String,
    ) {
        /** 一键拜访：按**预设**（服务器策略 + 好友）执行"换号 + 拜访"。 */
        VISIT_PRESET("用户请求拜访（按预设）", "换号拜访"),

        /** 换号：按服务器清单顺序换下一个。 */
        SWITCH_NEXT("用户请求换号（下一个）", "换号 → 下一个"),

        /** 换号：换到指定区服。 */
        SWITCH_SERVER("用户请求换号（指定区服）", "换号"),

        /**
         * 只拜访：**跳过全部换号步骤**，在当前所在的区服直接进好友农场去拜访预设里的好友
         * （FR-04 的「只拜访」区间：第 7 步 → 第 10 步）。
         */
        VISIT_ONLY("用户请求只拜访（跳过换号）", "只拜访"),

        /**
         * 继续（FR-05）：流程**中止 / 暂停**之后，从失败的那一步重试，不重跑前面的步骤。
         *
         * M3 只有"停止"，没有这一项；M4 起流程真的会中止（验证不过 / 离开前台），所以补上这个出口
         * （T4-4c 消费，菜单入口属 T4-5）。
         */
        RESUME("用户请求继续（从失败那一步重试）", "继续"),

        /** 停止：中断当前流程（M3 没有可停的流程，照常发出，由上层说明"没有进行中的流程"）。 */
        STOP("用户请求停止", "停止"),
    }

    /**
     * 一次请求。
     *
     * @param serverName 指定了区服时才有值（[Kind.SWITCH_SERVER] 必有；[Kind.VISIT_PRESET] 只在预设为
     *   "固定区服"时才有）；**null = 按清单换下一个**。
     * @param friendName 目标好友（[Kind.VISIT_PRESET] 带）。
     */
    data class Request(
        val kind: Kind,
        val nowMs: Long,
        val serverName: String? = null,
        val friendName: String? = null,
    ) {
        // 2026-09-29：原先这里有一段 `briefText()`（"动作名：目标区服 → 好友"）专供底部浮窗提示那种
        // "一闪而过"的提示。用户口径（"既然顶部增加了悬浮窗,那么底部的toast是不是可以去除了?"）
        // 把底部提示整体移除之后它没有调用方了 ⇒ **一并删除**（不留死代码）。
        // 详细描述仍走 [Kind.logText]（`MmLog` 留痕）与 `PatrolRequestConsumer` 的日志（用 [Kind.briefText]）。
    }

    private val listeners = CopyOnWriteArraySet<(Request) -> Unit>()

    @Volatile
    private var count = 0

    /** 累计收到多少次用户请求（审计用；进程内计数）。 */
    val requestCount: Int get() = count

    fun addListener(listener: (Request) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Request) -> Unit) {
        listeners.remove(listener)
    }

    /** 记录一次用户请求，返回这是第几次。 */
    fun request(request: Request): Int {
        count++
        val detail = buildString {
            request.serverName?.let { append("，目标区服「$it」") }
            request.friendName?.let { append("，目标好友「$it」") }
        }
        MmLog.i(TAG, "${request.kind.logText}（第 $count 次）$detail")
        listeners.forEach { it(request) }
        return count
    }
}
