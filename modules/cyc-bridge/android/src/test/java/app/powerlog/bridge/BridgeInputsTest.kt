package app.powerlog.bridge

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun rideOptionsFixture(): Payload =
    JSONObject(File("../../../tests/fixtures/contract/ride-options.json").readText()).map()

internal fun rejectedRideOptions(): List<Pair<String, Payload>> {
    val fixture = rideOptionsFixture()
    val rates =
        (fixture["rejectedSampleRates"] as List<*>) +
            (fixture["rejectedSampleRateTypes"] as List<*>) +
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
    val options = (fixture["rejectedOptionValues"] as List<*>).map { it as Map<*, *> }
    return rates.map { "sampleHz" to mapOf("indoor" to false, "sampleHz" to it) } +
        options.map { it["field"] as String to (mapOf("indoor" to false) + (it["field"] as String to it["value"])) } +
        listOf("indoor" to emptyMap())
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BridgeInputsTest {
    private val wrong = listOf(null, true, "2", emptyMap<String, Any?>(), listOf(2))
    private val nonstrings = listOf(null, true, 2, emptyMap<String, Any?>(), listOf(2))
    private val malformed = wrong - null
    private val nonfinite = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
    private val common: Payload = mapOf("source" to "live", "generation" to 0)
    private val plot = common + mapOf("expectedRevision" to "0", "metrics" to listOf("humanPowerW"))
    private val stats = plot + mapOf("startSeconds" to 0.0, "endSeconds" to 10.0)
    private val inspect = plot + mapOf("seconds" to 1.0)

    private fun rejects(field: String, body: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java, body)
        assertTrue("$field: ${error.message}", error.message.orEmpty().contains(field))
    }

    @Test
    fun rideOptionsRejectEveryFixtureValueAndNonfiniteRate() {
        for ((field, input) in rejectedRideOptions()) rejects(field) { BridgeInputs.ride(input) }
    }

    @Test
    fun rideDefaultsNullsAndBooleanTypesAreExplicit() {
        for (indoor in listOf(false, true)) {
            val expected = RideOptions(indoor = indoor)
            assertEquals(expected, BridgeInputs.ride(mapOf("indoor" to indoor)))
            assertEquals(
                expected,
                BridgeInputs.ride(
                    mapOf(
                        "indoor" to indoor,
                        "useWatch" to null,
                        "saveToHealth" to null,
                        "recordGPS" to null,
                        "sampleHz" to null,
                    )
                ),
            )
            for (field in listOf("indoor", "useWatch", "saveToHealth", "recordGPS")) {
                for (value in listOf("false", 0, 1.5, emptyList<Any>(), emptyMap<String, Any>()) + nonfinite) rejects(
                    field
                ) {
                    BridgeInputs.ride(mapOf("indoor" to indoor, field to value))
                }
            }
        }
        for (rate in listOf(2, 4, 8)) {
            assertEquals(rate, BridgeInputs.sampleHz(rate))
            assertEquals(rate, BridgeInputs.sampleHz(rate.toDouble()))
        }
    }

    @Test
    fun effectiveOptionsUseGpsAndTheSelectedOwnersHealthCapability() {
        val input = BridgeInputs.ride(mapOf("indoor" to false, "useWatch" to true))
        val capabilities = RideCapabilities(true, true, true, false, "appleHealth", false, false)
        val watch = input.effective(capabilities)
        assertTrue(watch.useWatch)
        assertFalse(watch.saveToHealth)
        assertFalse(watch.recordGPS)
        val phone = input.effective(capabilities.copy(watchWorkout = false))
        assertFalse(phone.useWatch)
        assertTrue(phone.saveToHealth)
        assertFalse(phone.recordGPS)
    }

    @Test
    fun connectRequiresAnIdentifierAndValidatesHzWithoutTruncation() {
        for (value in nonstrings + listOf("", " ", "bike\n", 123)) rejects("deviceId") {
            BridgeInputs.connect(mapOf("deviceId" to value))
        }
        rejects("deviceId") { BridgeInputs.connect(emptyMap()) }
        assertEquals(ConnectInput("bike", 2), BridgeInputs.connect(mapOf("deviceId" to "bike")))
        assertEquals(ConnectInput("bike", 2), BridgeInputs.connect(mapOf("deviceId" to "bike", "hz" to null)))
        val fixture = rideOptionsFixture()
        for (value in
            (fixture["rejectedSampleRates"] as List<*>) +
                (fixture["rejectedSampleRateTypes"] as List<*>) +
                nonfinite) rejects("hz") { BridgeInputs.connect(mapOf("deviceId" to "bike", "hz" to value)) }
        for (rate in listOf(2.0, 4.0, 8.0)) assertEquals(
            rate.toInt(),
            BridgeInputs.connect(mapOf("deviceId" to "bike", "hz" to rate)).sampleHz,
        )
    }

    @Test
    fun catalogRejectsMalformedLimitsAndIncompleteOrMalformedCursors() {
        assertEquals(CatalogInput(), BridgeInputs.catalog(emptyMap()))
        for (value in malformed + nonfinite + listOf(0, -1, 1.5, 101, Double.MAX_VALUE)) rejects("limit") {
            BridgeInputs.catalog(mapOf("limit" to value))
        }
        for (limit in listOf(1, 100)) assertEquals(limit, BridgeInputs.catalog(mapOf("limit" to limit)).limit)
        val cursor = mapOf("beforeStartedAt" to "2026-01-01T00:00:00.000Z", "beforeID" to "ride")
        assertEquals(CatalogCursor(cursor.getValue("beforeStartedAt"), "ride"), BridgeInputs.catalog(cursor).cursor)
        for (field in cursor.keys) {
            rejects(field) { BridgeInputs.catalog(cursor - field) }
            for (value in nonstrings + listOf("", " ", 1)) rejects(field) {
                BridgeInputs.catalog(cursor + (field to value))
            }
        }
        rejects("beforeStartedAt") { BridgeInputs.catalog(cursor + ("beforeStartedAt" to "yesterday")) }
    }

    @Test
    fun allMonitorOperationsValidateTargetsAndBoundedGeneration() {
        for (operation in MonitorOperation.entries) {
            val input = stats + mapOf("seconds" to 1, "sinceRevision" to "0")
            assertEquals(MonitorTarget.Live, BridgeInputs.monitor(operation, input).target)
            assertEquals(
                MonitorTarget.Workout("ride"),
                BridgeInputs.monitor(
                        operation,
                        input + mapOf("source" to "workout", "id" to "ride"),
                    )
                    .target,
            )
            for (field in listOf("source", "generation")) rejects(field) {
                BridgeInputs.monitor(operation, input - field)
            }
            for (value in wrong + listOf("unknown", "", 0)) rejects("source") {
                BridgeInputs.monitor(operation, input + ("source" to value))
            }
            for (value in wrong + nonfinite + listOf(-1, 0.5, Int.MAX_VALUE.toDouble() + 1)) rejects("generation") {
                BridgeInputs.monitor(operation, input + ("generation" to value))
            }
            assertEquals(
                Int.MAX_VALUE,
                BridgeInputs.monitor(operation, input + ("generation" to Int.MAX_VALUE)).generation,
            )
            for (value in nonstrings + listOf("", " ", 1)) rejects("id") {
                BridgeInputs.monitor(operation, input + mapOf("source" to "workout", "id" to value))
            }
            rejects("id") { BridgeInputs.monitor(operation, input + ("source" to "workout")) }
        }
    }

    @Test
    fun monitorValidatesDistanceRevisionsAndTheWholeMetricList() {
        for (source in DistanceSource.entries) assertEquals(
            source,
            BridgeInputs.monitor(MonitorOperation.Plot, plot + ("distanceSource" to source.wire)).distanceSource,
        )
        for (value in malformed + listOf("gps", "")) rejects("distanceSource") {
            BridgeInputs.monitor(MonitorOperation.Plot, plot + ("distanceSource" to value))
        }
        for (field in listOf("expectedRevision", "sinceRevision")) {
            for (value in nonstrings - null + listOf(0, "", "-1", "1.5", "+1", "1e2", "9223372036854775808")) rejects(
                field
            ) {
                BridgeInputs.monitor(MonitorOperation.Plot, plot + (field to value))
            }
        }
        rejects("expectedRevision") { BridgeInputs.monitor(MonitorOperation.Plot, plot - "expectedRevision") }
        rejects("sinceRevision") { BridgeInputs.monitor(MonitorOperation.Changes, common) }
        for (operation in
            listOf(MonitorOperation.Latest, MonitorOperation.Plot, MonitorOperation.Inspect, MonitorOperation.Stats)) {
            val input = stats + ("seconds" to 0)
            rejects("metrics") { BridgeInputs.monitor(operation, input - "metrics") }
            for (value in
                wrong + listOf(listOf("humanPowerW", null), listOf("unknown"), List(33) { "humanPowerW" })) rejects(
                "metrics"
            ) {
                BridgeInputs.monitor(operation, input + ("metrics" to value))
            }
        }
        val unavailable = listOf("heartRateBpm", "healthSpeedMps", "activeEnergyKcal", "basalEnergyKcal")
        assertEquals(
            unavailable,
            BridgeInputs.monitor(MonitorOperation.Latest, common + ("metrics" to unavailable)).metrics,
        )
        val repeated = List(32) { "humanPowerW" }
        assertEquals(repeated, BridgeInputs.monitor(MonitorOperation.Plot, plot + ("metrics" to repeated)).metrics)
    }

    @Test
    fun explicitNullOptionalFieldsMeanOmitted() {
        val omitted =
            mapOf(
                "id" to null,
                "distanceSource" to null,
                "sinceRevision" to null,
                "startSeconds" to null,
                "endSeconds" to null,
                "seconds" to null,
                "buckets" to null,
                "pixelWidth" to null,
                "includeEndpoints" to null,
                "anchor" to null,
                "startAnchor" to null,
                "endAnchor" to null,
            )
        assertEquals(
            BridgeInputs.monitor(MonitorOperation.Latest, common + ("metrics" to listOf("humanPowerW"))),
            BridgeInputs.monitor(MonitorOperation.Latest, common + ("metrics" to listOf("humanPowerW")) + omitted),
        )
        assertEquals(
            CatalogInput(),
            BridgeInputs.catalog(mapOf("limit" to null, "beforeStartedAt" to null, "beforeID" to null)),
        )
    }

    @Test
    fun monitorRangesBucketsAndEndpointFlagsAreStrict() {
        val input = stats + ("seconds" to 0)
        for (field in listOf("startSeconds", "endSeconds", "seconds", "pixelWidth")) for (value in
            malformed + nonfinite + listOf(-0.1)) rejects(field) {
            BridgeInputs.monitor(MonitorOperation.Stats, input + (field to value))
        }
        rejects("endSeconds") { BridgeInputs.monitor(MonitorOperation.Stats, stats + ("endSeconds" to -1)) }
        rejects("endSeconds") { BridgeInputs.monitor(MonitorOperation.Stats, stats + ("startSeconds" to 11)) }
        for (field in listOf("startSeconds", "endSeconds")) rejects(field) {
            BridgeInputs.monitor(MonitorOperation.Stats, stats - field)
        }
        rejects("seconds") { BridgeInputs.monitor(MonitorOperation.Inspect, plot) }
        for (value in malformed + nonfinite + listOf(0, 1.5, 513, -1)) rejects("buckets") {
            BridgeInputs.monitor(MonitorOperation.Plot, plot + ("buckets" to value))
        }
        for (buckets in listOf(1, 512)) assertEquals(
            buckets,
            BridgeInputs.monitor(MonitorOperation.Plot, plot + ("buckets" to buckets)).buckets,
        )
        assertEquals(128, BridgeInputs.monitor(MonitorOperation.Plot, plot).buckets)
        assertNull(BridgeInputs.monitor(MonitorOperation.Plot, plot).endSeconds)
        val fractional =
            BridgeInputs.monitor(
                MonitorOperation.Stats,
                stats + mapOf("startSeconds" to 0.25, "endSeconds" to 0.75, "pixelWidth" to 120.5),
            )
        assertEquals(0.25, fractional.startSeconds, 0.0)
        assertEquals(0.75, fractional.endSeconds!!, 0.0)
        assertEquals(0.0, BridgeInputs.monitor(MonitorOperation.Plot, plot).startSeconds, 0.0)
        for (value in listOf("true", 1, 0.5, emptyList<Any>()) + nonfinite) rejects("includeEndpoints") {
            BridgeInputs.monitor(MonitorOperation.Stats, stats + ("includeEndpoints" to value))
        }
        assertTrue(BridgeInputs.monitor(MonitorOperation.Stats, stats + ("includeEndpoints" to true)).includeEndpoints)
    }

    @Test
    fun anchorsKeepStringIdentityAndCheckTheLongConversionAndMetricRelationship() {
        val input = stats + ("seconds" to 0)
        val anchor = mapOf("metric" to "humanPowerW", "observationId" to "9223372036854775807")
        for (field in listOf("anchor", "startAnchor", "endAnchor")) {
            val request = BridgeInputs.monitor(MonitorOperation.Stats, input + (field to anchor))
            val parsed =
                when (field) {
                    "anchor" -> request.anchor
                    "startAnchor" -> request.startAnchor
                    else -> request.endAnchor
                }
            assertEquals(Long.MAX_VALUE, parsed!!.rowId)
            for (value in malformed + listOf("anchor", 1)) rejects(field) {
                BridgeInputs.monitor(MonitorOperation.Stats, input + (field to value))
            }
            for (value in nonstrings + listOf("0", "-1", "1.2", "9223372036854775808", "", "+1", 1)) rejects(
                "$field.observationId"
            ) {
                BridgeInputs.monitor(MonitorOperation.Stats, input + (field to (anchor + ("observationId" to value))))
            }
            for (value in listOf(null, "cadenceRpm", "unknown", 1)) rejects("$field.metric") {
                BridgeInputs.monitor(MonitorOperation.Stats, input + (field to (anchor + ("metric" to value))))
            }
            for (member in anchor.keys) rejects("$field.$member") {
                BridgeInputs.monitor(MonitorOperation.Stats, input + (field to (anchor - member)))
            }
        }
        rejects("anchor") { BridgeInputs.monitor(MonitorOperation.Plot, plot + ("anchor" to anchor)) }
        rejects("startAnchor") { BridgeInputs.monitor(MonitorOperation.Plot, plot + ("startAnchor" to anchor)) }
        rejects("endAnchor") { BridgeInputs.monitor(MonitorOperation.Plot, plot + ("endAnchor" to anchor)) }
        assertEquals(
            1L,
            BridgeInputs.monitor(MonitorOperation.Inspect, inspect + ("anchor" to (anchor + ("observationId" to "1"))))
                .anchor!!
                .rowId,
        )
    }
}
