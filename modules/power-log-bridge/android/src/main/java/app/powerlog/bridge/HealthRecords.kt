package app.powerlog.bridge

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device as NativeDevice
import androidx.health.connect.client.units.*
import app.powerlog.core.health.*
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

internal object HealthRecords {
    fun instant(us: Long): Instant = Instant.ofEpochMilli(SecondBuckets.floorDiv(us, 1000))

    fun type(component: Component): KClass<out Record> =
        when (component) {
            Component.riderPower -> PowerRecord::class
            Component.cyclingCadence -> CyclingPedalingCadenceRecord::class
            Component.cyclingSpeed -> SpeedRecord::class
            Component.cyclingDistance -> DistanceRecord::class
            Component.workout,
            Component.route,
            Component.laps -> ExerciseSessionRecord::class
            else -> error("No Health Connect record for $component")
        }

    fun native(record: PublicationRecord, providerId: String = ""): Record {
        val projection =
            (record.frozenContent as NormalizedContent.Present).projection as AdapterProjection.HealthConnect
        val a = ZoneOffset.ofTotalSeconds(projection.startZoneOffsetSeconds)
        val b = ZoneOffset.ofTotalSeconds(projection.endZoneOffsetSeconds)
        return when (val content = projection.content) {
            is ProviderContent.Quantity -> {
                val q = content.fields
                val start = instant(q.startUs)
                val end = instant(q.endUs)
                require(end > start) { "unrepresentableTime" }
                val device =
                    NativeDevice(
                        type =
                            if (q.source.device == Device.controller) NativeDevice.TYPE_UNKNOWN
                            else NativeDevice.TYPE_PHONE
                    )
                val metadata = HealthMetadata.create(providerId, record.syncId, record.version, device)
                when (q.kind) {
                    HealthKind.riderPower ->
                        PowerRecord(
                            start,
                            a,
                            end,
                            b,
                            listOf(PowerRecord.Sample(start, q.value.watts)),
                            metadata,
                        )
                    HealthKind.cyclingCadence ->
                        CyclingPedalingCadenceRecord(
                            start,
                            a,
                            end,
                            b,
                            listOf(CyclingPedalingCadenceRecord.Sample(start, q.value)),
                            metadata,
                        )
                    HealthKind.cyclingSpeed ->
                        SpeedRecord(
                            start,
                            a,
                            end,
                            b,
                            listOf(SpeedRecord.Sample(start, q.value.metersPerSecond)),
                            metadata,
                        )
                    HealthKind.cyclingDistance -> DistanceRecord(start, a, end, b, q.value.meters, metadata)
                }
            }
            is ProviderContent.Session -> {
                val q = content.fields
                val metadata =
                    HealthMetadata.create(
                        providerId,
                        record.syncId,
                        record.version,
                        NativeDevice(
                            type = NativeDevice.TYPE_PHONE,
                            manufacturer = projection.encoding.manufacturer,
                            model = projection.encoding.model,
                        ),
                    )
                ExerciseSessionRecord(
                    instant(q.startUs),
                    a,
                    instant(q.endUs),
                    b,
                    metadata,
                    if (q.metadata.indoor) ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
                    else ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                    title = "Power Log ride",
                    laps = q.laps.map { ExerciseLap(instant(it.startUs), instant(it.endUs)) },
                    segments =
                        q.pauses.map {
                            ExerciseSegment(
                                instant(it.startUs),
                                instant(it.endUs),
                                ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                            )
                        },
                    exerciseRoute =
                        q.route
                            .takeIf { it.isNotEmpty() }
                            ?.let { points ->
                                ExerciseRoute(
                                    points.map {
                                        ExerciseRoute.Location(
                                            instant(it.timeUs),
                                            it.latitude,
                                            it.longitude,
                                            it.horizontalAccuracyM?.meters,
                                            it.verticalAccuracyM?.meters,
                                            it.altitudeMeters?.meters,
                                        )
                                    }
                                )
                            },
                )
            }
            else -> error("Unsupported Health Connect projection")
        }
    }

    private fun normalizedDevice(device: NativeDevice?) = device?.takeUnless {
        it.type == NativeDevice.TYPE_UNKNOWN && it.manufacturer == null && it.model == null
    }

    fun matches(expected: PublicationRecord, actual: Record): Boolean {
        if (
            actual.metadata.clientRecordId != expected.syncId || actual.metadata.clientRecordVersion != expected.version
        )
            return false
        val native = native(expected)
        return when {
            native is PowerRecord && actual is PowerRecord ->
                native.startTime == actual.startTime &&
                    native.endTime == actual.endTime &&
                    native.samples == actual.samples &&
                    native.startZoneOffset == actual.startZoneOffset &&
                    native.endZoneOffset == actual.endZoneOffset
            native is CyclingPedalingCadenceRecord && actual is CyclingPedalingCadenceRecord ->
                native.startTime == actual.startTime &&
                    native.endTime == actual.endTime &&
                    native.samples == actual.samples
            native is SpeedRecord && actual is SpeedRecord ->
                native.startTime == actual.startTime &&
                    native.endTime == actual.endTime &&
                    native.samples == actual.samples
            native is DistanceRecord && actual is DistanceRecord ->
                native.startTime == actual.startTime &&
                    native.endTime == actual.endTime &&
                    native.distance == actual.distance
            native is ExerciseSessionRecord && actual is ExerciseSessionRecord -> {
                val intent = expected.routeIntent
                native.startTime == actual.startTime &&
                    native.endTime == actual.endTime &&
                    native.exerciseType == actual.exerciseType &&
                    native.title == actual.title &&
                    native.laps == actual.laps &&
                    native.segments == actual.segments &&
                    (intent == RouteIntent.Preserve || native.exerciseRouteResult == actual.exerciseRouteResult)
            }
            else -> false
        } &&
            normalizedDevice(native.metadata.device) == normalizedDevice(actual.metadata.device) &&
            native.metadata.recordingMethod == actual.metadata.recordingMethod
    }

    fun sessionEncodedBytes(record: ExerciseSessionRecord, encoding: RouteEncoding): Long {
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.writeInt(0)
            parcel.writeString(record.metadata.id.takeIf { it.length >= 36 } ?: "00000000-0000-0000-0000-000000000000")
            parcel.writeString(encoding.packageName)
            parcel.writeString(encoding.appName)
            parcel.writeLong(record.metadata.lastModifiedTime.toEpochMilli())
            parcel.writeString(record.metadata.clientRecordId)
            parcel.writeLong(record.metadata.clientRecordVersion)
            parcel.writeString(record.metadata.device?.manufacturer)
            parcel.writeString(record.metadata.device?.model)
            parcel.writeInt(record.metadata.device?.type ?: 0)
            parcel.writeInt(record.metadata.recordingMethod)
            parcel.writeLong(record.startTime.toEpochMilli())
            parcel.writeInt(record.startZoneOffset?.totalSeconds ?: 0)
            parcel.writeLong(record.endTime.toEpochMilli())
            parcel.writeInt(record.endZoneOffset?.totalSeconds ?: 0)
            parcel.writeString(record.notes)
            parcel.writeInt(record.exerciseType)
            parcel.writeString(record.title)
            val route = (record.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.route
            parcel.writeBoolean(route != null)
            parcel.writeBoolean(route == null)
            if (route != null) {
                parcel.writeInt(route.size)
                route.forEach {
                    parcel.writeLong(it.time.toEpochMilli())
                    parcel.writeDouble(it.latitude)
                    parcel.writeDouble(it.longitude)
                    parcel.writeDouble(it.horizontalAccuracy?.inMeters ?: 0.0)
                    parcel.writeDouble(it.verticalAccuracy?.inMeters ?: 0.0)
                    parcel.writeDouble(it.altitude?.inMeters ?: 0.0)
                }
            }
            parcel.writeInt(record.laps.size)
            record.laps.forEach {
                parcel.writeLong(it.startTime.toEpochMilli())
                parcel.writeLong(it.endTime.toEpochMilli())
                parcel.writeDouble(it.length?.inMeters ?: 0.0)
            }
            parcel.writeInt(record.segments.size)
            record.segments.forEach {
                parcel.writeLong(it.startTime.toEpochMilli())
                parcel.writeLong(it.endTime.toEpochMilli())
                parcel.writeInt(it.repetitions)
                parcel.writeInt(it.segmentType)
            }
            parcel.writeString(record.plannedExerciseSessionId)
            parcel.dataSize().toLong()
        } finally {
            parcel.recycle()
        }
    }
}
