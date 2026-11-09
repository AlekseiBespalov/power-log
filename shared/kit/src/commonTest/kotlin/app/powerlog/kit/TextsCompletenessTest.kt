package app.powerlog.kit

import app.powerlog.domain.connection.ConnectionDisplay
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.health.ComponentResult
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.RideSettings
import app.powerlog.domain.core.ride.RideTimer
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.diagnostics.TraceState
import app.powerlog.domain.diagnostics.TraceStopReason
import app.powerlog.domain.recording.PermissionAction
import app.powerlog.domain.recording.PermissionNeed
import app.powerlog.domain.recording.RideCardFacts
import app.powerlog.domain.recording.RideCardState
import app.powerlog.domain.recording.SourceKind
import app.powerlog.domain.recording.SourceState
import app.powerlog.domain.settings.AppSettings
import app.powerlog.domain.settings.ChartRange
import app.powerlog.domain.settings.PersistenceState
import app.powerlog.domain.settings.ViewId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextsCompletenessTest {
    private val options =
        RideOptions(
            SampleRate.hz2,
            indoor = false,
            useWatch = false,
            saveToHealth = true,
            recordGps = true,
            distanceSource = DistanceSource.phoneGps,
            batteryMethod = BatteryMethod.controller,
        )

    private fun assertText(text: String?, what: String) = assertTrue(!text.isNullOrBlank(), what)

    @Test
    fun everyFailureCodeHasAMessageOnEveryPlatformAndForEachRefusalDetail() {
        for (code in FailureCode.entries) for (platform in TextPlatform.entries) for (detail in
            RefusalDetail.entries + null) {
            val text = Texts.failure(code, detail, platform, if (platform == TextPlatform.android) 34 else null)
            assertText(text.text, "$code $platform $detail")
            assertEquals(listOf(text.message, text.nextStep).filter { it.isNotBlank() }.joinToString(" "), text.text)
        }
        val unreadable = Texts.failure(FailureCode.storageRefused, RefusalDetail.unreadable, TextPlatform.ios, null)
        val unavailable = Texts.failure(FailureCode.storageRefused, RefusalDetail.unavailable, TextPlatform.ios, null)
        assertNotEquals(unreadable, unavailable)
    }

    @Test
    fun bluetoothDeniedAsksForLocationBelowAndroid12AndNearbyDevicesFromIt() {
        fun step(sdk: Int?) = Texts.failure(FailureCode.bluetoothDenied, null, TextPlatform.android, sdk).nextStep
        assertEquals("Allow Location for Power Log in Settings.", step(30))
        assertEquals("Allow Nearby devices for Power Log in Settings.", step(31))
        assertEquals("Allow Nearby devices for Power Log in Settings.", step(null))
        assertEquals(
            "Allow Bluetooth for Power Log in Settings.",
            Texts.failure(FailureCode.bluetoothDenied, null, TextPlatform.ios, null).nextStep,
        )
    }

    @Test
    fun everyStateTheUiShowsHasAText() {
        RidePhase.entries.forEach { assertText(Texts.phaseChip(it), "$it") }
        RidePhase.entries.forEach { assertText(Texts.historyPhase(it, interrupted = true), "$it") }
        for (kind in SourceKind.entries) for (state in SourceState.entries) for (provider in
            HealthProviderKind.entries + null) assertText(
            Texts.source(kind, state, SourceContext(options, provider)).label,
            "$kind $state $provider",
        )
        for (outcome in HealthOutcome.entries) for (reason in HealthReason.entries + null) {
            val health = RideHealth(HealthProviderKind.appleHealth, outcome, reason, null)
            assertText(Texts.health(health), "$outcome $reason")
            if (reason == null || outcome == HealthOutcome.saved) assertNull(Texts.healthReason(health))
            else assertText(Texts.healthReason(health), "$outcome $reason reason")
            assertText(Texts.historyHealthLine(health, emptyList()), "$outcome $reason")
        }
        for (component in HealthComponent.entries) for (state in
            ComponentState.entries.filter {
                it != ComponentState.saved && it != ComponentState.notRequested
            }) for (reason in HealthReason.entries + null) {
            val lines =
                Texts.healthComponents(
                    listOf(ComponentResult(component, state, reason)),
                    HealthProviderKind.healthConnect,
                )
            assertEquals(1, lines.size, "$component $state $reason")
            assertText(lines.single(), "$component $state $reason")
        }
        HealthProviderKind.entries.forEach { assertText(Texts.provider(it), "$it") }
        ViewId.entries.forEach { assertText(Texts.viewName(it), "$it") }
        ChartRange.entries.forEach { assertText(Texts.range(it), "$it") }
        ChartState.entries.forEach {
            val status = Texts.chartStatus(it)
            assertText(status.label + status.detail, "$it")
        }
        MetricId.entries.forEach {
            val appearance = MetricAppearance.of(it)
            assertText(appearance.label, "$it")
            assertText(appearance.shortLabel, "$it")
            assertText(Units.display(it, UnitSystem.imperial, TemperatureUnit.fahrenheit).symbol.ifEmpty { "-" }, "$it")
        }
        for (state in ConnectionState.entries) for (display in ConnectionDisplay.entries) assertText(
            Texts.connection(state, display),
            "$state $display",
        )
        for (need in PermissionNeed.entries) for (action in PermissionAction.entries) for (platform in
            TextPlatform.entries) {
            val text = Texts.permission(need, action, HealthProviderKind.appleHealth, platform)
            assertText(text.label, "$need $action $platform")
            assertText(text.detail, "$need $action $platform")
        }
        for (state in TraceState.entries) for (reason in TraceStopReason.entries + null) assertText(
            Texts.traceStatus(state, 3, reason),
            "$state $reason",
        )
        PersistenceState.entries.forEach { assertText(Texts.storagePersistence(it), "$it") }
    }

    @Test
    fun everySettingButTheViewsHasATitle() {
        val fields =
            listOf("sampleRate", "distance", "battery", "indoor", "useWatch", "saveToHealth", "recordGps") +
                listOf("units", "temperature", "fitPower", "activeView", "historyView", "historyRange")
        val declared =
            listOf(RideSettings.serializer().descriptor, AppSettings.serializer().descriptor)
                .flatMap { descriptor -> List(descriptor.elementsCount, descriptor::getElementName) }
                .filter { it != "views" }
        assertEquals(declared.toSet(), fields.toSet())
        for (field in fields) {
            val title = Texts.setting(field)
            assertText(title, field)
            assertNotEquals(field, title)
        }
    }

    @Test
    fun aRideCardWithoutFreshUntilInstantsShowsNoBikeValuesOrHeartRate() {
        val facts =
            RideCardFacts(
                rideId = "11111111-1111-4111-8111-111111111111",
                token = "66666666-6666-4666-8666-666666666666",
                state = RideCardState.running,
                timer = RideTimer(TimerState.running, 0, 1_000_000),
                powerW = 200.0,
                heartRateBpm = 150.0,
                bikeFreshUntilUtcUs = null,
                heartRateFreshUntilUtcUs = null,
                controls = listOf(RideAction.pause, RideAction.finish),
            )
        val render = RideCardProjection.project(facts, 5_000_000).render(5_000_000, systemStale = false)
        assertEquals("—", render.bike.text)
        assertEquals("—", render.heartRate.text)
        assertEquals("Bike unavailable", render.status.text)
    }
}
