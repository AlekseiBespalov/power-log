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

/** Optional write-only integration. Original observations always remain in Power Log. */
internal class HealthExport(private val context: Context, private val store: RideStore) {
    val available
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client
        get() = HealthConnectClient.getOrCreate(context)

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

    fun granted(): Set<String> =
        if (available) runBlocking { client.permissionController.getGrantedPermissions() }
        else emptySet()

    fun status(): Payload {
        val grants = granted()
        val required = permissions(false)
        return mapOf(
            "available" to available,
            "provider" to "healthConnect",
            "requiredWrites" to required.toList(),
            "readAuthorization" to "notObservable",
            "writeAuthorization" to
                permissions(true).associateWith {
                    if (it in grants) "authorized" else "notDetermined"
                },
            "requestStatus" to if (grants.containsAll(required)) "unnecessary" else "shouldRequest",
        )
    }

    fun save(id: String) = runBlocking {
        val meta = store.metadata(id)
        require(meta.flag("saveToHealth") && meta.str("phase") == "completed")
        check(available && granted().containsAll(permissions(meta.flag("recordGPS")))) {
            "Allow Health Connect access to save this ride there."
        }
        writeRide(id) { client.insertRecords(it) }
    }

    internal suspend fun writeRide(id: String, write: suspend (List<Record>) -> Unit) {
        val meta = store.metadata(id)
        require(meta.flag("saveToHealth") && meta.str("phase") == "completed")
        val start = Instant.parse(meta.str("startedAt"))
        val elapsed = store.timing(id).first
        check(elapsed > 0) { "This ride is too short to save to Health Connect." }
        fun time(seconds: Double) = start.plusNanos((seconds * 1e9).toLong())
        val end = time(elapsed)
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
        val routeStride =
            kotlin.math.max(1, kotlin.math.ceil(store.count(id, "location") / 5000.0).toInt())
        var routeIndex = 0
        var batch = 0
        suspend fun flush() {
            val records = mutableListOf<Record>()
            if (power.isNotEmpty())
                records.add(
                    PowerRecord(
                        power.first().time,
                        ZoneOffset.UTC,
                        power.last().time.plusNanos(1).coerceAtMost(end),
                        ZoneOffset.UTC,
                        power.toList(),
                        metadata("power:$batch"),
                    )
                )
            if (cadence.isNotEmpty())
                records.add(
                    CyclingPedalingCadenceRecord(
                        cadence.first().time,
                        ZoneOffset.UTC,
                        cadence.last().time.plusNanos(1).coerceAtMost(end),
                        ZoneOffset.UTC,
                        cadence.toList(),
                        metadata("cadence:$batch"),
                    )
                )
            if (speed.isNotEmpty())
                records.add(
                    SpeedRecord(
                        speed.first().time,
                        ZoneOffset.UTC,
                        speed.last().time.plusNanos(1).coerceAtMost(end),
                        ZoneOffset.UTC,
                        speed.toList(),
                        metadata("speed:$batch"),
                    )
                )
            if (records.isNotEmpty()) write(records)
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
                val t = time(row.time)
                if (!row.active || t < start || t >= end) continue
                row.values["humanPowerW"]
                    ?.takeIf { it in 0.0..10000.0 }
                    ?.let { power.add(PowerRecord.Sample(t, it.watts)) }
                row.values["cadenceRpm"]
                    ?.takeIf { it in 0.0..10000.0 }
                    ?.let { cadence.add(CyclingPedalingCadenceRecord.Sample(t, it)) }
                if (
                    row.kind == "location" &&
                        (row.values["horizontalAccuracyM"] ?: 999.0) in 0.0..50.0
                ) {
                    row.values["speedMps"]
                        ?.takeIf { it in 0.0..40.0 }
                        ?.let { speed.add(SpeedRecord.Sample(t, it.metersPerSecond)) }
                    if (routeIndex++ % routeStride == 0)
                        route.add(
                            ExerciseRoute.Location(
                                t,
                                row.values.getValue("latitude"),
                                row.values.getValue("longitude"),
                                row.values.getValue("horizontalAccuracyM").meters,
                            )
                        )
                }
            }
            power.sortBy { it.time }
            cadence.sortBy { it.time }
            speed.sortBy { it.time }
            flush()
            after = rows.last().id
        }
        val selected = RideDistance(store).selected(id, "auto")
        if (selected != null) {
            val records = mutableListOf<Record>()
            var index = 0
            store.readableDatabase
                .rawQuery(
                    "SELECT start,end,meters FROM distance_intervals WHERE ride=? AND source=? ORDER BY end",
                    arrayOf(id, selected),
                )
                .use { cursor ->
                    var a = 0.0
                    var b = 0.0
                    var meters = 0.0
                    suspend fun flushDistance() {
                        if (b <= a) return
                        records.add(
                            DistanceRecord(
                                time(a),
                                ZoneOffset.UTC,
                                time(b),
                                ZoneOffset.UTC,
                                meters.meters,
                                metadata("distance:${index++}"),
                            )
                        )
                        if (records.size >= 100) {
                            write(records.toList())
                            records.clear()
                        }
                    }
                    while (cursor.moveToNext()) {
                        val x = cursor.getDouble(0)
                        val y = cursor.getDouble(1)
                        if (x != b || y - a > 60) {
                            flushDistance()
                            a = x
                            meters = 0.0
                        }
                        if (b == 0.0) a = x
                        b = y
                        meters += cursor.getDouble(2)
                    }
                    flushDistance()
                    if (records.isNotEmpty()) write(records)
                }
        }
        val events = store.events(id)
        val segments = mutableListOf<ExerciseSegment>()
        var paused: Double? = null
        events.forEach { (t, action) ->
            if (action == "pause") paused = t
            else if (action in listOf("resume", "stop")) {
                paused?.let {
                    if (t > it)
                        segments.add(
                            ExerciseSegment(
                                time(it),
                                time(t),
                                ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                            )
                        )
                }
                paused = null
            }
        }
        val laps =
            (listOf(0.0) + events.filter { it.second == "lap" }.map { it.first } + elapsed)
                .zipWithNext()
                .filter { (a, b) -> b > a }
                .map { (a, b) -> ExerciseLap(time(a), time(b)) }
        write(
            listOf(
                ExerciseSessionRecord(
                    start,
                    ZoneOffset.UTC,
                    end,
                    ZoneOffset.UTC,
                    metadata("session"),
                    if (meta.flag("indoor")) ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
                    else ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                    title = "Power Log ride",
                    segments = segments,
                    laps = laps,
                    exerciseRoute =
                        route
                            .takeIf { it.isNotEmpty() }
                            ?.distinctBy { it.time }
                            ?.sortedBy { it.time }
                            ?.let { ExerciseRoute(it) },
                )
            )
        )
    }
}
