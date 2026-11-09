package app.powerlog.platform.host

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import app.powerlog.platform.ui.AndroidUiContext
import expo.modules.interfaces.permissions.Permissions
import expo.modules.interfaces.permissions.PermissionsStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object HostUiContext : AndroidUiContext {
    private const val FIRST_REQUEST_CODE = 8643
    private const val REQUEST_CODE_COUNT = 0x100

    private class Attached(val owner: Any, val activity: () -> Activity?, val permissions: () -> Permissions?)

    @Volatile private var attached: Attached? = null
    private val pending = ConcurrentHashMap<Int, (Int?, Intent?) -> Unit>()
    private val codes = AtomicInteger()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun attach(owner: Any, activity: () -> Activity?, permissions: () -> Permissions?) {
        attached = Attached(owner, activity, permissions)
    }

    fun detach(owner: Any) {
        if (attached?.owner !== owner) return
        attached = null
        for (code in pending.keys.toList()) pending.remove(code)?.invoke(Activity.RESULT_CANCELED, null)
    }

    fun result(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val done = pending.remove(requestCode) ?: return false
        done(resultCode, data)
        return true
    }

    override fun activity(): Activity? = attached?.activity?.invoke()

    override fun requestPermissions(permissions: List<String>, done: (Map<String, Boolean>) -> Unit): Boolean {
        val manager = attached?.permissions?.invoke() ?: return false
        if (activity() == null) return false
        manager.askForPermissions(
            { result -> done(result.mapValues { (_, response) -> response.status == PermissionsStatus.GRANTED }) },
            *permissions.toTypedArray(),
        )
        return true
    }

    override fun startForResult(intent: Intent, done: (resultCode: Int?, data: Intent?) -> Unit) {
        main.post {
            val activity = activity() ?: return@post done(null, null)
            val code = FIRST_REQUEST_CODE + codes.getAndIncrement() % REQUEST_CODE_COUNT
            pending[code] = done
            try {
                activity.startActivityForResult(intent, code)
            } catch (_: RuntimeException) {
                pending.remove(code)
                done(null, null)
            }
        }
    }
}
