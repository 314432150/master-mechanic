package com.example.mastermechanic.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/**
 * **警示 / 隐患**（不是"当前失败"）：深琥珀 = **L2**（`docs/decisions/ADR-008-信息级别与配色.md`）。
 *
 * 用户 2026-10-01 口径："**提示类信息不应该使用红色**" —— 红留给"当前不可用 / 你被拒了"（L1），
 * 而"还能用，但有隐患"（例如"常驻守护在跑、但通知被关掉了 ⇒ 系统可能回收它"）走这一色。
 * 取深琥珀而非亮黄：亮色主题的浅底上亮黄读不清（与标定页 `ON_DARK_ERROR` 那条"配色要衬底"的教训同源）；
 * 深底上的同级别用亮琥珀 `#FFD54F`（见 ADR-008 的两列成对口径）。
 */
val Warning40 = Color(0xFF8A5A00)