package app.powerlog.domain.core.controller

import app.powerlog.domain.core.controller.cyc.CycDecoder
import app.powerlog.domain.core.metric.MetricId
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable @JvmInline public value class DecoderId(public val value: String)

@Serializable
public data class ControllerIdentity(
    public val decoder: DecoderId,
    public val model: String,
    public val firmware: String,
    public val protocol: String,
)

public data class ControllerCapabilities(public val metrics: Set<MetricId>, public val controllerSpeed: Boolean)

public data class TransportProfile(
    public val service: String,
    public val notify: String,
    public val write: String,
    public val namePrefixes: List<String>,
)

@Serializable
public enum class ControllerRequest {
    identity,
    telemetry,
}

@Serializable
public enum class GattResult {
    ready,
    serviceMissing,
    characteristicMissing,
    notifyFailed,
}

public class ControllerFrame internal constructor(public val bytes: ByteArray)

public interface ControllerDecoder {
    public val id: DecoderId
    public val transport: TransportProfile
    public val traceContract: TraceContract

    public fun request(kind: ControllerRequest): ControllerFrame

    public fun newFramer(): ControllerFramer

    public fun capabilities(identity: ControllerIdentity): ControllerCapabilities

    public fun derive(identity: ControllerIdentity, measured: Map<MetricId, Double>): Map<MetricId, Double>
}

public interface ControllerFramer {
    public fun feed(bytes: ByteArray, identity: ControllerIdentity?): DecodeResult

    public fun reset(): DecodeResult
}

@Serializable
public data class TraceContract(
    public val service: String,
    public val write: String,
    public val notify: String,
    public val identityRequestHex: String,
    public val telemetryRequestHex: String,
    public val telemetryMaskHex: String,
    public val fields: List<TraceWireField>,
)

@Serializable
public data class TraceWireField(
    public val metric: String,
    public val bit: Int,
    public val width: Int,
    public val divisor: Double,
    public val signed: Boolean,
)

public data class DecodeResult(
    public val replies: List<ControllerReply>,
    public val discardedBytes: Long,
    public val pendingBytes: Int,
)

public sealed interface ControllerReply {
    public data class Identity(public val identity: ControllerIdentity) : ControllerReply

    public data class Reading(public val values: Map<MetricId, Double>) : ControllerReply

    public data class Refused(public val kind: ControllerRequest) : ControllerReply
}

public object ControllerDecoders {
    public val all: List<ControllerDecoder>
        get() = listOf(CycDecoder)

    public fun of(id: DecoderId): ControllerDecoder? = all.firstOrNull { it.id == id }

    public fun forTransport(service: String): ControllerDecoder? = all.firstOrNull {
        it.transport.service.equals(service, ignoreCase = true)
    }
}

public object ControllerPolicy {
    public const val replyUs: Long = 2_500_000L
    public const val resendUs: Long = 1_000_000L
    public const val silenceUs: Long = 3_000_000L
    public const val linkReleaseUs: Long = 2_000_000L
    public const val searchUs: Long = 20_000_000L
    public const val connectUs: Long = 20_000_000L
    public const val cancelGraceUs: Long = 5_000_000L
    public val reconnectBackoffUs: List<Long> = listOf(1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_000_000L)
    public const val rideReconnectUs: Long = 30_000_000L
    public const val stableLinkUs: Long = 30_000_000L
    public const val linkLimitDrops: Int = 3
    public const val linkLimitSpreadUs: Long = 1_000_000L
    public const val linkLimitMarginUs: Long = 2_000_000L
    public const val unknownIdentityAttempts: Int = 2
    public const val backgroundPollHz: Int = 1
    public const val joinUs: Long = 6_000_000L
}
