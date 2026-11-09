package app.powerlog.data

import app.powerlog.application.store.MaintenanceRepository
import app.powerlog.application.store.Step
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.ids.RideId

private val CLEANUP_ORDER = listOf(IdbObjectStore.facts, IdbObjectStore.rideEvent, IdbObjectStore.controllerSession)

internal class IdbMaintenanceRepository(private val archive: IdbArchive) : MaintenanceRepository {
    override suspend fun pendingDeletions(): StoreResult<List<RideId>> =
        archive.read(listOf(IdbObjectStore.ride)) {
            val deleted = mutableListOf<Pair<Double, RideId>>()
            cursor(
                store(IdbObjectStore.ride).openCursor(null, "next"),
                each = { record ->
                    val at = record.deletedUtcUs
                    if (at != null) deleted.add(at.unsafeCast<Double>() to RideId(record.uuid.unsafeCast<String>()))
                    true
                },
                done = { answer(deleted.sortedBy { it.first }.map { it.second }) },
            )
        }

    override suspend fun cleanupStep(rideId: RideId, maxRows: Int): StoreResult<Step> =
        archive.write(CLEANUP_ORDER + IdbObjectStore.ride) {
            request(store(IdbObjectStore.ride).get(rideId.value)) { record ->
                when {
                    record == null -> answer(Step.done)
                    record.deletedUtcUs == null -> fail(StorageError(StorageError.Kind.constraint))
                    else -> deleteFirst(0, rideId, maxOf(1, maxRows))
                }
            }
        }

    override suspend fun reclaimStep(): StoreResult<Step> = StoreResult.Ok(Step.done)

    override suspend fun checkpoint(truncate: Boolean): StoreResult<Unit> = StoreResult.Ok(Unit)

    private fun IdbJob<Step>.deleteFirst(index: Int, rideId: RideId, maxRows: Int) {
        if (index == CLEANUP_ORDER.size) {
            store(IdbObjectStore.ride).delete(rideId.value)
            return answer(Step.done)
        }
        val source = store(CLEANUP_ORDER[index])
        request(source.getAllKeys(rideKeys(rideId), maxRows)) { found ->
            val keys = found.unsafeCast<Array<Any>>()
            if (keys.isEmpty()) return@request deleteFirst(index + 1, rideId, maxRows)
            source.delete(IDBKeyRange.bound(keys[0], keys[keys.size - 1], false, false))
            answer(Step.more)
        }
    }
}
