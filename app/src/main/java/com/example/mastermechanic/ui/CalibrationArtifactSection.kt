package com.example.mastermechanic.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R
import com.example.mastermechanic.decision.UiState

/**
 * 「标定结果」区的**头**与**尾**（M5-U5 收尾，2026-10-02）。
 *
 * ## 为什么拆出来
 *
 * 用户："**先只把清单搬进 LazyColumn**"（UX 评审 P1：整页 `Column.verticalScroll` + `forEach` 铺 20+ 行
 * ⇒ 一次性组合、每行还可能解位图，横屏时主按钮被挤出首屏）。
 * 行现在是页面 `LazyColumn` 的 `item`（**会被回收**），所以"清单外面那层卡片"没法再包着行 ⇒
 * 拆成**区头 / 行 / 区尾**三段：头部（标题 + 工作台入口 + 摘要 + 筛选）在这里，行在
 * `CalibrationEntryScreen` 的 `LazyColumn` 里，尾部（生效说明 + 危险动作）也在这里。
 *
 * ⚠ 视觉代价如实：原来"一整张卡包住清单"没了，行与行之间只靠缩略图自带的底与分隔线区分
 * （本来就如此，见 `CalibrationEntryScreen` 里那段"不画边框"的口径）；卡片外壳只留头尾两段。
 */

/** 区头：标题 + 工作台入口 + 解析状态 / 空态 + 统计两行 + 筛选 chips。 */
@Composable
internal fun ArtifactSectionHeader(
    artifact: ArtifactState,
    framesExist: Boolean,
    groups: List<ArtifactGroup>,
    filter: UiState?,
    onFilterChange: (UiState?) -> Unit,
    onOpenWorkbench: () -> Unit,
) {
    val data = artifact.data
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_artifact_title),
                style = MaterialTheme.typography.titleMedium,
            )
            // T1-5m：工作台入口属标定结果操作，放在本区。
            // UX 评审（2026-10-02）：**还没有结果时它是这一页的主 CTA**（filled）；
            // 已经有结果时降为 tonal —— 那时用户多半是回来看清单的，重心不该被按钮抢走。
            if (data == null) {
                Button(
                    onClick = onOpenWorkbench,
                    enabled = framesExist,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.calibration_workbench_open))
                }
            } else {
                FilledTonalButton(
                    onClick = onOpenWorkbench,
                    enabled = framesExist,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.calibration_workbench_open))
                }
            }
            when {
                artifact.broken -> Text(
                    text = stringResource(R.string.calibration_artifact_broken),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                data == null -> Text(
                    text = stringResource(R.string.calibration_artifact_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    val roleCounts = CalibrationArtifactGroups.roleCounts(groups)
                    // **统计信息分两行**（用户 2026-10-01 口径）：一行说"多少条"、一行说"覆盖范围"。
                    // 挤作一行时后半截（界面数 / 帧尺寸）会被挤到换行处，读起来像两件无关的事。
                    Text(
                        text = stringResource(
                            R.string.calibration_artifact_summary,
                            // 「共 N 条」= **标志条数 + 锚点条数**（用户口径）：口径收在
                            // CalibrationArtifactGroups.roleCounts / rowCount，与分组标题、筛选按钮同源。
                            roleCounts.total,
                            roleCounts.marker,
                            roleCounts.anchor,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.calibration_artifact_scope,
                            groups.count { it.state != UiState.UNKNOWN },
                            data.frameWidth,
                            data.frameHeight,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = filter == null,
                                onClick = { onFilterChange(null) },
                                label = { Text(stringResource(R.string.calibration_filter_all)) },
                            )
                        }
                        items(groups, key = { it.state.name }) { group ->
                            FilterChip(
                                selected = filter == group.state,
                                onClick = { onFilterChange(group.state) },
                                label = {
                                    // 计数与「总条数」同源：都数**记录行**（每个角色一行）
                                    Text("${group.state.label} ${CalibrationArtifactGroups.rowsOf(group).size}")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 区尾：生效说明 + 危险动作（与上方清单**隔开 16dp**，UX 评审 P2）。 */
@Composable
internal fun ArtifactSectionFooter(onDeleteArtifact: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.calibration_take_effect),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 危险动作（UX 评审 P2）：与上方清单**隔开 16dp** + error 描边 + 写明不可撤销。
        // 原来它跟清单最后一行**零间隔**，而且和普通次要按钮长得一样 ——
        // 用户单手滚到底想点最后一行，落点正好在"删除整个标定结果"上 ✗。
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onDeleteArtifact,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) {
            Text(text = stringResource(R.string.calibration_delete))
        }
        Text(
            text = stringResource(R.string.calibration_delete_irreversible),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
