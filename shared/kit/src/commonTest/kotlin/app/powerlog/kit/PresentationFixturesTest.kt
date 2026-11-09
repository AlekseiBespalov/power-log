package app.powerlog.kit

import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideTimer
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.RideCardFacts
import app.powerlog.domain.recording.RideCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresentationFixturesTest {
    private val rewritten = setOf(FailureCode.ownerForeign)
    private val speedUnits = mapOf("km/h" to UnitSystem.metric, "mph" to UnitSystem.imperial)
    private val speeds = setOf(MetricId.speedMps, MetricId.controllerSpeedMps)

    private fun representable(value: Long): Boolean = value > -(1L shl 53) && value < 1L shl 53

    @Test
    fun everyKeptFailureCodeReadsAsToday() {
        val vocabulary = fixture("presentation/failures.json")["vocabulary"].list()
        var checked = 0
        for (row in vocabulary) {
            val code = FailureCode.entries.firstOrNull { it.name == row["code"].text() } ?: continue
            if (code in rewritten) continue
            val ios = Texts.failure(code, null, TextPlatform.ios, null)
            assertEquals(row["message"].text(), ios.message, code.name)
            assertEquals(row["nextStep"].text(), ios.nextStep, code.name)
            val android = Texts.failure(code, null, TextPlatform.android, 31)
            assertEquals(row["androidNextStep"].optionalText() ?: row["nextStep"].text(), android.nextStep, code.name)
            checked++
        }
        assertEquals(33, checked)
    }

    @Test
    fun appleHealthOutcomesUseThePhrasesTheWatchShows() {
        for ((outcome, phrase) in fixture("presentation/health-states.json")["appleHealth"].fields()) {
            val health = RideHealth(HealthProviderKind.appleHealth, HealthOutcome.valueOf(outcome), null, null)
            assertEquals(phrase.text(), Texts.health(health), outcome)
            assertEquals(phrase.text(), Texts.historyHealthLine(health, emptyList()), outcome)
        }
    }

    @Test
    fun metricsFormatAsTodayInTheirUnits() {
        val formatting = fixture("presentation/formatting.json")
        for (row in formatting["metrics"].list()) {
            val metric = MetricId.valueOf(row["id"].text())
            val units = if (metric in speeds) speedUnits[row["unit"].text()] ?: continue else UnitSystem.metric
            val value = row["value"].let { if (it == J.Null) null else it.double() }
            assertEquals(row["text"].text(), Format.metric(metric, value, units, TemperatureUnit.celsius), "$row")
        }
        for (row in formatting["exact"].list()) {
            val original = row["original"].text().toLongOrNull() ?: continue
            if (!representable(original)) continue
            val metric = MetricId.valueOf(row["id"].text())
            assertEquals(
                row["text"].text(),
                Format.exact(metric, original.toDouble(), UnitSystem.metric, TemperatureUnit.celsius),
                "$row",
            )
        }
        for (row in formatting["duration"].list()) {
            val seconds = row["seconds"].let { if (it == J.Null) null else it.double() }
            val us = seconds?.let { kotlin.math.round(it * 1_000_000).toLong() }
            assertEquals(row["text"].text(), Format.duration(us), "$row")
        }
        for (row in formatting["differences"].list()) {
            val exact = listOf(row["aExact"], row["bExact"]).mapNotNull { it.optionalText() }
            if (!exact.all { it.toLongOrNull()?.let(::representable) == true }) continue
            val metric = MetricId.valueOf(row["id"].text())
            val a = row["a"].let { if (it == J.Null) null else it.double() }
            val b = row["b"].let { if (it == J.Null) null else it.double() }
            assertEquals(
                row["text"].text(),
                Format.difference(metric, a, b, UnitSystem.metric, TemperatureUnit.celsius),
                "$row",
            )
        }
        val selections = mapOf("auto" to DistanceSelection.auto, "controller" to DistanceSelection.controller)
        for (row in formatting["distanceOptions"].list()) {
            val source =
                when (val name = row["source"].text()) {
                    "gps:phone" -> DistanceSelection.phoneGps
                    else -> selections[name] ?: continue
                }
            assertEquals(row["label"].text(), Texts.distanceSource(source, row["foreground"].bool()), "$row")
        }
    }

    @Test
    fun theRideCardRendersTodaysEntriesStatusesControlsAndTimer() {
        val activity = fixture("presentation/activity.json")
        var checked = 0
        for (row in activity["cases"].list()) {
            val pending = row["pending"] != J.Null
            val state =
                when (row["phase"].text()) {
                    "running" -> if (pending) RideCardState.updating else RideCardState.running
                    "paused" -> if (pending) RideCardState.updating else RideCardState.paused
                    "preparing" -> RideCardState.starting
                    "interrupted" -> RideCardState.interrupted
                    else -> continue
                }
            val running = row["phase"].text() == "running"
            val facts =
                RideCardFacts(
                    rideId = activity["rideA"].text(),
                    token = activity["token"].text(),
                    state = state,
                    timer =
                        if (running) RideTimer(TimerState.running, 0, 95_000_000)
                        else RideTimer(TimerState.frozen, 0, null),
                    powerW = 200.0,
                    heartRateBpm = 150.0,
                    bikeFreshUntilUtcUs = activity["bikeExpiresAtUs"].long(),
                    heartRateFreshUntilUtcUs = activity["heartRateExpiresAtUs"].long(),
                    controls =
                        when (state) {
                            RideCardState.running -> listOf(RideAction.pause, RideAction.finish)
                            RideCardState.paused -> listOf(RideAction.resume, RideAction.finish)
                            else -> emptyList()
                        },
                )
            val now = row["nowUs"].long()
            val content = RideCardProjection.project(facts, now)
            val render = content.render(now, row["systemStale"].bool())
            assertEquals(row["bike"].text(), render.bike.text, "$row")
            assertEquals(row["heart"].text(), render.heartRate.text, "$row")
            assertEquals(row["status"].text(), render.status.text, "$row")
            assertEquals(row["controls"].list().map { it.text() }, render.controls.map { it.action.name }, "$row")
            assertEquals(row["timer"].text(), render.timer.mode.name, "$row")
            assertEquals(row["staleDateUs"].optionalText()?.toLong(), content.staleDateUtcUs, "$row")
            assertTrue(render.controls.all { it.requiresConfirmation == (it.action == RideAction.finish) }, "$row")
            checked++
        }
        assertEquals(14, checked)
    }
}
