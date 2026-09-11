package com.example.mastermechanic.recognition

/**
 * 信号检测器（T1-3 入口）：对信号规格执行「模板匹配 → 峰值合并 → 可信性判定」，
 * 输出判定记录（§5-1）。
 *
 * 判定规则（§5-2、红线 7）：
 * - 最高分低于命中线（[MatchParams.matchThreshold]）→ 未命中；
 * - 竞争位置也达到命中线（多处高分）→ 不可信，按未识别处理；
 * - 最高分与竞争分差距小于差距线（[MatchParams.ambiguityMargin]）→ 不可信，按未识别处理；
 * - 其余 → 命中。
 *
 * 对每条信号输出一份记录（含未命中与不可信），保证审计链完整。
 */
class SignalDetector(private val signals: List<SignalSpec>, private val params: MatchParams) {

    /** 对全部信号逐个判定；输出顺序与构造时一致（确定性）。 */
    fun detect(image: GrayImage): List<DetectionRecord> = signals.map { detectSignal(image, it) }

    /** 单信号判定：多模板峰值合并后按规则给出结论。 */
    fun detectSignal(image: GrayImage, signal: SignalSpec): DetectionRecord {
        val peaks = signal.templates
            .flatMap { TemplateMatcher.findPeaks(image, it, signal.window, params) }
            .let { TemplateMatcher.suppressPeaks(it, params.peakMinDistance) }
        return DetectionRecord(signal.name, judge(peaks), peaks.firstOrNull(), peaks.getOrNull(1))
    }

    /** 判定规则本体（输入为已合并 / 抑制的峰值序列）：纯函数，独立可测。 */
    fun judge(peaks: List<MatchPeak>): Verdict {
        val best = peaks.firstOrNull()
        val competitor = peaks.getOrNull(1)
        return when {
            best == null || best.score < params.matchThreshold -> Verdict.NOT_MATCHED
            competitor != null && competitor.score >= params.matchThreshold -> Verdict.UNRELIABLE
            competitor != null && best.score - competitor.score < params.ambiguityMargin -> Verdict.UNRELIABLE
            else -> Verdict.MATCHED
        }
    }
}
