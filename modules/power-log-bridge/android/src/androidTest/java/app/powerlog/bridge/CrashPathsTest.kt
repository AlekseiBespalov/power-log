package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.powerlog.core.completion.RideCompletion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Interrupted recordings retain committed data; no provider mutation is replayed after restart. */
@RunWith(AndroidJUnit4::class)
class CrashPathsTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val start = "2026-01-01T00:00:00Z"

    @Test
    fun interruptedRecordingCanFinishAtItsLastCommittedReading() {
        val name = "interrupted-${java.util.UUID.randomUUID()}.sqlite"
        val id =
            RideStore(context, name).use { store ->
                val id = store.create(RideOptions(true, saveToHealth = false), RecordingClockReading(start, 0))
                store.prepareLocalOwner(id)
                store.insert(id, 1.25, "2026-01-01T00:00:01.250Z", "telemetry", true, 0, mapOf("humanPowerW" to 125.0))
                store.fixtureCheckpoint(id, RideTiming(1.25, 1.25, "2026-01-01T00:00:01.250Z"))
                id
            }
        RideStore(context, name).use { store ->
            store.recoverOrphans()
            assertEquals(1, store.page(id).count { it.kind == "telemetry" })
            assertEquals("interrupted", store.metadata(id)["phase"])
            store.fixtureOwnerCommand(
                id,
                RideTiming(60.0, 60.0, "2026-01-01T00:01:00Z"),
                app.powerlog.core.presentation.RideAction.stop,
            )
            assertTrue(RideCompletion.exportReady(store.completion(id), id))
            assertEquals("2026-01-01T00:00:01.250Z", store.metadata(id)["endedAt"])
        }
    }

    @Test
    fun interruptedPublicationIsUncertainWithoutRepeatingTheProviderWrite() {
        val name = "uncertain-${java.util.UUID.randomUUID()}.sqlite"
        val id =
            RideStore(context, name).use { store ->
                val id = store.create(RideOptions(true, saveToHealth = true), RecordingClockReading(start, 0))
                store.fixtureOriginalEnd(id, RideTiming(2.0, 2.0, "2026-01-01T00:00:02Z"))
                store.putHealthJob(store.ensureHealthJob(id).copy(status = "submitted"))
                id
            }
        RideStore(context, name).use { store ->
            val provider = FakeHealthConnectAccess()
            HealthExport(context, store, provider).save(id)
            assertEquals("uncertain", store.healthJob(id)?.status)
            assertTrue(provider.batches.isEmpty())
            assertTrue(RideCompletion.exportReady(store.completion(id), id))
            store.remove(id, discard = true)
            assertFalse(store.exists(id))
        }
    }
}
