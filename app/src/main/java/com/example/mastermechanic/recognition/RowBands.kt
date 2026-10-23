package com.example.mastermechanic.recognition

/**
 * 把一块区域**按行切带**（纯逻辑：只吃灰度像素，不碰安卓框架）。
 *
 * ## 为什么需要它
 *
 * 换引擎后走的是 **rec-only**（`OcrRec` 那条路不带检测模型，见 `docs/plans/m6-ocr-engine.md` §3.1）：
 * rec 一次只认**一行字**，把「数字 + 立牌名」或整片好友名单塞进去会读出 `7号` / 空。而检测模型（det）
 * 既慢（整屏 2.1~3.1s）又会把等级数字并进名字 ⇒ 行切分改在这里用**投影法**自己做，代价是零下载、几毫秒。
 *
 * ## 口径
 *
 * 1. 统计整块区域的均值与标准差，**偏离均值超过 [DEVIATION] 个标准差**的像素算"墨"（深字浅底、浅字深底都成立，
 *    不必先知道配色）；
 * 2. 某一行墨像素数 ≥ `max(2, 宽/200)` 即判为"有字"；
 * 3. 相邻有字行之间的空隙 ≤ [MERGE_GAP] 直接并（抗锯齿 / 笔画间隙）；
 * 4. 带高 < [MIN_HEIGHT] 的丢掉（噪点、分隔线）；
 * 5. 最多返回 [MAX_BANDS] 条（防御：区域框错时不要退化成几千次推理）。
 *
 * 返回的坐标是**运行帧坐标**（`x` 一律是整块区域的左右边界——名字行右对齐的按钮不参与识别，
 * 由调用方在裁列时先挡掉）。
 */
object RowBands {

    /** 判定"偏离均值多少倍标准差"算墨。 */
    const val DEVIATION = 1.0

    /** 相邻带之间允许的最大空隙（像素）。 */
    const val MERGE_GAP = 4

    /** 最小带高（像素）：低于它的都是噪点。 */
    const val MIN_HEIGHT = 10

    /** 单次最多切几条带。 */
    const val MAX_BANDS = 40

    fun split(image: GrayImage, region: PixelBounds): List<PixelBounds> {
        val x0 = region.x0.coerceIn(0, image.width)
        val x1 = region.x1.coerceIn(0, image.width)
        val y0 = region.y0.coerceIn(0, image.height)
        val y1 = region.y1.coerceIn(0, image.height)
        if (x1 - x0 < 2 || y1 - y0 < 2) return emptyList()

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in y0 until y1) {
            val row = y * image.width
            for (x in x0 until x1) {
                val v = image.pixels[row + x].toInt() and 0xFF
                sum += v
                sumSq += v.toDouble() * v
                count++
            }
        }
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        val sigma = Math.sqrt(variance)
        // 整块几乎没有对比度（纯色 / 全黑）：没有行可切
        if (sigma < MIN_CONTRAST) return emptyList()

        val threshold = DEVIATION * sigma
        val minInkPerRow = maxOf(2, (x1 - x0) / 200)

        // 先求每行墨量，再扫出带
        val ink = IntArray(y1 - y0)
        for (y in y0 until y1) {
            val row = y * image.width
            var hits = 0
            for (x in x0 until x1) {
                val v = image.pixels[row + x].toInt() and 0xFF
                if (Math.abs(v - mean) > threshold) hits++
            }
            ink[y - y0] = hits
        }

        val bands = ArrayList<PixelBounds>()
        var start = -1
        var lastInk = -1
        for (offset in ink.indices) {
            val hasInk = ink[offset] >= minInkPerRow
            if (hasInk) {
                if (start < 0) {
                    start = offset
                } else if (offset - lastInk > MERGE_GAP) {
                    bands += band(image, x0, x1, y0 + start, y0 + lastInk + 1)
                    start = offset
                }
                lastInk = offset
            }
        }
        if (start >= 0 && lastInk >= start) {
            bands += band(image, x0, x1, y0 + start, y0 + lastInk + 1)
        }
        return bands.filter { it.y1 - it.y0 >= MIN_HEIGHT }.take(MAX_BANDS)
    }

    private fun band(image: GrayImage, x0: Int, x1: Int, top: Int, bottom: Int) =
        PixelBounds(
            x0 = x0,
            y0 = top.coerceAtLeast(0),
            x1 = x1.coerceAtMost(image.width),
            y1 = bottom.coerceAtMost(image.height),
        )

    /** 低于这个标准差的区域视为"没有可读对比度"。 */
    private const val MIN_CONTRAST = 6.0
}
