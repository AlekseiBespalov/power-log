package app.powerlog.platform.keepalive

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.KeepAliveEvents
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.RecordingKeepAlive
import app.powerlog.application.port.Reply
import app.powerlog.platform.ridecard.AndroidRideCard
import java.util.UUID

class AndroidRecordingKeepAlive(
    private val context: Context,
    private val service: Class<out Service>,
    private val card: AndroidRideCard,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val promote: (Service, Int, Notification, Int) -> Unit = { service, id, notification, types ->
        ServiceCompat.startForeground(service, id, notification, types)
    },
) : RecordingKeepAlive {
    private class Hold(val id: String, val location: Boolean, val reply: Reply<Unit>) {
        var released = false
    }

    private val lock = Any()
    private var events: KeepAliveEvents? = null
    private var wake: PowerManager.WakeLock? = null
    private val starting = mutableMapOf<String, Hold>()
    private var held: Hold? = null
    private var running: Service? = null
    private var lastStartId = 0

    override fun attach(keepAliveEvents: KeepAliveEvents) {
        events = keepAliveEvents
    }

    override fun hold(kind: HoldKind, location: Boolean, reply: Reply<Unit>) {
        if (kind == HoldKind.reconnect) {
            main.post { reply.ok(Unit) }
            return
        }
        synchronized(lock) {
            if (held?.released == false && running != null) {
                main.post { reply.ok(Unit) }
                return
            }
            held?.released = true
            val hold = Hold(UUID.randomUUID().toString(), location, reply)
            held = hold
            starting[hold.id] = hold
            try {
                acquireWake()
                ContextCompat.startForegroundService(context, Intent(context, service).putExtra(HOLD, hold.id))
            } catch (error: RuntimeException) {
                starting -= hold.id
                held = null
                releaseWake()
                main.post { reply.failed(refusal(error)) }
            }
        }
    }

    override fun release(kind: HoldKind) {
        if (kind == HoldKind.reconnect) return
        synchronized(lock) {
            val hold = held ?: return
            held = null
            hold.released = true
            releaseWake()
            val promoted = running ?: return
            running = null
            main.post {
                val stopId =
                    synchronized(lock) {
                        if (running === promoted) return@post
                        lastStartId
                    }
                card.released(promoted)
                promoted.stopSelf(stopId)
            }
        }
    }

    fun started(service: Service, intent: Intent?, startId: Int): Boolean {
        synchronized(lock) { lastStartId = startId }
        val id = intent?.getStringExtra(HOLD) ?: return false
        synchronized(lock) {
            val hold = starting.remove(id)
            if (hold == null || hold.released) {
                arrivedReleased(service, startId)
                hold?.reply?.ok(Unit)
                return true
            }
            try {
                promote(service, AndroidRideCard.NOTIFICATION, card.foregroundNotification(), types(hold.location))
            } catch (error: RuntimeException) {
                if (held === hold) held = null
                releaseWake()
                service.stopSelf(startId)
                hold.reply.failed(refusal(error))
                return true
            }
            running = service
            card.foregrounded()
            hold.reply.ok(Unit)
            return true
        }
    }

    fun holding(service: Service): Boolean = synchronized(lock) { running === service }

    fun destroyed(service: Service) {
        synchronized(lock) { if (running === service) running = null }
    }

    private fun arrivedReleased(service: Service, startId: Int) {
        val promoted = running === service
        if (promoted) return
        runCatching { promote(service, AndroidRideCard.NOTIFICATION, card.placeholder(), types(false)) }
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        service.stopSelf(startId)
    }

    private fun types(location: Boolean): Int =
        if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        else 0

    private fun refusal(error: RuntimeException): PortError =
        PortError(
            when {
                Build.VERSION.SDK_INT >= 31 && error is ForegroundServiceStartNotAllowedException ->
                    PortErrorKind.notAllowedInBackground
                error is SecurityException -> PortErrorKind.denied
                else -> PortErrorKind.failed
            }
        )

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val acquired =
            context
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerLog:recording")
        acquired.acquire(WAKE_CAP_MS)
        wake = acquired
    }

    private fun releaseWake() {
        wake?.takeIf { it.isHeld }?.release()
        wake = null
    }

    companion object {
        const val HOLD = "app.powerlog.keepalive.hold"
        private const val WAKE_CAP_MS = 24 * 60 * 60 * 1_000L
    }
}
