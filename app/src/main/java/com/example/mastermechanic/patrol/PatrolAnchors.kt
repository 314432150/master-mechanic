package com.example.mastermechanic.patrol

import com.example.mastermechanic.decision.UiState

/**
 * 跑号每一步「点哪个锚点、点完看到什么」的契约（M4-T4-3 离线部分）。
 *
 * ## 为什么需要这一层
 *
 * 锚点是**标定产物里的记录**（`anchor=<状态>|<元素 ID>`，模板挂在同一条记录上）。
 * 两边共用的那个词是**用途**（[Purpose]）：产物里的锚点带 `purpose=` 字段，**锚点的元素 ID 也就是用途名**
 * （`launch_login`，见 `CalibrationSignals.targetFor`），所以"第 6 步点哪个锚点"= 按「状态 + 用途」查
 * （`CalibrationData.anchorIdFor`）。查不到就如实说"这个用途还没标定"，绝不拿别的锚点去点
 * （落空是安全的，猜中才是危险的）。
 *
 * **同一个界面常有不止一个要点的控件**（大厅 = 设置入口 + 农场入口），用途正是用来分开它们的：
 * 用途是**标定与代码之间的契约** —— [purposesFor] 给出的名字与 [all] / [actions] 用的是同一批常量。
 * 没有用途可选的界面（活动弹窗只有一个关闭控件）`purpose` 为空——FR-01 按状态取锚点，不看用途。
 *
 * ## 点击链（[actions]）
 *
 * 一步不一定只点一下，**每一击之间都有一次画面确认**（`Action.expect`）——这样"点完到底进没进去"
 * 才有答案，而不是一路盲点下去（FR-04 硬性要求 1）。两处需要多击的（都是 2026-09-19 真机取证后定的）：
 *
 * | 步骤 | 点击链 |
 * | --- | --- |
 * | 2 退出农场 | 农场 / 好友的农场点「返回」→ **返回场景确认框**选「返回大厅」→ 大厅 |
 * | 3 退出登录 | 大厅点「设置入口」→ **设置页**点「退出登录」→ **退出登录确认框**点「确定」→ 启动页 |
 *
 * 其余步骤都是一击：`farm_friends` / `hall_farm` / `launch_switch` / `launch_login` 等；
 * 第 5 / 9 步不走锚点（在列表里按名称挑，见 [NameLocator]）。
 */
object PatrolAnchors {

    // ------------------------------------------------------------------ 锚点名（= 用途名）

    /** 大厅 · 设置入口（第 3 步的第一击）。 */
    const val HALL_SETTINGS = "hall_settings"

    /** 大厅 · 王者农场入口（第 7 步）。 */
    const val HALL_FARM = "hall_farm"

    /** 启动页 · 换区（第 4 步）。 */
    const val LAUNCH_SWITCH = "launch_switch"

    /**
     * 启动页 · 开始游戏（第 6 步）。
     *
     * 刻意**不叫** `launch_start`：那个名字历史上是"启动页"这个状态的**标志**记录名，
     * 而用途名一旦写进产物与文档就不好再改（现在标志槽位叫 `launch_page_e1`、已不会撞名），故沿用。
     */
    const val LAUNCH_LOGIN = "launch_login"

    /** 农场 · 返回 / 退出（第 2 步）。 */
    const val FARM_EXIT = "farm_exit"

    /** 农场 · 好友入口（第 8 步）。 */
    const val FARM_FRIENDS = "farm_friends"

    /** 好友的农场 · 返回 / 退出（第 2 步；与自己的农场是两个模板，名字也分开）。 */
    const val FRIEND_FARM_EXIT = "friend_farm_exit"

    /** 好友的农场 · 好友入口（第 8 步）。 */
    const val FRIEND_FARM_FRIENDS = "friend_farm_friends"

    /**
     * 好友列表 · **每行最右侧的「拜访」图标**（第 9 步，2026-09-21 用户补框）。
     *
     * 第 9 步的流程是"**先按名字找到那一行，再点这一行的拜访图标**"：
     * 名字由文字识别给出（`NameLocating`），点击点由本锚点给出 ——
     * 真机核对（2026-09-19）：好友面板每行是「名字 + 层级 + 申请 + 最右侧拜访 icon」，
     * **拜访要点的就是那个最右侧 icon**（名字、中间的「申请」都不点）。
     *
     * 所有行共用同一个图标样式 ⇒ 只需**框一次**（不像名字那样一个名字一条）；
     * 运行时把它的搜索窗口在 **y 上收窄到目标名字所在的那一行**（x 沿用标定窗口，图标在同一列）。
     */
    const val FRIEND_VISIT = "friend_visit"

    /**
     * 好友列表 · 底部的「**搜好友**」入口（第 9 步的**搜索式查找**，2026-10-01 用户提出并拍板）。
     *
     * ## 完整点击顺序（用户 2026-10-01 明确校正过，别搞错）
     *
     * ```
     * ① 点「搜好友」            ← 本锚点
     * ② 点「请输入好友昵称」      ← [FRIEND_SEARCH_FIELD]（点它才会聚焦、才弹输入法）
     * ③ 输入昵称（原生写入，见下）
     * ④ 输入完成 ⇒ 收输入法      ← ⚠ 这一步用户特别提示过（见 [FRIEND_SEARCH_FIELD] 的说明）
     * ⑤ 点「搜索」              ← [FRIEND_SEARCH_GO]
     * ⇒ 结果页（仍是普通好友行，行尾 [FRIEND_VISIT]）⇒ 沿用现有名称定位
     * ```
     *
     * ## 输入这一步走**安卓原生**（用户 2026-10-01 的判断，很可能正确）
     *
     * 用户的怀疑：点「请输入好友昵称」后弹出的是**安卓原生输入控件**（Unity 的 `TouchScreenKeyboard`
     * 正是用原生 `EditText` 实现）⇒ **能用原生方式直接写**：
     * `AccessibilityService.rootInActiveWindow` 递归找 `isEditable == true` 的节点 → `ACTION_FOCUS`
     * → **`ACTION_SET_TEXT`**（把中文一次写进去，**不需要**输入法按键、也不需要候选词）。
     * 这样连"弹键盘 / 点完成 / 收键盘"三个坑都可能一并绕开（若仍弹了键盘，再用 `GLOBAL_ACTION_BACK` 收）。
     * ⚠ 实际能不能成**必须真机验证**（见刀 2 的日志）：先打节点信息（className / 包名 / id / 文本），
     * 写不进去就退回"点输入框 → 输入法逐键 + 候选"（最脆，最后手段）。
     *
     * ## 为什么要有它
     *
     * 滑屏找好友**经常超过单步预算被中止**（用户报"滑屏时长超过 15 秒就会被中止"），列表长时还有
     * "跳屏漏人"的风险；而游戏自带搜索（真机截图：列表底部「🔍 搜好友」）**一次就把所有同名小号列出来**
     * ⇒ 又快又准。结果页**仍是普通好友行**（行尾有拜访图标）⇒ 现有的"名称列 OCR + 括号内全等 +
     * 同名取最上面 + 点行尾图标"整段逻辑原样复用，新增的只有"打开搜索 → 填字 → 点搜索"这三下。
     *
     * ## 口径（2026-10-01 用户拍板）
     *
     * 第 9 步改为：**先看当前屏；没有就搜索**（搜索不到 ⇒ **如实停下提示**）；
     * 搜索结果里**多个同名小号仍取最上面那一个**（与老口径一致）。
     *
     * ## 2026-10-01 二次拍板：**滑屏找人整条删除**
     *
     * 用户原话："**常用好友置顶，现在当前屏找、没找到再去点搜索框，这个搜索链已经够用了，
     * 可以去掉滑屏搜索的部分**" ⇒ 第 9 步只剩「当前屏命中 / 搜索链」两条路径，
     * 搜索链走不了（本组锚点没标定 / 写文字失败）时**如实停下**。
     */
    const val FRIEND_SEARCH_ENTRY = "friend_search_entry"

    /**
     * 好友列表 · **搜索框**（就是那行占位文字「请输入好友昵称」；点它才会聚焦、才弹输入法）。
     *
     * 随后由无障碍**原生写入**目标昵称（`ACTION_SET_TEXT`，见 [FRIEND_SEARCH_ENTRY] 的说明）。
     *
     * ⚠ 框**整条输入框**（左侧放大镜图标到右端清除按钮之间那一片），不要只框里面那行字。
     * ⚠ **输入完成后要先收起输入法**（用户 2026-10-01 特别提示）——输入法是全屏编辑模式，
     * 它盖着面板、搜索按钮根本点不到；优先用原生 `ACTION_SET_TEXT`（不弹键盘 ⇒ 免这一步），
     * 若弹了键盘则用 `GLOBAL_ACTION_BACK` 收（Back 只收键盘、不关面板），都不行再加"输入法完成键"那个框。
     */
    const val FRIEND_SEARCH_FIELD = "friend_search_field"

    /**
     * 好友列表 · 搜索框右侧的「**搜索**」按钮（黄色那个，旁边是蓝色的「取消」）。
     *
     * ⚠ 只框「搜索」，**千万别框到「取消」**（点错会把搜索面板关掉、白跑一轮）。
     */
    const val FRIEND_SEARCH_GO = "friend_search_go"

    /**
     * 好友列表 · **列表可见区域**（2026-09-21，第 9 步文字识别的输入区域）。
     *
     * ## 为什么需要它
     *
     * 第 9 步靠**文字识别**读"屏幕上写了哪些名字"（见 `recognition/NameReading`），
     * 而识别引擎需要一块**明确的输入区域**：整帧送进去会被引擎内部缩回小图、
     * 真机字高只有 ≈15px 的字反而读不出来；只送"列表那一条"才既够清晰又不超时。
     *
     * ## 为什么做成"锚点"而不是新引入一个"区域"概念
     *
     * 区域本来就要**框一次、按比例记录、随帧尺寸换算** —— 这三件事锚点窗口已经全都具备
     * （`SearchWindow` 是比例、`pixelBounds` 负责换算）。做成一条 `ANCHOR` 记录、
     * 给它一个用途名，就零成本复用了标定 / 产物 / 审计整条链路；运行时**不定位它**，
     * 只取它的窗口当 OCR 区域。
     *
     * ## 「不定位」是必须**声明**的，不是自动的（2026-09-22 真机踩过）
     *
     * 上面那句"运行时不定位它"曾经只是文档口径：产物把它记成 `ANCHOR`，而定位器会对该状态的
     * *全部*锚点做模板匹配 ⇒ 这块 **431×511** 的区域跟着一起扫
     * （原始窗口 941×1533 → 运行时收紧成 565×921，粗搜 ≈1.6e9 次乘加 = 单轮匹配成本的 97%），
     * **单轮 23 秒**、帧线程整段停摆，
     * 用户"停止后 3 秒重试"因此读到旧结论被误拦。现在由 [nonLocatableAnchors] 显式排除。
     *
     * 框法：把好友列表**可见的那一片**框住（含名字与行尾图标所在的整列），别框进顶部状态栏。
     */
    const val FRIEND_LIST_AREA = "friend_list_area"

    /**
     * 好友列表 · **好友名称列**（2026-09-22 用户口径）：在好友列表里框一次**名字所在的那一列**
     * （一条竖带），运行时 OCR 的输入区域 = 「列表区域」∩ 它。
     *
     * ## 为什么框名称列，而不是"把行首头像排除掉"
     *
     * 曾考虑过"框一行的头像格、再把头像左侧那条带从 OCR 输入里切掉"，**用户否决**：
     * 头像带**异形边框**（等级框 / 皮肤装饰），边界随好友变，框不准也说不清。
     * 名字列的边界是**两列文字之间的空隙**，看得见、对得准，且与好友无关、框一次全区服通用。
     *
     * ## 它解决什么
     *
     * 行首的头像与装饰会被 OCR 读成文字（真机实录 `价`/`新`/`愈` 这类前缀），
     * 噪声块还会让"按行去重 / 行带定位"跟着变脆。裁掉它们之后送进引擎的**只有名字文字**。
     *
     * **它只影响"送去读什么"，不影响"点哪里"** —— 行带的横向仍取「列表区域」的左右边界
     * （拜访图标在行尾，用名称列的右边界会让行带里没有图标，见 `NameLocating.visitRanges`）。
     *
     * ## 框法（2026-09-22 用户口径：**只需横向对准，纵向随便**）
     *
     * - **横向**：只框**名字文字**那一列 —— 左边界落在头像右边、右边界给到名字文字结束。
     * - **纵向**：**随便** —— 只框一行高也行、框歪也行。运行时纵向**一律取「列表区域」**，
     *   名称列的上下边界**被忽略**（见 `NameLocating.visitRanges`）。
     *
     * 早先要求"纵向与「列表区域」上下齐平"（因为两个框取的是**交集**，纵向也取），用户真机实操反馈
     * **很难对准**，而纵向本来也不该由用户管 ⇒ 2026-09-22 按用户口径把这个**要求**删掉（删的是要求、
     * 不是这条锚点 —— 保留它有个安全上的理由，见下）。
     *
     * ## 它还是"行带不串行"的保险（2026-09-22 评估，**未真机验证**）
     *
     * 它挡住的不只是行首头像，还有**行尾文字**（层级 / 「申请」/ 图标）⇒ 送进 OCR 的每一行
     * ≈"名字那一小条" ⇒ 行框高 ≈ 名字高（真机 ≈15px）⇒ `NameLocating.rowBandOf` 的行带高
     * = 3×字高 ≈45px < 行周期 ≈83px ⇒ **行带不会串到邻行**。
     * 若整条移除（OCR 区域直接 = 列表区域），行尾文字会一起进 OCR、行框被撑高，行带就可能跨到邻行
     * ⇒ 行内找图标时命中**隔壁那一行**的图标（= 点错人，红线 4）。保留锚点就不必冒这个险。
     */
    const val FRIEND_LIST_NAME_COLUMN = "friend_list_name_column"

    /**
     * 服务器列表 · **列表显示区域**（T4-9，2026-09-24 用户要求）：框一次服务器列表**可见的那一片**，
     * 作为服务器名识别的**输入范围** —— 服务器清单是长期数据、整页都是文字，把识别范围收进这一片
     * 既省时间也少误读（尤其是背景/装饰上的文字不该进 OCR）。
     *
     * 与 [FRIEND_LIST_AREA] 同族，因此同样吃两条硬口径（见 [nonLocatableAnchors]）：
     * ① **不进模板匹配**（它进 [nameAreaAnchors] ⇒ 自动被排除）；② **不做窗口收紧**（"用户框多大就用多大"）。
     */
    const val SERVER_LIST_AREA = "server_list_area"

    /**
     * 服务器列表 · **左侧列竖带**（T4-9，2026-09-24）：**只取横向** —— 框住"名字文字"的左右边界即可，
     * **纵向随便框（只框一行高也行）**，运行时纵向一律用 [SERVER_LIST_AREA]。与「好友名称列」完全同口径。
     */
    const val SERVER_LIST_COLUMN_LEFT = "server_list_column_left"

    /** 服务器列表 · **右侧列竖带**：同上，只取横向。 */
    const val SERVER_LIST_COLUMN_RIGHT = "server_list_column_right"

    /**
     * 第 5 步（选择区服）**审计用的名字**（T4-9，2026-09-24）——它**不是**标定项。
     *
     * 这一步点的是"识别给出的名字框本身"（[actions] 为空 ⇒ 没有锚点），而
     * [com.example.mastermechanic.action.ClickRequest] 要求说明"点的是什么"（NFR-05 审计链：
     * 每次点击都要能对上一条识别记录）⇒ 给这条路径一个固定的说明性名字，别让日志里出现空值
     * 或把区域锚点（它不是点击目标）写进去。
     */
    const val SERVER_NAME = "server_name"

    /** 设置页 · 退出登录（第 3 步的第二击）。 */
    const val SETTINGS_LOGOUT = "settings_logout"

    /**
     * 返回场景确认框 · 「**返回大厅**」（第 2 步的第二击）。
     *
     * 那个弹框有两个按钮：「返回稷下学院」与「返回大厅」。**只点「返回大厅」**——
     * 本流程下一步（退出登录）要求人在大厅；稷下学院不在 §2.1 的状态清单里，进去就无从验证。
     * 另一个按钮不标、也不点（要支持它就等于要先把稷下学院定义成一个状态）。
     */
    const val RETURN_LOBBY = "return_lobby"

    /** 退出登录确认框 · 确认（第 3 步的第三击）。 */
    const val LOGOUT_CONFIRM_OK = "logout_confirm_ok"

    /**
     * 一个**用途**：给人看的 [label] + 写进产物 `purpose=` 字段的 [name]（契约名）。
     *
     * 标定页只在 [purposesFor] 非空的界面上让用户选用途；单控件界面没有用途。
     */
    data class Purpose(val label: String, val name: String)

    /**
     * 这个界面上「可点击的控件」各有哪几种用途；空列表 = 没有用途可选
     * （如活动弹窗：它只有一个关闭控件，FR-01 按状态取即可，不需要用途）。
     *
     * 返回的 [Purpose.name] 与 [nameFor] / [actions] 用的是同一批常量，
     * 标定页选出来的用途必然就是跑号要找的那一个。
     */
    /**
     * ……
     *
     * **空列表不是"忘了标"，是刻意的**（2026-09-20 用户拍板，2026-09-29 扩到三屏）：**遮挡屏**
     * （活动弹窗 / 新手引导 / 新手大厅）都是空表 —— 它们各自只有一个关闭控件，自动关闭直接取
     * "当前状态的第一个锚点"，不需要用途来区分谁是谁；而且活动弹窗有多个相似模板、每次框选都要
     * **新增一条**，一旦给了用途就会变成"同用途覆盖"，两种样式会互相顶掉。
     * **不要**因为"锚点看起来都该有用途"就给它们补用途。
     */
    fun purposesFor(state: UiState): List<Purpose> = when (state) {
        UiState.HALL -> listOf(
            Purpose("设置入口", HALL_SETTINGS),
            Purpose("农场入口", HALL_FARM),
        )

        UiState.LAUNCH_PAGE -> listOf(
            Purpose("换区", LAUNCH_SWITCH),
            Purpose("开始游戏", LAUNCH_LOGIN),
        )

        // 农场：只有两个**要点的**控件（返回 / 好友入口）。
        // 2026-09-23 用户拍板**把农场归属判定整块删掉** ⇒ 原先三条身份用途
        // （主账号头像 / 好友头像 / 农场名称）连同判据一起撤下：自己的农场与好友的农场
        // 换号 / 拜访路径完全一样，不需要知道"这是谁的农场"。
        UiState.FARM -> listOf(
            Purpose("返回 / 退出", FARM_EXIT),
            Purpose("好友入口", FARM_FRIENDS),
        )

        // 好友的农场**不再单列**（2026-09-21 合并）：它与上面 FARM 是同一张用途表。
        // 旧产物里若还写着 `state=FRIEND_FARM|…` 也能照常读 —— 只是新建的标定都归到 FARM 一侧

        UiState.HALL_SETTINGS -> listOf(Purpose("退出登录", SETTINGS_LOGOUT))

        UiState.LOGOUT_CONFIRM -> listOf(Purpose("确认退出", LOGOUT_CONFIRM_OK))

        // 弹框里有两个按钮，但只有「返回大厅」是本流程要的（另一个进稷下学院，不在状态清单里）
        UiState.SCENE_RETURN_CONFIRM -> listOf(Purpose("返回大厅", RETURN_LOBBY))

        // 好友列表：第 9 步**先按名字找到那一行**，再点这一行最右侧的拜访图标（2026-09-21 用户补框）。
        // 用途表非空 ⇒ 标定页会让用户选用途（否则用途名写不进产物，代码按 `friend_visit` 永远找不到它）
        UiState.FRIEND_LIST -> listOf(
            Purpose("拜访（行尾图标）", FRIEND_VISIT),
            // 第 9 步文字识别的输入区域（框列表可见的那一片，一次性）
            Purpose("列表区域", FRIEND_LIST_AREA),
            // 第 9 步文字识别的**精化**：只把名字这一列送去读（行首头像/装饰不进 OCR）
            Purpose("好友名称列", FRIEND_LIST_NAME_COLUMN),
            // 第 9 步**搜索式查找**（2026-10-01）：三个都是"要点的控件"，不是识别区域
            Purpose("搜好友（底部入口）", FRIEND_SEARCH_ENTRY),
            Purpose("搜索框", FRIEND_SEARCH_FIELD),
            Purpose("搜索按钮", FRIEND_SEARCH_GO),
        )

        // 服务器列表（第 5 步「选服」）：三条**都是"只圈区域"的识别用锚点**（粗区域 + 左右两条列竖带）。
        // 这一屏**没有可点的锚点**：第 5 步点的是"识别给出的名字框本身"（位置由文字识别给出），
        // 审计里用 [SERVER_NAME] 那个说明性名字，它不是标定项（不进 [all]）。
        // 2026-09-24 起第 5 步已接线（`NameLocating.locateRealm` + `CaptureService` 的两列读）。
        UiState.SERVER_SELECT -> listOf(
            Purpose("列表区域", SERVER_LIST_AREA),
            // T4-9：两条列竖带（**只取横向**，纵向用「列表区域」）⇒ 面积降到约 1/2.5
            Purpose("左列竖带", SERVER_LIST_COLUMN_LEFT),
            Purpose("右列竖带", SERVER_LIST_COLUMN_RIGHT),
        )

        else -> emptyList()
    }

    // ------------------------------------------------------------------ 待标定清单

    /** 一个待标定的锚点：在哪个状态、叫什么名字、用来干什么（给标定的人看）。 */
    data class Need(val state: UiState, val name: String, val purpose: String)

    /**
     * **需要标定的锚点全集**（12 条）——这份清单同时也是"标定侧现在缺什么"的答案。
     *
     * 刻意手写而不是从 [nameFor] 反推：它是**验收对象**（真机核对的清单），
     * 从实现反推就等于自己证明自己。
     *
     * 第 12 条 `friend_visit`（2026-09-21）**不走步骤点击链**：第 9 步先按名字找到那一行、再点行尾图标，
     * 由 [nameLocatingAnchors] 给出用处；但它**一样要标定**，所以照样在本清单里（否则"还缺什么"会漏）。
     */
    val all: List<Need> = listOf(
        Need(UiState.FARM, FARM_EXIT, "第 2 步：农场里的返回 / 退出"),
        Need(UiState.FARM, FARM_FRIENDS, "第 8 步：农场里的好友入口"),
        Need(UiState.SCENE_RETURN_CONFIRM, RETURN_LOBBY, "第 2 步：返回确认框里的「返回大厅」"),
        Need(UiState.HALL, HALL_SETTINGS, "第 3 步：大厅里的设置入口"),
        Need(UiState.HALL, HALL_FARM, "第 7 步：大厅里的农场入口"),
        Need(UiState.HALL_SETTINGS, SETTINGS_LOGOUT, "第 3 步：设置页里的退出登录"),
        Need(UiState.LOGOUT_CONFIRM, LOGOUT_CONFIRM_OK, "第 3 步：确认框里的确认"),
        Need(UiState.LAUNCH_PAGE, LAUNCH_SWITCH, "第 4 步：启动页的「换区」"),
        Need(UiState.LAUNCH_PAGE, LAUNCH_LOGIN, "第 6 步：启动页的「开始游戏」"),
        Need(
            UiState.FRIEND_LIST,
            FRIEND_VISIT,
            "第 9 步：好友列表**每行最右侧**的「拜访」图标（先按名字找行，再点它；框一次，所有行复用）",
        ),
        Need(
            UiState.FRIEND_LIST,
            FRIEND_LIST_AREA,
            "第 9 步：**好友列表可见区域**（文字识别的输入范围；框一次即可，它本身不是点击目标）",
        ),
        Need(
            UiState.FRIEND_LIST,
            FRIEND_LIST_NAME_COLUMN,
            "第 9 步：**好友名称列**（只需**横向**对准：左边界落在头像右边、右边界给到名字文字结束；" +
                "**纵向随便框，只框一行高也行**——运行时纵向一律用「列表区域」；框一次，与好友无关）",
        ),
        Need(
            UiState.FRIEND_LIST,
            FRIEND_SEARCH_ENTRY,
            "第 9 步**搜索式查找**：列表**底部**的「搜好友」入口（连放大镜图标一起框，框到文字右侧一点即可）",
        ),
        Need(
            UiState.FRIEND_LIST,
            FRIEND_SEARCH_FIELD,
            "第 9 步搜索式查找：**搜索框整条**（左端放大镜图标 → 右端清除按钮；点它会聚焦并弹输入法）",
        ),
        Need(
            UiState.FRIEND_LIST,
            FRIEND_SEARCH_GO,
            "第 9 步搜索式查找：**搜索框右侧的「搜索」按钮**（黄色那个；⚠ 别框到旁边的「取消」）",
        ),
        Need(
            UiState.SERVER_SELECT,
            SERVER_LIST_AREA,
            "第 5 步（T4-9）：**服务器列表可见区域**（服务器名识别的输入范围；" +
                "框可见的那一片即可，不必框到屏幕外；框一次，与区服无关）",
        ),
        // 2026-09-23：三条**身份判据**锚点（主账号头像 / 好友头像 / 农场名称）随判据一起撤下 ——
        // 农场归属整块不再判定，清单也就不能再催用户去框它们。
    )

    /**
     * **按名称定位**那两步额外需要的辅助锚点（2026-09-21）：状态 → 锚点用途。
     *
     * 第 5 / 9 步**不走步骤点击链**（[actions] 为空）——它们先按名字找到那一行，再点这一行里的控件：
     * - 第 5 步（选区服）：**点名字本身**（识别给出的框中心），不需要锚点；
     * - 第 9 步（拜访好友）：要点的是**那一行最右侧的拜访图标**（名字与中间的「申请」都不点），
     *   所以由本表给出 `friend_visit`。
     *
     * 三处必须同时提到它（`PatrolAnchorsTest` 钉住）：本表（运行时查）、[all]（"还缺什么"的清单）、
     * [purposesFor]（标定页的用途候选）——少任何一处，用户就会遇到"框了锚点却选不到用途"。
     */
    val nameLocatingAnchors: Map<UiState, String> = mapOf(
        UiState.FRIEND_LIST to FRIEND_VISIT,
    )

    /**
     * **第 9 步「搜索式查找」要点的三个控件**（2026-10-01 用户拍板）：状态 → 锚点，**有序**。
     *
     * 顺序即执行顺序：① [FRIEND_SEARCH_ENTRY] 打开搜索面板 → ② [FRIEND_SEARCH_FIELD] 点输入框让它聚焦
     * （随后由无障碍 `ACTION_SET_TEXT` 写入目标昵称）→ ③ [FRIEND_SEARCH_GO] 点「搜索」出结果。
     * 结果页仍是普通好友行（行尾是 [FRIEND_VISIT]）⇒ 出结果之后**沿用现有那套**名称定位逻辑。
     *
     * ⚠ 三个都是**点击目标**（不是只圈区域），所以**不在** [nonLocatableAnchors] 里；
     * 标定页要能在 [purposesFor] 里选到它们的用途（已加），[all] 里也各有一条（"还缺什么"不能漏）。
     */
    val searchFlowAnchors: Map<UiState, List<String>> = mapOf(
        UiState.FRIEND_LIST to listOf(FRIEND_SEARCH_ENTRY, FRIEND_SEARCH_FIELD, FRIEND_SEARCH_GO),
    )

    /**
     * **文字识别的输入区域**锚点（2026-09-21）：状态 → 用途。
     *
     * 与 [nameLocatingAnchors] 是一对：那个回答"定位到目标之后**点哪里**"（行尾图标），
     * 这个回答"**把哪一片送去读文字**"。它不是点击目标，运行时也不会被定位 ——
     * 只取窗口当 OCR 的输入范围（见 [FRIEND_LIST_AREA] 的说明）。
     */
    val nameAreaAnchors: Map<UiState, String> = mapOf(
        UiState.FRIEND_LIST to FRIEND_LIST_AREA,
        // T4-9：服务器名识别的输入范围（框一次，第 5 步与「试读服务器名」共用）
        UiState.SERVER_SELECT to SERVER_LIST_AREA,
    )

    /**
     * **文字识别输入区域的精化**（2026-09-22 用户口径）：状态 → 用途。
     *
     * 与 [nameAreaAnchors] 是"粗 → 精"两层：那个回答"大概把哪一片送去读"（含行首头像 / 装饰），
     * 这个回答"其中**哪一列是文字**"。运行时 **输入区域 = 粗区域 ∩ 本列**（`NameLocating.visitRanges`）。
     *
     * **可选**：老产物里没有它 ⇒ 退回粗区域（不裁列），并在日志里明说"没框「好友名称列」"，
     * 而不是自动猜一条列出来 —— 缺数据就说缺数据，这是红线 3 的同一口径。
     */
    val nameColumnAnchors: Map<UiState, String> = mapOf(
        UiState.FRIEND_LIST to FRIEND_LIST_NAME_COLUMN,
    )

    /**
     * **服务器列表的两条列竖带**（T4-9，2026-09-24）：与 [nameColumnAnchors] 同口径（**只取横向**，
     * 纵向一律用 [SERVER_LIST_AREA]），差别只在**一个状态有两条列** ⇒ 形状从 `Map<UiState, String>`
     * 换成 `Map<UiState, List<String>>`。
     *
     * 为什么必须拆两条：服务器是两列格子、列右侧各有大片空白 —— 真机试读（2026-09-24）：
     * 整块区域 x = 440~2972，而文字只占左列 ≈477~1050、右列 ≈1750~2240，
     * 结果 25 行读了 **1109ms（超生产上限 1000ms）**。各框一条竖带后 `输入区域 = 列表区域 ∩ 该列`，
     * 面积降到约 1/2.5 ⇒ 预期 ≈500ms。
     *
     * **顺序即读取顺序**（先左后右）；两条**互不影响**，不做"左右行配对"的推断（红线 3 同口径）。
     */
    val serverListColumnAnchors: Map<UiState, List<String>> = mapOf(
        UiState.SERVER_SELECT to listOf(SERVER_LIST_COLUMN_LEFT, SERVER_LIST_COLUMN_RIGHT),
    )

    /**
     * **只圈区域、不当点击目标**的锚点用途全集（2026-09-22 真机修）：构造 `AnchorLocator` 时排除。
     *
     * 它与 [nameLocatingAnchors] / [all] / [purposesFor] 是同一批契约名，取 [nameAreaAnchors]
     * 与 [nameColumnAnchors] 的值即可。
     * 单独给个带"后果"的名字，是因为**漏了它不是少点一下就完了**：这些锚点往往框得很大
     * （「列表区域」真机 431×511），一旦跟着做模板匹配，帧线程单轮停摆 20 秒以上 ——
     * 实测与算量见 [FRIEND_LIST_AREA] 与 `AnchorLocator` 的构造参数说明。
     *
     * **两处调用点必须同时给它**（2026-09-22 起）：
     * ① `CalibrationData.toAnchorLocator(nonLocatable = …)` —— 别让它们跟着做模板匹配；
     * ② `CalibrationStore.loadForRuntime(exemptFromShrink = …)` —— 别让窗口被隐式收紧
     *    （"用户框多大就用多大"，理由见 [FRIEND_LIST_AREA] 与 `tightenedWindows`）。
     */
    val nonLocatableAnchors: Set<String> =
        (nameAreaAnchors.values + nameColumnAnchors.values + serverListColumnAnchors.values.flatten()).toSet()

    // ------------------------------------------------------------------ 点击链

    /**
     * 一次动作：**先在 [state] 确认画面**，点 [anchor]，做完应该看到 [expect]。
     *
     * 三件事缺一不可——少了 [state] 就是"用未确认的画面找点击点"，
     * 少了 [expect] 就是"点完不验证"（红线 5 / FR-04 硬性要求 1）。
     */
    data class Action(val state: UiState, val anchor: String, val expect: UiState)

    /**
     * 这一步要按顺序做的动作；**空列表 = 这一步不走锚点**，两种情形：
     * - 第 1 / 10 步：没有动作（起点确认 / 已到达终点）；
     * - 第 5 / 9 步：在列表里**按名称定位**（[NameLocator]），挑哪个不是位置问题。
     */
    fun actions(step: PatrolFlow.Step): List<Action> = when (step) {
        PatrolFlow.Step.CHECK_START, PatrolFlow.Step.DONE -> emptyList()

        // 退出农场：点左上角的返回 → 弹出「请选择需要返回的场景」→ 选「返回大厅」（2026-09-19 真机取证）。
        // 前两击按当前所在状态二选一；第三击两个农场的弹框共用（同一屏）
        // 2026-09-21 合并：自己的农场与好友的农场共用一个返回入口（样式一致，用户确认）
        PatrolFlow.Step.LEAVE_FARM -> listOf(
            Action(UiState.FARM, FARM_EXIT, UiState.SCENE_RETURN_CONFIRM),
            Action(UiState.SCENE_RETURN_CONFIRM, RETURN_LOBBY, UiState.HALL),
        )

        // 退出登录：三次点击、三次确认（2026-09-19 用户确认；中间两屏见 §2.1）
        PatrolFlow.Step.LOGOUT -> listOf(
            Action(UiState.HALL, HALL_SETTINGS, UiState.HALL_SETTINGS),
            Action(UiState.HALL_SETTINGS, SETTINGS_LOGOUT, UiState.LOGOUT_CONFIRM),
            Action(UiState.LOGOUT_CONFIRM, LOGOUT_CONFIRM_OK, UiState.LAUNCH_PAGE),
        )

        PatrolFlow.Step.SWITCH_SERVER -> listOf(
            Action(UiState.LAUNCH_PAGE, LAUNCH_SWITCH, UiState.SERVER_SELECT),
        )

        PatrolFlow.Step.PICK_SERVER -> emptyList()

        PatrolFlow.Step.LOGIN -> listOf(
            Action(UiState.LAUNCH_PAGE, LAUNCH_LOGIN, UiState.HALL),
        )

        PatrolFlow.Step.ENTER_FARM -> listOf(
            Action(UiState.HALL, HALL_FARM, UiState.FARM),
        )

        // 同上：两个农场的好友入口也共用一个锚点
        PatrolFlow.Step.OPEN_FRIENDS -> listOf(
            Action(UiState.FARM, FARM_FRIENDS, UiState.FRIEND_LIST),
        )

        PatrolFlow.Step.VISIT_FRIEND -> emptyList()
    }

    /**
     * 当前画面下该做的那一个动作；null = 现在这个画面不是这一步要动手的地方
     * （要么还没走到该确认的画面，要么这一步不走锚点）。
     */
    fun actionFor(step: PatrolFlow.Step, state: UiState): Action? =
        actions(step).firstOrNull { it.state == state }

    /** 这一步要点的全部锚点名（真机核对与标定清单用）。 */
    fun anchorsOf(step: PatrolFlow.Step): List<String> = actions(step).map { it.anchor }

    /**
     * [step] 在已确认的 [state] 下该点哪个锚点；null = 这一步在这个画面上不动手。
     * 等价于 [actionFor] 的锚点部分，保留它是因为"查名字"是最常用的问法。
     */
    fun nameFor(step: PatrolFlow.Step, state: UiState): String? = actionFor(step, state)?.anchor

    /** 这一步是不是"按名称定位"（第 5 / 9 步）——调用方据此走 [NameLocator] 而不是锚点。 */
    fun isNameLocated(step: PatrolFlow.Step): Boolean =
        step == PatrolFlow.Step.PICK_SERVER || step == PatrolFlow.Step.VISIT_FRIEND

}
