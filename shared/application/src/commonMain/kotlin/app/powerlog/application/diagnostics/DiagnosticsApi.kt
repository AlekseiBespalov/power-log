package app.powerlog.application.diagnostics

import app.powerlog.application.api.ApiStream
import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.application.port.DeviceConditions
import app.powerlog.application.port.DeviceSnapshot
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.RadioState
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.TraceContract
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.diagnostics.TraceState
import app.powerlog.domain.diagnostics.TraceStopReason
import kotlinx.serialization.Serializable

public interface DiagnosticsApi {
    public val state: ApiStream<Empty, DiagnosticsView>

    public suspend fun startTrace(input: Empty): Outcome<Unit>

    public suspend fun stopTrace(input: Empty): Outcome<Unit>

    public suspend fun clearTrace(input: Empty): Outcome<Unit>

    public suspend fun readTrace(input: Empty): Outcome<TraceDocument>

    public suspend fun readLog(input: DiagnosticsReadInput): Outcome<DiagnosticsPage>
}

@Serializable public data class DiagnosticsView(public val trace: TraceView)

@Serializable
public data class TraceView(
    public val state: TraceState,
    public val events: Int,
    public val stopReason: TraceStopReason?,
)

@Serializable
public data class TraceDocument(
    public val appVersion: String,
    public val platform: Platform,
    public val exportedAt: String?,
    public val expectedContract: TraceContract,
    public val trace: TraceLog,
)

@Serializable
public data class TraceLog(
    public val format: String,
    public val version: Int,
    public val capturing: Boolean,
    public val stopReason: TraceStopReason?,
    public val eventCount: Int,
    public val byteCount: Int,
    public val notice: String,
    public val events: List<TraceEvent>,
)

@Serializable
public data class TraceEvent(
    public val elapsedSeconds: Double,
    public val event: String,
    public val detail: String,
    public val byteLength: Int?,
    public val capturedBytes: Int?,
    public val hex: String?,
)

@Serializable public data class DiagnosticsReadInput(public val after: Long?)

@Serializable
public data class DiagnosticsPage(
    public val device: DeviceReport?,
    public val connection: ConnectionReport?,
    public val log: String,
    public val logError: FailureCode?,
    public val next: Long?,
)

@Serializable
public data class DeviceReport(
    public val snapshot: DeviceSnapshot,
    public val conditions: DeviceConditions,
    public val radio: RadioState?,
    public val bluetoothPermission: PermissionState?,
)

@Serializable
public data class ConnectionReport(
    public val state: ConnectionState,
    public val identity: ControllerIdentity?,
    public val sampleRateHz: Int?,
    public val background: Boolean,
    public val connectionAttempts: Int,
    public val reconnects: Int,
    public val lastSampleAgeMs: Long?,
    public val lastGapMs: Long?,
    public val recentSampleHz: Double?,
)

public object DiagnosticsPolicy {
    public const val traceMaxUs: Long = 120_000_000L
    public const val logPageBytes: Int = 262_144
    public const val traceMaxPacketBytes: Int = 4_096
    public const val traceMaxEventChars: Int = 64
    public const val traceMaxDetailChars: Int = 2_048
}
