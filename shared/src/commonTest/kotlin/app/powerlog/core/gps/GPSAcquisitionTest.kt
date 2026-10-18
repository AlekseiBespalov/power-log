package app.powerlog.core.gps

import app.powerlog.core.analytics.*
import app.powerlog.core.protocol.Timestamps
import kotlin.test.*

private fun gpsFixture(name: String): J = fixture("gps/$name.json")

private val baseUs = Timestamps.parse("2026-01-01T00:00:00Z").comparisonUs

private fun original(timeUs: Long, latitude: Double = 40.0, accuracy: Double = 5.0): GPSOriginal {
    val seconds = timeUs / 1000000
    val timestamp =
        "2026-01-01T00:${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}.${(timeUs % 1000000).toString().padStart(6, '0')}Z"
    return GPSOriginal(Timestamps.parse(timestamp), latitude, 20.0, accuracy)
}

private fun timeline(
    epoch: String = "epoch",
    origin: Double = 0.0,
    epochStart: Double? = null,
    stop: Double? = null,
    uncertain: Boolean = false,
) = GPSTimeline(epoch, baseUs, origin, epochStart, stop, uncertain)

private fun receipt(offsetUs: Long, uptime: Double) = GPSReceipt(baseUs + offsetUs, uptime)

class GPSAcquisitionTest {
    @Test
    fun batchesUseOriginalTimestampAge() {
        val clock = GPSAcquisition()
        val mapped = clock.mapBatch(listOf(original(2000000)), receipt(2000000, 2.0), timeline()).fixes.single()
        assertEquals(2.0, mapped.acquisitionMonotonic)
        assertEquals(2000000, mapped.elapsedUs)
        assertTrue(clock.admit(mapped))
    }

    @Test
    fun gpsRawTimeFixtures() {
        for (row in gpsFixture("raw-time")["cases"].list()) {
            val clock = GPSAcquisition()
            row["prime"].optionalText()?.let {
                val prime = Timestamps.parse(it)
                assertNotNull(
                    clock.map(
                        GPSOriginal(prime, 40.0, 20.0, 5.0),
                        GPSReceipt(prime, row["primeUptime"].double()),
                        timeline(),
                    )
                )
            }
            val fix =
                clock.map(
                    GPSOriginal(Timestamps.parse(row["fix"].text()), 40.0, 20.0, 5.0),
                    GPSReceipt(Timestamps.parse(row["receipt"].text()), row["uptime"].double()),
                    timeline(),
                )
            assertEquals(row["mapped"].bool(), fix != null, row["id"].text())
            if (fix != null) {
                assertEquals(row["acquisition"].double(), fix.acquisitionMonotonic, 1e-12, row["id"].text())
                assertEquals(row["elapsedUs"].long(), fix.elapsedUs, row["id"].text())
                assertEquals(row["uncertain"].bool(), fix.uncertain, row["id"].text())
            }
        }
    }

    @Test
    fun gpsExactTimestampAndClockInputs() {
        val clock = GPSAcquisition()
        val timestamp = Timestamps.parse("2026-01-01T00:00:10.123456789Z")
        val fix = assertNotNull(clock.map(GPSOriginal(timestamp, 40.0, 20.0, 5.0), receipt(20000000, 20.0), timeline()))
        assertEquals(timestamp.original, fix.original.timestamp.original)
        assertTrue(clock.admit(fix))
        val equivalent = GPSOriginal(Timestamps.parse("2026-01-01T00:00:10.123456789+00:00"), 40.0, 20.0, 5.0)
        assertFalse(clock.admit(assertNotNull(clock.map(equivalent, receipt(20000000, 20.0), timeline("next")))))
        val different = equivalent.copy(timestamp = Timestamps.parse("2026-01-01T00:00:10.123456790Z"))
        assertTrue(clock.admit(assertNotNull(clock.map(different, receipt(20000000, 20.0), timeline("third")))))
        assertNull(clock.map(original(10000000), receipt(20000000, 20.0), timeline(origin = Double.NaN)))
        assertFalse(assertNotNull(GPSAcquisition().map(original(0), receipt(0, 0.0), timeline())).uncertain)
    }

    @Test
    fun gpsAcquisitionFixtures() {
        for (row in gpsFixture("acquisition")["cases"].list()) {
            val clock = GPSAcquisition()
            val fix =
                clock.map(
                    (row["timestamp"].optionalText()?.let { GPSOriginal(Timestamps.parse(it), 40.0, 20.0, 5.0) }
                        ?: original(
                            row["fixUs"].long(),
                            row["latitude"].optionalDouble() ?: 40.0,
                            row["accuracy"].optionalDouble() ?: 5.0,
                        )),
                    receipt(row["receiptUs"].long(), row["uptime"].double()),
                    timeline(
                        origin = row["origin"].optionalDouble() ?: 0.0,
                        epochStart = row["epochStart"].optionalDouble(),
                        stop = row["stop"].optionalDouble(),
                    ),
                )
            assertEquals(row["mapped"].bool(), fix != null, row["id"].text())
            if (fix != null) {
                assertEquals(row["acquisition"].double(), fix.acquisitionMonotonic, row["id"].text())
                assertTrue(clock.admit(fix), row["id"].text())
                assertEquals(row["elapsed"].double(), fix.elapsedSeconds, row["id"].text())
                assertEquals(row["elapsedUs"].long(), fix.elapsedUs, row["id"].text())
            }
        }
    }

    @Test
    fun gpsOrderingAndDedupFixtures() {
        val clock = GPSAcquisition()
        for (row in gpsFixture("acquisition")["ordering"].list()) {
            val fix = assertNotNull(clock.map(original(row["timeUs"].long()), receipt(20000000, 20.0), timeline()))
            assertEquals(row["accepted"].bool(), clock.admit(fix), row["timeUs"].text())
        }
        val batchClock = GPSAcquisition()
        val batch =
            batchClock.mapBatch(
                listOf(original(12000000), original(10000000), original(11000000), original(1000000)),
                receipt(20000000, 20.0),
                timeline(),
            )
        assertTrue(batch.discontinuity)
        assertEquals(listOf(10.0, 11.0, 12.0), batch.fixes.map { it.acquisitionMonotonic })
        batch.fixes.forEach { assertTrue(batchClock.admit(it)) }
        val duplicate = assertNotNull(batchClock.map(original(12000000), receipt(22000000, 22.0), timeline()))
        assertFalse(batchClock.admit(duplicate))
        assertEquals(12.0, duplicate.acquisitionMonotonic)
    }

    @Test
    fun gpsClockBarrierFixtures() {
        for (row in gpsFixture("clock")["cases"].list()) {
            val clock = GPSAcquisition()
            if (row["prime"] == J.Bool(true))
                assertNotNull(clock.map(original(1000000000), receipt(1000000000, 1000.0), timeline()))
            val fix =
                assertNotNull(
                    clock.map(
                        original(row["receiptUs"].long()),
                        receipt(row["receiptUs"].long(), row["uptime"].double()),
                        timeline(),
                    )
                )
            assertEquals(row["uncertain"].bool(), fix.uncertain, row["id"].text())
        }
        val clock = GPSAcquisition()
        assertNotNull(clock.map(original(1000000000), receipt(1000000000, 1000.0), timeline()))
        assertTrue(assertNotNull(clock.map(original(1011000001), receipt(1011000001, 1010.0), timeline())).uncertain)
        assertTrue(assertNotNull(clock.map(original(1026000001), receipt(1026000001, 1025.0), timeline())).uncertain)
        assertFalse(
            assertNotNull(clock.map(original(1026000002), receipt(1026000002, 1025.000001), timeline())).uncertain
        )
    }

    @Test
    fun gpsEpochAndRetainedDeduplication() {
        val clock = GPSAcquisition()
        val first = assertNotNull(clock.map(original(10000000), receipt(20000000, 20.0), timeline()))
        assertTrue(clock.admit(first))
        val replay = assertNotNull(clock.map(original(10000000), receipt(20000000, 20.0), timeline("next")))
        assertFalse(clock.admit(replay))
        assertFalse(clock.admit(first))
        val new = assertNotNull(clock.map(original(9000000), receipt(20000000, 20.0), timeline("next")))
        assertTrue(clock.admit(new))
        val freshRide = GPSAcquisition()
        val freshFix = assertNotNull(freshRide.map(original(10000000), receipt(20000000, 20.0), timeline()))
        assertFalse(freshRide.admit(first))
        assertTrue(freshRide.admit(freshFix))
        val uncertain =
            assertNotNull(clock.map(original(21000000), receipt(21000000, 21.0), timeline("next", uncertain = true)))
        assertTrue(uncertain.uncertain)
    }

    @Test
    fun gpsRejectedBatchAndDeduplicationBound() {
        val rejectedClock = GPSAcquisition()
        assertFalse(
            assertNotNull(rejectedClock.map(original(1000000000), receipt(1000000000, 1000.0), timeline())).uncertain
        )
        assertNull(rejectedClock.map(original(2000000000, Double.NaN), receipt(2000000000, 1010.0), timeline()))
        assertFalse(
            assertNotNull(rejectedClock.map(original(1020000000), receipt(1020000000, 1020.0), timeline())).uncertain
        )
        val clock = GPSAcquisition()
        val rejected =
            clock.mapBatch(
                listOf(original(10000000, Double.NaN), original(10000000, accuracy = Double.POSITIVE_INFINITY)),
                receipt(20000000, 20.0),
                timeline(),
            )
        assertTrue(rejected.discontinuity)
        assertTrue(rejected.fixes.isEmpty())
        assertNull(clock.map(original(10000000), receipt(20000000, Double.NaN), timeline()))
        repeat(257) { i ->
            val fix = assertNotNull(clock.map(original(i * 1000000L), receipt(i * 1000000L, i.toDouble()), timeline()))
            assertTrue(clock.admit(fix))
        }
        val epoch = timeline("second")
        assertTrue(clock.admit(assertNotNull(clock.map(original(0), receipt(10000000, 10.0), epoch))))
        assertFalse(clock.admit(assertNotNull(clock.map(original(2000000), receipt(10000000, 10.0), epoch))))
    }

    @Test
    fun elapsedMappingPreservesAcquisitionThenOriginEvaluation() {
        val fix = GPSAcquisition().map(original(5000000), receipt(20000000, 20.1), timeline(origin = 0.1))!!
        assertEquals(5.000000000000002, fix.elapsedSeconds)
        assertEquals(5000000, fix.elapsedUs)
    }
}
