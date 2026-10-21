package com.example.mastermechanic.servers

import android.content.Context
import com.example.mastermechanic.log.MmLog
import java.io.File
import java.time.LocalDate

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
 * 6. **跨日视为"没有游标"**（2026-10-19 U8）：游标文件里带**写下它的日期**，用到时拿"今天"跟它比；
 *    不是同一天就从第一条开始，并在日志里写明"跨日重置"。**不做定时任务** —— 用不到就不比较，
 *    进程被杀 / 休眠一夜都不会漏（下次用到时日期自然就不一样了），也避开"定时器在哪个时区跑"的坑。
 * 7. **只有真换到才推进，且不看来路**（2026-10-19 U8-8，判据 = [switchedTo]）：轮换算出来的、
 *    用户在菜单里**手选**的，只要第 5 步真的换过去了就都推进（原来只认"轮换算出来的"，手选完不写 ⇒
 *    下次「下一个」从旧游标后面取）。参数层选固定区服只是改草稿，**不写**（没真换过去，写了就是谎报）。
 *
 * 纯逻辑（[nextOf]）与文件读写分开：前者 JVM 可测，后者只吃 [File]（与 `ServerListStore` 同一套写法：
 * 临时文件 + 重命名，避免半写文件被下次读到）。
 */
object ServerRotation {

    private const val TAG = "MM-Server"
    private const val DIR_NAME = "servers"
    private const val FILE_NAME = "cursor.txt"
    private const val FORMAT_TAG = "mm-server-cursor"

    /** 2 = 2026-10-19 U8 起多一行「写下它的日期」（用于跨日重置；旧版 1 的文件仍能读）。 */
    private const val VERSION = 2
    private const val KEY_SERVER_NAME = "serverName="
    private const val KEY_SAVED_ON = "savedOn="

    /**
     * 第 5 步（`PatrolFlow.Step.PICK_SERVER`）的步骤编号。
     *
     * 本文件刻意**不依赖 `patrol` 包**（它是纯数据层，JVM 单测不碰 Android）：两边靠
     * `ServerRotationTest.theStepNumberMatchesTheFlow` 钉住，改步骤编号时那条例句会先红。
     */
    const val PICK_SERVER_STEP = 5

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

    /**
     * 游标推进判据（纯逻辑，2026-10-19 U8-8）：这一次**真的换到区服**了吗？
     *
     * 通过第 5 步 = 从"选择区服"走到别的步骤（[afterStep] != [PICK_SERVER_STEP]），且本次目标名非空。
     * **不看来路**：轮换算出来的、用户在菜单里手选的，只要真换过去就都算 —— 这正是 U8 放宽的那一条
     * （原来 `PatrolSession.targetServerFromRotation` 那道判断让手选不推进，导致"手选 X 后点下一个"
     * 从旧游标后面取，而不是 X 的下一条）。
     *
     * **保留"成功才推进"**：调用点只在第 5 步真正通过时问一次，失败 / 中止时 `afterStep` 仍是
     * [PICK_SERVER_STEP]（或流程已结束）⇒ 游标不动，用户重试仍指同一个区服。
     */
    fun switchedTo(beforeStep: Int, afterStep: Int?, targetServer: String?): Boolean =
        beforeStep == PICK_SERVER_STEP &&
            afterStep != null &&
            afterStep != PICK_SERVER_STEP &&
            !targetServer.isNullOrBlank()

    /** 解析"下一个区服"（读清单 + 读游标；不写任何东西）。 */
    fun resolveNext(context: Context, today: LocalDate = LocalDate.now()): Resolution =
        resolveNext(
            listFile = ServerListStore.listFile(context),
            cursorFile = cursorFile(context),
            today = today,
        )

    /** 解析"下一个区服"（纯文件版，JVM 可测；与 [resolveNext] 共用同一实现）。 */
    fun resolveNext(listFile: File, cursorFile: File, today: LocalDate = LocalDate.now()): Resolution {
        val list = try {
            ServerListStore.load(listFile)
        } catch (e: Exception) {
            // 清单坏了要如实说（它是用户数据，不许静默当作"没有清单"）
            return Resolution.Failed("服务器清单读不了：${e.message ?: e.javaClass.simpleName}")
        } ?: return Resolution.Failed("还没有服务器清单（请先在配置页添加）")
        if (list.isEmpty) return Resolution.EmptyList
        val cursorRead = readCursor(cursorFile, today)
        val cursor = cursorRead.serverName
        val entry = nextOf(list, cursor) ?: return Resolution.EmptyList
        val detail = when {
            // 跨日重置 / 旧版没日期（readCursor 已经把结论写进日志，这里让"为什么从第一条起"跟着走）
            cursorRead.detail != null -> cursorRead.detail
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
    fun saveCursor(context: Context, serverName: String, on: LocalDate = LocalDate.now()): File =
        saveCursor(cursorFile(context), serverName, on)

    /**
     * 写游标（纯文件版，JVM 可测）。
     *
     * [on] = 写下它的日期（默认今天；单测注入固定日期来演"同一天 / 跨日"）。
     * 日期是**跨日重置的唯一依据**：读到它跟"今天"不一样就当作没有游标（见 [readCursor]）。
     */
    fun saveCursor(file: File, serverName: String, on: LocalDate = LocalDate.now()): File {
        file.parentFile?.mkdirs()
        val text = buildString {
            appendLine("# MasterMechanic 「下一个」游标：**上一次成功换到的区服**（供「下一个」用）")
            appendLine("format=$FORMAT_TAG")
            appendLine("version=$VERSION")
            appendLine("$KEY_SERVER_NAME$serverName")
            appendLine("$KEY_SAVED_ON$on")
        }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
        return file
    }

    /**
     * 读游标的**结论**：[serverName] 为空 = 当作"没有游标"（从清单第一条起）；
     * [detail] 说明"为什么当作没有"（进日志与 [Resolution.Next.detail]；null = 没有额外原因）。
     */
    data class CursorRead(val serverName: String?, val detail: String?)

    /**
     * 读游标并做**跨日判定**（纯文件版，JVM 可测：日期从 [today] 注入）。
     *
     * 三种"当作没有游标"：文件不存在 / 读不了 / 没有 `serverName=` 行（与旧口径一致），
     * 加上 U8 新的一条 —— **游标不是今天写的**（跨日重置）。
     *
     * ⚠ **旧版（U8 之前）写的游标没有日期行**：分不清是不是今天写的 ⇒ 按"没有游标"处理
     * （代价只是多从清单第一条起一次；反过来继续用它则可能拿几天前的游标去轮换）。
     */
    fun readCursor(file: File, today: LocalDate = LocalDate.now()): CursorRead {
        if (!file.isFile) return CursorRead(null, null)
        val lines = try {
            file.readLines(Charsets.UTF_8)
        } catch (e: Exception) {
            // 派生状态：坏了就当作"没有游标"，但**留一行**（不静默）
            MmLog.w(TAG, "「下一个」游标读不了（按「没有游标」处理，从清单第一条开始）：${e.message}")
            return CursorRead(null, null)
        }
        val name = lines.firstOrNull { it.startsWith(KEY_SERVER_NAME) }
            ?.removePrefix(KEY_SERVER_NAME)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return CursorRead(null, null)
        val dateLine = lines.firstOrNull { it.startsWith(KEY_SAVED_ON) }
        val savedOn = dateLine
            ?.removePrefix(KEY_SAVED_ON)
            ?.trim()
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return when {
            dateLine == null -> {
                // 旧版（U8 之前）写的：分不清是不是今天写的 ⇒ 宁可多从第一条起一次
                MmLog.i(TAG, "「下一个」：游标没有日期（旧版写的）⇒ 按「没有游标」处理，从清单第一条开始")
                CursorRead(null, "游标没有日期（旧版写的）⇒ 从清单第一条开始")
            }
            savedOn == null -> {
                // 有日期行但读不出来（手改 / 截断）：同样不拿它去轮换，并如实说原因
                MmLog.w(TAG, "「下一个」：游标日期读不出来（$dateLine）⇒ 按「没有游标」处理，从清单第一条开始")
                CursorRead(null, "游标日期读不出来（$dateLine）⇒ 从清单第一条开始")
            }
            savedOn != today -> {
                MmLog.i(TAG, "「下一个」跨日重置：游标是 $savedOn 写的（今天 $today）⇒ 从清单第一条开始")
                CursorRead(null, "跨日重置（游标是 $savedOn 写的，今天 $today）⇒ 从清单第一条开始")
            }
            else -> CursorRead(name, null)
        }
    }

    /**
     * 读游标名（只要名字）。文件不存在 / 没有 `serverName=` 行 / 读不了 / **跨日** ⇒ null。
     *
     * 保留给既有调用点与单测；需要"为什么"的时候用 [readCursor]。
     */
    fun loadCursor(file: File, today: LocalDate = LocalDate.now()): String? =
        readCursor(file, today).serverName
}
