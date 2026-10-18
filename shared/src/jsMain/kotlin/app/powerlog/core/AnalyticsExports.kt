@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.analytics.*
import app.powerlog.core.health.*
import kotlin.js.JsExport

@JsExport fun analyticsTimeUs(seconds: Double): String = FixedPoint.timeUs(seconds).toString()

@JsExport
fun analyticsQuantize(metric: String, kind: String, value: String): String? =
    Metrics.policy(metric).admit(jsScalar(kind, value))?.toString()

@JsExport
fun analyticsDivide(numerator: String, denominator: String): Double =
    FixedPoint.divide(numerator.toLong(), denominator.toLong())

@JsExport
fun analyticsGPSDistance(latitude0: Double, longitude0: Double, latitude1: Double, longitude1: Double): Double =
    PureMath.haversine(latitude0, longitude0, latitude1, longitude1)

@JsExport
fun analyticsValid(metric: String, value: Double?, accuracy: Double?): Boolean =
    Metrics.policy(metric).valid(value?.let { Scalar.Real64(it) }, accuracy?.let { Scalar.Real64(it) })

@JsExport
class AnalyticsTimeline(endUs: String, lifecycle: Array<AnalyticsItem>) {
    private val timeline = ActiveTimeline(endUs.toLong(), lifecycle.map { it.core() })
    val endUs = timeline.endUs.toString()
    val startsUs = timeline.intervals.map { it.startUs.toString() }.toTypedArray()
    val endsUs = timeline.intervals.map { it.endUs.toString() }.toTypedArray()
    val lapEndsUs = timeline.lapEnds.map { it.toString() }.toTypedArray()
    val timerUs = timeline.timerUs.toString()
    val activeThroughEnd = timeline.activeThroughEnd

    fun activeInterval(item: AnalyticsItem): Int? = timeline.membership(item.core())

    fun interruptionIndex(item: AnalyticsItem): Int = timeline.interruptionIndex(item.core())

    fun assign(
        elapsed: DoubleArray,
        kind: String,
        producers: Array<String?>?,
        sequences: DoubleArray?,
        epochs: Array<String?>?,
    ): AnalyticsAssignment {
        val intervals = IntArray(elapsed.size)
        val interruptions = IntArray(elapsed.size)
        elapsed.forEachIndexed { index, time ->
            if (time.isFinite()) {
                val producer = if (kind == "telemetry") "controller" else producers?.get(index) ?: "phone"
                val item =
                    AnalysisItem(
                        FixedPoint.timeUs(time),
                        time,
                        "",
                        "",
                        producer,
                        sequences?.get(index)?.takeIf { it.isFinite() }?.toLong() ?: 1L,
                        kind,
                        producer,
                        "",
                        emptyMap(),
                        continuity = Continuity(epochs?.get(index)),
                    )
                intervals[index] = (timeline.membership(item) ?: -1) + 1
                interruptions[index] = timeline.interruptionIndex(item)
            }
        }
        return AnalyticsAssignment(intervals, interruptions)
    }

    fun rangeTimerUs(startUs: String, endUs: String): String =
        timeline.intervals
            .fold(0L) { sum, interval ->
                FixedPoint.add(
                    sum,
                    maxOf(0L, minOf(endUs.toLong(), interval.endUs) - maxOf(startUs.toLong(), interval.startUs)),
                )
            }
            .toString()
}

@JsExport class AnalyticsAssignment internal constructor(val interval: IntArray, val interruption: IntArray)

@JsExport
class AnalyticsFitStatistics(endUs: String) {
    private val range = AnalysisRange(0, endUs.toLong())
    private val power = MetricReducer(Metrics.policy("humanPowerW"), range)
    private val cadence = MetricReducer(Metrics.policy("cadenceRpm"), range)
    private val motor = MetricReducer(Metrics.policy("motorInputPowerW"), range)
    private val heart = MetricReducer(Metrics.policy("heartRateBpm"), range)
    private val speed = MetricReducer(Metrics.policy("speedMps"), range)
    private var identity = 0L

    private fun append(
        reducer: MetricReducer,
        timeUs: Long,
        time: Double,
        value: Double,
        interval: Int,
        continuity: Continuity,
    ) {
        val id = (++identity).toString()
        reducer.append(
            MetricReading(
                timeUs,
                Original(id, id, "", time, Scalar.Real64(value)),
                interval.takeIf { it > 0 },
                continuity,
            )
        )
    }

    fun telemetry(
        elapsed: DoubleArray,
        watts: DoubleArray,
        rpm: DoubleArray,
        battery: DoubleArray,
        intervals: IntArray,
        runs: IntArray,
    ) {
        elapsed.forEachIndexed { index, time ->
            val timeUs = FixedPoint.timeUs(time)
            val continuity = Continuity(interruptionIndex = runs[index])
            append(power, timeUs, time, watts[index], intervals[index], continuity)
            append(cadence, timeUs, time, rpm[index], intervals[index], continuity)
            append(motor, timeUs, time, battery[index], intervals[index], continuity)
        }
    }

    fun heartRate(time: Double, value: Double, interval: Int, interruption: Int, clock: String?, connection: String?) {
        append(heart, FixedPoint.timeUs(time), time, value, interval, Continuity(clock, connection, interruption))
    }

    fun gpsSpeed(time: Double, value: Double, interval: Int) {
        append(speed, FixedPoint.timeUs(time), time, value, interval, Continuity())
    }

    fun maximumSpeed(): Double? = speed.result().statistics.max?.value?.real()

    fun powerResult(): AnalyticsStatistics = AnalyticsStatistics(power.result())

    fun cadenceResult(): AnalyticsStatistics = AnalyticsStatistics(cadence.result())

    fun motorResult(): AnalyticsStatistics = AnalyticsStatistics(motor.result())

    fun heartRateResult(): AnalyticsStatistics = AnalyticsStatistics(heart.result())
}

internal fun jsScalar(kind: String, value: String): Scalar =
    when (kind) {
        "integer64" -> Scalar.Integer64(value.toLong())
        "real64" -> Scalar.Real64(value.toDouble())
        else -> error("Unknown scalar kind")
    }

@JsExport
class AnalyticsOriginal internal constructor(original: Original) {
    val observationId = original.observationId
    val eventId = original.eventId
    val timestamp = original.timestamp
    val elapsedSeconds = original.elapsedSeconds
    val originalElapsedSeconds = original.originalElapsedSeconds
    val value = original.value.real()
    val exactValue = (original.value as? Scalar.Integer64)?.value?.toString()
}

@JsExport
class AnalyticsStatistics internal constructor(result: MetricResult) {
    val count = result.statistics.count.toString()
    val min = result.statistics.min?.let(::AnalyticsOriginal)
    val max = result.statistics.max?.let(::AnalyticsOriginal)
    val sampleMean = result.statistics.sampleMean
    val coveredSeconds = result.statistics.coveredSeconds
    val integral = result.statistics.integral
    val average = result.average
    val coveredUs = result.coveredUs.toString()
    val integralQUs = result.integralQUs?.toString()
    val sumQ = result.sumQ?.toString()
}

@JsExport
class AnalyticsMetricReducer(metric: String, startUs: String, endUs: String) {
    private val reducer = MetricReducer(Metrics.monitorPolicy(metric), AnalysisRange(startUs.toLong(), endUs.toLong()))

    fun append(
        timeUs: String,
        observationId: String,
        eventId: String,
        timestamp: String,
        elapsedSeconds: Double,
        scalarKind: String,
        value: String?,
        activeInterval: Int?,
        clockEpoch: String?,
        connectionEpoch: String?,
        interruptionIndex: Int,
        selected: Boolean,
        accuracy: Double?,
    ) {
        val original = value?.let {
            Original(observationId, eventId, timestamp, elapsedSeconds, jsScalar(scalarKind, it))
        }
        reducer.append(
            MetricReading(
                timeUs.toLong(),
                original,
                activeInterval,
                Continuity(clockEpoch, connectionEpoch, interruptionIndex),
                selected,
                accuracy?.let { Scalar.Real64(it) },
            )
        )
    }

    fun result(): AnalyticsStatistics? =
        (metricOutcome(reducer) as? MetricOutcome.Complete)?.result?.let(::AnalyticsStatistics)

    fun outcome(): String =
        when (val outcome = metricOutcome(reducer)) {
            is MetricOutcome.Complete -> "complete"
            is MetricOutcome.Overflow -> "numericOverflow:${outcome.failure.metric}:${outcome.failure.quantity}"
        }
}

@JsExport
class AnalyticsSource(val source: String, val device: String, val provider: String?, val dataOrigin: String?) {
    internal fun core() = MeasurementSource(source, device, provider, dataOrigin)
}

@JsExport
class AnalyticsItem(
    val collectionUs: String,
    val collectionElapsedSeconds: Double,
    val eventId: String,
    val observationId: String,
    val producer: String,
    val sequence: String,
    val kind: String,
    val source: String,
    val timestamp: String,
    val metricIds: Array<String>,
    val scalarKinds: Array<String>,
    val scalarValues: Array<String>,
    val originalElapsedSeconds: Double?,
    val representation: String?,
    val clockEpoch: String?,
    val connectionEpoch: String?,
    val interruptionIndex: Int,
    val action: String?,
    val distanceBarrier: Boolean,
    val interrupted: Boolean,
    val controllerSequence: String?,
    val hasElapsedMapping: Boolean,
    val origin: AnalyticsOrigin? = null,
    val declaredMetrics: Array<String>? = null,
) {
    internal fun core(): AnalysisItem {
        require(metricIds.size == scalarKinds.size && scalarKinds.size == scalarValues.size)
        return AnalysisItem(
            collectionUs.toLong(),
            collectionElapsedSeconds,
            eventId,
            observationId,
            producer,
            sequence.toLong(),
            kind,
            source,
            timestamp,
            metricIds.indices.associate { metricIds[it] to jsScalar(scalarKinds[it], scalarValues[it]) },
            originalElapsedSeconds,
            representation,
            Continuity(clockEpoch, connectionEpoch, interruptionIndex),
            action,
            distanceBarrier,
            interrupted,
            controllerSequence?.toLong(),
            hasElapsedMapping,
            origin?.core(),
            declaredMetrics?.toList(),
        )
    }
}

@JsExport
class AnalyticsMetadata(
    val id: String,
    val startedAt: String,
    val elapsedSeconds: Double,
    val indoor: Boolean,
    val watchEnabled: Boolean,
    val recordGPS: Boolean,
    val saveToHealth: Boolean,
    val sampleHz: Int,
    val endedAt: String?,
    val stopElapsedSeconds: Double?,
    val sources: Array<AnalyticsSource>,
    val completeEncodedMetadata: String,
    val phase: String = "running",
    val healthOutcome: String? = null,
    val healthReadbackPending: Boolean = true,
) {
    internal fun core() =
        AnalysisMetadata(
            id,
            startedAt,
            elapsedSeconds,
            indoor,
            watchEnabled,
            recordGPS,
            saveToHealth,
            sampleHz,
            endedAt,
            stopElapsedSeconds,
            sources.map { it.core() },
            completeEncodedMetadata,
            phase,
            healthOutcome?.let(app.powerlog.core.health.HealthOutcome::valueOf),
            healthReadbackPending,
        )
}

@JsExport
class AnalyticsPin(metadata: AnalyticsMetadata, observationRevision: String, distanceSelection: String) {
    internal val value = AnalysisPin(metadata.id, observationRevision.toLong(), metadata.core(), distanceSelection)

    fun identity(): String = CacheIdentity.of(value)

    fun filename(): String = CacheIdentity.filename(value)

    fun mayPublish(current: AnalyticsPin): Boolean = CacheIdentity.mayPublish(value, current.value)
}

@JsExport
class AnalyticsDistanceProfile(
    val source: String,
    val label: String,
    val estimated: Boolean,
    val partial: Boolean,
    val coveredUs: String,
    val uncoveredUs: String,
    val distanceQ: String,
) {
    internal fun core() =
        DistanceProfile(source, label, estimated, partial, coveredUs.toLong(), uncoveredUs.toLong(), distanceQ.toLong())

    val coveredSeconds
        get() = FixedPoint.divide(coveredUs.toLong(), 1000000)

    val uncoveredSeconds
        get() = FixedPoint.divide(uncoveredUs.toLong(), 1000000)

    val distanceMeters
        get() = FixedPoint.divide(distanceQ.toLong(), 1000000)
}

@JsExport
class AnalyticsPrepared internal constructor(internal val value: PreparedSummary) {
    val actualEndUs = value.actualEndUs.toString()
    val timerUs = value.timeline.timerUs.toString()
    val lapEndsUs = value.timeline.lapEnds.map { it.toString() }.toTypedArray()

    fun activeInterval(item: AnalyticsItem): Int? = value.timeline.membership(item.core())

    fun activeIntervalAt(collectionUs: String, producer: String, sequence: String, clockEpoch: String?): Int? =
        value.timeline.membership(collectionUs.toLong(), producer, sequence.toLong(), clockEpoch)

    fun rangeTimerUs(startUs: String, endUs: String): String {
        val start = startUs.toLong()
        val end = endUs.toLong()
        return value.timeline.intervals
            .fold(0L) { sum, interval ->
                FixedPoint.add(sum, maxOf(0L, minOf(end, interval.endUs) - maxOf(start, interval.startUs)))
            }
            .toString()
    }
}

@JsExport
class AnalyticsPreparation(pin: AnalyticsPin) {
    private val preparation = SummaryPreparation(pin.value)

    fun append(item: AnalyticsItem) {
        preparation.append(item.core())
    }

    fun energy(evidence: AnalyticsEnergy) {
        preparation.energy(evidence.core())
    }

    fun finish(selection: String, profiles: Array<AnalyticsDistanceProfile>): AnalyticsPrepared {
        val initial = preparation.finish()
        val m = initial.pin.metadata
        val info = selectDistance(selection, profiles.map { it.core() }, m.watchEnabled, m.indoor)
        return AnalyticsPrepared(preparation.finish(info))
    }
}

@JsExport
class AnalyticsDistance internal constructor(info: DistanceInfo) {
    private fun profile(p: DistanceProfile) =
        AnalyticsDistanceProfile(
            p.source,
            p.label,
            p.estimated,
            p.partial,
            p.coveredUs.toString(),
            p.uncoveredUs.toString(),
            p.distanceQ.toString(),
        )

    val selection = info.selection
    val selected = info.selected?.let(::profile)
    val available = info.available.map(::profile).toTypedArray()
}

@JsExport
class AnalyticsComposition(
    prepared: AnalyticsPrepared,
    selection: String,
    profiles: Array<AnalyticsDistanceProfile>,
    healthSelections: Array<AnalyticsHealthSelection>,
) {
    private val composer =
        SummaryComposer(
            prepared.value,
            selectDistance(
                selection,
                profiles.map { it.core() },
                prepared.value.pin.metadata.watchEnabled,
                prepared.value.pin.metadata.indoor,
            ),
            healthSelections.map { it.core() },
        )

    fun append(item: AnalyticsItem) {
        composer.append(AnalysisProjection.Observation(item.core()))
    }

    fun energy(evidence: AnalyticsEnergy) {
        composer.energy(evidence.core())
    }

    fun cancel() {
        composer.cancel()
    }

    fun summaryJson(lifecycleCount: String): String? =
        (composer.finish() as? SummaryOutcome.Complete)?.summary?.let {
            SummaryWire.encode(it, lifecycleCount.toLong())
        }

    fun outcome(): String =
        when (val result = composer.finish()) {
            is SummaryOutcome.Complete -> "complete"
            is SummaryOutcome.Cancelled -> "cancelled"
            is SummaryOutcome.InvalidEvidence -> "invalidEvidence:${result.reason}"
            is SummaryOutcome.Overflow -> "numericOverflow:${result.failure.metric}:${result.failure.quantity}"
        }
}

@JsExport
class AnalyticsOrigin(
    val provider: String?,
    val dataOrigin: String?,
    val providerObservationId: String?,
    val originEventId: String,
    val producer: String,
    val sequence: String,
    val originRevision: String?,
) {
    internal fun core() =
        Origin(provider, dataOrigin, providerObservationId, originEventId, producer, sequence.toLong(), originRevision)
}

@JsExport
class AnalyticsEnergy(
    val metric: String,
    val kind: String,
    val source: String,
    val origin: AnalyticsOrigin,
    val observationRevision: String,
    collectionUs: String,
    val startUs: String,
    val endUs: String,
    val originalStartTimestamp: String,
    val originalEndTimestamp: String,
    val valueKcal: Double,
) {
    private val pointCollectionUs = collectionUs
    val collectionUs: String
        get() = core().collectionUs.toString()

    internal fun core(): EnergyEvidence {
        if (kind == "intervalRecord")
            return EnergyEvidence.IntervalRecord(
                metric,
                EnergyInterval(
                    source,
                    origin.core(),
                    observationRevision.toLong(),
                    startUs.toLong(),
                    endUs.toLong(),
                    originalStartTimestamp,
                    originalEndTimestamp,
                    valueKcal,
                ),
            )
        val point =
            EnergyPoint(
                source,
                origin.core(),
                observationRevision.toLong(),
                pointCollectionUs.toLong(),
                originalStartTimestamp,
                valueKcal,
                Association.RideWindow(startUs.toLong(), endUs.toLong()),
            )
        return when (kind) {
            "cumulativeSnapshot" -> EnergyEvidence.CumulativeSnapshot(metric, point)
            "finalWorkoutTotal" -> EnergyEvidence.FinalWorkoutTotal(metric, point)
            else -> error("Unknown energy kind")
        }
    }
}

@JsExport
fun analyticsHealthSource(owner: String, available: Array<String>): String? =
    selectedHealthSource(owner, available.toSet())

@JsExport fun analyticsEnergyAvailable(evidence: AnalyticsEnergy): Boolean = energyAvailable(evidence.core())

@JsExport
class AnalyticsInterval internal constructor(internal val value: DistanceInterval) {
    val startUs = value.startUs.toString()
    val endUs = value.endUs.toString()
    val distanceQ = value.distanceQ.toString()
    val segment = value.segment
    val activeInterval = value.activeInterval
    val start = AnalyticsOriginal(value.start)
    val end = AnalyticsOriginal(value.end)
    val speed0Q = value.speed0Q?.toString()
    val speed1Q = value.speed1Q?.toString()
    val indivisible = value.indivisible
}

@JsExport
class AnalyticsDistanceRange internal constructor(internal val value: DistanceRange) {
    val distanceQ = value.distanceQ.toString()
    val coveredUs = value.coveredUs.toString()
    val distanceMeters = value.distanceMeters
    val coveredSeconds = value.coveredSeconds
    val unresolvedBoundary = value.unresolvedBoundary
    val partial = value.partial
}

@JsExport
class AnalyticsDistanceReducer(startUs: String, endUs: String, distanceQ: String = "0", coveredUs: String = "0") {
    private val reducer =
        DistanceReducer(
            AnalysisRange(startUs.toLong(), endUs.toLong()),
            DistanceRange(distanceQ.toLong(), coveredUs.toLong(), false, false),
        )

    fun append(interval: AnalyticsInterval) {
        reducer.append(interval.value)
    }

    fun result(timerUs: String): AnalyticsDistanceRange = AnalyticsDistanceRange(reducer.result(timerUs.toLong()))
}

@JsExport
class AnalyticsGPSKernel(private val fullContinuity: Boolean) {
    private var kernel = GPSKernel(fullContinuity)
    var accepted = false
        private set

    var startsSegment = false
        private set

    val segment
        get() = kernel.segment

    val altitudeSegments
        get() = kernel.altitudeSegments.toString()

    fun append(item: AnalyticsItem, activeInterval: Int?): AnalyticsInterval? {
        val result = kernel.append(GPSFix(item.core(), activeInterval))
        accepted = result.accepted
        startsSegment = result.startsSegment
        return result.interval?.let(::AnalyticsInterval)
    }

    fun reset() {
        kernel.reset()
    }

    fun restoreDistance(
        previous: AnalyticsItem?,
        activeInterval: Int?,
        geometryPrevious: AnalyticsItem?,
        geometryActiveInterval: Int?,
        segment: Int,
    ) {
        kernel = GPSKernel(fullContinuity)
        kernel.restoreDistanceCheckpoint(
            GPSDistanceCheckpoint(
                previous?.let { GPSFix(it.core(), activeInterval) },
                geometryPrevious?.let { GPSFix(it.core(), geometryActiveInterval) },
                segment,
            )
        )
    }

    fun previousObservationId(): String? = kernel.distanceCheckpoint().previous?.item?.observationId

    fun geometryPreviousObservationId(): String? = kernel.distanceCheckpoint().geometryPrevious?.item?.observationId

    fun distanceQ(): String? = kernel.distanceQ()?.toString()

    fun ascentQ(): String? = kernel.ascentQ()?.toString()

    fun descentQ(): String? = kernel.descentQ()?.toString()
}

@JsExport
class AnalyticsHealthSelection(
    val metric: String,
    val source: String,
    val dataOrigin: String?,
    val coveredUs: String,
    val origins: Array<AnalyticsOrigin>,
    val startUs: String,
    val endUs: String,
    val workoutIdentity: String?,
) {
    internal fun core() =
        HealthSelection(
            metric,
            source,
            dataOrigin,
            coveredUs.toLong(),
            origins.map { it.core() },
            workoutIdentity?.let { Association.Workout(it) }
                ?: Association.RideWindow(startUs.toLong(), endUs.toLong()),
        )
}

@JsExport
class AnalyticsCoverage internal constructor(score: OriginCoverageScore) {
    val dataOrigin = score.dataOrigin
    val coveredUs = score.coveredUs.toString()
}

@JsExport
class AnalyticsEnergyOrigins(source: String, startUs: String, endUs: String) {
    private val reducer = EnergyOriginPreparation(source, AnalysisRange(startUs.toLong(), endUs.toLong()))

    fun append(metric: String, evidence: AnalyticsEnergy) {
        reducer.append(metric, (evidence.core() as EnergyEvidence.IntervalRecord).interval)
    }

    fun selected(): AnalyticsCoverage? = reducer.selected()?.let(::AnalyticsCoverage)
}

@JsExport
class AnalyticsControllerKernel {
    private var kernel = ControllerDistanceKernel()

    fun restore(
        item: AnalyticsItem?,
        activeInterval: Int?,
        model: String?,
        protocol: String?,
        identity: String?,
        continuity: String?,
        segment: Int,
        monotonicSeconds: Double? = null,
        counter: String? = null,
    ) {
        val previous = item?.let {
            val original = it.core()
            ControllerDistanceEvidence(
                original.collectionUs,
                original.collectionElapsedSeconds,
                original.number("controllerSpeedMps"),
                model,
                protocol,
                identity,
                continuity,
                activeInterval,
                monotonicSeconds,
                counter?.let { Scalar.Integer64(it.toLong()) },
                original.distanceBarrier,
                originalAnchor(original, original.number("controllerSpeedMps") ?: 0.0),
            )
        }
        kernel = ControllerDistanceKernel(ControllerDistanceCheckpoint(previous, segment))
    }

    fun previousObservationId(): String? = kernel.checkpoint().previous?.anchor?.observationId

    fun segment(): Int = kernel.checkpoint().segment

    fun append(
        item: AnalyticsItem,
        activeInterval: Int?,
        model: String?,
        protocol: String?,
        deviceIdentity: String?,
        continuityIdentity: String?,
        monotonicSeconds: Double?,
        counter: String?,
    ): AnalyticsInterval? {
        val original = item.core()
        val speed = original.values["controllerSpeedMps"]?.real()
        val anchor = originalAnchor(original, speed ?: 0.0)
        return kernel
            .append(
                ControllerDistanceEvidence(
                    original.collectionUs,
                    original.collectionElapsedSeconds,
                    speed,
                    model,
                    protocol,
                    deviceIdentity,
                    continuityIdentity,
                    activeInterval,
                    monotonicSeconds,
                    counter?.let { Scalar.Integer64(it.toLong()) },
                    original.distanceBarrier,
                    anchor,
                )
            )
            ?.let(::AnalyticsInterval)
    }
}

@JsExport
fun analyticsStoredInterval(
    startUs: String,
    endUs: String,
    distanceQ: String,
    segment: Int,
    activeInterval: Int,
    start: AnalyticsItem,
    end: AnalyticsItem,
    speed0Q: String?,
    speed1Q: String?,
    indivisible: Boolean,
): AnalyticsInterval =
    AnalyticsInterval(
        DistanceInterval(
            startUs.toLong(),
            endUs.toLong(),
            distanceQ.toLong(),
            segment,
            activeInterval,
            originalAnchor(start.core(), 0.0),
            originalAnchor(end.core(), 0.0),
            speed0Q?.toLong(),
            speed1Q?.toLong(),
            indivisible,
        )
    )

private fun analyticsProfile(
    source: String,
    range: AnalyticsDistanceRange,
    timerUs: String,
    sources: Array<AnalyticsSource>,
): AnalyticsDistanceProfile? =
    distanceProfile(source, range.value, timerUs.toLong(), sources.map { it.core() })?.let {
        AnalyticsDistanceProfile(
            it.source,
            it.label,
            it.estimated,
            it.partial,
            it.coveredUs.toString(),
            it.uncoveredUs.toString(),
            it.distanceQ.toString(),
        )
    }

@JsExport
fun analyticsDistanceSelection(
    selection: String,
    profiles: Array<AnalyticsDistanceProfile>,
    indoor: Boolean,
): AnalyticsDistance = AnalyticsDistance(selectDistance(selection, profiles.map { it.core() }, false, indoor))

private fun originalAnchor(item: AnalysisItem, value: Double): Original =
    Original(
        item.observationId,
        item.eventId,
        item.timestamp,
        item.collectionElapsedSeconds,
        Scalar.Real64(value),
        item.originalElapsedSeconds,
    )

@JsExport
fun analyticsProfileTotals(
    source: String,
    distanceQ: String,
    coveredUs: String,
    timerUs: String,
    sources: Array<AnalyticsSource>,
): AnalyticsDistanceProfile? =
    analyticsProfile(
        source,
        AnalyticsDistanceRange(DistanceRange(distanceQ.toLong(), coveredUs.toLong(), false, false)),
        timerUs,
        sources,
    )

@JsExport
fun analyticsDistanceLabel(source: String, sources: Array<AnalyticsSource>): String =
    DistancePolicy.label(source, sources.map { it.core() })

@JsExport
class AnalyticsHeartRateOrigins(source: String, startUs: String, endUs: String) {
    private val window = AnalysisRange(startUs.toLong(), endUs.toLong())
    private val reducer = HeartRateOriginPreparation(source, window)

    fun append(
        collectionUs: String,
        origin: AnalyticsOrigin,
        value: Double?,
        clockEpoch: String?,
        connectionEpoch: String?,
    ) {
        val time = collectionUs.toLong()
        reducer.append(
            HeartRateEvidence(
                origin.core(),
                origin.producer,
                Association.RideWindow(window.startUs, window.endUs),
                "",
                "",
                time,
                time,
                time,
                time,
                Continuity(clockEpoch, connectionEpoch),
                "rawQuantity",
                null,
                1L,
                value,
                "bpm",
                1L,
            )
        )
    }

    fun selected(): String? = reducer.selected()?.dataOrigin
}

@JsExport
fun analyticsCollectionAvailable(collectionUs: String): Boolean = summaryCollectionAvailable(collectionUs.toLong())

@JsExport
fun distanceResolveAtStart(selection: String, recordGPS: Boolean, indoor: Boolean): String =
    DistancePolicy.resolveAtStart(selection, recordGPS, indoor)
