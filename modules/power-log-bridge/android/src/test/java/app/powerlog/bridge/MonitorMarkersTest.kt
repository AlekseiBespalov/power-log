package app.powerlog.bridge

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MonitorMarkersTest {
    @Test
    fun bothMetricsMoveWhileJavascriptIsBehind() {
        val state = MonitorMarkers()
        val metrics = setOf("humanPowerW", "motorInputPowerW")
        fun move(sequence: Int, seconds: Double) =
            state.synchronize("ride", listOf(0.0, 10.0, 1.0, seconds, 0.0, 0.0, 1.0, sequence.toDouble()))
        fun points(seconds: Double) = metrics.mapIndexed { index, id ->
            mapOf("id" to id, "seconds" to seconds, "value" to seconds * (index + 1), "key" to "view:1")
        }
        val old =
            json(
                mapOf(
                    "sourceId" to "ride",
                    "epoch" to 1,
                    "sequence" to 1,
                    "cursorPending" to false,
                    "points" to points(1.0),
                )
            )
        move(1, 1.0)
        state.receive(old, metrics)
        move(2, 2.0)
        state.target(json(mapOf("epoch" to 1, "sequence" to 2, "points" to points(2.0))))
        assertTrue(state.points().isEmpty())
        assertEquals(listOf(2.0, 2.0), state.previews("view:1").map { it.seconds })
        state.receive(old, metrics)
        assertEquals(listOf(2.0, 4.0), state.previews("view:1").map { it.value })
        state.receive(
            json(
                mapOf(
                    "sourceId" to "ride",
                    "epoch" to 1,
                    "sequence" to 2,
                    "cursorPending" to false,
                    "points" to emptyList<Payload>(),
                )
            ),
            metrics,
        )
        assertTrue(state.points().isEmpty())
        assertTrue(state.previews("view:1").isEmpty())
    }

    @Test
    fun rejectsOldCursorPointsAndClearedOrUnavailableTargets() {
        val state = MonitorMarkers()
        val point = mapOf("id" to "humanPowerW", "seconds" to 1.0, "value" to 250.0, "key" to "view:1")
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
        assertTrue(state.points().isEmpty())
        state.target(json(mapOf("epoch" to 1, "sequence" to 2, "points" to listOf(point))))
        assertEquals(1, state.previews("view:1").size)
        assertTrue(state.previews("view:2").isEmpty())
        select(2, false, emptyList())
        assertTrue(state.points().isEmpty())
        assertTrue(state.previews("view:1").isEmpty())
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

    @Test
    fun acceptanceFixturesFenceSourceRevisionEpochAndSequence() {
        val root = System.getenv("POWER_LOG_CONTRACT_FIXTURES") ?: "../../../tests/fixtures/contract"
        val fixture = JSONObject(File(root, "inspection/acceptance.json").readText())
        val context = fixture.getJSONObject("context")
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val state = MonitorMarkers()
            state.synchronize(
                context.getString("source"),
                listOf(
                    0.0,
                    10.0,
                    1.0,
                    1.0,
                    0.0,
                    0.0,
                    context.getString("epoch").toDouble(),
                    context.getString("sequence").toDouble(),
                ),
                context.getString("revision"),
            )
            state.receive(
                json(
                    mapOf(
                        "sourceId" to case.optString("source", context.getString("source")),
                        "revision" to case.optString("revision", context.getString("revision")),
                        "epoch" to case.optString("epoch", context.getString("epoch")).toDouble(),
                        "sequence" to case.optString("sequence", context.getString("sequence")).toDouble(),
                        "generation" to case.optString("generation", "1").toLong(),
                        "distanceSelection" to case.optString("selection", "auto"),
                        "cursorPending" to false,
                        "points" to listOf(mapOf("id" to "humanPowerW", "seconds" to 1.125, "value" to 200.0)),
                    )
                ),
                setOf("humanPowerW"),
            )
            assertEquals(case.getString("id"), case.getBoolean("accepted"), state.points().isNotEmpty())
        }
    }
}
