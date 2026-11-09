package app.powerlog.runtime.compose

import android.content.Context
import android.os.HandlerThread
import app.powerlog.application.port.Clock
import app.powerlog.data.AndroidStores
import app.powerlog.runtime.diagnostics.AndroidDiagnostics
import app.powerlog.runtime.diagnostics.OsLogMirror

public object AndroidRuntime {
    public fun create(
        context: Context,
        threads: AndroidThreads,
        clock: Clock,
        ports: Ports,
        devTools: DevTools?,
    ): PowerLogRuntime {
        val executors = AndroidExecutors.application(threads)
        val storeExecutors = AndroidExecutors.stores()
        val files = AndroidStores.diagnosticLog(context, storeExecutors)
        val log = OsLogMirror(files, everyLine = devTools != null, AndroidDiagnostics::osLog)
        val stores = AndroidStores.create(context, storeExecutors, log, clock::read)
        val diagnostics = { AndroidStores.diagnosticDirectory(context) }
        val crashLines = AndroidDiagnostics.lines(diagnostics)
        val runtime = Composition.create(executors, clock, ports, Composition.stores(stores, log), devTools, crashLines)
        AndroidDiagnostics.install(diagnostics, clock)
        return runtime
    }
}

public class AndroidThreads(
    public val ride: HandlerThread,
    public val health: HandlerThread,
    public val maintenance: HandlerThread,
)
