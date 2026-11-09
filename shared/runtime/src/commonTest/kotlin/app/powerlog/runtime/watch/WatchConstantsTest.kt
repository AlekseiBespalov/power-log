package app.powerlog.runtime.watch

import app.powerlog.application.watch.WatchProtocol
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.health.HealthIds
import app.powerlog.domain.recording.StartPolicy
import app.powerlog.runtime.contractFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class WatchConstantsTest {
    private val constants: JsonObject =
        Json.parseToJsonElement(contractFixture("watch/constants.json").decodeToString()).jsonObject

    private fun number(name: String): Long = constants.getValue(name).jsonPrimitive.long

    @Test
    fun protocolValuesAreWatchProtocols() {
        val protocol = constants.getValue("watchProtocol").jsonObject.mapValues { it.value.jsonPrimitive.long }
        assertEquals(
            mapOf(
                "version" to WatchProtocol.version.toLong(),
                "maxMessageBytes" to WatchProtocol.maxMessageBytes.toLong(),
                "maxQuantities" to WatchProtocol.maxQuantities.toLong(),
                "maxRoutePoints" to WatchProtocol.maxRoutePoints.toLong(),
                "maxLaps" to WatchProtocol.maxLaps.toLong(),
                "maxHealthSamples" to WatchProtocol.maxHealthSamples.toLong(),
                "rideIntervalUs" to WatchProtocol.rideIntervalUs,
                "routineMinIntervalUs" to WatchProtocol.routineMinIntervalUs,
                "commandExpiryUs" to WatchProtocol.commandExpiryUs,
                "resendUs" to WatchProtocol.resendUs,
                "replayUs" to WatchProtocol.replayUs,
            ),
            protocol,
        )
    }

    @Test
    fun startWindowIsStartPolicys() {
        assertEquals(StartPolicy.windowUs, number("startWindowUs"))
    }

    @Test
    fun heartRateDisplayHoldIsTheCatalogs() {
        assertEquals(MetricCatalog.of(MetricId.heartRateBpm).displayHoldUs, number("heartRateDisplayHoldUs"))
    }

    @Test
    fun ownSyncIdPrefixMarksPowerLogsOwnSamples() {
        val prefix = constants.getValue("ownSyncIdPrefix").jsonPrimitive.content
        assertTrue(HealthIds.isOwn("${prefix}11111111-1111-4111-8111-111111111111:workout"))
        assertFalse(HealthIds.isOwn("app.example.watch:energy:17"))
    }
}
