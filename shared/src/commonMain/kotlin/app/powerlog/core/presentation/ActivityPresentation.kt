package app.powerlog.core.presentation

import app.powerlog.core.RideActions
import app.powerlog.core.StreamStatus
import app.powerlog.core.telemetry.MetricCatalog
import kotlin.math.floor

data class FormattedEntry(val text: String, val unit: String, val color: String, val accessibility: String)

data class EntryAlternatives(
    val available: FormattedEntry,
    val expired: FormattedEntry,
    val systemStale: FormattedEntry,
)

data class ActivityStatus(val text: String, val symbol: String, val accessibility: String)

data class TimerInstruction(val mode: String, val originUs: Long?, val text: String?, val accessibility: String)

data class ActivityPhaseInput(
    val phase: String,
    val watchOwned: Boolean,
    val hasCutoff: Boolean,
    val ownerPhase: String?,
    val stopOutcome: String?,
    val verified: Boolean,
    val pendingStop: Boolean,
    val phoneStopping: Boolean,
    val closedWithoutWatch: Boolean = false,
)

object ActivityPhases {
    fun resolve(input: ActivityPhaseInput): String =
        if (input.phase !in setOf("completed", "failed", "idle", "ready") && input.phoneStopping) "finishing"
        else input.phase
}

data class ActivityControl(
    val action: String,
    val label: String,
    val symbol: String,
    val rideId: String,
    val controlToken: String,
    val expectedPhase: String,
    val requiresConfirmation: Boolean,
)

data class ActivityInput
@Throws(IllegalArgumentException::class)
constructor(
    val rideId: String,
    val publicationSequence: Long,
    val controlToken: String,
    val phase: String,
    val pendingAction: String?,
    val timerSeconds: Double?,
) {
    init {
        require(
            presentationId(rideId) == rideId && publicationSequence > 0 && presentationId(controlToken) == controlToken
        )
    }
}

class ActivityPresentation(
    val rideId: String,
    val publicationSequence: Long,
    val controlToken: String,
    val expectedPhase: String,
    val bike: EntryAlternatives,
    val heartRate: EntryAlternatives,
    val bikeExpiresAtUs: Long?,
    val heartRateExpiresAtUs: Long?,
    val bikeAvailableStatus: ActivityStatus,
    val bikeExpiredStatus: ActivityStatus,
    val systemStaleStatus: ActivityStatus,
    controls: List<ActivityControl>,
    val timer: TimerInstruction,
    val systemStaleTimer: TimerInstruction,
    val staleDateUs: Long?,
) {
    private val retainedControls = controls.toList()
    val controls: List<ActivityControl>
        get() = retainedControls.toList()

    fun render(nowUtcUs: Long, systemStale: Boolean): ActivityRender {
        fun select(entries: EntryAlternatives, expiry: Long?): FormattedEntry =
            if (systemStale) entries.systemStale
            else if (expiry != null && nowUtcUs < expiry) entries.available else entries.expired
        return ActivityRender(
            select(bike, bikeExpiresAtUs),
            select(heartRate, heartRateExpiresAtUs),
            if (systemStale) systemStaleStatus
            else if (bikeExpiresAtUs != null && nowUtcUs < bikeExpiresAtUs) bikeAvailableStatus else bikeExpiredStatus,
            if (systemStale) systemStaleTimer else timer,
            if (systemStale) emptyList() else retainedControls,
            !systemStale,
        )
    }
}

data class ActivityRender(
    val bike: FormattedEntry,
    val heartRate: FormattedEntry,
    val status: ActivityStatus,
    val timer: TimerInstruction,
    val controls: List<ActivityControl>,
    val showReadings: Boolean,
)

object ActivityPresentations {
    const val refreshSeconds = 15.0

    private fun checkedAdd(a: Long, b: Long): Long? =
        if ((b > 0 && a > Long.MAX_VALUE - b) || (b < 0 && a < Long.MIN_VALUE - b)) null else a + b

    private fun deadline(reading: LiveReading?, clock: PresentationClock, seconds: Double): Long? {
        val age = reading?.acquisition?.ageUs(clock) ?: return null
        return checkedAdd(clock.utcUs, (seconds * 1_000_000).toLong() - age)
    }

    private fun entry(metric: String, reading: LiveReading?): EntryAlternatives {
        val declaration = MetricCatalog.metric(metric)
        val text = MetricCatalog.format(metric, reading?.values?.get(metric))
        fun value(text: String) =
            FormattedEntry(text, declaration.unit, declaration.color, "${declaration.label}: $text ${declaration.unit}")
        val missing = value("—")
        return EntryAlternatives(value(text), missing, missing)
    }

    private fun status(input: ActivityInput, bikeAvailable: Boolean): ActivityStatus {
        val text =
            if (input.pendingAction != null) "Updating…"
            else
                when (input.phase) {
                    "running" -> if (bikeAvailable) "Recording" else "Bike unavailable"
                    "paused" -> "Paused"
                    "preparing" -> "Starting…"
                    "interrupted" -> "Open Power Log"
                    "finishing" -> "Finishing…"
                    else -> "Ride ended"
                }
        val symbol =
            if (input.phase == "running") {
                if (bikeAvailable) "bicycle" else "antenna.radiowaves.left.and.right.slash"
            } else "pause.fill"
        return ActivityStatus(text, symbol, text)
    }

    fun project(input: ActivityInput, state: LivePresentationState, clock: PresentationClock): ActivityPresentation {
        val matching = state.rideId == input.rideId
        val bike = if (matching) state.reading(LiveSource.controller) else null
        val heart = if (matching) state.reading(LiveSource.heartRate) else null
        val bikeExpiry = deadline(bike, clock, StreamStatus.controllerStaleSeconds + refreshSeconds)
        val heartExpiry = deadline(heart, clock, StreamStatus.heartStaleSeconds + refreshSeconds)
        val staleDate = listOfNotNull(bikeExpiry, heartExpiry).filter { it > clock.utcUs }.minOrNull()
        val context = ExternalControlContext(input.rideId, input.controlToken, input.phase, input.pendingAction)
        fun control(action: String, label: String, symbol: String): ActivityControl? {
            val decision =
                RideActions.external(
                    context,
                    ExternalControlRequest(input.rideId, input.controlToken, input.phase, action),
                    false,
                )
            if (decision.outcome == "rejected") return null
            return ActivityControl(
                action,
                label,
                symbol,
                input.rideId,
                input.controlToken,
                input.phase,
                decision.outcome == "confirmationRequired",
            )
        }
        val controls =
            listOfNotNull(
                control("pause", "Pause", "pause.fill"),
                control("resume", "Resume", "play.fill"),
                control("finish", "Finish", "stop.fill"),
            )
        val duration = input.timerSeconds
        val origin =
            if (duration != null && duration.isFinite() && duration >= 0 && duration < Long.MAX_VALUE / 1_000_000.0)
                checkedAdd(clock.utcUs, -floor(duration * 1_000_000).toLong())
            else null
        val timer =
            if (input.phase == "running" && origin != null) TimerInstruction("running", origin, null, "Ride time")
            else if (duration != null && duration.isFinite())
                TimerInstruction("frozen", null, PresentationFormatting.duration(duration), "Ride time")
            else TimerInstruction("unavailable", null, "—", "Ride time")
        return ActivityPresentation(
            input.rideId,
            input.publicationSequence,
            input.controlToken,
            input.phase,
            entry("humanPowerW", bike),
            entry("heartRateBpm", heart),
            bikeExpiry,
            heartExpiry,
            status(input, true),
            status(input, false),
            ActivityStatus("Open Power Log", "clock.badge.questionmark", "Ride status unavailable"),
            controls,
            timer,
            TimerInstruction("unavailable", null, "—", "Ride status unavailable"),
            staleDate,
        )
    }
}
