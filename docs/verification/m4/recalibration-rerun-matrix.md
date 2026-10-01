# 重标定之后：哪些离线回放 / 探针必须重跑（2026-09-23）

> **触发**：游戏赛季更新导致 UI 变化，用户**已重录帧池、已重新框选产物**（基本流程不变）。
> 本文件只回答一件事：**换掉帧池 / 产物之后，哪些离线回放与探针必须重跑、输入是什么、判据是什么。**
>
> 事实基线：`app/src/test/java/com/example/mastermechanic/**` 下 9 个 `*ProbeTest` + 1 个 `*BatchRunTest`
> （共 10 个类、19 个 `@Test`）全部逐条核对过输入/输出/跳过条件/判据；批次与产物文件名逐处列出（见 §3）。

## 0. 一条硬口径：**产物与帧池必须同源**

这是本轮排查最重要的结论，也是最容易踩的坑：

> **帧池是"哪个界面版本"，产物就必须是"同一个界面版本下框出来的"** —— 两者缺一，回放量出来的
> 东西就没有意义：拿新界面的模板去搜旧界面的帧，分数只会普遍偏低，看起来像"识别坏了"，
> 实际是**输入配错了**。

所以重标定之后**不能只换一半**：

| 只换产物 | 只换帧池 | 两个都换（正确） |
| --- | --- | --- |
| 旧帧里没有新 UI ⇒ 分数普遍偏低、误判成"模板框歪了" | 新帧里没有旧模板 ⇒ 同上 | 分数区间与旧批次可比，结论可信 |

`CanvasOrientationProbeTest` 是这条口径的**活标本**：它的帧取自**归档不动的文档帧**
（`docs/recognition/samples/t1-5-*` 与 `docs/verification/t1-10/*`），而产物取 `app/build/replay-work/calibration.txt`。
**产物一换，它的断言（分数 ≥ 0.9、残差 ≤ 2px）必然失败** —— 不是探针坏了，是"新模板 × 旧界面帧"。

## 1. 重跑矩阵

路径基准：绝大多数探针的 `user.dir` = **模块目录 `app/`**，故 `build/replay-work` 实为 **`app/build/replay-work/`**。
（原先还有一个用 `.parentFile` 上溯仓库根的 `DeviceFrameIdentityProbeTest`，**2026-09-23 已删**。）

| # | 测试类 | 输入 | 输出 | 跳过条件（`assumeTrue`，没准备好会**静默跳过**） | 通过判据 | 界面已变后 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `ReplayBatchRunTest.exportRawBatchToGray` | `app/build/replay-work/raw/*.png` | `app/build/replay-work/set-20260912-03/*.png`（同名灰度） | `raw 目录未就绪：把设备原始帧放入 build/replay-work/raw/ 后重跑` | 导出帧数 `> 0` | **必跑**（新帧池入库第一站） |
| 2 | `ReplayBatchRunTest.diagnoseBatch` | `calibration.txt` + `set-20260912-03/manifest.txt` | `out/diagnostics.txt` | `工作目录未就绪：放入 calibration.txt 与 batch/manifest.txt 后重跑` | 诊断表行数 `>= 3` | **必跑**（但须先换新批次目录名） |
| 3 | `ReplayBatchRunTest.generateWorkReport` | 同上 | `out/report-YYYYMMDD-主题.md` | 同上 | 报告文件存在 | **必跑** |
| 4 | `SpeedupProbeTest.evaluateSpeedupVariants` | `calibration.txt` + `set-20260912-03/manifest.txt` | `out/t1-10e-speedup.txt` | `工作目录未就绪：需要 build/replay-work/calibration.txt 与 set-20260912-03/manifest.txt` | 复刻基线 vs 生产实现**不一致帧数 == 0**；结果行数 `== variants.size` | **必跑**（验证提速改动在新帧上不改判定） |
| 5 | `SpeedupProbeTest.profileMultiSignalCost` | `calibration-7sig.txt` + `set-20260912-03/manifest.txt` | `out/t1-10f-cost-profile.txt` | `需要 build/replay-work/calibration-7sig.txt 与 set-20260912-03/manifest.txt` | 计数非空 | 可延后（成本测量；7 信号产物须重新导出） |
| 6 | `SpeedupProbeTest.measureOnDeviceFrames` | `calibration-7sig.txt` + `raw-67/`（顺带灰度化到 `set-20260912-06/`） | `out/t1-10f-device-frames-67.txt` | `需要 build/replay-work/calibration-7sig.txt 与 raw-67/（设备帧池副本）` | `times.size == files.size` | 可延后 |
| 7 | `SpeedupProbeTest.measureSubsetSavings` | `calibration-7sig.txt` + `set-20260912-06/` | `out/t1-10g-subset-savings.txt` | `需要 build/replay-work/calibration-7sig.txt 与灰度帧 set-20260912-06/` | 两个 `size` 都 `== files.size` | 可延后 |
| 8 | `SpeedupProbeTest.profilePerSignalCost` | `calibration-7sig.txt` + `set-20260912-03/manifest.txt` | `out/t1-13b-per-signal-cost.txt` | `需要 build/replay-work/calibration-7sig.txt 与 set-20260912-03/manifest.txt` | `perSignal.size == data.signals.size` 且 `fullTimes.size == frames.size` | **必跑**（逐信号耗时证据表要重填） |
| 9 | `CalibrationReviewProbeTest` | `t2-2-artifact.txt` + `t2-2-frames/*.png` | `out/t2-2-per-signal.txt` | `工作目录未就绪：需要 build/replay-work/t2-2-artifact.txt 与 t2-2-frames/*.png` | **至少一帧判出「活动弹窗」** | **必跑** |
| 10 | `PopupWatchProbeTest` | `t2-2-artifact.txt` + `set-20260912-03/` | `out/t2-5-popup-watch.txt` | `工作目录未就绪：需要 build/replay-work/t2-2-artifact.txt 与 set-20260912-03/（manifest.txt + PNG）` | ① 三类负背景**零误命中**；② `HALL_POPUP` 命中 `> 0`（**条件断言**：仅当全批命中过弹窗才断言）；③ 搜索集合恒为 `{popupNames}` 且不含 `launch_start` | **必跑**（FR-01 的误报面） |
| 11 | `AnchorLocateProbeTest` | `t2-2-artifact.txt` + `t2-2-frames/*.png` | `out/t2-3-anchor-locate.txt` | `工作目录未就绪：需要 build/replay-work/t2-2-artifact.txt 与 t2-2-frames/*.png` | **纯打印、无断言**（人读：同屏多帧分数应稳定） | **必跑**（锚点是否框歪的第一现场） |
| 12 | `FarmMergeProbeTest` | `t2-2-artifact.txt` + `t2-2-frames/*.png` | `out/t4-3-farm-merge-cross.txt` | 同上 | **纯打印、无断言**（末尾直接写「结论」段） | **必跑**（两个农场能否共用一条锚点） |
| ~~13~~ | ~~`DeviceFrameIdentityProbeTest`~~ | — | — | — | **2026-09-23 随农场归属判定整块删除**（它量的 `own_avatar` / `friend_avatar` 已不存在） | **不用跑** |
| 14 | `CanvasOrientationProbeTest`（2 例） | `app/build/replay-work/calibration.txt` + `docs/recognition/samples/t1-5-launch-start-frame.png` +（优先 `app/build/replay-work/t1-11a-horizontal-frame.png`，否则 `docs/verification/t1-10/t1-10-05-launch-page-session1.png`） | `out/t1-11a-normalized-best.png` / `out/t1-11a-vertical-source.png` | `探针输入未就绪（标定产物 / 竖画布帧 / 横画布侧帧）` | 最优模型锚点分数 `>= 0.9`；位置残差 `<= 2px`；两会话状态均 `== LAUNCH_PAGE`；两侧命中位置差 `<= 2px` | **不能"只换产物"**：帧是归档旧 UI ⇒ 换产物后断言必然失败（见 §0） |
| 15 | `FarmIdentityProbeTest`（3 例）<br>⚠️ 名字是历史名 | `t2-2-artifact.txt` + `t2-2-frames/*.png` | `out/t4-3-farm-identity-probe.txt` + `out/t4-3-crops/*.png` | `需要 t2-2-artifact.txt 与 t2-2-frames/` | **纯打印、无断言**（交叉分数 / NCC / 文字行高 / 横向跨度） | **必跑**，但**测的不再是身份判据**（那条 2026-09-23 已删）：它量的是"两个农场能否共用同一条返回 / 好友入口锚点"（合并前提），与 `FarmMergeProbeTest` 同源 |
| 16 | `NameLocateProbeTest` | `t2-2-artifact.txt` + `t2-2-frames/*.png` | `out/t4-3-name-locate-probe.txt` + `out/t4-3-crops/*.png` | `需要 build/replay-work/t2-2-artifact.txt 与 t2-2-frames/（真机帧池）` | **纯打印、无断言**（文字行几何 / 跨列一致性 / 区分度 / 跨帧稳定性 / 负样本对照 / 位置微移敏感度） | **必跑**（OCR 输入区域与名称列的口径依据） |

> 这 10 个类**全部被 `-PfastTests` 排除**（`app/build.gradle.kts` 的过滤规则：
> `excludeTestsMatching("*ProbeTest")` / `excludeTestsMatching("*BatchRunTest")`）。
> 因此切片收尾的 `:app:test -PfastTests` **一条都不会跑**，必须显式点名，例如：
> `:app:testDebugUnitTest --tests "*AnchorLocateProbeTest*"`。

## 2. 怎么排优先级

按"**它护着什么**"排，而不是按文件顺序：

1. **先跑能证伪标定质量的**（框歪了、模板取进了动态元素）：#11 `AnchorLocateProbeTest`、
   #9 `CalibrationReviewProbeTest`、#10 `PopupWatchProbeTest`。分数区间与误报面是后续一切的前提。
2. **再跑判定口径没被改坏的**：#4 `evaluateSpeedupVariants`（提速前后判定逐帧一致）。
3. **再跑"点错人"这条唯一的离线防线**：#16 `NameLocateProbeTest`（第 9 步按名称定位）、
   #12 `FarmMergeProbeTest`、#15 `FarmIdentityProbeTest`（农场合并前提）—— 2026-09-23 起
   **不再有"到达后回头确认是哪位好友"这一层**，所以名称定位的准确性就是全部。
4. **最后补性能证据表**：#8 `profilePerSignalCost`（回填 `docs/verification/m4/t4-perf-anchor-locate-scope.txt` 一类表格）。
5. #14 `CanvasOrientationProbeTest` **单独处理**：它测的是画布几何归一（与游戏 UI 无关），
   但断言绑在 `calibration.txt` 上 ⇒ 要么把它指向与新产物同源的新帧池，要么明确记录"本轮该探针失效"。
   **不要**在不换帧的情况下重跑它然后宣布"报告变红了"。

## 3. 硬编码的批次名 / 产物文件名（换新帧池要改的地方）

一共只有 **10 个文件**（8 类名字）需要动，逐个列出（行号为 2026-09-23 状态）：

| 名字 | 出现位置 |
| --- | --- |
| `set-20260912-03` | `recognition/ReplayBatchRunTest.kt:26`；`recognition/SpeedupProbeTest.kt:29,34,82,216,494,500`；`recognition/PopupWatchProbeTest.kt:27,36,53,68,81` |
| `set-20260912-06` | `recognition/SpeedupProbeTest.kt:276,286,359,360,362`（**仅此一处，文档零引用**） |
| `raw-67` | `recognition/SpeedupProbeTest.kt:276,282,283,285`（**仅此一处，文档零引用**） |
| `t2-2-artifact.txt` | `recognition/AnchorLocateProbeTest.kt:30,37`；`CalibrationReviewProbeTest.kt:29,38`；`FarmMergeProbeTest.kt:39`；`FarmIdentityProbeTest.kt:36`；`NameLocateProbeTest.kt:43`；`PopupWatchProbeTest.kt:26,35` |
| `t2-2-frames` | `AnchorLocateProbeTest.kt:30,38`；`CalibrationReviewProbeTest.kt:30,39`；`FarmMergeProbeTest.kt:40,46,55`；`FarmIdentityProbeTest.kt:37`；`NameLocateProbeTest.kt:44` |
| `calibration.txt` / `calibration-7sig.txt` | `ReplayBatchRunTest.kt:27`；`SpeedupProbeTest.kt:35,37`；`CanvasOrientationProbeTest.kt:44` |
| `t1-11a-horizontal-frame.png`（回退到 `docs/verification/t1-10/…`） | `CanvasOrientationProbeTest.kt:45,48-51` |
| ~~`_tmp_check/replay-pull/{calibration.txt,frames}`~~ | 原 `DeviceFrameIdentityProbeTest.kt:34-38` —— **该探针 2026-09-23 已删，这一项随之作废** |

**改批次名时务必同时改两处**：① 顶部注释里的路径说明（那些注释是"下一个人照着做"的唯一线索）；
② `assumeTrue` 的文案（它会把文件名念给用户听，是**跳过时唯一的提示**）。

## 4. 新帧池的归档与命名约定

沿用既有约定，不另造一套：

- **批次目录**：`app/build/replay-work/set-YYYYMMDD-NN/`（同一天多批用 `-01/-02/…` 递增）。
- **目录内容**：`manifest.txt` + 灰度 PNG（**同名**）。
- **清单格式**（`recognition/ReplaySet.kt`，v1）：
  ```
  format=mm-replay-set
  version=1
  device=<设备>
  scene=<场景说明>
  sample=<文件>|<类别>|<信号名>|<矩形>|<备注>
  ```
  类别 token：`positive` / `neg-mask` / `neg-motion` / `neg-similar` / `neg-plain`；
  **负样本的信号名与矩形留空**；矩形是比例矩形 `left,top,right,bottom`（0..1）。
  解析是**严格**的（未知键 / 非法类别 / 字段数不对 / 重名 → 抛错并带行号）。
- **入库路径**：设备原始帧先放 `app/build/replay-work/raw/`，再跑
  `--tests "*ReplayBatchRunTest*"` 里的 `exportRawBatchToGray` 灰度化（或由 `SpeedupProbeTest.measureOnDeviceFrames` 顺带导出）。
- **产物副本**：与批次**同源**的那一份产物，建议另存一个带日期的名字（现有 `t2-2-artifact.txt` 就是这个角色，
  并且已经有 `.before-t24.txt` 这种备份先例）。
- **取帧脚本**：设备帧池从应用私有目录拉取 —— `_tmp_check/mm-pull-frames.ps1`（当前 `OutDir` 默认 `_tmp_check\mm-frames`）。
  文本类文件（日志 / 产物）用 `_tmp_check/mm-cat-log.ps1`（**不带 `-t`**：无线 adb 的 transport id 每次都会变）。

> ⚠️ **`app/build/replay-work/calibration.txt` 不要覆盖**：`SpeedupProbeTest` / `CanvasOrientationProbeTest` /
> `ReplayBatchRunTest` 都用它。要换新产物时，**先备份**再替换（与 `t2-2-artifact.before-t24.txt` 同一做法）。

## 5. 复跑命令（Windows）

```powershell
# 单类点名（默认不带 -PfastTests，探针可以跑）
.\gradlew.bat :app:testDebugUnitTest --tests "*AnchorLocateProbeTest*"

# 一次跑完"必跑"那批（注意：-PfastTests 会把它们全排除，所以这里不能用它）
.\gradlew.bat :app:testDebugUnitTest --tests "*AnchorLocateProbeTest*" --tests "*CalibrationReviewProbeTest*" --tests "*PopupWatchProbeTest*"

# 切片收尾（业务全量，跳过全部探针与批次）
.\gradlew.bat :app:test -PfastTests

# 里程碑收尾真全量（含探针，约 3.5 分钟）
.\gradlew.bat :app:test
```

本机经 `_tmp_check\run-gradle.ps1` 转发（`JAVA_HOME` 内联不生效），把上面参数原样传给该脚本即可。

## 6. 未取证 / 待用户介入（如实记录）

- **本次未实际重跑任何探针**：`app/build/replay-work/` 里现存批次仍是**旧界面**的
  （`set-20260912-03` 104 帧 / `set-20260912-06` 67 帧 / `raw-67` 67 帧 / `t2-2-frames` 76 帧，
  均为 2026-09-12 ~ 09-19 录制；帧数为 2026-09-23 实地清点），新赛季帧池**尚未入库**。
- **设备当前不在线**（`adb install` 报 `no devices/emulators found`）⇒ 新帧池的拉取与真机取证
  等设备接入后执行。
- **判定"旧结论是否失效"需要界面差异的证据**：新旧界面对比截图（顶栏 / 弹窗样式 / 好友列表）
  应归档到本目录，作为"旧结论作废"的依据；未归档前不宣称任何旧结论仍然成立。
