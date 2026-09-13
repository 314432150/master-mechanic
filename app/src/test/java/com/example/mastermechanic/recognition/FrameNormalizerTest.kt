package com.example.mastermechanic.recognition

import com.example.mastermechanic.decision.RecognitionLoop
import com.example.mastermechanic.decision.SignalStateMapping
import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * 帧方向归一单测（T1-11c）：以「同一画面的竖 / 横两种画布帧」为样本，验证
 * ① 同几何走零拷贝快路径；② 转置画布归一后与标定几何判定一致（结论 / 位置）；
 * ③ 转屏过渡帧（内容被 90° 旋转满幅）落为未命中——不误报（研究文档 §9）。
 *
 * 合成画面尺寸取 40×88 / 88×40（长宽比 2.2，与真机画布同比例），测试侧独立渲染"设备呈现"，
 * 被测对象为生产路径（[CanvasGeometry] + [FrameNormalizer] + [RecognitionLoop]）。
 */
class FrameNormalizerTest {

    private val canvasWidth = 40
    private val canvasHeight = 88
    private val frameWidth = 88
    private val frameHeight = 40
    private val geometry = CanvasGeometry.of(canvasWidth, canvasHeight)
    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)
    private val window = SearchWindow(0.0, 0.35, 1.0, 0.65)

    /** 画面原图：长宽比 88:40 = 2.2（与标定画布长宽比一致）。 */
    private fun picture(): GrayImage {
        val image = SyntheticImages.background(frameWidth, frameHeight, seed = 301)
        SyntheticImages.drawPattern(image, 30, 12, 20, 10, SyntheticImages.pattern(20, 10, seed = 302))
        return image
    }

    /** 把画面按「等比缩放居中」渲染进竖画布（测试侧采样，模拟设备呈现，不用被测实现）。 */
    private fun renderIntoPortraitCanvas(picture: GrayImage): GrayImage {
        val area = geometry.calibrationArea
        val out = ByteArray(canvasWidth * canvasHeight)
        for (y in area.y0 until area.y1) {
            for (x in area.x0 until area.x1) {
                val sx = (x - area.x0 + 0.5) * picture.width.toDouble() / area.width - 0.5
                val sy = (y - area.y0 + 0.5) * picture.height.toDouble() / area.height - 0.5
                out[y * canvasWidth + x] = bilinear(picture, sx, sy).toByte()
            }
        }
        return GrayImage(canvasWidth, canvasHeight, out)
    }

    private class Fixture(val portrait: GrayImage, val landscape: GrayImage, val loop: RecognitionLoop)

    private fun fixture(): Fixture {
        val picture = picture()
        val portrait = renderIntoPortraitCanvas(picture)
        val template = SyntheticImages.crop(portrait, 15, 41, 7, 3) // 产物口径：模板从标定帧裁剪
        val loop = RecognitionLoop(
            signals = listOf(SignalSpec("启动标志", window, listOf(template))),
            params = params,
            mapping = SignalStateMapping(
                listOf(SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("启动标志"))),
            ),
            geometry = geometry,
        )
        return Fixture(portrait, picture, loop)
    }

    @Test
    fun sameGeometryIsZeroCopyFastPath() {
        val fixture = fixture()
        val mapping = geometry.mappingFor(canvasWidth, canvasHeight)
        assertTrue(mapping.isIdentity)
        val regions = listOf(PixelBounds(0, 30, canvasWidth, 58))
        assertSame(fixture.portrait, FrameNormalizer.normalize(fixture.portrait, mapping, regions))
    }

    @Test
    fun landscapeFrameMatchesCalibrationGeometry() {
        val fixture = fixture()
        val onPortrait = fixture.loop.process(fixture.portrait, isForeground = true)
        assertEquals(UiState.UNKNOWN, onPortrait.state) // 首轮命中 1 次：滞回未进入
        val onLandscape = fixture.loop.process(fixture.landscape, isForeground = true)

        val portraitRecord = onPortrait.records.first()
        val landscapeRecord = onLandscape.records.first()
        assertTrue("竖画布应命中", portraitRecord.matched)
        assertTrue("横画布归一后应命中", landscapeRecord.matched)
        assertEquals(Verdict.MATCHED, landscapeRecord.verdict)
        // 判定一致性：结论相同、位置一致（±1 像素）、分数仍显著高于命中线
        assertPositionNear(portraitRecord.best!!.x, landscapeRecord.best!!.x)
        assertPositionNear(portraitRecord.best!!.y, landscapeRecord.best!!.y)
        assertTrue("归一后分数应仍高于命中线：${landscapeRecord.best!!.score}", landscapeRecord.best!!.score > 0.85)
        // 横画布帧连续两轮命中即进入「启动页」（滞回在归一后的帧上同样成立）
        assertEquals(UiState.LAUNCH_PAGE, onLandscape.state)
    }

    @Test
    fun rotatedTransitionFrameDoesNotMatch() {
        val fixture = fixture()
        // 转屏过渡形态：画面被 90° 旋转后铺满标定画布（研究文档 §9）
        val rotated = rotate90Clockwise(fixture.landscape)
        assertEquals(canvasWidth, rotated.width)
        assertEquals(canvasHeight, rotated.height)
        val record = fixture.loop.process(rotated, isForeground = true).records.first()
        assertFalse("过渡帧不应命中（不误报）", record.matched)
        assertTrue(record.best == null || record.best!!.score < 0.85)
    }

    @Test
    fun windowRegionsMapIntoTransposedFrame() {
        val fixture = fixture()
        val regions = fixture.loop.windowRegions(frameWidth, frameHeight)
        assertEquals(1, regions.size)
        // 标定窗口（x 0..40 / y 30..58）按 ×2.2 映射后覆盖整幅 88×40 帧（含采样外扩、裁剪到帧内）
        assertEquals(PixelBounds(0, 0, 88, 40), regions.first())
    }

    @Test
    fun normalizedFrameIsDeterministic() {
        val fixture = fixture()
        val mapping = geometry.mappingFor(frameWidth, frameHeight)
        val regions = fixture.loop.windowRegions(frameWidth, frameHeight)
        val first = FrameNormalizer.normalize(fixture.landscape, mapping, regions)
        val second = FrameNormalizer.normalize(fixture.landscape, mapping, regions)
        assertTrue(first.pixels.contentEquals(second.pixels))
    }

    /** 双线性采样（测试侧参考实现，边界钳制）。 */
    private fun bilinear(image: GrayImage, x: Double, y: Double): Int {
        val xFloor = floor(x).toInt()
        val yFloor = floor(y).toInt()
        val fx = x - xFloor
        val fy = y - yFloor
        val x0 = xFloor.coerceIn(0, image.width - 1)
        val y0 = yFloor.coerceIn(0, image.height - 1)
        val x1 = (xFloor + 1).coerceIn(0, image.width - 1)
        val y1 = (yFloor + 1).coerceIn(0, image.height - 1)
        val p00 = image.pixels[y0 * image.width + x0].toInt() and 0xFF
        val p10 = image.pixels[y0 * image.width + x1].toInt() and 0xFF
        val p01 = image.pixels[y1 * image.width + x0].toInt() and 0xFF
        val p11 = image.pixels[y1 * image.width + x1].toInt() and 0xFF
        val top = p00 + (p10 - p00) * fx
        val bottom = p01 + (p11 - p01) * fx
        return floor(top + (bottom - top) * fy + 0.5).toInt().coerceIn(0, 255)
    }

    private fun rotate90Clockwise(image: GrayImage): GrayImage {
        val pixels = ByteArray(image.width * image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                pixels[x * image.height + (image.height - 1 - y)] = image.pixels[y * image.width + x]
            }
        }
        return GrayImage(image.height, image.width, pixels)
    }

    /** 位置一致性：容差 1 像素（归一化重采样的量化误差）。 */
    private fun assertPositionNear(expected: Int, actual: Int) {
        assertTrue("期望 $expected ±1，实际 $actual", abs(expected - actual) <= 1)
    }
}
