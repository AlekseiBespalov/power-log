@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.core.telemetry.cyc

import app.powerlog.core.ControllerProtocolApi
import kotlin.js.JsExport

@JsExport
class ControllerWireField internal constructor(field: CycField) {
    val metric: String = field.metric.name
    val bit: Int = field.bit
    val width: Int = field.width
    val divisor: Double = field.divisor
    val signed: Boolean = field.signed
}

@JsExport fun controllerSelectedMask(): Int = CycProtocol.selectedMask

@JsExport
fun controllerWireFields(): Array<ControllerWireField> = CycProtocol.fields.map(::ControllerWireField).toTypedArray()

@JsExport fun controllerCrc(bytes: ByteArray): Int = CycProtocol.crc(bytes)

@JsExport fun controllerProtocol(): ControllerProtocolApi = ControllerProtocolApi(CycControllerDecoder())
