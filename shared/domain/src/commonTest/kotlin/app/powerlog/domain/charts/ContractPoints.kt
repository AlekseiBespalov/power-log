package app.powerlog.domain.charts

import app.powerlog.domain.analytics.J
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.MetricOrigin
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.time.TimeRange

internal class ContractPoints(
    case: J,
    private val metric: MetricId,
    endUs: Long,
    points: List<J> = case["points"].list(),
) {
    private sealed interface Input {
        val elapsedUs: Long

        class Row(override val elapsedUs: Long, val row: Int) : Input

        class Other(override val elapsedUs: Long, val fact: Fact) : Input
    }

    private class Point(val json: J, val index: Int) {
        val id = json["id"].text()
        val timeUs = json["timeUs"].long()
        val kind = json["kind"].optionalText() ?: "telemetry"
        val sequence = json["sequence"].optionalText()?.toLong()
    }

    private val definition = MetricCatalog.of(metric)
    private val events =
        if (case["lifecycle"] == J.Null) emptyList()
        else
            case["lifecycle"].list().mapIndexed { index, event ->
                val atUs = event["timeUs"].long()
                RideEvent(EventId("event-$index"), RideEventKind.valueOf(event["action"].text()), utc(atUs), atUs)
            }
    private val timeline = if (case["lifecycle"] == J.Null) null else ActiveTimeline(endUs, events)
    private val ids = mutableMapOf<Long, String>()
    private val refs = mutableMapOf<String, OriginalRef>()
    private val inputs = mutableListOf<Input>()
    private val references =
        points.filter { it["reference"] != J.Null }.associate { it["reference"].text() to it["id"].text() }
    val sessions = mapOf(SESSION to ControllerSession(ride, SESSION, identity, capabilities))
    val telemetry: TelemetryRows

    init {
        val kept =
            points
                .mapIndexed { index, json -> Point(json, index) }
                .filter { it.json["selected"] != J.Bool(false) }
                .filter {
                    it.json["declared"] == J.Null ||
                        it.json["declared"].list().any { name -> name.text() == metric.name }
                }
        val ranks = kept.sortedBy { it.id }.withIndex().associate { (rank, point) -> point to rank + 1L }
        val ordered = kept.sortedWith(compareBy<Point> { it.timeUs }.thenBy { it.sequence ?: ranks.getValue(it) })
        val rows = ordered.filter { it.kind == "telemetry" }
        val runs = runs(rows)
        val quanta = DoubleArray(rows.size) { quanta(rows[it].json["value"]) }
        telemetry =
            TelemetryRows(
                rows = rows.size,
                elapsedUs = DoubleArray(rows.size) { rows[it].timeUs.toDouble() },
                sessionNo = IntArray(rows.size) { SESSION },
                seq = DoubleArray(rows.size) { (rows[it].sequence ?: ranks.getValue(rows[it])).toDouble() },
                utcUs = DoubleArray(rows.size) { utc(rows[it]).toDouble() },
                activeInterval = IntArray(rows.size) { interval(rows[it]) },
                run = IntArray(rows.size) { runs[it] },
                quanta = mapOf(metric to quanta),
            )
        for (point in ordered) {
            val utcUs = utc(point)
            ids[utcUs] = point.id
            when (point.kind) {
                "telemetry" -> {
                    val row = rows.indexOf(point)
                    inputs += Input.Row(point.timeUs, row)
                    refs[point.id] = OriginalRef(utcUs, point.timeUs, quanta[row].toLong())
                }
                "location" -> inputs += Input.Other(point.timeUs, Fact.Location(fix(point, ranks.getValue(point))))
                "health" -> inputs += Input.Other(point.timeUs, Fact.Health(sample(point)))
                else -> error("unknown point kind ${point.kind}")
            }
            refs.getOrPut(point.id) { OriginalRef(utcUs, point.timeUs, quantize(point.json["value"]) ?: 0) }
        }
        for (event in events) inputs += Input.Other(event.elapsedUs, Fact.Event(event))
        inputs.sortBy { it.elapsedUs }
    }

    fun feed(consumer: TelemetryConsumer, within: TimeRange = everything, facts: (Fact) -> Unit) {
        for (input in inputs.filter { it.elapsedUs >= within.startUs && it.elapsedUs < within.endUs }) when (input) {
            is Input.Row -> consumer.append(telemetry, input.row, input.row + 1, sessions)
            is Input.Other -> facts(input.fact)
        }
    }

    fun id(ref: OriginalRef?): String? = ref?.let { ids.getValue(it.utcUs) }

    fun named(reference: String): String = references[reference] ?: reference

    fun ref(id: String): OriginalRef = refs.getValue(id)

    private fun interval(point: Point): Int {
        val line = timeline
        if (line != null) return line.intervalAt(point.timeUs) ?: 0
        return 1 + (point.json["interruptionIndex"].optionalText()?.toInt() ?: 0)
    }

    private fun runs(rows: List<Point>): IntArray {
        var run = 0
        var connection: String? = null
        var active: Int? = null
        return IntArray(rows.size) { index ->
            val point = rows[index]
            val next = point.json["connection"].optionalText()
            val interval = interval(point)
            if (index == 0 || next != connection || interval != active) run++
            connection = next
            active = interval
            run
        }
    }

    private fun quanta(value: J): Double = quantize(value)?.toDouble() ?: Double.NaN

    private fun quantize(value: J): Long? =
        when (value) {
            J.Null -> null
            is J.Str -> if (value.text() == "NaN") null else FixedPoint.product(value.long(), definition.quantum)
            else -> FixedPoint.quantize(value.double(), definition.quantum)
        }

    private fun number(value: J): Double? =
        when (value) {
            J.Null -> null
            is J.Str -> if (value.text() == "NaN") Double.NaN else value.long().toDouble()
            else -> value.double()
        }

    private fun fix(point: Point, rank: Long): LocationFix {
        val value = number(point.json["value"])
        val accuracy = point.json["accuracy"].optionalDouble()
        fun on(id: MetricId, otherwise: Double?) = if (metric == id) value else otherwise
        return LocationFix(
            rideId = ride,
            seq = point.sequence ?: rank,
            elapsedUs = point.timeUs,
            utcUs = utc(point),
            activeInterval = interval(point).takeIf { it != 0 },
            run = 1,
            barrier = false,
            latitude = 45.5,
            longitude = 9.2,
            horizontalAccuracyM = on(MetricId.horizontalAccuracyM, 5.0),
            altitudeM = on(MetricId.altitudeMeters, 120.0),
            altitudeDatum = AltitudeDatum.msl,
            verticalAccuracyM = on(MetricId.verticalAccuracyM, 3.0),
            mslM = on(MetricId.altitudeMeters, 120.0),
            mslVerticalAccuracyM = on(MetricId.verticalAccuracyM, 3.0),
            speedMps = on(MetricId.speedMps, 5.0),
            speedAccuracyMps = if (metric == MetricId.speedMps) accuracy else 0.5,
            courseDeg = on(MetricId.courseDegrees, 37.0),
            courseAccuracyDeg = if (metric == MetricId.courseDegrees) accuracy else 8.0,
        )
    }

    private fun sample(point: Point): HealthSample {
        val wire = if (definition.origin == MetricOrigin.health) metric.name else MetricId.heartRateBpm.name
        return HealthSample(
            rideId = ride,
            producer = Producer.phone,
            sampleKey = point.id,
            dataOrigin = "",
            parentId = null,
            member = null,
            metric = HealthMetric(wire),
            representation = HealthRepresentation.rawQuantity,
            value = number(point.json["value"]),
            sampleCount = 1,
            utcUs = utc(point),
            elapsedUs = point.timeUs,
            startUtcUs = null,
            endUtcUs = null,
            activeInterval = interval(point).takeIf { it != 0 },
        )
    }

    private fun utc(point: Point): Long = START_UTC_US + point.timeUs * 1_000 + point.index

    private fun utc(atUs: Long): Long = START_UTC_US + atUs * 1_000

    companion object {
        const val SESSION = 1
        const val START_UTC_US = 1_000_000_000_000_000L
        val ride = RideId("5a1e0001-0000-4000-8000-0000000000c1")
        private val everything = TimeRange(Long.MIN_VALUE, Long.MAX_VALUE)
        private val identity = ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3")
        private val capabilities =
            ControllerCapabilities(
                MetricCatalog.all.filter { it.origin == MetricOrigin.telemetry }.map { it.id }.toSet(),
                true,
            )

        fun distance(point: J, start: J?): DistanceInterval {
            val end = OriginalRef(START_UTC_US + point["tieKey"].long(), point["timeUs"].long(), meters(point))
            val startUs = point["startUs"].long()
            val first =
                start?.let { OriginalRef(START_UTC_US + it["tieKey"].long(), it["timeUs"].long(), meters(it)) }
                    ?: OriginalRef(START_UTC_US - startUs, startUs, end.valueQ)
            return DistanceInterval(
                TimeRange(startUs, end.elapsedUs),
                end.valueQ - first.valueQ,
                point["segment"].long().toInt(),
                1,
                first,
                end,
                null,
                null,
            )
        }

        private fun meters(point: J): Long = FixedPoint.quantize(point["value"].double(), 1_000_000L)
    }
}
