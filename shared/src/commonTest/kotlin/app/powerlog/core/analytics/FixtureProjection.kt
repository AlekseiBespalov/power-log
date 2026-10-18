package app.powerlog.core.analytics

import app.powerlog.core.health.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun j(value: Any?): J =
    when (value) {
        null -> J.Null
        is J -> value
        is String -> J.Str(value)
        is Boolean -> J.Bool(value)
        is Number -> J.Num(value.toString())
        is Map<*, *> -> J.Obj(value.entries.associate { it.key.toString() to j(it.value) })
        is Iterable<*> -> J.Arr(value.map { j(it) })
        else -> error("Not JSON: $value")
    }

internal fun obj(vararg pairs: Pair<String, Any?>): J = j(pairs.filter { it.second != null }.toMap())

internal fun overlay(base: J, changes: J): J {
    if (base is J.Obj && changes is J.Obj)
        return J.Obj(base.fields + changes.fields.mapValues { (key, value) -> overlay(base[key], value) })
    if (base is J.Arr && changes is J.Arr && base.values.size == changes.values.size)
        return J.Arr(base.values.indices.map { overlay(base.values[it], changes.values[it]) })
    return changes
}

internal fun assertJson(expected: J, actual: J, path: String = "", partial: Boolean = false) {
    when (expected) {
        is J.Obj -> {
            assertTrue(actual is J.Obj, "$path expected object, got $actual")
            if (!partial) assertEquals(expected.fields.keys, actual.fields.keys, "$path fields")
            for ((key, value) in expected.fields) assertJson(value, actual[key], "$path/$key", partial)
        }
        is J.Arr -> {
            assertTrue(actual is J.Arr, "$path expected array")
            assertEquals(expected.values.size, actual.values.size, "$path size")
            expected.values.indices.forEach { assertJson(expected.values[it], actual.values[it], "$path/$it", partial) }
        }
        is J.Num -> {
            assertTrue(actual is J.Num, "$path expected number got $actual")
            assertEquals(
                expected.double().toBits(),
                actual.double().toBits(),
                "$path expected ${expected.text} got ${actual.text}",
            )
        }
        else -> assertEquals(expected, actual, path)
    }
}

internal fun scalar(value: J): Scalar =
    when (value) {
        is J.Obj ->
            if (value["kind"].text() == "integer64") Scalar.Integer64(value["exactValue"].long())
            else Scalar.Real64(value["value"].double())
        is J.Num -> value.text.toLongOrNull()?.let { Scalar.Integer64(it) } ?: Scalar.Real64(value.double())
        else -> error("Not scalar $value")
    }

internal fun utcUs(value: String): Long {
    val date = value.substring(0, 10).split('-').map { it.toLong() }
    val year = date[0] - if (date[1] <= 2) 1 else 0
    val era = year / 400
    val y = year - era * 400
    val month = date[1] + if (date[1] > 2) -3 else 9
    val days = era * 146097 + y * 365 + y / 4 - y / 100 + (153 * month + 2) / 5 + date[2] - 1 - 719468
    val seconds =
        days * 86400 +
            value.substring(11, 13).toLong() * 3600 +
            value.substring(14, 16).toLong() * 60 +
            value.substring(17, 19).toLong()
    val fraction =
        if (value.getOrNull(19) == '.') value.substring(20).takeWhile { it.isDigit() }.padEnd(6, '0').take(6).toLong()
        else 0
    return seconds * 1000000 + fraction
}

internal fun origin(value: J): Origin =
    Origin(
        value["provider"].optionalText(),
        value["dataOrigin"].optionalText(),
        value["providerObservationId"].optionalText(),
        value["originEventId"].text(),
        value["producer"].text(),
        value["sequence"].long(),
        value["originRevision"].optionalText(),
    )

internal fun original(value: J): Original =
    Original(
        value["observationId"].text(),
        value["eventId"].text(),
        value["timestamp"].text(),
        value["elapsedSeconds"].double(),
        scalar(value["value"]),
        value["originalElapsedSeconds"].optionalDouble(),
    )

internal fun energyInterval(value: J): EnergyInterval =
    EnergyInterval(
        value["source"].text(),
        origin(value["origin"]),
        value["observationRevision"].long(),
        value["startUs"].long(),
        value["endUs"].long(),
        value["originalStartTimestamp"].text(),
        value["originalEndTimestamp"].text(),
        value["valueKcal"].double(),
    )

internal fun energy(value: J): EnergyEvidence {
    val metric = value["metric"].text()
    if (value["kind"].text() == "intervalRecord") return EnergyEvidence.IntervalRecord(metric, energyInterval(value))
    val point =
        EnergyPoint(
            value["source"].text(),
            origin(value["origin"]),
            value["observationRevision"].long(),
            value["collectionUs"].long(),
            value["originalTimestamp"].text(),
            value["valueKcal"].double(),
            Association.Workout(value["association"]["identity"].optionalText() ?: "synthetic"),
        )
    return if (value["kind"].text() == "finalWorkoutTotal") EnergyEvidence.FinalWorkoutTotal(metric, point)
    else EnergyEvidence.CumulativeSnapshot(metric, point)
}

internal fun controller(value: J): ControllerDistanceEvidence =
    ControllerDistanceEvidence(
        value["timeUs"].long(),
        value["originalTime"].double(),
        value["speedMps"].optionalDouble(),
        value["model"].optionalText(),
        value["protocol"].optionalText(),
        value["deviceIdentity"].optionalText(),
        value["continuityIdentity"].optionalText(),
        value["activeInterval"].optionalText()?.toInt(),
        value["monotonicSeconds"].optionalDouble(),
        if (value["counter"] == J.Null) null else scalar(value["counter"]),
        value["barrier"].bool(),
        original(value["anchor"]),
    )

internal class RideFixture(val input: J, val revision: Long = 4, val pageLimit: Int = 256) {
    val create = input["create"]
    private val update = input["metadataUpdate"]
    private val counters = mutableMapOf<String, Long>()
    val events = input["events"].list()
    val items =
        events
            .map { event ->
                val p = event["payload"]
                val source = event["source"].text()
                counters[source] = (counters[source] ?: 0) + 1
                val elapsed =
                    event["elapsedSeconds"].optionalDouble()
                        ?: FixedPoint.divide(
                            utcUs(event["timestamp"].text()) - utcUs(create["startedAt"].text()),
                            1000000,
                        )
                val sequence = p["observationSequence"].optionalText()?.toLong() ?: counters.getValue(source)
                AnalysisItem(
                    FixedPoint.timeUs(elapsed),
                    elapsed,
                    event["eventId"].text(),
                    "event:${event["eventId"].text()}",
                    source,
                    sequence,
                    event["kind"].text(),
                    source,
                    event["timestamp"].text(),
                    p.fields().filterValues { it is J.Num }.mapValues { scalar(it.value) },
                    event["originalElapsedSeconds"].optionalDouble(),
                    p["representation"].optionalText(),
                    Continuity(p["clockEpoch"].optionalText(), p["connectionEpoch"].optionalText()),
                    p["action"].optionalText(),
                    p["distanceBarrier"] == J.Bool(true),
                    p["interrupted"] == J.Bool(true),
                    p["controllerSequence"].optionalText()?.toLong(),
                    event["elapsedSeconds"] != J.Null,
                )
            }
            .sortedWith(
                compareBy<AnalysisItem> { it.collectionUs }
                    .thenBy { if (it.kind == "lifecycle") 0 else 1 }
                    .thenBy { it.producer }
                    .thenBy { it.sequence }
                    .thenBy { it.observationId }
            )
    val pin: AnalysisPin
    val prepared: PreparedSummary
    val distance: DistanceInfo
    val intervals: Map<String, List<DistanceInterval>>
    val summary: Summary

    init {
        val watch = create["watchEnabled"].bool()
        val m =
            AnalysisMetadata(
                create["id"].text(),
                create["startedAt"].text(),
                update["elapsedSeconds"].optionalDouble() ?: input["stopElapsedSeconds"].optionalDouble() ?: 0.0,
                create["indoor"].bool(),
                watch,
                create["recordGPS"].bool(),
                create["saveToHealth"].bool(),
                8,
                input["endedAt"].optionalText(),
                input["stopElapsedSeconds"].optionalDouble(),
                listOf(
                    MeasurementSource("phone", "iphone", "appleHealth"),
                    MeasurementSource("watch", "appleWatch", "appleHealth"),
                ),
                input.toString(),
                phase = update["phase"].optionalText() ?: if (input["endedAt"] != J.Null) "completed" else "running",
                healthOutcome = update["healthKitState"].optionalText()?.let(HealthOutcome::valueOf),
                healthReadbackPending = update["healthReadbackPending"] == J.Bool(true),
            )
        pin = AnalysisPin(m.id, revision, m, input["distanceSource"].optionalText() ?: "auto")
        val prep = SummaryPreparation(pin)
        items.chunked(pageLimit).forEach { page -> page.forEach(prep::append) }
        val first = prep.finish()
        val spans = mutableMapOf<String, List<DistanceInterval>>()
        for (source in listOf("phone", "watch")) {
            val gps = GPSKernel()
            spans["gps:$source"] =
                items
                    .filter { it.kind == "location" && it.source == source }
                    .mapNotNull { gps.append(GPSFix(it, first.timeline.membership(it))).interval }
        }
        val kernel = ControllerDistanceKernel()
        spans["controller"] =
            events
                .filter { it["kind"].text() == "telemetry" }
                .mapNotNull { event ->
                    val p = event["payload"]
                    val item = items.first { it.eventId == event["eventId"].text() }
                    val original =
                        item.original("controllerSpeedMps")
                            ?: Original(
                                item.observationId,
                                item.eventId,
                                item.timestamp,
                                item.collectionElapsedSeconds,
                                Scalar.Real64(0.0),
                            )
                    kernel.append(
                        ControllerDistanceEvidence(
                            item.collectionUs,
                            item.collectionElapsedSeconds,
                            item.number("controllerSpeedMps"),
                            p["controllerModel"].optionalText(),
                            p["controllerProtocol"].optionalText(),
                            p["captureSessionID"].optionalText(),
                            item.continuity.connectionEpoch?.let { "$it:${item.continuity.clockEpoch}" },
                            first.timeline.membership(item),
                            item.number("monotonicSeconds"),
                            p["observationSequence"].optionalText()?.let { Scalar.Integer64(it.toLong()) },
                            false,
                            original,
                        )
                    )
                }
        intervals = spans
        val available =
            listOf("gps:watch", "gps:phone", "controller").mapNotNull { key ->
                val edges = spans.getValue(key)
                if (edges.isEmpty()) null
                else {
                    val reducer = DistanceReducer(AnalysisRange(0, first.actualEndUs))
                    edges.forEach(reducer::append)
                    val result = reducer.result(first.timeline.timerUs)
                    distanceProfile(key, result, first.timeline.timerUs, m.sources)
                }
            }
        distance = selectDistance(pin.distanceSelection, available, watch, m.indoor)
        prepared = prep.finish(distance)
        val composer = SummaryComposer(prepared, distance)
        items.chunked(pageLimit).forEach { page -> page.forEach(composer::append) }
        for (event in events.filter { it["kind"].text() == "health" }) {
            val item = items.first { it.eventId == event["eventId"].text() }
            for (metric in listOf("active", "basal")) {
                val value = item.number("${metric}EnergyKcal") ?: continue
                val point =
                    EnergyPoint(
                        item.source,
                        eventOrigin(event, item),
                        revision,
                        item.collectionUs,
                        item.timestamp,
                        value,
                        Association.Workout("synthetic-workout"),
                    )
                composer.energy(
                    if (item.representation == "finalWorkoutTotal") EnergyEvidence.FinalWorkoutTotal(metric, point)
                    else EnergyEvidence.CumulativeSnapshot(metric, point)
                )
            }
        }
        summary = (composer.finish() as SummaryOutcome.Complete).summary
    }

    private fun eventOrigin(event: J, item: AnalysisItem): Origin =
        Origin(
            "appleHealth",
            null,
            event["payload"]["sampleUUID"].optionalText(),
            item.eventId,
            item.producer,
            item.sequence,
        )

    fun statistics(): J =
        j(
            (input["queries"] as? J.Arr)?.values.orEmpty().map { query ->
                val range =
                    AnalysisRange(
                        FixedPoint.timeUs(query["startSeconds"].double()),
                        FixedPoint.timeUs(query["endSeconds"].double()),
                    )
                val results = linkedMapOf<String, J>()
                for (metric in query["metrics"].list().map { it.text() }) {
                    if (metric == "distanceMeters") {
                        val source = distance.selected?.source
                        val reducer = DistanceReducer(range)
                        if (source != null) intervals.getValue(source).forEach(reducer::append)
                        val timer =
                            prepared.timeline.intervals.sumOf {
                                maxOf(0L, minOf(it.endUs, range.endUs) - maxOf(it.startUs, range.startUs))
                            }
                        val result = reducer.result(timer)
                        results[metric] =
                            obj(
                                "distance" to if (source == null) null else result.distanceMeters,
                                "coveredSeconds" to result.coveredSeconds,
                                "partial" to result.partial,
                                "unresolvedBoundary" to result.unresolvedBoundary,
                            )
                        continue
                    }
                    val selected = items.filter { item ->
                        when (metric) {
                            "heartRateBpm" ->
                                item.kind == "health" &&
                                    item.source == prepared.healthSources[metric] &&
                                    (item.source !in prepared.rawHeartRateSources ||
                                        item.representation in listOf("rawQuantity", "rawSeries"))
                            "activeEnergyKcal",
                            "basalEnergyKcal" ->
                                item.kind == "health" &&
                                    item.source == prepared.healthSources[metric.removeSuffix("EnergyKcal")]
                            "speedMps",
                            "altitudeMeters",
                            "horizontalAccuracyM",
                            "verticalAccuracyM",
                            "courseDegrees" -> item.kind == "location" && item.source == prepared.locationSource
                            else -> item.kind == "telemetry"
                        }
                    }
                    if (selected.none { it.values.containsKey(metric) }) continue
                    val reducer = MetricReducer(Metrics.monitorPolicy(metric), range)
                    selected.chunked(pageLimit).forEach { page ->
                        page.forEach { reducer.append(monitorReading(it, metric, prepared.timeline.membership(it))) }
                    }
                    results[metric] = statisticsJson(reducer.result().statistics)
                }
                J.Obj(results)
            }
        )
}

internal fun originalJson(value: Original?): J? = value?.let {
    obj(
        "observationId" to it.observationId,
        "timestamp" to it.timestamp,
        "elapsedSeconds" to it.elapsedSeconds,
        "value" to it.value.real(),
        "exactValue" to (it.value as? Scalar.Integer64)?.value?.toString(),
    )
}

internal fun statisticsJson(s: MetricStatistics): J =
    obj(
        "count" to s.count,
        "min" to originalJson(s.min),
        "max" to originalJson(s.max),
        "sampleMean" to s.sampleMean,
        "coveredSeconds" to s.coveredSeconds,
        "integral" to s.integral,
    )

internal fun profileJson(p: DistanceProfile): J =
    obj(
        "source" to p.source,
        "label" to p.label,
        "estimated" to p.estimated,
        "partial" to p.partial,
        "coveredSeconds" to p.coveredSeconds,
        "uncoveredSeconds" to p.uncoveredSeconds,
        "distanceMeters" to p.distanceMeters,
    )

internal fun distanceJson(d: DistanceInfo): J =
    obj(
        "selection" to d.selection,
        "selected" to (d.selected?.let(::profileJson) ?: J.Null),
        "available" to d.available.map(::profileJson),
    )

internal fun summaryJson(s: Summary): J =
    obj(
        "schemaVersion" to s.schemaVersion,
        "id" to s.id,
        "startedAt" to s.startedAt,
        "endedAt" to s.endedAt,
        "elapsedSeconds" to s.elapsedSeconds,
        "timerSeconds" to s.timerSeconds,
        "distance" to distanceJson(s.distance),
        "telemetryCoveredSeconds" to s.telemetryCoveredSeconds,
        "heartRateCoveredSeconds" to s.heartRateCoveredSeconds,
        "eventCount" to s.eventCount,
        "telemetryCount" to s.telemetryCount,
        "locationCount" to s.locationCount,
        "healthCount" to s.healthCount,
        "lapCount" to s.lapCount,
        "routePreview" to
            s.routePreview.map {
                obj(
                    "latitude" to it.latitude,
                    "longitude" to it.longitude,
                    "elapsedSeconds" to it.elapsedSeconds,
                    "segment" to it.segment,
                    "startsSegment" to it.startsSegment,
                )
            },
        "provenance" to s.provenance,
        "completeness" to
            obj(
                "distance" to s.completeness.distance,
                "riderPower" to s.completeness.riderPower,
                "heartRate" to s.completeness.heartRate,
                "route" to s.completeness.route,
            ),
        "distanceMeters" to s.distanceMeters,
        "gpsDistanceMeters" to s.gpsDistanceMeters,
        "averageSpeedMps" to s.averageSpeedMps,
        "maximumSpeedMps" to s.maximumSpeedMps,
        "ascentMeters" to s.ascentMeters,
        "descentMeters" to s.descentMeters,
        "averageHeartRateBpm" to s.averageHeartRateBpm,
        "maximumHeartRateBpm" to s.maximumHeartRateBpm,
        "averageRiderPowerW" to s.averageRiderPowerW,
        "maximumRiderPowerW" to s.maximumRiderPowerW,
        "averageCadenceRpm" to s.averageCadenceRpm,
        "maximumCadenceRpm" to s.maximumCadenceRpm,
        "activeEnergyKcal" to s.activeEnergyKcal,
        "basalEnergyKcal" to s.basalEnergyKcal,
        "riderWorkJoules" to s.riderWorkJoules,
    )
