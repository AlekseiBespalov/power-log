package app.powerlog.domain.health

import app.powerlog.domain.analytics.fixture
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthQuantity
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.time.Utc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthIdsTest {
    private val ride = RideId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")

    @Test
    fun everyPlatformNamesARidesRecordsWithOneGrammar() {
        val case = fixture("health/identity.json")["cases"].list().single { it["name"].text() == "canonical-ids" }
        val input = case["input"]
        val expected = case["expected"]
        val rideId = RideId(input["rideId"].text().lowercase())
        fun seq(origin: String) = origin.removePrefix("seq:phone:").toLong()
        assertEquals(expected["workout"].text(), HealthIds.workout(rideId))
        assertEquals(expected["route"].text(), HealthIds.route(rideId))
        assertEquals(expected["lap"].text(), HealthIds.lap(rideId, EventId(input["eventId"].text())))
        assertEquals(
            expected["second"].text(),
            HealthIds.second(rideId, HealthQuantity.riderPower, input["second"].long()),
        )
        assertEquals(
            expected["distance"].text(),
            HealthIds.distance(rideId, seq(input["from"].text()), seq(input["to"].text())),
        )
    }

    @Test
    fun aSecondIsCountedFromTheRideStart() {
        val start = requireNotNull(Utc.parseUs("2026-09-06T10:00:00.400Z"))
        val reading = requireNotNull(Utc.parseUs("2026-09-06T10:00:05.300Z"))
        val n = (reading - start).floorDiv(1_000_000L)
        assertEquals(4, n)
        val bounds = HealthPublication.bounds(start, start + 60_000_000, n)
        assertTrue(reading >= bounds.range.startUtcUs && reading < bounds.range.endUtcUs)
        assertEquals(
            "powerlog:${ride.value}:rider-power:second:4",
            HealthIds.second(ride, HealthQuantity.riderPower, n),
        )
        assertEquals(
            listOf("rider-power", "cycling-cadence", "cycling-speed"),
            listOf(HealthQuantity.riderPower, HealthQuantity.cyclingCadence, HealthQuantity.cyclingSpeed).map {
                HealthIds.second(ride, it, 0).substringAfter("${ride.value}:").substringBefore(":second")
            },
        )
    }

    @Test
    fun aGpsEdgeIsNamedByTheSeqsOfItsTwoFixes() {
        assertEquals(
            "powerlog:${ride.value}:cycling-distance:interval:seq:phone:7:seq:phone:8",
            HealthIds.distance(ride, 7, 8),
        )
    }

    @Test
    fun onlyPowerLogsOwnIdsAreOwn() {
        assertTrue(HealthIds.isOwn("powerlog:x"))
        assertTrue(HealthIds.isOwn(HealthIds.workout(ride)))
        assertFalse(HealthIds.isOwn("power-log:x"))
        assertFalse(HealthIds.isOwn("powerlog.x"))
        assertFalse(HealthIds.isOwn(null))
    }

    @Test
    fun retryIsOfferedOnlyForKnownSafeFailures() {
        val safe =
            setOf(HealthReason.permission, HealthReason.quota, HealthReason.locked, HealthReason.transientFailure)
        for (state in ComponentState.entries) for (reason in HealthReason.entries + null) assertEquals(
            state == ComponentState.failed && reason in safe,
            PublicationRules.retryAllowed(state, reason),
            "$state $reason",
        )
    }
}
