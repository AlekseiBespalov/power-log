package app.powerlog.application.settings

import app.powerlog.application.api.Outcome
import app.powerlog.application.port.Clock
import app.powerlog.application.store.SettingsLoad
import app.powerlog.application.store.SettingsRepository
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ride.DeviceCapabilities
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.settings.LocaleFacts
import app.powerlog.domain.settings.SettingsDocument
import app.powerlog.domain.settings.SettingsDraft
import app.powerlog.domain.settings.SettingsRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class SettingsTest {
    private val locale = LocaleFacts("GB", null, null)
    private val defaults = SettingsRules.defaults(locale)
    private val capabilities = DeviceCapabilities(true, true, true, HealthProviderKind.appleHealth, false, true)

    private class Repository(var loads: MutableList<StoreResult<SettingsLoad>>) : SettingsRepository {
        val saved = mutableListOf<SettingsDocument>()
        val saves = ArrayDeque<StoreResult<Unit>>()

        override suspend fun document(): StoreResult<SettingsLoad> = loads.removeFirst()

        override suspend fun save(settings: SettingsDocument): StoreResult<Unit> {
            saved += settings
            return saves.removeFirstOrNull() ?: StoreResult.Ok(Unit)
        }
    }

    private fun TestScope.settings(repository: Repository): Settings {
        val clock =
            object : Clock {
                override fun monoUs(): Long = testScheduler.currentTime * 1_000

                override fun utcUs(): Long = monoUs()

                override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
            }
        return Settings(repository, { locale }, { capabilities }, clock)
    }

    private fun failed(kind: StorageError.Kind) = StoreResult.Failed(StorageError(kind))

    private class Shared : SettingsRepository {
        var stored: SettingsDocument? = null

        override suspend fun document(): StoreResult<SettingsLoad> =
            StoreResult.Ok(stored?.let { SettingsLoad.Loaded(SettingsDraft.of(it)) } ?: SettingsLoad.Missing)

        override suspend fun save(settings: SettingsDocument): StoreResult<Unit> {
            stored = settings
            return StoreResult.Ok(Unit)
        }
    }

    @Test
    fun eachLoadResultSanitizesSavesDefaultsReadsUnreadableOrRefuses() = runTest {
        val stored = defaults.copy(ride = defaults.ride.copy(sampleRate = SampleRate.hz4))
        val loaded = Repository(mutableListOf(StoreResult.Ok(SettingsLoad.Loaded(SettingsDraft.of(stored)))))
        val kept = settings(loaded)
        assertEquals(Settings.Load.Loaded, kept.load())
        assertEquals(SampleRate.hz4, kept.ride.sampleRate)
        assertEquals(emptyList(), loaded.saved)
        val missing = Repository(mutableListOf(StoreResult.Ok(SettingsLoad.Missing)))
        val fresh = settings(missing)
        assertEquals(Settings.Load.Loaded, fresh.load())
        assertEquals(listOf(defaults), missing.saved)
        val unreadable = settings(Repository(mutableListOf(StoreResult.Ok(SettingsLoad.Unreadable))))
        unreadable.load()
        assertEquals(SettingsDocumentState.unreadable, unreadable.stream.value.state)
        assertEquals(
            Settings.Load.Refused,
            settings(Repository(mutableListOf(failed(StorageError.Kind.corrupt)))).load(),
        )
        val io = settings(Repository(mutableListOf(failed(StorageError.Kind.io))))
        io.load()
        assertEquals(SettingsDocumentState.unreadable, io.stream.value.state)
    }

    @Test
    fun aBusyReadAtLaunchIsReadAgainNeverUnreadable() = runTest {
        val repository = Repository(mutableListOf(failed(StorageError.Kind.busy), StoreResult.Ok(SettingsLoad.Missing)))
        val settings = settings(repository)
        assertEquals(Settings.Load.Loaded, settings.load())
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(SettingsDocumentState.ok, settings.stream.value.state)
    }

    @Test
    fun aBusySaveIsResentAndAFailedOneShowsSaveFailedUntilALaterSave() = runTest {
        val repository = Repository(mutableListOf(StoreResult.Ok(SettingsLoad.Missing)))
        val settings = settings(repository)
        settings.load()
        repository.saves += failed(StorageError.Kind.busy)
        assertEquals(Outcome.Ok(Unit), settings.change(SettingsChange.SampleRateTo(SampleRate.hz8)))
        assertEquals(3, repository.saved.size)
        repository.saves += failed(StorageError.Kind.full)
        val failed = assertIs<Outcome.Failed>(settings.change(SettingsChange.SampleRateTo(SampleRate.hz2)))
        assertEquals(FailureCode.settingsSaveFailed, failed.failure.code)
        assertEquals(SettingsDocumentState.saveFailed, settings.stream.value.state)
        assertEquals(FailureCode.settingsSaveFailed, settings.stream.value.failure?.code)
        assertEquals(true, settings.saveFailed)
        assertEquals(Outcome.Ok(Unit), settings.change(SettingsChange.SampleRateTo(SampleRate.hz4)))
        assertEquals(SettingsDocumentState.ok, settings.stream.value.state)
        assertNull(settings.stream.value.failure)
    }

    @Test
    fun anUnreadableDocumentShowsChangesUnsavedUntilReset() = runTest {
        val repository = Repository(mutableListOf(StoreResult.Ok(SettingsLoad.Unreadable)))
        val settings = settings(repository)
        settings.load()
        assertEquals(Outcome.Ok(Unit), settings.change(SettingsChange.SampleRateTo(SampleRate.hz8)))
        assertEquals(SampleRate.hz8, settings.stream.value.ride.sampleRate)
        assertEquals(emptyList(), repository.saved)
        assertEquals(false, settings.readable)
        assertEquals(Outcome.Ok(Unit), settings.change(SettingsChange.Reset))
        assertEquals(listOf(defaults), repository.saved)
        assertEquals(SettingsDocumentState.ok, settings.stream.value.state)
    }

    @Test
    fun goingIndoorFromAnOutdoorRideKeepsItsGps() = runTest {
        val repository = Repository(mutableListOf(StoreResult.Ok(SettingsLoad.Missing)))
        val settings = settings(repository)
        settings.load()
        settings.change(SettingsChange.Toggle(RideToggle.indoor, true))
        assertEquals(true, settings.ride.indoor)
        assertEquals(true, settings.ride.recordGps)
        assertEquals(true, settings.stream.value.effective?.recordGps)
    }

    @Test
    fun rideSettingChangesReachTheirListeners() = runTest {
        val repository = Repository(mutableListOf(StoreResult.Ok(SettingsLoad.Missing)))
        val settings = settings(repository)
        val heard = mutableListOf<SampleRate>()
        settings.onRideSettings { heard += it.sampleRate }
        settings.load()
        settings.change(SettingsChange.SampleRateTo(SampleRate.hz8))
        settings.change(SettingsChange.UnitsTo(app.powerlog.domain.core.metric.UnitSystem.imperial))
        assertEquals(listOf(SampleRate.hz8), heard)
    }

    @Test
    fun twoTabsSavingKeepTheLaterWholeDocumentAndEachKeepsItsOwnUntilItReloads() = runTest {
        val shared = Shared()
        val first = Settings(shared, { locale }, { capabilities }, testClock())
        val second = Settings(shared, { locale }, { capabilities }, testClock())
        first.load()
        second.load()
        first.change(SettingsChange.SampleRateTo(SampleRate.hz8))
        second.change(SettingsChange.Toggle(RideToggle.indoor, true))
        val stored = requireNotNull(shared.stored)
        assertEquals(true, stored.ride.indoor)
        assertEquals(defaults.ride.sampleRate, stored.ride.sampleRate)
        assertEquals(SampleRate.hz8, first.ride.sampleRate)
        first.load()
        assertEquals(true, first.ride.indoor)
        assertEquals(defaults.ride.sampleRate, first.ride.sampleRate)
    }

    private fun TestScope.testClock(): Clock =
        object : Clock {
            override fun monoUs(): Long = testScheduler.currentTime * 1_000

            override fun utcUs(): Long = monoUs()

            override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
        }
}
