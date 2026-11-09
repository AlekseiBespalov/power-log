package app.powerlog.domain.telemetry.cyc

import app.powerlog.domain.telemetry.*

class CycControllerDecoder : ControllerProtocol {
    private val frames = CycFrameDecoder()

    override fun legacyRequest(kind: ControllerRequest): ByteArray =
        when (kind) {
            ControllerRequest.identity -> CycRequest.identity.frame()
            ControllerRequest.telemetry -> CycRequest.selective.frame()
        }

    override fun feed(notification: ByteArray, identity: ControllerIdentity?): ControllerDecodeResult {
        val replies =
            frames.feed(notification).mapNotNull { payload ->
                val role =
                    when (payload.firstOrNull()?.toInt()?.and(255)) {
                        0,
                        111 -> ControllerRequest.identity
                        50 -> ControllerRequest.telemetry
                        else -> return@mapNotNull null
                    }
                try {
                    when (val packet = CycProtocol.packet(payload)) {
                        is CycIdentityPacket -> ControllerIdentityReply(packet.identity)
                        is CycTelemetryPacket ->
                            ControllerReadingReply(
                                if (identity == null) packet.telemetry
                                else CycProtocol.withIdentity(packet.telemetry, identity)
                            )
                    }
                } catch (error: CycException) {
                    ControllerRejectedReply(role, error.reason.name)
                }
            }
        return ControllerDecodeResult(replies, frames.discardedBytes, frames.pendingBytes)
    }

    override fun reset(): ControllerDecodeResult {
        frames.reset()
        return ControllerDecodeResult(emptyList(), frames.discardedBytes, frames.pendingBytes)
    }
}
