package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.history.AddedRidesView
import app.powerlog.application.history.FakeExampleRideSource
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthOutcomeRules
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExampleRidesScenarioTest {
    @Test
    fun withoutASourceExampleRidesAreUnavailable() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            assertEquals(FailureCode.actionUnavailable, scenario.api.history.addExampleRides(Empty).code())
        }
    }

    @Test
    fun exampleRidesAreWrittenBatchByBatchOnceAndASecondCallAddsNone() {
        val scenario = Scenario()
        val source = FakeExampleRideSource(mapOf(example(1), example(2)))
        scenario.exampleRides = source
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            assertEquals(Outcome.Ok(AddedRidesView(2)), scenario.api.history.addExampleRides(Empty))
            assertEquals(2, scenario.page().size)
            assertTrue(scenario.page().all { it.example && it.phase == RidePhase.completed })
            assertEquals(
                listOf(RideId("example-1") to 0, RideId("example-1") to 1, RideId("example-1") to 2) +
                    listOf(RideId("example-2") to 0, RideId("example-2") to 1, RideId("example-2") to 2),
                source.requests,
            )
            assertEquals(Outcome.Ok(AddedRidesView(0)), scenario.api.history.addExampleRides(Empty))
            assertEquals(2, scenario.page().size)
        }
    }

    @Test
    fun exampleRidesAreRefusedWhileARideIsOpen() {
        val scenario = Scenario()
        scenario.exampleRides = FakeExampleRideSource(mapOf(example(1)))
        scenario.test {
            with(scenario) { riding() }
            assertEquals(FailureCode.rideActive, scenario.api.history.addExampleRides(Empty).code())
            assertTrue(scenario.mutations().none { it is Mutation.CreateRide && it.ride.example })
        }
    }

    private fun example(n: Int): Pair<RideId, List<WriteBatch>> {
        val id = RideId("example-$n")
        val startedUtcUs = Scenario.START_UTC_US - n * 86_400_000_000L
        val end = RideTiming(startedUtcUs + 60_000_000, 60_000_000, 60_000_000)
        val options =
            RideOptions(SampleRate.hz4, false, true, false, true, DistanceSource.phoneGps, BatteryMethod.controller)
        val ride = Ride(id, Device.iphone, true, startedUtcUs, options, RidePhase.completed, false, end, end)
        return id to
            listOf(
                WriteBatch(listOf(Mutation.CreateRide(ride, HealthOutcomeRules.initial(options, null)))),
                WriteBatch(emptyList()),
            )
    }
}
