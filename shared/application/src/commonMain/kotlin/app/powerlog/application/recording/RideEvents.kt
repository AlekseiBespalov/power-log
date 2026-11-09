package app.powerlog.application.recording

import app.powerlog.domain.core.analysis.distance.GpsEdge
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.recording.RideTransition

internal interface RideEvents {
    fun rideTransitioned(ride: Ride, transition: RideTransition, eventId: EventId?, events: List<RideEvent>)

    fun batchCommitted(
        rideId: RideId,
        rows: TelemetryRows,
        sessions: Map<Int, ControllerSession>,
        fixes: List<LocationFix>,
        edges: List<GpsEdge>,
    )

    fun rideRestored(ride: Ride)
}
