package app.powerlog.bridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import app.powerlog.core.presentation.ExternalControlRequest
import app.powerlog.core.presentation.RideFailure
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.concurrent.RejectedExecutionException

internal fun submit(promise: Promise, dispatch: (Runnable) -> Unit, stage: String? = null, body: () -> Any?) {
    val job = Runnable {
        try {
            promise.resolve(body())
        } catch (error: Throwable) {
            promise.rejectFailure(error, stage)
        }
    }
    try {
        dispatch(job)
    } catch (error: RejectedExecutionException) {
        promise.rejectFailure(
            CodedException("ERR_READ_DEFERRED", "Read capacity is temporarily unavailable.", error),
            stage,
        )
    } catch (error: Throwable) {
        promise.rejectFailure(error, stage)
    }
}

private inline fun guard(promise: Promise, stage: String? = null, body: () -> Unit) {
    try {
        body()
    } catch (error: Throwable) {
        promise.rejectFailure(error, stage)
    }
}

class PowerLogBridgeModule internal constructor(private val engineProvider: (() -> RecordingEngine)?) : Module() {
    constructor() : this(null)

    private var healthContinuation: (() -> Unit)? = null
    private val summaryOwner = java.util.UUID.randomUUID().toString()
    private val engine
        get() = engineProvider?.invoke() ?: RecordingEngine.get(requireNotNull(appContext.reactContext))

    private val exportSource by lazy { ExportSource.create(engine) }
    private val exportSink by lazy { ExportSink.create(engine.context) }

    private val listener: (String, Payload) -> Unit = { name, value -> sendEvent(name, value) }

    private fun submit(promise: Promise, stage: String? = null, read: Boolean = false, body: () -> Any?) {
        submit(
            promise,
            { job ->
                if (read) engine.reads.execute(job) else engine.handler.post(job)
            },
            stage,
        ) {
            engine.awaitReady()
            body()
        }
    }

    private fun <T> read(input: Payload, promise: Promise, parse: (Payload) -> T, body: (T) -> Any?) =
        guard(promise, "read") {
            val request = parse(input)
            submit(promise, "read", true) { body(request) }
        }

    private fun permissions(
        gps: Boolean,
        notifications: Boolean,
        promise: Promise,
        stage: String?,
        after: () -> Any?,
    ) =
        guard(promise, stage) {
            val required = buildList {
                if (Build.VERSION.SDK_INT >= 31) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (gps || Build.VERSION.SDK_INT < 31) {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (notifications && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val manager = appContext.permissions ?: throw IllegalStateException("Permissions are unavailable.")
            if (gps)
                appContext.reactContext
                    ?.getSharedPreferences("permissions", 0)
                    ?.edit()
                    ?.putBoolean("locationRequested", true)
                    ?.apply()
            manager.askForPermissions(
                { submit(promise, stage, read = stage == "health", body = after) },
                *required.toTypedArray(),
            )
        }

    private fun ridePermissions(
        input: Payload,
        promise: Promise,
        stage: String,
        requestHealth: Boolean = true,
        after: (RideOptions) -> Any?,
    ) =
        guard(promise, stage) {
            val options = BridgeInputs.ride(input)
            val effective = options.effective(engine.capabilities())
            val gps = effective.recordGPS
            val continueRequest = { after(options) }
            if (!effective.saveToHealth || !requestHealth) permissions(gps, true, promise, stage, continueRequest)
            else engine.reads.execute { healthPermissions(gps, promise, stage, continueRequest) }
        }

    private fun healthPermissions(gps: Boolean, promise: Promise, stage: String, after: () -> Any?) =
        guard(promise, stage) {
            engine.awaitReady()
            if (!engine.health.available) throw RideFailureException(RideFailure.healthDenied)
            if (engine.health.granted().containsAll(engine.health.permissions(gps)))
                permissions(gps, true, promise, stage, after)
            else {
                val activity = appContext.throwingActivity
                activity.runOnUiThread {
                    guard(promise, stage) {
                        if (healthContinuation != null) throw RideFailureException(RideFailure.actionPending)
                        healthContinuation = { permissions(gps, true, promise, stage, after) }
                        try {
                            activity.startActivityForResult(
                                Intent(activity, HealthPermissionsActivity::class.java).putExtra("gps", gps),
                                8642,
                            )
                        } catch (error: Throwable) {
                            healthContinuation = null
                            throw error
                        }
                    }
                }
            }
        }

    private fun connect(options: Payload, promise: Promise) =
        guard(promise, "bike") {
            val input = BridgeInputs.connect(options)
            engine.handler.post {
                guard(promise, "bike") {
                    engine.awaitReady()
                    engine.connect(input) { error ->
                        if (error == null) promise.resolve(null) else promise.rejectFailure(error, "bike")
                    }
                }
            }
        }

    internal fun notificationIntent(intent: Intent?) {
        val request = intent?.let(RideNotificationAdapter::request) ?: return
        if (request.action != "finish") return
        intent.removeExtra("action")
        engine.handler.post {
            runCatching {
                engine.awaitReady()
                engine.externalAction(request, false)
            }
        }
    }

    private fun externalRequest(input: Payload) =
        ExternalControlRequest(
            requireNotNull(input["rideId"] as? String),
            requireNotNull(input["controlToken"] as? String),
            requireNotNull(input["expectedPhase"] as? String),
            requireNotNull(input["action"] as? String),
        )

    override fun definition() = ModuleDefinition {
        Name("PowerLogBridge")
        Events("onDevice", "onState", "onSample", "onWorkoutState")
        OnCreate {
            engine.listeners.add(listener)
            notificationIntent(appContext.currentActivity?.intent)
            exportSink.cleanup()
        }
        OnNewIntent { intent -> notificationIntent(intent) }
        OnActivityEntersForeground {
            engine.foreground()
            notificationIntent(appContext.currentActivity?.intent)
        }
        AsyncFunction("finishExternalWorkoutAction") { input: Payload, discard: Boolean, promise: Promise ->
            submit(promise, "finish") { engine.externalAction(externalRequest(input), true, discard) }
        }
        AsyncFunction("cancelExternalWorkoutAction") { input: Payload, promise: Promise ->
            submit(promise) { engine.cancelExternalAction(externalRequest(input)) }
        }
        OnDestroy {
            engine.listeners.remove(listener)
            healthContinuation = null
            engine.summaries.cancelOwner(summaryOwner)
            engine.monitorReads.cancelOwner(summaryOwner)
            exportSource.closeAll()
            exportSink.abortAll()
        }
        OnActivityResult { _, (requestCode, _, _) ->
            if (requestCode == 8642) {
                val continuation = healthContinuation
                healthContinuation = null
                continuation?.invoke()
            }
        }
        View(MonitorRasterView::class) {
            Events("onRenderStatus")
            Prop("sourceId") { view: MonitorRasterView, value: String -> view.source(value) }
            Prop("sceneKey") { view: MonitorRasterView, value: String -> view.key(value) }
            Prop("scene") { view: MonitorRasterView, value: String -> view.scene(value) }
            Prop("presentation") { view: MonitorRasterView, value: List<Double> ->
                view.presentation(value)
            }
            Prop("selection") { view: MonitorRasterView, value: String -> view.selection(value) }
            Prop("selectionTarget") { view: MonitorRasterView, value: String -> view.target(value) }
        }
        AsyncFunction("getState") { promise: Promise -> submit(promise, "read") { engine.bluetooth.state } }
        AsyncFunction("getMonotonicSeconds") { SystemClock.elapsedRealtime() / 1000.0 }
        AsyncFunction("getDiagnostics") { promise: Promise ->
            submit(promise, "read") { engine.bluetooth.diagnostics() }
        }
        AsyncFunction("startScan") { promise: Promise ->
            permissions(false, false, promise, "bike") {
                engine.bluetooth.startScan()
                null
            }
        }
        AsyncFunction("stopScan") { promise: Promise ->
            submit(promise, "bike") {
                engine.bluetooth.stopScan()
                null
            }
        }
        AsyncFunction("connect") { options: Map<String, Any?>, promise: Promise ->
            connect(options, promise)
        }
        AsyncFunction("disconnect") { promise: Promise ->
            submit(promise, "bike") {
                engine.bluetooth.disconnect()
                null
            }
        }
        AsyncFunction("getWorkoutState") { promise: Promise -> submit(promise, "read") { engine.state() } }
        AsyncFunction("getWorkoutPermissions") { promise: Promise ->
            submit(promise, "read", read = true) { engine.permissionStatus() }
        }
        AsyncFunction("requestWorkoutPermissions") { options: Map<String, Any?>, promise: Promise ->
            ridePermissions(options, promise, "health") { engine.permissionStatus() }
        }
        AsyncFunction("startWorkout") { options: Map<String, Any?>, promise: Promise ->
            guard(promise, "start") {
                val input = BridgeInputs.ride(options)
                engine.reads.execute {
                    guard(promise, "start") {
                        engine.awaitReady()
                        val effective = input.effective(engine.capabilities())
                        val grants = if (effective.saveToHealth) engine.health.granted() else emptySet()
                        permissions(effective.recordGPS, true, promise, "start") { engine.start(input, grants) }
                    }
                }
            }
        }
        AsyncFunction("pauseWorkout") { id: String?, promise: Promise ->
            submit(promise) { engine.action("pause", id) }
        }
        AsyncFunction("resumeWorkout") { id: String?, promise: Promise ->
            submit(promise) { engine.action("resume", id) }
        }
        AsyncFunction("markWorkoutLap") { id: String?, promise: Promise ->
            submit(promise) { engine.action("lap", id) }
        }
        AsyncFunction("stopWorkout") { id: String?, promise: Promise ->
            submit(promise, "finish") { engine.action("stop", id) }
        }
        AsyncFunction("discardWorkout") { id: String, promise: Promise ->
            submit(promise) { engine.action("discard", id) }
        }
        AsyncFunction("deleteWorkout") { id: String, promise: Promise ->
            submit(promise) { engine.delete(id) }
        }
        AsyncFunction("refreshWorkoutHealth") { id: String, promise: Promise ->
            submit(promise) { engine.refreshHealth(id) }
        }
        AsyncFunction("retryWorkoutHealthSave") { id: String, promise: Promise ->
            submit(promise) { engine.refreshHealth(id, retrySave = true) }
        }
        AsyncFunction("listWorkouts") { options: Map<String, Any?>, promise: Promise ->
            read(options, promise, BridgeInputs::catalog) { engine.store.list(it) }
        }
        AsyncFunction("readWorkout") { id: String, source: String?, cancellationId: String?, promise: Promise ->
            guard(promise, "read") {
                engine.summaries.submit(
                    summaryOwner,
                    cancellationId ?: java.util.UUID.randomUUID().toString(),
                    promise,
                ) {
                    engine.awaitReady()
                    RideDetail(engine.store, engine.distance)
                        .detail(id, BridgeInputs.distanceSource(source ?: "auto").wire)
                }
            }
        }
        AsyncFunction("cancelWorkoutRead") { cancellationId: String, promise: Promise ->
            guard(promise, "read") {
                engine.summaries.cancel(cancellationId)
                promise.resolve(null)
            }
        }
        AsyncFunction("exportOpen") { request: Map<String, Any?>, promise: Promise ->
            guard(promise) { exportSource.open(request, promise) }
        }
        AsyncFunction("exportPage") { request: Map<String, Any?>, promise: Promise ->
            guard(promise) { exportSource.page(request, promise) }
        }
        AsyncFunction("exportClose") { session: String, promise: Promise ->
            guard(promise) { exportSource.close(session, promise) }
        }
        AsyncFunction("sinkOpen") { kind: String, context: Map<String, Any?>, promise: Promise ->
            guard(promise) { exportSink.open(kind, context, promise) }
        }
        AsyncFunction("sinkWrite") { id: String, bytes: ByteArray, promise: Promise ->
            guard(promise) { exportSink.write(id, bytes, promise) }
        }
        AsyncFunction("sinkWriteAt") { id: String, offset: Double, bytes: ByteArray, promise: Promise ->
            guard(promise) { exportSink.writeAt(id, offset, bytes, promise) }
        }
        AsyncFunction("sinkBeginDeflate") { id: String, promise: Promise ->
            guard(promise) { exportSink.beginDeflate(id, promise) }
        }
        AsyncFunction("sinkEndDeflate") { id: String, promise: Promise ->
            guard(promise) { exportSink.endDeflate(id, promise) }
        }
        AsyncFunction("sinkCommit") { id: String, name: String, promise: Promise ->
            guard(promise) { exportSink.commit(id, name, promise) }
        }
        AsyncFunction("sinkAbort") { id: String, promise: Promise -> guard(promise) { exportSink.abort(id, promise) } }
        listOf(
                "describeMonitorSource" to MonitorOperation.Describe,
                "readMonitorLatest" to MonitorOperation.Latest,
                "readMonitorPlot" to MonitorOperation.Plot,
                "inspectMonitorAt" to MonitorOperation.Inspect,
                "readMonitorRangeStats" to MonitorOperation.Stats,
                "monitorChangesSince" to MonitorOperation.Changes,
            )
            .forEach { (name, kind) ->
                AsyncFunction(name) { options: Map<String, Any?>, promise: Promise ->
                    guard(promise, "read") {
                        val input = BridgeInputs.monitor(kind, options)
                        val cancellationId =
                            options["cancellationId"] as? String ?: java.util.UUID.randomUUID().toString()
                        engine.monitorReads.submit(summaryOwner, cancellationId, kind, promise) {
                            engine.awaitReady()
                            engine.monitorQuery(input)
                        }
                    }
                }
            }
        AsyncFunction("cancelMonitorRead") { cancellationId: String, promise: Promise ->
            guard(promise, "read") {
                engine.monitorReads.cancel(cancellationId)
                promise.resolve(null)
            }
        }
        AsyncFunction("shareFile") { uri: String, promise: Promise ->
            guard(promise) {
                val context = requireNotNull(appContext.reactContext)
                val shared = ExportSharing.shareable(context, uri)
                val intent =
                    Intent(Intent.ACTION_SEND)
                        .setType(ExportSharing.mimeType(shared))
                        .putExtra(Intent.EXTRA_STREAM, shared)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                requireNotNull(appContext.currentActivity).startActivity(Intent.createChooser(intent, "Export ride"))
                promise.resolve(null)
            }
        }
    }
}
