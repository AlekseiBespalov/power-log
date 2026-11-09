package app.powerlog.platform.host

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import app.powerlog.application.port.Clock
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.runtime.compose.DevTools
import app.powerlog.runtime.compose.PowerLogRuntime
import app.powerlog.runtime.dev.DevSwitches
import app.powerlog.runtime.dev.DevToolsFactory
import java.io.File

internal class DevHooks private constructor(private val context: Context, clock: Clock) {
    private val switches by lazy { read() }

    val tools: DevTools = DevToolsFactory.create({ switches }, clock)

    fun launched(runtime: PowerLogRuntime, refusal: RefusalDetail?) {
        if (refusal == null && switches.slice) tools.runSlice(runtime.openBridgeSession())
    }

    private fun read(): DevSwitches {
        val file = File(context.filesDir, FILE)
        val text = if (file.isFile) file.readText() else "{}"
        val switches = DevSwitches.parse(text)
        if (switches.crashAfterUs != null || switches.killAfterUs != null)
            file.writeText(DevSwitches.withoutTimers(text))
        return switches
    }

    private fun arm() {
        val main = Handler(Looper.getMainLooper())
        switches.crashAfterUs?.let { delayUs ->
            main.postDelayed({ throw IllegalStateException("crashAfterUs") }, delayUs / 1_000L)
        }
        switches.killAfterUs?.let { delayUs ->
            main.postDelayed({ Process.killProcess(Process.myPid()) }, delayUs / 1_000L)
        }
    }

    companion object {
        const val FILE: String = "dev-switches.json"

        fun create(context: Context, clock: Clock, ride: HandlerThread): DevHooks =
            DevHooks(context, clock).also { hooks -> Handler(ride.looper).post { hooks.arm() } }
    }
}
