package app.powerlog.platform.ridecard

import android.app.Notification
import android.content.Intent
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideTimer
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.RideCardState
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidRideCardTest {
    private val rig = RideCardRig()
    private val card = rig.card

    private fun text(notification: Notification?) = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)

    private fun title(notification: Notification?) = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)

    private fun foreground() {
        card.foregroundNotification()
        card.foregrounded()
        rig.idle()
    }

    @Test
    fun theNotificationDrawsReadingsTheTimerAndImmutableControlsCarryingTheirIdentity() {
        val facts = rig.facts()
        val notification = card.notification(facts, rig.nowUtcUs(), ongoing = true)
        assertEquals("Recording", title(notification))
        assertEquals("200 W · 150 bpm", text(notification))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals((rig.nowUtcUs() - 10_000_000) / 1_000, notification.`when`)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("Pause", "Finish"), notification.actions.map { it.title.toString() })
        val pause = notification.actions[0].actionIntent
        val finish = notification.actions[1].actionIntent
        assertTrue(shadowOf(pause).isServiceIntent)
        assertTrue(shadowOf(finish).isActivityIntent)
        for ((pending, action) in listOf(pause to RideAction.pause, finish to RideAction.finish)) {
            assertTrue(pending.isImmutable)
            val intent = requireNotNull(RideCardIntents.read(shadowOf(pending).savedIntent))
            assertEquals(RideCardRig.RIDE, intent.rideId)
            assertEquals(RideCardRig.TOKEN, intent.token)
            assertEquals(RideCardState.running, intent.phase)
            assertEquals(action, intent.action)
        }
        val newer = card.notification(rig.facts(token = RideCardRig.OLD_TOKEN), rig.nowUtcUs(), ongoing = true)
        assertNotEquals(shadowOf(pause).savedIntent.data, shadowOf(newer.actions[0].actionIntent).savedIntent.data)
    }

    @Test
    fun readingsTurnToDashesAtTheirFreshUntilInstantsAndAPausedTimerShowsItsText() {
        val facts = rig.facts()
        assertEquals("— W · — bpm", text(card.notification(facts, rig.nowUtcUs() + 30_000_000, ongoing = true)))
        assertEquals("— W · 150 bpm", text(card.notification(facts, rig.nowUtcUs() + 21_000_000, ongoing = true)))
        val paused =
            card.notification(
                rig.facts(
                    state = RideCardState.paused,
                    timer = RideTimer(TimerState.frozen, 10_000_000, null),
                    controls = listOf(RideAction.resume, RideAction.finish),
                ),
                rig.nowUtcUs(),
                ongoing = true,
            )
        assertEquals("Paused", title(paused))
        assertFalse(paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("200 W · 150 bpm · 00:10", text(paused))
        assertEquals(listOf("Resume", "Finish"), paused.actions.map { it.title.toString() })
    }

    @Test
    fun anInterruptedRideShowsOpenPowerLogWithoutControls() {
        val interrupted =
            card.notification(
                rig.facts(
                    state = RideCardState.interrupted,
                    controls = emptyList(),
                    timer = RideTimer(TimerState.unavailable, 0, null),
                ),
                rig.nowUtcUs(),
                ongoing = false,
            )
        assertEquals("Open Power Log", title(interrupted))
        assertNull(interrupted.actions)
        assertTrue(interrupted.flags and Notification.FLAG_ONGOING_EVENT == 0)
    }

    @Test
    fun withoutAHoldInThisProcessPublishingPostsNothing() {
        card.publish(rig.facts(state = RideCardState.interrupted, controls = emptyList()))
        rig.idle(Duration.ofSeconds(60))
        assertNull(rig.shown())
    }

    @Test
    fun theForegroundNotificationIsThePlaceholderUntilTheFirstFactsThenTheNewestFacts() {
        assertEquals("Power Log", title(card.foregroundNotification()))
        card.publish(rig.facts())
        rig.idle()
        assertEquals("Recording", title(card.foregroundNotification()))
    }

    @Test
    fun manyPublishesPostAtMostFiveASecondOnceTheSystemsAverageSettles() {
        foreground()
        val posted = mutableListOf<Pair<Int, Notification>>()
        repeat(1_200) { index ->
            card.publish(rig.facts(powerW = index.toDouble()))
            rig.idle(Duration.ofMillis(10))
            rig.shown()?.let { if (posted.lastOrNull()?.second !== it) posted += index to it }
        }
        val settled = posted.count { (index, _) -> index >= 200 }
        assertTrue("$settled posts in 10 s", settled in 10..50)
        rig.idle(Duration.ofSeconds(2))
        assertEquals("1199 W · 150 bpm", text(rig.shown()))
    }

    @Test
    fun theCardIsPostedAgainAtTheEarliestFreshUntilInstant() {
        foreground()
        card.publish(rig.facts())
        rig.idle()
        val first = rig.shown()
        assertEquals("200 W · 150 bpm", text(first))
        rig.idle(Duration.ofMillis(20_900))
        assertSame(first, rig.shown())
        rig.idle(Duration.ofMillis(200))
        assertEquals("— W · 150 bpm", text(rig.shown()))
        rig.idle(Duration.ofSeconds(9))
        assertEquals("— W · — bpm", text(rig.shown()))
    }

    @Test
    fun aControlChangeIsPostedAtOnce() {
        foreground()
        card.publish(rig.facts())
        rig.idle()
        val running = rig.shown()
        card.publish(
            rig.facts(
                state = RideCardState.updating,
                token = RideCardRig.OLD_TOKEN,
                controls = emptyList(),
            )
        )
        rig.idle()
        assertNotSame(running, rig.shown())
        assertEquals("Updating…", title(rig.shown()))
    }

    @Test
    fun endAfterADetachedReleaseCancelsTheNotification() {
        val service =
            org.robolectric.Robolectric.buildService(app.powerlog.platform.host.RecordingService::class.java)
                .create()
                .get()
        foreground()
        card.publish(rig.facts())
        rig.idle()
        card.released(service)
        rig.idle()
        val detached = rig.shown()
        assertNotNull(detached)
        assertTrue(requireNotNull(detached).flags and Notification.FLAG_ONGOING_EVENT == 0)
        card.publish(rig.facts(powerW = 210.0))
        rig.idle(Duration.ofSeconds(1))
        assertEquals("210 W · 150 bpm", text(rig.shown()))
        card.end()
        rig.idle()
        assertNull(rig.shown())
        card.publish(rig.facts())
        rig.idle(Duration.ofSeconds(1))
        assertNull(rig.shown())
    }

    @Test
    fun theFinishIntentCarriesThePathlessUrlThatRoutesToTheRideTab() {
        val finish = card.notification(rig.facts(), rig.nowUtcUs(), ongoing = true).actions[1].actionIntent
        assertEquals(
            "power-log:///?control=${RideCardRig.RIDE}%2F${RideCardRig.TOKEN}%2Frunning%2Ffinish",
            shadowOf(finish).savedIntent.dataString,
        )
    }

    @Test
    fun endCancelsAnEarlierProcesssDetachedCard() {
        val notifications = rig.application.getSystemService(android.app.NotificationManager::class.java)
        notifications.notify(AndroidRideCard.NOTIFICATION, card.placeholder())
        card.end()
        rig.idle()
        assertNull(rig.shown())
    }

    @Test
    fun onlyTheCardsOwnIntentsAreRead() {
        assertNull(RideCardIntents.read(Intent().putExtra("action", "pause").putExtra("ride", RideCardRig.RIDE)))
        val control = app.powerlog.kit.RideCardProjection.project(rig.facts(), rig.nowUtcUs()).controls.first()
        val legacy = RideCardIntents.command(Intent(), control).setData(android.net.Uri.parse("power-log:///?n=1"))
        assertNull(RideCardIntents.read(legacy))
        val unknown = RideCardIntents.command(Intent(), control).putExtra(RideCardIntents.ACTION, "stop")
        assertNull(RideCardIntents.read(unknown))
    }
}
