package com.example.mastermechanic.recognition

import java.util.Random

/**
 * 测试辅助：确定性合成图像与模板（固定种子——测试数据本身可复现）。
 *
 * 纹理统一取 80..176 的温和区间：亮度 / 对比度变换测试中不越界，
 * 避免裁剪破坏 NCC 的归一化不变量。
 */
internal object SyntheticImages {

    /** 底噪图：灰度 100..139 的均匀噪声。 */
    fun background(width: Int, height: Int, seed: Long): GrayImage {
        val random = Random(seed)
        val pixels = ByteArray(width * height)
        for (i in pixels.indices) pixels[i] = (100 + random.nextInt(40)).toByte()
        return GrayImage(width, height, pixels)
    }

    /** 固定种子的纹理块（80..176）。 */
    fun pattern(width: Int, height: Int, seed: Long): ByteArray {
        val random = Random(seed)
        val pixels = ByteArray(width * height)
        for (i in pixels.indices) pixels[i] = (80 + random.nextInt(97)).toByte()
        return pixels
    }

    /** 把纹理块绘制到图像 (x, y) 处（直接修改像素）。 */
    fun drawPattern(image: GrayImage, x: Int, y: Int, width: Int, height: Int, pattern: ByteArray) {
        for (row in 0 until height) {
            for (col in 0 until width) {
                image.pixels[(y + row) * image.width + (x + col)] = pattern[row * width + col]
            }
        }
    }

    /** 从图像裁剪 → 模板。 */
    fun crop(image: GrayImage, x: Int, y: Int, width: Int, height: Int): Template {
        val pixels = ByteArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                pixels[row * width + col] = image.pixels[(y + row) * image.width + (x + col)]
            }
        }
        return Template(width, height, pixels)
    }

    /** 亮度 / 对比度线性变换：p' = contrast·(p − 128) + 128 + brightness（越界裁剪）。 */
    fun transformed(image: GrayImage, contrast: Double, brightness: Double): GrayImage {
        val pixels = ByteArray(image.pixels.size)
        for (i in pixels.indices) {
            val v = contrast * ((image.pixels[i].toInt() and 0xFF) - 128) + 128 + brightness
            pixels[i] = v.coerceIn(0.0, 255.0).toInt().toByte()
        }
        return GrayImage(image.width, image.height, pixels)
    }

    /** 半透明遮罩：区域内像素 = (1−alpha)·原值 + alpha·覆盖值。 */
    fun overlay(image: GrayImage, x: Int, y: Int, width: Int, height: Int, alpha: Double, value: Int) {
        for (row in 0 until height) {
            for (col in 0 until width) {
                val i = (y + row) * image.width + (x + col)
                val p = image.pixels[i].toInt() and 0xFF
                image.pixels[i] = ((1 - alpha) * p + alpha * value).toInt().toByte()
            }
        }
    }
}
