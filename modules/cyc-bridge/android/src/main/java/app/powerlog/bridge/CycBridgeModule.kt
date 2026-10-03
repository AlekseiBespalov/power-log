package app.powerlog.bridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class CycBridgeModule : Module() {
    private var healthContinuation: (() -> Unit)? = null
    private val engine
        get() = RecordingEngine.get(requireNotNull(appContext.reactContext))

    private val exportSource by lazy { ExportSource.create(engine) }
    private val exportSink by lazy { ExportSink.create(engine.context) }

    private val listener: (String, Payload) -> Unit = { name, value -> sendEvent(name, value) }

    private fun submit(promise: Promise, read: Boolean = false, body: () -> Any?) {
        val job = Runnable {
            try {
                engine.awaitReady()
                promise.resolve(body())
            } catch (error: Exception) {
                promise.reject("E_POWER_LOG", error.message ?: "Power Log operation failed", error)
            }
        }
        if (read) engine.reads.execute(job) else engine.handler.post(job)
    }

    private fun <T> read(input: Payload, promise: Promise, parse: (Payload) -> T, body: (T) -> Any?) {
        val request =
            try {
                parse(input)
            } catch (error: Exception) {
                promise.reject("E_POWER_LOG", error.message, error)
                return
            }
        submit(promise, true) { body(request) }
    }

    private fun permissions(
        gps: Boolean,
        notifications: Boolean,
        promise: Promise,
        after: () -> Any?,
    ) {
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
        val manager =
            appContext.permissions
                ?: run {
                    promise.reject("E_PERMISSIONS", "Permissions are unavailable", null)
                    return
                }
        if (gps)
            appContext.reactContext
                ?.getSharedPreferences("permissions", 0)
                ?.edit()
                ?.putBoolean("locationRequested", true)
                ?.apply()
        manager.askForPermissions({ submit(promise, body = after) }, *required.toTypedArray())
    }

    private fun ridePermissions(
        input: Payload,
        promise: Promise,
        requestHealth: Boolean = true,
        after: (RideOptions) -> Any?,
    ) {
        val options =
            try {
                BridgeInputs.ride(input)
            } catch (error: Exception) {
                promise.reject("E_PERMISSIONS", error.message, error)
                return
            }
        val effective = options.effective(engine.capabilities())
        val gps = effective.recordGPS
        val continueRequest = { after(options) }
        if (!effective.saveToHealth || !requestHealth) {
            permissions(gps, true, promise, continueRequest)
            return
        }
        engine.reads.execute {
            try {
                engine.awaitReady()
                check(engine.health.available) { "Health Connect is unavailable." }
                val required = engine.health.permissions(gps)
                if (engine.health.granted().containsAll(required)) {
                    permissions(gps, true, promise, continueRequest)
                    return@execute
                }
                val activity = appContext.throwingActivity
                activity.runOnUiThread {
                    if (healthContinuation != null) {
                        promise.reject(
                            "E_PERMISSIONS",
                            "A permission request is already open",
                            null,
                        )
                        return@runOnUiThread
                    }
                    healthContinuation = { permissions(gps, true, promise, continueRequest) }
                    try {
                        activity.startActivityForResult(
                            Intent(activity, HealthPermissionsActivity::class.java).putExtra("gps", gps),
                            8642,
                        )
                    } catch (error: Exception) {
                        healthContinuation = null
                        promise.reject("E_PERMISSIONS", error.message, error)
                    }
                }
            } catch (error: Exception) {
                promise.reject("E_PERMISSIONS", error.message, error)
            }
        }
    }

    private fun connect(options: Payload, promise: Promise) {
        val input =
            try {
                BridgeInputs.connect(options)
            } catch (error: Exception) {
                promise.reject("E_BLUETOOTH", error.message, error)
                return
            }
        engine.handler.post {
            try {
                engine.awaitReady()
                engine.connect(input) { error ->
                    if (error == null) promise.resolve(null) else promise.reject("E_BLUETOOTH", error.message, error)
                }
            } catch (error: Exception) {
                promise.reject("E_BLUETOOTH", error.message, error)
            }
        }
    }

    override fun definition() = ModuleDefinition {
        Name("CycBridge")
        Events("onDevice", "onState", "onSample", "onWorkoutState")
        OnCreate {
            engine.listeners.add(listener)
            exportSink.cleanup()
        }
        OnDestroy {
            engine.listeners.remove(listener)
            healthContinuation = null
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
        AsyncFunction("getState") { promise: Promise -> submit(promise) { engine.bluetooth.state } }
        AsyncFunction("getMonotonicSeconds") { SystemClock.elapsedRealtime() / 1000.0 }
        AsyncFunction("getDiagnostics") { promise: Promise ->
            submit(promise) { engine.bluetooth.diagnostics() }
        }
        AsyncFunction("startScan") { promise: Promise ->
            permissions(false, false, promise) {
                engine.bluetooth.startScan()
                null
            }
        }
        AsyncFunction("stopScan") { promise: Promise ->
            submit(promise) {
                engine.bluetooth.stopScan()
                null
            }
        }
        AsyncFunction("connect") { options: Map<String, Any?>, promise: Promise ->
            connect(options, promise)
        }
        AsyncFunction("disconnect") { promise: Promise ->
            submit(promise) {
                engine.bluetooth.disconnect()
                null
            }
        }
        AsyncFunction("getWorkoutState") { promise: Promise -> submit(promise) { engine.state() } }
        AsyncFunction("getWorkoutPermissions") { promise: Promise ->
            submit(promise) { engine.permissionStatus() }
        }
        AsyncFunction("requestWorkoutPermissions") { options: Map<String, Any?>, promise: Promise ->
            ridePermissions(options, promise) { engine.permissionStatus() }
        }
        AsyncFunction("startWorkout") { options: Map<String, Any?>, promise: Promise ->
            ridePermissions(options, promise, requestHealth = false) { input -> engine.start(input) }
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
            submit(promise) { engine.action("stop", id) }
        }
        AsyncFunction("discardWorkout") { id: String, promise: Promise ->
            submit(promise) { engine.action("discard", id) }
        }
        AsyncFunction("deleteWorkout") { id: String, promise: Promise ->
            submit(promise) { engine.delete(id) }
        }
        AsyncFunction("recoverWorkout") { id: String, promise: Promise ->
            submit(promise) { engine.recover(id) }
        }
        AsyncFunction("listWorkouts") { options: Map<String, Any?>, promise: Promise ->
            read(options, promise, BridgeInputs::catalog) { engine.store.list(it) }
        }
        AsyncFunction("readWorkout") { id: String, source: String?, promise: Promise ->
            submit(promise, true) {
                RideDetail(engine.store, engine.distance, engine.monitor)
                    .detail(id, BridgeInputs.distanceSource(source ?: "auto").wire)
            }
        }
        AsyncFunction("exportOpen") { request: Map<String, Any?>, promise: Promise ->
            exportSource.open(request, promise)
        }
        AsyncFunction("exportPage") { request: Map<String, Any?>, promise: Promise ->
            exportSource.page(request, promise)
        }
        AsyncFunction("exportClose") { session: String, promise: Promise -> exportSource.close(session, promise) }
        AsyncFunction("sinkOpen") { kind: String, context: Map<String, Any?>, promise: Promise ->
            exportSink.open(kind, context, promise)
        }
        AsyncFunction("sinkWrite") { id: String, bytes: ByteArray, promise: Promise ->
            exportSink.write(id, bytes, promise)
        }
        AsyncFunction("sinkWriteAt") { id: String, offset: Double, bytes: ByteArray, promise: Promise ->
            exportSink.writeAt(id, offset, bytes, promise)
        }
        AsyncFunction("sinkBeginDeflate") { id: String, promise: Promise -> exportSink.beginDeflate(id, promise) }
        AsyncFunction("sinkEndDeflate") { id: String, promise: Promise -> exportSink.endDeflate(id, promise) }
        AsyncFunction("sinkCommit") { id: String, name: String, promise: Promise ->
            exportSink.commit(id, name, promise)
        }
        AsyncFunction("sinkAbort") { id: String, promise: Promise -> exportSink.abort(id, promise) }
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
                    read(options, promise, { BridgeInputs.monitor(kind, it) }) { input ->
                        engine.monitor.query(engine.source(input.target), input)
                    }
                }
            }
        AsyncFunction("shareFile") { uri: String, promise: Promise ->
            try {
                val context = requireNotNull(appContext.reactContext)
                val shared = ExportSharing.shareable(context, uri)
                val intent =
                    Intent(Intent.ACTION_SEND)
                        .setType(ExportSharing.mimeType(shared))
                        .putExtra(Intent.EXTRA_STREAM, shared)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                requireNotNull(appContext.currentActivity).startActivity(Intent.createChooser(intent, "Export ride"))
                promise.resolve(null)
            } catch (error: Exception) {
                promise.reject("E_EXPORT", error.message, error)
            }
        }
    }
}
