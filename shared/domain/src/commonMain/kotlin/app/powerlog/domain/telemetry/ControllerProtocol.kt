package app.powerlog.domain.telemetry

enum class LegacyControllerRequest {
    identity,
    telemetry,
}

interface ControllerProtocol {
    fun legacyRequest(kind: ControllerRequest): ByteArray

    fun feed(notification: ByteArray, identity: ControllerIdentity?): ControllerDecodeResult

    fun reset(): ControllerDecodeResult
}

class ControllerDecodeResult
@Throws(IllegalArgumentException::class)
constructor(replies: List<ControllerReply>, val discardedBytes: Long, val pendingBytes: Int) {
    private val decoded = replies.toList()
    val replies: List<ControllerReply>
        get() = decoded.toList()

    init {
        require(discardedBytes >= 0 && pendingBytes >= 0)
    }
}

sealed class LegacyControllerReply(val kind: ControllerRequest)

data class ControllerIdentityReply(val identity: ControllerIdentity) : ControllerReply(ControllerRequest.identity)

data class ControllerReadingReply(val reading: TelemetryReading) : ControllerReply(ControllerRequest.telemetry)

class ControllerRejectedReply
@Throws(IllegalArgumentException::class)
constructor(kind: ControllerRequest, val reason: String) : ControllerReply(kind) {
    init {
        require(reason.isNotBlank())
    }
}

public typealias ControllerReply = LegacyControllerReply

public typealias ControllerRequest = LegacyControllerRequest
