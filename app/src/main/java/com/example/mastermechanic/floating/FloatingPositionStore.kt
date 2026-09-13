package com.example.mastermechanic.floating

import android.content.Context
import java.io.File

/** 位置读取结果：[recoveredReason] 非空表示**发生了回落**（调用方须记日志，ADR-006：不静默）。 */
data class FloatingPositionResult(
    val positions: FloatingPositions,
    val recoveredReason: String? = null,
)

/**
 * 悬浮窗位置存取（FR-07 / ADR-006）：应用私有目录（随应用卸载清除）。
 *
 * **损坏分流**（ADR-006 第 3 条）：本数据的"损坏"后果轻——**回落默认位置并重写文件**，
 * 不把界面卡住；但必须把原因带回给调用方**写日志**（丢失无后果，静默却不可接受）。
 *
 * 与 [com.example.mastermechanic.patrol.PatrolConfigStore] 一样，**核心实现只吃 [File]**：
 * 编解码与"缺失 / 损坏 / 回落"语义全部可在 JVM 单测覆盖，`Context` 只出现在文件路径这一层。
 */
object FloatingPositionStore {

    private const val DIR_NAME = "floating"
    private const val FILE_NAME = "window.txt"

    fun positionFile(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    fun save(
        context: Context,
        positions: FloatingPositions,
        screenWidth: Int,
        screenHeight: Int,
    ): File = save(positionFile(context), positions, screenWidth, screenHeight)

    /** 保存到指定文件（纯文件版，JVM 可测）；临时文件 + rename 防半写。 */
    fun save(
        file: File,
        positions: FloatingPositions,
        screenWidth: Int,
        screenHeight: Int,
    ): File {
        file.parentFile?.mkdirs()
        val text = FloatingPositionCodec.encode(positions, screenWidth, screenHeight)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
        return file
    }

    /**
     * 加载位置：文件不存在 / 解析失败 → **回落默认并重写文件**（并把原因带回写日志）；
     * **旧格式（v1 / v2）→ 顺手重写成当前格式**（手柄位置改为布局常量、状态标签位置保留）。
     * 读取成功（且已是当前格式）返回解析结果与 `recoveredReason = null`。
     */
    fun loadOrRecover(
        file: File,
        screenWidth: Int,
        screenHeight: Int,
    ): FloatingPositionResult {
        if (!file.isFile) {
            save(file, FloatingPositions.DEFAULT, screenWidth, screenHeight)
            return FloatingPositionResult(FloatingPositions.DEFAULT, "位置文件不存在，已按默认位置重建")
        }
        return try {
            val text = file.readText(Charsets.UTF_8)
            val positions = FloatingPositionCodec.decode(text)
            if (FloatingPositionCodec.needsRewrite(text)) {
                save(file, positions, screenWidth, screenHeight)
                FloatingPositionResult(
                    positions,
                    "位置文件为旧格式，已重写：手柄位置改用固定布局（右侧、纵向三分之一），状态标签位置保留",
                )
            } else {
                FloatingPositionResult(positions)
            }
        } catch (e: IllegalArgumentException) {
            save(file, FloatingPositions.DEFAULT, screenWidth, screenHeight)
            FloatingPositionResult(
                FloatingPositions.DEFAULT,
                "位置文件不可用（${e.message ?: e.javaClass.simpleName}），已回落默认位置",
            )
        }
    }

    /** 重置为默认位置（FR-07「始终可被找回」的兜底入口）；返回写入的文件。 */
    fun reset(context: Context, screenWidth: Int, screenHeight: Int): File =
        save(context, FloatingPositions.DEFAULT, screenWidth, screenHeight)

    /**
     * 只读取（供界面展示"当前停在哪"）：文件不存在 / 损坏返回 null，**不写盘**——
     * 界面只做展示，不做修复；修复是 [loadOrRecover]（挂载时）的职责。
     */
    fun loadOrNull(file: File): FloatingPositions? {
        if (!file.isFile) return null
        return try {
            FloatingPositionCodec.decode(file.readText(Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun loadOrNull(context: Context): FloatingPositions? = loadOrNull(positionFile(context))
}
