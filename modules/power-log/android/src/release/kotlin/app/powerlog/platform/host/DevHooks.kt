package app.powerlog.platform.host

import android.content.Context
import android.os.HandlerThread
import app.powerlog.application.port.Clock
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.runtime.compose.DevTools
import app.powerlog.runtime.compose.PowerLogRuntime

internal class DevHooks private constructor() {
    val tools: DevTools? = null

    fun launched(runtime: PowerLogRuntime, refusal: RefusalDetail?) {}

    companion object {
        fun create(context: Context, clock: Clock, ride: HandlerThread): DevHooks? = null
    }
}
