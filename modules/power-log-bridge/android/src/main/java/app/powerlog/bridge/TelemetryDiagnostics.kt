package app.powerlog.bridge

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.powerlog.core.telemetry.TelemetryStage
import app.powerlog.core.telemetry.TelemetryTiming
import app.powerlog.core.telemetry.cyc.CycRejection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal enum class TelemetryDiagnosticEvent(val wire: String) {
    engineStarted("engine_started"),
    connectRequested("connect_requested"),
    connectionAttempt("connection_attempt"),
    linkConnected("link_connected"),
    identityVerified("identity_verified"),
    transportStage("transport_stage"),
    transportError("transport_error"),
    disconnectRequested("disconnect_requested"),
    disconnected("disconnected"),
    connectionFailed("connection_failed"),
    reconnectScheduled("reconnect_scheduled"),
    sessionStopped("session_stopped"),
    bluetoothState("bluetooth_state"),
    telemetrySummary("telemetry_summary"),
    telemetryGap("telemetry_gap"),
    requestResent("request_resent"),
    backgroundChanged("background_changed"),
    recordingError("recording_error"),
    restored("restored"),
    resumed("resumed"),
}

internal enum class TelemetryDiagnosticReason(val wire: String) {
    manualDisconnect("manual_disconnect"),
    connectionDeadline("connection_deadline"),
    responseTimeout("response_timeout"),
    expiredReply("expired_reply"),
    writeReadyTimeout("write_ready_timeout"),
    writeFailed("write_failed"),
    linkDisconnected("link_disconnected"),
    connectionFailed("connection_failed"),
    notificationSetup("notification_setup"),
    notificationError("notification_error"),
    serviceDiscovery("service_discovery"),
    characteristicDiscovery("characteristic_discovery"),
    servicesInvalidated("services_invalidated"),
    identityRejected("identity_rejected"),
    telemetryRejected("telemetry_rejected"),
    writeCapacity("write_capacity"),
    retryLimit("retry_limit"),
    cancellationDeadline("cancellation_deadline"),
    bluetoothUnavailable("bluetooth_unavailable"),
    platformError("platform_error"),
    unexpectedConnection("unexpected_connection"),
    pendingReconnect("pending_reconnect"),
    restorationCleanup("restoration_cleanup"),
    storageFailure("storage_failure"),
    appTerminating("app_terminating"),
    plannedReconnect("planned_reconnect"),
}

internal fun gattError(status: Int): Payload = mapOf("errorDomain" to "AndroidGattStatus", "errorCode" to status)

internal fun bluetoothStatusError(code: Int): Payload =
    mapOf("errorDomain" to "AndroidBluetoothStatus", "errorCode" to code)

internal class TelemetryDiagnosticMetrics(now: Double) {
    var sampleCount = 0
        private set

    var connectionAttempts = 0
        private set

    var reconnects = 0
        private set

    var requestTimeouts = 0
        private set

    var linkSamples = 0
        private set

    var lastGapSeconds: Double? = null
        private set

    var cancellationReason: TelemetryDiagnosticReason? = null
        private set

    var nextSummary = now + 30
        private set

    private var linkStarted: Double? = null
    private var lastSample: Double? = null
    private var cancellationStarted: Double? = null
    private var cancelledConnectionSeconds: Double? = null
    private var cancelledSampleAgeSeconds: Double? = null
    private var windowFirstSample: Double? = null
    private var windowLastSample: Double? = null
    private var windowSamples = 0
    private var latencySumMs = 0.0
    private var latencyMaxMs = 0.0
    private var latencyCount = 0

    fun beginAttempt(now: Double) {
        connectionAttempts++
        linkStarted = null
        linkSamples = 0
        cancellationReason = null
        cancellationStarted = null
        cancelledConnectionSeconds = null
        cancelledSampleAgeSeconds = null
        resetWindow(now)
    }

    fun connected(now: Double) {
        linkStarted = now
    }

    fun closed() {
        linkStarted = null
    }

    fun scheduledReconnect() {
        reconnects++
    }

    fun timedOut() {
        requestTimeouts++
    }

    fun connectionSeconds(now: Double) = linkStarted?.let { maxOf(0.0, now - it) }

    fun sampleAge(now: Double) = lastSample?.let { maxOf(0.0, now - it) }

    // Keep the first app cancellation reason until its disconnect callback, even after transport fields are cleared.
    fun requestedCancellation(reason: TelemetryDiagnosticReason, now: Double) {
        if (cancellationReason != null) return
        cancellationReason = reason
        cancellationStarted = now
        cancelledConnectionSeconds = connectionSeconds(now)
        cancelledSampleAgeSeconds = sampleAge(now)
    }

    fun disconnected(now: Double, status: Int, defaultReason: TelemetryDiagnosticReason): Payload {
        val fields =
            mutableMapOf<String, Any?>(
                "initiator" to if (cancellationReason == null) "peer_or_system" else "app",
                "reason" to (cancellationReason ?: defaultReason).wire,
                "connectionSeconds" to connectionSeconds(now),
                "sampleAgeSeconds" to sampleAge(now),
                "linkSamples" to linkSamples,
            )
        fields.putAll(gattError(status))
        cancellationStarted?.let { fields["cancellationCallbackSeconds"] = maxOf(0.0, now - it) }
        cancelledConnectionSeconds?.let { fields["connectionSecondsAtCancel"] = it }
        cancelledSampleAgeSeconds?.let { fields["sampleAgeSecondsAtCancel"] = it }
        linkStarted = null
        cancellationReason = null
        cancellationStarted = null
        resetWindow(now)
        return fields
    }

    fun receivedSample(now: Double, responseSeconds: Double): Double? {
        val gap = lastSample?.let { now - it }?.takeIf { it > TelemetryTiming.telemetryTimeoutSeconds }
        if (gap != null) {
            lastGapSeconds = gap
            resetWindow(now)
        }
        sampleCount++
        linkSamples++
        lastSample = now
        if (windowFirstSample == null) windowFirstSample = now
        windowLastSample = now
        windowSamples++
        if (responseSeconds.isFinite() && responseSeconds >= 0) {
            val milliseconds = responseSeconds * 1000
            latencySumMs += milliseconds
            latencyMaxMs = maxOf(latencyMaxMs, milliseconds)
            latencyCount++
        }
        return gap
    }

    fun recentSampleHz(now: Double): Double? {
        val first = windowFirstSample ?: return null
        val last = windowLastSample ?: return null
        if (windowSamples <= 1 || last <= first || now - last > TelemetryTiming.telemetryTimeoutSeconds) return null
        return (windowSamples - 1) / (last - first)
    }

    fun latency(): Payload =
        if (latencyCount == 0) emptyMap()
        else mapOf("latencyMeanMs" to latencySumMs / latencyCount, "latencyMaxMs" to latencyMaxMs)

    fun resetWindow(now: Double) {
        windowFirstSample = null
        windowLastSample = null
        windowSamples = 0
        latencySumMs = 0.0
        latencyMaxMs = 0.0
        latencyCount = 0
        nextSummary = now + 30
    }
}

internal class TelemetryDiagnosticLog(
    private val directory: File,
    byteLimit: Int = MAXIMUM_FILE_BYTES,
    private val executor: Executor = serialExecutor(),
) {
    private val byteLimit = byteLimit.coerceIn(512, MAXIMUM_FILE_BYTES)
    private val current = File(directory, "events.jsonl")
    private val previous = File(directory, "events.previous.jsonl")
    private var output: FileOutputStream? = null
    private var currentBytes = 0L
    private var writeError: Exception? = null

    fun append(event: TelemetryDiagnosticEvent, elapsedSeconds: Double, fields: Payload = emptyMap()) {
        if (!elapsedSeconds.isFinite() || elapsedSeconds < 0) return
        val entry = sanitized(fields)
        entry["schemaVersion"] = 1
        entry["timestamp"] = iso()
        entry["elapsedSeconds"] = elapsedSeconds
        entry["event"] = event.wire
        val line = JSONObject().apply { for ((key, value) in entry) put(key, value) }.toString() + "\n"
        val payload = line.toByteArray(Charsets.UTF_8)
        if (payload.size > byteLimit) return
        executor.execute {
            try {
                val stream = output ?: open()
                if (currentBytes + payload.size > byteLimit) rotate().write(payload) else stream.write(payload)
                currentBytes += payload.size
            } catch (error: Exception) {
                writeError = error
            }
        }
    }

    fun read(): String {
        val task =
            FutureTask(
                Callable {
                    if (writeError != null) throw IOException("Private diagnostic storage is unavailable.")
                    String(bounded(previous) + bounded(current), Charsets.UTF_8)
                }
            )
        executor.execute(task)
        try {
            return task.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun open(): FileOutputStream {
        if (!directory.isDirectory && !directory.mkdirs())
            throw IOException("Private diagnostic storage is unavailable.")
        recover(current)
        recover(previous)
        currentBytes = current.length()
        return FileOutputStream(current, true).also { output = it }
    }

    private fun rotate(): FileOutputStream {
        output?.close()
        output = null
        if (previous.exists() && !previous.delete())
            throw IOException("The previous diagnostic log could not be removed.")
        if (!current.renameTo(previous)) throw IOException("The diagnostic log could not be rotated.")
        currentBytes = 0
        return FileOutputStream(current, false).also { output = it }
    }

    // Recover only bounded complete lines after a process interruption.
    private fun recover(file: File) {
        val size = file.takeIf { it.isFile }?.length() ?: return
        if (size == 0L) return
        val complete =
            RandomAccessFile(file, "r").use { reader ->
                reader.seek(size - 1)
                reader.read() == NEWLINE.toInt()
            }
        if (complete && size <= byteLimit) return
        val staging = File(directory, "${file.name}.tmp")
        staging.writeBytes(bounded(file))
        if (!staging.renameTo(file)) throw IOException("The diagnostic log could not be recovered.")
    }

    private fun bounded(file: File): ByteArray {
        if (!file.isFile) return ByteArray(0)
        RandomAccessFile(file, "r").use { reader ->
            val offset = maxOf(0L, reader.length() - byteLimit)
            val bytes = ByteArray((reader.length() - offset).toInt())
            reader.seek(offset)
            reader.readFully(bytes)
            // A retained tail starts in an unknown line; omit that line rather than emit malformed JSON.
            val start = if (offset > 0) bytes.indexOf(NEWLINE) + 1 else 0
            if (offset > 0 && start == 0) return ByteArray(0)
            val end = bytes.lastIndexOf(NEWLINE) + 1
            return if (end <= start) ByteArray(0) else bytes.copyOfRange(start, end)
        }
    }

    companion object {
        const val MAXIMUM_FILE_BYTES = 4 * 1024 * 1024
        private const val NEWLINE = '\n'.code.toByte()

        val numberFields =
            setOf(
                "requestedHz",
                "sampleCount",
                "connectionAttempts",
                "reconnects",
                "requestTimeouts",
                "decoderDiscardedBytes",
                "lastSampleAgeSeconds",
                "gapSeconds",
                "linkSamples",
                "connectionSeconds",
                "sampleAgeSeconds",
                "cancellationCallbackSeconds",
                "connectionSecondsAtCancel",
                "sampleAgeSecondsAtCancel",
                "requestAgeSeconds",
                "writeBlockedSeconds",
                "retryAttempt",
                "retryDelaySeconds",
                "errorCode",
                "recentSampleHz",
                "latencyMeanMs",
                "latencyMaxMs",
                "notificationErrors",
                "pendingBytes",
                "queueDelaySeconds",
                "storageWriteMs",
                "connectionGeneration",
                "cpuSeconds",
                "thermalState",
                "thermalStatus",
            )
        val booleanFields =
            setOf("background", "systemReconnect", "recordingFailed", "interactive", "powerSave", "deviceIdle")
        val stringFields =
            mapOf(
                "initiator" to setOf("app", "peer_or_system"),
                "request" to setOf("identity", "telemetry"),
                "manager" to setOf("central"),
                "status" to setOf("idle", "scanning", "connecting", "connected", "reconnecting", "error"),
                "bluetoothState" to
                    setOf("unknown", "resetting", "unsupported", "unauthorized", "poweredOff", "poweredOn"),
            )
        val errorDomains =
            setOf(
                "CBErrorDomain",
                "CBATTErrorDomain",
                "NSCocoaErrorDomain",
                "NSPOSIXErrorDomain",
                "NSOSStatusErrorDomain",
                "AndroidGattStatus",
                "AndroidBluetoothStatus",
            )

        fun safeErrorDomain(domain: String) = if (domain in errorDomains) domain else "OtherErrorDomain"

        private fun sanitized(fields: Payload): java.util.TreeMap<String, Any> {
            val safe = java.util.TreeMap<String, Any>()
            for ((key, value) in fields) {
                when {
                    value is Number && key in numberFields -> if (value.toDouble().isFinite()) safe[key] = value
                    value is Boolean && key in booleanFields -> safe[key] = value
                    value !is String -> Unit
                    key == "transportStage" -> if (TelemetryStage.entries.any { it.name == value }) safe[key] = value
                    key == "reason" -> if (TelemetryDiagnosticReason.entries.any { it.wire == value }) safe[key] = value
                    key == "errorDomain" -> safe[key] = safeErrorDomain(value)
                    key == "rejection" -> if (CycRejection.entries.any { it.name == value }) safe[key] = value
                    stringFields[key]?.contains(value) == true -> safe[key] = value
                }
            }
            return safe
        }

        private fun serialExecutor(): Executor =
            ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { job ->
                    Thread(job, "PowerLogDiagnostics").apply { isDaemon = true }
                }
                .apply { allowCoreThreadTimeOut(true) }
    }
}

internal fun diagnosticDevice(context: Context): Payload {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(PowerManager::class.java)
    return mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "osVersion" to Build.VERSION.RELEASE,
        "sdkVersion" to Build.VERSION.SDK_INT,
        "bluetoothEnabled" to context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled,
        "nearbyDevicesAllowed" to
            if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_CONNECT) else null,
        "preciseLocationAllowed" to granted(Manifest.permission.ACCESS_FINE_LOCATION),
        "notificationsAllowed" to
            if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.POST_NOTIFICATIONS) else null,
        "ignoringBatteryOptimizations" to power?.isIgnoringBatteryOptimizations(context.packageName),
        "powerSave" to power?.isPowerSaveMode,
        "backgroundRestricted" to context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted,
        "standbyBucket" to context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket,
    )
}
