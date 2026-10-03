package app.powerlog.bridge

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*

internal class FakeHealthConnectAccess : HealthConnectAccess {
    override var available = true
    var grants =
        setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(PowerRecord::class),
            HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
        )
    var grantReads = 0
    val batches = mutableListOf<List<Record>>()
    var beforeWrite: (List<Record>) -> Unit = {}
    var afterWrite: (List<Record>) -> Unit = {}

    override suspend fun granted(): Set<String> {
        grantReads++
        return if (available) grants else emptySet()
    }

    override suspend fun write(records: List<Record>) {
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
        batches.add(records.toList())
        afterWrite(records)
    }
}
