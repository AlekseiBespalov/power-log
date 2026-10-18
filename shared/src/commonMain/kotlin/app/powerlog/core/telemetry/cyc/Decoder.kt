package app.powerlog.core.telemetry.cyc

import app.powerlog.core.telemetry.*

private val protocolName = Regex("[0-9]+\\.[0-9]+")

class CycControllerDecoder : ControllerProtocol, ControllerRestorer {
    private val frames = CycFrameDecoder()

    override fun supportsProtocol(controllerProtocol: String): Boolean = protocolName.matches(controllerProtocol)

    override fun restoreIdentity(
        controllerModel: String,
        firmwareLabel: String,
        controllerProtocol: String,
    ): ControllerIdentity = CycProtocol.restoreIdentity(controllerModel, firmwareLabel, controllerProtocol)

    override fun restoreReading(metrics: Map<String, Double>, identity: ControllerIdentity): TelemetryReading =
        CycProtocol.restoreReading(metrics, identity)

    override fun request(kind: ControllerRequest): ByteArray =
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
