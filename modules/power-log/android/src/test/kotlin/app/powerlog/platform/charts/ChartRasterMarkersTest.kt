package app.powerlog.platform.charts

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private typealias Payload = Map<String, Any?>

private fun selection(value: Payload) = ChartSelection(JSONObject(value))

private fun target(value: Payload) = ChartTarget.parse(JSONObject(value).toString())!!

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ChartRasterMarkersTest {
    @Test
    fun bothMetricsMoveWhileJavascriptIsBehind() {
        val state = ChartRasterMarkers()
        val metrics = setOf("humanPowerW", "motorInputPowerW")
        fun move(sequence: Int, seconds: Double) =
            state.synchronize("ride", listOf(0.0, 10.0, 1.0, seconds, 0.0, 0.0, 1.0, sequence.toDouble()))
        fun points(seconds: Double) = metrics.mapIndexed { index, id ->
            mapOf("id" to id, "atS" to seconds, "value" to seconds * (index + 1), "key" to "view:1")
        }
        val old =
            JSONObject(
                mapOf(
                    "sourceId" to "ride",
                    "epoch" to 1,
                    "sequence" to 1,
                    "cursorPending" to false,
                    "points" to points(1.0),
                )
            )
        move(1, 1.0)
        state.receive(ChartSelection(old), metrics)
        move(2, 2.0)
        state.target(target(mapOf("epoch" to 1, "sequence" to 2, "points" to points(2.0))))
        assertTrue(state.points().isEmpty())
        assertEquals(listOf(2.0, 2.0), state.previews("view:1").map { it.atS })
        state.receive(ChartSelection(old), metrics)
        assertEquals(listOf(2.0, 4.0), state.previews("view:1").map { it.value })
        state.receive(
            selection(
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
        val state = ChartRasterMarkers()
        val point = mapOf("id" to "humanPowerW", "atS" to 1.0, "value" to 250.0, "key" to "view:1")
        fun move(seq: Int, active: Boolean = true) =
            state.synchronize(
                "ride",
                listOf(0.0, 10.0, if (active) 1.0 else 0.0, 1.0, 0.0, 0.0, 1.0, seq.toDouble()),
            )
        fun select(seq: Int, pending: Boolean, points: List<Payload>) =
            state.receive(
                selection(
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
        state.target(target(mapOf("epoch" to 1, "sequence" to 2, "points" to listOf(point))))
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
    fun acceptsOnlyTheCurrentSourceRevisionEpochAndSequence() {
        val accepted = mapOf<String, Any>("sourceId" to "ride", "revision" to "7", "epoch" to 2, "sequence" to 3)
        for ((change, expected) in
            listOf(
                emptyMap<String, Any>() to true,
                mapOf("sourceId" to "other") to false,
                mapOf("revision" to "6") to false,
                mapOf("epoch" to 1) to false,
                mapOf("sequence" to 2) to false,
                mapOf("sequence" to 4) to false,
            )) {
            val state = ChartRasterMarkers()
            state.synchronize("ride", listOf(0.0, 10.0, 1.0, 1.0, 0.0, 0.0, 2.0, 3.0), "7")
            state.receive(
                selection(
                    accepted +
                        change +
                        mapOf(
                            "cursorPending" to false,
                            "points" to listOf(mapOf("id" to "humanPowerW", "atS" to 1.125, "value" to 200.0)),
                        )
                ),
                setOf("humanPowerW"),
            )
            assertEquals(change.toString(), expected, state.points().isNotEmpty())
        }
    }
}
