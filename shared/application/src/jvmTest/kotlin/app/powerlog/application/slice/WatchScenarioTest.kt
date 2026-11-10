package app.powerlog.application.slice

import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.fakes.Answer
import app.powerlog.application.fakes.FakeHealthProvider
import app.powerlog.application.port.HealthOriginal
import app.powerlog.application.port.LiveHeartRate
import app.powerlog.application.port.WatchLinkAvailability
import app.powerlog.application.settings.RideToggle
import app.powerlog.application.settings.SettingsChange
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.application.watch.WatchDecodeResult
import app.powerlog.application.watch.WatchMessage
import app.powerlog.application.watch.WatchPhase
import app.powerlog.application.watch.WatchProtocol
import app.powerlog.application.watch.WatchStartOptions
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.recording.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class WatchScenarioTest {
    @Test
    fun anotherVersionOrAnUndecodableMessageIsAnsweredWithNoEffect() {
        val scenario = watching()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val mismatch = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.VersionMismatch))
            val invalid = scenario.watchLink.receive("not json".encodeToByteArray())
            delay(10)
            assertEquals(FailureCode.watchVersionMismatch, scenario.ack(mismatch.replies.single()).error)
            assertEquals(FailureCode.watchMessageInvalid, scenario.ack(invalid.replies.single()).error)
            assertTrue(scenario.archive.attempts.isEmpty())
        }
    }

    @Test
    fun watchMessagesWaitForTheGate() {
        val scenario = watching()
        scenario.test {
            val early = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.VersionMismatch))
            delay(10)
            assertTrue(early.replies.isEmpty())
            assertEquals(LaunchResult.Ready, scenario.launch())
            delay(10)
            assertEquals(FailureCode.watchVersionMismatch, scenario.ack(early.replies.single()).error)
        }
    }

    @Test
    fun aWatchStartRequestIsAnsweredWithTheRunningRideOnceTheStartSettles() {
        val scenario = watching()
        scenario.watchAnswers { WatchMessage.AckMessage(null, null) }
        scenario.test {
            ready(scenario)
            val reply = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(startRequest())))
            while (reply.replies.isEmpty()) delay(1)
            val ride = assertIs<WatchMessage.RideMessage>(scenario.message(reply.replies.single()))
            assertEquals(WatchPhase.running, ride.phase)
            val start = scenario.sent().filterIsInstance<WatchMessage.StartMessage>().single()
            assertEquals("request-1", start.requestId)
            assertEquals(ride.rideId, start.rideId)
            assertEquals(RidePhase.running, scenario.recording().phase)
            assertTrue(requireNotNull(scenario.recording().ride).options.useWatch)
        }
    }

    @Test
    fun aWatchStartReadsTheSameSettingsSnapshotAsTheApp() {
        val scenario = watching()
        scenario.watchAnswers { WatchMessage.AckMessage(null, null) }
        scenario.test {
            ready(scenario)
            assertEquals(Outcome.Ok(Unit), scenario.api.settings.change(SettingsChange.SampleRateTo(SampleRate.hz8)))
            assertEquals(
                Outcome.Ok(Unit),
                scenario.api.settings.change(SettingsChange.BatteryTo(BatteryMethod.calculated)),
            )
            val reply = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(startRequest())))
            while (reply.replies.isEmpty()) delay(1)
            val options = requireNotNull(scenario.recording().ride).options
            assertEquals(SampleRate.hz8, options.sampleRate)
            assertEquals(BatteryMethod.calculated, options.batteryMethod)
            assertTrue(options.indoor)
        }
    }

    @Test
    fun anUnreachableWatchIsLaunchedThenSentStartOnceReachable() {
        val scenario = watching(reachable = false)
        scenario.watchAnswers { WatchMessage.AckMessage(null, null) }
        scenario.test {
            ready(scenario, useWatch = true)
            val start = async { scenario.api.recording.start(app.powerlog.application.api.Empty) }
            while (scenario.watchLink.launches.received.isEmpty()) delay(1)
            delay(2_000)
            assertTrue(scenario.sent().none { it is WatchMessage.StartMessage })
            scenario.watchLink.setAvailability(WatchLinkAvailability(installed = true, reachable = true))
            assertEquals(Outcome.Ok(Unit), start.await())
            assertEquals(1, scenario.sent().count { it is WatchMessage.StartMessage })
        }
    }

    @Test
    fun aWatchsHealthDeniedFailsTheStart() {
        val scenario = watching()
        scenario.watchAnswers { message ->
            if (message is WatchMessage.StartMessage) WatchMessage.AckMessage(FailureCode.healthDenied, null)
            else WatchMessage.AckMessage(null, null)
        }
        scenario.test {
            ready(scenario, useWatch = true)
            assertEquals(
                FailureCode.healthDenied,
                scenario.api.recording.start(app.powerlog.application.api.Empty).code(),
            )
            assertTrue(scenario.mutations().any { it is Mutation.SetPhase && it.phase == RidePhase.failed })
        }
    }

    @Test
    fun aCancelStartByRequestIdDiscardsThePreparingRide() {
        val scenario = watching()
        scenario.watchLink.sends.script = { Answer.Held }
        scenario.test {
            ready(scenario)
            val started = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(startRequest())))
            scenario.recording { it.phase == RidePhase.preparing }
            val cancel = WatchMessage.CancelStartMessage(null, "request-1", null)
            val cancelled = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(cancel)))
            while (started.replies.isEmpty() || cancelled.replies.isEmpty()) delay(1)
            assertEquals(FailureCode.startCancelled, scenario.ack(started.replies.single()).error)
            assertEquals(null, scenario.ack(cancelled.replies.single()).error)
            assertEquals(null, scenario.recording { it.phase == null }.failure)
            assertTrue(scenario.mutations().any { it is Mutation.MarkRideDeleted })
        }
    }

    @Test
    fun aCancelStartDuringASyncBeepCountdownLeavesNoRide() {
        val scenario = watching()
        scenario.watchLink.sends.script = { Answer.Held }
        scenario.test {
            ready(scenario)
            val beeps = SettingsChange.Toggle(RideToggle.syncBeeps, true)
            assertEquals(Outcome.Ok(Unit), scenario.api.settings.change(beeps))
            val started = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(startRequest())))
            scenario.recording { it.cue != null }
            val cancel = WatchMessage.CancelStartMessage(null, "request-1", null)
            val cancelled = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(cancel)))
            while (started.replies.isEmpty() || cancelled.replies.isEmpty()) delay(1)
            assertEquals(FailureCode.startCancelled, scenario.ack(started.replies.single()).error)
            assertEquals(null, scenario.ack(cancelled.replies.single()).error)
            assertTrue(scenario.mutations().none { it is Mutation.CreateRide })
            assertEquals(1, scenario.tonePlayer.stops)
        }
    }

    @Test
    fun commandsAnswerExpiredOtherRideOrTheAppliedRide() {
        val scenario = watching()
        scenario.watchAnswers { WatchMessage.AckMessage(null, null) }
        scenario.test {
            val rideId = watchRide(scenario)
            val now = scenario.clock.utcUs()
            fun command(rideId: RideId, expires: Long, action: RideAction = RideAction.pause) =
                scenario.watchLink.receive(
                    scenario.codec.bytesFor(
                        WatchDecodeResult.Valid(WatchMessage.CommandMessage(rideId, action, expires))
                    )
                )
            val expired = command(rideId, now - 1)
            val other = command(RideId("another"), now + WatchProtocol.commandExpiryUs)
            val valid = command(rideId, now + WatchProtocol.commandExpiryUs)
            while (listOf(expired, other, valid).any { it.replies.isEmpty() }) delay(1)
            assertEquals(FailureCode.actionUnavailable, scenario.ack(expired.replies.single()).error)
            assertEquals(FailureCode.rideChanged, scenario.ack(other.replies.single()).error)
            val paused = assertIs<WatchMessage.RideMessage>(scenario.message(valid.replies.single()))
            assertEquals(WatchPhase.paused, paused.phase)
            assertEquals(RidePhase.paused, scenario.recording().phase)

            val finish = command(rideId, scenario.clock.utcUs() + WatchProtocol.commandExpiryUs, RideAction.finish)
            while (finish.replies.isEmpty()) delay(1)
            val finished = assertIs<WatchMessage.RideMessage>(scenario.message(finish.replies.single()))
            assertEquals(WatchPhase.completed, finished.phase)
            assertTrue(finished.stateRevision > paused.stateRevision)
            assertTrue(finished.endedUtcUs != null)
            delay(1_000)
            val terminal = scenario.rides().last { it.endedUtcUs != null }
            assertEquals(WatchPhase.completed, terminal.phase)
            assertTrue(terminal.stateRevision > finished.stateRevision)
        }
    }

    @Test
    fun anOpenRideSendsRideOnChangesAndEveryIntervalAndTheTerminalUntilAcknowledged() {
        val scenario = watching()
        var acknowledging = true
        scenario.watchAnswers { message ->
            if (message is WatchMessage.RideMessage && message.endedUtcUs != null && !acknowledging) null
            else WatchMessage.AckMessage(null, null)
        }
        scenario.test {
            val rideId = watchRide(scenario)
            val before = scenario.rides().size
            delay(10_000)
            val routine = scenario.rides().drop(before)
            assertTrue(routine.size in 4..6, "${routine.size} routine rides")
            acknowledging = false
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            delay(5_000)
            val terminal = scenario.rides().filter { it.endedUtcUs != null }
            assertTrue(terminal.size >= 4, "${terminal.size} terminal sends")
            assertTrue(terminal.all { it.phase == WatchPhase.completed && it.rideId == rideId })
            acknowledging = true
            delay(2_000)
            val acknowledged = scenario.rides().count { it.endedUtcUs != null }
            delay(5_000)
            assertEquals(acknowledged, scenario.rides().count { it.endedUtcUs != null })
        }
    }

    @Test
    fun healthSamplesForAnUnknownRideAreAcknowledgedAndAnOutcomeIsStored() {
        val scenario = watching()
        scenario.watchAnswers { WatchMessage.AckMessage(null, null) }
        scenario.test {
            val rideId = watchRide(scenario, saveToHealth = true)
            assertTrue(requireNotNull(scenario.recording().ride).options.saveToHealth)
            val unknown = WatchMessage.HealthSamplesMessage(RideId("unknown"), 7, listOf(sample(scenario, "s1")), null)
            val known = WatchMessage.HealthSamplesMessage(rideId, 3, listOf(sample(scenario, "s2")), null)
            val first = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(unknown)))
            val second = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(known)))
            while (first.replies.isEmpty() || second.replies.isEmpty()) delay(1)
            assertEquals(7L, scenario.ack(first.replies.single()).acknowledgedSequence)
            assertEquals(3L, scenario.ack(second.replies.single()).acknowledgedSequence)
            assertEquals(
                listOf("s2"),
                scenario.mutations().filterIsInstance<Mutation.AppendHealthSamples>().flatMap { batch ->
                    batch.samples.map { it.parentId }
                },
            )
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            val outcome = WatchMessage.HealthOutcomeMessage(rideId, HealthOutcome.saved, "workout-1", null)
            val stored = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(outcome)))
            while (stored.replies.isEmpty()) delay(1)
            assertEquals(null, scenario.ack(stored.replies.single()).error)
            val health = scenario.mutations().filterIsInstance<Mutation.SaveHealthOutcome>().last().health
            assertEquals(HealthOutcome.saved, health.outcome)
            assertEquals("workout-1", health.workoutId)
        }
    }

    @Test
    fun aWatchRideRestoredInterruptedAtLaunchSendsItsRideEveryInterval() {
        val scenario = watching()
        scenario.watchAnswers { WatchMessage.AckMessage(null, null) }
        scenario.test {
            val seeded = scenario.seedOpenRide(RidePhase.running, useWatch = true)
            assertEquals(LaunchResult.Ready, scenario.launch())
            delay(1_000)
            val first = scenario.rides()
            assertTrue(first.isNotEmpty())
            assertTrue(first.all { it.rideId == seeded.id && it.phase == WatchPhase.interrupted })
            delay(10_000)
            val routine = scenario.rides().size - first.size
            assertTrue(routine in 4..6, "$routine routine rides")
            assertEquals(Outcome.Ok(Unit), scenario.control(seeded.id, RideAction.discard))
            delay(1_000)
            assertEquals(WatchPhase.discarded, scenario.rides().last().phase)
            val ended = scenario.rides().size
            delay(10_000)
            assertEquals(ended, scenario.rides().size)
        }
    }

    @Test
    fun theWatchsHeartRateTurnsStaleAtItsStaleInstant() {
        val scenario = watching()
        scenario.watchAnswers { WatchMessage.AckMessage(null, null) }
        scenario.test {
            val rideId = watchRide(scenario)
            val beat = LiveHeartRate(120.0, scenario.clock.utcUs())
            val receivedAt = scenario.clock.monoUs()
            scenario.watchLink.receive(
                scenario.codec.bytesFor(
                    WatchDecodeResult.Valid(WatchMessage.HealthSamplesMessage(rideId, 1, emptyList(), beat))
                )
            )
            scenario.recording { it.sources.heartRate == SourceState.receiving }
            scenario.recording { it.sources.heartRate == SourceState.stale }
            val hold = requireNotNull(MetricCatalog.of(MetricId.heartRateBpm).displayHoldUs)
            assertEquals(receivedAt + hold, scenario.clock.monoUs())
        }
    }

    @Test
    fun aWatchDiscardIsAnsweredWithTheDiscardedRide() {
        val scenario = watching()
        scenario.watchAnswers { WatchMessage.AckMessage(null, null) }
        scenario.test {
            val rideId = watchRide(scenario)
            val command =
                WatchMessage.CommandMessage(
                    rideId,
                    RideAction.discard,
                    scenario.clock.utcUs() + WatchProtocol.commandExpiryUs,
                )
            val discard = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(command)))
            while (discard.replies.isEmpty()) delay(1)
            val reply = assertIs<WatchMessage.RideMessage>(scenario.message(discard.replies.single()))
            assertEquals(WatchPhase.discarded, reply.phase)
            assertTrue(reply.endedUtcUs != null)
        }
    }

    @Test
    fun aMessageWhoseHandlingThrowsGetsOneUnknownAck() {
        val scenario = watching()
        scenario.watchAnswers { WatchMessage.AckMessage(null, null) }
        scenario.test {
            val rideId = watchRide(scenario, saveToHealth = true)
            scenario.archive.onCommit = { batch ->
                if (batch.mutations.any { it is Mutation.SaveHealthOutcome }) error("store") else null
            }
            val outcome = WatchMessage.HealthOutcomeMessage(rideId, HealthOutcome.saved, "workout-1", null)
            val reply = scenario.watchLink.receive(scenario.codec.bytesFor(WatchDecodeResult.Valid(outcome)))
            delay(100)
            assertEquals(FailureCode.unknown, scenario.ack(reply.replies.single()).error)
            val line = scenario.archive.log.single { it.event == "unexpected" }
            assertEquals(LogValue.Text("WatchRides"), line.fields.single { it.key == "component" }.value)
        }
    }

    private fun watching(reachable: Boolean = true): Scenario {
        val scenario = Scenario()
        scenario.watch = scenario.watchLink
        scenario.health = FakeHealthProvider.apple(scenario.clock, scenario.post)
        scenario.application
        scenario.test { scenario.watchLink.setAvailability(WatchLinkAvailability(installed = true, reachable)) }
        return scenario
    }

    private suspend fun ScenarioScope.ready(scenario: Scenario, useWatch: Boolean = false) {
        with(scenario) { ready() }
        if (useWatch)
            assertEquals(
                Outcome.Ok(Unit),
                scenario.api.settings.change(SettingsChange.Toggle(RideToggle.useWatch, true)),
            )
    }

    private suspend fun ScenarioScope.watchRide(scenario: Scenario, saveToHealth: Boolean = false): RideId {
        ready(scenario, useWatch = true)
        if (saveToHealth)
            assertEquals(
                Outcome.Ok(Unit),
                scenario.api.settings.change(SettingsChange.Toggle(RideToggle.saveToHealth, true)),
            )
        assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(app.powerlog.application.api.Empty))
        return requireNotNull(scenario.recording { it.phase == RidePhase.running }.ride).rideId
    }

    private fun startRequest() =
        WatchMessage.StartRequestMessage("request-1", WatchStartOptions(indoor = true, saveToHealth = false, null))

    private fun sample(scenario: Scenario, id: String) =
        HealthOriginal(
            sampleId = id,
            member = null,
            metric = "heartRate",
            value = 120.0,
            startUtcUs = scenario.clock.utcUs(),
            endUtcUs = scenario.clock.utcUs(),
            dataOrigin = "com.apple.health",
            syncId = null,
        )

    private fun Scenario.watchAnswers(answer: (WatchMessage) -> WatchMessage?) {
        watchLink.sends.script = { bytes ->
            val message = (codec.decode(bytes) as WatchDecodeResult.Valid).message
            answer(message)?.let { Answer.Ok(codec.encode(it)) } ?: Answer.Held
        }
    }

    private fun Scenario.sent(): List<WatchMessage> =
        watchLink.sends.received.mapNotNull { (codec.decode(it) as? WatchDecodeResult.Valid)?.message }

    private fun Scenario.rides(): List<WatchMessage.RideMessage> = sent().filterIsInstance<WatchMessage.RideMessage>()

    private fun Scenario.message(bytes: ByteArray): WatchMessage =
        (codec.decode(bytes) as WatchDecodeResult.Valid).message

    private fun Scenario.ack(bytes: ByteArray): WatchMessage.AckMessage = assertIs(message(bytes))
}
