package app.powerlog.bridge

import java.io.File
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
        id = store.create(mapOf("recordGPS" to false))
    }

    @After
    fun close() {
        store.close()
    }

    private fun append(
        t: Double,
        v: Double,
        segment: Int = 0,
        epoch: String = "one",
        active: Boolean = true,
    ): Long {
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
        return row
    }

    private fun request(vararg args: Pair<String, Any?>) =
        mapOf(
            "generation" to 7,
            "expectedRevision" to store.revision(id).toString(),
            "metrics" to listOf("humanPowerW"),
            "startSeconds" to 0.0,
            "endSeconds" to 10.0,
        ) + args

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
        store.seal(id, 1.0, 1.0)
        assertEquals(2L, store.count(id))
        assertEquals(10.0, distance.total(id, "controller").first, 0.0)
        store.remove(id)
        assertEquals(0L, store.count(id))
        assertEquals(0.0, distance.total(id, "controller").first, 0.0)
    }

    @Test
    fun abruptTerminationRetainsOnlyCommittedTime() {
        append(1.0, 123.0)
        store.update(id, "running", 1.0, 1.0)
        store.recoverOrphans()
        val metadata = store.metadata(id)
        assertEquals("completed", metadata["phase"])
        assertEquals(true, metadata["interrupted"])
        assertEquals(metadata["sealRevision"], metadata["verifiedSealRevision"])
        assertEquals(1.0, store.timing(id).second, 0.0)
    }

    @Test
    fun noDistanceAcrossDisconnectOrPauseAndClippedIntegration() {
        append(0.0, 100.0)
        append(1.0, 200.0)
        append(2.0, 300.0, epoch = "two")
        append(3.0, 400.0, epoch = "two")
        append(4.0, 0.0, segment = 1, active = false)
        append(5.0, 500.0, segment = 2)
        append(6.0, 600.0, segment = 2)
        assertEquals(30.0, distance.total(id, "controller").first, 0.0)
        assertEquals(5.0, distance.range(id, "controller", 0.25, 0.75).first, 0.00001)
        val info = distance.info(id, "gps:phone")
        assertNull(info["selected"])
        assertNotNull(distance.info(id, "auto")["selected"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun boundedGeometryAndExactCursorExtrema() {
        store.transaction { for (i in 0..8000) append(i / 8.0, if (i == 4777) 999.0 else 100.0) }
        val result = monitor.query("plot", id, request("endSeconds" to 1000.0, "buckets" to 64))
        val points = (result["series"] as Map<String, List<Payload>>).getValue("humanPowerW")
        assertTrue(points.size <= 4 * 66)
        assertTrue(points.any { it.num("value") == 999.0 })
        val peak = points.first { it.num("value") == 999.0 }
        val exact =
            monitor.query(
                "inspect",
                id,
                request(
                    "seconds" to peak.num("elapsedSeconds"),
                    "anchor" to
                        mapOf("metric" to "humanPowerW", "observationId" to peak["observationId"]),
                ),
            )
        assertEquals(
            peak["observationId"],
            ((exact["points"] as Payload)["humanPowerW"] as Payload)["observationId"],
        )
        val stale = monitor.query("plot", id, request("expectedRevision" to "0"))
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
                store.update(id, "running", time, time)
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
                    val result = try {
                        monitor.query(kind, id, request("sinceRevision" to "0"))
                    } catch (error: IllegalStateException) {
                        assertTrue(error.message.orEmpty().startsWith("[monitor-contention]"))
                        continue
                    }
                    assertEquals("ok", result["status"])
                    if (kind == "describe") {
                        val domain = result["domain"] as Map<*, *>
                        assertTrue((domain["end"] as Number).toDouble() >= 1.0)
                        assertEquals(0, domain["start"])
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
        assertEquals("ok", monitor.query("describe", id, request())["status"])
        assertEquals("ok", monitor.query("changes", id, request())["status"])
    }

    @Test
    fun exportsAreFinalizedAndFITChecksummed() {
        val exporter = RideExport(RuntimeEnvironment.getApplication(), store, distance, monitor)
        assertThrows(IllegalStateException::class.java) { exporter.fit(id, "auto") }
        append(0.0, 150.0)
        append(1.0, 250.0)
        store.seal(id, 1.0, 1.0)
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
        fun gps(t: Double, lon: Double, accuracy: Double) {
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
                        .query("stats", id, request("startSeconds" to start, "endSeconds" to end))[
                            "statistics"]
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
                    integral +=
                        (a.second + (b.second - a.second) * ((lo + hi) / 2 - a.first) / dt) *
                            (hi - lo)
                    coverage += hi - lo
                }
            }
            assertEquals(integral, stats.num("integral"), 1e-7)
            assertEquals(coverage, stats.num("coveredSeconds"), 1e-7)
        }
        val points =
            (monitor.query("plot", id, request("endSeconds" to 200.0, "buckets" to 8))["series"]
                    as Map<String, List<Payload>>)
                .getValue("humanPowerW")
        assertTrue(points.size <= 4 * 10)
        assertTrue(points.count { it.flag("startsSegment") } >= 3)
        store.seal(id, 200.0, 200.0)
        store.withSavedRide(id) {
            assertThrows(IllegalStateException::class.java) { store.remove(id) }
        }
        store.remove(id)
        assertTrue(store.list(emptyMap()).isEmpty())
    }
}
