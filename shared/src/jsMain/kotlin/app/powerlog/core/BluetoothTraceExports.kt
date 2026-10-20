@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.telemetry.BluetoothTrace as CoreBluetoothTrace
import kotlin.js.JsExport

@JsExport
class BluetoothTrace {
    private val core = CoreBluetoothTrace()

    fun start(now: Double) = core.start(now)

    fun stop() = core.stop()

    fun clear() = core.clear()

    fun record(now: Double, event: String, detail: String, bytes: ByteArray?) = core.record(now, event, detail, bytes)

    fun status(now: Double): BluetoothTraceStatus = BluetoothTraceStatus(core.status(now))

    fun exportJson(now: Double): String = core.exportJson(now)
}

@JsExport
class BluetoothTraceStatus internal constructor(state: app.powerlog.core.telemetry.BluetoothTraceStatus) {
    val capturing = state.capturing
    val eventCount = state.eventCount
    val byteCount = state.byteCount
    val stopReason = state.stopReason
    val lastEvent = state.lastEvent
}
