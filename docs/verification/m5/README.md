# M5 取证索引（界面重构：信息架构 + 文案精简）

> 计划与验收草案：[../../plans/m5-ui-restructure.md](../../plans/m5-ui-restructure.md)
> 口径与决策：[ADR-009 界面结构](../../decisions/ADR-009-界面结构（导航与信息架构）.md)（提议）、
> [ADR-008 信息级别与配色](../../decisions/ADR-008-信息级别与配色.md)（不改）

每张卡完成后，证据按卡归档到本目录（一个卡一个子目录或一节），格式沿用 M1~M4：
**判据 → 结论 → 原始证据（日志 / 命令 / 单测名）**，未取证与偏差照实写。

---

## U7 第一刀：死文案清零（2026-10-01 完成）✅

**判据**：`requirements`/计划 V3「**零死文案**：`strings.xml` 内每条都被引用」＋ 计划 §4 规则 6「删 `patrol_*`」。

| # | 判据 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | `patrol_*` 残留清零 | ✅ | 删 60 条 `patrol_*` + 1 条 `auth_open_patrol_config`（旧「跑号清单」，M4 被「拜访规则」取代后的壳）；删前仓库级搜索确认**零引用** |
| 2 | 其余未引用文案清零 | ✅ 再删 **36 条** | 由**新写的守卫单测**列出：`floating_menu_*`（拜访规则 CONFIG 旧文案）/ `floating_reason_config_*` / `floating_side_*` / `floating_label_popup_blocked` / `calibration_friend_*` / `calibration_workbench_frames` / `calibration_workbench_write_hint` / `calibration_note_hint` / `calibration_view` / `calibration_signal_template` / `calibration_delete_artifact_confirm_*` / `calibration_artifact_present` / `capture_servers_*` / `server_index` / `server_item_separator` |
| 3 | 条数下降 | ✅ **454 → 361（-93，-20.5%）** | `Select-String -Pattern '<string '` 数行：改前 `git show HEAD:…strings.xml` = 454，改后 = 361。⚠ 计划 V3 的"≥30%"是 **U7 整卡**目标，第一刀只吃掉死文案 ⇒ 剩下的靠 U1~U6 逐屏重写时达成 |
| 4 | 防复发（不是"删完就算"） | ✅ 新增守卫 | `app/src/test/java/com/example/mastermechanic/ui/StringResourcesTest.kt#everyStringResourceIsReferenced`：扫 `strings.xml` 的 `<string name>` ↔ `src/` 全部 `kt`/`java`/`xml` 里的 `R.string.X` / `@string/X`，差集非空即失败并列出名字 |
| 5 | 全量单测不回归 | ✅ | `-PfastTests` **952 例 0 失败**（951 → 952，+1 守卫）；`assembleDebug` ✓；`adb install -r` + `am start` ✓ |

**这套守卫"不覆盖"什么（如实记录，不当成通过）**：① 动态取名（`getIdentifier("x","string",…)`）认不出来；
② 只检默认 `values/`（将来加 `values-en/` 要同步扩）；③ `<string-array>` / `<plurals>` 的条目没有独立名字，
不在待检集合里；④ **只扫 `app/src`** ⇒ 多模块工程会假阳性（本工程 `settings.gradle.kts` 只有 `:app`，已确认）。

**踩坑（已写进测试注释）**：① Kotlin 块注释**可嵌套** —— 在 KDoc 里写 `src/**` 会被当成嵌套注释开始
（`Unclosed comment` 编译错）；② 以引号结尾的 raw string（`"""…""""`）不合语法，改用转义普通串。
