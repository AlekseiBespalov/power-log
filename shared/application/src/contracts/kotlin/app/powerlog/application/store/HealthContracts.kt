package app.powerlog.application.store

import app.powerlog.application.store.ContractData.MAX_SAFE
import app.powerlog.application.store.ContractData.START_UTC_US
import app.powerlog.application.store.ContractData.append
import app.powerlog.application.store.ContractData.health
import app.powerlog.application.store.ContractData.heartRate
import app.powerlog.application.store.ContractData.publish
import app.powerlog.application.store.ContractData.readback
import app.powerlog.application.store.ContractData.readings
import app.powerlog.application.store.ContractData.ride
import app.powerlog.application.store.ContractData.rideId
import app.powerlog.application.store.ContractData.sample
import app.powerlog.application.store.ContractData.session
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val activeEnergy = HealthMetric("activeEnergyKcal")

private fun keyOrder(work: List<HealthWork>): List<HealthWork> =
    work.sortedWith(
        compareBy(
            {
                when (it.scope) {
                    is HealthWorkScope.Publish -> HealthTask.publish.name
                    is HealthWorkScope.Readback -> HealthTask.readback.name
                }
            },
            {
                when (val scope = it.scope) {
                    is HealthWorkScope.Publish -> scope.component.name
                    is HealthWorkScope.Readback -> scope.metric.wire
                }
            },
        )
    )

abstract class HealthSampleRepeatsContract : SqliteArchiveContract() {
    @Test
    fun samplesWhoseKeyTheRideStoredAreSkippedAndNewOnesInserted() = sqlite { store ->
        val id = rideId(1)
        val other = rideId(2)
        store.create(ride(id))
        store.create(ride(other))
        val stored = listOf(sample(id, "k1", 0), sample(id, "k2", 1_000_000))
        store.commit(Mutation.AppendHealthSamples(id, stored))
        val revision = store.revision(id)!!
        val added = sample(id, "k3", 2_000_000)
        store.commit(
            Mutation.AppendHealthSamples(
                id,
                listOf(sample(id, "k2", 1_000_000, value = 99.0), added, sample(id, "k3", 2_000_000, value = 98.0)),
            )
        )
        assertEquals(stored + added, store.allHealth(id))
        assertEquals(revision + 1, store.revision(id))
        store.commit(Mutation.AppendHealthSamples(other, listOf(sample(other, "k1", 0))))
        assertEquals(listOf(sample(other, "k1", 0)), store.allHealth(other))
    }

    @Test
    fun aBatchOfRepeatsOnlyMovesNoRevisionAndEmitsNoNotice() = sqlite { store ->
        val id = rideId(1)
        store.create(ride(id))
        val stored = listOf(sample(id, "k1", 0), sample(id, "k2", 1_000_000))
        store.commit(Mutation.AppendHealthSamples(id, stored))
        val revision = store.revision(id)!!
        val marker = store.rides.marker().ok()
        val notices =
            notices(store) {
                val committed = store.commit(Mutation.AppendHealthSamples(id, stored.reversed()))
                assertEquals(mapOf(id to revision), committed.revisions)
                assertEquals(marker, committed.historyRevision)
            }
        assertEquals(emptyList(), notices.commits)
        assertEquals(emptyList(), notices.history)
        assertEquals(stored, store.allHealth(id))
    }
}

abstract class HealthWorkContract : SqliteArchiveContract() {
    @Test
    fun theHealthOutcomeIsReplacedAndReadsBack() = sqlite { store ->
        val id = rideId(1)
        val deleted = rideId(2)
        store.create(ride(id))
        store.create(ride(deleted, phase = RidePhase.completed, endUs = 1))
        store.commit(Mutation.MarkRideDeleted(deleted, START_UTC_US), closedRide = deleted)
        assertEquals(health, store.health.outcome(id).ok())
        val failed = RideHealth(HealthProviderKind.healthConnect, HealthOutcome.failed, HealthReason.permission, "w,1")
        store.commit(Mutation.SaveHealthOutcome(id, failed))
        assertEquals(failed, store.health.outcome(id).ok())
        assertNull(store.health.outcome(deleted).ok())
        assertNull(store.health.outcome(rideId(3)).ok())
    }

    @Test
    fun workRowsAreReplacedByKeyAndRemovedByTaskOrInFull() = sqlite { store ->
        val id = rideId(1)
        val other = rideId(2)
        store.create(ride(id))
        store.create(ride(other))
        val publishRows =
            HealthComponent.entries.map {
                publish(id, it, ComponentState.entries[it.ordinal % ComponentState.entries.size])
            }
        val readbackRows =
            listOf(heartRate, activeEnergy).mapIndexed { index, metric ->
                readback(id, metric, cursor = "page-$index", dueUtcUs = START_UTC_US + index)
            }
        val otherRows = listOf(publish(other, HealthComponent.workout), readback(other, heartRate))
        store.commit(Mutation.PutHealthWork(publishRows + readbackRows + otherRows))
        assertEquals(keyOrder(publishRows + readbackRows), store.health.work(id).ok())
        val submitted =
            publish(
                id,
                HealthComponent.workout,
                ComponentState.submitted,
                attempts = 2,
                cursor = "seconds:12",
                dueUtcUs = START_UTC_US + 60_000_000,
                reason = HealthReason.transientFailure,
            )
        store.commit(Mutation.PutHealthWork(listOf(submitted)))
        val replaced = publishRows.map { if (it.scope == submitted.scope) submitted else it }
        assertEquals(keyOrder(replaced + readbackRows), store.health.work(id).ok())
        store.commit(Mutation.RemoveHealthWork(id, HealthTask.readback))
        assertEquals(keyOrder(replaced), store.health.work(id).ok())
        store.commit(Mutation.PutHealthWork(readbackRows))
        store.commit(Mutation.RemoveHealthWork(id, HealthTask.publish))
        assertEquals(keyOrder(readbackRows), store.health.work(id).ok())
        store.commit(Mutation.RemoveHealthWork(id, null))
        assertEquals(emptyList(), store.health.work(id).ok())
        assertEquals(keyOrder(otherRows), store.health.work(other).ok())
    }

    @Test
    fun dueRidesAnswerRowsDueByNowEarliestFirstThenCompletedAndroidRidesWithPendingHealth() = sqlite { store ->
        val now = START_UTC_US + 150_000_000
        val pending = health.copy(provider = HealthProviderKind.healthConnect)
        val dueNow = rideId(1)
        val dueFirst = rideId(2)
        val later = rideId(3)
        val android = rideId(4)
        val iphone = rideId(5)
        val running = rideId(6)
        val saved = rideId(7)
        val androidDue = rideId(8)
        val waiting = rideId(9)
        val completed = { id: RideId, device: Device ->
            ride(id, phase = RidePhase.completed, recordedBy = device, endUs = 1)
        }
        store.create(completed(dueNow, Device.iphone))
        store.create(completed(dueFirst, Device.iphone))
        store.create(completed(later, Device.iphone))
        store.create(completed(android, Device.androidPhone), pending)
        store.create(completed(iphone, Device.iphone), pending)
        store.create(ride(running, recordedBy = Device.androidPhone), pending)
        store.create(completed(saved, Device.androidPhone), pending.copy(outcome = HealthOutcome.saved))
        store.create(completed(androidDue, Device.androidPhone), pending)
        store.create(completed(waiting, Device.androidPhone), pending)
        store.commit(
            Mutation.PutHealthWork(
                listOf(
                    readback(dueNow, heartRate, dueUtcUs = now),
                    publish(dueFirst, HealthComponent.workout, dueUtcUs = START_UTC_US + 50_000_000),
                    readback(later, heartRate, dueUtcUs = now + 1),
                    readback(androidDue, heartRate, dueUtcUs = START_UTC_US + 75_000_000),
                    readback(waiting, heartRate),
                )
            )
        )
        assertEquals(listOf(dueFirst, androidDue, dueNow, android), store.health.dueRides(now).ok())
    }

    @Test
    fun deferredRidesAnswerRidesWithAPendingReadbackRowThatHasNoDue() = sqlite { store ->
        val deferred = rideId(1)
        val due = rideId(2)
        val ended = rideId(3)
        val publishing = rideId(4)
        for (id in listOf(deferred, due, ended, publishing)) store.create(
            ride(id, phase = RidePhase.completed, recordedBy = Device.androidPhone, endUs = 1)
        )
        store.commit(
            Mutation.PutHealthWork(
                listOf(
                    readback(deferred, heartRate),
                    readback(due, heartRate, dueUtcUs = START_UTC_US),
                    readback(ended, heartRate).copy(state = ComponentState.unavailable),
                    publish(publishing, HealthComponent.workout),
                )
            )
        )
        assertEquals(listOf(deferred), store.health.deferredRides().ok())
    }
}

abstract class HealthColumnRangeContract : SqliteArchiveContract() {
    @Test
    fun everyHealthSampleColumnRoundTripsAtItsExtremesAndInEveryVocabulary() = sqlite { store ->
        val id = rideId(1)
        store.create(ride(id))
        val samples =
            HealthRepresentation.entries.mapIndexed { index, representation ->
                val third = index % 3
                HealthSample(
                    rideId = id,
                    producer = if (index % 2 == 0) Producer.phone else Producer.watch,
                    sampleKey = "${AWKWARD_TEXTS[index % AWKWARD_TEXTS.size]}#$index",
                    dataOrigin = AWKWARD_TEXTS[(index + 1) % AWKWARD_TEXTS.size],
                    parentId = if (third == 0) null else AWKWARD_TEXTS[(index + 2) % AWKWARD_TEXTS.size],
                    member = listOf(null, 0, Int.MAX_VALUE)[third],
                    metric = if (third == 0) null else HealthMetric(AWKWARD_TEXTS[(index + 3) % AWKWARD_TEXTS.size]),
                    representation = representation,
                    value = listOf(null, -1.0, Double.MAX_VALUE)[third],
                    sampleCount = listOf(null, 0, Int.MAX_VALUE)[third],
                    utcUs = listOf(-MAX_SAFE, 0L, MAX_SAFE)[third],
                    elapsedUs = if (index == 0) -MAX_SAFE else -1_000_000L + index,
                    startUtcUs = listOf(null, -MAX_SAFE, MAX_SAFE)[third],
                    endUtcUs = listOf(null, 0L, MAX_SAFE)[third],
                    activeInterval = listOf(null, 1, Int.MAX_VALUE)[third],
                )
            }
        store.commit(Mutation.AppendHealthSamples(id, samples))
        assertEquals(samples, store.allHealth(id))
    }

    @Test
    fun everyHealthWorkColumnRoundTripsForBothScopes() = sqlite { store ->
        val id = rideId(1)
        store.create(ride(id))
        val reasons = listOf<HealthReason?>(null) + HealthReason.entries
        val publishRows =
            HealthComponent.entries.mapIndexed { index, component ->
                HealthWork(
                    rideId = id,
                    scope = HealthWorkScope.Publish(component),
                    state = ComponentState.entries[index % ComponentState.entries.size],
                    attempts = listOf(0, 1, Int.MAX_VALUE)[index % 3],
                    cursor = (listOf(null) + AWKWARD_TEXTS)[index % (AWKWARD_TEXTS.size + 1)],
                    dueUtcUs = listOf(null, -MAX_SAFE, MAX_SAFE)[index % 3],
                    reason = reasons[index % reasons.size],
                )
            }
        val readbackRows =
            AWKWARD_TEXTS.filter { it.isNotEmpty() }
                .mapIndexed { index, wire ->
                    HealthWork(
                        rideId = id,
                        scope = HealthWorkScope.Readback(HealthMetric(wire)),
                        state = ComponentState.entries[index % ComponentState.entries.size],
                        attempts = index,
                        cursor = AWKWARD_TEXTS[index],
                        dueUtcUs = START_UTC_US + index,
                        reason = null,
                    )
                }
        store.commit(Mutation.PutHealthWork(publishRows + readbackRows))
        assertEquals(keyOrder(publishRows + readbackRows), store.health.work(id).ok())
    }
}

abstract class HealthPagingContract : SqliteArchiveContract() {
    @Test
    fun healthPagesHoldEverySampleOnceByElapsedThenInsertionOrder() = sqlite { store ->
        val id = rideId(1)
        store.create(ride(id))
        val first = List(14) { sample(id, "a$it", -3_000_000L + it / 2 * 1_000_000L, value = it.toDouble()) }
        val second = List(14) { sample(id, "b$it", -3_000_000L + it / 2 * 1_000_000L, metric = activeEnergy) }
        store.commit(Mutation.AppendHealthSamples(id, first))
        store.commit(Mutation.AppendHealthSamples(id, second.reversed()))
        val expected = (first + second.reversed()).sortedBy { it.elapsedUs }
        for (limit in listOf(1, 7, 4_096)) assertEquals(expected, store.allHealth(id, limit = limit), "limit $limit")
        val range = TimeRange(-2_000_000, 1_000_000)
        val inside = expected.filter { it.elapsedUs in -2_000_000 until 1_000_000 }
        for (limit in listOf(1, 7, 4_096)) assertEquals(
            inside,
            store.allHealth(id, range, limit = limit),
            "limit $limit",
        )
        assertEquals(
            expected.filter { it.metric == activeEnergy },
            store.allHealth(id, metric = activeEnergy, limit = 7),
        )
        val empty = store.measurements.health(id, TimeRange(0, 0), null, null, 7).ok()
        assertEquals(HealthSamplePage(emptyList(), null), empty)
    }

    @Test
    fun aSourceFilterMatchesEachGivenFieldAndANullFieldMatchesAll() = sqlite { store ->
        val id = rideId(1)
        store.create(ride(id))
        val watch = sample(id, "w", 0)
        val phone = sample(id, "p", 1, producer = Producer.phone, dataOrigin = "app.phone")
        val cumulative =
            sample(id, "c", 2, producer = Producer.phone, representation = HealthRepresentation.cumulativeWorkoutTotal)
        store.commit(Mutation.AppendHealthSamples(id, listOf(watch, phone, cumulative)))
        val cases =
            listOf(
                HealthSourceFilter(null, null, null) to listOf(watch, phone, cumulative),
                HealthSourceFilter(Producer.phone, null, null) to listOf(phone, cumulative),
                HealthSourceFilter(Producer.phone, "app.phone", null) to listOf(phone),
                HealthSourceFilter(null, null, setOf(HealthRepresentation.cumulativeWorkoutTotal)) to
                    listOf(cumulative),
                HealthSourceFilter(Producer.watch, "app.phone", null) to emptyList(),
            )
        for ((filter, matching) in cases) assertEquals(matching, store.allHealth(id, source = filter), "$filter")
    }
}

abstract class PublishRowsContract : SqliteArchiveContract() {
    @Test
    fun eachRideComesWithItsPublishRowsInKeyOrderAndLimitCountsRides() = sqlite { store ->
        val ids = (1..3).map { rideId(it) }
        ids.forEachIndexed { index, id ->
            store.create(ride(id, phase = RidePhase.completed, startedUtcUs = START_UTC_US + index, endUs = 1))
            store.commit(
                Mutation.PutHealthWork(HealthComponent.entries.map { publish(id, it) } + readback(id, heartRate))
            )
        }
        val rows = { id: RideId -> keyOrder(HealthComponent.entries.map { publish(id, it) }) }
        val first = store.rides.page(null, 2).ok()
        assertEquals(listOf(ids[2], ids[1]), first.rows.map { it.ride.id })
        assertEquals(listOf(rows(ids[2]), rows(ids[1])), first.rows.map { it.publish })
        val rest = store.rides.page(first.next, 2).ok()
        assertEquals(listOf(rows(ids[0])), rest.rows.map { it.publish })
        assertNull(rest.next)
        assertEquals(rows(ids[1]), store.rides.ride(ids[1]).ok()?.publish)
    }
}

abstract class HealthNearestContract : SqliteArchiveContract() {
    @Test
    fun nearestAnswersTheClosestSampleOfTheMetricsFromTheSource() = sqlite { store ->
        val id = rideId(1)
        store.create(ride(id))
        store.commit(
            Mutation.AppendHealthSamples(
                id,
                listOf(
                    sample(id, "h1", -1_000_000),
                    sample(id, "h2", 3_000_000),
                    sample(
                        id,
                        "e1",
                        1_000_000,
                        metric = activeEnergy,
                        producer = Producer.phone,
                        representation = HealthRepresentation.cumulativeWorkoutTotal,
                        dataOrigin = "app.phone",
                    ),
                ),
            )
        )
        val heart = setOf(heartRate)
        val both = setOf(heartRate, activeEnergy)
        val energy = setOf(activeEnergy)
        val cases =
            listOf(
                Triple(Nearest(NearestTarget.Health(heart, null), 0, true, MAX_SAFE), -1_000_000L, "an earlier sample"),
                Triple(Nearest(NearestTarget.Health(heart, null), 0, false, MAX_SAFE), 3_000_000L, "a later sample"),
                Triple(Nearest(NearestTarget.Health(heart, null), 0, false, 2_000_000), null, "beyond the bound"),
                Triple(Nearest(NearestTarget.Health(both, null), 0, false, MAX_SAFE), 1_000_000L, "either metric"),
                Triple(
                    Nearest(
                        NearestTarget.Health(energy, HealthSourceFilter(Producer.watch, null, null)),
                        0,
                        false,
                        MAX_SAFE,
                    ),
                    null,
                    "another producer",
                ),
                Triple(
                    Nearest(
                        NearestTarget.Health(energy, HealthSourceFilter(Producer.phone, "app.phone", null)),
                        0,
                        false,
                        MAX_SAFE,
                    ),
                    1_000_000L,
                    "the producer and data origin",
                ),
                Triple(
                    Nearest(
                        NearestTarget.Health(
                            energy,
                            HealthSourceFilter(null, null, setOf(HealthRepresentation.rawQuantity)),
                        ),
                        0,
                        false,
                        MAX_SAFE,
                    ),
                    null,
                    "another representation",
                ),
                Triple(Nearest(NearestTarget.Health(emptySet(), null), 0, false, MAX_SAFE), null, "no metric"),
            )
        for ((query, expected, name) in cases) assertEquals(expected, query.on(store, id), name)
    }
}

abstract class HealthLastFactContract : SqliteArchiveContract() {
    @Test
    fun lastFactCountsHealthSamplesByTheirEnd() = sqlite { store ->
        val healthOnly = rideId(1)
        val mixed = rideId(2)
        store.create(ride(healthOnly))
        store.create(ride(mixed))
        store.commit(
            Mutation.AppendHealthSamples(
                healthOnly,
                listOf(
                    sample(healthOnly, "a", -1_000_000),
                    sample(healthOnly, "b", 3_000_000),
                    sample(healthOnly, "c", 2_000_000),
                ),
            )
        )
        assertEquals(RetainedEnd(START_UTC_US + 3_000_000, 3_000_000), store.measurements.lastFact(healthOnly).ok())
        store.commit(
            Mutation.OpenControllerSession(session(mixed)),
            append(mixed, readings(3, fromUs = 1_000_000)),
            Mutation.AppendHealthSamples(mixed, listOf(sample(mixed, "a", 2_000_000))),
        )
        assertEquals(RetainedEnd(START_UTC_US + 2_000_000, 2_000_000), store.measurements.lastFact(mixed).ok())
        store.commit(append(mixed, readings(1, fromUs = 4_000_000, firstSeq = 4)))
        assertEquals(RetainedEnd(START_UTC_US + 4_000_000, 4_000_000), store.measurements.lastFact(mixed).ok())
    }
}
