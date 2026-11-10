package app.powerlog.application.recording

import app.powerlog.application.api.ApiFailure
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.recording.LifecycleState

internal class RecordingState {
    var lifecycle: LifecycleState = LifecycleState(null, null)
    var pending: RideAction? = null
    var cue: CueView? = null
    var deciding: Boolean = false
    var failure: ApiFailure? = null
    var otherTab: Boolean = false
    var interruptedInMemory: Boolean = false
    var finishRequest: FinishRequestView? = null

    val open: Ride?
        get() = lifecycle.open

    val recording: Boolean
        get() = lifecycle.open?.phase in recordingPhases

    companion object {
        val recordingPhases: Set<RidePhase> = setOf(RidePhase.preparing, RidePhase.running, RidePhase.paused)
    }
}
