package app.powerlog.runtime.dev

import app.powerlog.application.history.ExampleRideSource
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.health.HealthOutcomeRules
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.Producer
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

internal class ExampleRideGenerator(private val longRide: () -> Boolean, private val recordedBy: () -> Device) :
    ExampleRideSource {
    private class Plan(
        val id: RideId,
        val startUtcUs: Long,
        val durationS: Int,
        val rate: SampleRate,
        val watch: Boolean,
    )

    private class State {
        var index = 0
        var wh = 0.0
        var ah = 0.0
        var mechanicalJoules = 0.0
        var heart = 105.0
        var latitude = 46.50
        var longitude = 11.35
        var previousHeading = 0.0
    }

    private val plans: List<Plan>
        get() =
            DURATIONS_S.mapIndexed { index, duration ->
                Plan(id(index), EPOCH_UTC_US - index * TWO_DAYS_US, duration, SampleRate.hz4, watch = true)
            } +
                if (longRide()) listOf(Plan(id(3), LONG_RIDE_UTC_US, LONG_RIDE_S, SampleRate.hz8, watch = false))
                else emptyList()

    private var cursor: Pair<RideId, Int>? = null
    private var state = State()

    override val rideIds: List<RideId>
        get() = plans.map { it.id }

    override fun batch(rideId: RideId, index: Int): WriteBatch? {
        val plan = plans.firstOrNull { it.id == rideId } ?: return null
        if (index == 0) {
            cursor = rideId to 0
            state = State()
            return opening(plan)
        }
        val rows = plan.durationS * plan.rate.hz + 1
        val first = (index - 1) * SECONDS_PER_BATCH * plan.rate.hz
        if (first >= rows) return null
        if (cursor != rideId to index - 1 || state.index != first) {
            state = State()
            while (state.index < first) sample(plan, state)
        }
        val last = minOf(rows, first + SECONDS_PER_BATCH * plan.rate.hz)
        val telemetry = mutableListOf<TelemetrySample>()
        val fixes = mutableListOf<LocationFix>()
        val health = mutableListOf<HealthSample>()
        while (state.index < last) {
            val reading = sample(plan, state)
            telemetry += reading.telemetry
            reading.fix?.let(fixes::add)
            health += reading.health
        }
        cursor = rideId to index
        val mutations = mutableListOf<Mutation>(Mutation.AppendTelemetry(rideId, TelemetryRows.of(telemetry)))
        if (fixes.isNotEmpty()) mutations += Mutation.AppendLocations(rideId, fixes)
        if (health.isNotEmpty()) mutations += Mutation.AppendHealthSamples(rideId, health)
        return WriteBatch(mutations)
    }

    private fun opening(plan: Plan): WriteBatch {
        val durationUs = plan.durationS * SECOND_US
        val end = RideTiming(plan.startUtcUs + durationUs, durationUs, durationUs)
        val options =
            RideOptions(
                plan.rate,
                indoor = false,
                useWatch = plan.watch,
                saveToHealth = false,
                recordGps = true,
                DistanceSource.phoneGps,
                BatteryMethod.controller,
            )
        val ride =
            Ride(
                plan.id,
                recordedBy(),
                example = true,
                plan.startUtcUs,
                options,
                RidePhase.completed,
                interrupted = false,
                checkpoint = end,
                end = end,
            )
        val events =
            listOf(
                    RideEventKind.start to 0L,
                    RideEventKind.lap to plan.durationS * plan.rate.hz / 2 * SECOND_US / plan.rate.hz,
                    RideEventKind.stop to durationUs,
                )
                .mapIndexed { index, (kind, elapsedUs) ->
                    RideEvent(EventId("${plan.id.value}:event:$index"), kind, plan.startUtcUs + elapsedUs, elapsedUs)
                }
        return WriteBatch(
            listOf(
                Mutation.CreateRide(ride, HealthOutcomeRules.initial(options, null)),
                Mutation.OpenControllerSession(
                    ControllerSession(plan.id, 1, IDENTITY, ControllerCapabilities(COLUMNS, controllerSpeed = true))
                ),
                Mutation.AppendEvents(plan.id, events),
            )
        )
    }

    private class Reading(val telemetry: TelemetrySample, val fix: LocationFix?, val health: List<HealthSample>)

    private fun sample(plan: Plan, state: State): Reading {
        val index = state.index++
        val hz = plan.rate.hz
        val duration = plan.durationS.toDouble()
        val t = index.toDouble() / hz
        val dt = 1.0 / hz
        val hill = max(0.0, sin((t - 420) / 325))
        val tempo = 0.5 + 0.5 * sin(t / 112)
        val surge = max(0.0, sin(t / 43)).pow(8)
        val lap = t % 660
        val coasting = lap > 585 && lap < 604
        val warmup = min(1.0, 0.50 + t / 600)
        val effort = max(0.0, (88 + 55 * hill + 28 * tempo + 71 * surge + 4 * sin(t * 1.7)) * warmup)
        val power = if (coasting) 0.0 else kotlin.math.round(effort)
        val cadence = if (coasting) 0.0 else kotlin.math.round(74 + 10 * tempo - 5 * hill + 3 * sin(t / 17))
        val motor = if (coasting) 0.0 else max(0.0, 135 + 270 * hill + 100 * tempo + 50 * surge + 12 * sin(t / 4))
        val speed = 6.05 + 0.7 * sin(t / 170) - 0.5 * hill + if (coasting) 0.45 else 0.0
        val voltage = 56.6 - 3.1 * t / duration - motor / 680
        val current = motor / voltage
        if (index > 0) {
            state.wh += motor * dt / 3600
            state.ah += current * dt / 3600
            state.mechanicalJoules += power * dt
        }
        val heading = 2 * PI * t / duration + 0.48 * sin(t / 245) + 0.14 * sin(t / 54)
        if (index > 0) {
            state.latitude += speed * dt * cos(state.previousHeading) / 111_320
            state.longitude += speed * dt * sin(state.previousHeading) / (111_320 * cos(state.latitude * PI / 180))
        }
        state.previousHeading = heading
        state.heart += ((109 + power * 0.19 + hill * 4) - state.heart) * dt / 28
        val torque = if (cadence > 0) power / (cadence * 2 * PI / 60) else 0.0
        val values =
            mapOf(
                MetricId.humanPowerW to power,
                MetricId.cadenceRpm to cadence,
                MetricId.motorInputPowerW to motor,
                MetricId.batteryVoltageV to voltage,
                MetricId.batteryCurrentA to current,
                MetricId.motorCurrentA to current * 1.65,
                MetricId.motorRpm to kotlin.math.round(cadence * 22),
                MetricId.pedalTorqueNm to torque,
                MetricId.controllerTempC to 24 + 12 * (1 - exp(-t / 820)) + hill * 2,
                MetricId.motorTempC to 24 + 25 * (1 - exp(-t / 730)) + hill * 7,
                MetricId.consumedAh to state.ah,
                MetricId.consumedWh to state.wh,
                MetricId.throttleVoltageV to 0.83,
                MetricId.faultCode to 0.0,
                MetricId.assistLevel to if (hill > 0.55) 3.0 else 2.0,
                MetricId.raceMode to 0.0,
                MetricId.speedRaw to speed * 3.6,
            )
        val elapsedUs = index * SECOND_US / hz
        val utcUs = plan.startUtcUs + elapsedUs
        val telemetry =
            TelemetrySample(
                plan.id,
                1,
                index + 1L,
                elapsedUs,
                utcUs,
                1,
                1,
                values.mapValues { (metric, value) -> (value * MetricCatalog.of(metric).quantum).roundToLong() },
            )
        if (index % hz != 0) return Reading(telemetry, null, emptyList())
        val second = index / hz
        val altitude = 480 + 78 * sin((t - 420) / 650) + 17 * sin(t / 125)
        val fix =
            LocationFix(
                rideId = plan.id,
                seq = second + 1L,
                elapsedUs = elapsedUs,
                utcUs = utcUs,
                activeInterval = 1,
                run = 1,
                barrier = false,
                latitude = state.latitude,
                longitude = state.longitude,
                horizontalAccuracyM = 3.2,
                altitudeM = altitude,
                altitudeDatum = AltitudeDatum.msl,
                verticalAccuracyM = 4.1,
                mslM = altitude,
                mslVerticalAccuracyM = 4.1,
                speedMps = speed,
                speedAccuracyMps = null,
                courseDeg = (heading % (2 * PI)) * 180 / PI,
                courseAccuracyDeg = null,
            )
        if (!plan.watch) return Reading(telemetry, fix, emptyList())
        fun health(metric: String, value: Double, cumulative: Boolean): HealthSample {
            val start = if (cumulative) plan.startUtcUs else utcUs
            return HealthSample(
                rideId = plan.id,
                producer = Producer.watch,
                sampleKey = "${plan.id.value}:$metric:$second",
                dataOrigin = HEALTH_DATA_ORIGIN,
                parentId = null,
                member = null,
                metric = HealthMetric(metric),
                representation =
                    if (cumulative) HealthRepresentation.cumulativeWorkoutTotal
                    else HealthRepresentation.builderMostRecent,
                value = value,
                sampleCount = null,
                utcUs = utcUs,
                elapsedUs = elapsedUs,
                startUtcUs = start,
                endUtcUs = utcUs,
                activeInterval = 1,
            )
        }
        return Reading(
            telemetry,
            fix,
            listOf(
                health("heartRateBpm", kotlin.math.round(state.heart), cumulative = false),
                health("activeEnergyKcal", state.mechanicalJoules / (4184 * 0.24), cumulative = true),
                health("basalEnergyKcal", t / 3600 * 63, cumulative = true),
            ),
        )
    }

    companion object {
        const val HEALTH_DATA_ORIGIN: String = "app.powerlog.mobile.watchkitapp"
        val DURATIONS_S: List<Int> = listOf(4_356, 2_838, 5_580)

        private const val SECOND_US = 1_000_000L
        private const val TWO_DAYS_US = 172_800L * SECOND_US
        private const val EPOCH_UTC_US = 1_788_679_800_000_000L
        private const val LONG_RIDE_UTC_US = 1_789_198_200_000_000L
        private const val LONG_RIDE_S = 8 * 3_600
        private const val SECONDS_PER_BATCH = 256
        private val IDENTITY = ControllerIdentity(DecoderId("cyc"), "X6", "example", "5.3")
        private val COLUMNS =
            setOf(
                MetricId.humanPowerW,
                MetricId.cadenceRpm,
                MetricId.motorInputPowerW,
                MetricId.batteryVoltageV,
                MetricId.batteryCurrentA,
                MetricId.motorCurrentA,
                MetricId.motorRpm,
                MetricId.pedalTorqueNm,
                MetricId.controllerTempC,
                MetricId.motorTempC,
                MetricId.consumedAh,
                MetricId.consumedWh,
                MetricId.throttleVoltageV,
                MetricId.faultCode,
                MetricId.assistLevel,
                MetricId.raceMode,
                MetricId.speedRaw,
            )

        fun id(index: Int): RideId = RideId("e8a00000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}")
    }
}
