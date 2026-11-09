package app.powerlog.platform.ridecard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.RideCard
import app.powerlog.application.port.RideCardEvents
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.RideCardFacts
import app.powerlog.kit.RideCardProjection
import app.powerlog.platform.R
import kotlin.math.ceil
import kotlin.math.pow

class AndroidRideCard(
    private val context: Context,
    private val service: Class<out Service>,
    private val ride: Handler,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val nowUtcUs: () -> Long = { System.currentTimeMillis() * 1_000 },
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) : RideCard {
    private enum class Shown {
        none,
        foreground,
        detached,
    }

    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var events: RideCardEvents? = null
    private var shown = Shown.none
    private var newest: RideCardFacts? = null
    private var posted: RideCardFacts? = null
    private var postedAtUtcUs = Long.MIN_VALUE
    private val rate = NotificationRate()
    private val refresh = Runnable { refreshDue() }
    private val expiry = Runnable { post() }

    override fun attach(rideCardEvents: RideCardEvents) {
        events = rideCardEvents
    }

    override fun publish(facts: RideCardFacts) {
        main.post {
            newest = facts
            if (shown != Shown.none) schedule()
        }
    }

    override fun end() {
        main.post {
            newest = null
            posted = null
            main.removeCallbacks(refresh)
            main.removeCallbacks(expiry)
            if (shown != Shown.foreground) {
                notifications.cancel(NOTIFICATION)
                shown = Shown.none
            }
        }
    }

    fun foregroundNotification(): Notification {
        channel()
        return newest?.let { recorded(it, ongoing = true) } ?: placeholder()
    }

    fun foregrounded() {
        shown = Shown.foreground
        if (newest != posted) schedule()
    }

    fun placeholder(): Notification {
        channel()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_power_log)
            .setContentTitle("Power Log")
            .build()
    }

    fun released(service: Service) {
        main.removeCallbacks(refresh)
        if (newest == null) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            shown = Shown.none
            return
        }
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_DETACH)
        shown = Shown.detached
        post()
    }

    internal fun notification(facts: RideCardFacts, nowUtcUs: Long, ongoing: Boolean): Notification {
        val render = RideCardProjection.project(facts, nowUtcUs).render(nowUtcUs, false)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val readings = listOf(render.bike, render.heartRate).joinToString(" · ") { "${it.text} ${it.unit}" }
        val running = render.timer.mode == TimerState.running
        val builder =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_power_log)
                .setContentTitle(render.status.text)
                .setContentText(listOfNotNull(readings, render.timer.text).joinToString(" · "))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setShowWhen(running)
                .setUsesChronometer(running)
        render.timer.originUtcUs?.let { builder.setWhen(it / 1_000) }
        open?.let { builder.setContentIntent(PendingIntent.getActivity(context, 0, it, FLAGS)) }
        render.controls.forEachIndexed { index, control ->
            val pending =
                if (control.requiresConfirmation) {
                    val target = open?.cloneFilter()?.setAction(Intent.ACTION_VIEW) ?: return@forEachIndexed
                    PendingIntent.getActivity(context, index + 1, RideCardIntents.command(target, control), FLAGS)
                } else
                    PendingIntent.getService(
                        context,
                        index + 1,
                        RideCardIntents.command(Intent(context, service), control),
                        FLAGS,
                    )
            builder.addAction(0, control.label, pending)
        }
        return builder.build()
    }

    private fun channel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ride recording", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun schedule() {
        val facts = newest ?: return
        main.removeCallbacks(refresh)
        val last = posted
        val priority =
            last == null ||
                facts.rideId != last.rideId ||
                facts.token != last.token ||
                facts.state != last.state ||
                facts.controls != last.controls
        main.postDelayed(refresh, rate.delay(elapsedMs(), if (priority) 0 else 2))
    }

    private fun refreshDue() {
        if (!expiredSincePost() && rate.delay(elapsedMs()) > 0) schedule() else post()
    }

    private fun expiredSincePost(): Boolean {
        val now = nowUtcUs()
        return listOfNotNull(
                newest?.bikeFreshUntilUtcUs,
                newest?.heartRateFreshUntilUtcUs,
                posted?.bikeFreshUntilUtcUs,
                posted?.heartRateFreshUntilUtcUs,
            )
            .any { postedAtUtcUs < it && it <= now }
    }

    private fun post() {
        main.removeCallbacks(refresh)
        val facts = newest ?: return
        if (shown == Shown.none) return
        try {
            notifications.notify(NOTIFICATION, recorded(facts, ongoing = shown == Shown.foreground))
        } catch (error: RuntimeException) {
            ride.post { events?.failed(PortError(PortErrorKind.failed)) }
        }
    }

    private fun recorded(facts: RideCardFacts, ongoing: Boolean): Notification {
        val now = nowUtcUs()
        val notification = notification(facts, now, ongoing)
        posted = facts
        postedAtUtcUs = now
        rate.record(elapsedMs())
        main.removeCallbacks(expiry)
        RideCardProjection.project(facts, now).staleDateUtcUs?.let {
            main.postDelayed(expiry, (it - now + 999) / 1_000)
        }
        return notification
    }

    companion object {
        const val NOTIFICATION = 4101
        private const val CHANNEL = "rides"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}

private class NotificationRate {
    private var lastEventMs: Long? = null
    private var interarrivalSeconds = 1000.0

    fun delay(nowMs: Long, reservedEvents: Int = 0): Long {
        val last = lastEventMs ?: return 0
        val requiredInterval = 1 / (LIMIT_PER_SECOND * ALPHA.pow(reservedEvents))
        val deltaSeconds = (requiredInterval - ALPHA * interarrivalSeconds) / (1 - ALPHA)
        return if (deltaSeconds <= MINIMUM_DELTA_SECONDS) 0
        else maxOf(0, last + ceil(deltaSeconds * 1000).toLong() - nowMs)
    }

    fun record(nowMs: Long) {
        lastEventMs?.let { last ->
            interarrivalSeconds =
                ALPHA * interarrivalSeconds + (1 - ALPHA) * maxOf((nowMs - last) / 1000.0, MINIMUM_DELTA_SECONDS)
        }
        lastEventMs = nowMs
    }

    companion object {
        // NotificationManagerService's enqueue limit (5 per second), smoothed the way the system averages it.
        private const val LIMIT_PER_SECOND = 5.0
        private const val ALPHA = 0.7
        private const val MINIMUM_DELTA_SECONDS = 0.0005
    }
}
