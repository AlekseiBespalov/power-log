package app.powerlog.platform.keepalive

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.KeepAliveEvents
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.recording.RideCardState
import app.powerlog.platform.host.PowerLogAndroid
import app.powerlog.platform.host.RecordingService
import app.powerlog.platform.ridecard.AndroidRideCard
import app.powerlog.platform.ridecard.RideCardRig
import app.powerlog.runtime.host.HostFacade
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidRecordingKeepAliveTest {
    private val rig = RideCardRig()
    private val answers = mutableListOf<Result<Unit>>()
    private val expiring = mutableListOf<HoldKind>()

    private fun keepAlive(
        context: android.content.Context = rig.application,
        promote: ((android.app.Service, Int, Notification, Int) -> Unit)? = null,
    ) =
        (if (promote == null) AndroidRecordingKeepAlive(context, RecordingService::class.java, rig.card)
            else
                AndroidRecordingKeepAlive(
                    context,
                    RecordingService::class.java,
                    rig.card,
                    android.os.Handler(android.os.Looper.getMainLooper()),
                    promote,
                ))
            .also {
                it.attach(
                    object : KeepAliveEvents {
                        override fun expiring(kind: HoldKind) {
                            expiring += kind
                        }
                    }
                )
                PowerLogAndroid.keepAlive = it
            }

    private val reply
        get() =
            Reply<Unit>(
                onOk = { answers += Result.success(Unit) },
                onFailed = { answers += Result.failure(Refused(it)) },
            )

    private class Refused(val error: PortError) : Exception()

    private fun refusal(index: Int) = (answers[index].exceptionOrNull() as Refused).error.kind

    private fun wake(): PowerManager.WakeLock? = ShadowPowerManager.getLatestWakeLock()

    private fun arrive(): ServiceController<RecordingService> {
        val started = requireNotNull(shadowOf(rig.application).nextStartedService)
        assertEquals(ComponentName(rig.application, RecordingService::class.java), started.component)
        return Robolectric.buildService(RecordingService::class.java, started).create().startCommand(0, 1)
    }

    @After
    fun forget() {
        PowerLogAndroid.keepAlive = null
        PowerLogAndroid.host = null
    }

    @Test
    fun aRideHoldTakesTheWakeLockAtOnceThenAnswersOnceTheServiceIsInFront() {
        val keepAlive = keepAlive()
        keepAlive.hold(HoldKind.ride, location = true, reply)
        assertTrue(requireNotNull(wake()).isHeld)
        assertTrue(answers.isEmpty())
        val service = arrive().get()
        rig.idle()
        assertEquals(listOf(Result.success(Unit)), answers)
        val shadow = shadowOf(service)
        assertFalse(shadow.isForegroundStopped)
        assertEquals(AndroidRideCard.NOTIFICATION, shadow.lastForegroundNotificationId)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.foregroundServiceType,
        )
        assertEquals(
            "Power Log",
            shadow.lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertTrue(keepAlive.holding(service))
    }

    @Test
    fun theForegroundNotificationShowsFactsPublishedBeforeTheHold() {
        val keepAlive = keepAlive()
        rig.card.publish(rig.facts())
        rig.idle()
        keepAlive.hold(HoldKind.ride, location = false, reply)
        val service = arrive().get()
        assertEquals(
            "Recording",
            shadowOf(service).lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
    }

    @Test
    fun refusalsReleaseTheWakeLockAndAnswerTheirKind() {
        val refusals =
            listOf(
                ForegroundServiceStartNotAllowedException("background") to PortErrorKind.notAllowedInBackground,
                SecurityException("missing type") to PortErrorKind.denied,
                IllegalStateException("other") to PortErrorKind.failed,
            )
        refusals.forEachIndexed { index, (error, kind) ->
            val refusing =
                object : ContextWrapper(rig.application) {
                    override fun startForegroundService(service: Intent): ComponentName = throw error
                }
            keepAlive(refusing).hold(HoldKind.ride, location = true, reply)
            assertTrue(answers.size == index)
            rig.idle()
            assertEquals(kind, refusal(index))
            assertFalse(requireNotNull(wake()).isHeld)
        }
    }

    @Test
    fun aReleaseBeforeTheServiceArrivesStopsItOnlyAfterItIsPromoted() {
        val keepAlive = keepAlive()
        keepAlive.hold(HoldKind.ride, location = false, reply)
        keepAlive.release(HoldKind.ride)
        assertFalse(requireNotNull(wake()).isHeld)
        assertNull(shadowOf(rig.application).nextStoppedService)
        val service = arrive().get()
        val shadow = shadowOf(service)
        assertEquals(AndroidRideCard.NOTIFICATION, shadow.lastForegroundNotificationId)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(listOf(Result.success(Unit)), answers)
        assertFalse(keepAlive.holding(service))
    }

    @Test
    fun aMissingTypeRefusalInFrontStopsTheServiceAndReleasesTheLock() {
        val keepAlive = keepAlive(promote = { _, _, _, _ -> throw SecurityException("missing location type") })
        keepAlive.hold(HoldKind.ride, location = true, reply)
        val service = arrive().get()
        assertEquals(PortErrorKind.denied, refusal(0))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(requireNotNull(wake()).isHeld)
        assertFalse(keepAlive.holding(service))
    }

    @Test
    fun aPendingStartReleasedBeforeArrivalIsPromotedOnTheRideChannel() {
        val keepAlive = keepAlive()
        keepAlive.hold(HoldKind.ride, location = false, reply)
        keepAlive.release(HoldKind.ride)
        val notifications = rig.application.getSystemService(android.app.NotificationManager::class.java)
        assertNull(notifications.getNotificationChannel("rides"))
        val service = arrive().get()
        assertEquals(AndroidRideCard.NOTIFICATION, shadowOf(service).lastForegroundNotificationId)
        assertNotNull(notifications.getNotificationChannel("rides"))
    }

    @Test
    fun aNewHoldPromotedBeforeTheReleasesStopRunsKeepsTheService() {
        val keepAlive = keepAlive()
        keepAlive.hold(HoldKind.ride, location = false, reply)
        val controller = arrive()
        keepAlive.release(HoldKind.ride)
        keepAlive.hold(HoldKind.ride, location = false, reply)
        val started = requireNotNull(shadowOf(rig.application).nextStartedService)
        controller.withIntent(started).startCommand(0, 2)
        rig.idle()
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(keepAlive.holding(controller.get()))
        assertEquals(2, answers.count { it.isSuccess })
    }

    @Test
    fun withoutNotificationPermissionTheServiceStillRunsInFront() {
        shadowOf(rig.application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val keepAlive = keepAlive()
        keepAlive.hold(HoldKind.ride, location = false, reply)
        val service = arrive().get()
        rig.card.publish(rig.facts())
        rig.idle(java.time.Duration.ofSeconds(1))
        assertEquals(listOf(Result.success(Unit)), answers)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertTrue(keepAlive.holding(service))
        assertTrue(rig.failures.isEmpty())
    }

    @Test
    fun reconnectHoldsAnswerAtOnce() {
        val keepAlive = keepAlive()
        keepAlive.hold(HoldKind.reconnect, location = false, reply)
        rig.idle()
        assertEquals(listOf(Result.success(Unit)), answers)
        assertNull(shadowOf(rig.application).nextStartedService)
        keepAlive.release(HoldKind.reconnect)
        assertTrue(expiring.isEmpty())
    }

    @Test
    fun aSecondRideHoldWhileInFrontAnswersAtOnce() {
        val keepAlive = keepAlive()
        keepAlive.hold(HoldKind.ride, location = false, reply)
        arrive()
        keepAlive.hold(HoldKind.ride, location = false, reply)
        rig.idle()
        assertEquals(2, answers.count { it.isSuccess })
        assertNull(shadowOf(rig.application).nextStartedService)
    }

    @Test
    fun releaseAfterEndRemovesTheNotificationAndStopsTheService() {
        val keepAlive = keepAlive()
        rig.card.publish(rig.facts())
        keepAlive.hold(HoldKind.ride, location = false, reply)
        val service = arrive().get()
        rig.card.end()
        keepAlive.release(HoldKind.ride)
        rig.idle()
        val shadow = shadowOf(service)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertTrue(shadow.isStoppedBySelf)
        assertFalse(requireNotNull(wake()).isHeld)
    }

    @Test
    fun releaseBeforeEndDetachesTheNotificationWhichEndThenCancels() {
        val keepAlive = keepAlive()
        rig.card.publish(rig.facts())
        keepAlive.hold(HoldKind.ride, location = false, reply)
        val service = arrive().get()
        rig.idle()
        keepAlive.release(HoldKind.ride)
        rig.idle()
        val shadow = shadowOf(service)
        assertTrue(shadow.isForegroundStopped)
        assertFalse(shadow.notificationShouldRemoved)
        assertTrue(shadow.isStoppedBySelf)
        val detached = requireNotNull(rig.shown())
        assertTrue(detached.flags and Notification.FLAG_ONGOING_EVENT == 0)
        rig.card.end()
        rig.idle()
        assertNull(rig.shown())
    }

    @Test
    fun pauseAndResumeFromTheNotificationReachTheHostWithoutOpeningTheApp() {
        val intents = mutableListOf<RideCardIntent>()
        PowerLogAndroid.host = RecordingHost(intents)
        val keepAlive = keepAlive()
        rig.card.publish(rig.facts())
        keepAlive.hold(HoldKind.ride, location = false, reply)
        val controller = arrive()
        rig.idle()
        val pause = requireNotNull(rig.shown()).actions[0].actionIntent
        controller.withIntent(shadowOf(pause).savedIntent).startCommand(0, 2)
        assertEquals(
            listOf(RideCardIntent(RideCardRig.RIDE, RideCardRig.TOKEN, RideCardState.running, RideAction.pause)),
            intents,
        )
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertNull(shadowOf(rig.application).nextStartedActivity)
    }

    @Test
    fun aNotificationIntentWithoutAHoldStopsTheServiceAfterForwarding() {
        val intents = mutableListOf<RideCardIntent>()
        PowerLogAndroid.host = RecordingHost(intents)
        keepAlive()
        val control = app.powerlog.kit.RideCardProjection.project(rig.facts(), rig.nowUtcUs()).controls.first()
        val intent =
            app.powerlog.platform.ridecard.RideCardIntents.command(
                Intent(rig.application, RecordingService::class.java),
                control,
            )
        val service = Robolectric.buildService(RecordingService::class.java, intent).create().startCommand(0, 1).get()
        assertEquals(1, intents.size)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun aFinishIntentFromTheNotificationIsForwardedOnceByTheActivityEntry() {
        val intents = mutableListOf<RideCardIntent>()
        PowerLogAndroid.host = RecordingHost(intents)
        val notification = rig.card.notification(rig.facts(), rig.nowUtcUs(), ongoing = true)
        val finish = shadowOf(notification.actions[1].actionIntent).savedIntent
        PowerLogAndroid.activityIntent(finish)
        PowerLogAndroid.activityIntent(finish)
        assertEquals(listOf(RideAction.finish), intents.map { it.action })
        val pause = shadowOf(notification.actions[0].actionIntent).savedIntent
        PowerLogAndroid.activityIntent(pause)
        assertEquals(1, intents.size)
    }

    private class RecordingHost(private val intents: MutableList<RideCardIntent>) : HostFacade {
        override fun launch(context: LaunchContext, done: (RefusalDetail?) -> Unit) {}

        override fun foregroundChanged(foreground: Boolean) {}

        override fun didEnterBackground(done: () -> Unit) {}

        override fun willTerminate(timeoutUs: Long): Boolean = true

        override fun rideCardIntent(intent: RideCardIntent, done: (ApiFailure?) -> Unit) {
            intents += intent
            done(null)
        }

        override fun runHealthWork(rideId: String, attempt: Int, done: (HealthWorkResult) -> Unit) {}
    }
}
