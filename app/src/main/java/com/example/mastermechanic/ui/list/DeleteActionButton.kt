package com.example.mastermechanic.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp

/**
 * 左滑露出的**删除按钮**（2026-09-15 从两个清单里抽出来统一）。
 *
 * 颜色一律走 `error` / `onError`（**正红**，2026-09-15 用户定稿：删除要醒目）：
 * 曾用过 `errorContainer` / `onErrorContainer`（M3 的"破坏性语义色"，但实测偏粉、不够醒目），
 * 用户明确要求改回正红 —— 抽成一个组件就是为了让它不再各自漂移（改一次，两处一起变）。
 *
 * 两点口径（沿用服务器清单已验证过的写法）：
 * - **只在压出来时才进组合**（由调用方决定，通常 `revealFraction > 0`）：语义节点不能常驻，
 *   否则读屏会念出一个眼睛看不见的按钮；
 * - `enabled` 让它**只有停稳之后才可点**（拖动途中不算，避免手指一划就删掉）。
 */
@Composable
fun DeleteActionButton(
    label: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.error)
            .clickable(enabled = enabled, onClickLabel = label) { onDelete() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp),
            color = MaterialTheme.colorScheme.onError,
        )
    }
}
