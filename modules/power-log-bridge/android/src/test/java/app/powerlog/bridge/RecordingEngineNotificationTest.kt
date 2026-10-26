package app.powerlog.bridge

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import app.powerlog.core.presentation.*
import app.powerlog.core.telemetry.*
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

class RecordingEngineNotificationTest : RecordingEngineTestBase() {
    @Test
    fun postedNotificationCarriesCoreTimersAndCurrentControlsAcrossPauseResumeAndFinish() {
        val id = command { start() }
        val origin = command { timeUs(iso()) / 1000 }
        openNotificationService()
        assertEquals(origin, postedNotification().`when`)
        assertTrue(postedNotification().extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        val original =
            RideNotificationAdapter.request(shadowOf(postedNotification().actions[0].actionIntent).savedIntent)
        command {
            advanceTo(5)
            engine.action("pause", id)
        }
        idleNotifications()
        assertEquals("Paused", postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("— W · — bpm · 00:05", postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
        assertFalse(postedNotification().extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        val paused =
            requireNotNull(
                RideNotificationAdapter.request(shadowOf(postedNotification().actions[0].actionIntent).savedIntent)
            )
        assertEquals("resume", paused.action)
        assertNotEquals(original?.controlToken, paused.controlToken)
        command {
            advanceTo(10)
            engine.action("resume", id)
        }
        idleNotifications()
        assertEquals(origin + 5000, postedNotification().`when`)
        assertTrue(postedNotification().extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        val resumed =
            requireNotNull(
                RideNotificationAdapter.request(shadowOf(postedNotification().actions[0].actionIntent).savedIntent)
            )
        assertEquals("pause", resumed.action)
        assertEquals(command { engine.state().str("controlToken") }, resumed.controlToken)
        assertNotEquals(paused.controlToken, resumed.controlToken)
        command { engine.action("stop", id) }
        idleNotifications()
        assertNull(
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java))
                .getNotification(4101)
        )
    }

    @Test
    fun notificationRejectsTokenPhaseAndCycleReplaysWithoutEffects() = command {
        val id = start()
        val publication = requireNotNull(engine.notificationState)
        val pause = ExternalControlRequest(id, publication.controlToken, "running", "pause")
        val original = engine.state()
        for (request in
            listOf(
                pause.let {
                    ExternalControlRequest(
                        it.rideId,
                        UUID.randomUUID().toString(),
                        it.expectedPhase,
                        it.action,
                    )
                },
                ExternalControlRequest(id, publication.controlToken, "paused", "pause"),
                ExternalControlRequest(
                    UUID.randomUUID().toString(),
                    publication.controlToken,
                    "running",
                    "pause",
                ),
            )) {
            assertThrows(IllegalStateException::class.java) {
                engine.externalAction(request, false)
            }
            assertEquals(original, engine.state())
            assertSame(publication, engine.notificationState)
        }
        engine.externalAction(pause, false)
        assertEquals("paused", engine.state()["phase"])
        engine.action("resume", id)
        val events = engine.store.events(id)
        assertThrows(IllegalStateException::class.java) { engine.externalAction(pause, false) }
        assertEquals(events, engine.store.events(id))
        assertEquals("running", engine.state()["phase"])
    }

    @Test
    fun notificationFinishUsesTheSharedConfirmationAndFencesPendingAndCancelledRequests() = command {
        val id = start()
        var request =
            ExternalControlRequest(
                id,
                requireNotNull(engine.notificationState).controlToken,
                "running",
                "finish",
            )
        val events = engine.store.events(id)
        assertThrows(IllegalStateException::class.java) { engine.externalAction(request, true) }
        engine.externalAction(request, false)
        assertEquals(request.toWireMap(), engine.state()["externalFinish"])
        assertEquals(events, engine.store.events(id))
        assertThrows(IllegalStateException::class.java) {
            engine.externalAction(request, false)
        }
        RecordingEngine::class.java.getDeclaredField("pendingAction").apply { isAccessible = true }.set(engine, "lap")
        try {
            assertThrows(IllegalStateException::class.java) {
                engine.externalAction(request, true)
            }
            assertEquals(events, engine.store.events(id))
        } finally {
            RecordingEngine::class
                .java
                .getDeclaredField("pendingAction")
                .apply { isAccessible = true }
                .set(engine, null)
        }
        engine.cancelExternalAction(request)
        assertThrows(IllegalStateException::class.java) { engine.externalAction(request, true) }
        request =
            ExternalControlRequest(
                id,
                requireNotNull(engine.notificationState).controlToken,
                "running",
                "finish",
            )
        engine.externalAction(request, false)
        engine.externalAction(request, true)
        assertNull(engine.notificationState)
        assertEquals("completed", engine.store.metadata(id)["phase"])
        assertThrows(IllegalStateException::class.java) { engine.externalAction(request, true) }
        Unit
    }

    @Test
    fun notificationFinishIntentRoutesThroughTheBridgeWithoutStopping() {
        val (id, intent, request) =
            command {
                val id = start()
                val control = requireNotNull(engine.notificationState).controls.last()
                val intent = RideNotificationAdapter.command(android.content.Intent(), control)
                Triple(id, intent, requireNotNull(RideNotificationAdapter.request(intent)))
            }
        val replay = android.content.Intent(intent)
        val module = PowerLogBridgeModule { engine }
        module.notificationIntent(intent)
        command {
            assertEquals(request.toWireMap(), engine.state()["externalFinish"])
            assertEquals("running", engine.state()["phase"])
            assertEquals(listOf("start"), engine.store.events(id).map { it.action })
            engine.cancelExternalAction(request)
        }
        module.notificationIntent(replay)
        command {
            assertNull(engine.state()["externalFinish"])
            assertEquals(listOf("start"), engine.store.events(id).map { it.action })
        }
    }

    @Test
    fun notificationDeadlineExpiresPostedPowerWithoutResurrectingAnOlderReading() {
        command {
            start()
            telemetry()
        }
        openNotificationService()
        assertEquals(
            "120 W · — bpm",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        command {
            advanceTo(2)
            telemetry()
            engine.flush()
        }
        idleNotifications()
        command { advanceTo(21) }
        idleNotifications()
        assertEquals(
            "120 W · — bpm",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        command { advanceTo(23) }
        idleNotifications()
        assertEquals(
            "— W · — bpm",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(
            "Bike unavailable",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        command { assertEquals(2, engine.store.page(engine.state().str("id")).size) }
    }

    @Test fun coalescedReadingKeepsTheDeadlineOfThePostedControlPresentation() = assertCoalescedReadingDeadline(null)

    @Test fun coalescedValidReadingReplacesThePostedValueAtItsDeadline() = assertCoalescedReadingDeadline(200.0)

    private fun assertCoalescedReadingDeadline(newPower: Double?) {
        val id = command {
            val id = start()
            telemetry()
            id
        }
        openNotificationService()
        ShadowSystemClock.advanceBy(Duration.ofMillis(20500))
        command { engine.action("pause", id) }
        idleNotifications()
        assertEquals("120 W · — bpm · 00:20", postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
        val before = notificationPublications().size
        ShadowSystemClock.advanceBy(Duration.ofMillis(50))
        command { telemetry(power = newPower) }
        idleNotifications()
        assertEquals(before, notificationPublications().size)
        assertEquals("120 W · — bpm · 00:20", postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
        ShadowSystemClock.advanceBy(Duration.ofMillis(450))
        idleNotifications()
        assertEquals(
            if (newPower == null) "— W · — bpm · 00:20" else "200 W · — bpm · 00:20",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(before + 1, notificationPublications().size)
        assertEquals("paused", requireNotNull(engine.notificationState).expectedPhase)
        command { engine.action("discard", id) }
        awaitHealthWork()
        service!!.destroy()
        service = null
    }

    @Test
    fun routineReadingsWaitForTheFifteenSecondActivityRefresh() {
        command {
            start()
            telemetry()
        }
        openNotificationService()
        val posted = notificationPublications().size
        ShadowSystemClock.advanceBy(Duration.ofMillis(14999))
        command { telemetry(power = 200.0) }
        idleNotifications()
        assertEquals(posted, notificationPublications().size)
        assertEquals("120 W · — bpm", postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        idleNotifications()
        assertEquals(posted + 1, notificationPublications().size)
        assertEquals("200 W · — bpm", postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun pauseImmediatelyFollowingARoutinePostPublishesItsStatusAndNewToken() {
        val id = command {
            val id = start(sampleHz = 8)
            telemetry()
            id
        }
        openNotificationService()
        ShadowSystemClock.advanceBy(Duration.ofMillis(15000))
        command { telemetry() }
        idleNotifications()
        val before = notificationPublications().size
        val oldToken = command { engine.state().str("controlToken") }
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        command { engine.action("pause", id) }
        idleNotifications()
        assertEquals(before + 1, notificationPublications().size)
        assertEquals("Paused", postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE))
        val request =
            requireNotNull(
                RideNotificationAdapter.request(shadowOf(postedNotification().actions[0].actionIntent).savedIntent)
            )
        assertNotEquals(oldToken, request.controlToken)
        assertEquals(command { engine.state().str("controlToken") }, request.controlToken)
        assertEquals("paused", request.expectedPhase)
    }

    @Test
    fun controlsUseAospEnqueueEstimateAndKeepTheLatestStateAcrossEightHzReadings() {
        val id = command {
            val id = start(sampleHz = 8)
            telemetry()
            id
        }
        openNotificationService()
        val tick = field("tick") as Runnable
        for (index in 1..640) {
            command {
                ShadowSystemClock.advanceBy(Duration.ofMillis(125))
                telemetry()
                if (index % 8 == 0) {
                    tick.run()
                    engine.handler.removeCallbacks(tick)
                }
            }
            idleNotifications()
            if (index == 8) {
                for (action in listOf("pause", "resume", "pause")) {
                    ShadowSystemClock.advanceBy(Duration.ofMillis(1))
                    command { engine.action(action, id) }
                    idleNotifications()
                    assertEquals(
                        if (action == "pause") "Paused" else "Recording",
                        postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE),
                    )
                    val request =
                        requireNotNull(
                            RideNotificationAdapter.request(
                                shadowOf(postedNotification().actions[0].actionIntent).savedIntent
                            )
                        )
                    assertEquals(command { engine.state().str("controlToken") }, request.controlToken)
                }
                command { engine.action("resume", id) }
                idleNotifications()
            }
        }
        val before = notificationPublications().size
        for (index in 0..40) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            command { engine.action(if (index % 2 == 0) "pause" else "resume", id) }
            idleNotifications()
        }
        val aospLimitPerSecond = 5.0
        val aospAlpha = 0.7
        val aospMinimumDeltaSeconds = 0.0005
        var interarrivalSeconds = 1000.0
        var previous: Long? = null
        for ((time, _) in notificationPublications()) {
            previous?.let { last ->
                val delta = maxOf((time - last) / 1000.0, aospMinimumDeltaSeconds)
                interarrivalSeconds = aospAlpha * interarrivalSeconds + (1 - aospAlpha) * delta
                assertTrue(
                    "AOSP limit=5/s; rate=1/(0.7*previousInterval+0.3*max(deltaSeconds,0.0005)) at $time",
                    1 / interarrivalSeconds <= aospLimitPerSecond,
                )
            }
            previous = time
        }
        assertTrue(notificationPublications().size - before < 41)
        val now = SystemClock.uptimeMillis()
        val due =
            requireNotNull(previous) +
                kotlin.math
                    .ceil((1 / aospLimitPerSecond - aospAlpha * interarrivalSeconds) / (1 - aospAlpha) * 1000)
                    .toLong()
        assertTrue("A pending control waits only for the AOSP estimate", due > now)
        val waiting = notificationPublications().size
        ShadowSystemClock.advanceBy(Duration.ofMillis(due - now - 1))
        idleNotifications()
        assertEquals(waiting, notificationPublications().size)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        idleNotifications()
        assertEquals(waiting + 1, notificationPublications().size)
        assertEquals("Paused", postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE))
        val request =
            requireNotNull(
                RideNotificationAdapter.request(shadowOf(postedNotification().actions[0].actionIntent).savedIntent)
            )
        assertEquals(command { engine.state().str("controlToken") }, request.controlToken)
        val lastDelta = maxOf((due - requireNotNull(previous)) / 1000.0, aospMinimumDeltaSeconds)
        assertTrue(1 / (aospAlpha * interarrivalSeconds + (1 - aospAlpha) * lastDelta) <= aospLimitPerSecond)
    }

    @Test
    fun readingStatusChangesBypassRoutineCoalescingWithoutAControlTokenChange() {
        val id = command { start() }
        openNotificationService()
        assertEquals("Bike unavailable", postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE))
        val token = command { engine.state().str("controlToken") }
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        command { telemetry() }
        idleNotifications()
        assertEquals("Recording", postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("120 W · — bpm", postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
        val request =
            requireNotNull(
                RideNotificationAdapter.request(shadowOf(postedNotification().actions[0].actionIntent).savedIntent)
            )
        assertEquals(id, request.rideId)
        assertEquals(token, request.controlToken)
    }

    @Test
    fun initialForegroundPublicationCannotReplaceAnAlreadyAcceptedNewerProjection() {
        command { start() }
        val snapshot = command {
            RecordingEngine::class
                .java
                .getDeclaredMethod("snapshot", Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(engine, SystemClock.elapsedRealtime(), true) as RideSnapshot
        }
        val clock = PresentationClock("startup", 0, System.currentTimeMillis() * 1000)
        val live = LivePresentation.adopt(LivePresentation.empty(), snapshot.id)
        val running = RideNotificationAdapter.project(snapshot, 4, live, clock)
        val paused =
            RideNotificationAdapter.project(
                snapshot.copy(phase = "paused", controlToken = UUID.randomUUID().toString()),
                5,
                live,
                clock,
            )
        val projectionField =
            RecordingEngine::class.java.getDeclaredField("notificationState").apply { isAccessible = true }
        projectionField.set(engine, running)
        openNotificationService(false)
        val target = service!!.get()
        val completed = CompletableFuture<Int>()
        val startup = Thread {
            try {
                completed.complete(target.onStartCommand(null, 0, 1))
            } catch (error: Throwable) {
                completed.completeExceptionally(error)
            }
        }
        synchronized(target) {
            startup.start()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (startup.state != Thread.State.BLOCKED && System.nanoTime() < until) Thread.yield()
            assertEquals(Thread.State.BLOCKED, startup.state)
            projectionField.set(engine, paused)
            RecordingService.refresh()
        }
        completed.get(5, TimeUnit.SECONDS)
        startup.join(5000)
        idleNotifications()
        assertEquals("Paused", postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE))
        val latest =
            RecordingService::class.java.getDeclaredField("latest").apply { isAccessible = true }.get(target)
                as ActivityPresentation
        assertEquals(5, latest.publicationSequence)
        val request =
            requireNotNull(
                RideNotificationAdapter.request(shadowOf(postedNotification().actions[0].actionIntent).savedIntent)
            )
        assertEquals(paused.controlToken, request.controlToken)
    }

    @Test
    fun notificationPostsAtBothSuppliedReadingDeadlinesWithoutAReactWake() {
        val fixture = JSONObject(File("../../../tests/fixtures/contract/presentation/activity.json").readText())
        val id = command { start() }
        val startUtc = command { timeUs(iso()) }
        openNotificationService()
        val publication = command {
            telemetry()
            observeHeartRate(id)
            requireNotNull(engine.notificationState)
        }
        command { advanceTo(1) }
        idleNotifications()
        assertEquals("120 W · 150 bpm", postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(
            startUtc + fixture.getString("bikeExpiresAtUs").toLong() - fixture.getString("bikeAcquiredUs").toLong(),
            publication.bikeExpiresAtUs,
        )
        assertEquals(
            startUtc + fixture.getString("heartRateExpiresAtUs").toLong() -
                fixture.getString("heartAcquiredUs").toLong(),
            publication.heartRateExpiresAtUs,
        )
        command { advanceTo(21) }
        idleNotifications()
        assertEquals(
            "— W · 150 bpm",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(
            "Bike unavailable",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        command { advanceTo(30) }
        idleNotifications()
        assertEquals(
            "— W · — bpm",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertTrue(
            notificationPublications().any {
                it.second.extras.getCharSequence(Notification.EXTRA_TEXT) == "— W · 150 bpm"
            }
        )
        command { assertEquals(1, engine.store.page(engine.state().str("id")).size) }
    }

    @Test
    fun notificationExpiresWhileTheCaptureHandlerIsBusy() {
        val id = command { start(saveToHealth = true) }
        openNotificationService()
        command {
            advanceTo(29)
            telemetry()
            observeHeartRate(id)
        }
        idleNotifications()
        assertEquals(
            "120 W · 150 bpm",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        command { advanceTo(30) }
        engine.handler.post {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            advanceTo(50)
            idleNotifications()
            assertEquals(
                "— W · 150 bpm",
                postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT),
            )
            assertEquals(
                "Bike unavailable",
                postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE),
            )
            advanceTo(59)
            idleNotifications()
            assertEquals(
                "— W · — bpm",
                postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT),
            )
            assertEquals(1L, release.count)
            advanceTo(60)
        } finally {
            release.countDown()
            command { engine.handler.removeCallbacks(field("tick") as Runnable) }
            scheduledHealth = { _, _ -> }
        }
    }

    @Test
    fun eightHzReadingsAndTicksLeavePublicationCapacityForPause() {
        val id = command { start(sampleHz = 8) }
        openNotificationService()
        val oldToken = command { engine.state().str("controlToken") }
        val tick = field("tick") as Runnable
        for (index in 1..24) {
            command {
                ShadowSystemClock.advanceBy(Duration.ofMillis(125))
                telemetry()
                if (index % 8 == 0) {
                    tick.run()
                    engine.handler.removeCallbacks(tick)
                }
            }
            idleNotifications()
            if (index == 12) {
                command { engine.action("pause", id) }
                idleNotifications()
                assertEquals(
                    "Paused",
                    postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE),
                )
                val resume =
                    requireNotNull(
                        RideNotificationAdapter.request(
                            shadowOf(postedNotification().actions[0].actionIntent).savedIntent
                        )
                    )
                assertEquals("paused", resume.expectedPhase)
                assertNotEquals(oldToken, resume.controlToken)
                assertEquals(command { engine.state().str("controlToken") }, resume.controlToken)
            }
        }
        command { advanceTo(24) }
        idleNotifications()
        assertEquals("— W · — bpm · 00:01", postedNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
        val postings = notificationPublications()
        assertTrue(postings.isNotEmpty())
        for ((time, _) in postings) {
            val count = postings.count { it.first > time - 1000 && it.first <= time }
            assertTrue("$count notifications in one second at $time", count <= 5)
        }
    }

    @Test
    fun selectedControllerDoesNotRequestRideStreamWithoutRide() = command {
        ControllerBluetooth::class
            .java
            .getDeclaredField("desired")
            .apply { isAccessible = true }
            .set(engine.bluetooth, "fixture")
        assertEquals("off", stream("controller")["status"])
    }
}
