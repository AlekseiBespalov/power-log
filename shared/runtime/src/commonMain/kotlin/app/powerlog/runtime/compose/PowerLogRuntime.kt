package app.powerlog.runtime.compose

import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.history.ExampleRideSource
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.Clock
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.runtime.bridge.BridgeSession
import app.powerlog.runtime.bridge.WireCodec
import app.powerlog.runtime.host.HostFacade

public class PowerLogRuntime
internal constructor(
    public val host: HostFacade,
    private val api: PowerLogApi,
    private val clock: Clock,
    private val log: DiagnosticLogRepository,
    private val ble: BleCentral?,
) {
    public fun openBridgeSession(): BridgeSession = BridgeSession(api, WireCodec(clock), clock, log)

    public fun prepareBluetooth() {
        ble?.prepare()
    }
}

public interface DevTools {
    public fun ports(real: Ports): Ports

    public val exampleRides: ExampleRideSource

    public fun runSlice(session: BridgeSession)
}
