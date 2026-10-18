package app.powerlog.bridge

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import app.powerlog.core.presentation.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RecordingServiceTest {
    private fun fixture(name: String) =
        JSONObject(File("../../../tests/fixtures/contract/presentation/$name.json").readText())

    private val fixture = fixture("activity")
    private val ride = fixture.getString("rideA")
    private val token = fixture.getString("token")

    private fun snapshot(phase: String, pending: String? = null, timer: Double = 10.0) =
        RideSnapshot(
            PlatformCapabilities.android(false),
            ride,
            phase,
            timer,
            "1",
            null,
            false,
            false,
            false,
            false,
            "notRequested",
            RideStreams(RideStream("receiving"), RideStream("receiving"), RideGPSStream("off")),
            null,
            controlToken = token,
            pendingAction = pending,
        )

    private fun live(bike: Boolean = true): LivePresentationState {
        var state = LivePresentation.adopt(LivePresentation.empty(), ride)
        for ((source, metric, value) in
            listOf(
                Triple(LiveSource.controller, "humanPowerW", 200.0),
                Triple(LiveSource.heartRate, "heartRateBpm", 150.0),
            )) {
            if (source == LiveSource.controller && !bike) continue
            val acquired =
                fixture.getString(if (source == LiveSource.controller) "bikeAcquiredUs" else "heartAcquiredUs").toLong()
            val clock = PresentationClock("process", acquired, acquired)
            state =
                LivePresentation.observe(
                    state,
                    ride,
                    source,
                    LiveReading(
                        LiveAcquisition(source.name, null, acquired, "process", acquired),
                        mapOf(metric to value),
                    ),
                    clock,
                )
        }
        return state
    }

    @Test
    fun activityFixturesPassThroughNotificationProjectionAndRender() {
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val now = row.getString("nowUs").toLong()
            val state = snapshot(row.getString("phase"), if (row.isNull("pending")) null else row.getString("pending"))
            val projection =
                RideNotificationAdapter.project(state, index + 1L, live(), PresentationClock("process", now, now))
            val rendered = projection.render(now, row.getBoolean("systemStale"))
            assertEquals(row.toString(), row.getString("bike"), rendered.bike.text)
            assertEquals(row.getString("heart"), rendered.heartRate.text)
            assertEquals(row.getString("status"), rendered.status.text)
            assertEquals(row.getString("timer"), rendered.timer.mode)
            assertEquals(
                (0 until row.getJSONArray("controls").length()).map { row.getJSONArray("controls").getString(it) },
                rendered.controls.map { it.action },
            )
            assertEquals(
                if (row.isNull("staleDateUs")) null else row.getString("staleDateUs").toLong(),
                projection.staleDateUs,
            )
            assertEquals(fixture.getString("bikeExpiresAtUs").toLong(), projection.bikeExpiresAtUs)
            assertEquals(fixture.getString("heartRateExpiresAtUs").toLong(), projection.heartRateExpiresAtUs)
        }
    }

    @Test
    fun missingEvidenceAndRestartDoNotInventDeadlines() {
        val clock = PresentationClock("process", 105000000, 105000000)
        val missing = RideNotificationAdapter.project(snapshot("running"), 1, live(false), clock)
        assertNull(missing.bikeExpiresAtUs)
        assertEquals(115000000L, missing.staleDateUs)
        assertEquals("—", missing.render(clock.utcUs, false).bike.text)
        val restarted = RideNotificationAdapter.project(snapshot("running"), 2, live(), clock.copy(epoch = "restart"))
        assertNull(restarted.staleDateUs)
        assertEquals("—", restarted.render(clock.utcUs, false).heartRate.text)
        val changed =
            RideNotificationAdapter.project(snapshot("running").copy(id = fixture.getString("rideB")), 3, live(), clock)
        assertNull(changed.staleDateUs)
    }

    @Test
    fun externalFixturesPassThroughNotificationActionAdmission() {
        val actions = fixture("actions").getJSONArray("external")
        for (index in 0 until actions.length()) {
            val row = actions.getJSONObject(index)
            val request =
                ExternalControlRequest(
                    ride,
                    row.getString("token"),
                    row.getString("expectedPhase"),
                    row.getString("action"),
                )
            val decision =
                RideNotificationAdapter.external(
                    snapshot("running", if (row.isNull("pending")) null else row.getString("pending")),
                    request,
                    row.getBoolean("confirmed"),
                )
            assertEquals(row.getString("outcome"), decision.outcome)
            assertEquals(if (row.isNull("command")) null else row.getString("command"), decision.command)
        }
    }

    @Test
    fun finishWithTheCurrentTokenStillRejectsAnotherExpectedPhase() {
        val request = ExternalControlRequest(ride, token, "paused", "finish")
        val decision = RideNotificationAdapter.external(snapshot("running"), request, false)
        assertEquals("rejected", decision.outcome)
        assertNull(decision.command)
    }

    @Test
    fun pendingActionFencesBothUnconfirmedAndConfirmedExternalFinish() {
        val request = ExternalControlRequest(ride, token, "running", "finish")
        for (confirmed in listOf(false, true)) {
            val decision = RideNotificationAdapter.external(snapshot("running", "lap"), request, confirmed)
            assertEquals("rejected", decision.outcome)
            assertNull(decision.command)
        }
    }

    @Test
    fun notificationDrawsReadingsTimerAndCarriesImmutableControlIdentity() {
        val service = Robolectric.buildService(RecordingService::class.java).get()
        val component = ComponentName(service.packageName, "${service.packageName}.MainActivity")
        shadowOf(service.packageManager).addActivityIfNotPresent(component)
        shadowOf(service.packageManager)
            .addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        val clock = PresentationClock("process", 105000000, 105000000)
        val projection = RideNotificationAdapter.project(snapshot("running"), 1, live(), clock)
        val notification = service.notification(projection, clock.utcUs)
        assertEquals("Recording", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("200 W · 150 bpm", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(95000L, notification.`when`)
        val pause = notification.actions[0].actionIntent
        val finish = notification.actions[1].actionIntent
        assertTrue(shadowOf(pause).isServiceIntent)
        assertTrue(shadowOf(finish).isActivityIntent)
        for (pending in listOf(pause, finish)) {
            val request = requireNotNull(RideNotificationAdapter.request(shadowOf(pending).savedIntent))
            assertEquals(ride, request.rideId)
            assertEquals(token, request.controlToken)
            assertEquals("running", request.expectedPhase)
            assertTrue(pending.isImmutable)
        }
        assertEquals("finish", RideNotificationAdapter.request(shadowOf(finish).savedIntent)?.action)
        val expired = service.notification(projection, 115000000)
        assertEquals("— W · — bpm", expired.extras.getCharSequence(Notification.EXTRA_TEXT))
        val paused =
            service.notification(RideNotificationAdapter.project(snapshot("paused"), 2, live(), clock), clock.utcUs)
        assertFalse(paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("200 W · 150 bpm · 00:10", paused.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(RideNotificationAdapter.request(Intent().putExtra("action", "stop").putExtra("ride", ride)))
        val next = projection.controls[0].copy(controlToken = fixture("actions").getString("oldToken"))
        val oldIntent = shadowOf(pause).savedIntent
        val newIntent = RideNotificationAdapter.command(Intent(), next)
        assertNotEquals(oldIntent.data, newIntent.data)
        assertEquals(token, RideNotificationAdapter.request(oldIntent)?.controlToken)
    }
}
