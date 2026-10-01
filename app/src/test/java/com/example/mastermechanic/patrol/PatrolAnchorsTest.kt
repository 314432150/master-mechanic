package com.example.mastermechanic.patrol

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4-3（离线部分）：每步点哪个锚点的契约、锚点用途、以及"退出登录"的点击链。 */
class PatrolAnchorsTest {

    // ---------------------------------------------------------------- 清单与命名

    @Test
    fun theChecklistHasThirteenDistinctAnchors() {
        // 11 条点击链 − 2 条（两个农场的返回 / 好友入口合并成 1 条 ×2）
        // + 第 9 步的「拜访」图标（按名称定位后用）
        // + 第 9 步文字识别的**列表区域**（2026-09-21 按名称定位走 OCR）
        // + 第 9 步文字识别的**好友名称列**（2026-09-22：只把名字这一列送去读，行首头像/装饰不进 OCR）
        // + 第 5 步文字识别的**服务器列表区域**（T4-9，2026-09-24）= 13
        // + 第 9 步**搜索式查找**的三个控件（2026-10-01 用户拍板：搜好友入口 / 搜索框 / 搜索按钮）= 16
        // 注：第 5 步的**左/右列竖带**（T4-9，2026-09-24）**不进这份"待标定清单"**（清单是"催用户去框"的，
        // 而两条竖带是**可选精化**、与「好友名称列」同口径）⇒ 清单是 16 条，锚点总数是 18。
        // 2026-09-23：三条**农场身份判据**锚点（主账号头像 / 好友头像 / 农场名称）随农场归属判定整块撤下。
        assertEquals(16, PatrolAnchors.all.size)
        val names = PatrolAnchors.all.map { it.name }
        assertEquals("锚点名必须唯一", names.size, names.distinct().size)
    }

    @Test
    fun everyAnchorNameIsAValidPurposeToken() {
        // 用途名要原样写进标定产物（含分隔符 / 换行的名字会把产物行读坏）
        for (need in PatrolAnchors.all) {
            assertTrue("非法用途名：${need.name}", CalibrationData.isValidId(need.name))
        }
    }

    @Test
    fun noAnchorIsAttachedToUnknownOrPopup() {
        // 「未知」不能声明锚点（红线 1）；活动弹窗的锚点属 FR-01，与跑号步骤无关
        for (need in PatrolAnchors.all) {
            assertFalse(need.state == UiState.UNKNOWN)
            assertFalse(need.state == UiState.ACTIVITY_POPUP)
        }
    }

    @Test
    fun everyAnchorOfTheChecklistIsUsedByTheCode() {
        // 「要标什么」与「代码点哪」必须是同一份事实：清单里的每条都要有出处 ——
        // 出处有四条：步骤点击链（[PatrolAnchors.actions]）、**按名称定位**的辅助锚点
        // （[PatrolAnchors.nameLocatingAnchors]，第 9 步的拜访图标走这条）、以及只圈区域不做点击的
        // 两条（[PatrolAnchors.nameAreaAnchors] 粗区域 / [PatrolAnchors.nameColumnAnchors] 名称列）。
        for (need in PatrolAnchors.all) {
            val inActions = Step.entries.any { step ->
                PatrolAnchors.actionFor(step, need.state)?.anchor == need.name
            }
            val inNameLocating = PatrolAnchors.nameLocatingAnchors[need.state] == need.name
            val inNameSample = PatrolAnchors.nameAreaAnchors[need.state] == need.name
            val inNameColumn = PatrolAnchors.nameColumnAnchors[need.state] == need.name
            // 第 9 步「搜索式查找」的三个控件（2026-10-01）
            val inSearchFlow = PatrolAnchors.searchFlowAnchors[need.state]?.contains(need.name) == true
            assertTrue(
                "清单里的锚点「${need.name}」在代码里没人用",
                inActions || inNameLocating || inNameSample || inNameColumn || inSearchFlow,
            )
        }
    }

    @Test
    fun actionsPlusNameLocatingPlusRegionAnchorsCoverExactlyTheChecklist() {
        val derived = buildSet {
            for (step in Step.entries) {
                PatrolAnchors.anchorsOf(step).forEach { add(it) }
            }
            PatrolAnchors.nameLocatingAnchors.values.forEach { add(it) }
            PatrolAnchors.nameAreaAnchors.values.forEach { add(it) }
            PatrolAnchors.nameColumnAnchors.values.forEach { add(it) }
            PatrolAnchors.searchFlowAnchors.values.flatten().forEach { add(it) }
        }
        assertEquals(PatrolAnchors.all.map { it.name }.toSet(), derived)
    }

    @Test
    fun nameLocatingAnchorsAreDeclaredInTheChecklistAndInThePurposes() {
        // 三处必须同时声明（2026-09-21 踩过的坑）：只写辅助锚点表而不进「用途表」，
        // 标定页就不会出现用途选项 → 用户框了锚点却取不到用途名，代码按用途永远找不到它
        for ((state, name) in PatrolAnchors.nameLocatingAnchors) {
            assertTrue(
                "辅助锚点「$name」也要进待标定清单（否则「还缺什么」会漏）",
                PatrolAnchors.all.any { it.state == state && it.name == name },
            )
            assertTrue(
                "辅助锚点「$name」也要进用途表（否则标定页没有用途项可选）",
                PatrolAnchors.purposesFor(state).any { it.name == name },
            )
        }
    }

    // ---------------------------------------------------------------- 用途（锚点名的来源）

    @Test
    fun regionOnlyAnchorsAreExactlyTheAreaAndColumnAnchors() {
        // 2026-09-22 真机修的落地口径：标了"只给文字识别圈范围"的锚点（用途表里的「列表区域」、
        // 「好友名称列」）**不能进模板定位** —— 真机实录漏排除 ⇒ 单轮 23.3 秒（帧线程整段停摆）。
        // 这里钉住"排除集合"与"两张区域锚点表"始终是同一份事实：将来新增一条区域锚点会自动被排除。
        // 两处调用点共用这一个集合：`toAnchorLocator`（不做模板匹配）与 `loadForRuntime`（窗口不收紧）。
        assertEquals(
            PatrolAnchors.nameAreaAnchors.values.toSet() +
                PatrolAnchors.nameColumnAnchors.values +
                // T4-9（2026-09-24）：服务器列表的两条列竖带同属"只圈区域"的一族
                PatrolAnchors.serverListColumnAnchors.values.flatten(),
            PatrolAnchors.nonLocatableAnchors,
        )
        assertTrue("区域锚点表当前非空，否则这个不变量是空的", PatrolAnchors.nonLocatableAnchors.isNotEmpty())
    }

    @Test
    fun regionOnlyAnchorsAreNeverClickTargets() {
        // 只圈区域的锚点不是点击目标：既不在点击链里，也不在"按名称定位后要点"的辅助锚点里
        // （真要把它当点击目标，就必须同时把它从 nonLocatableAnchors 里拿出来，否则点了也是白点）
        for (name in PatrolAnchors.nonLocatableAnchors) {
            for (step in Step.entries) {
                assertFalse(
                    "区域锚点「$name」出现在第 ${step.number} 步的点击链里",
                    PatrolAnchors.actions(step).any { it.anchor == name },
                )
            }
            assertFalse(
                "区域锚点「$name」被当成按名称定位后的点击目标",
                PatrolAnchors.nameLocatingAnchors.containsValue(name),
            )
        }
    }

    @Test
    fun purposesCoverExactlyTheAnchorsUsedOnEachState() {
        // 标定页按「用途」取名 → 用途名集合必须与该状态实际要点的锚点一一对上
        val usedByState = mutableMapOf<UiState, MutableSet<String>>()
        for (step in Step.entries) {
            for (action in PatrolAnchors.actions(step)) {
                usedByState.getOrPut(action.state) { mutableSetOf() } += action.anchor
            }
        }
        // 按名称定位那两步的辅助锚点也算"这个状态实际要点的锚点"（第 9 步的拜访图标）
        for ((state, anchor) in PatrolAnchors.nameLocatingAnchors) {
            usedByState.getOrPut(state) { mutableSetOf() } += anchor
        }
        // 第 9 步的**识别区域**也算"这个状态在用的锚点"（2026-09-21 按名称定位走文字识别）：
        // 粗区域（列表区域）与精化列（好友名称列）都是产物里要标的东西 ⇒ 都要在用途表里可选
        for ((state, anchor) in PatrolAnchors.nameAreaAnchors) {
            usedByState.getOrPut(state) { mutableSetOf() } += anchor
        }
        for ((state, anchor) in PatrolAnchors.nameColumnAnchors) {
            usedByState.getOrPut(state) { mutableSetOf() } += anchor
        }
        // 第 9 步「搜索式查找」的三个控件（2026-10-01）：也要能在用途表里选到
        for ((state, anchors) in PatrolAnchors.searchFlowAnchors) {
            for (anchor in anchors) usedByState.getOrPut(state) { mutableSetOf() } += anchor
        }
        // 第 5 步（T4-9，2026-09-24）同理：粗区域（服务器列表区域）+ **两条**列竖带都要能在用途表里选到
        for ((state, anchors) in PatrolAnchors.serverListColumnAnchors) {
            for (anchor in anchors) {
                usedByState.getOrPut(state) { mutableSetOf() } += anchor
            }
        }
        for ((state, used) in usedByState) {
            assertEquals(
                "$state 的用途名与实际要点的锚点不一致",
                used,
                PatrolAnchors.purposesFor(state).map { it.name }.toSet(),
            )
        }
    }

    @Test
    fun statesWithoutPurposesKeepTheOldAutomaticNaming() {
        // 无用途表 = 不要求用户选用途，沿用自动序号名：
        // - 活动弹窗只有一个关闭控件 → 沿用 popup_close_anchor（向后兼容，不动既有产物）；
        // - **好友列表**（第 9 步）2026-09-21 起**有**用途了（行尾拜访图标）⇒ 已从本表移出；
        // - **服务器列表**（第 5 步）**2026-09-24 起也有用途了**（T4-9）：「列表区域」= 服务器名识别的输入范围。
        //   注意它**仍不是点击目标**（点的是"名字本身"，位置由识别给出），只是多了一条识别用的区域锚点。
        assertTrue(PatrolAnchors.purposesFor(UiState.ACTIVITY_POPUP).isEmpty())
        assertTrue(PatrolAnchors.purposesFor(UiState.UNKNOWN).isEmpty())
        // 服务器列表相反：必须有「列表区域」这一项，否则标定页没法让用户框它
        assertEquals(
            listOf(
                PatrolAnchors.SERVER_LIST_AREA,
                PatrolAnchors.SERVER_LIST_COLUMN_LEFT,
                PatrolAnchors.SERVER_LIST_COLUMN_RIGHT,
            ),
            PatrolAnchors.purposesFor(UiState.SERVER_SELECT).map { it.name },
        )
        // 好友列表相反：必须有用途项，否则标定页选不到
        // `friend_visit` / `friend_list_area` / `friend_list_name_column`
        // + 搜索式查找的三个控件（2026-10-01：`friend_search_entry` / `friend_search_field` / `friend_search_go`）
        assertEquals(
            listOf(
                PatrolAnchors.FRIEND_VISIT,
                PatrolAnchors.FRIEND_LIST_AREA,
                PatrolAnchors.FRIEND_LIST_NAME_COLUMN,
                PatrolAnchors.FRIEND_SEARCH_ENTRY,
                PatrolAnchors.FRIEND_SEARCH_FIELD,
                PatrolAnchors.FRIEND_SEARCH_GO,
            ),
            PatrolAnchors.purposesFor(UiState.FRIEND_LIST).map { it.name },
        )
    }

    @Test
    fun purposeLabelsAreDistinctWithinAState() {
        for (state in UiState.entries) {
            val labels = PatrolAnchors.purposesFor(state).map { it.label }
            assertEquals("$state 的用途重名", labels.size, labels.distinct().size)
        }
    }

    @Test
    fun everyPurposeHasANonBlankLabel() {
        for (state in UiState.entries) {
            for (purpose in PatrolAnchors.purposesFor(state)) {
                assertTrue("$state 有用途没有中文名", purpose.label.isNotBlank())
                assertTrue(CalibrationData.isValidId(purpose.name))
            }
        }
    }

    @Test
    fun screensWithTwoButtonsHaveTwoPurposes() {
        // 一个界面两个控件、序号分不出谁是谁 —— 这正是要引入"用途"的原因
        for (state in listOf(UiState.HALL, UiState.LAUNCH_PAGE)) {
            assertEquals("$state 应该有两个用途", 2, PatrolAnchors.purposesFor(state).size)
        }
        // 2026-09-21 起农场画面曾一度是"2 个动作 + 3 条身份判据"（主账号头像 / 好友头像 / 农场名称）。
        // 2026-09-23 用户拍板把农场归属判定整块删掉 ⇒ 农场回到**只有两个要点的控件**。
        assertEquals(
            "农场：返回 + 好友入口",
            listOf(
                PatrolAnchors.FARM_EXIT,
                PatrolAnchors.FARM_FRIENDS,
            ),
            PatrolAnchors.purposesFor(UiState.FARM).map { it.name },
        )
    }

    @Test
    fun bothFarmsShareOneExitAndOneFriendsAnchorAfterTheMerge() {
        // 2026-09-21 合并：返回箭头与好友入口在两个农场的样式一致（用户确认）⇒ 各只需一条锚点。
        // FRIEND_FARM 状态保留只为兼容旧产物里的规则，新契约不再为它声明锚点
        assertEquals(PatrolAnchors.FARM_EXIT, PatrolAnchors.nameFor(Step.LEAVE_FARM, UiState.FARM))
        assertEquals(PatrolAnchors.FARM_FRIENDS, PatrolAnchors.nameFor(Step.OPEN_FRIENDS, UiState.FARM))
        assertNull(
            "合并后好友农场不再有独立的返回锚点",
            PatrolAnchors.nameFor(Step.LEAVE_FARM, UiState.FRIEND_FARM),
        )
        assertNull(PatrolAnchors.nameFor(Step.OPEN_FRIENDS, UiState.FRIEND_FARM))
    }

    // ---------------------------------------------------------------- 第 3 步的点击链

    @Test
    fun logoutIsAThreeClickChainThroughTheTwoNewScreens() {
        // 大厅 → 设置页 → 确认框 → 启动页；每一次点击都带自己的验证目标（不盲点）
        val chain = PatrolAnchors.actions(Step.LOGOUT)
        assertEquals(3, chain.size)
        assertEquals(
            listOf(
                PatrolAnchors.Action(UiState.HALL, PatrolAnchors.HALL_SETTINGS, UiState.HALL_SETTINGS),
                PatrolAnchors.Action(
                    UiState.HALL_SETTINGS,
                    PatrolAnchors.SETTINGS_LOGOUT,
                    UiState.LOGOUT_CONFIRM,
                ),
                PatrolAnchors.Action(
                    UiState.LOGOUT_CONFIRM,
                    PatrolAnchors.LOGOUT_CONFIRM_OK,
                    UiState.LAUNCH_PAGE,
                ),
            ),
            chain,
        )
    }

    @Test
    fun leavingTheFarmGoesThroughTheReturnConfirmDialog() {
        // 真机取证（2026-09-19）：农场 / 好友农场点左上角返回会弹「请选择需要返回的场景」，
        // 直接判到大厅会把这一屏当成未知而停下 → 必须显式走「返回大厅」
        val chain = PatrolAnchors.actions(Step.LEAVE_FARM)
        // 2026-09-21 合并：两个农场共用一个返回入口 ⇒ 三击变两击
        assertEquals(2, chain.size)
        assertEquals(
            listOf(
                PatrolAnchors.Action(
                    UiState.FARM,
                    PatrolAnchors.FARM_EXIT,
                    UiState.SCENE_RETURN_CONFIRM,
                ),
                // 2026-09-21 合并：好友农场不再单列（两个农场共用返回入口）⇒ 三击变两击
                PatrolAnchors.Action(
                    UiState.SCENE_RETURN_CONFIRM,
                    PatrolAnchors.RETURN_LOBBY,
                    UiState.HALL,
                ),
            ),
            chain,
        )
        // 弹框里另一项（返回稷下学院）不在契约里：它通往一个没有状态定义的界面
        assertFalse(PatrolAnchors.anchorsOf(Step.LEAVE_FARM).any { it.contains("academy") })
    }

    @Test
    fun actionForPicksTheStepOfTheChainMatchingTheCurrentScreen() {
        assertEquals(
            PatrolAnchors.SETTINGS_LOGOUT,
            PatrolAnchors.actionFor(Step.LOGOUT, UiState.HALL_SETTINGS)?.anchor,
        )
        assertEquals(
            PatrolAnchors.LOGOUT_CONFIRM_OK,
            PatrolAnchors.actionFor(Step.LOGOUT, UiState.LOGOUT_CONFIRM)?.anchor,
        )
        // 走到启动页 = 这一步已经做完，不该再有动作
        assertNull(PatrolAnchors.actionFor(Step.LOGOUT, UiState.LAUNCH_PAGE))
    }

    @Test
    fun everyActionVerifiesAScreenDifferentFromTheOneItStartsFrom() {
        // 点完看到的是**别的**画面，否则"验证"等于自证（红线 5 的意义就没了）
        for (step in Step.entries) {
            for (action in PatrolAnchors.actions(step)) {
                assertFalse(
                    "第 ${step.number} 步的动作 ${action.anchor} 点完还是同一个画面（${action.state}）",
                    action.state == action.expect,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 名称定位那两步

    @Test
    fun nameLocatedStepsDoNotUseAnchors() {
        // 第 5 / 9 步在列表里挑名字，锚点帮不上忙（挑哪个不是位置问题，是内容问题）
        assertTrue(PatrolAnchors.isNameLocated(Step.PICK_SERVER))
        assertTrue(PatrolAnchors.isNameLocated(Step.VISIT_FRIEND))
        assertTrue(PatrolAnchors.actions(Step.PICK_SERVER).isEmpty())
        assertTrue(PatrolAnchors.actions(Step.VISIT_FRIEND).isEmpty())
        assertNull(PatrolAnchors.nameFor(Step.PICK_SERVER, UiState.SERVER_SELECT))
        assertNull(PatrolAnchors.nameFor(Step.VISIT_FRIEND, UiState.FRIEND_LIST))
        for (step in Step.entries) {
            if (step == Step.PICK_SERVER || step == Step.VISIT_FRIEND) continue
            assertFalse("第 ${step.number} 步不该被当成名称定位", PatrolAnchors.isNameLocated(step))
        }
    }

    // ---------------------------------------------------------------- 不兜底

    @Test
    fun nameForNeverFallsBackToAnotherState() {
        // 不兜底：拿大厅的锚点名去农场里找是危险的错觉，落空才是安全的
        assertNull(PatrolAnchors.nameFor(Step.LOGOUT, UiState.FARM))
        assertNull(PatrolAnchors.nameFor(Step.ENTER_FARM, UiState.FARM))
        assertNull(PatrolAnchors.nameFor(Step.LEAVE_FARM, UiState.HALL))
        assertNull(PatrolAnchors.nameFor(Step.SWITCH_SERVER, UiState.SERVER_SELECT))
        assertNull(PatrolAnchors.nameFor(Step.CHECK_START, UiState.HALL))
        assertNull(PatrolAnchors.nameFor(Step.DONE, UiState.FRIEND_FARM))
    }
}
