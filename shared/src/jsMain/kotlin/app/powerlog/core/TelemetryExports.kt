@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.ControllerCapabilities as CoreControllerCapabilities
import app.powerlog.core.telemetry.ControllerDecodeResult as CoreControllerDecodeResult
import app.powerlog.core.telemetry.ControllerIdentity as CoreControllerIdentity
import app.powerlog.core.telemetry.TelemetryReading as CoreTelemetryReading
import kotlin.js.JsExport

private fun telemetryDecimal(value: String): Long {
    require(value == "0" || value.isNotEmpty() && value[0] != '0' && value.all { it in '0'..'9' })
    return value.toLong()
}

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
fun controllerRestoreIdentity(
    controllerModel: String,
    firmwareLabel: String,
    controllerProtocol: String,
): ControllerIdentity =
    ControllerIdentity(TelemetryRestoration.restore(controllerModel, firmwareLabel, controllerProtocol, null).identity)

@JsExport
fun controllerRestoreReading(metrics: Array<StoredMetric>, identity: ControllerIdentity): TelemetryReading {
    require(metrics.map { it.metric }.toSet().size == metrics.size)
    val restored =
        TelemetryRestoration.restore(
            identity.controllerModel,
            identity.firmwareLabel,
            identity.controllerProtocol,
            metrics.associate { it.metric to it.value },
        )
    require(restored.identity == identity.core)
    return TelemetryReading(requireNotNull(restored.reading))
}

@JsExport
class ControllerProtocolApi internal constructor(internal val core: ControllerProtocol) {
    fun requestFrame(request: String): ByteArray = core.request(ControllerRequest.valueOf(request))

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

@JsExport
class TelemetryNotifications internal constructor(decision: TelemetryNotificationDecision) {
    val action: String = decision.action.name
    val replies: Array<ControllerResponse> = decision.replies.map(::ControllerResponse).toTypedArray()
    val discardedBytes: String = decision.discardedBytes.toString()
    val pendingBytes: Int = decision.pendingBytes
}

@JsExport
class TelemetryTimingPolicy internal constructor() {
    val telemetryTimeoutSeconds: Double = TelemetryTiming.telemetryTimeoutSeconds
    val discoverySeconds: Double = TelemetryTiming.discoverySeconds
    val attemptSeconds: Double = TelemetryTiming.attemptSeconds
    val cancellationGraceSeconds: Double = TelemetryTiming.cancellationGraceSeconds
    val displayHoldSeconds: Double = TelemetryTiming.displayHoldSeconds
    val stableTelemetrySeconds: Double = TelemetryTiming.stableTelemetrySeconds
}

@JsExport fun telemetryTimingPolicy(): TelemetryTimingPolicy = TelemetryTimingPolicy()

@JsExport
class TelemetryRecoveryPlan internal constructor(internal val core: TelemetryRetryPlan) {
    val delaySeconds: Double
        get() = core.delaySeconds

    val adoptSystemReconnect: Boolean
        get() = core.adoptSystemReconnect

    val cancelBeforeRetry: Boolean
        get() = core.cancelBeforeRetry
}

@JsExport
fun telemetryRecoveryPlan(
    attempt: Double,
    activeRide: Boolean,
    callbackCompleted: Boolean,
    peerOrSystem: Boolean,
    stableTelemetrySeconds: Double?,
    systemIsReconnecting: Boolean,
    systemAttemptPending: Boolean,
    peripheralIsDisconnected: Boolean,
): TelemetryRecoveryPlan? {
    require(attempt.isFinite() && attempt >= 1 && attempt <= Int.MAX_VALUE && attempt == attempt.toInt().toDouble())
    return TelemetryReconnectPolicy.plan(
            attempt.toInt(),
            activeRide,
            callbackCompleted,
            peerOrSystem,
            stableTelemetrySeconds,
            systemIsReconnecting,
            systemAttemptPending,
            peripheralIsDisconnected,
        )
        ?.let(::TelemetryRecoveryPlan)
}

@JsExport
class TelemetryTransportSession(protocol: ControllerProtocolApi) {
    private val session = TelemetrySession(protocol.core)
    val discardedBytes: String
        get() = session.discardedBytes.toString()

    val pendingBytes: Int
        get() = session.pendingBytes

    val generation: String
        get() = session.state.generation.toString()

    val stage: String
        get() = session.state.stage.name

    val attemptDeadline: Double?
        get() = session.state.attemptDeadline

    val reconnectDue: Double?
        get() = session.state.reconnectDue

    val cancellationDeadline: Double?
        get() = session.state.cancellationDeadline

    val cancellationPending: Boolean
        get() = session.state.cancellationPending

    val cancellationFailed: Boolean
        get() = session.state.cancellationFailed

    val identity: ControllerIdentity?
        get() = session.identity?.let(::ControllerIdentity)

    fun requestFrame(request: String): ByteArray = session.request(ControllerRequest.valueOf(request))

    fun notifications(
        generation: String,
        request: String,
        bytes: ByteArray,
        sentAt: Double,
        now: Double,
    ): TelemetryNotifications =
        TelemetryNotifications(
            session.notifications(telemetryDecimal(generation), ControllerRequest.valueOf(request), bytes, sentAt, now)
        )

    fun begin(now: Double, systemInitiated: Boolean): Boolean = session.begin(now, systemInitiated)

    fun advance(generation: String, expected: String, next: String, now: Double): String =
        session
            .advance(telemetryDecimal(generation), TelemetryStage.valueOf(expected), TelemetryStage.valueOf(next), now)
            .name

    fun check(
        now: Double,
        responseSentAt: Double?,
        writeBlockedAt: Double?,
        nativeDisconnecting: Boolean = false,
    ): String = session.check(now, responseSentAt, writeBlockedAt, nativeDisconnecting).name

    fun scheduleRetry(plan: TelemetryRecoveryPlan, now: Double) {
        session.scheduleRetry(plan.core, now)
    }

    fun cancelDuringBackoff() {
        session.cancelDuringBackoff()
    }

    fun disconnected(generation: String, now: Double) {
        session.disconnected(telemetryDecimal(generation), now)
    }

    fun manualDisconnect(cancellationRequired: Boolean) {
        session.manualDisconnect(cancellationRequired)
    }

    fun radioUnavailable() {
        session.radioUnavailable()
    }
}

@JsExport
class TelemetryDiscoveryWindow(generation: String, now: Double) {
    private val core = TelemetryDiscovery.begin(telemetryDecimal(generation), now)
    val deadline: Double
        get() = core.deadline

    fun accepts(generation: String, now: Double): Boolean = core.accepts(telemetryDecimal(generation), now)

    fun expired(now: Double): Boolean = core.expired(now)
}

@JsExport
fun telemetryReplyAdmissible(sentAt: Double, now: Double): Boolean = TelemetryTiming.replyAdmissible(sentAt, now)

@JsExport
fun telemetryWriteExpired(blockedAt: Double, now: Double): Boolean = TelemetryTiming.writeExpired(blockedAt, now)

@JsExport
class CoreMetricDeclaration internal constructor(core: MetricDeclaration) {
    val id: String = core.id
    val label: String = core.label
    val shortLabel: String = core.shortLabel
    val unit: String = core.unit
    val group: String = core.group.name
    val color: String = core.color
    val decimals: Int = core.decimals
    val gapSeconds: Double = core.gapSeconds
    val scale: Double = core.scale
    val kind: String? = core.kind?.name
    val zero: Boolean? = core.zero
    val semantics: String? = core.semantics?.name
    val description: String? = core.description
}

@JsExport
fun coreMetricDeclarations(): Array<CoreMetricDeclaration> =
    MetricCatalog.all.map(::CoreMetricDeclaration).toTypedArray()
