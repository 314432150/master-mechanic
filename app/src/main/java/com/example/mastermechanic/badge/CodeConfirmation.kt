package com.example.mastermechanic.badge

/**
 * 分享码的**"两次读一致才算数"**（temp/badge-share-1021，2026-10-22 加固；纯逻辑，JVM 可测）。
 *
 * ## 为什么需要它
 *
 * 同一张牌在真机上被读出过两个不同的码：`QGG4ZS` 与 `GG4Z9`（漏一位、末位 `S` 读成 `9`），
 * 立牌池因此出现两条同名同分的牌（见 `docs/progress.md` 2026-10-22）。一次读数就落盘，
 * 等于"赌这一帧 OCR 是对的"——而这块小字的错误率明显高于其他地方。
 *
 * ## 规则
 *
 * - 同一个码被读到 [agreeNeeded] 次 ⇒ [Verdict.Confirmed]（同一屏是静态的，两次一致基本就是对的）；
 * - 读到 [maxReads] 次还没有任何码达标 ⇒ [Verdict.Conflict]，**不落盘**，由调用方停下报原因
 *   （红线 7：识别不可信 ⇒ 按未识别处理，不挑"最像的"）。
 * - **长度不参与判定**（2026-10-23 修正）：曾经要求"码必须 6 位，否则这次读数不算数"，
 *   真机上遇到稳定读成 5 位的牌（`HDXQM` 连读 6 次都一样）⇒ 永远确认不了、整轮任务被打死。
 *   "稳定地读到同一个串"已经是这里能拿到的最强证据；位数可疑只作为**警告**由调用方写进日志。
 *
 * ## 作用域（2026-10-23 修正，真机 bug）
 *
 * 每次 [offer] 都要带 [scope]：**这一张牌、这一次弹框**的唯一标识。作用域一变就自动清空读数。
 * 为什么必须这样：真机上投票键只用了「画面 + 步骤号」，而「我的立牌」里两张牌都走同一个步骤号，
 * 任务重启后键也不变 ⇒ 上一张牌的读数被带进下一张，日志出现
 * `读数不一致（16RGRB / 16RGRB / Y9P4CZ）`——前两个其实是前一张牌的读数。
 *
 * 线程：只在帧线程上使用（同 `CaptureService` 的其他立牌状态）。
 */
class CodeConfirmation(
    private val agreeNeeded: Int = 2,
    private val maxReads: Int = 3,
) {

    /** 一次投票的结局。 */
    sealed interface Verdict {
        /** 码已确认（读到 [agreeNeeded] 次一致）；[reads] = 这个作用域里一共读了几次。 */
        data class Confirmed(val code: String, val reads: Int) : Verdict

        /** 还需要再读一次；[last] 是这一次读到的原始串（可能为 null，仅用于日志）。 */
        data class NeedMore(val reads: Int, val last: String?) : Verdict

        /** 读到上限仍不一致 ⇒ 不可信。 */
        data class Conflict(val reads: List<String?>) : Verdict
    }

    private var scope: String? = null
    private val reads = mutableListOf<String?>()

    /** 当前作用域里已经读了几次（调用方据此决定下一次间隔，也可以用来写日志）。 */
    val readCount: Int get() = reads.size

    /** 清空（换到另一张牌 / 另一个屏幕 / 一次新任务时调用；[offer] 换作用域时也会自动清）。 */
    fun reset() {
        scope = null
        reads.clear()
    }

    /**
     * 交一次读数。
     *
     * @param scope 这一张牌 / 这一次弹框的唯一标识（变 ⇒ 自动重新开始投票）
     * @param code 读到的码；null = "这一块没解析出唯一值"（同样占一次读数，避免无限重试）
     */
    fun offer(scope: String, code: String?): Verdict {
        if (scope != this.scope) {
            this.scope = scope
            reads.clear()
        }
        reads += code
        if (code != null && reads.count { it == code } >= agreeNeeded) {
            return Verdict.Confirmed(code, reads.size)
        }
        if (reads.size >= maxReads) return Verdict.Conflict(reads.toList())
        return Verdict.NeedMore(reads.size, code)
    }
}
