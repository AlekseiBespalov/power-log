package app.powerlog.bridge

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideStoreTest {
    private lateinit var store: RideStore
    private lateinit var distance: RideDistance
    private lateinit var monitor: RideMonitor
    private lateinit var id: String

    @Before
    fun setup() {
        store = RideStore(RuntimeEnvironment.getApplication(), "test-${UUID.randomUUID()}.sqlite")
        distance = RideDistance(store)
        monitor = RideMonitor(store, distance)
        id = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = false))
    }

    @After
    fun close() {
        store.close()
    }

    @Test
    fun revisionBoundRollsBackInsertCheckpointAndSealWithoutLosingOriginals() {
        append(0.0, 100.0)
        store.writableDatabase.execSQL(
            "UPDATE rides SET revision=? WHERE id=?",
            arrayOf(RideStore.MAX_SAFE_REVISION - 1, id),
        )
        append(1.0, 200.0)
        assertEquals(RideStore.MAX_SAFE_REVISION, store.revision(id))
        assertEquals(9_007_199_254_740_991.0, store.metadata(id).num("collectionRevision"), 0.0)
        val originals = store.page(id)
        val events = store.events(id)
        val timing = store.timing(id)
        val metadata = store.metadata(id)
        assertThrows(android.database.sqlite.SQLiteException::class.java) { append(2.0, 300.0) }
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            store.insert(id, 2.0, iso(), "telemetry", true, 0, mapOf("humanPowerW" to 300.0))
        }
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            store.update(id, "paused", RideTiming(2.0, 2.0, iso()))
        }
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            store.transition(id, RideTiming(2.0, 2.0, iso()), "pause")
        }
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            store.seal(id, RideTiming(2.0, 2.0, iso()))
        }
        assertEquals(originals, store.page(id))
        assertEquals(events, store.events(id))
        assertEquals(timing, store.timing(id))
        assertEquals(metadata, store.metadata(id))
        assertEquals(RideStore.MAX_SAFE_REVISION, RideStore.nextRevision(RideStore.MAX_SAFE_REVISION - 1))
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            RideStore.nextRevision(RideStore.MAX_SAFE_REVISION)
        }
        reopen()
        assertEquals(RideStore.MAX_SAFE_REVISION, store.revision(id))
        assertEquals(originals, store.page(id))
    }

    @Test
    fun generatedProvenanceRequiresAnExplicitInternalCreationArgument() {
        val options = BridgeInputs.ride(mapOf("indoor" to true, "saveToHealth" to false, "example" to true))
        assertEquals(false, store.metadata(store.create(options))["example"])
        assertEquals(true, store.metadata(store.create(options, example = true))["example"])
    }

    @Test
    fun catalogDefaultIsOneHundredAndValidatedCursorContinuesWithoutSkippingTies() {
        store.writableDatabase.execSQL("UPDATE rides SET started=? WHERE id=?", arrayOf("2026-01-01T00:00:00.000Z", id))
        repeat(100) {
            val next = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = false))
            store.writableDatabase.execSQL(
                "UPDATE rides SET started=? WHERE id=?",
                arrayOf("2026-01-01T00:00:00.000Z", next),
            )
        }
        val first = store.list(BridgeInputs.catalog(emptyMap()))
        assertEquals(100, first.size)
        val second =
            store.list(
                BridgeInputs.catalog(
                    mapOf("beforeStartedAt" to "2026-01-01T00:00:00.000Z", "beforeID" to first.last()["id"])
                )
            )
        assertEquals(1, second.size)
        assertEquals(101, (first + second).map { it["id"] }.toSet().size)
    }

    @Test
    fun catalogElapsedKeepsRetainedTimingAcrossClockJumpsAndReopen() {
        val start = Instant.parse(store.metadata(id).str("startedAt"))
        assertEquals(0.0, store.metadata(id)["elapsedSeconds"])
        store.update(id, "running", RideTiming(3.0, 3.0, start.plusSeconds(3600).toString()))
        assertEquals(3.0, store.list(CatalogInput()).single()["elapsedSeconds"])
        store.transition(id, RideTiming(5.0, 5.0, start.minusSeconds(3600).toString()), "pause")
        assertEquals(5.0, store.metadata(id)["elapsedSeconds"])
        store.seal(id, RideTiming(10.0, 5.0, start.minusSeconds(7200).toString()))
        reopen()
        repeat(2) {
            assertEquals(10.0, store.metadata(id)["elapsedSeconds"])
            assertEquals(10.0, store.list(CatalogInput()).single()["elapsedSeconds"])
            assertEquals(5.0, store.timing(id).timer, 0.0)
            assertEquals(start.minusSeconds(7200).toString(), store.metadata(id)["endedAt"])
        }
    }

    private fun append(
        t: Double,
        v: Double,
        segment: Int = 0,
        epoch: String = "one",
        active: Boolean = true,
    ): Long = store.transaction {
        val values = mapOf("humanPowerW" to v, "controllerSpeedMps" to 10.0)
        val row =
            store.insert(
                id,
                t,
                iso(1767225600000 + (t * 1000).toLong()),
                "telemetry",
                active,
                segment,
                values,
                "X6|test|5.3",
                epoch,
            )
        distance.append(id, row, t, values, active, segment, epoch, "X6|test|5.3", false)
        row
    }

    private fun request(vararg args: Pair<String, Any?>) =
        mapOf(
            "generation" to 7,
            "expectedRevision" to store.revision(id).toString(),
            "metrics" to listOf("humanPowerW"),
            "startSeconds" to 0.0,
            "endSeconds" to 10.0,
        ) + args

    @Suppress("UNCHECKED_CAST")
    private fun inspect(time: Double, metric: String = "humanPowerW", anchor: Payload? = null) =
        (monitor
            .query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Inspect,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                        request("seconds" to time, "metrics" to listOf(metric)) +
                        (anchor?.let { mapOf("anchor" to it) } ?: emptyMap()),
                ),
            )["points"]
            as Payload)[metric]
            as? Payload

    @Suppress("UNCHECKED_CAST")
    private fun plot(end: Double, buckets: Int = 1, metric: String = "humanPowerW") =
        (monitor
                .query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Plot,
                        mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                            request("endSeconds" to end, "buckets" to buckets, "metrics" to listOf(metric)),
                    ),
                )["series"]
                as Map<String, List<Payload>>)
            .getValue(metric)

    private fun gps(time: Double, longitude: Double, speed: Double = 10.0, accuracy: Double? = 0.1) {
        val values =
            mapOf(
                "latitude" to 0.0,
                "longitude" to longitude,
                "horizontalAccuracyM" to 5.0,
                "speedMps" to speed,
            ) + (accuracy?.let { mapOf("speedAccuracyMps" to it) } ?: emptyMap())
        store.transaction {
            val row = store.insert(id, time, iso(), "location", true, 0, values)
            distance.append(id, row, time, values, true, 0, "gps", "phone", true)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun inspectionAndRangeEndpointsDoNotBorrowAcrossGaps() {
        append(0.0, 100.0)
        append(10.0, 200.0)
        append(16.0, 300.0)
        assertNull(inspect(2.0))
        assertNull(inspect(11.0))
        assertEquals(200.0, inspect(10.0)!!.num("value"), 0.0)
        val result =
            monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Stats,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                        request("startSeconds" to 2.0, "endSeconds" to 8.0, "includeEndpoints" to true),
                ),
            )
        val endpoints = result["endpoints"] as Map<String, Payload>
        assertNull(endpoints.getValue("start")["humanPowerW"])
        assertNull(endpoints.getValue("end")["humanPowerW"])
    }

    @Test
    fun inspectionTiesChooseEarlierAndCompletedRidesHaveNoTail() {
        val first = append(0.0, 100.0)
        append(5.0, 200.0)
        store.seal(id, RideTiming(5.0, 5.0, iso()))
        assertEquals(first.toString(), inspect(2.5)!!["observationId"])
        assertThrows(IllegalArgumentException::class.java) { inspect(-0.1) }
        assertNull(inspect(5.1))
    }

    @Test
    fun liveInspectionHoldsOriginalTimestampOnlyBelowDisplayGap() {
        val first = append(0.0, 100.0)
        for (phase in listOf("running", "paused")) {
            store.update(id, phase, RideTiming(6.0, 0.0, iso()))
            val held = inspect(5.999)!!
            assertEquals(first.toString(), held["observationId"])
            assertEquals(0.0, held.num("elapsedSeconds"), 0.0)
            assertEquals(iso(1767225600000), held["timestamp"])
            assertNull(inspect(6.0))
        }
        assertEquals(1L, store.count(id))
    }

    @Test
    fun observationAnchorSelectsTheRequestedOriginalAtADuplicateTime() {
        val first = append(0.0, 100.0)
        append(0.0, 200.0)
        val point =
            inspect(
                0.0,
                anchor = mapOf("metric" to "humanPowerW", "observationId" to first.toString()),
            )!!
        assertEquals(first.toString(), point["observationId"])
        assertEquals(100.0, point.num("value"), 0.0)
    }

    @Test
    fun locationInspectionUsesItsTenSecondDisplayGap() {
        gps(0.0, 0.0)
        gps(9.0, 0.0001)
        gps(19.0, 0.0002)
        assertEquals(0.0, inspect(4.5, "speedMps")!!.num("elapsedSeconds"), 0.0)
        assertNull(inspect(10.0, "speedMps"))
        assertEquals(9.0, inspect(14.0, "distanceMeters")!!.num("elapsedSeconds"), 0.0)
        assertEquals(1, plot(20.0, metric = "distanceMeters").count { it.flag("startsSegment") })
    }

    @Test
    fun distanceInspectionRequiresSupportedIntervalsWhileGeometryHoldsShortGaps() {
        append(0.0, 100.0)
        append(1.0, 100.0)
        append(2.0, 100.0)
        append(5.0, 100.0)
        append(6.0, 100.0)
        assertEquals(1.0, inspect(1.5, "distanceMeters")!!.num("elapsedSeconds"), 0.0)
        assertEquals(6.0, inspect(6.0, "distanceMeters")!!.num("elapsedSeconds"), 0.0)
        for (time in listOf(0.5, 3.0, 5.5, 6.1)) assertNull(inspect(time, "distanceMeters"))
        val points = plot(7.0, buckets = 16, metric = "distanceMeters")
        assertEquals(listOf(0.0, 1.0, 2.0, 5.0, 6.0), points.map { it.num("elapsedSeconds") })
        assertEquals(listOf(true, false, false, false, false), points.map { it.flag("startsSegment") })
    }

    @Test
    fun reductionKeepsEveryContinuousRunWithinOneBucket() {
        store.transaction {
            listOf(
                    0.0 to 0.0,
                    1.0 to 100.0,
                    8.0 to 50.0,
                    9.0 to 51.0,
                    16.0 to 0.0,
                    17.0 to 100.0,
                )
                .forEach { (time, value) -> append(time, value) }
        }
        val points = plot(18.0)
        assertEquals(
            listOf(0.0, 1.0, 8.0, 9.0, 16.0, 17.0),
            points.map { it.num("elapsedSeconds") },
        )
        assertEquals(
            listOf(0.0, 8.0, 16.0),
            points.filter { it.flag("startsSegment") }.map { it.num("elapsedSeconds") },
        )
    }

    @Test
    fun reconnectAndPauseMarkersDoNotBreakShortDisplaySpansOrExtendIntegration() {
        append(0.0, 100.0)
        append(1.0, 100.0)
        append(4.0, 100.0, epoch = "two")
        append(5.0, 100.0, epoch = "two")
        append(5.5, 100.0, epoch = "three")
        append(6.0, 1000.0, segment = 1, epoch = "two", active = false)
        append(7.0, 100.0, segment = 2, epoch = "two")
        val points = plot(8.0, 8)
        assertEquals(1, points.count { it.flag("startsSegment") })
        assertTrue(points.any { it.num("value") == 1000.0 })
        val stats = store.analytics.stats(id, "humanPowerW", 0.0, 8.0)
        assertEquals(2.0, stats.num("coveredSeconds"), 0.0)
        assertEquals(200.0, stats.num("integral"), 0.0)
    }

    @Test
    fun courseNorthWrapStartsANewRunBeforeReduction() {
        store.transaction {
            listOf(358.0, 359.0, 1.0, 2.0).forEachIndexed { index, course ->
                store.insert(
                    id,
                    16.0 + index,
                    iso(),
                    "location",
                    true,
                    0,
                    mapOf("courseDegrees" to course),
                )
            }
        }
        val points = plot(48.0, metric = "courseDegrees")
        assertEquals(listOf(358.0, 359.0, 1.0, 2.0), points.map { it.num("value") })
        assertEquals(listOf(true, false, true, false), points.map { it.flag("startsSegment") })
    }

    @Test
    fun indoorAutoExcludesGPSButExplicitGPSRemainsAvailable() {
        gps(0.0, 0.0)
        gps(1.0, 0.0001)
        assertEquals("gps:phone", distance.selected(id, "auto"))
        store.update(id, "running", RideTiming(1.0, 1.0, iso()), mapOf("indoor" to true))
        assertNull(distance.selected(id, "auto"))
        assertEquals("gps:phone", distance.selected(id, "gps:phone"))
        append(0.0, 100.0)
        append(1.0, 100.0)
        assertEquals("controller", distance.selected(id, "auto"))
    }

    @Test
    fun stationaryGPSRequiresBothSpeedsBelowThresholdAndNoInvalidSpeedAccuracy() {
        for ((speed, accuracy) in listOf(0.49 to 0.1, 0.5 to 0.1, 0.49 to -1.0, 0.49 to null)) {
            id = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = true))
            gps(0.0, 0.0, speed, accuracy)
            gps(1.0, 0.0001, speed, accuracy)
            val (meters, covered) = distance.total(id, "gps:phone")
            if (speed < 0.5 && (accuracy == null || accuracy >= 0)) assertEquals(0.0, meters, 0.0)
            else assertTrue(meters > 11.0)
            assertEquals(1.0, covered, 0.0)
        }
        id = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = true))
        gps(0.0, 0.0, 0.49)
        gps(1.0, 0.0001, 0.5)
        assertTrue(distance.total(id, "gps:phone").first > 11.0)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun partialDistanceUsesAMillisecondToleranceAndExcludesPausedTime() {
        append(0.0, 100.0)
        append(1.0, 100.0)
        for ((elapsed, partial) in listOf(1.0005 to false, 1.002 to true, 2.0 to true)) {
            store.update(id, "running", RideTiming(elapsed, elapsed, iso()))
            val selected = distance.info(id, "auto")["selected"] as Payload
            assertEquals(partial, selected["partial"])
            val stats =
                (monitor
                        .query(
                            id,
                            BridgeInputs.monitor(
                                MonitorOperation.Stats,
                                mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                    request("metrics" to listOf("distanceMeters"), "endSeconds" to elapsed),
                            ),
                        )["statistics"]
                        as Map<String, Payload>)
                    .getValue("distanceMeters")
            assertEquals(partial, stats["partial"])
        }
        store.lifecycle(id, RideTiming(1.0, 0.0, iso()), "pause")
        store.lifecycle(id, RideTiming(5.0, 0.0, iso()), "resume")
        append(5.0, 100.0, segment = 2)
        append(6.0, 100.0, segment = 2)
        store.seal(id, RideTiming(6.0, 2.0, iso()))
        val stats =
            (monitor
                    .query(
                        id,
                        BridgeInputs.monitor(
                            MonitorOperation.Stats,
                            mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                request("metrics" to listOf("distanceMeters"), "endSeconds" to 6.0),
                        ),
                    )["statistics"]
                    as Map<String, Payload>)
                .getValue("distanceMeters")
        assertEquals(false, stats["partial"])
        assertEquals(2.0, stats.num("coveredSeconds"), 0.0)
        assertEquals(0.5, distance.activeSeconds(id, 0.5, 4.0), 0.0)
        assertEquals(false, (distance.info(id, "auto")["selected"] as Payload)["partial"])
    }

    @Test
    fun originalsRollbackTogetherAndDeletionCascades() {
        assertThrows(IllegalStateException::class.java) {
            store.transaction {
                append(0.0, 10.0)
                append(1.0, 20.0)
                error("fault injection")
            }
        }
        assertEquals(0L, store.count(id))
        assertEquals(0.0, distance.total(id, "controller").first, 0.0)
        distance.reset()
        store.transaction {
            append(0.0, 10.0)
            append(1.0, 20.0)
        }
        store.seal(id, RideTiming(1.0, 1.0, iso()))
        assertEquals(2L, store.count(id))
        assertEquals(10.0, distance.total(id, "controller").first, 0.0)
        store.remove(id)
        assertEquals(0L, store.count(id))
        assertEquals(0.0, distance.total(id, "controller").first, 0.0)
    }

    private fun reopen() {
        val name = store.databaseName
        store.close()
        store = RideStore(RuntimeEnvironment.getApplication(), name)
        distance = RideDistance(store)
        monitor = RideMonitor(store, distance)
    }

    @Test
    fun abruptTerminationRetainsOnlyCommittedTime() {
        for (phase in listOf("running", "paused", "finishing")) {
            for (offset in listOf(-3600L, 3600L)) {
                id = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = false))
                val started = store.metadata(id).str("startedAt")
                val retained = RideTiming(10.0, 6.0, Instant.parse(started).plusSeconds(offset).toString())
                store.transaction {
                    append(8.0, 123.0)
                    store.update(id, phase, retained)
                }
                val original = store.page(id).single()
                reopen()
                assertEquals(retained, store.timing(id))
                store.recoverOrphans()
                val metadata = store.metadata(id)
                assertEquals("completed", metadata["phase"])
                assertEquals(true, metadata["interrupted"])
                assertEquals(metadata["sealRevision"], metadata["verifiedSealRevision"])
                assertEquals(retained, store.timing(id))
                assertEquals(retained.timestamp, metadata["endedAt"])
                assertEquals(RideEvent(10.0, "stop", retained.timestamp), store.events(id).last())
                assertEquals(original, store.page(id).single())
                assertEquals(1.0, metadata.num("eventCount"), 0.0)
                assertEquals(original.timestamp, inspect(8.0)!!["timestamp"])
                assertNull(inspect(8.1))
            }
        }
    }

    @Test
    fun lifecycleUtcAndCommittedStopSurviveReopenWithoutAddingDowntime() {
        val started = store.metadata(id).str("startedAt")
        assertEquals(RideTiming(0.0, 0.0, started), store.timing(id))
        assertEquals(listOf(RideEvent(0.0, "start", started)), store.events(id))
        val pause = RideTiming(3.0, 3.0, "2026-01-01T11:00:00.000Z")
        val resume = RideTiming(5.0, 3.0, "2026-01-01T09:00:00.000Z")
        val lap = RideTiming(7.0, 5.0, "2026-01-01T12:00:00.000Z")
        val stop = RideTiming(9.0, 7.0, "2026-01-01T08:00:00.000Z")
        store.transition(id, pause, "pause")
        store.transition(id, resume, "resume")
        store.transaction {
            store.lifecycle(id, lap, "lap")
            store.update(id, "running", lap)
        }
        store.seal(id, stop)
        val metadata = store.metadata(id)
        reopen()
        store.recoverOrphans()
        store.recoverOrphans()
        assertEquals(metadata, store.metadata(id))
        assertFalse(store.metadata(id).flag("interrupted"))
        assertEquals(stop, store.timing(id))
        assertEquals(
            listOf(
                RideEvent(0.0, "start", started),
                RideEvent(3.0, "pause", pause.timestamp),
                RideEvent(5.0, "resume", resume.timestamp),
                RideEvent(7.0, "lap", lap.timestamp),
                RideEvent(9.0, "stop", stop.timestamp),
            ),
            store.events(id),
        )
        assertEquals(7.0, distance.activeSeconds(id, 0.0, 1000.0), 0.0)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun reconnectEpochsSeparateCachedAndClippedIntegrationWhilePlotsStayContinuous() {
        store.transaction {
            for (second in 0..63) {
                append(second.toDouble(), 100.0, epoch = if (second < 17 || second >= 32) "one" else "two")
            }
            store.update(id, "running", RideTiming(63.0, 63.0, iso()))
        }
        reopen()
        for (buckets in listOf(1, 64)) {
            val points = plot(64.0, buckets)
            assertEquals(1, points.count { it.flag("startsSegment") })
        }
        for ((start, end, covered) in
            listOf(
                Triple(0.0, 64.0, 61.0),
                Triple(16.25, 16.75, 0.0),
                Triple(31.25, 31.75, 0.0),
                Triple(17.25, 17.75, 0.5),
            )) {
            val stats =
                (monitor
                        .query(
                            id,
                            BridgeInputs.monitor(
                                MonitorOperation.Stats,
                                mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                    request(
                                        "startSeconds" to start,
                                        "endSeconds" to end,
                                    ),
                            ),
                        )["statistics"]
                        as Map<String, Payload>)
                    .getValue("humanPowerW")
            assertEquals(covered, stats.num("coveredSeconds"), 0.0)
            assertEquals(covered * 100, stats.num("integral"), 0.0)
        }
        assertEquals(16.0, inspect(16.5)!!.num("elapsedSeconds"), 0.0)
        assertEquals(31.0, inspect(31.5)!!.num("elapsedSeconds"), 0.0)
        store.recoverOrphans()
        assertNull(inspect(63.1))
        for (buckets in listOf(1, 64)) {
            val points = plot(64.0, buckets)
            assertEquals(1, points.count { it.flag("startsSegment") })
            assertTrue(points.all { it.num("elapsedSeconds") <= 63.0 })
        }
        val endpoints =
            monitor
                .query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Stats,
                        mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                            request(
                                "startSeconds" to 63.1,
                                "endSeconds" to 63.2,
                                "includeEndpoints" to true,
                            ),
                    ),
                )["endpoints"]
                as Map<String, Payload>
        assertNull(endpoints.getValue("start")["humanPowerW"])
        assertNull(endpoints.getValue("end")["humanPowerW"])
        assertEquals(64L, store.count(id))
    }

    @Test
    fun unsupportedVersionsLeaveTheCatalogIntact() {
        val context = RuntimeEnvironment.getApplication()
        val name = store.databaseName
        val path = context.getDatabasePath(name).path
        store.close()
        for ((version, message) in
            listOf(
                2 to "Power Log can't open rides saved by an earlier version. Reinstall the app to start over.",
                4 to "Update Power Log to open your rides",
            )) {
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.version = version
            }
            store = RideStore(context, name)
            val error = assertThrows(IllegalStateException::class.java) { store.list(CatalogInput()) }
            assertEquals(message, error.message)
            store.close()
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(version, db.version)
                db.rawQuery("SELECT id FROM rides", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(id, rows.getString(0))
                    assertFalse(rows.moveToNext())
                }
            }
        }
    }

    @Test
    fun pendingHealthJobsSurviveReopeningBeyondTheFirstHistoryPage() {
        val pending = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = false))
        val cutoff = java.time.Instant.parse(store.metadata(pending).str("startedAt")).plusSeconds(10).toString()
        store.seal(pending, RideTiming(10.0, 10.0, cutoff))
        store.writableDatabase.execSQL(
            "UPDATE rides SET started=? WHERE id=?",
            arrayOf("2020-01-01T00:00:00Z", pending),
        )
        repeat(101) {
            val completed = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = false))
            store.seal(completed, RideTiming(10.0, 10.0, iso()))
        }
        assertFalse(store.list(CatalogInput(limit = 100)).any { it.str("id") == pending })
        val name = store.databaseName
        store.close()
        store = RideStore(RuntimeEnvironment.getApplication(), name)
        store.recoverOrphans()
        assertEquals(listOf(pending), store.pendingHealthJobs())
        assertEquals("completed", store.metadata(pending)["phase"])
        assertFalse(store.metadata(pending).flag("interrupted"))
    }

    @Test
    fun interruptedHealthRideBecomesRetryableInsteadOfUnrequested() {
        val ride = store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = false))
        val started = java.time.Instant.parse(store.metadata(ride).str("startedAt"))
        store.update(ride, "running", RideTiming(10.0, 10.0, started.plusSeconds(10).toString()))
        reopen()
        store.recoverOrphans()
        val metadata = store.metadata(ride)
        assertTrue(metadata.flag("interrupted"))
        assertEquals("notSaved", metadata["healthKitState"])
        assertTrue(
            (metadata["warnings"] as List<*>).any { (it as String).contains("interrupted before Health Connect") }
        )
        assertTrue(store.pendingHealthJobs().isEmpty())
    }

    @Test
    fun unavailableExplicitDistanceDoesNotSelectTheControllerFallback() {
        append(0.0, 100.0)
        append(1.0, 200.0)
        val info = distance.info(id, "gps:phone")
        assertNull(info["selected"])
        assertNotNull(distance.info(id, "auto")["selected"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun boundedGeometryAndExactCursorExtrema() {
        store.transaction { for (i in 0..8000) append(i / 8.0, if (i == 4777) 999.0 else 100.0) }
        val result =
            monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Plot,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                        request("endSeconds" to 1000.0, "buckets" to 64),
                ),
            )
        val points = (result["series"] as Map<String, List<Payload>>).getValue("humanPowerW")
        assertTrue(points.size <= 4 * 66)
        assertTrue(points.any { it.num("value") == 999.0 })
        val peak = points.first { it.num("value") == 999.0 }
        val exact =
            monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Inspect,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                        request(
                            "seconds" to peak.num("elapsedSeconds"),
                            "anchor" to mapOf("metric" to "humanPowerW", "observationId" to peak["observationId"]),
                        ),
                ),
            )
        assertEquals(
            peak["observationId"],
            ((exact["points"] as Payload)["humanPowerW"] as Payload)["observationId"],
        )
        val stale =
            monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Plot,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                        request("expectedRevision" to "0"),
                ),
            )
        assertEquals("retry", stale["status"])
    }

    @Test
    fun metadataReadsStayCompleteWhileRecordingAdvances() {
        append(0.0, 100.0)
        val running = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val writer = executor.submit {
            var time = 1.0
            while (running.get()) {
                store.update(id, "running", RideTiming(time, time, iso()))
                started.countDown()
                time += 0.125
                Thread.yield()
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val initialRevision = store.revision(id)
            repeat(50) {
                for (kind in listOf("describe", "changes")) {
                    val result =
                        try {
                            monitor.query(
                                id,
                                BridgeInputs.monitor(
                                    MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                                    mapOf(
                                        "source" to "workout",
                                        "id" to id,
                                        "generation" to 0,
                                        "sinceRevision" to "0",
                                    ) + request("sinceRevision" to "0"),
                                ),
                            )
                        } catch (error: IllegalStateException) {
                            assertTrue(error.message.orEmpty().startsWith("[monitor-contention]"))
                            continue
                        }
                    assertEquals("ok", result["status"])
                    if (kind == "describe") {
                        val domain = result["domain"] as Map<*, *>
                        assertTrue((domain["end"] as Number).toDouble() >= 1.0)
                        assertEquals(0.0, (domain["start"] as Number).toDouble(), 0.0)
                    } else {
                        assertTrue(result["changes"] is List<*>)
                    }
                }
            }
            assertTrue(store.revision(id) > initialRevision)
        } finally {
            running.set(false)
            executor.shutdown()
            writer.get(5, TimeUnit.SECONDS)
        }
        assertEquals(
            "ok",
            monitor
                .query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Describe,
                        mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") + request(),
                    ),
                )["status"],
        )
        assertEquals(
            "ok",
            monitor
                .query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Changes,
                        mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") + request(),
                    ),
                )["status"],
        )
    }

    @Test
    fun exportsAreFinalizedAndFITChecksummed() {
        val exporter = RideExport(RuntimeEnvironment.getApplication(), store, distance, monitor)
        assertThrows(IllegalStateException::class.java) { exporter.fit(id, "auto") }
        append(0.0, 150.0)
        append(1.0, 250.0)
        store.seal(id, RideTiming(1.0, 1.0, iso()))
        val file = File(java.net.URI(exporter.fit(id, "auto")))
        val bytes = file.readBytes()
        assertEquals(".FIT", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(0, FitWriter.crc(bytes))
        val zip = java.util.zip.ZipFile(File(java.net.URI(exporter.archive(id))))
        zip.use {
            assertNotNull(it.getEntry("telemetry.csv"))
            assertNotNull(it.getEntry("metadata.json"))
        }
    }

    @Test
    fun gpsRejectsPoorAccuracyJumpsAndGaps() {
        fun gps(t: Double, lon: Double, accuracy: Double) = store.transaction {
            val values =
                mapOf(
                    "latitude" to 0.0,
                    "longitude" to lon,
                    "horizontalAccuracyM" to accuracy,
                    "speedMps" to 10.0,
                )
            val row = store.insert(id, t, iso(), "location", true, 0, values)
            distance.append(id, row, t, values, true, 0, "gps", "phone", true)
        }
        gps(0.0, 0.0, 5.0)
        gps(1.0, 0.0001, 5.0)
        val valid = distance.total(id, "gps:phone").first
        assertTrue(valid in 11.0..11.2)
        gps(2.0, 1.0, 5.0)
        gps(3.0, 1.0001, 80.0)
        gps(20.0, 1.0002, 5.0)
        assertEquals(valid, distance.total(id, "gps:phone").first, 0.00001)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun checkpointsMatchOriginalStatisticsAcrossClippedEdgesAndGaps() {
        val rows = mutableListOf<Triple<Double, Double, Int>>()
        store.transaction {
            for (i in 0..1600) {
                if (i in 650..750) continue
                val time = i / 8.0
                val value = (i % 301).toDouble()
                val segment = if (i < 800) 0 else 1
                append(time, value, segment)
                rows.add(Triple(time, value, segment))
            }
        }
        for ((start, end) in listOf(0.0 to 200.0, 15.3 to 145.7, 78.4 to 90.0, 32.0 to 64.0)) {
            val stats =
                (monitor
                        .query(
                            id,
                            BridgeInputs.monitor(
                                MonitorOperation.Stats,
                                mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                    request("startSeconds" to start, "endSeconds" to end),
                            ),
                        )["statistics"]
                        as Map<String, Payload>)
                    .getValue("humanPowerW")
            val included = rows.filter { it.first in start..end }
            assertEquals(included.size.toLong(), (stats["count"] as Number).toLong())
            assertEquals(
                included.minOfOrNull { it.second },
                (stats["min"] as? Payload)?.num("value"),
            )
            var integral = 0.0
            var coverage = 0.0
            rows.zipWithNext().forEach { (a, b) ->
                val dt = b.first - a.first
                val lo = maxOf(start, a.first)
                val hi = minOf(end, b.first)
                if (a.third == b.third && dt <= 2.5 && hi > lo) {
                    integral += (a.second + (b.second - a.second) * ((lo + hi) / 2 - a.first) / dt) * (hi - lo)
                    coverage += hi - lo
                }
            }
            assertEquals(integral, stats.num("integral"), 1e-7)
            assertEquals(coverage, stats.num("coveredSeconds"), 1e-7)
        }
        val points =
            (monitor
                    .query(
                        id,
                        BridgeInputs.monitor(
                            MonitorOperation.Plot,
                            mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                request("endSeconds" to 200.0, "buckets" to 8),
                        ),
                    )["series"]
                    as Map<String, List<Payload>>)
                .getValue("humanPowerW")
        assertTrue(points.size <= 4 * 10)
        assertEquals(2, points.count { it.flag("startsSegment") })
        store.seal(id, RideTiming(200.0, 200.0, iso()))
        store.withSavedRide(id) {
            assertThrows(IllegalStateException::class.java) { store.remove(id) }
        }
        store.remove(id)
        assertTrue(store.list(CatalogInput()).isEmpty())
    }
}
