package app.powerlog.domain.health

import app.powerlog.domain.analytics.fixture
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.time.UtcRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteReductionTest {
    private fun point(second: Long, accuracyM: Double = 4.0, latitude: Double = 40.0, offsetUs: Long = 0) =
        RoutePoint(
            1_788_706_200_000_000 + second * 1_000_000 + offsetUs,
            latitude,
            -3.5 + second * 1e-5,
            600.0,
            accuracyM,
            3.0,
            90.0,
            6.0,
        )

    private val ride: List<RoutePoint> =
        (0L until 2_000L).map { second ->
            when {
                second % 97 == 0L -> point(second, accuracyM = 60.0)
                second % 113 == 0L -> point(second - 1)
                else -> point(second)
            }
        }

    @Test
    fun offeringPointsOneByOneAndAppendingThemAtOnceKeepTheSameRoute() {
        val offered = RouteReduction.Apple(64)
        ride.forEach { offered.offer(it) }
        val appended = RouteReduction.Apple(64)
        appended.append(ride)
        assertEquals(offered.result(), appended.result())
        assertEquals(offered.stride, appended.stride)
        assertTrue(offered.result().size <= 64)
        assertEquals(ride.last(), offered.result().last())
        assertEquals(offered.result().sortedBy { it.utcUs }, offered.result())
    }

    @Test
    fun pointsStreamedOnTheNewestStrideAreTheRoute() {
        val reduction = RouteReduction.Apple(64)
        val watch = mutableListOf<IndexedRoutePoint>()
        for (point in ride) {
            val index = reduction.offer(point) ?: continue
            if (index % reduction.stride == 0L) watch += IndexedRoutePoint(index, point)
            watch.retainAll { it.index % reduction.stride == 0L }
        }
        val latest = requireNotNull(reduction.latest)
        val received =
            watch.map { it.point } + if (watch.last().index == latest.index) emptyList() else listOf(latest.point)
        assertEquals(reduction.result(), received)
    }

    @Test
    fun theAppleRouteAcceptsAccuratePointsInTimeOrderAndThinsByDoublingItsStride() {
        val reduction = RouteReduction.Apple(4)
        assertNull(reduction.offer(point(0, accuracyM = 51.0)))
        assertNull(reduction.offer(point(0, accuracyM = -1.0)))
        assertEquals(0, reduction.offer(point(0, accuracyM = 50.0)))
        assertNull(reduction.offer(point(0)))
        assertEquals(1, reduction.offer(point(1)))
        assertEquals(2, reduction.offer(point(2)))
        assertEquals(3, reduction.offer(point(3)))
        assertEquals(1, reduction.stride)
        assertEquals(4, reduction.offer(point(4)))
        assertEquals(2, reduction.stride)
        assertEquals(listOf(0L, 2L, 4L).map { point(it, accuracyM = if (it == 0L) 50.0 else 4.0) }, reduction.result())
        assertEquals(5, reduction.offer(point(5)))
        assertEquals(IndexedRoutePoint(5, point(5)), reduction.latest)
        assertEquals(listOf(point(0, accuracyM = 50.0), point(2), point(4), point(5)), reduction.result())
        assertEquals(emptyList(), RouteReduction.Apple(4).result())
        assertFailsWith<IllegalArgumentException> { RouteReduction.Apple(3) }
    }

    @Test
    fun theHealthConnectRouteKeepsItsPriorityPointsWithinTheRecordBudget() {
        val route = fixture("health/route.json")
        val cases = route["cases"].list().associateBy { it["name"].text() }
        val nine = cases.getValue("nested-nine")
        val nested =
            RouteReduction.HealthConnect(nine["input"]["baseBytes"].long(), nine["input"]["budgetBytes"].long())
        val points = (0L until nine["input"]["pointCount"].long()).map { point(it) }
        nested.append(points)
        assertEquals(nine["expected"]["selectedIndices"].list().map { points[it.long().toInt()] }, nested.result())
        for (name in listOf("extra-laps-pause", "lab-encoding-golden", "singleton")) {
            val input = cases.getValue(name)["input"]
            val reduction =
                RouteReduction.HealthConnect(
                    input["baseBytes"].long(),
                    input["budgetBytes"].long(),
                )
            val all = (0L until input["eligibleCount"].long()).map { point(it) }
            reduction.append(all.take(5_000))
            reduction.append(all.drop(5_000))
            val result = reduction.result()
            val expected = cases.getValue(name)["expected"]
            val count =
                expected["selectedCount"].let {
                    if (it == app.powerlog.domain.analytics.J.Null) 1 else it.long().toInt()
                }
            assertEquals(count, result.size, name)
            assertEquals(all.first(), result.first(), name)
            assertEquals(all.last(), result.last(), name)
        }
        val tooLarge = cases.getValue("mandatory-too-large")["input"]
        val none = RouteReduction.HealthConnect(tooLarge["baseBytes"].long(), tooLarge["budgetBytes"].long())
        none.append(listOf(point(0), point(1)))
        assertEquals(emptyList(), none.result())
    }

    @Test
    fun theHealthConnectBaseBytesCountTheSessionWithoutItsRoute() {
        val strings = fixture("health/route.json")["stringBytes"]
        val start = 1_788_706_200_000_000L
        val workout =
            HealthPlan.Workout(
                syncId = HealthIds.workout(RideId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")),
                rideId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                startUtcUs = start,
                endUtcUs = start + 60_000_000,
                indoor = false,
                laps = listOf(UtcRange(start, start + 60_000_000)),
                pauses = emptyList(),
            )
        val named =
            listOf("providerUUIDReserve", "packageName", "appName", "clientRecordId", "title").sumOf {
                strings[it].long()
            }
        val absent = listOf("manufacturer", "model", "notes", "plannedExerciseSessionId").size * 4L
        assertEquals(
            76 + 24 + named + absent,
            RouteReduction.HealthConnect.baseBytes(workout, "app.powerlog.mobile", "Power Log"),
        )
        val paused = workout.copy(pauses = listOf(UtcRange(start + 1_000_000, start + 2_000_000)))
        assertEquals(
            76 + 48 + named + absent,
            RouteReduction.HealthConnect.baseBytes(paused, "app.powerlog.mobile", "Power Log"),
        )
    }

    @Test
    fun theHealthConnectBaseBytesCountTheSessionsDevice() {
        val start = 1_788_706_200_000_000L
        val workout =
            HealthPlan.Workout("powerlog:r:workout", "r", start, start + 60_000_000, false, emptyList(), emptyList())
        val bare = RouteReduction.HealthConnect.baseBytes(workout, "app.powerlog.mobile", "Power Log")
        assertEquals(
            bare - 8 + HealthRoutes.stringBytes("samsung") + HealthRoutes.stringBytes("SM-S901B"),
            RouteReduction.HealthConnect.baseBytes(workout, "app.powerlog.mobile", "Power Log", "samsung", "SM-S901B"),
        )
    }

    @Test
    fun aStoredFixEntersTheRouteWithItsSeaLevelAltitude() {
        val fix =
            LocationFix(
                rideId = RideId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                seq = 1,
                elapsedUs = 1_000_000,
                utcUs = 1_788_706_201_000_000,
                activeInterval = 1,
                run = 1,
                barrier = false,
                latitude = 40.0,
                longitude = -3.5,
                horizontalAccuracyM = 4.0,
                altitudeM = 652.0,
                altitudeDatum = AltitudeDatum.wgs84Ellipsoid,
                verticalAccuracyM = 6.0,
                mslM = 600.0,
                mslVerticalAccuracyM = 3.0,
                speedMps = 6.0,
                speedAccuracyMps = 0.5,
                courseDeg = 90.0,
                courseAccuracyDeg = 5.0,
            )
        assertEquals(
            RoutePoint(1_788_706_201_000_000, 40.0, -3.5, 600.0, 4.0, 3.0, 90.0, 6.0),
            RoutePoint.of(fix),
        )
        assertNull(RoutePoint.of(fix.copy(horizontalAccuracyM = null)))
    }

    @Test
    fun theHealthConnectRouteTakesOneAccuratePointPerMillisecond() {
        val reduction = RouteReduction.HealthConnect(440, 900_000)
        reduction.append(
            listOf(
                point(0),
                point(1),
                point(1, offsetUs = 999),
                point(1, offsetUs = 1_000),
                point(2, accuracyM = 50.5),
                point(3, latitude = 91.0),
                point(4, accuracyM = 50.0),
            )
        )
        reduction.append(listOf(point(3)))
        assertEquals(
            listOf(point(0), point(1), point(1, offsetUs = 1_000), point(4, accuracyM = 50.0)),
            reduction.result(),
        )
    }
}
