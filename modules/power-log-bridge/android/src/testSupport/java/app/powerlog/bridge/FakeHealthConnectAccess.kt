package app.powerlog.bridge

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import java.util.UUID
import kotlin.reflect.KClass

internal class FakeHealthConnectAccess(private val deduplicateSampleTimes: Boolean = true) : HealthConnectAccess {
    override var available = true
    override var backgroundReadSupported = true
    override var readableFrom: java.time.Instant? = java.time.Instant.EPOCH
    var grants =
        setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(PowerRecord::class),
            HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        )

    init {
        grants += (HealthExport.writeTypes + HealthReadback.types).map { HealthPermission.getReadPermission(it) }
    }

    var beforeGranted: () -> Unit = {}
    var grantReads = 0
    val batches = mutableListOf<List<Record>>()
    private val stored = linkedMapOf<Pair<KClass<out Record>, String>, Record>()
    val records: List<Record>
        get() = stored.values.toList()

    var beforeWrite: (List<Record>) -> Unit = {}
    var afterWrite: (List<Record>) -> Unit = {}

    override suspend fun granted(): Set<String> {
        beforeGranted()
        grantReads++
        return if (available) grants else emptySet()
    }

    suspend fun write(records: List<Record>) {
        insert(records)
    }

    override suspend fun insert(records: List<Record>): HealthReceipt {
        beforeWrite(records)
        check(records.isNotEmpty())
        if (
            records.any { HealthPermission.getWritePermission(it::class) !in grants } ||
                (HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE !in grants &&
                    records.filterIsInstance<ExerciseSessionRecord>().any {
                        it.exerciseRouteResult is ExerciseRouteResult.Data
                    })
        ) {
            throw SecurityException("Health Connect permission revoked")
        }
        for (record in records) {
            val key = record::class to (record.metadata.clientRecordId ?: UUID.randomUUID().toString())
            val previous = stored[key]
            if (previous != null && record.metadata.clientRecordVersion < previous.metadata.clientRecordVersion)
                continue
            val nativeId = previous?.metadata?.id ?: UUID.randomUUID().toString()
            stored[key] =
                normalize(
                    record,
                    previous,
                    HealthMetadata.received(
                        nativeId,
                        record.metadata.clientRecordId,
                        record.metadata.clientRecordVersion,
                        record.metadata.device,
                        "app.powerlog.mobile",
                        java.time.Instant.now(),
                        record.metadata.recordingMethod,
                    ),
                )
        }
        batches.add(records.toList())
        afterWrite(records)
        return HealthReceipt(
            records.map { stored[it::class to checkNotNull(it.metadata.clientRecordId)]!!.metadata.id }
        )
    }

    override suspend fun update(records: List<Record>) {
        check(records.all { r -> stored.values.any { it.metadata.id == r.metadata.id } }) { "Missing provider ID" }
        write(records)
    }

    var beforeRead: () -> Unit = {}

    override suspend fun read(
        type: KClass<out Record>,
        start: java.time.Instant,
        end: java.time.Instant,
        page: String?,
        own: Boolean,
    ): HealthPage {
        beforeRead()
        if (HealthPermission.getReadPermission(type) !in grants) throw SecurityException("Read denied")
        val candidates =
            stored.values
                .filter { type.isInstance(it) && (!own || it.metadata.dataOrigin.packageName == "app.powerlog.mobile") }
                .filter {
                    when (it) {
                        is PowerRecord -> it.startTime < end && it.endTime > start
                        is CyclingPedalingCadenceRecord -> it.startTime < end && it.endTime > start
                        is SpeedRecord -> it.startTime < end && it.endTime > start
                        is DistanceRecord -> it.startTime < end && it.endTime > start
                        is ExerciseSessionRecord -> it.startTime < end && it.endTime > start
                        is HeartRateRecord -> it.startTime < end && it.endTime > start
                        is ActiveCaloriesBurnedRecord -> it.startTime < end && it.endTime > start
                        is RespiratoryRateRecord -> it.time >= start && it.time < end
                        is OxygenSaturationRecord -> it.time >= start && it.time < end
                        is HeartRateVariabilityRmssdRecord -> it.time >= start && it.time < end
                        else -> false
                    }
                }
        val offset = page?.toInt() ?: 0
        return HealthPage(
            candidates.drop(offset).take(128),
            if (offset + 128 < candidates.size) (offset + 128).toString() else null,
        )
    }

    override suspend fun readById(type: KClass<out Record>, id: String): Record? {
        if (HealthPermission.getReadPermission(type) !in grants) throw SecurityException("Read denied")
        return stored.values.firstOrNull { type.isInstance(it) && it.metadata.id == id }
    }

    fun seed(record: Record) {
        stored[record::class to (record.metadata.clientRecordId ?: record.metadata.id)] = record
    }

    fun forget(clientId: String) {
        stored.keys.filter { it.second == clientId }.forEach(stored::remove)
    }

    private fun normalize(
        record: Record,
        previous: Record?,
        metadata: androidx.health.connect.client.records.metadata.Metadata,
    ): Record =
        when (record) {
            is PowerRecord ->
                PowerRecord(
                    record.startTime,
                    record.startZoneOffset,
                    record.endTime,
                    record.endZoneOffset,
                    if (deduplicateSampleTimes) record.samples.distinctBy { it.time } else record.samples,
                    metadata,
                )
            is CyclingPedalingCadenceRecord ->
                CyclingPedalingCadenceRecord(
                    record.startTime,
                    record.startZoneOffset,
                    record.endTime,
                    record.endZoneOffset,
                    if (deduplicateSampleTimes) record.samples.distinctBy { it.time } else record.samples,
                    metadata,
                )
            is SpeedRecord ->
                SpeedRecord(
                    record.startTime,
                    record.startZoneOffset,
                    record.endTime,
                    record.endZoneOffset,
                    if (deduplicateSampleTimes) record.samples.distinctBy { it.time } else record.samples,
                    metadata,
                )
            is HeartRateRecord ->
                HeartRateRecord(
                    record.startTime,
                    record.startZoneOffset,
                    record.endTime,
                    record.endZoneOffset,
                    if (deduplicateSampleTimes) record.samples.distinctBy { it.time } else record.samples,
                    metadata,
                )
            is DistanceRecord ->
                DistanceRecord(
                    record.startTime,
                    record.startZoneOffset,
                    record.endTime,
                    record.endZoneOffset,
                    record.distance,
                    metadata,
                )
            is ExerciseSessionRecord -> {
                val supplied = (record.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute
                val preserved = (previous as? ExerciseSessionRecord)?.exerciseRouteResult as? ExerciseRouteResult.Data
                val route =
                    if (HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE in grants) supplied
                    else preserved?.exerciseRoute
                ExerciseSessionRecord(
                    record.startTime,
                    record.startZoneOffset,
                    record.endTime,
                    record.endZoneOffset,
                    metadata,
                    record.exerciseType,
                    record.title,
                    record.notes,
                    record.segments,
                    record.laps,
                    route,
                    record.plannedExerciseSessionId,
                )
            }
            else -> record
        }
}
