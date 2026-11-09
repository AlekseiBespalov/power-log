package app.powerlog.application.goldens

import app.powerlog.application.store.Mutation
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoldenRideTest {
    private val id = RideId("00000000-0000-4000-8000-000000000001")
    private val power = TELEMETRY_COLUMNS.indexOfFirst { it.metric == MetricId.humanPowerW }

    @Test
    fun theRideAndItsSessionsComeFirstThenAtMost4096RowsOfOneKindPerBatch() {
        val telemetry = TelemetryBuilder()
        repeat(4_097) { telemetry.add(it * 125_000L, 1, it + 1L, it.toLong(), 1, 1, counts(it.toDouble())) }
        val batches = golden(telemetry, fixes = 2).batches()
        assertEquals(
            listOf(
                listOf("CreateRide", "OpenControllerSession"),
                listOf("AppendTelemetry"),
                listOf("AppendTelemetry"),
            ) + listOf("AppendLocations", "AppendHealthSamples", "AppendEvents").map(::listOf),
            batches.map { batch -> batch.mutations.map { it::class.simpleName } },
        )
        val rows = batches.flatMap { it.mutations }.filterIsInstance<Mutation.AppendTelemetry>().map { it.rows }
        assertEquals(listOf(4_096, 1), rows.map { it.rows })
        assertEquals(4_097.0, rows[1].seq.single())
        assertEquals(4_096.0, rows[1].quanta.getValue(MetricId.humanPowerW).single())
        assertEquals(setOf(MetricId.humanPowerW), rows[0].quanta.keys)
    }

    @Test
    fun rowsOutOfKeyOrderOrNamingAMissingSessionAreRefused() {
        val outOfOrder = TelemetryBuilder()
        outOfOrder.add(1_000, 1, 1, 0, 1, 1, counts(1.0))
        outOfOrder.add(1_000, 1, 1, 0, 1, 1, counts(2.0))
        assertEquals(
            "telemetry row 2 is not in key order",
            assertFailsWith<GoldenRefusal> { golden(outOfOrder).checkKeys() }.message,
        )
        val unknown = TelemetryBuilder()
        unknown.add(0, 2, 1, 0, 1, 1, counts(1.0))
        assertEquals(
            "telemetry row 1 names controller session 2, which the ride lacks",
            assertFailsWith<GoldenRefusal> { golden(unknown).checkKeys() }.message,
        )
        assertEquals(
            "location seq 1 is not in key order",
            assertFailsWith<GoldenRefusal> { golden(TelemetryBuilder(), fixes = 2, reversedFixes = true).checkKeys() }
                .message,
        )
    }

    private fun counts(power: Double): DoubleArray =
        DoubleArray(TELEMETRY_COLUMNS.size) { if (it == this.power) power else Double.NaN }

    private fun golden(telemetry: TelemetryBuilder, fixes: Int = 0, reversedFixes: Boolean = false): GoldenRide {
        val located = List(fixes) { fix(it + 1L, it * 1_000_000L) }
        return GoldenRide(
            platform = Platform.ios,
            exportedAt = "2026-09-09T00:00:30.000Z",
            ride =
                Ride(
                    id = id,
                    recordedBy = Device.iphone,
                    example = false,
                    startedUtcUs = 0,
                    options =
                        RideOptions(
                            SampleRate.hz8,
                            false,
                            false,
                            true,
                            true,
                            DistanceSource.controller,
                            BatteryMethod.controller,
                        ),
                    phase = RidePhase.completed,
                    interrupted = false,
                    checkpoint = RideTiming(1, 1, 1),
                    end = RideTiming(1, 1, 1),
                ),
            health = RideHealth(null, HealthOutcome.notRequested, null, null),
            sessions =
                listOf(
                    ControllerSession(
                        id,
                        1,
                        ControllerIdentity(DecoderId("cyc"), "X6", "1", "5.3"),
                        UNSTORED_CAPABILITIES,
                    )
                ),
            telemetry = telemetry,
            fixes = if (reversedFixes) located.reversed() else located,
            samples = listOf(sample()),
            events = listOf(RideEvent(EventId("00000000-0000-4000-9000-000000000001"), RideEventKind.start, 0, 0)),
        )
    }

    private fun fix(seq: Long, elapsedUs: Long) =
        LocationFix(
            id,
            seq,
            elapsedUs,
            elapsedUs,
            1,
            1,
            false,
            45.5,
            9.2,
            4.0,
            null,
            AltitudeDatum.msl,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
        )

    private fun sample() =
        HealthSample(
            id,
            Producer.watch,
            "k",
            "origin",
            null,
            null,
            HealthMetric("heartRateBpm"),
            HealthRepresentation.rawQuantity,
            120.0,
            null,
            0,
            0,
            0,
            0,
            1,
        )
}
