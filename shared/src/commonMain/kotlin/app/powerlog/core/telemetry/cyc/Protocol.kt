package app.powerlog.core.telemetry.cyc

import app.powerlog.core.telemetry.*
import kotlin.math.floor

enum class CycRejection {
    identity,
    telemetryCommandOrLength,
    telemetryMask,
    unknownPacket,
    retainedMetrics,
}

class CycException(val reason: CycRejection) : IllegalArgumentException(reason.name)

// Framing, CRC and command 50 (COMM_GET_VALUES_SELECTIVE) follow the VESC packet protocol of the controller's bldc
// firmware, where reply command 0 is COMM_FW_VERSION: https://github.com/vedderb/bldc/blob/master/datatypes.h
enum class CycRequest(val command: Int) {
    identity(111),
    selective(50);

    fun frame(): ByteArray {
        val payload =
            if (this == identity) byteArrayOf(command.toByte())
            else byteArrayOf(command.toByte()) + ByteArray(4) { (CycProtocol.selectedMask ushr (24 - it * 8)).toByte() }
        val crc = CycProtocol.crc(payload)
        return byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
    }
}

data class CycField(
    val metric: TelemetryMetric,
    val bit: Int,
    val width: Int,
    val divisor: Double,
    val signed: Boolean,
)

sealed class CycPacket

data class CycIdentityPacket(val identity: ControllerIdentity) : CycPacket()

data class CycTelemetryPacket(val telemetry: TelemetryReading) : CycPacket()

object CycProtocol {
    const val selectedMask: Int = 0x03c0fb8f
    const val telemetryPayloadBytes: Int = 54
    val fields: List<CycField>
        get() = layout.toList()

    private val layout =
        listOf(
            CycField(TelemetryMetric.controllerTempC, 0, 2, 10.0, true),
            CycField(TelemetryMetric.motorTempC, 1, 2, 10.0, true),
            CycField(TelemetryMetric.motorCurrentA, 2, 4, 100.0, true),
            CycField(TelemetryMetric.batteryCurrentA, 3, 4, 100.0, true),
            CycField(TelemetryMetric.motorRpm, 7, 4, 1.0, true),
            CycField(TelemetryMetric.batteryVoltageV, 8, 2, 10.0, true),
            CycField(TelemetryMetric.consumedAh, 9, 4, 10000.0, true),
            CycField(TelemetryMetric.consumedWh, 11, 4, 10000.0, true),
            CycField(TelemetryMetric.cadenceRpm, 12, 4, 10000.0, true),
            CycField(TelemetryMetric.throttleVoltageV, 13, 4, 100.0, true),
            CycField(TelemetryMetric.pedalTorqueNm, 14, 4, 100.0, true),
            CycField(TelemetryMetric.faultCode, 15, 1, 1.0, false),
            CycField(TelemetryMetric.humanPowerW, 22, 4, 1.0, true),
            CycField(TelemetryMetric.speedRaw, 23, 4, 100.0, true),
            CycField(TelemetryMetric.raceMode, 24, 1, 1.0, false),
            CycField(TelemetryMetric.assistLevel, 25, 1, 1.0, false),
        )
    private val identityPattern = Regex("X(6|12)([A-Za-z_][A-Za-z0-9_]{0,29})? +[0-9]{6,8}[A-Z]{0,8}")
    private val protocolPattern = Regex("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})")
    internal var identityCheckObserverForTesting: (() -> Unit)? = null

    @Throws(CycException::class)
    fun restoreIdentity(
        controllerModel: String,
        firmwareLabel: String,
        controllerProtocol: String,
    ): ControllerIdentity {
        identityCheckObserverForTesting?.invoke()
        if (
            !identityPattern.matches("$controllerModel $firmwareLabel") ||
                controllerModel.contains(' ') ||
                firmwareLabel.contains(' ') ||
                !protocolPattern.matches(controllerProtocol)
        )
            throw CycException(CycRejection.identity)
        val parts = controllerProtocol.split('.').map(String::toInt)
        if (parts.any { it > 255 }) throw CycException(CycRejection.identity)
        // In CYC firmware 5.3 field 23 is app_get_speed() in km/h, scaled by 100 in commands.c:
        // https://github.com/CYC-MOTOR/CYC-firmware/blob/d97bb381d11a7f7a0d2d93f2fc1612887ba59bc1/bldc-release_5_03-controller%20v2.7z
        val knownSpeed = controllerModel in listOf("X6", "X12") && parts[0] == 5 && parts[1] == 3
        val metrics =
            layout.map { it.metric }.toSet() +
                TelemetryMetric.motorInputPowerW +
                if (knownSpeed) setOf(TelemetryMetric.controllerSpeedMps) else emptySet()
        return ControllerIdentity(
            controllerModel,
            firmwareLabel,
            controllerProtocol,
            ControllerCapabilities(metrics, knownSpeed),
            this,
        )
    }

    @Throws(CycException::class)
    fun restoreReading(metrics: Map<String, Double>, identity: ControllerIdentity): TelemetryReading {
        fun reject(): Nothing = throw CycException(CycRejection.retainedMetrics)
        if (
            identity.verifier !== this &&
                identity !=
                    restoreIdentity(identity.controllerModel, identity.firmwareLabel, identity.controllerProtocol)
        )
            reject()
        val values = metrics.mapKeys { (name, _) ->
            TelemetryMetric.entries.firstOrNull { it.name == name } ?: reject()
        }
        val expected =
            layout.map { it.metric }.toSet() +
                TelemetryMetric.motorInputPowerW +
                if (identity.hasKnownSpeedUnit) setOf(TelemetryMetric.controllerSpeedMps) else emptySet()
        if (values.keys != expected || values.values.any { !it.isFinite() }) reject()
        for (field in layout) {
            val value = values.getValue(field.metric)
            val raw = floor(value * field.divisor + 0.5)
            val minimum = if (!field.signed) 0.0 else if (field.width == 2) -32768.0 else Int.MIN_VALUE.toDouble()
            val maximum = if (!field.signed) 255.0 else if (field.width == 2) 32767.0 else Int.MAX_VALUE.toDouble()
            if (raw !in minimum..maximum || (raw / field.divisor).toBits() != value.toBits()) reject()
        }
        if (values.getValue(TelemetryMetric.motorInputPowerW).toBits() != batteryInputPower(values).toBits()) reject()
        if (
            identity.hasKnownSpeedUnit &&
                values.getValue(TelemetryMetric.controllerSpeedMps).toBits() !=
                    (values.getValue(TelemetryMetric.speedRaw) / 3.6).toBits()
        )
            reject()
        return TelemetryReading(values, identity)
    }

    @Throws(CycException::class)
    fun withIdentity(reading: TelemetryReading, identity: ControllerIdentity): TelemetryReading {
        val values =
            reading.metrics - TelemetryMetric.controllerSpeedMps +
                if (identity.hasKnownSpeedUnit)
                    mapOf(
                        TelemetryMetric.controllerSpeedMps to
                            (reading.value(TelemetryMetric.speedRaw)
                                ?: throw CycException(CycRejection.retainedMetrics)) / 3.6
                    )
                else emptyMap()
        return restoreReading(values.mapKeys { it.key.name }, identity)
    }

    private fun batteryInputPower(values: Map<TelemetryMetric, Double>): Double =
        floor(
            values.getValue(TelemetryMetric.batteryVoltageV) *
                values.getValue(TelemetryMetric.batteryCurrentA) *
                10000 + 0.5
        ) / 10000

    fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        return crc
    }

    @Throws(IllegalArgumentException::class)
    fun identity(payload: ByteArray): ControllerIdentity {
        fun reject(): Nothing = throw CycException(CycRejection.identity)
        if (payload.size !in 4..1024 || (payload[0].toInt() and 255) !in listOf(0, 111)) reject()
        val end = (3 until payload.size).firstOrNull { payload[it] == 0.toByte() } ?: reject()
        if (end - 3 > 128) reject()
        val ascii =
            payload
                .sliceArray(3 until end)
                .takeWhile { (it.toInt() and 255) in 0x20..0x7e }
                .map { (it.toInt() and 255).toChar() }
                .joinToString("")
        val prefix = identityPattern.find(ascii)?.takeIf { it.range.first == 0 }?.value ?: reject()
        val boundary = 3 + prefix.length
        if (boundary != end && payload[boundary] != 0x20.toByte()) reject()
        val parts = prefix.split(' ').filter { it.isNotEmpty() }
        return restoreIdentity(parts[0], parts[1], "${payload[1].toInt() and 255}.${payload[2].toInt() and 255}")
    }

    @Throws(CycException::class)
    fun telemetry(payload: ByteArray): TelemetryReading {
        if (payload.size != telemetryPayloadBytes || payload[0] != 50.toByte())
            throw CycException(CycRejection.telemetryCommandOrLength)
        var cursor = 1
        fun unsigned(width: Int): Int {
            var value = 0
            repeat(width) { value = (value shl 8) or (payload[cursor++].toInt() and 255) }
            return value
        }
        if (unsigned(4) != selectedMask) throw CycException(CycRejection.telemetryMask)
        val values =
            layout
                .associate { field ->
                    val raw = unsigned(field.width)
                    val value = if (field.signed && field.width == 2) raw.toShort().toInt() else raw
                    field.metric to value.toDouble() / field.divisor
                }
                .toMutableMap()
        values[TelemetryMetric.motorInputPowerW] = batteryInputPower(values)
        return TelemetryReading(values)
    }

    @Throws(CycException::class)
    fun packet(payload: ByteArray): CycPacket =
        when (payload.firstOrNull()?.toInt()?.and(255)) {
            0,
            111 -> CycIdentityPacket(identity(payload))
            50 -> CycTelemetryPacket(telemetry(payload))
            else -> throw CycException(CycRejection.unknownPacket)
        }
}

internal class CycFrameDecoder {
    private var buffer = byteArrayOf()
    var discardedBytes: Long = 0
        private set

    val pendingBytes: Int
        get() = buffer.size

    fun reset() {
        discardedBytes += buffer.size
        buffer = byteArrayOf()
    }

    fun feed(bytes: ByteArray): List<ByteArray> {
        buffer += bytes
        val packets = mutableListOf<ByteArray>()
        while (buffer.isNotEmpty()) {
            var incomplete: Int? = null
            var found = false
            for (offset in buffer.indices) {
                val start = buffer[offset].toInt() and 255
                if (start != 2 && start != 3) continue
                val header = if (start == 2) 2 else 3
                val remaining = buffer.size - offset
                if (remaining < header) {
                    if (incomplete == null) incomplete = offset
                    continue
                }
                val length =
                    if (start == 2) buffer[offset + 1].toInt() and 255
                    else ((buffer[offset + 1].toInt() and 255) shl 8) or (buffer[offset + 2].toInt() and 255)
                if (length !in 1..1024 || (start == 3 && length <= 255)) continue
                val size = header + length + 3
                if (remaining < size) {
                    if (incomplete == null) incomplete = offset
                    continue
                }
                val payload = buffer.copyOfRange(offset + header, offset + header + length)
                val crc =
                    ((buffer[offset + size - 3].toInt() and 255) shl 8) or (buffer[offset + size - 2].toInt() and 255)
                if (buffer[offset + size - 1] != 3.toByte() || CycProtocol.crc(payload) != crc) continue
                packets.add(payload)
                discardedBytes += offset
                buffer = buffer.copyOfRange(offset + size, buffer.size)
                found = true
                break
            }
            if (found) continue
            val discarded = incomplete ?: buffer.size
            discardedBytes += discarded
            buffer = buffer.copyOfRange(discarded, buffer.size)
            break
        }
        if (buffer.size > 1029) reset()
        return packets
    }
}
