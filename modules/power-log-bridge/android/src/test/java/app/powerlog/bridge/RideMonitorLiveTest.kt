package app.powerlog.bridge

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideMonitorLiveTest {
    private lateinit var store: RideStore
    private lateinit var monitor: RideMonitor
    private lateinit var id: String

    @Before
    fun setup() {
        store = RideStore(RuntimeEnvironment.getApplication(), "live-${UUID.randomUUID()}.sqlite")
        id =
            store.create(
                RideOptions(indoor = false, saveToHealth = false, recordGPS = true, distanceSource = "controller"),
                SystemRecordingClock.read(),
            )
        monitor = RideMonitor(store, RideDistance(store)) { 100.0 }
        monitor.selectLiveRide(id)
    }

    @After fun close() = store.close()

    private fun insert(time: Double, metric: String, value: Double): Long =
        store.insert(
            id,
            time,
            iso(),
            if (metric in locationMetrics) "location" else "telemetry",
            true,
            0,
            mapOf(metric to value),
        )

    private fun latest(vararg metrics: String): Payload =
        monitor.query(
            id,
            BridgeInputs.monitor(
                MonitorOperation.Latest,
                mapOf("source" to "workout", "id" to id, "generation" to 0, "metrics" to metrics.toList()),
            ),
        )

    private fun point(result: Payload, metric: String): Map<*, *>? =
        (result["points"] as Map<*, *>)[metric] as? Map<*, *>

    private fun plot(start: Double, end: Double): List<*> {
        val result =
            monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Plot,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "expectedRevision" to store.revision(id).toString(),
                        "metrics" to listOf("speedMps"),
                        "startSeconds" to start,
                        "endSeconds" to end,
                        "buckets" to 100,
                    ),
                ),
            )
        assertEquals("ok", result["status"])
        return (result["series"] as Map<*, *>)["speedMps"] as List<*>
    }

    private fun describe(source: String = "workout"): Payload =
        monitor.query(
            id,
            BridgeInputs.monitor(MonitorOperation.Describe, mapOf("source" to source, "id" to id, "generation" to 7)),
        )

    @Test
    fun emptyLiveAndWorkoutDescriptionsHaveTenSecondDomainsAndAvailableOutcomes() {
        for (source in listOf("live", "workout")) {
            val result = describe(source)
            assertEquals("ok", result["status"])
            assertEquals("$source:$id", result["sourceId"])
            assertEquals(7, result["generation"])
            assertEquals(store.revision(id).toString(), result["revision"])
            assertEquals(store.metadata(id)["startedAt"], result["startedAt"])
            assertEquals(mapOf("start" to 0.0, "end" to 10.0), result["domain"])
            assertEquals("available", result["outcome"])
            assertEquals(emptyList<String>(), result["availableMetrics"])
            assertEquals(emptyMap<String, Any>(), result["metricSources"])
            assertFalse(result.containsKey("warnings"))
            assertTrue(result.containsKey("nowSeconds"))
            assertTrue(result.containsKey("monotonicAt"))
            assertFalse(result.containsKey("liveAcquiredAt"))
        }
        store.seal(id, RideTiming(12.0, 12.0, iso()))
        val saved = describe()
        assertEquals(mapOf("start" to 0.0, "end" to 12.0), saved["domain"])
        assertEquals("available", saved["outcome"])
        assertFalse(saved.containsKey("nowSeconds"))
        assertFalse(saved.containsKey("monotonicAt"))
    }

    @Test
    fun descriptionsReportPartialDistanceAndKeepObservedDomainAndSortedMetrics() {
        val distance = RideDistance(store)
        store.transaction {
            for (time in listOf(12.0, 13.0)) {
                val values = mapOf("controllerSpeedMps" to 4.0, "humanPowerW" to 120.0)
                val row = store.insert(id, time, iso(), "telemetry", true, 0, values, "X6|20250725|5.3", "fixture")
                distance.append(id, row, time, values, true, 0, "fixture", "X6|20250725|5.3", false)
            }
        }
        store.update(id, "running", RideTiming(13.0, 13.0, iso()))
        val result = describe()
        assertEquals("partial", result["outcome"])
        assertEquals(mapOf("start" to 0.0, "end" to 13.0), result["domain"])
        assertEquals(
            listOf("controllerDistanceMeters", "controllerSpeedMps", "distanceMeters", "humanPowerW"),
            result["availableMetrics"],
        )
        assertEquals(distance.info(id, "auto")["selected"], (result["metricSources"] as Map<*, *>)["distanceMeters"])
    }

    @Test
    fun plotNeighborsPreserveOriginalsAndSegmentsAcrossBucketsAndTies() {
        val points = listOf(1.0 to 3.0, 15.0 to 4.0, 16.0 to 5.0, 18.0 to 6.0, 18.0 to 7.0, 64.0 to 8.0, 66.0 to 9.0)
        val originals = points.mapIndexed { index, (time, value) ->
            store.insert(
                id,
                time,
                iso(),
                "location",
                true,
                0,
                mapOf("speedMps" to value),
                original =
                    OriginalIdentity(
                        "00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}",
                        "phone",
                        (index + 2).toLong(),
                    ),
            )
        }
        fun expected(vararg selected: Pair<Int, Boolean>) = selected.map { (index, starts) ->
            val row = store.page(id).first { it.id == originals[index] }
            mapOf(
                "observationId" to row.id.toString(),
                "elapsedSeconds" to row.time,
                "timestamp" to row.timestamp,
                "value" to row.values.getValue("speedMps"),
                "startsSegment" to starts,
            )
        }
        assertEquals(expected(1 to true, 2 to false, 3 to false), plot(16.0, 17.0))
        assertEquals(expected(2 to true, 3 to false, 4 to false, 5 to true), plot(17.0, 18.0))
        assertEquals(expected(3 to true, 5 to true), plot(32.0, 48.0))
        assertEquals(expected(0 to true), plot(0.0, 0.5))
        assertEquals(expected(6 to true), plot(80.0, 90.0))
    }

    private fun observedQueries(block: () -> Unit): List<Pair<String, Array<Any>>> {
        val previous = store.queryObserver
        val queries = mutableListOf<Pair<String, Array<Any>>>()
        store.queryObserver = { sql, args -> queries.add(sql to args) }
        try {
            block()
        } finally {
            store.queryObserver = previous
        }
        return queries
    }

    @Test
    fun completedInspectionFloorsSubmicrosecondTimesAndPreservesTheIdentitySelectedOriginalColdAndWarm() {
        fun original(time: Double, timestamp: String, suffix: String, sequence: Long, value: Double) =
            store.insert(
                id,
                time,
                timestamp,
                "telemetry",
                true,
                0,
                mapOf("humanPowerW" to value),
                original = OriginalIdentity("00000000-0000-4000-8000-$suffix", "controller", sequence),
            )
        original(1.0000008, "2026-01-01T00:00:01.0000008Z", "000000000002", 10, 200.0)
        val selected = original(1.0000002, "2026-01-01T00:00:01.0000002Z", "000000000001", 20, 120.0)
        original(1.0000012, "2026-01-01T00:00:01.0000012Z", "000000000003", 30, 300.0)
        store.seal(id, RideTiming(2.0, 2.0, "2026-01-01T00:00:02Z"))
        val name = store.databaseName
        store.close()
        store = RideStore(RuntimeEnvironment.getApplication(), name)
        monitor = RideMonitor(store, RideDistance(store)) { 100.0 }
        val expected =
            mapOf(
                "observationId" to selected.toString(),
                "elapsedSeconds" to 1.0000002,
                "timestamp" to "2026-01-01T00:00:01.0000002Z",
                "value" to 120.0,
            )
        for (state in listOf("cold", "warm")) {
            val result =
                monitor.query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Inspect,
                        mapOf(
                            "source" to "workout",
                            "id" to id,
                            "generation" to 1,
                            "expectedRevision" to store.revision(id).toString(),
                            "metrics" to listOf("humanPowerW"),
                            "seconds" to 1.0000008,
                        ),
                    ),
                )
            assertEquals(state, "ok", result["status"])
            assertEquals(state, expected, point(result, "humanPowerW"))
            assertEquals(state, mapOf("humanPowerW" to false), result["gaps"])
        }
    }

    @Test
    fun checkpointsRebuiltFromOneMixedBatchMatchOriginalsCommittedInTimeOrder() {
        fun build(batched: Boolean, earlierFix: Boolean): List<Any?> {
            val ride =
                store.create(
                    RideOptions(indoor = false, saveToHealth = false, recordGPS = true, distanceSource = "controller"),
                    SystemRecordingClock.read(),
                )
            fun add(time: Double, kind: String, values: Map<String, Double>) =
                store.insert(ride, time, iso(), kind, true, 0, values)
            if (earlierFix) add(14.0, "location", mapOf("speedMps" to 1.0))
            else add(14.0, "telemetry", mapOf("humanPowerW" to 90.0))
            val rest =
                listOf(
                    Triple(16.0, "telemetry", mapOf("humanPowerW" to 100.0)),
                    Triple(15.0, "location", mapOf("speedMps" to 3.0)),
                    Triple(16.0, "location", mapOf("speedMps" to 5.0)),
                )
            if (batched) store.transaction { rest.forEach { (time, kind, values) -> add(time, kind, values) } }
            else rest.sortedBy { it.first }.forEach { (time, kind, values) -> add(time, kind, values) }
            val stats = store.analytics.stats(ride, "speedMps", 0.0, 48.0)
            val plot = store.analytics.plot(ride, "speedMps", 0.0, 48.0, 1)
            return listOf(
                stats["count"],
                stats["integral"],
                stats["coveredSeconds"],
                plot.map { it.num("elapsedSeconds") to it["startsSegment"] },
            )
        }
        for (earlierFix in listOf(true, false)) assertEquals(build(false, earlierFix), build(true, earlierFix))
    }

    @Test
    fun sparseGpsPlotUsesCheckpointsAndExactLookupsAfterHoursOfTelemetry() {
        val gps = insert(1.0, "speedMps", 3.0)
        val original = store.page(id).single()
        val expected =
            listOf(
                mapOf(
                    "observationId" to gps.toString(),
                    "elapsedSeconds" to 1.0,
                    "timestamp" to original.timestamp,
                    "value" to 3.0,
                    "startsSegment" to true,
                )
            )
        var next = 2
        val work =
            listOf(1000, 14400).map { last ->
                store.transaction {
                    for (second in next..last) insert(second.toDouble(), "humanPowerW", 120.0)
                }
                next = last + 1
                val queries = observedQueries {
                    assertEquals(expected, plot(32.0, 48.0))
                    assertEquals(expected, plot(last - 10.0, last - 5.0))
                }
                val originals = queries.filter { it.first.contains("FROM observations") }
                assertTrue(originals.toString(), originals.isNotEmpty())
                val checkpoints = queries.filter { it.first.contains("FROM analytics") }
                assertEquals(6, checkpoints.size)
                (originals + checkpoints).forEach { (sql, args) ->
                    val plan =
                        store.select("EXPLAIN QUERY PLAN $sql", args) { c ->
                            buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString()
                        }
                    assertTrue(plan, plan.contains("SEARCH"))
                    assertFalse(plan, plan.contains("SCAN") || plan.contains("TEMP B-TREE"))
                }
                (originals + checkpoints).size
            }
        assertEquals(work.first(), work.last())
        assertTrue(work.last() <= 32)
    }

    @Test
    fun committedEvidencePublishesWithoutQueriesAndRejectsInvalidAcquisitions() {
        val row = insert(1.0, "humanPowerW", 120.0)
        val queries = observedQueries {
            monitor.committedLiveObservation(id, row, 101.0, listOf("humanPowerW"))
        }
        assertTrue(queries.toString(), queries.isEmpty())
        val original = latest("humanPowerW")
        val next = insert(2.0, "humanPowerW", 150.0)
        for (acquiredAt in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            monitor.committedLiveObservation(id, next, acquiredAt, listOf("humanPowerW"))
            assertEquals(original["points"], latest("humanPowerW")["points"])
            assertEquals(original["liveAcquiredAt"], latest("humanPowerW")["liveAcquiredAt"])
        }
        monitor.committedLiveObservation(id, next, 102.0, listOf("humanPowerW"))
        assertEquals(next.toString(), point(latest("humanPowerW"), "humanPowerW")?.get("observationId"))
    }

    @Test
    fun sparseGpsLatestUsesOnlyIndexedObservationLookupsAfterHoursOfTelemetry() {
        val gps = insert(1.0, "speedMps", 3.0)
        monitor.committedLiveObservation(id, gps, 101.0, listOf("speedMps"))
        val power = store.transaction {
            var row = 0L
            for (second in 2..14400) row = insert(second.toDouble(), "humanPowerW", 120.0)
            row
        }
        monitor.committedLiveObservation(id, power, 14500.0, listOf("humanPowerW"))
        val db = store.readableDatabase
        val factory = SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply { isAccessible = true }
        val previous = factory.get(db)
        val queries = mutableListOf<String>()
        factory.set(
            db,
            SQLiteDatabase.CursorFactory { _, driver, table, query ->
                queries.add(query.toString().removePrefix("SQLiteQuery: "))
                SQLiteCursor(driver, table, query)
            },
        )
        try {
            val result = latest("speedMps", "humanPowerW", "cadenceRpm")
            assertEquals("ok", result["status"])
            assertEquals(gps.toString(), point(result, "speedMps")?.get("observationId"))
            assertEquals(power.toString(), point(result, "humanPowerW")?.get("observationId"))
            assertEquals(
                mapOf("speedMps" to 101.0, "humanPowerW" to 14500.0, "cadenceRpm" to null),
                result["liveAcquiredAt"],
            )
            assertNull(point(result, "cadenceRpm"))
        } finally {
            factory.set(db, previous)
        }
        assertTrue(queries.size <= 16)
        val observations = queries.filter { it.contains("FROM observations") }
        assertEquals(2, observations.size)
        observations.forEach { sql ->
            db.rawQuery("EXPLAIN QUERY PLAN $sql", arrayOf(id, power.toString())).use { c ->
                val plan = buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString()
                assertTrue(plan, plan.contains("SEARCH o USING INTEGER PRIMARY KEY"))
                assertFalse(plan, plan.contains("SCAN") || plan.contains("TEMP B-TREE"))
            }
        }
    }

    @Test
    fun repeatedAndOlderAcquisitionsNeverReplaceCurrentEvidence() {
        val gps = insert(2.0, "speedMps", 3.0)
        monitor.committedLiveObservation(id, gps, 102.0, listOf("speedMps"))
        val current = latest("speedMps")
        monitor.committedLiveObservation(id, gps, 110.0, listOf("speedMps"))
        val duplicate = insert(2.0, "speedMps", 8.0)
        monitor.committedLiveObservation(id, duplicate, 102.0, listOf("speedMps"))
        val delayed = insert(1.0, "speedMps", 9.0)
        monitor.committedLiveObservation(id, delayed, 101.0, listOf("speedMps"))
        assertEquals(current["points"], latest("speedMps")["points"])
        assertEquals(current["liveAcquiredAt"], latest("speedMps")["liveAcquiredAt"])
        val next = insert(3.0, "speedMps", 4.0)
        monitor.committedLiveObservation(id, next, 103.0, listOf("speedMps"))
        assertEquals(next.toString(), point(latest("speedMps"), "speedMps")?.get("observationId"))
        assertEquals(mapOf("speedMps" to 103.0), latest("speedMps")["liveAcquiredAt"])
    }

    @Test
    fun evidenceRequiresCommitAndNeverComesFromUnregisteredOriginals() {
        val row = insert(1.0, "humanPowerW", 120.0)
        assertEquals(row.toString(), point(latest("humanPowerW"), "humanPowerW")?.get("observationId"))
        assertEquals(mapOf("humanPowerW" to null), latest("humanPowerW")["liveAcquiredAt"])
        store.transaction {
            assertThrows(IllegalStateException::class.java) {
                monitor.committedLiveObservation(id, row, 101.0, listOf("humanPowerW"))
            }
        }
        assertEquals(row.toString(), point(latest("humanPowerW"), "humanPowerW")?.get("observationId"))
        assertEquals(mapOf("humanPowerW" to null), latest("humanPowerW")["liveAcquiredAt"])
        monitor.committedLiveObservation(id, row, 101.0, listOf("humanPowerW"))
        assertEquals(row.toString(), point(latest("humanPowerW"), "humanPowerW")?.get("observationId"))
    }

    @Test
    fun restartHasNoLiveEvidenceAndSavedLatestStillReturnsTheOriginal() {
        val row = insert(1.0, "humanPowerW", 120.0)
        monitor.committedLiveObservation(id, row, 101.0, listOf("humanPowerW"))
        val name = store.databaseName
        store.prepareLocalOwner(id)
        store.close()
        store = RideStore(RuntimeEnvironment.getApplication(), name)
        monitor = RideMonitor(store, RideDistance(store)) { 100.0 }
        monitor.selectLiveRide(id)
        assertEquals(row.toString(), point(latest("humanPowerW"), "humanPowerW")?.get("observationId"))
        assertEquals(mapOf("humanPowerW" to null), latest("humanPowerW")["liveAcquiredAt"])
        store.recoverOrphans()
        val saved = latest("humanPowerW")
        assertEquals(row.toString(), point(saved, "humanPowerW")?.get("observationId"))
        assertFalse(saved.containsKey("liveAcquiredAt"))
        assertFalse(saved.containsKey("monotonicAt"))
    }

    @Test
    fun changingRidesClearsEvidenceAndRejectsPreviousRideDeliveries() {
        val original = id
        val row = insert(1.0, "humanPowerW", 120.0)
        monitor.committedLiveObservation(original, row, 101.0, listOf("humanPowerW"))
        id =
            store.create(
                RideOptions(indoor = false, saveToHealth = false, recordGPS = false),
                SystemRecordingClock.read(),
            )
        monitor.selectLiveRide(id)
        monitor.committedLiveObservation(original, row, 102.0, listOf("humanPowerW"))
        assertNull(point(latest("humanPowerW"), "humanPowerW"))
        assertEquals(mapOf("humanPowerW" to null), latest("humanPowerW")["liveAcquiredAt"])
        val next = insert(1.0, "humanPowerW", 150.0)
        monitor.committedLiveObservation(id, next, 102.0, listOf("humanPowerW"))
        assertEquals(next.toString(), point(latest("humanPowerW"), "humanPowerW")?.get("observationId"))
        id = original
        monitor.selectLiveRide(id)
        assertEquals(row.toString(), point(latest("humanPowerW"), "humanPowerW")?.get("observationId"))
        assertEquals(mapOf("humanPowerW" to null), latest("humanPowerW")["liveAcquiredAt"])
    }

    @Test
    fun eightHourInspectionPerformance() {
        if (System.getenv("POWER_LOG_MONITOR_BENCHMARK") != "1") return
        for (offset in 0 until 230400 step 256) {
            store.transaction {
                for (i in offset until minOf(offset + 256, 230400)) store.insert(
                    id,
                    i / 8.0,
                    iso(),
                    "telemetry",
                    true,
                    0,
                    mapOf("humanPowerW" to 100.0),
                    original = OriginalIdentity(UUID.randomUUID().toString(), "controller", i.toLong() + 1),
                )
            }
        }
        store.seal(id, RideTiming(28800.0, 28800.0, iso()))
        fun run(operation: MonitorOperation, start: Double, end: Double, seconds: Double = 14400.03) =
            monitor.query(
                id,
                BridgeInputs.monitor(
                    operation,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 1,
                        "expectedRevision" to store.revision(id).toString(),
                        "metrics" to listOf("humanPowerW"),
                        "startSeconds" to start,
                        "endSeconds" to end,
                        "seconds" to seconds,
                        "buckets" to 240,
                    ),
                ),
            )
        val first = System.nanoTime()
        run(MonitorOperation.Plot, 0.0, 28800.0)
        val plotMs = (System.nanoTime() - first) / 1e6
        val statistics = System.nanoTime()
        run(MonitorOperation.Stats, 0.0, 28800.0)
        val statisticsMs = (System.nanoTime() - statistics) / 1e6
        val firstMs = (System.nanoTime() - first) / 1e6
        val zoom = System.nanoTime()
        run(MonitorOperation.Plot, 14390.0, 14410.0)
        val zoomMs = (System.nanoTime() - zoom) / 1e6
        val scrub = System.nanoTime()
        repeat(30) { run(MonitorOperation.Inspect, 0.0, 28800.0, 14400.03 + it / 8.0) }
        val report =
            mapOf(
                "count" to 230400,
                "firstOpenMs" to firstMs,
                "plotMs" to plotMs,
                "statisticsMs" to statisticsMs,
                "zoomMs" to zoomMs,
                "scrubMs" to (System.nanoTime() - scrub) / 30e6,
            )
        println("MONITOR_PERFORMANCE " + JSONObject(report).toString())
        System.getenv("POWER_LOG_MONITOR_REPORT")?.let { File(it).writeText(JSONObject(report).toString()) }
        if (System.getenv("POWER_LOG_MONITOR_COST_GATE") == "1") assertTrue("First open $firstMs ms", firstMs <= 364.0)
    }

    @Test
    fun inspectionSelectionFixturesUseStoredOriginalsAndRejectStaleRevision() {
        val root = System.getenv("POWER_LOG_CONTRACT_FIXTURES") ?: "../../../tests/fixtures/contract"
        val cases = JSONObject(File(root, "inspection/selection.json").readText()).getJSONArray("cases")
        for (caseIndex in 0 until cases.length()) {
            val case = cases.getJSONObject(caseIndex)
            val live = case.optBoolean("allowTrailing")
            val ride =
                store.create(
                    RideOptions(indoor = false, saveToHealth = false, recordGPS = true, distanceSource = "controller"),
                    SystemRecordingClock.read(),
                )
            val lifecycle = case.optJSONArray("lifecycle")
            if (lifecycle != null)
                for (j in 0 until lifecycle.length()) {
                    val event = lifecycle.getJSONObject(j)
                    store.lifecycle(
                        ride,
                        RideTiming(event.getString("timeUs").toDouble() / 1e6, 0.0, iso()),
                        event.getString("action"),
                        event.optBoolean("interrupted"),
                        evidence =
                            if (event.has("controllerSequence"))
                                mapOf("controllerSequence" to event.getString("controllerSequence"))
                            else emptyMap(),
                    )
                }
            val metric = case.getString("metric")
            val originals = case.getJSONArray("points")
            val identities = mutableMapOf<String, Long>()
            store.transaction {
                (0 until originals.length())
                    .sortedBy { originals.getJSONObject(it).getString("id") }
                    .forEachIndexed { rank, index ->
                        val original = originals.getJSONObject(index)
                        identities[original.getString("id")] =
                            store.insert(
                                ride,
                                original.getString("timeUs").toDouble() / 1e6,
                                "2026-01-01T00:00:00.123456789Z",
                                original.optString("kind", "telemetry"),
                                true,
                                0,
                                mapOf(metric to original.getDouble("value")),
                                original =
                                    OriginalIdentity(
                                        "${(rank + 1).toString().padStart(8, '0')}-0000-4000-8000-000000000000",
                                        "controller",
                                        original.optString("sequence", (index + 1).toString()).toLong(),
                                    ),
                                evidence =
                                    OriginalEvidence(
                                        interruptionIndex = original.optString("interruptionIndex", "0").toInt()
                                    ),
                            )
                    }
            }
            if (live) {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
                monitor.selectLiveRide(ride)
                monitor.committedLiveObservation(
                    ride,
                    identities.values.last(),
                    android.os.SystemClock.elapsedRealtime() / 1000.0,
                    listOf(metric),
                )
            }
            val fields =
                mutableMapOf<String, Any?>(
                    "source" to if (live) "live" else "workout",
                    "id" to ride,
                    "generation" to 1,
                    "metrics" to listOf(metric),
                    "expectedRevision" to store.revision(ride).toString(),
                    "seconds" to case.getString("timeUs").toDouble() / 1e6,
                )
            if (case.has("anchor"))
                fields["anchor"] =
                    mapOf(
                        "metric" to metric,
                        "observationId" to identities.getValue(case.getString("anchor")).toString(),
                    )
            val result = monitor.query(ride, BridgeInputs.monitor(MonitorOperation.Inspect, fields))
            val selected = point(result, metric)
            val expected =
                if (case.isNull("expected")) null else identities.getValue(case.getString("expected")).toString()
            assertEquals(case.getString("id"), expected, selected?.get("observationId"))
            if (expected != null) assertEquals("2026-01-01T00:00:00.123456789Z", selected?.get("timestamp"))
            fields["expectedRevision"] = "0"
            assertEquals("retry", monitor.query(ride, BridgeInputs.monitor(MonitorOperation.Inspect, fields))["status"])
        }
    }

    @Test
    fun plotFixturesRunThroughCaptureCachesAndPreserveOriginalTimestamps() {
        val root = System.getenv("POWER_LOG_CONTRACT_FIXTURES") ?: "../../../tests/fixtures/contract"
        val cases = JSONObject(File(root, "inspection/plot.json").readText()).getJSONArray("cases")
        for (caseIndex in 0 until cases.length()) {
            val case = cases.getJSONObject(caseIndex)
            val ride =
                store.create(
                    RideOptions(indoor = false, saveToHealth = false, recordGPS = true, distanceSource = "controller"),
                    SystemRecordingClock.read(),
                )
            val metric = case.getString("metric")
            val points = case.getJSONArray("points")
            val identities = mutableMapOf<String, Long>()
            val names = (0 until points.length()).map { points.getJSONObject(it).getString("id") }.sorted()
            (0 until points.length())
                .map { points.getJSONObject(it) }
                .firstOrNull { it.optBoolean("boundary") }
                ?.let { boundary ->
                    val timing = RideTiming(boundary.getString("timeUs").toDouble() / 1e6, 0.0, iso())
                    store.lifecycle(ride, timing, "pause", true, evidence = mapOf("controllerSequence" to "2"))
                    store.lifecycle(ride, timing, "resume")
                }
            store.transaction {
                for (i in 0 until points.length()) {
                    val p = points.getJSONObject(i)
                    val kind = p.optString("kind", if (metric in locationMetrics) "location" else "telemetry")
                    val identity =
                        OriginalIdentity(
                            "${(names.indexOf(p.getString("id")) + 1).toString().padStart(8, '0')}-0000-4000-8000-${ride.takeLast(12)}",
                            if (kind == "telemetry") "controller" else "phone",
                            p.optString("sequence", (i + if (kind == "telemetry") 1 else 2).toString()).toLong(),
                        )
                    identities[p.getString("id")] =
                        store.insert(
                            ride,
                            p.getString("timeUs").toDouble() / 1e6,
                            "2026-01-01T00:00:00.123456789Z",
                            kind,
                            true,
                            0,
                            if (p.isNull("value")) emptyMap() else mapOf(metric to p.getDouble("value")),
                            epoch = p.optString("connection"),
                            original = identity,
                            evidence =
                                OriginalEvidence(
                                    clockEpoch = p.optString("clock").ifEmpty { null },
                                    interruptionIndex = p.optString("interruptionIndex", "0").toInt(),
                                ),
                            detail =
                                if (kind == "health")
                                    mapOf(
                                        "measure" to "heartRate",
                                        "value" to if (p.isNull("value")) null else p.getDouble("value"),
                                        "representation" to "native",
                                        "unit" to "bpm",
                                        "endUs" to p.getString("timeUs"),
                                        "originalEndTimestamp" to "2026-01-01T00:00:00.123456789Z",
                                        "origin" to
                                            mapOf(
                                                "dataOrigin" to "fixture",
                                                "providerObservationId" to identity.eventId,
                                                "originEventId" to identity.eventId,
                                                "producer" to "phone",
                                                "sequence" to identity.sequence.toString(),
                                            ),
                                    )
                                else emptyMap(),
                        )
                }
            }
            store.update(
                ride,
                "running",
                RideTiming(
                    (0 until points.length()).maxOf { points.getJSONObject(it).getString("timeUs").toDouble() / 1e6 },
                    0.0,
                    iso(),
                ),
            )
            val start = case.optString("startUs", "0").toDouble() / 1e6
            val end =
                case
                    .optString(
                        "endUs",
                        (0 until points.length())
                            .maxOf { points.getJSONObject(it).getString("timeUs").toLong() }
                            .toString(),
                    )
                    .toDouble() / 1e6
            val result =
                monitor.query(
                    ride,
                    BridgeInputs.monitor(
                        MonitorOperation.Plot,
                        mapOf(
                            "source" to "workout",
                            "id" to ride,
                            "generation" to 1,
                            "expectedRevision" to store.revision(ride).toString(),
                            "metrics" to listOf(metric),
                            "startSeconds" to start,
                            "endSeconds" to end,
                            "buckets" to 1,
                        ),
                    ),
                )
            assertEquals(case.getString("id"), "ok", result["status"])
            val series = (result["series"] as Map<*, *>)[metric] as List<Map<*, *>>
            val expected = case.getJSONArray("expected")
            assertEquals(
                case.getString("id"),
                (0 until expected.length()).map { identities.getValue(expected.getString(it)).toString() },
                series.map { it["observationId"] },
            )
            val segments = case.getJSONArray("segments")
            assertEquals(
                case.getString("id"),
                (0 until segments.length()).map { it == 0 || segments.getInt(it) != segments.getInt(it - 1) },
                series.map { it["startsSegment"] },
            )
            assertTrue(case.getString("id"), series.all { it["timestamp"] == "2026-01-01T00:00:00.123456789Z" })
        }
    }

    @Test
    fun interruptionRejectsFreshTrailingInspectionBeforeAndAfterStop() {
        val row = insert(1.0, "humanPowerW", 100.0)
        monitor.committedLiveObservation(
            id,
            row,
            100.0,
            listOf("humanPowerW"),
        )
        store.lifecycle(id, RideTiming(1.0, 1.0, iso()), "pause", true, evidence = mapOf("controllerSequence" to "1"))
        for (ended in listOf(false, true)) {
            store.update(id, "paused", RideTiming(2.0, 1.0, iso()))
            if (ended) store.seal(id, RideTiming(2.0, 1.0, iso()))
            val result =
                monitor.query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Inspect,
                        mapOf(
                            "source" to "live",
                            "id" to id,
                            "generation" to 1,
                            "expectedRevision" to store.revision(id).toString(),
                            "metrics" to listOf("humanPowerW"),
                            "seconds" to 2.0,
                        ),
                    ),
                )
            assertEquals("ok", result["status"])
            assertNull(point(result, "humanPowerW"))
        }
    }

    @Test
    fun distanceZoomRetainsBothCrossingNeighboursAcrossCacheBuckets() {
        id = store.create(RideOptions(false, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
        val distance = RideDistance(store)
        store.transaction {
            for (time in listOf(15.875, 16.125)) {
                val values = mapOf("controllerSpeedMps" to 10.0)
                val row = store.insert(id, time, iso(), "telemetry", true, 0, values, "X6|bike|5.3", "epoch")
                distance.append(id, row, time, values, true, 0, "epoch", "X6|bike|5.3", false)
            }
            store.update(id, "running", RideTiming(17.0, 17.0, iso()))
        }
        val result =
            RideMonitor(store, distance)
                .query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Plot,
                        mapOf(
                            "source" to "workout",
                            "id" to id,
                            "generation" to 1,
                            "expectedRevision" to store.revision(id).toString(),
                            "metrics" to listOf("distanceMeters"),
                            "distanceSource" to "controller",
                            "startSeconds" to 16.0,
                            "endSeconds" to 16.0625,
                            "buckets" to 240,
                        ),
                    ),
                )
        val points = (result["series"] as Map<*, *>)["distanceMeters"] as List<Map<*, *>>
        assertEquals(listOf(15.875, 16.125), points.map { it["elapsedSeconds"] })
        assertEquals(listOf(true, false), points.map { it["startsSegment"] })
    }
}
