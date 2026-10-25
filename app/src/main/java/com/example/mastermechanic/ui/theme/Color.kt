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

/**
 * **运行中 / 已完成**（正面状态）：深绿 = **L4**（`docs/decisions/ADR-008-信息级别与配色.md` 的亮底那一列）。
 *
 * 2026-10-25 用它做授权页"常驻守护 / 屏幕采集**正在运行**"的**圆点**（用户口径：状态文字去掉，
 * 行首点一个绿点 + 行尾「停止」——"一按就知道是运行中了"）。为什么不直接用 `colorScheme.primary`：
 * primary 在这一页已经表示"已就绪 / 可点"，而"**进程真的在跑**"是更强的正面状态，L4 的绿是 ADR 里
 * 已经定好的一档，不必新造色。⚠ 亮底用深绿（`#69F0AE` 是深底那一列，浅底上读不清）。
 */
val Success40 = Color(0xFF1B7A3E)