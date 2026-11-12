package app.powerlog.domain.core.controller.cyc

import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerDecoder
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.ControllerFramer
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.ControllerReply
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.DecodeResult
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.controller.TraceContract
import app.powerlog.domain.core.controller.TraceWireField
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.telemetry.cyc.CycException
import app.powerlog.domain.telemetry.cyc.CycFrameDecoder
import app.powerlog.domain.telemetry.cyc.CycIdentityPacket
import app.powerlog.domain.telemetry.cyc.CycProtocol
import app.powerlog.domain.telemetry.cyc.CycRequest
import app.powerlog.domain.telemetry.cyc.CycTelemetryPacket

public object CycDecoder : ControllerDecoder {
    private const val KMH_PER_MPS = 3.6

    override val id: DecoderId = DecoderId("cyc")

    override val transport: TransportProfile =
        TransportProfile(
            service = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            notify = "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
            write = "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            namePrefixes = listOf("CYCMOTOR"),
        )

    override val traceContract: TraceContract =
        TraceContract(
            service = transport.service,
            write = transport.write,
            notify = transport.notify,
            identityRequestHex = CycRequest.identity.frame().toHex(),
            telemetryRequestHex = CycRequest.selective.frame().toHex(),
            telemetryMaskHex = CycProtocol.selectedMask.toUInt().toString(16).padStart(8, '0'),
            fields = CycProtocol.fields.map { TraceWireField(it.metric.name, it.bit, it.width, it.divisor, it.signed) },
        )

    private val measured: Set<MetricId> = CycProtocol.fields.map { MetricId.valueOf(it.metric.name) }.toSet()

    override fun request(kind: ControllerRequest): ControllerFrame =
        when (kind) {
            ControllerRequest.identity -> ControllerFrame(CycRequest.identity.frame())
            ControllerRequest.telemetry -> ControllerFrame(CycRequest.selective.frame())
        }

    override fun newFramer(): ControllerFramer = CycFramer()

    override fun capabilities(identity: ControllerIdentity): ControllerCapabilities {
        val speed = controllerSpeed(identity)
        val derived =
            if (speed) setOf(MetricId.motorInputPowerW, MetricId.controllerSpeedMps)
            else setOf(MetricId.motorInputPowerW)
        return ControllerCapabilities(measured + derived, speed)
    }

    override fun derive(identity: ControllerIdentity, measured: Map<MetricId, Double>): Map<MetricId, Double> {
        val values = measured - MetricId.motorInputPowerW - MetricId.controllerSpeedMps
        val voltage = values[MetricId.batteryVoltageV]
        val current = values[MetricId.batteryCurrentA]
        val power = if (voltage != null && current != null) CycProtocol.batteryInputPower(voltage, current) else null
        val speedRaw = values[MetricId.speedRaw]
        val speed = if (speedRaw != null && controllerSpeed(identity)) speedRaw / KMH_PER_MPS else null
        return values +
            listOfNotNull(power?.let { MetricId.motorInputPowerW to it }) +
            listOfNotNull(speed?.let { MetricId.controllerSpeedMps to it })
    }

    private fun controllerSpeed(identity: ControllerIdentity): Boolean =
        identity.decoder == id && identity.model in setOf("X6", "X12") && identity.protocol == "5.3"

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private class CycFramer : ControllerFramer {
    private val frames = CycFrameDecoder()
    private var reportedDiscards = 0L

    override fun feed(bytes: ByteArray, identity: ControllerIdentity?): DecodeResult {
        val replies = mutableListOf<ControllerReply>()
        var unknownFrames = 0
        var unknownCommand: Int? = null
        for (payload in frames.feed(bytes)) {
            val reply = reply(payload, identity)
            if (reply != null) replies += reply
            else {
                unknownFrames++
                unknownCommand = payload[0].toInt() and 0xff
            }
        }
        return DecodeResult(replies, newDiscards(), frames.pendingBytes, unknownFrames, unknownCommand)
    }

    override fun reset(): DecodeResult {
        frames.reset()
        return DecodeResult(emptyList(), newDiscards(), frames.pendingBytes)
    }

    private fun newDiscards(): Long {
        val total = frames.discardedBytes
        return (total - reportedDiscards).also { reportedDiscards = total }
    }

    private fun reply(payload: ByteArray, identity: ControllerIdentity?): ControllerReply? {
        val kind =
            when (payload.firstOrNull()?.toInt()?.and(0xff)) {
                IDENTITY_FIRMWARE,
                IDENTITY_COMMAND -> ControllerRequest.identity
                TELEMETRY_COMMAND -> ControllerRequest.telemetry
                else -> return null
            }
        return try {
            when (val packet = CycProtocol.packet(payload)) {
                is CycIdentityPacket ->
                    ControllerReply.Identity(
                        ControllerIdentity(
                            decoder = CycDecoder.id,
                            model = packet.identity.controllerModel,
                            firmware = packet.identity.firmwareLabel,
                            protocol = packet.identity.controllerProtocol,
                        )
                    )
                is CycTelemetryPacket -> {
                    val values = packet.telemetry.metrics.mapKeys { MetricId.valueOf(it.key.name) }
                    ControllerReply.Reading(if (identity == null) values else CycDecoder.derive(identity, values))
                }
            }
        } catch (_: CycException) {
            ControllerReply.Refused(kind)
        }
    }

    private companion object {
        const val IDENTITY_FIRMWARE = 0
        const val IDENTITY_COMMAND = 111
        const val TELEMETRY_COMMAND = 50
    }
}
