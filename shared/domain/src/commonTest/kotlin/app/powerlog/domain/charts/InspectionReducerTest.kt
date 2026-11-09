package app.powerlog.domain.charts

import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class InspectionReducerTest {
    private val ride = RideId("5a1e0001-0000-4000-8000-0000000000ee")
    private val identity = ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3")
    private val sessions =
        mapOf(
            1 to
                ControllerSession(
                    ride,
                    1,
                    identity,
                    requireNotNull(ControllerDecoders.of(identity.decoder)).capabilities(identity),
                )
        )

    private fun rows(
        vararg readings: Pair<Long, Long?>,
        run: ((Long) -> Int)? = null,
        interval: (Long) -> Int = { 1 },
    ) =
        TelemetryRows.of(
            readings.mapIndexed { index, (atUs, wattsQ) ->
                TelemetrySample(
                    ride,
                    1,
                    index + 1L,
                    atUs,
                    START_UTC_US + atUs,
                    interval(atUs),
                    (run ?: interval)(atUs),
                    buildMap {
                        put(MetricId.speedRaw, 1_800L)
                        if (wattsQ != null) put(MetricId.humanPowerW, wattsQ)
                    },
                )
            }
        )

    private fun ref(atUs: Long, wattsQ: Long) = OriginalRef(START_UTC_US + atUs, atUs, wattsQ)

    private fun inspect(
        readings: TelemetryRows,
        atUs: Long,
        anchor: OriginalRef? = null,
        range: TimeRange? = null,
        live: Boolean = true,
    ): InspectionResult {
        val reducer = InspectionReducer(MetricId.humanPowerW, InspectionTarget(atUs, anchor), range, live)
        reducer.append(readings, 0, readings.rows, sessions)
        return assertIs<Bounded.Done<InspectionResult>>(reducer.result()).value
    }

    @Test
    fun theCursorReportsTheNearestOriginalOfItsLine() {
        val readings = rows(0L to 100_000L, 1_000_000L to 200_000L)
        assertEquals(ref(1_000_000, 200_000), inspect(readings, 600_000).selection)
        assertEquals(ref(0, 100_000), inspect(readings, 500_000).selection)
        assertEquals(ref(1_000_000, 200_000), inspect(readings, 1_000_000).selection)
    }

    @Test
    fun theCursorNeverReportsAcrossAGapAPauseOrAMissingReading() {
        val gap = inspect(rows(0L to 100_000L, 7_000_000L to 200_000L), 1_000_000)
        assertNull(gap.selection)
        assertEquals(ref(0, 100_000), gap.reference)
        assertNull(inspect(rows(0L to 100_000L, 1_000_000L to 200_000L) { if (it > 0) 2 else 1 }, 400_000).selection)
        assertNull(inspect(rows(0L to 100_000L, 500_000L to null, 1_000_000L to 200_000L), 200_000).selection)
    }

    @Test
    fun theCursorAnswersAcrossARecordedRidesShortReconnect() {
        val readings = rows(0L to 100_000L, 1_000_000L to 200_000L, run = { if (it > 0) 2 else 1 })
        assertEquals(ref(0, 100_000), inspect(readings, 400_000).selection)
    }

    @Test
    fun theAnchoredOriginalWinsAndTheLastOriginalHoldsWithinThePlotGap() {
        val readings = rows(0L to 100_000L, 1_000_000L to 200_000L, 1_000_000L to 300_000L)
        assertEquals(ref(0, 100_000), inspect(readings, 0, anchor = ref(0, 100_000)).selection)
        assertEquals(ref(1_000_000, 200_000), inspect(readings, 1_000_000).selection)
        assertEquals(ref(1_000_000, 200_000), inspect(readings, 6_900_000).selection)
        assertNull(inspect(readings, 7_000_000).selection)
    }

    @Test
    fun pastTheLastReadingOnlyALiveSourceSelectsItAndNeverAcrossAnInvalidReadingOrAPause() {
        val readings = rows(0L to 100_000L, 1_000_000L to 200_000L)
        assertEquals(ref(1_000_000, 200_000), inspect(readings, 3_000_000).selection)
        assertNull(inspect(readings, 3_000_000, live = false).selection)
        assertEquals(ref(1_000_000, 200_000), inspect(readings, 1_000_000, live = false).selection)
        assertNull(inspect(rows(0L to 100_000L, 1_000_000L to 200_000L, 2_000_000L to null), 3_000_000).selection)
        val paused = InspectionReducer(MetricId.humanPowerW, InspectionTarget(3_000_000, null), null, true)
        paused.append(readings, 0, readings.rows, sessions)
        paused.append(Fact.Event(RideEvent(EventId("pause"), RideEventKind.pause, START_UTC_US + 2_000_000, 2_000_000)))
        assertNull(assertIs<Bounded.Done<InspectionResult>>(paused.result()).value.selection)
    }

    @Test
    fun aRangeAlsoReducesItsStatistics() {
        val readings = rows(0L to 100_000L, 1_000_000L to 200_000L, 2_000_000L to 300_000L)
        val statistics = requireNotNull(inspect(readings, 0, range = TimeRange(0, 2_000_000)).statistics)
        assertEquals(2L, statistics.count)
        assertEquals(ref(1_000_000, 200_000), statistics.maximum)
    }

    @Test
    fun aDistanceCursorTakesTheNearestBoundaryOfTheIntervalAroundIt() {
        val readings = rows(0L to 1L, 1_000_000L to 1L, 2_000_000L to 1L, 9_000_000L to 1L, 10_000_000L to 1L)
        val intervals = ControllerDistanceReducer(null).append(readings, 0, readings.rows, sessions)
        fun cursor(atUs: Long): OriginalRef? {
            val reducer =
                InspectionReducer(MetricId.controllerDistanceMeters, InspectionTarget(atUs, null), null, false)
            intervals.forEach(reducer::distance)
            return assertIs<Bounded.Done<InspectionResult>>(reducer.result()).value.selection
        }
        assertEquals(OriginalRef(START_UTC_US + 1_000_000, 1_000_000, 5_000_000), cursor(1_400_000))
        assertEquals(OriginalRef(START_UTC_US + 2_000_000, 2_000_000, 10_000_000), cursor(1_600_000))
        assertNull(cursor(5_000_000))
    }

    private companion object {
        const val START_UTC_US = 1_772_355_600_000_000L
    }
}
