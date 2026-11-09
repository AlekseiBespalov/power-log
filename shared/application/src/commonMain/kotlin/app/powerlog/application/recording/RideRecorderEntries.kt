package app.powerlog.application.recording

import app.powerlog.application.api.Outcome
import app.powerlog.application.port.LiveHeartRate
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.StartRequest
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.recording.RideCommand
import app.powerlog.domain.recording.RideSignal

internal interface RideRecorderEntries {
    suspend fun startFromWatch(request: StartRequest, requestId: String): Outcome<Unit>

    suspend fun decide(command: RideCommand.Control): Outcome<Unit>

    fun observe(signal: RideSignal)

    fun heartRate(rideId: RideId, heartRate: LiveHeartRate, at: ClockReading)

    fun healthSessionEnded(rideId: RideId)
}
