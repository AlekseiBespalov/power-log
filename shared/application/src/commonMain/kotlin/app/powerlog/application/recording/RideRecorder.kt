package app.powerlog.application.recording

import app.powerlog.application.CommitPolicy
import app.powerlog.application.Deadlines
import app.powerlog.application.Logs
import app.powerlog.application.api.Answer
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.answer
import app.powerlog.application.api.commitFailure
import app.powerlog.application.api.failure
import app.powerlog.application.api.newId
import app.powerlog.application.api.occurrence
import app.powerlog.application.connection.ControllerConnection
import app.powerlog.application.port.Clock
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.LiveHeartRate
import app.powerlog.application.port.LocationSource
import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.RecordingKeepAlive
import app.powerlog.application.port.Reply
import app.powerlog.application.port.RideCard
import app.powerlog.application.port.StoragePersistence
import app.powerlog.application.port.WatchLink
import app.powerlog.application.settings.Settings
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthOutcomeRules
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthWriter
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.DeviceCapabilities
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RideOptionsRules
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.StartRequest
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.recording.CommandIssuer
import app.powerlog.domain.recording.FinishContext
import app.powerlog.domain.recording.LifecycleDecision
import app.powerlog.domain.recording.RideCommand
import app.powerlog.domain.recording.RideLifecycle
import app.powerlog.domain.recording.RideSignal
import app.powerlog.domain.recording.RideTransition
import app.powerlog.domain.recording.StartContext
import app.powerlog.domain.recording.StartPolicy
import app.powerlog.domain.settings.PersistenceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class RideRecorder(
    private val scope: CoroutineScope,
    private val ride: CoroutineDispatcher,
    private val clock: Clock,
    private val writer: ArchiveWriter,
    private val measurements: MeasurementRepository,
    private val state: RecordingState,
    private val capture: Capture,
    private val live: LivePublisher,
    private val connection: ControllerConnection,
    private val settings: Settings,
    private val events: RideEvents,
    private val device: DeviceInfo,
    private val permissions: PermissionGate,
    private val access: Permissions,
    private val ports: RecordingPorts,
    private val capabilities: () -> DeviceCapabilities,
    private val logs: Logs,
    private val rides: OpenRides,
    private val leases: TabLeases?,
    private val phoneSession: Boolean,
    private val deadlines: Deadlines,
) : RideRecorderEntries {
    class RecordingPorts(
        val keepAlive: RecordingKeepAlive?,
        val location: LocationSource?,
        val rideCard: RideCard?,
        val health: HealthProvider?,
        val watch: WatchLink?,
        val persistence: StoragePersistence? = null,
    )

    private enum class Part {
        hold,
        watch,
        session,
    }

    private class PendingStart(
        val rideId: RideId,
        val event: EventId,
        val options: RideOptions,
        val requestId: String?,
        val parts: MutableSet<Part>,
    ) {
        val answer = CompletableDeferred<Outcome<Unit>>()
        var window: Job? = null
    }

    class Waiting(val rideId: RideId, val event: EventId, val requestId: String?)

    private var pendingStart: PendingStart? = null
    private var endingSession: RideId? = null
    var cardToken: () -> String? = { null }
    var endCard: () -> Unit = {}
    var storageChanged: (PersistenceState) -> Unit = {}
    private var storageAsked = false

    val waiting: Waiting?
        get() = pendingStart?.let { Waiting(it.rideId, it.event, it.requestId) }

    init {
        live.startContext = { startContext(capabilitiesOptions(), fromWatch = false) }
        capture.storageFailed = { rideId, failure -> interrupt(rideId, failure) }
    }

    suspend fun start(): Outcome<Unit> = start(null, null)

    private suspend fun start(watchRequest: StartRequest?, requestId: String?): Outcome<Unit> {
        if (state.pending != null || state.deciding) return refused(FailureCode.actionPending)
        state.deciding = true
        val answer =
            try {
                begin(watchRequest, requestId)
            } finally {
                state.deciding = false
            }
        live.publish(urgent = true)
        return answer.await()
    }

    private suspend fun begin(watchRequest: StartRequest?, requestId: String?): Deferred<Outcome<Unit>> {
        val event = EventId(newId())
        val saved = settings.settled()
        val fromWatch = watchRequest != null
        val request = watchRequest ?: StartRequest(saved.indoor, saved.useWatch, saved.saveToHealth, saved.recordGps)
        val options = RideOptionsRules.resolve(saved, request, capabilities())
        if (!fromWatch)
            prompt(options)?.let {
                return CompletableDeferred(refused(it))
            }
        askPersistence()
        var ownerForeign = false
        if (leases != null) {
            ownerForeign = !leases.take(waitIfHeld = false)
            if (!ownerForeign)
                leasedStart()?.let {
                    leases.release()
                    return CompletableDeferred(it)
                }
        }
        val rideId = RideId(newId())
        val cutoff = clock.read()
        val context = startContext(options, fromWatch).copy(ownerForeign = ownerForeign || state.otherTab)
        val command = RideCommand.Start(event, rideId, recordedBy(), context)
        val decision = RideLifecycle.decide(state.lifecycle, command, cutoff, null)
        if (decision !is LifecycleDecision.Accepted) {
            releaseLease()
            return CompletableDeferred(refused((decision as LifecycleDecision.Refused).code))
        }
        val created = requireNotNull(decision.saved)
        val provider = if (created.options.useWatch) HealthProviderKind.appleHealth else ports.health?.kind
        val rows =
            HealthOutcomeRules.initialComponents(created.options).map {
                HealthWork(rideId, HealthWorkScope.Publish(it.component), it.state, 0, null, null, it.reason)
            }
        val mutations =
            listOf(Mutation.CreateRide(created, HealthOutcomeRules.initial(created.options, provider))) +
                listOfNotNull(rows.takeIf { it.isNotEmpty() }?.let(Mutation::PutHealthWork))
        val failed = committedOrFailure(writer.commit(WriteBatch(mutations)))
        if (failed != null) {
            releaseLease()
            return CompletableDeferred(shown(failed))
        }
        val parts = mutableSetOf<Part>()
        if (ports.keepAlive != null) parts += Part.hold
        if (created.options.useWatch) parts += Part.watch
        if (phoneWrites(created)) parts += Part.session
        val pending = PendingStart(rideId, event, created.options, requestId, parts)
        pendingStart = pending
        accept(decision, event)
        connection.recordingChanged(true, created.options.sampleRate)
        val deadlineUs = StartPolicy.deadline(cutoff.monoUs)
        pending.window =
            scope.launch(ride) {
                deadlines.until(deadlineUs)
                timedOut(pending)
            }
        transitioned(
            created,
            RideTransition.prepared,
            event,
            emptyList(),
            if (fromWatch) CommandIssuer.watch else CommandIssuer.rider,
        )
        val keepAlive = ports.keepAlive
        if (keepAlive == null) confirmIfReady(pending)
        else
            keepAlive.hold(
                HoldKind.ride,
                created.options.recordGps,
                Reply(
                    onOk = { scope.launch(ride) { part(pending, Part.hold) } },
                    onFailed = { scope.launch(ride) { fail(pending, FailureCode.startInterrupted) } },
                ),
            )
        return pending.answer
    }

    suspend fun control(rideId: RideId, action: RideAction, issuer: CommandIssuer, event: EventId): Outcome<Unit> {
        val discardingStart =
            action == RideAction.discard && state.open?.phase == RidePhase.preparing && state.pending == null
        if ((state.pending != null || state.deciding) && !discardingStart) return refused(FailureCode.actionPending)
        state.pending = action
        live.publish(urgent = true)
        val outcome =
            try {
                decideControl(rideId, action, issuer, event)
            } finally {
                state.pending = null
            }
        live.publish(urgent = true)
        return outcome
    }

    private suspend fun decideControl(
        rideId: RideId,
        action: RideAction,
        issuer: CommandIssuer,
        event: EventId,
    ): Outcome<Unit> {
        val leases = leases
        val open = state.open
        val ending = action == RideAction.finish || action == RideAction.discard
        if (leases == null || !ending || open?.id != rideId || open.phase != RidePhase.interrupted)
            return decided(rideId, action, issuer, event)
        if (!leases.take(waitIfHeld = true)) return refused(FailureCode.ownerForeign)
        try {
            val stored =
                when (val read = rides.read("read")) {
                    is OpenRides.Read.Failed -> return shown(failure(FailureCode.readFailed))
                    is OpenRides.Read.Found -> read.ride
                }
            if (stored?.id != rideId) {
                rides.restore(stored)
                return refused(FailureCode.rideChanged)
            }
            return decided(rideId, action, issuer, event)
        } finally {
            leases.release()
        }
    }

    private suspend fun decided(
        rideId: RideId,
        action: RideAction,
        issuer: CommandIssuer,
        event: EventId,
    ): Outcome<Unit> {
        val open = state.open
        val cutoff = clock.read()
        val flushing = open != null && open.id == rideId && open.phase in setOf(RidePhase.running, RidePhase.paused)
        if (flushing) {
            capture.fence(cutoff.monoUs)
            capture.flush()?.let {
                capture.lift()
                return shown(it)
            }
        }
        val finish =
            finishContext(rideId, action) ?: return shown(failure(FailureCode.readFailed)).also { capture.lift() }
        val decision =
            RideLifecycle.decide(
                state.lifecycle,
                RideCommand.Control(event, rideId, action, issuer),
                cutoff,
                finish.value,
            )
        if (decision !is LifecycleDecision.Accepted) {
            capture.lift()
            return refused((decision as LifecycleDecision.Refused).code)
        }
        val result = writer.commit(batch(decision, cutoff))
        val failed = committedOrFailure(result)
        if (failed != null) {
            live.publish(urgent = true)
            if (flushing && failed.code != FailureCode.storageBusy && failed.code != FailureCode.rideChanged)
                interrupt(rideId, failed)
            capture.lift()
            return shown(failed)
        }
        val before = requireNotNull(open)
        accept(decision, event)
        capture.lift()
        work(before, decision, event, issuer)
        return Outcome.Ok(Unit)
    }

    private suspend fun leasedStart(): Outcome<Unit>? {
        val stored =
            when (val read = rides.read("read")) {
                is OpenRides.Read.Failed -> return shown(failure(FailureCode.readFailed))
                is OpenRides.Read.Found -> read.ride
            }
        if (stored == null) {
            if (state.open != null) rides.restore(null)
            return null
        }
        if (stored.phase in RecordingState.recordingPhases) rides.interrupt(stored, atLaunch = false)
        else if (!rides.holds(stored)) rides.restore(stored)
        return refused(FailureCode.rideActive)
    }

    override suspend fun startFromWatch(request: StartRequest, requestId: String): Outcome<Unit> =
        start(request, requestId)

    suspend fun rideCardIntent(intent: RideCardIntent): Outcome<Unit> {
        if (intent.token != cardToken()) return Outcome.Failed(failure(FailureCode.rideChanged))
        val rideId = RideId(intent.rideId)
        return when (intent.action) {
            RideAction.pause,
            RideAction.resume -> control(rideId, intent.action, CommandIssuer.rider, EventId(newId()))
            RideAction.finish -> {
                state.finishRequest = FinishRequestView(newId(), rideId)
                live.publish(urgent = true)
                Outcome.Ok(Unit)
            }
            else -> Outcome.Failed(failure(FailureCode.actionUnavailable))
        }
    }

    override suspend fun decide(command: RideCommand.Control): Outcome<Unit> =
        control(command.rideId, command.action, command.issuer, command.event)

    override fun observe(signal: RideSignal) {
        val pending = pendingStart ?: return
        when (signal) {
            is RideSignal.StartFailed ->
                if (signal.rideId == pending.rideId) scope.launch(ride) { fail(pending, signal.code) }
            is RideSignal.StartConfirmed ->
                if (signal.rideId == pending.rideId)
                    scope.launch(ride) { part(pending, if (pending.options.useWatch) Part.watch else Part.session) }
            else -> Unit
        }
    }

    override fun heartRate(rideId: RideId, heartRate: LiveHeartRate, at: ClockReading) {
        if (state.open?.id != rideId) return
        live.heartRate(heartRate.bpm, at.monoUs - (at.utcUs - heartRate.utcUs))
    }

    override fun healthSessionEnded(rideId: RideId) {
        if (endingSession != rideId) return
        endingSession = null
        live.publish(urgent = true)
    }

    fun startContext(options: RideOptions?, fromWatch: Boolean): StartContext {
        val states = access.states
        return StartContext(
            ownerForeign = state.otherTab,
            settingsSaveFailed = settings.saveFailed,
            settingsReadable = settings.readable,
            bikeVerified = connection.connected,
            options = options,
            watchInstalled = ports.watch?.availability()?.installed == true,
            fromWatch = fromWatch,
            locationDenied =
                states == null ||
                    states.location !in setOf(PermissionState.granted, PermissionState.notDetermined) ||
                    states.locationServicesOn == false,
            healthSessionEnding = endingSession != null,
            healthWriteDenied = false,
        )
    }

    fun visibilityChanged(visible: Boolean) {
        if (device.snapshot().platform != Platform.web) return
        capture.hidden = !visible
        val open = state.open
        if (open?.phase != RidePhase.running || !open.options.recordGps) return
        if (visible) {
            ports.location?.resume()
            capture.gpsBarrier()
        } else ports.location?.pause()
    }

    fun restored(interruptedInMemory: Boolean) {
        state.interruptedInMemory = interruptedInMemory
        live.rideChanged()
    }

    private fun capabilitiesOptions(): RideOptions? {
        val ride = settings.ride
        return RideOptionsRules.resolve(
            ride,
            StartRequest(ride.indoor, ride.useWatch, ride.saveToHealth, ride.recordGps),
            capabilities(),
        )
    }

    private suspend fun prompt(options: RideOptions?): FailureCode? {
        val platform = device.snapshot().platform
        val gps = options?.recordGps == true
        val kinds =
            when (platform) {
                Platform.android ->
                    setOfNotNull(
                        PermissionKind.bluetooth,
                        PermissionKind.notifications,
                        PermissionKind.location.takeIf { gps },
                    )
                Platform.ios,
                Platform.web -> {
                    if (!gps) return null
                    val status = answer<PermissionStates> { permissions.status(it) }
                    if (status is Answer.Ok) access.answered(status.value)
                    if (access.states?.location != PermissionState.notDetermined) return null
                    setOf(PermissionKind.location)
                }
            }
        return when (val answered = answer<PermissionStates> { permissions.request(kinds, it) }) {
            is Answer.Ok -> {
                access.answered(answered.value)
                null
            }
            is Answer.Failed -> FailureCode.uiUnavailable.takeIf { answered.error.kind == PortErrorKind.uiUnavailable }
        }
    }

    private suspend fun part(pending: PendingStart, part: Part) {
        if (pendingStart !== pending || !pending.parts.remove(part)) return
        confirmIfReady(pending)
    }

    private suspend fun confirmIfReady(pending: PendingStart) {
        if (pendingStart === pending && pending.parts.isEmpty()) confirm(pending)
    }

    private fun askPersistence() {
        val persistence = ports.persistence ?: return
        if (storageAsked) return
        storageAsked = true
        persistence.request(
            Reply(
                onOk = { answered -> scope.launch(ride) { storageChanged(answered) } },
                onFailed = {},
            )
        )
    }

    private fun phoneWrites(ride: Ride): Boolean =
        phoneSession &&
            ride.options.healthWriter == HealthWriter.phone &&
            device.snapshot().platform == Platform.ios &&
            ports.health?.session != null

    private suspend fun confirm(pending: PendingStart) {
        val decision = RideLifecycle.observe(state.lifecycle, RideSignal.StartConfirmed(pending.rideId), clock.read())
        if (decision !is LifecycleDecision.Accepted) return
        val running = requireNotNull(decision.saved)
        val session = capture.firstSession(pending.rideId)
        val mutations =
            listOf(
                Mutation.SetPhase(pending.rideId, RidePhase.running),
                Mutation.AppendEvents(pending.rideId, decision.events),
                Mutation.Checkpoint(pending.rideId, running.checkpoint),
            ) + listOfNotNull(session?.let(Mutation::OpenControllerSession))
        while (true) {
            if (pendingStart !== pending) return
            val result = writer.commit(WriteBatch(mutations))
            if (pendingStart !== pending) return
            if (result is StoreResult.Failed && result.error.kind == StorageError.Kind.busy) {
                delay(CommitPolicy.busyRetryUs / 1_000)
                continue
            }
            val failed = committedOrFailure(result)
            if (failed != null) return fail(pending, failed.code)
            break
        }
        pending.window?.cancel()
        pendingStart = null
        accept(decision, pending.event)
        capture.started(pending.rideId, session)
        if (running.options.recordGps) ports.location?.start()
        pending.answer.complete(Outcome.Ok(Unit))
        transitioned(running, RideTransition.started, pending.event, decision.events)
    }

    private suspend fun timedOut(pending: PendingStart) {
        if (pendingStart !== pending) return
        pending.window = null
        val code = if (pending.options.useWatch) FailureCode.watchStartUnconfirmed else FailureCode.startUnconfirmed
        fail(pending, code, timedOut = true)
    }

    private suspend fun fail(pending: PendingStart, code: FailureCode, timedOut: Boolean = false) {
        if (pendingStart !== pending) return
        val signal =
            if (timedOut) RideSignal.StartTimedOut(pending.rideId) else RideSignal.StartFailed(pending.rideId, code)
        val decision =
            RideLifecycle.observe(state.lifecycle, signal, clock.read()) as? LifecycleDecision.Accepted ?: return
        val failed = requireNotNull(decision.saved)
        pending.window?.cancel()
        pendingStart = null
        val batch =
            WriteBatch(
                listOf(
                    Mutation.SetPhase(pending.rideId, RidePhase.failed),
                    Mutation.Checkpoint(pending.rideId, failed.checkpoint),
                )
            )
        while (true) {
            val result = writer.commit(batch)
            if (result is StoreResult.Failed && result.error.kind == StorageError.Kind.busy) {
                delay(CommitPolicy.busyRetryUs / 1_000)
                continue
            }
            val failed = committedOrFailure(result)
            if (failed != null) {
                state.failure = occurrence(code, state.failure)
                pending.answer.complete(Outcome.Failed(failure(code)))
                if (result is StoreResult.Failed) interrupt(pending.rideId, failed)
                return
            }
            break
        }
        accept(decision, pending.event)
        connection.recordingChanged(false, settings.ride.sampleRate)
        endCard()
        ports.keepAlive?.release(HoldKind.ride)
        if (phoneWrites(failed)) endingSession = failed.id
        releaseLease()
        state.failure = occurrence(code, state.failure).takeIf { code != FailureCode.startCancelled }
        live.publish(urgent = true)
        pending.answer.complete(Outcome.Failed(failure(code)))
        transitioned(failed, RideTransition.failed, pending.event, emptyList(), code = code)
    }

    private fun interrupt(rideId: RideId, cause: ApiFailure) {
        val decision = RideLifecycle.observe(state.lifecycle, RideSignal.StorageFailed(rideId), clock.read())
        if (decision !is LifecycleDecision.Accepted) return
        val interrupted = requireNotNull(decision.saved)
        state.lifecycle = decision.next
        capture.stopped()
        pendingStart?.let {
            it.window?.cancel()
            pendingStart = null
            it.answer.complete(Outcome.Failed(failure(cause.code)))
        }
        ports.location?.stop()
        connection.recordingChanged(false, settings.ride.sampleRate)
        ports.keepAlive?.release(HoldKind.ride)
        state.failure = occurrence(cause.code, state.failure)
        live.rideChanged()
        scope.launch(ride) {
            while (true) {
                val result = writer.commit(WriteBatch(listOf(Mutation.SetPhase(rideId, RidePhase.interrupted))))
                if (result is StoreResult.Failed && result.error.kind == StorageError.Kind.busy) {
                    delay(CommitPolicy.busyRetryUs / 1_000)
                    continue
                }
                state.interruptedInMemory = committedOrFailure(result) != null
                break
            }
            transitioned(interrupted, RideTransition.interruptedByStorage, null, emptyList(), code = cause.code)
        }
    }

    private suspend fun finishContext(rideId: RideId, action: RideAction): Holder<FinishContext?>? {
        val open = state.open
        if (action != RideAction.finish || open?.id != rideId || open.phase != RidePhase.interrupted)
            return Holder(null)
        val last = measurements.lastFact(rideId) as? StoreResult.Ok ?: return null
        val events = measurements.events(rideId) as? StoreResult.Ok ?: return null
        return Holder(FinishContext(last.value, events.value))
    }

    private class Holder<T>(val value: T)

    private fun batch(decision: LifecycleDecision.Accepted, cutoff: ClockReading): WriteBatch {
        val deleted = decision.deleted
        if (deleted != null)
            return WriteBatch(
                listOf(Mutation.RemoveHealthWork(deleted, null), Mutation.MarkRideDeleted(deleted, cutoff.utcUs))
            )
        val saved = requireNotNull(decision.saved)
        val before = requireNotNull(state.open)
        val mutations = mutableListOf<Mutation>()
        if (state.interruptedInMemory && saved.phase == RidePhase.completed)
            mutations += Mutation.SetPhase(saved.id, RidePhase.interrupted)
        if (saved.phase != before.phase) mutations += Mutation.SetPhase(saved.id, saved.phase)
        if (decision.events.isNotEmpty()) mutations += Mutation.AppendEvents(saved.id, decision.events)
        mutations += Mutation.Checkpoint(saved.id, saved.checkpoint)
        saved.end?.let { mutations += Mutation.EndRide(saved.id, it) }
        return WriteBatch(mutations)
    }

    private suspend fun work(
        before: Ride,
        decision: LifecycleDecision.Accepted,
        event: EventId,
        issuer: CommandIssuer,
    ) {
        val location = ports.location
        when (decision.transition) {
            RideTransition.paused -> location?.pause()
            RideTransition.resumed -> {
                location?.resume()
                capture.gpsBarrier()
            }
            RideTransition.completed,
            RideTransition.discarded -> {
                capture.stopped()
                location?.stop()
                connection.recordingChanged(false, settings.ride.sampleRate)
                endCard()
                ports.keepAlive?.release(HoldKind.ride)
                if (phoneWrites(before)) endingSession = before.id
                state.interruptedInMemory = false
                releaseLease()
                pendingStart?.let {
                    it.window?.cancel()
                    pendingStart = null
                    it.answer.complete(Outcome.Failed(failure(FailureCode.startCancelled)))
                }
            }
            else -> Unit
        }
        transitioned(decision.saved ?: before, decision.transition, event, decision.events, issuer)
    }

    private fun accept(decision: LifecycleDecision.Accepted, event: EventId) {
        state.lifecycle = decision.next
        state.failure = null
        live.rideChanged()
    }

    private fun transitioned(
        ride: Ride,
        transition: RideTransition,
        event: EventId?,
        committed: List<app.powerlog.domain.core.ride.RideEvent>,
        issuer: CommandIssuer? = null,
        code: FailureCode? = null,
    ) {
        val phase = if (transition == RideTransition.discarded) "discarded" else ride.phase.name
        if (transition != RideTransition.lapped) {
            val warning = ride.phase == RidePhase.interrupted || ride.phase == RidePhase.failed
            logs.append(
                logs.line(
                    if (warning && transition != RideTransition.discarded) LogLevel.warning else LogLevel.info,
                    "ridePhase",
                    listOfNotNull(
                        LogField("phase", LogValue.Text(phase)),
                        issuer?.let { LogField("issuer", LogValue.Text(it.name)) },
                        code?.let { LogField("code", LogValue.Text(it.name)) },
                    ),
                )
            )
        }
        events.rideTransitioned(ride, transition, event, committed)
    }

    private fun committedOrFailure(result: StoreResult<Commit>): ApiFailure? =
        when (result) {
            is StoreResult.Ok ->
                when (result.value) {
                    is Commit.Committed -> null
                    is Commit.RideGone -> failure(FailureCode.rideChanged)
                    is Commit.RideOpen -> failure(FailureCode.rideInProgress)
                }
            is StoreResult.Failed -> {
                logs.storageFailed(result.error, "command")
                commitFailure(result.error)
            }
        }

    private fun refused(code: FailureCode): Outcome<Unit> = shown(failure(code))

    private fun shown(failed: ApiFailure): Outcome<Unit> {
        if (failed.code != FailureCode.startCancelled)
            state.failure = occurrence(failed.code, state.failure, failed.detail)
        live.publish(urgent = true)
        return Outcome.Failed(failed)
    }

    private fun releaseLease() {
        leases?.release()
    }

    private fun recordedBy(): Device =
        when (device.snapshot().platform) {
            Platform.ios -> Device.iphone
            Platform.android -> Device.androidPhone
            Platform.web -> Device.browser
        }
}
