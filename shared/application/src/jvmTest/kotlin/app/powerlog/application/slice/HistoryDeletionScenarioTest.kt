package app.powerlog.application.slice

import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.RideInput
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class HistoryDeletionScenarioTest {
    @Test
    fun deletingAStoredRideRemovesItAndADeletedOrUnknownRideIsOk() {
        val scenario = Scenario()
        scenario.test {
            val seeded =
                scenario.seedRide(endUs = 10_000_000, readingsUs = (0L until 10_000_000L step 250_000L).toList())
            assertEquals(LaunchResult.Ready, scenario.launch())
            val input = RideInput(seeded.ride.id)
            assertEquals(Outcome.Ok(Unit), scenario.api.history.delete(input))
            assertTrue(scenario.page().isEmpty())
            val ride = scenario.api.history.ride.next(input, null, "ride")
            assertEquals(FailureCode.rideDeleted, assertIs<StreamAnswer.Failed>(ride).failure.code)
            val deletes = scenario.mutations().filterIsInstance<Mutation.MarkRideDeleted>()
            assertEquals(listOf(seeded.ride.id), deletes.map { it.rideId })
            assertTrue(scenario.mutations().any { it == Mutation.RemoveHealthWork(seeded.ride.id, null) })

            assertEquals(Outcome.Ok(Unit), scenario.api.history.delete(input))
            assertEquals(Outcome.Ok(Unit), scenario.api.history.delete(RideInput(RideId("never-stored"))))
            assertEquals(1, scenario.mutations().filterIsInstance<Mutation.MarkRideDeleted>().size)
        }
    }

    @Test
    fun aRideThatRecordsIsNotDeleted() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            assertEquals(FailureCode.rideInProgress, scenario.api.history.delete(RideInput(rideId)).code())
            assertTrue(scenario.mutations().none { it is Mutation.MarkRideDeleted })
        }
    }

    @Test
    fun aDeletedRidesCacheIsDroppedBeforeAndAfterItsCleanupStepsThenTheStoreReclaimed() {
        val scenario = Scenario()
        scenario.test {
            val seeded =
                scenario.seedRide(endUs = 600_000_000, readingsUs = (0L until 600_000_000L step 62_500L).toList())
            assertEquals(LaunchResult.Ready, scenario.launch())
            assertEquals(Outcome.Ok(Unit), scenario.api.history.delete(RideInput(seeded.ride.id)))
            delay(1_000)
            val steps = scenario.archive.journal.filter { "Step" in it || it.startsWith("dropRide") }
            val id = seeded.ride.id.value
            assertTrue(steps.count { it == "cleanupStep($id)" } > 1, "$steps")
            assertEquals(2, steps.count { it == "dropRide($id)" })
            assertTrue(steps.indexOf("dropRide($id)") < steps.indexOf("cleanupStep($id)"))
            assertTrue(steps.lastIndexOf("dropRide($id)") > steps.lastIndexOf("cleanupStep($id)"))
            assertTrue(steps.last() == "reclaimStep")
        }
    }

    @Test
    fun aCleanupAProcessEndInterruptedResumesAtLaunch() {
        val scenario = Scenario()
        scenario.test {
            val seeded =
                scenario.seedRide(endUs = 10_000_000, readingsUs = (0L until 10_000_000L step 250_000L).toList())
            scenario.archive.seed(WriteBatch(listOf(Mutation.MarkRideDeleted(seeded.ride.id, Scenario.START_UTC_US))))
            assertEquals(LaunchResult.Ready, scenario.launch())
            delay(1_000)
            assertTrue("dropRide(${seeded.ride.id.value})" in scenario.archive.journal)
        }
    }

    @Test
    fun deletingARideCancelsItsHealthWork() {
        val scenario = Scenario(app.powerlog.domain.core.ride.Platform.android)
        val background = app.powerlog.application.fakes.FakeBackgroundScheduler()
        scenario.backgroundScheduler = background
        scenario.health = app.powerlog.application.fakes.FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        scenario.test {
            val seeded =
                scenario.seedRide(
                    endUs = 10_000_000,
                    readingsUs = (0L until 10_000_000L step 250_000L).toList(),
                    recordedBy = app.powerlog.domain.core.ride.Device.androidPhone,
                    provider = app.powerlog.domain.core.health.HealthProviderKind.healthConnect,
                )
            assertEquals(LaunchResult.Ready, scenario.launch())
            background.schedule(seeded.ride.id.value, null, app.powerlog.application.port.ScheduleKind.replace)
            assertEquals(Outcome.Ok(Unit), scenario.api.history.delete(RideInput(seeded.ride.id)))
            assertTrue(seeded.ride.id.value in background.cancelled)
            assertTrue(background.pending(seeded.ride.id.value).isEmpty())
        }
    }

    @Test
    fun cleanupRunsInStepsWhileAnotherRideRecords() {
        val scenario = Scenario()
        scenario.test {
            val seeded =
                scenario.seedRide(endUs = 600_000_000, readingsUs = (0L until 600_000_000L step 62_500L).toList())
            val rideId = with(scenario) { riding() }
            delay(3_000)
            assertEquals(Outcome.Ok(Unit), scenario.api.history.delete(RideInput(seeded.ride.id)))
            delay(5_000)
            val journal = scenario.archive.journal
            val steps = journal.withIndex().filter { it.value == "cleanupStep(${seeded.ride.id.value})" }
            assertTrue(steps.size > 1, "$journal")
            val telemetry = journal.withIndex().filter { "AppendTelemetry" in it.value }.map { it.index }
            assertTrue(telemetry.any { it > steps.last().index }, "recording goes on after the cleanup")
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, app.powerlog.domain.core.ride.RideAction.finish))
        }
    }
}
