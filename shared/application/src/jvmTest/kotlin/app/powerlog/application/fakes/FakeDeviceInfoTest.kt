package app.powerlog.application.fakes

import app.powerlog.application.port.DeviceConditions
import app.powerlog.application.port.ExitReport
import app.powerlog.application.port.ThermalLevel
import app.powerlog.domain.core.ride.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeDeviceInfoTest {
    @Test
    fun unreportedFieldsStayNull() {
        val info = FakeDeviceInfo(snapshot = FakeDeviceInfo.snapshot(Platform.ios))

        assertEquals(Platform.ios, info.snapshot().platform)
        assertNull(info.snapshot().sdkLevel)
        assertEquals(DeviceConditions(null, null, null, null, null, null, null), info.conditions())
        assertNull(info.previousExit())
    }

    @Test
    fun answersTheScriptedConditionsAndExit() {
        val exit = ExitReport(atUtcUs = 1_000, reason = "crash", status = 0, importance = 100)
        val info = FakeDeviceInfo(previousExit = exit)
        info.conditions = DeviceConditions(true, ThermalLevel.serious, 5, 6, false, false, 10)

        assertEquals(Platform.android, info.snapshot().platform)
        assertEquals(ThermalLevel.serious, info.conditions().thermal)
        assertEquals(exit, info.previousExit())
    }
}
