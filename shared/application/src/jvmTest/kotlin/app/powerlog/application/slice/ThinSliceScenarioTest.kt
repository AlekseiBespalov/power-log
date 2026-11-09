package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.charts.ChartSource
import app.powerlog.application.charts.PlotInput
import app.powerlog.application.charts.PlotView
import app.powerlog.application.history.HistoryPage
import app.powerlog.application.history.HistoryPageInput
import app.powerlog.application.recording.ControlInput
import app.powerlog.application.settings.SettingsView
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class ThinSliceScenarioTest {
    @Test
    fun anIndoorRideRecordsSixtySecondsOfFramesAndChartsItsRiderPower() {
        val scenario = Scenario()
        val api = scenario.api
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.indoor()
            val settings = assertIs<StreamAnswer.Value<SettingsView>>(api.settings.state.next(Empty, null, "settings"))
            assertEquals(false, settings.value.effective?.recordGps)

            assertEquals(Outcome.Ok(Unit), api.recording.start(Empty))
            val started = scenario.recording()
            assertEquals(RidePhase.running, started.phase)
            val rideId = requireNotNull(started.ride).rideId
            assertEquals(SampleRate.hz8, started.ride?.options?.sampleRate)

            delay(60_000)
            assertEquals(Outcome.Ok(Unit), api.recording.control(ControlInput(rideId, RideAction.finish)))
            val rows = scenario.telemetryRows()
            assertTrue(rows >= 456, "$rows telemetry rows")

            val page = assertIs<Outcome.Ok<HistoryPage>>(api.history.page(HistoryPageInput(null, "history")))
            val row = page.value.rows.single { it.rideId == rideId }
            assertEquals(RidePhase.completed, row.phase)
            assertTrue(row.indoor)

            val plot =
                assertIs<Outcome.Ok<PlotView>>(
                    api.charts.plot(
                        PlotInput(ChartSource.Ride(rideId), listOf(MetricId.humanPowerW), "plot", null, 64, 0)
                    )
                )
            val series = plot.value.series.single()
            assertTrue(series.first.isNotEmpty())
            assertTrue(series.max.all { it == 32_767.0 })

            assertEquals(Outcome.Ok(Unit), api.connection.disconnect(Empty))
        }
    }
}
