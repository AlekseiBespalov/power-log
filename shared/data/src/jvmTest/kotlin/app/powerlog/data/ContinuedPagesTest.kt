package app.powerlog.data

import app.powerlog.application.store.ContractData
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.commit
import app.powerlog.application.store.create
import app.powerlog.application.store.ok
import app.powerlog.application.store.opened
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ContinuedPagesTest {
    private val harness = SqlArchiveHarness(JvmSqlPlatform)

    @Test
    fun aContinuedPageSearchesFromItsKeyAndNotFromTheRangeStart() = runTest {
        val store = opened(harness.create(this))
        val id = ContractData.rideId(1)
        store.create(ContractData.ride(id))
        store.commit(
            Mutation.OpenControllerSession(ContractData.session(id)),
            ContractData.append(id, ContractData.readings(40)),
            Mutation.AppendLocations(id, ContractData.fixes(id, 40)),
            Mutation.AppendHealthSamples(id, List(40) { ContractData.sample(id, "s$it", it * 1_000_000L) }),
        )
        val range = ContractData.ALL
        val telemetry = store.measurements.telemetry(id, range, null, 7).ok().next!!
        val locations = store.measurements.locations(id, range, null, 7).ok().next!!
        val health = store.measurements.health(id, range, null, null, 7).ok().next!!
        val pages = store.executed {
            store.measurements.telemetry(id, range, telemetry, 7).ok()
            store.measurements.locations(id, range, locations, 7).ok()
            store.measurements.health(id, range, null, health, 7).ok()
        }
        assertEquals(
            listOf(telemetry.elapsedUs, locations.elapsedUs, health.elapsedUs),
            pages.map { it.longs.getValue(2) },
        )
    }
}
