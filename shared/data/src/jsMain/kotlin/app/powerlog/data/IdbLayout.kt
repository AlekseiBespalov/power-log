package app.powerlog.data

internal sealed interface IdbKeyPath {
    data class Field(val name: String) : IdbKeyPath

    data class Compound(val names: List<String>) : IdbKeyPath
}

internal data class IdbIndex(val name: String, val keyPath: IdbKeyPath, val unique: Boolean = false)

internal enum class IdbObjectStore(val keyPath: IdbKeyPath, val indexes: List<IdbIndex> = emptyList()) {
    appState(IdbKeyPath.Field("k")),
    ride(
        IdbKeyPath.Field("uuid"),
        listOf(IdbIndex("history", IdbKeyPath.Field("historyKey")), IdbIndex("open", IdbKeyPath.Field("openKey"))),
    ),
    controllerSession(IdbKeyPath.Compound(listOf("ride", "sessionNo"))),
    rideEvent(
        IdbKeyPath.Compound(listOf("ride", "seq")),
        listOf(IdbIndex("eventId", IdbKeyPath.Compound(listOf("ride", "eventId")), unique = true)),
    ),
    facts(
        IdbKeyPath.Compound(listOf("ride", "kind", "batchNo")),
        listOf(IdbIndex("end", IdbKeyPath.Compound(listOf("ride", "kind", "lastUs")))),
    ),
    knownController(IdbKeyPath.Field("bikeId")),
    derived(IdbKeyPath.Compound(listOf("ride", "family", "variant", "part"))),
}

internal enum class IdbFactKind(val columns: List<String>) {
    telemetry(
        listOf(
            "elapsedUs",
            "sessionNo",
            "seq",
            "utcUs",
            "activeInterval",
            "run",
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
            "speedRaw",
            "raceMode",
        )
    ),
    location(
        listOf(
            "elapsedUs",
            "seq",
            "utcUs",
            "activeInterval",
            "run",
            "barrier",
            "lat",
            "lon",
            "hAccM",
            "altitudeM",
            "altitudeDatum",
            "vAccM",
            "mslM",
            "mslVAccM",
            "speedMps",
            "speedAccMps",
            "courseDeg",
            "courseAccDeg",
        )
    ),
}
