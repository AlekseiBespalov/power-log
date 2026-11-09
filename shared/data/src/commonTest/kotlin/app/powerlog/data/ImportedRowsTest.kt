package app.powerlog.data

import app.powerlog.application.store.ImportedPage
import app.powerlog.application.store.NearestTarget
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.TelemetryKey
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.imports.ImportReader
import app.powerlog.domain.imports.ImportResult
import app.powerlog.domain.imports.ImportedRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ImportedRowsTest {
    private val id = RideId("00000000-0000-4000-8000-000000000001")
    private val capabilities = ControllerCapabilities(setOf(MetricId.humanPowerW, MetricId.speedRaw), true)

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

    private fun row(index: Int) =
        Row(
            utcUs = 1.7e15 + index * 125_000.0,
            elapsedUs = index * 125_000.0,
            elapsedSeconds = index * 0.125,
            activeInterval = if (index % 7 == 0) 0 else 1,
            run = 1 + index / 5_000,
            connection = index / 3_000,
            timestamp = "2026-09-09T00:00:00." + "9".repeat(1 + index % 9) + "Z",
            values =
                buildMap {
                    put(MetricId.humanPowerW, 100.25 + index % 50)
                    if (index % 3 == 0) put(MetricId.speedRaw, 20.5 + index % 10)
                    if (index % 3 == 0) put(MetricId.controllerSpeedMps, 5.5)
                    if (index == 9_999) put(MetricId.cadenceRpm, 1e300)
                },
        )

    private fun page(rows: List<Row>): ImportedRows {
        val texts = rows.map { it.timestamp.encodeToByteArray() }
        val offsets = IntArray(rows.size + 1)
        for (index in rows.indices) offsets[index + 1] = offsets[index] + texts[index].size
        val metrics = rows.flatMap { it.values.keys }.toSet()
        return ImportedRows(
            rows = rows.size,
            utcUs = DoubleArray(rows.size) { rows[it].utcUs },
            elapsedUs = DoubleArray(rows.size) { rows[it].elapsedUs },
            elapsedSeconds = DoubleArray(rows.size) { rows[it].elapsedSeconds },
            activeInterval = IntArray(rows.size) { rows[it].activeInterval },
            run = IntArray(rows.size) { rows[it].run },
            connection = IntArray(rows.size) { rows[it].connection },
            timestamps = texts.fold(ByteArray(0)) { all, next -> all + next },
            timestampOffsets = offsets,
            values =
                metrics.associateWith { metric -> DoubleArray(rows.size) { rows[it].values[metric] ?: Double.NaN } },
        )
    }

    private fun rowsOf(page: ImportedRows): List<Row> =
        List(page.rows) { index ->
            Row(
                page.utcUs[index],
                page.elapsedUs[index],
                page.elapsedSeconds[index],
                page.activeInterval[index],
                page.run[index],
                page.connection[index],
                page.timestamps.decodeToString(page.timestampOffsets[index], page.timestampOffsets[index + 1]),
                page.values.entries
                    .mapNotNull { (metric, values) -> values[index].takeUnless { it.isNaN() }?.let { metric to it } }
                    .toMap(),
            )
        }

    private fun imported(count: Int): Pair<MemoryMeasurements, List<Row>> {
        val memory = MemoryMeasurements()
        val rows = List(count) { row(it) }
        memory.openImported(id, maxReadings = 700_000, maxElapsedUs = 604_800_000_000)
        for (chunk in rows.chunked(4_096)) assertTrue(memory.appendImported(id, page(chunk)))
        memory.finishImported(id, capabilities)
        return memory to rows
    }

    @Test
    fun rowsOfSeveralChunksRoundTripThroughPagesOfAnySize() = runTest {
        val (memory, rows) = imported(10_000)
        for (limit in listOf(4_096, 1_000, 3)) {
            val read = mutableListOf<Row>()
            var after: Int? = null
            do {
                val page = (memory.importedRows(id, after, limit) as StoreResult.Ok<ImportedPage>).value
                assertTrue(page.rows.rows in 1..limit)
                read += rowsOf(page.rows)
                after = page.next
            } while (after != null)
            assertEquals(rows, read, "limit $limit")
        }
    }

    @Test
    fun telemetryPagesCarryQuantaAcrossChunksWithTheFilesControllerSpeedAndNoUnquantizableValues() = runTest {
        val (memory, rows) = imported(10_000)
        val elapsed = mutableListOf<Long>()
        val power = mutableListOf<Double>()
        val speed = mutableListOf<Double>()
        val controller = mutableListOf<Double>()
        val speedQuantum = MetricCatalog.of(MetricId.controllerSpeedMps).quantum
        var after: TelemetryKey? = null
        do {
            val page = memory.telemetry(id, TimeRange(Long.MIN_VALUE, Long.MAX_VALUE), after, 4_000).ok()
            val telemetry = page.rows
            for (index in 0 until telemetry.rows) {
                controller +=
                    telemetry.quanta.getValue(MetricId.controllerSpeedMps)[index].takeUnless { it.isNaN() } ?: -1.0
                elapsed += telemetry.elapsedUs[index].toLong()
                power += telemetry.quanta.getValue(MetricId.humanPowerW)[index]
                speed += telemetry.quanta[MetricId.speedRaw]?.get(index)?.takeUnless { it.isNaN() } ?: -1.0
                if (telemetry.elapsedUs[index] == rows.last().elapsedUs) {
                    assertTrue(telemetry.quanta[MetricId.cadenceRpm]?.get(index)?.isNaN() ?: true)
                }
            }
            after = page.next
        } while (after != null)
        assertEquals(rows.map { it.elapsedUs.toLong() }, elapsed)
        assertEquals(rows.map { it.values.getValue(MetricId.humanPowerW) * 1_000 }, power)
        assertEquals(rows.map { it.values[MetricId.speedRaw]?.times(100) ?: -1.0 }, speed)
        assertEquals(rows.map { it.values[MetricId.controllerSpeedMps]?.times(speedQuantum) ?: -1.0 }, controller)
        val last = rows.last()
        assertEquals(RetainedEnd(last.utcUs.toLong(), last.elapsedUs.toLong()), memory.lastFact(id).ok())
        assertEquals(listOf(0, 1, 2, 3), memory.sessions(id).ok().map { it.sessionNo })
        assertEquals(3L, memory.extent(id).ok()?.revision)
    }

    @Test
    fun anEmptyPageAppendsNothingAndKeepsTheRevision() = runTest {
        val (memory, _) = imported(3)
        assertTrue(memory.appendImported(id, page(emptyList())))
        assertEquals(1L, memory.extent(id).ok()?.revision)
    }

    @Test
    fun aFileWithoutSpeedRawGivesItsControllerSpeedAsSpeedRawQuanta() = runTest {
        val reader = ImportReader()
        val fed =
            reader.feed(
                "timestamp,elapsedSeconds,run,controllerSpeedMps,cadenceRpm\n" +
                    "2026-09-09T00:00:00.000Z,0,1,5.5,80\n" +
                    "2026-09-09T00:00:01.000Z,1,1,,1000000000000\n"
            ) as ImportResult.Rows
        val read = reader.finish() as ImportResult.Read
        val memory = MemoryMeasurements()
        memory.openImported(id, maxReadings = 10, maxElapsedUs = 604_800_000_000)
        for (page in fed.pages + read.rows) assertTrue(memory.appendImported(id, page))
        memory.finishImported(id, read.capabilities)
        val telemetry = memory.telemetry(id, TimeRange(Long.MIN_VALUE, Long.MAX_VALUE), null, 10).ok().rows
        assertEquals(
            listOf(5.5 * MetricCatalog.of(MetricId.controllerSpeedMps).quantum, -1.0),
            telemetry.quanta.getValue(MetricId.controllerSpeedMps).map { if (it.isNaN()) -1.0 else it },
        )
        assertEquals(
            listOf(1_980.0, -1.0),
            telemetry.quanta.getValue(MetricId.speedRaw).map { if (it.isNaN()) -1.0 else it },
        )
        assertEquals(
            listOf(800_000.0, -1.0),
            telemetry.quanta.getValue(MetricId.cadenceRpm).map { if (it.isNaN()) -1.0 else it },
        )
        val exported = (memory.importedRows(id, null, 10) as StoreResult.Ok<ImportedPage>).value.rows
        assertEquals(setOf(MetricId.controllerSpeedMps, MetricId.cadenceRpm), exported.values.keys)
        val speedRaw = NearestTarget.Telemetry(MetricId.speedRaw)
        assertEquals(0L, memory.nearest(id, speedRaw, 1_000_000, before = true, withinUs = 5_000_000).ok())
    }

    @Test
    fun anUnknownRideHasNoImportedRows() = runTest {
        val page = (MemoryMeasurements().importedRows(id, null, 10) as StoreResult.Ok<ImportedPage>).value
        assertEquals(0, page.rows.rows)
        assertNull(page.next)
    }

    private fun <T> StoreResult<T>.ok(): T = (this as StoreResult.Ok<T>).value
}
