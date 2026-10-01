package com.example.mastermechanic.friends

import android.content.Context
import com.example.mastermechanic.calibration.CalibrationStore
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.preset.VisitPresetStore

/**
 * 好友改名的**下游同步**（2026-09-22 用户口径：改名不能只在好友清单里生效）。
 *
 * ## 为什么必须有这一步
 *
 * 好友名是**跨文件的引用键**：好友清单是它的主副本，另外两处存着同一个字符串 ——
 * `preset/visit.txt` 的"拜访谁"，以及标定产物里好友头像锚点的**归属好友名**。
 * 改名只落在一处，另外两处就指向一个**不存在的人**：
 *
 * - 预设：第 9 步按名字在列表里找行 → 找不到 → 中止（红线 3：不挑"最像的"）；
 * - 产物：运行时要按"目标好友名"**精确取**头像模板 → 取不到 → 认不出已到达谁的农场。
 *
 * 两处文件单看都"合法"，所以症状是"流程莫名中止 / 认不出"，事后极难归因。这里把它一次性对齐。
 *
 * ## 口径
 *
 * 1. **全等匹配**：只改正好等于旧名的引用（与红线 3 同源，不做"最像的"）；
 * 2. **只改名字**：模板 / 窗口 / 用途一概不动（改名 = 同一个人换了名字，划过的框仍然有效）；
 * 3. **失败不吞**：任一处同步失败都如实回传（[Report.failure]）交给界面提示，绝不假装成功。
 *
 * 落盘走各自的 Store（临时文件 + rename，ADR-006）；产物写回还会触发采集侧就地重载。
 * **调用前提**：好友清单本身**已经落盘成功** —— 清单是主副本，它没改成功就谈不上同步。
 */
object FriendRenameSync {

    private const val TAG = "MM-FriendRename"

    /**
     * 同步结果。
     *
     * @param presetRenamed 拜访规则里引用的就是这个名字 → 已跟着改；
     * @param anchorsRenamed 跟着改的**标定记录**条数（好友头像锚点的归属好友名）；
     * @param failure 失败原因（null = 两处都成功）。**改名本身已经保存**，这里只影响下游引用。
     */
    data class Report(
        val presetRenamed: Boolean = false,
        val anchorsRenamed: Int = 0,
        val failure: String? = null,
    ) {

        /** 有没有下游引用跟着改（界面据此决定提示哪一句）。 */
        val changed: Boolean get() = presetRenamed || anchorsRenamed > 0
    }

    /**
     * 把 [oldName] 的两处引用改挂到 [newName]，并**就地落盘**。
     *
     * 不抛错：读盘 / 落盘的异常转成 [Report.failure]（改名是主操作，下游同步失败不该把界面搞崩，
     * 也不该让"改名"看起来没成功）。
     */
    fun apply(context: Context, oldName: String, newName: String): Report {
        if (oldName == newName) return Report()

        // ① 拜访规则
        val presetRenamed = try {
            val preset = VisitPresetStore.load(context)
            val renamed = preset?.renamedFriend(oldName, newName)
            if (renamed != null && renamed !== preset) {
                VisitPresetStore.save(context, renamed)
                true
            } else {
                false // 没引用这个名字 / 还没配过预设 —— 都不是错误
            }
        } catch (e: Exception) {
            MmLog.w(TAG, "拜访规则同步失败（预设文件读不了或写不进）", e)
            return Report(failure = "拜访规则：${reason(e)}")
        }

        // ② 标定产物的归属好友名（只改名字，模板与窗口不动）
        val anchorsRenamed = try {
            val data = CalibrationStore.load(context)
            val affected = data?.signals?.count { it.friend == oldName } ?: 0
            if (affected > 0 && data != null) {
                CalibrationStore.save(context, data.renamedFriend(oldName, newName))
                affected
            } else {
                0
            }
        } catch (e: Exception) {
            MmLog.w(TAG, "标定产物同步失败（产物读不了、写不进，或新名字写不进产物）", e)
            return Report(presetRenamed = presetRenamed, failure = "标定产物：${reason(e)}")
        }

        if (presetRenamed || anchorsRenamed > 0) {
            MmLog.i(
                TAG,
                "好友改名同步：「$oldName」→「$newName」（拜访规则${if (presetRenamed) "已改" else "未引用"}，" +
                    "标定记录 $anchorsRenamed 条）",
            )
        }
        return Report(presetRenamed = presetRenamed, anchorsRenamed = anchorsRenamed)
    }

    /** 原始原因（与其它失败文案同一口径：报 Store / 编解码器给的话，不自己改写）。 */
    private fun reason(e: Throwable): String = e.message ?: e.javaClass.simpleName
}
