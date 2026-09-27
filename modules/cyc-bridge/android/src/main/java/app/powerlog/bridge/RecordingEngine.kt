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
    val monitor = RideMonitor(store, distance, ::monitorOriginSeconds)
    val listeners = CopyOnWriteArrayList<(String, Payload) -> Unit>()
    private val locations = context.getSystemService(LocationManager::class.java)
    private var live = ""
    private var liveStart = SystemClock.elapsedRealtime()
    @Volatile private var ride: String? = null
    private var phase = "idle"
    private var selectedRide: String? = null
    private var options = RideOptions(indoor = false, saveToHealth = false, recordGPS = false)
    @Volatile private var startClock = 0L
    private var activeSince = 0L
    private var activeSeconds = 0.0
    private var segment = 0
    private var telemetrySegment = 0
    private var rideDevice: String? = null
    private var historyRevision = 0L
    private var lastDeleted: String? = null
    private var lastTelemetry = 0L
    private var lastLocation = 0L
    private var lastEpoch = ""
    private val ready = java.util.concurrent.CountDownLatch(1)
    @Volatile private var initializationError: Exception? = null
    private var sequence = 0L
    private var rideError: String? = null
    private var lastSampleEvent = 0L
    private var lastLocationValues: Map<String, Double> = emptyMap()
    private var gpsUnavailable = false
    private var wake: PowerManager.WakeLock? = null

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
    )

    private val pending = mutableListOf<Pending>()
    val bluetooth = CycBluetooth(context, handler, ::emit, ::telemetry) { ride != null }
    @Volatile
    var notificationState = RideNotificationState(null, "idle", 0L, 0.0)
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
                        store.update(id, phase, timing())
                        publish()
                    }
                } catch (error: Exception) {
                    captureFailed(error)
                }
                handler.postDelayed(this, 1000)
            }
        }

    private fun elapsed(now: Long = SystemClock.elapsedRealtime()) =
        if (ride == null) 0.0 else (now - startClock) / 1000.0

    private fun timer(now: Long = SystemClock.elapsedRealtime()) =
        activeSeconds + if (phase == "running") (now - activeSince) / 1000.0 else 0.0

    private fun timing(now: Long = SystemClock.elapsedRealtime()) = RideTiming(elapsed(now), timer(now), iso())

    fun capabilities() = RideCapabilities.android(health.available)

    fun state(): Payload = snapshot().toWireMap()

    private fun snapshot(): RideSnapshot {
        val now = SystemClock.elapsedRealtime()
        val fresh = now - lastTelemetry <= 2500 && lastTelemetry != 0L
        val gpsFresh = now - lastLocation <= 10000 && lastLocation != 0L
        val id = ride
        val gpsStatus =
            when {
                id == null || !options.recordGPS -> "off"
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "denied"
                phase == "paused" -> "paused"
                gpsUnavailable || !locations.isLocationEnabled -> "unavailable"
                lastLocation == 0L -> "waiting"
                !gpsFresh -> "stale"
                (lastLocationValues["horizontalAccuracyM"] ?: 0.0) > 50 -> "weak"
                else -> "receiving"
            }
        val cycStatus =
            when {
                id == null && bluetooth.deviceId == null -> "off"
                phase == "paused" -> "paused"
                fresh -> "receiving"
                lastTelemetry != 0L -> "stale"
                else -> "waiting"
            }
        return RideSnapshot(
            capabilities = capabilities(),
            id = id,
            phase = phase,
            timerSeconds = timer(now),
            historyRevision = historyRevision.toString(),
            lastDeletedWorkoutId = lastDeleted,
            indoor = options.indoor,
            useWatch = options.useWatch,
            saveToHealth = options.saveToHealth,
            recordGPS = options.recordGPS,
            healthKitState = if (id != null && options.saveToHealth) "pending" else "notRequested",
            streams =
                RideStreams(
                    RideStream(cycStatus),
                    RideStream("off"),
                    RideGPSStream(
                        gpsStatus,
                        accuracyMeters = lastLocationValues["horizontalAccuracyM"]?.takeIf { it.isFinite() && it >= 0 },
                    ),
                ),
            error = rideError,
        )
    }

    private fun publish() {
        val snapshot = state()
        emit("onWorkoutState", snapshot)
        val notification = RideNotificationState(ride, phase, activeSince, activeSeconds)
        if (notification != notificationState) {
            notificationState = notification
            RecordingService.refresh(context)
        }
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

    fun start(input: RideOptions): Payload {
        check(ride == null) { "A ride is already recording." }
        val nextHistoryRevision = RideStore.nextRevision(historyRevision)
        val effective = input.effective(capabilities())
        if (effective.saveToHealth)
            check(health.granted().containsAll(health.permissions(effective.recordGPS))) {
                "Allow Health Connect access before starting, or turn off Health Connect in Settings."
            }
        val gps = effective.recordGPS
        check(!gps || granted(Manifest.permission.ACCESS_FINE_LOCATION) && locations.isLocationEnabled) {
            "Allow precise location and turn on Location to record a GPS route."
        }
        if (Build.VERSION.SDK_INT >= 31)
            check(granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                "Allow Nearby devices before recording."
            }
        flush()
        bluetooth.setHz(effective.sampleHz)
        options = effective
        rideError = null
        val created = store.create(options)
        phase = "running"
        startClock = SystemClock.elapsedRealtime()
        ride = created
        selectedRide = created
        monitor.selectLiveRide(created)
        activeSince = startClock
        activeSeconds = 0.0
        segment++
        telemetrySegment++
        boundaries.clear()
        boundaries.add(Boundary(0.0, true, segment))
        lastLocation = 0L
        lastLocationValues = emptyMap()
        gpsUnavailable = false
        rideDevice = bluetooth.deviceId
        distance.retainOnly(live)
        gpsActive = gps
        notificationState = RideNotificationState(ride, phase, activeSince, activeSeconds)
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
            if (gps) startLocationUpdates()
        } catch (error: Exception) {
            ride?.let { store.remove(it) }
            ride = null
            phase = "idle"
            cleanup()
            throw error
        }
        historyRevision = nextHistoryRevision
        publish()
        return state()
    }

    private fun requireRide(id: String?): String {
        val current = ride ?: error("No ride is recording.")
        require(id == null || id == current) { "This command belongs to an earlier ride." }
        return current
    }

    fun action(action: String, id: String?): Payload {
        if (action in listOf("pause", "resume", "lap")) {
            require(id == null || id == selectedRide) {
                "The selected workout changed. Refresh before trying again."
            }
            when (action) {
                "pause" -> check(phase == "running") { "The workout is not ready to pause." }
                "resume" -> check(phase == "paused") { "The owner must be paused before resuming." }
                "lap" -> check(phase == "running") { "Start or resume the workout before marking a lap." }
            }
        }
        val current = requireRide(id)
        val nextHistoryRevision =
            if (action in listOf("stop", "discard")) RideStore.nextRevision(historyRevision) else historyRevision
        flush()
        val now = SystemClock.elapsedRealtime()
        val timing = timing(now)
        val time = timing.elapsed
        val active = timing.timer
        when (action) {
            "pause" ->
                if (phase == "running") {
                    store.transition(current, timing, "pause")
                    activeSeconds = active
                    phase = "paused"
                    segment++
                    telemetrySegment++
                    boundary(time)
                    if (options.recordGPS) locations.removeUpdates(locationListener)
                }
            "resume" ->
                if (phase == "paused") {
                    store.transition(current, timing, "resume")
                    activeSince = now
                    phase = "running"
                    segment++
                    telemetrySegment++
                    boundary(time)
                    if (options.recordGPS) startLocationUpdates()
                }
            "lap" -> {
                store.transaction {
                    store.lifecycle(current, timing, "lap")
                    store.update(current, phase, timing)
                }
            }
            "stop" -> {
                store.seal(current, timing)
                ride = null
                phase = "idle"
                cleanup()
                historyRevision = nextHistoryRevision
                if (options.saveToHealth) queueHealth(current)
            }
            "discard" -> {
                store.remove(current)
                lastDeleted = current
                selectedRide = null
                ride = null
                phase = "idle"
                cleanup()
                historyRevision = nextHistoryRevision
            }
            else -> error("Unknown ride action")
        }
        publish()
        return state()
    }

    private fun boundary(time: Double) {
        boundaries.add(Boundary(time, phase == "running", segment))
        // Keep the preceding boundary for every fix still admissible in the ten-second window.
        while (boundaries.size > 1 && boundaries[1].time <= time - 10) boundaries.removeAt(0)
    }

    fun delete(id: String): Payload {
        check(id != ride) { "Finish or discard the active ride first." }
        val nextHistoryRevision = RideStore.nextRevision(historyRevision)
        store.metadata(id)
        store.remove(id)
        historyRevision = nextHistoryRevision
        lastDeleted = id
        if (selectedRide == id) selectedRide = null
        publish()
        return state()
    }

    fun recover(id: String): Payload {
        if (id == ride) return state()
        val meta = store.metadata(id)
        check(meta.str("phase") == "completed") { "This ride cannot be recovered." }
        if (meta.flag("saveToHealth") && meta.str("healthKitState") == "notSaved") {
            store.healthStatus(id, HealthExportResult("pending"))
            queueHealth(id)
        }
        return state()
    }

    private fun queueHealth(id: String) {
        if (store.metadata(id).str("healthKitState") != "pending") return
        if (!healthPending.add(id)) return
        try {
            healthWork.execute {
                val outcome = runCatching {
                    store.withSavedRide(id) {
                        if (store.metadata(id).str("healthKitState") == "pending") health.save(id) else null
                    }
                }
                    .getOrElse {
                        HealthExportResult(
                            "notSaved",
                            reason = "Health Connect saving failed: ${it.message ?: "Try again."}",
                        )
                    }
                handler.post {
                    healthPending.remove(id)
                    val nextHistoryRevision = runCatching {
                        RideStore.nextRevision(historyRevision)
                    }
                        .getOrElse {
                            rideError = "Health Connect status could not be saved. ${it.message ?: ""}"
                            publish()
                            return@post
                        }
                    runCatching {
                        if (outcome != null && store.metadata(id).str("healthKitState") == "pending")
                            store.healthStatus(id, outcome)
                    }
                    historyRevision = nextHistoryRevision
                    publish()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            healthPending.remove(id)
            rideError = "Ride saved locally. Health Connect saving could not be scheduled."
        }
    }

    private fun cleanup() {
        if (bluetooth.state["status"] == "reconnecting") bluetooth.disconnect()
        monitor.selectLiveRide(live)
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
        values: Map<String, Double>,
        identity: CycProtocol.Identity,
        epoch: String,
        acquiredAt: Long,
        timestamp: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        val current = ride
        val liveTime = (acquiredAt - liveStart) / 1000.0
        val rideTime = current?.let { elapsed(acquiredAt) }
        val rideActive = rideTime?.let { time -> boundaries.lastOrNull { it.time <= time }?.active }
        val time = rideTime ?: liveTime
        lastTelemetry = acquiredAt
        sequence++
        if (lastEpoch != epoch) {
            telemetrySegment++
            lastEpoch = epoch
        }
        val identityText = "${identity.model}|${identity.firmware}|${identity.protocol}"
        fun observation(target: String, at: Double, active: Boolean, segment: Int) =
            Pending(
                target,
                at,
                timestamp,
                "telemetry",
                active,
                segment,
                values,
                identityText,
                epoch,
                acquiredAt / 1000.0,
            )
        pending.add(observation(live, liveTime, true, 0))
        if (current != null && rideTime != null && rideActive != null)
            pending.add(observation(current, rideTime, rideActive, telemetrySegment))
        try {
            if (pending.count { current == null || it.ride != live } >= 8) flush()
        } catch (error: Exception) {
            captureFailed(error)
        }
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
                        "controllerModel" to identity.model,
                        "firmwareLabel" to identity.firmware,
                        "controllerProtocol" to identity.protocol,
                        "connectionEpoch" to epoch,
                        "interruptionIndex" to 0,
                    ),
            )
        }
    }

    private fun startLocationUpdates() {
        locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener, thread.looper)
    }

    private val locationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val id = ride ?: return
                val now = SystemClock.elapsedRealtimeNanos()
                if (location.elapsedRealtimeNanos <= 0 || now - location.elapsedRealtimeNanos !in 0..10_000_000_000L)
                    return
                val time = (location.elapsedRealtimeNanos / 1_000_000 - startClock) / 1000.0
                if (time < 0) return
                val boundary = boundaries.lastOrNull { it.time <= time } ?: return
                if (!boundary.active) return
                val values =
                    mutableMapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "horizontalAccuracyM" to location.accuracy.toDouble(),
                    )
                if (location.hasSpeed() && location.speed in 0f..40f) values["speedMps"] = location.speed.toDouble()
                if (location.hasAltitude()) values["altitudeMeters"] = location.altitude
                if (location.hasVerticalAccuracy())
                    values["verticalAccuracyM"] = location.verticalAccuracyMeters.toDouble()
                if (location.hasSpeedAccuracy())
                    values["speedAccuracyMps"] = location.speedAccuracyMetersPerSecond.toDouble()
                if (location.hasBearing()) values["courseDegrees"] = location.bearing.toDouble()
                val acquiredAt = location.elapsedRealtimeNanos / 1_000_000
                if (acquiredAt >= lastLocation) {
                    lastLocation = acquiredAt
                    lastLocationValues = values
                    gpsUnavailable = false
                }
                pending.add(
                    Pending(
                        id,
                        time,
                        iso(location.time),
                        "location",
                        boundary.active,
                        boundary.segment,
                        values,
                        "phone",
                        "gps",
                        location.elapsedRealtimeNanos / 1e9,
                    )
                )
            }

            override fun onProviderDisabled(provider: String) {
                gpsUnavailable = true
            }

            override fun onProviderEnabled(provider: String) {
                gpsUnavailable = false
            }
        }

    fun flush() {
        if (pending.isEmpty()) return
        val timing = ride?.let { timing() }
        // Originals and their distance checkpoints commit atomically.
        val committed = store.transaction {
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
                Triple(row, p, p.values.keys + listOfNotNull(distanceColumn))
            }
            ride?.let { store.update(it, phase, checkNotNull(timing)) }
            rows
        }
        pending.clear()
        committed.forEach { (row, p, metrics) ->
            monitor.committedLiveObservation(p.ride, row, p.acquiredAt, metrics)
        }
    }

    private fun captureFailed(error: Exception) {
        pending.clear()
        distance.reset()
        rideError = "Recording stopped: storage could not save more data. ${error.message ?: ""}"
        val id = ride
        if (id != null) {
            val nextHistoryRevision = runCatching { RideStore.nextRevision(historyRevision) }.getOrNull()
            if (nextHistoryRevision != null) {
                runCatching { store.seal(id, store.timing(id), interrupted = true) }
                historyRevision = nextHistoryRevision
            }
            ride = null
            phase = "idle"
            cleanup()
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
        live = store.replaceLive()
        liveStart = SystemClock.elapsedRealtime()
        if (ride == null) monitor.selectLiveRide(live)
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

    init {
        handler.post {
            try {
                store.recoverOrphans()
                startLiveSession()
                initialized = true
                tick.run()
                store.pendingHealthJobs().forEach { queueHealth(it) }
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
