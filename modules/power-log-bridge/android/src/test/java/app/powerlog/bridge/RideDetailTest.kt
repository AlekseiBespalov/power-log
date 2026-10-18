package app.powerlog.bridge

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideDetailTest {
    private lateinit var store: RideStore
    private lateinit var distance: RideDistance
    private lateinit var detail: RideDetail
    private lateinit var id: String

    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        store = RideStore(context, "detail-${UUID.randomUUID()}.sqlite")
        distance = RideDistance(store)
        detail = RideDetail(store, distance)
        id =
            store.create(
                RideOptions(indoor = false, saveToHealth = false, recordGPS = false),
                RecordingClockReading(iso(1767225600000L), 0),
            )
    }

    @After
    fun close() {
        store.close()
    }

    private fun power(time: Double, value: Double, active: Boolean = true, segment: Int = 0) =
        store.insert(
            id,
            time,
            iso(1767225600000L + (time * 1000).toLong()),
            "telemetry",
            active,
            segment,
            mapOf("humanPowerW" to value),
            "X6|synthetic|5.3",
            "one",
        )

    @Test
    fun partialDistanceStaysInTheSummaryDistanceWithoutRideNotices() {
        store.transaction {
            for (time in listOf(12.0, 13.0)) {
                val values = mapOf("controllerSpeedMps" to 4.0, "humanPowerW" to 120.0)
                val row = store.insert(id, time, iso(), "telemetry", true, 0, values, "X6|synthetic|5.3", "one")
                distance.append(id, row, time, values, true, 0, "one", "X6|synthetic|5.3", false)
            }
        }
        store.seal(id, RideTiming(13.0, 13.0, iso()))
        val summary = detail.detail(id, "auto")["summary"] as Map<*, *>
        val selected = (summary["distance"] as Map<*, *>)["selected"] as Map<*, *>
        assertEquals("controller", selected["source"])
        assertEquals(true, selected["partial"])
        assertFalse(summary.containsKey("warnings"))
        assertEquals(
            setOf("distance", "distanceMeters"),
            summary.keys.filter { it is String && "distance" in it.lowercase() }.toSet(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun pausedPowerIsExcludedFromStatisticsAndSummaryButRetainedInOriginals() {
        store.transaction {
            power(0.0, 100.0)
            power(1.0, 200.0)
            power(16.0, 1000.0, false, 1)
            power(17.0, 0.0, false, 1)
            power(32.0, 200.0, true, 2)
            power(33.0, 300.0, true, 2)
            store.lifecycle(id, RideTiming(2.0, 0.0, iso()), "pause")
            store.lifecycle(id, RideTiming(32.0, 0.0, iso()), "resume")
        }
        store.seal(id, RideTiming(34.0, 4.0, iso()))
        val stats = store.analytics.stats(id, "humanPowerW", 0.0, 34.0)
        assertEquals(4L, stats["count"])
        assertEquals(200.0, stats.num("sampleMean"), 0.0)
        assertEquals(100.0, (stats["min"] as Payload).num("value"), 0.0)
        assertEquals(300.0, (stats["max"] as Payload).num("value"), 0.0)
        assertEquals(400.0, stats.num("integral"), 0.0)
        assertEquals(2.0, stats.num("coveredSeconds"), 0.0)
        for ((start, end) in listOf(16.0 to 17.0, 16.5 to 17.5)) {
            val paused = store.analytics.stats(id, "humanPowerW", start, end)
            assertEquals(0L, paused["count"])
            assertNull(paused["sampleMean"])
            assertNull(paused["min"])
            assertNull(paused["max"])
            assertEquals(0.0, paused.num("integral"), 0.0)
        }
        val summary = detail.detail(id, "auto")["summary"] as Payload
        assertEquals(300.0, summary.num("maximumRiderPowerW"), 0.0)
        val points = store.analytics.plot(id, "humanPowerW", 0.0, 34.0, 8)
        assertTrue(points.any { it.num("value") == 1000.0 })
        assertEquals(
            listOf(100.0, 200.0, 1000.0, 0.0, 200.0, 300.0),
            store.page(id).map { it.values.getValue("humanPowerW") },
        )
    }

    @Test
    fun completedDetailUsesTheSealedCutoffAndLifecycleKeepsItsUtcAcrossClockJumps() {
        val started = store.events(id).single()
        val pause = RideTiming(2.0, 2.0, "2026-01-01T01:00:00.000Z")
        val resume = RideTiming(3.0, 2.0, "2025-12-31T23:00:00.000Z")
        val lap = RideTiming(4.0, 3.0, "2026-01-01T02:00:00.000Z")
        val cutoff = RideTiming(5.0, 4.0, "2025-12-31T22:00:00.000Z")
        store.transaction {
            power(1.0, 100.0)
            store.transition(id, pause, "pause")
            store.transition(id, resume, "resume")
            store.lifecycle(id, lap, "lap")
            store.update(id, "running", lap)
            power(4.0, 200.0)
        }
        store.seal(id, cutoff, interrupted = true)
        assertEquals(
            listOf(
                started,
                RideEvent(2.0, "pause", pause.timestamp),
                RideEvent(3.0, "resume", resume.timestamp),
                RideEvent(4.0, "lap", lap.timestamp),
                RideEvent(5.0, "stop", cutoff.timestamp, true),
            ),
            store.events(id),
        )
        val summary = detail.detail(id, "auto")["summary"] as Payload
        assertEquals(cutoff.timestamp, summary["endedAt"])
        assertEquals(cutoff.elapsed, summary.num("elapsedSeconds"), 0.0)
        assertEquals(cutoff.timer, summary.num("timerSeconds"), 0.0)
        assertEquals(2.0, summary.num("lapCount"), 0.0)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun detailWithoutAnEndUsesTheRetainedCheckpointUtc() {
        val timing = RideTiming(10.0, 6.0, "2025-12-31T23:00:00.000Z")
        store.lifecycle(id, RideTiming(6.0, 6.0, timing.timestamp), "pause")
        store.update(id, "paused", timing)
        repeat(2) {
            val summary = detail.detail(id, "auto")["summary"] as Payload
            assertEquals(timing.timestamp, summary["endedAt"])
            assertEquals(timing.elapsed, summary.num("elapsedSeconds"), 0.0)
            assertEquals(timing.timer, summary.num("timerSeconds"), 0.0)
        }
    }

    private fun withRide(count: Int = 2, body: (RideStore, String, String) -> Unit) {
        val name = "detail-performance-${UUID.randomUUID()}.sqlite"
        RideStore(context, name).use { store ->
            val id = store.create(RideOptions(true, false, false), RecordingClockReading("2026-01-01T00:00:00Z", 0))
            store.transaction {
                repeat(count) { index ->
                    store.insert(
                        id,
                        index.toDouble(),
                        iso(1767225600000 + index * 1000L),
                        "telemetry",
                        true,
                        0,
                        mapOf("humanPowerW" to 100.0, "cadenceRpm" to 80.0),
                        epoch = "one",
                        original =
                            OriginalIdentity(
                                UUID.nameUUIDFromBytes("$name:$index".toByteArray()).toString(),
                                "controller",
                                index + 1L,
                            ),
                    )
                }
            }
            store.seal(id, RideTiming(count.toDouble(), count.toDouble(), iso(1767225600000 + count * 1000L)))
            body(store, id, name)
        }
    }

    private fun summary(store: RideStore, id: String, source: String = "auto") =
        RideDetail(store, RideDistance(store)).detail(id, source)

    private fun pages(store: RideStore, body: () -> Unit): Int {
        var pages = 0
        store.queryObserver = { sql, _ ->
            if ("FROM observations o INDEXED BY observations_elapsed" in sql) pages++
        }
        try {
            body()
        } finally {
            store.queryObserver = null
        }
        return pages
    }

    @Test
    fun warmSummaryUsesCacheAcrossReadersAndStoreReopen() = withRide { store, id, name ->
        val cold = summary(store, id)
        assertEquals(0, pages(store) { assertEquals(cold, summary(store, id)) })
        RideStore(context, name).use { reopened ->
            assertEquals(0, pages(reopened) { assertEquals(cold, summary(reopened, id)) })
        }
        store.remove(id)
        assertFalse(File(context.getDatabasePath(name).path + ".summaries", id).exists())
    }

    @Test
    fun coldSummaryUsesBoundedLargerPagesWithoutReplayingSmallPages() =
        withRide(600) { store, id, _ ->
            val reads =
                pages(store) {
                    val result = summary(store, id)["summary"] as Map<*, *>
                    assertEquals("600", result["telemetryCount"])
                    assertEquals(59900.0, result["riderWorkJoules"])
                }
            assertTrue("Two summary passes used $reads pages", reads <= 10)
        }

    @Test
    fun detailPreparesHealthOriginsOnlyOnceAndDoesNotScanHeartRateForEnergy() =
        withRide(128) { store, id, _ ->
            val start = Instant.parse("2026-01-01T00:00:00Z")
            HealthReadback(store, FakeHealthConnectAccess())
                .import(
                    id,
                    androidx.health.connect.client.records.HeartRateRecord(
                        start,
                        ZoneOffset.UTC,
                        start.plusSeconds(128),
                        ZoneOffset.UTC,
                        (0L until 128).map {
                            androidx.health.connect.client.records.HeartRateRecord.Sample(start.plusSeconds(it), 100)
                        },
                        HealthMetadata.received(
                            UUID.randomUUID().toString(),
                            "generated:detail-heart",
                            1,
                            null,
                            "generated.phone",
                            start,
                            androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                        ),
                    ),
                )
            var preparationPages = 0
            var unrestrictedHealthPages = 0
            store.queryObserver = { sql, _ ->
                if (sql.startsWith("SELECT o.* FROM observations") && "o.kind='health'" in sql) {
                    if ("IN (" in sql && "$.measure" in sql) preparationPages++
                    if ("$.measure" !in sql) unrestrictedHealthPages++
                }
            }
            try {
                assertEquals(100.0, (summary(store, id)["summary"] as Map<*, *>)["averageHeartRateBpm"])
                assertTrue("Origin preparation used $preparationPages pages", preparationPages <= 4)
                assertEquals("Energy scans replayed unrelated HR originals", 0, unrestrictedHealthPages)
            } finally {
                store.queryObserver = null
            }
        }

    @Test
    fun summaryCacheKeepsFrozenSelectionAndRejectsChangedRevisionOrMetadata() = withRide { store, id, _ ->
        summary(store, id)
        assertEquals(
            0,
            pages(store) {
                assertEquals(
                    "controller",
                    ((summary(store, id, "controller")["summary"] as Map<*, *>)["distance"] as Map<*, *>)["selection"],
                )
            },
        )
        store.insert(
            id,
            2.0,
            "2026-01-01T00:00:02Z",
            "telemetry",
            true,
            0,
            mapOf("humanPowerW" to 300.0),
            epoch = "one",
        )
        assertTrue(
            pages(store) { assertEquals(300.0, (summary(store, id)["summary"] as Map<*, *>)["maximumRiderPowerW"]) } > 0
        )
        val metadata =
            JSONObject(
                store.readableDatabase.rawQuery("SELECT metadata FROM rides WHERE id=?", arrayOf(id)).use {
                    it.moveToFirst()
                    it.getString(0)
                }
            )
        metadata.put("sampleHz", 4)
        store.writableDatabase.execSQL("UPDATE rides SET metadata=? WHERE id=?", arrayOf(metadata.toString(), id))
        assertTrue(
            pages(store) { assertEquals(4.0, (summary(store, id)["metadata"] as Payload).num("sampleHz"), 0.0) } > 0
        )
        assertEquals(0, pages(store) { summary(store, id) })
    }

    @Test
    fun corruptSummaryCacheRecomputesFromOriginals() = withRide { store, id, name ->
        val cold = summary(store, id)
        val folder = File(context.getDatabasePath(name).path + ".summaries", id)
        assertTrue("Summary cache was not written", folder.isDirectory)
        val file = folder.listFiles()!!.single { it.name.endsWith(".json") }
        val corrupted = JSONObject(file.readText())
        corrupted.put("summary", corrupted.getString("summary").replace("100.0", "999.0"))
        file.writeText(corrupted.toString())
        assertTrue(pages(store) { assertEquals(cold, summary(store, id)) } > 0)
        assertEquals(0, pages(store) { assertEquals(cold, summary(store, id)) })
        file.appendText(" ".repeat(1048576))
        assertTrue("Oversized cache was accepted", pages(store) { assertEquals(cold, summary(store, id)) } > 0)
        assertEquals(0, pages(store) { assertEquals(cold, summary(store, id)) })
    }

    @Test
    fun cancelledAndObsoleteReadsDoNotPublishSummaryCache() = withRide { store, id, name ->
        var scans = 0
        store.queryObserver = { sql, _ ->
            if ("FROM observations o INDEXED BY observations_elapsed" in sql && ++scans == 3)
                Thread.currentThread().interrupt()
        }
        try {
            assertThrows(IllegalStateException::class.java) { summary(store, id) }
        } finally {
            Thread.interrupted()
            store.queryObserver = null
        }
        val folder = File(context.getDatabasePath(name).path + ".summaries", id)
        assertTrue(folder.listFiles().isNullOrEmpty())
        val pin = store.pin(id)
        store.insert(
            id,
            2.0,
            "2026-01-01T00:00:02Z",
            "telemetry",
            true,
            0,
            mapOf("humanPowerW" to 300.0),
            epoch = "one",
        )
        store.withPin(pin) { assertEquals(100.0, (summary(store, id)["summary"] as Map<*, *>)["maximumRiderPowerW"]) }
        assertTrue(folder.listFiles().isNullOrEmpty())
        assertEquals(300.0, (summary(store, id)["summary"] as Map<*, *>)["maximumRiderPowerW"])
    }

    @Test
    fun analysisPagesBoundLargeOriginalsAndRetainEveryContinuation() = withRide { store, id, _ ->
        store.transaction {
            repeat(50) { index ->
                store.insert(
                    id,
                    index + 2.0,
                    iso(1767225600000 + (index + 2) * 1000L),
                    "telemetry",
                    true,
                    0,
                    mapOf("humanPowerW" to 100.0),
                    identity = "é".repeat(4096),
                    epoch = "é".repeat(4096),
                )
            }
        }
        store.withReadPin(id) {
            val page =
                store.select(
                    "SELECT * FROM observations WHERE ride=? ORDER BY elapsed_us,producer,producer_sequence,id LIMIT 256",
                    arrayOf(id),
                ) {
                    with(store) { it.observationPage() }
                }
            assertTrue(page.size < 50)
            assertTrue(
                page.sumOf { json(it.evidence).toString().toByteArray(Charsets.UTF_8).size * 2 + 1024 } <= 524288
            )
            val ids = mutableListOf<String>()
            RideAnalysis(store).each(id) { if (it.kind != "lifecycle") ids.add(it.eventId) }
            assertEquals(52, ids.size)
            assertEquals(52, ids.toSet().size)
        }
    }

    @Test
    fun summaryProjectionMatchesTheOriginalDecoderAndAvoidsOpaquePayloadReads() = withRide { store, id, _ ->
        store.insert(
            id,
            0.5,
            "2026-01-01T00:00:00.500Z",
            "location",
            true,
            1,
            mapOf(
                "latitude" to 0.0,
                "longitude" to 0.00001,
                "horizontalAccuracyM" to 5.0,
                "altitudeMeters" to 10.0,
                "verticalAccuracyM" to 2.0,
                "speedMps" to 1.0,
                "speedAccuracyMps" to 0.5,
                "courseDegrees" to 10.0,
            ),
            epoch = "gps",
            evidence = OriginalEvidence(originalElapsedSeconds = 0.5008, clockEpoch = "clock", interruptionIndex = 1),
            detail = mapOf("distanceBarrier" to true, "collectionMapped" to false),
        )
        store.insert(
            id,
            1.5,
            "2026-01-01T00:00:01.500Z",
            "telemetry",
            true,
            1,
            mapOf("cadenceRpm" to 80.0),
            epoch = "two",
        )
        val start = Instant.parse("2026-01-01T00:00:00Z")
        HealthReadback(store, FakeHealthConnectAccess())
            .import(
                id,
                androidx.health.connect.client.records.HeartRateRecord(
                    start,
                    ZoneOffset.UTC,
                    start.plusSeconds(2),
                    ZoneOffset.UTC,
                    listOf(
                        androidx.health.connect.client.records.HeartRateRecord.Sample(start, 100),
                        androidx.health.connect.client.records.HeartRateRecord.Sample(start.plusSeconds(1), 110),
                    ),
                    HealthMetadata.received(
                        UUID.randomUUID().toString(),
                        "generated:projection-heart",
                        1,
                        null,
                        "generated.phone",
                        start,
                        androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                    ),
                ),
            )
        store.withReadPin(id) {
            val full = mutableListOf<app.powerlog.core.analytics.AnalysisItem>()
            val projected = mutableListOf<app.powerlog.core.analytics.AnalysisItem>()
            val analysis = RideAnalysis(store)
            analysis.each(id) { full.add(it.copy(values = it.values.filterKeys { key -> key in summaryMetrics })) }
            analysis.eachSummary(id, projected::add)
            assertEquals(full, projected)
        }
        var opaqueReads = 0
        store.queryObserver = { sql, _ ->
            if (
                sql.startsWith("SELECT o.* FROM observations o INDEXED BY observations_elapsed") &&
                    "o.kind='health'" !in sql
            )
                opaqueReads++
        }
        try {
            summary(store, id)
        } finally {
            store.queryObserver = null
        }
        assertEquals("Summary replayed opaque original payloads", 0, opaqueReads)
    }
}
