package com.example.mastermechanic.patrol

/**
 * 名称定位（M4-T4-3）：在识别到的候选里，按名字找到**唯一**的那个。
 *
 * FR-04 硬性要求 3 的原话：**「服务器列表与好友列表必须按名称精确定位。名称不唯一、
 * 或与配置不一致时视为未找到。」** 所以这里的口径只有一条、并且是硬的：
 *
 * - **全等才算命中**（不做前缀、不做模糊、不挑"最像的"）—— 挑错人比找不到的代价大得多
 *   （找不到只是停住，用户可以补；点错人就真的打扰了别人 / 进错了区服）；
 * - **命中 0 个或 ≥2 个，都算"未找到"**，并且**如实说清是哪种**（用户才知道该去改什么）；
 * - 名字里**保留中间空格**（游戏里的名字可能带空格）、只去首尾空白。
 *
 * 纯逻辑：候选的"位置"用泛型 [T] 承载（真机上是坐标，单测里可以是任意标记），本文件不碰坐标。
 */
object NameLocator {

    /** 识别层给出的一条候选：[name] 是屏幕上的名字，[payload] 是拿它去点击所需的东西。 */
    data class Candidate<T>(val name: String, val payload: T)

    /** 定位结果。 */
    sealed interface Result<out T> {
        /** 唯一命中。 */
        data class Found<T>(val payload: T) : Result<T>

        /** 未找到：[detail] 说明是"一个都没有"还是"有好几个"，界面据此提示用户（不静默）。 */
        data class NotFound(val detail: String) : Result<Nothing>
    }

    /** 滚动查找的次数上限（FR-04 硬性要求 4：超出即中止并提示）。 */
    const val MAX_SCROLLS = 10

    /** 名字的归一化：只去首尾空白（中间的空格原样保留 —— 名字可能要带空格才对得上）。 */
    fun normalize(name: String): String = name.trim()

    /**
     * 在**一屏**的候选里定位 [target]。
     *
     * @return 唯一命中 → [Result.Found]；一个都没命中、或命中多个 → [Result.NotFound]。
     */
    fun <T> locate(target: String, candidates: List<Candidate<T>>): Result<T> {
        val wanted = normalize(target)
        require(wanted.isNotEmpty()) { "定位目标不能为空" }
        val hits = candidates.filter { normalize(it.name) == wanted }
        return when (hits.size) {
            1 -> Result.Found(hits[0].payload)
            0 -> Result.NotFound("没找到「$wanted」")
            else -> Result.NotFound("「$wanted」在屏幕上有 ${hits.size} 个，分不清是哪一个（不停下就会点错）")
        }
    }

    /**
     * **跨屏**定位（列表需要滚动时用）：把所有已看到的候选**合起来**判一次。
     *
     * 为什么必须合起来判：同一个名字如果在前一屏和后一屏各出现一次，那它仍然是"不唯一" ——
     * 看到第一个就点下去，恰恰是 FR-04 要防的那类误点。
     *
     * 调用方负责"滚几屏"，并用 [MAX_SCROLLS] 兜住上限。
     */
    fun <T> locateAcross(pages: List<List<Candidate<T>>>, target: String): Result<T> =
        locate(target, pages.flatten())
}
