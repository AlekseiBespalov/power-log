package app.powerlog.application.fakes

import app.powerlog.application.port.DeviceConditions
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.DeviceSnapshot
import app.powerlog.application.port.ExitReport
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.settings.LocaleFacts

class FakeDeviceInfo(
    var snapshot: DeviceSnapshot = snapshot(Platform.android),
    var conditions: DeviceConditions = DeviceConditions(null, null, null, null, null, null, null),
    var previousExit: ExitReport? = null,
) : DeviceInfo {
    override fun snapshot(): DeviceSnapshot = snapshot

    override fun conditions(): DeviceConditions = conditions

    override fun previousExit(): ExitReport? = previousExit

    companion object {
        fun snapshot(platform: Platform): DeviceSnapshot =
            DeviceSnapshot(
                platform = platform,
                manufacturer = "Power Log",
                model = "Fake",
                osVersion = "1",
                sdkLevel = if (platform == Platform.android) 36 else null,
                appVersion = "0.0.0",
                appBuild = "1",
                uses24h = true,
                locale = "en-GB",
                localeFacts = LocaleFacts(region = null, measurementSystem = null, temperature = null),
            )
    }
}
