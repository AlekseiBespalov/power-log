package app.powerlog.platform.host

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.powerlog.platform.ridecard.RideCardIntents

class RecordingService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keepAlive = PowerLogAndroid.keepAlive
        if (keepAlive?.started(this, intent, startId) == true) return START_NOT_STICKY
        intent?.let(RideCardIntents::read)?.let(PowerLogAndroid::rideCardIntent)
        if (keepAlive?.holding(this) != true) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        PowerLogAndroid.keepAlive?.destroyed(this)
        super.onDestroy()
    }
}
