package com.example.mastermechanic.recognition

/**
 * PP-OCR rec 的两件**纯逻辑**（不碰安卓框架，单测可跑）：输入宽度换算 + CTC 贪心解码。
 *
 * 放在 `recognition/` 而不是 `service/`，是为了守住 ADR-003 §3 的边界：
 * 识别层只做"数字进、文字出"的纯计算，安卓侧的 ONNX 会话在 `service/OrtRecEngine`。
 */
object RecInput {

    /** rec 模型的固定输入高度（PP-OCRv4 的推理规格）。 */
    const val HEIGHT = 48

    /** 最宽输入（再宽就压到这条线，避免一张图撑爆显存/内存）。 */
    const val MAX_WIDTH = 640

    /**
     * 按原图长宽比算出的输入宽度（保持字形不被拉伸）。
     *
     * 这就是 PP-OCR 的 `RecResizeImg`：高固定 48，宽按比例，**不补边**（单张推理无需对齐批次）。
     */
    fun width(sourceWidth: Int, sourceHeight: Int): Int {
        require(sourceHeight > 0 && sourceWidth > 0) { "原图尺寸必须为正：${sourceWidth}x$sourceHeight" }
        val scaled = Math.round(HEIGHT.toDouble() * sourceWidth / sourceHeight).toInt()
        return scaled.coerceIn(1, MAX_WIDTH)
    }
}

/**
 * CTC 贪心解码：逐时间步取最大类，**去重相邻相同**、**丢掉空白类**。
 *
 * 为什么要单独拎出来：这是"模型输出 → 屏幕文字"的最后一跳，也是最容易写错的一跳
 * （忘了合并重复、或把 blank 也拼进去，就会变成 `9999` / 掺空字符）。
 * 它只依赖一个 `label(index) -> String?`，所以单测可以塞假的 logits 直接钉住行为。
 */
object CtcDecode {

    /**
     * @param logits 展平后的 `[timeSteps × classes]`，行主序（时间步在外层）
     * @param label 类别下标 → 字符；返回 `null` 表示"不是字符"（空白类）
     */
    fun greedy(
        logits: FloatArray,
        timeSteps: Int,
        classes: Int,
        label: (Int) -> String?,
    ): String {
        require(classes > 0 && timeSteps > 0) { "logits 形状非法：${timeSteps}x$classes" }
        require(logits.size >= timeSteps * classes) {
            "logits 长度 ${logits.size} 不足 ${timeSteps}x$classes"
        }
        val text = StringBuilder()
        var previous = -1
        for (t in 0 until timeSteps) {
            val base = t * classes
            var best = 0
            var bestScore = logits[base]
            for (c in 1 until classes) {
                val score = logits[base + c]
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
            if (best != previous) {
                label(best)?.let { text.append(it) }
            }
            previous = best
        }
        return text.toString()
    }
}
