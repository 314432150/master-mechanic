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

---

## U1 导航骨架（2026-10-01 代码完成；真机走查待用户）

**判据**：计划 U1 —— "4 个一级页与 4 个抽屉页都能到、系统返回逐级回退正确；旧 `screen` 编号地狱
（含空号 `2`）清除"。

| # | 判据 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | 一级 ≤5、路由不重名、落地页在一级里 | ✅ | `-PfastTests` **956 例 0 失败**（含新增 `RoutesTest` 4 例：`theBottomBarStaysWithinFiveDestinations` / `everyRouteIsNamedOnceAndNobodyIsEmpty` / `theLandingPageIsOneOfTheTopLevelOnes` / `theMenuKeepsTheFourSystemEntriesTheUserPicked`）|
| 2 | 旧 `screen` 编号地狱（含空号 `2`）清除 | ✅（代码层） | `MainActivity` 里已无 `when(screen)` / `rememberSaveable` 编号；目的地名字集中在 `ui/nav/Routes.kt`，旧编号→新路由的对应表写在该文件注释里 |
| 3 | 编译与装机 | ✅ | `assembleDebug` ✓；`adb install -r` + `am start` ✓（依赖新增 `navigation-compose 2.9.0` + `material-icons-core`） |
| 4 | **4 个一级页 + 4 个抽屉页逐项能到** | ⏳ **待真机走查** | 走查步骤见下 |
| 5 | **系统返回逐级回退**、到「运行」才退出 | ⏳ **待真机走查** | 同上 |
| 6 | 验收 V6（无障碍）：图标按钮有 `contentDescription`、`NavigationBar` 有文字标签 | 部分 ✅（代码层）：抽屉按钮 `nav_open_drawer`；底栏四项都带文字标签。⏳ TalkBack 实测待走查 | 代码 + 走查 |
| 8 | **底栏标签不被截断**（用户 2026-10-01："「拜访设置」「账号与好友」作为导航栏太长了"） | ✅ | **底栏一律 2 字**（运行 / 拜访 / 清单 / 标定），**页面标题仍用全名**；守卫单测 `StringResourcesTest.theBottomBarLabelsStayShortEnoughToNotGetTruncated`（直接扫 `strings.xml` 钉「≤2 字」）⇒ `-PfastTests` **957 例 0 失败**。⏳ 走查时确认**真机上没被截断成"拜访设…"** |
| 7 | 验收 V1 的另一半（"任意功能 ≤2 次点击可达"） | ⏳ **待真机逐项走查**（单测测不了） | 走查 |

### 真机走查步骤（U1，8 步）

1. **重装后先重新授权**（`-r` 会重置无障碍与采集授权；菜单里「⟳ 重新授权采集」可一键重建采集）；
2. 底栏四项**逐一点一遍**：运行 / 拜访设置 / 账号与好友 / 标定 —— 每项都到得了，**图标与文字都显示**；
3. 抽屉：从「拜访设置 / 账号与好友 / 诊断 / 设置 / 帮助」任意一页**左上角按钮**打开抽屉，
   再**从屏幕左缘往右滑**试一次（手势也要能开）；四项逐一点一遍；
4. 「账号与好友」两个 Tab 切换正常；回授权页点「服务器」/「好友」两个入口 ⇒ 分别落到**对应 Tab**；
5. **返回键**：从「账号与好友 → 好友清单」按返回 ⇒ **回到账号与好友**（不是退出 App）；
   回到底栏「运行」再按返回 ⇒ 才退出；
6. 「标定」进去后**页面自带页头**、**没有双层标题栏**（壳不给标定出标题）；占位页（拜访设置 / 诊断 / 设置 / 帮助）**有**壳的标题栏；
7. **一键重新授权采集**照旧可用：悬浮窗菜单点它 ⇒ App 到前台并弹系统采集授权窗（现在落到 `授权与权限`）；
8. 已知的两处**过渡态**属正常，不算 bug：① 占位页只写"本页在 M5-U3/U6 建设"；
   ② 「运行」与抽屉「授权与权限」现在长得一样（U2 / U6 会各自长出来）。
   ⤷ **2026-10-01 起第 ② 条已兑现一半**：U2 完成后「运行」是自己的页（三张状态卡 + 停止/继续），
   只有抽屉的「授权与权限」还是老授权页（U6 收拾）。

---

## U2「运行」页（2026-10-01 代码完成；真机走查待用户）

**判据**：计划 U2 —— 状态卡（授权是否齐 / 画面在不在来 / 流程第几步或结局）+ **停止 / 继续**；
**复用现有信号、不新造状态**；⚠ **不含"发起执行"**（用户 2026-10-01 拍板：发起只在悬浮窗）。

| # | 判据 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | 三块读数**全部来自现有信号**（本页不持有状态） | ✅（代码层） | 授权 = `AuthorizationChecks.collect`；画面 = `CaptureSessionSignal` + `FrameFreshness`；流程 = `PatrolSession` + `PatrolStatus` + `PatrolResultSignal.displayState`（与悬浮窗标签同一处） |
| 2 | **画面这一项从最严重往下说**（会话不在 > 没被投喂 > 停更 > 正常） | ✅ | 纯逻辑 `RunPageLogic.frameState` + 用例 `RunPageLogicTest.theMostSevereFrameProblemWins`（三种毛病的**处置不同**，说错用户就白折腾） |
| 3 | 停止 / 「该回游戏继续」**与悬浮窗同一条判据** | ✅ | `RunPageLogic.actions` 直接借 `PatrolStatus.showsControlRow` / `canResume` + 用例 `thePageOnlyStopsAndPointsAtTheRightPlaceToContinue`（无流程 / 运行中 / 暂停 / 中止 / 已完成五种） |
| 4 | 「停止」走**同一条消费链** | ✅（代码层） | 按钮发 `PatrolRequestSignal.Request(Kind.STOP)` ⇒ `PatrolRequestConsumer`（与悬浮窗控制行完全一致，日志 `MM-Patrol` 同一句） |
| 4b | **「继续」在 App 里不做按钮**（用户 2026-10-01 真机："在 App 里出现继续按钮没有意义，因为游戏不在前台"） | ✅ | 页面改为显示「**已停在第 N 步：请回到游戏，点悬浮窗的「继续」**」（`run_resume_in_game`）；判据仍由 `PatrolStatus.canResume` 决定**显不显示这句**。依据：跑号每一步都以**游戏在前台**为前提，而在 App 里按「继续」时前台正是我们自己 ⇒ 要么被门禁拦、要么立刻再暂停。与"发起只在悬浮窗"同一条口径 |
| 5 | 授权摘要（缺几项） | ✅ | `RunPageLogic.authReady` / `missingAuthCount`（借 `AuthorizationSummary`）+ 用例 |
| 6 | 无"发起执行"入口 | ✅ | 页面只有「去授权与权限 / 重新授权采集 / 停止 / 继续」四个动作，无换号或拜访按钮 |
| 7 | 全量单测 + 编译 + 装机 | ✅ | `-PfastTests` **960 例 0 失败**（+3 `RunPageLogicTest`）✓ ＋ `assembleDebug` ✓ ＋ `adb install -r` + `am start` ✓ |
| 8 | **真机走查**：跑号中本页与悬浮窗标签说**同一句话** | ⏳ **待用户** | 走查：① 空态（没跑）⇒ 流程卡写「当前没有进行中的流程」、**没有**任何按钮；② 跑号中 ⇒ 进度行与悬浮窗标签一致，只有「停止」可按；③ 暂停（切出去 >3.5 秒）⇒ 出现「**已停在第 N 步：请回到游戏，点悬浮窗的「继续」**」，回游戏点悬浮窗的「继续」真能接着跑；④ 停更时 ⇒ 画面卡说「已停更 N 秒」，点「重新授权采集」能重建；⑤ 走查前先重新授权（`-r` 重装会重置） |
