package app.powerlog.runtime.compose

import app.powerlog.application.Executors
import app.powerlog.application.port.Clock
import app.powerlog.data.WebStores

internal object WebRuntime {
    internal fun create(clock: Clock, ports: Ports, archiveName: String): PowerLogRuntime {
        val loop = WebLoop
        val stores = WebStores.create(archiveName, WebStores.diagnosticLog, clock::read)
        return Composition.create(
            executors = Executors(ride = loop, health = loop, maintenance = loop, reads = loop),
            clock = clock,
            ports = ports,
            stores = Composition.stores(stores, log = null),
            devTools = null,
        )
    }
}
