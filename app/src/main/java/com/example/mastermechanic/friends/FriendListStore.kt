package com.example.mastermechanic.friends

import android.content.Context
import java.io.File

/**
 * 好友清单存取（FR-07 / ADR-006）：应用私有目录（随应用卸载清除，不跨设备复用）。
 *
 * 与 [com.example.mastermechanic.servers.ServerListStore] 完全同构：
 *
 * - **核心实现只吃 [File]**：编解码之外的语义（不存在 → null、解析失败 → 抛错、半写防护）
 *   全部可在 JVM 单测覆盖，`Context` 仅出现在"文件路径"这一层；
 * - 写入采用「临时文件 + 重命名」：避免半写文件被后续加载误读（掉电 / 进程被杀）；
 * - **损坏不静默降级**（ADR-006）：解析失败抛 [IllegalArgumentException]，由界面明确提示并给出重建入口。
 */
object FriendListStore {

    private const val DIR_NAME = "friends"
    private const val FILE_NAME = "list.txt"

    fun listFile(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    fun exists(context: Context): Boolean = listFile(context).isFile

    /** 保存（覆盖写）；返回清单文件。 */
    fun save(context: Context, list: FriendList): File = save(listFile(context), list)

    /** 保存到指定文件（纯文件版，JVM 可测；与 [save] 共用同一实现）。 */
    fun save(file: File, list: FriendList): File {
        file.parentFile?.mkdirs()
        val text = FriendListCodec.encode(list)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // 重命名失败（个别文件系统限制）：退化为直接覆盖写
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
        return file
    }

    /** 加载：文件不存在返回 null；解析失败抛 [IllegalArgumentException]（不静默降级）。 */
    fun load(context: Context): FriendList? = load(listFile(context))

    /** 从指定文件加载（纯文件版，JVM 可测）。 */
    fun load(file: File): FriendList? =
        if (!file.isFile) null else FriendListCodec.decode(file.readText(Charsets.UTF_8))

    /** 删除清单；返回是否删除成功（不存在时为 false）。 */
    fun delete(context: Context): Boolean = listFile(context).delete()
}
