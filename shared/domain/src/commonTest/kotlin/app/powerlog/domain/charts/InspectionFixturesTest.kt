package app.powerlog.domain.charts

import app.powerlog.domain.analytics.J
import app.powerlog.domain.analytics.fixture
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.MetricStatistics
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class InspectionFixturesTest {
    private fun cases(name: String): List<J> = fixture("inspection/$name.json")["cases"].list()

    private fun each(cases: List<J>, check: (J) -> Unit) {
        val failures =
            cases
                .filter { it["id"].text() !in retired }
                .mapNotNull { case ->
                    try {
                        check(case)
                        null
                    } catch (failure: AssertionError) {
                        failure.message
                    } catch (failure: RuntimeException) {
                        "${case["id"].text()}: $failure"
                    }
                }
        assertEquals(emptyList(), failures)
    }

    private fun metric(case: J): MetricId = MetricId.valueOf(case["metric"].optionalText() ?: "humanPowerW")

    private fun statistics(points: ContractPoints, metric: MetricId, range: TimeRange): MetricStatistics {
        val reducer = MetricReducer(metric, range)
        points.feed(reducer) { reducer.append(it) }
        return assertIs<Bounded.Done<MetricStatistics>>(reducer.result()).value
    }

    private fun integral(statistics: MetricStatistics, metric: MetricId): Double? =
        statistics.integralQUs?.let { FixedPoint.divide(it, MetricCatalog.of(metric).quantum * 1_000_000) }

    private fun plotted(points: ContractPoints, result: List<PlotPoint>): Pair<List<String>, List<Int>> {
        val drawn =
            result
                .flatMap { point -> listOf(point.first, point.min, point.max, point.last).map { it to point.segment } }
                .distinctBy { it.first.utcUs }
                .sortedWith(compareBy({ it.first.elapsedUs }, { it.first.utcUs }))
        return drawn.map { points.id(it.first)!! } to drawn.map { it.second }
    }

    @Test
    fun selectionCasesRunThroughTheInspectionReducer() {
        each(cases("selection")) { case ->
            val id = case["id"].text()
            val metric = metric(case)
            val points = ContractPoints(case, metric, 20_000_000)
            val anchor = case["anchor"].optionalText()?.let(points::ref)
            val reducer =
                InspectionReducer(
                    metric,
                    InspectionTarget(case["timeUs"].long(), anchor),
                    TimeRange(0, 20_000_000),
                    case["allowTrailing"] == J.Bool(true),
                )
            points.feed(reducer) { reducer.append(it) }
            val result = assertIs<Bounded.Done<InspectionResult>>(reducer.result()).value
            assertEquals(case["expected"].optionalText(), points.id(result.selection), id)
        }
    }

    @Test
    fun statisticsCasesRunThroughTheMetricReducer() {
        each(cases("statistics")) { case ->
            val id = case["id"].text()
            val metric = metric(case)
            val points = ContractPoints(case, metric, case["rideEndUs"].long())
            val endUs = case["endUs"].long()
            val atEnd = case["points"].list().any { it["timeUs"].long() == endUs }
            val result = statistics(points, metric, TimeRange(case["startUs"].long(), if (atEnd) endUs + 1 else endUs))
            if (case["absent"] == J.Bool(true)) {
                assertEquals(0L, result.count, id)
                return@each
            }
            assertEquals(case["count"].long(), result.count, "$id count")
            assertEquals(case["minimum"].optionalText(), points.id(result.minimum), "$id minimum")
            assertEquals(case["maximum"].optionalText(), points.id(result.maximum), "$id maximum")
            assertEquals(case["mean"].optionalDouble(), result.average, "$id mean")
            assertEquals(case["coveredUs"].long(), result.coveredUs, "$id coveredUs")
            assertEquals(case["integral"].optionalDouble(), integral(result, metric), "$id integral")
            if (case["suppressed"] == J.Bool(true)) assertNull(result.integralQUs, id)
        }
    }

    @Test
    fun plotCasesRunThroughThePlotReducer() {
        each(cases("plot") + cases("plot-order")) { case ->
            val id = case["id"].text()
            val metric = metric(case)
            val range =
                TimeRange(
                    case["startUs"].optionalText()?.toLong() ?: 0,
                    case["endUs"].optionalText()?.toLong() ?: 20_000_000,
                )
            val points = ContractPoints(case, metric, range.endUs)
            val reducer = PlotReducer(metric, range, null, 1)
            points.feed(reducer) { reducer.append(it) }
            val (drawn, segments) = plotted(points, assertIs<Bounded.Done<List<PlotPoint>>>(reducer.result()).value)
            assertEquals(case["expected"].list().map { it.text() }, drawn, id)
            assertEquals(case["segments"].list().map { it.long().toInt() }, segments, "$id segments")
        }
    }

    @Test
    fun tileStatisticsCasesMergeIntoTheirRange() {
        each(fixture("inspection/accelerators.json")["statistics"].list() + cases("compact")) { case ->
            val id = case["id"].text()
            val metric = metric(case)
            val endUs = case["endUs"].long()
            val points = ContractPoints(case, metric, case["rideEndUs"].optionalText()?.toLong() ?: endUs)
            val atEnd = case["points"].list().any { it["timeUs"].long() == endUs }
            val range = TimeRange(case["startUs"].long(), if (atEnd) endUs + 1 else endUs)
            val tiles = case["buckets"].list().map { TimeRange(it.list()[0].long(), it.list()[1].long()) }
            val query = MetricReducer(metric, range)
            if (tiles.all { it.startUs >= range.startUs && it.endUs <= range.endUs }) {
                points.feed(query, TimeRange(Long.MIN_VALUE, tiles.first().startUs), query::append)
                for (tile in tiles) {
                    val bucket = MetricReducer(metric, tile, true)
                    points.feed(bucket, facts = bucket::append)
                    query.merge(bucket.state())
                }
                points.feed(query, TimeRange(tiles.last().endUs, Long.MAX_VALUE), query::append)
            } else points.feed(query, facts = query::append)
            val result = assertIs<Bounded.Done<MetricStatistics>>(query.result()).value
            assertEquals(case["count"].long(), result.count, "$id count")
            assertEquals(case["coveredUs"].long(), result.coveredUs, "$id coveredUs")
            assertEquals(case["integral"].optionalDouble(), integral(result, metric), "$id integral")
            if (case["mean"] != J.Null) assertEquals(case["mean"].optionalDouble(), result.average, "$id mean")
            assertEquals(case["minimum"].optionalText()?.let(points::named), points.id(result.minimum), "$id minimum")
            assertEquals(case["maximum"].optionalText()?.let(points::named), points.id(result.maximum), "$id maximum")
        }
    }

    @Test
    fun alignedPlotCasesKeepEachSlotsOriginals() {
        each(cases("aligned-plot")) { case ->
            val id = case["id"].text()
            val metric = metric(case)
            val range = TimeRange(case["startUs"].long(), case["endUs"].long())
            val buckets = case["buckets"].long().toInt()
            val slot = PlotSlots.alignedSlotUs(range, buckets)
            assertEquals(case["alignedSlotUs"].long(), slot, "$id slot")
            val points = ContractPoints(case, metric, range.endUs)
            val reducer = PlotReducer(metric, range, slot, buckets)
            points.feed(reducer) { reducer.append(it) }
            val (drawn, segments) = plotted(points, assertIs<Bounded.Done<List<PlotPoint>>>(reducer.result()).value)
            assertEquals(case["expected"].list().map { it.text() }, drawn, id)
            assertEquals(case["segments"].list().map { it.long().toInt() }, segments, "$id segments")
        }
    }

    @Test
    fun slotAndBudgetCasesAreThePlotSlots() {
        val slots = fixture("inspection/aligned-slots.json")
        for (case in slots["slots"].list()) {
            val id = case["id"].text()
            val range = TimeRange(case["startUs"].long(), case["endUs"].long())
            val buckets = case["buckets"].long().toInt()
            if (case["invalid"] == J.Bool(true)) {
                assertFailsWith<IllegalArgumentException>(id) { PlotSlots.alignedSlotUs(range, buckets) }
                continue
            }
            assertEquals(case["slotUs"].optionalText()?.toLong(), PlotSlots.alignedSlotUs(range, buckets), id)
        }
        for (case in slots["budgets"].list()) {
            val id = case["id"].text()
            val requested = case["requested"].long().toInt()
            val metrics = case["metrics"].long().toInt()
            if (case["invalid"] == J.Bool(true)) {
                assertFailsWith<IllegalArgumentException>(id) { PlotSlots.buckets(requested, metrics) }
                continue
            }
            assertEquals(case["buckets"].long().toInt(), PlotSlots.buckets(requested, metrics), id)
        }
    }

    @Test
    fun distancePlotCasesDrawTheirIntervalsBoundaries() {
        each(cases("distance-plot")) { case ->
            val id = case["id"].text()
            val points = case["points"].list().sortedWith(compareBy({ it["timeUs"].long() }, { it["tieKey"].long() }))
            val endUs = case["endUs"].long()
            val atEnd = points.any { it["timeUs"].long() == endUs }
            val range = TimeRange(case["startUs"].long(), if (atEnd) endUs + 1 else endUs)
            val reducer = PlotReducer(MetricId.controllerDistanceMeters, range, null, 1)
            for (point in points.filter { it["timeUs"].long() > it["startUs"].long() }) {
                val start = points.lastOrNull { it["timeUs"].long() == point["startUs"].long() }
                reducer.distance(ContractPoints.distance(point, start))
            }
            val names = points.associate { ContractPoints.START_UTC_US + it["tieKey"].long() to it["id"].text() }
            val drawn =
                assertIs<Bounded.Done<List<PlotPoint>>>(reducer.result())
                    .value
                    .flatMap { point ->
                        listOf(point.first, point.min, point.max, point.last).map { it to point.segment }
                    }
                    .distinctBy { it.first.utcUs }
                    .filter { it.first.utcUs in names }
            assertEquals(case["expected"].list().map { it.text() }, drawn.map { names[it.first.utcUs] }, id)
            val segments = case["segments"].list().map { it.long() }
            assertEquals(segments.map { segments.indexOf(it) }, drawn.map { it.second }, "$id segments")
        }
    }

    @Test
    fun distanceSelectionCasesTakeTheNearestBoundaryOfTheirInterval() {
        each(cases("distance")) { case ->
            val id = case["id"].text()
            val before = case["before"].takeIf { it != J.Null }
            val after = case["after"].takeIf { it != J.Null }
            val anchored = case["anchored"].takeIf { it != J.Null }
            val intervals =
                listOfNotNull(before, after, anchored)
                    .filter { it["timeUs"].long() > it["startUs"].long() }
                    .map { point ->
                        ContractPoints.distance(
                            point,
                            before?.takeIf {
                                it !== point &&
                                    it["timeUs"].long() == point["startUs"].long() &&
                                    it["segment"] == point["segment"]
                            },
                        )
                    }
            val anchor = anchored?.let { point ->
                intervals
                    .flatMap { listOf(it.start, it.end) }
                    .firstOrNull { it.utcUs == ContractPoints.START_UTC_US + point["tieKey"].long() }
                    ?: ContractPoints.distance(point, null).end
            }
            val reducer =
                InspectionReducer(
                    MetricId.controllerDistanceMeters,
                    InspectionTarget(case["timeUs"].long(), anchor),
                    null,
                    false,
                )
            intervals.forEach(reducer::distance)
            val selected = assertIs<Bounded.Done<InspectionResult>>(reducer.result()).value.selection
            val names =
                listOfNotNull(before, after, anchored).associate {
                    ContractPoints.START_UTC_US + it["tieKey"].long() to it["id"].text()
                }
            assertEquals(case["expected"].optionalText(), selected?.let { names[it.utcUs] }, id)
        }
    }

    private companion object {
        const val ONE_CLOCK = "a ride has one clock, so clockEpoch is ignored (30 §7.2)"
        const val PAUSE_INSTANT =
            "a reading at a Pause's instant has no interval, and a zero-length pause puts both readings in the next one " +
                "(30 §3.2.3), so today's closing and resumed readings at one instant have no counterpart"
        const val QUANTIZED =
            "readings are stored quantized (30 §3.2.2), so a value a millionth past the range is in it"
        const val FLOAT64 = "telemetry and Health values are Float64 (30 §3.2.5), exact only below 2^53"
        const val NO_CRANK = "crank revolutions are not in the metric catalog (30 §3.2.2)"
        val retired =
            mapOf(
                "clock-segment" to ONE_CLOCK,
                "clock-barrier" to ONE_CLOCK,
                "committed-interruption-close" to PAUSE_INSTANT,
                "resumed-equal-time-neighbour" to PAUSE_INSTANT,
                "exact-interruption-cutoff-identity" to PAUSE_INSTANT,
                "viewport-nonexact-interruption-predecessor" to PAUSE_INSTANT,
                "upper-power-barrier" to QUANTIZED,
                "cadence-barrier" to QUANTIZED,
                "semantic-assistLevel" to FLOAT64,
                "semantic-raceMode" to FLOAT64,
                "semantic-faultCode" to FLOAT64,
                "semantic-consumedAh" to FLOAT64,
                "semantic-consumedWh" to FLOAT64,
                "semantic-activeEnergyKcal" to FLOAT64,
                "semantic-basalEnergyKcal" to FLOAT64,
                "semantic-crankRevolutions" to NO_CRANK,
                "exact-semantic-counter" to NO_CRANK,
                "owned-edge-epoch" to ONE_CLOCK,
                "equal-time-clock-output-order" to ONE_CLOCK,
                "equal-cutoff-producer-output-order" to PAUSE_INSTANT,
                "compact-exact-semantic-counter" to FLOAT64,
            )
    }
}
