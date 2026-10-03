package app.powerlog.bridge

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HealthExportTest {
    private fun withRecordedRide(
        gps: Boolean = true,
        samples: Int = 2,
        measurements: (Int) -> Map<String, Double> = {
            mapOf("humanPowerW" to 150.0, "cadenceRpm" to 85.0)
        },
        body: (String, HealthExport, FakeHealthConnectAccess) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-permissions.sqlite")
        try {
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = true, recordGPS = gps),
                    SystemRecordingClock.read(),
                )
            val start = Instant.parse(store.metadata(id).str("startedAt"))
            val distance = RideDistance(store)
            store.transaction {
                repeat(samples) { i ->
                    val values = measurements(i) + mapOf("controllerSpeedMps" to 5.0)
                    val time = start.plusSeconds(i.toLong()).toString()
                    val row = store.insert(id, i.toDouble(), time, "telemetry", true, 0, values, "fixture", "fixture")
                    distance.append(id, row, i.toDouble(), values, true, 0, "fixture", "fixture", false)
                    if (gps)
                        store.insert(
                            id,
                            i.toDouble(),
                            time,
                            "location",
                            true,
                            0,
                            mapOf(
                                "latitude" to 0.0,
                                "longitude" to i * 0.00001,
                                "horizontalAccuracyM" to 5.0,
                                "speedMps" to 3.0,
                            ),
                        )
                }
            }
            store.seal(
                id,
                RideTiming(samples.toDouble(), samples.toDouble(), start.plusSeconds(samples.toLong()).toString()),
            )
            val access = FakeHealthConnectAccess()
            body(id, HealthExport(context, store, access), access)
        } finally {
            store.close()
        }
    }

    @Test
    fun deniedPowerSavesEveryOtherAuthorizedType() = withRecordedRide { id, exporter, access ->
        access.grants -= HealthPermission.getWritePermission(PowerRecord::class)
        val result = exporter.save(id)
        val records = access.batches.flatten()
        assertEquals("saved", result.state)
        assertEquals(2, result.omitted)
        assertEquals(9, result.written)
        assertEquals(
            "Health Connect omitted 2 items. Health Connect access to power was not granted; those measurements were not saved.",
            result.reason,
        )
        assertTrue(records.none { it is PowerRecord })
        assertEquals(2, records.filterIsInstance<CyclingPedalingCadenceRecord>().sumOf { it.samples.size })
        assertEquals(2, records.filterIsInstance<SpeedRecord>().sumOf { it.samples.size })
        assertEquals(5.0, records.filterIsInstance<DistanceRecord>().single().distance.inMeters, 0.0)
        val session = records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(2, (session.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route.size)
        assertTrue(access.batches.last().single() is ExerciseSessionRecord)
    }

    @Test
    fun deniedRouteKeepsTheSessionAndAllAuthorizedQuantities() = withRecordedRide { id, exporter, access ->
        access.grants -= HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
        val result = exporter.save(id)
        val records = access.batches.flatten()
        assertEquals("saved", result.state)
        assertEquals(2, result.omitted)
        assertEquals(9, result.written)
        assertEquals(
            "Health Connect omitted 2 items. Health Connect access to exercise route was not granted; those measurements were not saved.",
            result.reason,
        )
        assertEquals(
            ExerciseRouteResult.NoData(),
            records.filterIsInstance<ExerciseSessionRecord>().single().exerciseRouteResult,
        )
        assertEquals(2, records.filterIsInstance<PowerRecord>().sumOf { it.samples.size })
        assertEquals(2, records.filterIsInstance<CyclingPedalingCadenceRecord>().sumOf { it.samples.size })
        assertEquals(2, records.filterIsInstance<SpeedRecord>().sumOf { it.samples.size })
        assertEquals(1, records.filterIsInstance<DistanceRecord>().size)
    }

    @Test
    fun deniedPowerSpeedAndDistanceDoNotBlockTheOtherTypes() = withRecordedRide { id, exporter, access ->
        access.grants -=
            setOf(
                HealthPermission.getWritePermission(PowerRecord::class),
                HealthPermission.getWritePermission(SpeedRecord::class),
                HealthPermission.getWritePermission(DistanceRecord::class),
            )
        val result = exporter.save(id)
        val records = access.batches.flatten()
        assertEquals("saved", result.state)
        assertEquals(5, result.omitted)
        assertEquals(6, result.written)
        for (type in listOf("power", "speed", "distance")) assertTrue(
            result.reason!!.contains("access to $type was not granted")
        )
        assertTrue(records.none { it is PowerRecord || it is SpeedRecord || it is DistanceRecord })
        assertEquals(2, records.filterIsInstance<CyclingPedalingCadenceRecord>().sumOf { it.samples.size })
        assertEquals(1, records.filterIsInstance<ExerciseSessionRecord>().size)
    }

    @Test
    fun deniedPowerWithoutRecordedPowerHasNoOmission() =
        withRecordedRide(
            gps = false,
            measurements = { mapOf("cadenceRpm" to 85.0) },
        ) { id, exporter, access ->
            access.grants -= HealthPermission.getWritePermission(PowerRecord::class)
            val result = exporter.save(id)
            assertEquals("saved", result.state)
            assertEquals(0, result.omitted)
            assertNull(result.reason)
        }

    @Test
    fun cadenceAndExerciseSessionShareTheEssentialPermission() = withRecordedRide { _, exporter, _ ->
        assertEquals(
            exporter.essentialPermissions(),
            setOf(HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class)),
        )
        assertEquals(exporter.essentialPermissions().toList(), exporter.status()["requiredWrites"])
    }

    @Test
    fun unavailableOrDeniedSessionPreventsAnySave() = withRecordedRide { id, exporter, access ->
        access.grants -= HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val denied = exporter.save(id)
        assertEquals("notSaved", denied.state)
        assertEquals(0, denied.written)
        assertEquals(0, denied.omitted)
        assertTrue(denied.reason!!.contains("Allow Health Connect access to save this ride there."))
        assertTrue(access.batches.isEmpty())
        access.grants += exporter.essentialPermissions()
        access.available = false
        assertEquals("notSaved", exporter.save(id).state)
        assertTrue(access.batches.isEmpty())
    }

    @Test
    fun powerAndCadenceUseTheSameInclusiveLimitsAsIPhone() =
        withRecordedRide(
            gps = false,
            samples = 3,
            measurements = { i ->
                mapOf("humanPowerW" to listOf(0.0, 5000.0, 5000.1)[i], "cadenceRpm" to listOf(0.0, 300.0, 300.1)[i])
            },
        ) { id, exporter, access ->
            val result = exporter.save(id)
            assertEquals("saved", result.state)
            assertEquals(2, result.omitted)
            assertEquals("Health Connect omitted 2 items. Invalid measurement values were omitted.", result.reason)
            val records = access.batches.flatten()
            assertEquals(
                listOf(0.0, 5000.0),
                records.filterIsInstance<PowerRecord>().flatMap { it.samples }.map { it.power.inWatts },
            )
            assertEquals(
                listOf(0.0, 300.0),
                records
                    .filterIsInstance<CyclingPedalingCadenceRecord>()
                    .flatMap { it.samples }
                    .map { it.revolutionsPerMinute },
            )
        }

    @Test
    fun powerRevokedBetweenBatchesKeepsEarlierWritesAndSkipsLaterPower() =
        withRecordedRide(gps = false, samples = 520) { id, exporter, access ->
            access.afterWrite = {
                access.grants -= HealthPermission.getWritePermission(PowerRecord::class)
            }
            val result = exporter.save(id)
            val records = access.batches.flatten()
            assertEquals("saved", result.state)
            assertEquals(8, result.omitted)
            assertEquals(1553, result.written)
            assertEquals(
                "Health Connect omitted 8 items. Health Connect access to power was not granted; those measurements were not saved.",
                result.reason,
            )
            assertEquals(512, records.filterIsInstance<PowerRecord>().sumOf { it.samples.size })
            assertEquals(520, records.filterIsInstance<CyclingPedalingCadenceRecord>().sumOf { it.samples.size })
            assertTrue(access.batches[1].none { it is PowerRecord })
            assertEquals(access.batches.size + 1, access.grantReads)
        }

    @Test
    fun powerRevokedDuringWriteRetriesTheBatchWithoutPower() = withRecordedRide { id, exporter, access ->
        var attempts = 0
        access.beforeWrite = {
            attempts++
            access.grants -= HealthPermission.getWritePermission(PowerRecord::class)
        }
        val result = exporter.save(id)
        assertEquals("saved", result.state)
        assertEquals(2, result.omitted)
        assertEquals(9, result.written)
        assertEquals(access.batches.size + 1, attempts)
        assertEquals(attempts + 1, access.grantReads)
        assertTrue(access.batches.flatten().none { it is PowerRecord })
        assertEquals(1, access.batches.flatten().filterIsInstance<ExerciseSessionRecord>().size)
    }

    @Test
    fun routeRevokedDuringSessionWriteRetriesWithoutRoute() = withRecordedRide { id, exporter, access ->
        var sessionAttempts = 0
        access.beforeWrite = { records ->
            if (records.any { it is ExerciseSessionRecord }) {
                sessionAttempts++
                access.grants -= HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
            }
        }
        val result = exporter.save(id)
        assertEquals("saved", result.state)
        assertEquals(2, sessionAttempts)
        assertEquals(2, result.omitted)
        assertEquals(9, result.written)
        assertEquals(
            ExerciseRouteResult.NoData(),
            access.batches.flatten().filterIsInstance<ExerciseSessionRecord>().single().exerciseRouteResult,
        )
    }

    @Test
    fun securityFailureIsRetriedOnlyOnceAndOtherFailuresAreNotRetried() = withRecordedRide { id, exporter, access ->
        var attempts = 0
        access.beforeWrite = {
            attempts++
            throw SecurityException("Still denied")
        }
        val security = exporter.save(id)
        assertEquals("notSaved", security.state)
        assertEquals(0, security.written)
        assertEquals(2, attempts)
        assertTrue(security.reason!!.contains("Still denied"))
        attempts = 0
        access.beforeWrite = {
            attempts++
            error("Provider failure")
        }
        assertEquals("notSaved", exporter.save(id).state)
        assertEquals(1, attempts)
        assertTrue(access.batches.isEmpty())
    }

    @Test
    fun exportsOnlyActualRiderMeasurementsWithStableIdsAndPauses() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-test.sqlite")
        try {
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = true, recordGPS = false),
                    SystemRecordingClock.read(),
                )
            val start = Instant.parse(store.metadata(id).str("startedAt"))
            val distance = RideDistance(store)
            store.transaction {
                for (i in 0..12) {
                    val values =
                        mapOf(
                            "humanPowerW" to 150.0,
                            "motorInputPowerW" to 2500.0,
                            "cadenceRpm" to 85.0,
                            "controllerSpeedMps" to 5.0,
                        )
                    val active = i !in 4..6
                    val segment = if (i < 4) 0 else if (i < 7) 1 else 2
                    val row =
                        store.insert(
                            id,
                            i.toDouble(),
                            start.plusSeconds(i.toLong()).toString(),
                            "telemetry",
                            active,
                            segment,
                            values,
                            "fixture",
                            "fixture",
                        )
                    distance.append(
                        id,
                        row,
                        i.toDouble(),
                        values,
                        active,
                        segment,
                        "fixture",
                        "fixture",
                        false,
                    )
                }
            }
            store.lifecycle(id, RideTiming(4.0, 4.0, start.plusSeconds(4).toString()), "pause")
            store.lifecycle(id, RideTiming(7.0, 4.0, start.plusSeconds(7).toString()), "resume")
            store.lifecycle(id, RideTiming(10.0, 7.0, start.plusSeconds(10).toString()), "lap")
            store.seal(id, RideTiming(13.0, 10.0, start.plusSeconds(13).toString()))
            val exporter = HealthExport(context, store, FakeHealthConnectAccess())
            suspend fun records(): List<Record> {
                val result = mutableListOf<Record>()
                exporter.writeRide(id) { result.addAll(it) }
                return result
            }
            val first = records()
            val retry = records()
            assertEquals(
                first.map { it.metadata.clientRecordId },
                retry.map { it.metadata.clientRecordId },
            )
            val power = first.filterIsInstance<PowerRecord>().flatMap { it.samples }
            assertEquals(10, power.size)
            assertTrue(power.all { it.power.inWatts == 150.0 })
            assertEquals(
                10,
                first.filterIsInstance<CyclingPedalingCadenceRecord>().sumOf { it.samples.size },
            )
            assertTrue(first.none { it is HeartRateRecord || it is SpeedRecord })
            val session = first.filterIsInstance<ExerciseSessionRecord>().single()
            assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, session.exerciseType)
            assertEquals(1, session.segments.size)
            assertEquals(2, session.laps.size)
            assertEquals("completed", store.metadata(id)["phase"])
        } finally {
            store.close()
        }
    }

    @Test
    fun outsideUtcTimesAreOmittedAndReportedWithoutChangingOriginals() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-timestamps.sqlite")
        try {
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = true, recordGPS = true),
                    SystemRecordingClock.read(),
                )
            val start = Instant.parse(store.metadata(id).str("startedAt"))
            val end = start.plusSeconds(20)
            val timestamps =
                listOf(
                    start.plusSeconds(3).toString(),
                    start.plusSeconds(1).toString(),
                    start.minusSeconds(1).toString(),
                    end.plusSeconds(1).toString(),
                    "invalid",
                    start.plusSeconds(5).toString(),
                )
            store.transaction {
                timestamps.forEachIndexed { index, timestamp ->
                    store.insert(
                        id,
                        index.toDouble(),
                        timestamp,
                        "telemetry",
                        index != 5,
                        0,
                        mapOf("humanPowerW" to 150.0, "cadenceRpm" to 80.0),
                    )
                    store.insert(
                        id,
                        index + 6.0,
                        timestamp,
                        "location",
                        index != 5,
                        0,
                        mapOf(
                            "latitude" to 0.0,
                            "longitude" to 0.0,
                            "horizontalAccuracyM" to 5.0,
                            "speedMps" to 3.0,
                        ),
                    )
                }
            }
            store.seal(id, RideTiming(40.0, 39.0, end.toString()))
            val originals = store.page(id)
            val records = mutableListOf<Record>()
            val outcome = HealthExport(context, store, FakeHealthConnectAccess()).writeRide(id) { records.addAll(it) }
            assertEquals("saved", outcome.state)
            assertEquals(10, outcome.written)
            assertEquals(16, outcome.omitted)
            assertTrue(outcome.reason!!.contains("UTC interval"))
            assertEquals(originals, store.page(id))
            val expected = listOf(start.plusSeconds(1), start.plusSeconds(3))
            val power = records.filterIsInstance<PowerRecord>().single()
            assertEquals(expected, power.samples.map { it.time })
            assertEquals(expected.first(), power.startTime)
            assertEquals(expected.last().plusMillis(1), power.endTime)
            assertEquals(
                expected,
                records.filterIsInstance<CyclingPedalingCadenceRecord>().single().samples.map { it.time },
            )
            assertEquals(expected, records.filterIsInstance<SpeedRecord>().single().samples.map { it.time })
            val session = records.filterIsInstance<ExerciseSessionRecord>().single()
            assertEquals(end, session.endTime)
            val route = (session.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute
            assertEquals(expected, route.route.map { it.time })
        } finally {
            store.close()
        }
    }

    @Test
    fun originalUtcTimesAndStableIdsSurviveMultipleChunks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-chunks.sqlite")
        try {
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = true, recordGPS = false),
                    SystemRecordingClock.read(),
                )
            val start = Instant.parse(store.metadata(id).str("startedAt"))
            val expected = (0 until 520).map { start.plusSeconds(it + 60L) }
            store.transaction {
                expected.forEachIndexed { index, timestamp ->
                    store.insert(
                        id,
                        index.toDouble(),
                        timestamp.toString(),
                        "telemetry",
                        true,
                        0,
                        mapOf("humanPowerW" to 150.0),
                    )
                }
            }
            store.seal(id, RideTiming(1000.0, 1000.0, start.plusSeconds(1000).toString()))
            val exporter = HealthExport(context, store, FakeHealthConnectAccess())
            suspend fun records(): List<Record> {
                val result = mutableListOf<Record>()
                exporter.writeRide(id) { result.addAll(it) }
                return result
            }
            val first = records()
            val power = first.filterIsInstance<PowerRecord>()
            assertEquals(listOf(512, 8), power.map { it.samples.size })
            assertEquals(expected, power.flatMap { it.samples }.map { it.time })
            assertEquals(first.map { it.metadata.clientRecordId }, records().map { it.metadata.clientRecordId })
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidCutoffIsTerminalBeforePermissionChecksAndSurvivesReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "health-terminal.sqlite"
        val store = RideStore(context, name)
        val ids = mutableListOf<String>()
        try {
            for (offset in listOf(0L, -3600L)) {
                val id =
                    store.create(
                        RideOptions(indoor = false, saveToHealth = true, recordGPS = false),
                        SystemRecordingClock.read(),
                    )
                ids.add(id)
                val start = Instant.parse(store.metadata(id).str("startedAt"))
                store.transaction {
                    store.insert(
                        id,
                        1.0,
                        start.plusSeconds(1).toString(),
                        "telemetry",
                        true,
                        0,
                        mapOf("humanPowerW" to 150.0),
                    )
                }
                val cutoff = RideTiming(20.0, 20.0, start.plusSeconds(offset).toString())
                if (offset == 0L) store.seal(id, cutoff)
                else {
                    store.update(id, "running", cutoff)
                    store.recoverOrphans()
                }
                assertEquals("unavailable", store.metadata(id)["healthKitState"])
                val exporter = HealthExport(context, store, FakeHealthConnectAccess())
                val result =
                    exporter.writeRide(id, prepare = { fail("No permission check for an invalid clock interval") }) {
                        fail("No records for an invalid clock interval")
                    }
                assertEquals("unavailable", result.state)
                assertEquals(0, result.written)
                assertEquals(0, result.omitted)
                assertTrue(result.reason!!.contains("clock cutoff"))
                assertEquals(result, exporter.save(id))
                store.healthStatus(id, result)
            }
        } finally {
            store.close()
        }
        val reopened = RideStore(context, name)
        try {
            assertTrue(reopened.pendingHealthJobs().isEmpty())
            for (id in ids) {
                val meta = reopened.metadata(id)
                assertEquals("unavailable", meta["healthKitState"])
                assertEquals("completed", meta["phase"])
                assertEquals(if (meta.flag("interrupted")) "partial" else "complete", meta["finalizationState"])
                assertEquals(meta["sealRevision"], meta["verifiedSealRevision"])
                assertEquals(1L, reopened.count(id))
                assertTrue(meta.str("healthReason").contains("clock cutoff"))
                val distance = RideDistance(reopened)
                for (kind in listOf("fit", "zip")) assertTrue(
                    openedExport(reopened, distance, id, kind)["session"] is String
                )
            }
            val next =
                reopened.create(
                    RideOptions(indoor = false, saveToHealth = false, recordGPS = false),
                    SystemRecordingClock.read(),
                )
            assertEquals("running", reopened.metadata(next)["phase"])
        } finally {
            reopened.close()
        }
    }

    @Test
    fun quantitiesAtTheCutoffKeepTheirUtcAndHavePositiveContainingIntervals() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-cutoff-samples.sqlite")
        try {
            for (atEnd in listOf(false, true)) {
                val id =
                    store.create(
                        RideOptions(indoor = false, saveToHealth = true, recordGPS = true),
                        SystemRecordingClock.read(),
                    )
                val start = Instant.parse(store.metadata(id).str("startedAt"))
                val end = start.plusSeconds(10)
                val time = if (atEnd) end else start
                store.transaction {
                    store.insert(
                        id,
                        1.0,
                        time.toString(),
                        "telemetry",
                        true,
                        0,
                        mapOf("humanPowerW" to 150.0, "cadenceRpm" to 80.0),
                    )
                    store.insert(
                        id,
                        1.0,
                        time.toString(),
                        "location",
                        true,
                        0,
                        mapOf("latitude" to 0.0, "longitude" to 0.0, "horizontalAccuracyM" to 5.0, "speedMps" to 3.0),
                    )
                }
                store.seal(id, RideTiming(20.0, 20.0, end.toString()))
                val records = mutableListOf<Record>()
                val result =
                    HealthExport(context, store, FakeHealthConnectAccess()).writeRide(id) { records.addAll(it) }
                assertEquals("saved", result.state)
                assertEquals(if (atEnd) 1 else 0, result.omitted)
                assertEquals(time, records.filterIsInstance<PowerRecord>().single().samples.single().time)
                assertEquals(
                    time,
                    records.filterIsInstance<CyclingPedalingCadenceRecord>().single().samples.single().time,
                )
                assertEquals(time, records.filterIsInstance<SpeedRecord>().single().samples.single().time)
                records.forEach { record ->
                    val (a, b) =
                        when (record) {
                            is PowerRecord -> record.startTime to record.endTime
                            is CyclingPedalingCadenceRecord -> record.startTime to record.endTime
                            is SpeedRecord -> record.startTime to record.endTime
                            is ExerciseSessionRecord -> record.startTime to record.endTime
                            else -> error("Unexpected Health record")
                        }
                    assertTrue(a >= start && b <= end && a < b)
                }
                val session = records.filterIsInstance<ExerciseSessionRecord>().single()
                if (atEnd) assertEquals(ExerciseRouteResult.NoData(), session.exerciseRouteResult)
                else
                    assertEquals(
                        time,
                        (session.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route.single().time,
                    )
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun backwardJumpInsideWorkoutOmitsOnlyInvalidDistanceInterval() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-distance-clock.sqlite")
        try {
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = true, recordGPS = false),
                    SystemRecordingClock.read(),
                )
            val start = Instant.parse(store.metadata(id).str("startedAt"))
            val distance = RideDistance(store)
            val timestamps = listOf(10L, 11L, 5L, 6L).map { start.plusSeconds(it) }
            store.transaction {
                timestamps.forEachIndexed { i, t ->
                    val values = mapOf("humanPowerW" to 150.0, "controllerSpeedMps" to 5.0)
                    val row =
                        store.insert(id, i.toDouble(), t.toString(), "telemetry", true, 0, values, "fixture", "fixture")
                    distance.append(id, row, i.toDouble(), values, true, 0, "fixture", "fixture", false)
                }
            }
            store.seal(id, RideTiming(4.0, 4.0, start.plusSeconds(20).toString()))
            val records = mutableListOf<Record>()
            val result = HealthExport(context, store, FakeHealthConnectAccess()).writeRide(id) { records.addAll(it) }
            assertEquals("saved", result.state)
            assertEquals(1, result.omitted)
            assertEquals(8, result.written)
            assertTrue(result.reason!!.contains("distance intervals"))
            val distances = records.filterIsInstance<DistanceRecord>()
            assertEquals(
                listOf(timestamps[0] to timestamps[1], timestamps[2] to timestamps[3]),
                distances.map { it.startTime to it.endTime },
            )
            assertEquals(listOf(5.0, 5.0), distances.map { it.distance.inMeters })
            assertEquals(15.0, distance.total(id, "controller").first, 0.0)
            assertEquals(timestamps.sorted(), records.filterIsInstance<PowerRecord>().single().samples.map { it.time })
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidAndOverlappingLifecycleIntervalsDoNotPreventTheRestSaving() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-lifecycle-clock.sqlite")
        try {
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = true, recordGPS = false),
                    SystemRecordingClock.read(),
                )
            val start = Instant.parse(store.metadata(id).str("startedAt"))
            val actions = listOf("pause", "resume", "pause", "resume", "pause", "resume", "pause", "resume", "pause")
            val offsets = listOf(10L, 12L, 11L, 13L, 8L, 7L, -1L, 1L, 18L)
            actions.forEachIndexed { i, action ->
                store.lifecycle(id, RideTiming(i + 1.0, 0.0, start.plusSeconds(offsets[i]).toString()), action)
            }
            listOf(8L, 5L, 15L, 25L, 17L).forEachIndexed { i, t ->
                store.lifecycle(id, RideTiming(i + 10.0, 0.0, start.plusSeconds(t).toString()), "lap")
            }
            store.seal(id, RideTiming(20.0, 10.0, start.plusSeconds(20).toString()))
            val records = mutableListOf<Record>()
            val result = HealthExport(context, store, FakeHealthConnectAccess()).writeRide(id) { records.addAll(it) }
            assertEquals("saved", result.state)
            assertEquals(7, result.omitted)
            assertEquals(5, result.written)
            val session = records.filterIsInstance<ExerciseSessionRecord>().single()
            assertEquals(
                listOf(10L to 12L, 18L to 20L).map { (a, b) -> start.plusSeconds(a) to start.plusSeconds(b) },
                session.segments.map { it.startTime to it.endTime },
            )
            assertEquals(
                listOf(0L to 8L, 17L to 20L).map { (a, b) -> start.plusSeconds(a) to start.plusSeconds(b) },
                session.laps.map { it.startTime to it.endTime },
            )
            store.healthStatus(id, result)
            assertEquals(7, (store.metadata(id)["healthExport"] as Map<*, *>)["omitted"])
            store.close()
            val reopened = RideStore(context, "health-lifecycle-clock.sqlite")
            try {
                assertEquals("saved", reopened.metadata(id)["healthKitState"])
                assertEquals(result.reason, (reopened.metadata(id)["healthExport"] as Map<*, *>)["reason"])
                assertNull(reopened.metadata(id)["healthReason"])
                assertEquals(7, (reopened.metadata(id)["healthExport"] as Map<*, *>)["omitted"])
                assertTrue(reopened.pendingHealthJobs().isEmpty())
            } finally {
                reopened.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidValuesAndDuplicateRouteTimesAreCountedAndValidMeasurementsStillSave() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-invalid-values.sqlite")
        try {
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = true, recordGPS = true),
                    SystemRecordingClock.read(),
                )
            val start = Instant.parse(store.metadata(id).str("startedAt"))
            store.transaction {
                store.insert(
                    id,
                    0.0,
                    start.toString(),
                    "telemetry",
                    true,
                    0,
                    mapOf("humanPowerW" to -1.0, "cadenceRpm" to 10001.0),
                )
                for (i in 0..3) store.insert(
                    id,
                    i.toDouble(),
                    start.plusSeconds(1).toString(),
                    "location",
                    true,
                    0,
                    mapOf(
                        "latitude" to if (i == 2) 91.0 else 0.0,
                        "longitude" to if (i == 3) 181.0 else 0.0,
                        "horizontalAccuracyM" to 5.0,
                    ),
                )
            }
            store.seal(id, RideTiming(10.0, 10.0, start.plusSeconds(10).toString()))
            val records = mutableListOf<Record>()
            val result = HealthExport(context, store, FakeHealthConnectAccess()).writeRide(id) { records.addAll(it) }
            assertEquals("saved", result.state)
            assertEquals(5, result.omitted)
            assertEquals(3, result.written)
            val session = records.filterIsInstance<ExerciseSessionRecord>().single()
            assertEquals(1, (session.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route.size)
        } finally {
            store.close()
        }
    }

    @Test
    fun failedWriteRetainsProgressAndRetryClearsTheHealthReason() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "health-retry.sqlite"
        val store = RideStore(context, name)
        val id =
            store.create(
                RideOptions(indoor = false, saveToHealth = true, recordGPS = false),
                SystemRecordingClock.read(),
            )
        val start = Instant.parse(store.metadata(id).str("startedAt"))
        try {
            store.transaction {
                store.insert(
                    id,
                    1.0,
                    start.plusSeconds(1).toString(),
                    "telemetry",
                    true,
                    0,
                    mapOf("humanPowerW" to 150.0),
                )
            }
            store.seal(id, RideTiming(10.0, 10.0, start.plusSeconds(10).toString()))
            val result =
                HealthExport(context, store, FakeHealthConnectAccess()).writeRide(id) {
                    if (it.any { record -> record is ExerciseSessionRecord }) error("Injected service failure")
                }
            assertEquals("notSaved", result.state)
            assertEquals(1, result.written)
            assertEquals(0, result.omitted)
            store.healthStatus(id, result)
        } finally {
            store.close()
        }
        val reopened = RideStore(context, name)
        try {
            assertEquals("notSaved", reopened.metadata(id)["healthKitState"])
            assertEquals(1, (reopened.metadata(id)["healthExport"] as Map<*, *>)["written"])
            assertTrue(reopened.metadata(id).str("healthReason").contains("Injected service failure"))
            run {
                val result = HealthExport(context, reopened, FakeHealthConnectAccess()).writeRide(id) {}
                assertEquals("saved", result.state)
                assertEquals(3, result.written)
                reopened.healthStatus(id, result)
            }
            assertEquals("saved", reopened.metadata(id)["healthKitState"])
            assertNull(reopened.metadata(id)["healthReason"])
        } finally {
            reopened.close()
        }
    }
}
