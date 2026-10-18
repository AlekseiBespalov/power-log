package app.powerlog.core.telemetry

object StorageColumns {
    val telemetry: List<String> =
        listOf(
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
            "raceMode",
            "speedRaw",
            "controllerSpeedMps",
        )
    val appleLocation: List<String> =
        listOf(
            "latitude",
            "longitude",
            "altitudeMeters",
            "horizontalAccuracyM",
            "verticalAccuracyM",
            "speedMps",
            "courseDegrees",
            "speedAccuracyMps",
            "courseAccuracyDegrees",
            "originalAltitudeMeters",
            "originalVerticalAccuracyM",
        )
    val androidLocation: List<String> =
        listOf(
            "speedMps",
            "altitudeMeters",
            "horizontalAccuracyM",
            "verticalAccuracyM",
            "courseDegrees",
            "latitude",
            "longitude",
            "speedAccuracyMps",
        )
    val health: List<String> =
        listOf(
            "heartRateBpm",
            "activeEnergyKcal",
            "basalEnergyKcal",
            "distanceMeters",
            "riderPowerW",
            "cadenceRpm",
            "speedMps",
            "value",
            "sampleCount",
        )
    val sampleIdentity: List<String> = listOf("controllerModel", "firmwareLabel", "controllerProtocol")
    val requiredSample: List<String> = listOf("timestamp", "elapsedSeconds", "sequence") + telemetry.dropLast(1)
    val androidMetricBits: Map<String, Int> =
        (telemetry +
                androidLocation +
                listOf(
                    "gpsDistanceMeters",
                    "controllerDistanceMeters",
                    "heartRateBpm",
                    "activeEnergyKcal",
                    "controllerEnergyWh",
                    "controllerChargeAh",
                    "calculatedEnergyWh",
                    "calculatedChargeAh",
                ))
            .mapIndexed { index, name -> name to index }
            .toMap()
}
