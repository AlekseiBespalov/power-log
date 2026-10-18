@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.protocol.Timestamps
import kotlin.js.JsExport

@JsExport
fun protocolTimestampMicroseconds(timestamp: String): String = Timestamps.parse(timestamp).comparisonUs.toString()
