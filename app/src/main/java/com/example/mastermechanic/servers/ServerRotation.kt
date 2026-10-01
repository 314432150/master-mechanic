package com.example.mastermechanic.servers

import android.content.Context
import com.example.mastermechanic.log.MmLog
import java.io.File

/**
 * 服务器**顺序轮换**的游标（M4-T4-9 补齐，2026-09-24 用户报障后实现）。
 *
 * ## 用户的真实场景
 * 「在同一个好友的农场里，轮流换不同服务器的小号来看他」（`ServerChoice.NEXT` / 菜单「下一个」）——
 * 所以最常用的动作是"换下一个"，而"下一个是谁"**必须记住上次换到了哪个区服**：
 * 这是唯一跨会话的状态（清单顺序在盘上、目标是流程里算的，只有"当前在哪"没地方存）。
 *
 * ## 口径（都写在日志里，可事后复查）
 * 1. **游标 = 上一次成功换到的区服名**，不是下标：清单会被用户改序 / 删条、也能整体重排，
 *    记下标会在下次换号时**悄悄指向另一个区服**；记名字最坏情况只是"这个名字没了"⇒ 回到第 3 条（从第一条起）。
 * 2. **只有"成功换到"才推进**（第 5 步真的点下去了、流程走进第 6 步）：失败 / 中止时游标不动 ——
 *    用户重试仍指向同一个区服。否则"没换成"会被悄悄跳过去，表现是"有个小号一直轮不到"。
 * 3. **下一个 = 清单里它的下一条；到末条回绕到第一条**（"轮流"的语义就是转圈）。
 *    **游标为空（第一次跑）/ 那个名字已不在清单里 ⇒ 从第一条开始**，并把原因写进日志。
 * 4. **清单为空 ⇒ 没有下一个**：如实返回原因，**不启动流程**（在配置页加一条是用户的动作，不猜）。
 * 5. **游标文件坏了 / 读不了 ⇒ 当作"没有游标"**（从第一条开始）+ 一行告警：
 *    它是**派生状态**（不是用户数据），不值得为此挡住流程；但也**不静默**。
 *
 * 纯逻辑（[nextOf]）与文件读写分开：前者 JVM 可测，后者只吃 [File]（与 `ServerListStore` 同一套写法：
 * 临时文件 + 重命名，避免半写文件被下次读到）。
 */
object ServerRotation {

    private const val TAG = "MM-Server"
    private const val DIR_NAME = "servers"
    private const val FILE_NAME = "cursor.txt"
    private const val FORMAT_TAG = "mm-server-cursor"
    private const val VERSION = 1
    private const val KEY_SERVER_NAME = "serverName="

    /** 「下一个」的解析结论（调用方据此写日志 / 决定启不启动）。 */
    sealed interface Resolution {

        /**
         * 解析到了下一个区服。
         *
         * @param serverName 目标区服名（清单里的原文，不改写）
         * @param detail 为什么是它（进日志：第一次跑 / 回绕 / 上一条是谁）——不参与任何判定
         */
        data class Next(val serverName: String, val detail: String) : Resolution

        /** 清单是空的 ⇒ 没有"下一个"。 */
        data object EmptyList : Resolution

        /** 清单读不了 / 还没建（文件坏了、权限等）——如实说原因，不猜。 */
        data class Failed(val reason: String) : Resolution
    }

    /**
     * 纯逻辑：清单 + 游标名 → 下一个条目（清单为空返回 null）。
     *
     * 三种"从头开始"的情形都归到这里：游标为空、游标那个名字已不在清单里。
     */
    fun nextOf(list: ServerList, cursor: String?): ServerEntry? {
        if (list.isEmpty) return null
        val index = cursor?.let { list.indexOfServerName(it) } ?: -1
        return if (index < 0) list.entries.first() else list.entries[(index + 1) % list.size]
    }

    /** 解析"下一个区服"（读清单 + 读游标；不写任何东西）。 */
    fun resolveNext(context: Context): Resolution =
        resolveNext(listFile = ServerListStore.listFile(context), cursorFile = cursorFile(context))

    /** 解析"下一个区服"（纯文件版，JVM 可测；与 [resolveNext] 共用同一实现）。 */
    fun resolveNext(listFile: File, cursorFile: File): Resolution {
        val list = try {
            ServerListStore.load(listFile)
        } catch (e: Exception) {
            // 清单坏了要如实说（它是用户数据，不许静默当作"没有清单"）
            return Resolution.Failed("服务器清单读不了：${e.message ?: e.javaClass.simpleName}")
        } ?: return Resolution.Failed("还没有服务器清单（请先在配置页添加）")
        if (list.isEmpty) return Resolution.EmptyList
        val cursor = loadCursor(cursorFile)
        val entry = nextOf(list, cursor) ?: return Resolution.EmptyList
        val detail = when {
            cursor == null -> "还没有记录当前区服 ⇒ 从清单第一条开始"
            list.indexOfServerName(cursor) < 0 -> "「$cursor」已不在清单里 ⇒ 从第一条开始"
            entry == list.entries.first() -> "上一条是「$cursor」（末条）⇒ 回绕到第一条"
            else -> "上一条是「$cursor」⇒ 取它的下一条"
        }
        return Resolution.Next(entry.serverName, detail)
    }

    /** 游标文件（应用私有目录 `servers/cursor.txt`）。 */
    fun cursorFile(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /** 记下"当前换到了哪个区服"（**成功换到时**才调用；返回游标文件）。 */
    fun saveCursor(context: Context, serverName: String): File = saveCursor(cursorFile(context), serverName)

    /** 写游标（纯文件版，JVM 可测）。 */
    fun saveCursor(file: File, serverName: String): File {
        file.parentFile?.mkdirs()
        val text = buildString {
            appendLine("# MasterMechanic 顺序轮换游标：**上一次成功换到的区服**（供「换下一个」用）")
            appendLine("format=$FORMAT_TAG")
            appendLine("version=$VERSION")
            appendLine("$KEY_SERVER_NAME$serverName")
        }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
        return file
    }

    /** 读游标（纯文件版）。文件不存在 / 没有 `serverName=` 行 / 读不了 ⇒ null（调用方按"没有游标"处理）。 */
    fun loadCursor(file: File): String? {
        if (!file.isFile) return null
        return try {
            file.readLines(Charsets.UTF_8)
                .firstOrNull { it.startsWith(KEY_SERVER_NAME) }
                ?.removePrefix(KEY_SERVER_NAME)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            // 派生状态：坏了就当作"没有游标"，但**留一行**（不静默）
            MmLog.w(TAG, "顺序轮换游标读不了（按「没有游标」处理，从清单第一条开始）：${e.message}")
            null
        }
    }
}
