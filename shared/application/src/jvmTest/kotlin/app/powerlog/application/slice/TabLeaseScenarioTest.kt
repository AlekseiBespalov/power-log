package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.recording.TabLeases
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.RideTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async

class TabLeaseScenarioTest {
    @Test
    fun anotherTabsRunningRideMakesEveryControlOwnerForeignUntilThatTabCloses() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            val other = scenario.otherTabLease()
            val ride = scenario.seedOpenRide(RidePhase.running)
            assertEquals(LaunchResult.Ready, scenario.launch())
            val foreign = scenario.recording { it.otherTab }
            assertNull(foreign.phase)
            for (availability in
                listOf(
                    foreign.allowed.start,
                    foreign.allowed.pause,
                    foreign.allowed.resume,
                    foreign.allowed.lap,
                    foreign.allowed.finish,
                    foreign.allowed.discard,
                )) assertEquals(FailureCode.ownerForeign, availability.reason)
            assertEquals(FailureCode.ownerForeign, scenario.api.recording.start(Empty).code())
            assertEquals(1, scenario.lock.waiting(TabLeases.LOCK))

            other.release()
            val interrupted = scenario.recording { it.phase == RidePhase.interrupted }
            assertFalse(interrupted.otherTab)
            assertEquals(ride.id, interrupted.ride?.rideId)
            assertTrue(interrupted.allowed.finish.enabled)
            assertTrue(interrupted.allowed.discard.enabled)
            assertEquals(Mutation.SetPhase(ride.id, RidePhase.interrupted), scenario.mutations().single())
            assertNull(scenario.lock.holder(TabLeases.LOCK))
        }
    }

    @Test
    fun anotherTabsStartArrivesAsANoticeThatQueuesTheWaitingAcquire() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val other = scenario.otherTabLease()
            val ride = scenario.seedOpenRide(RidePhase.running, otherTab = true)
            scenario.recording { it.otherTab }
            assertEquals(1, scenario.lock.waiting(TabLeases.LOCK))

            other.release()
            val interrupted = scenario.recording { it.phase == RidePhase.interrupted }
            assertEquals(ride.id, interrupted.ride?.rideId)
            assertTrue(interrupted.allowed.finish.enabled)
            assertTrue(interrupted.allowed.discard.enabled)
            assertNull(scenario.lock.holder(TabLeases.LOCK))
        }
    }

    @Test
    fun aLeftoverRunningRideMeetsStartUnderTheLease() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.indoor()
            val ride = scenario.seedOpenRide(RidePhase.running, otherTab = true, notify = false)
            assertEquals(FailureCode.rideActive, scenario.api.recording.start(Empty).code())
            val interrupted = scenario.recording { it.phase == RidePhase.interrupted }
            assertEquals(ride.id, interrupted.ride?.rideId)
            assertEquals(Mutation.SetPhase(ride.id, RidePhase.interrupted), scenario.mutations().last())
            assertNull(scenario.lock.holder(TabLeases.LOCK))
        }
    }

    @Test
    fun aTabWithoutTheRideRestoresAnInterruptedOneWhenItStarts() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.indoor()
            val ride = scenario.seedOpenRide(RidePhase.interrupted, otherTab = true, notify = false)
            val before = scenario.mutations().size
            assertEquals(FailureCode.rideActive, scenario.api.recording.start(Empty).code())
            assertEquals(ride.id, scenario.recording { it.phase == RidePhase.interrupted }.ride?.rideId)
            assertEquals(before, scenario.mutations().size)
            assertNull(scenario.lock.holder(TabLeases.LOCK))
        }
    }

    @Test
    fun anIdleTabFinishesAnInterruptedRideUnderTheLease() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            val ride = scenario.seedOpenRide(RidePhase.interrupted)
            scenario.archive.lastFact = StoreResult.Ok(RetainedEnd(Scenario.START_UTC_US + 4_000_000, 4_000_000))
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.recording { it.phase == RidePhase.interrupted }
            val leased = mutableListOf<Boolean>()
            scenario.archive.onCommit = { batch ->
                if (batch.mutations.any { it is Mutation.EndRide })
                    leased += scenario.lock.holder(TabLeases.LOCK) != null
                null
            }
            assertEquals(Outcome.Ok(Unit), scenario.control(ride.id, RideAction.finish))
            assertEquals(listOf(true), leased)
            assertNull(scenario.lock.holder(TabLeases.LOCK))
            assertNull(scenario.recording { it.phase == null }.ride)
        }
    }

    @Test
    fun aTabDropsAnInterruptedRideAnotherTabFinished() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            val ride = scenario.seedOpenRide(RidePhase.interrupted)
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.recording { it.phase == RidePhase.interrupted }
            scenario.archive.otherTab(finished(ride.id))
            assertNull(scenario.recording { it.phase == null }.ride)
            assertTrue(scenario.archive.attempts.isEmpty())
        }
    }

    @Test
    fun aFinishUnderWayWaitsForTheLeaseThenAnswersRideChanged() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            val ride = scenario.seedOpenRide(RidePhase.interrupted)
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.recording { it.phase == RidePhase.interrupted }
            val other = scenario.otherTabLease()
            val finish = async { scenario.control(ride.id, RideAction.discard) }
            runCurrent()
            assertEquals(1, scenario.lock.waiting(TabLeases.LOCK))
            scenario.archive.otherTab(finished(ride.id))
            runCurrent()
            assertFalse(finish.isCompleted)

            other.release()
            assertEquals(FailureCode.rideChanged, finish.await().code())
            assertTrue(scenario.archive.attempts.isEmpty())
            assertNull(scenario.lock.holder(TabLeases.LOCK))
            assertNull(scenario.recording { it.phase == null }.ride)
        }
    }

    @Test
    fun aStartRefusedAfterItTookTheLeaseReleasesItAtOnce() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.indoor()
            assertEquals(FailureCode.bikeRequired, scenario.api.recording.start(Empty).code())
            assertNull(scenario.lock.holder(TabLeases.LOCK))
            scenario.otherTabLease().release()
        }
    }

    private fun finished(rideId: app.powerlog.domain.core.ids.RideId): WriteBatch {
        val end = RideTiming(Scenario.START_UTC_US + 5_000_000, 5_000_000, 5_000_000)
        return WriteBatch(listOf(Mutation.SetPhase(rideId, RidePhase.completed), Mutation.EndRide(rideId, end)))
    }
}
