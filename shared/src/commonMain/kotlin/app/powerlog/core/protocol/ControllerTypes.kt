package app.powerlog.core.protocol

data class RideEndpoints(val phoneEndpointId: String, val watchEndpointId: String)

enum class ControllerAbsence {
    noValidReading,
    noCoverage,
    inactive,
    invalid,
    numericOverflow,
}

sealed class ControllerContent

data class ControllerPresent(val value: Double) : ControllerContent()

data class ControllerAbsent(val reason: ControllerAbsence) : ControllerContent()

data class ControllerMetric(val contentVersion: Long, val sourceRevision: Long, val content: ControllerContent)

data class ControllerSecond(
    val n: Long,
    val endOffsetUs: Long,
    val power: ControllerMetric,
    val cadence: ControllerMetric,
) {
    internal val canonicalUtf8: String
        get() = JsonCodec.encode(json())

    internal val canonicalBytes: ByteArray
        get() = canonicalUtf8.encodeToByteArray()
}

sealed class DisplayDistance {
    abstract val generation: Long
    abstract val projectionSequence: Long
    abstract val selection: String
}

data class AvailableDistance(
    override val generation: Long,
    override val projectionSequence: Long,
    override val selection: String,
    val source: String,
    val meters: Double,
    val partial: Boolean,
    val estimated: Boolean,
) : DisplayDistance()

data class UnavailableDistance(
    override val generation: Long,
    override val projectionSequence: Long,
    override val selection: String,
) : DisplayDistance()

sealed class ControllerDisplay {
    abstract val distance: DisplayDistance?
}

data class ControllerReading(
    val sourceSequence: Long,
    val ageSeconds: Double,
    val humanPowerW: Double?,
    val cadenceRpm: Double?,
    override val distance: DisplayDistance? = null,
) : ControllerDisplay()

data class ControllerDistanceOnly(override val distance: DisplayDistance) : ControllerDisplay()

internal fun ControllerContent.json(): Obj =
    when (this) {
        is ControllerPresent -> obj("state" to "present".json(), "value" to value.json())
        is ControllerAbsent -> obj("state" to "absent".json(), "reason" to reason.name.json())
    }

internal fun ControllerMetric.json() =
    obj(
        "contentVersion" to contentVersion.json(),
        "sourceRevision" to sourceRevision.json(),
        "content" to content.json(),
    )

internal fun ControllerSecond.json() =
    obj("n" to n.json(), "endOffsetUs" to endOffsetUs.json(), "power" to power.json(), "cadence" to cadence.json())

internal fun DisplayDistance.json(): Obj =
    when (this) {
        is AvailableDistance ->
            obj(
                "generation" to generation.json(),
                "projectionSequence" to projectionSequence.json(),
                "selection" to selection.json(),
                "state" to "available".json(),
                "source" to source.json(),
                "meters" to meters.json(),
                "partial" to partial.json(),
                "estimated" to estimated.json(),
            )
        is UnavailableDistance ->
            obj(
                "generation" to generation.json(),
                "projectionSequence" to projectionSequence.json(),
                "selection" to selection.json(),
                "state" to "unavailable".json(),
            )
    }

internal fun ControllerDisplay.json(): Obj =
    when (this) {
        is ControllerReading ->
            obj(
                "sourceSequence" to sourceSequence.json(),
                "ageSeconds" to ageSeconds.json(),
                "humanPowerW" to humanPowerW?.json(),
                "cadenceRpm" to cadenceRpm?.json(),
                "distance" to distance?.json(),
            )
        is ControllerDistanceOnly -> obj("distance" to distance.json())
    }
