package com.example.mastermechanic.auth

/**
 * 授权引导的**顺序口径**（2026-10-02 用户报障后抽出；纯逻辑，JVM 可测）。
 *
 * ## 用户报的是什么（原话）
 *
 * > 点击"去授权与权限"的逻辑有问题，目前是直接拉起采集授权操作，**一同意就跳转到游戏了**，
 * > 此时**无障碍和守护可能还没启动**，又要切换回 app 来启动这两项。
 *
 * ## 根因（两处，都不是"顺序表写错"）
 *
 * [AUTHORIZATION_DISPLAY_ORDER] 早就把采集放最后了（`auth_capture_desc` 里也写着"点它会跳到游戏，
 * 所以放在最后"）—— 缺的是**"什么时候允许直接拉采集"**这条纪律：
 *
 * 1. **「运行」页的「去「授权与权限」」按钮**直接去拉采集授权（应 `requestReauth`）⇒ 用户点的是"去授权页"，
 *    得到的却是系统采集弹窗；一确认画面就交给系统 / 游戏，用户想再启动无障碍与守护就得切回来 ✗；
 * 2. **授权页的自动拉起**（`LaunchedEffect(reauthTick)`，为悬浮窗那行"⟳ 重新授权采集"服务）
 *    只看"有没有请求"，不看"前面几项办完没有"✗。
 *
 * ⇒ 两条纪律，本类是它们的**唯一实现**（免得两处各写一遍、日后又漂移）：
 *
 * - [blockingCapture]：采集前面还有缺项时，**不许**自动 / 一键拉起采集授权，先把人引到缺项上；
 * - [captureIsTheOnlyMissing]：**只剩采集**时才允许"一键建会话"（那时它确实是最后一步，帮用户省一次点击）。
 *
 * ⚠ 不是"禁止用户点采集"：采集那张卡自己的按钮**照旧可用**（用户明确点它就该有反应），
 * 这里管的是**程序替用户拉起**的那些路径。判据用不上任何阈值，只是顺序。
 */
object AuthGuide {

    /** 采集**之前**要办好的项（顺序 = 授权页显示顺序，去掉采集自己）。 */
    val BEFORE_CAPTURE: List<AuthItem> = AUTHORIZATION_DISPLAY_ORDER.filter { it != AuthItem.SCREEN_CAPTURE }

    /**
     * 采集前面**还缺**的项（按显示顺序；空列表 = 可以拉采集了）。
     *
     * 顺序就是界面自上而下的顺序 —— 提示里念出来的是"先办第一件"，用户不需要自己去对应哪张卡。
     */
    fun blockingCapture(statuses: List<AuthStatus>): List<AuthItem> =
        missingInDisplayOrder(statuses).filter { it in BEFORE_CAPTURE }

    /** 下一个该办的项（按显示顺序第一个缺项；全齐 = null）。 */
    fun nextStep(statuses: List<AuthStatus>): AuthItem? = missingInDisplayOrder(statuses).firstOrNull()

    /**
     * **只剩「屏幕采集」没办**（= 前面几项都就绪）⇒ 这时才允许"一键拉起采集"。
     *
     * 为什么单挑这一档：用户点完这一步就会离开 App（画面切到游戏），
     * 所以他来点这一下的**前提**必须是"别的事都办完了" —— 否则他迟早得为别的事切回来。
     */
    fun captureIsTheOnlyMissing(statuses: List<AuthStatus>): Boolean =
        statuses.any { it.item == AuthItem.SCREEN_CAPTURE && it.state == AuthState.MISSING } &&
            blockingCapture(statuses).isEmpty()

    /** 缺项按**显示顺序**返回（与"还缺：A、B"那句读起来的顺序一致）。 */
    private fun missingInDisplayOrder(statuses: List<AuthStatus>): List<AuthItem> {
        val missing = AuthorizationSummary.missingItems(statuses).toSet()
        return AUTHORIZATION_DISPLAY_ORDER.filter { it in missing }
    }
}
