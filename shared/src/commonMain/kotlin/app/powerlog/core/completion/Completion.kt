package app.powerlog.core.completion

import app.powerlog.core.health.*
import app.powerlog.core.protocol.*

data class RideCompletionSnapshot
@Throws(IllegalArgumentException::class)
constructor(
    val rideId: String?,
    val phase: String,
    val endedAt: ProtocolTimestamp?,
    val collectionRevision: Long?,
    val healthOutcome: HealthOutcome,
    val healthReason: String? = null,
    val saveToHealth: Boolean = true,
    val watchEnabled: Boolean = false,
    val browser: Boolean = false,
    val exportEligible: Boolean = true,
    val healthRetryAvailable: Boolean = false,
) {
    init {
        require(collectionRevision == null || collectionRevision in 0..MAX_REVISION) { "Invalid workout revision" }
    }
}

object RideCompletion {
    // Local data is usable independently of the outcome of an external Health save or companion delivery.
    fun exportReady(snapshot: RideCompletionSnapshot, selectedRideId: String?): Boolean =
        snapshot.exportEligible &&
            snapshot.rideId != null &&
            snapshot.rideId == selectedRideId &&
            snapshot.phase == "completed" &&
            snapshot.endedAt != null &&
            (snapshot.collectionRevision ?: 0) > 0

    fun openExport(
        snapshot: RideCompletionSnapshot,
        selectedRideId: String?,
        pinRevision: Long,
    ): Boolean = exportReady(snapshot, selectedRideId) && snapshot.collectionRevision == pinRevision
}

data class HistoricalDeleteContext(
    val targetPhase: String,
    val selectedTargetPhase: String? = null,
    val healthBusy: Boolean? = null,
    val startBusy: Boolean? = null,
    val finalizationBusy: Boolean? = null,
    val targetPending: Boolean? = null,
    val targetRecovering: Boolean? = null,
    val targetBusy: Boolean? = null,
)

object HistoricalDeletionAdmission {
    fun canDelete(context: HistoricalDeleteContext): Boolean =
        context.targetPhase in listOf("completed", "failed") &&
            (context.selectedTargetPhase == null || context.selectedTargetPhase in listOf("completed", "failed")) &&
            context.healthBusy != true &&
            context.startBusy != true &&
            context.finalizationBusy != true &&
            context.targetPending != true &&
            context.targetRecovering != true &&
            context.targetBusy != true
}

class CompletionUpdates {
    private var requestRevision = 0L
    private var current: RideCompletionSnapshot? = null

    fun beginRefresh(): Long = ++requestRevision

    fun receive(snapshot: RideCompletionSnapshot): Boolean {
        requestRevision++
        return admit(snapshot)
    }

    fun finishRefresh(request: Long, snapshot: RideCompletionSnapshot): Boolean =
        request == requestRevision && admit(snapshot)

    private fun admit(snapshot: RideCompletionSnapshot): Boolean {
        val previous = current
        if (
            previous != null &&
                previous.rideId == snapshot.rideId &&
                older(snapshot.collectionRevision, previous.collectionRevision)
        )
            return false
        current = snapshot
        return true
    }

    private fun older(incoming: Long?, retained: Long?): Boolean =
        incoming != null && retained != null && incoming < retained
}
