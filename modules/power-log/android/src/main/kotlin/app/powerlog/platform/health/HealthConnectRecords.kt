package app.powerlog.platform.health

import android.os.Build
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import androidx.health.connect.client.units.meters
import app.powerlog.application.port.HealthOriginal
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthQuantity
import app.powerlog.domain.health.HealthConnectRecordPlan
import app.powerlog.domain.health.HealthIds
import app.powerlog.domain.health.HealthPolicy
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

internal object HealthConnectRecords {
    const val MAX_LAPS_OR_PAUSES = 1_000

    val readTypes: Map<String, KClass<out Record>> =
        mapOf(
            "heartRate" to HeartRateRecord::class,
            "activeEnergy" to ActiveCaloriesBurnedRecord::class,
            "respiratoryRate" to RespiratoryRateRecord::class,
            "oxygenSaturation" to OxygenSaturationRecord::class,
            "hrvRMSSD" to HeartRateVariabilityRmssdRecord::class,
            "riderPower" to PowerRecord::class,
            "cyclingCadence" to CyclingPedalingCadenceRecord::class,
            "cyclingSpeed" to SpeedRecord::class,
            "cyclingDistance" to DistanceRecord::class,
            "association" to ExerciseSessionRecord::class,
        )

    fun writePermission(component: HealthComponent): String? =
        when (component) {
            HealthComponent.workout,
            HealthComponent.laps,
            HealthComponent.association -> HealthPermission.getWritePermission(ExerciseSessionRecord::class)
            HealthComponent.route -> HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
            HealthComponent.riderPower -> HealthPermission.getWritePermission(PowerRecord::class)
            HealthComponent.cyclingCadence -> HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class)
            HealthComponent.cyclingSpeed -> HealthPermission.getWritePermission(SpeedRecord::class)
            HealthComponent.cyclingDistance -> HealthPermission.getWritePermission(DistanceRecord::class)
            HealthComponent.readback -> null
        }

    fun readPermission(metric: String): String? = readTypes[metric]?.let(HealthPermission::getReadPermission)

    fun instant(us: Long): Instant = Instant.ofEpochMilli(Math.floorDiv(us, 1_000L))

    fun exact(us: Long): Instant =
        Instant.ofEpochSecond(Math.floorDiv(us, 1_000_000L), Math.floorMod(us, 1_000_000L) * 1_000L)

    fun micros(instant: Instant): Long =
        Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000L), instant.nano / 1_000L)

    fun record(plan: HealthConnectRecordPlan): Record? =
        when (plan) {
            is HealthConnectRecordPlan.Record -> quantity(plan)
            is HealthConnectRecordPlan.Session -> session(plan)
        }

    private fun quantity(record: HealthConnectRecordPlan.Record): Record? {
        val plan = record.plan
        val start = instant(plan.startUtcUs)
        val end = instant(plan.endUtcUs)
        if (end <= start) return null
        val device =
            Device(
                type =
                    when (plan.quantity) {
                        HealthQuantity.riderPower,
                        HealthQuantity.cyclingCadence -> Device.TYPE_UNKNOWN
                        HealthQuantity.cyclingSpeed,
                        HealthQuantity.cyclingDistance -> Device.TYPE_PHONE
                    }
            )
        val metadata = Metadata.activelyRecorded(device, plan.syncId, HealthIds.syncVersion.toLong())
        val utc = ZoneOffset.UTC
        return when (plan.quantity) {
            HealthQuantity.riderPower ->
                PowerRecord(start, utc, end, utc, listOf(PowerRecord.Sample(start, Power.watts(plan.value))), metadata)
            HealthQuantity.cyclingCadence ->
                CyclingPedalingCadenceRecord(
                    start,
                    utc,
                    end,
                    utc,
                    listOf(CyclingPedalingCadenceRecord.Sample(start, plan.value)),
                    metadata,
                )
            HealthQuantity.cyclingSpeed ->
                SpeedRecord(
                    start,
                    utc,
                    end,
                    utc,
                    listOf(SpeedRecord.Sample(start, Velocity.metersPerSecond(plan.value))),
                    metadata,
                )
            HealthQuantity.cyclingDistance -> DistanceRecord(start, utc, end, utc, plan.value.meters, metadata)
        }
    }

    private fun session(session: HealthConnectRecordPlan.Session): Record? {
        val plan = session.plan
        val start = instant(plan.startUtcUs)
        val end = instant(requireNotNull(plan.endUtcUs) { "A session plan has its end" })
        if (end <= start) return null
        val device = Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL)
        val utc = ZoneOffset.UTC
        return ExerciseSessionRecord(
            start,
            utc,
            end,
            utc,
            Metadata.activelyRecorded(device, plan.syncId, HealthIds.syncVersion.toLong()),
            if (plan.indoor) ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
            else ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            title = HealthPolicy.healthConnectSessionTitle,
            laps = plan.laps.map { ExerciseLap(instant(it.startUtcUs), instant(it.endUtcUs)) },
            segments =
                plan.pauses.map {
                    ExerciseSegment(
                        instant(it.startUtcUs),
                        instant(it.endUtcUs),
                        ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                    )
                },
            exerciseRoute =
                session.route
                    .takeIf { it.isNotEmpty() }
                    ?.let { points ->
                        ExerciseRoute(
                            points.map {
                                ExerciseRoute.Location(
                                    instant(it.utcUs),
                                    it.latitude,
                                    it.longitude,
                                    it.horizontalAccuracyM.meters,
                                    it.verticalAccuracyM?.meters,
                                    it.altitudeM?.meters,
                                )
                            }
                        )
                    },
        )
    }

    fun originals(metric: String, record: Record): List<HealthOriginal> {
        fun original(member: Int?, value: Double?, start: Instant, end: Instant) =
            HealthOriginal(
                sampleId = record.metadata.id.lowercase(),
                member = member,
                metric = metric,
                value = value,
                startUtcUs = micros(start),
                endUtcUs = micros(end),
                dataOrigin = record.metadata.dataOrigin.packageName,
                syncId = record.metadata.clientRecordId,
            )
        return when (record) {
            is HeartRateRecord ->
                record.samples.mapIndexed { i, it -> original(i, it.beatsPerMinute.toDouble(), it.time, it.time) }
            is PowerRecord -> record.samples.mapIndexed { i, it -> original(i, it.power.inWatts, it.time, it.time) }
            is CyclingPedalingCadenceRecord ->
                record.samples.mapIndexed { i, it -> original(i, it.revolutionsPerMinute, it.time, it.time) }
            is SpeedRecord ->
                record.samples.mapIndexed { i, it -> original(i, it.speed.inMetersPerSecond, it.time, it.time) }
            is ActiveCaloriesBurnedRecord ->
                listOf(original(null, record.energy.inKilocalories, record.startTime, record.endTime))
            is DistanceRecord -> listOf(original(null, record.distance.inMeters, record.startTime, record.endTime))
            is RespiratoryRateRecord -> listOf(original(null, record.rate, record.time, record.time))
            is OxygenSaturationRecord -> listOf(original(null, record.percentage.value, record.time, record.time))
            is HeartRateVariabilityRmssdRecord ->
                listOf(original(null, record.heartRateVariabilityMillis, record.time, record.time))
            is ExerciseSessionRecord -> listOf(original(null, null, record.startTime, record.endTime))
            else -> emptyList()
        }
    }
}
