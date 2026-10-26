package app.powerlog.bridge

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideEnergySeriesTest {
    private fun EnergyRide.series(
        smoothing: Int,
        buckets: Int,
        end: Double? = maxOf(10.0, time + 0.4),
        start: Double? = 0.0,
    ): Payload =
        energy(
            mapOf(
                    "startSeconds" to start,
                    "endSeconds" to end,
                    "buckets" to buckets,
                    "energySmoothingSeconds" to smoothing,
                    "energyTotals" to false,
                )
                .filterValues { it != null }
        )

    private fun EnergyRide.fresh(smoothing: Int, buckets: Int, end: Double? = maxOf(10.0, time + 0.4)): Payload =
        store.withReadPin(id) {
            store.energy.read(id, 0.0, end, buckets, smoothing, includeTotals = false, cached = false)
        }

    private fun EnergyRide.assertSeries(
        label: String,
        smoothing: Int,
        buckets: Int,
        end: Double? = maxOf(10.0, time + 0.4),
    ) {
        val expected = fresh(smoothing, buckets, end)
        assertEquals(label, expected, series(smoothing, buckets, end).filterKeys { it in expected })
    }

    private fun EnergyRide.rowsFrom(timeUs: Long): Long =
        store.select(
            "SELECT count(*) FROM observations WHERE ride=? AND elapsed_us>=? AND (kind='telemetry' OR (kind='location' AND ?))",
            arrayOf(id, timeUs, if (gps) 1 else 0),
        ) {
            it.moveToFirst()
            it.getLong(0)
        }

    @Test
    fun wholeRideSeriesResumeFromTheirCutAndMatchFreshAlignedQueries() {
        for (gps in listOf(false, true)) withEnergyRide(gps, "controller") { ride ->
            val energy = ride.store.energy
            ride.advance(1.0)
            for (smoothing in listOf(0, 30)) ride.assertSeries("gps $gps first $smoothing", smoothing, 64)
            val built = energy.seriesBuilt.get()
            for (step in 0 until 36) {
                val previous = ride.time
                if (step > 6 && ride.random.nextInt(10) == 0) ride.time += 20.0 + ride.random.nextInt(30)
                ride.advance(0.5 + ride.random.nextInt(4))
                val cut = minOf(maxOf(10.0, previous) - 2.5, previous)
                for (smoothing in listOf(0, 30)) {
                    val label = "gps $gps step $step smoothing $smoothing"
                    val expected = ride.fresh(smoothing, 64)
                    val resumed = energy.seriesResumed.get()
                    val fed = energy.fed.get()
                    assertEquals(label, expected, ride.series(smoothing, 64).filterKeys { it in expected })
                    assertEquals(label, resumed + 1, energy.seriesResumed.get())
                    assertEquals(label, ride.rowsFrom(elapsedUs(cut)), energy.fed.get() - fed)
                }
            }
            assertEquals(built, energy.seriesBuilt.get())
        }
    }

    @Test
    fun lateRowsAndLifecycleChangesRebuildTheSeries() =
        withEnergyRide(true, "controller") { ride ->
            val energy = ride.store.energy
            ride.advance(60.0)
            ride.assertSeries("first", 30, 64)
            fun change(label: String, rebuilds: Long, change: () -> Unit) {
                ride.advance(3.0)
                ride.assertSeries("$label before", 30, 64)
                change()
                val built = energy.seriesBuilt.get()
                ride.assertSeries(label, 30, 64)
                assertEquals(label, built + rebuilds, energy.seriesBuilt.get())
                ride.advance(2.0)
                ride.assertSeries("$label after", 30, 64)
                assertEquals(label, built + rebuilds, energy.seriesBuilt.get())
            }
            change("delayed telemetry after the cut", 0) {
                ride.store.transaction { ride.sample(ride.time - 2.75, "late") }
            }
            change("late telemetry", 1) { ride.store.transaction { ride.sample(ride.time - 20.25, "late") } }
            change("late location", 1) { ride.store.transaction { ride.fix(ride.time - 30.5) } }
            change("pause", 1) {
                ride.store.lifecycle(ride.id, RideTiming(ride.time, ride.time, iso()), "pause")
                ride.store.lifecycle(ride.id, RideTiming(ride.time, ride.time, iso()), "resume")
            }
            change("late pause and resume before the cut", 1) {
                ride.store.lifecycle(ride.id, RideTiming(ride.time - 20.0, ride.time - 20.0, iso()), "pause")
                ride.store.lifecycle(ride.id, RideTiming(ride.time - 10.0, ride.time - 10.0, iso()), "resume")
            }
        }

    @Test
    fun appendOnlyRevisionsWithoutReadsStayIncremental() =
        withEnergyRide(true, "controller") { ride ->
            val energy = ride.store.energy
            ride.advance(60.0)
            for (smoothing in listOf(5, 60)) ride.assertSeries("first $smoothing", smoothing, 64)
            val built = energy.seriesBuilt.get()
            val revision = ride.store.readRevision(ride.id)
            ride.advance(500.0)
            assertTrue(ride.store.readRevision(ride.id) - revision > 512)
            for (step in 0 until 2) {
                for (smoothing in listOf(5, 60)) {
                    val resumed = energy.seriesResumed.get()
                    ride.assertSeries("step $step smoothing $smoothing", smoothing, 64)
                    assertEquals(resumed + 1, energy.seriesResumed.get())
                }
                ride.advance(3.0)
            }
            assertEquals(built, energy.seriesBuilt.get())
        }

    @Test
    fun onlyReadsFromZeroThroughTheDataEndUseTheWholeRideSeries() =
        withEnergyRide(false, "controller") { ride ->
            val energy = ride.store.energy
            ride.advance(90.0)
            ride.assertSeries("first", 0, 64)
            assertEquals(ride.series(0, 64, end = ride.time), ride.series(0, 64, end = ride.time + 50.0))
            val counters = { listOf(energy.seriesBuilt.get(), energy.seriesResumed.get(), energy.seriesFresh.get()) }
            val before = counters()
            ride.series(0, 64, end = ride.time - 30.0)
            ride.series(0, 64, start = 10.0)
            assertEquals(before, counters())
        }

    @Test
    fun wholeRideSeriesSwitchToAbsoluteSlotsOnceTheRideSpansTheirBuckets() =
        withEnergyRide(false, "controller") { ride ->
            ride.advance(20.0)
            ride.assertSeries("exact width", 0, 40)
            ride.advance(100.0)
            ride.assertSeries("aligned", 0, 40)
            val points =
                ((ride.series(0, 40)["series"] as Map<*, *>)["riderWhPerKm"] as List<*>).map {
                    elapsedUs(((it as Map<*, *>)["elapsedSeconds"] as Number).toDouble())
                }
            for (slotStart in 4_000_000L until 120_000_000L step 4_000_000L) assertTrue(
                "$slotStart in $points",
                slotStart in points,
            )
        }

    @Test
    fun readsPinnedBeforeTheSeriesUseAFreshQueryAndKeepTheCache() =
        withEnergyRide(false, "controller") { ride ->
            val energy = ride.store.energy
            ride.advance(60.0)
            val end = ride.time + 0.4
            val pin = ride.store.pin(ride.id)
            ride.advance(30.0)
            ride.assertSeries("newer", 0, 64)
            val built = energy.seriesBuilt.get()
            val fresh = energy.seriesFresh.get()
            ride.store.withPin(pin) { ride.assertSeries("pinned", 0, 64, end) }
            assertEquals(fresh + 1, energy.seriesFresh.get())
            ride.advance(2.0)
            ride.assertSeries("current", 0, 64)
            assertEquals(built, energy.seriesBuilt.get())
        }

    @Test
    fun aSeriesThatOverflowsServesFreshQueriesWithoutRebuilding() =
        withEnergyRide(false, "controller") { ride ->
            val energy = RideEnergyPerDistance(ride.store, seriesPoints = 32)
            fun read(cached: Boolean) =
                ride.store.withReadPin(ride.id) {
                    energy.read(ride.id, 0.0, null, 64, 0, includeTotals = false, cached = cached)
                }
            ride.advance(120.0)
            repeat(3) {
                assertEquals(read(false), read(true))
                ride.advance(5.0)
            }
            assertEquals(1L, energy.seriesBuilt.get())
            assertEquals(2L, energy.seriesFresh.get())
        }
}
