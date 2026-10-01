package com.example.mastermechanic.calibration

import com.example.mastermechanic.capture.RgbaToGray
import com.example.mastermechanic.recognition.Template

/**
 * 模板提取（T1-5a，纯逻辑）：从标定帧的 RGBA 数据中裁剪指定矩形，生成灰度模板。
 *
 * 灰度化复用 [RgbaToGray]（与运行时帧灰度是同一实现），保证标定模板与运行帧同源（§5-4）。
 * 矩形为半开区间 [x0, x1) × [y0, y1)，须落在帧内且非空。
 */
object TemplateExtractor {

    /**
     * **缩略模板的长边上限**（2026-09-29，取 64）：只给"只圈区域"的锚点用。
     *
     * 64 是"看得清是哪一块"与"字节数可忽略"的折中：64×64 = 4096 字节，vs
     * `server_list_area` 整块 2532×1118 = **283 万字节**。
     */
    const val PREVIEW_MAX_PX = 64

    fun extract(
        rgba: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        rowStride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Template {
        require(x0 >= 0 && y0 >= 0 && x1 <= frameWidth && y1 <= frameHeight && x0 < x1 && y0 < y1) {
            "裁剪区域 [$x0,$x1)×[$y0,$y1) 超出帧 ${frameWidth}x$frameHeight 或为空"
        }
        val gray = RgbaToGray.toGray(rgba, frameWidth, frameHeight, rowStride)
        val width = x1 - x0
        val height = y1 - y0
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            val rowStart = (y0 + y) * frameWidth
            gray.pixels.copyInto(pixels, y * width, rowStart + x0, rowStart + x1)
        }
        return Template(width, height, pixels)
    }

    /**
     * **缩略模板**（2026-09-29）：把模板按"长边不超过 [maxSide]"降采样。
     *
     * ## 为什么需要它
     *
     * **只圈区域**的锚点（`PatrolAnchors.nonLocatableAnchors`：列表区域 / 好友名称列 / 列竖带）
     * **不参与模板匹配** —— 它们只是"把这一片送去读文字"的范围（窗口生效，模板不生效）。
     * 可它们过去照样把**整块**存进产物：真机实录 `server_list_area` 2532×1118（283 万字节）、
     * `friend_list_area` 859×1091 ⇒ 产物被撑到 **5.4MB**，重载时 `Base64.decode` 申请 3.7MB 失败
     * ⇒ **帧线程 OOM、整个采集会话没了**（progress 第 258 条）。
     * 而模板在产物里**只用于标定页的缩略预览**，根本不需要那个分辨率。
     *
     * ⚠ **绝不能给参与匹配的信号用**：降采样后的像素与原图不同，模板匹配会直接失效。
     * 调用方只有两处：写入时（把区域锚点存成缩略图）与加载时的一次性瘦身（[CalibrationStore]）。
     *
     * 抽样用**最近邻**（不插值）：只求"看得出来是哪一块"，不求好看，也避免引入任何平滑假设。
     *
     * @return 长边已经不超过 [maxSide] 时**原样返回同一个对象**（不做无谓的复制；调用方据此判断"要不要落盘"）
     */
    fun preview(template: Template, maxSide: Int = PREVIEW_MAX_PX): Template {
        require(maxSide >= 1) { "缩略长边至少 1：$maxSide" }
        val longSide = maxOf(template.width, template.height)
        if (longSide <= maxSide) return template
        val scale = longSide.toDouble() / maxSide
        val width = maxOf(1, (template.width / scale).toInt())
        val height = maxOf(1, (template.height / scale).toInt())
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            // 目标像素中心 → 原图坐标（-0.5 是为了"覆盖整片"：否则首尾各丢半格）
            val srcY = ((y + 0.5) * scale - 0.5).toInt().coerceIn(0, template.height - 1)
            val srcRow = srcY * template.width
            for (x in 0 until width) {
                val srcX = ((x + 0.5) * scale - 0.5).toInt().coerceIn(0, template.width - 1)
                pixels[y * width + x] = template.pixels[srcRow + srcX]
            }
        }
        return Template(width, height, pixels)
    }
}
