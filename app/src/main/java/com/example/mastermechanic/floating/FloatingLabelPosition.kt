package com.example.mastermechanic.floating

/**
 * **状态标签的落位与拖动**（2026-09-28 加；纯逻辑、可 JVM 重跑）。
 *
 * ## 它是什么
 *
 * 顶部正中心那条**显示"当前正在进行的操作"**的小标签（用户 2026-09-28 口径："在屏幕顶部的正中心位置，
 * 增加一个可以拖动的悬浮窗……用来显示当前正在进行的操作，内容要简短"）。它是悬浮窗的**第三个窗口**
 * （手柄 + 菜单 + 标签），也是**唯一可拖动的部件**（[FloatingPosition] 那条"手柄不可拖动"没变）。
 *
 * ## 为什么拖动要落在这里（纯逻辑）
 *
 * 拖动这件事只有三件"判定"值得单独测：**默认在顶部正中心**、**拖到哪都留在屏内**、
 * **重开 / 转屏后回到你放的地方**。它们都不依赖安卓框架（给几个尺寸就能算），所以按项目惯例
 * 单独放一个纯逻辑对象（红线 4：代码里不出现任何具体分辨率 / 密度 —— 尺寸一律由调用方按运行时读到的值传进来）。
 *
 * ## 坐标口径
 *
 * 与另外两个窗口一致：窗口坐标 = **整块屏幕**的左上角原点（`FLAG_LAYOUT_IN_SCREEN`），
 * 这里的 x/y 就是 `WindowManager.LayoutParams` 的 x/y。
 * y 的下限是**状态栏高度**（`topInset`）：标签不该压到状态栏上（那里是系统手势的起手区，
 * 也不该被下拉通知栏抢触摸）。
 */
object FloatingLabelPosition {

    /** 一个窗口位置（左上角）。 */
    data class At(val x: Int, val y: Int)

    /** 位置的比例表示（x / 屏宽、y / 屏高）：**存盘用**，转屏 / 重开后仍能还原到相近的地方。 */
    data class Ratios(val x: Float, val y: Float)

    /**
     * 默认位置：**顶部正中心**（用户口径的原话位置）。
     *
     * 为什么"正中心"要按标签自己的宽度算而不是 `Gravity.CENTER_HORIZONTAL`：另外两个窗口都用
     * `gravity = TOP or START` + x/y 落位，标签也跟着用同一套坐标 —— 少一套坐标系，落位日志才对得上。
     */
    fun topCenterX(screenWidth: Int, labelWidth: Int): Int =
        (screenWidth - labelWidth) / 2

    /** 默认纵向位置：状态栏之下 [marginPx] 处。 */
    fun topY(topInset: Int, marginPx: Int): Int = topInset + marginPx

    /**
     * **默认落位 = 顶部居中**（`x` 按标签宽度居中、`y` = 状态栏之下 `marginPx`）。
     *
     * 为什么要有一个函数把两件事捆起来（用户 2026-10-01 口径：「更多」里要有"重置提示位置"）：
     * 挂载（[fromRatios] 返回 null 时）、重置、以及"默认态下换文案后重新居中"**三处必须是同一个口径**
     * —— 散着写就会出现"重置后是按 A 算、换文案后按 B 算"这种只在某些文案长度下才露头的偏差。
     *
     * ⚠ 它**与文字长度无关的两半**：`y` 恒 = [topY]（标签高度是常量，宽度怎么变都不动纵向）；
     * `x` 由 [topCenterX] 按**当前**宽度算 ⇒ 换文案后重新调用它仍是居中（见 [keepCenterX] 的说明）。
     */
    fun defaultAt(screenWidth: Int, topInset: Int, labelWidth: Int, marginPx: Int): At =
        At(x = topCenterX(screenWidth, labelWidth), y = topY(topInset, marginPx))

    /** 横向钳在屏内（标签**完整可见**：不学手柄"只露一半"——它是给眼睛看的，露一半就看不清了）。 */
    fun clampX(x: Int, screenWidth: Int, labelWidth: Int): Int =
        x.coerceIn(0, (screenWidth - labelWidth).coerceAtLeast(0))

    /** 纵向钳在"状态栏之下、屏幕之内"。 */
    fun clampY(y: Int, screenHeight: Int, labelHeight: Int, topInset: Int): Int {
        val min = topInset.coerceAtLeast(0)
        val max = (screenHeight - labelHeight).coerceAtLeast(min)
        return y.coerceIn(min, max)
    }

    /**
     * 拖动中的新位置：**按下那一刻的窗口位置 + 手指位移**，再钳进屏内。
     *
     * 用"按下时的位置 + 总位移"而不是"上一帧的位置 + 增量"：前者不会因为某一帧的钳位而累积漂移
     * （拖到边缘再拖回来时手感才对）。
     */
    fun dragTo(
        startX: Int,
        startY: Int,
        dx: Int,
        dy: Int,
        screenWidth: Int,
        screenHeight: Int,
        labelWidth: Int,
        labelHeight: Int,
        topInset: Int,
    ): At = At(
        x = clampX(startX + dx, screenWidth, labelWidth),
        y = clampY(startY + dy, screenHeight, labelHeight, topInset),
    )

    /**
     * 从存下来的比例还原位置；**没有存过就给 `null`**（调用方走默认的顶部居中）。
     *
     * 越界 / 非法比例（NaN、负数）一律钳进屏内而不是丢弃 —— 转屏后比例还留着，只是那个位置在新几何下
     * 可能落在屏外，钳一下比"忘了用户放哪"更贴近用户预期。
     */
    fun fromRatios(
        ratios: Ratios?,
        screenWidth: Int,
        screenHeight: Int,
        labelWidth: Int,
        labelHeight: Int,
        topInset: Int,
    ): At? {
        if (ratios == null) return null
        if (ratios.x.isNaN() || ratios.y.isNaN()) return null
        return At(
            x = clampX((ratios.x * screenWidth).toInt(), screenWidth, labelWidth),
            y = clampY((ratios.y * screenHeight).toInt(), screenHeight, labelHeight, topInset),
        )
    }

    /** 把当前位置记成比例（只存左上角；落盘前钳一次，存进去的一定是屏内的位置）。 */
    fun toRatios(x: Int, y: Int, screenWidth: Int, screenHeight: Int): Ratios = Ratios(
        x = if (screenWidth > 0) x.toFloat() / screenWidth else 0f,
        y = if (screenHeight > 0) y.toFloat() / screenHeight else 0f,
    )

    /**
     * 文案换了、宽度变了之后的新 x：**以原来的中心为准**。
     *
     * 为什么不按比例重算：标签宽度会随文案变（"第 3/10 步 · 换号拜访" ↔ "待命"），按比例重算会让它
     * 一边变字一边往左挤；按中心对齐则"用户拖到哪就还在哪附近"，而默认居中态也自然保持居中。
     */
    fun keepCenterX(centerX: Int, newWidth: Int, screenWidth: Int): Int =
        clampX(centerX - newWidth / 2, screenWidth, newWidth)
}
