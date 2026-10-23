package com.example.mastermechanic.service

import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.NameReader
import com.example.mastermechanic.recognition.PixelBounds

/**
 * 新引擎的**回退壳**（M6 换 OCR 引擎，计划 §6 / §8「失败路径」）。
 *
 * 红线 3 要求"不静默降级"，所以：
 * - 首次**抛异常**（模型缺失 / ORT 初始化失败 / 会话被回收）⇒ 当场切到 [fallback] 并**打日志写明原因**；
 * - 只切一次，之后固定用回退实现（不再反复试，避免每帧都抛一次）；
 * - 回退实现自己也会抛（例如设备没有 ML Kit 模型）⇒ 如实再抛，由调用方按"未找到"处理。
 *
 * ⚠ **读不到字（空列表）不算失败**：那是"这一屏确实没有目标"，与"引擎坏了"是两件事，
 * 不能因为一次没读到就悄悄换引擎（否则真机上永远比不出谁更准）。
 */
class FallbackNameReader(
    private val primary: NameReader,
    private val fallback: () -> NameReader,
) : NameReader {

    private var current: NameReader = primary
    private var fellBack = false

    override fun read(image: GrayImage, region: PixelBounds): List<NameCandidate> =
        try {
            current.read(image, region)
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            if (fellBack) throw t
            fellBack = true
            current = fallback()
            MmLog.w(TAG, "新 OCR 引擎不可用，已回退 ML Kit（原因：${t.message ?: t.javaClass.simpleName}）")
            current.read(image, region)
        }

    private companion object {
        const val TAG = "MM-NameReader"
    }
}
