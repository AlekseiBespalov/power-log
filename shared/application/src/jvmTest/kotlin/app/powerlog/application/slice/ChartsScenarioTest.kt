package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.api.StreamPolicy
import app.powerlog.application.charts.ChartHeadInput
import app.powerlog.application.charts.ChartHeadView
import app.powerlog.application.charts.ChartSource
import app.powerlog.application.charts.PlotInput
import app.powerlog.application.charts.PlotView
import app.powerlog.application.store.Mutation
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class ChartsScenarioTest {
    @Test
    fun aZoomedPlotIncludesTheNearestOriginalsAroundItsRangeAndAGapInsideABucketBreaksTheLine() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(10_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.pause))
            delay(15_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.resume))
            delay(10_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))

            val range = TimeRange(5_060_000, 30_060_000)
            val elapsed =
                scenario.telemetry().flatMap { append ->
                    (0 until append.rows.rows).map { append.rows.elapsedUs[it].toLong() }
                }
            val plot = assertIs<Outcome.Ok<PlotView>>(scenario.api.charts.plot(zoom(rideId, range, "zoom"))).value
            val series = plot.series.single()
            assertEquals(elapsed.last { it < range.startUs }, series.firstUs.first())
            assertEquals(elapsed.first { it >= range.endUs }, series.lastUs.last())

            val slot = requireNotNull(plot.slotUs)
            val inside = series.firstUs.indices.drop(1).dropLast(1)
            val broken =
                inside.zipWithNext().any { (a, b) ->
                    series.firstUs[a] / slot == series.firstUs[b] / slot && series.segment[a] != series.segment[b]
                }
            assertTrue(broken, "segments ${series.segment} at ${series.firstUs}, slot $slot")
        }
    }

    @Test
    fun aNewerPlotWithTheSameKeyCancelsTheOlder() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(3_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            val older = async { scenario.api.charts.plot(zoom(rideId, null, "chart")) }
            val newer = async { scenario.api.charts.plot(zoom(rideId, null, "chart")) }
            assertEquals(Outcome.Cancelled, older.await())
            assertIs<Outcome.Ok<PlotView>>(newer.await())
        }
    }

    @Test
    fun theChartHeadDescribesThePreviewARecordingRideAndAStoredRide() {
        val scenario = Scenario()
        val head = scenario.api.charts.head
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val connecting = scenario.clock.monoUs()
            with(scenario) { connect() }
            val connected = scenario.clock.monoUs()
            scenario.indoor()
            delay(3_000)
            val preview =
                assertIs<StreamAnswer.Value<ChartHeadView>>(head.next(ChartHeadInput(ChartSource.Preview), null, "p"))
                    .value
            assertTrue(preview.live)
            assertTrue(requireNotNull(preview.originMonoUs) in connecting..connected)
            assertEquals(TimeRange(0, 10_000_000), preview.rangeUs)
            assertTrue(MetricId.humanPowerW in preview.metrics, preview.metrics.toString())
            assertFalse(preview.catchingUp)

            val startedAtUs = scenario.clock.monoUs()
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            val rideId = requireNotNull(scenario.recording().ride).rideId
            val source = ChartHeadInput(ChartSource.Ride(rideId))
            val recording = assertIs<StreamAnswer.Value<ChartHeadView>>(head.next(source, null, "r"))
            assertTrue(recording.value.live)
            assertEquals(startedAtUs, recording.value.originMonoUs)
            assertEquals(preview.metrics, recording.value.metrics)
            val answeredAtUs = scenario.clock.monoUs()
            val moved = assertIs<StreamAnswer.Value<ChartHeadView>>(head.next(source, recording.revision, "r"))
            assertTrue(moved.value.revision > recording.value.revision)
            assertTrue(scenario.clock.monoUs() - answeredAtUs >= StreamPolicy.chartsHeadUs)

            delay(20_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            val stored = assertIs<StreamAnswer.Value<ChartHeadView>>(head.next(source, null, "s")).value
            assertFalse(stored.live)
            assertNull(stored.originMonoUs)
            val end = requireNotNull(scenario.mutations().filterIsInstance<Mutation.EndRide>().single().end).elapsedUs
            val newest = scenario.telemetry().maxOf { it.rows.elapsedUs[it.rows.rows - 1].toLong() } + 1
            assertEquals(TimeRange(0, maxOf(end, newest)), stored.rangeUs)

            val unknown = head.next(ChartHeadInput(ChartSource.Ride(RideId("unknown"))), null, "u")
            assertEquals(FailureCode.rideDeleted, assertIs<StreamAnswer.Failed>(unknown).failure.code)
        }
    }

    @Test
    fun aPreviewsChartHeadFollowsTheNextPreview() {
        val scenario = Scenario()
        val head = scenario.api.charts.head
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            delay(30_000)
            scenario.telemetryReplies = false
            delay(1_000)
            val first =
                assertIs<StreamAnswer.Value<ChartHeadView>>(head.next(ChartHeadInput(ChartSource.Preview), null, "p"))
            val next = async { head.next(ChartHeadInput(ChartSource.Preview), first.revision, "p") }
            assertEquals(Outcome.Ok(Unit), scenario.api.connection.disconnect(Empty))
            scenario.fake.os.disconnected(Scenario.BIKE)
            runCurrent()
            scenario.telemetryReplies = true
            val reconnecting = scenario.clock.monoUs()
            with(scenario) { connect() }
            val second = assertIs<StreamAnswer.Value<ChartHeadView>>(next.await())
            assertTrue(requireNotNull(second.value.originMonoUs) >= reconnecting)
            assertTrue(second.value.revision < first.value.revision)
        }
    }

    private fun zoom(rideId: RideId, range: TimeRange?, key: String) =
        PlotInput(ChartSource.Ride(rideId), listOf(MetricId.humanPowerW), key, range, 1, 0)
}
