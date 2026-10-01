package com.example.mastermechanic.patrol

/**
 * 探针里用的"画面区"（T4-6 拆除画布方向归一时引入）。
 *
 * 原先探针用 `CanvasGeometry.of(标定帧).contentArea(运行帧)` 算"帧内真正有画面的那一条带"，
 * 好让裁剪出来的参考图落在画面里。归一拆除后 **帧恒与标定同几何** ⇒ 画面区恒等于整帧，
 * 因此这里直接按整帧给出；字段名与原先的 `ContentArea` 保持一致，探针代码其余部分照旧。
 */
data class Area(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
}
