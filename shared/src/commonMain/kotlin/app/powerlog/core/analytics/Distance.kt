package app.powerlog.core.analytics

import app.powerlog.core.health.MeasurementSource

data class DistanceInterval(
    val startUs: Long,
    val endUs: Long,
    val distanceQ: Long,
    val segment: Int,
    val activeInterval: Int,
    val start: Original,
    val end: Original,
    val speed0Q: Long? = null,
    val speed1Q: Long? = null,
    val indivisible: Boolean = false,
    val originalMeters: Double? = null,
)

data class DistanceRange(
    val distanceQ: Long,
    val coveredUs: Long,
    val unresolvedBoundary: Boolean,
    val partial: Boolean,
) {
    val distanceMeters
        get() = FixedPoint.divide(distanceQ, 1000000)

    val coveredSeconds
        get() = FixedPoint.divide(coveredUs, 1000000)
}

class DistanceReducer(val range: AnalysisRange, initial: DistanceRange?) {
    constructor(range: AnalysisRange) : this(range, null)

    private val distance = CheckedSum().also { it.add(initial?.distanceQ ?: 0L) }
    private val covered = CheckedSum().also { it.add(initial?.coveredUs ?: 0L) }
    private var unresolved = initial?.unresolvedBoundary ?: false

    fun append(interval: DistanceInterval) {
        val a = maxOf(range.startUs, interval.startUs)
        val b = minOf(range.endUs, interval.endUs)
        if (b <= a) return
        if (interval.indivisible && (a != interval.startUs || b != interval.endUs)) {
            unresolved = true
            return
        }
        val duration = FixedPoint.subtract(interval.endUs, interval.startUs)
        val amount =
            try {
                if (interval.speed0Q != null && interval.speed1Q != null) {
                    val v0 =
                        FixedPoint.interpolate(
                            interval.speed0Q,
                            interval.speed1Q,
                            a - interval.startUs,
                            duration,
                        )
                    val v1 =
                        FixedPoint.interpolate(
                            interval.speed0Q,
                            interval.speed1Q,
                            b - interval.startUs,
                            duration,
                        )
                    controllerContribution(v0, v1, b - a)
                } else FixedPoint.share(interval.distanceQ, b - a, duration)
            } catch (_: ArithmeticException) {
                throw NumericFailure(NumericOverflow("distanceMeters", Quantity.distanceQ))
            }
        distance.add(amount)
        covered.add(b - a)
    }

    fun result(timerUs: Long = range.endUs - range.startUs): DistanceRange {
        val d = distance.value("distanceMeters", Quantity.distanceQ)
        val c = covered.value("distanceMeters", Quantity.coveredUs)
        return DistanceRange(d, c, unresolved, unresolved || uncoveredUs(timerUs, c) > 1000L)
    }
}

fun uncoveredUs(timerUs: Long, coveredUs: Long): Long = maxOf(0L, FixedPoint.subtract(timerUs, coveredUs))

private fun controllerContribution(v0: Long, v1: Long, durationUs: Long): Long =
    FixedPoint.halfEven(FixedPoint.product(FixedPoint.add(v0, v1), durationUs), 2000000L)

object DistancePolicy {
    const val maximumControllerIntervalUs = 2500000L
    // A controller link loss shorter than the six-second hold joins the readings on either side.
    const val maximumControllerEdgeUs = BatteryConsumptionPolicy.maximumCounterEdgeUs
    const val maximumGPSIntervalUs = 10000000L

    fun validSelection(selection: String): Boolean = selection in listOf("auto", "gps:watch", "gps:phone", "controller")

    /** Resolve once at Start; temporary loss of the selected source creates a gap, never a source switch. */
    @Throws(IllegalArgumentException::class)
    fun resolveAtStart(selection: String, recordGPS: Boolean, indoor: Boolean): String {
        require(validSelection(selection)) { "Invalid distance source" }
        return when (selection) {
            "auto" -> if (recordGPS && !indoor) "gps:phone" else "controller"
            "gps:phone" -> "gps:phone".also { require(recordGPS) { "GPS recording is disabled" } }
            "controller" -> "controller"
            else -> throw IllegalArgumentException("The phone supplies GPS for companion rides")
        }
    }

    internal fun preference(watchEnabled: Boolean, indoor: Boolean): List<String> {
        val owner = if (watchEnabled) "watch" else "phone"
        val other = if (watchEnabled) "phone" else "watch"
        return (if (indoor) emptyList() else listOf("gps:$owner", "gps:$other")) + "controller"
    }

    fun orderAvailable(
        available: List<DistanceProfile>,
        watchEnabled: Boolean,
        indoor: Boolean,
    ): List<DistanceProfile> {
        val preference = preference(watchEnabled, indoor)
        return available.sortedBy { preference.indexOf(it.source).takeIf { index -> index >= 0 } ?: 99 }
    }

    fun edge(dtUs: Long, chordMeters: Double): Boolean =
        dtUs > 0L &&
            dtUs <= maximumGPSIntervalUs &&
            chordMeters.isFinite() &&
            chordMeters >= 0 &&
            chordMeters / FixedPoint.divide(dtUs, 1000000) <= 40

    fun label(source: String, descriptors: List<MeasurementSource>): String {
        if (source == "controller") return "Controller estimate"
        val device = source.substringAfter(':')
        val descriptor = descriptors.firstOrNull { it.source.name == device }
        return when (descriptor?.device?.name ?: if (device == "watch") "appleWatch" else "iphone") {
            "appleWatch",
            "wearWatch" -> "GPS · Apple Watch"
            "androidPhone" -> "GPS · Android phone"
            "browser" -> "GPS · Browser"
            else -> "GPS · iPhone"
        }
    }
}

data class DistanceProfile(
    val source: String,
    val label: String,
    val estimated: Boolean,
    val partial: Boolean,
    val coveredUs: Long,
    val uncoveredUs: Long,
    val distanceQ: Long,
) {
    val coveredSeconds
        get() = FixedPoint.divide(coveredUs, 1000000)

    val uncoveredSeconds
        get() = FixedPoint.divide(uncoveredUs, 1000000)

    val distanceMeters
        get() = FixedPoint.divide(distanceQ, 1000000)
}

data class DistanceInfo(
    val selection: String,
    val selected: DistanceProfile?,
    val available: List<DistanceProfile>,
)

fun selectDistance(
    selection: String,
    available: List<DistanceProfile>,
    watchEnabled: Boolean,
    indoor: Boolean,
): DistanceInfo {
    require(DistancePolicy.validSelection(selection))
    val preference = DistancePolicy.preference(watchEnabled, indoor)
    val selected =
        if (selection == "auto") preference.firstNotNullOfOrNull { key -> available.firstOrNull { it.source == key } }
        else available.firstOrNull { it.source == selection }
    return DistanceInfo(selection, selected, available)
}

data class ControllerDistanceCheckpoint(val previous: ControllerDistanceEvidence?, val segment: Int)

class ControllerDistanceKernel(checkpoint: ControllerDistanceCheckpoint?) {
    constructor() : this(null)

    private var previous: ControllerDistanceEvidence? = checkpoint?.previous
    private var segment = checkpoint?.segment ?: 0
    private var previousSpeedQ = previous?.speedMps?.let { FixedPoint.quantize(it, 1000000) }

    fun checkpoint(): ControllerDistanceCheckpoint = ControllerDistanceCheckpoint(previous, segment)

    fun append(current: ControllerDistanceEvidence): DistanceInterval? {
        val old = previous
        val oldSpeedQ = previousSpeedQ
        val valid =
            !current.barrier &&
                current.timeUs >= 0 &&
                (current.model == "X6" || current.model == "X12") &&
                current.protocol == "5.3" &&
                !current.deviceIdentity.isNullOrEmpty() &&
                !current.continuityIdentity.isNullOrEmpty() &&
                current.activeInterval != null &&
                Metrics.validSpeed(current.speedMps)
        if (!valid) {
            previous = null
            segment++
            return null
        }
        previous = current
        previousSpeedQ = FixedPoint.quantize(requireNotNull(current.speedMps), 1000000)
        if (old == null) return null
        val dt =
            try {
                FixedPoint.subtract(current.timeUs, old.timeUs)
            } catch (_: ArithmeticException) {
                0L
            }
        if (dt <= 0) {
            segment++
            previous =
                if (
                    dt == 0L &&
                        (old.continuityIdentity != current.continuityIdentity ||
                            old.activeInterval != current.activeInterval)
                )
                    current
                else null
            return null
        }
        val same =
            old.model == current.model &&
                old.protocol == current.protocol &&
                old.deviceIdentity == current.deviceIdentity &&
                old.activeInterval == current.activeInterval
        val monotonic =
            old.monotonicSeconds == null ||
                current.monotonicSeconds == null ||
                current.monotonicSeconds > old.monotonicSeconds
        val counter =
            old.counter == null || current.counter == null || compareScalars(current.counter, old.counter) >= 0
        if (dt >= DistancePolicy.maximumControllerEdgeUs || !same || !monotonic || !counter) {
            segment++
            return null
        }
        val v0 = requireNotNull(oldSpeedQ)
        val v1 = requireNotNull(previousSpeedQ)
        val distance = controllerContribution(v0, v1, dt)
        return DistanceInterval(
            old.timeUs,
            current.timeUs,
            distance,
            segment,
            requireNotNull(current.activeInterval),
            old.anchor,
            current.anchor,
            v0,
            v1,
            originalMeters =
                (requireNotNull(old.speedMps) + requireNotNull(current.speedMps)) * FixedPoint.divide(dt, 1000000) / 2,
        )
    }
}

fun distanceProfile(
    source: String,
    range: DistanceRange,
    timerUs: Long,
    descriptors: List<MeasurementSource>,
): DistanceProfile? {
    if (range.coveredUs <= 0) return null
    val uncovered = uncoveredUs(timerUs, range.coveredUs)
    return DistanceProfile(
        source,
        DistancePolicy.label(source, descriptors),
        source == "controller",
        uncovered > 1000L,
        range.coveredUs,
        uncovered,
        range.distanceQ,
    )
}
