package app.powerlog.platform.host

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchReason
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.platform.ble.AndroidBleCentral
import app.powerlog.platform.device.AndroidDeviceInfo
import app.powerlog.platform.files.AndroidFilePicker
import app.powerlog.platform.health.AndroidHealthProvider
import app.powerlog.platform.keepalive.AndroidRecordingKeepAlive
import app.powerlog.platform.location.AndroidLocationSource
import app.powerlog.platform.permissions.AndroidPermissionGate
import app.powerlog.platform.ridecard.AndroidRideCard
import app.powerlog.platform.ridecard.RideCardIntents
import app.powerlog.platform.scheduler.AndroidBackgroundScheduler
import app.powerlog.platform.ui.AndroidUiContext
import app.powerlog.runtime.clock.AndroidClock
import app.powerlog.runtime.compose.AndroidRuntime
import app.powerlog.runtime.compose.AndroidThreads
import app.powerlog.runtime.compose.Ports
import app.powerlog.runtime.compose.PowerLogRuntime
import app.powerlog.runtime.host.HostFacade

object PowerLogAndroid {
    @Volatile
    var runtime: PowerLogRuntime? = null
        internal set

    lateinit var application: Application
        private set

    @Volatile internal var host: HostFacade? = null

    @Volatile internal var keepAlive: AndroidRecordingKeepAlive? = null

    val ui: AndroidUiContext = HostUiContext

    @Synchronized
    fun start(application: Application) {
        if (runtime != null) return
        this.application = application
        val threads =
            AndroidThreads(
                started("PowerLogRide", Process.THREAD_PRIORITY_DEFAULT),
                started("PowerLogHealth", Process.THREAD_PRIORITY_DEFAULT),
                started("PowerLogMaintenance", Process.THREAD_PRIORITY_BACKGROUND),
            )
        val clock = AndroidClock()
        val ride = Handler(threads.ride.looper)
        val rideCard = AndroidRideCard(application, RecordingService::class.java, ride)
        val keepAlive = AndroidRecordingKeepAlive(application, RecordingService::class.java, rideCard)
        this.keepAlive = keepAlive
        val ports =
            Ports(
                permissions = AndroidPermissionGate(application, ui, ride),
                picker = AndroidFilePicker(application, ui, ride),
                device = AndroidDeviceInfo(application),
                ble = AndroidBleCentral(application, ride, clock),
                location = AndroidLocationSource(application, threads.ride.looper, clock),
                health = AndroidHealthProvider(application, ui, clock),
                keepAlive = keepAlive,
                tabLock = null,
                persistence = null,
                rideCard = rideCard,
                watch = null,
                scheduler = AndroidBackgroundScheduler(application, HealthWorker::class.java, clock),
            )
        val hooks = DevHooks.create(application, clock, threads.ride)
        val created = AndroidRuntime.create(application, threads, clock, ports, hooks?.tools)
        runtime = created
        host = created.host
        created.host.launch(LaunchContext(LaunchReason.background)) { refusal -> hooks?.launched(created, refusal) }
    }

    fun foregroundChanged(foreground: Boolean) {
        host?.foregroundChanged(foreground)
    }

    fun didEnterBackground() {
        host?.didEnterBackground {}
    }

    fun activityIntent(intent: Intent?) {
        val card = intent?.let(RideCardIntents::read) ?: return
        if (card.action != RideAction.finish) return
        intent.removeExtra(RideCardIntents.ACTION)
        rideCardIntent(card)
    }

    internal fun rideCardIntent(intent: RideCardIntent) {
        host?.rideCardIntent(intent) {}
    }

    private fun started(name: String, priority: Int): HandlerThread = HandlerThread(name, priority).apply { start() }
}
