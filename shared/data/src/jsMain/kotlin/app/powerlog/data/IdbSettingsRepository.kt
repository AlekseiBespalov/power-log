package app.powerlog.data

import app.powerlog.application.store.SettingsLoad
import app.powerlog.application.store.SettingsRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.settings.SettingsDocument

internal class IdbSettingsRepository(private val archive: IdbArchive) : SettingsRepository {
    override suspend fun document(): StoreResult<SettingsLoad> =
        archive.read(listOf(IdbObjectStore.appState)) {
            request(store(IdbObjectStore.appState).get(IdbArchive.APP)) { app ->
                answer(SettingsCodec.decode(textOrNull(app.settings)))
            }
        }

    override suspend fun save(settings: SettingsDocument): StoreResult<Unit> =
        saveSettings(SettingsCodec.encode(settings))

    suspend fun saveSettings(text: String?): StoreResult<Unit> =
        archive.write(listOf(IdbObjectStore.appState)) {
            val appState = store(IdbObjectStore.appState)
            request(appState.get(IdbArchive.APP)) { app ->
                app.settings = text ?: undefined
                appState.put(app)
                answer(Unit)
            }
        }
}
