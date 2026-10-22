package com.example.mastermechanic.badge

import com.example.mastermechanic.servers.ServerList

/**
 * **当前登录的是哪个区服**（临时需求 temp/badge-share-1021，2026-10-23 用户报障）。
 *
 * ## 为什么需要
 *
 * 任务意外停止后重新点「搜集立牌」，即使大厅**就是**队首目标区服，也会照常走
 * 「退出登录 → 换区 → 选服 → 登录」——白重登一次（1~2 分钟，且登出/登录自身有失败风险）。
 * 大厅左下角有游戏昵称，而服务器清单每条都带「角色名」（每个小号名字不同）⇒ 用昵称反推区服。
 *
 * ## 为什么不猜（红线 3 / 7）
 *
 * 错判成"已在该区服"的后果是**这一轮的分享码记到错误的区服名下**（数据污染），
 * 比多花两分钟糟得多。所以只要有一点说不清（同名、没填、读不出、两条读数不一致），
 * 一律回 [Verdict.Unknown] ⇒ 调用方照常换号。
 *
 * 纯逻辑（JVM 可测）：不碰安卓、不碰盘、不读帧。
 */
object CurrentServer {

    /** 判定结果。 */
    sealed interface Verdict {
        /** 昵称指向的区服**就是**目标 ⇒ 不必换号。 */
        data class Same(val serverName: String, val nickname: String) : Verdict

        /**
         * 昵称指向**别的**区服（[current] 为 null = 清单里没有这个昵称，只是"不是目标"）。
         * 无论哪种都要换号，差别只在日志怎么解释。
         */
        data class Elsewhere(val target: String, val nickname: String, val current: String?) : Verdict

        /** 判不出来（缺数据 / 有歧义）⇒ 调用方照常换号，并把 [reason] 写进日志。 */
        data class Unknown(val reason: String) : Verdict
    }

    /**
     * 按昵称判断"现在是否已经在 [target] 这个区服"。
     *
     * @param nickname 大厅左下角读到的游戏昵称（OCR 原文，可直接带噪声空格）
     * @param list 服务器清单（比对键 = 每条的角色名）
     * @param target 队首目标区服名
     */
    fun judge(nickname: String, list: ServerList, target: String): Verdict {
        val key = normalize(nickname)
        if (key.isEmpty()) return Verdict.Unknown("大厅昵称没读到文字")
        val targetName = target.trim()
        if (targetName.isEmpty()) return Verdict.Unknown("没有目标区服（队列为空）")
        val targetEntry = list.entries.firstOrNull { it.serverName == targetName }
            ?: return Verdict.Unknown("服务器清单里没有「$targetName」")
        if (!targetEntry.hasCharacterName) {
            // 目标那条不填角色名 ⇒ 无从比对；**不拿"昵称不像"当"不在该区服"的证据**
            return Verdict.Unknown("清单里「$targetName」没填角色名（填上才能判断当前是不是它）")
        }
        val matches = list.entries.filter { it.hasCharacterName && normalize(it.characterName) == key }
        if (matches.size > 1) {
            val names = matches.joinToString("、") { it.serverName }
            return Verdict.Unknown("清单里有 ${matches.size} 个区服的角色名都叫「$key」（$names）—— 分不出是哪个")
        }
        val hit = matches.firstOrNull() ?: return Verdict.Elsewhere(targetName, key, current = null)
        return if (hit.serverName == targetName) {
            Verdict.Same(hit.serverName, key)
        } else {
            Verdict.Elsewhere(targetName, key, current = hit.serverName)
        }
    }

    /**
     * 归一口径：**去掉所有空白**（含全角空格）。
     *
     * 只做这一种归一：OCR 常在字与字之间插空格，而玩家昵称本身几乎不含空白；
     * **不做**形近字纠正、不做前缀/后缀放宽 —— 少一个字就是"读不全"，按读不全处理（红线 7）。
     */
    fun normalize(text: String): String = text.filterNot { it.isWhitespace() || it == '\u3000' }.trim()
}
