package app.powerlog.domain.imports

import app.powerlog.domain.analytics.fixture
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.Micros
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportReaderTest {
    private val columns =
        listOf(
            "timestamp",
            "elapsedSeconds",
            "activeInterval",
            "run",
            "connection",
            "humanPowerW",
            "cadenceRpm",
            "motorInputPowerW",
            "batteryVoltageV",
            "batteryCurrentA",
            "motorCurrentA",
            "motorRpm",
            "pedalTorqueNm",
            "controllerTempC",
            "motorTempC",
            "consumedAh",
            "consumedWh",
            "throttleVoltageV",
            "faultCode",
            "assistLevel",
            "controllerSpeedMps",
            "controller.raceMode",
            "controller.speedRaw",
        )
    private val header = columns.joinToString(",")

    private fun timestamp(row: Int): String = timestampAt(500L * row)

    private fun timestampAt(milliseconds: Long): String {
        val seconds = milliseconds / 1_000
        fun two(value: Long) = value.toString().padStart(2, '0')
        return "2026-11-02T${two(10 + seconds / 3_600)}:${two(seconds / 60 % 60)}:${two(seconds % 60)}." +
            "${(milliseconds % 1_000).toString().padStart(3, '0')}Z"
    }

    private fun line(row: Int, run: Int = 1, elapsed: String = (row * 0.5).toString()): String =
        listOf(
                timestamp(row),
                elapsed,
                "1",
                "$run",
                "1",
                "${150 + row % 50}",
                "80.5",
                "412.25",
                "51.75",
                "7.966",
                "11.5",
                "3150",
                "21.375",
                "38",
                "44.5",
                "0.125",
                "6.5",
                "0.85",
                "0",
                "3",
                "6.25",
                "0",
                "22.5",
            )
            .joinToString(",")

    private fun csv(rows: Int): String =
        (listOf(header) + (0 until rows).map { line(it) }).joinToString("\n", postfix = "\n")

    private fun refused(result: ImportResult) =
        assertEquals(FailureCode.importUnreadable, assertIs<ImportResult.Refused>(result).code)

    private fun pages(result: ImportResult): List<ImportedRows> = assertIs<ImportResult.Rows>(result).pages

    private fun ImportedRows.timestamp(row: Int): String =
        timestamps.copyOfRange(timestampOffsets[row], timestampOffsets[row + 1]).decodeToString()

    @Test
    fun aHeaderWithoutTheEnvelopeIsUnreadable() {
        refused(ImportReader().feed(columns.filter { it != "run" }.joinToString(",") + "\n"))
        refused(ImportReader().feed(columns.filter { it != "timestamp" }.joinToString(",") + "\n"))
        refused(ImportReader().feed("$header,humanPowerW\n"))
        refused(ImportReader().finish())
        val reader = ImportReader()
        refused(reader.feed("timestamp,elapsedSeconds\n"))
        refused(reader.feed(line(0) + "\n"))
        refused(reader.finish())
    }

    @Test
    fun rowsArriveInPagesInOrderAndTheRestWithTheCapabilities() {
        val text = csv(10_000)
        val reader = ImportReader()
        val read = mutableListOf<ImportedRows>()
        for (start in text.indices step 1_048_576) read +=
            pages(reader.feed(text.substring(start, minOf(text.length, start + 1_048_576))))
        val last = assertIs<ImportResult.Read>(reader.finish())
        read += last.rows
        assertTrue(read.all { it.rows <= ImportPolicy.pageRows })
        assertEquals(10_000, read.sumOf { it.rows })
        val elapsed = read.flatMap { page -> page.elapsedSeconds.toList() }
        assertEquals((0 until 10_000).map { it * 0.5 }, elapsed)
        assertEquals(columns.drop(5).size, last.capabilities.metrics.size)
        assertTrue(last.capabilities.controllerSpeed)
        assertTrue(MetricId.raceMode in last.capabilities.metrics && MetricId.speedRaw in last.capabilities.metrics)
    }

    @Test
    fun aRowSplitAcrossChunksWaitsForItsLineEnd() {
        val reader = ImportReader()
        val row = line(0)
        assertEquals(emptyList(), pages(reader.feed("$header\n${row.take(30)}")))
        val rows = pages(reader.feed(row.drop(30) + "\n"))
        assertEquals(1, rows.single().rows)
        assertEquals(timestamp(0), rows.single().timestamp(0))
        val unterminated = ImportReader()
        pages(unterminated.feed("$header\r\n${line(0)}\r\n${line(1)}"))
        val rest = assertIs<ImportResult.Read>(unterminated.finish()).rows
        assertEquals(1, rest.rows)
        assertEquals(timestamp(1), rest.timestamp(0))
    }

    @Test
    fun oversizedCellsDecreasingTimesAndTooManyRowsAreUnreadable() {
        refused(ImportReader().feed("$header\n${line(0).replace(",80.5,", ",${"8".repeat(257)},")}\n"))
        pages(ImportReader().feed("$header\n${line(0).replace(",80.5,", ",${"8".repeat(256)},")}\n"))
        refused(ImportReader().feed("$header\n${line(1)}\n${line(0)}\n"))
        refused(ImportReader().feed("$header\n${line(0, run = 2)}\n${line(1, run = 1)}\n"))
        refused(ImportReader().feed("$header\n${line(0, elapsed = "604800.5")}\n"))
        refused(ImportReader().feed("$header\n${line(0, elapsed = "Infinity")}\n"))
        refused(ImportReader().feed("$header\n${line(0, run = 0)}\n"))
        refused(ImportReader().feed("$header\n${line(0).replace(",3,6.25,", ",3.5,6.25,")}\n"))
        refused(ImportReader().feed("$header\n${line(0).replace("Z,", "+01:00,")}\n"))
        refused(ImportReader().feed("$header\n${line(0).replace("2026-11-02", "2026-02-30")}\n"))
        refused(ImportReader().feed("$header\n${line(0)},extra\n"))
        refused(ImportReader().feed("$header\n${line(0)}\n\n"))
        refused(ImportReader().feed("$header\n${line(0).replace("80.5", "80,5é")}\n"))
        val reader = ImportReader()
        val rows = List(ImportPolicy.pageRows) { "${timestamp(0)},0,1" }.joinToString("\n", postfix = "\n")
        var total = pages(reader.feed("timestamp,elapsedSeconds,run\n")).sumOf { it.rows }
        while (total + ImportPolicy.pageRows <= ImportPolicy.maxSamples) total +=
            pages(reader.feed(rows)).sumOf { it.rows }
        val rest = ImportPolicy.maxSamples - total
        total +=
            pages(reader.feed(List(rest) { "${timestamp(0)},0,1" }.joinToString("\n", postfix = "\n"))).sumOf {
                it.rows
            }
        assertEquals(ImportPolicy.maxSamples, total)
        refused(reader.feed("${timestamp(0)},0,1\n"))
        refused(reader.finish())
    }

    @Test
    fun missingValuesAreAbsentUnknownColumnsIgnoredAndControllerSpeedFollowsItsColumn() {
        val reader = ImportReader()
        val text =
            "note,run,elapsedSeconds,timestamp,humanPowerW,controllerSpeedMps\n" +
                "a,1,0,${timestamp(0)},,6.25\n" +
                "\"b,\"\"c\"\"\",1,0.5,${timestamp(1)},210,\n"
        val rows = pages(reader.feed(text)).single()
        assertEquals(2, rows.rows)
        assertTrue(rows.values.getValue(MetricId.humanPowerW)[0].isNaN())
        assertEquals(210.0, rows.values.getValue(MetricId.humanPowerW)[1])
        assertTrue(rows.values.getValue(MetricId.controllerSpeedMps)[1].isNaN())
        assertEquals(setOf(MetricId.humanPowerW, MetricId.controllerSpeedMps), rows.values.keys)
        assertContentEquals(intArrayOf(0, 0), rows.activeInterval)
        assertContentEquals(intArrayOf(0, 0), rows.connection)
        val read = assertIs<ImportResult.Read>(reader.finish())
        assertEquals(0, read.rows.rows)
        assertTrue(read.capabilities.controllerSpeed)
        val withoutSpeed = ImportReader()
        pages(withoutSpeed.feed("timestamp,elapsedSeconds,run\n${timestamp(0)},0,1\n"))
        assertEquals(false, assertIs<ImportResult.Read>(withoutSpeed.finish()).capabilities.controllerSpeed)
    }

    @Test
    fun aControllerSpeedColumnLeftEmptyGivesNoControllerSpeed() {
        val empty = ImportReader()
        pages(
            empty.feed(
                "timestamp,elapsedSeconds,run,controllerSpeedMps,controller.speedRaw\n" +
                    "${timestamp(0)},0,1,,180\n${timestamp(1)},0.5,1,,181\n"
            )
        )
        val read = assertIs<ImportResult.Read>(empty.finish())
        assertEquals(false, read.capabilities.controllerSpeed)
        assertEquals(setOf(MetricId.speedRaw), read.capabilities.metrics)

        val late = ImportReader()
        val rows =
            (0 until ImportPolicy.pageRows + 1).joinToString("") { row ->
                "${timestamp(row)},${row * 0.5},1,${if (row == ImportPolicy.pageRows) "6.25" else ""}\n"
            }
        pages(late.feed("timestamp,elapsedSeconds,run,controllerSpeedMps\n$rows"))
        assertTrue(assertIs<ImportResult.Read>(late.finish()).capabilities.controllerSpeed)
    }

    @Test
    fun aRowsTimestampAndElapsedSecondsReadBackUnchanged() {
        for (case in listOf("telemetry-lifecycle", "gps-iphone", "gps-android", "health-descriptor")) {
            val lines = fixture("../export/zip/$case/telemetry.csv.json").list().map { it.text() }
            for (ending in listOf("\n", "\r\n")) {
                val reader = ImportReader()
                val read = pages(reader.feed("\uFEFF" + lines.joinToString(ending, postfix = ending)))
                val rows = read.singleOrNull() ?: assertIs<ImportResult.Read>(reader.finish()).rows
                assertEquals(lines.size - 1, rows.rows, case)
                for ((index, text) in lines.drop(1).withIndex()) {
                    val cells = text.split(',')
                    assertEquals(cells[0], rows.timestamp(index), case)
                    assertEquals(cells[1].toDouble(), rows.elapsedSeconds[index], case)
                    assertEquals(Micros.of(cells[1].toDouble()).toDouble(), rows.elapsedUs[index], case)
                }
            }
        }
    }

    @Test
    fun aGapAtAPageBoundaryIsCountedOnce() {
        val last = ImportPolicy.pageRows - 1
        val step =
            line(last, elapsed = (last * 0.5 + 2.6).toString())
                .replace(timestamp(last), timestampAt(last * 500L + 2_600))
        val text = (listOf(header) + (0..last).map { line(it) } + step).joinToString("\n", postfix = "\n")
        val pages = pages(ImportReader().feed(text))
        assertEquals(listOf(ImportPolicy.pageRows, 1), pages.map { it.rows })
        val counts = ImportCountsReducer()
        pages.forEach(counts::append)
        assertEquals(ImportCounts(gaps = 1, clockAdjustments = 0), counts.result())
    }

    @Test
    fun clockAdjustmentsAndGapsFollowTodaysCounters() {
        val text =
            listOf(
                    header,
                    line(0),
                    line(1),
                    line(2, run = 2),
                    line(3, run = 2).replace(timestamp(3), timestamp(2)),
                    line(10, run = 2, elapsed = "1.5"),
                )
                .joinToString("\n", postfix = "\n")
        val counts = ImportCountsReducer()
        pages(ImportReader().feed(text)).forEach(counts::append)
        assertEquals(ImportCounts(gaps = 1, clockAdjustments = 2), counts.result())
    }
}
