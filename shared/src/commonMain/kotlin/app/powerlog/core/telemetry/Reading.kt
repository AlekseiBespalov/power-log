package app.powerlog.core.telemetry

enum class TelemetryRejection {
    identity,
    retainedMetrics,
    invalidEvidence,
}

class TelemetryException(val reason: TelemetryRejection) : IllegalArgumentException(reason.name)

enum class TelemetryMetric {
    humanPowerW,
    cadenceRpm,
    heartRateBpm,
    pedalTorqueNm,
    activeEnergyKcal,
    basalEnergyKcal,
    batteryVoltageV,
    batteryCurrentA,
    motorInputPowerW,
    consumedAh,
    consumedWh,
    motorCurrentA,
    motorTempC,
    controllerTempC,
    motorRpm,
    throttleVoltageV,
    speedMps,
    controllerSpeedMps,
    distanceMeters,
    altitudeMeters,
    horizontalAccuracyM,
    verticalAccuracyM,
    courseDegrees,
    assistLevel,
    raceMode,
    faultCode,
    speedRaw,
}

class ControllerCapabilities
@Throws(TelemetryException::class)
constructor(suppliedMetrics: Set<TelemetryMetric>, val hasKnownSpeedUnit: Boolean) {
    private val supplied = suppliedMetrics.toSet()
    val suppliedMetrics: Set<TelemetryMetric>
        get() = supplied.toSet()

    init {
        if (supplied.isEmpty()) throw TelemetryException(TelemetryRejection.identity)
    }

    fun supplies(metric: TelemetryMetric): Boolean = metric in supplied

    override fun equals(other: Any?): Boolean =
        other is ControllerCapabilities && supplied == other.supplied && hasKnownSpeedUnit == other.hasKnownSpeedUnit

    override fun hashCode(): Int = 31 * supplied.hashCode() + hasKnownSpeedUnit.hashCode()
}

class ControllerIdentity
@Throws(TelemetryException::class)
internal constructor(
    val controllerModel: String,
    val firmwareLabel: String,
    val controllerProtocol: String,
    val capabilities: ControllerCapabilities,
    internal val verifier: Any?,
) {
    @Throws(TelemetryException::class)
    constructor(
        controllerModel: String,
        firmwareLabel: String,
        controllerProtocol: String,
        capabilities: ControllerCapabilities,
    ) : this(controllerModel, firmwareLabel, controllerProtocol, capabilities, null)

    init {
        if (
            listOf(controllerModel, firmwareLabel, controllerProtocol).any {
                it.isEmpty() ||
                    it.length > 128 ||
                    it != it.trim() ||
                    it.any { character -> character.code !in 0x20..0x7e }
            }
        )
            throw TelemetryException(TelemetryRejection.identity)
    }

    val hasKnownSpeedUnit: Boolean
        get() = capabilities.hasKnownSpeedUnit

    override fun equals(other: Any?): Boolean =
        other is ControllerIdentity &&
            controllerModel == other.controllerModel &&
            firmwareLabel == other.firmwareLabel &&
            controllerProtocol == other.controllerProtocol &&
            capabilities == other.capabilities

    override fun hashCode(): Int =
        31 * (31 * (31 * controllerModel.hashCode() + firmwareLabel.hashCode()) + controllerProtocol.hashCode()) +
            capabilities.hashCode()
}

class TelemetryReading
@Throws(TelemetryException::class)
constructor(values: Map<TelemetryMetric, Double>, val identity: ControllerIdentity? = null) {
    private val values = values.toMap()
    val metrics: Map<TelemetryMetric, Double>
        get() = values.toMap()

    init {
        if (
            this.values.isEmpty() ||
                this.values.values.any { !it.isFinite() } ||
                (identity != null && this.values.keys.any { !identity.capabilities.supplies(it) })
        )
            throw TelemetryException(TelemetryRejection.retainedMetrics)
    }

    fun value(metric: TelemetryMetric): Double? = values[metric]

    override fun equals(other: Any?): Boolean =
        other is TelemetryReading && values == other.values && identity == other.identity

    override fun hashCode(): Int = 31 * values.hashCode() + (identity?.hashCode() ?: 0)
}
