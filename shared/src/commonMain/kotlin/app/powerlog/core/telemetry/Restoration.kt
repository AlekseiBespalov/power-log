package app.powerlog.core.telemetry

import app.powerlog.core.telemetry.cyc.CycControllerDecoder

interface ControllerRestorer {
    fun supportsProtocol(controllerProtocol: String): Boolean

    @Throws(IllegalArgumentException::class)
    fun restoreIdentity(controllerModel: String, firmwareLabel: String, controllerProtocol: String): ControllerIdentity

    @Throws(IllegalArgumentException::class)
    fun restoreReading(metrics: Map<String, Double>, identity: ControllerIdentity): TelemetryReading
}

class RestoredTelemetry(val identity: ControllerIdentity, val reading: TelemetryReading?)

object TelemetryRestoration {
    private val decoders: List<ControllerRestorer> = listOf(CycControllerDecoder())

    @Throws(IllegalArgumentException::class)
    fun restore(
        controllerModel: String,
        firmwareLabel: String,
        controllerProtocol: String,
        metrics: Map<String, Double>?,
        restorers: List<ControllerRestorer> = emptyList(),
    ): RestoredTelemetry {
        val decoder =
            (decoders + restorers).singleOrNull { it.supportsProtocol(controllerProtocol) }
                ?: throw TelemetryException(TelemetryRejection.identity)
        val identity = decoder.restoreIdentity(controllerModel, firmwareLabel, controllerProtocol)
        if (
            identity.controllerModel != controllerModel ||
                identity.firmwareLabel != firmwareLabel ||
                identity.controllerProtocol != controllerProtocol
        )
            throw TelemetryException(TelemetryRejection.identity)
        val reading = metrics?.let { decoder.restoreReading(it, identity) }
        if (reading != null && reading.identity != identity)
            throw TelemetryException(TelemetryRejection.retainedMetrics)
        return RestoredTelemetry(identity, reading)
    }
}
