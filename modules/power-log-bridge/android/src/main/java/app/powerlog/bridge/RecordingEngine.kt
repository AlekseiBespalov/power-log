@file:Suppress("MissingPermission")

package app.powerlog.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import androidx.core.content.ContextCompat
import app.powerlog.core.StreamStatus
import app.powerlog.core.gps.*
import app.powerlog.core.lifecycle.*
import app.powerlog.core.presentation.*
import app.powerlog.core.protocol.OwnerTiming
import app.powerlog.core.protocol.Timestamps
import app.powerlog.core.telemetry.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal class RecordingEngine
private constructor(
    val context: Context,
    private val clock: RecordingClock,
    healthAccess: HealthConnectAccess,
    private val healthScheduler: (String, Long?, Boolean) -> Unit,
) {
    private val thread = HandlerThread("PowerLogCapture").apply { start() }
    val handler = Handler(thread.looper)
    val reads = Executors.newSingleThreadExecutor { task -> Thread(task, "PowerLogReads") }
    val summaries = SummaryReads()
    val monitorReads = MonitorReads()
    val store = RideStore.shared(context)
    val health = HealthExport(context, store, healthAccess, { timeUs(clock.read().timestamp) })
    val healthTasks = Executors.newSingleThreadExecutor { task -> Thread(task, "PowerLogHealthWork") }
    private val healthOperations = mutableSetOf<String>()
    private val workPublishPending = java.util.concurrent.atomic.AtomicBoolean()
    private val previewStore = RideStore(context, null)
    private val previewDistance = RideDistance(previewStore)
    private val previewMonitor = RideMonitor(previewStore, previewDistance, ::monitorOriginSeconds)
    val distance = RideDistance(store)
    val monitor = RideMonitor(store, distance, ::monitorOriginSeconds)
    val listeners = CopyOnWriteArrayList<(String, Payload) -> Unit>()
    private val locations = context.getSystemService(LocationManager::class.java)
    private var live = ""
    private var liveStart = 0L
    @Volatile private var ride: String? = null
    private var phase = "idle"
    private var controlToken = java.util.UUID.randomUUID().toString()
    private var pendingAction: String? = null
    private var externalFinish: ExternalControlRequest? = null
    private var livePresentation = LivePresentation.empty()
    private var notificationSequence = 0L
    private var controlIdentity = Triple<String?, String, String?>(null, phase, null)
    private var selectedRide: String? = null
    private var options = RideOptions(indoor = false, saveToHealth = false, recordGPS = false)
    @Volatile private var startClock = 0L
    private var lifecycle = LifecycleState()
    private var captureBatch = CaptureBatch()
    private var segment = 0
    private var telemetrySegment = 0
    private var interruptionIndex = 0
    private var rideDevice: String? = null
    private val historyRevision: Long
        get() = store.catalogState().revision

    private val lastDeleted: String?
        get() = store.catalogState().lastDeletedWorkoutId

    private val telemetryCapture = TelemetryCapture(store)
    private val previewCapture = TelemetryCapture(previewStore)
    private var captureSession = java.util.UUID.randomUUID().toString()
    private val captureClockEpoch = RecordingIdentities.clockEpoch()
    private var captureSequence = 0L
    private var previousTelemetryClock: RecordingClockReading? = null
    private var lastTelemetry = 0L
    private var lastLocation = 0L
    private var gpsAcquisition = GPSAcquisition()
    private var gpsBarrier = false
    private var gpsClockEpoch = 0L
    private var lastEpoch = ""
    private val ready = java.util.concurrent.CountDownLatch(1)
    @Volatile private var initializationError: Exception? = null
    private var sequence = 0L
    private var rideError: String? = null
    private var altitudeConversion = mslConversion(context)
    private var lastSampleEvent = 0L
    private var lastLocationValues: Map<String, Double> = emptyMap()
    private var gpsUnavailable = false
    private var wake: PowerManager.WakeLock? = null
    private var publishedSnapshot: RideSnapshot? = null
    private var publishedReadWorkoutIds: List<String> = emptyList()

    private data class ForegroundRequest(
        val key: LifecycleKey,
        val id: String,
        val waitForReady: Boolean,
        val ready: java.util.concurrent.CompletableFuture<Unit> = java.util.concurrent.CompletableFuture(),
    )

    @Volatile private var foregroundRequest: ForegroundRequest? = null

    private data class Boundary(val time: Double, val active: Boolean, val segment: Int)

    private val boundaries = mutableListOf<Boundary>()

    private data class Pending(
        val ride: String,
        val time: Double,
        val timestamp: String,
        val kind: String,
        val active: Boolean,
        val segment: Int,
        val values: Map<String, Double>,
        val identity: String,
        val epoch: String,
        val acquiredAt: Double,
        val original: OriginalIdentity,
        val altitude: AltitudeEvidence? = null,
        val distanceBarrier: Boolean = false,
        val interruptionIndex: Int = 0,
        val telemetry: TelemetryCaptureEvidence? = null,
    ) {
        fun payload(): Payload =
            mapOf(
                "ride" to ride,
                "time" to time,
                "timestamp" to timestamp,
                "kind" to kind,
                "active" to active,
                "segment" to segment,
                "values" to values,
                "identity" to identity,
                "epoch" to epoch,
                "acquiredAt" to acquiredAt,
                "original" to original.payload(),
                "altitude" to altitude?.payload(),
                "distanceBarrier" to distanceBarrier,
                "interruptionIndex" to interruptionIndex,
                "telemetry" to telemetry?.detail(),
            )
    }

    private val pending = mutableListOf<Pending>()

    private fun pendingOriginal(
        id: String,
        producer: String,
        eventId: String = java.util.UUID.randomUUID().toString(),
    ): OriginalIdentity {
        val highWater =
            maxOf(
                (if (id.startsWith("live-")) previewStore else store).producerHighWater(id, producer),
                pending
                    .filter { it.ride == id && it.original.producer == producer }
                    .maxOfOrNull { it.original.sequence } ?: 0,
            )
        return OriginalIdentity(eventId, producer, Math.addExact(highWater, 1))
    }

    private fun buffer(rows: List<Pending>): Boolean {
        if (rows.isEmpty()) return false
        val first = rows.first()
        fun membership(row: Pending?) = row?.let {
            CaptureMembership(it.ride.removePrefix("live-"), it.time, lifecycle.generation.coerceAtLeast(1))
        }
        val original =
            CaptureOriginal(
                first.original.eventId,
                first.original.producer,
                first.original.sequence,
                Timestamps.parse(first.timestamp),
                captureClockEpoch,
                (first.acquiredAt * 1_000_000).toLong(),
                first.telemetry?.connectionEpoch,
                membership(rows.firstOrNull { it.ride.startsWith("live-") }),
                membership(rows.firstOrNull { !it.ride.startsWith("live-") }),
                json(mapOf("rows" to rows.map { it.payload() })).toString(),
            )
        val fault =
            CaptureFault(
                java.util.UUID.randomUUID().toString(),
                lifecycle.ride?.key,
                Timestamps.parse(first.timestamp),
                first.original.eventId,
                RideFailure.recordingInterrupted.name,
            )
        val decision = LifecycleCapture.admit(captureBatch, original, fault)
        captureBatch = decision.batch
        if (decision.admitted)
            pending.addAll(rows.filter { it.ride.startsWith("live-") || decision.batch.originals.last().ride != null })
        if (decision.outcome == "overflow")
            captureFailed(IllegalStateException(fault.error), LifecycleStorageError.captureInterrupted)
        return decision.admitted
    }

    val bluetooth = ControllerBluetooth(context, handler, ::emit, ::telemetry) { ride != null }
    @Volatile
    var notificationState: ActivityPresentation? = null
        private set

    @Volatile
    var gpsActive = false
        private set

    @Volatile
    var initialized = false
        private set

    private val tick =
        object : Runnable {
            override fun run() {
                try {
                    flush()
                    if (ride != null) publish()
                } catch (error: Exception) {
                    if (!storageBusy(error)) captureFailed(error)
                } finally {
                    handler.postDelayed(this, 1000)
                }
            }
        }

    private fun ownerTiming(now: Long = clock.read().monotonicMillis) =
        lifecycle.ride?.let {
            RideLifecycle.timing(it, clock.read().copy(monotonicMillis = now).lifecycle(captureClockEpoch))
        }

    private fun timer(now: Long = clock.read().monotonicMillis) = ownerTiming(now)?.timerSeconds ?: 0.0

    fun capabilities() = PlatformCapabilities.android(health.available)

    fun state(): Payload =
        try {
            snapshot().toWireMap()
        } catch (error: Exception) {
            captureFailed(error)
            faultSnapshot().toWireMap()
        }

    private fun snapshot(
        now: Long = clock.read().monotonicMillis,
        readStorage: Boolean = true,
    ): RideSnapshot {
        val id = ride ?: selectedRide
        val metadata = if (readStorage) id?.let(store::metadata) else null
        val retained = if (readStorage) null else publishedSnapshot?.takeIf { it.id == id }
        val ownerHealth =
            app.powerlog.core.health.OwnerHealth(
                if (options.saveToHealth) app.powerlog.core.health.HealthOutcome.pending
                else app.powerlog.core.health.HealthOutcome.notRequested,
                null,
            )
        val active = ride != null
        val gpsStatus =
            StreamStatus.gps(
                requested = active && options.recordGPS,
                phase = phase,
                age = if (lastLocation == 0L) null else (now - lastLocation) / 1000.0,
                accuracy = lastLocationValues["horizontalAccuracyM"],
                provider =
                    when {
                        !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "denied"
                        gpsUnavailable || !locations.isLocationEnabled -> "unavailable"
                        else -> null
                    },
            )
        val controllerStatus =
            StreamStatus.controller(
                requested = active,
                phase = phase,
                age = if (lastTelemetry == 0L) null else (now - lastTelemetry) / 1000.0,
            )
        return RideSnapshot(
                capabilities = capabilities(),
                controlToken = controlToken,
                pendingAction = lifecycle.ride?.pending?.action?.name ?: pendingAction,
                healthBusy = RideStore.healthBusy,
                readWorkoutIds = store.readWorkoutIds,
                startBusy = lifecycle.ride?.pending?.effect == LifecycleEffectKind.startOwner,
                finalizationBusy = lifecycle.ride?.pending?.terminal == true,
                externalFinish = externalFinish,
                id = id,
                phase = if (active) phase else metadata?.str("phase") ?: phase,
                timerSeconds =
                    if (active || id == null) timer(now)
                    else if (readStorage) store.timing(id).timer else retained?.timerSeconds ?: 0.0,
                collectionRevision = (metadata?.get("collectionRevision") as? String) ?: retained?.collectionRevision,
                historyRevision =
                    if (readStorage) historyRevision.toString() else publishedSnapshot?.historyRevision ?: "0",
                lastDeletedWorkoutId = if (readStorage) lastDeleted else publishedSnapshot?.lastDeletedWorkoutId,
                indoor = options.indoor,
                useWatch = options.useWatch,
                saveToHealth = options.saveToHealth,
                recordGPS = options.recordGPS,
                distanceSource = lifecycle.ride?.options?.distanceSource ?: metadata?.get("distanceSource") as? String,
                batteryConsumptionSource =
                    lifecycle.ride?.options?.batteryConsumptionSource
                        ?: metadata?.get("batteryConsumptionSource") as? String
                        ?: options.batteryConsumptionSource,
                healthKitState =
                    if (id != null && options.saveToHealth)
                        metadata?.str("healthKitState") ?: retained?.healthKitState ?: ownerHealth.outcome.name
                    else "notRequested",
                healthReason =
                    (metadata?.get("healthReason") as? String) ?: retained?.healthReason ?: ownerHealth.reason?.name,
                streams =
                    RideStreams(
                        RideStream(controllerStatus),
                        RideStream("off"),
                        RideGPSStream(
                            gpsStatus,
                            accuracyMeters =
                                lastLocationValues["horizontalAccuracyM"]?.takeIf {
                                    it.isFinite() && it >= 0
                                },
                        ),
                    ),
                failure = rideError?.let(Failures::present),
            )
            .also { if (readStorage) publishedSnapshot = it }
    }

    private fun faultSnapshot() = snapshot(readStorage = false)

    private fun refreshControlIdentity() {
        val identity = Triple(ride, phase, pendingAction)
        if (identity != controlIdentity) {
            controlIdentity = identity
            controlToken = java.util.UUID.randomUUID().toString()
            externalFinish = null
        }
    }

    private fun publish() {
        refreshControlIdentity()
        try {
            publishSnapshot(snapshot())
        } catch (error: Exception) {
            captureFailed(error)
        }
    }

    private fun publishSnapshot(snapshot: RideSnapshot) {
        publishedReadWorkoutIds = snapshot.readWorkoutIds
        emit("onWorkoutState", snapshot.toWireMap())
        refreshNotification(snapshot)
    }

    private fun presentationClock(reading: RecordingClockReading = clock.read()) =
        PresentationClock(
            captureClockEpoch,
            reading.monotonicMillis * 1000,
            Timestamps.parse(reading.timestamp).comparisonUs,
        )

    private fun refreshNotification(snapshot: RideSnapshot = snapshot()) {
        refreshControlIdentity()
        livePresentation = LivePresentation.adopt(livePresentation, ride)
        val reading = clock.read()
        val now = presentationClock(reading)
        val publication = ride?.let {
            notificationSequence = Math.addExact(notificationSequence, 1)
            RideNotificationAdapter.project(
                snapshot,
                notificationSequence,
                livePresentation,
                now,
            )
        }
        notificationState = publication
        RecordingService.refresh()
    }

    fun externalAction(request: ExternalControlRequest, confirmed: Boolean, discard: Boolean = false): Payload {
        val decision = RideNotificationAdapter.external(snapshot(), request, confirmed)
        if (decision.outcome == "rejected") throw RideFailureException(decision.error)
        if (decision.outcome == "confirmationRequired") {
            check(externalFinish == null) { "A Finish confirmation is already open." }
            externalFinish = request
            publish()
        } else {
            if (request.action == "finish")
                check(externalFinish?.toWireMap() == request.toWireMap()) { "Confirm finishing the selected ride." }
            action(
                if (discard && request.action == "finish") "discard" else requireNotNull(decision.command),
                request.rideId,
            )
        }
        return state()
    }

    fun cancelExternalAction(request: ExternalControlRequest): Payload {
        if (externalFinish?.toWireMap() == request.toWireMap()) {
            externalFinish = null
            controlToken = java.util.UUID.randomUUID().toString()
            publish()
        }
        return state()
    }

    fun permissionStatus(): Payload {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val requested =
            context.getSharedPreferences("permissions", Context.MODE_PRIVATE).getBoolean("locationRequested", false)
        return mapOf(
            "health" to health.status(),
            "location" to if (fine || coarse) "authorizedWhenInUse" else if (requested) "denied" else "notDetermined",
            "locationServicesEnabled" to locations.isLocationEnabled,
            "locationAccuracyAuthorization" to if (fine) "full" else if (coarse) "reduced" else "unknown",
        )
    }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun start(input: RideOptions, healthGrants: Set<String> = emptySet()): Payload {
        flush()
        val capturedStart = clock.read().lifecycle(captureClockEpoch)
        val decision =
            store.startLifecycle(
                lifecycle,
                LifecycleStart(
                    RecordingIdentities.ride(),
                    RecordingIdentities.command(),
                    capturedStart,
                    input.requested(),
                    capabilities(),
                    bikeReady = bluetooth.state["status"] == "connected",
                    retainedCapture = captureBatch.originals.isNotEmpty(),
                    phoneRecovery = LifecycleOwnerRecovery.local,
                ),
            )
        val frozen = decision.mutation?.state?.ride?.options
        if (frozen != null) {
            if (frozen.saveToHealth && !(health.available && healthGrants.containsAll(health.essentialPermissions())))
                throw RideFailureException(RideFailure.healthDenied)
            if (frozen.recordGPS && !(granted(Manifest.permission.ACCESS_FINE_LOCATION) && locations.isLocationEnabled))
                throw RideFailureException(RideFailure.locationDenied)
            if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
                throw RideFailureException(RideFailure.bluetoothDenied)
        }
        applyLifecycle(decision, capturedStart)
        publish()
        return state()
    }

    private fun applyLifecycle(decision: LifecycleTransition, capturedStart: LifecycleClock? = null) {
        val before = lifecycle
        val outcome =
            try {
                store.commitLifecycle(before, decision)
            } catch (error: Exception) {
                if (
                    error is android.database.sqlite.SQLiteException ||
                        error is expo.modules.kotlin.exception.CodedException
                )
                    captureFailed(error)
                throw error
            }
        adoptLifecycle(outcome.state)
        refreshControlIdentity()
        decision.mutation?.let { mutation ->
            if (mutation.resetLive) {
                livePresentation = LivePresentation.empty()
                lastTelemetry = 0L
                lastLocation = 0L
                lastLocationValues = emptyMap()
                gpsUnavailable = false
            }
            if (mutation.resetIntegration) {
                segment++
                telemetrySegment++
                boundary(lifecycle.ride!!.timing.elapsedSeconds)
            }
        }
        outcome.effects.forEach { executeEffect(it, capturedStart) }
    }

    private fun adoptLifecycle(state: LifecycleState) {
        lifecycle = state
        val owner = state.ride
        pendingAction = owner?.pending?.action?.name
        phase = owner?.phase?.name ?: "idle"
        rideError = owner?.error
        selectedRide = owner?.key?.rideId ?: selectedRide
        ride =
            owner
                ?.takeIf { it.phase !in setOf(LifecyclePhase.completed, LifecyclePhase.failed) || it.pending != null }
                ?.key
                ?.rideId
        if (owner != null) {
            val frozen = owner.options
            options =
                RideOptions(
                    frozen.indoor,
                    frozen.useWatch,
                    frozen.saveToHealth,
                    frozen.recordGPS,
                    frozen.sampleHz,
                    frozen.distanceSource,
                    frozen.batteryConsumptionSource,
                )
            val now = clock.read().monotonicMillis
            startClock =
                now -
                    (RideLifecycle.timing(owner, clock.read().lifecycle(captureClockEpoch)).elapsedSeconds * 1000)
                        .toLong()
        }
    }

    private fun receipt(effect: LifecycleEffect, error: String? = null, at: LifecycleClock? = null) {
        applyLifecycle(
            store.receiptLifecycle(
                lifecycle,
                LifecycleReceipt(
                    effect.intent.key,
                    effect.intent.commandId,
                    effect.intent.effect,
                    at ?: clock.read().lifecycle(captureClockEpoch),
                    error = error,
                    startedAt =
                        if (effect.intent.effect == LifecycleEffectKind.startOwner) effect.intent.timing.timestamp
                        else null,
                ),
            )
        )
    }

    private fun executeEffect(effect: LifecycleEffect, capturedStart: LifecycleClock? = null) {
        try {
            executeOwnerOperation(effect, capturedStart)
        } catch (error: Exception) {
            if (
                error is android.database.sqlite.SQLiteException ||
                    error is expo.modules.kotlin.exception.CodedException
            )
                captureFailed(error)
            else {
                val start = effect.intent.effect == LifecycleEffectKind.startOwner
                receipt(effect, RideFailures.classify(error, if (start) "start" else null).name)
                if (start) removeFailedStart()
            }
            throw error
        }
    }

    private fun executeOwnerOperation(effect: LifecycleEffect, capturedStart: LifecycleClock?) {
        val intent = effect.intent
        val id = intent.key.rideId
        when (intent.effect) {
            LifecycleEffectKind.startOwner -> {
                bluetooth.setHz(intent.options.sampleHz)
                telemetryCapture.reset()
                interruptionIndex = 0
                segment++
                telemetrySegment++
                boundaries.clear()
                boundaries.add(Boundary(0.0, true, segment))
                gpsAcquisition = GPSAcquisition()
                gpsBarrier = false
                gpsClockEpoch++
                rideDevice = bluetooth.deviceId
                distance.retainOnly(live)
                monitor.selectLiveRide(id)
                previewMonitor.selectLiveRide(id)
                gpsActive = options.recordGPS
                refreshNotification()
                try {
                    beginForegroundCapture()
                    if (options.recordGPS) startLocationUpdates()
                } catch (error: Exception) {
                    receipt(effect, RideFailures.classify(error, "start").name)
                    cleanup()
                    removeFailedStart()
                    throw error
                }
                receipt(effect, at = capturedStart)
            }
            LifecycleEffectKind.pauseOwner -> {
                if (options.recordGPS) locations.removeUpdates(locationListener)
                receipt(effect)
            }
            LifecycleEffectKind.resumeOwner -> {
                if (options.recordGPS) startLocationUpdates()
                receipt(effect)
            }
            LifecycleEffectKind.lapOwner -> receipt(effect)
            LifecycleEffectKind.stopOwner -> {
                cleanup()
                store.finishCollection(id)
                receipt(effect)
                if (intent.options.saveToHealth) queueHealth(id)
            }
            LifecycleEffectKind.discardOwner -> {
                receipt(effect)
                cleanup()
            }
            LifecycleEffectKind.deleteRecording -> {
                cleanup()
                deleteRecording(effect)
            }
            LifecycleEffectKind.repairHealth -> {
                healthOperation(
                    effect,
                    {
                        health.repair(id)
                        healthScheduler(id, store.healthJob(id)?.resumeAtUs, true)
                    },
                ) {}
            }
            LifecycleEffectKind.repairRecording -> error("Interrupted rides can only be finished or discarded.")
            LifecycleEffectKind.recoverOwner -> error("Android has no native recording session to recover.")
        }
    }

    private fun healthOperation(effect: LifecycleEffect, body: () -> Unit, completed: () -> Unit) {
        val operation = "${effect.intent.commandId}:${effect.intent.effect}"
        if (!healthOperations.add(operation)) return
        healthTasks.execute {
            val error = runCatching(body).exceptionOrNull()
            handler.post {
                if (!healthOperations.remove(operation)) return@post
                val pending = lifecycle.ride?.pending
                if (
                    effect.intent.effect != LifecycleEffectKind.repairHealth &&
                        (pending?.commandId != effect.intent.commandId || pending.effect != effect.intent.effect)
                )
                    return@post
                try {
                    if (error != null) receipt(effect, RideFailures.classify(error, "health").name) else completed()
                    publish()
                } catch (failure: Exception) {
                    captureFailed(failure)
                }
            }
        }
    }

    private fun deleteRecording(effect: LifecycleEffect) {
        val id = effect.intent.key.rideId
        val before = lifecycle
        val decision =
            store.receiptLifecycle(
                before,
                LifecycleReceipt(
                    effect.intent.key,
                    effect.intent.commandId,
                    effect.intent.effect,
                    clock.read().lifecycle(captureClockEpoch),
                ),
            )
        store.transaction {
            if (store.exists(id)) store.remove(id, discard = true)
            store.commitLifecycle(before, decision)
            store.writableDatabase.delete("lifecycle_intents", "ride=?", arrayOf(id))
        }
        selectedRide = null
        adoptLifecycle(decision.afterCommit(true).state)
        livePresentation = LivePresentation.empty()
        cleanup()
    }

    fun foreground() {
        healthTasks.execute {
            try {
                for ((id, token) in store.resumeHealthReads()) {
                    try {
                        healthScheduler(id, null, true)
                        store.acknowledgeHealthReads(id, token)
                    } catch (error: Exception) {
                        handler.post {
                            rideError = RideFailures.classify(error, "health").name
                            publish()
                        }
                    }
                }
            } catch (error: Exception) {
                handler.post {
                    rideError = RideFailures.classify(error, "health").name
                    publish()
                }
            }
        }
    }

    private fun beginForegroundCapture(waitForReady: Boolean = false) {
        val request =
            ForegroundRequest(requireNotNull(lifecycle.ride).key, java.util.UUID.randomUUID().toString(), waitForReady)
        foregroundRequest = request
        ContextCompat.startForegroundService(
            context,
            Intent(context, RecordingService::class.java).putExtra(RecordingService.FOREGROUND_REQUEST, request.id),
        )
        if (waitForReady) {
            try {
                request.ready.get(PresentationTimeouts.recoveryUs, java.util.concurrent.TimeUnit.MICROSECONDS)
            } catch (error: java.util.concurrent.ExecutionException) {
                throw (error.cause as? Exception ?: error)
            }
        }
        if (wake?.isHeld != true) {
            val acquired =
                context
                    .getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerLog:recording")
            try {
                acquired.acquire(24 * 60 * 60 * 1000L)
                wake = acquired
            } catch (error: Exception) {
                if (acquired.isHeld) acquired.release()
                throw error
            }
        }
    }

    internal fun foregroundReady(requestId: String?, rideId: String, error: Exception? = null) {
        val request = foregroundRequest ?: return
        if (request.key.rideId != rideId || requestId != null && request.id != requestId) return
        val waiting = request.waitForReady && !request.ready.isDone
        if (error == null) request.ready.complete(Unit)
        else {
            request.ready.completeExceptionally(error)
            if (!waiting)
                handler.post {
                    if (foregroundRequest === request && lifecycle.ride?.key == request.key) {
                        cleanup()
                        captureFailed(error, LifecycleStorageError.captureInterrupted)
                    }
                }
        }
    }

    internal val awaitingForeground: Boolean
        get() = foregroundRequest?.let { it.waitForReady && !it.ready.isDone } == true

    private fun requestLifecycle(request: LifecycleRequest): LifecycleTransition =
        try {
            store.requestLifecycle(lifecycle, request)
        } catch (error: Exception) {
            captureFailed(error)
            throw error
        }

    fun action(action: String, id: String?, commandId: String = java.util.UUID.randomUUID().toString()): Payload {
        val owner = lifecycle.ride ?: error("No ride is recording.")
        require(id == null || id == owner.key.rideId) { "This command belongs to an earlier ride." }
        val reading = clock.read()
        val request =
            LifecycleRequest(owner.key, commandId, RideAction.valueOf(action), reading.lifecycle(captureClockEpoch))
        if (captureBatch.fault != null) flush(reading)
        val admission = requestLifecycle(request)
        admission.rejection?.let { throw RideFailureException(it) }
        flush(reading)
        val current = requireNotNull(lifecycle.ride)
        val decision =
            requestLifecycle(
                LifecycleRequest(
                    current.key,
                    commandId,
                    RideAction.valueOf(action),
                    reading.lifecycle(captureClockEpoch),
                    retainedCapture = captureBatch.originals.isNotEmpty(),
                )
            )
        applyLifecycle(decision)
        publish()
        return state()
    }

    private fun boundary(time: Double) {
        boundaries.add(Boundary(time, phase == "running", segment))
        while (boundaries.size > 1 && boundaries[1].time <= time - 15) boundaries.removeAt(0)
    }

    fun delete(id: String): Payload {
        store.deleteHistorical(id, lifecycle)
        if (selectedRide == id) selectedRide = null
        if (lifecycle.ride?.key?.rideId == id) adoptLifecycle(store.lifecycleState())
        publish()
        return state()
    }

    fun refreshHealth(id: String, retrySave: Boolean = false): Payload {
        check(store.exists(id) && store.metadata(id)["endedAt"] != null) {
            "Finish this ride before refreshing Health."
        }
        healthTasks.execute {
            try {
                if (retrySave) health.repair(id) else health.refresh(id)
                if (store.exists(id)) healthScheduler(id, null, true)
            } finally {
                handler.post { publish() }
            }
        }
        return state()
    }

    private fun queueHealth(id: String) {
        healthTasks.execute {
            try {
                healthScheduler(id, store.healthJob(id)?.resumeAtUs, false)
            } catch (error: Exception) {
                handler.post {
                    rideError = RideFailures.classify(error, "health").name
                    publish()
                }
            }
        }
    }

    private fun removeFailedStart(): Boolean {
        val owner =
            lifecycle.ride?.takeIf { RideLifecycle.failedStartRemovable(it, captureBatch.originals.isNotEmpty()) }
                ?: return false
        try {
            val id = owner.key.rideId
            if (store.originalMembershipCount(id) > 0) return false
            val outcome = store.removeFailedStart(lifecycle, RideLifecycle.clearSelection(lifecycle, id))
            selectedRide = null
            adoptLifecycle(outcome.state)
            livePresentation = LivePresentation.empty()
            return true
        } catch (error: Exception) {
            android.util.Log.w("PowerLog", "A failed start could not be removed.", error)
            return false
        }
    }

    private fun cleanup() {
        foregroundRequest = null
        if (bluetooth.state["status"] == "reconnecting") bluetooth.disconnect()
        previewMonitor.selectLiveRide(live)
        rideDevice = null
        gpsActive = false
        boundaries.clear()
        locations.removeUpdates(locationListener)
        if (wake?.isHeld == true) wake?.release()
        wake = null
        distance.retainOnly(live)
        context.stopService(Intent(context, RecordingService::class.java))
    }

    private fun telemetry(
        reading: TelemetryReading,
        epoch: String,
        clockReading: RecordingClockReading,
    ) {
        val acquiredAt = clockReading.monotonicMillis
        val timestamp = clockReading.timestamp
        val identity = requireNotNull(reading.identity)
        val values = reading.values()
        val discontinuity =
            previousTelemetryClock?.takeIf { acquiredAt >= it.monotonicMillis }?.let(clockReading::discontinuity)
        val current = ride
        val liveTime = (acquiredAt - liveStart) / 1000.0
        val rideTime = current?.let {
            lifecycle.ride?.let { owner ->
                RideLifecycle.collectionElapsed(owner, clockReading.lifecycle(captureClockEpoch))
            }
        }
        val rideActive = rideTime?.let { time -> boundaries.lastOrNull { it.time <= time }?.active }
        val time = rideTime ?: liveTime
        sequence = Math.addExact(sequence, 1)
        captureSequence = Math.addExact(captureSequence, 1)
        if (lastEpoch != epoch) {
            telemetrySegment++
            lastEpoch = epoch
        }
        val identityText = "${identity.controllerModel}|${identity.firmwareLabel}|${identity.controllerProtocol}"
        val eventId = RecordingIdentities.telemetry()
        fun observation(target: String, at: Double, active: Boolean, segment: Int): Pending? {
            val evidence =
                TelemetryCaptureEvidence(
                    target.removePrefix("live-"),
                    eventId,
                    captureSession,
                    captureClockEpoch,
                    epoch,
                    captureSequence,
                    Timestamps.parse(timestamp),
                    acquiredAt / 1000.0,
                    liveTime.coerceAtLeast(0.0),
                    reading,
                    discontinuity,
                )
            val retained = pending.lastOrNull { it.ride == target && it.telemetry != null }?.telemetry
            if (
                !(if (target == live) previewCapture else telemetryCapture)
                    .prepare(evidence, retained, target)
                    .commitOriginal
            )
                return null
            return Pending(
                target,
                at,
                timestamp,
                "telemetry",
                active,
                segment,
                values.toMap(),
                identityText,
                epoch,
                acquiredAt / 1000.0,
                pendingOriginal(target, "controller", eventId),
                interruptionIndex = if (target == live) 0 else interruptionIndex,
                telemetry = evidence,
            )
        }
        val pendingCount = pending.size
        buffer(
            listOfNotNull(
                if (current == null) observation(live, liveTime, true, 0) else null,
                if (current != null && rideTime != null && rideActive != null)
                    observation(current, rideTime, rideActive, telemetrySegment)
                else null,
            )
        )
        if (pending.size == pendingCount) return
        lastTelemetry = acquiredAt
        if (current != null) {
            livePresentation =
                LivePresentation.observe(
                    LivePresentation.adopt(livePresentation, current),
                    current,
                    LiveSource.controller,
                    LiveReading(
                        LiveAcquisition(
                            eventId,
                            captureSequence,
                            Timestamps.parse(timestamp).comparisonUs,
                            captureClockEpoch,
                            acquiredAt * 1000,
                        ),
                        values,
                    ),
                    presentationClock(clockReading),
                )
            refreshNotification(snapshot(readStorage = false))
        }
        previousTelemetryClock = clockReading
        try {
            if (pending.count { current == null || it.ride != live } >= 8) flush()
        } catch (error: Exception) {
            captureFailed(error)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleEvent >= 250) {
            lastSampleEvent = now
            emit(
                "onSample",
                values +
                    mapOf(
                        "timestamp" to timestamp,
                        "elapsedSeconds" to time,
                        "acquiredAtMonotonic" to acquiredAt / 1000.0,
                        "sequence" to sequence,
                        "controllerModel" to identity.controllerModel,
                        "firmwareLabel" to identity.firmwareLabel,
                        "controllerProtocol" to identity.controllerProtocol,
                        "connectionEpoch" to epoch,
                        "interruptionIndex" to 0,
                    ),
            )
        }
    }

    private fun startLocationUpdates() {
        try {
            locations.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000,
                0f,
                locationListener,
                thread.looper,
            )
        } catch (error: SecurityException) {
            throw RideFailureException(RideFailures.classify(error, "location"), error)
        }
    }

    private val locationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) = receive(listOf(location))

            override fun onLocationChanged(locations: MutableList<Location>) = receive(locations)

            private fun receive(locations: List<Location>) {
                val id = ride ?: return
                if (
                    lifecycle.ride?.let {
                        RideLifecycle.collectionElapsed(it, clock.read().lifecycle(captureClockEpoch))
                    } == null
                ) {
                    gpsBarrier = true
                    return
                }
                val timeline =
                    GPSTimeline(
                        "gps:$gpsClockEpoch",
                        requireNotNull(lifecycle.ride).startedAt.comparisonUs,
                        startClock / 1000.0,
                        startClock / 1000.0 + (boundaries.firstOrNull()?.time ?: 0.0),
                    )
                val originals = locations.mapNotNull { location ->
                    runCatching {
                        location to
                            GPSOriginal(
                                Timestamps.parse(iso(location.time)),
                                location.latitude,
                                location.longitude,
                                location.accuracy.toDouble(),
                            )
                    }
                        .getOrElse {
                            gpsBarrier = true
                            null
                        }
                }
                val fixes = originals.mapNotNull { (location, original) ->
                    val receiptNanos = SystemClock.elapsedRealtimeNanos()
                    val age = receiptNanos - location.elapsedRealtimeNanos
                    if (age < 0 || age > 15_000_000_000L) null
                    else
                        gpsAcquisition.map(
                            original,
                            GPSReceipt(
                                original.timestamp.comparisonUs + age / 1000,
                                receiptNanos / 1_000_000_000.0,
                                (age % 1000).toInt(),
                            ),
                            timeline,
                        )
                }
                val batch = GPSMappingBatch(fixes.sortedBy { it.acquisitionMonotonic }, fixes.size != originals.size)
                if (batch.discontinuity) gpsBarrier = true
                for (fix in batch.fixes) {
                    if (!gpsAcquisition.admit(fix)) continue
                    val location = originals.first { it.second === fix.original }.first
                    val time = fix.elapsedSeconds
                    val boundary = boundaries.lastOrNull { it.time <= time } ?: continue
                    if (!boundary.active) continue
                    val values =
                        mutableMapOf(
                            "latitude" to location.latitude,
                            "longitude" to location.longitude,
                            "horizontalAccuracyM" to location.accuracy.toDouble(),
                        )
                    if (location.hasSpeed() && location.speed.isFinite()) values["speedMps"] = location.speed.toDouble()
                    val altitude = AltitudeEvidence.capture(location, altitudeConversion)
                    values.putAll(altitude.readings())
                    if (location.hasSpeedAccuracy())
                        values["speedAccuracyMps"] = location.speedAccuracyMetersPerSecond.toDouble()
                    if (location.hasBearing()) values["courseDegrees"] = location.bearing.toDouble()
                    if (location.hasBearingAccuracy())
                        values["courseAccuracyDegrees"] = location.bearingAccuracyDegrees.toDouble()
                    val acquiredAt = (fix.acquisitionMonotonic * 1000).toLong()
                    if (acquiredAt >= lastLocation) {
                        lastLocation = acquiredAt
                        lastLocationValues = values
                        gpsUnavailable = false
                    }
                    buffer(
                        listOf(
                            Pending(
                                id,
                                time,
                                iso(location.time),
                                "location",
                                boundary.active,
                                boundary.segment,
                                values,
                                "phone",
                                "gps:$gpsClockEpoch",
                                fix.acquisitionMonotonic,
                                pendingOriginal(id, "phone"),
                                altitude,
                                distanceBarrier = gpsBarrier || fix.uncertain,
                                interruptionIndex = interruptionIndex,
                            )
                        )
                    )
                    gpsBarrier = false
                }
            }

            override fun onProviderDisabled(provider: String) {
                gpsBarrier = true
                gpsUnavailable = true
            }

            override fun onProviderEnabled(provider: String) {
                gpsUnavailable = false
            }
        }

    fun flush(clockReading: RecordingClockReading = clock.read()) {
        val preview = pending.firstOrNull()?.ride?.startsWith("live-") == true
        val store = if (preview) previewStore else this.store
        val distance = if (preview) previewDistance else this.distance
        val monitor = if (preview) previewMonitor else this.monitor

        if (captureBatch.fault != null) store.persistStorage(lifecycle, captureBatch)
        val frozen = lifecycle.ride?.storageInterrupted == true
        val attempted = captureBatch.originals
        val before = lifecycle
        val owner = before.ride
        val checkpoint =
            owner
                ?.takeIf { owner ->
                    !preview &&
                        pending.any { it.time >= owner.timing.elapsedSeconds } &&
                        owner.pending == null &&
                        owner.terminal == null
                }
                ?.let {
                    val latest = pending.maxBy { sample -> sample.time }
                    val at =
                        if (frozen) it.timing
                        else
                            OwnerTiming(
                                Timestamps.parse(latest.timestamp),
                                latest.time,
                                it.timing.timerSeconds +
                                    if (it.phase == LifecyclePhase.running)
                                        (latest.time - it.timing.elapsedSeconds).coerceAtLeast(0.0)
                                    else 0.0,
                            )
                    store.checkpointLifecycle(
                        before,
                        at,
                        LifecycleClock(
                            at.timestamp,
                            it.epoch,
                            (it.elapsedOriginUs ?: clockReading.monotonicMillis * 1000) +
                                elapsedUs(at.elapsedSeconds - it.timing.elapsedSeconds),
                        ),
                        retainedCapture = frozen,
                    )
                }
        val next = checkpoint?.afterCommit(true)?.state ?: before
        val committed =
            try {
                store.transaction {
                    val rows = pending.map { p ->
                        val row =
                            store.insert(
                                p.ride,
                                p.time,
                                p.timestamp,
                                p.kind,
                                p.active,
                                p.segment,
                                p.values,
                                p.identity,
                                p.epoch,
                                p.original,
                                OriginalEvidence(
                                    p.time,
                                    p.acquiredAt,
                                    if (p.kind == "location") p.epoch else null,
                                    p.interruptionIndex,
                                ),
                                p.altitude,
                                detail =
                                    p.telemetry?.detail().orEmpty() +
                                        if (p.distanceBarrier) mapOf("distanceBarrier" to true) else emptyMap(),
                            )
                        val distanceColumn =
                            distance.append(
                                p.ride,
                                row,
                                p.time,
                                p.values,
                                p.active,
                                p.segment,
                                p.epoch,
                                p.identity,
                                p.kind == "location",
                            )
                        Triple(
                            row,
                            p,
                            p.values.keys +
                                listOfNotNull(distanceColumn) +
                                if (preview) emptySet()
                                else consumptionMetrics.intersect(store.available(p.ride).toSet()),
                        )
                    }
                    if (checkpoint?.mutation != null) store.commitLifecycle(before, checkpoint)
                    val batch = LifecycleCapture.committed(captureBatch, attempted, true).batch
                    val ack = batch.fault?.let { LifecycleStorage.acknowledged(next, batch, it.faultId, true) }
                    if (ack != null) store.persistStorage(ack.state, ack.batch)
                    if (preview && rows.isNotEmpty()) {
                        val cutoff = rows.last().second.time - 60.0
                        val removed =
                            store.writableDatabase.delete(
                                "observations",
                                "ride=? AND time<?",
                                arrayOf(live, cutoff.toString()),
                            )
                        if (removed > 0) {
                            store.writableDatabase.delete(
                                "distance_intervals",
                                "ride=? AND end_us<?",
                                arrayOf(live, elapsedUs(cutoff).toString()),
                            )
                            store.analytics.trimBefore(live, cutoff)
                        }
                    }
                    rows
                }
            } catch (error: Exception) {
                captureBatch = LifecycleCapture.committed(captureBatch, attempted, false).batch
                if (!storageBusy(error)) captureFailed(error)
                throw error
            }
        captureBatch = LifecycleCapture.committed(captureBatch, attempted, true).batch
        val ack = captureBatch.fault?.let { LifecycleStorage.acknowledged(next, captureBatch, it.faultId, true) }
        if (ack != null) {
            captureBatch = ack.batch
            adoptLifecycle(ack.state)
        } else adoptLifecycle(next)
        pending.clear()
        committed.forEach { (row, p, metrics) ->
            p.telemetry?.let { (if (preview) previewCapture else telemetryCapture).committed(it) }
            monitor.committedLiveObservation(p.ride, row, p.acquiredAt, metrics)
        }
    }

    private fun storageBusy(error: Exception) =
        error is android.database.sqlite.SQLiteDatabaseLockedException ||
            (error as? expo.modules.kotlin.exception.CodedException)?.code == "ERR_STORAGE_BUSY"

    private fun captureFailed(error: Exception) =
        captureFailed(
            error,
            if (
                error is android.database.sqlite.SQLiteException ||
                    error is expo.modules.kotlin.exception.CodedException
            )
                LifecycleStorageError.sqlite
            else if (error is java.io.IOException) LifecycleStorageError.posix else LifecycleStorageError.operation,
        )

    private fun captureFailed(error: Exception, kind: LifecycleStorageError) {
        val fault =
            captureBatch.fault
                ?: CaptureFault(
                    java.util.UUID.randomUUID().toString(),
                    lifecycle.ride?.key,
                    Timestamps.parse(clock.read().timestamp),
                    pending.firstOrNull()?.original?.eventId,
                    if (kind == LifecycleStorageError.captureInterrupted) RideFailure.recordingInterrupted.name
                    else RideFailures.classify(error, "capture").name,
                )
        val failed =
            LifecycleStorage.failed(lifecycle, captureBatch, fault, kind, clock.read().lifecycle(captureClockEpoch))
        captureBatch = failed.batch
        adoptLifecycle(failed.state)
        if (failed.state.ride == null) rideError = fault.error
        if (failed.haltCapture) {
            gpsActive = false
            locations.removeUpdates(locationListener)
            if (bluetooth.state["status"] == "reconnecting") bluetooth.disconnect()
            runCatching { store.persistStorage(lifecycle, captureBatch) }
        }
        refreshControlIdentity()
        publishSnapshot(faultSnapshot())
    }

    fun checkBike(id: String) {
        check(ride == null || rideDevice == null || rideDevice == id) {
            "Finish this ride before connecting a different bike."
        }
        if (ride != null) rideDevice = id
    }

    fun awaitReady() {
        check(ready.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            "Ride storage did not open."
        }
        initializationError?.let { throw it }
    }

    fun monitorQuery(input: MonitorInput): Payload {
        val id = source(input.target)
        return (if (id.startsWith("live-")) previewMonitor else monitor).query(id, input)
    }

    fun source(target: MonitorTarget) =
        when (target) {
            MonitorTarget.Live -> ride ?: live
            is MonitorTarget.Workout -> target.id
        }

    fun connect(input: ConnectInput, completion: (Throwable?) -> Unit) {
        bluetooth.validateConnect(input.deviceId, input.sampleHz)
        checkBike(input.deviceId)
        if (!bluetooth.connectedTo(input.deviceId)) startLiveSession()
        bluetooth.connect(input.deviceId, input.sampleHz, completion)
    }

    private fun startLiveSession() {
        flush()
        distance.forget(live)
        val reading = clock.read()
        live = previewStore.replaceLive(reading)
        liveStart = reading.monotonicMillis
        telemetryCapture.reset()
        captureSession = live.removePrefix("live-")
        captureSequence = 0L
        previousTelemetryClock = null
        sequence = 0L
        lastTelemetry = 0L
        if (ride == null) previewMonitor.selectLiveRide(live)
    }

    private fun monitorOriginSeconds(id: String): Double? {
        val current = ride
        val origin =
            when (id) {
                current -> startClock
                live -> liveStart
                else -> return null
            }
        if (current != ride) return null
        return origin / 1000.0
    }

    private fun recoveryIndex(id: String): Int =
        store.select("SELECT count(*) FROM lifecycle WHERE ride=? AND interrupted=1", arrayOf(id)) {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun restoreLocalCheckpoint() {
        adoptLifecycle(store.lifecycleState())
        val owner = lifecycle.ride ?: return
        captureBatch = CaptureBatch()
        if (owner.phase == LifecyclePhase.interrupted) {
            interruptionIndex = recoveryIndex(owner.key.rideId)
            monitor.selectLiveRide(owner.key.rideId)
        }
        refreshNotification()
    }

    init {
        handler.post {
            try {
                val restarted = store.recoverOrphans(clock.read().lifecycle(captureClockEpoch))
                adoptLifecycle(restarted.state)
                startLiveSession()
                restoreLocalCheckpoint()
                removeFailedStart()
                restarted.effects.forEach { executeEffect(it) }
                initialized = true
                tick.run()
                store.pendingHealthJobs(includeBlocked = true).forEach { queueHealth(it) }
            } catch (error: Exception) {
                initializationError = error
            } finally {
                ready.countDown()
            }
        }
    }

    fun emit(event: String, value: Payload) {
        listeners.forEach { runCatching { it(event, value) } }
    }

    companion object {
        @Volatile private var instance: RecordingEngine? = null

        fun workChanged() {
            instance?.let { engine ->
                if (engine.workPublishPending.compareAndSet(false, true))
                    engine.handler.post {
                        engine.workPublishPending.set(false)
                        if (engine.initialized) {
                            val snapshot = engine.snapshot(readStorage = false)
                            if (snapshot.readWorkoutIds != engine.publishedReadWorkoutIds) {
                                engine.publishedSnapshot = snapshot
                                engine.publishSnapshot(snapshot)
                            }
                        }
                    }
            }
        }

        fun healthBusyChanged() {
            instance?.let { engine -> engine.handler.post { if (engine.initialized) engine.publish() } }
        }

        fun healthCompleted() {
            instance?.let { engine ->
                engine.handler.post {
                    if (engine.initialized) {
                        try {
                            engine.publish()
                        } catch (error: Exception) {
                            engine.captureFailed(error)
                        }
                    }
                }
            }
        }

        fun get(
            context: Context,
            clock: RecordingClock = SystemRecordingClock,
            healthAccess: HealthConnectAccess = AndroidHealthConnectAccess(context),
            healthScheduler: (String, Long?, Boolean) -> Unit = { id, resume, replace ->
                HealthPublicationWorker.schedule(
                    context,
                    id,
                    resume,
                    if (replace) androidx.work.ExistingWorkPolicy.REPLACE else androidx.work.ExistingWorkPolicy.KEEP,
                )
            },
        ): RecordingEngine =
            instance
                ?: synchronized(this) {
                    instance
                        ?: RecordingEngine(
                                context.applicationContext,
                                clock,
                                healthAccess,
                                healthScheduler,
                            )
                            .also {
                                instance = it
                            }
                }
    }
}
