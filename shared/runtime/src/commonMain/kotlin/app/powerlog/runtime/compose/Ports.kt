package app.powerlog.runtime.compose

import app.powerlog.application.port.BackgroundScheduler
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.FilePicker
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.LocationSource
import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.RecordingKeepAlive
import app.powerlog.application.port.RideCard
import app.powerlog.application.port.StoragePersistence
import app.powerlog.application.port.TabLock
import app.powerlog.application.port.WatchLink

public class Ports(
    public val permissions: PermissionGate,
    public val picker: FilePicker,
    public val device: DeviceInfo,
    public val ble: BleCentral?,
    public val location: LocationSource?,
    public val health: HealthProvider?,
    public val keepAlive: RecordingKeepAlive?,
    public val tabLock: TabLock?,
    public val persistence: StoragePersistence?,
    public val rideCard: RideCard?,
    public val watch: WatchLink?,
    public val scheduler: BackgroundScheduler?,
)
