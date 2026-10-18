package app.powerlog.core.analytics

import app.powerlog.core.health.*

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val elapsedSeconds: Double,
    val segment: Int,
    val startsSegment: Int,
)

data class Completeness(
    val distance: String,
    val riderPower: String,
    val heartRate: String,
    val route: String,
)

data class Summary(
    val id: String,
    val startedAt: String,
    val endedAt: String,
    val elapsedSeconds: Double,
    val timerSeconds: Double,
    val distance: DistanceInfo,
    val telemetryCoveredSeconds: Double,
    val heartRateCoveredSeconds: Double,
    val eventCount: Long,
    val telemetryCount: Long,
    val locationCount: Long,
    val healthCount: Long,
    val lapCount: Int,
    val routePreview: List<RoutePoint>,
    val provenance: Map<String, String>,
    val completeness: Completeness,
    val distanceMeters: Double? = null,
    val gpsDistanceMeters: Double? = null,
    val averageSpeedMps: Double? = null,
    val maximumSpeedMps: Double? = null,
    val ascentMeters: Double? = null,
    val descentMeters: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val maximumHeartRateBpm: Double? = null,
    val averageRiderPowerW: Double? = null,
    val maximumRiderPowerW: Double? = null,
    val averageCadenceRpm: Double? = null,
    val maximumCadenceRpm: Double? = null,
    val activeEnergyKcal: Double? = null,
    val basalEnergyKcal: Double? = null,
    val riderWorkJoules: Double? = null,
    val schemaVersion: Int = 1,
)

sealed class SummaryOutcome {
    data class Complete(val summary: Summary) : SummaryOutcome()

    data class Overflow(val failure: NumericOverflow) : SummaryOutcome()

    data class InvalidEvidence(val reason: String) : SummaryOutcome()

    data object Cancelled : SummaryOutcome()
}

data class PreparedSummary(
    val pin: AnalysisPin,
    val actualEndUs: Long,
    val endedAt: String,
    val timeline: ActiveTimeline,
    val locationSource: String?,
    val goodLocations: Map<String, Long>,
    val healthSources: Map<String, String>,
    val rawHeartRateSources: Set<String>,
    val eventCount: Long,
    val counts: Map<String, Long>,
    val rawHeartRateOrigins: Map<String, Set<String>>,
) {
    fun heartRateEligible(item: AnalysisItem, dataOrigin: String?): Boolean {
        val raw =
            if (dataOrigin == null) item.source in rawHeartRateSources
            else dataOrigin in rawHeartRateOrigins[item.source].orEmpty()
        return !raw || item.representation == "rawQuantity" || item.representation == "rawSeries"
    }
}

fun selectedHealthSource(owner: String, available: Set<String>): String? =
    listOf(owner, "phone", "watch").firstOrNull { it in available }

fun summaryCollectionAvailable(collectionUs: Long): Boolean =
    collectionUs >= -1000000L && collectionUs <= 2678400000000L

fun energyAvailable(evidence: EnergyEvidence): Boolean =
    summaryCollectionAvailable(evidence.collectionUs) && validEnergyEvidence(evidence)

class SummaryPreparation(private val pin: AnalysisPin) {
    private val lifecycle = mutableListOf<AnalysisItem>()
    private val counts = mutableMapOf<String, Long>()
    private val locations = mutableMapOf<String, Long>()
    private val available = mutableMapOf<String, MutableSet<String>>()
    private val rawHeartRate = mutableSetOf<String>()
    private val rawHeartRateOrigins = mutableMapOf<String, MutableSet<String>>()
    private var maxTime = 0L
    private var lastTimestamp: String? = null
    private var total = 0L
    private var lastObservationId: String? = null
    private var invalid: String? = null

    private fun countObservation(id: String, kind: String) {
        if (id == lastObservationId) return
        total = FixedPoint.add(total, 1)
        counts[kind] = FixedPoint.add(counts[kind] ?: 0, 1)
        lastObservationId = id
    }

    fun append(projection: AnalysisProjection) {
        when (projection) {
            is AnalysisProjection.Observation -> append(projection.item)
            is AnalysisProjection.Lifecycle -> append(projection.item)
            is AnalysisProjection.Energy -> {
                countObservation(projection.observationId, "health")
                energy(projection.evidence)
            }
            is AnalysisProjection.ControllerDistance -> {
                countObservation(projection.observationId, "telemetry")
                if (projection.evidence.timeUs >= maxTime && projection.evidence.timeUs <= 2678400000000L) {
                    maxTime = projection.evidence.timeUs
                    lastTimestamp = projection.evidence.anchor.timestamp
                }
            }
            is AnalysisProjection.Altitude -> countObservation(projection.observationId, "location")
        }
    }

    internal fun telemetry(timeUs: Long, observationId: String, timestamp: String, hasElapsedMapping: Boolean) {
        countObservation(observationId, "telemetry")
        if (summaryCollectionAvailable(timeUs) && hasElapsedMapping && timeUs >= maxTime) {
            maxTime = timeUs
            lastTimestamp = timestamp
        }
    }

    fun append(item: AnalysisItem) {
        countObservation(item.observationId, item.kind)
        if (!summaryCollectionAvailable(item.collectionUs)) return
        if (item.kind != "health" && item.hasElapsedMapping && item.collectionUs >= maxTime) {
            maxTime = item.collectionUs
            lastTimestamp = item.timestamp
        }
        when (item.kind) {
            "lifecycle" -> {
                if (lifecycle.size == 10000) invalid = "Too many lifecycle events" else lifecycle.add(item)
            }
            "location" ->
                if (Metrics.policy("horizontalAccuracyM").valid(item.values["horizontalAccuracyM"]))
                    locations[item.source] = FixedPoint.add(locations[item.source] ?: 0, 1)
            "health" -> {
                if (
                    item.isReadingOf("heartRateBpm") &&
                        Metrics.policy("heartRateBpm").valid(item.values["heartRateBpm"])
                ) {
                    available.getOrPut("heartRateBpm") { mutableSetOf() }.add(item.source)
                    if (item.representation == "rawQuantity" || item.representation == "rawSeries") {
                        rawHeartRate.add(item.source)
                        item.origin?.dataOrigin?.let {
                            rawHeartRateOrigins.getOrPut(item.source) { mutableSetOf() }.add(it)
                        }
                    }
                }
                for (metric in listOf("active", "basal")) if (
                    item.isReadingOf("${metric}EnergyKcal") &&
                        Metrics.policy("${metric}EnergyKcal").valid(item.values["${metric}EnergyKcal"])
                )
                    available.getOrPut(metric) { mutableSetOf() }.add(item.source)
            }
        }
    }

    fun energy(evidence: EnergyEvidence) {
        if (energyAvailable(evidence)) available.getOrPut(evidence.metric) { mutableSetOf() }.add(evidence.source.name)
    }

    fun finishOutcome(distance: DistanceInfo? = null): PreparationOutcome =
        try {
            PreparationOutcome.Prepared(finish(distance))
        } catch (e: NumericFailure) {
            PreparationOutcome.Overflow(e.failure)
        } catch (e: ArithmeticException) {
            PreparationOutcome.Overflow(NumericOverflow("summary", Quantity.count))
        } catch (e: IllegalArgumentException) {
            PreparationOutcome.InvalidEvidence(e.message.orEmpty())
        }

    fun finish(distance: DistanceInfo? = null): PreparedSummary {
        require(invalid == null) { requireNotNull(invalid) }
        val metadata = pin.metadata
        val end =
            metadata.stopElapsedSeconds?.let { FixedPoint.timeUs(it) }
                ?: maxOf(FixedPoint.timeUs(metadata.elapsedSeconds), maxTime)
        require(end >= 0L && end <= 2678400000000L) { "Invalid ride end" }
        val ended = metadata.endedAt ?: lastTimestamp ?: metadata.startedAt
        val owner = if (metadata.watchEnabled) "watch" else "phone"
        val order = listOf(owner, "phone", "watch").distinct()
        val sources =
            available
                .mapNotNull { (metric, devices) -> selectedHealthSource(owner, devices)?.let { metric to it } }
                .toMap()
        val gps = order.firstOrNull { (locations[it] ?: 0) > 0 }
        val selectedGPS = distance?.selected?.source?.takeIf { it.startsWith("gps:") }?.substringAfter(':')
        return PreparedSummary(
            pin,
            end,
            ended,
            ActiveTimeline(end, lifecycle),
            selectedGPS ?: gps,
            locations.toMap(),
            sources,
            rawHeartRate.toSet(),
            total,
            counts.toMap(),
            rawHeartRateOrigins.mapValues { it.value.toSet() },
        )
    }
}

class SummaryComposer(
    private val prepared: PreparedSummary,
    private val distance: DistanceInfo,
    private val healthSelections: List<HealthSelection> = emptyList(),
) {
    private val power = MetricReducer(Metrics.policy("humanPowerW"), AnalysisRange(0, prepared.actualEndUs))
    private val cadence = MetricReducer(Metrics.policy("cadenceRpm"), AnalysisRange(0, prepared.actualEndUs))
    private val hr = MetricReducer(Metrics.policy("heartRateBpm"), AnalysisRange(0, prepared.actualEndUs))
    private val geometry = GPSKernel(fullContinuity = true)
    private val preview = mutableListOf<RoutePoint>()
    private var lastPreview: RoutePoint? = null
    private var previewIndex = 0L
    private val stride = maxOf(1L, ((prepared.goodLocations[prepared.locationSource] ?: 0) + 253) / 254)
    private var maximumSpeed: Double? = null
    private val energies =
        listOf("active", "basal").associateWith { metric ->
            EnergyReducer(
                metric,
                prepared.actualEndUs,
                prepared.healthSources[metric],
                healthSelections.firstOrNull { it.metric == metric }?.dataOrigin,
            )
        }
    private var cancelled = false
    private var telemetryContinuity: Continuity? = null
    private val failures = mutableListOf<NumericOverflow>()
    private var invalid: String? = null

    fun append(projection: AnalysisProjection) {
        if (cancelled) return
        try {
            when (projection) {
                is AnalysisProjection.Observation -> append(projection.item)
                is AnalysisProjection.Lifecycle -> append(projection.item)
                is AnalysisProjection.Energy -> energy(projection.evidence)
                else -> Unit
            }
        } catch (e: NumericFailure) {
            failures.add(e.failure)
        } catch (e: ArithmeticException) {
            failures.add(NumericOverflow("summary", Quantity.sumQ))
        } catch (e: IllegalArgumentException) {
            invalid = e.message
        }
    }

    fun cancel() {
        cancelled = true
    }

    internal fun telemetry(input: SummaryTelemetryInput, index: Int) {
        val time = input.key(index, 0)
        if (cancelled || !summaryCollectionAvailable(time)) return
        val flags = input.key(index, 2)
        val producer = input.text(index, 3)
        val sequence = input.key(index, 1)
        val clock = input.text(index, 4).takeIf { flags and 32L != 0L }
        val connection = input.text(index, 5).takeIf { flags and 64L != 0L }
        val active = prepared.timeline.membership(time, producer, sequence, clock)
        val interruption = prepared.timeline.interruptionIndex(time, producer, sequence, clock)
        val continuity =
            telemetryContinuity?.takeIf {
                it.clockEpoch == clock && it.connectionEpoch == connection && it.interruptionIndex == interruption
            } ?: Continuity(clock, connection, interruption).also { telemetryContinuity = it }
        fun append(reducer: MetricReducer, column: Int, integerFlag: Long, missingFlag: Long, selectedFlag: Long) {
            if (flags and selectedFlag == 0L) return
            val scalar =
                when {
                    flags and missingFlag != 0L -> null
                    flags and integerFlag != 0L -> Scalar.Integer64(input.key(index, column + 1))
                    else -> Scalar.Real64(input.number(index, column))
                }
            reducer.appendTelemetry(
                time,
                scalar,
                active,
                continuity,
                input.text(index, 0),
                input.text(index, 1),
                input.text(index, 2),
                input.number(index, 0),
                input.number(index, 1).takeIf { flags and 16L != 0L },
            )
        }
        append(power, 2, 1, 2, 128)
        append(cadence, 3, 4, 8, 256)
    }

    fun append(item: AnalysisItem) {
        if (cancelled || !summaryCollectionAvailable(item.collectionUs)) return
        val active = prepared.timeline.membership(item)
        fun reading(metric: String) =
            MetricReading(item.collectionUs, item.original(metric), active, item.continuity, item.isReadingOf(metric))
        when (item.kind) {
            "telemetry" -> {
                power.append(reading("humanPowerW"))
                cadence.append(reading("cadenceRpm"))
            }
            "health" -> {
                val selected = item.isReadingOf("heartRateBpm") && item.source == prepared.healthSources["heartRateBpm"]
                if (selected) {
                    val dataOrigin =
                        healthSelections
                            .firstOrNull { it.metric == "heartRateBpm" && it.source.name == item.source }
                            ?.dataOrigin
                    if (dataOrigin != null && item.origin == null) {
                        invalid = "Missing heart-rate origin"
                        return
                    }
                    if (dataOrigin != null && item.origin?.dataOrigin != dataOrigin) return
                    if (prepared.heartRateEligible(item, dataOrigin)) hr.append(reading("heartRateBpm"))
                }
            }
            "location" ->
                if (item.source == prepared.locationSource) {
                    val accepted = geometry.append(GPSFix(item, active))
                    if (accepted.accepted) {
                        val point =
                            RoutePoint(
                                requireNotNull(item.number("latitude")),
                                requireNotNull(item.number("longitude")),
                                item.collectionElapsedSeconds,
                                geometry.segment,
                                if (accepted.startsSegment) 1 else 0,
                            )
                        if (previewIndex % stride == 0L && preview.size < 255) {
                            preview.add(point)
                        }
                        previewIndex++
                        lastPreview = point
                        val speed = item.number("speedMps")
                        if (
                            Metrics.validSpeed(speed, item.number("speedAccuracyMps")) &&
                                (maximumSpeed == null || requireNotNull(speed) > requireNotNull(maximumSpeed))
                        )
                            maximumSpeed = speed
                    }
                }
        }
    }

    fun energy(evidence: EnergyEvidence) {
        if (cancelled || !summaryCollectionAvailable(evidence.collectionUs)) return
        try {
            energies[evidence.metric]?.append(evidence)
        } catch (e: NumericFailure) {
            failures.add(e.failure)
        }
    }

    fun finish(): SummaryOutcome {
        if (cancelled) return SummaryOutcome.Cancelled
        if (invalid != null) return SummaryOutcome.InvalidEvidence(requireNotNull(invalid))
        fun <T> collect(block: () -> T): T? =
            try {
                block()
            } catch (e: NumericFailure) {
                failures.add(e.failure)
                null
            } catch (_: ArithmeticException) {
                failures.add(NumericOverflow("summary", Quantity.sumQ))
                null
            }
        val results = listOf(power, cadence, hr).associate { it.policy.id to collect(it::result) }
        val activeQ = collect { energies.getValue("active").valueQ() }
        val basalQ = collect { energies.getValue("basal").valueQ() }
        val gpsQ = collect(geometry::distanceQ)
        collect(geometry::coveredUs)
        val ascentQ = collect(geometry::ascentQ)
        val descentQ = collect(geometry::descentQ)
        if (failures.isNotEmpty())
            return SummaryOutcome.Overflow(
                failures.minWith { a, b ->
                    val order = compareUtf8(a.metric, b.metric)
                    if (order == 0) a.quantity.ordinal.compareTo(b.quantity.ordinal) else order
                }
            )
        return try {
            SummaryOutcome.Complete(
                compose(
                    results.mapValues { requireNotNull(it.value) },
                    activeQ,
                    basalQ,
                    gpsQ,
                    ascentQ,
                    descentQ,
                )
            )
        } catch (e: NumericFailure) {
            SummaryOutcome.Overflow(e.failure)
        } catch (e: ArithmeticException) {
            SummaryOutcome.Overflow(NumericOverflow("summary", Quantity.sumQ))
        } catch (e: IllegalArgumentException) {
            SummaryOutcome.InvalidEvidence(e.message.orEmpty())
        }
    }

    private fun compose(
        results: Map<String, MetricResult>,
        activeQ: Long?,
        basalQ: Long?,
        gpsQ: Long?,
        ascentQ: Long?,
        descentQ: Long?,
    ): Summary {
        val p = results.getValue("humanPowerW")
        val c = results.getValue("cadenceRpm")
        val h = results.getValue("heartRateBpm")
        val m = prepared.pin.metadata
        val selected = distance.selected
        val gpsLabel = prepared.locationSource?.let { DistancePolicy.label("gps:$it", m.sources) } ?: "unavailable"
        val hrLabel =
            prepared.healthSources["heartRateBpm"]?.let { ProviderNames.label(it, m.sources) } ?: "unavailable"
        val last = lastPreview
        val route = preview.toMutableList()
        if (last != null && route.lastOrNull()?.elapsedSeconds != last.elapsedSeconds) route.add(last)
        fun physical(q: Long?, scale: Long): Double? = q?.let { FixedPoint.divide(it, scale) }
        fun completeness(result: MetricResult, allowance: Long): String =
            if (result.statistics.max == null) "unavailable"
            else if (FixedPoint.add(result.coveredUs, allowance) < prepared.timeline.timerUs) "partial" else "observed"
        return Summary(
            m.id,
            m.startedAt,
            prepared.endedAt,
            FixedPoint.divide(prepared.actualEndUs, 1000000),
            FixedPoint.divide(prepared.timeline.timerUs, 1000000),
            distance,
            FixedPoint.divide(p.coveredUs, 1000000),
            FixedPoint.divide(h.coveredUs, 1000000),
            prepared.eventCount,
            prepared.counts["telemetry"] ?: 0,
            prepared.counts["location"] ?: 0,
            prepared.counts["health"] ?: 0,
            prepared.timeline.lapEnds.size,
            route,
            mapOf(
                "riderPower" to
                    "controller.humanPowerW; trapezoidal integral over adjacent active observations <=2.5 s",
                "cadence" to "controller.cadenceRpm; smoothed controller RPM",
                "gps" to gpsLabel,
                "heartRate" to hrLabel,
                "distance" to
                    (selected?.let {
                        "${it.label}; ${if (it.source.startsWith("gps:")) "gpsGeometry" else "controllerSpeed"}"
                    } ?: "unavailable"),
                "speed" to if (prepared.locationSource == null) "unavailable" else "$gpsLabel; speed in m/s",
                "rawData" to RAW_DATA,
            ),
            Completeness(
                if (selected == null) "unavailable" else if (selected.partial) "partial" else "observed",
                completeness(p, 2500000),
                completeness(h, 10000000),
                if (!m.recordGPS) "notRequested"
                else if (prepared.locationSource == null) "unavailable"
                else if (geometry.rejected > 0) "partial" else "observed",
            ),
            distanceMeters = selected?.distanceMeters,
            gpsDistanceMeters = physical(gpsQ, 1000000),
            averageSpeedMps =
                selected?.takeIf { it.coveredUs > 0 }?.let { FixedPoint.divide(it.distanceQ, it.coveredUs) },
            maximumSpeedMps = maximumSpeed,
            ascentMeters = physical(ascentQ, 1000),
            descentMeters = physical(descentQ, 1000),
            averageHeartRateBpm = h.average,
            maximumHeartRateBpm = h.statistics.max?.value?.real(),
            averageRiderPowerW = p.average,
            maximumRiderPowerW = p.statistics.max?.value?.real(),
            averageCadenceRpm = c.average,
            maximumCadenceRpm = c.statistics.max?.value?.real(),
            activeEnergyKcal = physical(activeQ, 1000000),
            basalEnergyKcal = physical(basalQ, 1000000),
            riderWorkJoules = if (p.coveredUs > 0) p.statistics.integral else null,
        )
    }

    companion object {
        const val RAW_DATA =
            "Canonical storage preserves every accepted source event; original ZIP exports that evidence and FIT contains a derived subset"
    }
}

sealed class PreparationOutcome {
    data class Prepared(val summary: PreparedSummary) : PreparationOutcome()

    data class Overflow(val failure: NumericOverflow) : PreparationOutcome()

    data class InvalidEvidence(val reason: String) : PreparationOutcome()
}
