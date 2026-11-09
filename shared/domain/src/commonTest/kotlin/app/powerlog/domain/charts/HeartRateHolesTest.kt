package app.powerlog.domain.charts

import app.powerlog.domain.CONTRACT_FIXTURES
import app.powerlog.domain.analytics.JsonParser
import app.powerlog.domain.analytics.readFixtureFile
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HeartRateHolesTest {
    private val fixture = JsonParser(readFixtureFile("$CONTRACT_FIXTURES/domain/heart-rate-holes.json")).parse()

    @Test
    fun heartRateLinesBreakAtHolesOfThirtySeconds() {
        val metric = MetricId.valueOf(fixture["metric"].text())
        val (start, end) = fixture["rangeUs"].list().map { it.long() }
        for (case in fixture["cases"].list()) {
            val plot = PlotReducer(metric, TimeRange(start, end), null, 200)
            for (atUs in case["samplesUs"].list().map { it.long() }) plot.append(Fact.Health(sample(atUs)))
            val points = assertIs<Bounded.Done<List<PlotPoint>>>(plot.result()).value
            assertEquals(
                case["segments"].list().map { it.long().toInt() },
                points.map { it.segment },
                case["name"].text(),
            )
        }
    }

    private fun sample(atUs: Long) =
        HealthSample(
            RideId("5a1e0001-0000-4000-8000-0000000000ff"),
            Producer.watch,
            "hr-$atUs",
            "app.powerlog.tests",
            null,
            null,
            HealthMetric("heartRateBpm"),
            HealthRepresentation.rawQuantity,
            120.0,
            1,
            1_772_355_600_000_000 + atUs,
            atUs,
            null,
            null,
            1,
        )
}
