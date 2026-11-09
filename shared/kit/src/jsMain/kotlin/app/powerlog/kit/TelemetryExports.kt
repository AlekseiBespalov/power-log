@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.kit

import app.powerlog.domain.telemetry.*
import app.powerlog.domain.telemetry.ControllerCapabilities as CoreControllerCapabilities
import app.powerlog.domain.telemetry.ControllerDecodeResult as CoreControllerDecodeResult
import app.powerlog.domain.telemetry.ControllerIdentity as CoreControllerIdentity
import app.powerlog.domain.telemetry.TelemetryReading as CoreTelemetryReading
import kotlin.js.JsExport

@JsExport
class ControllerIdentity internal constructor(internal val core: CoreControllerIdentity) {
    val controllerModel: String
        get() = core.controllerModel

    val firmwareLabel: String
        get() = core.firmwareLabel

    val controllerProtocol: String
        get() = core.controllerProtocol

    val hasKnownSpeedUnit: Boolean
        get() = core.hasKnownSpeedUnit

    val capabilities: ControllerCapabilities
        get() =
            ControllerCapabilities(
                core.capabilities.suppliedMetrics.map { it.name }.toTypedArray(),
                core.capabilities.hasKnownSpeedUnit,
            )
}

@JsExport
class TelemetryReading internal constructor(internal val core: CoreTelemetryReading) {
    val identity: ControllerIdentity?
        get() = core.identity?.let(::ControllerIdentity)

    fun value(metric: String): Double? = core.value(TelemetryMetric.valueOf(metric))

    val metrics: Array<StoredMetric>
        get() = core.metrics.map { StoredMetric(it.key.name, it.value) }.toTypedArray()
}

@JsExport
class ControllerCapabilities(suppliedMetrics: Array<String>, val hasKnownSpeedUnit: Boolean) {
    internal val core: CoreControllerCapabilities

    init {
        require(suppliedMetrics.toSet().size == suppliedMetrics.size)
        core = CoreControllerCapabilities(suppliedMetrics.map(TelemetryMetric::valueOf).toSet(), hasKnownSpeedUnit)
    }

    val suppliedMetrics: Array<String>
        get() = core.suppliedMetrics.map { it.name }.toTypedArray()

    fun supplies(metric: String): Boolean = core.supplies(TelemetryMetric.valueOf(metric))
}

@JsExport class StoredMetric(val metric: String, val value: Double)

@JsExport
class ControllerProtocolApi internal constructor(internal val core: ControllerProtocol) {
    fun requestFrame(request: String): ByteArray = core.legacyRequest(ControllerRequest.valueOf(request))

    fun feed(notification: ByteArray, identity: ControllerIdentity?): ControllerDecodeResult =
        ControllerDecodeResult(core.feed(notification, identity?.core))

    fun reset(): ControllerDecodeResult = ControllerDecodeResult(core.reset())
}

@JsExport
class ControllerDecodeResult internal constructor(result: CoreControllerDecodeResult) {
    val replies: Array<ControllerResponse> = result.replies.map(::ControllerResponse).toTypedArray()
    val discardedBytes: String = result.discardedBytes.toString()
    val pendingBytes: Int = result.pendingBytes
}

@JsExport
class ControllerResponse internal constructor(reply: ControllerReply) {
    val kind: String = reply.kind.name
    val identity: ControllerIdentity? = (reply as? ControllerIdentityReply)?.identity?.let(::ControllerIdentity)
    val reading: TelemetryReading? = (reply as? ControllerReadingReply)?.reading?.let(::TelemetryReading)
    val rejectionReason: String? = (reply as? ControllerRejectedReply)?.reason
}
