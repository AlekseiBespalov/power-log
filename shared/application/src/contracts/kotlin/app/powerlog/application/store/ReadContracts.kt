package app.powerlog.application.store

import app.powerlog.application.store.ContractData.MAX_SAFE
import app.powerlog.application.store.ContractData.Reading
import app.powerlog.application.store.ContractData.START_UTC_US
import app.powerlog.application.store.ContractData.append
import app.powerlog.application.store.ContractData.event
import app.powerlog.application.store.ContractData.fix
import app.powerlog.application.store.ContractData.fixes
import app.powerlog.application.store.ContractData.health
import app.powerlog.application.store.ContractData.heartRate
import app.powerlog.application.store.ContractData.identity
import app.powerlog.application.store.ContractData.readings
import app.powerlog.application.store.ContractData.ride
import app.powerlog.application.store.ContractData.rideId
import app.powerlog.application.store.ContractData.sample
import app.powerlog.application.store.ContractData.session
import app.powerlog.application.store.ContractData.timing
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val STORED_TELEMETRY: List<MetricId> =
    listOf(
        MetricId.humanPowerW,
        MetricId.cadenceRpm,
        MetricId.motorInputPowerW,
        MetricId.batteryVoltageV,
        MetricId.batteryCurrentA,
        MetricId.motorCurrentA,
        MetricId.motorRpm,
        MetricId.pedalTorqueNm,
        MetricId.controllerTempC,
        MetricId.motorTempC,
        MetricId.consumedAh,
        MetricId.consumedWh,
        MetricId.throttleVoltageV,
        MetricId.faultCode,
        MetricId.assistLevel,
        MetricId.speedRaw,
        MetricId.raceMode,
    )

internal val AWKWARD_TEXTS = listOf("", "a,b", "say \"hi\"", "two\nlines", "naïve 🚲 ñ", "x".repeat(300))

private val REALS = listOf(-1.0, 0.0, 0.1, 123.456789, 1e-300, 4.9e-324, Double.MAX_VALUE, -Double.MAX_VALUE)

abstract class ColumnRangeContract : ArchiveContract() {
    @Test
    fun everyRideColumnRoundTripsAtItsExtremesAndInEveryVocabulary() = contract { store ->
        val written = rideColumns()
        for ((ride, health) in written) store.create(ride, health)
        for ((ride, health) in written) {
            val record = assertNotNull(store.rides.ride(ride.id).ok(), "${ride.id}")
            assertEquals(ride, record.ride)
            assertEquals(health, record.health)
            assertEquals(1L, record.revision)
        }
    }

    @Test
    fun everyEventColumnRoundTrips() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        val events =
            RideEventKind.entries.mapIndexed { index, kind ->
                RideEvent(ContractData.eventId(index + 1), kind, -MAX_SAFE + index, -MAX_SAFE + index)
            } +
                listOf(
                    RideEvent(ContractData.eventId(10), RideEventKind.lap, 0, 0),
                    RideEvent(ContractData.eventId(11), RideEventKind.stop, MAX_SAFE, MAX_SAFE),
                )
        store.commit(Mutation.AppendEvents(id, events))
        assertEquals(events, store.measurements.events(id).ok())
    }

    @Test
    fun everyControllerSessionColumnRoundTrips() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        val numbers = listOf(1, 2, Int.MAX_VALUE)
        val identities =
            numbers.indices.map {
                ControllerIdentity(DecoderId("cyc"), AWKWARD_TEXTS[it], AWKWARD_TEXTS[it + 2], AWKWARD_TEXTS[it + 3])
            }
        store.commit(
            *numbers
                .zip(identities)
                .map { (no, identity) -> Mutation.OpenControllerSession(session(id, no, identity)) }
                .toTypedArray()
        )
        val stored = store.measurements.sessions(id).ok()
        assertEquals(numbers, stored.map { it.sessionNo })
        assertEquals(identities, stored.map { it.identity })
    }

    @Test
    fun everyTelemetryColumnRoundTripsAtItsMinimumMaximumZeroAndAbsence() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        val all = STORED_TELEMETRY.toSet()
        val rows =
            listOf(
                Reading(-MAX_SAFE, 1, 1, -MAX_SAFE, null, 0, all.associateWith { -MAX_SAFE }),
                Reading(0, 2, 1, 0, 1, 1, all.associateWith { MAX_SAFE }),
                Reading(0, 1, Int.MAX_VALUE, MAX_SAFE, Int.MAX_VALUE, Int.MAX_VALUE, all.associateWith { 0L }),
                Reading(MAX_SAFE, MAX_SAFE, 1, 1, 1, 1, emptyMap()),
            )
        store.commit(append(id, rows))
        assertEquals(rows, store.allTelemetry(id))
    }

    @Test
    fun everyLocationColumnRoundTripsAtItsExtremesAndAbsence() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        val fixes =
            REALS.indices.map { index ->
                val real = REALS[index]
                val absent = index % 2 == 1
                LocationFix(
                    rideId = id,
                    seq = index + 1L,
                    elapsedUs =
                        if (index == 0) -MAX_SAFE else if (index == REALS.lastIndex) MAX_SAFE else index.toLong(),
                    utcUs = if (index == 0) -MAX_SAFE else MAX_SAFE - index,
                    activeInterval = listOf(null, 1, Int.MAX_VALUE)[index % 3],
                    run = listOf(0, 1, Int.MAX_VALUE)[index % 3],
                    barrier = index % 2 == 0,
                    latitude = real,
                    longitude = REALS[REALS.lastIndex - index],
                    horizontalAccuracyM = real.takeUnless { absent },
                    altitudeM = real.takeUnless { absent },
                    altitudeDatum = AltitudeDatum.entries[index % AltitudeDatum.entries.size],
                    verticalAccuracyM = real.takeUnless { absent },
                    mslM = real.takeUnless { absent },
                    mslVerticalAccuracyM = real.takeUnless { absent },
                    speedMps = real.takeUnless { absent },
                    speedAccuracyMps = real.takeUnless { absent },
                    courseDeg = real.takeUnless { absent },
                    courseAccuracyDeg = real.takeUnless { absent },
                )
            }
        store.commit(Mutation.AppendLocations(id, fixes))
        assertEquals(fixes, store.allLocations(id))
    }

    @Test
    fun knownControllersKeepTheirBikeIdAsGivenAndTheirTexts() = contract { store ->
        val bikes =
            listOf("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff", "aBc+/=", "E621E1F8-C36C-495A-93FC-0C247A3E6E5F")
                .map(::BikeId)
        val identities =
            bikes.indices.map {
                ControllerIdentity(DecoderId("cyc"), AWKWARD_TEXTS[it + 1], AWKWARD_TEXTS[it], AWKWARD_TEXTS[it + 2])
            }
        for ((bike, identity) in bikes.zip(identities)) store.commit(Mutation.RememberController(bike, identity))
        assertEquals(identities, bikes.map { store.connection.known(it).ok() })
        for (bike in bikes) {
            store.commit(Mutation.SelectBike(bike))
            assertEquals(bike, store.connection.selected().ok())
        }
    }

    private fun rideColumns(): List<Pair<Ride, RideHealth>> {
        val outcomes = HealthOutcome.entries
        val reasons = listOf<HealthReason?>(null) + HealthReason.entries
        val count = maxOf(outcomes.size, reasons.size, RidePhase.entries.size)
        return List(count) { index ->
            val flag = index % 2 == 0
            val extreme = listOf(-MAX_SAFE, 0L, MAX_SAFE)[index % 3]
            val ride =
                Ride(
                    id = rideId(index + 1),
                    recordedBy = Device.entries[index % Device.entries.size],
                    example = flag,
                    startedUtcUs = extreme,
                    options =
                        RideOptions(
                            sampleRate = SampleRate.entries[index % SampleRate.entries.size],
                            indoor = !flag,
                            useWatch = flag,
                            saveToHealth = !flag,
                            recordGps = flag,
                            distanceSource = DistanceSource.entries[index % DistanceSource.entries.size],
                            batteryMethod = BatteryMethod.entries[index % BatteryMethod.entries.size],
                        ),
                    phase = RidePhase.entries[index % RidePhase.entries.size],
                    interrupted = !flag,
                    checkpoint = RideTiming(extreme, extreme, extreme),
                    end = if (index % 4 == 3) null else RideTiming(-extreme, -extreme, -extreme),
                )
            val health =
                RideHealth(
                    provider =
                        listOf(null, HealthProviderKind.appleHealth, HealthProviderKind.healthConnect)[index % 3],
                    outcome = outcomes[index % outcomes.size],
                    reason = reasons[index % reasons.size],
                    workoutId = (listOf(null) + AWKWARD_TEXTS)[index % (AWKWARD_TEXTS.size + 1)],
                )
            ride to health
        }
    }
}

abstract class KeysetPagingContract : ArchiveContract() {
    private val limits = listOf(1, 7, 4_096)

    @Test
    fun telemetryPagesHoldEveryRowOnceInKeyOrderWithNextNullExactlyAtTheEnd() = contract { store ->
        val id = rideId(1)
        val rows = telemetry()
        store.create(ride(id))
        rows.chunked(5).forEach { store.commit(append(id, it)) }
        for (limit in limits) assertEquals(rows, store.allTelemetry(id, limit = limit), "limit $limit")
        val from = rows[3].elapsedUs
        val to = rows[20].elapsedUs
        val inside = rows.filter { it.elapsedUs in from until to }
        for (limit in limits) assertEquals(inside, store.allTelemetry(id, TimeRange(from, to), limit), "limit $limit")
        for (range in listOf(TimeRange(from, from), TimeRange(rows.last().elapsedUs + 1, rows.last().elapsedUs + 9))) {
            val page = store.measurements.telemetry(id, range, null, 7).ok()
            assertEquals(0, page.rows.rows)
            assertNull(page.next)
        }
    }

    @Test
    fun locationPagesHoldEveryFixOnceInKeyOrderWithNextNullExactlyAtTheEnd() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        val fixes = List(28) { fix(id, it + 1L, it / 2 * 500_000L) }
        fixes.chunked(6).forEach { store.commit(Mutation.AppendLocations(id, it)) }
        for (limit in limits) assertEquals(fixes, store.allLocations(id, limit = limit), "limit $limit")
        val from = fixes[5].elapsedUs
        val to = fixes[17].elapsedUs
        val inside = fixes.filter { it.elapsedUs in from until to }
        for (limit in limits) assertEquals(inside, store.allLocations(id, TimeRange(from, to), limit), "limit $limit")
        val empty = store.measurements.locations(id, TimeRange(from, from), null, 7).ok()
        assertEquals(LocationPage(emptyList(), null), empty)
    }

    private fun telemetry(): List<Reading> {
        val rows = mutableListOf<Reading>()
        var second = 0L
        for (index in 0 until 21) {
            val elapsedUs = index * 125_000L
            rows += Reading(elapsedUs, index + 1L, 1)
            if (index % 2 == 0) rows += Reading(elapsedUs, ++second, 2)
        }
        return rows.take(27) + Reading(rows[26].elapsedUs, 1_000, 3)
    }
}

abstract class RidePagesContract : ArchiveContract() {
    @Test
    fun pagesAreNewestFirstWithDeletedRidesHiddenAndLimitCountingRides() = contract { store ->
        val rides = population(store)
        val expected = listOf(rides[4], rides[0], rides[2], rides[1], rides[5])
        for (limit in listOf(1, 2, 5, 50)) {
            assertEquals(expected, pages(store, limit).map { it.ride.id }, "limit $limit")
        }
        val page = store.rides.page(null, 2).ok()
        assertEquals(RideCursor(START_UTC_US + 3_000_000, rides[0]), page.next)
        assertEquals(
            listOf(rides[5]),
            store.rides.page(RideCursor(START_UTC_US + 2_000_000, rides[1]), 2).ok().rows.map { it.ride.id },
        )
        assertNull(store.rides.page(RideCursor(START_UTC_US, rides[5]), 2).ok().next)
    }

    @Test
    fun aRideTheWholePagingSeesAppearsOnceWhileOthersComeAndGo() = contract { store ->
        val rides = population(store)
        val first = store.rides.page(null, 2).ok()
        store.create(ride(rideId(8), phase = RidePhase.completed, startedUtcUs = START_UTC_US + 9_000_000, endUs = 1))
        store.create(ride(rideId(9), phase = RidePhase.completed, startedUtcUs = START_UTC_US + 2_500_000, endUs = 1))
        store.commit(Mutation.MarkRideDeleted(rides[5], START_UTC_US), closedRide = rides[5])
        val seen = (first.rows + pages(store, 2, first.next)).map { it.ride.id }
        assertEquals(seen.distinct(), seen)
        assertEquals(listOf(rides[4], rides[0], rides[2], rides[1]), seen.filter { it in rides })
    }

    @Test
    fun rideAndOpenRideAnswerTheUndeletedRideAndMarkerTheHistoryRevision() = contract { store ->
        val rides = population(store)
        assertNull(store.rides.ride(rides[3]).ok())
        assertNull(store.rides.ride(rideId(99)).ok())
        val record = assertNotNull(store.rides.ride(rides[0]).ok())
        assertEquals(RideRecord(record.ride, health, emptyList(), 1), record)
        assertEquals(rides[4], store.rides.openRide().ok()?.ride?.id)
        val committed = store.commit(Mutation.SetPhase(rides[4], RidePhase.completed))
        assertEquals(committed.historyRevision, store.rides.marker().ok())
        assertNull(store.rides.openRide().ok())
        val deleted = rideId(7)
        store.create(ride(deleted))
        store.commit(Mutation.MarkRideDeleted(deleted, START_UTC_US))
        assertNull(store.rides.openRide().ok())
    }

    private suspend fun population(store: StoreUnderTest): List<RideId> {
        val starts = listOf(3, 2, 2, 1, 5, 0).map { START_UTC_US + it * 1_000_000L }
        val ids = starts.indices.map { rideId(it + 1) }
        ids.zip(starts).forEachIndexed { index, (id, start) ->
            val open = index == 4
            store.create(
                ride(
                    id,
                    phase = if (open) RidePhase.running else RidePhase.completed,
                    startedUtcUs = start,
                    endUs = 1.takeUnless { open }?.toLong(),
                )
            )
        }
        store.commit(Mutation.MarkRideDeleted(ids[3], START_UTC_US), closedRide = ids[3])
        return ids
    }

    private suspend fun pages(store: StoreUnderTest, limit: Int, from: RideCursor? = null): List<RideRecord> {
        val records = mutableListOf<RideRecord>()
        var after = from
        var pages = 0
        do {
            val page = store.rides.page(after, limit).ok()
            pages++
            assertEquals(true, page.rows.size <= limit, "a page holds ${page.rows.size} rides for a limit of $limit")
            if (pages > 1) assertTrue(page.rows.isNotEmpty(), "next was not null at the end")
            records += page.rows
            after = page.next
            if (after != null)
                assertEquals(page.rows.lastOrNull()?.let { RideCursor(it.ride.startedUtcUs, it.ride.id) }, after)
        } while (after != null)
        return records
    }
}

abstract class MeasurementReadsContract : ArchiveContract() {
    @Test
    fun extentAnswersEachKindsRangeAndTheHealthMetricsAndProducers() = contract { store ->
        val id = rideId(1)
        val empty = rideId(2)
        store.create(ride(id))
        store.create(ride(empty))
        val rows = readings(9, fromUs = 250_000)
        store.commit(Mutation.OpenControllerSession(session(id)), append(id, rows))
        store.commit(Mutation.AppendLocations(id, fixes(id, 3, fromUs = 100_000)))
        store.commit(
            Mutation.AppendEvents(id, listOf(event(1, RideEventKind.start, 0), event(2, RideEventKind.stop, 9_000_000)))
        )
        val samples =
            listOf(
                sample(id, "s1", -2_000_000),
                sample(id, "s2", 5_000_000, metric = HealthMetric("activeEnergyKcal"), producer = Producer.phone),
            )
        if (storesHealth) store.commit(Mutation.AppendHealthSamples(id, samples))
        val revision = store.revision(id)!!
        val expected =
            Extent(
                revision = revision,
                telemetry = TimeRange(rows.first().elapsedUs, rows.last().elapsedUs + 1),
                locations = TimeRange(100_000, 2_100_001),
                health = if (storesHealth) TimeRange(-2_000_000, 5_000_001) else null,
                healthMetrics = if (storesHealth) setOf(heartRate, HealthMetric("activeEnergyKcal")) else emptySet(),
                healthProducers = if (storesHealth) setOf(Producer.watch, Producer.phone) else emptySet(),
            )
        assertEquals(expected, store.measurements.extent(id).ok())
        assertEquals(
            Extent(store.revision(empty)!!, null, null, null, emptySet(), emptySet()),
            store.measurements.extent(empty).ok(),
        )
        assertNull(store.measurements.extent(rideId(3)).ok())
    }

    @Test
    fun sessionsCarryTheirDecodersCapabilitiesInSessionOrder() = contract { store ->
        val id = rideId(1)
        val other = ControllerIdentity(DecoderId("cyc"), "X12", "20260202", "5.4")
        store.create(ride(id))
        store.commit(Mutation.OpenControllerSession(session(id, 1, identity)))
        store.commit(Mutation.OpenControllerSession(session(id, 2, other)))
        val stored = store.measurements.sessions(id).ok()
        assertEquals(listOf(1, 2), stored.map { it.sessionNo })
        assertEquals(listOf(identity, other), stored.map { it.identity })
        assertEquals(
            listOf(identity, other).map { ControllerDecoders.of(it.decoder)?.capabilities(it) },
            stored.map { it.capabilities },
        )
    }

    @Test
    fun aSessionOfAnUnknownDecoderFailsItsReadAsConstraint() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        store.commit(Mutation.OpenControllerSession(session(id, 1, identity.copy(decoder = DecoderId("unknown")))))
        store.measurements.sessions(id).assertFailed(StorageError.Kind.constraint)
    }

    @Test
    fun eventsAnswerInSeqOrderWhichIsElapsedOrder() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        val events =
            listOf(
                event(1, RideEventKind.start, 0),
                event(2, RideEventKind.pause, 1_000_000),
                event(3, RideEventKind.lap, 1_000_000),
                event(4, RideEventKind.resume, 2_000_000),
            )
        store.commit(Mutation.AppendEvents(id, events.take(2)))
        store.commit(Mutation.AppendEvents(id, events.drop(2)))
        assertEquals(events, store.measurements.events(id).ok())
    }

    @Test
    fun telemetryCarriesSpeedRawAndRaceModeFromTheirColumnsAndNeverControllerSpeed() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        store.commit(
            Mutation.OpenControllerSession(session(id)),
            append(id, listOf(Reading(0, 1, quanta = mapOf(MetricId.speedRaw to 2_000L, MetricId.raceMode to 1L)))),
        )
        val page = store.measurements.telemetry(id, ContractData.ALL, null, 10).ok()
        assertEquals(listOf(2_000.0), page.rows.quanta[MetricId.speedRaw]?.toList())
        assertEquals(listOf(1.0), page.rows.quanta[MetricId.raceMode]?.toList())
        assertEquals(false, MetricId.controllerSpeedMps in page.rows.quanta)
    }
}

abstract class NearestContract : ArchiveContract() {
    @Test
    fun nearestAnswersTheClosestTelemetryRowReportingTheMetricWithinTheBound() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        val power = mapOf(MetricId.humanPowerW to 1L)
        val rows =
            listOf(
                Reading(0, 1, quanta = power),
                Reading(1_000_000, 2, quanta = mapOf(MetricId.cadenceRpm to 1L)),
                Reading(2_000_000, 3, quanta = power),
                Reading(3_000_000, 4, quanta = mapOf(MetricId.cadenceRpm to 2L)),
            )
        store.commit(Mutation.OpenControllerSession(session(id)), append(id, rows))
        val humanPower = NearestTarget.Telemetry(MetricId.humanPowerW)
        val cases =
            listOf(
                Triple(Nearest(humanPower, 1_500_000, true, 2_000_000), 0L, "the earlier row, skipping one without it"),
                Triple(Nearest(humanPower, 1_500_000, true, 1_000_000), null, "beyond the bound"),
                Triple(Nearest(humanPower, 1_500_000, false, 2_000_000), 2_000_000L, "the later row"),
                Triple(Nearest(humanPower, 2_000_000, false, 0), 2_000_000L, "a row at the instant"),
                Triple(Nearest(humanPower, 2_000_000, true, 2_000_000), 0L, "before excludes the instant"),
                Triple(Nearest(humanPower, 2_000_001, false, 9_000_000), null, "nothing after"),
                Triple(
                    Nearest(NearestTarget.Telemetry(MetricId.cadenceRpm), 3_000_000, true, 9_000_000),
                    1_000_000L,
                    "cadence",
                ),
                Triple(
                    Nearest(NearestTarget.Telemetry(MetricId.heartRateBpm), 0, false, MAX_SAFE),
                    null,
                    "no telemetry column",
                ),
                Triple(
                    Nearest(NearestTarget.Telemetry(MetricId.controllerSpeedMps), 0, false, MAX_SAFE),
                    null,
                    "derived",
                ),
            )
        for ((query, expected, name) in cases) assertEquals(expected, query.on(store, id), name)
    }

    @Test
    fun nearestAnswersTheClosestFixWhoseColumnForTheMetricHoldsAValue() = contract { store ->
        val id = rideId(1)
        store.create(ride(id))
        store.commit(
            Mutation.AppendLocations(
                id,
                listOf(
                    fix(id, 1, 0, mslM = null, horizontalAccuracyM = null),
                    fix(id, 2, 2_000_000, speedMps = null),
                    fix(id, 3, 4_000_000, mslM = null),
                ),
            )
        )
        val cases =
            listOf(
                Triple(
                    Nearest(NearestTarget.Location(MetricId.speedMps), 1_000_000, false, MAX_SAFE),
                    4_000_000L,
                    "speed",
                ),
                Triple(
                    Nearest(NearestTarget.Location(MetricId.altitudeMeters), 0, false, MAX_SAFE),
                    2_000_000L,
                    "MSL altitude",
                ),
                Triple(
                    Nearest(NearestTarget.Location(MetricId.altitudeMeters), 4_000_000, true, 1_000_000),
                    null,
                    "bound",
                ),
                Triple(
                    Nearest(NearestTarget.Location(MetricId.horizontalAccuracyM), 1_000_000, true, MAX_SAFE),
                    null,
                    "accuracy",
                ),
                Triple(
                    Nearest(NearestTarget.Location(MetricId.verticalAccuracyM), 0, false, MAX_SAFE),
                    0L,
                    "MSL accuracy",
                ),
                Triple(
                    Nearest(NearestTarget.Location(MetricId.courseDegrees), 5_000_000, true, MAX_SAFE),
                    4_000_000L,
                    "course",
                ),
                Triple(
                    Nearest(NearestTarget.Location(MetricId.humanPowerW), 0, false, MAX_SAFE),
                    null,
                    "no location column",
                ),
            )
        for ((query, expected, name) in cases) assertEquals(expected, query.on(store, id), name)
    }
}

internal class Nearest(val target: NearestTarget, val atUs: Long, val before: Boolean, val withinUs: Long) {
    suspend fun on(store: StoreUnderTest, rideId: RideId): Long? =
        store.measurements.nearest(rideId, target, atUs, before, withinUs).ok()
}

abstract class LastFactContract : ArchiveContract() {
    @Test
    fun lastFactAnswersTheLatestTelemetryRowOrFixAndIgnoresEvents() = contract { store ->
        val telemetry = rideId(1)
        val locations = rideId(2)
        val both = rideId(3)
        val events = rideId(4)
        val nothing = rideId(5)
        for (id in listOf(telemetry, locations, both, events, nothing)) store.create(ride(id))
        val rows = readings(4)
        store.commit(Mutation.OpenControllerSession(session(telemetry)), append(telemetry, rows))
        store.commit(Mutation.AppendLocations(locations, fixes(locations, 3)))
        store.commit(
            Mutation.OpenControllerSession(session(both)),
            append(both, readings(4, fromUs = 2_500_000)),
            Mutation.AppendLocations(both, fixes(both, 3)),
        )
        store.commit(
            Mutation.AppendEvents(
                events,
                listOf(event(1, RideEventKind.start, 0), event(2, RideEventKind.stop, 9_000_000)),
            )
        )
        assertEquals(RetainedEnd(rows.last().utcUs, rows.last().elapsedUs), store.measurements.lastFact(telemetry).ok())
        assertEquals(RetainedEnd(START_UTC_US + 2_000_000, 2_000_000), store.measurements.lastFact(locations).ok())
        assertEquals(RetainedEnd(START_UTC_US + 2_875_000, 2_875_000), store.measurements.lastFact(both).ok())
        assertNull(store.measurements.lastFact(events).ok())
        assertNull(store.measurements.lastFact(nothing).ok())
    }
}

abstract class VocabularyContract : ArchiveContract() {
    @Test
    fun aStoredValueOutsideItsVocabularyFailsOnlyTheReadsOfItsRow() = contract { store ->
        val bad = rideId(1)
        val good = rideId(2)
        store.create(ride(bad))
        store.create(ride(good))
        val record = store.rides.ride(good).ok()
        store.writeOutOfVocabulary(bad)
        store.rides.ride(bad).assertFailed(StorageError.Kind.constraint)
        assertEquals(record, store.rides.ride(good).ok())
        store.commit(Mutation.Checkpoint(good, timing(START_UTC_US, 1_000_000)))
        assertEquals(timing(START_UTC_US, 1_000_000), store.rides.ride(good).ok()?.ride?.checkpoint)
    }
}
