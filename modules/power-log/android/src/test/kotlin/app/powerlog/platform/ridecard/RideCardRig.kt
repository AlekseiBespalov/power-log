package app.powerlog.platform.ridecard

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RideCardEvents
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideTimer
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.RideCardFacts
import app.powerlog.domain.recording.RideCardState
import app.powerlog.platform.host.RecordingService
import java.time.Duration
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

class RideCardRig {
    val application: Application = RuntimeEnvironment.getApplication()
    val failures = mutableListOf<PortError>()
    private val startUtcUs = 1_700_000_000_000_000L
    private val startElapsedMs = SystemClock.elapsedRealtime()
    val card =
        AndroidRideCard(
            application,
            RecordingService::class.java,
            Handler(Looper.getMainLooper()),
            nowUtcUs = ::nowUtcUs,
        )
    private val notifications = application.getSystemService(NotificationManager::class.java)

    init {
        val component = ComponentName(application.packageName, "${application.packageName}.MainActivity")
        shadowOf(application.packageManager).addActivityIfNotPresent(component)
        shadowOf(application.packageManager)
            .addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        card.attach(
            object : RideCardEvents {
                override fun failed(error: PortError) {
                    failures += error
                }
            }
        )
    }

    fun nowUtcUs(): Long = startUtcUs + (SystemClock.elapsedRealtime() - startElapsedMs) * 1_000

    fun shown(): Notification? = shadowOf(notifications).getNotification(AndroidRideCard.NOTIFICATION)

    fun idle(duration: Duration = Duration.ZERO) {
        shadowOf(Looper.getMainLooper()).idleFor(duration)
    }

    fun facts(
        state: RideCardState = RideCardState.running,
        token: String = TOKEN,
        powerW: Double? = 200.0,
        heartRateBpm: Double? = 150.0,
        bikeFreshUs: Long? = 21_000_000,
        heartFreshUs: Long? = 30_000_000,
        controls: List<RideAction> = listOf(RideAction.pause, RideAction.finish),
        timer: RideTimer = RideTimer(TimerState.running, 10_000_000, nowUtcUs() - 10_000_000),
    ): RideCardFacts =
        RideCardFacts(
            RIDE,
            token,
            state,
            timer,
            powerW,
            heartRateBpm,
            bikeFreshUs?.let { nowUtcUs() + it },
            heartFreshUs?.let { nowUtcUs() + it },
            controls,
        )

    companion object {
        const val RIDE = "11111111-1111-4111-8111-111111111111"
        const val TOKEN = "66666666-6666-4666-8666-666666666666"
        const val OLD_TOKEN = "77777777-7777-4777-8777-777777777777"
    }
}
