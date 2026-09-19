package com.example.mastermechanic.patrol

import com.example.mastermechanic.patrol.PatrolRequestSignal.Kind
import com.example.mastermechanic.patrol.PatrolRequestSignal.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PatrolRequestSignal] 的广播口径：带上内容、退订后不再收到、计数递增。 */
class PatrolRequestSignalTest {

    @Test
    fun listenerReceivesWhatUserPicked() {
        val got = mutableListOf<Request>()
        val listener: (Request) -> Unit = { got += it }
        PatrolRequestSignal.addListener(listener)
        try {
            PatrolRequestSignal.request(
                Request(kind = Kind.SWITCH_SERVER, nowMs = 1L, serverName = "龙腾"),
            )
        } finally {
            PatrolRequestSignal.removeListener(listener)
        }
        assertEquals(1, got.size)
        assertEquals(Kind.SWITCH_SERVER, got[0].kind)
        assertEquals("龙腾", got[0].serverName)
        assertNull(got[0].friendName)
    }

    @Test
    fun presetVisitCarriesFriendAndOptionalServer() {
        val got = mutableListOf<Request>()
        val listener: (Request) -> Unit = { got += it }
        PatrolRequestSignal.addListener(listener)
        try {
            // 预设是"固定区服"时带区服名；"换下一个"时不带（serverName == null）
            PatrolRequestSignal.request(
                Request(kind = Kind.VISIT_PRESET, nowMs = 2L, serverName = "龙腾", friendName = "阿明"),
            )
            PatrolRequestSignal.request(
                Request(kind = Kind.VISIT_PRESET, nowMs = 3L, friendName = "阿明"),
            )
        } finally {
            PatrolRequestSignal.removeListener(listener)
        }
        assertEquals("阿明", got[0].friendName)
        assertEquals("龙腾", got[0].serverName)
        assertEquals("阿明", got[1].friendName)
        assertNull(got[1].serverName)
    }

    @Test
    fun removedListenerStopsReceiving() {
        var hits = 0
        val listener: (Request) -> Unit = { hits++ }
        PatrolRequestSignal.addListener(listener)
        PatrolRequestSignal.removeListener(listener)
        PatrolRequestSignal.request(Request(kind = Kind.VISIT_PRESET, nowMs = 3L))
        assertEquals(0, hits)
    }

    @Test
    fun countGrowsWithEachRequest() {
        val before = PatrolRequestSignal.requestCount
        PatrolRequestSignal.request(Request(kind = Kind.STOP, nowMs = 4L))
        PatrolRequestSignal.request(Request(kind = Kind.SWITCH_NEXT, nowMs = 5L))
        assertTrue(PatrolRequestSignal.requestCount >= before + 2)
    }
}
