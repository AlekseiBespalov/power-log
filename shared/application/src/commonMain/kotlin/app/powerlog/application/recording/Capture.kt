package app.powerlog.application.recording

import app.powerlog.application.CommitPolicy
import app.powerlog.application.Deadlines
import app.powerlog.application.Logs
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.commitFailure
import app.powerlog.application.api.failure
import app.powerlog.application.connection.ConnectionSummary
import app.powerlog.application.connection.ReadingSink
import app.powerlog.application.port.Clock
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.LocationEvents
import app.powerlog.application.port.LocationProviderState
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.MemoryMeasurementRepository
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.recording.DropReason
import app.powerlog.domain.recording.GpsClock
import app.powerlog.domain.recording.LocationAdmission
import app.powerlog.domain.recording.LocationDecision
import app.powerlog.domain.recording.LocationOriginal
import app.powerlog.domain.recording.RideLifecycle
import app.powerlog.domain.recording.TelemetryAdmission
import app.powerlog.domain.recording.TelemetryCursor
import app.powerlog.domain.recording.TelemetryDecision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

@OptIn(ExperimentalCoroutinesApi::class)
internal class Capture(
    private val scope: CoroutineScope,
    private val ride: CoroutineDispatcher,
    private val clock: Clock,
    private val writer: ArchiveWriter,
    private val memory: MemoryMeasurementRepository,
    private val state: RecordingState,
    private val live: LivePublisher,
    private val events: RideEvents,
    private val logs: Logs,
    private val device: DeviceInfo,
    private val deadlines: Deadlines,
) : ReadingSink {
    private enum class Committing {
        idle,
        committing,
        busy,
    }

    private class Fenced(val values: Map<MetricId, Double>, val at: ClockReading)

    private class FencedFixes(val batch: List<LocationOriginal>, val at: ClockReading)

    private class Formed(val batch: WriteBatch, val telemetry: Int, val fixes: Int)

    var storageFailed: (RideId, ApiFailure) -> Unit = { _, _ -> }
    var connection: () -> ConnectionSummary? = { null }
    var background: () -> Boolean = { false }
    var hidden = false

    val locationEvents: LocationEvents =
        object : LocationEvents {
            override fun fixesReceived(batch: List<LocationOriginal>, at: ClockReading) {
                if (fenceMonoUs == null) return admitFixes(batch, at)
                val room =
                    CaptureBatchPolicy.inboxEntries -
                        (inbox.size + fixes.size + fenced.size) -
                        fencedFixes.sumOf { it.batch.size }
                if (room < batch.size) refuse()
                if (room > 0) fencedFixes += FencedFixes(batch.take(room), at)
            }

            override fun providerChanged(state: LocationProviderState) {
                logs.event(
                    if (state == LocationProviderState.available) LogLevel.info else LogLevel.warning,
                    "location",
                    "state" to state,
                )
                live.gpsProvider(state)
            }
        }

    private var previewId: RideId? = null
    private var previewOriginMonoUs: Long? = null
    private var previewCursor = emptyCursor
    private var previewSession: ControllerSession? = null
    private var verified: Pair<ControllerIdentity, ControllerCapabilities>? = null

    private var rideId: RideId? = null
    private val sessions = linkedMapOf<Int, ControllerSession>()
    private var session: ControllerSession? = null
    private var pendingSession: ControllerSession? = null
    private var cursor = emptyCursor
    private val inbox = ArrayDeque<TelemetrySample>()
    private val fenced = ArrayDeque<Fenced>()
    private val fixes = ArrayDeque<LocationFix>()
    private val fencedFixes = ArrayDeque<FencedFixes>()
    private var gpsClock = newGpsClock
    private var fenceMonoUs: Long? = null
    private val committing = MutableStateFlow(Committing.idle)
    private var busyUntilMonoUs = 0L
    private var lastCommitMonoUs = 0L
    private var worker: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var summaries: Job? = null
    private var window = Window(0L)

    private class Window(val startedMonoUs: Long) {
        var received = 0
        var admitted = 0
        var dropped = 0
        var committed = 0
        var busy = 0
        var inboxHigh = 0
        var commitMaxUs = 0L
        var timerLateUs = 0L
        val fixesDropped = mutableMapOf<DropReason, Int>()
    }

    override fun previewStarted(previewId: RideId, originMonoUs: Long) {
        this.previewId?.let(memory::close)
        memory.openPreview(previewId, CaptureBatchPolicy.previewReadings)
        this.previewId = previewId
        previewOriginMonoUs = originMonoUs
        previewCursor = emptyCursor
        previewSession = null
        live.previewStarted(previewId, originMonoUs)
        if (summaries?.isActive != true) {
            window = Window(originMonoUs)
            summaries = scope.launch(ride) { summarize() }
        }
    }

    override fun controllerVerified(identity: ControllerIdentity, capabilities: ControllerCapabilities) {
        verified = identity to capabilities
        val open = state.open
        val id = rideId
        if (open != null && id == open.id && open.phase in setOf(RidePhase.running, RidePhase.paused)) {
            val next = ControllerSession(id, (session?.sessionNo ?: 0) + 1, identity, capabilities)
            session = next
            sessions[next.sessionNo] = next
            pendingSession = next
            wake.trySend(Unit)
            return
        }
        val preview = previewId ?: return
        val next = ControllerSession(preview, (previewSession?.sessionNo ?: 0) + 1, identity, capabilities)
        previewSession = next
        memory.addSession(preview, next)
    }

    override fun readingReceived(values: Map<MetricId, Double>, at: ClockReading) {
        window.received++
        if (hidden && state.recording) {
            window.dropped++
            return
        }
        if (!state.recording) {
            preview(values, at)
            return
        }
        val fence = fenceMonoUs
        if (fence != null && at.monoUs >= fence) {
            if (full()) refuse() else fenced += Fenced(values, at)
            return
        }
        admit(values, at)
    }

    fun firstSession(rideId: RideId): ControllerSession? {
        val (identity, capabilities) = verified ?: return null
        return ControllerSession(rideId, 1, identity, capabilities)
    }

    fun started(rideId: RideId, first: ControllerSession?) {
        this.rideId = rideId
        sessions.clear()
        first?.let { sessions[it.sessionNo] = it }
        session = first
        pendingSession = null
        cursor = emptyCursor
        inbox.clear()
        fenced.clear()
        fixes.clear()
        fencedFixes.clear()
        gpsClock = newGpsClock
        lastCommitMonoUs = clock.monoUs()
        committing.value = Committing.idle
        worker?.cancel()
        worker = scope.launch(ride) { work() }
    }

    fun stopped() {
        worker?.cancel()
        worker = null
        retained = null
        committing.value = Committing.idle
        inbox.clear()
        fenced.clear()
        fixes.clear()
        fencedFixes.clear()
        fenceMonoUs = null
        pendingSession = null
        session = null
        rideId = null
    }

    fun fence(cutoffMonoUs: Long) {
        fenceMonoUs = cutoffMonoUs
    }

    fun lift() {
        fenceMonoUs = null
        wake.trySend(Unit)
        val waiting = fenced.toList()
        fenced.clear()
        for (reading in waiting) if (state.recording) admit(reading.values, reading.at)
        else preview(reading.values, reading.at)
        val waitingFixes = fencedFixes.toList()
        fencedFixes.clear()
        for (fixBatch in waitingFixes) admitFixes(fixBatch.batch, fixBatch.at)
    }

    fun gpsBarrier() {
        gpsClock = gpsClock.copy(barrierPending = true)
    }

    suspend fun flush(): ApiFailure? {
        while (true) {
            val status = committing.first { it != Committing.committing }
            if (status == Committing.busy) return failure(FailureCode.storageBusy)
            if (inbox.isEmpty() && fixes.isEmpty() && pendingSession == null) return null
            val result = commitOnce(force = true) ?: return null
            if (result is StoreResult.Failed) {
                if (result.error.kind == StorageError.Kind.busy) {
                    backOff()
                    return failure(FailureCode.storageBusy)
                }
                return commitFailure(result.error)
            }
        }
    }

    private fun admit(values: Map<MetricId, Double>, at: ClockReading) {
        val current = session ?: return
        when (val decision = TelemetryAdmission.admit(values, current, at, state.lifecycle, cursor)) {
            is TelemetryDecision.Admitted -> {
                if (full()) {
                    refuse()
                    return
                }
                cursor = decision.next
                inbox += decision.sample
                window.admitted++
                window.inboxHigh = maxOf(window.inboxHigh, inbox.size + fenced.size)
                if (inbox.size == 1 || inbox.size >= CaptureBatchPolicy.dueEntries) wake.trySend(Unit)
            }
            is TelemetryDecision.Dropped -> window.dropped++
        }
    }

    private fun admitFixes(batch: List<LocationOriginal>, at: ClockReading) {
        if (rideId == null || state.open?.options?.recordGps != true) return
        for (original in batch) {
            when (val decision = LocationAdmission.admit(original, at, state.lifecycle, gpsClock)) {
                is LocationDecision.Admitted -> {
                    if (full()) {
                        gpsClock = gpsClock.copy(barrierPending = true)
                        refuse()
                        continue
                    }
                    gpsClock = decision.next
                    fixes += decision.fix
                    window.inboxHigh = maxOf(window.inboxHigh, inbox.size + fixes.size + fenced.size)
                    val origin = state.lifecycle.clock?.elapsedOriginMonoUs ?: 0L
                    live.fixAdmitted(origin + decision.fix.elapsedUs, decision.fix.horizontalAccuracyM)
                    if (inbox.size + fixes.size == 1 || inbox.size + fixes.size >= CaptureBatchPolicy.dueEntries)
                        wake.trySend(Unit)
                }
                is LocationDecision.Dropped -> {
                    gpsClock = decision.next
                    window.fixesDropped[decision.reason] = (window.fixesDropped[decision.reason] ?: 0) + 1
                }
            }
        }
    }

    private fun preview(values: Map<MetricId, Double>, at: ClockReading) {
        val id = previewId ?: return
        val origin = previewOriginMonoUs ?: return
        val current = previewSession ?: return
        when (val decision = TelemetryAdmission.preview(values, current, at, origin, previewCursor)) {
            is TelemetryDecision.Admitted -> {
                previewCursor = decision.next
                memory.appendPreview(id, listOf(decision.sample))
                live.previewReading(values, decision.sample.elapsedUs, at.monoUs)
            }
            is TelemetryDecision.Dropped -> window.dropped++
        }
    }

    private fun full(): Boolean =
        inbox.size + fixes.size + fenced.size + fencedFixes.sumOf { it.batch.size } >= CaptureBatchPolicy.inboxEntries

    private fun refuse() {
        window.dropped++
        live.inboxFull(true)
    }

    private fun due(nowMonoUs: Long): Boolean {
        if (inbox.size + fixes.size >= CaptureBatchPolicy.dueEntries || pendingSession != null) return true
        val oldest = oldestMonoUs() ?: return false
        return nowMonoUs - oldest >= CaptureBatchPolicy.dueAgeUs
    }

    private fun oldestMonoUs(): Long? {
        val origin = state.lifecycle.clock?.elapsedOriginMonoUs ?: 0L
        return listOfNotNull(inbox.firstOrNull()?.elapsedUs, fixes.firstOrNull()?.elapsedUs).minOrNull()?.plus(origin)
    }

    private fun backOff() {
        window.busy++
        committing.value = Committing.busy
        busyUntilMonoUs = clock.monoUs() + CommitPolicy.busyRetryUs
        wake.trySend(Unit)
    }

    private var retained: Formed? = null

    private suspend fun commitOnce(force: Boolean): StoreResult<Commit>? {
        val id = rideId ?: return null
        val formed = retained ?: nextBatch(id, force) ?: return null
        retained = formed
        committing.value = Committing.committing
        val result = writer.commit(formed.batch)
        committing.value = Committing.idle
        wake.trySend(Unit)
        if (result is StoreResult.Failed && result.error.kind == StorageError.Kind.busy) return result
        retained = null
        when (result) {
            is StoreResult.Ok ->
                when (result.value) {
                    is Commit.Committed -> committed(id, formed)
                    is Commit.RideGone,
                    is Commit.RideOpen -> stopped()
                }
            is StoreResult.Failed -> {
                logs.storageFailed(result.error, "capture")
                storageFailed(id, commitFailure(result.error))
            }
        }
        return result
    }

    private fun nextBatch(id: RideId, force: Boolean): Formed? {
        pendingSession?.let {
            return Formed(WriteBatch(listOf(Mutation.OpenControllerSession(it))), 0, 0)
        }
        val now = clock.read()
        if (inbox.isEmpty() && fixes.isEmpty()) {
            if (force || state.open?.phase != RidePhase.running) return null
            if (now.monoUs - lastCommitMonoUs < CaptureBatchPolicy.checkpointUs) return null
            return Formed(WriteBatch(listOf(Mutation.Checkpoint(id, RideLifecycle.timing(state.lifecycle, now)))), 0, 0)
        }
        val telemetry = minOf(inbox.size, CaptureBatchPolicy.maxEntries)
        val located = minOf(fixes.size, CaptureBatchPolicy.maxEntries - telemetry)
        val mutations = buildList {
            if (telemetry > 0) add(Mutation.AppendTelemetry(id, TelemetryRows.of(inbox.take(telemetry))))
            if (located > 0) add(Mutation.AppendLocations(id, fixes.take(located)))
            add(Mutation.Checkpoint(id, RideLifecycle.timing(state.lifecycle, now)))
        }
        return Formed(WriteBatch(mutations), telemetry, located)
    }

    private fun committed(id: RideId, formed: Formed) {
        lastCommitMonoUs = clock.monoUs()
        oldestMonoUs()?.let {
            if (formed.telemetry + formed.fixes > 0)
                window.commitMaxUs = maxOf(window.commitMaxUs, lastCommitMonoUs - it)
        }
        window.committed += formed.telemetry + formed.fixes
        repeat(formed.telemetry) { inbox.removeFirst() }
        repeat(formed.fixes) { fixes.removeFirst() }
        if (formed.batch.mutations.firstOrNull() is Mutation.OpenControllerSession) pendingSession = null
        live.inboxFull(false)
        val rows = formed.batch.mutations.firstNotNullOfOrNull { (it as? Mutation.AppendTelemetry)?.rows }
        val located = formed.batch.mutations.firstNotNullOfOrNull { (it as? Mutation.AppendLocations)?.fixes }
        if (rows == null && located == null) return
        val edges = live.committed(rows, sessions, located.orEmpty())
        events.batchCommitted(id, rows ?: TelemetryRows.of(emptyList()), sessions.toMap(), located.orEmpty(), edges)
    }

    private suspend fun work() {
        var armedAt: Long? = null
        while (true) {
            val now = clock.monoUs()
            if (committing.value == Committing.busy && now >= busyUntilMonoUs) committing.value = Committing.idle
            val at = wakeAt(now)
            when {
                at == null -> wake.receive()
                at > now -> {
                    armedAt = at
                    select {
                        wake.onReceive {}
                        onTimeout((at - now + 999) / 1_000) {}
                    }
                }
                else -> {
                    armedAt?.let { if (now > it) window.timerLateUs = maxOf(window.timerLateUs, now - it) }
                    armedAt = null
                    val result = commitOnce(force = false)
                    if (result is StoreResult.Failed && result.error.kind == StorageError.Kind.busy) backOff()
                }
            }
        }
    }

    private fun wakeAt(nowMonoUs: Long): Long? {
        if (committing.value == Committing.busy) return busyUntilMonoUs
        if (committing.value != Committing.idle || fenceMonoUs != null) return null
        val dueAt =
            if (inbox.size + fixes.size >= CaptureBatchPolicy.dueEntries || pendingSession != null) nowMonoUs
            else oldestMonoUs()?.plus(CaptureBatchPolicy.dueAgeUs)
        val checkpointAt =
            (lastCommitMonoUs + CaptureBatchPolicy.checkpointUs).takeIf { state.open?.phase == RidePhase.running }
        return listOfNotNull(dueAt, checkpointAt).minOrNull()
    }

    private suspend fun summarize() {
        while (true) {
            val endsAtMonoUs = window.startedMonoUs + CaptureBatchPolicy.summaryUs
            deadlines.until(endsAtMonoUs)
            val connection = connection() ?: return
            val now = clock.monoUs()
            val ended = window
            window = Window(now)
            val windowUs = now - ended.startedMonoUs
            val conditions = device.conditions()
            logs.event(
                LogLevel.info,
                "captureSummary",
                "windowMs" to windowUs / 1_000,
                "requestedHz" to connection.requestedHz,
                "receivedHz" to ended.received * 1_000_000.0 / windowUs,
                "admitted" to ended.admitted,
                "dropped" to ended.dropped,
                "committed" to ended.committed,
                "busy" to ended.busy,
                "inboxHigh" to ended.inboxHigh,
                "commitMaxMs" to ended.commitMaxUs / 1_000,
                "background" to background(),
                "recording" to state.recording,
                "replyMeanMs" to connection.replyMeanMs,
                "replyMaxMs" to connection.replyMaxMs,
                "notificationErrors" to connection.notificationErrors,
                "discardedBytes" to connection.discardedBytes,
                "timerLateMs" to ended.timerLateUs / 1_000,
                "fixesDropped" to
                    LogValue.Fields(
                        ended.fixesDropped.map { (reason, count) ->
                            LogField(reason.name, LogValue.Integer(count.toLong()))
                        }
                    ),
                "processCpuMs" to conditions.processCpuUs?.let { it / 1_000 },
                "footprintBytes" to conditions.footprintBytes,
                "thermal" to conditions.thermal,
                "powerSave" to conditions.powerSave,
            )
        }
    }

    private companion object {
        val emptyCursor = TelemetryCursor(0, null, 0, null)
        val newGpsClock = GpsClock(null, null, null, barrierPending = false, lastSeq = 0, run = 0)
    }
}
