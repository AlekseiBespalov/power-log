package app.powerlog.kit

import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.MetricOrigin
import app.powerlog.domain.core.metric.MetricSemantics

public enum class MetricGroup {
    rider,
    battery,
    motor,
    route,
    status,
}

public data class Appearance(
    public val label: String,
    public val shortLabel: String,
    public val color: String,
    public val description: String?,
    public val group: MetricGroup,
    public val step: Boolean,
    public val zero: Boolean,
    public val selectable: Boolean,
    public val semantics: MetricSemantics,
    public val allowsMean: Boolean,
    public val origin: MetricOrigin,
)

public object MetricAppearance {
    private class Look(
        val label: String,
        val shortLabel: String,
        val color: String,
        val group: MetricGroup,
        val zero: Boolean,
        val step: Boolean = false,
        val description: String? = null,
    )

    private val looks: Map<MetricId, Look> =
        mapOf(
            MetricId.humanPowerW to Look("Rider power", "Rider", "#ff8735", MetricGroup.rider, true),
            MetricId.cadenceRpm to Look("Cadence", "Cadence", "#ad98e4", MetricGroup.rider, true),
            MetricId.heartRateBpm to Look("Heart rate", "Heart rate", "#fb7185", MetricGroup.rider, false),
            MetricId.pedalTorqueNm to Look("Pedal torque", "Torque", "#e9b65a", MetricGroup.rider, true),
            MetricId.activeEnergyKcal to Look("Active energy", "Active energy", "#f6b26b", MetricGroup.rider, true),
            MetricId.basalEnergyKcal to Look("Resting energy", "Resting energy", "#b8a184", MetricGroup.rider, true),
            MetricId.batteryVoltageV to Look("Battery voltage", "Voltage", "#f4cf5b", MetricGroup.battery, false),
            MetricId.batteryCurrentA to
                Look("Battery current", "Battery current", "#57c6f5", MetricGroup.battery, true),
            MetricId.motorInputPowerW to
                Look(
                    "Battery input power",
                    "Battery input",
                    "#3d8bfd",
                    MetricGroup.battery,
                    true,
                    description =
                        "Calculated by Power Log as battery voltage × current; electrical input, not mechanical motor output.",
                ),
            MetricId.consumedAh to
                Look(
                    "Charge counter (controller)",
                    "Charge counter (controller)",
                    "#cbb48f",
                    MetricGroup.battery,
                    true,
                    description = "Original controller counter; may include consumption from earlier rides.",
                ),
            MetricId.consumedWh to
                Look(
                    "Energy counter (controller)",
                    "Energy counter (controller)",
                    "#e2ae71",
                    MetricGroup.battery,
                    true,
                    description = "Original controller counter; may include consumption from earlier rides.",
                ),
            MetricId.motorCurrentA to Look("Motor current", "Motor current", "#e693c1", MetricGroup.motor, true),
            MetricId.motorTempC to Look("Motor temperature", "Motor", "#ff9875", MetricGroup.motor, false),
            MetricId.controllerTempC to
                Look("Controller temperature", "Controller", "#6bd4c5", MetricGroup.motor, false),
            MetricId.motorRpm to Look("Motor speed", "Motor speed", "#84b1f5", MetricGroup.motor, true),
            MetricId.throttleVoltageV to Look("Throttle voltage", "Throttle", "#d3a8e6", MetricGroup.motor, false),
            MetricId.speedMps to Look("GPS speed", "GPS", "#91c77a", MetricGroup.route, true),
            MetricId.controllerSpeedMps to Look("Controller speed", "Controller", "#8799ac", MetricGroup.route, true),
            MetricId.distanceMeters to Look("Ride distance", "Distance", "#aed195", MetricGroup.route, true),
            MetricId.gpsDistanceMeters to
                Look(
                    "Distance (GPS)",
                    "Distance (GPS)",
                    "#a9d18e",
                    MetricGroup.route,
                    true,
                    description = "Calculated by Power Log from accepted phone GPS positions.",
                ),
            MetricId.controllerDistanceMeters to
                Look(
                    "Distance (controller)",
                    "Distance (controller)",
                    "#6dbab7",
                    MetricGroup.route,
                    true,
                    description = "Calculated by Power Log from controller speed; not a controller odometer.",
                ),
            MetricId.controllerEnergyWh to
                Look(
                    "Energy used (controller)",
                    "Energy used (controller)",
                    "#f3c260",
                    MetricGroup.battery,
                    true,
                    description = "Ride consumption from changes in the controller energy counter, starting at zero.",
                ),
            MetricId.controllerChargeAh to
                Look(
                    "Charge used (controller)",
                    "Charge used (controller)",
                    "#e0b36a",
                    MetricGroup.battery,
                    true,
                    description = "Ride consumption from changes in the controller charge counter, starting at zero.",
                ),
            MetricId.calculatedEnergyWh to
                Look(
                    "Energy used (calc)",
                    "Energy used (calc)",
                    "#8dbfdf",
                    MetricGroup.battery,
                    true,
                    description =
                        "Calculated by Power Log by integrating positive battery voltage × current during this ride.",
                ),
            MetricId.calculatedChargeAh to
                Look(
                    "Charge used (calc)",
                    "Charge used (calc)",
                    "#9eafd9",
                    MetricGroup.battery,
                    true,
                    description = "Calculated by Power Log by integrating positive battery current during this ride.",
                ),
            MetricId.batteryWhPerKm to
                Look(
                    "Battery consumption",
                    "Battery consumption",
                    "#3d8bfd",
                    MetricGroup.battery,
                    true,
                    description =
                        "Battery power divided by speed; ride average uses the selected battery energy and distance.",
                ),
            MetricId.riderWhPerKm to
                Look(
                    "Rider contribution",
                    "Rider contribution",
                    "#ff8735",
                    MetricGroup.rider,
                    true,
                    description =
                        "Rider power divided by speed; ride average uses accumulated pedalling work and distance.",
                ),
            MetricId.altitudeMeters to Look("Altitude", "Altitude", "#b6bcd0", MetricGroup.route, false),
            MetricId.horizontalAccuracyM to Look("GPS accuracy", "GPS accuracy", "#94a3b8", MetricGroup.route, true),
            MetricId.verticalAccuracyM to
                Look("Altitude accuracy", "Altitude accuracy", "#acb4ce", MetricGroup.route, true),
            MetricId.courseDegrees to Look("Course", "Course", "#73aaa7", MetricGroup.route, false),
            MetricId.assistLevel to Look("Assist level", "Assist", "#dcad77", MetricGroup.status, true, step = true),
            MetricId.raceMode to Look("Race mode", "Race mode", "#ed9c94", MetricGroup.status, true, step = true),
            MetricId.faultCode to
                Look("Controller fault code", "Fault code", "#d7dde5", MetricGroup.status, true, step = true),
            MetricId.speedRaw to
                Look("Controller speed signal (uncalibrated)", "Speed signal", "#8799ac", MetricGroup.route, false),
        )

    public fun of(metric: MetricId): Appearance {
        val look = looks.getValue(metric)
        val definition = MetricCatalog.of(metric)
        return Appearance(
            look.label,
            look.shortLabel,
            look.color,
            look.description,
            look.group,
            look.step,
            look.zero,
            definition.selectable,
            definition.semantics,
            definition.allowsMean,
            definition.origin,
        )
    }
}
