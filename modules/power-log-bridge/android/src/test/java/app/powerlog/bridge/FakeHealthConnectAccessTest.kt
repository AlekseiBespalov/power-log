package app.powerlog.bridge

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.watts
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeHealthConnectAccessTest {
    private val start = Instant.parse("2026-10-02T00:00:00Z")
    private val end = start.plusSeconds(120)

    private fun metadata(id: String, version: Long = 1) =
        Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE), id, version)

    private fun power(value: Double, version: Long = 1, id: String = "power") =
        PowerRecord(
            start,
            ZoneOffset.UTC,
            end,
            ZoneOffset.UTC,
            listOf(PowerRecord.Sample(start, value.watts)),
            metadata(id, version),
        )

    private fun session(version: Long, route: Boolean, laps: Int = 1) =
        ExerciseSessionRecord(
            start,
            ZoneOffset.UTC,
            end,
            ZoneOffset.UTC,
            metadata("workout", version),
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            laps =
                (0 until laps).map {
                    ExerciseLap(start.plusSeconds(it * 30L), start.plusSeconds((it + 1) * 30L))
                },
            exerciseRoute =
                if (route) ExerciseRoute(listOf(ExerciseRoute.Location(start, 0.0, version * 0.001))) else null,
        )

    @Test
    fun equalVersionReplacesLowerVersionIsIgnoredAndHigherVersionReplaces() = runBlocking {
        val access = FakeHealthConnectAccess()
        for ((version, value) in listOf(1L to 151.0, 1L to 999.0, 0L to 998.0, 2L to 152.0)) {
            access.write(listOf(power(value, version)))
            assertEquals(1, access.records.size)
            val saved = access.records.single() as PowerRecord
            assertEquals(
                if (version == 0L) 999.0 else value,
                saved.samples.single().power.inWatts,
                0.0,
            )
            assertEquals(if (version == 0L) 1L else version, saved.metadata.clientRecordVersion)
        }
        assertEquals(4, access.batches.size)
    }

    @Test
    fun equalTimesFollowBothObservedProviderPoliciesInEverySeries() = runBlocking {
        equalTimeSeries(false)
        equalTimeSeries(true)
    }

    private suspend fun equalTimeSeries(deduplicate: Boolean) {
        val access = if (deduplicate) FakeHealthConnectAccess() else FakeHealthConnectAccess(false)
        access.grants += HealthPermission.getWritePermission(HeartRateRecord::class)
        access.write(
            listOf<Record>(
                PowerRecord(
                    start,
                    ZoneOffset.UTC,
                    end,
                    ZoneOffset.UTC,
                    listOf(
                        PowerRecord.Sample(start, 111.0.watts),
                        PowerRecord.Sample(start, 222.0.watts),
                        PowerRecord.Sample(start, 111.0.watts),
                    ),
                    metadata("power"),
                ),
                CyclingPedalingCadenceRecord(
                    start,
                    ZoneOffset.UTC,
                    end,
                    ZoneOffset.UTC,
                    listOf(
                        CyclingPedalingCadenceRecord.Sample(start, 81.0),
                        CyclingPedalingCadenceRecord.Sample(start, 82.0),
                        CyclingPedalingCadenceRecord.Sample(start, 81.0),
                    ),
                    metadata("cadence"),
                ),
                SpeedRecord(
                    start,
                    ZoneOffset.UTC,
                    end,
                    ZoneOffset.UTC,
                    listOf(
                        SpeedRecord.Sample(
                            start,
                            androidx.health.connect.client.units.Velocity.metersPerSecond(5.0),
                        ),
                        SpeedRecord.Sample(
                            start,
                            androidx.health.connect.client.units.Velocity.metersPerSecond(6.0),
                        ),
                        SpeedRecord.Sample(
                            start,
                            androidx.health.connect.client.units.Velocity.metersPerSecond(5.0),
                        ),
                    ),
                    metadata("speed"),
                ),
                HeartRateRecord(
                    start,
                    ZoneOffset.UTC,
                    end,
                    ZoneOffset.UTC,
                    listOf(
                        HeartRateRecord.Sample(start, 81),
                        HeartRateRecord.Sample(start, 82),
                        HeartRateRecord.Sample(start, 81),
                    ),
                    metadata("heart"),
                ),
            )
        )
        assertEquals(
            if (deduplicate) listOf(111.0) else listOf(111.0, 222.0, 111.0),
            access.records.filterIsInstance<PowerRecord>().single().samples.map {
                it.power.inWatts
            },
        )
        assertEquals(
            if (deduplicate) listOf(81.0) else listOf(81.0, 82.0, 81.0),
            access.records.filterIsInstance<CyclingPedalingCadenceRecord>().single().samples.map {
                it.revolutionsPerMinute
            },
        )
        assertEquals(
            if (deduplicate) listOf(5.0) else listOf(5.0, 6.0, 5.0),
            access.records.filterIsInstance<SpeedRecord>().single().samples.map {
                it.speed.inMetersPerSecond
            },
        )
        assertEquals(
            if (deduplicate) listOf(81L) else listOf(81L, 82L, 81L),
            access.records.filterIsInstance<HeartRateRecord>().single().samples.map {
                it.beatsPerMinute
            },
        )
    }

    @Test
    fun sessionRevisionsReplaceLapsAndRoutesThenOmissionRemovesAnAuthorizedRoute() = runBlocking {
        val access = FakeHealthConnectAccess()
        access.write(listOf(session(1, true)))
        access.write(listOf(session(2, true, 2)))
        val revised = access.records.single() as ExerciseSessionRecord
        assertEquals(2, revised.laps.size)
        assertEquals(
            0.002,
            (revised.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route.single().longitude,
            0.0,
        )
        access.write(listOf(session(3, false)))
        assertEquals(
            ExerciseRouteResult.NoData(),
            (access.records.single() as ExerciseSessionRecord).exerciseRouteResult,
        )
    }

    @Test
    fun sessionOmissionPreservesTheRouteWhenRoutePermissionIsRevoked() = runBlocking {
        val access = FakeHealthConnectAccess()
        access.write(listOf(session(1, true)))
        access.grants -= HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
        access.write(listOf(session(2, false, 2)))
        assertEquals(1, access.records.size)
        val saved = access.records.single() as ExerciseSessionRecord
        assertEquals(2, saved.laps.size)
        assertEquals(
            1,
            (saved.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route.size,
        )
        assertEquals(2L, saved.metadata.clientRecordVersion)
    }

    @Test
    fun revokedPermissionRejectsTheWholeBatchAndHooksKeepLostReceiptWrites() = runBlocking {
        val access = FakeHealthConnectAccess()
        access.beforeWrite = {
            access.grants -= HealthPermission.getWritePermission(PowerRecord::class)
        }
        try {
            access.write(listOf<Record>(session(1, true), power(151.0)))
            fail("Expected revoked permission")
        } catch (_: SecurityException) {}
        assertTrue(access.records.isEmpty())
        access.beforeWrite = {}
        access.grants += HealthPermission.getWritePermission(PowerRecord::class)
        access.afterWrite = { error("Lost receipt") }
        try {
            access.write(listOf(power(151.0)))
        } catch (_: IllegalStateException) {}
        assertEquals(1, access.records.size)
        access.afterWrite = {}
        access.write(listOf(power(151.0)))
        assertEquals(1, access.records.size)
    }
}
