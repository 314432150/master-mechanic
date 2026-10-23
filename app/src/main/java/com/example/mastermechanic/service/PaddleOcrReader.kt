package com.example.mastermechanic.service

import android.graphics.Bitmap
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.recognition.DigitReader
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.NameReader
import com.example.mastermechanic.recognition.PixelBounds

/**
 * Paddle 引擎的**装配体**（只在立牌流程那条线上存在）：同一个 [OrtRecEngine] 同时承担两条通道。
 *
 * - [NameReader.read]：名单类区域 → 先按行带切块再逐行 rec（见 [PaddleNameReader]）；
 * - [DigitReader.readDigits]：分数块 → **整块直接 rec**（区域本身就是一个数，不需要切行带）。
 *
 * 为什么分数不走 [PaddleNameReader]：那是"切行带 + 逐行"的口径，对单个数字块是多余的一跳；
 * 而且离线实测（`docs/verification/m6/t6-1-ocr-offline-gate.md` §3.5）整块直喂 rec 就是 4~20ms。
 *
 * ⚠ `DigitReader` 只在立牌流程分支上存在（`stage/m6` 从 `main` 拉，没有这个接口），所以本类不进那条分支。
 */
class PaddleOcrReader(private val engine: OrtRecEngine) : NameReader, DigitReader {

    private val names = PaddleNameReader.of(engine)

    override fun read(image: GrayImage, region: PixelBounds): List<NameCandidate> =
        names.read(image, region)

    override fun readDigits(image: GrayImage, region: PixelBounds): List<NameCandidate> {
        val bitmap = toBitmap(image, region)
        val text = try {
            engine.recognize(bitmap)
        } catch (t: Throwable) {
            // 分数块这一跳失败就如实报"没读到"（不猜数字），与 ML Kit 数字通道的失败口径一致
            MmLog.w(TAG, "分数块识别失败（按未读到处理）：${t.message ?: t.javaClass.simpleName}")
            return emptyList()
        } finally {
            bitmap.recycle()
        }
        // 只留数字：花体分数旁边可能粘着「分」之类（真机 ML Kit 版同样只取数字）
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return emptyList()
        return listOf(
            NameCandidate(
                text = digits,
                x = region.x0,
                y = region.y0,
                width = (region.x1 - region.x0).coerceAtLeast(1),
                height = (region.y1 - region.y0).coerceAtLeast(1),
            ),
        )
    }

    private companion object {
        const val TAG = "MM-PaddleName"
    }

    private fun toBitmap(image: GrayImage, region: PixelBounds): Bitmap {
        val width = (region.x1 - region.x0).coerceAtLeast(1)
        val height = (region.y1 - region.y0).coerceAtLeast(1)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = (region.y0 + y) * image.width
            for (x in 0 until width) {
                val v = image.pixels[row + region.x0 + x].toInt() and 0xFF
                pixels[y * width + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
