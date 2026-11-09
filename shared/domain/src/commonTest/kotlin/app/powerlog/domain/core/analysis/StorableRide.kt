package app.powerlog.domain.core.analysis

import app.powerlog.domain.CONTRACT_FIXTURES
import app.powerlog.domain.analytics.J
import app.powerlog.domain.analytics.JsonParser
import app.powerlog.domain.analytics.readFixtureFile
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
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
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming

internal class StorableRide(val name: String) {
    private val root = json("ride.json")

    val platform: Platform = Platform.valueOf(root["context"]["platform"].text())
    val ride: Ride = ride(root["ride"])
    val sessions: Map<Int, ControllerSession> =
        root["controller_session"].list().associate { row ->
            val identity =
                ControllerIdentity(
                    DecoderId(row["decoder"].text()),
                    row["model"].text(),
                    row["firmware"].text(),
                    row["protocol"].text(),
                )
            val number = row["session_no"].long().toInt()
            number to
                ControllerSession(
                    ride.id,
                    number,
                    identity,
                    requireNotNull(ControllerDecoders.of(identity.decoder)).capabilities(identity),
                )
        }
    val telemetry: TelemetryRows = TelemetryRows.of(root["telemetry"].list().map(::sample))
    val fixes: List<LocationFix> = root["location"].list().map(::fix)
    val events: List<RideEvent> = root["ride_event"].list().map(::event)
    val stored: List<HealthSample> = root["health_sample"].list().map(::health)
    val samples: List<HealthSample> =
        ActiveTimeline(requireNotNull(ride.end).elapsedUs, events).let { timeline ->
            stored.map { it.copy(activeInterval = timeline.intervalAt(it.elapsedUs)) }
        }

    fun page(projection: String): Map<String, List<J>> {
        val pages = json("pages/$projection.json").list()
        val names = pages.first()["columns"].fields().keys
        return names.associateWith { name -> pages.flatMap { it["columns"][name].list() } }
    }

    private fun json(file: String): J =
        JsonParser(readFixtureFile("$CONTRACT_FIXTURES/../export/storable/$name/$file")).parse()

    private fun ride(row: J): Ride =
        Ride(
            id = RideId(row["uuid"].text()),
            recordedBy = Device.valueOf(row["recorded_by"].text()),
            example = flag(row["example"]),
            startedUtcUs = row["started_utc_us"].long(),
            options =
                RideOptions(
                    sampleRate = SampleRate.entries.first { it.hz.toLong() == row["sample_hz"].long() },
                    indoor = flag(row["indoor"]),
                    useWatch = flag(row["use_watch"]),
                    saveToHealth = flag(row["save_to_health"]),
                    recordGps = flag(row["record_gps"]),
                    distanceSource =
                        if (row["distance_source"].text() == "gps:phone") DistanceSource.phoneGps
                        else DistanceSource.controller,
                    batteryMethod = BatteryMethod.valueOf(row["battery_method"].text()),
                ),
            phase = RidePhase.valueOf(row["phase"].text()),
            interrupted = flag(row["interrupted"]),
            checkpoint = RideTiming(row["cp_utc_us"].long(), row["cp_elapsed_us"].long(), row["cp_timer_us"].long()),
            end = RideTiming(row["end_utc_us"].long(), row["end_elapsed_us"].long(), row["end_timer_us"].long()),
        )

    private fun sample(row: J): TelemetrySample =
        TelemetrySample(
            ride.id,
            row["session_no"].long().toInt(),
            row["seq"].long(),
            row["elapsed_us"].long(),
            row["utc_us"].long(),
            row["active_interval"].optionalText()?.toInt(),
            row["run"].long().toInt(),
            row.fields()
                .entries
                .filter { it.key !in TELEMETRY_KEYS && it.value != J.Null }
                .associate { (key, value) -> MetricId.valueOf(camel(key)) to value.long() },
        )

    private fun fix(row: J): LocationFix =
        LocationFix(
            rideId = ride.id,
            seq = row["seq"].long(),
            elapsedUs = row["elapsed_us"].long(),
            utcUs = row["utc_us"].long(),
            activeInterval = row["active_interval"].optionalText()?.toInt(),
            run = row["run"].long().toInt(),
            barrier = flag(row["barrier"]),
            latitude = row["lat"].double(),
            longitude = row["lon"].double(),
            horizontalAccuracyM = row["h_acc_m"].optionalDouble(),
            altitudeM = row["altitude_m"].optionalDouble(),
            altitudeDatum = AltitudeDatum.valueOf(row["altitude_datum"].text()),
            verticalAccuracyM = row["v_acc_m"].optionalDouble(),
            mslM = row["msl_m"].optionalDouble(),
            mslVerticalAccuracyM = row["msl_v_acc_m"].optionalDouble(),
            speedMps = row["speed_mps"].optionalDouble(),
            speedAccuracyMps = row["speed_acc_mps"].optionalDouble(),
            courseDeg = row["course_deg"].optionalDouble(),
            courseAccuracyDeg = row["course_acc_deg"].optionalDouble(),
        )

    private fun health(row: J): HealthSample =
        HealthSample(
            rideId = ride.id,
            producer = Producer.valueOf(row["producer"].text()),
            sampleKey = row["sample_key"].text(),
            dataOrigin = row["data_origin"].text(),
            parentId = row["parent_id"].optionalText(),
            member = row["member"].optionalText()?.toInt(),
            metric = row["metric"].optionalText()?.let(::HealthMetric),
            representation = HealthRepresentation.valueOf(row["representation"].text()),
            value = row["value"].optionalDouble(),
            sampleCount = row["sample_count"].optionalText()?.toInt(),
            utcUs = row["utc_us"].long(),
            elapsedUs = row["elapsed_us"].long(),
            startUtcUs = row["start_utc_us"].optionalText()?.toLong(),
            endUtcUs = row["end_utc_us"].optionalText()?.toLong(),
            activeInterval = row["active_interval"].optionalText()?.toInt(),
        )

    private fun event(row: J): RideEvent =
        RideEvent(
            EventId("00000000-0000-4000-8000-${row["seq"].long().toString().padStart(12, '0')}"),
            RideEventKind.valueOf(row["kind"].text()),
            row["utc_us"].long(),
            row["elapsed_us"].long(),
        )

    private fun flag(value: J): Boolean = value.long() == 1L

    private fun camel(snake: String): String =
        snake
            .split('_')
            .mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar { it.uppercase() } }
            .joinToString("")

    companion object {
        val names =
            listOf("ios-gps-distance", "ios-controller-distance", "android-gps-distance", "android-controller-distance")

        private val TELEMETRY_KEYS = setOf("elapsed_us", "session_no", "seq", "utc_us", "active_interval", "run")
    }
}

internal fun List<J>.doubles(): List<Double?> = map { if (it == J.Null) null else it.double() }
