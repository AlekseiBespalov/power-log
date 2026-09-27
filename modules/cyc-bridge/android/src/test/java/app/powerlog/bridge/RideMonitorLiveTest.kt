package app.powerlog.bridge

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
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
        id = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = true))
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

    @Test
    fun plotNeighborsPreserveOriginalsAndSegmentsAcrossBucketsAndTies() {
        val points = listOf(1.0 to 3.0, 15.0 to 4.0, 16.0 to 5.0, 18.0 to 6.0, 18.0 to 7.0, 64.0 to 8.0, 66.0 to 9.0)
        val originals = points.map { (time, value) -> insert(time, "speedMps", value) }
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
        assertEquals(expected(2 to true, 3 to false, 4 to true, 5 to true), plot(17.0, 18.0))
        assertEquals(expected(4 to true, 5 to true), plot(32.0, 48.0))
        assertEquals(expected(0 to true), plot(0.0, 0.5))
        assertEquals(expected(6 to true), plot(80.0, 90.0))
    }

    private fun observedQueries(block: () -> Unit): List<String> {
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
            block()
        } finally {
            factory.set(db, previous)
        }
        return queries
    }

    @Test
    fun checkpointsRebuiltFromOneMixedBatchMatchOriginalsCommittedInTimeOrder() {
        fun build(batched: Boolean, earlierFix: Boolean): List<Any?> {
            val ride = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = true))
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
                val db = store.readableDatabase
                val originals = queries.filter { it.contains("FROM observations") }
                assertEquals(2, originals.size)
                originals.forEach { sql ->
                    assertEquals("SELECT * FROM observations WHERE ride=? AND id=?", sql)
                    db.rawQuery("EXPLAIN QUERY PLAN $sql", arrayOf(id, gps.toString())).use { c ->
                        val plan = buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString()
                        assertTrue(plan, plan.contains("SEARCH observations USING INTEGER PRIMARY KEY"))
                        assertFalse(plan, plan.contains("SCAN") || plan.contains("TEMP B-TREE"))
                    }
                }
                val checkpoints = queries.filter { it.contains("FROM analytics") }
                assertEquals(6, checkpoints.size)
                checkpoints.forEach { sql ->
                    db.rawQuery("EXPLAIN QUERY PLAN $sql", arrayOf(id, "speedMps", "2", "48")).use { c ->
                        val plan = buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString()
                        assertTrue(plan, plan.contains("SEARCH analytics USING PRIMARY KEY"))
                        assertFalse(plan, plan.contains("SCAN") || plan.contains("TEMP B-TREE"))
                    }
                }
                queries.size
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
                assertTrue(plan, plan.contains("SEARCH observations USING INTEGER PRIMARY KEY"))
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
        assertNull(point(latest("humanPowerW"), "humanPowerW"))
        assertEquals(mapOf("humanPowerW" to null), latest("humanPowerW")["liveAcquiredAt"])
        store.transaction {
            assertThrows(IllegalStateException::class.java) {
                monitor.committedLiveObservation(id, row, 101.0, listOf("humanPowerW"))
            }
        }
        assertNull(point(latest("humanPowerW"), "humanPowerW"))
        monitor.committedLiveObservation(id, row, 101.0, listOf("humanPowerW"))
        assertEquals(row.toString(), point(latest("humanPowerW"), "humanPowerW")?.get("observationId"))
    }

    @Test
    fun restartHasNoLiveEvidenceAndSavedLatestStillReturnsTheOriginal() {
        val row = insert(1.0, "humanPowerW", 120.0)
        monitor.committedLiveObservation(id, row, 101.0, listOf("humanPowerW"))
        val name = store.databaseName
        store.close()
        store = RideStore(RuntimeEnvironment.getApplication(), name)
        monitor = RideMonitor(store, RideDistance(store)) { 100.0 }
        monitor.selectLiveRide(id)
        assertNull(point(latest("humanPowerW"), "humanPowerW"))
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
        id = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = false))
        monitor.selectLiveRide(id)
        monitor.committedLiveObservation(original, row, 102.0, listOf("humanPowerW"))
        assertNull(point(latest("humanPowerW"), "humanPowerW"))
        val next = insert(1.0, "humanPowerW", 150.0)
        monitor.committedLiveObservation(id, next, 102.0, listOf("humanPowerW"))
        assertEquals(next.toString(), point(latest("humanPowerW"), "humanPowerW")?.get("observationId"))
        id = original
        monitor.selectLiveRide(id)
        assertNull(point(latest("humanPowerW"), "humanPowerW"))
    }
}
