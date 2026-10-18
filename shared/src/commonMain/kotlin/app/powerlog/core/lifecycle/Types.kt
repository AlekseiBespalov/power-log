package app.powerlog.core.lifecycle

import app.powerlog.core.HealthIdentity
import app.powerlog.core.presentation.FrozenRideOptions
import app.powerlog.core.presentation.RideAction
import app.powerlog.core.presentation.RideFailure
import app.powerlog.core.protocol.*

enum class LifecyclePhase {
    idle,
    preparing,
    running,
    paused,
    interrupted,
    finishing,
    completed,
    failed,
}

enum class LifecycleRecovery {
    idle,
    unresolved,
    storage,
    checkingStorage,
    checking,
    absent,
    resolved,
}

enum class LifecycleOwnerRecovery {
    local,
    nativeSession,
}

enum class LifecycleEffectKind {
    startOwner,
    pauseOwner,
    resumeOwner,
    lapOwner,
    stopOwner,
    discardOwner,
    deleteRecording,
    recoverOwner,
    repairRecording,
    repairHealth,
}

enum class LifecycleIntentStatus {
    pending,
    applied,
    failed,
}

data class LifecycleClock
@Throws(IllegalArgumentException::class)
constructor(val timestamp: ProtocolTimestamp, val epoch: String, val monotonicUs: Long) {
    init {
        require(HealthIdentity.validRide(epoch) && monotonicUs >= 0)
        require(Timestamps.parse(timestamp.original) == timestamp)
    }
}

data class LifecycleKey
@Throws(IllegalArgumentException::class)
constructor(val rideId: String, val owner: Role, val generation: Long) {
    init {
        require(HealthIdentity.validRide(rideId) && generation in 1..MAX_REVISION)
    }
}

data class LifecycleIntent
@Throws(IllegalArgumentException::class)
constructor(
    val commandId: String,
    val key: LifecycleKey,
    val action: RideAction,
    val timing: OwnerTiming,
    val options: FrozenRideOptions,
    val effect: LifecycleEffectKind,
    val status: LifecycleIntentStatus = LifecycleIntentStatus.pending,
    val error: String? = null,
) {
    init {
        require(HealthIdentity.validRide(commandId))
        validateTiming(timing)
        require(key.owner.name == options.recordingOwner)
        val allowed =
            when (action) {
                RideAction.start -> setOf(LifecycleEffectKind.startOwner, LifecycleEffectKind.recoverOwner)
                RideAction.pause -> setOf(LifecycleEffectKind.pauseOwner, LifecycleEffectKind.recoverOwner)
                RideAction.resume -> setOf(LifecycleEffectKind.resumeOwner, LifecycleEffectKind.recoverOwner)
                RideAction.lap -> setOf(LifecycleEffectKind.lapOwner, LifecycleEffectKind.recoverOwner)
                RideAction.stop,
                RideAction.stopUnconfirmed -> setOf(LifecycleEffectKind.stopOwner, LifecycleEffectKind.recoverOwner)
                RideAction.discard -> setOf(LifecycleEffectKind.discardOwner, LifecycleEffectKind.deleteRecording)
                RideAction.recover ->
                    setOf(
                        LifecycleEffectKind.recoverOwner,
                        LifecycleEffectKind.repairRecording,
                        LifecycleEffectKind.repairHealth,
                    )
            }
        require(effect in allowed && (effect != LifecycleEffectKind.repairHealth || options.saveToHealth))
        require(if (status == LifecycleIntentStatus.failed) !error.isNullOrBlank() else error == null)
    }

    val terminal: Boolean
        get() =
            effect in
                setOf(
                    LifecycleEffectKind.stopOwner,
                    LifecycleEffectKind.discardOwner,
                    LifecycleEffectKind.deleteRecording,
                )
}

data class LifecycleEvent(
    val eventId: String,
    val key: LifecycleKey,
    val action: String,
    val timing: OwnerTiming,
    val interrupted: Boolean = false,
    val clockEpoch: String? = null,
)

data class LifecycleRide
@Throws(IllegalArgumentException::class)
constructor(
    val key: LifecycleKey,
    val options: FrozenRideOptions,
    val startedAt: ProtocolTimestamp,
    val phase: LifecyclePhase,
    val timing: OwnerTiming,
    val epoch: String,
    val elapsedOriginUs: Long? = null,
    val runningOriginUs: Long? = null,
    val ownerSnapshot: OwnerSnapshot? = null,
    val pending: LifecycleIntent? = null,
    val terminal: LifecycleIntent? = null,
    val recovery: LifecycleRecovery = LifecycleRecovery.idle,
    val uncertainEpoch: Boolean = false,
    val error: String? = null,
    val collectionClosed: Boolean = false,
    val deleted: Boolean = false,
    val storageInterrupted: Boolean = false,
    val ownerRecovery: LifecycleOwnerRecovery = LifecycleOwnerRecovery.local,
    val nativeEventIds: List<String> = emptyList(),
) {
    init {
        require(nativeEventIds.distinct().size == nativeEventIds.size && nativeEventIds.all(HealthIdentity::validRide))
        require(key.owner.name == options.recordingOwner && HealthIdentity.validRide(epoch))
        validateTiming(timing)
        require(Timestamps.parse(startedAt.original) == startedAt)
        require(listOf(elapsedOriginUs, runningOriginUs).all { it == null || it >= 0 })
        require(runningOriginUs == null || phase == LifecyclePhase.running && elapsedOriginUs != null)
        require(ownerSnapshot == null || ownerSnapshot.owner == key.owner)
        require(ownerSnapshot == null || OwnerSnapshots.admit(null, ownerSnapshot).rejection == null)
        require(options.saveToHealth || ownerSnapshot?.healthOutcome in setOf(null, "notRequested", "discarded"))
        require(listOf(pending, terminal).all { it == null || it.key == key && it.options == options })
        require(terminal == null || terminal.terminal && elapsedOriginUs == null && runningOriginUs == null)
        require(terminal == null || timing == terminal.timing)
    }

    val healthOutcome: String
        get() =
            when {
                terminal?.action == RideAction.discard || ownerSnapshot?.healthOutcome == "discarded" -> "discarded"
                !options.saveToHealth -> "notRequested"
                else -> ownerSnapshot?.healthOutcome ?: "pending"
            }
}

data class LifecycleState
@Throws(IllegalArgumentException::class)
constructor(val generation: Long = 0, val ride: LifecycleRide? = null) {
    init {
        require(generation in 0..MAX_REVISION && (ride == null || ride.key.generation == generation))
    }
}

data class LifecycleEffect(val intent: LifecycleIntent) {
    val effectId: String
        get() = "${intent.commandId}:${intent.effect.name}"
}

data class LifecycleMutation(
    val state: LifecycleState,
    val intent: LifecycleIntent? = null,
    val events: List<LifecycleEvent> = emptyList(),
    val resetLive: Boolean = false,
    val resetIntegration: Boolean = false,
    val failedCommand: LifecycleIntent? = null,
)

data class LifecycleOutcome(
    val state: LifecycleState,
    val effects: List<LifecycleEffect>,
    val outcome: String,
    val error: String?,
)

class LifecycleTransition
internal constructor(
    private val before: LifecycleState,
    val mutation: LifecycleMutation?,
    val outcome: String,
    val rejection: String? = null,
    private val effects: List<LifecycleEffect> = emptyList(),
) {
    fun afterCommit(succeeded: Boolean): LifecycleOutcome =
        when {
            rejection != null -> LifecycleOutcome(before, emptyList(), outcome, rejection)
            mutation == null -> LifecycleOutcome(before, emptyList(), outcome, null)
            !succeeded -> LifecycleOutcome(before, emptyList(), "commitFailed", RideFailure.storageFailed.name)
            else -> LifecycleOutcome(mutation.state, effects, outcome, null)
        }
}

data class LifecycleOwnerEvent
@Throws(IllegalArgumentException::class)
constructor(
    val action: RideAction,
    val timestamp: ProtocolTimestamp,
    val commandId: String? = null,
    val timing: OwnerTiming? = null,
    val nativeEventId: String? = null,
) {
    @Throws(IllegalArgumentException::class)
    constructor(
        action: RideAction,
        timestamp: ProtocolTimestamp,
        commandId: String?,
        timing: OwnerTiming?,
    ) : this(action, timestamp, commandId, timing, null)

    constructor(
        action: RideAction,
        timestamp: ProtocolTimestamp,
        commandId: String?,
    ) : this(action, timestamp, commandId, null)

    init {
        require(action in setOf(RideAction.pause, RideAction.resume, RideAction.lap))
        require(Timestamps.parse(timestamp.original) == timestamp)
        require(commandId == null || HealthIdentity.validRide(commandId))
        require(nativeEventId == null || HealthIdentity.validRide(nativeEventId))
        require(action != RideAction.lap || commandId != null)
        require(timing == null || timing.timestamp == timestamp)
        timing?.let(::validateTiming)
    }
}

data class LifecycleReceipt(
    val key: LifecycleKey,
    val commandId: String,
    val effect: LifecycleEffectKind,
    val clock: LifecycleClock,
    val error: String? = null,
    val ownerSnapshot: OwnerSnapshot? = null,
    val startedAt: ProtocolTimestamp? = null,
    val ownerEvents: List<LifecycleOwnerEvent> = emptyList(),
)

internal fun validateTiming(timing: OwnerTiming) {
    require(Schema.timing(timing.json()) == timing)
}
