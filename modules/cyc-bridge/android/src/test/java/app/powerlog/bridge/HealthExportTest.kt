package app.powerlog.bridge

import androidx.health.connect.client.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HealthExportTest {
    @Test
    fun exportsOnlyActualRiderMeasurementsWithStableIdsAndPauses() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-test.sqlite")
        try {
            val id = store.create(mapOf("saveToHealth" to true, "recordGPS" to false))
            val distance = RideDistance(store)
            store.transaction {
                for (i in 0..12) {
                    val values =
                        mapOf(
                            "humanPowerW" to 150.0,
                            "motorInputPowerW" to 2500.0,
                            "cadenceRpm" to 85.0,
                            "controllerSpeedMps" to 5.0,
                        )
                    val active = i !in 4..6
                    val segment = if (i < 4) 0 else if (i < 7) 1 else 2
                    val row =
                        store.insert(
                            id,
                            i.toDouble(),
                            iso(),
                            "telemetry",
                            active,
                            segment,
                            values,
                            "fixture",
                            "fixture",
                        )
                    distance.append(
                        id,
                        row,
                        i.toDouble(),
                        values,
                        active,
                        segment,
                        "fixture",
                        "fixture",
                        false,
                    )
                }
            }
            store.lifecycle(id, 4.0, "pause")
            store.lifecycle(id, 7.0, "resume")
            store.lifecycle(id, 10.0, "lap")
            store.seal(id, 13.0, 10.0)
            val exporter = HealthExport(context, store)
            suspend fun records(): List<Record> {
                val result = mutableListOf<Record>()
                exporter.writeRide(id) { result.addAll(it) }
                return result
            }
            val first = records()
            val retry = records()
            assertEquals(
                first.map { it.metadata.clientRecordId },
                retry.map { it.metadata.clientRecordId },
            )
            val power = first.filterIsInstance<PowerRecord>().flatMap { it.samples }
            assertEquals(10, power.size)
            assertTrue(power.all { it.power.inWatts == 150.0 })
            assertEquals(
                10,
                first.filterIsInstance<CyclingPedalingCadenceRecord>().sumOf { it.samples.size },
            )
            assertTrue(first.none { it is HeartRateRecord || it is SpeedRecord })
            val session = first.filterIsInstance<ExerciseSessionRecord>().single()
            assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, session.exerciseType)
            assertEquals(1, session.segments.size)
            assertEquals(2, session.laps.size)
            assertEquals("completed", store.metadata(id)["phase"])
        } finally {
            store.close()
        }
    }
}
