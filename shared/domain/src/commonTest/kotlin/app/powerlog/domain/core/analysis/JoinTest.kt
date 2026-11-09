package app.powerlog.domain.core.analysis

import app.powerlog.domain.CONTRACT_FIXTURES
import app.powerlog.domain.analytics.J
import app.powerlog.domain.analytics.JsonParser
import app.powerlog.domain.analytics.readFixtureFile
import app.powerlog.domain.core.analysis.battery.BatteryConsumptionReducer
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.battery.ConsumptionReducer
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JoinTest {
    private val ride = RideId("5a1e0001-0000-4000-8000-0000000000aa")
    private val fixture = JsonParser(readFixtureFile("$CONTRACT_FIXTURES/domain/join.json")).parse()

    @Test
    fun theSixSecondJoinCasesSegmentAsTheFixtureSays() {
        for (case in fixture["cases"].list()) {
            val (rows, sessions) = ride(case)
            val intervals = ControllerDistanceReducer(null).append(rows, 0, rows.rows, sessions)
            assertEquals(
                case["segments"].long().toInt(),
                intervals.map { it.segment }.distinct().size,
                case["name"].text(),
            )
        }
    }

    @Test
    fun aCounterRiseAcrossALossIsOneIndivisibleIntervalCountedOnce() {
        for (case in fixture["cases"].list().filter { it["indivisible"] != J.Null }) {
            val name = case["name"].text()
            val (rows, sessions) = ride(case)
            val intervals = BatteryConsumptionReducer(null).append(rows, 0, rows.rows, sessions)
            val expected = case["indivisible"]
            val metric = ConsumptionMetric.valueOf(expected["metric"].text())
            val bridge = intervals.single {
                it.metric == metric && it.range == TimeRange(expected["startUs"].long(), expected["endUs"].long())
            }
            assertTrue(bridge.indivisible && !bridge.baseline, name)
            near(expected["amount"].double(), bridge.endValue - bridge.startValue, name)
            val total = ConsumptionReducer(metric, TimeRange(0, rows.elapsedUs.last().toLong() + 1), null)
            intervals.forEach(total::append)
            near(case["total"]["amount"].double(), total.result(0).amount, name)
        }
    }

    private fun ride(case: J): Pair<TelemetryRows, Map<Int, ControllerSession>> {
        val sessions =
            case["sessions"].fields().entries.associate { (number, name) ->
                val identity =
                    if (name == J.Null) null
                    else
                        fixture["identities"][name.text()].let {
                            ControllerIdentity(
                                DecoderId(it["decoder"].text()),
                                it["model"].text(),
                                it["firmware"].text(),
                                it["protocol"].text(),
                            )
                        }
                val capabilities =
                    if (identity == null) imported
                    else requireNotNull(ControllerDecoders.of(identity.decoder)).capabilities(identity)
                number.toInt() to ControllerSession(ride, number.toInt(), identity, capabilities)
            }
        val samples =
            case["readings"].list().mapIndexed { index, reading ->
                TelemetrySample(
                    ride,
                    reading["session"].long().toInt(),
                    index + 1L,
                    reading["atUs"].long(),
                    1_772_355_600_000_000 + reading["atUs"].long(),
                    reading["interval"].long().toInt(),
                    reading["run"].long().toInt(),
                    buildMap {
                        put(MetricId.speedRaw, SPEED_RAW)
                        reading["consumedWh"].optionalText()?.let { put(MetricId.consumedWh, it.toLong()) }
                        reading["consumedAh"].optionalText()?.let { put(MetricId.consumedAh, it.toLong()) }
                    },
                )
            }
        return TelemetryRows.of(samples) to sessions
    }

    private fun near(expected: Double, actual: Double, name: String) =
        assertTrue(abs(expected - actual) < 1e-12, "$name: $expected, not $actual")

    private companion object {
        const val SPEED_RAW = 2_000L
        val imported =
            ControllerCapabilities(
                setOf(MetricId.speedRaw, MetricId.controllerSpeedMps, MetricId.consumedWh, MetricId.consumedAh),
                true,
            )
    }
}
