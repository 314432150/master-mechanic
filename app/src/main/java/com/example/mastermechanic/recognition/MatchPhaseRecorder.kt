package com.example.mastermechanic.recognition

/**
 * 把 [TemplateMatcher.MatchCostProbe] 的分段回调**累加**成一份可直接读的表
 * （2026-10-18 N6-1 真机侧：把归因探针接到采集层）。
 *
 * ## 为什么需要它
 *
 * [TemplateMatcher] 里的插桩是**逐段回调**（一次 [TemplateMatcher.findPeaks] 收 5 段：
 * 前缀和 / 粗搜 / 代表点 / 精搜 / 抑制），而且**多模板信号会收到多组**（每模板一组）。
 * 直接把回调丢给日志会得到一堆零散行、且多模板时被重复计数。
 *
 * ⇒ 这里只做累加，**排序与占比留给读日志的人**（真机上是每 100 帧出一行汇总，
 * 由 `CaptureService` 输出）。
 *
 * ## 开销
 *
 * [TemplateMatcher] 里的五处插桩都是 `probe?.phase(...)` 短路形式 ⇒ `probe == null` 时
 * 连参数（`msSince(...)`、`"候选 ${coarse.size} → …"` 这些字符串）都不求值 ⇒ **生产关闭时零开销**。
 * 开启时的成本是每次 [phase] 一次 map 累加（字符串键），相对单条信号几十毫秒可忽略。
 *
 * ⚠ **线程安全**：匹配在 [com.example.mastermechanic.decision.RecognitionLoop] 的
 * "按信号并行"分支里跑在多个 worker 上，但**每条信号有自己的 recorder 实例**
 * （在 [com.example.mastermechanic.decision.RecognitionLoop.timedDetect] 内部新建）⇒
 * 天然无共享、无需加锁。
 */
class MatchPhaseRecorder : TemplateMatcher.MatchCostProbe {

    private val totals = LinkedHashMap<String, Double>()
    private val notes = LinkedHashMap<String, String>()

    override fun phase(name: String, ms: Double, note: String) {
        totals[name] = (totals[name] ?: 0.0) + ms
        // 同名段多次上报（多模板）时把 note 收成一行，避免日志里挤满重复文本
        notes[name] = if (notes.containsKey(name)) "${notes[name]} + $note" else note
    }

    /** 累计耗时（毫秒）：键为 `TemplateMatcher.PHASE_*`，值 = 该信号本轮各模板累加后的总耗时。 */
    fun totalsMs(): Map<String, Double> = totals

    /** 人类可读的 `prefix 1.2ms（窗口 361×397）` 形式，按耗时降序。 */
    fun summary(): String =
        totals.entries
            .sortedByDescending { it.value }
            .joinToString("、") { (name, ms) ->
                val note = notes[name].orEmpty()
                if (note.isEmpty()) "%s %.2fms".format(name, ms) else "%s %.2fms(%s)".format(name, ms, note)
            }

    /** 各段合计（毫秒）。 */
    fun totalMs(): Double = totals.values.sum()
}