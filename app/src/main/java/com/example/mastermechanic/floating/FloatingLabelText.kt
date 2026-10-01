package com.example.mastermechanic.floating

import com.example.mastermechanic.patrol.PatrolFlow

/**
 * **顶部状态标签显示什么**（2026-09-28 加；纯逻辑、可 JVM 重跑）。
 *
 * 用户口径："用来显示**当前正在进行的操作**，内容要简短，以防挡住过多的游戏UI"；
 * 装机后第一次真机又补了一句："**有些内容太长了，显示不完，而且太长了看不过来**"。
 *
 * ## ⚠ 为什么不能直接显示 `PatrolSession.lastNote`
 *
 * 第一版就是那么干的，真机上标签被撑到 200dp 上限、末尾截断。原因是那句 note 是**给日志和菜单**写的
 * 完整句子，例如：
 * ```
 * 第 3 步：刚点过，等画面切换，继续等（已等 5 秒）        ← 26 字，标签放不下
 * 第 5 步：点区服「发条权杖」（区号「392」/ 区名「发条权杖」在 (760, 338)）   ← 更长
 * ```
 * ⇒ 标签**自己造短句**（只取"第几步 + 这一步在干什么"），完整句子仍留在菜单进度块与日志里
 * （那是它们该待的地方）。
 *
 * ## 显示什么（优先级）
 *
 * | 优先级 | 显示 | 例子 |
 * |---|---|---|
 * | ①（一次性动作） | 用户刚点的那一下（面板这时是收起的） | `正在试读` / `正在读服务器` |
 * | ②（中止 / 暂停） | **需要用户决策**的状态 ⇒ 压过"我正在干活" | `已中止` / `已暂停` |
 * | ③（画面久未更新） | 60 秒没有新帧（`frameStale`）**且有活儿在等画面**（2026-09-30 L2）；停更 ≥120 秒改说**处置** | `画面久未更新` / `画面久未更新·请重授权` |
 * | ④（关弹窗） | FR-01 关弹窗三态（`PopupCloseSignal`）：在关 / **受阻** / **已停手** | `正在关弹窗` / `关弹窗受阻·没认出` / `关弹窗已停手` |
 * | ⑤（跑号） | **只说在做什么**（不带步号） | `退出登录` / `选择区服` |
 * | ⑥（已完成） | 结果话，让位于"正在进行的事" | `已完成` |
 * | ⑦ | 实在什么都没有 | `待命` |
 *
 * ⚠ 还有一句**不在表里** —— 它比这张表更靠前，由调用方直接返回、不进 [of]：`采集已停止（点手柄重新授权）`
 * （会话没了 ⇒ "正在关弹窗 / 第 N 步"全都不成立，见 `FloatingWindow.labelContent`）。
 *
 * ⚠ **③ 只在"有活儿在等画面"时说话**（2026-09-30，L2）：待命 / 已完成 / 已中止 / 已暂停都不等新画面，
 * 而"屏幕静止 ⇒ 平台不产帧"本来就是常态（`FrameFreshness` 里记着当日真机实测：目标在前台、画面静止
 * ⇒ 60 秒零新帧）⇒ 那时挂「画面久未更新」只是噪声，还会把"已完成 / 已暂停"这类**要用户看的结果话**挤掉。
 *
 * 取舍的理由都写在 [of] 里；**每一条都有单测**（`FloatingLabelTextTest`）。
 *
 * ⚠ **次序里有一个真机踩过的坑**（2026-09-29 用户报障"关闭弹窗依旧显示待命"）：登录后被自动关掉的
 * 那一串弹窗，**这时候根本没有跑号流程**（`patrol == null`）。所以"关弹窗"必须排在"没流程 ⇒ 待命"
 * **之前** —— 早先写反了，那一条就永远走不到（单测也漏了这个组合，见 `closingPopupShowsWithoutAPatrol`）。
 *
 * ## 长度上限
 *
 * [MAX_CHARS] = 12 个字符：11sp 下约 132dp（面板宽 792dp 的 1/6）。
 *
 * ⚠ **别把上限卡到"刚好等于最长那句"**（2026-09-29 真机报障："宽度有点窄了，`关闭弹窗` 都无法显示完全"）：
 * 一度收到 8 字，而 `关闭弹窗 第2次` 正好 8 字、`关闭弹窗 第12次`（点满 12 次上限时）是 9 字
 * ⇒ 后者被 [shorten] 截成 `关闭弹窗 第1…`。上限的职责是**兜底防长**，不是"裁掉正常文案"；
 * 真机日志能印证（标签宽度 168/256/344/381/438 px，密度 4 ⇒ 每字 ≈44px + 内边距 80px，438px 已顶格）。
 * ⚠ KDoc 里**不要用"斜杠 + 星号"来强调**（例如把数字夹在两个星号里想加粗）：Kotlin 的块注释**可嵌套**，
 * 一旦出现块注释的起始符，就会开一个子注释；后面若没有配对的收尾符，整个文件报 `Unclosed comment`（这条踩过）。
 * 现在那句也顺手缩短成 `关弹窗 第N次`，最长只有 8 字，离上限留有余量。
 *
 * ⚠ **结尾不要用 `…`**（2026-09-29 用户报障："`关弹窗中`这句还是显示了省略号,还是被截断了"）：
 * 小胶囊里结尾的省略号会被读成"**文字被截断了**"，而不是"正在进行" ⇒ 一律改用「正在 + 动作」。
 * [shorten] 是**兜底**：将来谁再加一句长文案（比如新的来源），也不会再把标签撑到截断。
 * 步号那句用**字面量**而不是 `strings.xml`：与 `PatrolStatus` 同一套做法 ——
 * 跑号措辞只在一个概念里演化，拆到两处就会各自长出不一样的说法。
 */
object FloatingLabelText {

    /** 标签最多显示几个字符（超了由 [shorten] 截断并加省略号）。 */
    const val MAX_CHARS = 12

    /**
     * **停更到什么程度**（2026-09-30 加）：调用方从 `FrameFreshness` 换算后递进来（本对象不碰安卓资源）。
     *
     * 两段的取舍（用户口径："停更持续时，提示里给'能做的事'"）：
     * - [RECENT]（刚过 60 秒）：**只陈述事实** —— 那一刻"屏幕本来静止"与"镜像失效"还分不出来，
     *   叫人去重新授权就是**猜**；
     * - [PERSISTENT]（≥ 120 秒）：采集侧的自动自救已经试过两轮
     *   （`CaptureService.checkSurfaceRetry`）⇒ 这时才给**处置**。
     */
    enum class FrameStale {
        NONE,
        RECENT,
        PERSISTENT,

        /**
         * **这次授权根本没被投喂**（2026-10-01 加，方案 2）：会话建立后 [FrameFreshness.FIRST_FEED_DEADLINE_MS]
         * 内**一帧都没收到**（`onImageAvailable` 回调 0 次）。
         *
         * 为什么单列一档，而不是并进 [RECENT]／[PERSISTENT]：那两档的前提是"**曾经**有过帧之后又停了"，
         * 而这一档是"**从头到尾零帧**"—— 真机卡住的那次正是它（停更看门狗因为没有基线，一句都不说，
         * 用户懵等 60 秒、跑号还跑了一半 ⇒ 白跑一轮）。
         * 处置也不同：[RECENT] 只陈述事实（"屏幕静止"与"镜像坏了"还分不出来），这一档则**直接给处置**
         * （换进程重开），因为"从没投喂"只有一种解释。
         */
        NEVER,
    }

    /** 固定那几句词（由调用方从 `strings.xml` 取：本对象不碰安卓资源）。 */
    data class Words(
        /** 什么都没在跑。 */
        val idle: String,
        /** 跑号已中止（**不带原因** —— 原因在菜单原因条与日志里）。 */
        val failed: String,
        /** 跑号已暂停。 */
        val paused: String,
        /** 跑号已完成。 */
        val finished: String,
        /**
         * **画面久未更新**（60 秒没有新帧 ⇒ 程序已停止一切点击，2026-09-30 L2 加）。
         *
         * 默认空串只为兼容既有调用点：**没传它就等于这一档不存在**（[of] 会走别的档）——
         * 宁可说别的，也不要让标签变成一个空胶囊（用户会以为界面坏了）。
         */
        val frameStalled: String = "",
        /**
         * 停更**持续**（[FrameStale.PERSISTENT]）时要说的**处置**（2026-09-30 加）。
         *
         * 与 [frameStalled] 分两句：60 秒只说事实（"我们看不见了"），**两分钟**后才说处置 —— 那时
         * 自动"换镜像表面"已经试过两轮（`CaptureService.checkSurfaceRetry`）⇒ 该用户动手了。
         * 空串 ⇒ 退化成 [frameStalled]（宁可只说事实，也不要让标签变空）。
         */
        val frameStalledAction: String = "",
        /**
         * **这次授权没被投喂**（[FrameStale.NEVER]）要说的话（2026-10-01 加，方案 2）。
         *
         * 空串 ⇒ 这一档不存在（[of] 会走别的档）：宁可说别的，也不要让标签变成空胶囊。
         */
        val frameStarved: String = "",
    )

    /**
     * 这一轮该显示的那句话（**恒定非空**，且长度 ≤ [MAX_CHARS]）。
     *
     * @param readingText 正在跑的"读整屏"动作（null / 空 = 没有）：它跑起来面板是收起的
     *   ⇒ 标签是唯一还能说"我在读"的地方，所以排第一
     * @param closingPopupText 正在自动关弹窗（null / 空 = 没有）；**排在"中止 / 暂停"之后**：
     *   中止 / 暂停是"需要你决定"的状态，比"我正在关弹窗"重要
     * @param patrol 当前跑号流程（null = 没在跑）
     * @param words 固定那几句词
     * @param frameStale 停更到什么程度（调用方从 `FrameFreshness` 换算；默认 [FrameStale.NONE] =
     *   老调用点行为不变）。**它不代表"镜像坏了"**，只代表"我们现在看不见变化"：
     *   采集侧同时已停止一切点击，所以这一刻"正在关弹窗 / 第 N 步"都是**旧画面上的结论** ⇒ 说这一句更有用。
     */
    fun of(
        readingText: String?,
        closingPopupText: String?,
        patrol: PatrolFlow.State?,
        words: Words,
        frameStale: FrameStale = FrameStale.NONE,
    ): String {
        val text = when {
            !readingText.isNullOrEmpty() -> readingText
            patrol?.outcome == PatrolFlow.Outcome.FAILED -> words.failed
            patrol?.paused == true -> words.paused
            // **这次授权没被投喂**（2026-10-01，方案 2）：与"久未更新"相反，它**不要求**"有活儿在等画面"——
            // 恰好在用户刚授完权、还没开始跑的时候就要说（否则他白跑一轮：真机就是这么卡在退出登录页的）。
            frameStale == FrameStale.NEVER && words.frameStarved.isNotEmpty() -> words.frameStarved
            // **画面久未更新**（2026-09-30，L2）：见 [staleTakesTheLabel]（三个条件缺一不可）
            staleTakesTheLabel(frameStale, words.frameStalled, closingPopupText, patrol) -> staleLabel(frameStale, words)
            // ⚠ **必须排在 `patrol == null` 之前**（2026-09-29 真机报障"关闭弹窗依旧显示待命"）：
            // 登录后被自动关掉的那些弹窗，**这时候根本没有跑号流程**（patrol == null）——
            // 早先写成"先判 patrol == null 就返回待命"，这一条就永远走不到。
            !closingPopupText.isNullOrEmpty() -> closingPopupText
            patrol == null -> words.idle
            // ⚠ **第 10 步（`Step.DONE`，步骤名就叫「完成」）直接说「已完成」**（2026-10-01 用户报障：
            // "先显示了一个「完成」，接着又显示一个「已完成」，重复了"）。那一步从"跑起来"到
            // `outcome` 变 `FINISHED` 之间会先闪一下步骤名，两步都说同一件事 ⇒ 只留结果话那句。
            patrol.outcome == PatrolFlow.Outcome.FINISHED ||
                patrol.step == PatrolFlow.Step.DONE -> words.finished
            else -> runningText(patrol)
        }
        return shorten(text)
    }

    /**
     * 这一拍标签是不是"**一句话都没有**"（= [of] 会走到 `words.idle` 那一档）
     * ⇒ 界面据此**整条摘掉**标签窗（2026-09-30 用户口径：待命期只藏标签、手柄留着）。
     *
     * ## 为什么单开一个函数，而不是"比较文案是不是「待命」"
     *
     * 比文案是**会错位**的：文案一改（措辞调整 / 换语言），判据就静默失效。这里与 [of] 共用
     * **同一套输入**、逐条对应优先级表，两处的"待命"含义永远一致（`FloatingLabelTextTest` 有
     * 一条用例专门钉住"`of` 给出 idle ⇔ `isIdle` 为真"）。
     *
     * ⚠ 判据成立的条件只有一条：**读数动作、关弹窗、跑号流程三件都没有**。反过来说，
     * 「已中止 / 已暂停 / 已完成 / 关弹窗受阻 / 采集已停止」**都不是**待命 ⇒ 它们照常显示
     * （尤其"已完成"：它正是用户要看到的结果话，绝不能跟着待命一起被藏掉）。
     *
     * ⚠ 「**画面久未更新**」这一档**不影响这里**（所以本方法与它无关）：它只在"有活儿在等画面"时出现
     * （见 [staleTakesTheLabel]），而那时三件里至少有一件在（在关弹窗 或 跑号进行中）⇒ 本来就非待命。
     * 反过来，"待命 + 停更"是**正常**状态（屏幕静止 ⇒ 平台不产帧）⇒ 那时就该是待命、标签整条摘掉。
     */
    fun isIdle(
        readingText: String?,
        closingPopupText: String?,
        patrol: PatrolFlow.State?,
        /**
         * **这次授权没被投喂**（2026-10-01，方案 2）：这一档说的是"程序现在**什么都做不了**"
         * ⇒ 必须说出来（绝不能跟着待命一起被藏掉 —— 那正是用户懵等的场景：没跑号、标签整条摘掉）。
         */
        frameStarved: Boolean = false,
    ): Boolean = !frameStarved &&
        readingText.isNullOrEmpty() &&
        closingPopupText.isNullOrEmpty() &&
        patrol == null

    /**
     * 「画面久未更新」这一档要不要占标签（2026-09-30，L2；[of] 用的唯一判据）。
     *
     * 三个条件同时成立才占：**真的停更了**（[frameStale]）∧ **有文案**（[frameStalledText] 非空）∧
     * **有活儿在等画面**（[waitingForFreshFrames]）。缺任何一个都走别的档。
     *
     * 为什么要"有活儿在等"这一条：**屏幕静止 ⇒ 平台不产帧**本来就是常态（静止兜底重放正是为此存在）——
     * 2026-09-30 真机实测就是"目标在前台（农场）+ 画面静止 + 60 秒零新帧"。待命 / 已完成 / 已中止 /
     * 已暂停时没有东西在等新画面 ⇒ 提示只会变成噪声，还会把"已完成 / 已暂停"这些**要用户看**的结果话挤掉。
     */
    private fun staleTakesTheLabel(
        frameStale: FrameStale,
        frameStalledText: String,
        closingPopupText: String?,
        patrol: PatrolFlow.State?,
    ): Boolean = frameStale != FrameStale.NONE &&
        frameStalledText.isNotEmpty() &&
        waitingForFreshFrames(closingPopupText, patrol)

    /**
     * 这一档**说什么**：停更很久（[FrameStale.PERSISTENT]）且有处置文案 ⇒ 说处置，否则只陈述事实。
     *
     * 为什么分两段（用户 2026-09-30 口径："停更持续时，提示里给'能做的事'"）：60 秒时"屏幕本来静止"与
     * "镜像失效"还分不出来（叫人重新授权就是猜）；而到两分钟时自动自救已试过两轮 ⇒ 该给处置了。
     */
    private fun staleLabel(stale: FrameStale, words: Words): String =
        if (stale == FrameStale.PERSISTENT && words.frameStalledAction.isNotEmpty()) {
            words.frameStalledAction
        } else {
            words.frameStalled
        }

    /**
     * **现在有没有活儿在等"新画面"**：正在关弹窗 ∨ 跑号正跑着（[PatrolFlow.Outcome.RUNNING]、也没被暂停）。
     *
     * ⚠ [PatrolFlow.State.outcome] **不是可空的**（`RUNNING` / `FINISHED` / `FAILED`）⇒ "还在跑"必须写
     * `== RUNNING`，不能写 `== null`（写成 null 会被编译器认成恒假 —— 单测当场就会炸）。
     * ⚠ 读整屏（[of] 的 `readingText`）不在判据里：它在第一档就已经返回了 ⇒ 走到这里时它必为空，
     * 所以不必再判一次（判了也不会更准）。
     */
    private fun waitingForFreshFrames(
        closingPopupText: String?,
        patrol: PatrolFlow.State?,
    ): Boolean = !closingPopupText.isNullOrEmpty() ||
        (patrol != null && patrol.outcome == PatrolFlow.Outcome.RUNNING && !patrol.paused)

    /**
     * 跑号正在做的那一步：**只说做什么**（`退出登录` / `选择区服` / `打开好友列表`）。
     *
     * 用户口径（2026-09-29）："**去除「第x步」、步骤序号这种不重要的信息，关键是目前在做什么**"。
     * 序号本来在标签上就没什么用（一眼看的是"在干什么"），而且"第 3 步 · 退出登录"这种写法
     * 在 11sp 下占 130dp —— 去掉之后只剩 4~6 个字（约 60dp），标签缩掉一半。
     * "第几步 / 一共几步"展开菜单就能看到（`PatrolStatus.progressLine`）。
     */
    fun runningText(patrol: PatrolFlow.State): String = patrol.step.label

    /** 超长截断（兜底）：[max] 个字符之内，多出来的用省略号收尾。 */
    fun shorten(text: String, max: Int = MAX_CHARS): String =
        if (text.length <= max) text else text.take(max - 1) + "…"
}
