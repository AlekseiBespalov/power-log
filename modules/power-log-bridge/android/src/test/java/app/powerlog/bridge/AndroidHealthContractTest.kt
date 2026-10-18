package app.powerlog.bridge

import androidx.health.connect.client.records.PowerRecord
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidHealthContractTest {
    @Test
    fun publishesCanonicalSingleMemberSeconds() {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-contract.sqlite")
        try {
            val start = Instant.parse("2026-10-02T00:00:00Z")
            val id =
                store.create(
                    RideOptions(false, saveToHealth = true, recordGPS = false),
                    RecordingClockReading(start.toString(), 0),
                )
            for (i in 0..8) store.insert(
                id,
                i / 8.0,
                start.plusMillis(i * 125L).toString(),
                "telemetry",
                true,
                0,
                mapOf("humanPowerW" to 150.0),
            )
            store.seal(id, RideTiming(1.0, 1.0, start.plusSeconds(1).toString()))
            val access = FakeHealthConnectAccess()
            HealthExport(context, store, access).save(id)
            val record = access.records.filterIsInstance<PowerRecord>().single()
            assertEquals("powerlog:$id:rider-power:second:0", record.metadata.clientRecordId)
            assertEquals(1, record.samples.size)
            assertEquals(start, record.startTime)
            assertEquals(start.plusSeconds(1), record.endTime)
        } finally {
            store.close()
        }
    }
}
