package com.example.mastermechanic.patrol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「进行中的流程」的并发口径（2026-09-22 真机 bug 的回归）。
 *
 * 钉住的是：**帧线程拿着旧快照写回时，不能把主线程已经停止的流程复活**。
 *
 * 真机实录（`files/logs/mm-log-20260922.txt`）：
 * ```
 * 01:15:46  停止：停在第 8 步
 * 01:15:53  跑号: 第 8 步通过 → 第 9 步    ← 停止之后流程又自己走了一步
 * 01:16:13  跑号: 第 9 步停下：没指定要拜访的好友名
 * ```
 * 后果不止"停不掉"：期望集合 / 画面判定全按已停止的那一步走，状态会滞留在旧结论上
 * （用户侧表现：关掉好友列表回农场后，菜单仍被拦"现在停在「好友列表」"）。
 */
class PatrolSessionTest {

    @After
    fun tearDown() {
        // 单例：每条用例后复位，避免互相污染
        PatrolSession.stop()
    }

    @Test
    fun aStaleCommitCannotReviveAStoppedRun() {
        PatrolSession.start(PatrolFlow.Scene.FARM, PatrolFlow.Range.VISIT_ONLY, nowMs = 0L)
        val snapshot = PatrolSession.current
        val generation = PatrolSession.currentGeneration
        assertNotNull("起点成立，流程应已开始", snapshot)

        // 主线程按了停止；帧线程手上还攥着停止前的快照
        PatrolSession.stop()
        assertNull("停止后没有进行中的流程", PatrolSession.current)

        PatrolSession.commit(snapshot, generation)

        assertNull("旧代次的写回必须被丢弃（否则「停止」等于没按）", PatrolSession.current)
    }

    @Test
    fun aCommitFromTheCurrentGenerationStillApplies() {
        PatrolSession.start(PatrolFlow.Scene.FARM, PatrolFlow.Range.VISIT_ONLY, nowMs = 0L)
        val generation = PatrolSession.currentGeneration
        val advanced = PatrolSession.current

        PatrolSession.commit(advanced, generation)

        assertEquals("同代次的写回照常生效（不能误伤正常推进）", advanced, PatrolSession.current)
    }

    @Test
    fun startingAgainInvalidatesTheRoundsOfThePreviousRun() {
        PatrolSession.start(PatrolFlow.Scene.FARM, PatrolFlow.Range.VISIT_ONLY, nowMs = 0L)
        val staleSnapshot = PatrolSession.current
        val staleGeneration = PatrolSession.currentGeneration

        // 用户停掉，又重新开始（这一次是「换号」）
        PatrolSession.stop()
        PatrolSession.start(PatrolFlow.Scene.FARM, PatrolFlow.Range.SWITCH_ONLY, nowMs = 0L)
        val fresh = PatrolSession.current

        PatrolSession.commit(staleSnapshot, staleGeneration)

        assertEquals("旧一次执行的结论不能覆盖新一次", fresh, PatrolSession.current)
    }

    @Test
    fun resumeInvalidatesTheRoundsThatSampledThePausedState() {
        // 2026-10-01 真机（用户报"**从桌面切回游戏，点了继续后依然报「游戏不在前台，已暂停」，
        // 需要再次点「继续」才正常恢复流程**"）：帧线程在用户点「继续」**之前**就把那份「已暂停」的
        // 快照读走了，算完 `commit` 回来 ⇒ 把刚恢复的流程**又写回暂停** ✗。
        // 日志佐证：两次 `继续：从9步接着来` 都打了（resume 本身成功），而中间那 7 秒里流程没有任何动静。
        // ⇒ resume 必须与 start / stop 同一条规矩：**递增代次**，让旧轮次整轮作废。
        PatrolSession.start(PatrolFlow.Scene.FARM, PatrolFlow.Range.VISIT_ONLY, nowMs = 0L)
        val paused = PatrolFlow.pause(requireNotNull(PatrolSession.current))
        PatrolSession.commit(paused, PatrolSession.currentGeneration)
        // 帧线程手上那份（已暂停）——它是在用户点「继续」之前取的
        val staleSnapshot = PatrolSession.current
        val staleGeneration = PatrolSession.currentGeneration

        assertTrue("暂停中应能继续", PatrolSession.resume(nowMs = 5_000L))

        PatrolSession.commit(staleSnapshot, staleGeneration)

        assertEquals(
            "旧代次的「已暂停」不能覆盖「继续」（否则用户得点两次才动）",
            PatrolFlow.Outcome.RUNNING,
            PatrolSession.current?.outcome,
        )
    }

    @Test
    fun theDeniedClickNoteSurvivesThePerRoundNote() {
        // 2026-09-30（progress 第 325 条 b）：被拒的原因要**粘在菜单上** —— 用户是**事后**才问
        // "为什么它不动"的，而 note 每轮都被编排层重写（只活 200~500ms）
        PatrolSession.start(PatrolFlow.Scene.FARM, PatrolFlow.Range.VISIT_ONLY, nowMs = 0L)
        PatrolSession.noteDenied("点击被拒 3 次：点击点落在屏幕外")

        PatrolSession.note("第 8 步：刚点过，等画面切换")

        assertEquals(
            "被拒说明必须活过一轮 note",
            "点击被拒 3 次：点击点落在屏幕外",
            PatrolSession.deniedNote,
        )
        PatrolSession.clearDeniedNote()
        assertNull("真的发出去了就清掉（那一句过期了）", PatrolSession.deniedNote)
    }

    @Test
    fun aNewRunDoesNotInheritTheOldDeniedNote() {
        PatrolSession.start(PatrolFlow.Scene.FARM, PatrolFlow.Range.VISIT_ONLY, nowMs = 0L)
        PatrolSession.noteDenied("点击被拒 3 次：点击点落在屏幕外")
        PatrolSession.stop()
        assertNull("停止即清", PatrolSession.deniedNote)

        PatrolSession.noteDenied("上一局留下的一句")
        PatrolSession.start(PatrolFlow.Scene.FARM, PatrolFlow.Range.VISIT_ONLY, nowMs = 0L)
        assertNull("新一局不继承上一局的拒绝原因", PatrolSession.deniedNote)
    }

    @Test
    fun stoppingClearsTheRunAndItsTarget() {
        PatrolSession.start(
            PatrolFlow.Scene.FARM,
            PatrolFlow.Range.VISIT_ONLY,
            nowMs = 0L,
            friendName = "阿娜雅",
        )
        assertEquals("阿娜雅", PatrolSession.targetFriend)

        PatrolSession.stop()

        assertNull(PatrolSession.current)
        assertNull("停止要连目标好友一起清掉（第 9 步据此报「没指定好友名」）", PatrolSession.targetFriend)
    }
}
