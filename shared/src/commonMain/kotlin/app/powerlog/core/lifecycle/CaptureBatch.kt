package app.powerlog.core.lifecycle

import app.powerlog.core.HealthIdentity
import app.powerlog.core.presentation.Failures
import app.powerlog.core.presentation.RideFailure
import app.powerlog.core.protocol.*

enum class LifecycleStorageError {
    sqlite,
    cocoa,
    posix,
    captureInterrupted,
    data,
    operation,
}

data class CaptureMembership
@Throws(IllegalArgumentException::class)
constructor(val collectionId: String, val elapsedSeconds: Double, val generation: Long) {
    init {
        require(HealthIdentity.validRide(collectionId) && generation in 1..MAX_REVISION)
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0)
    }
}

data class CaptureOriginal
@Throws(IllegalArgumentException::class)
constructor(
    val observationId: String,
    val producer: String,
    val sequence: Long,
    val timestamp: ProtocolTimestamp,
    val clockEpoch: String,
    val acquisitionUs: Long,
    val connectionEpoch: String?,
    val live: CaptureMembership?,
    val ride: CaptureMembership?,
    val content: String,
) {
    init {
        require(HealthIdentity.validRide(observationId) && HealthIdentity.validRide(clockEpoch))
        require(connectionEpoch == null || HealthIdentity.validRide(connectionEpoch))
        require(producer in setOf("controller", "phone", "watch") && sequence > 0 && acquisitionUs >= 0)
        require(Timestamps.parse(timestamp.original) == timestamp)
        JsonCodec.checkUnicode(content)
    }
}

data class CaptureFault
@Throws(IllegalArgumentException::class)
constructor(
    val faultId: String,
    val key: LifecycleKey?,
    val timestamp: ProtocolTimestamp,
    val observationId: String?,
    val error: String,
) {
    init {
        require(HealthIdentity.validRide(faultId) && (observationId == null || HealthIdentity.validRide(observationId)))
        require(Timestamps.parse(timestamp.original) == timestamp && error.isNotBlank())
    }
}

class CaptureBatch
@Throws(IllegalArgumentException::class)
constructor(originals: List<CaptureOriginal> = emptyList(), val fault: CaptureFault? = null) {
    private val owned: List<CaptureOriginal> = originals.toList()
    val originals: List<CaptureOriginal>
        get() = owned.toList()

    init {
        require(originals.size <= maximumFrames && originals.map { it.observationId }.distinct().size == originals.size)
    }

    companion object {
        const val maximumFrames = 64
    }
}

data class CaptureAdmission(val batch: CaptureBatch, val admitted: Boolean, val outcome: String, val error: String?)

data class CaptureCommit(val batch: CaptureBatch, val accepted: List<CaptureOriginal>, val outcome: String)

object LifecycleCapture {
    @Throws(IllegalArgumentException::class)
    fun admit(batch: CaptureBatch, original: CaptureOriginal, overflow: CaptureFault): CaptureAdmission {
        val known = batch.originals.firstOrNull { it.observationId == original.observationId }
        val admitted = if (batch.fault == null || known?.ride != null) original else original.copy(ride = null)
        if (known != null)
            return CaptureAdmission(
                batch,
                false,
                if (known == admitted) "duplicate" else "conflict",
                if (known == admitted) null else RideFailure.captureRejected.name,
            )
        if (batch.originals.size == CaptureBatch.maximumFrames) {
            val fault = batch.fault ?: overflow
            return CaptureAdmission(
                CaptureBatch(batch.originals, fault),
                false,
                "overflow",
                Failures.present(fault.error).name,
            )
        }
        return CaptureAdmission(CaptureBatch(batch.originals + admitted, batch.fault), true, "buffered", null)
    }

    @Throws(IllegalArgumentException::class)
    fun committed(batch: CaptureBatch, attempted: List<CaptureOriginal>, succeeded: Boolean): CaptureCommit {
        require(attempted.size <= CaptureBatch.maximumFrames)
        if (!succeeded) return CaptureCommit(batch, emptyList(), "commitFailed")
        if (attempted.isEmpty()) return CaptureCommit(batch, emptyList(), "checkpointCommitted")
        if (batch.originals.take(attempted.size) != attempted) return CaptureCommit(batch, emptyList(), "staleCommit")
        return CaptureCommit(
            CaptureBatch(batch.originals.drop(attempted.size), batch.fault),
            attempted.toList(),
            "committed",
        )
    }

    fun acknowledgeFault(batch: CaptureBatch, faultId: String, succeeded: Boolean): CaptureBatch =
        if (succeeded && batch.fault?.faultId == faultId) CaptureBatch(batch.originals) else batch
}

data class LifecycleStorageFailure(val state: LifecycleState, val batch: CaptureBatch, val haltCapture: Boolean)

object LifecycleStorage {
    private val activePhases =
        setOf(
            LifecyclePhase.preparing,
            LifecyclePhase.running,
            LifecyclePhase.paused,
            LifecyclePhase.interrupted,
            LifecyclePhase.finishing,
        )

    @Throws(IllegalArgumentException::class)
    fun restored(state: LifecycleState, frozen: LifecycleState): LifecycleState {
        val failed = frozen.ride
        val retained = state.ride
        require(failed != null && failed.storageInterrupted) { "The fault checkpoint is not frozen." }
        require(
            retained != null && retained.key.rideId == failed.key.rideId && retained.key.owner == failed.key.owner
        ) {
            "Capture recovery conflicts with the selected owner."
        }
        return if (retained.storageInterrupted) state else frozen
    }

    fun qualifies(error: LifecycleStorageError): Boolean =
        error in
            setOf(
                LifecycleStorageError.sqlite,
                LifecycleStorageError.cocoa,
                LifecycleStorageError.posix,
                LifecycleStorageError.captureInterrupted,
            )

    @Throws(IllegalArgumentException::class)
    fun failed(
        state: LifecycleState,
        batch: CaptureBatch,
        fault: CaptureFault,
        kind: LifecycleStorageError,
        clock: LifecycleClock,
    ): LifecycleStorageFailure {
        val ride = state.ride
        val current =
            ride != null &&
                fault.key == ride.key &&
                !ride.collectionClosed &&
                !ride.deleted &&
                (ride.pending != null || ride.terminal != null || ride.phase in activePhases)
        val error = Failures.present(fault.error).name
        if (!qualifies(kind))
            return LifecycleStorageFailure(
                if (current) state.copy(ride = ride!!.copy(error = error)) else state,
                batch,
                false,
            )
        val retained = CaptureBatch(batch.originals, batch.fault ?: fault)
        if (!current) return LifecycleStorageFailure(state, retained, false)
        val boundary = ride!!.terminal?.timing ?: ride.timing
        val next =
            ride.copy(
                phase = if (ride.terminal == null) LifecyclePhase.interrupted else ride.phase,
                timing = boundary,
                elapsedOriginUs = null,
                runningOriginUs = null,
                recovery = LifecycleRecovery.storage,
                error = error,
            )
        return LifecycleStorageFailure(state.copy(ride = next.copy(storageInterrupted = true)), retained, true)
    }

    fun interruption(state: LifecycleState, batch: CaptureBatch): LifecycleEvent? {
        val ride = state.ride ?: return null
        val fault = batch.fault ?: return null
        val key = fault.key ?: return null
        if (
            !ride.storageInterrupted ||
                ride.terminal != null ||
                ride.key.rideId != key.rideId ||
                ride.key.owner != key.owner
        )
            return null
        return LifecycleEvent(fault.faultId, key, "pause", ride.timing, interrupted = true)
    }

    fun acknowledged(
        state: LifecycleState,
        batch: CaptureBatch,
        faultId: String,
        succeeded: Boolean,
    ): LifecycleStorageFailure {
        if (!succeeded || batch.originals.isNotEmpty() || batch.fault?.faultId != faultId)
            return LifecycleStorageFailure(state, batch, false)
        val ride = state.ride
        val sameOwner =
            ride != null &&
                !ride.collectionClosed &&
                !ride.deleted &&
                ride.key.rideId == batch.fault.key?.rideId &&
                ride.key.owner == batch.fault.key?.owner
        val next =
            if (sameOwner) {
                val current = ride!!
                val terminalCommitted =
                    current.phase == LifecyclePhase.completed &&
                        current.pending == null &&
                        current.terminal?.status == LifecycleIntentStatus.applied &&
                        current.ownerSnapshot?.phase == "completed"
                state.copy(
                    ride =
                        current.copy(
                            storageInterrupted = false,
                            recovery = if (terminalCommitted) LifecycleRecovery.resolved else current.recovery,
                            error = if (terminalCommitted) null else current.error,
                        )
                )
            } else state
        return LifecycleStorageFailure(next, LifecycleCapture.acknowledgeFault(batch, faultId, true), false)
    }
}
