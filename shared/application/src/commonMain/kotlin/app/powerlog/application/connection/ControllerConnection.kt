package app.powerlog.application.connection

import app.powerlog.application.Deadlines
import app.powerlog.application.Logs
import app.powerlog.application.api.Answer
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.Published
import app.powerlog.application.api.StreamPolicy
import app.powerlog.application.api.answer
import app.powerlog.application.api.failure
import app.powerlog.application.api.newId
import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.Clock
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.RecordingKeepAlive
import app.powerlog.application.port.Reply
import app.powerlog.application.port.SendResult
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.ConnectionRepository
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.connection.ConnectionDisplay
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.connection.ControllerLink
import app.powerlog.domain.connection.LinkAction
import app.powerlog.domain.core.controller.ControllerDecoder
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerFramer
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.ControllerPolicy
import app.powerlog.domain.core.controller.ControllerReply
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.GattResult
import app.powerlog.domain.core.controller.TraceContract
import app.powerlog.domain.core.failure.Availability
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.ClockReading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

@OptIn(ExperimentalCoroutinesApi::class)
internal class ControllerConnection(
    private val scope: CoroutineScope,
    private val ride: CoroutineDispatcher,
    private val clock: Clock,
    private val ble: BleCentral?,
    private val permissions: PermissionGate,
    private val device: DeviceInfo,
    private val connections: ConnectionRepository,
    private val writer: ArchiveWriter,
    private val sink: ReadingSink,
    private val trace: TraceSink,
    private val logs: Logs,
    private val keepAlive: RecordingKeepAlive?,
    private val deadlines: Deadlines,
) : BleEvents {
    private class Remembered(val bikeId: BikeId, val known: ControllerIdentity?, val adopt: Boolean)

    private class Cancelled(val reason: String, val linkUpMonoUs: Long?)

    var changed: () -> Unit = {}
    var rideOpen: () -> Boolean = { false }
    var permissionsAnswered: (PermissionStates) -> Unit = {}

    private var state = if (ble == null) ConnectionState.unavailable else ConnectionState.idle
    private val found = mutableListOf<FoundBikeView>()
    private val services = mutableMapOf<BikeId, List<String>>()
    private var bike: BikeView? = null
    private var failure: ApiFailure? = null
    private var searchWindow: Job? = null
    private var radioWait: CompletableDeferred<RadioState>? = null

    private var link: ControllerLink? = null
    private var linkId: Long? = null
    private var linkAlive = false
    private var bikeId: BikeId? = null
    private var decoder: ControllerDecoder? = null
    private var framer: ControllerFramer? = null
    private var identity: ControllerIdentity? = null
    private var inFlight = false
    private var lastSend: LinkAction.Send? = null
    private var reconnect: LinkAction.Reconnect? = null
    private var pendingConnect: CompletableDeferred<Outcome<Unit>>? = null
    private var pendingSearch: CompletableDeferred<Outcome<Unit>>? = null
    private val cancelled = mutableSetOf<Long>()
    private var releasing: Long? = null
    private var releaseAtMonoUs: Long? = null
    private var releaseUnasked = false
    private var opening: Boolean? = null

    private var rate: SampleRate = SampleRate.hz2
    private var background = false
    private var recording = false
    private var rideBike: BikeId? = null
    private var newestMonoUs: Long? = null
    private var faultCode: Int? = null

    private var wakeAtMonoUs = Long.MAX_VALUE
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var timer: Job? = null
    private var attempts = 0
    private var reconnects = 0
    private var previousReadingMonoUs: Long? = null
    private val recentReadings = ArrayDeque<Long>()
    private var holdEnd: Job? = null
    private var launchWait: Remembered? = null
    private var rememberedAttempt = false
    private var rememberedRan = false
    private var reconnectHold = false
    private var holdExpired = false
    private var knownBike = false
    private var linkAttempt = 0
    private var attemptMonoUs: Long? = null
    private var linkUpMonoUs: Long? = null
    private var sentMonoUs: Long? = null
    private var cause: String? = null
    private val cancelReasons = mutableMapOf<Long, Cancelled>()
    private var pace: Pair<Int, Boolean>? = null
    private var notificationErrors = 0
    private var discardedBytes = 0L
    private var replies = 0
    private var replySumUs = 0L
    private var replyMaxUs = 0L

    val stream: Published<ConnectionView> = Published(view(), StreamPolicy.foundBikesUs, clock)

    private val held = HeldBleEvents(this, deadlines::poke)

    val events: BleEvents
        get() = held

    val connected: Boolean
        get() = state == ConnectionState.connected

    fun start(background: Boolean) {
        this.background = background
        pace = paceNow()
        held.release()
        if (device.snapshot().platform == Platform.ios) scope.launch(ride) { remembered(adopt = true) }
    }

    fun foreground(foreground: Boolean) {
        backgroundChanged(!foreground)
        if (!foreground || rememberedRan || device.snapshot().platform != Platform.android) return
        rememberedRan = true
        scope.launch(ride) { remembered(adopt = false) }
    }

    fun holdExpiring() {
        if (!reconnectHold) return
        reconnectHold = false
        holdExpired = true
        keepAlive?.release(HoldKind.reconnect)
    }

    private suspend fun remembered(adopt: Boolean) {
        val central = ble ?: return
        if (state != ConnectionState.idle || bikeId != null) return
        val selected = (connections.selected() as? StoreResult.Ok)?.value ?: return
        val known = (connections.known(selected) as? StoreResult.Ok)?.value
        if (state != ConnectionState.idle || bikeId != null) return
        central.prepare()
        val wait = Remembered(selected, known, adopt)
        bikeId = selected
        bike = BikeView(selected, null, known, null)
        val radio = central.radio()
        if (radio == RadioState.poweredOn) return openRemembered(wait)
        launchWait = wait
        logRadio(radio)
        failure = radioFailure(radio)
        publish(urgent = true)
    }

    private fun openRemembered(wait: Remembered) {
        launchWait = null
        val chosen = wait.known?.let { ControllerDecoders.of(it.decoder) } ?: ControllerDecoders.all.first()
        sink.previewStarted(RideId(newId()), clock.monoUs())
        decoder = chosen
        framer = chosen.newFramer()
        identity = null
        knownBike = wait.known != null
        link = ControllerLink(chosen, knownBike, device.snapshot().platform == Platform.ios, remembered = true)
        bikeId = wait.bikeId
        bike = BikeView(wait.bikeId, null, wait.known, null)
        failure = null
        newestMonoUs = null
        faultCode = null
        state = ConnectionState.reconnecting
        linkAttempt = 0
        open(wait.adopt)
        rememberedAttempt = true
        publish(urgent = true)
    }

    suspend fun startSearch(): Outcome<Unit> {
        if (recording) return refused(FailureCode.rideActive)
        if (state in linked) return refused(FailureCode.disconnectFirst)
        dropLaunchWait()
        pendingSearch?.let {
            return it.await()
        }
        val central = ble ?: return shown(FailureCode.bluetoothUnavailable)
        val claim = CompletableDeferred<Outcome<Unit>>()
        pendingSearch = claim
        found.clear()
        services.clear()
        bike = null
        failure = null
        state = ConnectionState.searching
        publish(urgent = true)
        val outcome = beginSearch(central)
        pendingSearch = null
        claim.complete(outcome)
        return outcome
    }

    private suspend fun beginSearch(central: BleCentral): Outcome<Unit> {
        bluetoothRefusal()?.let {
            return searchRefused(it)
        }
        if (state != ConnectionState.searching) return Outcome.Ok(Unit)
        central.prepare()
        var radio = central.radio()
        if (radio == RadioState.unknown || radio == RadioState.resetting) {
            val waiting = CompletableDeferred<RadioState>()
            radioWait = waiting
            radio = deadlines.await(waiting, clock.monoUs() + ControllerPolicy.searchUs) ?: radio
            radioWait = null
        }
        if (state != ConnectionState.searching) return Outcome.Ok(Unit)
        radioRefusal(radio)?.let {
            return searchRefused(it)
        }
        central.startSearch(ControllerDecoders.all.map { it.transport }, inventory = trace.capturing)
        note(LogLevel.info, "searchStarted", "inventory" to trace.capturing)
        searchWindow?.cancel()
        val searchEndsAtMonoUs = clock.monoUs() + ControllerPolicy.searchUs
        searchWindow =
            scope.launch(ride) {
                deadlines.until(searchEndsAtMonoUs)
                searchEnded(found.isEmpty() && device.snapshot().platform != Platform.web, "windowEnded")
            }
        return Outcome.Ok(Unit)
    }

    private fun searchRefused(code: FailureCode): Outcome<Unit> {
        if (state == ConnectionState.searching)
            state = if (ble == null) ConnectionState.unavailable else ConnectionState.idle
        return shown(code)
    }

    fun stopSearch(): Outcome<Unit> {
        if (state == ConnectionState.searching) searchEnded(nothingFound = false, "stopped")
        return Outcome.Ok(Unit)
    }

    suspend fun connect(target: BikeId): Outcome<Unit> {
        if (recording && rideBike != null && rideBike != target) return refused(FailureCode.differentBike)
        if (target == bikeId && state == ConnectionState.connected) return Outcome.Ok(Unit)
        if (target == bikeId && state in linked)
            pendingConnect?.let {
                return it.await()
            }
        if (state in linked) return refused(FailureCode.disconnectFirst)
        val central = ble ?: return shown(FailureCode.bluetoothUnavailable)
        dropLaunchWait()
        if (state == ConnectionState.searching) searchEnded(nothingFound = false, "stopped")
        val answer = CompletableDeferred<Outcome<Unit>>()
        val previous = bikeId
        val shownBike = bike
        pendingConnect = answer
        bikeId = target
        bike = BikeView(target, found.firstOrNull { it.bikeId == target }?.advertisedName, null, null)
        state = ConnectionState.connecting
        publish(urgent = true)
        bluetoothRefusal()?.let {
            return connectRefused(answer, previous, shownBike, it)
        }
        if (pendingConnect !== answer) return answer.await()
        central.prepare()
        radioRefusal(central.radio())?.let {
            return connectRefused(answer, previous, shownBike, it)
        }
        val known = (connections.known(target) as? StoreResult.Ok)?.value
        if (pendingConnect !== answer) return answer.await()
        val chosen =
            known?.let { ControllerDecoders.of(it.decoder) }
                ?: services[target].orEmpty().firstNotNullOfOrNull(ControllerDecoders::forTransport)
                ?: ControllerDecoders.all.first()
        sink.previewStarted(RideId(newId()), clock.monoUs())
        decoder = chosen
        framer = chosen.newFramer()
        identity = null
        knownBike = known != null
        link = ControllerLink(chosen, knownBike, device.snapshot().platform == Platform.ios)
        linkAttempt = 0
        bikeId = target
        bike = BikeView(target, found.firstOrNull { it.bikeId == target }?.advertisedName, known, null)
        failure = null
        newestMonoUs = null
        faultCode = null
        open(adopt = false)
        publish(urgent = true)
        return answer.await()
    }

    private fun connectRefused(
        answer: CompletableDeferred<Outcome<Unit>>,
        previous: BikeId?,
        shownBike: BikeView?,
        code: FailureCode,
    ): Outcome<Unit> {
        if (pendingConnect === answer) {
            pendingConnect = null
            bikeId = previous
            bike = shownBike
            state = if (ble == null) ConnectionState.unavailable else ConnectionState.idle
        }
        val refused = shown(code)
        answer.complete(refused)
        return refused
    }

    suspend fun disconnect(): Outcome<Unit> {
        if (rideOpen()) return refused(FailureCode.rideActive)
        pendingConnect?.complete(Outcome.Cancelled)
        pendingConnect = null
        dropLaunchWait()
        if (state == ConnectionState.searching) {
            searchWindow?.cancel()
            ble?.stopSearch()
            logSearchEnded("stopped", null)
        }
        endLink("rider")
        state = if (ble == null) ConnectionState.unavailable else ConnectionState.idle
        failure = null
        publish(urgent = true)
        forget("rider")
        return Outcome.Ok(Unit)
    }

    fun recordingChanged(recording: Boolean, rate: SampleRate) {
        val endedReconnecting = !recording && this.recording && state == ConnectionState.reconnecting
        this.recording = recording
        this.rate = rate
        rideBike = if (recording) bikeId else null
        if (endedReconnecting) {
            endLink("rideEnded")
            state = if (ble == null) ConnectionState.unavailable else ConnectionState.idle
            failure = null
            forgetLater("rideEnded")
        } else repace()
        logPace()
        publish(urgent = true)
    }

    fun rateChanged(rate: SampleRate) {
        if (recording) return
        this.rate = rate
        repace()
        logPace()
    }

    fun backgroundChanged(background: Boolean) {
        if (this.background == background) return
        this.background = background
        repace()
        logPace()
        holdForReconnect()
    }

    override fun radioChanged(state: RadioState) {
        logRadio(state)
        radioWait?.complete(state)
        launchWait?.let { wait ->
            if (state == RadioState.poweredOn) return openRemembered(wait)
            failure = radioFailure(state)
            publish(urgent = true)
            return
        }
        val searchRefusal = radioRefusal(state).takeIf { state != RadioState.unknown && state != RadioState.resetting }
        if (this.state == ConnectionState.searching && radioWait == null && searchRefusal != null) {
            searchWindow?.cancel()
            ble?.stopSearch()
            logSearchEnded("stopped", null)
            this.state = ConnectionState.idle
            failure = failure(searchRefusal)
            publish(urgent = true)
            return
        }
        val current = link ?: return
        val now = clock.monoUs()
        val code = radioRefusal(state)
        if (code != null && !recording) {
            pendingConnect?.complete(Outcome.Failed(failure(code)))
            pendingConnect = null
            endLink("radio")
            this.state = ConnectionState.idle
            failure = failure(code)
            publish(urgent = true)
            forgetLater("radio")
        } else if (code != null) {
            current.radio(false, now)
            reconnect = null
            failure = failure(code)
            cancelLink("radio")
            this.state = ConnectionState.reconnecting
            publish(urgent = true)
        } else if (state == RadioState.poweredOn) {
            if (failure?.code in radioCodes) failure = null
            current.radio(true, now)?.let(::run) ?: publish(urgent = true)
        }
    }

    override fun advertised(advertisement: Advertisement) {
        if (state != ConnectionState.searching) return
        val id = BikeId(advertisement.bike)
        if (trace.capturing)
            trace.record(
                "advertisement",
                "name=${advertisement.name} rssi=${advertisement.rssi} services=${advertisement.services.joinToString(",")}",
                advertisement.manufacturerData,
                advertisement.at,
            )
        if (found.any { it.bikeId == id }) return
        found += FoundBikeView(id, advertisement.name, null)
        services[id] = advertisement.services
        publish(urgent = false)
        scope.launch(ride) {
            val known = (connections.known(id) as? StoreResult.Ok)?.value ?: return@launch
            val index = found.indexOfFirst { it.bikeId == id }
            if (index >= 0) found[index] = found[index].copy(known = known)
            publish(urgent = false)
        }
    }

    override fun searchEnded(error: PortError?) {
        if (state != ConnectionState.searching) return
        searchWindow?.cancel()
        logSearchEnded(
            when (error?.kind) {
                null -> "windowEnded"
                PortErrorKind.cancelled -> "chooserCancelled"
                else -> "searchFailed"
            },
            error,
        )
        state = ConnectionState.idle
        failure =
            when (error?.kind) {
                null,
                PortErrorKind.cancelled -> null
                PortErrorKind.noUserActivation -> failure(FailureCode.uiUnavailable)
                PortErrorKind.denied -> failure(FailureCode.bluetoothDenied)
                PortErrorKind.failed ->
                    if (device.snapshot().platform == Platform.web) failure(FailureCode.bikeUnavailable)
                    else failure(FailureCode.bluetoothSearchFailed)
                else -> failure(FailureCode.bluetoothSearchFailed)
            }
        publish(urgent = true)
    }

    override fun linkUp(link: Long, bike: String) {
        if (link != linkId) return
        val now = clock.monoUs()
        linkUpMonoUs = now
        note(
            LogLevel.info,
            "linkUp",
            "attempt" to linkAttempt,
            "elapsedMs" to attemptMonoUs?.let { (now - it) / 1_000 },
        )
        this.link?.linkUp(now)
        ble?.ready(link, inventory = trace.capturing)
    }

    override fun linkFailed(link: Long, error: PortError) {
        if (link != linkId) return
        linkAlive = false
        note(LogLevel.warning, "linkFailed", "attempt" to linkAttempt, "platform" to error.platform)
        val code =
            when (error.kind) {
                PortErrorKind.unknownBike -> FailureCode.bikeNotFound
                PortErrorKind.poweredOff -> FailureCode.bluetoothOff
                PortErrorKind.denied -> FailureCode.bluetoothDenied
                PortErrorKind.unsupported -> FailureCode.bluetoothUnsupported
                PortErrorKind.notReady -> FailureCode.bluetoothNotReady
                else -> null
            }
        if (code == null) {
            cause = "linkFailed"
            this.link
                ?.disconnected(clock.monoUs(), peerOrSystem = false, recording, systemReconnecting = false)
                ?.let(::run)
            return
        }
        if (code == FailureCode.bikeNotFound && rememberedAttempt) {
            endLink()
            state = if (ble == null) ConnectionState.unavailable else ConnectionState.idle
            failure = null
            publish(urgent = true)
            return
        }
        if (code in radioCodes && state == ConnectionState.reconnecting) {
            this.link?.radio(false, clock.monoUs())
            reconnect = null
            failure = failure(code)
            publish(urgent = true)
            return
        }
        giveUp(code)
    }

    override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) {
        val now = clock.monoUs()
        val ended = cancelReasons.remove(link)
        if (ended != null || link == linkId)
            note(
                LogLevel.warning,
                "linkDown",
                "initiator" to if (ended != null) "app" else "peerOrSystem",
                "reason" to ended?.reason,
                "systemReconnecting" to systemReconnecting,
                "linkMs" to (ended?.linkUpMonoUs ?: linkUpMonoUs)?.let { (now - it) / 1_000 },
                "sampleAgeMs" to newestMonoUs?.let { (now - it) / 1_000 },
                "platform" to error?.platform,
            )
        if (cancelled.remove(link)) {
            if (link == releasing) {
                releasing = null
                releaseAtMonoUs = null
                releaseUnasked = false
                opening?.let {
                    opening = null
                    open(it)
                }
            }
            if (link == linkId) {
                linkAlive = false
                val released = this.link?.released(clock.monoUs())
                if (released is LinkAction.Reconnect) reconnect = released
                if (released != null && released !is LinkAction.Reconnect) run(released)
                else reconnect?.let { arm(it.atMonoUs) }
            }
            return
        }
        if (link != linkId) return
        linkAlive = systemReconnecting
        inFlight = false
        cause = "peerOrSystem"
        this.link?.disconnected(clock.monoUs(), peerOrSystem = true, recording, systemReconnecting)?.let(::run)
    }

    override fun linkReady(link: Long, result: GattReady) {
        if (link != linkId) return
        if (trace.capturing)
            result.inventory?.forEach { service ->
                trace.record("service", service.uuid, null, clock.read())
                for (characteristic in service.characteristics) trace.record(
                    "characteristic",
                    "${characteristic.uuid} properties=${characteristic.properties}",
                    null,
                    clock.read(),
                )
            }
        note(
            if (result.outcome == GattResult.ready) LogLevel.info else LogLevel.warning,
            "ready",
            "outcome" to result.outcome,
            "maxWriteBytes" to result.maxWriteBytes,
            "platform" to result.error?.platform,
        )
        cause = "gattSetup"
        this.link?.linkReady(result.outcome, result.error != null, clock.monoUs(), recording)?.let(::run)
    }

    override fun servicesInvalidated(link: Long) {
        if (link != linkId) return
        val now = clock.monoUs()
        if (linkAlive) {
            releasing = link
            releaseAtMonoUs = now + ControllerPolicy.cancelGraceUs
            releaseUnasked = true
        }
        cancelLink("servicesInvalidated")
        cause = "servicesInvalidated"
        this.link?.disconnected(now, peerOrSystem = false, recording, systemReconnecting = false)?.let(::run)
        releaseAtMonoUs?.let { if (releaseUnasked && wakeAtMonoUs > it) arm(it) }
    }

    override fun canSend(link: Long) {
        if (link != linkId) return
        lastSend?.let(::send)
    }

    override fun notified(link: Long, bytes: ByteArray, at: ClockReading) {
        if (link != linkId) return
        if (!inFlight) {
            if (trace.capturing) trace.record("notification", "ignored=noRequest", bytes, at)
            return
        }
        val current = this.link ?: return
        val frames = framer ?: return
        if (current.notified(at.monoUs)) frames.reset()
        val decoded = frames.feed(bytes, identity)
        discardedBytes += decoded.discardedBytes
        if (trace.capturing) {
            trace.record("notification", "", bytes, at)
            trace.record(
                "decoder",
                "replies=${decoded.replies.size} discarded=${decoded.discardedBytes} pending=${decoded.pendingBytes}",
                null,
                at,
            )
        }
        val replies = current.replies(decoded, at.monoUs)
        if (replies.isEmpty()) return
        inFlight = false
        for (reply in replies) {
            when (reply) {
                is ControllerReply.Identity -> verified(reply.identity)
                is ControllerReply.Reading -> reading(reply.values, at)
                is ControllerReply.Refused -> {
                    if (trace.capturing) trace.record("rejected", "${reply.kind}", null, at)
                    if (reply.kind == ControllerRequest.identity)
                        note(LogLevel.warning, "identity", "outcome" to "rejected", "rejection" to reply.kind)
                    cause = "rejected"
                    val code =
                        if (reply.kind == ControllerRequest.identity) FailureCode.controllerUnsupported
                        else FailureCode.firmwareUnsupported
                    return giveUp(code)
                }
            }
        }
        run(next(current, at.monoUs))
    }

    override fun notificationFailed(link: Long, error: PortError) {
        if (link == linkId) notificationErrors++
    }

    override fun writeFailed(link: Long, error: PortError) {
        if (link != linkId) return
        note(LogLevel.warning, "writeFailed", "result" to "failed", "platform" to error.platform)
    }

    private fun verified(verified: ControllerIdentity) {
        val chosen = decoder ?: return
        val target = bikeId ?: return
        identity = verified
        rememberedAttempt = false
        note(LogLevel.info, "identity", "outcome" to "verified")
        val capabilities = chosen.capabilities(verified)
        bike = bike?.copy(identity = verified, controllerSpeed = capabilities.controllerSpeed)
        scope.launch(ride) {
            writer.commit(
                WriteBatch(listOf(Mutation.RememberController(target, verified), Mutation.SelectBike(target)))
            )
        }
        sink.controllerVerified(verified, capabilities)
    }

    private fun reading(values: Map<MetricId, Double>, at: ClockReading) {
        newestMonoUs?.let {
            if (at.monoUs - it > GAP_US) note(LogLevel.info, "telemetryGap", "gapMs" to (at.monoUs - it) / 1_000)
        }
        sentMonoUs?.let { replied(at.monoUs - it) }
        previousReadingMonoUs = newestMonoUs
        newestMonoUs = at.monoUs
        recentReadings.addLast(at.monoUs)
        while (recentReadings.first() < at.monoUs - RECENT_US) recentReadings.removeFirst()
        val fault = values[MetricId.faultCode]?.toInt()?.takeIf { it != 0 }
        val faultChanged = fault != faultCode
        faultCode = fault
        sink.readingReceived(values, at)
        if (faultChanged && state == ConnectionState.connected) publish(urgent = false)
        if (state != ConnectionState.connected) {
            state = ConnectionState.connected
            failure = null
            reconnect = null
            pendingConnect?.complete(Outcome.Ok(Unit))
            pendingConnect = null
            publish(urgent = true)
        }
    }

    private fun run(action: LinkAction) {
        when (action) {
            is LinkAction.Send -> {
                if (action.resent) note(LogLevel.info, "requestResent")
                send(action)
            }
            is LinkAction.Wait -> arm(action.untilMonoUs)
            is LinkAction.Reconnect -> reconnect(action)
            is LinkAction.GiveUp -> giveUp(action.code)
            LinkAction.Release -> {
                val id = linkId ?: return
                if (!release(id)) link?.unreleased(clock.monoUs(), recording)?.let(::run)
            }
        }
    }

    private fun send(action: LinkAction.Send) {
        val central = ble ?: return
        val id = linkId ?: return
        val current = link ?: return
        lastSend = action
        when (central.send(id, action.frame)) {
            SendResult.accepted -> {
                if (trace.capturing)
                    trace.record("write", if (action.resent) "resent" else "", action.frame.bytes, clock.read())
                inFlight = true
                lastSend = null
                sentMonoUs = clock.monoUs()
                arm(current.accepted(clock.monoUs()))
            }
            SendResult.blocked -> arm(current.blocked(clock.monoUs()))
            SendResult.tooLong -> {
                note(LogLevel.warning, "writeFailed", "result" to SendResult.tooLong)
                cause = "writeCapacity"
                giveUp(FailureCode.controllerUnsupported)
            }
            SendResult.notConnected -> {
                note(LogLevel.warning, "writeFailed", "result" to SendResult.notConnected)
                cause = "linkFailed"
                linkAlive = false
                run(current.disconnected(clock.monoUs(), peerOrSystem = false, recording, systemReconnecting = false))
            }
        }
    }

    private fun reconnect(action: LinkAction.Reconnect) {
        reconnects++
        rememberedAttempt = false
        val reason = cause ?: "pendingReconnect"
        note(
            LogLevel.info,
            "reconnectScheduled",
            "attempt" to linkAttempt + 1,
            "delayMs" to maxOf(0L, action.atMonoUs - clock.monoUs()) / 1_000,
            "reason" to reason,
        )
        state = ConnectionState.reconnecting
        reconnect = action
        identity = null
        if (!action.adopt) cancelLink(reason) else linkId?.let(cancelled::remove)
        cause = null
        if (action.adopt || !linkAlive) {
            link?.released(clock.monoUs())
            arm(action.atMonoUs)
        } else link?.let { run(it.timedOut(clock.monoUs(), recording)) }
        publish(urgent = true)
    }

    private fun giveUp(code: FailureCode) {
        note(LogLevel.warning, "reconnectGaveUp", "attempts" to linkAttempt, "code" to code)
        cancelLink(cause ?: "pendingReconnect")
        endLink()
        state = if (ble == null) ConnectionState.unavailable else ConnectionState.idle
        failure = failure(code)
        pendingConnect?.complete(Outcome.Failed(failure(code)))
        pendingConnect = null
        cause = null
        publish(urgent = true)
        forgetLater("gaveUp")
    }

    private fun open(adopt: Boolean) {
        val central = ble ?: return
        val chosen = decoder ?: return
        val target = bikeId ?: return
        val current = link ?: return
        if (releasing != null) {
            opening = adopt
            val at = releaseAtMonoUs
            if (at != null) arm(at) else current.unreleased(clock.monoUs(), recording)?.let(::run)
            return
        }
        inFlight = false
        lastSend = null
        identity = null
        framer = chosen.newFramer()
        attempts++
        linkAttempt++
        attemptMonoUs = clock.monoUs()
        linkUpMonoUs = null
        sentMonoUs = null
        note(LogLevel.info, "connectAttempt", "attempt" to linkAttempt, "adopt" to adopt, "known" to knownBike)
        val id = central.connect(target.value, chosen.transport, adopt)
        linkId = id
        linkAlive = true
        arm(current.attempt(clock.monoUs()))
    }

    private fun arm(atMonoUs: Long) {
        if (atMonoUs == Long.MAX_VALUE) return
        wakeAtMonoUs = atMonoUs
        if (timer?.isActive != true) timer = scope.launch(ride) { wakeUp() }
        wake.trySend(Unit)
    }

    private suspend fun wakeUp() {
        while (true) {
            val at = wakeAtMonoUs
            val wait = at - clock.monoUs()
            when {
                at == Long.MAX_VALUE -> wake.receive()
                wait > 0 ->
                    select {
                        wake.onReceive {}
                        onTimeout((wait + 999) / 1_000) {}
                    }
                else -> {
                    wakeAtMonoUs = Long.MAX_VALUE
                    due()
                }
            }
        }
    }

    private fun due() {
        val held = releasing
        if (held != null && (opening != null || releaseUnasked)) {
            val at = releaseAtMonoUs ?: return
            if (clock.monoUs() < at) return arm(at)
            releaseAtMonoUs = null
            if (!release(held)) link?.unreleased(clock.monoUs(), recording)?.let(::run)
            return
        }
        val pending = reconnect
        if (pending != null && state == ConnectionState.reconnecting && (pending.adopt || !linkAlive)) {
            if (clock.monoUs() >= pending.atMonoUs) {
                reconnect = null
                open(pending.adopt)
            } else arm(pending.atMonoUs)
            return
        }
        val current = link ?: return
        val now = clock.monoUs()
        val timeout =
            when {
                linkUpMonoUs == null -> "attemptTimeout"
                lastSend != null -> "writeTimeout"
                else -> "replyTimeout"
            }
        val action = current.timedOut(now, recording)
        if (action is LinkAction.Reconnect || action is LinkAction.GiveUp) {
            cause = timeout
            if (timeout != "attemptTimeout")
                note(
                    LogLevel.warning,
                    "replyTimeout",
                    "request" to if (identity == null) ControllerRequest.identity else ControllerRequest.telemetry,
                    "held" to (timeout == "writeTimeout"),
                    "waitedMs" to sentMonoUs?.let { (now - it) / 1_000 },
                )
        }
        run(if (action is LinkAction.Wait) next(current, now) else action)
    }

    private fun next(current: ControllerLink, nowMonoUs: Long): LinkAction =
        current.next(nowMonoUs, rate, background, recording).also {
            if (it is LinkAction.Reconnect) cause = "plannedReconnect"
        }

    private fun repace() {
        val current = link ?: return
        if (state != ConnectionState.connected || inFlight) return
        run(next(current, clock.monoUs()))
    }

    private fun cancelLink(reason: String) {
        val id = linkId ?: return
        if (linkAlive && id !in cancelled) {
            cancelled += id
            cancelReasons[id] = Cancelled(reason, linkUpMonoUs)
            ble?.cancel(id)
        }
        inFlight = false
    }

    private fun endLink(reason: String = cause ?: "pendingReconnect") {
        val ended = linkId?.takeIf { linkAlive }
        cancelLink(reason)
        if (ended != null) {
            releasing = ended
            releaseAtMonoUs = clock.monoUs() + ControllerPolicy.cancelGraceUs
        }
        opening = null
        link = null
        linkId = null
        linkAlive = false
        reconnect = null
        framer = null
        identity = null
        rememberedAttempt = false
        linkUpMonoUs = null
        sentMonoUs = null
        wakeAtMonoUs = Long.MAX_VALUE
    }

    private fun dropLaunchWait() {
        if (launchWait == null) return
        launchWait = null
        bikeId = null
        bike = null
        failure = null
    }

    private suspend fun forget(reason: String) {
        note(LogLevel.info, "bikeForgotten", "reason" to reason)
        writer.commit(WriteBatch(listOf(Mutation.SelectBike(null))))
    }

    private fun forgetLater(reason: String) {
        scope.launch(ride) { forget(reason) }
    }

    private fun holdForReconnect() {
        val keepAlive = keepAlive ?: return
        val wanted =
            device.snapshot().platform == Platform.ios &&
                background &&
                identity == null &&
                (state == ConnectionState.connecting || state == ConnectionState.reconnecting)
        if (!wanted) holdExpired = false
        val hold = wanted && !holdExpired
        if (hold == reconnectHold) return
        reconnectHold = hold
        if (hold)
            keepAlive.hold(
                HoldKind.reconnect,
                location = false,
                Reply(onOk = {}, onFailed = { logs.failure("keepAlive", it) }),
            )
        else keepAlive.release(HoldKind.reconnect)
    }

    private fun paceNow(): Pair<Int, Boolean> {
        val hz = if (background && !recording) minOf(rate.hz, ControllerPolicy.backgroundPollHz) else rate.hz
        return hz to background
    }

    private fun logPace() {
        val now = paceNow()
        if (now == pace) return
        pace = now
        note(LogLevel.info, "rateChanged", "hz" to now.first, "background" to now.second)
    }

    private fun note(level: LogLevel, event: String, vararg fields: Pair<String, Any?>) {
        logs.event(level, event, *fields)
        if (trace.capturing)
            trace.record(
                event,
                fields.filter { it.second != null }.joinToString(" ") { (key, value) -> "$key=$value" },
                null,
                clock.read(),
            )
    }

    private fun logRadio(radio: RadioState) {
        note(if (radio == RadioState.poweredOn) LogLevel.info else LogLevel.warning, "radio", "state" to radio)
    }

    private fun radioFailure(radio: RadioState): ApiFailure? =
        radioRefusal(radio)?.takeIf { radio != RadioState.unknown && radio != RadioState.resetting }?.let(::failure)

    private fun logSearchEnded(reason: String, error: PortError?) {
        note(
            LogLevel.info,
            "searchEnded",
            "reason" to reason,
            "found" to found.size,
            "platform" to error?.platform,
        )
    }

    private suspend fun bluetoothRefusal(): FailureCode? {
        if (device.snapshot().platform != Platform.android) return null
        val states = answer<PermissionStates> { permissions.request(setOf(PermissionKind.bluetooth), it) }
        if (states is Answer.Ok) permissionsAnswered(states.value)
        return FailureCode.bluetoothDenied.takeIf {
            states !is Answer.Ok || states.value.bluetooth != PermissionState.granted
        }
    }

    private fun radioRefusal(radio: RadioState): FailureCode? =
        when (radio) {
            RadioState.poweredOn -> null
            RadioState.poweredOff -> FailureCode.bluetoothOff
            RadioState.denied -> FailureCode.bluetoothDenied
            RadioState.unsupported -> FailureCode.bluetoothUnsupported
            RadioState.unknown,
            RadioState.resetting -> FailureCode.bluetoothNotReady
        }

    private fun searchEnded(nothingFound: Boolean, reason: String) {
        searchWindow?.cancel()
        ble?.stopSearch()
        logSearchEnded(reason, null)
        state = ConnectionState.idle
        failure = if (nothingFound) failure(FailureCode.bikeNotFound) else null
        publish(urgent = true)
    }

    private fun refused(code: FailureCode): Outcome<Unit> = Outcome.Failed(failure(code))

    private fun shown(code: FailureCode): Outcome<Unit> {
        failure = failure(code)
        publish(urgent = true)
        return Outcome.Failed(failure(code))
    }

    private fun publish(urgent: Boolean) {
        val view = view()
        stream.publish(view, urgent)
        changed()
        holdForReconnect()
        val heldUntil = view.heldUntilMonoUs
        if (heldUntil == null) {
            holdEnd?.cancel()
            holdEnd = null
        } else if (holdEnd?.isActive != true)
            holdEnd =
                scope.launch(ride) {
                    deadlines.until(heldUntil)
                    holdEnd = null
                    publish(urgent = true)
                }
    }

    private fun view(): ConnectionView {
        val hold = requireNotNull(MetricCatalog.of(MetricId.humanPowerW).displayHoldUs)
        val newest = newestMonoUs
        val within = newest != null && clock.monoUs() - newest < hold
        val display =
            when {
                state == ConnectionState.connected && within -> ConnectionDisplay.live
                state == ConnectionState.reconnecting && within -> ConnectionDisplay.held
                else -> ConnectionDisplay.unavailable
            }
        val open = rideOpen()
        val enabled = Availability(true, null)
        return ConnectionView(
            state = state,
            display = display,
            heldUntilMonoUs = newest?.plus(hold)?.takeIf { display == ConnectionDisplay.held },
            bike = bike,
            found = found.toList(),
            faultCode = faultCode.takeIf { display != ConnectionDisplay.unavailable },
            failure =
                failure
                    ?: failure(FailureCode.bikeReconnecting).takeIf {
                        state == ConnectionState.reconnecting && display == ConnectionDisplay.unavailable
                    },
            allowed =
                ConnectionAllowed(
                    startSearch =
                        when {
                            recording -> Availability(false, FailureCode.rideActive)
                            state in linked -> Availability(false, FailureCode.disconnectFirst)
                            else -> enabled
                        },
                    stopSearch = enabled,
                    connect =
                        when {
                            recording -> Availability(false, FailureCode.differentBike)
                            state in linked -> Availability(false, FailureCode.disconnectFirst)
                            else -> enabled
                        },
                    disconnect = if (open) Availability(false, FailureCode.rideActive) else enabled,
                ),
        )
    }

    private fun release(link: Long): Boolean {
        val released = ble?.release(link) == true
        note(LogLevel.warning, "cancellationDeadline", "released" to released)
        return released
    }

    fun snapshot(): ConnectionSnapshot {
        val now = clock.monoUs()
        val newest = newestMonoUs
        return ConnectionSnapshot(
            state = state,
            identity = identity,
            sampleRateHz = rate.hz.takeIf { link != null },
            background = background,
            connectionAttempts = attempts,
            reconnects = reconnects,
            lastSampleAgeMs = newest?.let { (now - it) / 1_000 },
            lastGapMs = previousReadingMonoUs?.let { previous -> newest?.let { (it - previous) / 1_000 } },
            recentSampleHz =
                recentReadings
                    .count { it >= now - RECENT_US }
                    .takeIf { newest != null }
                    ?.let { it * 1_000_000.0 / RECENT_US },
        )
    }

    fun summary(): ConnectionSummary {
        val summary =
            ConnectionSummary(
                replyMeanMs = (replySumUs / replies / 1_000).takeIf { replies > 0 },
                replyMaxMs = (replyMaxUs / 1_000).takeIf { replies > 0 },
                notificationErrors = notificationErrors,
                discardedBytes = discardedBytes,
                requestedHz = paceNow().first.takeIf { link != null },
            )
        replies = 0
        replySumUs = 0
        replyMaxUs = 0
        notificationErrors = 0
        discardedBytes = 0
        return summary
    }

    val wanted: Boolean
        get() = bike != null && state != ConnectionState.idle && state != ConnectionState.unavailable

    private fun replied(latencyUs: Long) {
        replies++
        replySumUs += latencyUs
        replyMaxUs = maxOf(replyMaxUs, latencyUs)
    }

    fun traceContract(): TraceContract = (decoder ?: ControllerDecoders.all.first()).traceContract

    private companion object {
        const val RECENT_US = 10_000_000L
        const val GAP_US = 2_500_000L
        val linked = setOf(ConnectionState.connecting, ConnectionState.connected, ConnectionState.reconnecting)
        val radioCodes =
            setOf(
                FailureCode.bluetoothOff,
                FailureCode.bluetoothDenied,
                FailureCode.bluetoothUnsupported,
                FailureCode.bluetoothNotReady,
            )
    }
}
