@file:Suppress("MissingPermission")

package app.powerlog.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal class RecordingEngine private constructor(val context: Context) {
    private val thread = HandlerThread("PowerLogCapture").apply { start() }
    val handler = Handler(thread.looper)
    val reads = Executors.newSingleThreadExecutor { task -> Thread(task, "PowerLogReads") }
    val store = RideStore(context)
    val health = HealthExport(context, store)
    private val healthWork = Executors.newSingleThreadExecutor()
    private val healthPending = mutableSetOf<String>()
    val distance = RideDistance(store)
    val monitor = RideMonitor(store, distance)
    val listeners = CopyOnWriteArrayList<(String, Payload) -> Unit>()
    private val locations = context.getSystemService(LocationManager::class.java)
    private var live = ""
    private var liveStart = SystemClock.elapsedRealtime()
    @Volatile private var ride: String? = null
    private var phase = "idle"
    private var options: Payload = emptyMap()
    private var startClock = 0L
    private var activeSince = 0L
    private var activeSeconds = 0.0
    private var segment = 0
    private var telemetrySegment = 0
    private var rideDevice: String? = null
    private var historyRevision = 0L
    private var lastDeleted: String? = null
    private var lastValues: Map<String, Double> = emptyMap()
    private var lastTelemetry = 0L
    private var lastLocation = 0L
    private var lastEpoch = ""
    private val ready = java.util.concurrent.CountDownLatch(1)
    @Volatile private var initializationError: Exception? = null
    private var sequence = 0L
    private var rideError: String? = null
    private var lastSampleEvent = 0L
    private var lastLocationValues: Map<String, Double> = emptyMap()
    private var wake: PowerManager.WakeLock? = null

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
    )

    private val pending = mutableListOf<Pending>()
    val bluetooth = CycBluetooth(context, handler, ::emit, ::telemetry) { ride != null }
    @Volatile
    var notificationState: Payload = emptyMap()
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
                    ride?.let { id ->
                        store.update(id, phase, elapsed(), timer())
                        publish()
                    }
                } catch (error: Exception) {
                    captureFailed(error)
                }
                handler.postDelayed(this, 1000)
            }
        }

    private fun elapsed() =
        if (ride == null) 0.0 else (SystemClock.elapsedRealtime() - startClock) / 1000.0

    private fun timer() =
        activeSeconds +
            if (phase == "running") (SystemClock.elapsedRealtime() - activeSince) / 1000.0 else 0.0

    fun state(): Payload {
        val now = SystemClock.elapsedRealtime()
        val fresh = now - lastTelemetry <= 2500 && lastTelemetry != 0L
        val gpsFresh = now - lastLocation <= 10000 && lastLocation != 0L
        val id = ride
        val distanceInfo = id?.let { distance.info(it, "auto") }
        return mapOf(
            "supported" to true,
            "capabilities" to
                mapOf(
                    "phoneWorkout" to true,
                    "watchWorkout" to false,
                    "healthKit" to false,
                    "healthConnect" to health.available,
                    "gps" to true,
                    "foregroundOnly" to false,
                ),
            "id" to id,
            "phase" to phase,
            "historyRevision" to historyRevision.toString(),
            "lastDeletedWorkoutId" to lastDeleted,
            "startedAt" to id?.let { store.metadata(it)["startedAt"] },
            "indoor" to options.flag("indoor"),
            "useWatch" to false,
            "saveToHealth" to options.flag("saveToHealth"),
            "recordGPS" to options.flag("recordGPS"),
            "elapsedSeconds" to elapsed(),
            "timerSeconds" to timer(),
            "pendingAction" to null,
            "recoveryState" to "idle",
            "healthKitState" to "notRequested",
            "healthKitUUID" to null,
            "watch" to
                mapOf(
                    "supported" to false,
                    "paired" to false,
                    "installed" to false,
                    "reachable" to false,
                    "pendingMessages" to 0,
                ),
            "streams" to
                mapOf(
                    "cyc" to
                        mapOf(
                            "status" to if (fresh) "live" else "missing",
                            "lastSampleAgeSeconds" to
                                if (lastTelemetry == 0L) null else (now - lastTelemetry) / 1000.0,
                        ),
                    "heartRate" to mapOf("status" to "unavailable", "lastSampleAgeSeconds" to null),
                    "gps" to
                        mapOf(
                            "status" to
                                if (gpsFresh) "live"
                                else if (gpsActive) "waiting" else "notRequested",
                            "lastSampleAgeSeconds" to
                                if (lastLocation == 0L) null else (now - lastLocation) / 1000.0,
                            "source" to "phone",
                            "accuracyMeters" to lastLocationValues["horizontalAccuracyM"],
                        ),
                ),
            "metrics" to
                mapOf(
                    "riderPowerW" to if (fresh) lastValues["humanPowerW"] else null,
                    "cadenceRpm" to if (fresh) lastValues["cadenceRpm"] else null,
                    "heartRateBpm" to null,
                    "activeEnergyKcal" to null,
                    "basalEnergyKcal" to null,
                    "distanceMeters" to
                        (distanceInfo?.get("selected") as? Map<*, *>)?.get("distanceMeters"),
                    "speedMps" to if (gpsFresh) lastLocationValues["speedMps"] else null,
                ),
            "distance" to distanceInfo,
            "warnings" to emptyList<String>(),
            "error" to rideError,
        )
    }

    private fun publish() {
        val snapshot = state()
        notificationState = snapshot
        emit("onWorkoutState", snapshot)
        RecordingService.refresh(context)
    }

    fun permissionStatus(): Payload {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val requested =
            context
                .getSharedPreferences("permissions", Context.MODE_PRIVATE)
                .getBoolean("locationRequested", false)
        return mapOf(
            "health" to health.status(),
            "location" to
                if (fine || coarse) "authorizedWhenInUse"
                else if (requested) "denied" else "notDetermined",
            "locationServicesEnabled" to locations.isLocationEnabled,
            "locationAccuracyAuthorization" to
                if (fine) "full" else if (coarse) "reduced" else "unknown",
        )
    }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun start(input: Payload): Payload {
        check(ride == null) { "A ride is already recording." }
        require(!input.flag("useWatch")) { "Watch recording is unavailable on Android." }
        if (input.flag("saveToHealth"))
            check(
                health.available &&
                    health
                        .granted()
                        .containsAll(
                            health.permissions(input.flag("recordGPS", !input.flag("indoor")))
                        )
            ) {
                "Allow Health Connect access before starting, or turn off Health Connect in Settings."
            }
        val gps = input.flag("recordGPS", !input.flag("indoor"))
        check(
            !gps || granted(Manifest.permission.ACCESS_FINE_LOCATION) && locations.isLocationEnabled
        ) {
            "Allow precise location and turn on Location to record a GPS route."
        }
        if (Build.VERSION.SDK_INT >= 31)
            check(granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                "Allow Nearby devices before recording."
            }
        flush()
        bluetooth.setHz(input.num("sampleHz", 2.0).toInt())
        options = input + mapOf("recordGPS" to gps)
        rideError = null
        ride = store.create(options)
        phase = "running"
        startClock = SystemClock.elapsedRealtime()
        activeSince = startClock
        activeSeconds = 0.0
        segment++
        telemetrySegment++
        rideDevice = bluetooth.deviceId
        distance.reset()
        gpsActive = gps
        notificationState = state()
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java),
            )
            wake =
                context
                    .getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerLog:recording")
                    .apply { acquire(24 * 60 * 60 * 1000L) }
            if (gps)
                locations.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0f,
                    locationListener,
                    thread.looper,
                )
        } catch (error: Exception) {
            ride?.let { store.remove(it) }
            ride = null
            phase = "idle"
            cleanup()
            throw error
        }
        historyRevision++
        publish()
        return state()
    }

    private fun requireRide(id: String?): String {
        val current = ride ?: error("No ride is recording.")
        require(id == null || id == current) { "This command belongs to an earlier ride." }
        return current
    }

    fun action(action: String, id: String?): Payload {
        val current = requireRide(id)
        flush()
        when (action) {
            "pause" ->
                if (phase == "running") {
                    activeSeconds = timer()
                    phase = "paused"
                    segment++
                    telemetrySegment++
                    distance.reset()
                    store.lifecycle(current, elapsed(), "pause")
                }
            "resume" ->
                if (phase == "paused") {
                    activeSince = SystemClock.elapsedRealtime()
                    phase = "running"
                    segment++
                    telemetrySegment++
                    distance.reset()
                    store.lifecycle(current, elapsed(), "resume")
                }
            "lap" -> {
                check(phase == "running") { "Resume the ride before marking a lap." }
                store.lifecycle(current, elapsed(), "lap")
            }
            "stop" -> {
                store.seal(current, elapsed(), timer())
                if (options.flag("saveToHealth")) queueHealth(current)
                ride = null
                phase = "idle"
                cleanup()
                historyRevision++
            }
            "discard" -> {
                store.remove(current)
                lastDeleted = current
                ride = null
                phase = "idle"
                cleanup()
                historyRevision++
            }
            else -> error("Unknown ride action")
        }
        if (ride != null) store.update(current, phase, elapsed(), timer())
        publish()
        return state()
    }

    fun delete(id: String): Payload {
        check(id != ride) { "Finish or discard the active ride first." }
        store.metadata(id)
        store.remove(id)
        historyRevision++
        lastDeleted = id
        publish()
        return state()
    }

    fun recover(id: String): Payload {
        if (id == ride) return state()
        val meta = store.metadata(id)
        check(meta.str("phase") == "completed") { "This ride cannot be recovered." }
        if (meta.flag("saveToHealth") && meta.str("healthKitState") != "saved") queueHealth(id)
        return state()
    }

    private fun queueHealth(id: String) {
        if (!healthPending.add(id)) return
        store.healthStatus(id, "pending")
        healthWork.execute {
            val outcome = runCatching { store.withSavedRide(id) { health.save(id) } }
            handler.post {
                healthPending.remove(id)
                runCatching {
                    store.healthStatus(id, if (outcome.isSuccess) "saved" else "notSaved")
                }
                historyRevision++
                publish()
            }
        }
    }

    private fun cleanup() {
        rideDevice = null
        gpsActive = false
        locations.removeUpdates(locationListener)
        if (wake?.isHeld == true) wake?.release()
        wake = null
        distance.reset()
        context.stopService(Intent(context, RecordingService::class.java))
    }

    private fun telemetry(
        values: Map<String, Double>,
        identity: CycProtocol.Identity,
        epoch: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        lastValues = values
        lastTelemetry = now
        sequence++
        val id = ride ?: live
        val time = if (ride == null) (now - liveStart) / 1000.0 else elapsed()
        if (lastEpoch != epoch) {
            telemetrySegment++
            lastEpoch = epoch
        }
        val stamp = iso()
        val identityText = "${identity.model}|${identity.firmware}|${identity.protocol}"
        pending.add(
            Pending(
                id,
                time,
                stamp,
                "telemetry",
                ride == null || phase == "running",
                telemetrySegment,
                values,
                identityText,
                epoch,
            )
        )
        try {
            if (pending.size >= 8) flush()
        } catch (error: Exception) {
            captureFailed(error)
        }
        if (now - lastSampleEvent >= 250) {
            lastSampleEvent = now
            emit(
                "onSample",
                values +
                    mapOf(
                        "timestamp" to stamp,
                        "elapsedSeconds" to time,
                        "sequence" to sequence,
                        "controllerModel" to identity.model,
                        "firmwareLabel" to identity.firmware,
                        "controllerProtocol" to identity.protocol,
                        "connectionEpoch" to epoch,
                    ),
            )
        }
    }

    private val locationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val id = ride ?: return
                val now = SystemClock.elapsedRealtimeNanos()
                if (
                    location.elapsedRealtimeNanos <= 0 ||
                        now - location.elapsedRealtimeNanos !in 0..10_000_000_000L
                )
                    return
                val time = (location.elapsedRealtimeNanos / 1_000_000 - startClock) / 1000.0
                if (time < 0) return
                val values =
                    mutableMapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "horizontalAccuracyM" to location.accuracy.toDouble(),
                    )
                if (location.hasSpeed() && location.speed in 0f..40f)
                    values["speedMps"] = location.speed.toDouble()
                if (location.hasAltitude()) values["altitudeMeters"] = location.altitude
                if (location.hasVerticalAccuracy())
                    values["verticalAccuracyM"] = location.verticalAccuracyMeters.toDouble()
                if (location.hasSpeedAccuracy())
                    values["speedAccuracyMps"] = location.speedAccuracyMetersPerSecond.toDouble()
                if (location.hasBearing()) values["courseDegrees"] = location.bearing.toDouble()
                lastLocation = SystemClock.elapsedRealtime()
                lastLocationValues = values
                pending.add(
                    Pending(
                        id,
                        time,
                        iso(location.time),
                        "location",
                        phase == "running",
                        segment,
                        values,
                        "phone",
                        "gps",
                    )
                )
            }

            override fun onProviderDisabled(provider: String) {
                lastLocation = 0
            }
        }

    fun flush() {
        if (pending.isEmpty()) return
        // Originals and their distance checkpoints commit atomically.
        store.transaction {
            pending.forEach { p ->
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
                    )
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
            }
            ride?.let { store.update(it, phase, elapsed(), timer()) }
        }
        pending.clear()
    }

    private fun captureFailed(error: Exception) {
        pending.clear()
        distance.reset()
        rideError = "Recording stopped: storage could not save more data. ${error.message ?: ""}"
        val id = ride
        if (id != null) {
            runCatching {
                val (elapsed, timer) = store.timing(id)
                store.seal(id, elapsed, timer, interrupted = true)
            }
            ride = null
            phase = "idle"
            cleanup()
            historyRevision++
        }
        publish()
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

    fun source(request: Payload) =
        if (request.str("source") == "workout") request.str("id").also { require(it.isNotBlank()) }
        else ride ?: live

    init {
        handler.post {
            try {
                store.recoverOrphans()
                live = store.create(emptyMap(), true)
                initialized = true
                tick.run()
                store
                    .list(mapOf("limit" to 100))
                    .filter { it.flag("saveToHealth") && it.str("healthKitState") == "pending" }
                    .forEach { queueHealth(it.str("id")) }
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

        fun get(context: Context): RecordingEngine =
            instance
                ?: synchronized(this) {
                    instance ?: RecordingEngine(context.applicationContext).also { instance = it }
                }
    }
}
