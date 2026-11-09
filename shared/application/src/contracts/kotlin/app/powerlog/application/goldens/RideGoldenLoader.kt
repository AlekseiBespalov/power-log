package app.powerlog.application.goldens

import app.powerlog.application.store.ArchiveWriter
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.Micros
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.domain.core.time.Utc
import app.powerlog.domain.exports.ExportPrint
import app.powerlog.domain.health.HealthSamples
import kotlin.math.abs
import kotlin.math.round

interface RideGoldenFiles {
    val entries: List<String>

    fun text(entry: String): String

    val sidecar: String
}

class RideGoldenLoader(private val healthMetric: (platform: Platform, printed: String) -> HealthMetric?) {
    suspend fun load(writer: ArchiveWriter, files: RideGoldenFiles): GoldenLoad = loadGolden(writer) { read(files) }

    private fun read(files: RideGoldenFiles): GoldenRide {
        val sidecar = Fields("sidecar.json", parseJson(files.sidecar, "sidecar.json"))
        sidecar.requireKeys(SIDECAR)
        if (sidecar.long("schema") != 1L) refuse("sidecar.json has schema ${sidecar.long("schema")}, not 1")
        val platform = serialEnum<Platform>(sidecar.text("platform"), "sidecar.json platform")
        val rideId = RideId(sidecar.text("rideId"))
        val tables = Tables(files)
        val pkg = tables.datapackage
        if (pkg.text("id") != rideId.value) refuse("datapackage.json id is not the sidecar's ride")
        val meta = pkg.obj("powerLog:ride")
        meta.requireKeys(RIDE)
        val ride = ride(rideId, platform, meta, sidecar.text("distanceSource"))
        val sources = pkg.obj("powerLog:sources")
        sources.requireKeys(setOf("connections", "gps", "health"))
        val sessions = sessions(rideId, sources.objects("connections"), sidecar.objects("connections"))
        val fixes = fixes(rideId, tables.table("gps"), sidecar.objects("gpsBarriers"))
        val healthRows = samples(ride, platform, tables.table("health"), sidecar.objects("healthRepresentations"))
        if (sources.texts("gps") != (if (fixes.isEmpty()) emptyList() else listOf(Producer.phone.name)))
            refuse("datapackage.json powerLog:sources.gps is not what the stored fixes print")
        val producers =
            sources.texts("health").map { serialEnum<Producer>(it, "datapackage.json powerLog:sources.health") }
        if (producers.toSet() != healthRows.samples.map { it.producer }.toSet())
            refuse("datapackage.json powerLog:sources.health is not the stored samples' producers")
        return GoldenRide(
            platform = platform,
            exportedAt = pkg.text("created"),
            ride = ride,
            health = rideHealth(meta.obj("health")),
            sessions = sessions,
            telemetry = telemetry(tables.table("telemetry"), sessions.size),
            fixes = fixes,
            samples = healthRows.samples,
            events = events(platform, tables.table("events")),
            droppedHealthLines = healthRows.dropped,
        )
    }

    private class Tables(private val files: RideGoldenFiles) {
        private val packageEntry =
            files.entries.singleOrNull { it == DATAPACKAGE || it.endsWith("/$DATAPACKAGE") }
                ?: refuse("the ZIP holds no single $DATAPACKAGE")
        private val folder = packageEntry.removeSuffix(DATAPACKAGE)

        val datapackage =
            Fields(DATAPACKAGE, parseJson(files.text(packageEntry), DATAPACKAGE)).apply { requireKeys(PACKAGE) }

        private val paths = datapackage.objects("resources").associate { it.text("name") to it.text("path") }

        init {
            if (paths.keys != RESOURCES.keys) refuse("$DATAPACKAGE lists the resources ${paths.keys.sorted()}")
            val unlisted = files.entries.toSet() - packageEntry - paths.values.map { folder + it }.toSet()
            if (unlisted.isNotEmpty()) refuse("the ZIP holds entries no resource lists: ${unlisted.sorted()}")
        }

        fun table(resource: String): CsvTable {
            val path = paths.getValue(resource)
            if (folder + path !in files.entries) refuse("the ZIP lacks $path")
            return CsvTable(path, files.text(folder + path)).apply { requireColumns(RESOURCES.getValue(resource)) }
        }
    }

    private fun ride(rideId: RideId, platform: Platform, meta: Fields, distanceSource: String): Ride {
        val owner = meta.obj("ownerTiming")
        owner.requireKeys(setOf("timestamp", "elapsedSeconds", "timerSeconds"))
        val checkpoint =
            RideTiming(
                utcUs = utc(owner.text("timestamp"), "datapackage.json ownerTiming.timestamp"),
                elapsedUs = Micros.of(owner.double("elapsedSeconds")),
                timerUs = Micros.of(owner.double("timerSeconds")),
            )
        return Ride(
            id = rideId,
            recordedBy = recorder(platform),
            example = meta.bool("example"),
            startedUtcUs = utc(meta.text("startedAt"), "datapackage.json startedAt"),
            options =
                RideOptions(
                    sampleRate =
                        SampleRate.entries.firstOrNull { it.hz == meta.intOrNull("sampleHz") }
                            ?: refuse("datapackage.json sampleHz is not 2, 4 or 8"),
                    indoor = meta.bool("indoor"),
                    useWatch = meta.bool("watchEnabled"),
                    saveToHealth = meta.bool("saveToHealth"),
                    recordGps = meta.bool("recordGPS"),
                    distanceSource = serialEnum<DistanceSource>(distanceSource, "sidecar.json distanceSource"),
                    batteryMethod =
                        serialEnum<BatteryMethod>(
                            meta.text("batteryConsumptionSource"),
                            "datapackage.json batteryConsumptionSource",
                        ),
                ),
            phase = RidePhase.completed,
            interrupted = meta.bool("interrupted"),
            checkpoint = checkpoint,
            end =
                RideTiming(
                    utcUs = utc(meta.text("endedAt"), "datapackage.json endedAt"),
                    elapsedUs = Micros.of(meta.double("elapsedSeconds")),
                    timerUs = checkpoint.timerUs,
                ),
        )
    }

    private fun rideHealth(health: Fields): RideHealth {
        health.requireKeys(setOf("provider", "state", "workoutUUID", "export"))
        return RideHealth(
            provider =
                health.textOrNull("provider")?.let {
                    serialEnum<HealthProviderKind>(it, "datapackage.json health.provider")
                },
            outcome = serialEnum<HealthOutcome>(health.text("state"), "datapackage.json health.state"),
            reason = null,
            workoutId = health.textOrNull("workoutUUID"),
        )
    }

    private fun sessions(rideId: RideId, connections: List<Fields>, decoders: List<Fields>): List<ControllerSession> {
        val decoderOf = decoders.associate { it.int("connection") to it.text("decoder") }
        if (decoderOf.keys != connections.map { it.int("connection") }.toSet())
            refuse("sidecar.json connections are not the datapackage.json connections")
        return connections.map { connection ->
            connection.requireKeys(setOf("connection", "vendor", "model", "firmware", "protocol"))
            val number = connection.int("connection")
            ControllerSession(
                rideId = rideId,
                sessionNo = number,
                identity =
                    ControllerIdentity(
                        decoder = DecoderId(decoderOf.getValue(number)),
                        model = connection.text("model"),
                        firmware = connection.text("firmware"),
                        protocol = connection.text("protocol"),
                    ),
                capabilities = UNSTORED_CAPABILITIES,
            )
        }
    }

    private fun telemetry(table: CsvTable, sessions: Int): TelemetryBuilder {
        val quanta = TELEMETRY_COLUMNS.map { MetricCatalog.of(it.metric).quantum.toDouble() }
        val telemetry = TelemetryBuilder()
        val counts = DoubleArray(TELEMETRY_COLUMNS.size)
        val seqs = LongArray(sessions)
        for (row in table.rows()) {
            val session = row.int("connection")
            if (session !in 1..sessions) refuse("${row.where("connection")} names no connection of the ride")
            TELEMETRY_COLUMNS.forEachIndexed { index, column ->
                val value = row.doubleOrNull(column.printed)
                counts[index] =
                    if (value == null) Double.NaN
                    else quantized(value, quanta[index]) ?: refuse("${row.where(column.printed)} is off its quantum")
            }
            telemetry.add(
                elapsedUs = Micros.of(row.double("elapsedSeconds")),
                sessionNo = session,
                seq = ++seqs[session - 1],
                utcUs = utc(row.text("timestamp"), row.where("timestamp")),
                activeInterval = row.intOrNull("activeInterval"),
                run = row.int("run"),
                counts = counts,
            )
        }
        return telemetry
    }

    private fun quantized(value: Double, quantum: Double): Double? {
        val count = round(value * quantum)
        return count.takeIf { abs(it) <= MAX_SAFE_INTEGER && it / quantum == value }
    }

    private fun fixes(rideId: RideId, table: CsvTable, barriers: List<Fields>): List<LocationFix> {
        val barrierAt = barriers.associate { it.long("seq") to it.long("elapsedUs") }
        val fixes =
            table
                .rows()
                .mapIndexed { index, row ->
                    val seq = index + 1L
                    if (row.text("source") != Producer.phone.name) refuse("${row.where("source")} is not phone")
                    val elapsedUs = Micros.of(row.double("elapsedSeconds"))
                    val barrier = barrierAt[seq]
                    if (barrier != null && barrier != elapsedUs)
                        refuse("sidecar.json gpsBarriers seq $seq is not at ${row.where("elapsedSeconds")}")
                    LocationFix(
                        rideId = rideId,
                        seq = seq,
                        elapsedUs = elapsedUs,
                        utcUs = utc(row.text("timestamp"), row.where("timestamp")),
                        activeInterval = row.intOrNull("activeInterval"),
                        run = row.int("run"),
                        barrier = barrier != null,
                        latitude = row.double("latitude"),
                        longitude = row.double("longitude"),
                        horizontalAccuracyM = row.doubleOrNull("horizontalAccuracyM"),
                        altitudeM = row.doubleOrNull("originalAltitudeMeters"),
                        altitudeDatum =
                            serialEnum(row.text("originalAltitudeDatum"), row.where("originalAltitudeDatum")),
                        verticalAccuracyM = row.doubleOrNull("originalVerticalAccuracyM"),
                        mslM = row.doubleOrNull("altitudeMeters"),
                        mslVerticalAccuracyM = row.doubleOrNull("verticalAccuracyM"),
                        speedMps = row.doubleOrNull("speedMps"),
                        speedAccuracyMps = row.doubleOrNull("speedAccuracyMps"),
                        courseDeg = row.doubleOrNull("courseDegrees"),
                        courseAccuracyDeg = row.doubleOrNull("courseAccuracyDegrees"),
                    )
                }
                .toList()
        if (barrierAt.keys.any { it !in 1..fixes.size }) refuse("sidecar.json gpsBarriers names a fix gps.csv lacks")
        return fixes
    }

    private class HealthRows(val samples: List<HealthSample>, val dropped: List<Int>)

    private fun samples(ride: Ride, platform: Platform, table: CsvTable, representations: List<Fields>): HealthRows {
        val representationAt = representations.associate {
            it.int("line") to
                serialEnum<HealthRepresentation>(it.text("representation"), "sidecar.json healthRepresentations")
        }
        val samples = mutableListOf<HealthSample>()
        val dropped = mutableListOf<Int>()
        var lastLine = 1
        for (row in table.rows()) {
            lastLine = row.line
            val representation =
                representationAt[row.line] ?: refuse("sidecar.json has no representation for ${row.where("kind")}")
            val metric = healthMetric(platform, row.text("metric")) ?: refuse("${row.where("metric")} is not stored")
            val startUtcUs = utc(row.text("startTimestamp"), row.where("startTimestamp"))
            val endUtcUs = utc(row.text("originalEndTimestamp"), row.where("originalEndTimestamp"))
            if (!HealthSamples.inRide(ride, metric, startUtcUs, endUtcUs)) {
                if (platform != Platform.android) refuse("health.csv line ${row.line} lies outside the ride")
                dropped += row.line
                continue
            }
            val producer = serialEnum<Producer>(row.text("source"), row.where("source"))
            if (producer != Producer.phone && producer != Producer.watch)
                refuse("${row.where("source")} is neither phone nor watch")
            samples +=
                HealthSample(
                    rideId = ride.id,
                    producer = producer,
                    sampleKey = row.text("originalEventId"),
                    dataOrigin = row.text("dataOrigin"),
                    parentId = row.textOrNull("parentId"),
                    member = row.intOrNull("memberIndex"),
                    metric = metric,
                    representation = representation,
                    value = row.doubleOrNull("value"),
                    sampleCount = row.intOrNull("sampleCount"),
                    utcUs = utc(row.text("timestamp"), row.where("timestamp")),
                    elapsedUs = Micros.of(row.double("elapsedSeconds")),
                    startUtcUs = startUtcUs,
                    endUtcUs = endUtcUs,
                    activeInterval = row.intOrNull("activeInterval"),
                )
        }
        if (representationAt.keys.any { it !in 2..lastLine })
            refuse("sidecar.json healthRepresentations names a line health.csv lacks")
        return HealthRows(samples, dropped)
    }

    private fun events(platform: Platform, table: CsvTable): List<RideEvent> {
        val producer = ExportPrint.eventProducer(platform).name
        return table
            .rows()
            .map { row ->
                if (row.textOrNull("timerSeconds") != null) refuse("${row.where("timerSeconds")} is not empty")
                if (row.text("interrupted") !in NOT_INTERRUPTED)
                    refuse("${row.where("interrupted")} marks an interruption")
                if (row.text("source") != producer) refuse("${row.where("source")} is not the ride's recorder")
                RideEvent(
                    id = newEventId(),
                    kind = serialEnum(row.text("action"), row.where("action")),
                    utcUs = utc(row.text("timestamp"), row.where("timestamp")),
                    elapsedUs = Micros.of(row.double("elapsedSeconds")),
                )
            }
            .toList()
    }

    private fun utc(text: String, where: String): Long = Utc.parseUs(text) ?: refuse("$where is not a UTC time")

    private fun recorder(platform: Platform): Device =
        when (platform) {
            Platform.ios -> Device.iphone
            Platform.android -> Device.androidPhone
            Platform.web -> Device.browser
        }

    private companion object {
        const val DATAPACKAGE = "datapackage.json"
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
        val NOT_INTERRUPTED = setOf("false", "0")

        val PACKAGE =
            setOf(
                "\$schema",
                "name",
                "id",
                "title",
                "created",
                "description",
                "powerLog:ride",
                "powerLog:batteryConsumption",
                "powerLog:sources",
                "resources",
            )

        val SIDECAR =
            setOf(
                "schema",
                "platform",
                "rideId",
                "distanceSource",
                "connections",
                "gpsBarriers",
                "healthRepresentations",
            )

        val RIDE =
            setOf(
                "startedAt",
                "endedAt",
                "elapsedSeconds",
                "timerSeconds",
                "ownerTiming",
                "indoor",
                "interrupted",
                "watchEnabled",
                "saveToHealth",
                "recordGPS",
                "sport",
                "subSport",
                "health",
                "example",
                "sampleHz",
                "batteryConsumptionSource",
            )

        val RESOURCES: Map<String, Set<String>> =
            mapOf(
                "telemetry" to
                    setOf("timestamp", "elapsedSeconds", "activeInterval", "run", "connection", "controllerSpeedMps") +
                        TELEMETRY_COLUMNS.map { it.printed },
                "battery_consumption" to emptySet(),
                "gps" to
                    setOf(
                        "timestamp",
                        "elapsedSeconds",
                        "activeInterval",
                        "run",
                        "source",
                        "latitude",
                        "longitude",
                        "altitudeMeters",
                        "verticalAccuracyM",
                        "originalAltitudeMeters",
                        "originalVerticalAccuracyM",
                        "originalAltitudeDatum",
                        "horizontalAccuracyM",
                        "speedMps",
                        "speedAccuracyMps",
                        "courseDegrees",
                        "courseAccuracyDegrees",
                    ),
                "health" to
                    setOf(
                        "timestamp",
                        "startTimestamp",
                        "elapsedSeconds",
                        "activeInterval",
                        "source",
                        "metric",
                        "value",
                        "unit",
                        "kind",
                        "sampleCount",
                        "provider",
                        "parentId",
                        "memberIndex",
                        "dataOrigin",
                        "originalEventId",
                        "originalEndTimestamp",
                        "logicalKey",
                        "targetEventId",
                        "originRevision",
                        "valueBits",
                        "evidence",
                    ),
                "events" to setOf("timestamp", "elapsedSeconds", "timerSeconds", "action", "interrupted", "source"),
            )
    }
}
