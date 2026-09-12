package com.example.mastermechanic.calibration

/**
 * 测试辅助：确定性合成 RGBA 帧（T1-5a）。
 *
 * 灰度纹理按等值 RGB 展开为 RGBA——BT.601 对 (v,v,v) 恒等（Y = v，四舍五入不越界），
 * 因此灰度还原结果与 [com.example.mastermechanic.recognition.SyntheticImages] 的纹理一致。
 */
internal object SyntheticRgba {

    /** 灰度值展开为 RGBA 帧（rowStride 可大于一行像素，模拟对齐填充）。 */
    fun fromGray(pixels: ByteArray, width: Int, height: Int, rowStride: Int = width * 4): ByteArray {
        require(rowStride >= width * 4) { "行跨度不足以容纳一行像素" }
        val out = ByteArray((height - 1) * rowStride + width * 4)
        for (y in 0 until height) {
            var dst = y * rowStride
            for (x in 0 until width) {
                val v = pixels[y * width + x]
                out[dst++] = v
                out[dst++] = v
                out[dst++] = v
                out[dst++] = 0xFF.toByte()
            }
        }
        return out
    }
}
