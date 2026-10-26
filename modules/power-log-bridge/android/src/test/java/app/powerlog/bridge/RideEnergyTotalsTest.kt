package app.powerlog.bridge

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideEnergyTotalsTest {
    private fun EnergyRide.monitor(smoothing: Int, end: Double = time + 0.4): Payload =
        energy(
            mapOf(
                "startSeconds" to 0.0,
                "endSeconds" to end,
                "energySmoothingSeconds" to smoothing,
                "energyTotals" to true,
            )
        )

    private fun EnergyRide.full(smoothing: Int, end: Double = time + 0.4): Payload =
        store.withReadPin(id) { RideEnergyPerDistance(store).read(id, 0.0, end, 128, smoothing) }

    private fun EnergyRide.assertTotals(label: String, smoothing: Int, end: Double = time + 0.4) {
        val expected = full(smoothing, end)
        val actual = monitor(smoothing, end)
        assertEquals(label, expected, actual.filterKeys { it in expected })
        assertEquals(label, emptyMap<String, Any?>(), actual["series"])
    }

    @Test
    fun wholeRideTotalsResumeAfterEveryAppendAndMatchFullReads() {
        val rides = listOf(false to "controller", true to "controller", false to "calculated")
        for ((gps, battery) in rides) withEnergyRide(gps, battery) { ride ->
            val energy = ride.store.energy
            ride.advance(60.0)
            for (smoothing in listOf(0, 30)) ride.assertTotals("first $smoothing", smoothing)
            for (step in 0 until 40) {
                if (ride.random.nextInt(12) == 0) ride.time += 30.0 + ride.random.nextInt(30)
                ride.advance(0.5 + ride.random.nextInt(6))
                for (smoothing in listOf(0, 30)) {
                    val resumed = energy.resumed.get()
                    val restarted = energy.restarted.get()
                    ride.assertTotals("gps $gps $battery step $step smoothing $smoothing", smoothing)
                    assertEquals("step $step", resumed + 1, energy.resumed.get())
                    assertEquals("step $step", restarted, energy.restarted.get())
                }
            }
        }
    }

    @Test
    fun steadyStateTotalsReadsFeedOnlyRowsAfterTheirCheckpoint() =
        withEnergyRide(true, "controller") { ride ->
            val energy = ride.store.energy
            ride.advance(600.0)
            ride.assertTotals("first", 0)
            ride.advance(300.0)
            ride.assertTotals("caught up", 0)
            val restarted = energy.restarted.get()
            for (step in 0 until 5) {
                ride.advance(1.0)
                val fed = energy.fed.get()
                ride.assertTotals("step $step", 0)
                val resumedRows = energy.fed.get() - fed
                val tail =
                    ride.store.select(
                        "SELECT (SELECT count(*) FROM observations WHERE ride=? AND elapsed_us>?)+(SELECT count(*) FROM distance_intervals WHERE ride=? AND end_us>?)+(SELECT count(*) FROM battery_intervals WHERE ride=? AND end_us>?)",
                        elapsedUs(ride.time - 30.0).let { arrayOf(ride.id, it, ride.id, it, ride.id, it) },
                    ) {
                        it.moveToFirst()
                        it.getLong(0)
                    }
                assertTrue("step $step fed $resumedRows of a $tail-row tail", resumedRows in 1..tail)
            }
            assertEquals(restarted, energy.restarted.get())
        }

    @Test
    fun lateInputsAndLifecycleChangesRestartFromAFullReadAndKeepMatching() =
        withEnergyRide(true, "controller") { ride ->
            val energy = ride.store.energy
            ride.advance(120.0)
            ride.assertTotals("first", 0)
            fun restart(label: String, change: () -> Unit) {
                ride.advance(3.0)
                ride.assertTotals("$label before", 0)
                change()
                val restarted = energy.restarted.get()
                ride.assertTotals(label, 0)
                assertEquals(label, restarted + 1, energy.restarted.get())
                ride.advance(2.0)
                val resumed = energy.resumed.get()
                ride.assertTotals("$label after", 0)
                assertEquals(label, resumed + 1, energy.resumed.get())
            }
            restart("late telemetry") { ride.store.transaction { ride.sample(ride.time - 40.25, "late") } }
            restart("late location") { ride.store.transaction { ride.fix(ride.time - 50.5) } }
            restart("pause") {
                ride.store.lifecycle(ride.id, RideTiming(ride.time, ride.time, iso()), "pause")
                ride.store.lifecycle(ride.id, RideTiming(ride.time, ride.time, iso()), "resume")
            }
            restart("interruption") {
                val cutoff = RideTiming(ride.time - 60.0, ride.time - 60.0, iso())
                ride.store.lifecycle(ride.id, cutoff, "pause", true, evidence = mapOf("controllerSequence" to "0"))
            }
        }

    @Test
    fun readsPinnedBeforeTheCheckpointUseAFullReadAndKeepTheNewerCheckpoint() =
        withEnergyRide(false, "controller") { ride ->
            val energy = ride.store.energy
            ride.advance(120.0)
            val end = ride.time + 0.4
            val pin = ride.store.pin(ride.id)
            ride.advance(60.0)
            ride.assertTotals("newer", 0)
            val restarted = energy.restarted.get()
            ride.store.withPin(pin) { ride.assertTotals("pinned", 0, end) }
            assertEquals(restarted + 1, energy.restarted.get())
            val resumed = energy.resumed.get()
            ride.assertTotals("current", 0)
            assertEquals(resumed + 1, energy.resumed.get())
        }

    @Test
    fun appendOnlyRevisionsWithoutReadsStayIncremental() =
        withEnergyRide(false, "calculated") { ride ->
            val energy = ride.store.energy
            ride.advance(120.0)
            ride.assertTotals("first", 0)
            val revision = ride.store.revision(ride.id)
            ride.advance(350.0)
            assertTrue(ride.store.revision(ride.id) - revision >= 700)
            val resumed = energy.resumed.get()
            ride.assertTotals("after", 0)
            assertEquals(resumed + 1, energy.resumed.get())
        }
}
