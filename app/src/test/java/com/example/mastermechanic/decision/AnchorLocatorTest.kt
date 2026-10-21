package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.PixelBounds
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作锚点定位单测（T2-3b）：点击点 = 匹配区域中心、按当前状态启用、不可信不点、
 * 方向归一后坐标回落运行帧，以及构造守卫。
 */
class AnchorLocatorTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)
    private val fullWindow = SearchWindow(0.0, 0.0, 1.0, 1.0)

    private fun anchor(name: String, template: Template, window: SearchWindow = fullWindow): SignalSpec =
        SignalSpec(name, window, listOf(template))

    private fun patternTemplate(width: Int, height: Int, seed: Long): Template =
        Template(width, height, SyntheticImages.pattern(width, height, seed))

    /** 底图 + 一处已知位置的纹理块（返回值含该块模板：模板即从画面上裁剪，标定语义的最小闭环）。 */
    private fun scene(
        width: Int,
        height: Int,
        blockX: Int,
        blockY: Int,
        blockWidth: Int,
        blockHeight: Int,
        seed: Long,
    ): Triple<com.example.mastermechanic.recognition.GrayImage, Template, ByteArray> {
        val image = SyntheticImages.background(width, height, seed = seed)
        val patch = SyntheticImages.pattern(blockWidth, blockHeight, seed = seed + 1)
        SyntheticImages.drawPattern(image, blockX, blockY, blockWidth, blockHeight, patch)
        return Triple(image, SyntheticImages.crop(image, blockX, blockY, blockWidth, blockHeight), patch)
    }

    @Test
    fun returnsClickPointAtMatchedRegionCenter() {
        val (frame, template, _) = scene(200, 150, blockX = 40, blockY = 50, blockWidth = 14, blockHeight = 11, seed = 401)
        val locator = AnchorLocator(mapOf(UiState.FARM to listOf(anchor("farm_button", template))), params)

        val hits = locator.locate(frame, UiState.FARM)

        assertEquals(1, hits.size)
        assertEquals("farm_button", hits[0].name)
        assertEquals(47.0, hits[0].frameX, 0.001) // 40 + 14/2
        assertEquals(55.5, hits[0].frameY, 0.001) // 50 + 11/2
        assertEquals(47.0, hits[0].calibrationX, 0.001)
        assertEquals(55.5, hits[0].calibrationY, 0.001)
        assertTrue("命中分应接近 1.0，实际 ${hits[0].score}", hits[0].score > 0.99)
    }

    @Test
    fun onlyLocatesAnchorsOfCurrentState() {
        // 画面上确实存在该元素：别的状态定位不到，说明"没搜"而不是"没命中"
        val (frame, template, _) = scene(200, 150, blockX = 40, blockY = 50, blockWidth = 14, blockHeight = 11, seed = 411)
        val locator = AnchorLocator(mapOf(UiState.HALL to listOf(anchor("hall_ok", template))), params)

        assertEquals(emptyList<AnchorHit>(), locator.locate(frame, UiState.FARM))
        assertEquals(emptyList<AnchorHit>(), locator.locate(frame, UiState.UNKNOWN))
        assertEquals(emptyList<String>(), locator.available(UiState.FARM))
        assertEquals(listOf("hall_ok"), locator.available(UiState.HALL))
        assertEquals(1, locator.locate(frame, UiState.HALL).size)
    }

    @Test
    fun unreliableMatchYieldsNoClickTarget() {
        // 同画面两处相同元素 → 多处高分 → 不可信：宁可"这次点不了"，也不点偏（红线 7）
        val (frame, template, patch) = scene(200, 150, blockX = 40, blockY = 50, blockWidth = 14, blockHeight = 11, seed = 421)
        SyntheticImages.drawPattern(frame, 140, 50, 14, 11, patch)
        val locator = AnchorLocator(mapOf(UiState.FARM to listOf(anchor("farm_button", template))), params)

        assertEquals(emptyList<AnchorHit>(), locator.locate(frame, UiState.FARM))
    }

    @Test
    fun stopsAtTheFirstHitYetStillScansPastAMiss() {
        // 2026-09-30 提速（真机：活动弹窗名下 5 条 X 锚点、每条 ≈83ms，而关闭流程只用第一条命中的那条）
        // ⇒ `locateFirstHit` 命中即停，第一条之后不再匹配。
        // ⚠ 同时钉住**等价性**："第一条命中的" ≠ "第一条记录" —— 第一条没命中时必须继续往下扫
        //（原实现在那条路上会用第二条；写成"只跑第一条记录"就是行为变更）。
        val (frame, template, _) =
            scene(200, 150, blockX = 40, blockY = 50, blockWidth = 14, blockHeight = 11, seed = 611)
        // 画面上**不存在**的图案 ⇒ 稳稳未命中（不能"从帧里裁一块"当未命中样本：那种会自匹配）
        val absent = patternTemplate(24, 20, 612)

        val firstHits = AnchorLocator(
            mapOf(UiState.FARM to listOf(anchor("first", template), anchor("second", template))),
            params,
        ).locateFirstHit(frame, UiState.FARM)
        assertEquals("第一条就命中 ⇒ 只回它，后面的不再匹配", listOf("first"), firstHits.map { it.name })

        val secondWins = AnchorLocator(
            mapOf(UiState.FARM to listOf(anchor("absent", absent), anchor("second", template))),
            params,
        ).locateFirstHit(frame, UiState.FARM)
        assertEquals("第一条没命中 ⇒ 必须继续扫，回第二条", listOf("second"), secondWins.map { it.name })
    }

    @Test
    fun preferredNamesPicksTheNewStyleAnchorEvenWhenItIsDeclaredSecond() {
        // **C′（2026-10-02）**：一屏可以标**多套样式**（活动弹窗 `activity_popup_e1` / `_e2`），而
        // "换了个新弹窗（控件也换）"时**必须用新那套自己的**关闭控件 ⇒ 调用方把"本轮确认在场的标志名"
        // 作为优先组传进来（配对依据是产物里的现成不变量：同一元素的**标志与锚点同名**）。
        // ⚠ 两组内部都保持**声明顺序** ⇒ 空集合时与旧行为逐字段一致（上一条用例钉着）。
        val (frame, template, _) =
            scene(200, 150, blockX = 40, blockY = 50, blockWidth = 14, blockHeight = 11, seed = 621)
        val locator = AnchorLocator(
            mapOf(
                UiState.ACTIVITY_POPUP to listOf(
                    anchor("activity_popup_e1", template),
                    anchor("activity_popup_e2", template),
                ),
            ),
            params,
        )

        assertEquals(
            "优先组里的那条要排到最前（声明顺序第二条照样拿得到）",
            listOf("activity_popup_e2"),
            locator.locateFirstHit(frame, UiState.ACTIVITY_POPUP, preferredNames = setOf("activity_popup_e2"))
                .map { it.name },
        )
        assertEquals(
            "优先组一条都没命中 ⇒ 落回声明顺序第一条（老行为不变）",
            listOf("activity_popup_e1"),
            locator.locateFirstHit(frame, UiState.ACTIVITY_POPUP, preferredNames = setOf("not_on_screen"))
                .map { it.name },
        )
    }

    @Test
    fun locateStillReturnsEveryHitBecausePatrolLooksAnchorsUpByName() {
        // **回归用例**（2026-09-30 真机事故）：`locate` 曾一度改成"命中即停"，而巡逻流程是**按名字**取
        // 锚点的（`PatrolRunner: input.anchors.firstOrNull { it.name == action.anchor }`）。
        // 启动页同时声明 `launch_switch`（第 4 步）与 `launch_login`（第 6 步）⇒ 只回第一条命中的
        // 那一条时，第 6 步永远"锚点没定位到"，等满 30 秒中止（用户报"换号卡在选完服务器之后"）。
        val (frame, template, _) =
            scene(200, 150, blockX = 40, blockY = 50, blockWidth = 14, blockHeight = 11, seed = 621)

        val hits = AnchorLocator(
            mapOf(
                UiState.LAUNCH_PAGE to listOf(
                    anchor("launch_switch", template),
                    anchor("launch_login", template),
                ),
            ),
            params,
        ).locate(frame, UiState.LAUNCH_PAGE)

        assertEquals(
            "两条都命中就必须两条都在（第 6 步要靠名字找到 launch_login）",
            listOf("launch_switch", "launch_login"),
            hits.map { it.name },
        )
    }

    @Test
    fun windowRegionsFollowRunningFrameGeometry() {
        // T4-6：运行帧与标定帧同几何 ⇒ 窗口比例直接按运行帧尺寸换算（不再经画面区映射）
        val locator = AnchorLocator(
            anchorsByState = mapOf(
                UiState.FARM to listOf(anchor("farm_button", patternTemplate(10, 8, 441), SearchWindow(0.2, 0.2, 0.4, 0.4))),
            ),
            params = params,
        )

        assertEquals(emptyList<PixelBounds>(), locator.windowRegions(400, 300, UiState.HALL))
        // 比例窗口 (0.2,0.2)-(0.4,0.4) 在 200x150 上 = (40,30)-(80,60)、在 400x300 上 = (80,60)-(160,120)
        assertEquals(PixelBounds(40, 30, 80, 60), locator.windowRegions(200, 150, UiState.FARM).single())
        assertEquals(PixelBounds(80, 60, 160, 120), locator.windowRegions(400, 300, UiState.FARM).single())
    }

    @Test
    fun emptyLocatorIsExplicitAndLocatesNothing() {
        val frame = SyntheticImages.background(120, 90, seed = 451)
        val locator = AnchorLocator(emptyMap(), params)

        assertTrue(locator.isEmpty)
        assertFalse(AnchorLocator(mapOf(UiState.FARM to listOf(anchor("a", patternTemplate(8, 6, 452)))), params).isEmpty)
        assertEquals(emptyList<AnchorHit>(), locator.locate(frame, UiState.FARM))
        assertEquals(emptyList<String>(), locator.available(UiState.FARM))
        assertEquals(emptyList<PixelBounds>(), locator.windowRegions(120, 90, UiState.FARM))
    }

    @Test
    fun regionOnlyAnchorIsNotLocatedButItsWindowIsStillGrayed() {
        // 2026-09-22 真机修：只圈区域的大块锚点（真机「列表区域」431x511，窗口 ≈941x1533）
        // 若跟着做模板匹配 → 单轮 ≈1.4e10 次乘加，**真机实录单轮 23.3 秒**、帧线程整段停摆。
        // 本用例钉住两件事：① 它不进定位；② 它的窗口**仍然**要进灰度转换（文字识别的输入就是那块像素）。
        val (frame, template, _) =
            scene(200, 150, blockX = 40, blockY = 50, blockWidth = 14, blockHeight = 11, seed = 471)
        val locator = AnchorLocator(
            anchorsByState = mapOf(
                UiState.FRIEND_LIST to listOf(
                    anchor("friend_visit", template),
                    anchor("friend_list_area", patternTemplate(60, 50, 472)),
                ),
            ),
            params = params,
            nonLocatable = setOf("friend_list_area"),
        )

        assertEquals(listOf("friend_visit"), locator.available(UiState.FRIEND_LIST))
        assertEquals(listOf("friend_visit"), locator.locate(frame, UiState.FRIEND_LIST).map { it.name })
        assertEquals(
            "只圈区域的锚点窗口仍要灰度化（否则文字识别读到全零像素）",
            2,
            locator.windowRegions(200, 150, UiState.FRIEND_LIST).size,
        )
        assertFalse("还有可点的锚点 → 不是「没标锚点」", locator.isEmpty)
    }

    @Test
    fun stateWithOnlyRegionAnchorsKeepsItsWindowButHasNoClickTarget() {
        // 一个状态只声明了"区域型"锚点时：没有任何可点的东西（可用于日志说清"没标锚点"），
        // 但灰度窗口不能跟着消失——OCR 的输入区就是它。
        val locator = AnchorLocator(
            anchorsByState = mapOf(
                UiState.FRIEND_LIST to listOf(anchor("friend_list_area", patternTemplate(30, 20, 481))),
            ),
            params = params,
            nonLocatable = setOf("friend_list_area"),
        )

        assertTrue(locator.isEmpty)
        assertEquals(emptyList<String>(), locator.available(UiState.FRIEND_LIST))
        assertEquals(1, locator.windowRegions(200, 150, UiState.FRIEND_LIST).size)
    }

    @Test
    fun iconColumnOutsideListAreaStillLocates() {
        val (frame, template, _) = scene(200, 150, 162, 60, 14, 11, seed = 491)
        val win = SearchWindow(160.0 / 200, 0.0, 182.0 / 200, 1.0)
        val locator = AnchorLocator(mapOf(UiState.FRIEND_LIST to listOf(anchor("friend_visit", template, win))), params)
        val hit = locator.locateWithin(frame, UiState.FRIEND_LIST, "friend_visit", PixelBounds(40, 55, 120, 80))
            ?: error("行带与图标列相交，应命中")
        assertEquals(169.0, hit.frameX, 0.6)
        assertEquals(65.5, hit.frameY, 0.6)
    }

    @Test
    fun windowInFrameFollowsFrameSize() {
        // 第 9 步要给 OCR 圈输入范围（T4-3f/g）：窗口比例按**运行帧尺寸**换算。
        // T4-6 之后运行帧与标定帧同几何，不再经画面区映射（原先那条路径已整体拆除）。
        val locator = AnchorLocator(
            anchorsByState = mapOf(
                UiState.FRIEND_LIST to listOf(
                    anchor("friend_list_area", patternTemplate(10, 8, 501), SearchWindow(0.2, 0.1, 0.6, 0.5)),
                ),
            ),
            params = params,
        )
        // (0.2,0.1)-(0.6,0.5)：200x150 上 = (40,15)-(120,75)；400x300 上 = (80,30)-(240,150)
        assertEquals(
            PixelBounds(40, 15, 120, 75),
            locator.windowInFrame(200, 150, UiState.FRIEND_LIST, "friend_list_area"),
        )
        assertEquals(
            PixelBounds(80, 30, 240, 150),
            locator.windowInFrame(400, 300, UiState.FRIEND_LIST, "friend_list_area"),
        )

        // 不存在的锚点 → null
        assertEquals(null, locator.windowInFrame(400, 300, UiState.FRIEND_LIST, "nonexistent"))
        // 不存在的状态 → null
        assertEquals(null, locator.windowInFrame(400, 300, UiState.FARM, "friend_list_area"))
    }

    fun rejectsUnknownStateAndMixedTemplateSizes() {
        // 「未知」不能声明锚点：状态未知 → 零点击（红线 1）
        assertThrows(IllegalArgumentException::class.java) {
            AnchorLocator(mapOf(UiState.UNKNOWN to listOf(anchor("x", patternTemplate(10, 8, 461)))), params)
        }
        // 同一条记录里多个样式模板尺寸不一致 → 中心无从确定（应拆成多条记录）
        assertThrows(IllegalArgumentException::class.java) {
            AnchorLocator(
                mapOf(
                    UiState.FARM to listOf(
                        SignalSpec("x", fullWindow, listOf(patternTemplate(10, 8, 462), patternTemplate(12, 9, 463))),
                    ),
                ),
                params,
            )
        }
        // 同状态锚点名重复
        assertThrows(IllegalArgumentException::class.java) {
            AnchorLocator(
                mapOf(
                    UiState.FARM to listOf(
                        anchor("x", patternTemplate(10, 8, 464)),
                        anchor("x", patternTemplate(10, 8, 465)),
                    ),
                ),
                params,
            )
        }
    }
}
