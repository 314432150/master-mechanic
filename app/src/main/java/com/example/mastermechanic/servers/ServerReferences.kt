package com.example.mastermechanic.servers

import android.content.Context
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.preset.VisitPresetStore

/**
 * 区服名被下游**引用了没有**（2026-09-22 用户口径：删除时也要做引用验证，与好友清单同一套）。
 *
 * 与 [ServerRenameSync] 是同一件事的两面：改名把引用**改挂过去**，删除则无处可改 —— 引用会变成
 * **悬空引用**：`preset/visit.txt` 里"固定用某个区服"仍指着它，而清单里已经没有这条了。
 * 区服名是**唯一参与定位的字段**（红线 3）⇒ 换号那一步永远找不到区服、流程中止，
 * 而两份文件单看都合法（事后极难归因）。所以删除时**先说清楚**，用户才知道删完还得去哪一页收拾。
 *
 * 注意：**策略为「顺序轮换」时预设里没有区服名**（换哪个由清单顺序决定）→ 天然不算引用。
 *
 * ## 口径
 *
 * 1. **全等匹配**（与红线 3 同源，不做"最像的"）；
 * 2. **只读**：不动任何文件 —— 程序不替用户悄悄清理引用；
 * 3. **失败不吞**：读不了就说"无法确认"，**绝不**当成"没有引用"。
 */
object ServerReferences {

    private const val TAG = "MM-ServerRefs"

    /**
     * 查询结果。
     *
     * @param presetNames 拜访规则里固定用的区服名（至多一个），且确实在这批名字里；
     * @param failure 读不了时给的原因；null = 查过了。
     */
    data class Hits(
        val presetNames: Set<String> = emptySet(),
        val failure: String? = null,
    ) {

        /** 有没有被引用（false 也可能是"没查成"，看 [failure]）。 */
        val any: Boolean get() = presetNames.isNotEmpty()

        /** 提示文案的第二格（只有"失败"那句用得到；与好友侧同一传参口径）。 */
        val arg: Any get() = failure ?: 0
    }

    /**
     * 查这批名字被谁引用。**只读**，不抛错（读盘异常 → [Hits.failure]）。
     *
     * 一次可查一批（整体清空时传清空前的全部区服名，用来说"其中几条还被引用"）。
     */
    fun of(context: Context, names: Collection<String>): Hits {
        val wanted = names.toSet()
        if (wanted.isEmpty()) return Hits()

        return try {
            val server = VisitPresetStore.load(context)?.serverName
            Hits(presetNames = if (server != null && server in wanted) setOf(server) else emptySet())
        } catch (e: Exception) {
            MmLog.w(TAG, "拜访规则读不了，无法确认引用", e)
            Hits(failure = "拜访规则：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
