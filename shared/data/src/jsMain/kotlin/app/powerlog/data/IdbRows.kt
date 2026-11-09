package app.powerlog.data

import app.powerlog.application.store.RideRecord
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.time.RideTiming

internal fun flag(value: Boolean): Int = if (value) 1 else 0

internal fun flagOf(value: dynamic): Boolean = Vocabularies.flag(long(value))

internal fun newRideRecord(ride: Ride): dynamic {
    val record = record()
    record.uuid = ride.id.value
    record.recordedBy = Vocabularies.device.name(ride.recordedBy)
    record.example = flag(ride.example)
    record.startedUtcUs = ride.startedUtcUs.js()
    record.sampleHz = ride.options.sampleRate.hz
    record.indoor = flag(ride.options.indoor)
    record.useWatch = flag(ride.options.useWatch)
    record.saveToHealth = flag(ride.options.saveToHealth)
    record.recordGps = flag(ride.options.recordGps)
    record.distanceSource = Vocabularies.distanceSource.name(ride.options.distanceSource)
    record.batteryMethod = Vocabularies.batteryMethod.name(ride.options.batteryMethod)
    return record
}

internal fun RideState<dynamic>.writeRecord() {
    val record = stored
    record.phase = Vocabularies.phase.name(phase)
    record.interrupted = flag(interrupted)
    record.cpUtcUs = checkpoint.utcUs.js()
    record.cpElapsedUs = checkpoint.elapsedUs.js()
    record.cpTimerUs = checkpoint.timerUs.js()
    record.endUtcUs = end?.utcUs?.js() ?: undefined
    record.endElapsedUs = end?.elapsedUs?.js() ?: undefined
    record.endTimerUs = end?.timerUs?.js() ?: undefined
    record.healthProvider = health.provider?.let(Vocabularies.provider::name) ?: undefined
    record.healthOutcome = Vocabularies.outcome.name(health.outcome)
    record.healthReason = health.reason?.let(Vocabularies.reason::name) ?: undefined
    record.healthWorkoutId = health.workoutId ?: undefined
    record.dataRevision = dataRevision.js()
    record.deletedUtcUs = deletedUtcUs?.js() ?: undefined
    record.historyKey = if (deletedUtcUs == null) arrayOf(record.startedUtcUs, record.uuid) else undefined
    record.openKey = if (deletedUtcUs == null && phase in OPEN_PHASES) 1 else undefined
}

internal fun rideState(record: dynamic): RideState<dynamic> =
    RideState(
        rideId = RideId(record.uuid.unsafeCast<String>()),
        stored = record,
        created = false,
        phase = Vocabularies.phase.value(record.phase.unsafeCast<String>()),
        interrupted = flagOf(record.interrupted),
        checkpoint = RideTiming(long(record.cpUtcUs), long(record.cpElapsedUs), long(record.cpTimerUs)),
        end = timingOf(record),
        health = healthOf(record),
        dataRevision = long(record.dataRevision),
        deletedUtcUs = longOrNull(record.deletedUtcUs),
    )

internal fun isLive(record: dynamic): Boolean = record != null && record.deletedUtcUs == null

// An array sorts after every number and string, so [ride, []] lies past every key that starts with the ride.
internal fun rideKeys(rideId: RideId): IDBKeyRange =
    IDBKeyRange.bound(arrayOf(rideId.value), arrayOf<Any>(rideId.value, emptyArray<Any>()), false, false)

internal fun rideRecord(record: dynamic): RideRecord =
    RideRecord(rideOf(record), healthOf(record), emptyList(), long(record.dataRevision))

internal fun rideOf(record: dynamic): Ride =
    Ride(
        id = RideId(record.uuid.unsafeCast<String>()),
        recordedBy = Vocabularies.device.value(record.recordedBy.unsafeCast<String>()),
        example = flagOf(record.example),
        startedUtcUs = long(record.startedUtcUs),
        options =
            RideOptions(
                sampleRate = Vocabularies.sampleRate(long(record.sampleHz)),
                indoor = flagOf(record.indoor),
                useWatch = flagOf(record.useWatch),
                saveToHealth = flagOf(record.saveToHealth),
                recordGps = flagOf(record.recordGps),
                distanceSource = Vocabularies.distanceSource.value(record.distanceSource.unsafeCast<String>()),
                batteryMethod = Vocabularies.batteryMethod.value(record.batteryMethod.unsafeCast<String>()),
            ),
        phase = Vocabularies.phase.value(record.phase.unsafeCast<String>()),
        interrupted = flagOf(record.interrupted),
        checkpoint = RideTiming(long(record.cpUtcUs), long(record.cpElapsedUs), long(record.cpTimerUs)),
        end = timingOf(record),
    )

internal fun healthOf(record: dynamic): RideHealth =
    RideHealth(
        provider = textOrNull(record.healthProvider)?.let(Vocabularies.provider::value),
        outcome = Vocabularies.outcome.value(record.healthOutcome.unsafeCast<String>()),
        reason = textOrNull(record.healthReason)?.let(Vocabularies.reason::value),
        workoutId = textOrNull(record.healthWorkoutId),
    )

private fun timingOf(record: dynamic): RideTiming? {
    val values = listOf(longOrNull(record.endUtcUs), longOrNull(record.endElapsedUs), longOrNull(record.endTimerUs))
    if (values.all { it == null }) return null
    if (values.any { it == null }) throw ConstraintViolation("a partial timing")
    return RideTiming(values[0]!!, values[1]!!, values[2]!!)
}

private val TELEMETRY_COLUMNS = IdbFactKind.telemetry.columns.size
private val LOCATION_COLUMNS = IdbFactKind.location.columns.size

internal fun locationColumn(column: LocationColumn): Int = IdbFactKind.location.columns.indexOf(column.idb)

internal fun telemetryColumn(index: Int): Int = 6 + index

internal fun factsRecord(ride: RideId, kind: IdbFactKind, batchNo: Long, rows: Int, values: DoubleArray): dynamic {
    val record = record()
    record.ride = ride.value
    record.kind = kind.name
    record.batchNo = batchNo.js()
    record.rows = rows
    record.firstUs = values[0]
    record.lastUs = values[rows - 1]
    record.values = values
    return record
}

internal fun telemetryValues(parts: List<TelemetryRows>): DoubleArray {
    val count = parts.sumOf { it.rows }
    val values = DoubleArray(count * TELEMETRY_COLUMNS)
    var row = 0
    for (part in parts) {
        requireTelemetryColumns(part.quanta.keys)
        val metrics = TELEMETRY_METRICS.map { part.quanta[it] }
        for (index in 0 until part.rows) {
            values[row] = part.elapsedUs[index]
            values[count + row] = part.sessionNo[index].toDouble()
            values[2 * count + row] = part.seq[index]
            values[3 * count + row] = part.utcUs[index]
            values[4 * count + row] = part.activeInterval[index].takeIf { it != 0 }?.toDouble() ?: Double.NaN
            values[5 * count + row] = part.run[index].toDouble()
            metrics.forEachIndexed { metric, column ->
                values[telemetryColumn(metric) * count + row] = column?.get(index) ?: Double.NaN
            }
            row++
        }
    }
    return values
}

internal fun locationValues(fixes: List<LocationFix>): DoubleArray {
    val count = fixes.size
    val values = DoubleArray(count * LOCATION_COLUMNS)
    fixes.forEachIndexed { row, fix ->
        val columns =
            listOf(
                fix.elapsedUs.toDouble(),
                fix.seq.toDouble(),
                fix.utcUs.toDouble(),
                fix.activeInterval?.toDouble(),
                fix.run.toDouble(),
                flag(fix.barrier).toDouble(),
                fix.latitude,
                fix.longitude,
                fix.horizontalAccuracyM,
                fix.altitudeM,
                fix.altitudeDatum.ordinal.toDouble(),
                fix.verticalAccuracyM,
                fix.mslM,
                fix.mslVerticalAccuracyM,
                fix.speedMps,
                fix.speedAccuracyMps,
                fix.courseDeg,
                fix.courseAccuracyDeg,
            )
        columns.forEachIndexed { column, value -> values[column * count + row] = value ?: Double.NaN }
    }
    return values
}

internal class Facts(record: dynamic) {
    val rows: Int = record.rows.unsafeCast<Int>()
    val values: DoubleArray = record.values.unsafeCast<DoubleArray>()

    fun value(column: Int, row: Int): Double = values[column * rows + row]

    fun elapsed(row: Int): Long = value(0, row).toLong()

    fun last(kind: IdbFactKind): RetainedEnd {
        val utc = kind.columns.indexOf("utcUs")
        var last = 0
        for (row in 1 until rows) {
            val elapsed = value(0, row)
            val latest = value(0, last)
            if (elapsed > latest || elapsed == latest && value(utc, row) > value(utc, last)) last = row
        }
        return RetainedEnd(value(utc, last).toLong(), elapsed(last))
    }

    fun fix(rideId: RideId, row: Int): LocationFix {
        fun nullable(column: Int): Double? = value(column, row).takeUnless { it.isNaN() }
        val datum = value(10, row).toInt()
        return LocationFix(
            rideId = rideId,
            seq = value(1, row).toLong(),
            elapsedUs = elapsed(row),
            utcUs = value(2, row).toLong(),
            activeInterval = nullable(3)?.toInt(),
            run = value(4, row).toInt(),
            barrier = Vocabularies.flag(value(5, row).toLong()),
            latitude = value(6, row),
            longitude = value(7, row),
            horizontalAccuracyM = nullable(8),
            altitudeM = nullable(9),
            altitudeDatum = AltitudeDatum.entries.getOrNull(datum) ?: throw ConstraintViolation("datum $datum"),
            verticalAccuracyM = nullable(11),
            mslM = nullable(12),
            mslVerticalAccuracyM = nullable(13),
            speedMps = nullable(14),
            speedAccuracyMps = nullable(15),
            courseDeg = nullable(16),
            courseAccuracyDeg = nullable(17),
        )
    }
}
