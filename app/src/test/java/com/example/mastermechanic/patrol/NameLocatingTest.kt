package com.example.mastermechanic.patrol

import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.PixelBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名称定位的编排（M4-T4-3 真机部分的纯逻辑部分）。
 *
 * - **区服**（第 5 步）：钉住 FR-04 硬性要求 3 —— **名称不唯一、或与配置不一致时视为未找到**
 *   （这一条松了，后果就是进错区服）；
 * - **好友**（第 9 步）：命中判定同上（取括号内备注 + 全等），但**多个完全同名时取最上面那一个**
 *   （2026-09-30 用户口径，`requirements.md`「口径修订十」）—— 真机原样是"同一好友的多个小号共用一个备注"。
 *
 * 两条路径的字段口径（2026-09-24 统一）：**去空白 → 取语义字段 → 全等** ——
 * 区服取「区」之后那一段（[NameLocating.locateRealm]），好友取括号里的备注名（[NameLocating.locateFriend]）。
 */
class NameLocatingTest {

    private fun candidate(text: String, x: Int = 100, y: Int = 200, width: Int = 90, height: Int = 15) =
        NameCandidate(text = text, x = x, y = y, width = width, height = height)

    @Test
    fun theRealmNameAfterTheAreaMarkerIsWhatIsCompared() {
        // 屏幕行 `微信 55区 龙腾争锋` ⇒ 名字段 = `龙腾争锋`：配这个名字命中；
        // 配它的**前缀** `龙腾` 不命中（全等口径，不靠"包含"）
        val page = listOf(listOf(candidate("微信 55区 龙腾争锋")))

        assertTrue(NameLocating.locateRealm("龙腾争锋", page) is NameLocator.Result.Found)
        assertTrue(NameLocating.locateRealm("龙腾", page) is NameLocator.Result.NotFound)
    }

    @Test
    fun theLineAndTheWordOnTheSameRowCountAsOneHit() {
        // 真实场景：识别会同时给出**整行**与**行内的词**，两者取完字段都是同一个区服名
        // —— 同一行只算一个，取更小的框（点击点更贴合名字本身）
        val found = NameLocating.locateRealm(
            "龙腾争锋",
            listOf(
                listOf(
                    candidate("微信 55区 龙腾争锋", x = 100, y = 200, width = 300, height = 15), // 整行（宽）
                    candidate("龙腾争锋", x = 220, y = 202, width = 90, height = 14), // 行内的词（窄）
                ),
            ),
        )

        val hit = requireNotNull(found as? NameLocator.Result.Found) { "同行重复应合并成一个：$found" }
        assertEquals("取更小的那个框：点击点更贴合名字本身", 265.0, hit.payload.centerX, 0.0)
    }

    @Test
    fun theSameNameOnTwoRowsIsStillAmbiguous() {
        // 同名出现在**两行**（不是同一行的两种粒度）→ 仍然不唯一（滚动翻屏后重名是常态）
        val found = NameLocating.locateRealm(
            "龙腾争锋",
            listOf(
                listOf(
                    candidate("微信 55区 龙腾争锋", y = 200),
                    candidate("微信 56区 龙腾争锋", y = 400),
                ),
            ),
        )

        assertTrue("不同行的同名仍算不唯一：$found", found is NameLocator.Result.NotFound)
        assertTrue((found as NameLocator.Result.NotFound).detail.contains("2 个"))
    }

    @Test
    fun theClickPointIsTheNameBoxCenter() {
        // 第 5 步点的是**名字本身**（识别给出的框中心）—— 这一条也是 serverPlanOf 的落点依据
        val found = NameLocating.locateRealm("龙腾争锋", listOf(listOf(candidate("微信 55区 龙腾争锋"))))

        val hit = requireNotNull(found as? NameLocator.Result.Found)
        assertEquals("点击点 = 名字框中心", 145.0, hit.payload.centerX, 0.0)
        assertEquals(207.5, hit.payload.centerY, 0.0)
    }

    @Test
    fun zeroHitsAndSeveralHitsAreBothNotFound() {
        val none = NameLocating.locateRealm("龙腾争锋", listOf(listOf(candidate("微信 55区 海韵之泪"))))
        assertTrue("一个都没有 → 未找到", none is NameLocator.Result.NotFound)
        assertTrue((none as NameLocator.Result.NotFound).detail.contains("没找到"))

        val many = NameLocating.locateRealm(
            "龙腾争锋",
            listOf(
                listOf(
                    candidate("微信 55区 龙腾争锋"),
                    candidate("微信 56区 龙腾争锋", y = 400),
                ),
            ),
        )
        assertTrue("有多个 → 未找到（点错区服比找不到代价大得多）", many is NameLocator.Result.NotFound)
        assertTrue((many as NameLocator.Result.NotFound).detail.contains("2"))
    }

    @Test
    fun pagesAreMergedBeforeJudgingUniqueness() {
        // 滚动翻屏：第 1 屏没看到、第 2 屏看到 → 找到
        val acrossPages = NameLocating.locateRealm(
            "龙腾争锋",
            listOf(listOf(candidate("微信 55区 海韵之泪")), listOf(candidate("微信 56区 龙腾争锋"))),
        )
        assertTrue(acrossPages is NameLocator.Result.Found)

        // 同一名字在两屏各出现一次 → **仍然不唯一**（单屏唯一 ≠ 全局唯一）
        val duplicated = NameLocating.locateRealm(
            "龙腾争锋",
            listOf(
                listOf(candidate("微信 55区 龙腾争锋")),
                listOf(candidate("微信 56区 龙腾争锋", y = 400)),
            ),
        )
        assertTrue("跨屏重名照样算不唯一：${duplicated}", duplicated is NameLocator.Result.NotFound)
    }

    @Test
    fun theRowBandSpansTheWholeRowSoTheVisitIconIsInsideIt() {
        // 2026-09-21 用户问"要不要把拜访图标列框进列表区域"时发现的自查疏漏：
        // 旧实现把行带横向圈在**名字框**上（宽仅 90px），而拜访图标在**行尾**（隔着整行）
        // ⇒ 在带里永远找不到图标。横向必须按**整行**（列表区域的左右边界）给。
        val band = NameLocating.rowBandOf(
            candidate("忽逢山海", y = 300),
            x0 = 40,
            x1 = 1400,
            frameHeight = 3168,
        )

        assertEquals("左边界 = 整行左（区域左）", 40, band.x0)
        assertEquals("右边界 = 整行右（区域右）—— 图标落在这一带内", 1400, band.x1)
        assertEquals("上扩 1 倍字高", 285, band.y0)
        assertEquals("下扩 2 倍字高（名字行 + 角色信息行）", 330, band.y1)

        // 越界一律夹回（不串到别处）；横向给反了也不能变成负宽度
        val clipped = NameLocating.rowBandOf(
            candidate("忽逢山海", y = 5),
            x0 = -5,
            x1 = -1,
            frameHeight = 3168,
        )
        assertEquals("贴帧顶夹回 0", 0, clipped.y0)
        assertEquals(0, clipped.x0)
        assertEquals("右边界不小于左边界", 0, clipped.x1)
    }

    @Test
    fun theFriendListNeedsTheVisitIconAnchor() {
        // 第 9 步：按名字找到那一行之后，点的是行尾的「拜访」图标（不是名字本身）——
        // 这个锚点不属于步骤点击链（第 5 / 9 步不进 actions），单列在 PatrolAnchors.nameLocatingAnchors
        assertEquals(
            PatrolAnchors.FRIEND_VISIT,
            PatrolAnchors.nameLocatingAnchors[com.example.mastermechanic.decision.UiState.FRIEND_LIST],
        )
        assertEquals("第 5 步点名字本身，不需要辅助锚点", null, PatrolAnchors.nameLocatingAnchors[
            com.example.mastermechanic.decision.UiState.SERVER_SELECT,
        ])
    }

    // ---------- 好友（第 9 步）：取括号内备注 + 全等（2026-09-24 用户拍板） ----------

    @Test
    fun theFriendRowIsMatchedByTheRemarkInsideTheParentheses() {
        // 真机 OCR 原文（2026-09-22）：读到 `克克洛儿(阿娜雅)`，而目标名配的是括号里的备注 `阿娜雅`
        val found = NameLocating.locateFriend(
            "阿娜雅",
            listOf(
                listOf(
                    candidate("克克洛儿(阿娜雅)"),
                    candidate("搜好友", y = 300),
                    candidate("拿取提醒", y = 500),
                ),
            ),
        )

        val hit = requireNotNull(found as? NameLocator.Result.Found) { "应命中括号里的名字：$found" }
        assertEquals("克克洛儿(阿娜雅)", hit.payload.text)
    }

    @Test
    fun aFullWidthParenthesisWorksToo() {
        // OCR 可能给全角括号（取值规则认两种括号）
        val found = NameLocating.locateFriend("阿娜雅", listOf(listOf(candidate("克克洛儿（阿娜雅）"))))

        assertTrue("全角括号同样能命中：$found", found is NameLocator.Result.Found)
    }

    @Test
    fun theNicknameOutsideTheParenthesesIsNoLongerAHit() {
        // **2026-09-24 的口径变更**：配的名字 = 游戏中的**备注名** ⇒ 只比括号里那一段。
        // 拿括号前的昵称去配**不再命中**（宁可停下让用户改配置，也不靠"包含"凑一个命中）
        val found = NameLocating.locateFriend("克克洛儿", listOf(listOf(candidate("克克洛儿(阿娜雅)"))))

        assertTrue("昵称不在括号里 ⇒ 未找到：$found", found is NameLocator.Result.NotFound)
    }

    @Test
    fun aMisreadCharacterMeansNotFoundNotTheWrongRow() {
        // 反向护栏：另一位好友的备注被读成「阿哪雅」（OCR 认错字）⇒ 字段与目标不等 ⇒ 未找到。
        // 判别力来自全等：差一个字就不命中（红线 3 没破）
        val found = NameLocating.locateFriend(
            "阿娜雅",
            listOf(listOf(candidate("新克克雨儿(阿哪雅)"), candidate("价克克洛儿(阿哪雅)"))),
        )

        assertTrue("差一个字 ⇒ 未找到：$found", found is NameLocator.Result.NotFound)
    }

    @Test
    fun nonNameEntriesAreNotPartOfTheMatch() {
        // 「搜好友」「拿取提醒」这类条目只是同一屏里的噪声：它们没有括号 ⇒ 取不到备注 ⇒ 不影响唯一性
        val found = NameLocating.locateFriend(
            "阿娜雅",
            listOf(
                listOf(
                    candidate("搜好友", y = 100),
                    candidate("克克洛儿(阿娜雅)", y = 200),
                    candidate("拿取提醒", y = 300),
                ),
            ),
        )

        assertTrue("应唯一命中好友那一行：$found", found is NameLocator.Result.Found)
    }

    @Test
    fun nameLocatingIsOnAfterTheTextRouteWasChosen() {
        // 2026-09-21 用户拍板走**文字识别**（模板路线在半透明背景 / 动态头像框 / 在线离线颜色 /
        // 需要滚动的列表上都不可靠）⇒ 启用。失败模式是"未找到 → 停下并提示"，
        // 不是"点错人"：全等 + 唯一（NameLocator）意味着读错一个字就匹配不上（红线 3 不破）。
        assertTrue("文字识别已按用户口径启用", NameLocating.ENABLED)
    }

    // ---------- 行级去重（整行 vs 行内词） ----------

    @Test
    fun rowLevelAndWordLevelCandidatesCountAsOne() {
        // 同一屏会同时给出「整行」与「行内词」两级结果；必须先按行去重（取更小的框），
        // 否则"明明只有一行"会被算成 2 个 → 白停一次（两级取完备注都是同一个名字）
        val page = listOf(
            listOf(
                candidate("克克雨儿(阿娜雅) 61 荣耀黄金 离线", y = 100, width = 600, height = 40),
                candidate("克克雨儿(阿娜雅)", y = 100, width = 90, height = 15),
            ),
        )

        val found = NameLocating.locateFriend("阿娜雅", page)
        val hit = requireNotNull(found as? NameLocator.Result.Found) { "同行重复应合并成一个：$found" }
        assertEquals("取更小的那个框（更贴合名字本身）", 90, hit.payload.width)
    }

    @Test
    fun twoAccountsSharingOneRemarkPickTheTopmostOne() {
        // 真机原样：阿娜雅是这位好友**两个小号**共用的备注（昵称分别是克克雨儿 / 克克洛儿）。
        // **2026-09-30 用户口径变更**："当屏幕上识别到多个相同的好友时，去最上面的那个"
        // ⇒ 不再停下（见 `requirements.md`「口径修订十」）。
        val page = listOf(
            listOf(
                candidate("克克洛儿(阿娜雅)", y = 400), // 下面那条
                candidate("克克雨儿(阿娜雅)", y = 100), // 上面那条（候选顺序**故意**与 y 相反）
            ),
        )

        val byRemark = NameLocating.locateFriend("阿娜雅", page)
        val hit = requireNotNull(byRemark as? NameLocator.Result.Found) { "同名多行应取最上面那条：$byRemark" }
        assertEquals("取 y 最小的那条（与候选顺序无关）", 100, hit.payload.y)

        // 配昵称仍然不命中：括号外的昵称不参与匹配（2026-09-24 口径没变）
        val byNickname = NameLocating.locateFriend("克克雨儿", page)
        assertTrue("昵称不再参与匹配：$byNickname", byNickname is NameLocator.Result.NotFound)
    }

    @Test
    fun topmostIsPickedWithinOnePageNotAcrossPages() {
        // ⚠ "最上面"只在**同一屏内**可比：翻屏后帧坐标不是一个空间（第 2 屏的 y 更小不代表它在上面）
        // ⇒ 逐页看，碰到第一屏有命中就用那一屏最上面的那条。
        val pages = listOf(
            listOf(candidate("克克雨儿(阿娜雅)", y = 400)),
            listOf(candidate("克克洛儿(阿娜雅)", y = 100)),
        )

        val hit = NameLocating.locateFriend("阿娜雅", pages)

        assertEquals(
            "用第 1 屏那条（y=400），不跨屏比 y",
            "克克雨儿(阿娜雅)",
            (hit as NameLocator.Result.Found).payload.text,
        )
    }

    @Test
    fun theErrorDetailSaysWhichKindOfFailureItIs() {
        // 好友这条现在只剩"一个都没有"这一种失败（同名多行 2026-09-30 起改为取最上面那条）
        val page = listOf(listOf(candidate("克克雨儿(阿娜雅)"), candidate("克克洛儿(阿娜雅)")))
        val none = NameLocating.locateFriend("不存在的好友", page) as NameLocator.Result.NotFound
        assertTrue("应说明'没找到'：${none.detail}", none.detail.contains("没找到"))
    }

    // ---------- 2026-09-22 用户口径（T4-3g）：名称列**只提供横向边界**，纵向一律取「列表区域」 ----------

    @Test
    fun visitRangesCropsTheOcrInputToTheNameColumnHorizontally() {
        // 横向照旧裁到名称列的左右边界（名称列故意两边都溢出列表区域 ⇒ 结果仍落在列表区域内）
        val listArea = PixelBounds(100, 200, 900, 1400)
        val column = PixelBounds(300, 150, 950, 1500)

        val ranges = NameLocating.visitRanges(listArea, column)

        assertEquals(PixelBounds(300, 200, 900, 1400), ranges.ocrRegion)
        assertFalse("横向有交集 ⇒ 非空", ranges.ocrRegion.isEmpty)
    }

    @Test
    fun theColumnVerticalBoundsAreIgnoredSoOneRowIsEnough() {
        // **本口径的意义所在**（2026-09-22 用户口径）：名称列只框了**一行高**（甚至框歪）⇒
        // OCR 输入纵向仍要覆盖**整个「列表区域」**。早先要求"与列表区域上下齐平"，真机上很难对准。
        val listArea = PixelBounds(100, 200, 900, 1400)
        val oneRow = PixelBounds(300, 700, 500, 760) // 只框了一行高

        val ranges = NameLocating.visitRanges(listArea, oneRow)

        assertEquals(
            "纵向 = 列表区域（名称列的上下边界被忽略）",
            PixelBounds(300, 200, 500, 1400),
            ranges.ocrRegion,
        )
    }

    @Test
    fun aColumnVerticallyOutsideTheListAreaIsNotAFailure() {
        // 纵向没对上**不算**"框错了"（纵向本来就不参与判断）；只有**横向**没对上才算 ⇒ isEmpty
        val listArea = PixelBounds(100, 200, 900, 1400)
        val columnAbove = PixelBounds(300, 10, 500, 100)

        val ranges = NameLocating.visitRanges(listArea, columnAbove)

        assertFalse("纵向没对上不是失败", ranges.ocrRegion.isEmpty)
        assertEquals(PixelBounds(300, 200, 500, 1400), ranges.ocrRegion)
    }

    @Test
    fun rowEdgesStayTheListAreaEdgesNotTheColumnEdges() {
        // 陷阱（同类疏漏踩过一次）：行带是"整行"，而**拜访图标在行尾**。若把 rowX 取成名称列的边界，
        // 行带里就只剩名字、没有图标 → 图标必然定位不到。两个边界都在这里钉住。
        val listArea = PixelBounds(100, 200, 900, 1400)
        val column = PixelBounds(300, 150, 500, 1500)

        val ranges = NameLocating.visitRanges(listArea, column)

        assertEquals("行带左边界 = 列表区域左边界", 100, ranges.rowX0)
        assertEquals("行带右边界 = 列表区域右边界（图标在行尾）", 900, ranges.rowX1)
    }

    @Test
    fun withoutTheColumnTheWholeListAreaIsRead() {
        // 老产物（没框过「好友名称列」）⇒ 不裁列，读整片列表区域。
        // **不假装裁过**：调用方据 nameColumn == null 在日志里明说"未框名称列"。
        val listArea = PixelBounds(100, 200, 900, 1400)

        val ranges = NameLocating.visitRanges(listArea, nameColumn = null)

        assertEquals(listArea, ranges.ocrRegion)
        assertEquals(100, ranges.rowX0)
        assertEquals(900, ranges.rowX1)
    }

    @Test
    fun aColumnOutsideTheListAreaIsReportedAsEmptyNotClamped() {
        // 两个框**横向**没对上（框错了）⇒ 交集为空。调用方必须**如实停下**：既不夹成一条零宽列、
        // 也不退回整片区域去凑 —— 两者都是"猜"（红线 3 的同一口径）。
        // 注意这里名称列的**纵向是对的**（200..1400）—— 仍然要判为失败：判据只看横向。
        val listArea = PixelBounds(100, 200, 900, 1400)

        val ranges = NameLocating.visitRanges(listArea, PixelBounds(950, 200, 1200, 1400))

        assertTrue("横向无交集 ⇒ isEmpty，绝不夹取", ranges.ocrRegion.isEmpty)
    }

    // ---------------------------------------------------------------- 第 9 步跨屏查找（T4-10 刀 1）

    @Test
    fun friendRowKeyIsTheMeaningInsideTheParensNotTheWholeLine() {
        // 行键两级：**先括号里那一段**（= App 里配的目标名的出处），没有括号才退回括号前并切掉首尾非文字符号。
        //
        // 2026-10-01 真机事故（用户："滑屏到了、也显示了好友，但**花了很长时间才找到**"）：
        // 目标 06:38:48 就已经在屏上，复眼却一直否决到 06:39:43（**55 秒**）—— 同一行连续 30+ 次读出的
        // **括号前**那一段一直在变（行首那个小图标被 OCR 读成不同符号），而括号里那一段**每次都是 `星月晚`**：
        val noisy = listOf("\u3001星月晚. (星月晚)", "-星月晚. (星月晚)", "\"星月晚. (星月晚)", "·星月晚. (星月晚)")
        assertEquals(
            "括号里那一段 = 每次读都一样的 `星月晚`",
            setOf("星月晚"),
            noisy.mapNotNull { NameLocating.friendRowKeyOf(candidate(it)) }.toSet(),
        )

        val first = candidate("克克雨儿(阿娜雅) 荣耀黄金 离线")
        val second = candidate("克克雨儿(阿娜雅) 尊贵铂金 在线中")
        assertEquals("阿娜雅", NameLocating.friendRowKeyOf(first))
        assertEquals(
            "两次读到的整行不同（在线状态/段位变了），但行键必须相同",
            NameLocating.friendRowKeyOf(first),
            NameLocating.friendRowKeyOf(second),
        )
        // 识别同时给出整行与行内的词时，两者算出同一个键（同一行的重复只算一次）
        assertEquals(
            NameLocating.friendRowKeyOf(candidate("克克雨儿(阿娜雅) 荣耀黄金 离线")),
            NameLocating.friendRowKeyOf(candidate("克克雨儿(阿娜雅)")),
        )
        // 全角括号同样认（与命中的字段规则同源）
        assertEquals("阿娜雅", NameLocating.friendRowKeyOf(candidate("克克雨儿（阿娜雅） 荣耀黄金")))
        // **没有括号也照样有键**（好友列表里没设备注的人很多 —— 只用括号会丢键、样本不够判"重合度"）
        assertEquals("李剑聪", NameLocating.friendRowKeyOf(candidate("李剑聪 荣耀黄金 离线")))
        // 没有括号时**切掉首尾非文字符号**：行首图标被读成 `、`/`-` 不该让键变样
        assertEquals("星月晚", NameLocating.friendRowKeyOf(candidate("\u3001星月晚. 荣耀黄金 离线")))
        assertEquals("星月晚", NameLocating.friendRowKeyOf(candidate("-星月晚. 荣耀黄金 离线")))
        // 一屏的键集合：去重 + 丢掉空行。⚠ 同一好友的**多个小号共用备注**时键相同（真机原样
        // `克克雨儿(阿娜雅)` / `克克洛儿(阿娜雅)`）—— 这是**有意的**（同一身份，重合度/复眼按一条算）
        assertEquals(
            setOf("阿娜雅", "许杨", "李剑聪"),
            NameLocating.friendRowKeysOf(
                listOf(
                    candidate("克克雨儿(阿娜雅) 荣耀黄金 离线"),
                    candidate("克克洛儿(阿娜雅) 尊贵铂金 在线"),
                    candidate("谁在远方(许杨) 荣耀黄金 离线"),
                    candidate("李剑聪 荣耀黄金 离线"),
                    candidate("   "),
                ),
            ),
        )
    }

    @Test
    fun friendRowYsKeepsTheTopmostYForTheSameRowKey() {
        // 复眼要的"行键 → 纵坐标"：同一行被读到不止一次（OCR 抖动 / 整行与行内词两级结果）时
        // 取**最上面**那一个 y —— 否则复眼会拿另一个读数的位置去比、把正确位置判成"挪了"。
        val page = listOf(
            candidate("克克雨儿(阿娜雅) 尊贵铂金", y = 500),
            candidate("克克雨儿(阿娜雅) 荣耀黄金", y = 300),
            candidate("谁在远方(许杨) 荣耀黄金", y = 700),
            candidate("   ", y = 100),
        )

        val ys = NameLocating.friendRowYsOf(page)

        assertEquals(300.0, ys.getValue("阿娜雅"), 1e-9)
        // 兜底键（括号前那一段）**也进表**：复眼任一把键命中同一纵线即算"还在原地"
        // （窄带里只读到半个括号时靠它兜住，见 NameLocator.friendRowKeyAliasesOf 的说明）
        assertEquals(300.0, ys.getValue("克克雨儿"), 1e-9)
        assertEquals(700.0, ys.getValue("许杨"), 1e-9)
        assertEquals(700.0, ys.getValue("谁在远方"), 1e-9)
        assertFalse("空行不进表", ys.containsKey(""))
        assertEquals(4, ys.size)
    }

    @Test
    fun friendRowKeyDropsTheStrayLetterAndTheTextlessNoiseRows() {
        // 2026-10-01 真机（用户报"好友列表滑到顶为什么重复滑了几次才停下"）：列表明明在顶部，
        // 同一行连续被读成 `B东百万` / `-东百万` / `p东百万` ⇒ 旧口径算出三个不同行键 ⇒
        // 同屏重合度掉到 36~50% ⇒ 判"还没到顶"、白滑到 3 下上限。
        assertEquals("东百万", NameLocating.friendRowKeyOf(candidate("B东百万 永恒钻石")))
        assertEquals("东百万", NameLocating.friendRowKeyOf(candidate("-东百万 永恒钻石")))
        assertEquals("东百万", NameLocating.friendRowKeyOf(candidate("p东百万 永恒钻石")))
        // ⚠ 多字母前缀是**真名**，原样保留（别把 `Boss~~喵` 切坏）
        assertEquals(setOf("Boss~~喵"), NameLocating.friendRowKeyAliasesOf(candidate("Boss~~喵 荣耀黄金")))
        // **不含汉字的行不给键**（真机里有 `8400` / `OO` 这种纯数字 / 纯字母的噪声行；
        // 两次读出来完全不同 ⇒ 只会拉低同屏重合度）。
        // ⚠ 混着汉字的噪声行（真机原样 `出8400`）这条规则挡不住 —— 那类靠"回顶判据放宽"兜
        // （见 `ServerListScan.TO_TOP_SAME_SCREEN_OVERLAP`）。
        assertTrue(
            "不含汉字的噪声行不参与身份判断",
            NameLocating.friendRowKeyOf(candidate("8400 永恒钻石")) == null,
        )
    }

    @Test
    fun aTooLargeEdgeRatioIsRejectedInsteadOfSilentlyReversingTheSwipe() {
        // 2026-10-01 真机事故：行程 = 区域高 ×(1 - 2×ratio) ⇒ 把好友的 ratio 从 0.35 加到 0.6 之后，
        // "回顶"那一枪变成手指**向上**划（实录 `(2551,853) -> (2551,631)`）⇒ 列表反而往下走，
        // 用户报"**好友列表根本没有往上移动**"。⇒ 越界必须当场报错，而不是安静地反向划屏。
        val area = PixelBounds(200, 100, 2800, 1219)
        for (r in listOf(0.05, 0.2, 0.25, 0.35, 0.45)) {
            val top = NameLocating.scrollDragOf(area, towardsTop = true, edgeRatio = r, durationMs = 600)
            assertTrue("ratio=$r 回顶必须是手指向下（内容下移）", top.toY > top.fromY)
            val down = NameLocating.scrollDragOf(area, towardsTop = false, edgeRatio = r, durationMs = 600)
            assertTrue("ratio=$r 往下找必须是手指向上", down.toY < down.fromY)
        }

        var threw = false
        try {
            NameLocating.scrollDragOf(area, towardsTop = true, edgeRatio = 0.6, durationMs = 600)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("越界的 edgeRatio 必须当场报错（真机那次就是 0.6）", threw)
    }

    @Test
    fun friendRowKeyAliasesCoverTheHalfReadParen() {
        // 复眼只在那一行的**窄带**里重读（±1 个名字高）⇒ 万一只读到半个括号（真机会发生），
        // 括号字段就成了半截 `阿娜` ✗ —— 拿正解去比必然对不上（2026-10-01 就是这么连否 55 秒）。
        // 这时"括号前（切掉首尾非文字符号）"那把键仍然认得出是同一行。
        val hit = candidate("克克雨儿(阿娜雅) 荣耀黄金 离线")
        val half = candidate("克克雨儿(阿娜 荣耀黄金 离线")

        assertEquals(setOf("阿娜雅", "克克雨儿"), NameLocating.friendRowKeyAliasesOf(hit))
        assertTrue(
            "半截读数的正解对不上（缺右括号时会把后面的段位/状态一起吞进来），" +
                "但兜底键「克克雨儿」一定对得上 ⇒ 复眼不会因此白等一轮",
            NameLocating.friendRowKeyAliasesOf(hit)
                .intersect(NameLocating.friendRowKeyAliasesOf(half))
                .contains("克克雨儿"),
        )
        // 没有括号的行：只有兜底键（这也正是它必须留在表里的理由）
        assertEquals(setOf("李剑聪"), NameLocating.friendRowKeyAliasesOf(candidate("李剑聪 荣耀黄金 离线")))
        // 行首图标的噪声在兜底键里被切掉 ⇒ 与正解一致（集合只有一个元素）
        assertEquals(setOf("星月晚"), NameLocating.friendRowKeyAliasesOf(candidate("-星月晚. 荣耀黄金 离线")))
    }

    @Test
    fun scrollDragDirectionIsNotReversed() {
        // 方向口径**极容易写反**（2026-09-29 第一版就写反过）：手指向下划 = 回顶、向上划 = 往下找。
        // 这里把它钉死：回顶时起点在上终点在下；往下找时相反。
        val area = PixelBounds(100, 200, 900, 1400) // 高 1200

        val up = NameLocating.scrollDragOf(area, towardsTop = true, edgeRatio = 0.25, durationMs = 300)
        val down = NameLocating.scrollDragOf(area, towardsTop = false, edgeRatio = 0.25, durationMs = 300)

        assertEquals("X 取区域中线（纵向拖动不会选中某一行）", 500.0, up.fromX, 0.001)
        assertEquals(up.fromX, up.toX, 0.001)

        assertEquals("回顶：从 25% 处划到 75% 处", 200 + 300.0, up.fromY, 0.001)
        assertEquals(200 + 900.0, up.toY, 0.001)
        assertTrue("回顶必须是往下划（toY > fromY）", up.toY > up.fromY)

        assertEquals("往下找：从 75% 处划到 25% 处", 200 + 900.0, down.fromY, 0.001)
        assertEquals(200 + 300.0, down.toY, 0.001)
        assertTrue("往下找必须是往上划（toY < fromY）", down.toY < down.fromY)

        // 拖动距离 = 半个区域高（大致换掉一屏，又不至于甩出去）
        assertEquals(600.0, up.toY - up.fromY, 0.001)
        assertEquals(300L, up.durationMs)
    }
}
