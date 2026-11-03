package app.powerlog.bridge

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.powerlog.core.presentation.ActivityPresentation
import app.powerlog.core.presentation.ActivityPresentations
import kotlin.math.ceil
import kotlin.math.pow

class RecordingService : Service() {
    private lateinit var engine: RecordingEngine
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var latest: ActivityPresentation? = null
    private var published: ActivityPresentation? = null
    private var refreshTask: Runnable? = null
    private var deadlineTask: Runnable? = null
    private var lastPublication = Long.MIN_VALUE
    private var lastRenderUtcUs = Long.MIN_VALUE
    private val enqueueRate = NotificationEnqueueRate()
    private var foreground = false
    private var foregroundTypes = 0
    private var closed = false

    override fun onCreate() {
        super.onCreate()
        engine = RecordingEngine.get(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Ride recording", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        active = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.getStringExtra(FOREGROUND_REQUEST)
        synchronized(this) {
            offer(engine.notificationState)
            val shown = latest?.takeIf { !closed }?.let { promote(it, request) } == true
            if (request != null) {
                if (!shown)
                    runCatching {
                        ServiceCompat.startForeground(this, NOTIFICATION, placeholder(), foregroundType(false))
                    }
                engine.serviceStarted(request)
            }
            if (!shown || request != null && request != engine.foregroundRequestId) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        intent?.let(RideNotificationAdapter::request)?.let { request ->
            engine.handler.post { runCatching { engine.externalAction(request, false) } }
        }
        return START_NOT_STICKY
    }

    private fun foregroundType(location: Boolean) =
        if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        else 0

    private fun placeholder(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_power_log)
            .setContentTitle("Power Log")
            .build()

    private fun promote(presentation: ActivityPresentation, requestId: String? = null): Boolean {
        val type = foregroundType(engine.gpsActive)
        try {
            if (!foreground || type and foregroundTypes != type) {
                ServiceCompat.startForeground(this, NOTIFICATION, notification(presentation), type)
                foreground = true
                foregroundTypes = type
                recordPublication(presentation)
                scheduleDeadline()
            }
            engine.foregroundReady(requestId, presentation.rideId)
            return true
        } catch (error: Exception) {
            engine.foregroundReady(requestId, presentation.rideId, error)
            stopSelf()
            return false
        }
    }

    @Synchronized
    private fun offer(presentation: ActivityPresentation?) {
        if (closed || presentation != null && presentation.publicationSequence < (latest?.publicationSequence ?: 0))
            return
        latest = presentation
        refreshTask?.let(notificationHandler::removeCallbacks)
        refreshTask = null
        if (presentation == null) {
            deadlineTask?.let(notificationHandler::removeCallbacks)
            deadlineTask = null
            published = null
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (foreground) {
            notificationHandler.post {
                synchronized(this) { latest?.let { if (!closed) promote(it) } }
            }
            scheduleDeadline()
            scheduleRefresh()
        }
    }

    private fun sameControls(a: ActivityPresentation, b: ActivityPresentation?) =
        b != null &&
            a.rideId == b.rideId &&
            a.controlToken == b.controlToken &&
            a.controls == b.controls &&
            a.render(System.currentTimeMillis() * 1000, false).status == b.render(lastRenderUtcUs, false).status

    private fun readingDeadlines() =
        listOfNotNull(
            latest?.bikeExpiresAtUs,
            latest?.heartRateExpiresAtUs,
            published?.bikeExpiresAtUs,
            published?.heartRateExpiresAtUs,
        )

    private fun expiredSincePublication() =
        readingDeadlines().any { lastRenderUtcUs < it && it <= System.currentTimeMillis() * 1000 }

    private fun scheduleRefresh() {
        val presentation = latest ?: return
        val now = SystemClock.uptimeMillis()
        val priority = !sameControls(presentation, published)
        val deadline = expiredSincePublication()
        val due =
            if (deadline) now
            else {
                val rateDue = now + enqueueRate.delay(SystemClock.elapsedRealtime(), if (priority) 0 else 2)
                if (priority || lastPublication == Long.MIN_VALUE) rateDue
                else maxOf(rateDue, lastPublication + ROUTINE_INTERVAL_MS)
            }
        val callback =
            object : Runnable {
                override fun run() =
                    synchronized(this@RecordingService) {
                        if (refreshTask !== this) return@synchronized
                        refreshTask = null
                        val deadline = expiredSincePublication()
                        if (!deadline && enqueueRate.delay(SystemClock.elapsedRealtime()) > 0) scheduleRefresh()
                        else publishNotification()
                    }
            }
        refreshTask = callback
        notificationHandler.postDelayed(callback, due - now)
    }

    private fun recordPublication(presentation: ActivityPresentation) {
        published = presentation
        lastPublication = SystemClock.uptimeMillis()
        lastRenderUtcUs = System.currentTimeMillis() * 1000
        enqueueRate.record(SystemClock.elapsedRealtime())
    }

    private fun publishNotification() {
        val presentation = latest ?: return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(presentation))
        recordPublication(presentation)
        scheduleDeadline()
    }

    private fun scheduleDeadline() {
        deadlineTask?.let(notificationHandler::removeCallbacks)
        deadlineTask = null
        if (!foreground || latest == null) return
        val now = System.currentTimeMillis() * 1000
        val deadline = readingDeadlines().filter { it > now }.minOrNull() ?: return
        val callback =
            object : Runnable {
                override fun run() =
                    synchronized(this@RecordingService) {
                        if (deadlineTask !== this) return@synchronized
                        deadlineTask = null
                        refreshTask?.let(notificationHandler::removeCallbacks)
                        refreshTask = null
                        publishNotification()
                    }
            }
        deadlineTask = callback
        notificationHandler.postDelayed(callback, (deadline - now + 999) / 1000)
    }

    internal fun notification(
        presentation: ActivityPresentation,
        nowUtcUs: Long = System.currentTimeMillis() * 1000,
    ): Notification {
        val rendered = presentation.render(nowUtcUs, false)
        val open = requireNotNull(packageManager.getLaunchIntentForPackage(packageName))
        val content =
            PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val readings = listOf(rendered.bike, rendered.heartRate).joinToString(" · ") { "${it.text} ${it.unit}" }
        val text = listOfNotNull(readings, rendered.timer.text).joinToString(" · ")
        val builder =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_power_log)
                .setContentTitle(rendered.status.text)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setShowWhen(rendered.timer.mode == "running")
                .setUsesChronometer(rendered.timer.mode == "running")
        rendered.timer.originUs?.let { builder.setWhen(it / 1000) }
        rendered.controls.forEachIndexed { index, control ->
            val target =
                if (control.requiresConfirmation) open.cloneFilter().setAction(Intent.ACTION_VIEW)
                else Intent(this, RecordingService::class.java)
            val command = RideNotificationAdapter.command(target, control)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending =
                if (control.requiresConfirmation) PendingIntent.getActivity(this, index + 1, command, flags)
                else PendingIntent.getService(this, index + 1, command, flags)
            builder.addAction(0, control.label, pending)
        }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(this) {
            closed = true
            notificationHandler.removeCallbacksAndMessages(null)
            latest = null
            refreshTask = null
            deadlineTask = null
            active = null
        }
        super.onDestroy()
    }

    companion object {
        internal const val FOREGROUND_REQUEST = "foregroundCaptureRequest"
        private const val CHANNEL = "rides"
        private const val ROUTINE_INTERVAL_MS = (ActivityPresentations.refreshSeconds * 1000).toLong()
        private const val NOTIFICATION = 4101
        @Volatile private var active: RecordingService? = null

        fun refresh() {
            val service = active ?: return
            service.offer(service.engine.notificationState)
        }
    }
}

private class NotificationEnqueueRate {
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
        private const val LIMIT_PER_SECOND = 5.0
        private const val ALPHA = 0.7
        private const val MINIMUM_DELTA_SECONDS = 0.0005
    }
}
