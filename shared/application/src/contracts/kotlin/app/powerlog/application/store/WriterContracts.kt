package app.powerlog.application.store

import app.powerlog.application.store.ContractData.START_UTC_US
import app.powerlog.application.store.ContractData.append
import app.powerlog.application.store.ContractData.event
import app.powerlog.application.store.ContractData.fixes
import app.powerlog.application.store.ContractData.health
import app.powerlog.application.store.ContractData.heartRate
import app.powerlog.application.store.ContractData.identity
import app.powerlog.application.store.ContractData.publish
import app.powerlog.application.store.ContractData.readback
import app.powerlog.application.store.ContractData.readings
import app.powerlog.application.store.ContractData.ride
import app.powerlog.application.store.ContractData.rideId
import app.powerlog.application.store.ContractData.sample
import app.powerlog.application.store.ContractData.session
import app.powerlog.application.store.ContractData.timing
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.RideSettings
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.settings.AppSettings
import app.powerlog.domain.settings.ChartRange
import app.powerlog.domain.settings.SettingsDocument
import app.powerlog.domain.settings.ViewId
import app.powerlog.domain.settings.ViewPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent

private val bike = BikeId("AA:BB:CC:DD:EE:FF")

abstract class BatchAtomicityContract : ArchiveContract() {
    @Test
    fun aBatchWhoseLastMutationFailsLeavesNoRowRideChangeRevisionOrNotice() = contract { store ->
        val kept = rideId(1)
        val created = rideId(2)
        store.create(ride(kept))
        store.commit(Mutation.OpenControllerSession(session(kept)), append(kept, readings(4)))
        val record = store.rides.ride(kept).ok()
        val extent = store.measurements.extent(kept).ok()
        val telemetry = store.allTelemetry(kept)
        val marker = store.rides.marker().ok()
        val failing = buildList {
            add(Mutation.CreateRide(ride(created), health))
            add(append(kept, readings(4, fromUs = 500_000, firstSeq = 5)))
            add(Mutation.AppendLocations(kept, fixes(kept, 2)))
            add(Mutation.AppendEvents(kept, listOf(event(1, RideEventKind.pause, 600_000))))
            add(Mutation.SetPhase(kept, RidePhase.paused))
            add(Mutation.Checkpoint(kept, timing(START_UTC_US, 600_000)))
            add(Mutation.SaveHealthOutcome(kept, health.copy(outcome = HealthOutcome.saved)))
            add(Mutation.RememberController(bike, identity))
            add(Mutation.SelectBike(bike))
            if (storesHealth) {
                add(Mutation.AppendHealthSamples(kept, listOf(sample(kept, "s1", 100_000))))
                add(Mutation.PutHealthWork(listOf(publish(kept, HealthComponent.workout))))
            }
            add(Mutation.OpenControllerSession(session(kept, sessionNo = 2, identity = null)))
        }
        val notices =
            notices(store) {
                store.writer.commit(WriteBatch(failing)).assertFailed(StorageError.Kind.constraint)
                assertEquals(marker, store.rides.marker().ok())
            }
        assertEquals(null, store.rides.ride(created).ok())
        assertEquals(record, store.rides.ride(kept).ok())
        assertEquals(extent, store.measurements.extent(kept).ok())
        assertEquals(telemetry, store.allTelemetry(kept))
        assertEquals(emptyList(), store.allLocations(kept))
        assertEquals(emptyList(), store.measurements.events(kept).ok())
        assertEquals(listOf(1), store.measurements.sessions(kept).ok().map { it.sessionNo })
        assertEquals(null, store.connection.known(bike).ok())
        assertEquals(null, store.connection.selected().ok())
        assertEquals(emptyList(), store.allHealth(kept))
        assertEquals(emptyList(), store.health.work(kept).ok())
        assertEquals(emptyList(), notices.commits)
        assertEquals(emptyList(), notices.history)
    }
}

abstract class ImpliedExistenceContract : ArchiveContract() {
    @Test
    fun aMutationNamingAnAbsentRideAnswersRideGoneAndAppliesNothing() = contract { store ->
        val absent = rideId(9)
        for (mutation in naming(absent)) {
            assertEquals(Commit.RideGone(absent), store.answer(Mutation.SelectBike(bike), mutation).ok(), "$mutation")
        }
        assertEquals(null, store.connection.selected().ok())
        assertEquals(null, store.rides.ride(absent).ok())
    }

    @Test
    fun aMutationNamingADeletedRideAnswersRideGoneAndAppliesNothing() = contract { store ->
        val deleted = rideId(1)
        store.create(ride(deleted, phase = RidePhase.completed, endUs = 1_000_000))
        store.commit(Mutation.MarkRideDeleted(deleted, START_UTC_US + 2_000_000))
        val marker = store.rides.marker().ok()
        for (mutation in naming(deleted)) {
            assertEquals(Commit.RideGone(deleted), store.answer(Mutation.SelectBike(bike), mutation).ok(), "$mutation")
        }
        assertEquals(null, store.connection.selected().ok())
        assertEquals(marker, store.rides.marker().ok())
    }

    @Test
    fun aRideCreatedEarlierInTheBatchTakesTheMutationsAfterIt() = contract { store ->
        val created = rideId(2)
        val committed =
            store.commit(
                Mutation.CreateRide(ride(created), health),
                Mutation.OpenControllerSession(session(created)),
                append(created, readings(3)),
                Mutation.AppendEvents(created, listOf(event(1, RideEventKind.start, 0))),
                Mutation.Checkpoint(created, timing(START_UTC_US, 250_000)),
            )
        assertEquals(setOf(created), committed.revisions.keys)
        assertEquals(readings(3), store.allTelemetry(created))
        assertEquals(listOf(event(1, RideEventKind.start, 0)), store.measurements.events(created).ok())
        assertEquals(timing(START_UTC_US, 250_000), store.rides.ride(created).ok()?.ride?.checkpoint)
    }

    private fun naming(rideId: RideId): List<Mutation> = buildList {
        add(Mutation.SetPhase(rideId, RidePhase.paused))
        add(Mutation.Checkpoint(rideId, timing(START_UTC_US, 1_000_000)))
        add(Mutation.EndRide(rideId, timing(START_UTC_US, 2_000_000)))
        add(Mutation.OpenControllerSession(session(rideId)))
        add(append(rideId, readings(1)))
        add(Mutation.AppendLocations(rideId, fixes(rideId, 1)))
        add(Mutation.AppendEvents(rideId, listOf(event(1, RideEventKind.pause, 0))))
        add(Mutation.SaveHealthOutcome(rideId, health.copy(outcome = HealthOutcome.saved)))
        add(Mutation.RemoveHealthWork(rideId, null))
        if (storesHealth) {
            add(Mutation.AppendHealthSamples(rideId, listOf(sample(rideId, "s1", 0))))
            add(Mutation.PutHealthWork(listOf(publish(rideId, HealthComponent.workout))))
        }
    }
}

abstract class ClosedRideContract : ArchiveContract() {
    @Test
    fun anAbsentOrDeletedClosedRideAnswersRideGone() = contract { store ->
        val absent = rideId(1)
        val deleted = rideId(2)
        assertEquals(Commit.RideGone(absent), store.answer(*deletion(absent), closedRide = absent).ok())
        store.create(ride(deleted, phase = RidePhase.completed, endUs = 1_000_000))
        store.commit(*deletion(deleted), closedRide = deleted)
        assertEquals(Commit.RideGone(deleted), store.answer(*deletion(deleted), closedRide = deleted).ok())
    }

    @Test
    fun aClosedRideInAnOpenPhaseAnswersRideOpenAndStays() = contract { store ->
        val open = listOf(RidePhase.preparing, RidePhase.running, RidePhase.paused, RidePhase.interrupted)
        open.forEachIndexed { index, phase ->
            val id = rideId(index + 1)
            store.create(ride(id, phase = phase, interrupted = phase == RidePhase.interrupted))
            assertEquals(Commit.RideOpen(id), store.answer(*deletion(id), closedRide = id).ok(), "$phase")
            assertEquals(phase, store.rides.ride(id).ok()?.ride?.phase)
        }
    }

    @Test
    fun aCompletedOrFailedClosedRideTakesTheBatch() = contract { store ->
        listOf(RidePhase.completed, RidePhase.failed).forEachIndexed { index, phase ->
            val id = rideId(index + 1)
            store.create(ride(id, phase = phase, endUs = 1_000_000))
            store.commit(*deletion(id), closedRide = id)
            assertEquals(null, store.rides.ride(id).ok(), "$phase")
        }
    }

    private fun deletion(rideId: RideId): Array<Mutation> =
        arrayOf(Mutation.MarkRideDeleted(rideId, START_UTC_US + 5_000_000), Mutation.RemoveHealthWork(rideId, null))
}

abstract class RevisionContract : ArchiveContract() {
    private class Step(
        val name: String,
        val mutations: List<Mutation>,
        val facts: Set<RideId> = emptySet(),
        val history: Set<RideId> = emptySet(),
        val historyMoves: Boolean = history.isNotEmpty(),
        val created: Set<RideId> = emptySet(),
    )

    private class Expected(val step: Step, val notice: CommitNotice)

    @Test
    fun eachCommitMovesTheRevisionsItsMutationsChangeOnceAndNoticesCarryThem() = contract { store ->
        val steps = steps(rideId(1), rideId(2))
        val revisions = mutableMapOf<RideId, Long>()
        var history = store.rides.marker().ok()
        val expected = mutableListOf<Expected>()
        val notices =
            notices(store) {
                for (step in steps) {
                    val committed = store.commit(*step.mutations.toTypedArray())
                    for (id in step.created) revisions[id] = 1
                    for (id in step.facts) revisions[id] = revisions.getValue(id) + 1
                    if (step.historyMoves) history++
                    val named = step.mutations.flatMap(::ridesOf).toSet()
                    assertEquals(named.associateWith { revisions.getValue(it) }, committed.revisions, step.name)
                    assertEquals(history, committed.historyRevision, step.name)
                    assertEquals(history, store.rides.marker().ok(), step.name)
                    if (step.facts.isNotEmpty() || step.historyMoves) {
                        val notice =
                            CommitNotice(
                                step.facts.associateWith { revisions.getValue(it) },
                                history,
                                step.history,
                                false,
                            )
                        expected += Expected(step, notice)
                    }
                }
            }
        assertNotices(expected, notices.commits)
        assertNotices(expected.filter { it.step.historyMoves }, notices.history)
    }

    private fun assertNotices(expected: List<Expected>, actual: List<CommitNotice>) {
        assertEquals(expected.size, actual.size, "notices for ${expected.map { it.step.name }}: $actual")
        expected.zip(actual).forEach { (want, got) ->
            for (id in want.step.created) got.revisions[id]?.let {
                assertEquals(1L, it, "${want.step.name}: the new ride's revision")
            }
            assertEquals(want.notice, got.copy(revisions = got.revisions - want.step.created), want.step.name)
        }
    }

    private fun steps(a: RideId, b: RideId): List<Step> = buildList {
        val origin = START_UTC_US
        add(
            Step(
                "a ride's creation",
                listOf(Mutation.CreateRide(ride(a), health)),
                history = setOf(a),
                created = setOf(a),
            )
        )
        add(
            Step(
                "another ride's creation",
                listOf(Mutation.CreateRide(ride(b), health)),
                history = setOf(b),
                created = setOf(b),
            )
        )
        if (storesHealth) {
            val insert = Mutation.PutHealthWork(listOf(publish(a, HealthComponent.workout)))
            add(Step("a publish row's insertion", listOf(insert), history = setOf(a)))
        }
        add(Step("telemetry", listOf(append(a, readings(2))), facts = setOf(a)))
        add(
            Step(
                "a capture batch",
                listOf(
                    append(a, readings(2, fromUs = 250_000, firstSeq = 3)),
                    Mutation.AppendLocations(a, fixes(a, 1)),
                    Mutation.AppendEvents(a, listOf(event(1, RideEventKind.lap, 300_000))),
                    Mutation.Checkpoint(a, timing(origin, 400_000)),
                ),
                facts = setOf(a),
            )
        )
        add(Step("a controller session", listOf(Mutation.OpenControllerSession(session(a))), facts = setOf(a)))
        add(Step("a later checkpoint", listOf(Mutation.Checkpoint(a, timing(origin, 900_000))), facts = setOf(a)))
        add(Step("an earlier checkpoint", listOf(Mutation.Checkpoint(a, timing(origin, 800_000)))))
        add(Step("the same checkpoint", listOf(Mutation.Checkpoint(a, timing(origin, 900_000)))))
        add(Step("a phase", listOf(Mutation.SetPhase(a, RidePhase.paused)), facts = setOf(a), history = setOf(a)))
        add(Step("the same phase", listOf(Mutation.SetPhase(a, RidePhase.paused))))
        add(
            Step(
                "an interruption",
                listOf(Mutation.SetPhase(a, RidePhase.interrupted)),
                facts = setOf(a),
                history = setOf(a),
            )
        )
        val saved = health.copy(outcome = HealthOutcome.saved, workoutId = "workout")
        add(Step("Health columns", listOf(Mutation.SaveHealthOutcome(a, saved)), history = setOf(a)))
        add(Step("the same Health columns", listOf(Mutation.SaveHealthOutcome(a, saved))))
        add(Step("an end", listOf(Mutation.EndRide(a, timing(origin, 900_000))), facts = setOf(a), history = setOf(a)))
        add(
            Step(
                "two rides",
                listOf(append(a, readings(1, fromUs = 950_000, firstSeq = 5)), append(b, readings(1))),
                facts = setOf(a, b),
            )
        )
        add(Step("a remembered bike", listOf(Mutation.RememberController(bike, identity), Mutation.SelectBike(bike))))
        if (storesHealth) {
            add(
                Step(
                    "a Health sample",
                    listOf(Mutation.AppendHealthSamples(a, listOf(sample(a, "s1", 0)))),
                    facts = setOf(a),
                )
            )
            val workout = HealthComponent.workout
            add(
                Step(
                    "a publish row's state",
                    listOf(Mutation.PutHealthWork(listOf(publish(a, workout, ComponentState.submitted)))),
                    history = setOf(a),
                )
            )
            val failed = publish(a, workout, ComponentState.failed, reason = HealthReason.quota)
            add(
                Step(
                    "a publish row's state and reason",
                    listOf(Mutation.PutHealthWork(listOf(failed))),
                    history = setOf(a),
                )
            )
            val reason = failed.copy(reason = HealthReason.permission)
            add(Step("a publish row's reason", listOf(Mutation.PutHealthWork(listOf(reason))), history = setOf(a)))
            val retried = reason.copy(attempts = 1, cursor = "seconds:3", dueUtcUs = origin + 60_000_000)
            add(Step("a publish row's attempts, cursor and due time", listOf(Mutation.PutHealthWork(listOf(retried)))))
            add(
                Step(
                    "a readback row",
                    listOf(Mutation.PutHealthWork(listOf(readback(a, heartRate, dueUtcUs = origin + 1_000_000)))),
                )
            )
            add(Step("a readback row's removal", listOf(Mutation.RemoveHealthWork(a, HealthTask.readback))))
            add(
                Step(
                    "a publish row's removal",
                    listOf(Mutation.RemoveHealthWork(a, HealthTask.publish)),
                    history = setOf(a),
                )
            )
            add(Step("a publish removal with no publish row", listOf(Mutation.RemoveHealthWork(a, HealthTask.publish))))
            add(
                Step(
                    "a publish row inserted again",
                    listOf(Mutation.PutHealthWork(listOf(publish(a, workout)))),
                    history = setOf(a),
                )
            )
            add(Step("every task's removal", listOf(Mutation.RemoveHealthWork(a, null)), history = setOf(a)))
        }
        add(Step("a deletion", listOf(Mutation.MarkRideDeleted(b, origin + 2_000_000)), history = setOf(b)))
    }

    private fun ridesOf(mutation: Mutation): List<RideId> =
        when (mutation) {
            is Mutation.CreateRide -> listOf(mutation.ride.id)
            is Mutation.SetPhase -> listOf(mutation.rideId)
            is Mutation.Checkpoint -> listOf(mutation.rideId)
            is Mutation.EndRide -> listOf(mutation.rideId)
            is Mutation.MarkRideDeleted -> listOf(mutation.rideId)
            is Mutation.OpenControllerSession -> listOf(mutation.session.rideId)
            is Mutation.AppendTelemetry -> listOf(mutation.rideId)
            is Mutation.AppendLocations -> listOf(mutation.rideId)
            is Mutation.AppendEvents -> listOf(mutation.rideId)
            is Mutation.AppendHealthSamples -> listOf(mutation.rideId)
            is Mutation.SaveHealthOutcome -> listOf(mutation.rideId)
            is Mutation.PutHealthWork -> mutation.items.map { it.rideId }
            is Mutation.RemoveHealthWork -> listOf(mutation.rideId)
            is Mutation.RememberController,
            is Mutation.SelectBike -> emptyList()
        }
}

abstract class WriterQueueContract : ArchiveContract() {
    @Test
    fun aFullQueueAnswersBusyAtOnceAndQueuedBatchesCommitInOrder() = contract { store ->
        val queued = rideId(1)
        val refused = rideId(2)
        store.create(ride(queued))
        val first = store.revision(queued)!!
        val stall = store.stallWriter()
        val answers =
            List(harness.queueJobs) { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    store.answer(Mutation.Checkpoint(queued, timing(START_UTC_US, (index + 1) * 1_000_000L)))
                }
            }
        runCurrent()
        store.answer(Mutation.CreateRide(ride(refused), health)).assertFailed(StorageError.Kind.busy)
        stall.release()
        answers.awaitAll().forEachIndexed { index, answer ->
            assertEquals(mapOf(queued to first + index + 1), assertIs<Commit.Committed>(answer.ok()).revisions)
        }
        assertEquals(null, store.rides.ride(refused).ok())
    }
}

abstract class DurabilityContract : ArchiveContract() {
    @Test
    fun aCommittedStateReadsBackAfterTheStoreIsClosedAndOpened() = contract { store ->
        val open = rideId(1)
        val finished = rideId(2)
        store.create(ride(open))
        store.create(
            ride(finished, phase = RidePhase.completed, checkpointUs = 9_000_000, endUs = 9_000_000),
            health.copy(outcome = HealthOutcome.saved, workoutId = "workout"),
        )
        store.commit(
            Mutation.OpenControllerSession(session(open)),
            append(open, readings(10)),
            Mutation.AppendLocations(open, fixes(open, 3)),
            Mutation.AppendEvents(open, listOf(event(1, RideEventKind.start, 0))),
            Mutation.Checkpoint(open, timing(START_UTC_US, 1_250_000)),
            Mutation.RememberController(bike, identity),
            Mutation.SelectBike(bike),
        )
        if (storesHealth)
            store.commit(
                Mutation.AppendHealthSamples(open, listOf(sample(open, "s1", -1_000_000), sample(open, "s2", 500_000))),
                Mutation.PutHealthWork(
                    listOf(publish(open, HealthComponent.workout), readback(open, heartRate, dueUtcUs = START_UTC_US))
                ),
            )
        store.settings.save(settings).ok()
        val before = state(store, listOf(open, finished))
        assertEquals(before, state(opened(store.reopen()), listOf(open, finished)))
    }

    private suspend fun state(store: StoreUnderTest, rides: List<RideId>): List<Any?> = buildList {
        add(store.rides.page(null, 10).ok())
        add(store.rides.openRide().ok())
        add(store.rides.marker().ok())
        for (ride in rides) {
            add(store.rides.ride(ride).ok())
            add(store.measurements.extent(ride).ok())
            add(store.measurements.sessions(ride).ok())
            add(store.measurements.events(ride).ok())
            add(store.allTelemetry(ride))
            add(store.allLocations(ride))
            add(store.allHealth(ride))
            add(store.health.outcome(ride).ok())
            add(store.health.work(ride).ok())
        }
        add(store.connection.known(bike).ok())
        add(store.connection.selected().ok())
        add(store.settings.document().ok())
    }

    private val settings =
        SettingsDocument(
            ride =
                RideSettings(
                    sampleRate = SampleRate.hz4,
                    distance = DistanceSelection.phoneGps,
                    battery = BatteryMethod.calculated,
                    indoor = false,
                    useWatch = true,
                    saveToHealth = true,
                    recordGps = null,
                    syncBeeps = false,
                ),
            app =
                AppSettings(
                    units = UnitSystem.imperial,
                    temperature = TemperatureUnit.fahrenheit,
                    fitPower = FitPowerSource.battery,
                    views =
                        mapOf(
                            ViewId.ride to
                                ViewPreset(
                                    listOf(MetricId.humanPowerW),
                                    listOf(MetricId.cadenceRpm),
                                    ChartRange.seconds120,
                                    2,
                                )
                        ),
                    activeView = ViewId.ride,
                    historyView = null,
                    historyRange = ChartRange.all,
                ),
        )
}

abstract class EndCheckpointContract : ArchiveContract() {
    @Test
    fun aFinishBatchSetsTheCheckpointToTheEndBelowAStoredLaterOne() = contract { store ->
        listOf(true, false).forEachIndexed { index, checkpointFirst ->
            val id = rideId(index + 1)
            store.create(ride(id, phase = RidePhase.interrupted, interrupted = true, checkpointUs = 100_000_000))
            val end = timing(START_UTC_US, 80_000_000, 70_000_000)
            val finish = listOf(Mutation.Checkpoint(id, end), Mutation.EndRide(id, end))
            store.commit(
                *(if (checkpointFirst) finish else finish.reversed()).toTypedArray(),
                Mutation.SetPhase(id, RidePhase.completed),
            )
            val stored = store.rides.ride(id).ok()?.ride
            assertEquals(end, stored?.checkpoint, "checkpoint first: $checkpointFirst")
            assertEquals(end, stored?.end, "checkpoint first: $checkpointFirst")
        }
    }
}
