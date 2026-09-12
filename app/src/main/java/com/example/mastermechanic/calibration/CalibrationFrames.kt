package com.example.mastermechanic.calibration

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer

/**
 * 标定帧文件工具（T1-5b）：PNG 帧的解码——预览缩略图与原始 RGBA 读出。
 *
 * 依赖安卓框架，仅 UI 侧使用；模板提取本身为纯逻辑（见 [TemplateExtractor]）。
 */
object CalibrationFrames {

    /** 原始帧的 RGBA 像素与尺寸。 */
    class FrameRgba(val pixels: ByteArray, val width: Int, val height: Int)

    /** 预览缩略图与原始帧尺寸（标注换算以原始尺寸为准，与缩略图采样率无关）。 */
    class Preview(val bitmap: Bitmap, val frameWidth: Int, val frameHeight: Int)

    /** 读取原分辨率 RGBA（压紧行跨度 = 宽×4）；解码失败返回 null（损坏文件）。 */
    fun readRgba(file: File): FrameRgba? {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        return try {
            val pixels = ByteArray(bitmap.width * bitmap.height * 4)
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
            FrameRgba(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** 解码预览缩略图（宽 ≤ maxWidth 的最大 2 次幂下采样）；失败返回 null。 */
    fun decodePreview(file: File, maxWidth: Int): Preview? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        return Preview(bitmap, bounds.outWidth, bounds.outHeight)
    }
}
