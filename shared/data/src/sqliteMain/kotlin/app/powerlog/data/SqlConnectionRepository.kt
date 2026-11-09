package app.powerlog.data

import app.powerlog.application.store.ConnectionRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.BikeId

internal class SqlConnectionRepository(private val archive: SqlArchive) : ConnectionRepository {
    override suspend fun known(bikeId: BikeId): StoreResult<ControllerIdentity?> = archive.read { db ->
        db.statement("SELECT decoder, model, firmware, protocol FROM known_controller WHERE bike_id = ?") {
            it.bindText(1, bikeId.value)
            if (!it.step()) return@statement null
            ControllerIdentity(DecoderId(it.getText(0)), it.getText(1), it.getText(2), it.getText(3))
        }
    }

    override suspend fun selected(): StoreResult<BikeId?> = archive.read { db ->
        db.statement("SELECT selected_bike FROM app_state WHERE k = 1") {
            it.step()
            it.textOrNull(0)?.let(::BikeId)
        }
    }
}
