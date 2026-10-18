package app.powerlog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CoreFixturesTest {
    private val ride = "00000000-0000-4000-8000-000000000001"

    @Test
    fun streamStatusFixtures() {
        data class Fixture(
            val requested: Boolean,
            val phase: String,
            val age: Double?,
            val cutoff: Double,
            val status: String?,
            val expected: String,
        )
        val fixtures =
            listOf(
                Fixture(false, "running", null, 2.5, null, "off"),
                Fixture(true, "running", null, 2.5, null, "waiting"),
                Fixture(true, "paused", 20.0, 2.5, null, "paused"),
                Fixture(true, "running", 5.999, 6.0, null, "receiving"),
                Fixture(true, "running", 6.0, 6.0, null, "stale"),
                Fixture(true, "running", 20.0, 10.0, "denied", "denied"),
                Fixture(true, "running", null, 10.0, "weak", "waiting"),
                Fixture(true, "running", 9.999, 10.0, "weak", "weak"),
                Fixture(true, "running", 10.0, 10.0, "weak", "stale"),
                Fixture(true, "running", 14.999, 15.0, null, "receiving"),
                Fixture(true, "running", 15.0, 15.0, null, "stale"),
            )
        fixtures.forEach {
            assertEquals(
                it.expected,
                StreamStatus.resolve(it.requested, it.phase, it.age, it.cutoff, it.status),
                it.toString(),
            )
        }
        listOf(
                "off",
                "waiting",
                "receiving",
                "weak",
                "stale",
                "paused",
                "denied",
                "restricted",
                "notDetermined",
                "unavailable",
            )
            .forEach { assertEquals(it, StreamStatus.normalized(it)) }
        listOf("GPS ±5 m", "inactive", "missing", "authorizedAlways", "unknown", null).forEach {
            assertEquals("waiting", StreamStatus.normalized(it))
        }
        listOf(
                0.0 to "receiving",
                50.0 to "receiving",
                50.1 to "weak",
                -1.0 to "unavailable",
                Double.POSITIVE_INFINITY to "unavailable",
                Double.NaN to "unavailable",
                null to "unavailable",
            )
            .forEach { assertEquals(it.second, StreamStatus.location(it.first)) }
        assertEquals("receiving", StreamStatus.controller(true, "running", 4.0))
        assertEquals("stale", StreamStatus.controller(true, "running", 6.0))
    }

    @Test
    fun streamStatusMemberships() {
        val phases =
            listOf(
                Triple("preparing", true, false),
                Triple("running", true, true),
                Triple("paused", true, true),
                Triple("interrupted", true, false),
                Triple("finishing", true, false),
                Triple("stopping", false, false),
                Triple("completed", false, false),
                Triple("failed", false, false),
                Triple("idle", false, false),
                Triple("unknown", false, false),
                Triple("", false, false),
            )
        for ((phase, active, available) in phases) {
            assertEquals(active, StreamStatus.active(phase), phase)
            assertEquals(available, StreamStatus.available(0.0, 6.0, phase), phase)
            assertEquals(available, StreamStatus.available(5.999, 6.0, phase), phase)
            for (age in listOf(null, -1.0, 6.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertFalse(StreamStatus.available(age, 6.0, phase), "$phase:$age")
            }
        }
        val expired =
            mapOf(
                "off" to "off",
                "waiting" to "stale",
                "receiving" to "stale",
                "backfilled" to "backfilled",
                "weak" to "stale",
                "stale" to "stale",
                "paused" to "paused",
                "denied" to "denied",
                "restricted" to "restricted",
                "notDetermined" to "notDetermined",
                "unavailable" to "unavailable",
                "unknown" to "stale",
                "" to "stale",
                null to "stale",
            )
        for ((status, expected) in expired) {
            assertEquals(expected, StreamStatus.resolve(true, "running", 6.0, 6.0, status), status)
        }
        for ((display, expected) in
            mapOf(
                "live" to "Connected",
                "held" to "Connected",
                "unavailable" to "Reconnecting…",
                "unknown" to "Reconnecting…",
                "" to "Reconnecting…",
            )) {
            assertEquals(expected, StreamStatus.connectionLabel("reconnecting", display), display)
        }
    }

    @Test
    fun rideActionFixtures() {
        val phases = listOf(null, "preparing", "running", "paused", "stopping", "completed", "failed")
        val admitted =
            mapOf(
                "start" to setOf(null, "completed", "failed"),
                "pause" to setOf("running"),
                "resume" to setOf("paused"),
                "lap" to setOf("running", "paused"),
                "discard" to setOf("preparing", "running", "paused"),
                "stop" to setOf("preparing", "running", "paused"),
            )
        for ((action, accepted) in admitted) for (phase in phases) {
            assertEquals(phase in accepted, RideActions.rejection(action, phase) == null, "$action:$phase")
        }
        assertEquals("actionUnavailable", RideActions.rejection("pause", "paused"))
        assertEquals(null, RideActions.rejection("start", "completed"))
    }

    @Test
    fun healthIdentityFixtures() {
        val ids = app.powerlog.core.health.HealthIds
        assertEquals("powerlog:$ride:workout", ids.workout(ride))
        assertEquals("powerlog:$ride:route", ids.route(ride))
        for (kind in
            app.powerlog.core.health.HealthKind.entries.filter {
                it != app.powerlog.core.health.HealthKind.cyclingDistance
            }) {
            assertEquals("powerlog:$ride:${kind.wire}:second:0", ids.second(ride, kind, 0))
        }
        assertEquals(
            "powerlog:$ride:cycling-distance:interval:seq:watch:1:seq:watch:2",
            ids.distance(ride, "seq:watch:1", "seq:watch:2"),
        )
        assertEquals(ride, ids.uuid(ride.uppercase()))
        for (invalid in listOf("", "ride", "$ride:workout")) assertFailsWith<IllegalArgumentException> {
            ids.workout(invalid)
        }
        for (invalid in
            listOf(
                "seq:phone:0",
                "seq:phone:01",
                "seq:phone:-1",
                "seq:phone:9223372036854775808",
                "seq:other:1",
                "phone:1",
                "$ride:watch-1",
                "seq:browser:1",
            )) {
            assertFailsWith<IllegalArgumentException> { ids.originKey(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { ids.origin("other", "1") }
        assertEquals("seq:controller:9223372036854775807", ids.origin("controller", Long.MAX_VALUE.toString()))
    }

    @Test
    fun sha256Fixtures() {
        val data = "abc".encodeToByteArray()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hash(data))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hash(byteArrayOf()))
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hash(ByteArray(1000000) { 97 }),
        )
    }

    @Test
    fun powerIntegrationFixtures() {
        data class Fixture(
            val times: DoubleArray,
            val values: DoubleArray,
            val runs: IntArray,
            val covered: Double,
            val work: Double,
            val maximum: Double?,
        )
        val fixtures =
            listOf(
                Fixture(
                    doubleArrayOf(0.0, 1.0, 2.0),
                    doubleArrayOf(100.0, 200.0, 300.0),
                    intArrayOf(0, 0, 0),
                    2.0,
                    400.0,
                    300.0,
                ),
                Fixture(
                    doubleArrayOf(0.0, 1.0, 2.0, 3.0),
                    doubleArrayOf(100.0, Double.NaN, 200.0, 200.0),
                    intArrayOf(0, 0, 0, 0),
                    1.0,
                    200.0,
                    200.0,
                ),
                Fixture(
                    doubleArrayOf(0.0, 2.5, 5.001),
                    doubleArrayOf(100.0, 100.0, 300.0),
                    intArrayOf(0, 0, 0),
                    2.5,
                    250.0,
                    300.0,
                ),
                Fixture(
                    doubleArrayOf(0.0, 1.0, 2.0, 3.0),
                    doubleArrayOf(100.0, -1.0, 32767.0, 300.0),
                    intArrayOf(0, 0, 0, 0),
                    0.0,
                    0.0,
                    300.0,
                ),
                Fixture(
                    doubleArrayOf(0.0, 1.0, 2.0, 3.0),
                    doubleArrayOf(100.0, 100.0, 200.0, 200.0),
                    intArrayOf(0, -1, 1, 1),
                    1.0,
                    200.0,
                    200.0,
                ),
                Fixture(
                    doubleArrayOf(0.0, 0.0, 1.0),
                    doubleArrayOf(0.0, 32766.0, 32766.0),
                    intArrayOf(0, 0, 1),
                    0.0,
                    0.0,
                    32766.0,
                ),
            )
        fixtures.forEach {
            val result = PowerKernel.integrate(it.times, it.values, it.runs)
            assertEquals(it.covered, result.covered)
            assertEquals(it.work, result.integral)
            assertEquals(it.maximum, result.maximum)
            assertEquals(if (it.covered > 0) it.work / it.covered else null, result.average)
        }
    }

    @Test
    fun powerIntegrationPreservesIeeeBits() {
        val zero = PowerKernel.integrate(doubleArrayOf(0.0, 1.0), doubleArrayOf(-0.0, 0.0), intArrayOf(0, 0))
        assertEquals(1.0.toBits(), zero.covered.toBits())
        assertEquals(0.0.toBits(), zero.integral.toBits())
        assertEquals((-0.0).toBits(), zero.maximum?.toBits())
        assertEquals(0.0.toBits(), zero.average?.toBits())
        val empty = PowerKernel.integrate(doubleArrayOf(), doubleArrayOf(), intArrayOf())
        assertEquals(PowerIntegration(0.0, 0.0, null, null), empty)
        val barriers =
            PowerKernel.integrate(
                doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0),
                doubleArrayOf(
                    100.0,
                    Double.POSITIVE_INFINITY,
                    200.0,
                    Double.NEGATIVE_INFINITY,
                    300.0,
                    Double.NaN,
                    400.0,
                    500.0,
                ),
                IntArray(8),
            )
        assertEquals(1.0.toBits(), barriers.covered.toBits())
        assertEquals(450.0.toBits(), barriers.integral.toBits())
        assertEquals(500.0.toBits(), barriers.maximum?.toBits())
        assertEquals(450.0.toBits(), barriers.average?.toBits())
        for (time in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                PowerKernel.integrate(doubleArrayOf(time), doubleArrayOf(100.0), intArrayOf(0))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            PowerKernel.integrate(doubleArrayOf(0.0), doubleArrayOf(), intArrayOf())
        }
    }

    @Test
    fun nearThresholdNumericFixture() {
        assertEquals("4049000000000000", NumericProbe.bits())
        assertFalse(NumericProbe.branch())
    }
}
