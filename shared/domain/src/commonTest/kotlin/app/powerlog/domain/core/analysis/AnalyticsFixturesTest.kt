package app.powerlog.domain.core.analysis

import app.powerlog.domain.analytics.J
import app.powerlog.domain.analytics.fixture
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.MetricStatistics
import app.powerlog.domain.core.analysis.statistics.timeAverage
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsFixturesTest {
    private val reference = fixture("analytics/iphone-reference.json")["cases"].list()
    private val deltas = fixture("analytics/approved-deltas.json")["cases"].list()
    private val review = fixture("analytics/review-required.json")["cases"].list()

    private fun delta(id: String): J = deltas.first { it["testId"].text() == id }

    private fun case(id: String): J = review.first { it["testId"].text() == id }

    private fun reference(name: String): J = reference.first { it["name"].text() == name }

    private fun collect(names: List<String>, check: (String) -> Unit) {
        val failures =
            names
                .filter { it !in retired }
                .mapNotNull { name ->
                    try {
                        check(name)
                        null
                    } catch (failure: AssertionError) {
                        "$name: ${failure.message}"
                    } catch (failure: RuntimeException) {
                        "$name: $failure"
                    }
                }
        assertEquals(emptyList(), failures)
    }

    private fun seconds(us: Long): Double = FixedPoint.divide(us, 1_000_000)

    private fun range(ride: ContractRide, query: J): TimeRange {
        val startUs = FixedPoint.timeUs(query["startSeconds"].double())
        val endUs = FixedPoint.timeUs(query["endSeconds"].double())
        return TimeRange(startUs, if (ride.hasOriginalAt(endUs)) endUs + 1 else endUs)
    }

    private fun expectStatistics(
        ride: ContractRide,
        expected: J,
        actual: MetricStatistics,
        metric: MetricId,
        path: String,
    ) {
        assertEquals(expected["count"].long(), actual.count, "$path count")
        assertEquals(expected["min"]["observationId"].optionalText(), ride.id(actual.minimum, metric), "$path min")
        assertEquals(expected["max"]["observationId"].optionalText(), ride.id(actual.maximum, metric), "$path max")
        val stored = { value: Double? -> value?.let { FixedPoint.quantize(it, MetricCatalog.of(metric).quantum) } }
        assertEquals(stored(expected["min"]["value"].optionalDouble()), actual.minimum?.valueQ, "$path min value")
        assertEquals(stored(expected["max"]["value"].optionalDouble()), actual.maximum?.valueQ, "$path max value")
        assertEquals(expected["sampleMean"].optionalDouble(), actual.average, "$path sampleMean")
        val integral = actual.integralQUs
        assertEquals(
            expected["coveredSeconds"].optionalDouble(),
            integral?.let { seconds(actual.coveredUs) },
            "$path coveredSeconds",
        )
        assertEquals(
            expected["integral"].optionalDouble(),
            integral?.let { FixedPoint.divide(it, MetricCatalog.of(metric).quantum * 1_000_000) },
            "$path integral",
        )
    }

    private fun expectQueries(ride: ContractRide, queries: List<J>, expected: List<J>, path: String) {
        assertEquals(expected.size, queries.size, "$path queries")
        for ((index, query) in queries.withIndex()) {
            if ("$path/$index" in retired) continue
            val range = range(ride, query)
            val results = expected[index]
            for (name in query["metrics"].list().map { it.text() }) {
                val wanted = results[name]
                val at = "$path/$index/$name"
                if (name == "distanceMeters") {
                    val totals = ride.distance(range)
                    assertEquals(
                        wanted["distance"].optionalDouble(),
                        ride.selectedIntervals?.let { totals.distanceQ / 1e6 },
                        "$at distance",
                    )
                    assertEquals(
                        wanted["coveredSeconds"].optionalDouble(),
                        seconds(totals.coveredUs),
                        "$at coveredSeconds",
                    )
                    assertEquals(wanted["partial"].bool(), totals.partial, "$at partial")
                    assertEquals(
                        wanted["unresolvedBoundary"].bool(),
                        totals.unresolvedBoundary,
                        "$at unresolvedBoundary",
                    )
                    continue
                }
                val metric = MetricId.valueOf(name)
                val statistics = ride.statistics(metric, range)
                if (wanted == J.Null) {
                    assertEquals(0L, statistics.count, "$at absent")
                    continue
                }
                expectStatistics(ride, wanted, statistics, metric, at)
            }
        }
    }

    @Test
    fun theReferenceRidesStatisticsAreTodays() {
        val targets = delta("chart-reference-targets")["expectedApproved"]
        collect(reference.filter { it["expectedCurrentIphoneStatistics"] != J.Null }.map { it["name"].text() }) { name
            ->
            val input = reference(name)["input"]
            val ride = ContractRide(input)
            expectQueries(ride, input["queries"].list(), targets[name].list(), name)
        }
    }

    @Test
    fun theApprovedDeltaRidesStatisticsAreTodays() {
        val zero = delta("chart-zero-width-end")
        val barriers = delta("all-channel-barriers")
        val rides =
            listOf(
                "chart-zero-width-end" to (zero["input"]["zeroWidthRide"] to zero["expectedApproved"]["zeroWidth"])
            ) +
                barriers["input"]["rides"].fields().map { (name, input) ->
                    "all-channel-barriers/$name" to (input to barriers["expectedApproved"][name])
                }
        val byName = rides.toMap()
        collect(byName.keys.toList()) { name ->
            val (input, expected) = byName.getValue(name)
            val ride = ContractRide(input)
            expectQueries(ride, input["queries"].list(), expected.list(), name)
        }
    }

    @Test
    fun theReviewRidesStatisticsAndDistancesAreTodays() {
        val ids =
            listOf(
                "controller-valid",
                "explicit-unavailable",
                "pause-stop-coincident",
                "paused-final-endpoint",
                "recovery-interruption",
                "checkpoint-valid",
                "checkpoint-paused",
                "checkpoint-changed-epoch",
                "admission-quantization",
            )
        collect(ids) { id ->
            val c = case(id)
            val input = c["input"]["archiveJob"]["input"]
            val ride = ContractRide(input)
            val expected = c["expectedApproved"]
            expectQueries(ride, input["queries"].list(), expected["statistics"].list(), id)
            if (expected["distanceFieldPresent"] != J.Null) {
                val whole = TimeRange(0, ride.endUs + 1)
                assertEquals(
                    expected["distanceMeters"].optionalDouble(),
                    ride.selectedIntervals?.let { ride.distance(whole).distanceQ / 1e6 },
                    "$id distanceMeters",
                )
            }
        }
    }

    @Test
    fun theGpsReferenceDistanceIsTodaysFixedPointSum() {
        val ride = ContractRide(reference(case("gps-fixed-point-summary")["input"]["referenceCase"].text())["input"])
        val expected = case("gps-fixed-point-summary")["expectedApproved"]["distance"]["selected"]
        val totals = ride.distance(TimeRange(0, ride.endUs + 1))
        assertEquals(expected["distanceMeters"].double(), totals.distanceQ / 1e6)
        assertEquals(expected["coveredSeconds"].double(), seconds(totals.coveredUs))
        assertEquals(expected["partial"].bool(), totals.partial)
        assertEquals(expected["uncoveredSeconds"].double(), seconds(ride.timeline.timerUs - totals.coveredUs))
    }

    @Test
    fun controllerEvidenceIntegratesOnceWhateverItsPages() {
        val c = case("controller-counter-equal")
        val evidence = c["input"]["projectedControllerEvidence"].list()
        val rows =
            TelemetryRows(
                rows = evidence.size,
                elapsedUs = DoubleArray(evidence.size) { evidence[it]["timeUs"].double() },
                sessionNo = IntArray(evidence.size) { ContractRide.SESSION },
                seq = DoubleArray(evidence.size) { it + 1.0 },
                utcUs = DoubleArray(evidence.size) { evidence[it]["timeUs"].double() },
                activeInterval = IntArray(evidence.size) { 1 },
                run = IntArray(evidence.size) { 1 },
                quanta =
                    mapOf(
                        MetricId.speedRaw to
                            DoubleArray(evidence.size) {
                                FixedPoint.quantize(evidence[it]["speedMps"].double() * 3.6, 100).toDouble()
                            }
                    ),
            )
        val sessions = ContractRide(reference("means-and-brackets")["input"]).sessions
        for (page in listOf(1, 2)) {
            val reducer = ControllerDistanceReducer(null)
            val intervals =
                (0 until rows.rows step page).flatMap {
                    reducer.append(rows, it, minOf(rows.rows, it + page), sessions)
                }
            val interval = intervals.single()
            assertEquals(c["expectedApproved"]["distanceQ"].long(), interval.distanceQ, "page $page")
            assertEquals(
                c["expectedApproved"]["coveredUs"].long(),
                interval.range.endUs - interval.range.startUs,
                "page $page",
            )
        }
    }

    @Test
    fun aDistanceIsPartialPastAMillisecondUncovered() {
        val c = case("distance-partial-microsecond")
        val timerUs = c["input"]["timerUs"].long()
        c["input"]["coveredUs"].list().forEachIndexed { index, covered ->
            val reducer = DistanceReducer(TimeRange(0, timerUs), null)
            val end = OriginalRef(covered.long(), covered.long(), 1_000_000)
            reducer.append(
                DistanceInterval(TimeRange(0, covered.long()), 1_000_000, 0, 1, OriginalRef(0, 0, 0), end, null, null)
            )
            val totals = (reducer.result(timerUs) as Bounded.Done).value
            assertEquals(
                c["expectedApproved"]["partial"].list()[index].bool(),
                totals.partial,
                "covered ${covered.long()}",
            )
        }
    }

    @Test
    fun fractionalReadingsSumTheSameInEveryPartition() {
        collect(listOf("fractional-partition-100.1", "fractional-partition-0.1")) { id ->
            val c = case(id)
            val reading = c["input"]["reading"]
            val metric = MetricId.valueOf(reading["metric"].text())
            val count = reading["count"].long().toInt()
            val rows =
                TelemetryRows(
                    rows = count,
                    elapsedUs =
                        DoubleArray(count) { (reading["startUs"].long() + it * reading["stepUs"].long()).toDouble() },
                    sessionNo = IntArray(count) { ContractRide.SESSION },
                    seq = DoubleArray(count) { it + 1.0 },
                    utcUs =
                        DoubleArray(count) { (reading["startUs"].long() + it * reading["stepUs"].long()).toDouble() },
                    activeInterval = IntArray(count) { 1 },
                    run = IntArray(count) { 1 },
                    quanta =
                        mapOf(
                            metric to
                                DoubleArray(count) {
                                    FixedPoint.quantize(reading["value"].double(), MetricCatalog.of(metric).quantum)
                                        .toDouble()
                                }
                        ),
                )
            val sessions = ContractRide(reference("means-and-brackets")["input"]).sessions
            val expected = c["expectedApproved"]
            val whole = TimeRange(0, rows.elapsedUs.last().toLong() + 1)
            val raw = MetricReducer(metric, whole).also { it.append(rows, 0, count, sessions) }
            val paged = MetricReducer(metric, whole)
            for (start in 0 until count step 128) paged.append(rows, start, minOf(count, start + 128), sessions)
            val first = MetricReducer(metric, whole).also { it.append(rows, 0, count / 2, sessions) }
            val resumed =
                MetricReducer(metric, whole).also {
                    it.merge(first.state())
                    it.append(rows, count / 2, count, sessions)
                }
            for ((name, reducer) in listOf("raw" to raw, "paged" to paged, "checkpoint" to resumed)) {
                val statistics = (reducer.result() as Bounded.Done).value
                assertEquals(expected["count"].long(), statistics.count, "$id $name count")
                assertEquals(expected["coveredUs"].long(), statistics.coveredUs, "$id $name coveredUs")
                assertEquals(expected["integralQUs"].long(), statistics.integralQUs, "$id $name integralQUs")
                assertEquals(expected["sequentialSampleMean"].double(), statistics.average, "$id $name sampleMean")
                assertEquals(
                    expected["weightedMean"].double(),
                    statistics.timeAverage(metric),
                    "$id $name weightedMean",
                )
            }
        }
    }

    @Test
    fun int64ValuesRoundOnceToFloat64() {
        val c = case("int64-output-rounding")
        val expected = c["expectedApproved"]
        c["input"]["integers"].list().zip(expected["conversions"].list()).forEach { (value, conversion) ->
            assertEquals(
                conversion["bitsHex"].text(),
                value.long().toDouble().toBits().toULong().toString(16).padStart(16, '0'),
            )
        }
        val division = c["input"]["division"]
        val quotient = FixedPoint.divide(division["numerator"].long(), division["denominator"].long())
        assertEquals(expected["division"]["bitsHex"].text(), quotient.toBits().toULong().toString(16).padStart(16, '0'))
    }

    @Test
    fun appleDistanceCasesIntegrateEachSource() {
        val cases = fixture("analytics/apple-distance.json")["cases"].list()
        collect(cases.map { it["id"].text() }) { id ->
            val c = cases.first { it["id"].text() == id }
            val samples = c["samples"].list().sortedBy { it["time"].double() }
            val intervals =
                if (c["source"].text() == "controller") {
                    val rows =
                        TelemetryRows(
                            rows = samples.size,
                            elapsedUs =
                                DoubleArray(samples.size) {
                                    FixedPoint.timeUs(samples[it]["time"].double()).toDouble()
                                },
                            sessionNo = IntArray(samples.size) { ContractRide.SESSION },
                            seq = DoubleArray(samples.size) { it + 1.0 },
                            utcUs =
                                DoubleArray(samples.size) {
                                    FixedPoint.timeUs(samples[it]["time"].double()).toDouble()
                                },
                            activeInterval = IntArray(samples.size) { 1 },
                            run = IntArray(samples.size) { 1 },
                            quanta =
                                mapOf(
                                    MetricId.speedRaw to
                                        DoubleArray(samples.size) {
                                            FixedPoint.quantize(samples[it]["speed"].double() * 3.6, 100).toDouble()
                                        }
                                ),
                        )
                    ControllerDistanceReducer(null)
                        .append(rows, 0, rows.rows, ContractRide(reference("means-and-brackets")["input"]).sessions)
                } else {
                    val reducer = GpsDistanceReducer(null)
                    samples.mapIndexedNotNull { index, sample ->
                        val atUs = FixedPoint.timeUs(sample["time"].double())
                        reducer
                            .append(
                                LocationFix(
                                    ContractRide.ride,
                                    index + 1L,
                                    atUs,
                                    atUs,
                                    1,
                                    1,
                                    false,
                                    sample["latitude"].optionalDouble() ?: 0.0,
                                    sample["longitude"].double(),
                                    5.0,
                                    sample["altitude"].optionalDouble(),
                                    AltitudeDatum.msl,
                                    sample["verticalAccuracy"].optionalDouble(),
                                    sample["altitude"].optionalDouble(),
                                    sample["verticalAccuracy"].optionalDouble(),
                                    null,
                                    null,
                                    null,
                                    null,
                                )
                            )
                            ?.interval
                    }
                }
            fun totals(range: TimeRange): DistanceTotals {
                val reducer = DistanceReducer(range, null)
                intervals.forEach(reducer::append)
                return (reducer.result(range.endUs - range.startUs) as Bounded.Done).value
            }
            if (c["distanceQ"] != J.Null) {
                val whole = totals(TimeRange(0, Long.MAX_VALUE / 2))
                assertEquals(c["distanceQ"].long(), whole.distanceQ, "$id distanceQ")
                assertEquals(c["coveredUs"].long(), whole.coveredUs, "$id coveredUs")
            }
            for (range in c["ranges"].takeIf { it != J.Null }?.list().orEmpty()) {
                val clipped =
                    totals(
                        TimeRange(FixedPoint.timeUs(range["start"].double()), FixedPoint.timeUs(range["end"].double()))
                    )
                assertEquals(range["distanceQ"].long(), clipped.distanceQ, "$id range distanceQ")
                assertEquals(range["coveredUs"].long(), clipped.coveredUs, "$id range coveredUs")
            }
        }
    }

    @Test
    fun timeNormalizationAndItsIntegrationBoundariesAreTodays() {
        val c = delta("time-microsecond-boundaries")
        c["input"]["normalizationSeconds"].list().zip(c["expectedApproved"]["normalizationUs"].list()).forEach {
            (seconds, us) ->
            assertEquals(us.long(), FixedPoint.timeUs(seconds.double()), seconds.text())
        }
        collect(c["input"]["rides"].fields().keys.toList()) { name ->
            val input = c["input"]["rides"][name]
            val expected = c["expectedApproved"]["rides"][name]
            val ride = ContractRide(input)
            val metric = if (name.startsWith("hr-")) MetricId.heartRateBpm else MetricId.humanPowerW
            val statistics = ride.statistics(metric, TimeRange(0, ride.endUs + 1))
            assertEquals(expected["coveredSeconds"].double(), seconds(statistics.coveredUs), "$name coveredSeconds")
            assertEquals(expected["average"].optionalDouble(), statistics.timeAverage(metric), "$name average")
            if (metric == MetricId.humanPowerW)
                assertEquals(
                    expected["riderWorkJoules"].optionalDouble(),
                    statistics.integralQUs
                        ?.takeIf { statistics.coveredUs > 0 }
                        ?.let { FixedPoint.divide(it, 1_000_000_000) },
                    "$name riderWorkJoules",
                )
        }
    }

    @Test
    fun importedRiderWorkNeedsTwoReadings() {
        val c = delta("imported-work-availability")
        collect(c["input"]["samplesByCase"].fields().keys.toList()) { name ->
            val samples = c["input"]["samplesByCase"][name].list()
            val rows =
                TelemetryRows(
                    rows = samples.size,
                    elapsedUs =
                        DoubleArray(samples.size) {
                            FixedPoint.timeUs(samples[it]["elapsedSeconds"].double()).toDouble()
                        },
                    sessionNo = IntArray(samples.size) { ContractRide.SESSION },
                    seq = DoubleArray(samples.size) { it + 1.0 },
                    utcUs =
                        DoubleArray(samples.size) {
                            FixedPoint.timeUs(samples[it]["elapsedSeconds"].double()).toDouble()
                        },
                    activeInterval = IntArray(samples.size) { samples[it]["activeInterval"].long().toInt() + 1 },
                    run = IntArray(samples.size) { samples[it]["run"].long().toInt() + 1 },
                    quanta =
                        mapOf(
                            MetricId.humanPowerW to
                                DoubleArray(samples.size) { samples[it]["humanPowerW"].double() * 1_000 }
                        ),
                )
            val reducer = MetricReducer(MetricId.humanPowerW, TimeRange(0, 1_000_001))
            reducer.append(rows, 0, rows.rows, ContractRide(reference("means-and-brackets")["input"]).sessions)
            val statistics = (reducer.result() as Bounded.Done).value
            val work =
                statistics.integralQUs
                    ?.takeIf { statistics.coveredUs > 0 }
                    ?.let { FixedPoint.divide(it, 3_600_000_000_000) }
            assertEquals(c["expectedApproved"][name]["humanEnergyWh"].optionalDouble(), work, name)
        }
    }

    private companion object {
        const val ONE_CLOCK = "a ride has one clock, so clockEpoch is ignored (30 §7.2)"
        val retired =
            mapOf(
                "checkpoint-changed-epoch" to ONE_CLOCK,
                "gps-equal-time-epoch-reanchor" to ONE_CLOCK,
                "controller-equal-time-continuity-reanchor" to ONE_CLOCK,
                "controller-normalized-equal-time-orders-producer-sequence" to ONE_CLOCK,
                "recovery-interruption" to
                    "a reading at a Pause's instant has no interval (30 §3.2.3), so today's committed interruption close " +
                        "has no counterpart",
                "controller-quantize-after-integrating-and-direct-clip" to
                    "controller speed is stored as speedRaw in 0.01 km/h (30 §3.2.4), so 3.7500024 m/s is not held",
                "admission-quantization/1" to
                    "a range is half-open, so including the original at its end B (20 §3.7.2) also covers the first " +
                        "microsecond of the edge after it",
                "controller-exact-counter-checkpoint" to
                    "the store keeps no controller frame counter; a session's readings join by time (30 §3.2.4)",
            )
    }
}
