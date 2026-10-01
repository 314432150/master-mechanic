package com.example.mastermechanic.patrol

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.PixelBounds
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 好友列表试读探针单测（2026-09-23）：探针的回答必须是**生产同一套口径**，
 * 否则"试读通过"不能说明跑号会通过。这里钉的就是这件事 ——
 * 区域推导与匹配都直接调用生产那两个入口（`AnchorLocator.windowInFrame` / `NameLocating.locateFriend`），
 * 探针自己只负责"把它们串起来 + 说清为什么没串成"。
 */
class FriendListOcrProbeTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)

    private fun template(seed: Long): Template =
        Template(10, 8, SyntheticImages.pattern(10, 8, seed))

    /** 列表区域 = 画面中部一大片；名称列 = 其中偏左的一条竖列（真机口径：只取左右边界）。 */
    private fun product(
        listArea: SearchWindow? = SearchWindow(0.1, 0.1, 0.9, 0.9),
        nameColumn: SearchWindow? = SearchWindow(0.3, 0.1, 0.7, 0.9),
    ): CalibrationData {
        val signals = mutableListOf<CalibrationData.SignalEntry>()
        val anchorNames = mutableListOf<String>()
        if (listArea != null) {
            signals += CalibrationData.SignalEntry(
                "friend_list_area",
                listArea,
                listOf(template(1)),
                SignalRole.ANCHOR,
                purpose = PatrolAnchors.FRIEND_LIST_AREA,
            )
            anchorNames += "friend_list_area"
        }
        if (nameColumn != null) {
            signals += CalibrationData.SignalEntry(
                "friend_list_name_column",
                nameColumn,
                listOf(template(2)),
                SignalRole.ANCHOR,
                purpose = PatrolAnchors.FRIEND_LIST_NAME_COLUMN,
            )
            anchorNames += "friend_list_name_column"
        }
        // 至少留一条锚点，免得产物落在"空产物"这条无关分支上
        signals += CalibrationData.SignalEntry(
            "friend_visit",
            SearchWindow(0.1, 0.1, 0.2, 0.3),
            listOf(template(3)),
            SignalRole.ANCHOR,
            purpose = PatrolAnchors.FRIEND_VISIT,
        )
        anchorNames += "friend_visit"
        return CalibrationData(
            100,
            80,
            params,
            signals,
            listOf(CalibrationData.StateRule(UiState.FRIEND_LIST, emptyList(), anchorNames)),
        )
    }

    private fun okRegion(region: FriendListOcrProbe.Region): FriendListOcrProbe.Region.Ok {
        assertTrue("这组输入应当能推出区域，实际 $region", region is FriendListOcrProbe.Region.Ok)
        return region as FriendListOcrProbe.Region.Ok
    }

    // ---------------------------------------------------------------- 区域：与跑号第 9 步同一条推导

    @Test
    fun regionIsTheListAreaClippedToTheNameColumn() {
        // 帧尺寸 == 标定尺寸（帧池里的帧就是标定帧）⇒ 直接按帧尺寸换算，取整不放大
        // （T4-6 前这里还要经 CanvasMapping.sourceBounds 做一次采样外扩 ±2，那条路径已随方向归一拆除）
        val region = okRegion(FriendListOcrProbe.region(product(), 100, 80))

        assertTrue(region.clippedByColumn)
        assertEquals("x 被裁到名称列（30…70）、纵向取列表区域（8…72）", PixelBounds(30, 8, 70, 72), region.bounds)
    }

    @Test
    fun regionFallsBackToTheWholeListAreaWithoutTheColumn() {
        // 没框「好友名称列」⇒ 退回整片列表区域（纵向仍是列表区域），并由 clippedByColumn=false 明说
        val region = okRegion(FriendListOcrProbe.region(product(nameColumn = null), 100, 80))

        assertFalse("没框名称列就要如实说没裁列", region.clippedByColumn)
        assertEquals(PixelBounds(10, 8, 90, 72), region.bounds)
        assertNull("没框过就该如实报 null（报告据此显示「没框过」）", region.nameColumn)
    }

    @Test
    fun regionReportsBothOriginalWindowsForDiagnosis() {
        // 只看交集分不清"是哪个框不对" —— 2026-09-23 真机踩过：名称列被框成"屏幕中线 → 右边缘"，
        // 交集等于没裁，只报交集会让人误判成"代码没生效"。两个原始框都要能报出来。
        val region = okRegion(FriendListOcrProbe.region(product(), 100, 80))

        assertEquals(PixelBounds(10, 8, 90, 72), region.listArea)
        assertEquals(PixelBounds(30, 8, 70, 72), region.nameColumn)
    }

    @Test
    fun regionReportsMissingWhenTheListAreaIsNotCalibrated() {
        // 没标「列表区域」= 没标定，不是"读不出来"：两种原因在界面上要给出不同的下一步
        val data = product(listArea = null)

        assertEquals(
            FriendListOcrProbe.Region.Missing(FriendListOcrProbe.Reason.NO_LIST_AREA),
            FriendListOcrProbe.region(data, 100, 80),
        )
    }

    @Test
    fun regionReportsEmptyWhenTheColumnDoesNotOverlapTheArea() {
        // 名称列框到列表区域**右外侧**：横向没有交集 ⇒ 如实报"两个框对不上"，
        // 绝不退回整片区域去凑（那等于猜，红线 3）
        val region = FriendListOcrProbe.region(
            product(nameColumn = SearchWindow(0.95, 0.1, 1.0, 0.9)),
            100,
            80,
        )

        assertEquals(
            FriendListOcrProbe.Region.Missing(FriendListOcrProbe.Reason.EMPTY_AFTER_CLIP),
            region,
        )
    }

    // ---------------------------------------------------------------- 匹配：直接吃生产那套"取括号内备注 + 全等 + 唯一"

    @Test
    fun matchFindsTheRowWhoseRemarkEqualsTheConfiguredName() {
        val candidates = listOf(
            NameCandidate("克克雨儿(阿娜雅) 荣耀黄金 离线", 10, 100, 200, 20),
            NameCandidate("小雪(表妹) 尊贵铂金 在线", 10, 130, 120, 20),
        )

        val hit = FriendListOcrProbe.match("阿娜雅", candidates)

        assertTrue("括号里的备注 == 配置的名字 ⇒ 命中", hit.found)
    }

    @Test
    fun matchStopsWhenTheNicknameIsConfusedWithTheRemark() {
        // 真机实录形态：引擎把噪声插进**昵称**内部（`立Boss-~~喵` vs 配置的 `Boss~~喵`）。
        // **2026-09-24 口径**：比的是**括号里的备注名**（这里 `阿娜雅`）⇒ 昵称压根不参与比较，
        // 结果是"未命中 ⇒ 停下"（红线 3），绝不挑"最像的"那一行。
        val candidates = listOf(NameCandidate("立Boss-~~喵(阿娜雅) 荣耀黄金 离线", 10, 100, 200, 20))

        val hit = FriendListOcrProbe.match("Boss~~喵", candidates)

        assertFalse("昵称不参与匹配 ⇒ 未命中，不挑最像的", hit.found)
    }

    @Test
    fun matchReportsWhyWhenTwoRowsLookAlike() {
        // **备注重名** = "分不清是哪一个"，必须如实报不唯一（不许挑一个），原因里带上面有几条
        val candidates = listOf(
            NameCandidate("克克雨儿(阿娜雅) 荣耀黄金 离线", 10, 100, 200, 20),
            NameCandidate("克克洛儿(阿娜雅) 尊贵铂金 在线", 10, 130, 200, 20),
        )

        val miss = FriendListOcrProbe.match("阿娜雅", candidates)

        assertFalse(miss.found)
        assertTrue("要说清是「有几个」，而不是「没找到」：${miss.detail}", miss.detail.contains("2"))
    }

    @Test
    fun matchAllKeepsTheListOrder() {
        // 界面上是按好友清单的顺序逐条对账的：顺序被打乱就没法一条条核对
        val candidates = listOf(NameCandidate("克克雨儿(阿娜雅) 荣耀黄金 离线", 10, 100, 200, 20))

        val results = FriendListOcrProbe.matchAll(listOf("小雪", "阿娜雅"), candidates)

        assertEquals(listOf("小雪", "阿娜雅"), results.map { it.name })
        assertEquals(listOf(false, true), results.map { it.found })
    }

    // ---------------------------------------------------------------- 候选：匹配真正吃到的那几条

    @Test
    fun candidateTextsCollapsesTheWholeRowAndTheInnerWord() {
        // 引擎对同一行会给两级结果（整行 + 行内词）⇒ 只留框更小的那个（行内词），
        // 否则同一个名字会被数两次、把"唯一"判成"不唯一"
        val candidates = listOf(
            NameCandidate("克克雨儿(阿娜雅) 荣耀黄金 离线", 10, 100, 200, 20),
            NameCandidate("克克雨儿(阿娜雅)", 10, 100, 90, 20),
        )

        assertEquals(listOf("克克雨儿(阿娜雅)"), FriendListOcrProbe.candidateTexts(candidates))
    }

    @Test
    fun candidateTextsKeepsDifferentRowsApart() {
        // 不同行同名**不去重**（那正是"分不清是哪一个"），两行都留着
        val candidates = listOf(
            NameCandidate("阿娜雅", 10, 100, 90, 20),
            NameCandidate("阿娜雅", 10, 130, 90, 20),
        )

        assertEquals(2, FriendListOcrProbe.candidateTexts(candidates).size)
    }

    // ---------------------------------------------------------------- 备注名（括号内容）

    @Test
    fun remarkIsTakenFromTheParentheses() {
        // 真机形态 `好友名称(备注名称)`：备注名由用户自己填 ⇒ 可以刻意避开生僻字符
        assertEquals("阿娜雅", FriendListOcrProbe.remarkIn("立Boss~~喵(阿娜雅)"))
        assertEquals("许杨", FriendListOcrProbe.remarkIn("0细细s听(许杨)"))
    }

    @Test
    fun remarkToleratesBrokenBrackets() {
        // 引擎会把括号读坏：全角、缺右括号、内容里带空格 —— 都按"能取就取"处理
        assertEquals("阿娜雅", FriendListOcrProbe.remarkIn("波星月晚.（阿娜雅）"))
        assertEquals("许杨", FriendListOcrProbe.remarkIn("0细细s听(许杨"))
        assertEquals("阿娜雅", FriendListOcrProbe.remarkIn("立Boss( 阿 娜 雅 )"))
    }

    @Test
    fun remarkTakesTheLastGroupAndReturnsNullWithoutOne() {
        // 昵称自己可能带括号，备注总在最后那段；没有括号/空括号 → null（不猜）
        assertEquals("备注", FriendListOcrProbe.remarkIn("昵称(a)的农场(备注)"))
        assertNull(FriendListOcrProbe.remarkIn("更多 拜访"))
        assertNull(FriendListOcrProbe.remarkIn("立Boss~~喵()"))
    }

    // ---------------------------------------------------------------- 区域像素质检（"读不到"分哪一类）

    @Test
    fun statsSeparatesAFlatFrameFromAContentfulOne() {
        // 纯色（= 这一帧这块地方是空的，多半是采集会话与屏幕方向不匹配）：标准差≈0
        val flat = GrayImage(20, 10, ByteArray(200) { 12.toByte() })
        val flatStats = FriendListOcrProbe.stats(flat, PixelBounds(0, 0, 20, 10))
        assertEquals(12.0, flatStats.mean, 0.01)
        assertEquals(0.0, flatStats.stdDev, 0.01)
        assertTrue("全平必须被判出来（否则「读不到」没法与「字太小」分开）", flatStats.flat)

        // 有内容（左半 20 / 右半 200）：标准差远高于阈值
        val content = GrayImage(20, 10, ByteArray(200) { i -> if (i % 20 < 10) 20.toByte() else 200.toByte() })
        val contentStats = FriendListOcrProbe.stats(content, PixelBounds(0, 0, 20, 10))
        assertEquals(110.0, contentStats.mean, 0.5)
        assertTrue("有内容时标准差应当明显：${contentStats.stdDev}", contentStats.stdDev > 50)
        assertTrue("不能把有内容的区域也报成全平", !contentStats.flat)
    }

    @Test
    fun statsClampsBoundsToTheFrame() {
        // 越界区域按帧界裁剪（防御性：区域理论上已被裁过，但统计不该因为越界而算错或抛异常）
        val image = GrayImage(4, 4, ByteArray(16) { 7.toByte() })
        val stats = FriendListOcrProbe.stats(image, PixelBounds(-10, -10, 100, 100))

        assertEquals(7.0, stats.mean, 0.01)
        assertEquals(0.0, stats.stdDev, 0.01)
    }

    @Test
    fun theRemarkIsMatchedEvenWhenTheNicknameIsMisread() {
        // **备注名这条路对"昵称读歪"天然免疫**：2026-09-24 起比的就是括号里那一段，
        // 昵称里读错什么字都不参与比较（真机 `细细๓听` 的 `๓` 被读成 `s`，备注名 `许杨` 照旧命中）
        val candidates = listOf(NameCandidate("0细细s听(许杨)", 10, 100, 200, 20))

        val hit = FriendListOcrProbe.match("许杨", candidates)

        assertTrue("昵称那半截被读错了（s ≠ ๓），但备注名照旧命中", hit.found)
    }

    // 画面区的三条用例（`pictureArea` / `isInside`）随 T4-6 拆除画布方向归一一起删除：
    // "画面只占帧内一条带、之外是留白"是"帧与屏幕几何不一致"的产物，转屏由 VirtualDisplay.resize()
    // 跟进后帧恒与屏幕同向同尺寸、画面铺满整帧 ⇒ 该失败模式从根上消失。
    // 仍保留的 [FriendListOcrProbe.stats]（区域/整帧质检）继续负责把"读到 0 行"分成框错 / 帧黑两类。
}
