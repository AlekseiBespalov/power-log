package app.powerlog.bridge

import app.powerlog.core.presentation.ExternalControlRequest
import app.powerlog.core.presentation.RideCapabilities
import app.powerlog.core.presentation.RideFailure

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

internal data class RideStreams(
    val controller: RideStream,
    val heartRate: RideStream,
    val gps: RideGPSStream,
)

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
    val failure: RideFailure?,
    val supported: Boolean = true,
    val pendingAction: String? = null,
    val collectionRevision: String? = null,
    val watch: RideWatch = RideWatch(),
    val controlToken: String? = null,
    val externalFinish: ExternalControlRequest? = null,
    val healthBusy: Boolean = false,
    val startBusy: Boolean = false,
    val finalizationBusy: Boolean = false,
    val healthReason: String? = null,
    val distanceSource: String? = null,
    val batteryConsumptionSource: String = "controller",
    val readWorkoutIds: List<String> = emptyList(),
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
            "controlToken" to controlToken,
            "externalFinish" to externalFinish?.toWireMap(),
            "timerSeconds" to timerSeconds,
            "historyRevision" to historyRevision,
            "lastDeletedWorkoutId" to lastDeletedWorkoutId,
            "collectionRevision" to collectionRevision,
            "healthBusy" to healthBusy,
            "readWorkoutIds" to readWorkoutIds,
            "startBusy" to startBusy,
            "finalizationBusy" to finalizationBusy,
            "indoor" to indoor,
            "useWatch" to useWatch,
            "saveToHealth" to saveToHealth,
            "recordGPS" to recordGPS,
            "distanceSource" to (distanceSource ?: if (recordGPS && !indoor) "gps:phone" else "controller"),
            "batteryConsumptionSource" to batteryConsumptionSource,
            "recordingOwner" to "phone",
            "healthPublisher" to "phone",
            "healthKitState" to healthKitState,
            "healthReason" to healthReason,
            "watch" to mapOf("installed" to watch.installed),
            "streams" to
                mapOf(
                    "controller" to mapOf("status" to streams.controller.status),
                    "heartRate" to mapOf("status" to streams.heartRate.status),
                    "gps" to
                        mapOf(
                            "status" to streams.gps.status,
                            "source" to streams.gps.source,
                            "accuracyMeters" to streams.gps.accuracyMeters,
                        ),
                ),
            "error" to failure?.text,
            "failure" to failure?.let { mapOf("code" to it.name, "message" to it.text) },
            "sessionBusy" to false,
        )
}
