package app.powerlog.bridge

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MonitorMarkersTest {
    @Test
    fun holdsOriginalsThroughPendingReadsButRejectsClearedAndUnavailableTargets() {
        val state = MonitorMarkers()
        val point =
            mapOf("id" to "humanPowerW", "seconds" to 1.0, "value" to 250.0, "key" to "view:1")
        fun move(seq: Int, active: Boolean = true) =
            state.synchronize(
                "ride",
                listOf(0.0, 10.0, if (active) 1.0 else 0.0, 1.0, 0.0, 0.0, 1.0, seq.toDouble()),
            )
        fun select(seq: Int, pending: Boolean, points: List<Payload>) =
            state.receive(
                json(
                    mapOf(
                        "sourceId" to "ride",
                        "epoch" to 1,
                        "sequence" to seq,
                        "cursorPending" to pending,
                        "points" to points,
                    )
                ),
                setOf("humanPowerW"),
            )
        move(1)
        select(1, false, listOf(point))
        move(2)
        select(2, true, emptyList())
        assertEquals(250.0, state.points().single().value, 0.0)
        state.target(json(mapOf("epoch" to 1, "sequence" to 2, "point" to point)))
        assertNotNull(state.primary("view:1"))
        assertNull(state.primary("view:2"))
        select(2, false, emptyList())
        assertTrue(state.points().isEmpty())
        assertNull(state.primary("view:1"))
        select(1, false, listOf(point))
        select(2, true, listOf(point))
        assertTrue(state.points().isEmpty())
        move(3, false)
        select(2, false, listOf(point))
        move(4)
        assertTrue(state.points().isEmpty())
        select(4, false, listOf(point))
        assertEquals(1, state.points().size)
        state.synchronize("other", listOf(0.0, 10.0, 1.0, 1.0, 0.0, 0.0, 1.0, 5.0))
        assertTrue(state.points().isEmpty())
    }
}
