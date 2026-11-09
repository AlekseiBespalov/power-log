package app.powerlog.data

import app.powerlog.application.store.SettingsLoad
import app.powerlog.application.store.SettingsRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.settings.SettingsDocument

internal class SqlSettingsRepository(private val archive: SqlArchive) : SettingsRepository {
    override suspend fun document(): StoreResult<SettingsLoad> = archive.read { db ->
        db.statement("SELECT settings FROM app_state WHERE k = 1") {
            it.step()
            SettingsCodec.decode(it.textOrNull(0))
        }
    }

    override suspend fun save(settings: SettingsDocument): StoreResult<Unit> {
        val text = SettingsCodec.encode(settings)
        return archive.write { db -> db.saveSettings(text) }
    }
}

internal fun SqlConnection.saveSettings(text: String?) {
    statement("UPDATE app_state SET settings = ? WHERE k = 1") {
        it.bindNullable(1, text)
        it.step()
    }
}
