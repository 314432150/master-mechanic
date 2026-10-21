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
 * ## 规则（宁可多花一次读数，也不落一个错码）
 *
 * - 只让**格式合格**的码参与投票（[ShareCodeParser.isWellFormedCode]：恰好 6 位字母数字），
 *   不合格的一律按"这次没读全"记一次读、但永远不能被确认为结果；
 * - 同一个码被读到 [agreeNeeded] 次 ⇒ [Verdict.Confirmed]（同一屏是静态的，两次一致基本就是对的）；
 * - 读到 [maxReads] 次还没有任何码达标 ⇒ [Verdict.Conflict]，**不落盘**，由调用方停下报原因
 *   （红线 7：识别不可信 ⇒ 按未识别处理，不挑"最像的"）。
 *
 * 线程：只在帧线程上使用（同 `CaptureService` 的其他立牌状态）。
 */
class CodeConfirmation(
    private val agreeNeeded: Int = 2,
    private val maxReads: Int = 3,
) {

    /** 一次投票的结局。 */
    sealed interface Verdict {
        /** 码已确认（读到 [agreeNeeded] 次一致）；[reads] = 一共读了几次。 */
        data class Confirmed(val code: String, val reads: Int) : Verdict

        /** 还需要再读一次；[last] 是这一次读到的原始串（可能不合格，仅用于日志）。 */
        data class NeedMore(val reads: Int, val last: String?) : Verdict

        /** 读到上限仍不一致 ⇒ 不可信。 */
        data class Conflict(val reads: List<String?>) : Verdict
    }

    private val reads = mutableListOf<String?>()

    /** 这一轮已经读了几次（调用方据此决定下一次间隔，也可以用来写日志）。 */
    val readCount: Int get() = reads.size

    /** 清空（换到另一张牌 / 另一个屏幕时调用）。 */
    fun reset() {
        reads.clear()
    }

    /**
     * 交一次读数（[code] = null 表示"这一块没解析出唯一值"）。
     *
     * 合格且出现够次数 ⇒ Confirmed；否则要么继续读，要么到上限判 Conflict。
     */
    fun offer(code: String?): Verdict {
        reads += code
        val valid = code != null && ShareCodeParser.isWellFormedCode(code)
        if (valid) {
            val same = reads.count { it == code }
            if (same >= agreeNeeded) return Verdict.Confirmed(code!!, reads.size)
        }
        if (reads.size >= maxReads) return Verdict.Conflict(reads.toList())
        return Verdict.NeedMore(reads.size, code)
    }
}
