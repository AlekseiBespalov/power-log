package app.powerlog.domain.telemetry

import app.powerlog.domain.protocol.*

class BluetoothTraceStatus(
    val capturing: Boolean,
    val eventCount: Int,
    val byteCount: Int,
    val stopReason: String?,
    val lastEvent: String?,
)

/**
 * Explicit, temporary raw capture. Adapters supply a monotonic clock and serialize access on their BLE queue. Never
 * attached to ride exports or routine diagnostics: bytes may contain a private controller serial.
 */
class BluetoothTrace {
    private val entries = mutableListOf<String>()
    private var started: Double? = null
    private var capturing = false
    private var byteCount = 0
    private var stopReason: String? = null
    private var lastEvent: String? = null

    fun start(now: Double) {
        require(now.isFinite())
        clear()
        started = now
        capturing = true
    }

    fun stop() {
        if (capturing) stopReason = "manual"
        capturing = false
    }

    fun clear() {
        entries.clear()
        started = null
        capturing = false
        byteCount = 0
        stopReason = null
        lastEvent = null
    }

    fun status(now: Double): BluetoothTraceStatus {
        if (capturing && (!now.isFinite() || now - checkNotNull(started) >= 120)) {
            capturing = false
            stopReason = "timeLimit"
        }
        return BluetoothTraceStatus(capturing, entries.size, byteCount, stopReason, lastEvent)
    }

    fun record(now: Double, event: String, detail: String, bytes: ByteArray?) {
        if (!status(now).capturing) return
        val captured = bytes?.take(4096)
        val fields =
            linkedMapOf<String, Json>(
                "elapsedSeconds" to Num(maxOf(0.0, now - checkNotNull(started))),
                "event" to Str(event.take(64)),
                "detail" to Str(detail.take(2048)),
            )
        if (bytes != null) {
            fields["byteLength"] = Num(bytes.size.toDouble())
            fields["capturedBytes"] = Num(checkNotNull(captured).size.toDouble())
            fields["hex"] = Str(captured.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
        }
        val line = JsonCodec.encode(Obj(fields))
        val size = line.encodeToByteArray().size + 1
        // Keep the initial handshake rather than rotating it out during a noisy connection.
        if (entries.size >= 4000 || byteCount + size > 1024 * 1024) {
            capturing = false
            stopReason = "sizeLimit"
            return
        }
        entries.add(line)
        byteCount += size
        lastEvent = "$event: ${detail.take(256)}"
    }

    fun exportJson(now: Double): String {
        val state = status(now)
        val metadata =
            JsonCodec.encode(
                Obj(
                    mapOf(
                        "format" to Str("power-log-bluetooth-trace"),
                        "version" to Num(1.0),
                        "capturing" to Bool(state.capturing),
                        "stopReason" to (state.stopReason?.let(::Str) ?: Nil),
                        "eventCount" to Num(state.eventCount.toDouble()),
                        "byteCount" to Num(state.byteCount.toDouble()),
                        "notice" to
                            Str(
                                "Raw Bluetooth data may contain identifiers. Share only intentionally. No GPS or Health data."
                            ),
                    )
                )
            )
        return metadata.dropLast(1) + ",\"events\":[" + entries.joinToString(",") + "]}"
    }
}
