package com.example.mastermechanic.servers

import android.content.Context
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.preset.VisitPresetStore

/**
 * 区服改名的**下游同步**（2026-09-22 用户口径：改名不能只在清单里生效）。
 *
 * 与好友改名同一套口径，只是引用它的地方只有一处：`preset/visit.txt` 里"固定用某个区服"时存的
 * [com.example.mastermechanic.preset.VisitPreset.serverName]。
 *
 * ## 为什么必须有这一步
 *
 * 区服名是**唯一参与定位的字段**（红线 3：找不到或不唯一就停下）。预设里存的是一份**副本**，
 * 清单改了它不改 → 换号那一步拿着一个**不存在的区服名**去找，表现是"永远找不到区服、流程中止"，
 * 而两份文件单看都合法（事后极难归因）。
 *
 * 注意：**策略为「顺序轮换」时预设里根本没有区服名**（换哪个由清单顺序决定）→ 那种情况天然无需同步，
 * 这里返回"没引用"，不是错误。
 *
 * ## 口径
 *
 * 1. **全等匹配**：只改正好等于旧名的引用（与红线 3 同源，不做"最像的"）；
 * 2. **只改名字**：策略（顺序轮换 / 固定区服）与好友一概不动 —— 改名 = 同一台小号换了区服名，
 *    "见谁""怎么选服"都不该跟着变；
 * 3. **失败不吞**：同步失败如实回传（[Report.failure]）交给界面提示，绝不假装成功。
 *
 * 落盘走 `VisitPresetStore`（临时文件 + rename，ADR-006）。
 * **调用前提**：服务器清单本身**已经落盘成功** —— 清单是主副本，它没改成功就谈不上同步。
 */
object ServerRenameSync {

    private const val TAG = "MM-ServerRename"

    /**
     * 同步结果。
     *
     * @param presetRenamed 拜访规则里固定用的就是这个名字 → 已跟着改；
     * @param failure 失败原因（null = 成功）。**改名本身已经保存**，这里只影响下游引用。
     */
    data class Report(
        val presetRenamed: Boolean = false,
        val failure: String? = null,
    ) {

        /** 有没有下游引用跟着改（界面据此决定提示哪一句）。 */
        val changed: Boolean get() = presetRenamed
    }

    /**
     * 把 [oldName] 的引用改挂到 [newName]，并**就地落盘**。
     *
     * 不抛错：读盘 / 落盘的异常转成 [Report.failure]（改名是主操作，下游同步失败不该把界面搞崩，
     * 也不该让"改名"看起来没成功）。
     */
    fun apply(context: Context, oldName: String, newName: String): Report {
        if (oldName == newName) return Report()

        return try {
            val preset = VisitPresetStore.load(context)
            val renamed = preset?.renamedServer(oldName, newName)
            if (renamed != null && renamed !== preset) {
                VisitPresetStore.save(context, renamed)
                MmLog.i(TAG, "区服改名同步：「$oldName」→「$newName」（拜访规则的固定区服已跟着改）")
                Report(presetRenamed = true)
            } else {
                Report() // 没引用这个名字 / 策略是顺序轮换 / 还没配过预设 —— 都不是错误
            }
        } catch (e: Exception) {
            MmLog.w(TAG, "拜访规则同步失败（预设文件读不了或写不进）", e)
            Report(failure = "拜访规则：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
