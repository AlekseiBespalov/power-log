package app.powerlog.application.slice

import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.charts.ChartHeadInput
import app.powerlog.application.charts.ChartHeadView
import app.powerlog.application.charts.ChartSource
import app.powerlog.application.charts.LatestInput
import app.powerlog.application.charts.LatestView
import app.powerlog.application.fakes.Answer
import app.powerlog.application.fakes.FakePickedFile
import app.powerlog.application.imports.ImportInput
import app.powerlog.application.imports.ImportedRideView
import app.powerlog.application.imports.KeyInput
import app.powerlog.application.port.PickedFile
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.imports.ImportPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async

class ImportsScenarioTest {
    @Test
    fun aFileOverTheSizeBoundIsUnreadableBeforeAnyRead() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val file = HeldFile(ImportPolicy.maxBytes + 1)
            scenario.picker.picks.script = { Answer.Ok(file) }
            assertEquals(FailureCode.importUnreadable, scenario.api.imports.open(KeyInput("open")).code())
            assertEquals(0, file.reads)
            assertTrue(file.closed)
        }
    }

    @Test
    fun aCancelledPickerAnswersCancelled() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.picker.picks.script = { Answer.Failed(PortError(PortErrorKind.cancelled)) }
            assertEquals(Outcome.Cancelled, scenario.api.imports.open(KeyInput("open")))
        }
    }

    @Test
    fun anImportIsReadInChunksAndSummarizedFromTheReducers() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val file = ChunkedFile("ride.csv", ("﻿" + csv()).encodeToByteArray(), chunk = 2)
            scenario.picker.picks.script = { Answer.Ok(file) }
            val view = assertIs<Outcome.Ok<ImportedRideView>>(scenario.api.imports.open(KeyInput("open"))).value
            assertEquals("ride.csv", view.name)
            val summary = view.summary
            assertEquals(80, summary.samples)
            assertEquals(22_750_000, summary.durationUs)
            assertEquals(1, summary.gaps)
            assertEquals(0, summary.clockAdjustments)
            assertEquals(200.0, summary.averagePowerW)
            assertEquals(200.0, summary.peakPowerW)
            assertEquals(80.0, summary.averageCadenceRpm)
            assertEquals(200.0 * 19.5 / 3_600, requireNotNull(summary.riderWorkWh), 1e-9)
            assertTrue(file.closed)
        }
    }

    @Test
    fun aRowFailingACheckIsUnreadableAndClosesTheFileAndTheImport() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val file =
                FakePickedFile("bad.csv", (csv() + "not-a-time,1,1,1,1,200,80\n").encodeToByteArray(), scenario.post)
            scenario.picker.picks.script = { Answer.Ok(file) }
            assertEquals(FailureCode.importUnreadable, scenario.api.imports.open(KeyInput("open")).code())
            assertTrue(file.closed)
        }
    }

    @Test
    fun anImportedRideChartsItsColumnsOnlyWithNoTotalsUntilItCloses() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.picker.picks.script = {
                Answer.Ok(FakePickedFile("ride.csv", csv().encodeToByteArray(), scenario.post))
            }
            val view = assertIs<Outcome.Ok<ImportedRideView>>(scenario.api.imports.open(KeyInput("open"))).value
            val source = ChartSource.Imported(view.importId)
            val head =
                assertIs<StreamAnswer.Value<ChartHeadView>>(
                        scenario.api.charts.head.next(ChartHeadInput(source), null, "head")
                    )
                    .value
            assertEquals(listOf(MetricId.humanPowerW, MetricId.cadenceRpm), head.metrics)
            assertFalse(head.live)
            assertEquals(0, head.rangeUs.startUs)
            val latest =
                assertIs<Outcome.Ok<LatestView>>(
                        scenario.api.charts.latest(LatestInput(source, listOf(MetricId.humanPowerW), "latest", 0))
                    )
                    .value
            assertEquals(200.0, latest.readings.single().value)
            assertNull(latest.readings.single().freshUntilMonoUs)

            assertEquals(Outcome.Ok(Unit), scenario.api.imports.close(ImportInput(view.importId)))
            assertEquals(Outcome.Ok(Unit), scenario.api.imports.close(ImportInput(view.importId)))
            val closed = scenario.api.charts.latest(LatestInput(source, listOf(MetricId.humanPowerW), "latest", 0))
            assertEquals(FailureCode.rideDeleted, closed.code())
        }
    }

    @Test
    fun aNewerOpenWithTheSameKeyCancelsTheOlderAndClosesItsFile() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val held = HeldFile(10)
            scenario.picker.picks.script = { Answer.Ok(held) }
            val older = async { scenario.api.imports.open(KeyInput("open")) }
            runCurrent()
            assertEquals(1, held.reads)
            scenario.picker.picks.script = { Answer.Failed(PortError(PortErrorKind.cancelled)) }
            val newer = async { scenario.api.imports.open(KeyInput("open")) }
            assertEquals(Outcome.Cancelled, older.await())
            assertEquals(Outcome.Cancelled, newer.await())
            assertTrue(held.closed)
        }
    }

    @Test
    fun aFilePickedAfterItsOpenWasCancelledIsClosed() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.picker.picks.script = { Answer.Held }
            val older = async { scenario.api.imports.open(KeyInput("open")) }
            runCurrent()
            scenario.picker.picks.script = { Answer.Failed(PortError(PortErrorKind.cancelled)) }
            val newer = async { scenario.api.imports.open(KeyInput("open")) }
            assertEquals(Outcome.Cancelled, older.await())
            assertEquals(Outcome.Cancelled, newer.await())
            val late = HeldFile(10)
            scenario.picker.picks.complete(late)
            runCurrent()
            assertTrue(late.closed)
            assertEquals(0, late.reads)
        }
    }

    @Test
    fun aWholeImportPlotIsBuiltOverTilesHeldUntilTheImportCloses() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val start = java.time.Instant.parse("2026-03-14T08:00:00Z")
            val rows =
                (0L until 1_200_000_000L step 250_000L).map { us ->
                    "${start.plusNanos(us * 1_000)},${us / 1_000_000.0},1,1,1,${150 + us / 250_000 % 7 * 10},80"
                }
            val text =
                (listOf("timestamp,elapsedSeconds,activeInterval,run,connection,humanPowerW,cadenceRpm") + rows)
                    .joinToString("\n", postfix = "\n")
            scenario.picker.picks.script = {
                Answer.Ok(FakePickedFile("long.csv", text.encodeToByteArray(), scenario.post))
            }
            val view = assertIs<Outcome.Ok<ImportedRideView>>(scenario.api.imports.open(KeyInput("open"))).value
            val input =
                app.powerlog.application.charts.PlotInput(
                    ChartSource.Imported(view.importId),
                    listOf(MetricId.humanPowerW),
                    "plot",
                    null,
                    64,
                    0,
                )
            val first = assertIs<Outcome.Ok<app.powerlog.application.charts.PlotView>>(scenario.api.charts.plot(input))
            assertTrue(requireNotNull(first.value.slotUs) >= app.powerlog.domain.charts.PlotTiles.baseSlotUs)
            assertTrue(first.value.series.single().maxUs.isNotEmpty())
            assertEquals(first, scenario.api.charts.plot(input))
            assertEquals(Outcome.Ok(Unit), scenario.api.imports.close(ImportInput(view.importId)))
            assertEquals(FailureCode.rideDeleted, scenario.api.charts.plot(input).code())
        }
    }

    private fun csv(): String {
        val start = java.time.Instant.parse("2026-03-14T08:00:00Z")
        val elapsedUs = (0L until 10_000_000L step 250_000L) + (13_000_000L until 23_000_000L step 250_000L)
        val rows = elapsedUs.map { us ->
            "${start.plusNanos(us * 1_000)},${us / 1_000_000.0},1,1,1,200,80"
        }
        return (listOf("timestamp,elapsedSeconds,activeInterval,run,connection,humanPowerW,cadenceRpm") + rows)
            .joinToString("\n", postfix = "\n")
    }

    private class HeldFile(override val sizeBytes: Long) : PickedFile {
        override val name: String = "held.csv"
        var reads = 0
        var closed = false

        override fun read(maxBytes: Int, reply: Reply<ByteArray?>) {
            reads++
        }

        override fun close() {
            closed = true
        }
    }

    private class ChunkedFile(override val name: String, private val bytes: ByteArray, private val chunk: Int) :
        PickedFile {
        private var offset = 0
        var closed = false

        override val sizeBytes: Long = bytes.size.toLong()

        override fun read(maxBytes: Int, reply: Reply<ByteArray?>) {
            val end = minOf(bytes.size, offset + minOf(chunk, maxBytes))
            reply.ok(if (offset == bytes.size) null else bytes.copyOfRange(offset, end))
            offset = end
        }

        override fun close() {
            closed = true
        }
    }
}
