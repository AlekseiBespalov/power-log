package app.powerlog.application.store

import app.powerlog.application.store.ContractData.Reading
import app.powerlog.application.store.ContractData.START_UTC_US
import app.powerlog.application.store.ContractData.rideId
import app.powerlog.application.store.ContractData.session
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.imports.ImportedRows
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

abstract class PreviewContract {
    protected abstract val harness: MemoryHarness

    @Test
    fun aPreviewKeepsExactlyTheNewestMaxReadings() = runTest {
        val memory = harness.create()
        val preview = rideId(1)
        memory.openPreview(preview, maxReadings = 5)
        memory.addSession(preview, session(preview))
        val samples =
            List(12) {
                TelemetrySample(
                    rideId = preview,
                    sessionNo = 1,
                    seq = it + 1L,
                    elapsedUs = it * 125_000L,
                    utcUs = START_UTC_US + it * 125_000L,
                    activeInterval = null,
                    run = 1,
                    quanta = mapOf(MetricId.humanPowerW to 1_000L * it),
                )
            }
        memory.appendPreview(preview, samples.take(3))
        memory.appendPreview(preview, samples.subList(3, 7))
        memory.appendPreview(preview, samples.drop(7))
        val newest =
            samples.takeLast(5).map {
                Reading(it.elapsedUs, it.seq, it.sessionNo, it.utcUs, it.activeInterval, it.run, it.quanta)
            }
        assertEquals(newest, memory.allTelemetry(preview))
        assertEquals(
            TimeRange(samples[7].elapsedUs, samples.last().elapsedUs + 1),
            memory.extent(preview).ok()?.telemetry,
        )
        assertEquals(listOf(session(preview)), memory.sessions(preview).ok())
        memory.close(preview)
        assertNull(memory.extent(preview).ok())
    }
}

abstract class ImportedRideContract {
    protected abstract val harness: MemoryHarness

    private val capabilities =
        ControllerCapabilities(setOf(MetricId.humanPowerW, MetricId.speedRaw), controllerSpeed = true)

    private data class Row(
        val utcUs: Double,
        val elapsedUs: Double,
        val elapsedSeconds: Double,
        val activeInterval: Int,
        val run: Int,
        val connection: Int,
        val timestamp: String,
        val values: Map<MetricId, Double>,
    )

    @Test
    fun aPagePastABoundAppendsNothingAndRowsRoundTripExactly() = runTest {
        val memory = harness.create()
        val id = rideId(1)
        memory.openImported(id, maxReadings = 10, maxElapsedUs = 60_000_000)
        val first = imported(0 until 4)
        val second = imported(4 until 8)
        assertTrue(memory.appendImported(id, first))
        assertTrue(memory.appendImported(id, second))
        assertFalse(memory.appendImported(id, imported(8 until 12)))
        assertFalse(memory.appendImported(id, imported(8 until 9, elapsedFromUs = 61_000_000)))
        memory.finishImported(id, capabilities)
        for (limit in listOf(1, 3, 4_096)) assertEquals(
            rows(first) + rows(second),
            allImported(memory, id, limit),
            "limit $limit",
        )
        assertEquals(2L, memory.extent(id).ok()?.revision)
    }

    @Test
    fun readsFollowTheImportedConnectionsAndCarryEachValueInItsQuanta() = runTest {
        val memory = harness.create()
        val id = rideId(1)
        memory.openImported(id, maxReadings = 100, maxElapsedUs = 60_000_000)
        val written = imported(0 until 9)
        assertTrue(memory.appendImported(id, written))
        memory.finishImported(id, capabilities)
        val sessions = memory.sessions(id).ok()
        assertEquals(listOf(0, 1, 2), sessions.map { it.sessionNo })
        assertEquals(listOf(null, null, null), sessions.map { it.identity })
        assertEquals(listOf(capabilities, capabilities, capabilities), sessions.map { it.capabilities })
        val expected =
            rows(written).map { row ->
                Reading(
                    elapsedUs = row.elapsedUs.toLong(),
                    seq = 0,
                    sessionNo = row.connection,
                    utcUs = row.utcUs.toLong(),
                    activeInterval = row.activeInterval.takeIf { it != 0 },
                    run = row.run,
                    quanta =
                        row.values.mapValues { (metric, value) ->
                            round(value * MetricCatalog.of(metric).quantum).toLong()
                        },
                )
            }
        assertEquals(expected, memory.allTelemetry(id).map { it.copy(seq = 0) })
        assertEquals(emptyList(), memory.events(id).ok())
        assertEquals(emptyList(), memory.allLocations(id))
        assertEquals(emptyList(), memory.allHealth(id))
        val extent = memory.extent(id).ok()
        assertEquals(TimeRange(0, 8_000_001), extent?.telemetry)
        assertEquals(emptySet(), extent?.healthMetrics)
        assertEquals(emptySet(), extent?.healthProducers)
    }

    @Test
    fun nearestFindsTheClosestImportedRowThatReportsTheMetric() = runTest {
        val memory = harness.create()
        val id = rideId(1)
        memory.openImported(id, maxReadings = 100, maxElapsedUs = 60_000_000)
        assertTrue(memory.appendImported(id, imported(0 until 9)))
        memory.finishImported(id, capabilities)
        val power = NearestTarget.Telemetry(MetricId.humanPowerW)
        assertEquals(2_000_000L, memory.nearest(id, power, 1_000_000, before = false, withinUs = 5_000_000).ok())
        assertEquals(0L, memory.nearest(id, power, 1_500_000, before = true, withinUs = 5_000_000).ok())
        assertNull(memory.nearest(id, power, 1_500_000, before = true, withinUs = 400_000).ok())
        assertEquals(8_000_000L, memory.nearest(id, power, 9_000_000, before = true, withinUs = 1_000_000).ok())
        assertNull(memory.nearest(id, NearestTarget.Telemetry(MetricId.motorRpm), 0, before = false, withinUs = 1).ok())
    }

    private fun imported(indices: IntRange, elapsedFromUs: Long = 0): ImportedRows {
        val rows = indices.count()
        val timestamps = indices.map { "2026-09-09T00:00:${(it % 60).toString().padStart(2, '0')}.250Z" }
        val bytes = timestamps.map { it.encodeToByteArray() }
        return ImportedRows(
            rows = rows,
            utcUs = DoubleArray(rows) { (START_UTC_US + (indices.first + it) * 1_000_000L + 250_000).toDouble() },
            elapsedUs = DoubleArray(rows) { (elapsedFromUs + (indices.first + it) * 1_000_000L).toDouble() },
            elapsedSeconds = DoubleArray(rows) { (elapsedFromUs + (indices.first + it) * 1_000_000L) / 1e6 },
            activeInterval = IntArray(rows) { if ((indices.first + it) % 4 == 3) 0 else 1 },
            run = IntArray(rows) { 1 },
            connection = IntArray(rows) { (indices.first + it) % 3 },
            timestamps = bytes.fold(ByteArray(0)) { all, next -> all + next },
            timestampOffsets = IntArray(rows + 1) { row -> bytes.take(row).sumOf { it.size } },
            values =
                mapOf(
                    MetricId.humanPowerW to
                        DoubleArray(rows) { if (it == 1) Double.NaN else 100.5 + indices.first + it },
                    MetricId.cadenceRpm to DoubleArray(rows) { 80.25 },
                    MetricId.speedRaw to DoubleArray(rows) { 20.01 + it },
                ),
        )
    }

    private fun rows(page: ImportedRows): List<Row> =
        List(page.rows) { row ->
            Row(
                utcUs = page.utcUs[row],
                elapsedUs = page.elapsedUs[row],
                elapsedSeconds = page.elapsedSeconds[row],
                activeInterval = page.activeInterval[row],
                run = page.run[row],
                connection = page.connection[row],
                timestamp = page.timestamps.decodeToString(page.timestampOffsets[row], page.timestampOffsets[row + 1]),
                values =
                    page.values.entries
                        .mapNotNull { (metric, values) -> values[row].takeUnless { it.isNaN() }?.let { metric to it } }
                        .toMap(),
            )
        }

    private suspend fun allImported(memory: MemoryMeasurementRepository, id: RideId, limit: Int): List<Row> {
        val rows = mutableListOf<Row>()
        var after: Int? = null
        var pages = 0
        do {
            val page = memory.importedRows(id, after, limit).ok()
            assertTrue(page.rows.rows <= limit, "an imported page holds ${page.rows.rows} rows for a limit of $limit")
            assertTrue(++pages <= 10_000, "imported pages keep coming")
            if (pages > 1) assertTrue(page.rows.rows > 0, "next was not null at the end")
            rows += rows(page.rows)
            after = page.next
        } while (after != null)
        return rows
    }
}
