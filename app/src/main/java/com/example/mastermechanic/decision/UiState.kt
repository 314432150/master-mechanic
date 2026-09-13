package com.example.mastermechanic.decision

/**
 * 界面状态（T1-4，§2.1；术语取值见 glossary，不得增减或改名）。
 *
 * 声明顺序 = 多状态同时命中时的候选优先级：活动弹窗为 FR-01 规定的最高优先级（弹窗遮挡画面、
 * 阻断识别），其余常规界面按 §2.1 表格顺序排列。[UNKNOWN] 不参与候选
 * （没有任何标志对应「未知」）。
 *
 * **FR-02（新手引导 / 新手大厅）已移除**（2026-09-13 用户口径，requirements §3.1）：账号打完一局后
 * 两界面不再出现、本版本不处理，故枚举中不含 `TUTORIAL_HALL` / `TUTORIAL_GUIDE`
 * （恢复条件见需求 §3.1）。标定页的归属状态列表直接取本枚举候选，无需另行过滤。
 *
 * [defaultSignalName] 为标定界面归属状态对应的信号名：选中该状态即自动采用（无需人工输入）；
 * 它不参与识别，运行时的信号—状态对应仍以标定产物为准。
 */
enum class UiState(val label: String, val defaultSignalName: String) {

    /** 活动弹窗（§2.1）：弹窗关闭控件标志；FR-01 最高优先级。 */
    ACTIVITY_POPUP("活动弹窗", "popup_close"),

    /** 启动页（§2.1）：「开始游戏」「换区」标志。 */
    LAUNCH_PAGE("启动页", "launch_start"),

    /** 服务器列表（§2.1）：「选择服务器」标志。 */
    SERVER_SELECT("服务器列表", "server_select"),

    /** 大厅（§2.1）：对战 / 排位 / 农场入口，任一命中即为大厅。 */
    HALL("大厅", "hall"),

    /** 农场（§2.1）：农场标志。 */
    FARM("农场", "farm"),

    /** 好友列表（§2.1）：好友列表标志。 */
    FRIEND_LIST("好友列表", "friend_list"),

    /** 好友的农场（§2.1）：拜访后进入的农场。 */
    FRIEND_FARM("好友的农场", "friend_farm"),

    /** 未知（§2.2）：连续 3 次未命中任何标志；未知不操作，也没有标志与默认名。 */
    UNKNOWN("未知", ""),
    ;

    /** 是否可作为「当前状态」的候选（未知没有标志，不参与候选）。 */
    val isCandidate: Boolean get() = this != UNKNOWN
}
