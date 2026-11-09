package app.powerlog.application.store

import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.ids.BikeId

public interface ConnectionRepository {
    public suspend fun known(bikeId: BikeId): StoreResult<ControllerIdentity?>

    public suspend fun selected(): StoreResult<BikeId?>
}
