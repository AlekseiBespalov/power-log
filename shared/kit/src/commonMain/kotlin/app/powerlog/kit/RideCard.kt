package app.powerlog.kit

import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.RideCardFacts
import app.powerlog.domain.recording.RideCardState

public object RideCardProjection {
    private const val RIDE_TIME = "Ride time"
    private const val STATUS_UNAVAILABLE = "Ride status unavailable"
    private val controlLooks =
        listOf(
            Triple(RideAction.pause, "Pause", "pause.fill"),
            Triple(RideAction.resume, "Resume", "play.fill"),
            Triple(RideAction.finish, "Finish", "stop.fill"),
        )

    public fun project(facts: RideCardFacts, nowUtcUs: Long): RideCardContent {
        val bikeExpiry = facts.bikeFreshUntilUtcUs
        val heartExpiry = facts.heartRateFreshUntilUtcUs
        val controls =
            controlLooks
                .filter { (action, _, _) -> action in facts.controls }
                .map { (action, label, symbol) ->
                    RideCardContent.Control(
                        action,
                        label,
                        symbol,
                        facts.rideId,
                        facts.token,
                        facts.state,
                        action == RideAction.finish,
                    )
                }
        return RideCardContent(
            rideId = facts.rideId,
            controlToken = facts.token,
            expectedPhase = facts.state,
            bike = entries(MetricId.humanPowerW, facts.powerW),
            heartRate = entries(MetricId.heartRateBpm, facts.heartRateBpm),
            bikeExpiresAtUtcUs = bikeExpiry,
            heartRateExpiresAtUtcUs = heartExpiry,
            bikeAvailableStatus = status(facts, bikeAvailable = true),
            bikeExpiredStatus = status(facts, bikeAvailable = false),
            systemStaleStatus =
                RideCardContent.Status("Open Power Log", "clock.badge.questionmark", STATUS_UNAVAILABLE),
            controls = controls,
            timer = timer(facts),
            systemStaleTimer = RideCardContent.Timer(TimerState.unavailable, null, Format.MISSING, STATUS_UNAVAILABLE),
            staleDateUtcUs = listOfNotNull(bikeExpiry, heartExpiry).filter { it > nowUtcUs }.minOrNull(),
        )
    }

    private fun entries(metric: MetricId, value: Double?): RideCardContent.Entries {
        val appearance = MetricAppearance.of(metric)
        val unit = Units.display(metric, UnitSystem.metric, TemperatureUnit.celsius).symbol
        fun entry(text: String) =
            RideCardContent.Entry(text, unit, appearance.color, "${appearance.label}: $text $unit")
        val missing = entry(Format.MISSING)
        return RideCardContent.Entries(
            entry(Format.metric(metric, value, UnitSystem.metric, TemperatureUnit.celsius)),
            missing,
            missing,
        )
    }

    private fun status(facts: RideCardFacts, bikeAvailable: Boolean): RideCardContent.Status {
        val text =
            when (facts.state) {
                RideCardState.updating -> "Updating…"
                RideCardState.running -> if (bikeAvailable) "Recording" else "Bike unavailable"
                RideCardState.paused -> "Paused"
                RideCardState.starting -> "Starting…"
                RideCardState.interrupted -> "Open Power Log"
            }
        val running =
            facts.state == RideCardState.running ||
                facts.state == RideCardState.updating && facts.timer.state == TimerState.running
        val symbol =
            when {
                !running -> "pause.fill"
                bikeAvailable -> "bicycle"
                else -> "antenna.radiowaves.left.and.right.slash"
            }
        return RideCardContent.Status(text, symbol, text)
    }

    private fun timer(facts: RideCardFacts): RideCardContent.Timer {
        val timer = facts.timer
        val origin = timer.originUtcUs
        return when {
            timer.state == TimerState.running && origin != null ->
                RideCardContent.Timer(TimerState.running, origin, null, RIDE_TIME)
            timer.state == TimerState.unavailable ->
                RideCardContent.Timer(TimerState.unavailable, null, Format.MISSING, RIDE_TIME)
            else -> RideCardContent.Timer(TimerState.frozen, null, Format.duration(timer.baseUs), RIDE_TIME)
        }
    }
}

public class RideCardContent(
    public val rideId: String,
    public val controlToken: String,
    public val expectedPhase: RideCardState,
    public val bike: Entries,
    public val heartRate: Entries,
    public val bikeExpiresAtUtcUs: Long?,
    public val heartRateExpiresAtUtcUs: Long?,
    public val bikeAvailableStatus: Status,
    public val bikeExpiredStatus: Status,
    public val systemStaleStatus: Status,
    public val controls: List<Control>,
    public val timer: Timer,
    public val systemStaleTimer: Timer,
    public val staleDateUtcUs: Long?,
) {
    public fun render(nowUtcUs: Long, systemStale: Boolean): Render {
        fun select(entries: Entries, expiry: Long?): Entry =
            when {
                systemStale -> entries.systemStale
                expiry != null && nowUtcUs < expiry -> entries.available
                else -> entries.expired
            }
        val bikeFresh = bikeExpiresAtUtcUs != null && nowUtcUs < bikeExpiresAtUtcUs
        return Render(
            select(bike, bikeExpiresAtUtcUs),
            select(heartRate, heartRateExpiresAtUtcUs),
            when {
                systemStale -> systemStaleStatus
                bikeFresh -> bikeAvailableStatus
                else -> bikeExpiredStatus
            },
            if (systemStale) systemStaleTimer else timer,
            if (systemStale) emptyList() else controls,
            !systemStale,
        )
    }

    public data class Entry(
        public val text: String,
        public val unit: String,
        public val color: String,
        public val accessibility: String,
    )

    public data class Entries(public val available: Entry, public val expired: Entry, public val systemStale: Entry)

    public data class Status(public val text: String, public val symbol: String, public val accessibility: String)

    public data class Timer(
        public val mode: TimerState,
        public val originUtcUs: Long?,
        public val text: String?,
        public val accessibility: String,
    )

    public data class Control(
        public val action: RideAction,
        public val label: String,
        public val symbol: String,
        public val rideId: String,
        public val controlToken: String,
        public val expectedPhase: RideCardState,
        public val requiresConfirmation: Boolean,
    )

    public data class Render(
        public val bike: Entry,
        public val heartRate: Entry,
        public val status: Status,
        public val timer: Timer,
        public val controls: List<Control>,
        public val showReadings: Boolean,
    )
}
