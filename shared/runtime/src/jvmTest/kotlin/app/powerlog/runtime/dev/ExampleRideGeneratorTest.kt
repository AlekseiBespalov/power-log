package app.powerlog.runtime.dev

import app.powerlog.application.PagePolicy
import app.powerlog.application.store.Mutation
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RidePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExampleRideGeneratorTest {
    private class Written(val mutations: List<Mutation>, val batches: Int)

    private fun written(generator: ExampleRideGenerator, rideId: RideId): Written {
        val batches = generateSequence(0) { it + 1 }.map { generator.batch(rideId, it) }.takeWhile { it != null }
        val all = batches.map { requireNotNull(it) }.toList()
        for (batch in all.drop(1)) {
            val rows = batch.mutations.sumOf { (it as? Mutation.AppendTelemetry)?.rows?.rows ?: 0 }
            assertTrue(rows <= PagePolicy.rows, "$rows rows in a batch")
        }
        return Written(all.flatMap { it.mutations }, all.size)
    }

    @Test
    fun threeCompletedExampleRidesWithTodaysIdsDurationsAndWatchHealthSamples() {
        val generator = ExampleRideGenerator({ false }) { Device.iphone }
        assertEquals(
            listOf(1, 2, 3).map { RideId("e8a00000-0000-4000-8000-00000000000$it") },
            generator.rideIds,
        )
        for ((index, rideId) in generator.rideIds.withIndex()) {
            val durationS = ExampleRideGenerator.DURATIONS_S[index]
            val mutations = written(generator, rideId).mutations
            val ride = assertIs<Mutation.CreateRide>(mutations.first()).ride
            assertTrue(ride.example)
            assertEquals(RidePhase.completed, ride.phase)
            assertEquals(durationS * 1_000_000L, ride.end?.elapsedUs)
            assertEquals(1_788_679_800_000_000L - index * 172_800_000_000L, ride.startedUtcUs)
            assertTrue(ride.options.useWatch && ride.options.recordGps && !ride.options.saveToHealth)
            val rows = mutations.filterIsInstance<Mutation.AppendTelemetry>().sumOf { it.rows.rows }
            assertEquals(durationS * 4 + 1, rows)
            val events = mutations.filterIsInstance<Mutation.AppendEvents>().flatMap { it.events }.map { it.kind }
            assertEquals(listOf(RideEventKind.start, RideEventKind.lap, RideEventKind.stop), events)
            val samples = mutations.filterIsInstance<Mutation.AppendHealthSamples>().flatMap { it.samples }
            assertEquals(3 * (durationS + 1), samples.size)
            assertTrue(samples.all { it.producer == Producer.watch })
            assertTrue(samples.all { it.dataOrigin == ExampleRideGenerator.HEALTH_DATA_ORIGIN })
            assertEquals(samples.size, samples.map { it.sampleKey }.toSet().size)
        }
    }

    @Test
    fun aBatchAskedOutOfOrderEqualsTheSameBatchAskedInOrder() {
        val generator = ExampleRideGenerator({ false }) { Device.androidPhone }
        val rideId = generator.rideIds.first()
        val inOrder = (0..3).map { generator.batch(rideId, it) }
        val again = ExampleRideGenerator({ false }) { Device.androidPhone }
        assertEquals(inOrder[3]?.mutations?.size, again.batch(rideId, 3)?.mutations?.size)
        val first = assertIs<Mutation.AppendTelemetry>(inOrder[3]?.mutations?.first())
        val second = assertIs<Mutation.AppendTelemetry>(again.batch(rideId, 3)?.mutations?.first())
        assertEquals(
            first.rows.quanta.mapValues { it.value.toList() },
            second.rows.quanta.mapValues { it.value.toList() },
        )
    }

    @Test
    fun theLongRideSwitchAddsAnEightHourRideAtEightHertzWithoutHealth() {
        val generator = ExampleRideGenerator({ true }) { Device.iphone }
        val rideId = generator.rideIds.last()
        assertEquals(RideId("e8a00000-0000-4000-8000-000000000004"), rideId)
        val mutations = written(generator, rideId).mutations
        val ride = assertIs<Mutation.CreateRide>(mutations.first()).ride
        assertEquals(1_789_198_200_000_000L, ride.startedUtcUs)
        assertEquals(8 * 3_600_000_000L, ride.end?.elapsedUs)
        assertEquals(8 * 3_600 * 8 + 1, mutations.filterIsInstance<Mutation.AppendTelemetry>().sumOf { it.rows.rows })
        assertTrue(mutations.none { it is Mutation.AppendHealthSamples })
    }
}
