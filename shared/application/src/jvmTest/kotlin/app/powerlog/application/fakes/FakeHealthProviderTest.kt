package app.powerlog.application.fakes

import app.powerlog.application.port.FinishedWorkout
import app.powerlog.application.port.HealthAvailability
import app.powerlog.application.port.HealthGrants
import app.powerlog.application.port.HealthOriginal
import app.powerlog.application.port.HealthOriginalPage
import app.powerlog.application.port.HealthScope
import app.powerlog.application.port.LiveHeartRate
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.SessionOptions
import app.powerlog.application.port.SessionState
import app.powerlog.application.port.WorkoutSessionEvents
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthQuantity
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.health.HealthConnectRecordPlan
import app.powerlog.domain.health.HealthPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeHealthProviderTest {
    private val queue = FakePortQueue()
    private val clock = FakeClock()
    private val ride = "00000000-0000-4000-8000-000000000001"

    @Test
    fun eachProviderHasItsOwnParts() {
        val apple = FakeHealthProvider.apple(clock, queue::post)
        val connect = FakeHealthProvider.healthConnect(clock, queue::post)

        assertEquals(HealthProviderKind.appleHealth, apple.kind)
        assertNotNull(apple.session)
        assertNull(apple.writer)
        assertEquals(HealthProviderKind.healthConnect, connect.kind)
        assertNull(connect.session)
        assertNotNull(connect.writer)
    }

    @Test
    fun availabilityAndGrantsAreScripted() {
        val provider = FakeHealthProvider.healthConnect(clock, queue::post)
        provider.access.availability = HealthAvailability.updateRequired
        provider.access.grants = provider.access.grants.copy(readableFromUtcUs = 5_000)
        val grants = ReplyProbe<HealthGrants>()
        val scope = HealthScope(write = emptySet(), read = setOf("heartRate"), route = false, backgroundRead = true)
        val requested = ReplyProbe<Unit>()

        provider.access.grants(grants.reply)
        queue.drain()
        provider.access.request(scope, requested.reply)
        queue.drain()

        assertEquals(HealthAvailability.updateRequired, provider.access.availability())
        assertEquals(5_000, grants.values.single().readableFromUtcUs)
        assertEquals(listOf(scope), provider.access.requests.received)
        assertEquals(1, requested.values.size)
    }

    @Test
    fun aHealthConnectCallInFlightMakesEveryOtherCallBusy() {
        val provider = FakeHealthProvider.healthConnect(clock, queue::post)
        provider.writer!!.inserts.script = { Answer.Held }
        val insert = ReplyProbe<List<String>>()
        val read = ReplyProbe<HealthOriginalPage>()
        val grants = ReplyProbe<HealthGrants>()

        provider.writer!!.insert(emptyList(), insert.reply)
        provider.reader.read("heartRate", 0, 10, null, read.reply)
        provider.access.grants(grants.reply)
        queue.drain()

        assertFalse(insert.settled)
        assertEquals(listOf(PortErrorKind.busy), read.kinds)
        assertEquals(listOf(PortErrorKind.busy), grants.kinds)
    }

    @Test
    fun appleCallsDoNotShareOneFlight() {
        val provider = FakeHealthProvider.apple(clock, queue::post)
        provider.access.requests.script = { Answer.Held }
        val read = ReplyProbe<HealthOriginalPage>()

        provider.access.request(HealthScope(emptySet(), emptySet(), false, false), ReplyProbe<Unit>().reply)
        provider.reader.read("heartRate", 0, 10, null, read.reply)
        queue.drain()

        assertEquals(1, read.values.size)
    }

    @Test
    fun scriptedFailuresKeepTheirKinds() {
        val provider = FakeHealthProvider.healthConnect(clock, queue::post)
        val kinds = listOf(PortErrorKind.denied, PortErrorKind.rateLimited, PortErrorKind.invalid, PortErrorKind.failed)
        val outcomes = kinds.map { kind ->
            provider.writer!!.inserts.script = { Answer.Failed(PortError(kind)) }
            ReplyProbe<List<String>>().also {
                provider.writer!!.insert(emptyList(), it.reply)
                queue.drain()
            }
        }

        assertEquals(kinds, outcomes.flatMap { it.kinds })
    }

    @Test
    fun readsPageOverlappingSamplesOfTheMetricUntilNextIsNull() {
        val provider = FakeHealthProvider.healthConnect(clock, queue::post)
        provider.reader.samples += List(130) { sample("hr-$it", "heartRate", startUtcUs = it * 10L) }
        provider.reader.samples += sample("power-0", "riderPower", startUtcUs = 0)
        provider.reader.samples += sample("hr-late", "heartRate", startUtcUs = 5_000)

        val first = ReplyProbe<HealthOriginalPage>()
        provider.reader.read("heartRate", 0, 2_000, null, first.reply)
        queue.drain()
        val second = ReplyProbe<HealthOriginalPage>()
        provider.reader.read("heartRate", 0, 2_000, first.values.single().next, second.reply)
        queue.drain()

        assertEquals(128, first.values.single().samples.size)
        assertNotNull(first.values.single().next)
        assertEquals(listOf("hr-128", "hr-129"), second.values.single().samples.map { it.sampleId })
        assertNull(second.values.single().next)
    }

    @Test
    fun insertsAnswerOneIdPerPlanInInputOrder() {
        val provider = FakeHealthProvider.healthConnect(clock, queue::post)
        val plans = List(3) { HealthConnectRecordPlan.Record(quantity("q-$it")) }
        val probe = ReplyProbe<List<String>>()

        provider.writer!!.insert(plans, probe.reply)
        queue.drain()

        assertEquals(listOf("record-1", "record-2", "record-3"), probe.values.single())
        assertEquals(listOf(plans), provider.writer!!.inserts.received)
    }

    @Test
    fun aSessionBeginsOnceFinishesWithItsIdAndRemembersTheSavedWorkout() {
        val session = FakeHealthProvider.apple(clock, queue::post).session!!
        session.attach(RecordingSessionEvents())
        val begin = ReplyProbe<Unit>()
        val again = ReplyProbe<Unit>()
        val add = ReplyProbe<Unit>()
        val lap = ReplyProbe<Unit>()
        val finish = ReplyProbe<FinishedWorkout>()

        session.begin(workout(), SessionOptions(indoor = false, route = true), begin.reply)
        session.begin(workout(), SessionOptions(indoor = false, route = true), again.reply)
        session.pause()
        session.resume()
        session.add(listOf(quantity("q-1")), add.reply)
        session.addLap(2_000, lap.reply)
        session.finish(3_000, HealthPlan.Route("route", emptyList()), finish.reply)
        queue.drain()
        val saved = ReplyProbe<String?>()
        val unknown = ReplyProbe<String?>()
        session.savedWorkout(ride, saved.reply)
        session.savedWorkout("00000000-0000-4000-8000-000000000002", unknown.reply)
        queue.drain()

        assertEquals(listOf(Unit), begin.values)
        assertEquals(listOf(PortErrorKind.busy), again.kinds)
        assertEquals(listOf("pause", "resume"), session.commands)
        assertEquals(listOf(2_000L), session.laps.received)
        assertTrue(finish.values.single().routeSaved)
        assertEquals(finish.values.single().workoutId, saved.values.single())
        assertEquals(listOf<String?>(null), unknown.values)
        assertNull(session.workout)
    }

    @Test
    fun aFailedBeginLeavesNoSessionSoTheNextBeginRuns() {
        val session = FakeHealthProvider.apple(clock, queue::post).session!!
        session.attach(RecordingSessionEvents())
        session.begins.script = { Answer.Failed(PortError(PortErrorKind.denied)) }
        val failed = ReplyProbe<Unit>()

        session.begin(workout(), SessionOptions(indoor = false, route = false), failed.reply)
        queue.drain()
        assertNull(session.workout)
        session.begins.script = { Answer.Ok(Unit) }
        val next = ReplyProbe<Unit>()
        session.begin(workout(), SessionOptions(indoor = false, route = false), next.reply)
        queue.drain()

        assertEquals(listOf(PortErrorKind.denied), failed.kinds)
        assertEquals(listOf(Unit), next.values)
    }

    @Test
    fun discardEndsTheSessionAndAnOrphanIsRecoveredOnce() {
        val session = FakeHealthProvider.apple(clock, queue::post).session!!
        session.attach(RecordingSessionEvents())
        session.orphan = ride

        session.begin(workout(), SessionOptions(indoor = true, route = false), ReplyProbe<Unit>().reply)
        session.discard(4_000)
        val first = ReplyProbe<String?>()
        val second = ReplyProbe<String?>()
        session.recoverOrphan(first.reply)
        session.recoverOrphan(second.reply)
        queue.drain()

        assertNull(session.workout)
        assertEquals(listOf("discard 4000"), session.commands)
        assertEquals(listOf<String?>(ride), first.values)
        assertEquals(listOf<String?>(null), second.values)
    }

    @Test
    fun sessionEventsArriveStampedAfterTheCall() {
        val session = FakeHealthProvider.apple(clock, queue::post).session!!
        val events = RecordingSessionEvents()
        session.attach(events)
        clock.advanceTo(7_000)

        session.state(SessionState.running, 1_000)
        session.heartRate(LiveHeartRate(bpm = 120.0, utcUs = 900))
        session.collect(listOf(sample("hr-1", "heartRate", 0)))
        session.fail(PortError(PortErrorKind.locked))
        assertTrue(events.log.isEmpty())
        queue.drain()

        assertEquals(
            listOf("state running 1000", "heartRate 120.0 at 7000", "samples 1 at 7000", "failed locked"),
            events.log,
        )
    }

    private class RecordingSessionEvents : WorkoutSessionEvents {
        val log = mutableListOf<String>()

        override fun stateChanged(state: SessionState, atUtcUs: Long) {
            log += "state $state $atUtcUs"
        }

        override fun failed(error: PortError) {
            log += "failed ${error.kind}"
        }

        override fun heartRateReceived(heartRate: LiveHeartRate, at: ClockReading) {
            log += "heartRate ${heartRate.bpm} at ${at.monoUs}"
        }

        override fun samplesCollected(samples: List<HealthOriginal>, at: ClockReading) {
            log += "samples ${samples.size} at ${at.monoUs}"
        }
    }

    private fun workout() =
        HealthPlan.Workout(
            syncId = "workout",
            rideId = ride,
            startUtcUs = 1_000,
            endUtcUs = null,
            indoor = false,
            laps = emptyList(),
            pauses = emptyList(),
        )

    private fun quantity(syncId: String) =
        HealthPlan.Quantity(
            syncId = syncId,
            quantity = HealthQuantity.entries.first(),
            startUtcUs = 1_000,
            endUtcUs = 2_000,
            value = 1.0,
        )

    private fun sample(id: String, metric: String, startUtcUs: Long) =
        HealthOriginal(
            sampleId = id,
            member = null,
            metric = metric,
            value = 1.0,
            startUtcUs = startUtcUs,
            endUtcUs = startUtcUs + 1,
            dataOrigin = "app.powerlog",
            syncId = null,
        )
}
