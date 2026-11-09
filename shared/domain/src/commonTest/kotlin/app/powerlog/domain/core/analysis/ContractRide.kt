package app.powerlog.domain.core.analysis

import app.powerlog.domain.analytics.J
import app.powerlog.domain.analytics.utcUs
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.MetricStatistics
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.MetricOrigin
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.time.TimeRange

internal class ContractRide(private val input: J) {
    private class Event(val json: J, val elapsedUs: Long, val utcUs: Long) {
        val id = "event:${json["eventId"].text()}"
        val kind = json["kind"].text()
        val source = json["source"].text()
        val payload = json["payload"]
    }

    private val create = input["create"]
    private val startedUtcUs = utcUs(create["startedAt"].text())
    private val watch = create["watchEnabled"].bool()
    private val events =
        input["events"]
            .list()
            .map { event ->
                val elapsedUs =
                    event["elapsedSeconds"].optionalDouble()?.let(FixedPoint::timeUs)
                        ?: (utcUs(event["timestamp"].text()) - startedUtcUs)
                Event(event, elapsedUs, utcUs(event["timestamp"].text()))
            }
            .sortedWith(compareBy<Event>({ it.elapsedUs }, { if (it.kind == "lifecycle") 0 else 1 }, { it.id }))
    val endUs: Long =
        (input["metadataUpdate"]["elapsedSeconds"].optionalDouble() ?: input["stopElapsedSeconds"].optionalDouble())
            ?.let(FixedPoint::timeUs) ?: 0L
    private val lifecycle =
        events
            .filter { it.kind == "lifecycle" }
            .mapNotNull { event ->
                val kind =
                    when (event.payload["action"].text()) {
                        "interruption" -> RideEventKind.pause
                        else -> RideEventKind.valueOf(event.payload["action"].text())
                    }
                RideEvent(EventId(event.id), kind, event.utcUs, event.elapsedUs)
            }
    val timeline = ActiveTimeline(endUs, lifecycle)
    private val ids = mutableMapOf<Pair<Long, Long>, MutableList<Event>>()
    private val telemetryEvents = events.filter { it.kind == "telemetry" }
    val sessions =
        mapOf(
            SESSION to
                ControllerSession(
                    ride,
                    SESSION,
                    identity,
                    ControllerCapabilities(
                        MetricCatalog.all.filter { it.origin == MetricOrigin.telemetry }.map { it.id }.toSet(),
                        true,
                    ),
                )
        )
    val telemetry: TelemetryRows = rows()
    val fixes: List<LocationFix> = fixes()
    private val samples: List<HealthSample> = samples()
    val gpsIntervals: List<DistanceInterval> =
        GpsDistanceReducer(null).let { reducer -> fixes.mapNotNull { reducer.append(it)?.interval } }
    val controllerIntervals: List<DistanceInterval> =
        ControllerDistanceReducer(null).append(telemetry, 0, telemetry.rows, sessions)
    val selectedIntervals: List<DistanceInterval>? =
        when (input["distanceSource"].optionalText() ?: "auto") {
            "auto" -> controllerIntervals.ifEmpty { gpsIntervals.ifEmpty { null } }
            "controller" -> controllerIntervals.ifEmpty { null }
            "gps:phone" -> gpsIntervals.ifEmpty { null }
            else -> null
        }

    fun id(ref: OriginalRef?, metric: MetricId): String? {
        if (ref == null) return null
        val quantum = MetricCatalog.of(metric).quantum
        val matching =
            ids[ref.utcUs to ref.elapsedUs].orEmpty().filter { event ->
                event.payload[metric.name].optionalDouble()?.let { FixedPoint.quantize(it, quantum) } == ref.valueQ
            }
        return matching.minOfOrNull { it.id }
    }

    fun statistics(metric: MetricId, range: TimeRange): MetricStatistics {
        val reducer = MetricReducer(metric, range)
        when (MetricCatalog.of(metric).origin) {
            MetricOrigin.location -> fixes.forEach { reducer.append(Fact.Location(it)) }
            MetricOrigin.health -> selected(metric).forEach { reducer.append(Fact.Health(it)) }
            else -> reducer.append(telemetry, 0, telemetry.rows, sessions)
        }
        return (reducer.result() as Bounded.Done).value
    }

    fun distance(range: TimeRange): DistanceTotals {
        val reducer = DistanceReducer(range, null)
        selectedIntervals.orEmpty().forEach(reducer::append)
        val timerUs =
            timeline.intervals.sumOf { maxOf(0L, minOf(it.endUs, range.endUs) - maxOf(it.startUs, range.startUs)) }
        return (reducer.result(timerUs) as Bounded.Done).value
    }

    fun hasOriginalAt(elapsedUs: Long): Boolean = events.any { it.kind != "lifecycle" && it.elapsedUs == elapsedUs }

    private fun selected(metric: MetricId): List<HealthSample> {
        val reading = samples.filter { it.metric?.wire == metric.name }
        val owner = if (watch) Producer.watch else Producer.phone
        val producer =
            listOf(owner, Producer.phone, Producer.watch).firstOrNull { p -> reading.any { it.producer == p } }
        val own = reading.filter { it.producer == producer }
        if (metric != MetricId.heartRateBpm) return own
        val raw = own.filter {
            it.representation == HealthRepresentation.rawQuantity || it.representation == HealthRepresentation.rawSeries
        }
        return raw.ifEmpty { own }
    }

    private fun named(event: Event) {
        ids.getOrPut(event.utcUs to event.elapsedUs) { mutableListOf() } += event
    }

    private fun interval(elapsedUs: Long): Int = timeline.intervalAt(elapsedUs) ?: 0

    private fun rows(): TelemetryRows {
        val metrics = MetricCatalog.all.filter { it.origin == MetricOrigin.telemetry }.map { it.id }
        var run = 0
        var previous: Pair<String?, Int>? = null
        val runs = telemetryEvents.map { event ->
            val continuity = event.payload["connectionEpoch"].optionalText() to interval(event.elapsedUs)
            if (continuity != previous) run++
            previous = continuity
            run
        }
        telemetryEvents.forEach(::named)
        return TelemetryRows(
            rows = telemetryEvents.size,
            elapsedUs = DoubleArray(telemetryEvents.size) { telemetryEvents[it].elapsedUs.toDouble() },
            sessionNo = IntArray(telemetryEvents.size) { SESSION },
            seq = DoubleArray(telemetryEvents.size) { it + 1.0 },
            utcUs = DoubleArray(telemetryEvents.size) { telemetryEvents[it].utcUs.toDouble() },
            activeInterval = IntArray(telemetryEvents.size) { interval(telemetryEvents[it].elapsedUs) },
            run = runs.toIntArray(),
            quanta =
                metrics.associateWith { metric ->
                    DoubleArray(telemetryEvents.size) { index ->
                        val payload = telemetryEvents[index].payload
                        val value =
                            if (metric == MetricId.speedRaw) payload["controllerSpeedMps"].optionalDouble()?.times(3.6)
                            else payload[metric.name].optionalDouble()
                        if (value == null) Double.NaN
                        else FixedPoint.quantize(value, MetricCatalog.of(metric).quantum).toDouble()
                    }
                },
        )
    }

    private fun fixes(): List<LocationFix> {
        var run = 0
        var previous: Int? = null
        return events
            .filter { it.kind == "location" }
            .mapIndexed { index, event ->
                named(event)
                val p = event.payload
                val active = interval(event.elapsedUs)
                val barrier = p["distanceBarrier"] == J.Bool(true)
                if (barrier || active != previous) run++
                previous = active
                LocationFix(
                    rideId = ride,
                    seq = index + 1L,
                    elapsedUs = event.elapsedUs,
                    utcUs = event.utcUs,
                    activeInterval = active.takeIf { it != 0 },
                    run = run,
                    barrier = barrier,
                    latitude = p["latitude"].double(),
                    longitude = p["longitude"].double(),
                    horizontalAccuracyM = p["horizontalAccuracyM"].optionalDouble(),
                    altitudeM = p["altitudeMeters"].optionalDouble(),
                    altitudeDatum = AltitudeDatum.msl,
                    verticalAccuracyM = p["verticalAccuracyM"].optionalDouble(),
                    mslM = p["altitudeMeters"].optionalDouble(),
                    mslVerticalAccuracyM = p["verticalAccuracyM"].optionalDouble(),
                    speedMps = p["speedMps"].optionalDouble(),
                    speedAccuracyMps = p["speedAccuracyMps"].optionalDouble(),
                    courseDeg = p["courseDegrees"].optionalDouble(),
                    courseAccuracyDeg = p["courseAccuracyDegrees"].optionalDouble(),
                )
            }
    }

    private fun samples(): List<HealthSample> =
        events
            .filter { it.kind == "health" }
            .flatMap { event ->
                named(event)
                val metrics = listOf(MetricId.heartRateBpm, MetricId.activeEnergyKcal, MetricId.basalEnergyKcal)
                val reported =
                    metrics.filter { event.payload[it.name] != J.Null }.ifEmpty { listOf(MetricId.heartRateBpm) }
                reported.map { metric ->
                    val value = event.payload[metric.name].optionalDouble()
                    HealthSample(
                        rideId = ride,
                        producer = Producer.valueOf(event.source),
                        sampleKey = "${event.id}:${metric.name}",
                        dataOrigin = "",
                        parentId = null,
                        member = null,
                        metric = HealthMetric(metric.name),
                        representation = representation(event.payload["representation"].optionalText()),
                        value = value,
                        sampleCount = 1,
                        utcUs = event.utcUs,
                        elapsedUs = event.elapsedUs,
                        startUtcUs = null,
                        endUtcUs = null,
                        activeInterval = interval(event.elapsedUs).takeIf { it != 0 },
                    )
                }
            }

    private fun representation(name: String?): HealthRepresentation =
        when (name) {
            null,
            "builderSnapshot" -> HealthRepresentation.builderMostRecent
            else -> HealthRepresentation.valueOf(name)
        }

    companion object {
        const val SESSION = 1
        val ride = RideId("5a1e0001-0000-4000-8000-0000000000a1")
        private val identity = ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3")
    }
}
