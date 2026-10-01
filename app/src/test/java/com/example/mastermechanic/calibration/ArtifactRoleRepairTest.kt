package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.SignalNames
import com.example.mastermechanic.recognition.TemplateMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **补回"丢了角色的记录"**（2026-10-01，一次性修复工具）。
 *
 * ## 为什么要它
 *
 * 真机实证：用户重框「大厅设置入口」时**只写了锚点**（`写入已落盘 hall_settings｜ANCHOR｜大厅`），
 * 而更早那条 `hall_settings|marker`（标定产物里本该有的**另一条记录**，同一元素、同一模板、窗口不同）
 * 已经不在产物里了 ⇒ 结果 `state=HALL` 这条规则**没有任何标志** ⇒ **大厅认不出来** ⇒
 * 换号的起点判定（起点 = 大厅）与第 7 步验证都会失效。
 *
 * ## 它做什么
 *
 * 走**和 App 完全相同的写入路径**（`CalibrationSignals.upsert` + `SelectionWindow.forSelection`）：
 * 拿现成那条锚点的**模板与选区**，按**标志**的固定余量（48px/边）补一条标志记录，备注沿用元素级备注
 * （没有就按新规则派生）。**不发明新像素** —— 模板一字不动，只补"另一面"。
 *
 * 工作目录 `app/build/replay-work/`：`calibration-device.txt`（设备产物副本）⇒ 输出
 * `out/calibration-with-hall-marker.txt`（**不会**自动推到设备；由 `_tmp_check/push-artifact.ps1` 推）。
 * 就绪条件不足整体跳过（`-PfastTests` 也跳过）。
 */
class ArtifactRoleRepairTest {

    private val workDir = File(System.getProperty("user.dir") ?: ".", "build/replay-work")
    private val sourceFile = File(workDir, "calibration-device.txt")

    /** 修好的**产物**（给 push 用）。 */
    private val artifactOut = File(workDir, "out/calibration-repaired.txt")

    /** 报告（给人看；⚠ 别和产物写同一个文件 —— 第一版就是这么写的，产物被报告覆盖了）。 */
    private val reportOut = File(workDir, "out/calibration-repair-report.txt")

    /** 要补的角色：哪个元素缺了哪个角色（都是"元素两面"里少了的一面）。 */
    private data class Missing(val id: String, val state: UiState, val role: SignalRole, val keeper: SignalRole)

    private val missing = listOf(
        // 大厅设置入口：只剩锚点 ⇒ 补标志（它是 `state=HALL` 唯一的标志）
        Missing(id = "hall_settings", state = UiState.HALL, role = SignalRole.MARKER, keeper = SignalRole.ANCHOR),
    )

    @Test
    fun addMissingRolesAndReport() {
        assumeTrue("工作目录未就绪：需要 build/replay-work/calibration-device.txt", sourceFile.isFile)

        var data = CalibrationCodec.decode(sourceFile.readText(Charsets.UTF_8))
        val before = data.signals.size
        val lines = ArrayList<String>()
        lines += "# 补回缺失角色的产物（2026-10-01）"
        lines += "- 来源：`${sourceFile.name}`（设备产物副本，信号 $before 个）；产物写入 `out/${artifactOut.name}`"
        lines += "- 做法：`CalibrationSignals.upsert` + `SelectionWindow.forSelection`（与 App 写入同一路径）；" +
            "模板沿用同元素另一条记录，**不改一个像素**"

        missing.forEach { need ->
            val keeper = data.signals.firstOrNull { it.id == need.id && it.role == need.keeper }
            val already = data.signals.any { it.id == need.id && it.role == need.role }
            if (keeper == null || already) {
                lines += "- ⏭ `${need.id}` 跳过（已有 `${need.role.label}` 或找不到 `${need.keeper.label}`）"
                return@forEach
            }
            val template = keeper.templates.first()
            val bounds = keeper.window.pixelBounds(data.frameWidth, data.frameHeight)
            // 模板恒在窗口正中 ⇒ 反推这次框选的像素矩形（不能拿窗口当选区：两种角色的余量不同）
            val x0 = (bounds.x0 + bounds.x1) / 2 - template.width / 2
            val y0 = (bounds.y0 + bounds.y1) / 2 - template.height / 2
            val note = data.noteOf(need.id)
            data = CalibrationSignals.upsert(
                current = data,
                id = need.id,
                state = need.state,
                window = SelectionWindow.forSelection(
                    frameWidth = data.frameWidth,
                    frameHeight = data.frameHeight,
                    x0 = x0,
                    y0 = y0,
                    x1 = x0 + template.width,
                    y1 = y0 + template.height,
                    marginPx = SelectionWindow.marginPxFor(need.role),
                ),
                template = template,
                params = data.params,
                frameWidth = data.frameWidth,
                frameHeight = data.frameHeight,
                role = need.role,
                purpose = null,
                fallbackNote = SignalNames.defaultNoteFor(
                    state = need.state,
                    role = need.role,
                    // 用途只对锚点有意义（标志没有用途）
                    purpose = if (need.role == SignalRole.ANCHOR) data.purposeOf(need.id) else null,
                ),
            )
            val added = data.signals.first { it.id == need.id && it.role == need.role }
            val addedBounds = added.window.pixelBounds(data.frameWidth, data.frameHeight)
            val ops = TemplateMatcher.estimateCoarseOps(
                added.window, template.width, template.height, data.frameWidth, data.frameHeight,
            )
            lines += "- ✅ 补 `${need.id}｜${need.role.label}｜${need.state.label}`：" +
                "模板 ${template.width}x${template.height}、窗口 " +
                "${addedBounds.x1 - addedBounds.x0}x${addedBounds.y1 - addedBounds.y0}、" +
                "乘加 ${"%.4f".format(ops / 1_000_000.0)}M（预算 " +
                "${"%.1f".format(TemplateMatcher.MATCH_OPS_BUDGET / 1_000_000.0)}M 以内）" +
                (if (note.isBlank()) "（无附注 ⇒ 备注为空）" else "（备注沿用「$note」）")
        }

        // 备注订正（2026-10-01 用户口径"锚点的备注要用锚点用途"）：默认按**记录的角色**派生；
        // 老规则（整元素一句话）写下的自动文本靠 `legacyDefaultNoteFor` 认出来换掉（手写的永不动）。
        val (normalized, notesChanged) = CalibrationSignals.withDefaultNotes(
            current = data,
            defaultFor = { state, role, purpose -> SignalNames.defaultNoteFor(state, role, purpose) },
            autoNoteHistory = { state, roles, purpose -> SignalNames.autoNoteHistory(state, roles, purpose) },
        )
        data = normalized
        lines += "- ✅ 备注订正：动了 $notesChanged 个元素（锚点按自己的角色说用途 / 关闭控件 / " +
            "「界面」的锚点；标志保持「界面」的界面标志；手写的没动）"

        val text = CalibrationCodec.encode(data)
        artifactOut.parentFile?.mkdirs()
        artifactOut.writeText(text, Charsets.UTF_8)

        // 自校验：回读一遍，确认每个状态都有标志（否则那一屏认不出来）
        val back = CalibrationCodec.decode(text)
        lines += ""
        lines += "## 回读自校验"
        lines += "- 信号 ${before} → ${back.signals.size} 个（锚点 " +
            "${back.signals.count { it.role == SignalRole.ANCHOR }}）"
        val withoutMarker = UiState.entries.filter { state ->
            state.isCandidate &&
                back.stateRules.any { it.state == state } &&
                back.stateRules.first { it.state == state }.signalNames.isEmpty()
        }
        lines += "- **没有任何标志的状态**（这些屏会认不出来）：" +
            if (withoutMarker.isEmpty()) "无 ✓" else withoutMarker.joinToString("、") { it.label } + " ✗"
        lines += "- 逐条（只看这次补过的元素）：" + missing.joinToString("；") { need ->
            val records = back.signals.filter { it.id == need.id }.map { it.role.label }
            "${need.id} → ${records.joinToString("+")}"
        }
        lines += ""
        lines += "## 回读后的备注（逐条记录）"
        back.signals.forEach { entry ->
            lines += "- `${entry.id}｜${entry.role.label}` ⇒ 「${entry.note}」"
        }
        write(lines)

        assertNotNull("修复后 HALL 必须重新有标志", back.stateRules.firstOrNull { it.state == UiState.HALL })
        assertEquals(
            "HALL 的标志列表必须非空（否则大厅认不出来）",
            listOf("hall_settings"),
            back.stateRules.first { it.state == UiState.HALL }.signalNames,
        )
    }

    private fun write(lines: List<String>) {
        reportOut.parentFile?.mkdirs()
        reportOut.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        println("\n" + lines.joinToString("\n"))
    }
}
