package app.powerlog.core.analytics

import app.powerlog.core.gps.GPSTimeline
import app.powerlog.core.health.*
import kotlin.test.*

class DistancePageTest {
    private fun controllerPage(count: Int, first: Int = 0): DistanceInputPage {
        val numbers = mutableListOf<Double>()
        val keys = mutableListOf<Long>()
        val text = mutableListOf<String>()
        repeat(count) { index ->
            val time = (first + index) / 8.0
            numbers.addAll(listOf(time, 4.0, time, Double.NaN))
            keys.addAll(listOf(FixedPoint.timeUs(time), 0, first + index.toLong(), 1, 0, 15))
            text.addAll(listOf("X6", "5.3", "device", "connection", "event-${first + index}", "time-$time"))
        }
        return DistanceInputPage(numbers.toDoubleArray(), keys.toLongArray(), text)
    }

    @Test
    fun controllerPagesRetainExactTotalsAndOriginalAnchorsAcrossRestore() {
        val first = ControllerDistanceKernel()
        val a = DistancePages.controller(first, controllerPage(128), 0, 0)
        assertEquals(63500000L, a.totalQ)
        assertEquals(15875000L, a.coveredUs)
        assertEquals("event-127", a.text.last { it.startsWith("event-") })
        val restored = ControllerDistanceKernel(first.checkpoint())
        val b = DistancePages.controller(restored, controllerPage(128, 128), a.totalQ, a.coveredUs)
        assertEquals(127500000L, b.totalQ)
        assertEquals(31875000L, b.coveredUs)
        assertEquals(63.5, b.numbers[5])
        assertFailsWith<IllegalArgumentException> { DistancePages.controller(restored, controllerPage(129), 0, 0) }
    }

    @Test
    fun compactGPSPagesMatchOriginalKernelAndAltitudeCheckpoint() {
        val sequential = GPSKernel()
        val paged = GPSKernel()
        var total = 0L
        var coverage = 0L
        for (first in listOf(0, 4)) {
            val numbers = mutableListOf<Double>()
            val keys = mutableListOf<Long>()
            val text = mutableListOf<String>()
            for (i in first until first + 4) {
                val time = i.toDouble()
                val latitude = i * 0.00001
                val altitude = 100.0 + i * 4
                val values =
                    mapOf(
                        "latitude" to Scalar.Real64(latitude),
                        "longitude" to Scalar.Real64(0.0),
                        "horizontalAccuracyM" to Scalar.Real64(3.0),
                        "altitudeMeters" to Scalar.Real64(altitude),
                        "verticalAccuracyM" to Scalar.Real64(2.0),
                    )
                val item =
                    AnalysisItem(
                        i * 1000000L,
                        time,
                        "e$i",
                        "o$i",
                        "phone",
                        i + 1L,
                        "location",
                        "phone",
                        "t$i",
                        values,
                        continuity = Continuity(clockEpoch = "epoch"),
                    )
                sequential.append(GPSFix(item, 0))
                numbers.addAll(listOf(time, latitude, 0.0, 3.0, Double.NaN, Double.NaN, altitude, 2.0, Double.NaN))
                keys.addAll(listOf(i * 1000000L, 0, i + 1L, 0, 1))
                text.addAll(listOf("e$i", "t$i", "o$i", "phone", "epoch"))
            }
            val result =
                DistancePages.gps(
                    paged,
                    DistanceInputPage(numbers.toDoubleArray(), keys.toLongArray(), text),
                    total,
                    coverage,
                )
            total = result.totalQ
            coverage = result.coveredUs
            assertEquals(sequential.distanceQ(), total)
            assertEquals(sequential.coveredUs(), coverage)
            assertEquals(sequential.distanceCheckpoint(), paged.distanceCheckpoint())
        }
        assertEquals(28000L, paged.ascentQ())
    }

    @Test
    fun pageReductionUsesBorrowedBuffersWithoutMaterializingArrays() {
        val input = controllerPage(128)
        var numericReads = 0
        val numbers =
            object : DistanceNumbers {
                override val size = input.numbers.size

                override fun get(index: Int): Double {
                    numericReads++
                    return input.numbers[index]
                }

                override fun set(index: Int, value: Double) = error("read-only")

                override fun array(count: Int): DoubleArray = error("input was copied")
            }
        val keys =
            object : DistanceKeys {
                override val size = input.keys.size

                override fun get(index: Int) = input.keys[index]

                override fun set(index: Int, value: Long) = error("read-only")

                override fun array(count: Int): LongArray = error("input was copied")
            }
        val borrowed = DistanceInputPage(numbers, keys, input.text)
        val output = DistancePages.controller(ControllerDistanceKernel(), borrowed, 0, 0)
        assertEquals(63500000L, output.totalQ)
        assertTrue(numericReads > 0)
    }

    @Test
    fun originIndexKeyHasTheCompleteCoreOrder() {
        val origins =
            listOf("", "a", "aa", "A", "é", "\u0000", "f0000000-0000-4000-8000-000000000001").flatMap { data ->
                listOf(1L, 2L, 10L, Long.MAX_VALUE).map { sequence ->
                    Origin(
                        Provider.appleHealth,
                        data,
                        null,
                        "a0000000-0000-4000-8000-000000000002",
                        Source.phone,
                        sequence,
                    )
                }
            }
        for (a in origins) for (b in origins) {
            assertEquals(
                compareOrigins(a, b).compareTo(0),
                HealthOriginOrder.key(a).compareTo(HealthOriginOrder.key(b)).compareTo(0),
            )
        }
    }

    @Test
    fun availableOrderPreservesOwnerAndIndoorPreference() {
        val profiles =
            listOf("gps:watch", "gps:phone", "controller").map { DistanceProfile(it, it, false, false, 1000000, 0, 0) }
        assertEquals(
            listOf("gps:phone", "gps:watch", "controller"),
            DistancePolicy.orderAvailable(profiles, false, false).map { it.source },
        )
        assertEquals(
            listOf("controller", "gps:watch", "gps:phone"),
            DistancePolicy.orderAvailable(profiles, true, true).map { it.source },
        )
    }

    @Test
    fun healthIsNoDistanceSelectionOrSpeedMetric() {
        for (selection in listOf("health:watch", "health:phone")) {
            assertFalse(DistancePolicy.validSelection(selection))
            assertFailsWith<IllegalArgumentException> { selectDistance(selection, emptyList(), false, false) }
        }
        assertTrue(app.powerlog.core.telemetry.MetricCatalog.all.none { it.id == "healthSpeedMps" })
        assertFailsWith<IllegalArgumentException> { Metrics.policy("healthSpeedMps") }
    }

    @Test
    fun acquisitionBoundaryAccessorRejectsOtherEpochsAndClosedBoundaries() {
        val timeline = GPSTimeline("epoch", 0, 100.0, 102.0, 110.0)
        assertNull(timeline.elapsedForAcquisition("epoch", 101.0))
        assertEquals(2.0, timeline.elapsedForAcquisition("epoch", 102.0))
        assertEquals(10.0, timeline.elapsedForAcquisition("epoch", 110.0))
        assertNull(timeline.elapsedForAcquisition("epoch", 110.000001))
        assertNull(timeline.elapsedForAcquisition("other", 105.0))
        assertNull(timeline.elapsedForAcquisition("epoch", Double.NaN))
    }
}
