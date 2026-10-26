package app.powerlog.core.analytics

data class MetricPolicy(
    val id: String,
    val scale: Long,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val gapUs: Long = 2500000,
    val suppressed: Boolean = false,
) {
    fun valid(value: Scalar?, accuracy: Scalar? = null): Boolean {
        val n = value?.real() ?: return false
        val validAccuracy = accuracy == null || accuracy.real().isFinite() && accuracy.real() >= 0
        return validAccuracy && n.isFinite() && (minimum == null || n >= minimum) && (maximum == null || n <= maximum)
    }

    fun admit(value: Scalar?): Long? = if (valid(value)) FixedPoint.quantize(requireNotNull(value), scale) else null
}

object Metrics {
    private val policies =
        listOf(
                MetricPolicy("humanPowerW", 1000, 0.0, 32766.0),
                MetricPolicy("cadenceRpm", 10000, 0.0, 254.0),
                MetricPolicy("heartRateBpm", 1000, 1.0, 254.0, 10000000),
                MetricPolicy("speedMps", 1000000, 0.0, 40.0, 10000000),
                MetricPolicy("speedAccuracyMps", 1000000, 0.0, gapUs = 10000000),
                MetricPolicy("controllerSpeedMps", 1000000, 0.0, 40.0),
                MetricPolicy("distanceMeters", 1000000, 0.0),
                MetricPolicy("gpsDistanceMeters", 1000000, 0.0, suppressed = true),
                MetricPolicy("controllerDistanceMeters", 1000000, 0.0, suppressed = true),
                MetricPolicy("controllerEnergyWh", 1000000, 0.0, suppressed = true),
                MetricPolicy("controllerChargeAh", 1000000, 0.0, suppressed = true),
                MetricPolicy("calculatedEnergyWh", 1000000, 0.0, suppressed = true),
                MetricPolicy("calculatedChargeAh", 1000000, 0.0, suppressed = true),
                MetricPolicy("batteryWhPerKm", 1000000, 0.0, suppressed = true),
                MetricPolicy("riderWhPerKm", 1000000, 0.0, suppressed = true),
                MetricPolicy("altitudeMeters", 1000, -500.0, 20000.0, 10000000),
                MetricPolicy("horizontalAccuracyM", 1000, 0.0, 50.0, 10000000),
                MetricPolicy("verticalAccuracyM", 1000, 0.0, 20.0, 10000000),
                MetricPolicy("courseDegrees", 1000, 0.0, suppressed = true),
                MetricPolicy("activeEnergyKcal", 1000000, 0.0, suppressed = true),
                MetricPolicy("basalEnergyKcal", 1000000, 0.0, suppressed = true),
                MetricPolicy("consumedAh", 10000, suppressed = true),
                MetricPolicy("consumedWh", 10000, suppressed = true),
                MetricPolicy("assistLevel", 1, suppressed = true),
                MetricPolicy("raceMode", 1, suppressed = true),
                MetricPolicy("faultCode", 1, suppressed = true),
                MetricPolicy("crankRevolutions", 1, suppressed = true),
                MetricPolicy("motorInputPowerW", 1000),
                MetricPolicy("batteryCurrentA", 100),
                MetricPolicy("motorCurrentA", 100),
                MetricPolicy("pedalTorqueNm", 100),
                MetricPolicy("motorTempC", 10),
                MetricPolicy("controllerTempC", 10),
                MetricPolicy("batteryVoltageV", 10),
                MetricPolicy("throttleVoltageV", 100),
                MetricPolicy("motorRpm", 1),
                MetricPolicy("speedRaw", 100),
            )
            .associateBy { it.id }

    fun policy(id: String): MetricPolicy = requireNotNull(policies[id]) { "Unknown metric $id" }

    private val monitorPolicies = policies.mapValues { (id, policy) ->
        when (id) {
            "speedMps",
            "controllerSpeedMps",
            "horizontalAccuracyM",
            "verticalAccuracyM" -> policy.copy(maximum = null)
            "altitudeMeters" -> policy.copy(minimum = null, maximum = null)
            else -> policy
        }
    }
    private val plotPolicies = monitorPolicies.mapValues { (id, policy) ->
        if (id == "humanPowerW" || id == "cadenceRpm") policy.copy(minimum = null, maximum = null) else policy
    }

    fun monitorPolicy(id: String): MetricPolicy = requireNotNull(monitorPolicies[id]) { "Unknown metric $id" }

    fun plotValid(item: AnalysisItem, metric: String): Boolean {
        val accuracy =
            when (metric) {
                "speedMps",
                "controllerSpeedMps" -> "speedAccuracyMps"
                "courseDegrees" -> "courseAccuracyDegrees"
                else -> null
            }
        return item.isReadingOf(metric) && plotValid(metric, item.values[metric], accuracy?.let { item.values[it] })
    }

    fun plotValid(metric: String, value: Scalar?, accuracy: Scalar? = null): Boolean {
        val policy = requireNotNull(plotPolicies[metric]) { "Unknown metric $metric" }
        return policy.valid(value, accuracy) && (metric != "courseDegrees" || requireNotNull(value).real() < 360)
    }

    fun validSpeed(speed: Double?, accuracy: Double? = null): Boolean =
        speed != null &&
            speed.isFinite() &&
            speed >= 0.0 &&
            speed <= 40.0 &&
            (accuracy == null || accuracy.isFinite() && accuracy >= 0)
}

class MetricValueReference(val reference: Long, val value: Scalar?, private val identity: (Long) -> String) {
    fun observationId(): String = identity(reference)

    override fun equals(other: Any?): Boolean =
        other is MetricValueReference && reference == other.reference && value == other.value

    override fun hashCode(): Int = 31 * reference.hashCode() + (value?.hashCode() ?: 0)
}

data class MetricReading(
    val timeUs: Long,
    val original: Original?,
    val activeInterval: Int?,
    val continuity: Continuity,
    val selected: Boolean = true,
    val accuracy: Scalar? = null,
    val reference: MetricValueReference? = null,
) {
    val value: Scalar?
        get() = original?.value ?: reference?.value

    fun observationId(): String = original?.observationId ?: requireNotNull(reference).observationId()
}

data class MetricAdmission(val reading: MetricReading, val valueQ: Long)

data class MetricStatistics(
    val count: Long,
    val min: Original?,
    val max: Original?,
    val sampleMean: Double?,
    val coveredSeconds: Double?,
    val integral: Double?,
)

data class MetricResult(
    val statistics: MetricStatistics,
    val coveredUs: Long,
    val integralQUs: Long?,
    val sumQ: Long?,
    val average: Double?,
    val previous: MetricAdmission?,
    val minimum: MetricAdmission? = null,
    val maximum: MetricAdmission? = null,
)

data class MetricCheckpoint(
    val metric: String,
    val range: AnalysisRange,
    val count: Long,
    val sum: SignedSum,
    val integral: SignedSum,
    val covered: SignedSum,
    val minimum: MetricAdmission?,
    val maximum: MetricAdmission?,
    val previous: MetricAdmission?,
    val firstRaw: MetricReading?,
    val lastRaw: MetricReading?,
    val overflowQuantities: Set<Quantity>,
    val bucket: Boolean = false,
    val leadingPredecessor: MetricAdmission? = null,
)

private class MetricInput {
    var timeUs = 0L
    var value: Scalar? = null
    var activeInterval: Int? = null
    var continuity = Continuity()
    var accuracy: Scalar? = null
    var selected = true
    private var retained: MetricReading? = null
    private var observation = ""
    private var event = ""
    private var timestamp = ""
    private var elapsed = 0.0
    private var originalElapsed: Double? = null

    fun assign(reading: MetricReading) {
        timeUs = reading.timeUs
        value = reading.value
        activeInterval = reading.activeInterval
        continuity = reading.continuity
        accuracy = reading.accuracy
        selected = reading.selected
        retained = reading
    }

    fun assign(input: MetricInput) {
        timeUs = input.timeUs
        value = input.value
        activeInterval = input.activeInterval
        continuity = input.continuity
        accuracy = input.accuracy
        selected = input.selected
        retained = input.retained
        observation = input.observation
        event = input.event
        timestamp = input.timestamp
        elapsed = input.elapsed
        originalElapsed = input.originalElapsed
    }

    fun telemetry(
        timeUs: Long,
        value: Scalar?,
        activeInterval: Int?,
        continuity: Continuity,
        observationId: String,
        eventId: String,
        timestamp: String,
        elapsed: Double,
        originalElapsed: Double?,
    ) {
        this.timeUs = timeUs
        this.value = value
        this.activeInterval = activeInterval
        this.continuity = continuity
        this.observation = observationId
        this.event = eventId
        this.timestamp = timestamp
        this.elapsed = elapsed
        this.originalElapsed = originalElapsed
        accuracy = null
        selected = true
        retained = null
    }

    fun observationId() = retained?.observationId() ?: observation

    fun snapshot(): MetricReading =
        retained
            ?: MetricReading(
                timeUs,
                value?.let { Original(observation, event, timestamp, elapsed, it, originalElapsed) },
                activeInterval,
                continuity,
            )
}

class MetricReducer(
    val policy: MetricPolicy,
    val range: AnalysisRange,
    predecessor: MetricAdmission? = null,
    checkpoint: MetricCheckpoint? = null,
    val bucket: Boolean = false,
) {
    private var count = 0L
    private val sum = CheckedSum(checkpoint?.sum ?: SignedSum())
    private val integral = CheckedSum(checkpoint?.integral ?: SignedSum())
    private val covered = CheckedSum(checkpoint?.covered ?: SignedSum())
    private var minimum: MetricAdmission? = null
    private var maximum: MetricAdmission? = null
    private val input = MetricInput()
    private val lastRaw = MetricInput()
    private val previous = MetricInput()
    private var hasLastRaw = false
    private var hasPrevious = predecessor != null
    private var previousQ = predecessor?.valueQ ?: 0L
    private var firstRaw: MetricReading? = null
    private val overflow = mutableSetOf<Quantity>()
    private val leadingPredecessor = checkpoint?.leadingPredecessor ?: predecessor

    init {
        predecessor?.let { previous.assign(it.reading) }
        if (checkpoint != null) {
            require(checkpoint.metric == policy.id && checkpoint.range == range && checkpoint.bucket == bucket)
            count = checkpoint.count
            minimum = checkpoint.minimum
            maximum = checkpoint.maximum
            hasPrevious = checkpoint.previous != null
            checkpoint.previous?.let {
                previous.assign(it.reading)
                previousQ = it.valueQ
            }
            firstRaw = checkpoint.firstRaw
            hasLastRaw = checkpoint.lastRaw != null
            checkpoint.lastRaw?.let { lastRaw.assign(it) }
            overflow.addAll(checkpoint.overflowQuantities)
        }
    }

    fun checkpoint(): MetricCheckpoint? =
        if (firstRaw == null) null
        else
            MetricCheckpoint(
                policy.id,
                range,
                count,
                sum.snapshot(),
                integral.snapshot(),
                covered.snapshot(),
                minimum,
                maximum,
                previousAdmission(),
                firstRaw,
                if (hasLastRaw) lastRaw.snapshot() else null,
                overflow.toSet(),
                bucket,
                leadingPredecessor,
            )

    fun mergeOwned(other: MetricCheckpoint) {
        require(other.metric == policy.id)
        require(other.range.startUs >= range.startUs && other.range.endUs <= range.endUs)
        require(other.bucket || other.range == range)
        require(
            !other.bucket ||
                other.leadingPredecessor == null ||
                other.leadingPredecessor.reading.timeUs >= range.startUs ||
                bucket && other.range.startUs == range.startUs && other.leadingPredecessor == leadingPredecessor
        )
        try {
            count = FixedPoint.add(count, other.count)
        } catch (_: ArithmeticException) {
            overflow.add(Quantity.count)
        }
        sum.merge(other.sum)
        integral.merge(other.integral)
        covered.merge(other.covered)
        fun pick(a: MetricAdmission?, b: MetricAdmission?, minimum: Boolean): MetricAdmission? {
            if (a == null) return b
            if (b == null) return a
            val order = compareScalars(requireNotNull(a.reading.value), requireNotNull(b.reading.value))
            return if (
                (if (minimum) order < 0 else order > 0) ||
                    order == 0 &&
                        before(a.reading.timeUs, a.reading.continuity.interruptionIndex, b) {
                            a.reading.observationId()
                        }
            )
                a
            else b
        }
        minimum = pick(minimum, other.minimum, true)
        maximum = pick(maximum, other.maximum, false)
        if (firstRaw == null) firstRaw = other.firstRaw
        if (other.lastRaw != null) {
            lastRaw.assign(other.lastRaw)
            hasLastRaw = true
            hasPrevious = other.previous != null
            other.previous?.let {
                previous.assign(it.reading)
                previousQ = it.valueQ
            }
        }
        overflow.addAll(other.overflowQuantities)
    }

    private inline fun before(
        timeUs: Long,
        interruption: Int,
        other: MetricAdmission,
        identity: () -> String,
    ): Boolean =
        when {
            timeUs != other.reading.timeUs -> timeUs < other.reading.timeUs
            interruption != other.reading.continuity.interruptionIndex ->
                interruption < other.reading.continuity.interruptionIndex
            else -> compareUtf8(identity(), other.reading.observationId()) < 0
        }

    private fun previousAdmission(): MetricAdmission? =
        if (hasPrevious) MetricAdmission(previous.snapshot(), previousQ) else null

    internal fun appendTelemetry(
        timeUs: Long,
        scalar: Scalar?,
        activeInterval: Int?,
        continuity: Continuity,
        observationId: String,
        eventId: String,
        timestamp: String,
        elapsed: Double,
        originalElapsed: Double?,
    ) {
        input.telemetry(
            timeUs,
            scalar,
            activeInterval,
            continuity,
            observationId,
            eventId,
            timestamp,
            elapsed,
            originalElapsed,
        )
        appendInput()
    }

    fun append(reading: MetricReading) {
        input.assign(reading)
        appendInput()
    }

    private fun appendInput() {
        val reading = input
        if (!reading.selected) return
        val owned =
            reading.timeUs >= range.startUs &&
                (if (bucket) reading.timeUs < range.endUs else reading.timeUs <= range.endUs)
        if (bucket && !owned) return
        if (firstRaw == null) firstRaw = reading.snapshot()
        lastRaw.assign(reading)
        hasLastRaw = true
        val scalar = reading.value
        if (
            reading.activeInterval == null ||
                !policy.valid(scalar, reading.accuracy) ||
                policy.id == "courseDegrees" && requireNotNull(scalar).real() >= 360
        ) {
            hasPrevious = false
            return
        }
        val q =
            try {
                if (policy.suppressed) 0L else FixedPoint.quantize(requireNotNull(scalar), policy.scale)
            } catch (_: ArithmeticException) {
                overflow.add(Quantity.sumQ)
                hasPrevious = false
                return
            }
        fun before(other: MetricAdmission): Boolean =
            before(reading.timeUs, reading.continuity.interruptionIndex, other) { reading.observationId() }

        if (owned) {
            try {
                count = FixedPoint.add(count, 1)
            } catch (_: ArithmeticException) {
                overflow.add(Quantity.count)
            }
            if (!policy.suppressed) sum.add(q)
            val low = minimum
            val high = maximum
            val keepMinimum =
                low == null ||
                    compareScalars(requireNotNull(scalar), requireNotNull(low.reading.value)) < 0 ||
                    compareScalars(requireNotNull(scalar), requireNotNull(low.reading.value)) == 0 && before(low)
            val keepMaximum =
                high == null ||
                    compareScalars(requireNotNull(scalar), requireNotNull(high.reading.value)) > 0 ||
                    compareScalars(requireNotNull(scalar), requireNotNull(high.reading.value)) == 0 && before(high)
            if (keepMinimum || keepMaximum) {
                val current = MetricAdmission(reading.snapshot(), q)
                if (keepMinimum) minimum = current
                if (keepMaximum) maximum = current
            }
        }
        val old = previous
        if (
            !policy.suppressed &&
                hasPrevious &&
                old.activeInterval == reading.activeInterval &&
                old.continuity == reading.continuity
        ) {
            val duration =
                try {
                    FixedPoint.subtract(reading.timeUs, old.timeUs)
                } catch (_: ArithmeticException) {
                    0L
                }
            if (duration > 0L && duration <= policy.gapUs) {
                val a = if (bucket) old.timeUs else maxOf(old.timeUs, range.startUs)
                val b = if (bucket) reading.timeUs else minOf(reading.timeUs, range.endUs)
                if (b > a) {
                    try {
                        val va = FixedPoint.interpolate(previousQ, q, a - old.timeUs, duration)
                        val vb = FixedPoint.interpolate(previousQ, q, b - old.timeUs, duration)
                        integral.add(FixedPoint.trapezoid(va, vb, b - a))
                        covered.add(b - a)
                    } catch (_: ArithmeticException) {
                        overflow.add(Quantity.integralQUs)
                    }
                }
            }
        }
        previous.assign(reading)
        previousQ = q
        hasPrevious = true
    }

    fun result(): MetricResult {
        if (sum.positiveOverflow || sum.negativeOverflow) overflow.add(Quantity.sumQ)
        if (integral.positiveOverflow || integral.negativeOverflow) overflow.add(Quantity.integralQUs)
        if (covered.positiveOverflow) overflow.add(Quantity.coveredUs)
        if (overflow.isNotEmpty()) throw NumericFailure(NumericOverflow(policy.id, overflow.minBy { it.ordinal }))
        val c = covered.value(policy.id, Quantity.coveredUs)
        val s = if (policy.suppressed) null else sum.value(policy.id, Quantity.sumQ)
        val i = if (policy.suppressed) null else integral.value(policy.id, Quantity.integralQUs)
        fun output(n: Long, d: Long, quantity: Quantity): Double =
            try {
                FixedPoint.divide(n, FixedPoint.product(policy.scale, d))
            } catch (_: ArithmeticException) {
                throw NumericFailure(NumericOverflow(policy.id, quantity))
            }
        return MetricResult(
            MetricStatistics(
                count,
                minimum?.reading?.original,
                maximum?.reading?.original,
                if (count > 0 && s != null) output(s, count, Quantity.sumQ) else null,
                if (policy.suppressed) null else FixedPoint.divide(c, 1000000),
                if (i == null) null else output(i, 1000000, Quantity.integralQUs),
            ),
            c,
            i,
            s,
            if (c > 0 && i != null) output(i, c, Quantity.integralQUs) else null,
            previousAdmission(),
            minimum,
            maximum,
        )
    }
}

fun riderWorkWh(result: MetricResult): Double? =
    if (result.coveredUs > 0) result.integralQUs?.let { FixedPoint.divide(it, 3600000000000) } else null

sealed class MetricOutcome {
    data class Complete(val result: MetricResult) : MetricOutcome()

    data class Overflow(val failure: NumericOverflow) : MetricOutcome()
}

fun metricOutcome(reducer: MetricReducer): MetricOutcome =
    try {
        MetricOutcome.Complete(reducer.result())
    } catch (e: NumericFailure) {
        MetricOutcome.Overflow(e.failure)
    }

fun monitorReading(item: AnalysisItem, metric: String, activeInterval: Int?, selected: Boolean = true): MetricReading {
    val accuracyKey =
        when (metric) {
            "speedMps",
            "controllerSpeedMps" -> "speedAccuracyMps"
            "courseDegrees" -> "courseAccuracyDegrees"
            else -> null
        }
    return MetricReading(
        item.collectionUs,
        item.original(metric),
        activeInterval,
        item.continuity,
        selected && item.isReadingOf(metric),
        accuracyKey?.let { item.values[it] },
    )
}
