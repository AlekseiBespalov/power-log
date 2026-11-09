package app.powerlog.runtime.dev

import app.powerlog.application.history.ExampleRideSource
import app.powerlog.application.port.Clock
import app.powerlog.application.port.DeviceInfo
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Platform
import app.powerlog.runtime.bridge.BridgeSession
import app.powerlog.runtime.compose.DevTools
import app.powerlog.runtime.compose.Ports
import app.powerlog.runtime.compose.RideBound
import kotlinx.coroutines.CoroutineDispatcher

internal class PhoneDevTools(private val switches: () -> DevSwitches, private val clock: Clock) : DevTools, RideBound {
    private var ride: CoroutineDispatcher? = null
    private var device: DeviceInfo? = null

    override fun bind(ride: CoroutineDispatcher) {
        this.ride = ride
    }

    private fun ride(): CoroutineDispatcher =
        checkNotNull(ride) { "the composition root binds the ride executor first" }

    override fun ports(real: Ports): Ports {
        device = real.device
        val ride = ride()
        val ble = real.ble
        val choice = DemoChoice { ble == null || switches().bike }
        return Ports(
            permissions = ChosenPermissionGate(choice, real.permissions) { GrantedPermissionGate(ride) },
            picker = real.picker,
            device = real.device,
            ble = if (ble == null) DemoBike(clock, ride) else ChosenBleCentral(choice, ble) { DemoBike(clock, ride) },
            location = real.location,
            health = real.health,
            keepAlive = real.keepAlive,
            tabLock = real.tabLock,
            persistence = real.persistence,
            rideCard = real.rideCard,
            watch = real.watch,
            scheduler = real.scheduler,
        )
    }

    override val exampleRides: ExampleRideSource =
        ExampleRideGenerator({ switches().longRide }) {
            when (checkNotNull(device) { "the composition root passes the ports first" }.snapshot().platform) {
                Platform.ios -> Device.iphone
                Platform.android -> Device.androidPhone
                Platform.web -> Device.browser
            }
        }

    override fun runSlice(session: BridgeSession) {
        SliceRun(session, clock, ride()).start()
    }
}
