package app.powerlog.application.diagnostics

import app.powerlog.application.Deadlines
import app.powerlog.application.Executors
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.Published
import app.powerlog.application.api.StreamPolicy
import app.powerlog.application.api.failure
import app.powerlog.application.connection.ConnectionSnapshot
import app.powerlog.application.connection.TraceSink
import app.powerlog.application.port.Clock
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.RadioState
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.controller.TraceContract
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.diagnostics.TraceState
import app.powerlog.domain.diagnostics.TraceStopReason
import app.powerlog.domain.telemetry.BluetoothTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class Diagnostics(
    private val scope: CoroutineScope,
    private val executors: Executors,
    private val clock: Clock,
    private val device: DeviceInfo,
    private val log: DiagnosticLogRepository?,
    private val deadlines: Deadlines,
) : TraceSink {
    class Reports(
        val connection: ConnectionSnapshot,
        val radio: RadioState?,
        val bluetoothPermission: PermissionState?,
        val contract: TraceContract,
    )

    var reports: () -> Reports = { error("the application sets the reports") }

    private val trace = BluetoothTrace()
    private val events = mutableListOf<TraceEvent>()
    private var startedUs = 0L
    private var timeLimit: Job? = null

    val stream: Published<DiagnosticsView> = Published(view(), StreamPolicy.diagnosticsStateUs, clock)

    override val capturing: Boolean
        get() = trace.status(seconds(clock.monoUs())).capturing

    override fun record(event: String, detail: String, bytes: ByteArray?, at: ClockReading) {
        val wasCapturing = capturing
        val count = trace.status(seconds(at.monoUs)).eventCount
        trace.record(seconds(at.monoUs), event, detail, bytes)
        if (trace.status(seconds(at.monoUs)).eventCount > count) {
            val captured = bytes?.copyOf(minOf(bytes.size, DiagnosticsPolicy.traceMaxPacketBytes))
            events +=
                TraceEvent(
                    elapsedSeconds = maxOf(0.0, seconds(at.monoUs - startedUs)),
                    event = event.take(DiagnosticsPolicy.traceMaxEventChars),
                    detail = detail.take(DiagnosticsPolicy.traceMaxDetailChars),
                    byteLength = bytes?.size,
                    capturedBytes = captured?.size,
                    hex = captured?.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
                )
        }
        publish(urgent = wasCapturing && !capturing)
    }

    fun start() {
        startedUs = clock.monoUs()
        events.clear()
        trace.start(seconds(startedUs))
        timeLimit?.cancel()
        val endsAtUs = clock.monoUs() + DiagnosticsPolicy.traceMaxUs
        timeLimit =
            scope.launch(executors.ride) {
                deadlines.until(endsAtUs)
                publish(urgent = true)
            }
        val connection = reports().connection
        record(
            "capture_started",
            "platform=${device.snapshot().platform} state=${connection.state} sampleRateHz=${connection.sampleRateHz}",
            null,
            clock.read(),
        )
        publish(urgent = true)
    }

    fun stop() {
        trace.stop()
        timeLimit?.cancel()
        publish(urgent = true)
    }

    fun clear() {
        trace.clear()
        events.clear()
        timeLimit?.cancel()
        publish(urgent = true)
    }

    suspend fun readTrace(): Outcome<TraceDocument> {
        val (log, contract) =
            withContext(executors.ride) {
                if (state() == TraceState.idle) return@withContext null
                val status = trace.status(seconds(clock.monoUs()))
                TraceLog(
                    format = "power-log-bluetooth-trace",
                    version = 1,
                    capturing = status.capturing,
                    stopReason = status.stopReason?.let(TraceStopReason::valueOf),
                    eventCount = status.eventCount,
                    byteCount = status.byteCount,
                    notice = NOTICE,
                    events = events.toList(),
                ) to reports().contract
            } ?: return Outcome.Failed(failure(FailureCode.actionUnavailable))
        return withContext(executors.reads) {
            val snapshot = device.snapshot()
            Outcome.Ok(TraceDocument(snapshot.appVersion, snapshot.platform, null, contract, log))
        }
    }

    suspend fun readLog(after: Long?): Outcome<DiagnosticsPage> {
        val store = log ?: return Outcome.Failed(failure(FailureCode.actionUnavailable))
        val first = after == null
        val reports = if (first) withContext(executors.ride) { reports() } else null
        return withContext(executors.reads) {
            val read = store.read(after ?: 0L, DiagnosticsPolicy.logPageBytes)
            if (read is StoreResult.Failed && !first) return@withContext Outcome.Failed(failure(FailureCode.readFailed))
            val chunk = (read as? StoreResult.Ok)?.value
            Outcome.Ok(
                DiagnosticsPage(
                    device = reports?.let(::deviceReport),
                    connection = reports?.connection?.let(::connectionReport),
                    log = chunk?.bytes?.decodeToString().orEmpty(),
                    logError = FailureCode.readFailed.takeIf { chunk == null },
                    next = chunk?.next,
                )
            )
        }
    }

    private fun connectionReport(snapshot: ConnectionSnapshot): ConnectionReport =
        ConnectionReport(
            state = snapshot.state,
            identity = snapshot.identity,
            sampleRateHz = snapshot.sampleRateHz,
            background = snapshot.background,
            connectionAttempts = snapshot.connectionAttempts,
            reconnects = snapshot.reconnects,
            lastSampleAgeMs = snapshot.lastSampleAgeMs,
            lastGapMs = snapshot.lastGapMs,
            recentSampleHz = snapshot.recentSampleHz,
        )

    private fun deviceReport(reports: Reports): DeviceReport {
        val snapshot = device.snapshot()
        val language = snapshot.locale.substringBefore('-').substringBefore('_')
        return DeviceReport(
            snapshot = snapshot.copy(locale = language, localeFacts = snapshot.localeFacts.copy(region = null)),
            conditions = device.conditions(),
            radio = reports.radio,
            bluetoothPermission = reports.bluetoothPermission,
        )
    }

    private fun publish(urgent: Boolean) {
        stream.publish(view(), urgent)
    }

    private fun view(): DiagnosticsView {
        val status = trace.status(seconds(clock.monoUs()))
        return DiagnosticsView(TraceView(state(), status.eventCount, status.stopReason?.let(TraceStopReason::valueOf)))
    }

    private fun state(): TraceState {
        val status = trace.status(seconds(clock.monoUs()))
        return when {
            status.capturing -> TraceState.capturing
            status.eventCount == 0 && status.stopReason == null -> TraceState.idle
            else -> TraceState.stopped
        }
    }

    private fun seconds(monoUs: Long): Double = monoUs / 1_000_000.0

    private companion object {
        const val NOTICE =
            "Raw Bluetooth data may contain identifiers. Share only intentionally. No GPS or Health data."
    }
}
