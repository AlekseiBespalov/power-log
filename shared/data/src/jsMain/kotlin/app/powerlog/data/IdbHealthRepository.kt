package app.powerlog.data

import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId

internal class IdbHealthRepository(private val archive: IdbArchive) : HealthRepository {
    override suspend fun outcome(rideId: RideId): StoreResult<RideHealth?> =
        archive.read(listOf(IdbObjectStore.ride)) {
            request(store(IdbObjectStore.ride).get(rideId.value)) { record ->
                answer(if (isLive(record)) healthOf(record) else null)
            }
        }

    override suspend fun work(rideId: RideId): StoreResult<List<HealthWork>> = StoreResult.Ok(emptyList())

    override suspend fun dueRides(nowUtcUs: Long): StoreResult<List<RideId>> = StoreResult.Ok(emptyList())

    override suspend fun deferredRides(): StoreResult<List<RideId>> = StoreResult.Ok(emptyList())
}
