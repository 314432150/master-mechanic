package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.CanvasGeometry
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
    fun mapsClickPointBackToRunningFrameThroughCanvasGeometry() {
        // 标定 200x150；运行帧 400x300（画面区 = 整幅，scale = 2）：同一元素在运行帧里是 2 倍大
        val (calibration, template, patch) = scene(200, 150, blockX = 40, blockY = 50, blockWidth = 14, blockHeight = 11, seed = 431)
        assertEquals(14, template.width) // 模板 = 标定画面上的 14x11 块
        assertEquals(11, template.height)

        val frame = SyntheticImages.background(400, 300, seed = 432)
        val scaled = ByteArray(28 * 22)
        for (row in 0 until 22) {
            for (col in 0 until 28) {
                scaled[row * 28 + col] = patch[(row / 2) * 14 + (col / 2)]
            }
        }
        SyntheticImages.drawPattern(frame, 80, 100, 28, 22, scaled)

        val locator = AnchorLocator(
            anchorsByState = mapOf(UiState.FARM to listOf(anchor("farm_button", template))),
            params = params,
            geometry = CanvasGeometry.of(calibration.width, calibration.height),
        )

        val hits = locator.locate(frame, UiState.FARM)

        assertEquals(1, hits.size)
        // 标定中心 (47, 55.5) → 运行帧 (94, 111) = 画面块 (80,100,28x22) 的中心
        assertEquals(47.0, hits[0].calibrationX, 0.001)
        assertEquals(55.5, hits[0].calibrationY, 0.001)
        assertEquals(94.0, hits[0].frameX, 0.001)
        assertEquals(111.0, hits[0].frameY, 0.001)
    }

    @Test
    fun windowRegionsFollowRunningFrameGeometry() {
        val locator = AnchorLocator(
            anchorsByState = mapOf(
                UiState.FARM to listOf(anchor("farm_button", patternTemplate(10, 8, 441), SearchWindow(0.2, 0.2, 0.4, 0.4))),
            ),
            params = params,
            geometry = CanvasGeometry.of(200, 150),
        )

        assertEquals(emptyList<PixelBounds>(), locator.windowRegions(400, 300, UiState.HALL))
        // 标定窗口 (40,30)-(80,60) → 运行帧 (80,60)-(160,120)，再加采样外扩 2 像素
        val region = locator.windowRegions(400, 300, UiState.FARM).single()
        assertEquals(PixelBounds(78, 58, 162, 122), region)
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
