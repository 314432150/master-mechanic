package com.example.mastermechanic.recognition

/**
 * 窗口内平均亮度（T7-1，M7 农场祝福）：为"同一个控件是**亮**还是**灰**"提供一个绝对量。
 *
 * ## 为什么必须另起一个判据，而不是模板匹配
 *
 * [TemplateMatcher] 用的是**归一化互相关（NCC）**，"分数对亮度 / 对比度归一化
 * （±线性变换不改变结果）"。而真机上同一个祝福控件的亮态与灰态，差别**几乎全在亮度**：
 *
 * ```text
 * 亮态  平均亮度 165.0   灰态 122.6   （比值 0.743，2026-10-28 真机实测）
 * 亮态  平均饱和度 0.314  灰态 0.227  （差值不足以单独当判据）
 * ```
 *
 * ⇒ 同一个模板对两态给出几乎一样高的分数，**NCC 结构上就分不开**；
 * 能分开的只有绝对亮度，所以另立本类（`docs/recognition/farm-bless-research.md` §2）。
 *
 * ## 分工（不要合并成一条判据）
 *
 * - 模板匹配回答"这个控件**在不在**"（亮 / 灰都该命中 —— 这正是 NCC 的长处）；
 * - 本类回答"它**亮不亮**"。
 *
 * 两者混用会出两种错：把"灰"读成"不在"（人物明明站在格子里，程序却以为还要往前走），
 * 或把"不在"读成"灰"（明明在空地上，程序却以为站在格子里）。
 */
object RegionBrightness {

    /**
     * [bounds] 内的平均灰度（0..255，返回值不取整）；区域为空返回 null。
     *
     * 越界部分按帧边界裁剪（与 [SearchWindow.pixelBounds] 的裁剪口径一致）；
     * 裁剪后为空同样返回 null —— 调用方据此按"读不到"处理，**不要当成 0（黑）**：
     * 0 会被 [BrightnessBaseline] 判成"灰"，等于把"没读到"说成"不可祝福"。
     */
    fun mean(image: GrayImage, bounds: PixelBounds): Double? {
        val x0 = bounds.x0.coerceIn(0, image.width)
        val y0 = bounds.y0.coerceIn(0, image.height)
        val x1 = bounds.x1.coerceIn(0, image.width)
        val y1 = bounds.y1.coerceIn(0, image.height)
        if (x1 <= x0 || y1 <= y0) return null
        val pixels = image.pixels
        var sum = 0L
        var count = 0L
        for (y in y0 until y1) {
            val base = y * image.width
            for (x in x0 until x1) {
                sum += (pixels[base + x].toInt() and 0xFF).toLong()
                count++
            }
        }
        return sum.toDouble() / count.toDouble()
    }
}

/**
 * 亮 / 灰的**标定基线**：亮态与灰态各一个平均亮度，分界取两者中点。
 *
 * 为什么是"两条基线取中点"而不是在代码里写一个阈值：阈值是**视觉量**，随设备屏幕、
 * 游戏版本与渲染设置变化；写进代码就是红线 4 禁的"硬编码阈值"。
 * 让用户在标定时各采一次，阈值就由目标设备自己产生。
 *
 * 实测参考（**只用于验证，不进入代码**）：`onMean ≈ 165`、`offMean ≈ 122.6` ⇒ 分界 ≈ 143.8。
 */
data class BrightnessBaseline(val onMean: Double, val offMean: Double) {

    init {
        require(onMean.isFinite() && offMean.isFinite()) { "基线必须是有限数值：on=$onMean off=$offMean" }
        require(onMean > offMean) { "亮态基线($onMean)必须大于灰态基线($offMean)" }
    }

    /** 分界：亮 / 灰的中点。 */
    val threshold: Double get() = (onMean + offMean) / 2.0

    /** 判定：`>=` 分界即"亮"。取 `>=` 是为了在恰好等于中点时偏向"亮"—— 该值本身来自标定，不会长期落在中点上。 */
    fun isOn(mean: Double): Boolean = mean >= threshold
}
