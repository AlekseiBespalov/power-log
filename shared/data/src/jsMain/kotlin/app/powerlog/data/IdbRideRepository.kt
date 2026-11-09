package app.powerlog.data

import app.powerlog.application.store.RideCursor
import app.powerlog.application.store.RideRecord
import app.powerlog.application.store.RideRecordPage
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.ids.RideId

internal class IdbRideRepository(private val archive: IdbArchive) : RideRepository {
    override suspend fun ride(rideId: RideId): StoreResult<RideRecord?> =
        archive.read(listOf(IdbObjectStore.ride)) {
            request(store(IdbObjectStore.ride).get(rideId.value)) { record ->
                answer(if (isLive(record)) rideRecord(record) else null)
            }
        }

    override suspend fun openRide(): StoreResult<RideRecord?> =
        archive.read(listOf(IdbObjectStore.ride)) {
            request(store(IdbObjectStore.ride).index("open").openCursor(null, "next")) { cursor ->
                answer(if (cursor == null) null else rideRecord(cursor.value))
            }
        }

    override suspend fun page(after: RideCursor?, limit: Int): StoreResult<RideRecordPage> =
        archive.read(listOf(IdbObjectStore.ride)) {
            val range = after?.let { IDBKeyRange.upperBound(arrayOf(it.startedUtcUs.js(), it.rideId.value), true) }
            val records = mutableListOf<RideRecord>()
            cursor(
                store(IdbObjectStore.ride).index("history").openCursor(range, "prev"),
                each = { record ->
                    records += rideRecord(record)
                    records.size <= limit
                },
                done = {
                    val rows = records.take(limit)
                    val next = rows.lastOrNull()?.takeIf { records.size > limit }?.ride
                    answer(RideRecordPage(rows, next?.let { RideCursor(it.startedUtcUs, it.id) }))
                },
            )
        }

    override suspend fun marker(): StoreResult<Long> =
        archive.read(listOf(IdbObjectStore.appState)) {
            request(store(IdbObjectStore.appState).get(IdbArchive.APP)) { app -> answer(long(app.historyRevision)) }
        }
}
