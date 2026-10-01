package com.example.mastermechanic.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R
import com.example.mastermechanic.calibration.CalibrationSignals
import com.example.mastermechanic.calibration.CalibrationStore
import com.example.mastermechanic.ui.ArtifactState
import com.example.mastermechanic.ui.loadArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 抽屉「**诊断**」页（M5-U6 的第一段；2026-10-02 先落「识别参数」这一块）。
 *
 * ## 为什么识别参数搬到这里（用户 2026-10-02）
 *
 * 用户："**识别参数目前还是放在了最底部，放进抽屉里吧**" —— 标定页那张卡已经是"高级折叠"，
 * 但**位置**仍在页面末尾（要滑到底才看得见）。判断放哪儿时的依据是"**什么时候会用到它**"：
 * 只在"**认不出画面 / 老判不可信**"时才需要调，而那正是来**诊断**页查问题的时刻
 * ⇒ 与识别诊断同一场景，放这里最顺。（另一候选是「设置」，但那里是 App 级开关
 * —— 自动关弹窗 / 悬浮窗位置 / 退出，与识别无关。）
 *
 * ## 与标定的关系
 *
 * 参数属于**标定产物**（`CalibrationStore` 里的 `params=`），这里只是**换了个入口**：
 * 读写同一个文件、同样的"编辑合法即写入"语义、同样的默认值（`CalibrationSignals.DEFAULT_*_TEXT`）。
 * 标定页不再出现任何参数输入框 —— 产物里已有的值照旧生效（**行为零回归**）。
 *
 * ## 本页后续（U6）
 *
 * 帧率 / 停更 / 当前识别画面与分数 / 单帧耗时 / 日志 —— 见 [R.string.diagnostics_coming] 那条占位说明；
 * 本轮只落参数这一段，其余留给 U6，**不静默**（页面明确写着"待补"）。
 */
@Composable
fun DiagnosticsRoute(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var artifactTick by remember { mutableIntStateOf(0) }
    val artifact = remember(resumeTick, artifactTick) { loadArtifact(context) }
    // 参数文本按产物当前值初始化（此后只随使用者的编辑走：编辑合法即写入产物，不回填覆盖输入）
    var thresholdText by remember { mutableStateOf(artifact.paramsText(0)) }
    var marginText by remember { mutableStateOf(artifact.paramsText(1)) }
    var minDistanceText by remember { mutableStateOf(artifact.paramsText(2)) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    /**
     * 参数编辑：**合法即写入产物**（无产物时不写 —— 参数会随首个信号一并写入）。
     *
     * 与标定页原来那份**逐字相同**（T1-5l ⑤ 的口径）：解析用编辑后的**实时**文本，基线产物**现读盘** ——
     * 若沿用组合期算出来的旧值，写入会落后一次编辑（真机核对实录：界面 0.86、产物 0.80）。
     */
    fun applyParams() {
        val params = CalibrationSignals.parseParams(thresholdText, marginText, minDistanceText) ?: return
        if (!CalibrationStore.exists(context)) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val current = CalibrationStore.load(context) ?: return@withContext false
                    val updated = CalibrationSignals.withParams(current, params) ?: return@withContext false
                    CalibrationStore.save(context, updated)
                    true
                }
            }
                .onSuccess { saved ->
                    if (saved) {
                        artifactTick++
                        message = context.getString(R.string.calibration_param_applied)
                    }
                }
                .onFailure {
                    message = context.getString(
                        R.string.calibration_write_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                }
        }
    }

    message?.let { text ->
        LaunchedEffect(text) {
            snackbarHostState.showSnackbar(text)
            message = null
        }
    }

    DiagnosticsScreen(
        artifact = artifact,
        thresholdText = thresholdText,
        marginText = marginText,
        minDistanceText = minDistanceText,
        paramsValid = CalibrationSignals.parseParams(thresholdText, marginText, minDistanceText) != null,
        onThresholdChange = { thresholdText = it; applyParams() },
        onMarginChange = { marginText = it; applyParams() },
        onMinDistanceChange = { minDistanceText = it; applyParams() },
        snackbarHostState = snackbarHostState,
    )
}

@Composable
private fun DiagnosticsScreen(
    artifact: ArtifactState,
    thresholdText: String,
    marginText: String,
    minDistanceText: String,
    paramsValid: Boolean,
    onThresholdChange: (String) -> Unit,
    onMarginChange: (String) -> Unit,
    onMinDistanceChange: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 与清单页同一套：**inset 只由壳消费一次**（见 `ServerListScreen` 里那段说明；
        // `ScaffoldInsetsGuardTest` 会钉住这一条）。
        contentWindowInsets = WindowInsets(0.dp),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ParamsCard(
                thresholdText = thresholdText,
                marginText = marginText,
                minDistanceText = minDistanceText,
                paramsValid = paramsValid,
                hasArtifact = artifact.data != null,
                onThresholdChange = onThresholdChange,
                onMarginChange = onMarginChange,
                onMinDistanceChange = onMinDistanceChange,
            )
            Text(
                text = stringResource(R.string.diagnostics_coming),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「**识别参数（高级）**」卡（2026-10-02 从标定页**整体搬来**，参数与文案一字未改）。
 *
 * 三条口径（用户 2026-10-02 拍板"保留但收进「高级」折叠"）：
 * 1. **默认收起** —— 首屏只占一行标题（不点开就不占地方）；
 * 2. **术语换白话** —— 「命中线 / 差距线 / 峰值间距」⇒「认出画面的门槛 / 两处相像时的辨别线 /
 *    靠多近算同一处」，每个值下面挂一行"**什么时候该改**"；
 * 3. 值仍然**编辑合法即写入产物**（行为零回归、产物格式不变）。
 *
 * ⚠ 为什么不是"删掉、只用内置默认"（用户问过）：这三个是**唯一**能调识别松紧的旋钮 ——
 * 换设备后若出现"认不出 / 老判不可信"，没有它就只剩改代码重装一条路（见 `progress.md` 第 415 条）。
 */
@Composable
private fun ParamsCard(
    thresholdText: String,
    marginText: String,
    minDistanceText: String,
    paramsValid: Boolean,
    hasArtifact: Boolean,
    onThresholdChange: (String) -> Unit,
    onMarginChange: (String) -> Unit,
    onMinDistanceChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.calibration_param_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.calibration_param_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { open = !open }) {
                    Text(
                        stringResource(
                            if (open) {
                                R.string.calibration_param_collapse
                            } else {
                                R.string.calibration_param_expand
                            },
                        ),
                    )
                }
            }
            if (open) {
                // 三个值**竖排 + 各自一行说明**（挤在一行正是"看不出是干嘛用的"的来源之一）
                ParamWithHint(
                    label = stringResource(R.string.calibration_param_threshold),
                    value = thresholdText,
                    onValueChange = onThresholdChange,
                    keyboardType = KeyboardType.Decimal,
                    hint = stringResource(R.string.calibration_param_threshold_hint),
                )
                ParamWithHint(
                    label = stringResource(R.string.calibration_param_margin),
                    value = marginText,
                    onValueChange = onMarginChange,
                    keyboardType = KeyboardType.Decimal,
                    hint = stringResource(R.string.calibration_param_margin_hint),
                )
                ParamWithHint(
                    label = stringResource(R.string.calibration_param_min_distance),
                    value = minDistanceText,
                    onValueChange = onMinDistanceChange,
                    keyboardType = KeyboardType.Number,
                    hint = stringResource(R.string.calibration_param_min_distance_hint),
                )
                if (!paramsValid) {
                    Text(
                        text = stringResource(R.string.calibration_param_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!hasArtifact) {
                    Text(
                        text = stringResource(R.string.calibration_param_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 一个参数 = 输入框 + 它下面那行「什么时候该改」（见 [ParamsCard] 的口径）。 */
@Composable
private fun ParamWithHint(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    hint: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
