package app.powerlog.runtime.compose

import app.powerlog.application.Executors
import app.powerlog.application.port.Clock
import app.powerlog.data.JvmStores
import app.powerlog.data.StoreExecutors
import java.io.File

public object JvmRuntime {
    public fun create(directory: File, executors: Executors, clock: Clock, ports: Ports): PowerLogRuntime {
        val stores =
            StoreExecutors(
                archiveWriter = executors.reads.limitedParallelism(1),
                archiveReads = executors.reads,
                cacheWriter = executors.reads.limitedParallelism(1),
                cacheReads = executors.scans,
                log = executors.reads.limitedParallelism(1),
            )
        val log = JvmStores.diagnosticLog(directory, stores)
        val opened = JvmStores.create(directory, stores, log, clock::read)
        return Composition.create(executors, clock, ports, Composition.stores(opened, log), devTools = null)
    }
}
