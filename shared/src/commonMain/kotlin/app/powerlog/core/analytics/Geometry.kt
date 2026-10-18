package app.powerlog.core.analytics

internal data class GPSDistanceRecord(
    val collectionUs: Long,
    val elapsed: Double,
    val eventId: String,
    val timestamp: String,
    val observationId: String,
    val producer: String,
    val sequence: Long,
    val epoch: String?,
    val barrier: Boolean,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val speed: Double?,
    val speedAccuracy: Double?,
    val altitude: Double?,
    val verticalAccuracy: Double?,
    val originalElapsed: Double?,
)

class GPSFix
private constructor(
    private val retained: AnalysisItem?,
    private val compact: GPSDistanceRecord?,
    val activeInterval: Int?,
) {
    constructor(item: AnalysisItem, activeInterval: Int?) : this(item, null, activeInterval)

    val item: AnalysisItem
        get() =
            retained
                ?: requireNotNull(compact).let { record ->
                    val values = mutableMapOf<String, Scalar>()
                    for (metric in
                        listOf(
                            "latitude",
                            "longitude",
                            "horizontalAccuracyM",
                            "speedMps",
                            "speedAccuracyMps",
                            "altitudeMeters",
                            "verticalAccuracyM",
                        )) {
                        number(metric)?.let { values[metric] = Scalar.Real64(it) }
                    }
                    AnalysisItem(
                        record.collectionUs,
                        record.elapsed,
                        record.eventId,
                        record.observationId,
                        record.producer,
                        record.sequence,
                        "location",
                        record.producer,
                        record.timestamp,
                        values,
                        record.originalElapsed,
                        continuity = Continuity(clockEpoch = record.epoch),
                        distanceBarrier = record.barrier,
                    )
                }

    internal val collectionUs
        get() = compact?.collectionUs ?: item.collectionUs

    internal val elapsed
        get() = compact?.elapsed ?: item.collectionElapsedSeconds

    internal val eventId
        get() = compact?.eventId ?: item.eventId

    internal val observationId
        get() = compact?.observationId ?: item.observationId

    internal val timestamp
        get() = compact?.timestamp ?: item.timestamp

    internal val epoch
        get() = if (compact != null) compact.epoch else item.continuity.clockEpoch

    internal val barrier
        get() = compact?.barrier ?: item.distanceBarrier

    internal val originalElapsed
        get() = if (compact != null) compact.originalElapsed else item.originalElapsedSeconds

    val latitude
        get() = number("latitude")

    val longitude
        get() = number("longitude")

    internal fun number(metric: String): Double? =
        if (compact == null) item.number(metric)
        else
            when (metric) {
                "latitude" -> compact.latitude
                "longitude" -> compact.longitude
                "horizontalAccuracyM" -> compact.accuracy
                "speedMps" -> compact.speed
                "speedAccuracyMps" -> compact.speedAccuracy
                "altitudeMeters" -> compact.altitude
                "verticalAccuracyM" -> compact.verticalAccuracy
                else -> null
            }

    internal fun scalar(metric: String): Scalar? =
        if (compact == null) item.values[metric] else number(metric)?.let { Scalar.Real64(it) }

    fun valid(): Boolean {
        val latitude = this.latitude
        val longitude = this.longitude
        return !barrier &&
            collectionUs >= 0 &&
            activeInterval != null &&
            latitude != null &&
            longitude != null &&
            latitude.isFinite() &&
            longitude.isFinite() &&
            latitude >= -90.0 &&
            latitude <= 90.0 &&
            longitude >= -180.0 &&
            longitude <= 180.0 &&
            Metrics.policy("horizontalAccuracyM").valid(scalar("horizontalAccuracyM"))
    }

    fun anchor() =
        Original(observationId, eventId, timestamp, elapsed, Scalar.Real64(requireNotNull(latitude)), originalElapsed)

    override fun equals(other: Any?) = other is GPSFix && activeInterval == other.activeInterval && item == other.item

    override fun hashCode() = 31 * item.hashCode() + (activeInterval ?: 0)

    companion object {
        internal fun compact(record: GPSDistanceRecord, activeInterval: Int?) = GPSFix(null, record, activeInterval)
    }
}

data class GPSAdmission(
    val accepted: Boolean,
    val startsSegment: Boolean,
    val interval: DistanceInterval?,
)

data class GPSDistanceCheckpoint(
    val previous: GPSFix?,
    val geometryPrevious: GPSFix?,
    val segment: Int,
    val altitudeAnchorMeters: Double? = null,
    val altitudeAnchorQ: Long? = null,
    val ascent: SignedSum = SignedSum(),
    val descent: SignedSum = SignedSum(),
    val distance: SignedSum = SignedSum(),
    val covered: SignedSum = SignedSum(),
    val altitudeSegments: Long = 0,
    val acceptedIntervals: Long = 0,
    val rejected: Long = 0,
) {
    constructor(
        previous: GPSFix?,
        geometryPrevious: GPSFix?,
        segment: Int,
    ) : this(
        previous,
        geometryPrevious,
        segment,
        null,
        null,
        SignedSum(),
        SignedSum(),
        SignedSum(),
        SignedSum(),
        0,
        0,
        0,
    )
}

class GPSKernel(private val fullContinuity: Boolean = false) {
    private var previous: GPSFix? = null
    private var geometryPrevious: GPSFix? = null
    private var altitudeAnchor: Pair<Double, Long>? = null
    private var ascent = CheckedSum()
    private var descent = CheckedSum()
    private var distance = CheckedSum()
    private var covered = CheckedSum()
    var altitudeSegments = 0L
        private set

    var acceptedIntervals = 0L
        private set

    var rejected = 0L
        private set

    var segment = -1
        private set

    fun distanceCheckpoint(): GPSDistanceCheckpoint =
        GPSDistanceCheckpoint(
            previous,
            geometryPrevious,
            segment,
            altitudeAnchor?.first,
            altitudeAnchor?.second,
            ascent.snapshot(),
            descent.snapshot(),
            distance.snapshot(),
            covered.snapshot(),
            altitudeSegments,
            acceptedIntervals,
            rejected,
        )

    fun restoreDistanceCheckpoint(checkpoint: GPSDistanceCheckpoint) {
        previous = checkpoint.previous
        geometryPrevious = checkpoint.geometryPrevious
        segment = checkpoint.segment
        altitudeAnchor =
            checkpoint.altitudeAnchorMeters?.let {
                it to requireNotNull(checkpoint.altitudeAnchorQ)
            }
        ascent = CheckedSum(checkpoint.ascent)
        descent = CheckedSum(checkpoint.descent)
        distance = CheckedSum(checkpoint.distance)
        covered = CheckedSum(checkpoint.covered)
        altitudeSegments = checkpoint.altitudeSegments
        acceptedIntervals = checkpoint.acceptedIntervals
        rejected = checkpoint.rejected
    }

    fun reset() {
        previous = null
        geometryPrevious = null
        altitudeAnchor = null
    }

    fun append(fix: GPSFix): GPSAdmission {
        if (fix.activeInterval == null) {
            reset()
            return GPSAdmission(false, false, null)
        }
        if (!fix.valid()) {
            rejected = FixedPoint.add(rejected, 1)
            reset()
            return GPSAdmission(false, false, null)
        }
        val old = previous
        val geometryOld = geometryPrevious
        val dt = if (old == null) 0L else FixedPoint.subtract(fix.collectionUs, old.collectionUs)
        val same = old != null && old.activeInterval == fix.activeInterval && old.epoch == fix.epoch
        val continuous = same && (!fullContinuity || old?.item?.continuity == fix.item.continuity)
        previous = fix
        if (old != null && dt <= 0) previous = if (dt == 0L && !same) fix else null
        val chord =
            if (old != null && dt > 0L && dt <= 10000000L && continuous)
                PureMath.haversine(
                    requireNotNull(old.latitude),
                    requireNotNull(old.longitude),
                    requireNotNull(fix.latitude),
                    requireNotNull(fix.longitude),
                )
            else null
        val supported =
            chord != null &&
                DistancePolicy.edge(dt, chord) &&
                (!fullContinuity || geometryOld != null && geometryOld.observationId == old?.observationId)
        val starts = !supported
        if (starts) {
            segment++
            if (geometryOld != null) rejected = FixedPoint.add(rejected, 1)
            altitudeAnchor = null
        }
        var interval: DistanceInterval? = null
        if (supported) {
            val first = requireNotNull(old)
            val stationary =
                Metrics.validSpeed(
                    first.number("speedMps"),
                    first.number("speedAccuracyMps"),
                ) &&
                    Metrics.validSpeed(
                        fix.number("speedMps"),
                        fix.number("speedAccuracyMps"),
                    ) &&
                    requireNotNull(first.number("speedMps")) < 0.5 &&
                    requireNotNull(fix.number("speedMps")) < 0.5
            val q = if (stationary) 0L else FixedPoint.quantize(requireNotNull(chord), 1000000)
            interval =
                DistanceInterval(
                    first.collectionUs,
                    fix.collectionUs,
                    q,
                    segment,
                    requireNotNull(fix.activeInterval),
                    first.anchor(),
                    fix.anchor(),
                    originalMeters = if (stationary) 0.0 else chord,
                )
            distance.add(q)
            covered.add(dt)
            acceptedIntervals = FixedPoint.add(acceptedIntervals, 1)
            altitude(fix, geometryOld ?: first)
        } else seedAltitude(fix)
        geometryPrevious = fix
        return GPSAdmission(true, starts, interval)
    }

    private fun seedAltitude(fix: GPSFix) {
        if (
            Metrics.policy("altitudeMeters").valid(fix.scalar("altitudeMeters")) &&
                Metrics.policy("verticalAccuracyM").valid(fix.scalar("verticalAccuracyM"))
        ) {
            val altitude = requireNotNull(fix.number("altitudeMeters"))
            altitudeAnchor = altitude to FixedPoint.quantize(altitude, 1000)
        }
    }

    private fun altitude(fix: GPSFix, old: GPSFix) {
        if (
            !Metrics.policy("altitudeMeters").valid(fix.scalar("altitudeMeters")) ||
                !Metrics.policy("verticalAccuracyM").valid(fix.scalar("verticalAccuracyM")) ||
                !Metrics.policy("verticalAccuracyM").valid(old.scalar("verticalAccuracyM"))
        ) {
            altitudeAnchor = null
            return
        }
        val anchor = altitudeAnchor
        if (anchor == null) {
            seedAltitude(fix)
            return
        }
        altitudeSegments = FixedPoint.add(altitudeSegments, 1)
        val altitude = requireNotNull(fix.number("altitudeMeters"))
        val delta = altitude - anchor.first
        val threshold =
            maxOf(
                3.0,
                requireNotNull(fix.number("verticalAccuracyM")),
                requireNotNull(old.number("verticalAccuracyM")),
            )
        if (PureMath.abs(delta) >= threshold) {
            val q = FixedPoint.quantize(altitude, 1000)
            val change = FixedPoint.subtract(q, anchor.second)
            if (delta > 0) ascent.add(change) else descent.add(-change)
            altitudeAnchor = altitude to q
        }
    }

    fun distanceQ(): Long? = if (acceptedIntervals == 0L) null else distance.value("distanceMeters", Quantity.distanceQ)

    fun coveredUs(): Long = covered.value("distanceMeters", Quantity.coveredUs)

    fun ascentQ(): Long? = if (altitudeSegments == 0L) null else ascent.value("altitudeMeters", Quantity.ascentQ)

    fun descentQ(): Long? = if (altitudeSegments == 0L) null else descent.value("altitudeMeters", Quantity.descentQ)
}
