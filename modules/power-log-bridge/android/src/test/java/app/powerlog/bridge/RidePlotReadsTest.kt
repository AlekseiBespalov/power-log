package app.powerlog.bridge

import app.powerlog.core.analytics.DistancePolicy
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RidePlotReadsTest {
    @Test
    fun eightHourPowerPlotReadsAtMost128CachedNodes() {
        RideStore(RuntimeEnvironment.getApplication(), "plot-eight-hours-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
            store.transaction {
                for (block in 0 until 28800 step 16) {
                    for ((offset, value) in listOf(0.0 to 150.0, 5.0 to 0.0, 10.0 to 999.0, 15.875 to 150.0)) {
                        store.insert(
                            id,
                            block + offset,
                            iso(),
                            "telemetry",
                            true,
                            0,
                            mapOf("humanPowerW" to value),
                            epoch = "fixture",
                        )
                    }
                }
                store.update(id, "running", RideTiming(28800.0, 28800.0, iso()))
            }
            val queries = mutableListOf<Pair<String, Array<Any>>>()
            store.queryObserver = { sql, args ->
                if (sql.startsWith("SELECT bucket,")) queries.add(sql to args.copyOf())
            }
            val points =
                try {
                    store.analytics.plot(id, "humanPowerW", 0.0, 28800.0, 128)
                } finally {
                    store.queryObserver = null
                }
            assertTrue(points.isNotEmpty())
            val rangeReads = queries.filter { "bucket>=? AND bucket<=?" in it.first }
            assertTrue(rangeReads.isNotEmpty())
            rangeReads.forEach { (sql, args) ->
                if (Regex("FROM analytics(?: |$)").containsMatchIn(sql))
                    assertTrue(
                        "Long plot scanned completed leaf checkpoints: $sql ${args.toList()}",
                        (args[2] as Long) >= 1792L && (args[3] as Long) <= 1800L,
                    )
            }
            var nodes = 0
            queries.forEach { (sql, args) ->
                store.select(sql, args) { cursor -> while (cursor.moveToNext()) nodes++ }
            }
            assertTrue("Eight-hour plot decoded $nodes cached nodes", nodes in 1..128)
        }
    }

    @Test
    fun coarsePlotsMatchLeavesAtClippedEdgesGapsAndCorruptParents() {
        RideStore(RuntimeEnvironment.getApplication(), "plot-blocks-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
            store.transaction {
                for (second in 0..1024) {
                    if (second in 300..320) continue
                    store.insert(
                        id,
                        second.toDouble(),
                        iso(),
                        "telemetry",
                        true,
                        0,
                        mapOf("humanPowerW" to if (second == 501) -1.0 else 150.0 + second % 100),
                        epoch = if (second < 600) "first" else "second",
                    )
                }
                store.update(id, "running", RideTiming(1024.0, 1024.0, iso()))
            }
            val windows = listOf(0.0 to 1024.0, 0.125 to 1023.125, 255.5 to 800.5, 310.0 to 1024.0)
            val expected = windows.map { (start, end) -> store.analytics.plot(id, "humanPowerW", start, end, 2) }
            for (payload in listOf("{", "{}", null)) {
                store.writableDatabase.execSQL(
                    "UPDATE analytics_plot_blocks SET payload=? WHERE ride=?",
                    arrayOf(payload, id),
                )
                assertEquals(
                    expected,
                    windows.map { (start, end) -> store.analytics.plot(id, "humanPowerW", start, end, 2) },
                )
            }
            store.writableDatabase.execSQL("DELETE FROM analytics_plot_blocks WHERE ride=?", arrayOf(id))
            assertEquals(
                expected,
                windows.map { (start, end) -> store.analytics.plot(id, "humanPowerW", start, end, 2) },
            )
        }
    }

    @Test
    fun coarsePlotsRetainPinnedRevisionsAndRollBackWithOriginals() {
        RideStore(RuntimeEnvironment.getApplication(), "plot-pins-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
            store.transaction {
                for (second in 0..512) {
                    store.insert(
                        id,
                        second.toDouble(),
                        iso(),
                        "telemetry",
                        true,
                        0,
                        mapOf("humanPowerW" to 150.0 + second % 100),
                        epoch = "fixture",
                    )
                }
                store.update(id, "running", RideTiming(512.0, 512.0, iso()))
            }
            val expected = store.analytics.plot(id, "humanPowerW", 0.0, 512.0, 2)
            val pin = store.pin(id)
            store.transaction {
                store.insert(id, 400.5, iso(), "telemetry", true, 0, mapOf("humanPowerW" to 999.0), epoch = "fixture")
            }
            store.withPin(pin) {
                assertEquals(expected, store.analytics.plot(id, "humanPowerW", 0.0, 512.0, 2))
                assertEquals(
                    2,
                    store.select("SELECT count(*) FROM analytics_plot_blocks WHERE ride=? AND bucket=1", arrayOf(id)) {
                        it.moveToFirst()
                        it.getInt(0)
                    },
                )
            }
            val latest = store.analytics.plot(id, "humanPowerW", 0.0, 512.0, 2)
            assertTrue(latest.any { it.num("value") == 999.0 })
            store.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER reject_plot_block BEFORE INSERT ON analytics_plot_blocks BEGIN SELECT RAISE(ABORT,'injected plot block failure'); END"
            )
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                store.transaction {
                    store.insert(
                        id,
                        401.5,
                        iso(),
                        "telemetry",
                        true,
                        0,
                        mapOf("humanPowerW" to 888.0),
                        epoch = "fixture",
                    )
                }
            }
            assertEquals(latest, store.analytics.plot(id, "humanPowerW", 0.0, 512.0, 2))
        }
    }

    @Test
    fun boundedIntervalsMatchMaterializedEndpointsAtIntegerBoundariesAndGaps() {
        for (gps in listOf(false, true)) {
            RideStore(RuntimeEnvironment.getApplication(), "plot-window-${UUID.randomUUID()}.sqlite").use { store ->
                val id =
                    store.create(RideOptions(false, saveToHealth = false, recordGPS = gps), SystemRecordingClock.read())
                val distance = RideDistance(store)
                val times = listOf(0.0, 0.125, 0.1250009, 1.0, 2.0, 16.0, 17.0, 18.0, 64.0, 65.0, 1.0, 1.25, 1.75)
                store.transaction {
                    times.forEachIndexed { index, time ->
                        val values =
                            if (gps)
                                mapOf(
                                    "latitude" to 0.0,
                                    "longitude" to index * 0.00001,
                                    "horizontalAccuracyM" to 3.0,
                                    "speedMps" to 1.0,
                                )
                            else mapOf("controllerSpeedMps" to 10.0)
                        val epoch = if (time < 16.0) "first" else "second"
                        val row =
                            store.insert(
                                id,
                                time,
                                iso(),
                                if (gps) "location" else "telemetry",
                                true,
                                0,
                                values,
                                epoch = epoch,
                            )
                        distance.append(id, row, time, values, true, 0, epoch, "X6|fixture|5.3", gps)
                    }
                    store.update(id, "running", RideTiming(65.0, 65.0, iso()))
                }
                val metric = if (gps) "gpsDistanceMeters" else "controllerDistanceMeters"
                store.withReadPin(id) {
                    val inspection = RideDistanceInspection(store)
                    val full = inspection.boundaries(id, metric, Long.MIN_VALUE, Long.MAX_VALUE)
                    assertTrue(full.isNotEmpty())
                    val windows =
                        listOf(
                            -1L,
                            0L,
                            124999L,
                            125000L,
                            125001L,
                            150000L,
                            200000L,
                            250000L,
                            300000L,
                            1000000L,
                            1500000L,
                            16000000L,
                            16500000L,
                            18000000L,
                            20000000L,
                            64000000L,
                            65000000L,
                            66000000L,
                        )
                    for (start in windows) for (end in windows.filter { it >= start }) {
                        val expected = full.filter { it.endUs >= start && it.startUs <= end }
                        assertEquals("$metric [$start,$end]", expected, inspection.boundaries(id, metric, start, end))
                    }
                }
            }
        }
    }

    @Test
    fun powerPlotReadsNoHealthDistanceOriginals() {
        RideStore(RuntimeEnvironment.getApplication(), "plot-health-${UUID.randomUUID()}.sqlite").use { store ->
            val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
            val id =
                store.create(
                    RideOptions(true, saveToHealth = false, recordGPS = false),
                    RecordingClockReading(start.toString(), 0),
                )
            store.transaction {
                store.insert(id, 0.0, start.toString(), "telemetry", true, 0, mapOf("humanPowerW" to 150.0))
                store.seal(id, RideTiming(8.0, 8.0, start.plusSeconds(8).toString()))
            }
            HealthReadback(store, FakeHealthConnectAccess())
                .import(
                    id,
                    androidx.health.connect.client.records.HeartRateRecord(
                        start,
                        java.time.ZoneOffset.UTC,
                        start.plusSeconds(8),
                        java.time.ZoneOffset.UTC,
                        (0L until 8L).map {
                            androidx.health.connect.client.records.HeartRateRecord.Sample(start.plusSeconds(it), 100)
                        },
                        HealthMetadata.received(
                            "00000000-0000-4000-8000-000000000001",
                            "generated:plot-heart",
                            1,
                            null,
                            "generated.phone",
                            start,
                            androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                        ),
                    ),
                )
            val unrelated =
                store.create(
                    RideOptions(true, saveToHealth = false, recordGPS = false),
                    RecordingClockReading(start.toString(), 0),
                )
            store.transaction {
                store.seal(unrelated, RideTiming(2048.0, 2048.0, start.plusSeconds(2048).toString()))
            }
            HealthReadback(store, FakeHealthConnectAccess())
                .import(
                    unrelated,
                    androidx.health.connect.client.records.HeartRateRecord(
                        start,
                        java.time.ZoneOffset.UTC,
                        start.plusSeconds(2048),
                        java.time.ZoneOffset.UTC,
                        (0L until 2048L).map {
                            androidx.health.connect.client.records.HeartRateRecord.Sample(start.plusSeconds(it), 110)
                        },
                        HealthMetadata.received(
                            "00000000-0000-4000-8000-000000000003",
                            "generated:unrelated-heart",
                            1,
                            null,
                            "generated.phone",
                            start,
                            androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                        ),
                    ),
                )
            HealthReadback(store, FakeHealthConnectAccess())
                .import(
                    unrelated,
                    androidx.health.connect.client.records.DistanceRecord(
                        start,
                        java.time.ZoneOffset.UTC,
                        start.plusSeconds(2048),
                        java.time.ZoneOffset.UTC,
                        androidx.health.connect.client.units.Length.meters(25.0),
                        HealthMetadata.received(
                            "00000000-0000-4000-8000-000000000004",
                            "generated:unrelated-distance",
                            1,
                            null,
                            "generated.phone",
                            start,
                            androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                        ),
                    ),
                )
            assertTrue(store.count(unrelated, "health") >= 2049L)
            fun request() =
                BridgeInputs.monitor(
                    MonitorOperation.Plot,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "expectedRevision" to store.revision(id).toString(),
                        "metrics" to listOf("humanPowerW"),
                        "buckets" to 1,
                        "startSeconds" to 0.0,
                        "endSeconds" to 8.0,
                    ),
                )
            val expected = RideMonitor(store, RideDistance(store)).query(id, request())
            HealthReadback(store, FakeHealthConnectAccess())
                .import(
                    id,
                    androidx.health.connect.client.records.DistanceRecord(
                        start,
                        java.time.ZoneOffset.UTC,
                        start.plusSeconds(8),
                        java.time.ZoneOffset.UTC,
                        androidx.health.connect.client.units.Length.meters(0.0),
                        HealthMetadata.received(
                            "00000000-0000-4000-8000-000000000002",
                            "generated:plot-distance",
                            1,
                            null,
                            "generated.phone",
                            start,
                            androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                        ),
                    ),
                )
            val reads = mutableListOf<String>()
            store.queryObserver = { sql, args ->
                if ("cyclingDistance" in sql || "cyclingDistance" in args) reads.add(sql)
            }
            try {
                val after = RideMonitor(store, RideDistance(store)).query(id, request())
                assertEquals(expected["series"], after["series"])
                assertEquals(emptyList<String>(), reads)
            } finally {
                store.queryObserver = null
            }
        }
    }

    @Test
    fun malformedPlotEnvelopeReplaysOriginals() {
        RideStore(RuntimeEnvironment.getApplication(), "plot-envelope-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
            store.transaction {
                for (time in listOf(0.0, 1.0, 16.0, 17.0)) {
                    store.insert(
                        id,
                        time,
                        iso(),
                        "telemetry",
                        true,
                        0,
                        mapOf("humanPowerW" to 150.0 + time),
                        epoch = "fixture",
                    )
                }
                store.update(id, "running", RideTiming(32.0, 32.0, iso()))
            }
            val expected = store.analytics.plot(id, "humanPowerW", 0.0, 32.0, 1)
            store.writableDatabase.execSQL("UPDATE analytics SET payload='{' WHERE ride=?", arrayOf(id))
            assertEquals(expected, store.analytics.plot(id, "humanPowerW", 0.0, 32.0, 1))
        }
    }

    @Test fun cachedDistancePlotOnlyReadsBoundaryWindows() = cachedPlot("controllerDistanceMeters", false)

    @Test fun cachedPowerPlotReadsOneRevision() = cachedPlot("humanPowerW", true)

    private fun cachedPlot(metric: String, checkRevision: Boolean) {
        RideStore(RuntimeEnvironment.getApplication(), "plot-reads-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
            val distance = RideDistance(store)
            store.transaction {
                for (i in 0..1024) {
                    val values = mapOf("controllerSpeedMps" to 10.0, "humanPowerW" to 150.0 + i % 100)
                    val row =
                        store.insert(
                            id,
                            i.toDouble(),
                            iso(),
                            "telemetry",
                            true,
                            0,
                            values,
                            "X6|fixture|5.3",
                            epoch = "fixture",
                        )
                    distance.append(id, row, i.toDouble(), values, true, 0, "fixture", "X6|fixture|5.3", false)
                }
                store.update(id, "running", RideTiming(1024.0, 1024.0, iso()))
            }
            var revisions = 0
            val windows = mutableListOf<Pair<Long, Long>>()
            store.queryObserver = { sql, args ->
                if (sql == "SELECT revision FROM rides WHERE id=?") revisions++
                if (checkRevision && "bucket>=? AND bucket<=?" in sql) {
                    assertTrue("Plot cache read was not bounded: $sql", "ORDER BY bucket LIMIT 128" in sql)
                    assertTrue("Plot loaded unused statistics: $sql", "json_extract(payload,'$.plot')" in sql)
                }
                if ("JOIN observations a" in sql && "JOIN observations b" in sql) {
                    assertTrue("Unbounded interval query: $sql", "d.end_us<=?" in sql)
                    windows.add((args[2] as Long) to (args[3] as Long))
                }
            }
            try {
                run {
                    revisions = 0
                    windows.clear()
                    val points = store.analytics.plot(id, metric, 0.0, 1024.0, 1)
                    assertTrue(points.isNotEmpty())
                    if (checkRevision) assertTrue("Revision read once per cached node: $revisions", revisions <= 8)
                    assertTrue(
                        "Full-domain boundary read: $windows",
                        windows.all { it.second - it.first <= 16000000 + DistancePolicy.maximumControllerEdgeUs },
                    )
                    assertTrue("Repeated interval reads: ${windows.size}", windows.size <= 3)
                }
            } finally {
                store.queryObserver = null
            }
        }
    }
}
