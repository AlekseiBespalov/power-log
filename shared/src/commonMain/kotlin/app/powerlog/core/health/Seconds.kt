package app.powerlog.core.health

import app.powerlog.core.analytics.*

object SecondKernel {
    @Throws(IllegalArgumentException::class)
    fun policy(kind: HealthKind): MetricPolicy =
        when (kind) {
            HealthKind.riderPower -> MetricPolicy(kind.wire, 1000, 0.0, 5000.0, 2500000)
            HealthKind.cyclingCadence -> MetricPolicy(kind.wire, 10000, 0.0, 300.0, 2500000)
            HealthKind.cyclingSpeed -> MetricPolicy(kind.wire, 1000000, 0.0, 40.0, 10000000)
            HealthKind.cyclingDistance -> throw IllegalArgumentException("Distance uses original edges")
        }

    @Throws(IllegalArgumentException::class)
    fun aggregate(bounds: SecondBounds, kind: HealthKind, readings: Array<SecondReading>): SecondOutcome {
        if (readings.size > 128) return SecondOutcome.Invalid(HealthReason.invalid)
        val policy = policy(kind)
        val sorted =
            readings.sortedWith(
                compareBy<SecondReading> { it.utcUs }
                    .thenBy { it.origin.producer }
                    .thenBy { it.origin.sequence }
                    .thenBy { it.origin.originEventId }
            )
        val reducer = MetricReducer(policy, AnalysisRange(bounds.startUs, bounds.endUs))
        var count = 0L
        var source: MeasurementSource? = null
        var previous: SecondReading? = null
        var anyActive = false
        try {
            for (reading in sorted) {
                val value = reading.originalScalar
                val speedFix =
                    kind != HealthKind.cyclingSpeed ||
                        reading.horizontalAccuracyM?.let { it.isFinite() && it >= 0 && it <= 50 } == true
                val sourceValid =
                    when (kind) {
                        HealthKind.riderPower,
                        HealthKind.cyclingCadence ->
                            reading.source.source == Source.controller && reading.source.device == Device.controller
                        HealthKind.cyclingSpeed ->
                            reading.source.source in listOf(Source.phone, Source.watch) &&
                                reading.source.device in
                                    listOf(Device.iphone, Device.androidPhone, Device.appleWatch, Device.wearWatch)
                        else -> false
                    }
                val valid =
                    sourceValid &&
                        !reading.barrier &&
                        reading.activeInterval != null &&
                        policy.valid(value, reading.speedAccuracyMps?.let { Scalar.Real64(it) }) &&
                        speedFix
                if (reading.activeInterval != null) anyActive = true
                if (valid && reading.utcUs >= bounds.startUs && reading.utcUs < bounds.endUs) {
                    count = FixedPoint.add(count, 1)
                    if (source == null) source = reading.source
                }
                if (valid && reading.admittedQ != null) require(policy.admit(value) == reading.admittedQ)
                if (
                    previous != null &&
                        (reading.collectionUs <= previous.collectionUs ||
                            FixedPoint.subtract(reading.collectionUs, previous.collectionUs) > policy.gapUs ||
                            reading.source != previous.source)
                )
                    reducer.append(MetricReading(reading.utcUs, null, null, reading.continuity))
                val original =
                    if (valid)
                        Original(
                            reading.origin.originEventId,
                            reading.origin.originEventId,
                            reading.originalUTC,
                            FixedPoint.divide(reading.collectionUs, 1000000),
                            requireNotNull(value),
                        )
                    else null
                reducer.append(
                    MetricReading(
                        reading.utcUs,
                        original,
                        if (valid) reading.activeInterval else null,
                        reading.continuity,
                    )
                )
                previous = reading
            }
            val result = reducer.result()
            if (count == 0L)
                return SecondOutcome.Absent(
                    if (readings.isNotEmpty() && !anyActive) Absence.inactive else Absence.noValidReading
                )
            if (result.coveredUs == 0L) return SecondOutcome.Absent(Absence.noCoverage)
            return SecondOutcome.Sample(
                SecondValue(
                    bounds.n,
                    bounds.startUs,
                    bounds.endUs,
                    count,
                    requireNotNull(result.integralQUs),
                    result.coveredUs,
                    requireNotNull(result.average),
                    requireNotNull(source),
                )
            )
        } catch (e: NumericFailure) {
            return SecondOutcome.NumericOverflow(kind, e.failure.quantity)
        } catch (_: ArithmeticException) {
            return SecondOutcome.NumericOverflow(kind, Quantity.integralQUs)
        }
    }
}
