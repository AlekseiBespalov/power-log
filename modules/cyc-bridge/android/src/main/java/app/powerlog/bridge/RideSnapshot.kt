package app.powerlog.bridge

internal data class RideCapabilities(
    val phoneWorkout: Boolean,
    val watchWorkout: Boolean,
    val phoneHealth: Boolean,
    val watchHealth: Boolean,
    val healthProvider: String?,
    val gps: Boolean,
    val foregroundOnly: Boolean,
) {
    companion object {
        fun android(phoneHealth: Boolean) =
            RideCapabilities(
                true,
                false,
                phoneHealth,
                false,
                if (phoneHealth) "healthConnect" else null,
                true,
                false,
            )
    }
}

internal data class RideWatch(val installed: Boolean = false)

internal data class RideStream(val status: String)

internal data class RideGPSStream(
    val status: String,
    val source: String = "phone",
    val accuracyMeters: Double? = null,
) {
    init {
        require(accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters >= 0)
    }
}

internal data class RideStreams(val cyc: RideStream, val heartRate: RideStream, val gps: RideGPSStream)

internal data class RideSnapshot(
    val capabilities: RideCapabilities,
    val id: String?,
    val phase: String,
    val timerSeconds: Double,
    val historyRevision: String,
    val lastDeletedWorkoutId: String?,
    val indoor: Boolean,
    val useWatch: Boolean,
    val saveToHealth: Boolean,
    val recordGPS: Boolean,
    val healthKitState: String,
    val streams: RideStreams,
    val error: String?,
    val supported: Boolean = true,
    val pendingAction: String? = null,
    val recoveryState: String = "idle",
    val watch: RideWatch = RideWatch(),
    val warnings: List<String> = emptyList(),
) {
    init {
        require(timerSeconds.isFinite() && timerSeconds >= 0)
    }

    fun toWireMap(): Map<String, Any?> =
        mapOf(
            "supported" to supported,
            "capabilities" to
                mapOf(
                    "phoneWorkout" to capabilities.phoneWorkout,
                    "watchWorkout" to capabilities.watchWorkout,
                    "phoneHealth" to capabilities.phoneHealth,
                    "watchHealth" to capabilities.watchHealth,
                    "healthProvider" to capabilities.healthProvider,
                    "gps" to capabilities.gps,
                    "foregroundOnly" to capabilities.foregroundOnly,
                ),
            "id" to id,
            "phase" to phase,
            "pendingAction" to pendingAction,
            "timerSeconds" to timerSeconds,
            "historyRevision" to historyRevision,
            "lastDeletedWorkoutId" to lastDeletedWorkoutId,
            "collectionRevision" to null,
            "sealRevision" to null,
            "verifiedSealRevision" to null,
            "finalizationState" to null,
            "indoor" to indoor,
            "useWatch" to useWatch,
            "saveToHealth" to saveToHealth,
            "recordGPS" to recordGPS,
            "recoveryState" to recoveryState,
            "recoveryMessage" to null,
            "healthKitState" to healthKitState,
            "watch" to mapOf("installed" to watch.installed),
            "streams" to
                mapOf(
                    "cyc" to mapOf("status" to streams.cyc.status),
                    "heartRate" to mapOf("status" to streams.heartRate.status),
                    "gps" to
                        mapOf(
                            "status" to streams.gps.status,
                            "source" to streams.gps.source,
                            "accuracyMeters" to streams.gps.accuracyMeters,
                        ),
                ),
            "warnings" to warnings,
            "error" to error,
        )
}
