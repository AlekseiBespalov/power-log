package app.powerlog.core.completion

import app.powerlog.core.health.*
import app.powerlog.core.protocol.*
import kotlin.test.*

class CompletionContractTest {
    private fun snapshot() =
        RideCompletionSnapshot(
            rideId = "11111111-1111-4111-8111-111111111111",
            phase = "completed",
            endedAt = Timestamps.parse("2026-01-01T00:01:00Z"),
            collectionRevision = 4,
            healthOutcome = HealthOutcome.pending,
            watchEnabled = true,
        )

    @Test
    fun localExportDoesNotWaitForWatchOrHealth() {
        val record = snapshot()
        for (outcome in HealthOutcome.entries) {
            val changed = record.copy(healthOutcome = outcome, healthReason = "unconfirmed")
            assertTrue(RideCompletion.exportReady(changed, changed.rideId), outcome.name)
            assertTrue(RideCompletion.openExport(changed, changed.rideId, 4), outcome.name)
        }
    }

    @Test
    fun exportStillRequiresCompletedLocalDataAndPinnedRevision() {
        val record = snapshot()
        for (changed in
            listOf(
                record.copy(phase = "running"),
                record.copy(phase = "interrupted"),
                record.copy(endedAt = null),
                record.copy(collectionRevision = null),
                record.copy(collectionRevision = 0),
                record.copy(exportEligible = false),
                record.copy(rideId = null),
            )) {
            assertFalse(RideCompletion.exportReady(changed, record.rideId))
        }
        assertFalse(RideCompletion.exportReady(record, "other-ride"))
        assertFalse(RideCompletion.openExport(record, record.rideId, 3))
        assertFalse(RideCompletion.openExport(record, record.rideId, 5))
    }

    @Test
    fun updatesFenceStaleCollectionReadsWithoutWaitingForProviderRevision() {
        val record = snapshot()
        val updates = CompletionUpdates()
        assertTrue(updates.receive(record))
        assertFalse(updates.receive(record.copy(collectionRevision = 3)))
        val old = updates.beginRefresh()
        val latest = updates.beginRefresh()
        assertFalse(updates.finishRefresh(old, record))
        assertTrue(updates.finishRefresh(latest, record))
        val pending = updates.beginRefresh()
        assertTrue(updates.receive(record.copy(rideId = "another")))
        assertFalse(updates.finishRefresh(pending, record))
    }

    @Test
    fun historicalDeletionStillProtectsAnExportReaderAndLocalMutation() {
        assertTrue(HistoricalDeletionAdmission.canDelete(HistoricalDeleteContext("completed")))
        assertFalse(HistoricalDeletionAdmission.canDelete(HistoricalDeleteContext("completed", targetBusy = true)))
        assertFalse(HistoricalDeletionAdmission.canDelete(HistoricalDeleteContext("completed", targetPending = true)))
        assertFalse(HistoricalDeletionAdmission.canDelete(HistoricalDeleteContext("running")))
    }

    @Test
    fun activeCaptureRevisionsDoNotRedeliverControlOrHistoryButCompletedImportsDo() {
        val active = snapshot().copy(phase = "running", endedAt = null)
        assertEquals(CompletionCodec.identity(active), CompletionCodec.identity(active.copy(collectionRevision = 5)))
        assertEquals(
            CompletionCodec.identity(active, true),
            CompletionCodec.identity(active.copy(collectionRevision = 5), true),
        )
        assertNotEquals(
            CompletionCodec.identity(snapshot()),
            CompletionCodec.identity(snapshot().copy(collectionRevision = 5)),
        )
    }
}
