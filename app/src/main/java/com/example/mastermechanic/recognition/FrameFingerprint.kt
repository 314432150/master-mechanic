package com.example.mastermechanic.recognition

/**
 * **画面指纹**（2026-10-23）：对若干区域里的像素算一个 64 位摘要，用来回答一个问题 ——
 * *"这一屏跟上次读文字时，是不是**一模一样**？"*
 *
 * ## 为什么需要它
 *
 * 换 Paddle rec-only 引擎后，"选服务器"这一步一屏要读 26 行（13 行带 × 两列），
 * 每次调用都有固定开销 ⇒ 一轮 ≈1s。而这一步为了防列表回弹，要求**两次一致的读数**才允许下手，
 * 于是同样 26 行要读两遍，一步 2.3s（旧引擎整块识别只要 1.1s）。
 *
 * 但如果**像素完全相同**，第二遍读出来的必然是同一份结果 —— 这时复用第一遍的候选是**等价**的，
 * 不是"拿旧画面下结论"。指纹就是用来判这条的。
 *
 * ## 判据强度（与 2026-09-29 那次事故的区别）
 *
 * 那次事故的成因是"**画面已经变了**（列表回弹 2 行）却还在用 1 秒前的读数和新的帧时刻"，
 * 于是被判成"两次读数一致"。这里的门槛相反：**像素先要一致**才允许复用 ——
 * 列表只要动一个像素（哪怕只是回弹 1px），指纹就变，这一轮照旧真读。
 *
 * ## 代价
 *
 * 采样计数（默认约 4 万个点）而不是全像素：两列 417×1119 共 ≈93 万像素，全量哈希要 ~2ms，
 * 采样后 <0.2ms —— 它出现在**每轮**（200ms 一轮）的路径上，必须便宜。
 * 采样步长固定，因此"同样的画面"永远得到同样的指纹；不同画面碰撞的概率可以忽略
 * （真机上列表一移动就是成片像素变化，不是一两个点）。
 */
object FrameFingerprint {

    /** 采样后希望拿到的点数（决定步长；太小会漏掉局部变化，太大会变慢）。 */
    private const val TARGET_SAMPLES = 40_000

    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L

    /**
     * 算 [regions] 的指纹；**空区域 / 越界部分按"跳过"处理**（不参与哈希），
     * 因此区域列表为空时返回 [EMPTY]（调用方据此不复用）。
     */
    fun of(image: GrayImage, regions: List<PixelBounds>): Long {
        if (regions.isEmpty()) return EMPTY
        var sampled = 0L
        for (region in regions) {
            val x0 = region.x0.coerceIn(0, image.width)
            val x1 = region.x1.coerceIn(0, image.width)
            val y0 = region.y0.coerceIn(0, image.height)
            val y1 = region.y1.coerceIn(0, image.height)
            if (x1 <= x0 || y1 <= y0) continue
            sampled += (x1 - x0).toLong() * (y1 - y0)
        }
        if (sampled <= 0L) return EMPTY
        // 面积越大步长越大：保证总点数 ≈ TARGET_SAMPLES（每次只算一次 sqrt，成本可忽略）
        val stride = maxOf(1, Math.sqrt(sampled / TARGET_SAMPLES.toDouble()).toInt())

        var hash = FNV_OFFSET
        for (region in regions) {
            val x0 = region.x0.coerceIn(0, image.width)
            val x1 = region.x1.coerceIn(0, image.width)
            val y0 = region.y0.coerceIn(0, image.height)
            val y1 = region.y1.coerceIn(0, image.height)
            var y = y0
            while (y < y1) {
                val row = y * image.width
                var x = x0
                while (x < x1) {
                    hash = (hash xor (image.pixels[row + x].toLong() and 0xFF)) * FNV_PRIME
                    x += stride
                }
                y += stride
            }
        }
        return hash
    }

    /** 空区域 / 越界：不做复用判断（调用方按"读到新结果"处理）。 */
    const val EMPTY = 0L
}
