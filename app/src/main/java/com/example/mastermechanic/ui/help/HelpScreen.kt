package com.example.mastermechanic.ui.help

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R

/**
 * 「帮助」页（M5-U6 第二段，2026-10-03）：**术语与流程**，一组一句、每句 ≤60 字（验收 V2）。
 *
 * ## 三条写作口径（照抄 U7 的规则，别在改的时候破掉）
 *
 * 1. **只写"实际怎么做的"**，不写愿景、不写"将来会"——用户按这句话做事，做不到就是骗人；
 * 2. **一个概念只出现一次**，且**沿用别处已经用过的说法**（授权四项的名字就取授权页那四个标签）；
 * 3. **每组 ≤5 条**（超过就不是"帮助"，是文档）。
 *
 * 页面**不持有状态**、**没有按钮**：它是纯说明，看完关掉即可（真出问题去「诊断」和日志）。
 */
@Composable
fun HelpScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HelpGroup(
            R.string.nav_help_what,
            listOf(R.string.nav_help_what_1),
        )
        HelpGroup(
            R.string.nav_help_auth,
            listOf(
                R.string.nav_help_auth_1,
                R.string.nav_help_auth_2,
                R.string.nav_help_auth_3,
                R.string.nav_help_auth_4,
                R.string.nav_help_auth_5,
            ),
        )
        HelpGroup(
            R.string.nav_help_run,
            listOf(R.string.nav_help_run_1, R.string.nav_help_run_2),
        )
        HelpGroup(R.string.nav_help_popup, listOf(R.string.nav_help_popup_1))
        HelpGroup(R.string.nav_help_calib, listOf(R.string.nav_help_calib_1))
        HelpGroup(R.string.nav_help_diag, listOf(R.string.nav_help_diag_1))
    }
}

/** 一组说明 = 一张卡（标题 + 若干句）。 */
@Composable
private fun HelpGroup(@StringRes titleRes: Int, bodyRes: List<Int>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            bodyRes.forEach { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


