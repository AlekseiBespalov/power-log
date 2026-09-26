package app.powerlog.bridge

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File

class CycBridgeModule : Module() {
    private var healthContinuation: (() -> Unit)? = null
    private val engine
        get() = RecordingEngine.get(requireNotNull(appContext.reactContext))

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
            if (notifications && Build.VERSION.SDK_INT >= 33)
                add(Manifest.permission.POST_NOTIFICATIONS)
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

    private fun ridePermissions(options: Payload, promise: Promise, after: () -> Any?) {
        val gps = options.flag("recordGPS", !options.flag("indoor"))
        if (!options.flag("saveToHealth")) {
            permissions(gps, true, promise, after)
            return
        }
        engine.reads.execute {
            try {
                engine.awaitReady()
                check(engine.health.available) { "Health Connect is unavailable." }
                val required = engine.health.permissions(gps)
                if (engine.health.granted().containsAll(required)) {
                    permissions(gps, true, promise, after)
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
                    healthContinuation = { permissions(gps, true, promise, after) }
                    try {
                        activity.startActivityForResult(
                            Intent(activity, HealthPermissionsActivity::class.java)
                                .putExtra("gps", gps),
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

    override fun definition() = ModuleDefinition {
        Name("CycBridge")
        Events("onDevice", "onState", "onSample", "onWorkoutState")
        OnCreate { engine.listeners.add(listener) }
        OnDestroy {
            engine.listeners.remove(listener)
            healthContinuation = null
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
        AsyncFunction("getDiagnostics") { promise: Promise ->
            submit(promise) { engine.bluetooth.diagnostics() }
        }
        AsyncFunction("readDiagnostics") { promise: Promise ->
            submit(promise) { json(engine.bluetooth.diagnostics()).toString() }
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
            engine.handler.post {
                try {
                    engine.awaitReady()
                    engine.checkBike(options.str("deviceId"))
                    engine.bluetooth.connect(
                        options.str("deviceId"),
                        options.num("hz", 2.0).toInt(),
                    ) { error ->
                        if (error == null) promise.resolve(null)
                        else promise.reject("E_BLUETOOTH", error.message, error)
                    }
                } catch (error: Exception) {
                    promise.reject("E_BLUETOOTH", error.message, error)
                }
            }
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
        AsyncFunction("requestWorkoutPermissions") { promise: Promise ->
            permissions(true, true, promise) { engine.permissionStatus() }
        }
        AsyncFunction("requestWorkoutPermissionsForOptions") {
            options: Map<String, Any?>,
            promise: Promise ->
            ridePermissions(options, promise) { engine.permissionStatus() }
        }
        AsyncFunction("startWorkout") { options: Map<String, Any?>, promise: Promise ->
            ridePermissions(options, promise) { engine.start(options) }
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
            submit(promise, true) { engine.store.list(options) }
        }
        AsyncFunction("readWorkout") { id: String, source: String?, promise: Promise ->
            submit(promise, true) {
                RideExport(engine.context, engine.store, engine.distance, engine.monitor)
                    .detail(id, source ?: "auto")
            }
        }
        AsyncFunction("exportWorkout") { id: String, source: String?, promise: Promise ->
            submit(promise, true) {
                engine.store.withSavedRide(id) {
                    RideExport(engine.context, engine.store, engine.distance, engine.monitor)
                        .fit(id, source ?: "auto")
                }
            }
        }
        AsyncFunction("exportWorkoutArchive") { id: String, promise: Promise ->
            submit(promise, true) {
                engine.store.withSavedRide(id) {
                    RideExport(engine.context, engine.store, engine.distance, engine.monitor)
                        .archive(id)
                }
            }
        }
        listOf(
                "describeMonitorSource" to "describe",
                "readMonitorLatest" to "latest",
                "readMonitorPlot" to "plot",
                "inspectMonitorAt" to "inspect",
                "readMonitorRangeStats" to "stats",
                "monitorChangesSince" to "changes",
            )
            .forEach { (name, kind) ->
                AsyncFunction(name) { options: Map<String, Any?>, promise: Promise ->
                    submit(promise, true) {
                        engine.monitor.query(kind, engine.source(options), options)
                    }
                }
            }
        AsyncFunction("shareFile") { uri: String, promise: Promise ->
            try {
                val context = requireNotNull(appContext.reactContext)
                val source = File(requireNotNull(Uri.parse(uri).path)).canonicalFile
                val cache = context.cacheDir.canonicalFile
                require(source.path.startsWith(cache.path + File.separator) && source.isFile) {
                    "Export file is unavailable."
                }
                val exported = File(File(cache, "exports").apply { mkdirs() }, source.name)
                if (source != exported) source.copyTo(exported, true)
                val shared =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.powerlog.files",
                        exported,
                    )
                val intent =
                    Intent(Intent.ACTION_SEND)
                        .setType(
                            if (exported.extension == "csv") "text/csv"
                            else "application/octet-stream"
                        )
                        .putExtra(Intent.EXTRA_STREAM, shared)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                requireNotNull(appContext.currentActivity)
                    .startActivity(Intent.createChooser(intent, "Export ride"))
                promise.resolve(null)
            } catch (error: Exception) {
                promise.reject("E_EXPORT", error.message, error)
            }
        }
    }
}
