@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package app.powerlog.core

import kotlin.js.JsExport

@JsExport
fun controllerStreamStatus(requested: Boolean, phase: String, age: Double?): String =
    StreamStatus.controller(requested, phase, age)

@JsExport
fun gpsStreamStatus(
    requested: Boolean,
    phase: String,
    age: Double?,
    accuracy: Double?,
    providerStatus: String?,
): String = StreamStatus.gps(requested, phase, age, accuracy, providerStatus)
