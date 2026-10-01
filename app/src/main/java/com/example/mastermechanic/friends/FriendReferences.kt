package com.example.mastermechanic.friends

import android.content.Context
import com.example.mastermechanic.calibration.CalibrationStore
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.preset.VisitPresetStore

/**
 * 好友名被下游**引用了没有**（2026-09-22 用户口径：删除时也要做引用验证）。
 *
 * 与 [FriendRenameSync] 是同一件事的两面：改名要把引用**改挂过去**，删除则没有可改的地方 ——
 * 引用会变成**悬空引用**。所以删除时唯一能做的就是**先说清楚**：这个名字还在被谁用着，
 * 用户才知道"删完之后还得去哪一页收拾"。
 *
 * 为什么非说不可：好友清单、`preset/visit.txt`、标定产物是**三份各自合法的文件**。
 * 删掉一个还被引用的好友，前两份单看都没问题，症状要等跑号时才出现 —— 第 9 步按名字在列表里
 * 找不到行 → 中止（红线 3：不挑"最像的"），或运行时按目标好友名**全等**取头像模板取不到 →
 * 认不出"这是谁的农场"。那时已经离"删除"这个动作很远了，没人能联想到。
 *
 * ## 口径
 *
 * 1. **全等匹配**：只认正好等于这些名字的引用（与红线 3 同源，不做"最像的"）；
 * 2. **只读**：这里**不动**任何文件 —— 删除是用户的决定，程序不替他清理引用（那是"悄悄改数据"）；
 * 3. **失败不吞**：读不了就如实说"无法确认"（[Hits.failure]），**绝不**当成"没有引用"——
 *    把"查不到"说成"没问题"正是最坏的一种撒谎。
 *
 * 一次可以查**一批**名字：删一行传一个名字，整体清空传清空前的全部名字（"其中几条还被引用"）。
 */
object FriendReferences {

    private const val TAG = "MM-FriendRefs"

    /**
     * 查询结果。
     *
     * @param presetNames 拜访规则（`preset/visit.txt`）引用的名字 —— 该文件只存一个好友名，所以至多一个；
     * @param anchorNames 标定产物里**有记录归在它名下**的名字（好友头像锚点的归属好友名）；
     * @param anchorCount 这些名字名下的**记录条数**（总和，用于说"几条"）；
     * @param failure 读不了（或读坏了）时给的原因；null = 两处都查过。
     */
    data class Hits(
        val presetNames: Set<String> = emptySet(),
        val anchorNames: Set<String> = emptySet(),
        val anchorCount: Int = 0,
        val failure: String? = null,
    ) {

        /** 这批名字里被下游引用的那些（预设 ∪ 产物）。 */
        val referenced: Set<String> get() = presetNames + anchorNames

        /** 有没有被引用（false 也可能是"没查成"，看 [failure]）。 */
        val any: Boolean get() = referenced.isNotEmpty()

        /**
         * 提示文案的第二格：失败原因是 String（`%2$s`）、条数是 Int（`%2$d`），
         * 由具体那条文案决定用哪个 —— 与 [FriendRenameSync.Report] 的传参口径一致。
         */
        val arg: Any get() = failure ?: anchorCount
    }

    /**
     * 查这批名字被谁引用。**只读**，不抛错（读盘异常 → [Hits.failure]）。
     *
     * 任一处理不出来就到此为止：结论不可信时，说"无法确认"比拼一个半真半假的结论安全。
     */
    fun of(context: Context, names: Collection<String>): Hits {
        val wanted = names.toSet()
        if (wanted.isEmpty()) return Hits()

        // ① 拜访规则的"拜访谁"
        val presetNames = try {
            val friend = VisitPresetStore.load(context)?.friendName
            if (friend != null && friend in wanted) setOf(friend) else emptySet()
        } catch (e: Exception) {
            MmLog.w(TAG, "拜访规则读不了，无法确认引用", e)
            return Hits(failure = "拜访规则：${reason(e)}")
        }

        // ② 标定产物里好友头像锚点的归属好友名
        return try {
            val signals = CalibrationStore.load(context)?.signals.orEmpty()
            val hits = signals.filter { it.friend != null && it.friend in wanted }
            Hits(
                presetNames = presetNames,
                anchorNames = hits.mapNotNull { it.friend }.toSet(),
                anchorCount = hits.size,
            )
        } catch (e: Exception) {
            MmLog.w(TAG, "标定产物读不了，无法确认引用", e)
            Hits(presetNames = presetNames, failure = "标定结果：${reason(e)}")
        }
    }

    /** 原始原因（与其它失败文案同一口径：报 Store / 编解码器给的话，不自己改写）。 */
    private fun reason(e: Throwable): String = e.message ?: e.javaClass.simpleName
}
