package app.powerlog.bridge

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
    @Test
    fun exportsOnlyActualRiderMeasurementsWithStableIdsAndPauses() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-test.sqlite")
        try {
            val id = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = false))
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
            val exporter = HealthExport(context, store)
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
            val id = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = true))
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
            val outcome = HealthExport(context, store).writeRide(id) { records.addAll(it) }
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
            val id = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = false))
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
            val exporter = HealthExport(context, store)
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
                val id = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = false))
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
                val exporter = HealthExport(context, store)
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
                assertTrue((meta["warnings"] as List<*>).single().toString().contains("clock cutoff"))
                val distance = RideDistance(reopened)
                val exporter = RideExport(context, reopened, distance, RideMonitor(reopened, distance))
                assertTrue(java.io.File(java.net.URI(exporter.fit(id, "auto"))).length() > 0)
                assertTrue(java.io.File(java.net.URI(exporter.archive(id))).length() > 0)
            }
            val next = reopened.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = false))
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
                val id = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = true))
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
                val result = HealthExport(context, store).writeRide(id) { records.addAll(it) }
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
            val id = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = false))
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
            val result = HealthExport(context, store).writeRide(id) { records.addAll(it) }
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
            val id = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = false))
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
            val result = HealthExport(context, store).writeRide(id) { records.addAll(it) }
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
            assertEquals(listOf(result.reason), store.metadata(id)["warnings"])
            assertEquals(7, (store.metadata(id)["healthExport"] as Map<*, *>)["omitted"])
            store.close()
            val reopened = RideStore(context, "health-lifecycle-clock.sqlite")
            try {
                assertEquals("saved", reopened.metadata(id)["healthKitState"])
                assertEquals(listOf(result.reason), reopened.metadata(id)["warnings"])
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
            val id = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = true))
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
            val result = HealthExport(context, store).writeRide(id) { records.addAll(it) }
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
    fun failedWriteRetainsProgressAndRetryReplacesOnlyTheHealthWarning() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "health-retry.sqlite"
        val store = RideStore(context, name)
        val id = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = false))
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
                store.update(
                    id,
                    "running",
                    RideTiming(1.0, 1.0, start.plusSeconds(1).toString()),
                    mapOf("warnings" to listOf("Existing capture notice.")),
                )
            }
            store.seal(id, RideTiming(10.0, 10.0, start.plusSeconds(10).toString()))
            val result =
                HealthExport(context, store).writeRide(id) {
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
            assertTrue(
                (reopened.metadata(id)["warnings"] as List<*>).last().toString().contains("Injected service failure")
            )
            run {
                val result = HealthExport(context, reopened).writeRide(id) {}
                assertEquals("saved", result.state)
                assertEquals(3, result.written)
                reopened.healthStatus(id, result)
            }
            assertEquals(listOf("Existing capture notice."), reopened.metadata(id)["warnings"])
        } finally {
            reopened.close()
        }
    }
}
