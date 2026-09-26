package app.powerlog.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The only outbound controller operations are the two read requests below. */
object CycProtocol {
    const val SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    const val MASK = 0x03c0fb8f

    enum class Read {
        IDENTITY,
        TELEMETRY,
    }

    fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 65535 }
        }
        return crc
    }

    fun request(read: Read): ByteArray {
        val payload =
            if (read == Read.IDENTITY) byteArrayOf(111)
            else byteArrayOf(50, 3, 0xc0.toByte(), 0xfb.toByte(), 0x8f.toByte())
        val crc = crc(payload)
        return byteArrayOf(2, payload.size.toByte()) +
            payload +
            byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
    }

    data class Identity(val model: String, val firmware: String, val protocol: String) {
        val knownSpeed
            get() = model in listOf("X6", "X12") && protocol == "5.3"
    }

    fun identity(bytes: ByteArray): Identity {
        require(bytes.size in 5..1024 && (bytes[0].toInt() and 255) in listOf(0, 111)) {
            "Unsupported controller"
        }
        val end =
            (3 until bytes.size).firstOrNull { bytes[it] == 0.toByte() }
                ?: error("Invalid controller identity")
        require(end - 3 in 1..128)
        val text = bytes.copyOfRange(3, end).toString(Charsets.US_ASCII)
        val match =
            Regex("^(X(?:6|12)(?:[A-Za-z_][A-Za-z0-9_]{0,29})?) +([0-9]{6,8}[A-Z]{0,8})(?= |$)")
                .find(text) ?: error("Unsupported controller. Connect a CYC X6 or X12.")
        return Identity(
            match.groupValues[1],
            match.groupValues[2],
            "${bytes[1].toInt() and 255}.${bytes[2].toInt() and 255}",
        )
    }

    private data class Field(val name: String, val bytes: Int, val scale: Double)

    private val fields =
        listOf(
            Field("controllerTempC", 2, 10.0),
            Field("motorTempC", 2, 10.0),
            Field("motorCurrentA", 4, 100.0),
            Field("batteryCurrentA", 4, 100.0),
            Field("idCurrentA", 4, 100.0),
            Field("iqCurrentA", 4, 100.0),
            Field("dutyCycle", 2, 1000.0),
            Field("motorRpm", 4, 1.0),
            Field("batteryVoltageV", 2, 10.0),
            Field("consumedAh", 4, 10000.0),
            Field("tripTimeRaw", 4, 1.0),
            Field("consumedWh", 4, 10000.0),
            Field("cadenceRpm", 4, 10000.0),
            Field("throttleVoltageV", 4, 100.0),
            Field("pedalTorqueNm", 4, 100.0),
            Field("faultCode", 1, 1.0),
            Field("ioFlags", 4, 1.0),
            Field("controllerId", 1, 1.0),
            Field("temperatures", 6, 10.0),
            Field("vdV", 4, 1000.0),
            Field("vqV", 4, 1000.0),
            Field("odometerRaw", 4, 1.0),
            Field("humanPowerW", 4, 1.0),
            Field("speedRaw", 4, 100.0),
            Field("raceMode", 1, 1.0),
            Field("assistLevel", 1, 1.0),
        )

    fun telemetry(bytes: ByteArray, identity: Identity): Map<String, Double> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.remaining() >= 5 && buffer.get() == 50.toByte() && buffer.int == MASK) {
            "Unexpected telemetry response"
        }
        val values = linkedMapOf<String, Double>()
        fields.forEachIndexed { bit, field ->
            if (MASK and (1 shl bit) != 0) {
                require(buffer.remaining() >= field.bytes) { "Truncated telemetry" }
                val raw =
                    when (field.bytes) {
                        1 -> buffer.get().toInt() and 255
                        2 -> buffer.short.toInt()
                        else -> buffer.int
                    }
                values[field.name] = raw / field.scale
            }
        }
        require(!buffer.hasRemaining()) { "Unexpected telemetry length" }
        values["motorInputPowerW"] =
            Math.round(
                values.getValue("batteryVoltageV") * values.getValue("batteryCurrentA") * 10000
            ) / 10000.0
        if (identity.knownSpeed) values["controllerSpeedMps"] = values.getValue("speedRaw") / 3.6
        return values
    }

    class Decoder {
        private var buffer = byteArrayOf()
        var discarded = 0L
            private set

        fun reset() {
            discarded += buffer.size
            buffer = byteArrayOf()
        }

        fun feed(bytes: ByteArray): List<ByteArray> {
            require(bytes.size <= 65536)
            buffer += bytes
            val result = mutableListOf<ByteArray>()
            while (buffer.isNotEmpty()) {
                var incomplete: Int? = null
                var found = false
                for (offset in buffer.indices) {
                    val start = buffer[offset].toInt() and 255
                    if (start != 2 && start != 3) continue
                    val header = if (start == 2) 2 else 3
                    if (buffer.size - offset < header) {
                        if (incomplete == null) incomplete = offset
                        continue
                    }
                    val length =
                        if (start == 2) buffer[offset + 1].toInt() and 255
                        else
                            ((buffer[offset + 1].toInt() and 255) shl 8) or
                                (buffer[offset + 2].toInt() and 255)
                    if (length !in 1..1024 || start == 3 && length <= 255) continue
                    val size = header + length + 3
                    if (buffer.size - offset < size) {
                        if (incomplete == null) incomplete = offset
                        continue
                    }
                    val payload = buffer.copyOfRange(offset + header, offset + header + length)
                    val pos = offset + header + length
                    val checksum =
                        ((buffer[pos].toInt() and 255) shl 8) or (buffer[pos + 1].toInt() and 255)
                    if (buffer[offset + size - 1] != 3.toByte() || crc(payload) != checksum)
                        continue
                    result.add(payload)
                    discarded += offset
                    buffer = buffer.copyOfRange(offset + size, buffer.size)
                    found = true
                    break
                }
                if (found) continue
                val drop = incomplete ?: buffer.size
                discarded += drop
                buffer = buffer.copyOfRange(drop, buffer.size)
                break
            }
            return result
        }
    }
}
