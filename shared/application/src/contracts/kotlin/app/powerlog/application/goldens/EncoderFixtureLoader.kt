package app.powerlog.application.goldens

import app.powerlog.application.store.ArchiveWriter
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming

object EncoderFixtureLoader {
    suspend fun load(writer: ArchiveWriter, rideJson: String): GoldenLoad = loadGolden(writer) { read(rideJson) }

    private fun read(text: String): GoldenRide {
        val root = Fields("ride.json", parseJson(text, "ride.json"))
        root.requireKeys(ROOT)
        if (root.long("schema") != 1L) refuse("ride.json has schema ${root.long("schema")}, not 1")
        val context = root.obj("context")
        context.requireKeys(setOf("platform", "exportedAt"))
        val row = root.obj("ride")
        row.requireKeys(RIDE)
        val rideId = RideId(row.text("uuid"))
        return GoldenRide(
            platform = serialEnum(context.text("platform"), "ride.json context.platform"),
            exportedAt = context.text("exportedAt"),
            ride = ride(rideId, row),
            health =
                RideHealth(
                    provider =
                        row.textOrNull("health_provider")?.let {
                            serialEnum<HealthProviderKind>(it, "ride.json ride.health_provider")
                        },
                    outcome = serialEnum(row.text("health_outcome"), "ride.json ride.health_outcome"),
                    reason =
                        row.textOrNull("health_reason")?.let {
                            serialEnum<HealthReason>(it, "ride.json ride.health_reason")
                        },
                    workoutId = row.textOrNull("health_workout_id"),
                ),
            sessions = root.objects("controller_session").map { session(rideId, it) },
            telemetry = telemetry(root.objects("telemetry")),
            fixes = root.objects("location").map { fix(rideId, it) },
            samples = samples(rideId, root.objects("health_sample")),
            events = events(root.objects("ride_event")),
        )
    }

    private fun ride(rideId: RideId, row: Fields): Ride =
        Ride(
            id = rideId,
            recordedBy = serialEnum(row.text("recorded_by"), "ride.json ride.recorded_by"),
            example = row.flag("example"),
            startedUtcUs = row.long("started_utc_us"),
            options =
                RideOptions(
                    sampleRate =
                        SampleRate.entries.firstOrNull { it.hz == row.int("sample_hz") }
                            ?: refuse("ride.json ride.sample_hz is not 2, 4 or 8"),
                    indoor = row.flag("indoor"),
                    useWatch = row.flag("use_watch"),
                    saveToHealth = row.flag("save_to_health"),
                    recordGps = row.flag("record_gps"),
                    distanceSource = serialEnum(row.text("distance_source"), "ride.json ride.distance_source"),
                    batteryMethod = serialEnum(row.text("battery_method"), "ride.json ride.battery_method"),
                ),
            phase = phase(row.text("phase")),
            interrupted = row.flag("interrupted"),
            checkpoint = RideTiming(row.long("cp_utc_us"), row.long("cp_elapsed_us"), row.long("cp_timer_us")),
            end = end(row),
        )

    private fun phase(name: String): RidePhase = serialEnum(name, "ride.json ride.phase")

    private fun end(row: Fields): RideTiming? {
        val utcUs = row.longOrNull("end_utc_us")
        val elapsedUs = row.longOrNull("end_elapsed_us")
        val timerUs = row.longOrNull("end_timer_us")
        if (utcUs == null && elapsedUs == null && timerUs == null) return null
        if (utcUs == null || elapsedUs == null || timerUs == null) refuse("ride.json ride holds part of an end")
        return RideTiming(utcUs, elapsedUs, timerUs)
    }

    private fun session(rideId: RideId, row: Fields): ControllerSession {
        row.requireKeys(setOf("session_no", "decoder", "model", "firmware", "protocol"))
        return ControllerSession(
            rideId = rideId,
            sessionNo = row.int("session_no"),
            identity =
                ControllerIdentity(
                    DecoderId(row.text("decoder")),
                    row.text("model"),
                    row.text("firmware"),
                    row.text("protocol"),
                ),
            capabilities = UNSTORED_CAPABILITIES,
        )
    }

    private fun telemetry(rows: List<Fields>): TelemetryBuilder {
        val telemetry = TelemetryBuilder()
        val counts = DoubleArray(TELEMETRY_COLUMNS.size)
        for (row in rows) {
            row.requireKeys(TELEMETRY)
            TELEMETRY_COLUMNS.forEachIndexed { index, column ->
                counts[index] = row.longOrNull(column.stored)?.toDouble() ?: Double.NaN
            }
            telemetry.add(
                elapsedUs = row.long("elapsed_us"),
                sessionNo = row.int("session_no"),
                seq = row.long("seq"),
                utcUs = row.long("utc_us"),
                activeInterval = row.intOrNull("active_interval"),
                run = row.int("run"),
                counts = counts,
            )
        }
        return telemetry
    }

    private fun fix(rideId: RideId, row: Fields): LocationFix {
        row.requireKeys(LOCATION)
        return LocationFix(
            rideId = rideId,
            seq = row.long("seq"),
            elapsedUs = row.long("elapsed_us"),
            utcUs = row.long("utc_us"),
            activeInterval = row.intOrNull("active_interval"),
            run = row.int("run"),
            barrier = row.flag("barrier"),
            latitude = row.double("lat"),
            longitude = row.double("lon"),
            horizontalAccuracyM = row.doubleOrNull("h_acc_m"),
            altitudeM = row.doubleOrNull("altitude_m"),
            altitudeDatum = serialEnum(row.text("altitude_datum"), "ride.json location.altitude_datum"),
            verticalAccuracyM = row.doubleOrNull("v_acc_m"),
            mslM = row.doubleOrNull("msl_m"),
            mslVerticalAccuracyM = row.doubleOrNull("msl_v_acc_m"),
            speedMps = row.doubleOrNull("speed_mps"),
            speedAccuracyMps = row.doubleOrNull("speed_acc_mps"),
            courseDeg = row.doubleOrNull("course_deg"),
            courseAccuracyDeg = row.doubleOrNull("course_acc_deg"),
        )
    }

    private fun samples(rideId: RideId, rows: List<Fields>): List<HealthSample> {
        rows.zipWithNext().forEach { (previous, row) ->
            if (row.long("id") <= previous.long("id")) refuse("ride.json health_sample ids are not in insertion order")
        }
        return rows.map { row ->
            row.requireKeys(HEALTH)
            HealthSample(
                rideId = rideId,
                producer = serialEnum(row.text("producer"), "ride.json health_sample.producer"),
                sampleKey = row.text("sample_key"),
                dataOrigin = row.text("data_origin"),
                parentId = row.textOrNull("parent_id"),
                member = row.intOrNull("member"),
                metric = row.textOrNull("metric")?.let(::HealthMetric),
                representation = serialEnum(row.text("representation"), "ride.json health_sample.representation"),
                value = row.doubleOrNull("value"),
                sampleCount = row.intOrNull("sample_count"),
                utcUs = row.long("utc_us"),
                elapsedUs = row.long("elapsed_us"),
                startUtcUs = row.longOrNull("start_utc_us"),
                endUtcUs = row.longOrNull("end_utc_us"),
                activeInterval = row.intOrNull("active_interval"),
            )
        }
    }

    private fun events(rows: List<Fields>): List<RideEvent> = rows.mapIndexed { index, row ->
        row.requireKeys(setOf("seq", "kind", "utc_us", "elapsed_us"))
        if (row.long("seq") != index + 1L) refuse("ride.json ride_event ${index + 1} has seq ${row.long("seq")}")
        RideEvent(
            id = newEventId(),
            kind = serialEnum(row.text("kind"), "ride.json ride_event.kind"),
            utcUs = row.long("utc_us"),
            elapsedUs = row.long("elapsed_us"),
        )
    }

    private val ROOT =
        setOf("schema", "context", "ride", "controller_session", "ride_event", "telemetry", "location", "health_sample")

    private val RIDE =
        setOf(
            "uuid",
            "recorded_by",
            "example",
            "started_utc_us",
            "sample_hz",
            "indoor",
            "use_watch",
            "save_to_health",
            "record_gps",
            "distance_source",
            "battery_method",
            "phase",
            "interrupted",
            "cp_utc_us",
            "cp_elapsed_us",
            "cp_timer_us",
            "end_utc_us",
            "end_elapsed_us",
            "end_timer_us",
            "health_provider",
            "health_outcome",
            "health_reason",
            "health_workout_id",
        )

    private val TELEMETRY =
        setOf("elapsed_us", "session_no", "seq", "utc_us", "active_interval", "run") +
            TELEMETRY_COLUMNS.map { it.stored }

    private val LOCATION =
        setOf(
            "elapsed_us",
            "seq",
            "utc_us",
            "active_interval",
            "run",
            "barrier",
            "lat",
            "lon",
            "h_acc_m",
            "altitude_m",
            "altitude_datum",
            "v_acc_m",
            "msl_m",
            "msl_v_acc_m",
            "speed_mps",
            "speed_acc_mps",
            "course_deg",
            "course_acc_deg",
        )

    private val HEALTH =
        setOf(
            "id",
            "elapsed_us",
            "producer",
            "sample_key",
            "utc_us",
            "start_utc_us",
            "end_utc_us",
            "active_interval",
            "metric",
            "value",
            "representation",
            "sample_count",
            "data_origin",
            "parent_id",
            "member",
        )
}
