package com.example.mastermechanic.ui.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import com.example.mastermechanic.action.PopupCloseSignal
import com.example.mastermechanic.calibration.CalibrationSignals
import com.example.mastermechanic.calibration.CalibrationStore
import com.example.mastermechanic.floating.FloatingLabelResetSignal
import com.example.mastermechanic.floating.FloatingLabelStore
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.patrol.PatrolRequestSignal
import com.example.mastermechanic.ui.ArtifactState
import com.example.mastermechanic.ui.loadArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock

/** 日志标签（与悬浮窗 / 诊断页同一套 `MM-` 前缀口径）。 */
private const val TAG = "MM-Settings"

/**
 * 抽屉「**设置**」页（M5-U6 的第一段；2026-10-02 先落「识别参数」这一块）。
 *
 * ## 识别参数为什么一路搬到这里（用户 2026-10-02 三次意见）
 *
 * ① 用户在标定页看到它"**藏在太靠下、三个值太过抽象**"⇒ 先做成默认收起的「高级」折叠；
 * ② 用户仍嫌"**在最底部**"⇒ 搬进抽屉 → **诊断**；
 * ③ 用户接着指出"**这个菜单放在诊断里好像不合适吧**" ⇒ 搬到这里。
 * ③ 是对的：**诊断是"看问题"的地方，这里是"改东西"的地方** —— 参数是配置，
 * 与「自动关弹窗 / 悬浮窗提示位置 / 退出 App」同类，归「设置」才符合心理模型。
 *
 * ⚠ 别再往「诊断」搬：那边只放**只读读数**（画面在不在来 / 识别结果 / 耗时 / 日志）。
 *
 * ## 与标定的关系
 *
 * 参数属于**标定产物**（`CalibrationStore` 里的 `params=`），这里只是**换了个入口**：
 * 读写同一个文件、同样的"编辑合法即写入"语义、同样的默认值（`CalibrationSignals.DEFAULT_*_TEXT`）。
 * 标定页不再出现任何参数输入框 —— 产物里已有的值照旧生效（**行为零回归**）。
 *
 * ## 本页内容（U6 第三段，2026-10-19 补齐）
 *
 * 帧率 / 停更 / 当前识别画面 / 单帧耗时 / 日志 那些**只读读数** 2026-10-03 已落进抽屉「诊断」页
 * （`ui/diagnostics/`）—— 判据：**诊断 = 看问题、设置 = 改东西**，所以它们不在本页。
 * 本页 = 四块**可改的东西**：识别参数（产物）/ 自动关弹窗 / 提示位置 / 退出 App。
 *
 * 三块新卡的口径（与悬浮窗同一份数据，不新造开关）：
 * - **自动关弹窗**：直接读写 `PopupCloseSignal.paused`（与「更多」层那一行同一个字段）⇒ 两边永远一致；
 *   它是**用户意图、不是落盘配置**（见那个字段的说明），所以这里如实写"重启后恢复自动"。
 * - **提示位置**：清落盘（`FloatingLabelStore.clear`）+ 发 [FloatingLabelResetSignal] ⇒ 悬浮窗当场重落位，
 *   不是"等下次挂载才生效"。
 * - **退出 App**：与「更多 → 退出」同一套（先发「停止」、再延迟一小拍杀进程）。
 */
@Composable
fun AppSettingsRoute(resumeTick: Int) {
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
    // 自动关弹窗是**进程内开关**（与悬浮窗「更多」同一份数据）⇒ 回到前台时重新对齐，免得显示旧值
    var autoCloseOn by remember { mutableStateOf(!PopupCloseSignal.paused) }
    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(resumeTick) { autoCloseOn = !PopupCloseSignal.paused }

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

    AppSettingsScreen(
        artifact = artifact,
        thresholdText = thresholdText,
        marginText = marginText,
        minDistanceText = minDistanceText,
        paramsValid = CalibrationSignals.parseParams(thresholdText, marginText, minDistanceText) != null,
        onThresholdChange = { thresholdText = it; applyParams() },
        onMarginChange = { marginText = it; applyParams() },
        onMinDistanceChange = { minDistanceText = it; applyParams() },
        autoCloseOn = autoCloseOn,
        onAutoCloseChange = { on ->
            // 与悬浮窗「更多 → 暂停/恢复自动关弹窗」**同一个字段** ⇒ 两边不会各说各话
            PopupCloseSignal.paused = !on
            autoCloseOn = on
            MmLog.i(
                TAG,
                if (on) "设置页：恢复自动关弹窗" else "设置页：暂停自动关弹窗（只识别、不动手）",
            )
            message = context.getString(
                if (on) R.string.settings_auto_close_resumed else R.string.settings_auto_close_paused,
            )
        },
        onResetLabel = {
            FloatingLabelStore.clear(context)
            FloatingLabelResetSignal.request()
            MmLog.i(TAG, "设置页：重置悬浮窗提示位置（落盘已清 + 请求悬浮窗当场重落位）")
            message = context.getString(R.string.settings_label_reset_done)
        },
        onExit = { confirmExit = true },
        snackbarHostState = snackbarHostState,
    )

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.settings_exit_confirm_title)) },
            text = { Text(stringResource(R.string.settings_exit_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        exitApp()
                    },
                ) { Text(stringResource(R.string.settings_exit_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) {
                    Text(stringResource(R.string.settings_exit_dismiss))
                }
            },
        )
    }
}

/**
 * **退出 App**（2026-10-19；与悬浮窗「更多 → 退出」同一套）。
 *
 * 进程一死，无障碍服务 / 采集会话 / 悬浮窗一起停 —— 不会出现"只剩服务在跑、界面没了"的半死状态。
 * 先发一次「停止」让消费方把流程状态清干净并留痕，再延迟一小拍杀进程（给日志落盘留时间）。
 */
private fun exitApp() {
    MmLog.i(TAG, "设置页：用户点了「退出」⇒ 停止流程 + 结束进程")
    runCatching {
        PatrolRequestSignal.request(
            PatrolRequestSignal.Request(
                kind = PatrolRequestSignal.Kind.STOP,
                nowMs = SystemClock.elapsedRealtime(),
            ),
        )
    }
    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 400L)
}

@Composable
private fun AppSettingsScreen(
    artifact: ArtifactState,
    thresholdText: String,
    marginText: String,
    minDistanceText: String,
    paramsValid: Boolean,
    onThresholdChange: (String) -> Unit,
    onMarginChange: (String) -> Unit,
    onMinDistanceChange: (String) -> Unit,
    autoCloseOn: Boolean,
    onAutoCloseChange: (Boolean) -> Unit,
    onResetLabel: () -> Unit,
    onExit: () -> Unit,
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
            SettingCard(
                title = stringResource(R.string.settings_auto_close_title),
                description = stringResource(R.string.settings_auto_close_desc),
            ) {
                Switch(checked = autoCloseOn, onCheckedChange = onAutoCloseChange)
            }
            SettingCard(
                title = stringResource(R.string.settings_label_title),
                description = stringResource(R.string.settings_label_desc),
            ) {
                TextButton(onClick = onResetLabel) {
                    Text(stringResource(R.string.floating_menu_reset_label))
                }
            }
            SettingCard(
                title = stringResource(R.string.settings_exit_title),
                description = stringResource(R.string.settings_exit_desc),
            ) {
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.settings_exit_action))
                }
            }
        }
    }
}

/**
 * 设置页的一块：**左边标题 + 右边控件**，说明挂在标题下面一行。
 *
 * 统一这个形状的理由（ADR-009 决策三）：设置项一律"标题 + 一句说明 + 一个控件"，
 * 控件放右侧、位置固定 ⇒ 扫一眼就知道哪几项是开关、哪几项是动作。
 */
@Composable
private fun SettingCard(
    title: String,
    description: String,
    action: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                action()
            }
            Text(
                text = description,
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
