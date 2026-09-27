package app.powerlog.bridge

import java.io.File
import java.net.URI
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideExportTest {
    private lateinit var store: RideStore
    private lateinit var exporter: RideExport
    private lateinit var id: String

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        store = RideStore(context, "export-${UUID.randomUUID()}.sqlite")
        val distance = RideDistance(store)
        exporter = RideExport(context, store, distance, RideMonitor(store, distance))
        id = store.create(RideOptions(indoor = false, saveToHealth = false, recordGPS = false))
        store.update(id, "running", RideTiming(0.0, 0.0, iso()), mapOf("startedAt" to iso(1767225600000L)))
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

    private fun messages(): Map<Int, List<Map<Int, Long>>> {
        val bytes = File(URI(exporter.fit(id, "auto"))).readBytes()
        assertEquals(0, FitWriter.crc(bytes))
        var offset = bytes[0].toInt() and 255
        fun read(size: Int): Long {
            var value = 0L
            repeat(size) { shift ->
                value = value or ((bytes[offset++].toLong() and 255) shl (shift * 8))
            }
            return value
        }
        var global = -1
        var fields = emptyList<Triple<Int, Int, Int>>()
        val result = mutableMapOf<Int, MutableList<Map<Int, Long>>>()
        while (offset < bytes.size - 2) {
            val header = read(1).toInt()
            if (header == 0x40) {
                assertEquals(0L, read(1))
                assertEquals(0L, read(1))
                global = read(2).toInt()
                fields =
                    List(read(1).toInt()) {
                        Triple(read(1).toInt(), read(1).toInt(), read(1).toInt())
                    }
            } else {
                assertEquals(0, header)
                val row = fields.associate { (number, size, type) ->
                    val value = read(size)
                    number to if (type == 0x85) value.toInt().toLong() else value
                }
                result.getOrPut(global) { mutableListOf() }.add(row)
            }
        }
        assertEquals(bytes.size - 2, offset)
        return result
    }

    @Test
    fun clockWarningsInspectOriginalsAndLifecycleEvenWhenTheCutoffClockReturns() {
        val base = 1767225600000L
        store.writableDatabase.execSQL("UPDATE lifecycle SET timestamp=? WHERE ride=?", arrayOf(iso(base), id))
        store.transaction {
            power(0.0, 100.0)
            power(1.0, 200.0)
            power(2.0, 300.0)
        }
        store.seal(id, RideTiming(3.0, 3.0, iso(base + 3000)))
        fun warnings() = (exporter.detail(id, "auto")["summary"] as Map<*, *>)["warnings"] as List<*>
        assertTrue(warnings().isEmpty())
        val expectedFit = messages()
        for (offset in listOf(-3600000L, 3600000L)) {
            store.writableDatabase.execSQL(
                "UPDATE observations SET timestamp=? WHERE ride=? AND time=1",
                arrayOf(iso(base + 1000 + offset), id),
            )
            assertTrue(warnings().single().toString().contains("clock changed"))
            assertEquals(expectedFit, messages())
            assertEquals(iso(base + 1000 + offset), store.page(id)[1].timestamp)
        }
        store.writableDatabase.execSQL(
            "UPDATE observations SET timestamp=? WHERE ride=? AND time=1",
            arrayOf(iso(base + 1000), id),
        )
        assertTrue(warnings().isEmpty())
        store.lifecycle(id, RideTiming(2.0, 2.0, iso(base - 3600000)), "lap")
        assertTrue(warnings().single().toString().contains("clock changed"))
        store.writableDatabase.execSQL("DELETE FROM lifecycle WHERE ride=? AND action='lap'", arrayOf(id))
        store.update(id, "completed", RideTiming(3.0, 3.0, iso(base - 3600000)))
        assertTrue(warnings().single().toString().contains("clock changed"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun pausedPowerIsExcludedFromStatisticsSummaryAndFITButRetainedInOriginals() {
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
        val summary = exporter.detail(id, "auto")["summary"] as Payload
        assertEquals(300.0, summary.num("maximumRiderPowerW"), 0.0)
        val fit = messages()
        assertEquals(300L, fit.getValue(18).single().getValue(21))
        val records = fit.getValue(20)
        assertEquals(listOf(100L, 200L, 200L, 300L), records.map { it.getValue(7) })
        val origin = records.first().getValue(253)
        assertEquals(listOf(0L, 1L, 32L, 33L), records.map { it.getValue(253) - origin })
        val points = store.analytics.plot(id, "humanPowerW", 0.0, 34.0, 8)
        assertTrue(points.any { it.num("value") == 1000.0 })
        ZipFile(File(URI(exporter.archive(id)))).use { zip ->
            val csv = zip.getInputStream(zip.getEntry("telemetry.csv")).bufferedReader().readLines()
            val column = csv.first().split(',').indexOf("humanPowerW")
            assertEquals(
                listOf(100.0, 200.0, 1000.0, 0.0, 200.0, 300.0),
                csv.drop(1).map { it.split(',')[column].toDouble() },
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun archivePreservesLifecycleUtcAndAppendsTheZeroInterruptionIndex() {
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
        ZipFile(File(URI(exporter.archive(id)))).use { zip ->
            val events = JSONArray(zip.getInputStream(zip.getEntry("lifecycle.json")).bufferedReader().readText())
            val expected =
                listOf(
                    started,
                    RideEvent(2.0, "pause", pause.timestamp),
                    RideEvent(3.0, "resume", resume.timestamp),
                    RideEvent(4.0, "lap", lap.timestamp),
                    RideEvent(5.0, "stop", cutoff.timestamp),
                )
            assertEquals(expected.size, events.length())
            expected.forEachIndexed { index, event ->
                val row = events.getJSONObject(index)
                assertEquals(event.time, row.getDouble("elapsedSeconds"), 0.0)
                assertEquals(event.action, row.getString("action"))
                assertEquals(event.timestamp, row.getString("timestamp"))
            }
            val csv = zip.getInputStream(zip.getEntry("telemetry.csv")).bufferedReader().readLines()
            val columns = csv.first().split(',')
            assertEquals(26, columns.size)
            assertEquals(listOf("connectionEpoch", "interruptionIndex"), columns.takeLast(2))
            assertEquals(3, csv.size)
            csv.drop(1).forEach { line ->
                val cells = line.split(',')
                assertEquals(26, cells.size)
                assertEquals("one", cells[24])
                assertEquals("0", cells[25])
            }
        }
        val summary = exporter.detail(id, "auto")["summary"] as Payload
        assertEquals(cutoff.timestamp, summary["endedAt"])
        assertEquals(cutoff.elapsed, summary.num("elapsedSeconds"), 0.0)
        assertEquals(cutoff.timer, summary.num("timerSeconds"), 0.0)
        val fit = messages()
        val origin = 1767225600L - 631065600
        assertEquals(listOf(0L, 2L, 3L, 5L), fit.getValue(21).map { it.getValue(253) - origin })
        assertEquals(listOf(3000L, 1000L), fit.getValue(19).map { it.getValue(8) })
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun detailWithoutAnEndUsesTheRetainedCheckpointUtc() {
        val timing = RideTiming(10.0, 6.0, "2025-12-31T23:00:00.000Z")
        store.update(id, "paused", timing)
        repeat(2) {
            val summary = exporter.detail(id, "auto")["summary"] as Payload
            assertEquals(timing.timestamp, summary["endedAt"])
            assertEquals(timing.elapsed, summary.num("elapsedSeconds"), 0.0)
            assertEquals(timing.timer, summary.num("timerSeconds"), 0.0)
        }
    }

    @Test
    fun halfWattPowerBinsRoundAwayFromZero() {
        store.transaction {
            power(0.0, 100.0)
            power(0.5, 101.0)
        }
        store.seal(id, RideTiming(1.0, 1.0, iso()))
        assertEquals(101L, messages().getValue(20).single().getValue(7))
        assertArrayEquals(byteArrayOf(-101, -1, -1, -1), Field.s32(0, -100.5).bytes)
        assertArrayEquals(byteArrayOf(-1, -1, -1, 127), Field.s32(0, 2147483646.5).bytes)
    }

    @Test
    fun longitudeAtTheAntimeridianNeverUsesTheInvalidSignedSentinel() {
        store.transaction {
            listOf(180.0, -180.0, 179.99999999).forEachIndexed { index, longitude ->
                store.insert(
                    id,
                    index.toDouble(),
                    iso(),
                    "location",
                    true,
                    0,
                    mapOf(
                        "latitude" to 0.0,
                        "longitude" to longitude,
                        "horizontalAccuracyM" to 5.0,
                    ),
                )
            }
        }
        store.seal(id, RideTiming(3.0, 3.0, iso()))
        val records = messages().getValue(20)
        assertEquals(3, records.size)
        assertTrue(records.all { it.getValue(0) == 0L && it.getValue(1) == -2147483648L })
    }
}
