package app.powerlog.kit

import app.powerlog.domain.connection.ConnectionDisplay
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.ride.StartRequest
import app.powerlog.domain.diagnostics.TraceState
import app.powerlog.domain.diagnostics.TraceStopReason
import app.powerlog.domain.recording.PermissionAction
import app.powerlog.domain.recording.PermissionNeed
import app.powerlog.domain.recording.SourceKind
import app.powerlog.domain.recording.SourceState
import app.powerlog.domain.settings.ChartRange
import app.powerlog.domain.settings.PersistenceState
import app.powerlog.domain.settings.ViewId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextsTest {
    private val outdoor =
        RideOptions(
            SampleRate.hz2,
            indoor = false,
            useWatch = false,
            saveToHealth = true,
            recordGps = true,
            distanceSource = DistanceSource.phoneGps,
            batteryMethod = BatteryMethod.controller,
        )

    private fun source(
        kind: SourceKind,
        state: SourceState,
        options: RideOptions = outdoor,
        provider: HealthProviderKind? = HealthProviderKind.appleHealth,
    ) = Texts.source(kind, state, SourceContext(options, provider))

    @Test
    fun failuresNameTheirPlatformVariants() {
        assertEquals(
            "Web Bluetooth is unavailable. Use Chrome on Android, supported desktop Chrome / Edge, or the iPhone app.",
            Texts.failure(FailureCode.bluetoothUnavailable, null, TextPlatform.web, null).text,
        )
        assertEquals(
            "Power Log can't save to Health. Allow it in the Health app, then start again.",
            Texts.failure(FailureCode.healthDenied, null, TextPlatform.watch, null).text,
        )
        assertEquals(
            "Power Log couldn't read your saved settings. Open Settings on your iPhone and reset them, then start again.",
            Texts.failure(FailureCode.settingsUnreadable, null, TextPlatform.watch, null).text,
        )
        assertEquals(
            "Power Log can't open your saved rides. Clear this site's data to start over.",
            Texts.failure(FailureCode.storageRefused, RefusalDetail.unreadable, TextPlatform.web, null).text,
        )
        assertEquals(
            "Power Log can't open your saved rides. Reinstall the app to start over.",
            Texts.failure(FailureCode.storageRefused, RefusalDetail.unreadable, TextPlatform.android, 34).text,
        )
        assertEquals(
            "Power Log couldn't open your rides. Free up some storage space, then open Power Log again.",
            Texts.failure(FailureCode.storageRefused, RefusalDetail.unavailable, TextPlatform.web, null).text,
        )
        assertEquals(
            "Install the current Power Log build.",
            Texts.failure(FailureCode.apiMismatch, null, TextPlatform.ios, null).text,
        )
        assertEquals(
            "Another browser tab is recording this ride. Use that tab to control it.",
            Texts.failure(FailureCode.ownerForeign, null, TextPlatform.web, null).text,
        )
        assertEquals(
            "This ride is too large for this file. The ride-data ZIP is still available.",
            Texts.failure(FailureCode.exportLimit, null, TextPlatform.ios, null).text,
        )
    }

    @Test
    fun sourceLinesShareOneVocabulary() {
        assertEquals(
            SourceText("Receiving samples", SourceTone.ready),
            source(SourceKind.controller, SourceState.receiving),
        )
        assertEquals(
            SourceText("Bike unavailable", SourceTone.neutral),
            source(SourceKind.controller, SourceState.unavailable),
        )
        assertEquals(
            SourceText("Waiting for workout samples", SourceTone.neutral),
            source(SourceKind.controller, SourceState.waiting),
        )
        assertEquals(
            SourceText("No recent samples", SourceTone.warning),
            source(SourceKind.heartRate, SourceState.stale),
        )
        assertEquals(
            SourceText("Not available on this device", SourceTone.neutral),
            source(SourceKind.heartRate, SourceState.unavailable),
        )
        val healthOff = outdoor.copy(saveToHealth = false)
        assertEquals(
            SourceText("Not selected", SourceTone.neutral),
            source(SourceKind.heartRate, SourceState.waiting, healthOff),
        )
        val connect = HealthProviderKind.healthConnect
        assertEquals(
            SourceText("Heart rate comes later", SourceTone.neutral),
            source(SourceKind.heartRate, SourceState.waiting, provider = connect),
        )
        assertEquals(
            SourceText("Heart-rate access denied", SourceTone.warning),
            source(SourceKind.heartRate, SourceState.denied, provider = connect),
        )
        assertEquals(
            SourceText("Heart rate from Health Connect", SourceTone.neutral),
            source(SourceKind.heartRate, SourceState.backfilled, provider = connect),
        )
        assertEquals(SourceText("Weak GPS signal", SourceTone.warning), source(SourceKind.gps, SourceState.weak))
        assertEquals(
            SourceText("Location permission denied", SourceTone.warning),
            source(SourceKind.gps, SourceState.denied),
        )
        assertEquals(SourceText("Waiting for GPS", SourceTone.neutral), source(SourceKind.gps, SourceState.waiting))
        val indoor = outdoor.copy(indoor = true, recordGps = false)
        assertEquals(
            SourceText("Off for indoor ride", SourceTone.neutral),
            source(SourceKind.gps, SourceState.off, indoor),
        )
        assertEquals(
            SourceText("Off", SourceTone.neutral),
            source(SourceKind.gps, SourceState.off, outdoor.copy(recordGps = false)),
        )
    }

    @Test
    fun permissionRowsFollowTheirNeedAndAction() {
        val ios = TextPlatform.ios
        val apple = HealthProviderKind.appleHealth
        assertEquals(
            PermissionText("Set up permissions", "Review the health permissions requested by Power Log."),
            Texts.permission(PermissionNeed.health, PermissionAction.request, apple, ios),
        )
        assertEquals(
            PermissionText("Allow location", "Your phone needs location access to record GPS measurements."),
            Texts.permission(PermissionNeed.location, PermissionAction.request, apple, ios),
        )
        assertEquals(
            PermissionText(
                "Review Health access",
                "Some write access is denied. Review Power Log’s permissions in Health Connect.",
            ),
            Texts.permission(
                PermissionNeed.health,
                PermissionAction.healthHelp,
                HealthProviderKind.healthConnect,
                TextPlatform.android,
            ),
        )
        assertEquals(
            PermissionText("Location settings", "Turn on Location Services to record the phone route."),
            Texts.permission(PermissionNeed.locationServices, PermissionAction.openAppSettings, apple, ios),
        )
        assertEquals(
            PermissionText("Location settings", "Location access is restricted by device settings."),
            Texts.permission(PermissionNeed.locationRestricted, PermissionAction.openAppSettings, apple, ios),
        )
        assertEquals(
            PermissionText("Precise location", "Enable Precise Location for a useful cycling route."),
            Texts.permission(PermissionNeed.preciseLocation, PermissionAction.openAppSettings, apple, ios),
        )
        assertEquals(
            PermissionText(
                "Check permissions",
                "Allow location in this site's browser settings, then check permissions.",
            ),
            Texts.permission(PermissionNeed.location, PermissionAction.check, null, TextPlatform.web),
        )
        assertEquals(
            PermissionText("Check permissions", "Current permission readiness could not be determined."),
            Texts.permission(PermissionNeed.health, PermissionAction.check, apple, ios),
        )
        assertEquals(
            PermissionText("Health unavailable", "Health saving is unavailable on this device."),
            Texts.permission(PermissionNeed.health, PermissionAction.unavailable, null, ios),
        )
    }

    @Test
    fun phasesChipsAndHistoryPhases() {
        assertEquals("Starting", Texts.phaseChip(RidePhase.preparing))
        assertEquals("Needs attention", Texts.phaseChip(RidePhase.interrupted))
        assertEquals("", Texts.historyPhase(RidePhase.completed, interrupted = false))
        assertEquals("Interrupted", Texts.historyPhase(RidePhase.completed, interrupted = true))
        assertEquals("Needs attention · Interrupted", Texts.historyPhase(RidePhase.interrupted, interrupted = true))
        assertEquals("Failed", Texts.historyPhase(RidePhase.failed, interrupted = false))
        assertEquals("Recording", Texts.historyPhase(RidePhase.running, interrupted = false))
    }

    @Test
    fun connectionLabelsAndTheFaultLine() {
        assertEquals("Connected", Texts.connection(ConnectionState.reconnecting, ConnectionDisplay.held))
        assertEquals("Reconnecting…", Texts.connection(ConnectionState.reconnecting, ConnectionDisplay.unavailable))
        assertEquals("Waiting for data", Texts.connection(ConnectionState.connected, ConnectionDisplay.unavailable))
        assertEquals("Connecting…", Texts.connection(ConnectionState.connecting, ConnectionDisplay.unavailable))
        assertEquals("Searching…", Texts.connection(ConnectionState.searching, ConnectionDisplay.unavailable))
        assertEquals("Not connected", Texts.connection(ConnectionState.idle, ConnectionDisplay.unavailable))
        assertEquals("Your controller reports fault 12. Check the bike's display.", Texts.controllerFault(12))
        assertEquals(
            "Controller speed needs CYC X6 or X12 firmware with protocol 5.3.",
            Texts.controllerSpeedNote(false),
        )
        assertNull(Texts.controllerSpeedNote(true))
        assertNull(Texts.controllerSpeedNote(null))
    }

    @Test
    fun settingsTitlesChoicesAndTheHealthNote() {
        assertEquals("Units", Texts.setting("units"))
        assertEquals("Metric", Texts.choice("units", "metric", TextPlatform.ios))
        assertEquals("Imperial", Texts.choice("units", "imperial", TextPlatform.ios))
        assertEquals("°C", Texts.choice("temperature", "celsius", TextPlatform.ios))
        assertEquals("°F", Texts.choice("temperature", "fahrenheit", TextPlatform.ios))
        assertEquals("8 Hz", Texts.choice("sampleRate", "8", TextPlatform.android))
        assertEquals("GPS · Phone", Texts.choice("distance", "gps:phone", TextPlatform.ios))
        assertEquals("GPS · Browser", Texts.choice("distance", "gps:phone", TextPlatform.web))
        assertEquals("Indoor", Texts.choice("indoor", "true", TextPlatform.ios))
        assertEquals("Battery input power", Texts.choice("fitPower", "battery", TextPlatform.ios))
        assertEquals("2 min", Texts.choice("historyRange", "seconds120", TextPlatform.ios))
        assertEquals("Temperature", Texts.choice("activeView", "temperature", TextPlatform.ios))
        assertEquals("odd", Texts.choice("units", "odd", TextPlatform.ios))
        assertEquals("Controller estimate", Texts.distanceSource(DistanceSelection.controller, foregroundOnly = true))
        val request = StartRequest(indoor = false, useWatch = false, saveToHealth = true, recordGps = null)
        assertEquals(
            "Phone-recorded rides cannot be saved to Health on this device. This ride will stay in Power Log.",
            Texts.healthSetup(outdoor.copy(saveToHealth = false), request, null),
        )
        assertEquals(
            "Watch-recorded rides cannot be saved to Apple Health on this device. This ride will stay in Power Log.",
            Texts.healthSetup(
                outdoor.copy(useWatch = true, saveToHealth = false),
                request,
                HealthProviderKind.appleHealth,
            ),
        )
        assertNull(Texts.healthSetup(outdoor, request, HealthProviderKind.appleHealth))
        assertNull(Texts.healthSetup(null, request, HealthProviderKind.appleHealth))
        assertNull(Texts.healthSetup(outdoor.copy(saveToHealth = false), request.copy(saveToHealth = false), null))
        assertEquals(
            "The browser may clear saved rides when storage runs low",
            Texts.storagePersistence(PersistenceState.notPersisted),
        )
    }

    @Test
    fun historyChartsAndTraceTexts() {
        assertEquals("Browser", Texts.recordedOn(Device.browser, useWatch = true))
        assertEquals("Watch + iPhone", Texts.recordedOn(Device.iphone, useWatch = true))
        assertEquals("Android", Texts.recordedOn(Device.androidPhone, useWatch = false))
        assertEquals("iPhone", Texts.recordedOn(Device.iphone, useWatch = false))
        assertEquals("0 rides", Texts.rideCount(0))
        assertEquals("1 ride", Texts.rideCount(1))
        assertEquals("1 lap", Texts.lapCount(1))
        assertEquals("3 laps", Texts.lapCount(3))
        assertEquals("Temperature", Texts.viewName(ViewId.temperature))
        assertEquals("Whole ride", Texts.range(ChartRange.all))
        assertEquals(StatusText("Syncing ride…", "Syncing remaining ride data."), Texts.chartStatus(ChartState.syncing))
        assertEquals("No capture", Texts.traceStatus(TraceState.idle, 0, null))
        assertEquals("Capturing · 12 events", Texts.traceStatus(TraceState.capturing, 12, null))
        assertEquals(
            "Capture stopped · 4000 events · Size limit reached",
            Texts.traceStatus(TraceState.stopped, 4_000, TraceStopReason.sizeLimit),
        )
        assertEquals(
            "Capture stopped · 9 events · Time limit reached",
            Texts.traceStatus(TraceState.stopped, 9, TraceStopReason.timeLimit),
        )
    }
}
