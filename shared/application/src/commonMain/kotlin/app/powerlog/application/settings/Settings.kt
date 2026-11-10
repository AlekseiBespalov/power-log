package app.powerlog.application.settings

import app.powerlog.application.CommitPolicy
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.Published
import app.powerlog.application.api.failure
import app.powerlog.application.api.occurrence
import app.powerlog.application.port.Clock
import app.powerlog.application.store.SettingsLoad
import app.powerlog.application.store.SettingsRepository
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.DeviceCapabilities
import app.powerlog.domain.core.ride.RideOptionsRules
import app.powerlog.domain.core.ride.RideSettings
import app.powerlog.domain.core.ride.StartRequest
import app.powerlog.domain.settings.LocaleFacts
import app.powerlog.domain.settings.SettingsDocument
import app.powerlog.domain.settings.SettingsDraft
import app.powerlog.domain.settings.SettingsRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class Settings(
    private val repository: SettingsRepository,
    private val locale: () -> LocaleFacts,
    private val capabilities: () -> DeviceCapabilities,
    clock: Clock,
) {
    sealed interface Load {
        data object Loaded : Load

        data object Refused : Load
    }

    private val saves = Mutex()
    private val listeners = mutableListOf<(RideSettings) -> Unit>()
    private var document: SettingsDocument = SettingsRules.defaults(LocaleFacts(null, null, null))
    private var documentState = SettingsDocumentState.ok
    private var saveFailure: ApiFailure? = null

    val stream: Published<SettingsView> = Published(view(), 0, clock)

    val ride: RideSettings
        get() = document.ride

    val readable: Boolean
        get() = documentState != SettingsDocumentState.unreadable

    val saveFailed: Boolean
        get() = documentState == SettingsDocumentState.saveFailed

    fun onRideSettings(listener: (RideSettings) -> Unit) {
        listeners += listener
    }

    suspend fun load(): Load {
        while (true) {
            when (val read = repository.document()) {
                is StoreResult.Ok ->
                    when (val load = read.value) {
                        is SettingsLoad.Loaded -> show(SettingsRules.sanitize(load.draft, locale()))
                        SettingsLoad.Missing -> {
                            show(SettingsRules.defaults(locale()))
                            persist(document)
                        }
                        SettingsLoad.Unreadable -> unreadable()
                    }
                is StoreResult.Failed ->
                    when (read.error.kind) {
                        StorageError.Kind.busy -> {
                            delay(CommitPolicy.busyRetryUs / 1_000)
                            continue
                        }
                        StorageError.Kind.corrupt -> return Load.Refused
                        else -> unreadable()
                    }
            }
            return Load.Loaded
        }
    }

    suspend fun change(change: SettingsChange): Outcome<Unit> {
        val next = if (change == SettingsChange.Reset) SettingsRules.defaults(locale()) else applied(change)
        if (change == SettingsChange.Reset) documentState = SettingsDocumentState.ok
        show(SettingsRules.sanitize(SettingsDraft.of(next), locale()))
        if (documentState == SettingsDocumentState.unreadable) return Outcome.Ok(Unit)
        return if (persist(document)) Outcome.Ok(Unit) else Outcome.Failed(failure(FailureCode.settingsSaveFailed))
    }

    suspend fun settled(): RideSettings = saves.withLock { document.ride }

    fun refresh() {
        stream.publish(view(), urgent = true)
    }

    private suspend fun persist(saved: SettingsDocument): Boolean = saves.withLock { save(saved) }

    private suspend fun save(saved: SettingsDocument): Boolean {
        while (true) {
            val result = repository.save(saved)
            if (result is StoreResult.Ok) {
                if (documentState == SettingsDocumentState.saveFailed) documentState = SettingsDocumentState.ok
                saveFailure = null
                refresh()
                return true
            }
            if ((result as StoreResult.Failed).error.kind != StorageError.Kind.busy) {
                documentState = SettingsDocumentState.saveFailed
                saveFailure = occurrence(FailureCode.settingsSaveFailed, saveFailure)
                refresh()
                return false
            }
            delay(CommitPolicy.busyRetryUs / 1_000)
        }
    }

    private fun applied(change: SettingsChange): SettingsDocument {
        val ride = document.ride
        val app = document.app
        return when (change) {
            is SettingsChange.SampleRateTo -> document.copy(ride = ride.copy(sampleRate = change.value))
            is SettingsChange.DistanceTo -> document.copy(ride = ride.copy(distance = change.value))
            is SettingsChange.BatteryTo -> document.copy(ride = ride.copy(battery = change.value))
            is SettingsChange.Toggle ->
                document.copy(
                    ride =
                        when (change.field) {
                            RideToggle.indoor ->
                                ride.copy(indoor = change.on, recordGps = ride.recordGps ?: !ride.indoor)
                            RideToggle.useWatch -> ride.copy(useWatch = change.on)
                            RideToggle.saveToHealth -> ride.copy(saveToHealth = change.on)
                            RideToggle.syncBeeps -> ride.copy(syncBeeps = change.on)
                        }
                )
            is SettingsChange.RecordGpsTo -> document.copy(ride = ride.copy(recordGps = change.value))
            is SettingsChange.UnitsTo -> document.copy(app = app.copy(units = change.value))
            is SettingsChange.TemperatureTo -> document.copy(app = app.copy(temperature = change.value))
            is SettingsChange.FitPowerTo -> document.copy(app = app.copy(fitPower = change.value))
            is SettingsChange.ActiveViewTo -> document.copy(app = app.copy(activeView = change.value))
            is SettingsChange.HistoryViewTo -> document.copy(app = app.copy(historyView = change.value))
            is SettingsChange.HistoryRangeTo -> document.copy(app = app.copy(historyRange = change.value))
            is SettingsChange.Preset -> {
                val preset = app.views.getValue(change.view)
                val changed =
                    preset.copy(
                        numbers = change.numbers ?: preset.numbers,
                        charts = change.charts ?: preset.charts,
                        range = change.range ?: preset.range,
                        webColumns = change.webColumns ?: preset.webColumns,
                    )
                document.copy(app = app.copy(views = app.views + (change.view to changed)))
            }
            is SettingsChange.ViewReset -> {
                val defaults = SettingsRules.defaults(locale()).app.views.getValue(change.view)
                document.copy(app = app.copy(views = app.views + (change.view to defaults)))
            }
            SettingsChange.Reset -> SettingsRules.defaults(locale())
        }
    }

    private fun unreadable() {
        documentState = SettingsDocumentState.unreadable
        show(SettingsRules.defaults(locale()))
    }

    private fun show(next: SettingsDocument) {
        val rideChanged = next.ride != document.ride
        document = next
        refresh()
        if (rideChanged) listeners.forEach { it(next.ride) }
    }

    private fun view(): SettingsView {
        val ride = document.ride
        val request = StartRequest(ride.indoor, ride.useWatch, ride.saveToHealth, ride.recordGps)
        return SettingsView(
            ride = ride,
            app = document.app,
            effective = RideOptionsRules.resolve(ride, request, capabilities()),
            state = documentState,
            failure = saveFailure.takeIf { documentState == SettingsDocumentState.saveFailed },
        )
    }
}
