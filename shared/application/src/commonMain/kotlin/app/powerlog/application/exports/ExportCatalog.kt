package app.powerlog.application.exports

import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.ride.Platform

internal data class ExportField(
    val name: String,
    val type: String,
    val description: String,
    val reads: List<String>,
    val unit: String? = null,
    val vendor: String? = null,
)

internal data class ExportTable(
    val name: String,
    val path: String,
    val projection: String,
    val fields: List<ExportField>,
)

internal data class HealthExportMetric(val wire: String, val column: String?, val identifier: String, val unit: String)

internal object ExportCatalog {
    private val everywhere = setOf(Platform.ios, Platform.android, Platform.web)
    private val phones = setOf(Platform.ios, Platform.android)

    private val projectionColumns: Map<String, List<Pair<String, Set<Platform>>>> =
        mapOf(
            "telemetry" to
                listOf(
                        "elapsedSeconds",
                        "timestamp",
                        "activeInterval",
                        "run",
                        "connection",
                        "humanPowerW",
                        "cadenceRpm",
                        "motorInputPowerW",
                        "batteryVoltageV",
                        "batteryCurrentA",
                        "motorCurrentA",
                        "motorRpm",
                        "pedalTorqueNm",
                        "controllerTempC",
                        "motorTempC",
                        "consumedAh",
                        "consumedWh",
                        "throttleVoltageV",
                        "faultCode",
                        "assistLevel",
                        "controllerSpeedMps",
                        "raceMode",
                        "speedRaw",
                    )
                    .map { it to everywhere },
            "gps" to
                listOf(
                        "elapsedSeconds",
                        "timestamp",
                        "activeInterval",
                        "run",
                        "producer",
                        "latitude",
                        "longitude",
                        "altitudeMeters",
                        "verticalAccuracyM",
                        "originalAltitudeMeters",
                        "originalVerticalAccuracyM",
                        "originalAltitudeDatum",
                        "horizontalAccuracyM",
                        "speedMps",
                        "speedAccuracyMps",
                        "courseDegrees",
                        "courseAccuracyDegrees",
                    )
                    .map { it to everywhere },
            "healthZip" to
                listOf(
                    "elapsedSeconds" to phones,
                    "timestamp" to phones,
                    "activeInterval" to phones,
                    "producer" to phones,
                    "heartRateBpm" to phones,
                    "activeEnergyKcal" to phones,
                    "basalEnergyKcal" to setOf(Platform.ios),
                    "distanceMeters" to phones,
                    "riderPowerW" to phones,
                    "cadenceRpm" to phones,
                    "speedMps" to phones,
                    "identifier" to phones,
                    "value" to phones,
                    "unit" to phones,
                    "representation" to phones,
                    "sampleCount" to phones,
                    "sampleStart" to phones,
                    "provider" to phones,
                    "providerParentId" to phones,
                    "dataOrigin" to phones,
                    "originalEventId" to phones,
                    "originalEndTimestamp" to phones,
                    "memberIndex" to phones,
                ),
            "lifecycle" to
                listOf("elapsedSeconds", "timestamp", "producer", "action", "interrupted").map { it to everywhere },
        )

    fun columns(projection: String, platform: Platform): List<String> =
        projectionColumns.getValue(projection).filter { platform in it.second }.map { it.first }

    val healthValueColumns: List<String> =
        listOf(
            "heartRateBpm",
            "activeEnergyKcal",
            "basalEnergyKcal",
            "distanceMeters",
            "riderPowerW",
            "cadenceRpm",
            "speedMps",
        )

    private val iphoneHealth =
        listOf(
            HealthExportMetric("heartRateBpm", "heartRateBpm", "HKQuantityTypeIdentifierHeartRate", "bpm"),
            HealthExportMetric(
                "activeEnergyKcal",
                "activeEnergyKcal",
                "HKQuantityTypeIdentifierActiveEnergyBurned",
                "kcal",
            ),
            HealthExportMetric(
                "basalEnergyKcal",
                "basalEnergyKcal",
                "HKQuantityTypeIdentifierBasalEnergyBurned",
                "kcal",
            ),
            HealthExportMetric(
                "cyclingDistanceMeters",
                "distanceMeters",
                "HKQuantityTypeIdentifierDistanceCycling",
                "m",
            ),
            HealthExportMetric("cyclingPowerW", "riderPowerW", "HKQuantityTypeIdentifierCyclingPower", "W"),
            HealthExportMetric("cyclingCadenceRpm", "cadenceRpm", "HKQuantityTypeIdentifierCyclingCadence", "rpm"),
            HealthExportMetric("cyclingSpeedMps", "speedMps", "HKQuantityTypeIdentifierCyclingSpeed", "m/s"),
            HealthExportMetric("respiratoryRateBrpm", null, "HKQuantityTypeIdentifierRespiratoryRate", "breaths/min"),
            HealthExportMetric("oxygenSaturationFraction", null, "HKQuantityTypeIdentifierOxygenSaturation", "%"),
            HealthExportMetric(
                "heartRateVariabilitySDNNMs",
                null,
                "HKQuantityTypeIdentifierHeartRateVariabilitySDNN",
                "ms",
            ),
            HealthExportMetric("physicalEffortMET", null, "HKQuantityTypeIdentifierPhysicalEffort", "kcal/(kg*hr)"),
            HealthExportMetric(
                "cyclingFunctionalThresholdPowerW",
                null,
                "HKQuantityTypeIdentifierCyclingFunctionalThresholdPower",
                "W",
            ),
            HealthExportMetric(
                "workoutEffortScore",
                null,
                "HKQuantityTypeIdentifierWorkoutEffortScore",
                "appleEffortScore",
            ),
            HealthExportMetric(
                "estimatedWorkoutEffortScore",
                null,
                "HKQuantityTypeIdentifierEstimatedWorkoutEffortScore",
                "appleEffortScore",
            ),
        )

    private val androidHealth =
        listOf(
            HealthExportMetric("heartRate", "heartRateBpm", "heartRate", "bpm"),
            HealthExportMetric("activeEnergy", "activeEnergyKcal", "activeEnergy", "kcal"),
            HealthExportMetric("respiratoryRate", null, "respiratoryRate", "breaths/min"),
            HealthExportMetric("oxygenSaturation", null, "oxygenSaturation", "%"),
            HealthExportMetric("hrvRMSSD", null, "hrvRMSSD", "ms"),
            HealthExportMetric("riderPower", null, "riderPower", "W"),
            HealthExportMetric("cyclingCadence", null, "cyclingCadence", "rpm"),
            HealthExportMetric("cyclingSpeed", null, "cyclingSpeed", "m/s"),
            HealthExportMetric("cyclingDistance", null, "cyclingDistance", "m"),
            HealthExportMetric("association", null, "association", "association"),
        )

    val healthMetrics: Map<Platform, List<HealthExportMetric>> =
        mapOf(Platform.ios to iphoneHealth, Platform.android to androidHealth, Platform.web to emptyList())

    fun healthMetric(sample: HealthSample, platform: Platform): HealthExportMetric? {
        val wire = sample.metric?.wire ?: return null
        return healthMetrics.getValue(platform).firstOrNull { it.wire == wire }
    }

    val tables: List<ExportTable> =
        listOf(
            ExportTable(
                "telemetry",
                "telemetry.csv",
                "telemetry",
                listOf(
                    ExportField(
                        "timestamp",
                        "datetime",
                        "Original reading time in the source clock, as retained",
                        listOf("timestamp"),
                    ),
                    ExportField("elapsedSeconds", "number", "Time on the ride timeline", listOf("elapsedSeconds"), "s"),
                    ExportField(
                        "activeInterval",
                        "integer",
                        "Activity interval, numbered from 1; empty while paused",
                        listOf("activeInterval"),
                    ),
                    ExportField(
                        "run",
                        "integer",
                        "Continuous run of readings, numbered from 1 in order of first appearance",
                        listOf("run"),
                    ),
                    ExportField(
                        "connection",
                        "integer",
                        "Controller connection, numbered as in powerLog:sources.connections",
                        listOf("connection"),
                    ),
                    ExportField(
                        "humanPowerW",
                        "number",
                        "Rider power reported by the controller",
                        listOf("humanPowerW"),
                        "W",
                    ),
                    ExportField(
                        "cadenceRpm",
                        "number",
                        "Crank cadence reported by the controller, smoothed",
                        listOf("cadenceRpm"),
                        "/min",
                    ),
                    ExportField(
                        "motorInputPowerW",
                        "number",
                        "Battery input power: battery voltage × battery current; not motor output or rider power",
                        listOf("motorInputPowerW"),
                        "W",
                    ),
                    ExportField("batteryVoltageV", "number", "Battery voltage", listOf("batteryVoltageV"), "V"),
                    ExportField(
                        "batteryCurrentA",
                        "number",
                        "Battery current; negative under regeneration",
                        listOf("batteryCurrentA"),
                        "A",
                    ),
                    ExportField("motorCurrentA", "number", "Motor current", listOf("motorCurrentA"), "A"),
                    ExportField("motorRpm", "number", "Motor speed; not crank cadence", listOf("motorRpm"), "/min"),
                    ExportField("pedalTorqueNm", "number", "Pedal torque", listOf("pedalTorqueNm"), "N.m"),
                    ExportField(
                        "controllerTempC",
                        "number",
                        "Controller temperature; not ambient temperature",
                        listOf("controllerTempC"),
                        "Cel",
                    ),
                    ExportField(
                        "motorTempC",
                        "number",
                        "Motor temperature; not ambient temperature",
                        listOf("motorTempC"),
                        "Cel",
                    ),
                    ExportField(
                        "consumedAh",
                        "number",
                        "Charge counter of the controller; not ride consumption",
                        listOf("consumedAh"),
                        "A.h",
                    ),
                    ExportField(
                        "consumedWh",
                        "number",
                        "Energy counter of the controller; not ride consumption",
                        listOf("consumedWh"),
                        "W.h",
                    ),
                    ExportField("throttleVoltageV", "number", "Throttle voltage", listOf("throttleVoltageV"), "V"),
                    ExportField(
                        "faultCode",
                        "integer",
                        "Fault code in the vocabulary of the connection's vendor",
                        listOf("faultCode"),
                    ),
                    ExportField(
                        "assistLevel",
                        "integer",
                        "Assist level reported by the controller",
                        listOf("assistLevel"),
                    ),
                    ExportField(
                        "controllerSpeedMps",
                        "number",
                        "Controller speed, normalized for a known controller profile",
                        listOf("controllerSpeedMps"),
                        "m/s",
                    ),
                    ExportField(
                        "controller.raceMode",
                        "integer",
                        "Controller race mode",
                        listOf("raceMode"),
                        null,
                        "controller",
                    ),
                    ExportField(
                        "controller.speedRaw",
                        "number",
                        "Controller speed field divided by 100; its unit depends on the controller profile",
                        listOf("speedRaw"),
                        null,
                        "controller",
                    ),
                ),
            ),
            ExportTable(
                "battery_consumption",
                "battery-consumption.csv",
                "consumption",
                listOf(
                    ExportField("timestamp", "datetime", "Original controller timestamp.", listOf("timestamp")),
                    ExportField(
                        "elapsedSeconds",
                        "number",
                        "Time on the ride timeline.",
                        listOf("elapsedSeconds"),
                        "s",
                    ),
                    ExportField(
                        "controllerEnergyWh",
                        "number",
                        "Energy used (controller). Ride consumption from changes in the controller energy counter, starting at zero.",
                        listOf("controllerEnergyWh"),
                        "Wh",
                    ),
                    ExportField(
                        "controllerChargeAh",
                        "number",
                        "Charge used (controller). Ride consumption from changes in the controller charge counter, starting at zero.",
                        listOf("controllerChargeAh"),
                        "Ah",
                    ),
                    ExportField(
                        "calculatedEnergyWh",
                        "number",
                        "Energy used (calc). Calculated by Power Log by integrating positive battery voltage × current during this ride.",
                        listOf("calculatedEnergyWh"),
                        "Wh",
                    ),
                    ExportField(
                        "calculatedChargeAh",
                        "number",
                        "Charge used (calc). Calculated by Power Log by integrating positive battery current during this ride.",
                        listOf("calculatedChargeAh"),
                        "Ah",
                    ),
                ),
            ),
            ExportTable(
                "gps",
                "gps.csv",
                "gps",
                listOf(
                    ExportField(
                        "timestamp",
                        "datetime",
                        "Original fix time in the source clock, as retained",
                        listOf("timestamp"),
                    ),
                    ExportField("elapsedSeconds", "number", "Time on the ride timeline", listOf("elapsedSeconds"), "s"),
                    ExportField(
                        "activeInterval",
                        "integer",
                        "Activity interval, numbered from 1; empty while paused",
                        listOf("activeInterval"),
                    ),
                    ExportField(
                        "run",
                        "integer",
                        "Continuous run of fixes, numbered from 1 in order of first appearance; a barrier fix is its own run",
                        listOf("run"),
                    ),
                    ExportField("source", "string", "GPS producer: phone or watch", listOf("producer")),
                    ExportField("latitude", "number", "Latitude", listOf("latitude"), "deg"),
                    ExportField("longitude", "number", "Longitude", listOf("longitude"), "deg"),
                    ExportField(
                        "altitudeMeters",
                        "number",
                        "Altitude above mean sea level",
                        listOf("altitudeMeters"),
                        "m",
                    ),
                    ExportField(
                        "verticalAccuracyM",
                        "number",
                        "Vertical accuracy of altitudeMeters",
                        listOf("verticalAccuracyM"),
                        "m",
                    ),
                    ExportField(
                        "originalAltitudeMeters",
                        "number",
                        "Original provider altitude in originalAltitudeDatum",
                        listOf("originalAltitudeMeters"),
                        "m",
                    ),
                    ExportField(
                        "originalVerticalAccuracyM",
                        "number",
                        "Original provider vertical accuracy",
                        listOf("originalVerticalAccuracyM"),
                        "m",
                    ),
                    ExportField(
                        "originalAltitudeDatum",
                        "string",
                        "Datum of the original altitude: msl, wgs84Ellipsoid or unknown",
                        listOf("originalAltitudeDatum"),
                    ),
                    ExportField(
                        "horizontalAccuracyM",
                        "number",
                        "Horizontal accuracy",
                        listOf("horizontalAccuracyM"),
                        "m",
                    ),
                    ExportField("speedMps", "number", "GPS speed", listOf("speedMps"), "m/s"),
                    ExportField(
                        "speedAccuracyMps",
                        "number",
                        "Accuracy of speedMps",
                        listOf("speedAccuracyMps"),
                        "m/s",
                    ),
                    ExportField(
                        "courseDegrees",
                        "number",
                        "Direction of travel; not device heading",
                        listOf("courseDegrees"),
                        "deg",
                    ),
                    ExportField(
                        "courseAccuracyDegrees",
                        "number",
                        "Accuracy of courseDegrees",
                        listOf("courseAccuracyDegrees"),
                        "deg",
                    ),
                ),
            ),
            ExportTable(
                "health",
                "health.csv",
                "healthZip",
                listOf(
                    ExportField(
                        "timestamp",
                        "datetime",
                        "Sample end, or the instant of an instantaneous value",
                        listOf("timestamp"),
                    ),
                    ExportField(
                        "startTimestamp",
                        "datetime",
                        "Sample interval start; empty for instants",
                        listOf("sampleStart"),
                    ),
                    ExportField("elapsedSeconds", "number", "Time on the ride timeline", listOf("elapsedSeconds"), "s"),
                    ExportField(
                        "activeInterval",
                        "integer",
                        "Activity interval, numbered from 1; empty outside activity",
                        listOf("activeInterval"),
                    ),
                    ExportField("source", "string", "Health producer: phone or watch", listOf("producer")),
                    ExportField(
                        "metric",
                        "string",
                        "Measured quantity",
                        listOf(
                            "heartRateBpm",
                            "activeEnergyKcal",
                            "basalEnergyKcal",
                            "distanceMeters",
                            "riderPowerW",
                            "cadenceRpm",
                            "speedMps",
                            "identifier",
                        ),
                    ),
                    ExportField(
                        "value",
                        "number",
                        "Measured value, in unit",
                        listOf(
                            "heartRateBpm",
                            "activeEnergyKcal",
                            "basalEnergyKcal",
                            "distanceMeters",
                            "riderPowerW",
                            "cadenceRpm",
                            "speedMps",
                            "value",
                        ),
                    ),
                    ExportField(
                        "unit",
                        "string",
                        "UCUM unit of value; an unknown metric keeps its stored unit, which is not UCUM",
                        listOf("identifier", "unit"),
                    ),
                    ExportField(
                        "kind",
                        "string",
                        "sample, aggregate, latest, cumulative, final, intervalRecord or retained provenance",
                        listOf("representation", "sampleCount"),
                    ),
                    ExportField(
                        "sampleCount",
                        "integer",
                        "Number of values condensed into an aggregate",
                        listOf("sampleCount"),
                    ),
                    ExportField("provider", "string", "Health provider", listOf("provider")),
                    ExportField("parentId", "string", "Original provider parent identity", listOf("providerParentId")),
                    ExportField("memberIndex", "string", "Canonical provider member index", listOf("memberIndex")),
                    ExportField("dataOrigin", "string", "Actual recording package or bundle", listOf("dataOrigin")),
                    ExportField(
                        "originalEventId",
                        "string",
                        "Stable original event identity",
                        listOf("originalEventId"),
                    ),
                    ExportField(
                        "originalEndTimestamp",
                        "string",
                        "Retained original interval end",
                        listOf("originalEndTimestamp"),
                    ),
                    ExportField(
                        "logicalKey",
                        "string",
                        "Full canonical provider member identity",
                        listOf("logicalKey"),
                    ),
                    ExportField(
                        "targetEventId",
                        "string",
                        "Correction or deletion target original",
                        listOf("targetEventId"),
                    ),
                    ExportField(
                        "originRevision",
                        "string",
                        "Retained native revision evidence",
                        listOf("originRevision"),
                    ),
                    ExportField("valueBits", "string", "Original binary64 value bits", listOf("valueBits")),
                    ExportField(
                        "evidence",
                        "string",
                        "Original provenance and association evidence",
                        listOf("evidence"),
                    ),
                ),
            ),
            ExportTable(
                "events",
                "events.csv",
                "lifecycle",
                listOf(
                    ExportField("timestamp", "datetime", "Original event time, as retained", listOf("timestamp")),
                    ExportField("elapsedSeconds", "number", "Time on the ride timeline", listOf("elapsedSeconds"), "s"),
                    ExportField(
                        "timerSeconds",
                        "number",
                        "Active time at the event; empty when not retained",
                        listOf("timerSeconds"),
                        "s",
                    ),
                    ExportField("action", "string", "start, pause, resume, lap or stop", listOf("action")),
                    ExportField(
                        "interrupted",
                        "boolean",
                        "The event records a capture interruption",
                        listOf("interrupted", "action"),
                    ),
                    ExportField("source", "string", "Recorder: phone, watch or browser", listOf("producer")),
                ),
            ),
        )
}
