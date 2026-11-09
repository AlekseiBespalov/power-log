package app.powerlog.data

import app.powerlog.application.store.ConnectionRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.BikeId

internal class IdbConnectionRepository(private val archive: IdbArchive) : ConnectionRepository {
    override suspend fun known(bikeId: BikeId): StoreResult<ControllerIdentity?> =
        archive.read(listOf(IdbObjectStore.knownController)) {
            request(store(IdbObjectStore.knownController).get(bikeId.value)) { row ->
                answer(
                    if (row == null) null
                    else
                        ControllerIdentity(
                            DecoderId(row.decoder.unsafeCast<String>()),
                            row.model.unsafeCast<String>(),
                            row.firmware.unsafeCast<String>(),
                            row.protocol.unsafeCast<String>(),
                        )
                )
            }
        }

    override suspend fun selected(): StoreResult<BikeId?> =
        archive.read(listOf(IdbObjectStore.appState)) {
            request(store(IdbObjectStore.appState).get(IdbArchive.APP)) { app ->
                answer(textOrNull(app.selectedBike)?.let(::BikeId))
            }
        }
}
