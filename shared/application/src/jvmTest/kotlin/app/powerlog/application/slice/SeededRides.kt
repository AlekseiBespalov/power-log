package app.powerlog.application.slice

import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.health.HealthOutcomeRules
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming
import kotlin.math.roundToLong

class SeededRide(val ride: Ride, val samples: List<TelemetrySample>, val fixes: List<LocationFix>)

val seededColumns: Set<MetricId> =
    setOf(
        MetricId.humanPowerW,
        MetricId.cadenceRpm,
        MetricId.speedRaw,
        MetricId.batteryVoltageV,
        MetricId.batteryCurrentA,
        MetricId.consumedWh,
    )

suspend fun Scenario.seedRide(
    id: String = "seeded-stored-ride",
    phase: RidePhase = RidePhase.completed,
    endUs: Long,
    readingsUs: List<Long>,
    columns: Set<MetricId> = seededColumns,
    distance: DistanceSource = DistanceSource.controller,
    fixesUs: List<Long> = emptyList(),
    events: List<Pair<RideEventKind, Long>> = emptyList(),
    recordedBy: Device = Device.iphone,
    provider: HealthProviderKind? = null,
    value: (MetricId, Long) -> Double? = { metric, elapsedUs -> seededValue(metric, elapsedUs) },
): SeededRide {
    val rideId = RideId(id)
    val startedUtcUs = Scenario.START_UTC_US
    val options =
        RideOptions(
            SampleRate.hz4,
            indoor = fixesUs.isEmpty(),
            useWatch = false,
            saveToHealth = provider != null,
            recordGps = fixesUs.isNotEmpty(),
            distance,
            BatteryMethod.controller,
        )
    val preparing =
        Ride(
            rideId,
            recordedBy,
            example = false,
            startedUtcUs,
            options,
            RidePhase.preparing,
            interrupted = false,
            RideTiming(startedUtcUs, 0, 0),
            end = null,
        )
    val samples = readingsUs.mapIndexed { index, elapsedUs ->
        TelemetrySample(
            rideId,
            1,
            index + 1L,
            elapsedUs,
            startedUtcUs + elapsedUs,
            1,
            1,
            columns
                .mapNotNull { metric ->
                    value(metric, elapsedUs)?.let { metric to (it * MetricCatalog.of(metric).quantum).roundToLong() }
                }
                .toMap(),
        )
    }
    val fixes = fixesUs.mapIndexed { index, elapsedUs ->
        LocationFix(
            rideId = rideId,
            seq = index + 1L,
            elapsedUs = elapsedUs,
            utcUs = startedUtcUs + elapsedUs,
            activeInterval = 1,
            run = 1,
            barrier = false,
            latitude = 40.0 + elapsedUs / 1_000_000.0 * 5e-5,
            longitude = -3.5,
            horizontalAccuracyM = 4.0,
            altitudeM = 652.0,
            altitudeDatum = AltitudeDatum.wgs84Ellipsoid,
            verticalAccuracyM = 6.0,
            mslM = 600.0 + elapsedUs / 10_000_000.0,
            mslVerticalAccuracyM = 3.0,
            speedMps = 5.5,
            speedAccuracyMps = 0.5,
            courseDeg = 0.0,
            courseAccuracyDeg = 5.0,
        )
    }
    val timeline =
        listOf(RideEventKind.start to 0L) +
            events +
            if (phase == RidePhase.completed) listOf(RideEventKind.stop to endUs) else emptyList()
    val rideEvents = timeline.mapIndexed { index, (kind, elapsedUs) ->
        RideEvent(EventId("$id-event-$index"), kind, startedUtcUs + elapsedUs, elapsedUs)
    }
    val end = RideTiming(startedUtcUs + endUs, endUs, endUs)
    val batches =
        mutableListOf(
            WriteBatch(
                listOf(Mutation.CreateRide(preparing, HealthOutcomeRules.initial(options, provider))) +
                    listOfNotNull(
                        HealthOutcomeRules.initialComponents(options)
                            .map {
                                HealthWork(
                                    rideId,
                                    HealthWorkScope.Publish(it.component),
                                    it.state,
                                    0,
                                    null,
                                    null,
                                    it.reason,
                                )
                            }
                            .takeIf { it.isNotEmpty() }
                            ?.let(Mutation::PutHealthWork)
                    )
            ),
            WriteBatch(
                listOf(
                    Mutation.SetPhase(rideId, RidePhase.running),
                    Mutation.OpenControllerSession(
                        ControllerSession(
                            rideId,
                            1,
                            seededIdentity,
                            ControllerCapabilities(columns, MetricId.speedRaw in columns),
                        )
                    ),
                    Mutation.AppendEvents(rideId, rideEvents),
                )
            ),
        )
    samples.chunked(512).forEach {
        batches += WriteBatch(listOf(Mutation.AppendTelemetry(rideId, TelemetryRows.of(it))))
    }
    if (fixes.isNotEmpty()) batches += WriteBatch(listOf(Mutation.AppendLocations(rideId, fixes)))
    batches +=
        WriteBatch(
            when (phase) {
                RidePhase.completed -> listOf(Mutation.EndRide(rideId, end), Mutation.SetPhase(rideId, phase))
                RidePhase.running -> listOf(Mutation.Checkpoint(rideId, end))
                else -> listOf(Mutation.Checkpoint(rideId, end), Mutation.SetPhase(rideId, phase))
            }
        )
    archive.seed(*batches.toTypedArray())
    return SeededRide(
        preparing.copy(phase = phase, checkpoint = end, end = end.takeIf { phase == RidePhase.completed }),
        samples,
        fixes,
    )
}

val seededIdentity = ControllerIdentity(DecoderId("cyc"), "X6", "20260101", "5.3")

fun seededValue(metric: MetricId, elapsedUs: Long): Double? {
    val second = elapsedUs / 1_000_000.0
    return when (metric) {
        MetricId.humanPowerW -> 150.0 + (elapsedUs / 250_000 % 7) * 10.0
        MetricId.cadenceRpm -> 80.0
        MetricId.speedRaw -> 20.0
        MetricId.batteryVoltageV -> 48.0
        MetricId.batteryCurrentA -> 5.0
        MetricId.consumedWh -> second * 0.1
        else -> null
    }
}
