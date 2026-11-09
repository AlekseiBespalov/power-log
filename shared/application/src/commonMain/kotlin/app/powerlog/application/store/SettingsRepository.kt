package app.powerlog.application.store

import app.powerlog.domain.settings.SettingsDocument
import app.powerlog.domain.settings.SettingsDraft

public interface SettingsRepository {
    public suspend fun document(): StoreResult<SettingsLoad>

    public suspend fun save(settings: SettingsDocument): StoreResult<Unit>
}

public sealed interface SettingsLoad {
    public data class Loaded(public val draft: SettingsDraft) : SettingsLoad

    public data object Missing : SettingsLoad

    public data object Unreadable : SettingsLoad
}
