package app.powerlog.core.analytics

import kotlin.test.Test
import kotlin.test.assertEquals

class DistanceCheckpointTest {
    @Test
    fun intervalReadBoundsMatchKernelAdmission() {
        assertEquals(6000000L, DistancePolicy.maximumControllerEdgeUs)
        assertEquals(10000000L, DistancePolicy.maximumGPSIntervalUs)
        assertEquals(true, DistancePolicy.edge(DistancePolicy.maximumGPSIntervalUs, 0.0))
        assertEquals(false, DistancePolicy.edge(DistancePolicy.maximumGPSIntervalUs + 1, 0.0))
        fun evidence(time: Long) =
            ControllerDistanceEvidence(
                time,
                time / 1000000.0,
                5.0,
                "X6",
                "5.3",
                "fixture",
                "fixture",
                0,
                null,
                null,
                false,
                Original("o$time", "e$time", "2026-01-01T00:00:00Z", time / 1000000.0, Scalar.Real64(5.0)),
            )
        for (duration in listOf(DistancePolicy.maximumControllerEdgeUs - 1, DistancePolicy.maximumControllerEdgeUs)) {
            val kernel = ControllerDistanceKernel()
            kernel.append(evidence(0))
            assertEquals(duration < DistancePolicy.maximumControllerEdgeUs, kernel.append(evidence(duration)) != null)
        }
    }

    @Test
    fun shortReconnectJoinsOnlyTheSameControllerInOneActiveInterval() {
        fun evidence(time: Long, identity: String = "bike", connection: String = "first", active: Int = 0) =
            ControllerDistanceEvidence(
                time,
                time / 1000000.0,
                5.0,
                "X6",
                "5.3",
                identity,
                connection,
                active,
                null,
                null,
                false,
                Original("o$time", "e$time", "2026-01-01T00:00:00Z", time / 1000000.0, Scalar.Real64(5.0)),
            )
        fun join(next: ControllerDistanceEvidence): Pair<DistanceInterval, DistanceInterval?> {
            val kernel = ControllerDistanceKernel()
            kernel.append(evidence(0))
            return requireNotNull(kernel.append(evidence(1000000))) to kernel.append(next)
        }
        val (before, next) = join(evidence(5000000, connection = "second"))
        val joined = requireNotNull(next)
        assertEquals(
            DistanceInterval(
                1000000,
                5000000,
                20000000,
                before.segment,
                0,
                before.end,
                joined.end,
                5000000,
                5000000,
                originalMeters = 20.0,
            ),
            joined,
        )
        assertEquals(null, join(evidence(5000000, identity = "other", connection = "second")).second)
        assertEquals(null, join(evidence(5000000, active = 1)).second)
    }

    @Test
    fun restoredCheckpointPreservesClearedPredecessorAndTotals() {
        fun evidence(time: Long) =
            ControllerDistanceEvidence(
                time,
                time / 1000000.0,
                5.0,
                "X6",
                "5.3",
                "bike",
                "run",
                0,
                null,
                null,
                false,
                Original(
                    "o$time",
                    "e$time",
                    "2026-01-01T00:00:00Z",
                    time / 1000000.0,
                    Scalar.Real64(5.0),
                ),
            )
        val kernel = ControllerDistanceKernel()
        kernel.append(evidence(0))
        val interval = requireNotNull(kernel.append(evidence(1000000)))
        val totals = DistanceReducer(AnalysisRange(0, 3000000))
        totals.append(interval)
        val first = totals.result(3000000)
        val restored = ControllerDistanceKernel(kernel.checkpoint())
        val restoredTotals = DistanceReducer(AnalysisRange(0, 3000000), first)
        val next = requireNotNull(restored.append(evidence(2000000)))
        restoredTotals.append(next)
        totals.append(requireNotNull(kernel.append(evidence(2000000))))
        assertEquals(totals.result(3000000), restoredTotals.result(3000000))
        restored.append(evidence(2000000))
        val duplicate = ControllerDistanceKernel(restored.checkpoint())
        assertEquals(null, duplicate.append(evidence(3000000)))
    }

    @Test
    fun restoredGPSPredecessorsPreserveDistanceIntervalsAndBarriers() {
        val kernel = GPSKernel()
        for ((index, time) in listOf(0L, 1000000L, 1000000L, 2000000L, 3000000L, 4000000L).withIndex()) {
            val item =
                AnalysisItem(
                    time,
                    time / 1000000.0,
                    "e$index",
                    "o$index",
                    "browser",
                    index + 1L,
                    "location",
                    "phone",
                    "2026-01-01T00:00:00Z",
                    mapOf(
                        "latitude" to Scalar.Real64(0.0),
                        "longitude" to Scalar.Real64(index * 0.00001),
                        "horizontalAccuracyM" to Scalar.Real64(if (index == 3) 100.0 else 5.0),
                    ),
                    continuity = Continuity(clockEpoch = if (index < 5) "first" else "second"),
                )
            val restored = GPSKernel()
            restored.restoreDistanceCheckpoint(kernel.distanceCheckpoint())
            val fix = GPSFix(item, 0)
            assertEquals(kernel.append(fix), restored.append(fix))
        }
    }

    @Test
    fun restoredGPSCheckpointRetainsAltitudeAnchorSumsAndCounters() {
        fun fix(index: Int, altitude: Double) =
            GPSFix(
                AnalysisItem(
                    index * 1000000L,
                    index.toDouble(),
                    "e$index",
                    "o$index",
                    "phone",
                    index + 1L,
                    "location",
                    "phone",
                    "2026-01-01T00:00:00Z",
                    mapOf(
                        "latitude" to Scalar.Real64(0.0),
                        "longitude" to Scalar.Real64(index * 0.00001),
                        "horizontalAccuracyM" to Scalar.Real64(2.0),
                        "altitudeMeters" to Scalar.Real64(altitude),
                        "verticalAccuracyM" to Scalar.Real64(2.0),
                    ),
                ),
                0,
            )
        val uninterrupted = GPSKernel()
        var restored = GPSKernel()
        for ((index, altitude) in listOf(100.0, 102.0, 105.0, 103.0, 100.0).withIndex()) {
            assertEquals(
                uninterrupted.append(fix(index, altitude)),
                restored.append(fix(index, altitude)),
            )
            val next = GPSKernel()
            next.restoreDistanceCheckpoint(restored.distanceCheckpoint())
            restored = next
            assertEquals(uninterrupted.distanceQ(), restored.distanceQ())
            assertEquals(uninterrupted.coveredUs(), restored.coveredUs())
            assertEquals(uninterrupted.ascentQ(), restored.ascentQ())
            assertEquals(uninterrupted.descentQ(), restored.descentQ())
            assertEquals(uninterrupted.altitudeSegments, restored.altitudeSegments)
            assertEquals(uninterrupted.acceptedIntervals, restored.acceptedIntervals)
            assertEquals(uninterrupted.rejected, restored.rejected)
        }
        assertEquals(5000L, restored.ascentQ())
        assertEquals(5000L, restored.descentQ())
    }

    @Test
    fun plotAdmissionPreservesRawPowerAndChecksCourseAndSpeedQuality() {
        val item =
            AnalysisItem(
                0,
                0.0,
                "e",
                "o",
                "phone",
                1,
                "location",
                "phone",
                "2026-01-01T00:00:00Z",
                mapOf("speedMps" to Scalar.Real64(41.0), "courseDegrees" to Scalar.Real64(359.0)),
            )
        assertEquals(true, Metrics.plotValid(item, "speedMps"))
        assertEquals(
            false,
            Metrics.plotValid(
                item.copy(values = item.values + ("speedAccuracyMps" to Scalar.Real64(-1.0))),
                "speedMps",
            ),
        )
        assertEquals(
            false,
            Metrics.plotValid(
                item.copy(values = item.values + ("courseAccuracyDegrees" to Scalar.Real64(-1.0))),
                "courseDegrees",
            ),
        )
        assertEquals(
            false,
            Metrics.plotValid(
                item.copy(values = item.values + ("courseDegrees" to Scalar.Real64(360.0))),
                "courseDegrees",
            ),
        )
        val power = item.copy(kind = "telemetry", values = mapOf("humanPowerW" to Scalar.Real64(-1.0)))
        assertEquals(true, Metrics.plotValid(power, "humanPowerW"))
        assertEquals(false, Metrics.policy("humanPowerW").valid(power.values["humanPowerW"]))
    }

    @Test
    fun intervalRetainsOriginalMetresBesideQuantizedDistance() {
        fun evidence(time: Long) =
            ControllerDistanceEvidence(
                time,
                time / 1000000.0,
                0.0078125,
                "X6",
                "5.3",
                "bike",
                "run",
                0,
                null,
                null,
                false,
                Original(
                    "o$time",
                    "e$time",
                    "2026-01-01T00:00:00Z",
                    time / 1000000.0,
                    Scalar.Real64(0.0078125),
                ),
            )
        val controller = ControllerDistanceKernel()
        controller.append(evidence(0))
        val edge = requireNotNull(controller.append(evidence(2000000)))
        assertEquals(15624L, edge.distanceQ)
        assertEquals(0.015625, edge.originalMeters)
        val gps = GPSKernel()
        fun fix(time: Long, longitude: Double) =
            GPSFix(
                AnalysisItem(
                    time,
                    time / 1000000.0,
                    "e$time",
                    "o$time",
                    "phone",
                    time + 1,
                    "location",
                    "phone",
                    "2026-01-01T00:00:00Z",
                    mapOf(
                        "latitude" to Scalar.Real64(0.0),
                        "longitude" to Scalar.Real64(longitude),
                        "horizontalAccuracyM" to Scalar.Real64(1.0),
                    ),
                ),
                0,
            )
        gps.append(fix(0, 0.0))
        val chord = requireNotNull(gps.append(fix(1000000, 0.000001)).interval)
        assertEquals(PureMath.haversine(0.0, 0.0, 0.0, 0.000001), chord.originalMeters)
    }
}
