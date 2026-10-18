package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.system.measureTimeMillis
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongRideTest {
    private class HealthPlanningRollback : RuntimeException()

    @Suppress("UNCHECKED_CAST")
    @Test
    fun eightHoursRetainsOriginalsAndBoundsInteractiveReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("long-ride-test.sqlite")
        val store = RideStore(context, "long-ride-test.sqlite")
        val distance = RideDistance(store)
        val monitor = RideMonitor(store, distance)
        try {
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = false, recordGPS = true, sampleHz = 8),
                    RecordingClockReading("2026-01-01T00:00:00Z", 0),
                )
            val seconds =
                if (InstrumentationRegistry.getArguments().getString("powerlogVolume") == "full") 28800 else 300
            val samples = seconds * 8
            val peakIndex = minOf(99999, samples / 2)
            val cursorSeconds = if (seconds == 28800) 10000.0 else seconds / 3.0
            val write = measureTimeMillis {
                for (batch in 0 until samples step 512) store.transaction {
                    for (i in batch until minOf(samples, batch + 512)) {
                        val values =
                            mapOf(
                                "humanPowerW" to if (i == peakIndex) 999.0 else 150.0 + (i % 100),
                                "cadenceRpm" to 80.0,
                                "motorInputPowerW" to 450.0,
                                "batteryVoltageV" to 50.0,
                                "batteryCurrentA" to 9.0,
                                "controllerSpeedMps" to 10.0,
                            )
                        val row =
                            store.insert(
                                id,
                                i / 8.0,
                                iso(1767225600000 + i * 125L),
                                "telemetry",
                                true,
                                0,
                                values,
                                "X6|fixture|5.3",
                                "fixture",
                                original =
                                    OriginalIdentity(
                                        java.util.UUID.nameUUIDFromBytes("long-ride:$i".toByteArray()).toString(),
                                        "controller",
                                        i + 1L,
                                    ),
                            )
                        distance.append(
                            id,
                            row,
                            i / 8.0,
                            values,
                            true,
                            0,
                            "fixture",
                            "X6|fixture|5.3",
                            false,
                        )
                    }
                }
                store.seal(
                    id,
                    RideTiming(
                        seconds.toDouble(),
                        seconds.toDouble(),
                        java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seconds.toLong()).toString(),
                    ),
                )
            }
            android.util.Log.i("PowerLogBenchmark", "samples=$samples importMs=$write stage=import")
            val gpsSamples = seconds
            val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
            for (batch in 0 until gpsSamples step 512) store.transaction {
                for (i in batch until minOf(gpsSamples, batch + 512)) {
                    val values =
                        mapOf(
                            "latitude" to 0.0,
                            "longitude" to i * 0.000001,
                            "horizontalAccuracyM" to 5.0,
                            "speedMps" to 5.0,
                        )
                    val row =
                        store.insert(
                            id,
                            i.toDouble(),
                            start.plusSeconds(i.toLong()).toString(),
                            "location",
                            true,
                            0,
                            values,
                            epoch = "gps",
                            original =
                                OriginalIdentity(
                                    java.util.UUID.nameUUIDFromBytes("long-gps:$i".toByteArray()).toString(),
                                    "phone",
                                    i + 3L,
                                ),
                        )
                    distance.append(id, row, i.toDouble(), values, true, 0, "gps", "", true)
                }
            }
            store.writableDatabase.execSQL("ANALYZE")
            HealthReadback(store, AndroidHealthConnectAccess(context))
                .import(
                    id,
                    androidx.health.connect.client.records.HeartRateRecord(
                        start,
                        java.time.ZoneOffset.UTC,
                        start.plusSeconds(seconds.toLong()),
                        java.time.ZoneOffset.UTC,
                        (0 until gpsSamples).map {
                            androidx.health.connect.client.records.HeartRateRecord.Sample(
                                start.plusSeconds(it.toLong()),
                                100,
                            )
                        },
                        HealthMetadata.received(
                            "00000000-0000-4000-8000-000000000001",
                            "generated:long-heart",
                            1,
                            null,
                            "generated.phone",
                            start,
                            androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                        ),
                    ),
                )
            store.writableDatabase.execSQL("DELETE FROM sqlite_stat1")
            val hasStat4 =
                store.select("SELECT 1 FROM sqlite_master WHERE name='sqlite_stat4'", emptyArray()) {
                    it.moveToFirst()
                }
            if (hasStat4) store.writableDatabase.execSQL("DELETE FROM sqlite_stat4")
            store.writableDatabase.execSQL("ANALYZE sqlite_schema")
            assertEquals((samples + gpsSamples * 2).toLong(), store.count(id))
            val healthPlanning = measureTimeMillis {
                assertThrows(HealthPlanningRollback::class.java) {
                    store.transaction {
                        assertHealthSecondReadsBounded(context, store, id, seconds)
                        throw HealthPlanningRollback()
                    }
                }
            }
            android.util.Log.i(
                "PowerLogHealthPlanning",
                "samples=$samples seconds=32 metrics=3 planningMs=$healthPlanning",
            )

            var points = emptyList<Payload>()
            val request =
                mapOf(
                    "generation" to 1,
                    "expectedRevision" to store.revision(id).toString(),
                    "metrics" to listOf("humanPowerW"),
                    "buckets" to 128,
                    "startSeconds" to 0.0,
                    "endSeconds" to seconds.toDouble(),
                )
            val plotReads = mutableListOf<Pair<String, Array<Any>>>()
            store.queryObserver = { sql, args ->
                if (sql.startsWith("SELECT bucket,")) plotReads.add(sql to args.copyOf())
            }
            val plot =
                try {
                    measureTimeMillis {
                        points =
                            (monitor
                                    .query(
                                        id,
                                        BridgeInputs.monitor(
                                            MonitorOperation.Plot,
                                            mapOf(
                                                "source" to "workout",
                                                "id" to id,
                                                "generation" to 0,
                                                "sinceRevision" to "0",
                                            ) + request,
                                        ),
                                    )["series"]
                                    as Map<String, List<Payload>>)
                                .getValue("humanPowerW")
                    }
                } finally {
                    store.queryObserver = null
                }
            var plotNodes = 0
            plotReads.forEach { (sql, args) ->
                store.select(sql, args) { c -> while (c.moveToNext()) plotNodes++ }
            }
            assertTrue("Full-ride plot decoded $plotNodes cached nodes", plotNodes in 1..128)
            assertTrue(points.size <= 520)
            assertTrue(points.any { it.num("value") == 999.0 })
            var distancePoints = emptyList<Payload>()
            val distancePlot = measureTimeMillis {
                val series = RideAnalytics(store).plot(id, "controllerDistanceMeters", 0.0, seconds.toDouble(), 128)
                distancePoints = series
                assertTrue(series.isNotEmpty())
                assertTrue(series.size <= 520)
                assertEquals(10.0 * (seconds - 0.125), series.last().num("value"), 0.001)
            }
            android.util.Log.i(
                "PowerLogPlotComparison",
                "samples=$samples plotMs=$plot distancePlotMs=$distancePlot plotNodes=$plotNodes",
            )
            val cursor = measureTimeMillis {
                repeat(100) {
                    monitor.query(
                        id,
                        BridgeInputs.monitor(
                            MonitorOperation.Inspect,
                            mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                request +
                                mapOf("seconds" to cursorSeconds + it),
                        ),
                    )
                }
            }
            val catalog = measureTimeMillis {
                repeat(20) { assertEquals(1, store.list(CatalogInput()).size) }
            }
            var healthCount = 0
            val healthPaging = measureTimeMillis { store.healthOriginalRows(id, "heartRate") { healthCount++ } }
            assertEquals(gpsSamples, healthCount)
            fun heartRequest(operation: MonitorOperation, seconds: Double = cursorSeconds) =
                BridgeInputs.monitor(
                    operation,
                    mapOf("source" to "workout", "id" to id, "sinceRevision" to "0") +
                        request +
                        mapOf("metrics" to listOf("heartRateBpm"), "seconds" to seconds),
                )
            fun heartRead(operation: MonitorOperation, seconds: Double = cursorSeconds) {
                val points = monitor.query(id, heartRequest(operation, seconds))["points"] as Map<String, Payload?>
                assertEquals(100.0, points.getValue("heartRateBpm")?.get("value"))
            }
            val heartPreparation = measureTimeMillis { heartRead(MonitorOperation.Latest) }
            var heartWarmPages = 0
            store.queryObserver = { sql, _ ->
                if ("o.kind='health'" in sql && "LIMIT 256" in sql) heartWarmPages++
            }
            val heartLatest = measureTimeMillis { repeat(100) { heartRead(MonitorOperation.Latest) } }
            val heartCursor = measureTimeMillis {
                repeat(100) { heartRead(MonitorOperation.Inspect, cursorSeconds + 0.5 + it) }
            }
            store.queryObserver = null
            assertEquals("Warm HR reads replayed originals", 0, heartWarmPages)
            var summary: Payload = emptyMap()
            var detail = 0L
            var warmDetail = 0L
            var summaryPages = 0
            var originalPages = 0
            var warmPages = 0
            store.summaries.remove(id)
            RideStore(context, "long-ride-test.sqlite").use { readerStore ->
                readerStore.queryObserver = { sql, _ ->
                    if ("FROM observations o INDEXED BY observations_elapsed" in sql) {
                        originalPages++
                        if ("o.kind='health'" !in sql) summaryPages++
                    }
                }
                val reader = RideDetail(readerStore, RideDistance(readerStore))
                var cold: Payload = emptyMap()
                detail = measureTimeMillis { cold = reader.detail(id, "auto") }
                summary = cold["summary"] as Payload
                val beforeWarm = originalPages
                warmDetail = measureTimeMillis { assertEquals(cold, reader.detail(id, "auto")) }
                warmPages = originalPages - beforeWarm
            }
            assertEquals("Warm detail replayed originals", 0, warmPages)
            assertTrue("Cold detail used $summaryPages summary pages", summaryPages < 3000)
            assertTrue("Cold detail used $originalPages original pages", originalPages < 3000)
            assertEquals(100.0, summary["averageHeartRateBpm"])
            assertEquals(gpsSamples.toString(), summary["locationCount"])
            assertEquals(seconds.toDouble(), summary["elapsedSeconds"])
            android.util.Log.i(
                "PowerLogBenchmark",
                "samples=$samples importMs=$write plotMs=$plot cursor100Ms=$cursor catalog20Ms=$catalog healthMembers=$healthCount healthPagingMs=$healthPaging hrPreparationMs=$heartPreparation hrLatest100Ms=$heartLatest hrCursor100Ms=$heartCursor hrWarmPages=$heartWarmPages coldDetailMs=$detail warmDetailMs=$warmDetail summaryPages=$summaryPages originalPages=$originalPages warmPages=$warmPages points=${points.size}",
            )
        } finally {
            store.close()
            context.deleteDatabase("long-ride-test.sqlite")
        }
    }
}
