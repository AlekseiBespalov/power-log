package app.powerlog.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

internal data class RideNotificationState(
    val id: String?,
    val phase: String,
    val activeSince: Long,
    val activeSeconds: Double,
) {
    fun timerMillis(now: Long): Long =
        (activeSeconds * 1000).toLong() + if (phase == "running") now - activeSince else 0L
}

/** A foreground service, not the React view, owns recording lifetime and lock-screen controls. */
class RecordingService : Service() {
    private lateinit var engine: RecordingEngine

    override fun onCreate() {
        super.onCreate()
        active = this
        engine = RecordingEngine.get(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Ride recording", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = engine.notificationState
        if (state.id == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type =
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    if (engine.gpsActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            else 0
        ServiceCompat.startForeground(this, NOTIFICATION, notification(state), type)
        val action = intent?.getStringExtra("action")
        val id = intent?.getStringExtra("ride")
        if (action != null && id != null) engine.handler.post { runCatching { engine.action(action, id) } }
        return START_NOT_STICKY
    }

    private fun notification(state: RideNotificationState): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)!!
        val content =
            PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val id = state.id
        val paused = state.phase == "paused"
        fun command(action: String, code: Int) =
            PendingIntent.getService(
                this,
                code,
                Intent(this, RecordingService::class.java)
                    .putExtra("action", action)
                    .putExtra("ride", id)
                    .setData(android.net.Uri.parse("power-log://ride/$id/$action")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_power_log)
            .setContentTitle(if (paused) "Ride paused" else "Recording ride")
            .setContentText("Power Log")
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setWhen(System.currentTimeMillis() - state.timerMillis(SystemClock.elapsedRealtime()))
            .setUsesChronometer(!paused)
            .addAction(
                0,
                if (paused) "Resume" else "Pause",
                command(if (paused) "resume" else "pause", 1),
            )
            .addAction(0, "Finish ride", command("stop", 2))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "rides"
        private const val NOTIFICATION = 4101
        @Volatile private var active: RecordingService? = null

        fun refresh(context: Context) {
            val service = active ?: return
            val state = service.engine.notificationState
            if (state.id != null)
                context
                    .getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION, service.notification(state))
            else service.stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }
}
