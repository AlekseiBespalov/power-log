package app.powerlog.application.slice

import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.RideInput
import app.powerlog.application.exports.ExportCloseInput
import app.powerlog.application.exports.ExportKind
import app.powerlog.application.exports.ExportOpenInput
import app.powerlog.application.exports.ExportPage
import app.powerlog.application.exports.ExportPageInput
import app.powerlog.application.exports.ExportSessionView
import app.powerlog.application.exports.ExportSource
import app.powerlog.application.exports.ExportsPolicy
import app.powerlog.application.exports.encodedBytes
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.ride.FitPowerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExportsScenarioTest {
    private fun Scenario.failureLines() = archive.log.filter { it.event == "failure" }

    private suspend fun Scenario.open(rideId: RideId): ExportSessionView =
        assertIs<Outcome.Ok<ExportSessionView>>(
                api.exports.open(ExportOpenInput(ExportSource.Ride(rideId), ExportKind.zip, FitPowerSource.rider))
            )
            .value

    @Test
    fun closingWithAFailureWritesOneFailureLineAndClosingAgainWritesNone() {
        val scenario = Scenario()
        scenario.test {
            val seeded =
                scenario.seedRide(endUs = 10_000_000, readingsUs = (0L until 10_000_000L step 250_000L).toList())
            assertEquals(LaunchResult.Ready, scenario.launch())
            val view = scenario.open(seeded.ride.id)
            val close = ExportCloseInput(view.sessionId, FailureCode.exportChanged)
            assertEquals(Outcome.Ok(Unit), scenario.api.exports.close(close))
            assertEquals(Outcome.Ok(Unit), scenario.api.exports.close(close))
            runCurrent()
            val line = scenario.failureLines().single()
            assertEquals(LogLevel.warning, line.level)
            assertEquals(
                listOf(LogField("source", LogValue.Text("exports")), LogField("error", LogValue.Text("exportChanged"))),
                line.fields,
            )
        }
    }

    @Test
    fun aCloseWithoutAFailureAndAFailedOpenWriteNoFailureLine() {
        val scenario = Scenario()
        scenario.test {
            val seeded =
                scenario.seedRide(endUs = 10_000_000, readingsUs = (0L until 10_000_000L step 250_000L).toList())
            assertEquals(LaunchResult.Ready, scenario.launch())
            val view = scenario.open(seeded.ride.id)
            assertEquals(Outcome.Ok(Unit), scenario.api.exports.close(ExportCloseInput(view.sessionId, null)))
            val unknown =
                ExportOpenInput(ExportSource.Ride(RideId("never-stored")), ExportKind.fit, FitPowerSource.rider)
            assertEquals(FailureCode.rideDeleted, scenario.api.exports.open(unknown).code())
            runCurrent()
            assertEquals(emptyList(), scenario.failureLines())
        }
    }

    @Test
    fun aHealthPageHoldsNoMoreThanItsEncodedLimit() {
        val scenario = Scenario()
        scenario.test {
            val seeded = scenario.seedRide(endUs = 3_000_000_000, readingsUs = listOf(0L))
            val origin = "o".repeat(2_048)
            val samples =
                List(3_000) { index ->
                    val elapsedUs = index * 1_000_000L
                    HealthSample(
                        seeded.ride.id,
                        Producer.watch,
                        "sample-$index",
                        origin,
                        null,
                        null,
                        HealthMetric("heartRate"),
                        HealthRepresentation.rawQuantity,
                        120.0,
                        null,
                        Scenario.START_UTC_US + elapsedUs,
                        elapsedUs,
                        Scenario.START_UTC_US + elapsedUs,
                        Scenario.START_UTC_US + elapsedUs,
                        1,
                    )
                }
            scenario.archive.seed(WriteBatch(listOf(Mutation.AppendHealthSamples(seeded.ride.id, samples))))
            assertEquals(LaunchResult.Ready, scenario.launch())
            val view = scenario.open(seeded.ride.id)
            val pages = mutableListOf<ExportPage>()
            var after: List<Double>? = null
            do {
                val input = ExportPageInput(view.sessionId, "healthZip", after)
                val page = assertIs<Outcome.Ok<ExportPage>>(scenario.api.exports.page(input)).value
                pages += page
                after = page.next
            } while (!page.done)
            assertTrue(pages.size > 1)
            assertTrue(pages.all { it.encodedBytes() <= ExportsPolicy.pageMaxBytes })
            assertEquals(samples.size, pages.sumOf { it.rows })
            assertEquals(Outcome.Ok(Unit), scenario.api.exports.close(ExportCloseInput(view.sessionId, null)))
        }
    }

    @Test
    fun aRideIsNotDeletedWhileItExports() {
        val scenario = Scenario()
        scenario.test {
            val seeded =
                scenario.seedRide(endUs = 10_000_000, readingsUs = (0L until 10_000_000L step 250_000L).toList())
            assertEquals(LaunchResult.Ready, scenario.launch())
            val view = scenario.open(seeded.ride.id)
            val input = RideInput(seeded.ride.id)
            assertEquals(FailureCode.exportInProgress, scenario.api.history.delete(input).code())
            assertEquals(Outcome.Ok(Unit), scenario.api.exports.close(ExportCloseInput(view.sessionId, null)))
            assertEquals(Outcome.Ok(Unit), scenario.api.history.delete(input))
        }
    }
}
