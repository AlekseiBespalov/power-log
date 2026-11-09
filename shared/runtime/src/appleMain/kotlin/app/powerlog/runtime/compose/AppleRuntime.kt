package app.powerlog.runtime.compose

import app.powerlog.application.port.Clock
import app.powerlog.data.AppleStores
import app.powerlog.runtime.diagnostics.AppleDiagnostics
import app.powerlog.runtime.diagnostics.OsLogMirror
import platform.darwin.NSObject

public object AppleRuntime {
    public fun create(
        queues: AppleQueues,
        clock: Clock,
        ports: Ports,
        directories: AppleDirectories,
        devTools: DevTools?,
    ): PowerLogRuntime {
        val executors = AppleExecutors.application(queues)
        val storeExecutors = AppleExecutors.stores()
        val files = AppleStores.diagnosticLog(directories.diagnostics, storeExecutors)
        val log = OsLogMirror(files, everyLine = devTools != null) { _, line -> AppleDiagnostics.osLog(line) }
        val stores = AppleStores.create(directories.archive, directories.cache, storeExecutors, log, clock::read)
        val crashLines = AppleDiagnostics.lines(directories.diagnostics)
        val runtime = Composition.create(executors, clock, ports, Composition.stores(stores, log), devTools, crashLines)
        AppleDiagnostics.install(directories.diagnostics, clock)
        return runtime
    }
}

public class AppleQueues(public val ride: NSObject, public val health: NSObject, public val maintenance: NSObject)

public class AppleDirectories(public val archive: String, public val cache: String, public val diagnostics: String)
