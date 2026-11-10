package app.powerlog.application.store

import app.powerlog.application.store.ContractData.START_UTC_US
import app.powerlog.application.store.ContractData.append
import app.powerlog.application.store.ContractData.event
import app.powerlog.application.store.ContractData.fix
import app.powerlog.application.store.ContractData.fixes
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
import app.powerlog.domain.charts.DerivedResume
import app.powerlog.domain.charts.PlotAggregate
import app.powerlog.domain.charts.PlotPoint
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.battery.ConsumptionTotals
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.energyperdistance.EnergyPerDistanceTotals
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.summary.LatLon
import app.powerlog.domain.core.analysis.summary.RideSummary
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.settings.AppSettingsDraft
import app.powerlog.domain.settings.ChartRange
import app.powerlog.domain.settings.LocaleFacts
import app.powerlog.domain.settings.MeasurementSystem
import app.powerlog.domain.settings.RideSettingsDraft
import app.powerlog.domain.settings.SettingsDraft
import app.powerlog.domain.settings.SettingsRules
import app.powerlog.domain.settings.ViewId
import app.powerlog.domain.settings.ViewPresetDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

internal fun summaryOf(rideId: RideId, distanceQ: Long = 1_000_000) =
    RideSummary(
        rideId = rideId,
        end = timing(START_UTC_US, 60_000_000, 55_000_000),
        distance = mapOf(DistanceSource.controller to DistanceTotals(distanceQ, 55_000_000, false, false)),
        averageSpeedMps = 5.5,
        battery = mapOf(ConsumptionMetric.controllerEnergyWh to ConsumptionTotals(1.5, 55_000_000, true, false, false)),
        averagePowerW = 150.0,
        averageCadenceRpm = 80.0,
        averageHeartRateBpm = null,
        ascentM = 12.5,
        activeEnergyKcal = 31.25,
        physiologyBackfilled = false,
        energyPerDistance = mapOf(MetricId.batteryWhPerKm to EnergyPerDistanceTotals(1.5, 1_000.0, 1.5)),
        route = listOf(listOf(LatLon(45.5, 9.2), LatLon(45.50005, 9.20004)), listOf(LatLon(45.6, 9.3))),
    )

internal fun aggregateOf(slot: Long): PlotAggregate {
    val point = OriginalRef(START_UTC_US + slot, slot, slot * 1_000)
    return PlotAggregate(
        points = listOf(PlotPoint(point, point, point.copy(valueQ = slot * 2_000), point, 0)),
        statistics = MetricReducer(MetricId.humanPowerW, TimeRange(0, 16_000_000)).state(),
        resume = DerivedResume.Distance(null, null, DistanceTotals(slot, 16_000_000, slot % 2 == 0L, false)),
    )
}

abstract class RememberedBikeContract : ArchiveContract() {
    @Test
    fun aRememberedBikeAndTheSelectionReadBackAndAForgottenBikeAnswersNull() = contract { store ->
        val bike = BikeId("AA:BB:CC:DD:EE:FF")
        val other = BikeId("aa:bb:cc:dd:ee:ff")
        assertNull(store.connection.known(bike).ok())
        assertNull(store.connection.selected().ok())
        store.commit(Mutation.RememberController(bike, identity))
        assertEquals(identity, store.connection.known(bike).ok())
        assertNull(store.connection.known(other).ok())
        store.commit(Mutation.SelectBike(bike))
        assertEquals(bike, store.connection.selected().ok())
        val updated = identity.copy(firmware = "20260301")
        store.commit(Mutation.RememberController(bike, updated))
        assertEquals(updated, store.connection.known(bike).ok())
        store.commit(Mutation.SelectBike(null))
        assertNull(store.connection.selected().ok())
        assertEquals(updated, store.connection.known(bike).ok())
    }
}

abstract class DeletionContract : ArchiveContract() {
    @Test
    fun aDeletedRideIsHiddenTakesNoWritesAndItsCleanupResumesAcrossAReopen() = contract { store ->
        val kept = rideId(1)
        val deleted = rideId(2)
        store.create(ride(kept))
        store.commit(Mutation.OpenControllerSession(session(kept)), append(kept, readings(3)))
        val keptState = state(store, kept)
        val stored = store.storedRows()
        store.create(ride(deleted, phase = RidePhase.completed, endUs = 60_000_000))
        store.commit(Mutation.OpenControllerSession(session(deleted)), append(deleted, readings(20)))
        store.commit(append(deleted, readings(20, fromUs = 2_500_000, firstSeq = 21)))
        store.commit(
            Mutation.OpenControllerSession(session(deleted, sessionNo = 2)),
            Mutation.AppendLocations(deleted, fixes(deleted, 10)),
            Mutation.AppendEvents(
                deleted,
                listOf(event(1, RideEventKind.start, 0), event(2, RideEventKind.stop, 60_000_000)),
            ),
        )
        if (storesHealth)
            store.commit(
                Mutation.AppendHealthSamples(deleted, List(6) { sample(deleted, "s$it", it * 1_000_000L) }),
                Mutation.PutHealthWork(listOf(publish(deleted, HealthComponent.workout), readback(deleted, heartRate))),
            )
        val revision = store.revision(deleted)!!
        store.cache.putSummary(deleted, revision, summaryOf(deleted))
        store.cache.putTiles(deleted, MetricId.humanPowerW, 0, 0, mapOf(0L to aggregateOf(0), 1L to aggregateOf(1)))
        store.commit(
            Mutation.MarkRideDeleted(deleted, START_UTC_US + 61_000_000),
            Mutation.RemoveHealthWork(deleted, null),
            closedRide = deleted,
        )
        assertHidden(store, deleted)
        assertEquals(Commit.RideGone(deleted), store.answer(append(deleted, readings(1, fromUs = 9_000_000))).ok())
        assertEquals(listOf(deleted), store.maintenance.pendingDeletions().ok())
        val maxRows = 3
        var current = store
        var steps = 0
        var rows = current.storedRows()
        do {
            val step = current.maintenance.cleanupStep(deleted, maxRows).ok()
            val left = current.storedRows()
            assertTrue(rows - left in 1..maxRows, "cleanup step ${steps + 1} deleted ${rows - left} rows")
            rows = left
            assertTrue(++steps < 1_000, "cleanup keeps answering more")
            if (step == Step.more) {
                if (steps == 2) current = opened(current.reopen())
                assertEquals(listOf(deleted), current.maintenance.pendingDeletions().ok())
                assertHidden(current, deleted)
            }
        } while (step == Step.more)
        assertEquals(emptyList(), current.maintenance.pendingDeletions().ok())
        assertEquals(stored, current.storedRows())
        assertEquals(keptState, state(current, kept))
        current.cache.dropRide(deleted)
        assertNull(current.cache.summary(deleted, revision))
        assertEquals(emptyMap(), current.cache.tiles(deleted, MetricId.humanPowerW, 0, 0L..1L, revision))
    }

    @Test
    fun cleanupSplitsKeyTiesAndDeletesTheHealthWorkADeletionLeftBehind() = contract { store ->
        val id = rideId(1)
        store.create(ride(id, phase = RidePhase.completed, endUs = 60_000_000))
        val stored = store.storedRows() - 1
        store.commit(
            Mutation.OpenControllerSession(session(id)),
            Mutation.OpenControllerSession(session(id, sessionNo = 2)),
            append(id, readings(3, stepUs = 0)),
            append(id, readings(3, stepUs = 0, sessionNo = 2)),
            Mutation.AppendLocations(id, List(3) { fix(id, it + 1L, 0) }),
        )
        if (storesHealth)
            store.commit(
                Mutation.AppendHealthSamples(id, List(3) { sample(id, "s$it", 0) }),
                Mutation.PutHealthWork(
                    HealthComponent.entries.take(3).map { publish(id, it) } + readback(id, heartRate)
                ),
            )
        store.commit(Mutation.MarkRideDeleted(id, START_UTC_US + 61_000_000))
        var rows = store.storedRows()
        var steps = 0
        do {
            val step = store.maintenance.cleanupStep(id, 2).ok()
            val left = store.storedRows()
            assertTrue(rows - left in 1..2, "cleanup step ${steps + 1} deleted ${rows - left} rows")
            rows = left
            assertTrue(++steps < 100, "cleanup keeps answering more")
        } while (step == Step.more)
        assertEquals(stored, rows)
        assertEquals(emptyList(), store.maintenance.pendingDeletions().ok())
    }

    private suspend fun assertHidden(store: StoreUnderTest, rideId: RideId) {
        assertNull(store.rides.ride(rideId).ok())
        assertNull(store.measurements.extent(rideId).ok())
        assertEquals(false, rideId in store.rides.page(null, 50).ok().rows.map { it.ride.id })
        assertEquals(emptyList(), store.measurements.sessions(rideId).ok())
        assertEquals(emptyList(), store.measurements.events(rideId).ok())
        assertEquals(emptyList(), store.allTelemetry(rideId))
        assertEquals(emptyList(), store.allLocations(rideId))
        assertEquals(emptyList(), store.allHealth(rideId))
        assertEquals(emptyList(), store.health.work(rideId).ok())
    }

    private suspend fun state(store: StoreUnderTest, rideId: RideId): List<Any?> =
        listOf(store.rides.ride(rideId).ok(), store.measurements.sessions(rideId).ok(), store.allTelemetry(rideId))
}

abstract class ReclaimContract : SqliteArchiveContract() {
    @Test
    fun reclaimEmptiesBothFreeListsAndTheFilesShrink() =
        sqlite(timeout = 5.minutes) { store ->
            val id = rideId(1)
            store.create(ride(id, phase = RidePhase.completed, endUs = 5_000_000_000))
            store.commit(Mutation.OpenControllerSession(session(id)))
            for (batch in 0 until 10) {
                val first = batch * BATCH_ROWS
                store.commit(append(id, readings(BATCH_ROWS, fromUs = first * 125_000L, firstSeq = first + 1L)))
            }
            store.commit(Mutation.AppendLocations(id, fixes(id, 4_000)))
            store.cache.putTiles(id, MetricId.humanPowerW, 0, 0, (0L until 2_000L).associateWith(::aggregateOf))
            store.commit(
                Mutation.MarkRideDeleted(id, START_UTC_US),
                Mutation.RemoveHealthWork(id, null),
                closedRide = id,
            )
            do {
                val cleanup = store.maintenance.cleanupStep(id, BATCH_ROWS).ok()
            } while (cleanup == Step.more)
            store.cache.dropRide(id)
            store.maintenance.checkpoint(truncate = true).ok()
            val archiveBytes = store.fileBytes(StoreFile.archive)
            val cacheBytes = store.fileBytes(StoreFile.cache)
            assertTrue(store.freelistCount(StoreFile.archive) > 0)
            assertTrue(store.freelistCount(StoreFile.cache) > 0)
            var steps = 0
            while (store.maintenance.reclaimStep().ok() == Step.more) {
                if (steps == 0) assertTrue(store.walBytes(StoreFile.archive) > 0, "the vacuum writes the WAL")
                steps++
                assertTrue(steps < 10_000, "reclaim keeps answering more")
            }
            assertEquals(0L, store.walBytes(StoreFile.archive), "the done step truncates the archive's WAL")
            assertEquals(0L, store.walBytes(StoreFile.cache), "the done step truncates the cache's WAL")
            store.maintenance.checkpoint(truncate = true).ok()
            assertEquals(0L, store.freelistCount(StoreFile.archive))
            assertEquals(0L, store.freelistCount(StoreFile.cache))
            assertTrue(store.fileBytes(StoreFile.archive) < archiveBytes, "the archive shrinks")
            assertTrue(store.fileBytes(StoreFile.cache) < cacheBytes, "the cache shrinks")
        }

    private companion object {
        const val BATCH_ROWS = 4_096
    }
}

abstract class SettingsContract : ArchiveContract() {
    @Test
    fun noSettingsRowIsMissingAndASavedDocumentReadsBackAsItsDraft() = contract { store ->
        assertEquals(SettingsLoad.Missing, store.settings.document().ok())
        val document = SettingsRules.defaults(locale)
        store.settings.save(document).ok()
        assertEquals(SettingsLoad.Loaded(SettingsDraft.of(document)), store.settings.document().ok())
        store.writeSettingsText(null)
        assertEquals(SettingsLoad.Missing, store.settings.document().ok())
    }

    @Test
    fun textThatIsNotAJsonObjectIsUnreadable() = contract { store ->
        for (text in listOf("", "{", "[]", "\"ride\"", "42", "null", "{\"ride\": }")) {
            store.writeSettingsText(text)
            assertEquals(SettingsLoad.Unreadable, store.settings.document().ok(), text)
        }
    }

    @Test
    fun unknownKeysAndWrongTypesDropAndEveryKnownFieldOfTheRightTypeStays() = contract { store ->
        store.writeSettingsText(
            """{"ride":{"sampleRate":"4","distance":"gps:phone","battery":"calculated","indoor":"yes",""" +
                """"useWatch":true,"saveToHealth":1,"extra":{}},"app":{"units":"imperial","temperature":"kelvin",""" +
                """"fitPower":"battery","activeView":"battery","historyRange":"seconds600","views":{"ride":""" +
                """{"numbers":["humanPowerW","notAMetric"],"charts":"cadenceRpm","range":"all","webColumns":3},""" +
                """"elsewhere":{"range":"all"}}},"other":1}"""
        )
        val expected =
            SettingsDraft(
                ride =
                    RideSettingsDraft(
                        sampleRate = SampleRate.hz4,
                        distance = DistanceSelection.phoneGps,
                        battery = BatteryMethod.calculated,
                        indoor = null,
                        useWatch = true,
                        saveToHealth = null,
                        recordGps = null,
                        syncBeeps = null,
                    ),
                app =
                    AppSettingsDraft(
                        units = UnitSystem.imperial,
                        temperature = null,
                        fitPower = FitPowerSource.battery,
                        views =
                            mapOf(
                                ViewId.ride to
                                    ViewPresetDraft(listOf("humanPowerW", "notAMetric"), null, ChartRange.all, 3)
                            ),
                        activeView = ViewId.battery,
                        historyView = null,
                        historyRange = ChartRange.seconds600,
                    ),
            )
        assertEquals(SettingsLoad.Loaded(expected), store.settings.document().ok())
    }

    @Test
    fun resetSavesTheDefaultsOverAnUnreadableRow() = contract { store ->
        store.writeSettingsText("{")
        val defaults = SettingsRules.defaults(LocaleFacts("US", MeasurementSystem.us, TemperatureUnit.fahrenheit))
        store.settings.save(defaults).ok()
        assertEquals(SettingsLoad.Loaded(SettingsDraft.of(defaults)), store.settings.document().ok())
    }

    private val locale = LocaleFacts("DE", MeasurementSystem.metric, TemperatureUnit.celsius)
}

abstract class CacheContract : ArchiveContract() {
    private val ride = rideId(1)
    private val power = MetricId.humanPowerW

    @Test
    fun aSummaryIsAHitOnlyAtTheRevisionItWasPutWithAndTheNextPutOverwritesIt() = contract { store ->
        store.cache.putSummary(ride, 5, summaryOf(ride))
        assertEquals(summaryOf(ride), store.cache.summary(ride, 5))
        assertNull(store.cache.summary(ride, 6))
        store.cache.putSummary(ride, 6, summaryOf(ride, distanceQ = 2_000_000))
        assertNull(store.cache.summary(ride, 5))
        assertEquals(summaryOf(ride, distanceQ = 2_000_000), store.cache.summary(ride, 6))
    }

    @Test
    fun tilesAreHitsAtTheirRevisionAndFinalTilesAtAnyRevision() = contract { store ->
        store.cache.putTiles(ride, power, 2, 5, mapOf(0L to aggregateOf(0), 1L to aggregateOf(1)))
        store.cache.putTiles(ride, power, 2, 0, mapOf(2L to aggregateOf(2)))
        assertEquals(
            mapOf(0L to aggregateOf(0), 1L to aggregateOf(1), 2L to aggregateOf(2)),
            store.cache.tiles(ride, power, 2, 0L..3L, 5),
        )
        assertEquals(mapOf(2L to aggregateOf(2)), store.cache.tiles(ride, power, 2, 0L..3L, 6))
        assertEquals(mapOf(1L to aggregateOf(1)), store.cache.tiles(ride, power, 2, 1L..1L, 5))
        assertEquals(emptyMap(), store.cache.tiles(ride, power, 3, 0L..3L, 5))
        assertEquals(emptyMap(), store.cache.tiles(ride, MetricId.cadenceRpm, 2, 0L..3L, 5))
        store.cache.putTiles(ride, power, 2, 6, mapOf(0L to aggregateOf(9)))
        assertEquals(mapOf(0L to aggregateOf(9), 2L to aggregateOf(2)), store.cache.tiles(ride, power, 2, 0L..3L, 6))
        assertEquals(mapOf(1L to aggregateOf(1), 2L to aggregateOf(2)), store.cache.tiles(ride, power, 2, 0L..3L, 5))
    }

    @Test
    fun aChangedRulesVersionOrLayoutEmptiesTheCache() = contract { store ->
        var current = store
        for (change in CacheChange.entries) {
            current.cache.putSummary(ride, 5, summaryOf(ride))
            current.cache.putTiles(ride, power, 0, 0, mapOf(0L to aggregateOf(0)))
            current = opened(current.reopenAfter(change))
            assertNull(current.cache.summary(ride, 5), "$change")
            assertEquals(emptyMap(), current.cache.tiles(ride, power, 0, 0L..0L, 5), "$change")
        }
    }

    @Test
    fun anUndecodableValueIsAMissAndAFailingWriteThrowsNothing() = contract { store ->
        store.cache.putSummary(ride, 5, summaryOf(ride))
        store.cache.putTiles(ride, power, 0, 5, mapOf(0L to aggregateOf(0)))
        store.corruptCachedValues(ride)
        assertNull(store.cache.summary(ride, 5))
        assertEquals(emptyMap(), store.cache.tiles(ride, power, 0, 0L..0L, 5))
        val other = rideId(2)
        store.failCacheWrites()
        store.cache.putSummary(other, 5, summaryOf(other))
        store.cache.putTiles(other, power, 0, 5, mapOf(0L to aggregateOf(0)))
        assertNull(store.cache.summary(other, 5))
    }

    @Test
    fun dropRideRemovesOnlyThatRidesEntriesEvenWithNoRideRow() = contract { store ->
        val other = rideId(2)
        for (id in listOf(ride, other)) {
            store.cache.putSummary(id, 5, summaryOf(id))
            store.cache.putTiles(id, power, 0, 0, mapOf(0L to aggregateOf(0)))
        }
        store.cache.dropRide(ride)
        assertNull(store.cache.summary(ride, 5))
        assertEquals(emptyMap(), store.cache.tiles(ride, power, 0, 0L..0L, 5))
        assertEquals(summaryOf(other), store.cache.summary(other, 5))
        assertEquals(mapOf(0L to aggregateOf(0)), store.cache.tiles(other, power, 0, 0L..0L, 5))
    }
}
