package com.example.mastermechanic.floating

import android.content.Context

/**
 * **状态标签的位置存盘**（2026-09-28 加）。
 *
 * ## 为什么这个要落盘，而手柄的位置不落盘
 *
 * FR-07 的既有口径是"**只有用户能改的东西才该落盘**"（手柄固定位置 ⇒ 不存，免得旧值压住新默认值）。
 * 标签是**用户拖出来的** ⇒ 位置就是用户数据：不存的话，每次采集会话重建 / 服务重启 / 转屏之后，
 * 用户都得重新把它拖开一次 —— 那正是"拖了等于白拖"。
 *
 * ## 存的是**比例**，不是像素
 *
 * 存像素在转屏 / 换分辨率之后会落在屏外（或位置漂移）；比例（x/屏宽、y/屏高）能还原到"相近的地方"，
 * 再由 [FloatingLabelPosition.fromRatios] 钳进屏内。
 *
 * 读写失败一律**当作"没存过"**（返回 null = 用默认的顶部居中）：位置存丢了不是故障，
 * 让悬浮窗挂不上才是故障。
 */
object FloatingLabelStore {

    private const val FILE = "floating_label"
    private const val KEY_X = "x_ratio"
    private const val KEY_Y = "y_ratio"

    /** 读回用户拖到的位置；**没存过 / 读失败 / 值不合法都给 `null`**（调用方走默认位置）。 */
    fun load(context: Context): FloatingLabelPosition.Ratios? = runCatching {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // getFloat 的默认值给 NaN：没存过 / 存了非数，都会在这里落成"不合法"
        val x = prefs.getFloat(KEY_X, Float.NaN)
        val y = prefs.getFloat(KEY_Y, Float.NaN)
        // 比例必须落在 [0,1]：越界说明是坏值（或写入时屏宽为 0）⇒ 当作没存过，回默认位置
        if (x.isNaN() || y.isNaN() || x < 0f || x > 1f || y < 0f || y > 1f) {
            null
        } else {
            FloatingLabelPosition.Ratios(x = x, y = y)
        }
    }.getOrNull()

    /** 记下用户拖到的位置（任意线程可调；失败只记日志，不影响拖动本身）。 */
    fun save(context: Context, ratios: FloatingLabelPosition.Ratios) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_X, ratios.x)
                .putFloat(KEY_Y, ratios.y)
                .apply()
        }
    }

    /**
     * **忘掉用户拖到的位置**（2026-10-01：「更多」层的"重置提示位置"用）——清空即回到默认（顶部居中）。
     *
     * 清空而不是存"默认位置的比例"：比例是**像素/屏宽**算出来的，存下来会在转屏 / 换分辨率后漂移；
     * 而"没存过"这个状态本来就有一套确定的默认口径（[FloatingLabelPosition.defaultAt]）⇒ 回到它最稳。
     */
    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_X)
                .remove(KEY_Y)
                .apply()
        }
    }
}
