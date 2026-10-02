package com.example.mastermechanic.action

import com.example.mastermechanic.decision.AnchorHit

/**
 * FR-01 活动弹窗自动关闭的决策本体（T2-4，纯逻辑，可 JVM 重跑）。
 *
 * 职责边界（与邻层的关系）：
 * - **不产生点击**：只给出 [ClickRequest]，下发一律经 `ClickDispatch.submit`（红线 6 的唯一实现点）；
 * - **不定位锚点**：锚点由 `AnchorLocator` 按当前状态定位后作为输入传入（本类只决定"用哪个 + 要不要用"）；
 * - **不做识别**：弹窗是否命中 / 是否确认由识别循环的 `RoundResult` 如实传入。
 *
 * ## 谁在用（2026-09-29）：**不只活动弹窗**
 *
 * 三屏共用本闭环，即 [UiState.guardedOverlays]：**FR-01 活动弹窗** + **FR-02 新手引导 / 新手大厅**
 * （新手号专有；登录后出现在活动弹窗之前，由用户口径恢复）。三者在"盖住画面、有一个点一下就消失的
 * 控件、看不到下层界面"这几点上完全同型，所以**决策本体一行不用改**：区别只是
 * [PopupRoundInput.source]（审计来源 FR-01 / FR-02）与 `decisionId` 前缀。
 *
 * ⚠ 因此本类里所有"弹窗"措辞都应按"**遮挡屏**"来读（阈值、闸门、术语全部照旧，不改语义）。
 *
 * ## 口径（T2-8，2026-09-13 真机实测后修订）
 *
 * **不做"单次点击是否奏效"的判定，只要弹窗还在就继续点**，唯一停止条件是「同一段弹窗内点击次数达到上限」。
 *
 * 为什么撤销原判据（原为「连续 [maxAttempts] 次点击后仍命中 → 判为误匹配，放弃」）：
 * 真机日志证明**「点击后仍命中」无法区分两件事**——
 * ① 弹窗没被关掉；② 前一个弹窗**已被关掉、紧接着冒出了下一个弹窗**（用户实测："挨个点掉，点掉一个马上又出现一个"）。
 * 二者在日志上完全同形，于是原判据把"正常地关掉一个又一个弹窗"误判成"反复失败"，
 * 3 次后放弃 → 剩下的弹窗再没人管（2026-09-13 实点现场即如此）。既然判不出来，就不该由它决定动作。
 *
 * **为什么仍保留一个上限**：上限防的不是"点不掉"，而是**"锚点匹配到了不是关闭控件的位置"**——
 * 那种情况下程序会一直往错位置点，而游戏里点到别处可能是不可逆操作（需求 §3.1「宁可漏关，不可错点」）。
 * 上限一到即停手；弹窗真的消失后自动复位，下一段弹窗重新计数。
 *
 * ## 补点前必须"看到**这一枪之后拍下**的画面"（2026-09-24 真机事故后加）
 *
 * 真机实录（`mm-log-20260924.txt` 18:51）：换号流程正常结束那一刻，`activity_popup_e1/e2/e3`
 * 三个标志在大厅上**同时满分命中**（0.9999），状态被判成"活动弹窗"⇒ FR-01 点了一枪；
 * 紧接着那一轮"仍命中弹窗"（0.9999，同样的满分）⇒ 又补了一枪。之后画面变成**商城**
 * （16.9 起弹窗标志掉到 0.06、大厅标志掉到 0.525；用户报"多点了一次，进到了商城"）。
 *
 * ⚠️ **判据不能写成"有没有新帧到来"**（2026-09-24 用户追问后修正）：**关掉弹窗当然算画面变化**，
 * 帧流也一直在来（实录 ≈10fps）；问题在于**"本轮判定用的是哪一张画面"**——
 * 帧到达 ≠ 本轮吃到它：兜底轮重放的是缓存副本（最多 300ms 前抓的），而一轮 500ms、单帧处理 P95 643ms
 * ⇒ 这一轮的结论完全可能落在**点击之前**看到的那张画面上。所以判据只能用
 * **本轮那张画面的拍摄时刻**（`PopupRoundInput.frameAtMs`）与上一枪比。
 *
 * ## ⚠️ 2026-09-28 再修：只"比上一枪新"**不够**，要"晚够一个观察窗"（[reClickGuardMs]）
 *
 * 用户又报同一条（"活动弹窗关闭后，偶尔触发了不必要的点击，进入了商城页"）。真机实录
 * （`mm-log-20260928.txt` 21:41，详见 `docs/progress.md` 第 219 条）：
 * ```
 * 21:41:00.755  第 1 枪下发（判定 fr01-1）→ 手势结束 00.807
 * 21:41:01.860  本轮画面拍于 **01.557**（比 00.755 新 800ms，弹窗仍 0.9999 命中）⇒ 补第 2 枪
 * 21:41:02.334  再补第 3 枪
 * 21:41:03.472  画面里"一屏都没命中"（弹窗 0.21、大厅 0.53）＝**商城页** ⇒ 用户进了商城
 * ```
 * 上一版判据（"比上一枪新"）**只筛掉"用点击之前的画面去补枪"**，挡不住"用刚点完 0.5~0.8 秒的画面去补枪"：
 * 那一小段里游戏还没把这一枪的结果画出来（画面是**旧内容的新帧**），而"弹窗仍在"的结论照样成立
 * ⇒ 又是一次盲点。点击本身是**不可逆**的（需求 §3.1「宁可漏关，不可错点」），
 * 所以口径收紧成：**本轮这张画面必须比上一枪晚至少 [reClickGuardMs] 才允许补点**（默认 1.0s ——
 * 21:41 那次危险的一枪只晚 0.17s，必被拦下；1.0s 也仍高于实测的渲染延迟 ≈0.76s）。
 *
 * 它不改变"不做单次成败判定"那条口径：**观察窗之后**的画面里弹窗仍命中，照旧继续点
 * （没关掉 / 换了新弹窗都能覆盖）；只有"我们还没看到这一枪真正之后的样子"时才停手。
 *
 * ## ⚠️ 2026-09-28 再加一道：**"跟上一枪一模一样的那张画面"上不重复扣扳机**（[identicalFramePercent]）
 *
 * 同一天把"画面到底变了多少"改成**整帧**比较之后（见 `capture/FrameSignature`；之前误用窗口化灰度，
 * 量出来全是百分之几的噪声），第一次拿到可信的数：
 * ```
 * 23:22:37.708  第 1 枪之后 0.57s ⇒ 整帧变化 41.7%（冒出了下一个弹窗）
 * 23:22:39.268  第 2 枪之后 0.48s ⇒ 整帧变化  0.1%（**逐点几乎相同**）
 * 23:22:40.104  第 3 枪时刻      ⇒ 整帧变化 43.5%（下一个弹窗到了）
 * 23:22:41.385  弹窗段收尾        ⇒ 整帧变化 70.6%（弹窗没了、露出下层界面）
 * ```
 * "变了"与"没变"分得极干净（0.1% ↔ 41.7%），于是加一道**只收紧、不放松**的闸：
 * **整帧变化 < [identicalFramePercent] ⇒ 这一枪要打的那张画面跟上一枪那张是同一张** ⇒ 不补点。
 *
 * 它不替代观察窗（两道各自独立、都在收紧方向），也不动"不做单次成败判定"那条口径；
 * 目的只有一个：**别对着同一张静止画面重复扣扳机** —— 2026-09-24 与 2026-09-28 两次"点进商城"，
 * 危险的那一枪都打在"游戏还没把上一枪的结果画出来"的时候（那种画面**逐点几乎没变**）。
 *
 * ⚠ 传 `null`（不可比：没基线 / 几何变了）⇒ 这道闸**不参与**：不把"不知道"当成"没变"。
 *
 * ⚠ **2026-09-29 补一道时间界**（[identicalFrameHoldMs]，2.5s）：这道闸只在"刚点完那一小段"里成立。
 * 真机实录（00:05）第 1 枪之后 6 秒画面只变了 **0.0%~1.7%** ⇒ 一路被它拦到第 2 枪，
 * 用户感受就是"**第一个弹窗很久才关掉**"。过了容忍时长画面还是一模一样，说明游戏早就定住了
 * （那张画面就是"现在"，弹窗也真的还在）⇒ 该补枪。
 *
 * ### 同一晚第二道：**"整幅换掉了" ⇒ 本段停手**（[sceneReplacedPercent]）
 *
 * 两轮序列放在一起看，暴露出一个**紧邻的失误**：两次"控制器其实已经想点、只被观察窗勉强拦下"的那一枪，
 * 都正好发生在"**弹窗刚消失、露出下层界面**"那张画面上 —— 23:22:41.385 整帧变化 **70.6%**，只差 **10ms**；
 * 23:30:40.066 变化 **69.4%**，只差 **210ms**。那正是 2026-09-24"进商城"那一枪的同型现场
 * （弹窗消失时 `activity_popup_e1` 在大厅上满分命中）⇒ 说明观察窗的余量只剩 0.1~0.2s。
 *
 * 而"换了一个新弹窗"与"弹窗没了"分得很开（**合法换弹窗最大 43.5% ↔ 露出下层最小 53.3%**）⇒ 再加一道只收紧的闸：
 * 整帧变化 ≥ [sceneReplacedPercent]（**50%**）判为"下层界面露出来了" ⇒ **本段直接停手**（弹窗消失后自动复位）。
 * ⚠ 50 是 2026-09-29 从 60 **收下来**的：那一段"露出下层"只有 **53.3%~53.4%**，60 根本没拦住，
 * 最后挡住那一枪的只剩"锚点没命中"（日志 `未命中关闭控件锚点`）—— 而那件事在 2026-09-24 恰恰不可靠。
 * 代价：万一"新弹窗"与上一个差得特别远（≥50%）会**漏关**那一个（红线 §3.1 可接受的那一侧，日志有据）。
 *
 * ## ⚡ 2026-09-29：把"等满 1 秒"换成"**画面变了就认账**"（用户口径）
 *
 * 用户原话："**说实话我觉得自动关闭弹窗还没有我手动点击快**……**真人点击的逻辑就是
 * 弹窗出现-点关闭-下一个弹窗出现-点关闭-大厅出现结束点击**"。
 *
 * 他说得对，原因也很清楚：**真人靠眼睛** —— 新弹窗一出现就看见、顺手就点；
 * 而观察窗那条闸是"**靠猜**"：它没法直接知道"游戏把上一枪的结果画出来了吗"，只能拿"等够 1 秒"当代理，
 * 于是每个弹窗都要多花约 0.7~1s。现在整帧变化（`capture/FrameSignature`）给了**直接证据**（每晚实测：
 * 换一个弹窗 ≈40%、弹窗没了 ≥68%），就该用它：**整帧变化 ≥ [renderedChangePercent]（20%）
 * ⇒ 上一枪已生效 ⇒ 立刻判下一枪**；观察窗与"同一张画面"只在"没有证据"时兜底。
 *
 * 为什么这样仍然安全：**"整幅换掉"（≥[sceneReplacedPercent] = 50%）先判** —— 那是"弹窗消失、
 * 露出下层界面"，直接停手（实测 53.3%~77.2%）；而 20%~50% 这一带实测就是"换了一个弹窗"（36.6%~43.5%）。
 *
 * **残留风险（如实记）**：若某个**尺寸中等**的弹窗消失时整帧变化正好落在 20%~50%，会被当成"换了新弹窗"
 * 而补枪 —— 那正是 2026-09-24 那次事故的形态。本游戏实测到的活动弹窗都是近全屏（消失时 ≥53%），
 * 但**没有中等尺寸弹窗的样本**；好在那两处日志每轮都带整帧变化百分比，真出问题一次就能看出来。
 *
 * ## 🛡 2026-09-29（二）：**只对"现在"的画面开枪**（[maxFrameAgeMs]）
 *
 * 用户提问："**如何绝对防止在大厅里误点进入商城**"（进了商城就跑不了农场了）。把两次事故
 * （2026-09-24 18:51、2026-09-28 21:41）与今晚的实测放在一起看，可以先说清一件事：
 *
 * > **关闭控件（X）的模板在大厅上并不命中**（回到大厅后 e1/e2/e3 只有 0.06~0.28 —— progress 第 189 条）。
 *
 * ⇒ 所以"点进商城"**不可能**是"在活的大厅画面上认出弹窗"造成的；它只有一条路径：
 * **判定用的那张画面比真实屏幕旧** —— 旧画面上是弹窗的 X，而同一坐标在**当下**的屏幕上是大厅的商城入口。
 *
 * 既然风险全部来自"画面与当下不符"，那就把它钉住（这一条 + 采集层的"开火前复眼"，见
 * `CaptureService.preFireRecheck`）：
 *
 * - **决策帧龄 ≤ [maxFrameAgeMs]**：本轮那张画面拍得太久以前 ⇒ 不下发（等下一轮）。
 *   它只负责掐"识别卡顿 / 兜底重放 / 帧流打嗝"——**上限值必须跟着当前"轮耗时"调**
 *   （它是"轮询 + 单轮识别"，慢下来就会恒拦；2026-09-29 真机因此从 500 调到 **1500**，见常量说明）；
 * - **开火前复眼**（在 `ClickDispatch` 之前、采集层做）：**现取一张当时最新的画面**
 *   （`ImageReader.acquireLatestImage()`，真机 ≈55fps ⇒ 约 20~40ms 前），在**同一个锚点**上再确认一次
 *   "X 还在那儿、位置没挪"；不在 / 挪了 ⇒ 这一枪不发；**取不到更新的帧** ⇒ 说明屏幕自我们那张画面之后
 *   没变过（平台只在内容变化时产帧）⇒ 本条不参与，交给上面几道闸。
 *
 * ⚠ 边界如实说：再往下还剩**一段物理上消除不了的窗口** —— "最新一帧拍下" 到 "手指落下" 之间的
 * 手势延迟（几十到 ~150ms，`dispatchGesture` 注入 + 平台派发）。也就是说，**除非**在这 0.1 秒里
 * 屏幕恰好自己变了（弹窗自动消失 / 用户同时手动点了它），否则不可能打偏。要再收紧只能改游戏，改不了。
 *
 * 每轮流程：
 * ```
 * ① 记录上一枪的观测结果（仅写日志，不影响动作）
 * ② 弹窗真的不在 → 复位并结束
 * ③ 已达上限 → 停止点击（等弹窗消失后复位）
 * ④ 决策帧够新 + 弹窗已确认 + 补点前的闸（整幅换掉 ⇒ 停手；**画面换了一幅 ⇒ 直接放行**；
 *    否则才看 观察窗 + 同一张画面）+ 锚点命中 → 下发一次点击
 *    （下发前还有采集层的"开火前复眼"：用更新的画面在同一落点复认一次）
 * ```
 * 相邻点击的间隔由统一转发层保证（红线 6，≥300ms），本类不做节流。
 *
 * 计数口径（2026-09-22）：只有真实点击一种行为，「演练不计数」那套分支已随点击模式一并移除 ——
 * 这里每给出一个 [PopupStep.Click] 就计一次；是否真的发得出去由 `ClickGate` 决定（拒绝会留审计）。
 */
class PopupCloseController(
    private val maxClicksPerPopupRun: Int = DEFAULT_MAX_CLICKS_PER_POPUP_RUN,
    /**
     * 补点的**观察窗**（ms，2026-09-28 加）：本轮画面必须比上一枪晚这么久，才允许再点一次。
     * 默认 [DEFAULT_RECLICK_GUARD_MS]；调大更保守（更慢）、调小更容易补枪（更容易错点）。
     */
    private val reClickGuardMs: Long = DEFAULT_RECLICK_GUARD_MS,
    /**
     * 同一段弹窗内**最多点多久**（ms，2026-09-28 加）。因观察窗让每枪都变贵（≥1.5s），
     * 光靠次数上限会让"锚点指错位置"时连点很久 ⇒ 再加一道总时长闸。默认 [DEFAULT_MAX_POPUP_RUN_MS]。
     */
    private val maxRunMs: Long = DEFAULT_MAX_POPUP_RUN_MS,
    /**
     * **"同一张画面"的判据**（%，2026-09-28 加）：本轮那张画面与上一枪那张的整帧变化小于它，
     * 就认为"又要打同一张图" ⇒ 不补点。默认 [DEFAULT_IDENTICAL_FRAME_PERCENT]（2.0%）。
     * 调小更保守（更容易补枪）、调大更容易停手（更慢但更安全）。
     */
    private val identicalFramePercent: Double = DEFAULT_IDENTICAL_FRAME_PERCENT,
    /**
     * **"整幅换掉了"的判据**（%，2026-09-28 加）：本轮那张画面与上一枪那张的整帧变化**不小于**它，
     * 就认为"弹窗没了、露出下层界面" ⇒ **本段停手**（等弹窗消失后自动复位）。
     * 默认 [DEFAULT_SCENE_REPLACED_PERCENT]（60.0%）。
     */
    private val sceneReplacedPercent: Double = DEFAULT_SCENE_REPLACED_PERCENT,
    /**
     * **"同一张画面"的容忍时长**（ms，2026-09-29 加）：画面与上一枪那张几乎一样时，只在这段时间内
     * 拦着不补枪；过了就说明游戏早定住了、弹窗真的还在 ⇒ 该补。默认 [DEFAULT_IDENTICAL_FRAME_HOLD_MS]。
     */
    private val identicalFrameHoldMs: Long = DEFAULT_IDENTICAL_FRAME_HOLD_MS,
    /**
     * **"游戏已经把上一枪的结果画出来了"的判据**（%，2026-09-29 加）：本轮画面与上一枪那张的整帧变化
     * **不小于**它 ⇒ 认为画面换了一幅、上一枪已生效 ⇒ **立刻**判下一枪（不再等观察窗）。
     * 默认 [DEFAULT_RENDERED_CHANGE_PERCENT]（20）。
     */
    private val renderedChangePercent: Double = DEFAULT_RENDERED_CHANGE_PERCENT,
    /**
     * 上面那条的**下限时长**（ms）：新弹窗实测在上一枪之后 ≈0.27s 就完整画好了，取 350ms
     * 跳过淡出 / 淡入那几帧（别点在动画中间）。默认 [DEFAULT_RENDERED_FLOOR_MS]。
     */
    private val renderedFloorMs: Long = DEFAULT_RENDERED_FLOOR_MS,
    /**
     * **决策帧的新鲜度上限**（ms，2026-09-29 加，见类注释"只对'现在'的画面开枪"）：
     * 本轮那张画面的拍摄时刻距"现在"超过它就**不下发这一枪**（等下一轮）。默认 [DEFAULT_MAX_FRAME_AGE_MS]。
     */
    private val maxFrameAgeMs: Long = DEFAULT_MAX_FRAME_AGE_MS,
    /**
     * **停手之后还允许"再给几次机会"**（2026-10-01 加，用户报"活动弹窗没有全部关掉"；默认 [MAX_RE_ARMS]）。
     * 见 [onRound] 停手分支：只为兜住"**整幅换掉**"那条代理量判据把'弹窗内容换了一幅'误判成'弹窗没了'"。
     */
    private val maxReArms: Int = MAX_RE_ARMS,
    /** 停手后**等多久**才允许复位重试（ms；默认 [RE_ARM_AFTER_MS]）—— 让画面先稳定下来（见 [onRound]）。 */
    private val reArmAfterMs: Long = RE_ARM_AFTER_MS,
    /**
     * **"关闭控件那一小块几乎没变"的阈值**（%，默认 [DEFAULT_ANCHOR_KEPT_PERCENT]）—— A2 局部证据。
     * 只用于"整帧变化 ≥ [sceneReplacedPercent] 时分辨是哪一种大变"，判据见 [onRound] 那段说明。
     */
    private val anchorKeptPercent: Double = DEFAULT_ANCHOR_KEPT_PERCENT,
) {

    init {
        require(maxClicksPerPopupRun >= 1) { "点击上限至少为 1：$maxClicksPerPopupRun" }
        require(reClickGuardMs >= 0) { "观察窗不能为负：$reClickGuardMs" }
        require(maxRunMs > 0) { "本段时长上限必须为正：$maxRunMs" }
        require(maxFrameAgeMs > 0) { "决策帧新鲜度上限必须为正：$maxFrameAgeMs" }
        require(identicalFramePercent >= 0.0) { "同一画面阈值不能为负：$identicalFramePercent" }
        require(identicalFrameHoldMs >= 0) { "同一画面容忍时长不能为负：$identicalFrameHoldMs" }
        require(renderedFloorMs >= 0) { "画面换了一幅的下限时长不能为负：$renderedFloorMs" }
        require(maxReArms >= 0) { "复位重试次数不能为负：$maxReArms" }
        require(reArmAfterMs >= 0) { "复位重试的等待时长不能为负：$reArmAfterMs" }
        require(anchorKeptPercent >= 0.0) { "『关闭控件那一小块几乎没变』的阈值不能为负：$anchorKeptPercent" }
        require(sceneReplacedPercent >= renderedChangePercent) {
            "「整幅换掉」阈值不能小于「画面换了一幅」：$sceneReplacedPercent < $renderedChangePercent"
        }
        require(renderedChangePercent >= identicalFramePercent) {
            "「画面换了一幅」阈值不能小于「同一画面」：$renderedChangePercent < $identicalFramePercent"
        }
    }

    /** 本段弹窗内已下发的点击次数（0 = 还没点过）。弹窗消失即复位。 */
    var attempt: Int = 0
        private set

    /** 是否有一枪已下发、等下一轮记录观测结果（**不影响动作**）。 */
    var awaitingVerification: Boolean = false
        private set

    /** 是否已达本段上限（停止点击；弹窗消失后复位）。 */
    var gaveUp: Boolean = false

    /**
     * **本段属于哪一屏遮挡屏**（2026-09-30 加，见 [PopupRoundInput.overlayKey]）。
     * 值变了 ⇒ 换了另一屏遮挡屏 ⇒ **新的一段**（不再受上一屏"已停手"的影响）。
     */
    private var overlayKey: String? = null
        private set

    /** 上一枪下发的时刻（0 = 本段还没点过；用于"这一帧比上一枪新吗"这条判据）。 */
    private var lastClickAtMs: Long = 0L

    /** 停手的时刻（0 = 本段没停过手；用于"停手后过了多久"这条自愈判据，见 [onRound]）。 */
    private var gaveUpAtMs: Long = 0L

    /**
     * **本屏遮挡屏已经"再给几次机会"了几次**（2026-10-01 修，用户报"活动弹窗没有全部关掉"）。
     *
     * 只在 [onRound] 的停手分支里增长；**新的一段**（弹窗消失 / 换屏 / 换成另一屏遮挡屏 ⇒ [reset]）归零。
     * 上限 [maxReArms]：既让误停手能自愈，又不至于变成"对着错位置反复点"（点击仍要过那几道闸）。
     */
    private var reArms: Int = 0

    /** 本段**第一枪**下发的时刻（0 = 本段还没点过；用于总时长闸 [maxRunMs]）。 */
    private var firstClickAtMs: Long = 0L

    fun onRound(input: PopupRoundInput): PopupRoundOutcome {
        // 冻结轮（FR-09）：非前台时"弹窗还在不在"无从判断——不动作、也**不推进计数**
        if (!input.foreground) {
            return outcome(PopupVerification.None, PopupStep.Skip("目标不在前台"))
        }

        // 弹窗是否还在，用**本轮原始命中**（比滞回结论灵敏：弹窗刚弹出/刚消失都能立刻反映）
        val present = input.popupHit || input.popupConfirmed
        var verification: PopupVerification = PopupVerification.None

        // **换了一屏遮挡屏 = 新的一段**（2026-09-30 真机修，见 [PopupRoundInput.overlayKey]）：
        // 游戏会背靠背弹两个遮挡屏（新手引导 → 新手大厅，实测相隔 0.46s、整帧变化 82.7%）。
        // "整幅换掉（≥[sceneReplacedPercent]）"那道闸会在第一屏关掉时把本段判成停手 —— **那是对的**；
        // 错的只是"停手"没跟着换屏重开一段 ⇒ 第二屏从此再也点不了（用户报"新手大厅没关"）。
        // 状态由滞回确认（连续 2 次命中）⇒ 换屏即重开是安全的（不是单轮抖动）。
        if (input.overlayKey != null && input.overlayKey != overlayKey) {
            reset()
            overlayKey = input.overlayKey
        }

        if (!present) {
            // 弹窗真的不在了 → 本段结束，计数复位（复位前把"上一枪之后的样子"记一次）
            if (awaitingVerification) {
                awaitingVerification = false
                verification = PopupVerification.Closed(attempt)
            }
            if (attempt != 0) reset()
            // 「未见遮挡屏」= 三屏（活动弹窗 / 新手引导 / 新手大厅）这一轮**都没命中** ⇒ 本段结束。
            // 措辞不再写"活动弹窗"（2026-09-29）：本闭环三屏共用，日志要按实际说的是哪一族
            return outcome(verification, PopupStep.Skip("未见遮挡屏（活动弹窗 / 新手引导 / 新手大厅）"))
        }

        // ① 上一枪的观测（只写日志）：点击之后弹窗是否还在。**不作为动作依据**
        if (awaitingVerification) {
            awaitingVerification = false
            verification = PopupVerification.StillPresent(attempt)
        }

        if (gaveUp) {
            // **自愈：停手之后，若画面稳定下来而遮挡屏仍被确认 ⇒ 再给一次机会**（2026-10-01 修）。
            //
            // ## 修的是什么（真机 06:17 那次"活动弹窗没有全部关掉"）
            //
            // 停手三条路里有一条是「**本轮画面整幅换掉了 ⇒ 多半是弹窗已被关掉，或换成了另一屏遮挡屏**」，
            // 它用的是"整帧变化 %"这个**代理量** —— 分不开"弹窗没了"与"**弹窗还在、只是内容换了一幅**"。
            // 那天点了一下之后弹窗内容换了（变化 66.2%），于是停手；而复位条件只有"换屏 / 遮挡屏消失"，
            // 弹窗还在 ⇒ **永远不复位** ⇒ 弹窗就那样一直挂在屏上（此后 3 分钟弹窗标志以 0.9958 一直命中）。
            //
            // ⇒ 补一条：**停手后过了 [reArmAfterMs]（画面已重新稳定）、而遮挡屏仍被确认** ⇒ 复位本段、
            //    重新走一遍（重新定位关闭控件）。上限 [maxReArms] 次/屏，且复位后每次点击**仍要过**
            //    "锚点定位 / 决策帧新鲜 / 比上一枪新 / 观察窗"那几道闸 ⇒ 不会退化成乱点
            //    （"宁可漏关，不可错点"的口径一个字没改）。
            //
            // 为什么用"过了 N 毫秒"当"画面稳定"的判据：`frameChangedPercent` 是**相对上一枪**的整帧变化，
            // 不是逐帧变化 ⇒ 拿它判"稳不稳"会一直很大。停手已过 3 秒 + 弹窗仍被确认（滞回要求连续命中）
            // 已经足够说明"这一屏是新的稳定状态"了。
            if (input.popupConfirmed && reArms < maxReArms &&
                gaveUpAtMs > 0L && input.nowMs - gaveUpAtMs >= reArmAfterMs
            ) {
                reArms++
                // 复位本段，但**保留 reArms 与 overlayKey**（同一屏弹窗仍是同一段；reArms 是"这一屏给过几次"的账）
                attempt = 0
                awaitingVerification = false
                gaveUp = false
                gaveUpAtMs = 0L
                lastClickAtMs = 0L
                firstClickAtMs = 0L
                return outcome(
                    verification,
                    PopupStep.Skip(
                        "停手后画面已稳定、遮挡屏仍被确认 ⇒ 复位重试（第 $reArms/$maxReArms 次）",
                    ),
                )
            }
            // ⚠ 措辞（2026-09-30 改）：旧句"已达本段点击上限（N 次）"会被读成"**上限就是 N**"
            // —— 真机那次 N=1，而上限其实是 `maxClicksPerPopupRun`（12），排障直接被带偏。
            // 现在如实说三件事：**本段已停手** / 已点几次与上限多少 / **怎么才会复位**
            // （换屏 或 遮挡屏消失 —— 两条复位路径都要写出来，否则会以为只能等"消失"）。
            return outcome(
                verification,
                PopupStep.Skip(
                    "本段已停手（已点 $attempt 次，上限 $maxClicksPerPopupRun）——" +
                        "换屏或遮挡屏消失后自动复位",
                ),
            )
        }

        // 弹窗刚命中、滞回还没确认（连续 2 次命中才进入该状态）：等下一轮——不拿未确认的结论点击
        if (!input.popupConfirmed) {
            return outcome(verification, PopupStep.Skip("弹窗刚命中、尚未确认（等下一轮）"))
        }

        // 兜底：达到上限 → 停手（防"锚点匹配到非关闭控件"时无限点击）
        // 两道闸：**次数**（上限，2026-09-13）与**总时长**（2026-09-28 加：观察窗让每枪都 ≥1.5s，
        // 只看次数会变成"对着错位置连点 18 秒"）—— 任一到达即停手，弹窗消失后自动复位。
        if (attempt >= maxClicksPerPopupRun) {
            gaveUp = true
            gaveUpAtMs = input.nowMs
            return outcome(
                verification,
                PopupStep.GiveUp(
                    attempt,
                    "本段已点满 $maxClicksPerPopupRun 次（次数上限）——多半是这个关闭控件的锚点指到了别处",
                ),
            )
        }
        if (attempt > 0 && input.nowMs - firstClickAtMs >= maxRunMs) {
            gaveUp = true
            gaveUpAtMs = input.nowMs
            return outcome(
                verification,
                PopupStep.GiveUp(
                    attempt,
                    "本段已连点 ${maxRunMs / 1000} 秒（时长上限）——多半是这个关闭控件的锚点指到了别处",
                ),
            )
        }

        // **决策帧必须够新**（2026-09-29 加，见类注释）：这一条与"比上一枪新"是两件事 ——
        // 后者只保证"不是点击之前那张"，不保证"不是几百毫秒到几秒之前那张"。而"拿旧画面决定点哪里"
        // 正是两次"点进商城"的形态（那一枪的坐标在旧画面上是弹窗的 X，在**当下**屏幕上是大厅的商城入口）。
        // 实测决策帧龄：常态 ≈320ms（轮询 120ms + 单轮识别 ≈200ms），单帧处理 P95 到 **643ms**
        // ⇒ 上限取 [maxFrameAgeMs]：常态照旧放行，只掐掉"识别卡了 / 兜底重放 / 帧流打嗝"那几种。
        val frameAgeMs = input.nowMs - input.frameAtMs
        if (frameAgeMs > maxFrameAgeMs) {
            return outcome(
                verification,
                PopupStep.Skip(
                    "本轮那张画面是 ${frameAgeMs}ms 前拍到的（上限 ${maxFrameAgeMs}ms）——" +
                        "画面不够新，不在旧画面上开枪，等下一轮（宁可漏关，不可错点）",
                ),
            )
        }

        // **补点前必须"本轮这张画面是上一枪之后拍的"**（2026-09-24 真机事故，见类注释）：
        // 拿旧画面上的"弹窗仍在"去补枪 = 盲点一次——若那一枪已经把弹窗关掉，露出的下层界面
        // 在同一个位置可能就是别的按钮（真机点进了商城）。宁可漏关（用户手动关 / 再跑一次），不可错点。
        if (attempt > 0) {
            if (input.frameAtMs <= lastClickAtMs) {
                return outcome(
                    verification,
                    PopupStep.Skip(
                        "本轮用的画面还是上一枪之前的（${input.frameAtMs}，上一次点击 ${lastClickAtMs}）——" +
                            "不补点，等这一枪之后的画面（宁可漏关，不可错点）",
                    ),
                )
            }
            // **"比上一枪新"还不够，要晚够一个观察窗**（2026-09-28 真机，见类注释 / progress 第 219 条）：
            // 刚点完的那 0.5~1 秒里，画面是"旧内容的新帧"（游戏还没把这一枪的结果画出来），
            // 拿它去补枪照样是盲点 —— 而点击不可逆。
            val changed = input.frameChangedPercent
            // **画面整幅换掉了 ⇒ 本段停手**（2026-09-28 加，见类注释）：这种"大变"指的正是
            // "弹窗没了、露出下层界面"——那一刻锚点在真机上会在大厅上满分命中（2026-09-24 事故），
            // 再往下点就是往刚露出来的下层界面点（商城）。
            // 实测：换一个弹窗 **36.6%~43.5%**，弹窗没了 / 露出下层 **53.3%~77.2%** ⇒ 阈值取 50%
            // （2026-09-29 从 60 收下来：那一段"露出下层"只有 53.3%~53.4%，60 根本没拦住）。
            //
            // ⚠ **2026-09-30 真机：这里必须回 [PopupStep.GiveUp]，不能回 Skip** —— 它同样是"本段停手"，
            // 而"停手必须被界面说出来"那条通道（`GiveUp` → `PopupCloseSignal.gaveUpReason`）此前只挂在
            // 另外两条停手路径上 ⇒ 走这条路的停手对界面**完全无声**。真机实录 15:56:16 这一句之后：
            // `状态标签: 待命期没有要说的 ⇒ 摘掉`，而弹窗在屏上又待了 50 多秒（用户报
            // "最后一次活动弹窗没有关掉"）。
            // ⚠ 同一段实录里这一帧的变化量恰好是 **50.0%**（正贴着阈值）⇒ "变化多少 %"这条代理量
            // 分不开"换了新弹窗"与"弹窗没了"，这道闸本身也在等改造（另行方案）。
            if (changed != null && changed >= sceneReplacedPercent) {
                // **A2（2026-10-02）：先用"局部证据"分辨是哪一种"大变"** —— 用户口径原话：
                // "**阈值判断总是存在例外的情况，我记得之前好像应取消了阈值判断的方案？**"（他记得对）。
                // 2026-09-29 撤销整帧比较时留下的口径就是答案：
                // "**别再用整帧比较，改看『X 自己那一小块』（锚点窗口像素 / 命中分数这一轮有没有变）**"。
                //
                // ⇒ 这里看 [PopupRoundInput.anchorWindowChangedPercent]（**关闭控件锚点窗口那一小块**的变化）：
                //   · 那一小块**几乎没变** ⇒ 屏上那个控件还在原样 ⇒ 判"**弹窗还在、只是内容换了一幅**"
                //     ⇒ **不停手**，继续往下走（锚点定位 / 决策帧新鲜 / 观察窗 / 开火前复眼那些闸一道不减）；
                //   · 那一小块**也变了**（或拿不到 ⇒ `null`）⇒ 按原来那条"整幅换掉 ⇒ 停手"处理。
                //
                // 为什么"局部没变"就敢不停手（安全依据）：2026-09-24"点进商城"那一枪的形态是
                // **弹窗没了、下层的元素顶上来** —— 那时"关闭控件所在的那一小块"必然明显变样，
                // 正是本条判据要分开的东西；而整帧 % 分不开它（两组数据只隔一道十几点的空档）。
                val local = input.anchorWindowChangedPercent
                if (local != null && local < anchorKeptPercent) {
                    // 落到下面那些闸上：≥renderedChangePercent 时仍可当轮补枪（本就要求帧够老），
                    // 否则退回观察窗。注意**没有**在这里放行任何点击 —— 放行由下面那些闸决定。
                } else {
                    gaveUp = true
                    gaveUpAtMs = input.nowMs
                    val localNote = if (local == null) {
                        "关闭控件那一小块拿不到（不可比）"
                    } else {
                        "关闭控件那一小块也变了 ${"%.1f".format(local)}%"
                    }
                    return outcome(
                        verification,
                        PopupStep.GiveUp(
                            attempt,
                            "本轮画面整幅换掉了（整帧变化 ${"%.1f".format(changed)}%）且$localNote" +
                                "——多半是弹窗已被关掉，或换成了另一屏遮挡屏",
                        ),
                    )
                }
            }
            // **画面换了一幅（但没到"整幅换掉"）⇒ 上一枪的结果已经画出来了 ⇒ 立刻判下一枪**。
            //
            // 2026-09-29 用户口径（原话）："**只要画面变化超过 20%，就认定画面发生了变化，然后只点一次**"、
            // "真人点击的逻辑就是 弹窗出现-点关闭-下一个弹窗出现-点关闭-大厅出现结束点击"。
            // 这正是观察窗与"同一张画面"那两道闸想证明的事（"游戏把这一枪的结果画出来了吗"），
            // 而画面变化本身就是最直接的证据 ⇒ 有它就不必再等满 [reClickGuardMs]。
            // 实测：换一个弹窗的整帧变化是 **36.6%~43.5%**，弹窗没了 / 露出下层是 53.3%~77.2%（走上面那条停手）。
            //
            // [renderedFloorMs] 是"别点在动画中间"的下限：实测新弹窗在**上一枪之后 ≈0.27s** 就已完整画出，
            // 取 350ms 跳过淡出/淡入那几帧（那几帧上锚点可能正压在半透明的旧弹窗上）。
            if (changed != null && changed >= renderedChangePercent) {
                if (input.frameAtMs - lastClickAtMs < renderedFloorMs) {
                    return outcome(
                        verification,
                        PopupStep.Skip(
                            "画面已换了一幅（整帧变化 ${"%.1f".format(changed)}%），但只比上一枪晚 " +
                                "${input.frameAtMs - lastClickAtMs}ms（下限 ${renderedFloorMs}ms）——" +
                                "再等一拍，避免点在动画中间",
                        ),
                    )
                }
                // 放行：不再看观察窗、不再看"同一张画面"（它们要证明的就是这件事）
            } else {
                val earliestFrameAtMs = lastClickAtMs + reClickGuardMs
                if (input.frameAtMs < earliestFrameAtMs) {
                    return outcome(
                        verification,
                        PopupStep.Skip(
                            "本轮画面只比上一枪晚 ${input.frameAtMs - lastClickAtMs}ms" +
                                "（观察窗 ${reClickGuardMs}ms，还差 ${earliestFrameAtMs - input.frameAtMs}ms）——" +
                                "不补点，等这一枪真正之后的画面（宁可漏关，不可错点）",
                        ),
                    )
                }
                // **跟上一枪那张（几乎）是同一张画面 ⇒ 别再扣一次扳机**（2026-09-28 加，见类注释）：
                // 观察窗保证"帧够新"，这一条保证"内容真的变了"——两次"点进商城"的危险一枪都打在
                // "游戏还没把上一枪的结果画出来"的时候，那种画面逐点几乎没变（实测 0.1% ↔ 变了是 41.7%）。
                //
                // ⚠ **只在一小段内拦**（[identicalFrameHoldMs]，2026-09-29 真机后加）：那个"还没画出来"的
                // 窗口只有一秒级（实测渲染延迟 ≈0.76s）；过了容忍时长画面**还是一模一样**，说明游戏早就
                // 定住了 —— 那张画面就是"现在"，弹窗也真的还在（状态与锚点都是在这一帧上判的）⇒ 该补枪。
                // 不加这道时间界，真机上就变成"第一个弹窗怎么也关不掉"：实录 00:05:46.6 第 1 枪之后 6 秒里
                // 整帧变化一直是 0.0%~1.7%（<2%）⇒ 一直被这条拦住，直到 00:05:52.6 才补上第 2 枪。
                val settledIdentical = input.nowMs - lastClickAtMs >= identicalFrameHoldMs
                if (changed != null && changed < identicalFramePercent && !settledIdentical) {
                    return outcome(
                        verification,
                        PopupStep.Skip(
                            "本轮画面与上一枪那张几乎一模一样（整帧变化 ${"%.1f".format(changed)}%，" +
                                "阈值 $identicalFramePercent%，容忍 ${identicalFrameHoldMs}ms 未到）" +
                                "——同一张画面上不重复点（宁可漏关，不可错点）",
                        ),
                    )
                }
            }
        }

        // 多条锚点时取**产物声明顺序的第一个**（AnchorLocator 保持声明顺序）：标定先标一条最保险。
        // 三屏各自只会有"一个关闭控件"（活动弹窗有多个相似样式，但那是**多条记录**、仍是同一类控件），
        // 所以统一取第一条即可 —— 这也正是新手两页**不需要"用途"**的原因（见 PatrolAnchors.purposesFor）。
        val anchor = input.anchors.firstOrNull()
            ?: return outcome(
                verification,
                PopupStep.Skip("未标定 / 未命中关闭控件锚点（不猜位置）"),
            )

        val request = ClickRequest(
            decisionId = input.decisionId,
            // 来源由调用方给出（FR-01 活动弹窗 / FR-02 新手两页），审计里必须能分开读
            source = input.source,
            anchorName = anchor.name,
            frameX = anchor.frameX,
            frameY = anchor.frameY,
        )
        // 真实点击是唯一行为（2026-09-22）：给出 Click 即推进计数并挂上待观测
        if (attempt == 0) firstClickAtMs = input.nowMs
        attempt += 1
        awaitingVerification = true
        lastClickAtMs = input.nowMs
        return outcome(verification, PopupStep.Click(request, attempt = attempt))
    }

    private fun reset() {
        attempt = 0
        awaitingVerification = false
        gaveUp = false
        overlayKey = null
        lastClickAtMs = 0L
        firstClickAtMs = 0L
        // 真正的新一段（弹窗消失 / 换屏 / 换成另一屏遮挡屏）⇒ "再给几次机会"的账也重置
        gaveUpAtMs = 0L
        reArms = 0
    }

    private fun outcome(verification: PopupVerification, step: PopupStep): PopupRoundOutcome =
        PopupRoundOutcome(verification = verification, attempt = attempt, step = step)

    companion object {

        /**
         * 同一段弹窗内的点击次数上限（T2-8，2026-09-13 用户拍板取 12）。
         *
         * 用途**仅是兜底**"锚点匹配到非关闭控件"的情形——不是"单次点击成败"的判据
         * （那个判不出来，见类注释）。12 足以覆盖常见弹窗数（实测一次登录 3~5 个）与少量误判；
         * 即使锚点全错，配合转发层 ≥300ms 的间隔，最多约 4 秒即停手。
         */
        const val DEFAULT_MAX_CLICKS_PER_POPUP_RUN = 12

        /**
         * 补点的**观察窗**：本轮画面要比上一枪晚这么久，才允许再点一次（2026-09-28 加）。
         *
         * 取 **1000ms**（2026-09-28 用户拍板：从 1.5s 降到 1.0s —— 实测"弹窗 1~3~5 个、点一个立马冒一个"，
         * 每枪都等 1.5s 让 3~5 个弹窗要 6~13s，太慢）：
         * 锚点：**拦住过 2026-09-28 那次危险的一枪**——它用的是"上一枪之后 **0.17s**"拍的画面
         * （21:41 那轮的第 4 枪）⇒ 1.0s 绰绰有余；而那条"游戏把结果画出来"的延迟实测 **≈0.76s**
         * ⇒ 1.0s 仍留有余量（0.6s 就贴着这条线了，故不采用）。
         */
        const val DEFAULT_RECLICK_GUARD_MS = 1_000L

        /**
         * 同一段弹窗内**最多连点多久**（2026-09-28 加，取 12s）。
         *
         * 与次数上限（12）配合：观察窗把每枪抬到 ≥1.5s ⇒ 次数上限单独存在时最坏是"18 秒一直在点"，
         * 而这一闸把最坏情况拉回 **≈8 枪 / 12 秒**（实测一次登录 3~5 个弹窗，够用）。
         * 它防的仍是"锚点匹配到非关闭控件"那种情况：次数 + 时长双到即停，弹窗消失后复位。
         */
        const val DEFAULT_MAX_POPUP_RUN_MS = 12_000L

        /**
         * **"同一张画面"的阈值**（%，2026-09-28 加，取 2.0）。
         *
         * 依据是当天的实测（整帧比较，见 `capture/FrameSignature`）：
         * **同一张静止画面 = 0.1%**，**换了一个弹窗 = 41.7% / 43.5%**，**弹窗没了 = 70.6%**。
         * 2.0% 离两头都极远（比"没变"高一档，离"变了"低一个数量级），
         * 只用来拦"逐点几乎一样"那种 —— 它既不承担"判断换没换弹窗"（那件事幅度判不了），
         * 也不与观察窗重复：观察窗管"帧够不够新"，这条管"内容真的变了没有"。
         */
        const val DEFAULT_IDENTICAL_FRAME_PERCENT = 2.0

        /**
         * **"整幅换掉了"的阈值**（%，2026-09-28 加、**2026-09-29 由 60 收到 50**）。
         *
         * 依据（全部整帧比较，见 `capture/FrameSignature`）：
         * ```
         * 换了一个新弹窗：36.6 / 39.8 / 39.9 / 40.1 / 40.2 / 41.7 / 43.2 / 43.5（%）  ← 八次，最大 43.5
         * 弹窗没了露出下层：                53.3 / 53.4 / 68.6 / 69.4 / 70.6 / 77.2（%）  ← 六次，**最小 53.3**
         * ```
         * 两组之间那道空档是 **43.5 → 53.3**，50.0 落在里面（离合法换弹窗留 6.5 个点、离"弹窗没了"
         * 留 3.3 个点）。
         *
         * ⚠ **为什么从 60 收到 50**（2026-09-29 01:03 那段的真机现场）：那一段"弹窗没了、露出下层界面"
         * 的整帧变化只有 **53.3%~53.4%**，**低于 60** ⇒ 这道闸当时**没有生效**，最后挡住那一枪的
         * 只剩"锚点没命中"（日志：`未命中关闭控件锚点（不猜位置）`）。而 2026-09-24 那次
         * "点进商城"恰恰证明：**弹窗刚消失那一刻，`activity_popup_e1` 在大厅上会满分命中（0.9999）**
         * ⇒ 那一天只是运气好。收到 50 之后这一段就会被判成"整幅换掉 ⇒ 停手"。
         *
         * ⚠ 为什么必须要有这道闸：**两次"差一点就点错"的那一枪，都出现在"弹窗刚消失"那张画面上**
         * （23:22:41.385 变化 70.6%，只差 10ms 被观察窗拦住；23:30:40.066 变化 69.4%，只差 210ms）
         * —— 那一刻 `activity_popup_e1` 在真机上会**在大厅上满分命中**（2026-09-24 事故原样），
         * 锚点这道闸不成立，只剩观察窗在兜，余量只有 0.1~0.2s。
         *
         * 代价（如实记）：万一某个"新弹窗"与上一个差得特别远（≥50%），会被误判成"弹窗没了"而**漏关**
         * 那一个 —— 按红线 §3.1「宁可漏关，不可错点」，这是可接受的那一侧；日志里会写明本段停手的原因。
         */
        const val DEFAULT_SCENE_REPLACED_PERCENT = 50.0

        /**
         * **"同一张画面"的容忍时长**（ms，2026-09-29 加，取 2500）。
         *
         * 依据：2026-09-29 真机（`mm-log-20260929.txt` 00:05）—— 第 1 枪之后 6 秒里整帧变化一直是
         * **0.0% ~ 1.7%**（都小于 [DEFAULT_IDENTICAL_FRAME_PERCENT]），于是"同一张画面 ⇒ 不重复点"
         * 这条闸把补枪一路拦到 **00:05:52.6**（用户感受："**第一个弹窗很久才关掉**"）。
         *
         * 而它要防的那种"画面还没画出来"的窗口只有**一秒级**（实测渲染延迟 ≈0.76s，见 [DEFAULT_RECLICK_GUARD_MS]）
         * ⇒ 取 2.5s 给足余量：既拦住"刚点完就补枪"（2026-09-24 与 2026-09-28 两次危险一枪分别只等了
         * 0.17s / 0.8s，都被拦下），又不会把"弹窗还在、游戏已定住"这种正常情况卡成关不掉。
         */
        const val DEFAULT_IDENTICAL_FRAME_HOLD_MS = 2_500L

        /**
         * **"画面换了一幅"的阈值**（%，2026-09-29 加；用户口径取 **20**）。
         *
         * 用户原话："**只要画面变化超过 20%，就认定画面发生了变化，然后只点一次**"。
         * 依据（同一晚实测）："换一个弹窗"的整帧变化是 **39.8%~43.5%**，"弹窗没了"是 **68.6%~82%**
         * （后者走"整幅换掉 ⇒ 停手"那条）⇒ 20% 落在"动画噪声（0~1.7%）"与"换一个弹窗（≈40%）"之间，
         * 离两头都远：够灵敏（新弹窗一出现就认），又不会被弹窗自身的动画骗到。
         */
        const val DEFAULT_RENDERED_CHANGE_PERCENT = 20.0

        /**
         * 上一条的**下限时长**（ms，取 350）。
         *
         * 实测新弹窗在**上一枪之后 ≈0.27s** 就已完整画出（23:36 那段："只比上一枪晚 266ms、整帧变化
         * 已 35.0%"）⇒ 350ms 足够跳过淡出/淡入那几帧 —— 那几帧上锚点可能正压在半透明的旧弹窗上。
         */
        const val DEFAULT_RENDERED_FLOOR_MS = 350L

        /**
         * **决策帧的新鲜度上限**（ms，2026-09-29 加；**同日由 500 调到 1500**，见下）。
         *
         * ## 为什么从 500 调到 1500（2026-09-29 真机事故：弹窗永远关不掉）
         *
         * 500 的依据是当时的实测"决策帧龄"（= `nowMs - frameAtMs`，**它 ≈ 轮询间隔 + 单轮识别耗时**）：
         * ```
         * 常态          约 320ms（弹窗期轮询 120ms + 单轮识别 ≈200ms）
         * 单帧处理 P95  643ms（`docs/progress.md` 第 190 条）
         * ```
         * 但**识别轮变慢之后这条闸就变成"恒真"**：真机实录（第 286 条）——
         * ```
         * FR-02 不动作: 本轮那张画面是 578ms 前拍到的（上限 500ms）——画面不够新，不在旧画面上开枪，等下一轮
         * （连续刷了几十轮，一枪都没发出去 ⇒ 新手引导一直关不掉，用户看到的是"新手引导无法识别"）
         * ```
         * ⇒ **它是"轮耗时"的函数，不是"画面有多旧"的绝对量**：一轮识别要 600ms，帧龄就必然是 600ms 左右
         * （判定跑在帧线程上，处理期间新帧回调都在排队）⇒ 500ms 在这种节奏下**把正常路径全拦了** ✗。
         *
         * ## 放宽之后靠什么保证"只对现在的画面开枪"（这才是那道真闸）
         *
         * `CaptureService.preFireRecheck`（**开火前复眼**）：下发之前**现取一张当时最新的画面**
         * （`ImageReader.acquireLatestImage()`，真机约 20~40ms 前），在**同一个锚点**上重新匹配一次
         * "X 还在原处、位置没挪"；不在 / 挪了 ⇒ 这一枪不发；取不到更新的帧 ⇒ 说明屏幕自我们那张画面之后
         * **没变过**（平台只在内容变化时产帧）⇒ 那条路径本来就安全 ✓。
         * ⇒ 所以本上限只负责掐掉"**真的卡了**"（识别卡顿 / 兜底重放 / 帧流打嗝）：
         * 取 1500ms —— 覆盖当前 ≈600ms 的常态（含重框前后的大标志），又能把"几秒前那张"挡在外面。
         *
         * ⚠ 它与"比上一枪新"（`frameAtMs > lastClickAtMs`）**不是一回事**：后者只挡住"点击之前那张"，
         * 挡不住"比点击新、但已经是好几百毫秒甚至更久以前"的那种。
         * ⚠ **再次改这个数之前，先看当前实测的"决策帧龄"**（日志里那行 `本轮那张画面是 Nms 前拍到的`）：
         * 它是"轮询 + 单轮识别"，识别一变慢就必须跟着调，否则要么全拦（太紧）、要么形同虚设（太松）。
         */
        const val DEFAULT_MAX_FRAME_AGE_MS = 1500L

        /**
         * **停手后还允许"复位重试"几次**（2026-10-01 加，用户报"活动弹窗没有全部关掉"）。
         *
         * 只为兜住"**整幅换掉**"那条代理量判据的误判（它分不开"弹窗没了"与"弹窗还在、内容换了一幅"）——
         * 见 `onRound` 停手分支里的完整说明。取 2：一次误判能自愈，又不会变成"对着错位置反复点"
         * （每次点击仍要过锚点定位 / 决策帧新鲜 / 比上一枪新 / 观察窗那几道闸）。
         */
        const val MAX_RE_ARMS = 2

        /**
         * 停手后**等多久**才允许复位重试（ms，取 3000）。用"过了这么久"当"画面已重新稳定"的判据：
         * `frameChangedPercent` 是**相对上一枪**的整帧变化、不是逐帧变化 ⇒ 拿它判"稳不稳"会一直很大；
         * 而"停手已过 3 秒 + 遮挡屏仍被滞回确认"足够说明这一屏是新的稳定状态了。
         */
        const val RE_ARM_AFTER_MS = 3_000L

        /**
         * **"关闭控件那一小块几乎没变"的阈值**（%，A2，2026-10-02，取 **10.0**）。
         *
         * ## 它取代的是什么（用户口径）
         *
         * 用户："**阈值判断总是存在例外的情况，我记得之前好像应取消了阈值判断的方案？**" —— 他记得对：
         * 2026-09-29 撤销整帧比较那两刀时留下的口径是"**别再用整帧比较，改看『X 自己那一小块』
         * （锚点窗口像素 / 命中分数这一轮有没有变）**"。
         * 原来"整幅换掉 ⇒ 停手"只用整帧 % 这个**代理量**，它分不开"弹窗没了"与"弹窗还在、只是内容换了一幅"
         * （真机 2026-10-02 00:40：点完一枪 60.6% ⇒ 误停手，而随后两轮标志仍以 0.9958 命中；
         *  叠加 `RE_ARM_AFTER_MS` 的等待，一次关弹窗花 **7.9 秒 / 3 枪**）。
         *
         * ## 为什么取 10
         *
         * 如实说：**先给一个保守值，靠真机日志标定** —— 每条 FR-01 行都会带上实测的"锚点窗口变化 X%"
         * （见 `CaptureService` 的 `frameChangeNote`），跑几次就知道"控件还在 / 弹窗没了"两组落在哪儿。
         * 它不是"又一道靠猜的阈值"：量的是**同一个控件那一小块**（不是整帧），而且 `null`（拿不到 / 不可比）
         * 时**一律退回停手** ⇒ 安全侧不变。
         */
        const val DEFAULT_ANCHOR_KEPT_PERCENT = 10.0
    }
}

/**
 * 一轮 FR-01 决策的输入（全部由调用方如实提供，本类不猜）。
 *
 * @param foreground 目标是否前台（冻结轮 = false → 不动作、不计数）
 * @param popupConfirmed 滞回结论 = 活动弹窗（连续 2 次命中才算确认）
 * @param popupHit 本轮**原始命中**含活动弹窗（用于判断"弹窗是否还在"，比滞回结论更灵敏）
 * @param anchors 当前状态的锚点命中列表（调用方已按 `AnchorLocator.locate` 定位；未确认状态时给空列表）
 * @param decisionId 判定记录 ID（NFR-05 审计链：与 `MM-Click` 日志对齐）
 * @param nowMs 本轮的时刻（`elapsedRealtime`）—— 点击时刻按它记账（判"这一轮画面比上一枪新吗"）
 * @param frameAtMs **本轮识别用的那张画面**的拍摄时刻（采集层如实传入：新帧就是它自己，
 *   兜底重放就是缓存副本被拍下的那一刻）。⚠ 不是"最近一帧的到达时刻"——帧流不停时那个值永远很新，
 *   而本轮吃到的可能是旧画面（见 [PopupCloseController] 类注释里的真机事故）
 * @param frameChangedPercent **本轮那张画面与"上一枪那张"的整帧变化**（%，0..100；采集层用
 *   `capture/FrameSignature` 如实算）。`null` = 不可比（还没开过枪 / 几何变了）⇒ 这道闸不参与。
 *   ⚠ 必须是**整帧**：识别层的灰度是窗口化的（窗口外全 0），拿它算只会得出"换了弹窗也几乎没变"。
 */
data class PopupRoundInput(
    val foreground: Boolean,
    /**
     * **点击来源**（2026-09-29 加）：三屏共用本闭环（活动弹窗 = FR-01；新手引导 / 新手大厅 = FR-02），
     * 审计与日志要能分开读（红线 2 的合法来源枚举）。默认 FR-01 = 老调用点不变。
     */
    val source: ClickSource = ClickSource.FR_01,
    val popupConfirmed: Boolean,
    val popupHit: Boolean,
    val anchors: List<AnchorHit>,
    val decisionId: String,
    val nowMs: Long,
    val frameAtMs: Long,
    val frameChangedPercent: Double? = null,
    /**
     * **关闭控件锚点窗口那一小块自上一枪以来的变化**（%，0..100；A2 局部证据，2026-10-02）。
     *
     * 采集层用 `capture/FrameSignature.sampleRegion` 采**同一个矩形**（= 这一枪点的那个锚点的搜索窗口，
     * 运行帧像素）在"上一枪那张画面"与"本轮这张画面"上的签名，再 `changedPercent` 如实算出来。
     * `null` = 拿不到（还没开过枪 / 几何变了 / 不知道锚点窗口 / 窗口变了 ⇒ 不可比）
     * ⇒ **不参与**判据，按原来那条"整幅换掉 ⇒ 停手"走（不可比 ≠ 没变）。
     *
     * ⚠ 它是**局部**量，与 [frameChangedPercent]（整帧）互补：整帧回答"变了多少"、它回答"**我们关心的那个
     * 控件变没变**"（见 [PopupCloseController.anchorKeptPercent] 的说明与 `docs/progress.md` 第 421/422 条）。
     */
    val anchorWindowChangedPercent: Double? = null,
    /**
     * **当前遮挡屏的身份**（2026-09-30 加，真机修）：三屏共用本闭环，而游戏会**背靠背弹两个遮挡屏**
     * （实测 2026-09-30 01:39：新手引导关掉后 **0.46s** 就是新手大厅，整帧变化 82.7%）。
     * 原先唯一的复位条件是"遮挡屏不在"，可在这种场合它**永远不复位** ⇒ 第二屏被"本段已停手"挡住
     * （用户报"新手大厅没关"）。
     *
     * 传 `UiState.name`（**状态未确认时传 null**）⇒ **换了它 = 新的一段**（计数与停手状态一并作废）。
     * 判据安全：状态是滞回确认的（连续 2 次命中），不是单轮抖动。
     */
    val overlayKey: String? = null,
)

/**
 * 上一枪的**观测**结论（只写日志、供事后审计"动作是否生效"，**不驱动任何动作**）。
 *
 * 之所以只观测不决策：见 [PopupCloseController] 类注释——「点击后仍命中」无法区分
 * "没关掉"与"换了一个新弹窗"，因此它不能作为成败判据。
 */
sealed interface PopupVerification {

    /** 本轮没有待观测的点击（没点过、或冻结轮）。 */
    data object None : PopupVerification

    /** 上一枪之后弹窗**已不再命中**（本段共点击 [attempts] 次）。 */
    data class Closed(val attempts: Int) : PopupVerification

    /** 上一枪之后**仍命中**弹窗（[attempts] = 本段已点击次数）——可能是没关掉，也可能是换了新弹窗。 */
    data class StillPresent(val attempts: Int) : PopupVerification
}

/** 本轮动作。 */
sealed interface PopupStep {

    /** 不动作，[note] 说明原因（写日志用；同一原因连续出现时调用方只记一次，避免刷屏）。 */
    data class Skip(val note: String) : PopupStep

    /** 下发一次点击（**是否真的下发由门禁决定**：守护未启动 / 非前台 / 状态未知 / 落点屏外都会被拒并留痕）。 */
    data class Click(val request: ClickRequest, val attempt: Int) : PopupStep

    /**
     * **本段已停手** → 停止点击（换屏 / 弹窗消失后复位）。
     *
     * [reason] = **为什么停手**（2026-09-30 加）：三条停手路径的原因完全不同（次数到顶 / 时长到顶 /
     * 画面整幅换掉），而界面要把这句话**原样给用户看**（顶部标签写「关弹窗已停手」，原因进悬浮窗菜单）
     * ⇒ 只给一个次数不够用：真机那次停手的次数是 **1**，原因其实是"画面整幅换掉"，
     * 旧日志却写"连续 N 次点击后仍命中，判为误匹配"，排障会被直接带偏。
     */
    data class GiveUp(val attempts: Int, val reason: String) : PopupStep
}

/** 一轮决策的完整结果：既说明"上一枪之后的观测"，也说明"这一轮要不要开枪"。 */
data class PopupRoundOutcome(
    val verification: PopupVerification,
    val attempt: Int,
    val step: PopupStep,
)
