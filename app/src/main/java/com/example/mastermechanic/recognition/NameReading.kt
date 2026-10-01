package com.example.mastermechanic.recognition

/**
 * 名称识别（M4-T4-3，ADR-003 §2：名称类走文字识别方向）。
 *
 * 与标志类（确定性模板匹配）并列的两条腿之一：**区服名 / 好友名是开放集，无法预采模板**，
 * 所以"屏幕上这块写的是什么字"要由文字识别回答；而"要不要点它"仍由
 * [com.example.mastermechanic.patrol.NameLocator] 的**全等 + 唯一**口径决定（红线 3）。
 *
 * ## 为什么这里只是个接口
 *
 * ADR-003 §3：识别层保持**纯逻辑**（输入帧数据、输出判定），不依赖安卓框架。
 * 而文字识别引擎（ML Kit）是安卓侧的，故：
 * - 本文件只声明"读一块区域 → 给出若干（文字 + 位置）"的[NameReader]接口；
 * - 安卓侧实现见 `service/MlKitNameReader`；单测用假实现（列表写死，纯逻辑可复现）。
 *
 * ## 与模板路线的分工（2026-09-21 帧池实测后的取舍）
 *
 * 曾评估过"把目标名字框一次当模板到处找"，实测**不可行**：名字行字高仅 ≈15px，
 * 同一个名字纵向挪 1 像素相似度就从 1.00 掉到 0.50~0.59（命中线 0.85），而滚动会让行落在非整数位置。
 * 文字识别不受位置微移影响——它看的是"写了什么"，不是"像不像"。
 */

/**
 * 名称识别给出的一条候选：屏幕上的**文字**与它在**运行帧**里的位置（左上角 + 尺寸）。
 *
 * [text] 是引擎读出来的原文（可能带空格 / 引擎自身的切分），**不做任何归一化**
 * ——归一化与比对是 [com.example.mastermechanic.patrol.NameLocator.normalize] 的事；
 * 位置一律是运行帧像素坐标（ADR-002：坐标来自识别判定结果）。
 */
data class NameCandidate(
    val text: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    /** 中心点（点击点用；第 5 步点名字本身就是它）。 */
    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0
}

/**
 * 名称读取通道：给一块灰度区域，返回这块里读到的文字候选。
 *
 * 返回的候选**不做"像不像目标"的判断** —— 那是定位器的职责；本接口只如实给"读到了什么、在哪"。
 * 读不出任何东西时返回空列表（调用方据此"未找到"，而不是猜）。
 */
fun interface NameReader {
    fun read(image: GrayImage, region: PixelBounds): List<NameCandidate>
}
