package app.powerlog.bridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.*
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking

internal data class HealthExportResult(
    val state: String,
    val written: Int = 0,
    val omitted: Int = 0,
    val reason: String? = null,
)

internal interface HealthConnectAccess {
    val available: Boolean

    suspend fun granted(): Set<String>

    suspend fun write(records: List<Record>)
}

internal class AndroidHealthConnectAccess(private val context: Context) : HealthConnectAccess {
    override val available
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client
        get() = HealthConnectClient.getOrCreate(context)

    override suspend fun granted(): Set<String> =
        if (available) client.permissionController.getGrantedPermissions() else emptySet()

    override suspend fun write(records: List<Record>) {
        client.insertRecords(records)
    }
}

internal class HealthExport(
    context: Context,
    private val store: RideStore,
    private val access: HealthConnectAccess = AndroidHealthConnectAccess(context),
) {
    val available
        get() = access.available

    fun essentialPermissions() = setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))

    fun permissions(gps: Boolean) = buildSet {
        add(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
        add(HealthPermission.getWritePermission(PowerRecord::class))
        add(HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class))
        add(HealthPermission.getWritePermission(DistanceRecord::class))
        if (gps) {
            add(HealthPermission.getWritePermission(SpeedRecord::class))
            add(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE)
        }
    }

    fun granted(): Set<String> = runBlocking { access.granted() }

    fun status(): Payload {
        val grants = granted()
        val required = essentialPermissions()
        return mapOf(
            "available" to available,
            "provider" to "healthConnect",
            "requiredWrites" to required.toList(),
            "writeAuthorization" to
                permissions(true).associateWith {
                    if (it in grants) "authorized" else "notDetermined"
                },
        )
    }

    fun save(id: String): HealthExportResult = runBlocking {
        writeRide(
            id,
            prepare = {
                check(available && access.granted().containsAll(essentialPermissions())) {
                    "Allow Health Connect access to save this ride there."
                }
            },
        ) {
            access.write(it)
        }
    }

    internal suspend fun writeRide(
        id: String,
        prepare: suspend (Boolean) -> Unit = {},
        write: suspend (List<Record>) -> Unit,
    ): HealthExportResult {
        var written = 0
        var omitted = 0
        val reasons = linkedSetOf<String>()
        fun omit(reason: String) {
            omitted++
            reasons.add(reason)
        }
        fun reason() =
            reasons
                .takeIf { it.isNotEmpty() }
                ?.joinToString(
                    " ",
                    prefix = if (omitted > 0) "Health Connect omitted $omitted items. " else "",
                )
        data class Write(val record: Record, val items: Int)
        fun authorized(records: List<Write>, grants: Set<String>): List<Write> {
            check(available && grants.containsAll(essentialPermissions())) {
                "Allow Health Connect access to save this ride there."
            }
            fun denied(type: String, items: Int) {
                omitted += items
                reasons.add("Health Connect access to $type was not granted; those measurements were not saved.")
            }
            return records.mapNotNull { item ->
                val record = item.record
                val route = (record as? ExerciseSessionRecord)?.exerciseRouteResult as? ExerciseRouteResult.Data
                val type =
                    when (record) {
                        is PowerRecord -> "power"
                        is CyclingPedalingCadenceRecord -> "cadence"
                        is SpeedRecord -> "speed"
                        is DistanceRecord -> "distance"
                        is ExerciseSessionRecord -> "exercise session"
                        else -> error("Unsupported Health Connect record")
                    }
                if (HealthPermission.getWritePermission(record::class) !in grants) {
                    denied(type, item.items)
                    null
                } else if (
                    record is ExerciseSessionRecord &&
                        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE !in grants &&
                        route != null
                ) {
                    val routeItems = route.exerciseRoute.route.size
                    denied("exercise route", routeItems)
                    Write(
                        ExerciseSessionRecord(
                            record.startTime,
                            record.startZoneOffset,
                            record.endTime,
                            record.endZoneOffset,
                            record.metadata,
                            record.exerciseType,
                            title = record.title,
                            segments = record.segments,
                            laps = record.laps,
                        ),
                        item.items - routeItems,
                    )
                } else item
            }
        }
        suspend fun writeAuthorized(records: List<Write>) {
            var pending = authorized(records, access.granted())
            if (pending.isEmpty()) return
            try {
                write(pending.map { it.record })
            } catch (_: SecurityException) {
                pending = authorized(pending, access.granted())
                if (pending.isEmpty()) return
                write(pending.map { it.record })
            }
            written += pending.sumOf { it.items }
        }
        try {
            val meta = store.metadata(id)
            require(meta.flag("saveToHealth") && meta.str("phase") == "completed")
            val start = Instant.parse(meta.str("startedAt"))
            val end = Instant.parse(meta.str("endedAt"))
            clockFailure(start, end)?.let {
                return it
            }
            prepare(meta.flag("recordGPS"))
            fun instant(value: String?) = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
            fun contains(a: Instant?, b: Instant?) = a != null && b != null && a >= start && b <= end && b > a
            fun metadata(part: String) =
                Metadata.activelyRecorded(
                    device = Device(type = Device.TYPE_PHONE),
                    clientRecordId = "power-log:$id:$part",
                    clientRecordVersion = 1,
                )
            val power = mutableListOf<PowerRecord.Sample>()
            val cadence = mutableListOf<CyclingPedalingCadenceRecord.Sample>()
            val speed = mutableListOf<SpeedRecord.Sample>()
            val route = mutableListOf<ExerciseRoute.Location>()
            val routeTimes = mutableSetOf<Instant>()
            val routeStride = kotlin.math.max(1, kotlin.math.ceil(store.count(id, "location") / 5000.0).toInt())
            var routeIndex = 0
            var batch = 0
            fun bounds(first: Instant, last: Instant): Pair<Instant, Instant> {
                val a = if (first == end) end.minusMillis(1).coerceAtLeast(start) else first
                val b = if (last == end) end else last.plusMillis(1).coerceAtMost(end)
                check(contains(a, b))
                return a to b
            }
            suspend fun flush() {
                val records = mutableListOf<Record>()
                if (power.isNotEmpty()) {
                    power.sortBy { it.time }
                    val (a, b) = bounds(power.first().time, power.last().time)
                    records.add(
                        PowerRecord(a, ZoneOffset.UTC, b, ZoneOffset.UTC, power.toList(), metadata("power:$batch"))
                    )
                }
                if (cadence.isNotEmpty()) {
                    cadence.sortBy { it.time }
                    val (a, b) = bounds(cadence.first().time, cadence.last().time)
                    records.add(
                        CyclingPedalingCadenceRecord(
                            a,
                            ZoneOffset.UTC,
                            b,
                            ZoneOffset.UTC,
                            cadence.toList(),
                            metadata("cadence:$batch"),
                        )
                    )
                }
                if (speed.isNotEmpty()) {
                    speed.sortBy { it.time }
                    val (a, b) = bounds(speed.first().time, speed.last().time)
                    records.add(
                        SpeedRecord(a, ZoneOffset.UTC, b, ZoneOffset.UTC, speed.toList(), metadata("speed:$batch"))
                    )
                }
                if (records.isNotEmpty()) {
                    writeAuthorized(
                        records.map { record ->
                            Write(
                                record,
                                when (record) {
                                    is PowerRecord -> power.size
                                    is CyclingPedalingCadenceRecord -> cadence.size
                                    is SpeedRecord -> speed.size
                                    else -> error("Unsupported measurement record")
                                },
                            )
                        }
                    )
                }
                power.clear()
                cadence.clear()
                speed.clear()
                batch++
            }
            // Chunking keeps Binder payloads and memory independent of ride duration.
            var after = 0L
            while (true) {
                val rows = store.page(id, after, 512)
                if (rows.isEmpty()) break
                for (row in rows) {
                    val t = instant(row.timestamp)
                    fun eligible(
                        value: Double,
                        range: ClosedFloatingPointRange<Double>,
                        gps: Boolean = false,
                    ): Boolean {
                        val cause =
                            when {
                                !row.active || (gps && !meta.flag("recordGPS")) ->
                                    "Inactive or disabled measurements were omitted."
                                t == null || t < start || t > end ->
                                    "Measurements outside the retained workout UTC interval were omitted."
                                !value.isFinite() || value !in range -> "Invalid measurement values were omitted."
                                gps && row.values["horizontalAccuracyM"]?.let { it in 0.0..50.0 } != true ->
                                    "GPS fixes with invalid accuracy were omitted."
                                else -> null
                            }
                        if (cause != null) omit(cause)
                        return cause == null
                    }
                    row.values["humanPowerW"]?.let {
                        if (eligible(it, 0.0..5000.0)) power.add(PowerRecord.Sample(t!!, it.watts))
                    }
                    row.values["cadenceRpm"]?.let {
                        if (eligible(it, 0.0..300.0)) cadence.add(CyclingPedalingCadenceRecord.Sample(t!!, it))
                    }
                    if (row.kind == "location") {
                        row.values["speedMps"]?.let {
                            if (eligible(it, 0.0..40.0, gps = true))
                                speed.add(SpeedRecord.Sample(t!!, it.metersPerSecond))
                        }
                        if (!eligible(row.values["latitude"] ?: Double.NaN, -90.0..90.0, gps = true)) continue
                        val longitude = row.values["longitude"]
                        when {
                            longitude == null || longitude !in -180.0..180.0 ->
                                omit("Invalid route coordinates were omitted.")
                            t == end -> omit("Route points at the workout end were omitted.")
                            routeIndex++ % routeStride != 0 ->
                                omit("Route points were reduced to the Health Connect limit.")
                            !routeTimes.add(t!!) -> omit("Duplicate route timestamps were omitted.")
                            else ->
                                route.add(
                                    ExerciseRoute.Location(
                                        t!!,
                                        row.values.getValue("latitude"),
                                        longitude,
                                        row.values.getValue("horizontalAccuracyM").meters,
                                    )
                                )
                        }
                    }
                }
                flush()
                after = rows.last().id
            }
            val selected = RideDistance(store).selected(id, "auto")
            if (selected != null) {
                val records = mutableListOf<Write>()
                var index = 0
                store.readableDatabase
                    .rawQuery(
                        "SELECT d.start,d.end,d.meters,a.timestamp,b.timestamp,d.from_id,d.to_id FROM distance_intervals d LEFT JOIN observations a ON a.id=d.from_id AND a.ride=d.ride LEFT JOIN observations b ON b.id=d.to_id AND b.ride=d.ride WHERE d.ride=? AND d.source=? ORDER BY d.end",
                        arrayOf(id, selected),
                    )
                    .use { cursor ->
                        var a: Instant? = null
                        var b: Instant? = null
                        var fromElapsed = 0.0
                        var toElapsed = 0.0
                        var toID = 0L
                        var meters = 0.0
                        var items = 0
                        suspend fun flushDistance() {
                            if (items == 0) return
                            records.add(
                                Write(
                                    DistanceRecord(
                                        a!!,
                                        ZoneOffset.UTC,
                                        b!!,
                                        ZoneOffset.UTC,
                                        meters.meters,
                                        metadata("distance:${index++}"),
                                    ),
                                    items,
                                )
                            )
                            items = 0
                            if (records.size >= 100) {
                                writeAuthorized(records.toList())
                                records.clear()
                            }
                        }
                        while (cursor.moveToNext()) {
                            val x = instant(cursor.getString(3))
                            val y = instant(cursor.getString(4))
                            val amount = cursor.getDouble(2)
                            if (!contains(x, y) || !amount.isFinite() || amount !in 0.0..1000000.0) {
                                flushDistance()
                                omit("Invalid or out-of-workout distance intervals were omitted.")
                                continue
                            }
                            if (
                                items > 0 &&
                                    (cursor.getDouble(0) != toElapsed ||
                                        cursor.getLong(5) != toID ||
                                        x != b ||
                                        cursor.getDouble(1) - fromElapsed > 60 ||
                                        y!! > a!!.plusSeconds(60) ||
                                        meters + amount > 1000000.0)
                            )
                                flushDistance()
                            if (items == 0) {
                                a = x
                                fromElapsed = cursor.getDouble(0)
                                meters = 0.0
                            }
                            b = y
                            toElapsed = cursor.getDouble(1)
                            toID = cursor.getLong(6)
                            meters += amount
                            items++
                        }
                        flushDistance()
                        if (records.isNotEmpty()) {
                            writeAuthorized(records)
                        }
                    }
            }
            val events = store.events(id)
            val segments = mutableListOf<ExerciseSegment>()
            var paused: RideEvent? = null
            for (event in events) {
                if (event.action == "pause") {
                    if (paused != null) omit("Unpaired pause events were omitted.")
                    paused = event
                } else if (event.action in listOf("resume", "stop")) {
                    paused?.let {
                        val a = instant(it.timestamp)
                        val b = instant(event.timestamp)
                        if (contains(a, b) && segments.none { a!! < it.endTime && b!! > it.startTime })
                            segments.add(ExerciseSegment(a!!, b!!, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE))
                        else omit("Invalid or overlapping pause intervals were omitted.")
                    }
                    paused = null
                }
            }
            if (paused != null) omit("Unpaired pause events were omitted.")
            val laps = mutableListOf<ExerciseLap>()
            val boundaries = listOf(start) + events.filter { it.action == "lap" }.map { instant(it.timestamp) } + end
            for ((a, b) in boundaries.zipWithNext()) {
                if (contains(a, b) && laps.none { a!! < it.endTime && b!! > it.startTime })
                    laps.add(ExerciseLap(a!!, b!!))
                else omit("Invalid or overlapping lap intervals were omitted.")
            }
            writeAuthorized(
                listOf(
                    Write(
                        ExerciseSessionRecord(
                            start,
                            ZoneOffset.UTC,
                            end,
                            ZoneOffset.UTC,
                            metadata("session"),
                            if (meta.flag("indoor")) ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
                            else ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                            title = "Power Log ride",
                            segments = segments.sortedBy { it.startTime },
                            laps = laps.sortedBy { it.startTime },
                            exerciseRoute =
                                route.takeIf { it.isNotEmpty() }?.sortedBy { it.time }?.let { ExerciseRoute(it) },
                        ),
                        1 + segments.size + laps.size + route.size,
                    )
                )
            )
            return HealthExportResult("saved", written, omitted, reason())
        } catch (error: Exception) {
            reasons.add("Health Connect saving failed: ${error.message ?: "Try again."}")
            return HealthExportResult("notSaved", written, omitted, reason())
        }
    }

    companion object {
        fun clockFailure(start: Instant, end: Instant): HealthExportResult? =
            if (end > start) null
            else
                HealthExportResult(
                    "unavailable",
                    reason =
                        "Health Connect is unavailable for this ride because its clock cutoff is not after its start.",
                )
    }
}
