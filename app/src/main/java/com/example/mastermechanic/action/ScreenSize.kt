package com.example.mastermechanic.action

/** 投影后的落点；[onScreen] 为假 = 落点不在显示区域内（**不得下发**）。 */
data class ScreenPoint(val x: Double, val y: Double, val onScreen: Boolean)

/**
 * 显示区域尺寸（T4-6 拆除画布方向归一后的产物，2026-09-23）。
 *
 * ## 这里原先是什么
 *
 * 原先是 `ClickGeometry`：因为"**帧 ≠ 屏幕**"（竖画布会话里帧内画面只占中间一条带，上下是留白），
 * 点击前必须按 `屏幕 =（帧 − 画面区左上）× 屏尺寸 / 画面区尺寸` 换算，否则点会落到屏幕外
 * （2026-09-20 真机实录：`farm_friends` 帧点 (1324,1450) 原样下发、屏幕只有 1440 高 ⇒ 点了毫无反应）。
 *
 * ## 为什么现在只剩尺寸
 *
 * 转屏已由 `VirtualDisplay.resize()`（`onCapturedContentResize`，minSdk 34 起恒可用）跟进
 * ⇒ **帧恒与屏幕同向、同尺寸**（`docs/progress.md` 第 163 / 167 条）⇒ 识别读的坐标就是屏幕坐标，
 * 换算整层不再有存在意义（用户口径：「旧产物不必兼容，冗余代码不该留着」）。
 *
 * ## 为什么"越界判定"必须留下
 *
 * 它是 NFR-05（**点错的代价远高于不点**）在点击层的落实：几何一旦错配（例如产物方向与本次会话不一致），
 * 落点可能整体落在屏幕外——此时**宁可不下发**，也不要"赌它刚好点中"。
 * 与换算时代**同一条口径**：算不出来（尺寸未知）就**不拦**（不猜，也不把一个"能点但坐标不理想"变成"完全不点"）。
 */
data class ScreenSize(val width: Int, val height: Int) {

    /** 屏幕尺寸是否拿到了（拿不到 ⇒ 不做越界判定）。 */
    val known: Boolean get() = width > 0 && height > 0

    /**
     * 帧坐标 → 屏幕坐标。T4-6 之后二者恒等（帧 == 屏幕），因此这里只做越界判定。
     * 尺寸未知时 [ScreenPoint.onScreen] 恒真（**不拦**，与换算时代同一口径）。
     */
    fun project(frameX: Double, frameY: Double): ScreenPoint {
        val onScreen = !known || (frameX >= 0 && frameY >= 0 && frameX < width && frameY < height)
        return ScreenPoint(frameX, frameY, onScreen)
    }

    /** 日志用一行（真机核对"这次按什么尺寸判的越界"）。 */
    fun describe(): String =
        if (known) "屏幕 ${width}x$height（帧 == 屏幕，1:1）" else "屏幕尺寸未知（不做越界判定）"

    companion object {
        /** 屏幕尺寸未知：不拦越界、不抖动（"不知道就不猜"）。 */
        val UNKNOWN = ScreenSize(0, 0)

        /**
         * **按"帧的几何"校正屏幕尺寸**（2026-09-30 加；真机修用户报的"卡在了打开好友列表"）。
         *
         * 屏幕与帧**互为转置**时返回按**帧**的尺寸 —— 因为进 [project] 的坐标来自**帧**（ADR-002），
         * 越界判定必须与落点在**同一个坐标系**里。
         *
         * ## 为什么真机会出现"互为转置"（是设计预期，不是故障）
         *
         * 本 App 锁竖屏（1440×3168）、游戏横屏（3168×1440），而镜像按口径 A **故意保持横屏**
         * （见 `CaptureService.attachMirror` 与 `noteFrameGeometry`）⇒ 我们自己的 App 在前台那一刻读到的
         * "屏幕"是竖屏、而帧仍是横屏。那个尺寸被推进 `ClickDispatch` 后会**一直用到下次刷新**，
         * 而刷新只发生在"收到新帧"时 —— **画面静止时平台不产帧**（静止兜底重放就是为此存在的）
         * ⇒ 尺寸会长时间停在竖屏。真机实录：
         * ```
         * 20:10:28.796  屏幕尺寸已更新: 屏幕 1440x3168   ← 我们 App 在前台（竖屏）
         * 20:10:57.963  点击拒绝（点击点落在屏幕外）｜farm_friends｜帧点 (2912, 429)  ← 2912 > 1440 ⇒ 误判
         * 20:11:39.735  屏幕尺寸已更新: 屏幕 3168x1440   ← 重新授权、游戏在前台
         * 20:11:46.767  点击下发 ✓ (2912, 429)          ← **同一个坐标**放行
         * ```
         * 后果不只是"少点一下"：跑号把"被拒"当成"刚点过"，白等一整个步骤预算（16 秒）后中止。
         *
         * 帧尺寸未知（还没收到过帧）时**不校正**：宁可交给 [UNKNOWN] 的"不拦"口径，也不猜一个几何。
         */
        fun forFrame(screen: ScreenSize, frameWidth: Int, frameHeight: Int): ScreenSize {
            val transposed = frameWidth > 0 && frameHeight > 0 &&
                screen.width == frameHeight && screen.height == frameWidth
            return if (transposed) ScreenSize(frameWidth, frameHeight) else screen
        }
    }
}
