package com.example.mastermechanic.capture

import kotlin.math.abs

/**
 * **整帧灰度签名**（2026-09-28）：把一帧 RGBA 隔点采样成灰度点阵，回答"自上一枪以来画面到底变了多少"。
 *
 * ## ⚠ 为什么不能用识别层那份 `GrayImage`（真机上刚踩过）
 *
 * 帧线程交给识别的那份是 [RgbaToGray.toGrayRegions] 的结果 —— **只转换识别窗口，窗口外全部置零**
 * （[RgbaToGray] 的 KDoc 与 `FriendListOcrTryout` 都记过这个坑："窗口外置零"对**识别**等价，
 * 对**整帧比较**则是致命的）。
 *
 * 2026-09-28 真机实录：三个**完全不同**的活动弹窗（金色「小黄鸭联动星元兑换」→ 紫色「比翼连心家具礼包」
 * → 蓝色「三丽鸥家族联动时装」）被依次关掉，而按窗口化灰度算出来的"画面变化"只有 **0.0% ~ 3.6%**
 * —— 据此差点否掉一条本来可行的判据（用户当场用三张截图指出"帧差距明明很大"）。
 *
 * ⇒ 本对象**直接从原始 RGBA 采样**：不做整帧转换（460 万字节的分配 + 460 万次运算纯属浪费，
 * 判断"整屏换了一幅画"用 57 万个点足够）。
 *
 * ## 采样口径
 *
 * 步长 [DEFAULT_STEP] = 8 ⇒ 3168×1440 得 **570,240 点**，覆盖整屏（含弹窗外的一切）；
 * 逐行按 `rowStride` 寻址（采集缓冲可能含对齐填充）。"某点变了" = 灰度差 ≥ [PIXEL_DELTA]。
 */
object FrameSignature {

    /** 默认采样步长（每 N 个像素取一点）。 */
    const val DEFAULT_STEP = 8

    /** "这个点变了"的灰度差阈值（0..255）：太小会把动画 / 倒计时也算成"变了一幅画"。 */
    const val PIXEL_DELTA = 24

    /** 单像素字节数（RGBA_8888）；与 `RgbaToGray.PIXEL_BYTES` 同一口径（那边是 private）。 */
    private const val PIXEL_BYTES = 4

    /**
     * 采一帧的签名：逐行、每 [step] 个像素取一点的亮度。
     *
     * @return 定长点阵；同一几何下长度恒定（几何变了 ⇒ 长度变 ⇒ 不可比，见 [changedPercent]）。
     */
    fun sample(
        rgba: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        step: Int = DEFAULT_STEP,
    ): ByteArray {
        require(width > 0 && height > 0) { "帧尺寸必须为正：${width}x$height" }
        require(step > 0) { "采样步长必须为正：$step" }
        require(rowStride >= width * PIXEL_BYTES) { "行跨度 $rowStride 小于一行像素字节数（${width * PIXEL_BYTES}）" }
        require(rgba.size >= (height - 1) * rowStride + (width - 1) * PIXEL_BYTES + 3) {
            "RGBA 数据不足以覆盖 ${width}x$height（rowStride=$rowStride，实际 ${rgba.size} 字节）"
        }

        val perRow = (width + step - 1) / step
        val out = ByteArray(height * perRow)
        var index = 0
        for (y in 0 until height) {
            val rowStart = y * rowStride
            var x = 0
            while (x < width) {
                out[index++] = lumaAt(rgba, rowStart + x * PIXEL_BYTES)
                x += step
            }
        }
        return out
    }

    /**
     * 采**一块矩形区域**的签名（A2 局部证据，2026-10-02）。
     *
     * ## 为什么需要它（"阈值判断总有例外"那条口径）
     *
     * 整帧变化只会回答"变了多少"，**回答不了"变的是什么"** —— 实测"换了一个新弹窗"是 36.6~43.5%，
     * "弹窗没了、露出下层界面"是 53.3~77.2%，两组只隔一道十几点的空档；50 那道线两边都踩着例外
     * （真机 2026-10-02：点完一枪弹窗**内容换了一幅**=60.6% ⇒ 被误判成"弹窗没了"而停手，
     * 而同一屏随后两轮弹窗标志仍以 0.9958 命中）。
     * 2026-09-29 撤销整帧比较时留下的口径是：**别看整帧，改看"X 自己那一小块"**
     * （见 `action/PopupCloseController` 的 `anchorKeptPercent` 与 `docs/progress.md` 第 421/422 条）。
     *
     * 本方法是那条口径的"尺子"：把**关闭控件锚点窗口**那一小块采成签名 ⇒ 与上一枪那张同区域签名比
     * ⇒ "那个控件还在原样吗"（还在 = 弹窗还在、只是内容换了一幅；变样了 = 弹窗没了、下层顶上来）。
     *
     * ⚠ 与 [sample] 同一套亮度口径与步长，**同一矩形**两次采样的长度恒定 ⇒ 可直接丢给 [changedPercent]。
     * 矩形不同（或几何变了）⇒ 长度不同 ⇒ [changedPercent] 如实给 `null`（不可比 ≠ 没变）。
     *
     * @param left 区域左边界（含）；[right] 右边界（**不含**）；[top] / [bottom] 同理。
     */
    fun sampleRegion(
        rgba: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        step: Int = DEFAULT_STEP,
    ): ByteArray {
        require(width > 0 && height > 0) { "帧尺寸必须为正：${width}x$height" }
        require(step > 0) { "采样步长必须为正：$step" }
        require(rowStride >= width * PIXEL_BYTES) { "行跨度 $rowStride 小于一行像素字节数（${width * PIXEL_BYTES}）" }
        require(left in 0 until width && right in (left + 1)..width) {
            "区域横向越界：[${left}, $right)（帧宽 $width）"
        }
        require(top in 0 until height && bottom in (top + 1)..height) {
            "区域纵向越界：[${top}, $bottom)（帧高 $height）"
        }
        require(rgba.size >= (bottom - 1) * rowStride + (right - 1) * PIXEL_BYTES + 3) {
            "RGBA 数据不足以覆盖 ${width}x$height（rowStride=$rowStride，实际 ${rgba.size} 字节）"
        }

        val cols = (right - left + step - 1) / step
        val out = ByteArray((bottom - top) * cols)
        var index = 0
        for (y in top until bottom) {
            val rowStart = y * rowStride
            var x = left
            while (x < right) {
                out[index++] = lumaAt(rgba, rowStart + x * PIXEL_BYTES)
                x += step
            }
        }
        return out
    }

    /**
     * 单点亮度（BT.601、整数四舍五入）：与 [RgbaToGray.toGray] 同一套公式
     * （公式在这里重写一份而不是去调它：那条路是**每帧 460 万次**的热路径，不该为"采样几个点"改它）。
     *
     * @param offset 该像素在 [rgba] 里的**首字节偏移**（调用方按 `rowStride` 算好，本方法不猜布局）。
     */
    fun lumaAt(rgba: ByteArray, offset: Int): Byte {
        val r = rgba[offset].toInt() and 0xFF
        val g = rgba[offset + 1].toInt() and 0xFF
        val b = rgba[offset + 2].toInt() and 0xFF
        return ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
    }

    /**
     * 两组签名里"变了"的点占比（%，0..100）。
     *
     * @return **尺寸不同 / 空签名给 `null`** —— 这类情况不可比，调用方必须当成"不知道"，
     *   绝不能当成"没变"（"静默失败"正是 2026-09-28 那次踩坑的味道）。
     */
    fun changedPercent(before: ByteArray, after: ByteArray, delta: Int = PIXEL_DELTA): Double? {
        if (before.isEmpty() || before.size != after.size) return null
        var changed = 0
        for (i in before.indices) {
            // ⚠ 亮度存 Byte（-128..127）而值是 0..255 ⇒ 必须按**无符号**比：
            // 否则"亮 ↔ 暗"（跨 128）会被误算成"变了"（2026-09-28 修的老 bug）
            val a = after[i].toInt() and 0xFF
            val b = before[i].toInt() and 0xFF
            if (abs(a - b) >= delta) changed++
        }
        return changed * 100.0 / before.size
    }
}
