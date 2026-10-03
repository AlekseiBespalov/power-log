package app.powerlog.bridge

import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideDetailTest {
    private lateinit var store: RideStore
    private lateinit var distance: RideDistance
    private lateinit var detail: RideDetail
    private lateinit var id: String

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        store = RideStore(context, "detail-${UUID.randomUUID()}.sqlite")
        distance = RideDistance(store)
        detail = RideDetail(store, distance, RideMonitor(store, distance))
        id =
            store.create(
                RideOptions(indoor = false, saveToHealth = false, recordGPS = false),
                SystemRecordingClock.read(),
            )
        store.update(id, "running", RideTiming(0.0, 0.0, iso()), mapOf("startedAt" to iso(1767225600000L)))
    }

    @After
    fun close() {
        store.close()
    }

    private fun power(time: Double, value: Double, active: Boolean = true, segment: Int = 0) =
        store.insert(
            id,
            time,
            iso(1767225600000L + (time * 1000).toLong()),
            "telemetry",
            active,
            segment,
            mapOf("humanPowerW" to value),
            "X6|synthetic|5.3",
            "one",
        )

    @Test
    fun partialDistanceStaysInTheSummaryDistanceWithoutRideNotices() {
        store.transaction {
            for (time in listOf(12.0, 13.0)) {
                val values = mapOf("controllerSpeedMps" to 4.0, "humanPowerW" to 120.0)
                val row = store.insert(id, time, iso(), "telemetry", true, 0, values, "X6|synthetic|5.3", "one")
                distance.append(id, row, time, values, true, 0, "one", "X6|synthetic|5.3", false)
            }
        }
        store.seal(id, RideTiming(13.0, 13.0, iso()))
        val summary = detail.detail(id, "auto")["summary"] as Map<*, *>
        val selected = (summary["distance"] as Map<*, *>)["selected"] as Map<*, *>
        assertEquals("controller", selected["source"])
        assertEquals(true, selected["partial"])
        assertFalse(summary.containsKey("warnings"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun pausedPowerIsExcludedFromStatisticsAndSummaryButRetainedInOriginals() {
        store.transaction {
            power(0.0, 100.0)
            power(1.0, 200.0)
            power(16.0, 1000.0, false, 1)
            power(17.0, 0.0, false, 1)
            power(32.0, 200.0, true, 2)
            power(33.0, 300.0, true, 2)
            store.lifecycle(id, RideTiming(2.0, 0.0, iso()), "pause")
            store.lifecycle(id, RideTiming(32.0, 0.0, iso()), "resume")
        }
        store.seal(id, RideTiming(34.0, 4.0, iso()))
        val stats = store.analytics.stats(id, "humanPowerW", 0.0, 34.0)
        assertEquals(4L, stats["count"])
        assertEquals(200.0, stats.num("sampleMean"), 0.0)
        assertEquals(100.0, (stats["min"] as Payload).num("value"), 0.0)
        assertEquals(300.0, (stats["max"] as Payload).num("value"), 0.0)
        assertEquals(400.0, stats.num("integral"), 0.0)
        assertEquals(2.0, stats.num("coveredSeconds"), 0.0)
        for ((start, end) in listOf(16.0 to 17.0, 16.5 to 17.5)) {
            val paused = store.analytics.stats(id, "humanPowerW", start, end)
            assertEquals(0L, paused["count"])
            assertNull(paused["sampleMean"])
            assertNull(paused["min"])
            assertNull(paused["max"])
            assertEquals(0.0, paused.num("integral"), 0.0)
        }
        val summary = detail.detail(id, "auto")["summary"] as Payload
        assertEquals(300.0, summary.num("maximumRiderPowerW"), 0.0)
        val points = store.analytics.plot(id, "humanPowerW", 0.0, 34.0, 8)
        assertTrue(points.any { it.num("value") == 1000.0 })
        assertEquals(
            listOf(100.0, 200.0, 1000.0, 0.0, 200.0, 300.0),
            store.page(id).map { it.values.getValue("humanPowerW") },
        )
    }

    @Test
    fun completedDetailUsesTheSealedCutoffAndLifecycleKeepsItsUtcAcrossClockJumps() {
        val started = store.events(id).single()
        val pause = RideTiming(2.0, 2.0, "2026-01-01T01:00:00.000Z")
        val resume = RideTiming(3.0, 2.0, "2025-12-31T23:00:00.000Z")
        val lap = RideTiming(4.0, 3.0, "2026-01-01T02:00:00.000Z")
        val cutoff = RideTiming(5.0, 4.0, "2025-12-31T22:00:00.000Z")
        store.transaction {
            power(1.0, 100.0)
            store.transition(id, pause, "pause")
            store.transition(id, resume, "resume")
            store.lifecycle(id, lap, "lap")
            store.update(id, "running", lap)
            power(4.0, 200.0)
        }
        store.seal(id, cutoff, interrupted = true)
        assertEquals(
            listOf(
                started,
                RideEvent(2.0, "pause", pause.timestamp),
                RideEvent(3.0, "resume", resume.timestamp),
                RideEvent(4.0, "lap", lap.timestamp),
                RideEvent(5.0, "stop", cutoff.timestamp),
            ),
            store.events(id),
        )
        val summary = detail.detail(id, "auto")["summary"] as Payload
        assertEquals(cutoff.timestamp, summary["endedAt"])
        assertEquals(cutoff.elapsed, summary.num("elapsedSeconds"), 0.0)
        assertEquals(cutoff.timer, summary.num("timerSeconds"), 0.0)
        assertEquals(2.0, summary.num("lapCount"), 0.0)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun detailWithoutAnEndUsesTheRetainedCheckpointUtc() {
        val timing = RideTiming(10.0, 6.0, "2025-12-31T23:00:00.000Z")
        store.update(id, "paused", timing)
        repeat(2) {
            val summary = detail.detail(id, "auto")["summary"] as Payload
            assertEquals(timing.timestamp, summary["endedAt"])
            assertEquals(timing.elapsed, summary.num("elapsedSeconds"), 0.0)
            assertEquals(timing.timer, summary.num("timerSeconds"), 0.0)
        }
    }
}
