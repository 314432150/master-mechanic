package com.example.mastermechanic.settings

import android.content.Context
import java.io.File

/** 偏好读取结果：[recoveredReason] 非空表示**发生了回落**（调用方须记日志，ADR-006：不静默）。 */
data class AppPreferencesResult(
    val preferences: AppPreferences,
    val recoveredReason: String? = null,
)

/**
 * 应用偏好存取（ADR-006）：应用私有目录。
 *
 * 分流口径与"位置类数据"一致（**缺失不算异常、损坏回落默认**）：
 * - 文件**不存在**（首次安装）= 用默认值，**不写盘**（没有发生过的事不该留痕）；
 * - 文件**不可用**（解析失败）= 回落默认并**重写文件**，原因回传给调用方写日志。
 *
 * 核心实现只吃 [File]：编解码与分流语义全部可在 JVM 单测覆盖。
 */
object AppPreferencesStore {

    private const val DIR_NAME = "settings"
    private const val FILE_NAME = "prefs.txt"

    fun preferencesFile(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    fun save(context: Context, preferences: AppPreferences): File =
        save(preferencesFile(context), preferences)

    /** 保存到指定文件（纯文件版，JVM 可测）；临时文件 + rename 防半写。 */
    fun save(file: File, preferences: AppPreferences): File {
        file.parentFile?.mkdirs()
        val text = AppPreferencesCodec.encode(preferences)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
        return file
    }

    /** 加载偏好（缺失 → 默认且不写盘；损坏 → 默认 + 重写 + 原因）。 */
    fun loadOrRecover(file: File): AppPreferencesResult {
        if (!file.isFile) return AppPreferencesResult(AppPreferences.DEFAULT)
        return try {
            AppPreferencesResult(AppPreferencesCodec.decode(file.readText(Charsets.UTF_8)))
        } catch (e: IllegalArgumentException) {
            save(file, AppPreferences.DEFAULT)
            AppPreferencesResult(
                AppPreferences.DEFAULT,
                "偏好文件不可用（${e.message ?: e.javaClass.simpleName}），已回落默认",
            )
        }
    }

    fun loadOrRecover(context: Context): AppPreferencesResult =
        loadOrRecover(preferencesFile(context))
}
