package app.powerlog.core.lifecycle

import app.powerlog.core.RideActions
import app.powerlog.core.presentation.*
import app.powerlog.core.protocol.*

data class LifecycleStart(
    val rideId: String,
    val commandId: String,
    val clock: LifecycleClock,
    val requested: RequestedRideOptions,
    val capabilities: RideCapabilities,
    val context: RideStartContext = RideStartContext(),
    val bikeReady: Boolean = true,
    val watchInstalled: Boolean = true,
    val retainedCapture: Boolean = false,
    val phoneRecovery: LifecycleOwnerRecovery = LifecycleOwnerRecovery.nativeSession,
)

data class LifecycleRequest(
    val key: LifecycleKey,
    val commandId: String,
    val action: RideAction,
    val clock: LifecycleClock,
    val surface: RideActionSurface = RideActionSurface.rider,
    val expectedPhase: LifecyclePhase? = null,
    val expectedOwnerRevision: Long? = null,
    val retainedCapture: Boolean = false,
    val ownerTiming: OwnerTiming? = null,
    val peerCommand: Boolean = false,
)

object RideLifecycle {
    fun canFinishAbsentOwner(ride: LifecycleRide?): Boolean =
        ride?.phase == LifecyclePhase.interrupted && ride.pending == null && !ride.storageInterrupted

    fun actionPhase(ride: LifecycleRide): String = ride.phase.name

    fun failedStartRemovable(ride: LifecycleRide?, hasOriginals: Boolean): Boolean =
        ride?.phase == LifecyclePhase.failed && !hasOriginals && ride.pending == null

    fun canExecute(state: LifecycleState, effect: LifecycleEffect): Boolean {
        val ride = state.ride ?: return false
        val intent = effect.intent
        if (ride.key != intent.key) return false
        return listOfNotNull(ride.pending, ride.terminal).any {
            it.commandId == intent.commandId && it.effect == intent.effect && it.status == LifecycleIntentStatus.pending
        }
    }

    fun nativeAction(ride: LifecycleRide?, fromPhase: String, toPhase: String): RideAction? {
        if (
            ride == null ||
                ride.terminal != null ||
                ride.storageInterrupted ||
                ride.phase !in setOf(LifecyclePhase.running, LifecyclePhase.paused)
        )
            return null
        return when {
            toPhase == "paused" -> RideAction.pause
            fromPhase == "paused" && toPhase == "running" -> RideAction.resume
            else -> null
        }
    }

    fun nativeActivityAction(ride: LifecycleRide?, fromPhase: String, toPhase: String): RideAction? =
        nativeAction(ride, fromPhase, toPhase)

    fun ingestNativeActivity(
        state: LifecycleState,
        key: LifecycleKey,
        evidence: List<LifecycleOwnerEvent>,
    ): LifecycleTransition {
        val ride = state.ride ?: return noOp(state, "staleOwner")
        if (
            ride.key != key ||
                ride.storageInterrupted ||
                ride.phase !in setOf(LifecyclePhase.running, LifecyclePhase.paused)
        )
            return noOp(state, "staleOwner")
        val seen = ride.nativeEventIds.toMutableSet()
        val events = evidence.mapNotNull { item ->
            val timing = item.timing ?: return@mapNotNull null
            if (item.action !in setOf(RideAction.pause, RideAction.resume)) return@mapNotNull null
            val id = nativeEventId(ride, item)
            if (!seen.add(id)) return@mapNotNull null
            LifecycleEvent(item.commandId ?: id, key, item.action.name, timing)
        }
        if (events.isEmpty()) return noOp(state, "unchangedEvidence")
        return transition(
            state,
            state.copy(ride = ride.copy(nativeEventIds = seen.toList())),
            events = events,
            resetIntegration = true,
        )
    }

    fun deletedSelection(state: LifecycleState, rideId: String): LifecycleTransition = clearSelection(state, rideId)

    fun clearSelection(state: LifecycleState, rideId: String): LifecycleTransition {
        if (state.ride?.key?.rideId != rideId) return noOp(state, "staleDeletion")
        require(state.generation < MAX_REVISION)
        return transition(state, LifecycleState(state.generation + 1), resetLive = true)
    }

    fun observeHealth(state: LifecycleState, key: LifecycleKey, incoming: OwnerSnapshot): LifecycleTransition {
        val ride = state.ride ?: return noOp(state, "staleOwner")
        if (ride.key != key || incoming.owner != key.owner) return noOp(state, "staleOwner")
        val previous = ride.ownerSnapshot ?: return noOp(state, "unstarted")
        if (incoming.ownerRevision <= previous.ownerRevision) return noOp(state, "staleOwner")
        val next =
            previous.copy(
                ownerRevision = incoming.ownerRevision,
                effectiveAt = incoming.effectiveAt,
                healthOutcome = incoming.healthOutcome,
                healthWorkoutID = incoming.healthWorkoutID,
                healthReason = incoming.healthReason,
            )
        return transition(state, state.copy(ride = ride.copy(ownerSnapshot = next)))
    }

    @Throws(IllegalArgumentException::class)
    fun nativeTiming(
        ride: LifecycleRide,
        clock: LifecycleClock,
        nativeElapsedSeconds: Double?,
        nativeTimerSeconds: Double?,
    ): OwnerTiming {
        if (
            ride.terminal != null ||
                ride.recovery in setOf(LifecycleRecovery.storage, LifecycleRecovery.checkingStorage)
        )
            return timing(ride, clock)
        val retained = timing(ride, clock)
        val elapsed =
            if (ride.epoch == clock.epoch && ride.phase in setOf(LifecyclePhase.running, LifecyclePhase.paused))
                retained.elapsedSeconds
            else nativeElapsedSeconds ?: retained.elapsedSeconds
        val timer = minOf(nativeTimerSeconds ?: retained.timerSeconds, elapsed)
        return OwnerTiming(clock.timestamp, elapsed, timer).also(::validateTiming)
    }

    @Throws(IllegalArgumentException::class)
    fun timing(ride: LifecycleRide, clock: LifecycleClock): OwnerTiming {
        if (ride.terminal != null) return ride.terminal.timing
        if (ride.elapsedOriginUs == null && ride.runningOriginUs == null) return ride.timing
        require(ride.epoch == clock.epoch)
        fun since(origin: Long?): Double {
            if (origin == null) return 0.0
            require(clock.monotonicUs >= origin)
            return (clock.monotonicUs - origin).toDouble() / 1_000_000
        }
        val elapsed = ride.timing.elapsedSeconds + since(ride.elapsedOriginUs)
        val timer = ride.timing.timerSeconds + since(ride.runningOriginUs)
        return OwnerTiming(clock.timestamp, elapsed, minOf(timer, elapsed)).also(::validateTiming)
    }

    fun collectionElapsed(ride: LifecycleRide, clock: LifecycleClock): Double? {
        val origin = ride.elapsedOriginUs ?: return null
        if (ride.epoch != clock.epoch || ride.terminal != null || ride.storageInterrupted) return null
        val elapsed = ride.timing.elapsedSeconds + (clock.monotonicUs - origin).toDouble() / 1_000_000
        return elapsed.takeIf { it >= 0 }
    }

    @Throws(IllegalArgumentException::class)
    fun checkpoint(state: LifecycleState, clock: LifecycleClock): LifecycleTransition {
        val ride = state.ride ?: return noOp(state, "empty")
        if (ride.collectionClosed || ride.deleted) return noOp(state, "closed")
        return transition(
            state,
            state.copy(
                ride =
                    ride.copy(
                        timing = timing(ride, clock),
                        elapsedOriginUs = ride.elapsedOriginUs?.let { clock.monotonicUs },
                        runningOriginUs = ride.runningOriginUs?.let { clock.monotonicUs },
                    )
            ),
            effects = emptyList(),
        )
    }

    @Throws(IllegalArgumentException::class)
    fun checkpoint(
        state: LifecycleState,
        key: LifecycleKey,
        timing: OwnerTiming,
        clock: LifecycleClock,
        retainedCapture: Boolean = false,
    ): LifecycleTransition {
        validateTiming(timing)
        val ride = state.ride ?: return reject(state, RideFailure.rideChanged)
        if (!current(ride, key)) return reject(state, RideFailure.rideChanged)
        if (ride.terminal != null || ride.storageInterrupted || ride.phase == LifecyclePhase.interrupted) {
            if (!retainedCapture || ride.terminal?.status == LifecycleIntentStatus.applied)
                return reject(state, RideFailure.captureRejected)
            val cutoff = ride.terminal?.timing ?: ride.timing
            if (timing.elapsedSeconds > cutoff.elapsedSeconds || timing.timerSeconds > cutoff.timerSeconds)
                return reject(state, RideFailure.captureRejected)
            return noOp(state, "retainedCheckpoint")
        }
        if (ride.phase !in setOf(LifecyclePhase.running, LifecyclePhase.paused) || ride.pending != null)
            return reject(state, RideFailure.captureRejected)
        if (
            retainedCapture &&
                timing.elapsedSeconds <= ride.timing.elapsedSeconds &&
                timing.timerSeconds <= ride.timing.timerSeconds
        )
            return noOp(state, "retainedCheckpoint")
        if (ride.phase == LifecyclePhase.paused && timing.timerSeconds != ride.timing.timerSeconds)
            return reject(state, RideFailure.captureRejected)
        if (timing.elapsedSeconds < ride.timing.elapsedSeconds || timing.timerSeconds < ride.timing.timerSeconds)
            return reject(state, RideFailure.captureRejected)
        require(ride.epoch == clock.epoch)
        val next =
            ride.copy(
                timing = timing,
                elapsedOriginUs = clock.monotonicUs,
                runningOriginUs = if (ride.phase == LifecyclePhase.running) clock.monotonicUs else null,
                ownerSnapshot = projection(ride, ride.phase, timing, clock.timestamp),
            )
        return transition(state, state.copy(ride = next), effects = emptyList())
    }

    @Throws(IllegalArgumentException::class)
    fun start(state: LifecycleState, request: LifecycleStart): LifecycleTransition {
        val current = state.ride
        val context =
            request.context.copy(
                unresolvedOwner =
                    request.context.unresolvedOwner ||
                        current != null &&
                            (current.phase !in setOf(LifecyclePhase.completed, LifecyclePhase.failed) ||
                                current.pending?.terminal == false ||
                                current.storageInterrupted ||
                                current.recovery in
                                    setOf(
                                        LifecycleRecovery.unresolved,
                                        LifecycleRecovery.storage,
                                        LifecycleRecovery.checkingStorage,
                                        LifecycleRecovery.checking,
                                    )),
                busy = request.context.busy || current?.pending?.terminal == false,
            )
        val reason = RideStarts.rejection(current?.phase?.name ?: "idle", context)
        if (reason != null) return reject(state, Failures.present(reason))
        if (request.retainedCapture) return reject(state, RideFailure.storageBusy)
        val options = RideOptions.resolve(request.requested, request.capabilities)
        val ready =
            RideOptions.readiness(
                options,
                request.capabilities,
                request.bikeReady,
                request.watchInstalled,
                current?.phase?.name,
                context,
            )
        if (!ready.available) return reject(state, Failures.present(ready.error))
        require(state.generation < MAX_REVISION)
        val key = LifecycleKey(request.rideId, Role.valueOf(options.recordingOwner), state.generation + 1)
        val timing = OwnerTiming(request.clock.timestamp, 0.0, 0.0)
        val intent =
            LifecycleIntent(
                request.commandId,
                key,
                RideAction.start,
                timing,
                options,
                LifecycleEffectKind.startOwner,
            )
        val ride =
            LifecycleRide(
                key,
                options,
                request.clock.timestamp,
                LifecyclePhase.preparing,
                timing,
                request.clock.epoch,
                pending = intent,
                ownerRecovery = LifecycleOwnerRecovery.local,
            )
        return transition(state, LifecycleState(key.generation, ride), intent, resetLive = true)
    }

    @Throws(IllegalArgumentException::class)
    fun request(
        state: LifecycleState,
        request: LifecycleRequest,
        retained: LifecycleIntent? = null,
    ): LifecycleTransition {
        val ride = state.ride ?: return reject(state, RideFailure.rideChanged)
        if (!current(ride, request.key)) return reject(state, RideFailure.rideChanged)
        require(app.powerlog.core.HealthIdentity.validRide(request.commandId))
        if (request.action in setOf(RideAction.start, RideAction.recover))
            return reject(state, RideFailure.actionUnavailable)
        if (
            request.expectedPhase != null && request.expectedPhase != ride.phase ||
                request.expectedOwnerRevision != null &&
                    request.expectedOwnerRevision != ride.ownerSnapshot?.ownerRevision
        )
            return reject(state, RideFailure.rideChanged)
        val known =
            retained
                ?: ride.pending?.takeIf { it.commandId == request.commandId }
                ?: ride.terminal?.takeIf { it.commandId == request.commandId }
        if (known != null) {
            if (known.key != request.key || known.action != request.action)
                return reject(state, RideFailure.rideChanged)
            return noOp(state, if (known.status == LifecycleIntentStatus.applied) "duplicate" else "pending")
        }
        if (request.retainedCapture || ride.storageInterrupted) return reject(state, RideFailure.storageBusy)
        val finishing = request.action in setOf(RideAction.stop, RideAction.stopUnconfirmed, RideAction.discard)
        if (ride.pending != null && !finishing) return reject(state, RideFailure.actionPending)
        RideActions.rejection(request.action.name, ride.phase.name, request.surface)?.let {
            return reject(state, Failures.present(it))
        }
        val boundary =
            if (ride.phase == LifecyclePhase.interrupted) ride.timing
            else request.ownerTiming ?: timing(ride, request.clock)
        validateTiming(boundary)
        val kind =
            when (request.action) {
                RideAction.pause -> LifecycleEffectKind.pauseOwner
                RideAction.resume -> LifecycleEffectKind.resumeOwner
                RideAction.lap -> LifecycleEffectKind.lapOwner
                RideAction.discard -> LifecycleEffectKind.deleteRecording
                else -> LifecycleEffectKind.stopOwner
            }
        val intent = LifecycleIntent(request.commandId, ride.key, request.action, boundary, ride.options, kind)
        val next =
            if (finishing)
                ride.copy(
                    phase =
                        if (request.action == RideAction.discard) LifecyclePhase.finishing
                        else LifecyclePhase.completed,
                    timing = boundary,
                    elapsedOriginUs = null,
                    runningOriginUs = null,
                    pending = if (request.action == RideAction.discard) intent else null,
                    terminal = intent,
                    recovery = LifecycleRecovery.resolved,
                    error = null,
                )
            else ride.copy(pending = intent, error = null)
        return transition(
            state,
            state.copy(ride = next),
            intent,
            events = if (finishing) listOf(event(intent, "stop")) else emptyList(),
        )
    }

    @Throws(IllegalArgumentException::class)
    fun receipt(state: LifecycleState, receipt: LifecycleReceipt): LifecycleTransition {
        val ride = state.ride ?: return noOp(state, "staleReceipt")
        if (ride.key != receipt.key) return noOp(state, "staleReceipt")
        val intent =
            listOfNotNull(ride.pending, ride.terminal).firstOrNull {
                it.commandId == receipt.commandId &&
                    it.effect == receipt.effect &&
                    it.status == LifecycleIntentStatus.pending
            } ?: return noOp(state, "staleReceipt")
        if (receipt.error != null) {
            val error = Failures.present(receipt.error).name
            val failed = intent.copy(status = LifecycleIntentStatus.failed, error = error)
            val next =
                ride.copy(
                    phase =
                        if (intent.action == RideAction.start) LifecyclePhase.failed
                        else if (intent.action == RideAction.discard) LifecyclePhase.interrupted else ride.phase,
                    pending = null,
                    terminal = if (intent.action == RideAction.discard) null else if (intent.terminal) failed else null,
                    error = error,
                )
            return transition(state, state.copy(ride = next), failed, effects = emptyList())
        }
        val applied = intent.copy(status = LifecycleIntentStatus.applied)
        if (intent.effect == LifecycleEffectKind.deleteRecording) {
            require(state.generation < MAX_REVISION)
            return transition(
                state,
                LifecycleState(state.generation + 1),
                applied,
                resetLive = true,
                effects = emptyList(),
            )
        }
        val phase =
            when (intent.action) {
                RideAction.start,
                RideAction.resume -> LifecyclePhase.running
                RideAction.pause -> LifecyclePhase.paused
                RideAction.stop,
                RideAction.stopUnconfirmed -> LifecyclePhase.completed
                else -> ride.phase
            }
        val startedAt =
            if (intent.action == RideAction.start) receipt.startedAt ?: receipt.clock.timestamp else ride.startedAt
        val boundary =
            when {
                intent.terminal -> intent.timing
                intent.action == RideAction.start -> OwnerTiming(startedAt, 0.0, 0.0)
                else -> timing(ride, receipt.clock)
            }
        val snapshot = projection(ride, phase, boundary, receipt.clock.timestamp)
        val next =
            ride.copy(
                startedAt = startedAt,
                phase = phase,
                timing = boundary,
                epoch = receipt.clock.epoch,
                elapsedOriginUs = if (intent.terminal) null else receipt.clock.monotonicUs,
                runningOriginUs = if (phase == LifecyclePhase.running) receipt.clock.monotonicUs else null,
                ownerSnapshot = snapshot,
                pending = null,
                terminal = if (intent.terminal) applied else null,
                recovery = LifecycleRecovery.resolved,
                error = null,
            )
        return transition(
            state,
            state.copy(ride = next),
            applied,
            events =
                if (intent.terminal) emptyList()
                else
                    listOf(
                        event(
                            intent,
                            intent.action.name,
                            if (intent.action == RideAction.lap) intent.timing else boundary,
                        )
                    ),
            resetIntegration = intent.action in setOf(RideAction.pause, RideAction.resume),
            effects = emptyList(),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun restart(state: LifecycleState, clock: LifecycleClock, recoveryCommandId: String): LifecycleTransition {
        val ride = state.ride ?: return noOp(state, "empty")
        if (ride.collectionClosed || ride.deleted) return noOp(state, "closed")
        val completed = ride.phase in setOf(LifecyclePhase.completed, LifecyclePhase.failed)
        val next =
            ride.copy(
                phase = if (completed) ride.phase else LifecyclePhase.interrupted,
                elapsedOriginUs = null,
                runningOriginUs = null,
                pending = null,
                // Capture queues are process-local; only committed originals survive restart.
                storageInterrupted = false,
                terminal =
                    if (completed) ride.terminal?.copy(status = LifecycleIntentStatus.applied, error = null) else null,
                recovery = if (completed) LifecycleRecovery.resolved else LifecycleRecovery.absent,
                error = if (completed) ride.error else RideFailure.recordingInterrupted.name,
            )
        val fenced = fenced(state.copy(ride = next))
        return transition(state, fenced, resetLive = true)
    }

    @Suppress("UNUSED_PARAMETER")
    fun recoveryGap(before: LifecycleState, transition: LifecycleTransition): LifecycleRecoveryGap? = null

    @Suppress("UNUSED_PARAMETER")
    fun absentOwner(
        state: LifecycleState,
        key: LifecycleKey,
        clock: LifecycleClock,
        commandId: String? = null,
    ): LifecycleTransition = if (state.ride?.key == key) noOp(state, "absentOwner") else noOp(state, "staleOwner")

    @Suppress("UNUSED_PARAMETER")
    fun observeOwner(
        state: LifecycleState,
        key: LifecycleKey,
        incoming: OwnerSnapshot,
        clock: LifecycleClock,
        ownerEvents: List<LifecycleOwnerEvent> = emptyList(),
    ): LifecycleTransition = observeHealth(state, key, incoming)

    fun observeNativeOwner(
        state: LifecycleState,
        key: LifecycleKey,
        incoming: OwnerSnapshot,
        clock: LifecycleClock,
        ownerEvents: List<LifecycleOwnerEvent> = emptyList(),
    ): LifecycleTransition = observeOwner(state, key, incoming, clock, ownerEvents)

    private fun projection(
        ride: LifecycleRide,
        phase: LifecyclePhase,
        timing: OwnerTiming,
        at: ProtocolTimestamp,
    ): OwnerSnapshot {
        val previous = ride.ownerSnapshot
        require((previous?.ownerRevision ?: 0) < MAX_REVISION)
        val snapshot =
            OwnerSnapshot(
                ride.key.owner,
                (previous?.ownerRevision ?: 0) + 1,
                at,
                phase.name,
                ride.healthOutcome,
                previous?.healthWorkoutID,
                ride.terminal?.timing?.timestamp,
                timing,
                previous?.healthReason,
            )
        require(OwnerSnapshots.admit(previous, snapshot).rejection == null)
        return snapshot
    }

    @Throws(IllegalArgumentException::class)
    fun fenced(state: LifecycleState): LifecycleState {
        require(state.generation < MAX_REVISION)
        val key = state.ride?.key?.copy(generation = state.generation + 1)
        val ride =
            state.ride?.copy(
                key = key!!,
                pending = state.ride.pending?.copy(key = key),
                terminal = state.ride.terminal?.copy(key = key),
            )
        return LifecycleState(state.generation + 1, ride)
    }

    fun interruptionId(ride: LifecycleRide): String {
        val input =
            "local-interruption:${ride.key.rideId}:${ride.epoch}:${CanonicalNumber.encode(ride.timing.elapsedSeconds)}"
        val hex = app.powerlog.core.Sha256.hash(input.encodeToByteArray()).take(32).toCharArray()
        hex[12] = '5'
        hex[16] = "89ab"[hex[16].digitToInt(16) and 3]
        val text = hex.concatToString()
        return "${text.take(8)}-${text.substring(8,12)}-${text.substring(12,16)}-${text.substring(16,20)}-${text.substring(20)}"
    }

    private fun nativeEventId(ride: LifecycleRide, event: LifecycleOwnerEvent): String =
        event.nativeEventId ?: LifecycleNativeEvidence.identity(ride.key, event.action, event.timestamp, null, null)

    private fun current(ride: LifecycleRide, key: LifecycleKey): Boolean =
        ride.key == key && !ride.collectionClosed && !ride.deleted

    private fun event(intent: LifecycleIntent, action: String, timing: OwnerTiming = intent.timing) =
        LifecycleEvent(intent.commandId, intent.key, action, timing)

    private fun reject(state: LifecycleState, failure: RideFailure) =
        LifecycleTransition(state, null, "rejected", failure.name)

    private fun noOp(state: LifecycleState, outcome: String) = LifecycleTransition(state, null, outcome)

    private fun transition(
        state: LifecycleState,
        next: LifecycleState,
        intent: LifecycleIntent? = null,
        events: List<LifecycleEvent> = emptyList(),
        resetLive: Boolean = false,
        resetIntegration: Boolean = false,
        failedCommand: LifecycleIntent? = null,
        effects: List<LifecycleEffect> = intent?.let { listOf(LifecycleEffect(it)) } ?: emptyList(),
        outcome: String = "admitted",
    ) =
        LifecycleTransition(
            state,
            LifecycleMutation(next, intent, events, resetLive, resetIntegration, failedCommand),
            outcome,
            effects = effects,
        )
}

data class LifecycleRecoveryGap(val start: ProtocolTimestamp, val end: ProtocolTimestamp)
