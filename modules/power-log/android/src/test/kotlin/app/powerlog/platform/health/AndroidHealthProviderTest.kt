package app.powerlog.platform.health

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.meters
import app.powerlog.application.port.Clock
import app.powerlog.application.port.HealthAvailability
import app.powerlog.application.port.HealthGrants
import app.powerlog.application.port.HealthOriginalPage
import app.powerlog.application.port.HealthScope
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthQuantity
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.core.time.UtcRange
import app.powerlog.domain.health.HealthConnectRecordPlan
import app.powerlog.domain.health.HealthPlan
import app.powerlog.domain.health.RoutePoint
import app.powerlog.platform.ui.AndroidUiContext
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidHealthProviderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val client = FakeClient()
    private val ui = FakeUi()
    private val clock = FixedClock(1_788_679_800_000_000L)
    private val preferences = context.getSharedPreferences("health-access-test", Context.MODE_PRIVATE)
    private val provider =
        AndroidHealthProvider(
            client,
            context,
            preferences,
            ui,
            clock,
            "app.powerlog",
            "Power Log",
            Dispatchers.Unconfined,
        )

    @Test
    fun availabilityFollowsTheSdkStatus() {
        assertEquals(HealthAvailability.available, provider.access.availability())
        client.status = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        assertEquals(HealthAvailability.updateRequired, provider.access.availability())
        client.status = HealthConnectClient.SDK_UNAVAILABLE
        assertEquals(HealthAvailability.unsupported, provider.access.availability())
        assertEquals(PortErrorKind.unsupported, failure<HealthGrants> { provider.access.grants(it) }.kind)
    }

    @Test
    fun theFirstGrantStartsTheReadableWindowAndEmptyGrantsClearIt() {
        client.grants =
            setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class), read(HeartRateRecord::class))
        val first = answer<HealthGrants> { provider.access.grants(it) }
        assertEquals(PermissionState.granted, first.write[HealthComponent.workout])
        assertEquals(PermissionState.granted, first.write[HealthComponent.laps])
        assertEquals(PermissionState.denied, first.write[HealthComponent.riderPower])
        assertEquals(PermissionState.denied, first.route)
        assertEquals(PermissionState.granted, first.read["heartRate"])
        assertEquals(PermissionState.denied, first.read["association"])
        assertNull(first.write[HealthComponent.readback])
        assertNull(first.backgroundRead)
        val window = 1_788_679_800_000_000L - 30L * 86_400_000_000L
        assertEquals(window, first.readableFromUtcUs)

        clock.utcUs = 1_788_766_200_000_000L
        assertEquals(window, answer<HealthGrants> { provider.access.grants(it) }.readableFromUtcUs)

        client.grants = emptySet()
        assertNull(answer<HealthGrants> { provider.access.grants(it) }.readableFromUtcUs)
    }

    @Test
    fun backgroundReadIsReportedOnlyWhereTheFeatureExists() {
        client.background = true
        assertEquals(PermissionState.denied, answer<HealthGrants> { provider.access.grants(it) }.backgroundRead)
        client.grants = setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        assertEquals(PermissionState.granted, answer<HealthGrants> { provider.access.grants(it) }.backgroundRead)
    }

    @Test
    fun requestShowsHealthConnectsScreenForTheScopeAndAnswersWhenItCloses() {
        val scope =
            HealthScope(
                write = setOf(HealthComponent.workout, HealthComponent.riderPower),
                read = setOf("heartRate", "association"),
                route = true,
                backgroundRead = true,
            )
        assertEquals(PortErrorKind.uiUnavailable, failure<Unit> { provider.access.request(scope, it) }.kind)

        ui.activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val answers = mutableListOf<Unit>()
        provider.access.request(scope, Reply({ answers += it }, { throw AssertionError(it.toString()) }))
        val expected =
            setOf(
                HealthPermission.getWritePermission(ExerciseSessionRecord::class),
                HealthPermission.getWritePermission(PowerRecord::class),
                read(HeartRateRecord::class),
                read(ExerciseSessionRecord::class),
                HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
            )
        assertTrue(ui.started.isEmpty())
        assertEquals(expected, ui.asked.single().toSet())
        assertTrue(answers.isEmpty())

        val held = CompletableDeferred<Unit>()
        client.hold = held
        provider.access.grants(Reply({}, {}))
        ui.answer(expected.associateWith { true })
        assertEquals(listOf(Unit), answers)
        client.hold = null
        held.complete(Unit)

        client.grants = expected
        val grants = answer<HealthGrants> { provider.access.grants(it) }
        assertEquals(PermissionState.granted, grants.write[HealthComponent.riderPower])
        assertEquals(PermissionState.granted, grants.route)
    }

    @Test
    @Config(sdk = [32])
    fun belowAndroid14TheRequestStartsHealthConnectsOwnScreen() {
        ui.activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scope =
            HealthScope(
                write = setOf(HealthComponent.workout),
                read = emptySet(),
                route = false,
                backgroundRead = false,
            )
        val answers = mutableListOf<Unit>()
        provider.access.request(scope, Reply({ answers += it }, { throw AssertionError(it.toString()) }))
        val expected = setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
        val intent = ui.started.single()
        assertTrue(
            intent.filterEquals(
                PermissionController.createRequestPermissionResultContract().createIntent(ui.activity!!, expected)
            )
        )
        assertTrue(ui.asked.isEmpty())
        ui.finish(Activity.RESULT_OK)
        assertEquals(listOf(Unit), answers)
    }

    @Test
    fun aReadPagesBy128AndGivesSeriesMembersSingleRecordsAndSessionsAsAssociations() {
        val start = Instant.ofEpochSecond(1_788_679_800)
        client.page =
            HealthConnectPage(
                listOf(
                    HeartRateRecord(
                        start,
                        ZoneOffset.UTC,
                        start.plusSeconds(2),
                        ZoneOffset.UTC,
                        listOf(HeartRateRecord.Sample(start, 120), HeartRateRecord.Sample(start.plusSeconds(1), 121)),
                        ReceivedMetadata.of("A1B2", "com.watch", null),
                    )
                ),
                "token-2",
            )
        val page =
            answer<HealthOriginalPage> {
                provider.reader.read("heartRate", 1_788_679_800_000_000L, 1_788_683_400_000_000L, "token-1", it)
            }
        assertEquals(listOf(Triple(HeartRateRecord::class, "token-1", 1_788_679_800_000_000L)), client.reads)
        assertEquals("token-2", page.next)
        assertEquals(listOf(0, 1), page.samples.map { it.member })
        assertEquals(listOf(120.0, 121.0), page.samples.map { it.value })
        assertEquals(1_788_679_801_000_000L, page.samples[1].startUtcUs)
        assertEquals("a1b2", page.samples[0].sampleId)
        assertEquals("com.watch", page.samples[0].dataOrigin)

        client.page =
            HealthConnectPage(
                listOf(
                    ExerciseSessionRecord(
                        start,
                        ZoneOffset.UTC,
                        start.plusSeconds(60),
                        ZoneOffset.UTC,
                        ReceivedMetadata.of("s1", "com.other", "their:id"),
                        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                    )
                ),
                "",
            )
        val sessions = answer<HealthOriginalPage> { provider.reader.read("association", 0, 1, null, it) }
        assertNull(sessions.next)
        val association = sessions.samples.single()
        assertEquals("association", association.metric)
        assertNull(association.member)
        assertNull(association.value)
        assertEquals("their:id", association.syncId)
        assertEquals(1_788_679_860_000_000L, association.endUtcUs)

        assertEquals(
            PortErrorKind.invalid,
            failure<HealthOriginalPage> { provider.reader.read("steps", 0, 1, null, it) }.kind,
        )
    }

    @Test
    fun aCallWhileAnotherRunsIsBusyAtOnceAlsoAfterTheCallerGaveUp() {
        val held = CompletableDeferred<Unit>()
        client.hold = held
        val first = mutableListOf<HealthGrants>()
        provider.access.grants(Reply({ first += it }, {}))
        assertEquals(
            PortErrorKind.busy,
            failure<HealthOriginalPage> { provider.reader.read("heartRate", 0, 1, null, it) }.kind,
        )
        assertEquals(PortErrorKind.busy, failure<List<String>> { provider.writer.insert(listOf(power(0)), it) }.kind)

        client.hold = null
        held.complete(Unit)
        assertEquals(1, first.size)
        answer<HealthGrants> { provider.access.grants(it) }
    }

    @Test
    fun platformFailuresMapToPortErrors() {
        val cases =
            listOf(
                SecurityException("no permission") to PortErrorKind.denied,
                IllegalStateException("Rate limit exceeded") to PortErrorKind.rateLimited,
                RemoteException("over quota") to PortErrorKind.rateLimited,
                IllegalArgumentException("bad record") to PortErrorKind.invalid,
                RemoteException("binder died") to PortErrorKind.failed,
                java.io.IOException("io") to PortErrorKind.failed,
                IllegalStateException("closed") to PortErrorKind.failed,
            )
        for ((error, kind) in cases) {
            client.error = error
            val failure = failure<HealthGrants> { provider.access.grants(it) }
            assertEquals(error.toString(), kind, failure.kind)
            assertEquals("HealthConnect", failure.platform?.domain)
        }
    }

    @Test
    fun insertWritesOneRecordPerPlanAndAnswersTheIdsInInputOrder() {
        client.ids = { records -> records.indices.map { "id-$it" } }
        val plans = listOf(power(0), distance(), speed())
        assertEquals(listOf("id-0", "id-1", "id-2"), answer<List<String>> { provider.writer.insert(plans, it) })
        val (power, distance, speed) = client.inserted.single()
        power as PowerRecord
        assertEquals("powerlog:r:rider-power:second:0", power.metadata.clientRecordId)
        assertEquals(1L, power.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, power.metadata.recordingMethod)
        assertEquals(Device.TYPE_UNKNOWN, power.metadata.device?.type)
        assertEquals(ZoneOffset.UTC, power.startZoneOffset)
        assertEquals(Instant.ofEpochMilli(1_788_679_800_000L), power.startTime)
        assertEquals(power.startTime, power.samples.single().time)
        assertEquals(250.0, power.samples.single().power.inWatts, 0.0)
        assertEquals(Device.TYPE_PHONE, (distance as DistanceRecord).metadata.device?.type)
        assertEquals(12.5, distance.distance.inMeters, 0.0)
        assertEquals(Device.TYPE_PHONE, (speed as SpeedRecord).metadata.device?.type)
    }

    @Test
    fun aSessionCarriesItsLapsPausesAndRouteAndARecordEmptyAtAMillisecondIsSkipped() {
        val session = session(laps = 2, pauses = 1, points = 3)
        val empty =
            HealthConnectRecordPlan.Record(
                HealthPlan.Quantity("powerlog:r:x", HealthQuantity.riderPower, 5_000, 5_400, 1.0)
            )
        client.ids = { records -> records.map { (it as? ExerciseSessionRecord)?.let { "workout" } ?: "quantity" } }
        val ids = answer<List<String>> { provider.writer.insert(listOf(power(0), empty, session), it) }
        assertEquals("workout", ids.last())
        val record = client.inserted.single().last() as ExerciseSessionRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, record.exerciseType)
        assertEquals("Power Log ride", record.title)
        assertEquals("powerlog:r:workout", record.metadata.clientRecordId)
        assertEquals(Device.TYPE_PHONE, record.metadata.device?.type)
        assertEquals(2, record.laps.size)
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE, record.segments.single().segmentType)
        val route = (record.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route
        assertEquals(3, route.size)
        assertEquals(5.0.meters, route[0].horizontalAccuracy)
    }

    @Test
    fun aSessionOverItsLimitsIsInvalidBeforeAnyCallAndAShortReceiptFails() {
        for (plan in listOf(session(laps = 1_001), session(pauses = 1_001), session(points = 20_000))) {
            assertEquals(PortErrorKind.invalid, failure<List<String>> { provider.writer.insert(listOf(plan), it) }.kind)
        }
        val zeroLap =
            session().let {
                it.copy(plan = it.plan.copy(laps = listOf(UtcRange(it.plan.startUtcUs, it.plan.startUtcUs + 10))))
            }
        assertEquals(PortErrorKind.invalid, failure<List<String>> { provider.writer.insert(listOf(zeroLap), it) }.kind)
        val instant =
            session().let { it.copy(plan = it.plan.copy(endUtcUs = it.plan.startUtcUs + 400, laps = emptyList())) }
        assertEquals(PortErrorKind.invalid, failure<List<String>> { provider.writer.insert(listOf(instant), it) }.kind)
        assertTrue(client.inserted.isEmpty())

        client.ids = { emptyList() }
        assertEquals(PortErrorKind.failed, failure<List<String>> { provider.writer.insert(listOf(power(0)), it) }.kind)
        assertEquals("app.powerlog", provider.writer.packageName)
        assertEquals(HealthProviderKind.healthConnect, provider.kind)
        assertNull(provider.session)
    }

    @Test
    fun theReadbackKeysAreTheDomains() {
        assertEquals(
            HealthMetrics.readback(HealthProviderKind.healthConnect).map { it.wire }.toSet(),
            HealthConnectRecords.readTypes.keys,
        )
    }

    @Test
    fun answersThatNeedNoCallArriveAfterTheCallReturns() {
        val queued = mutableListOf<Runnable>()
        val later =
            AndroidHealthProvider(
                client,
                context,
                preferences,
                ui,
                clock,
                "app.powerlog",
                "Power Log",
                java.util.concurrent.Executor { queued += it }.asCoroutineDispatcher(),
            )
        val failures = mutableListOf<PortErrorKind>()
        later.reader.read("steps", 0, 1, null, Reply({}, { failures += it.kind }))
        later.writer.insert(listOf(session(laps = 1_001)), Reply({}, { failures += it.kind }))
        client.status = HealthConnectClient.SDK_UNAVAILABLE
        later.access.grants(Reply({}, { failures += it.kind }))
        assertTrue(failures.isEmpty())
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        assertEquals(listOf(PortErrorKind.invalid, PortErrorKind.invalid, PortErrorKind.unsupported), failures)
    }

    private fun read(type: KClass<out Record>) = HealthPermission.getReadPermission(type)

    private fun power(n: Long) =
        HealthConnectRecordPlan.Record(
            HealthPlan.Quantity(
                "powerlog:r:rider-power:second:$n",
                HealthQuantity.riderPower,
                1_788_679_800_000_000L + n * 1_000_000L,
                1_788_679_801_000_000L + n * 1_000_000L,
                250.0,
            )
        )

    private fun distance() =
        HealthConnectRecordPlan.Record(
            HealthPlan.Quantity(
                "powerlog:r:d",
                HealthQuantity.cyclingDistance,
                1_788_679_800_000_000L,
                1_788_679_805_000_000L,
                12.5,
            )
        )

    private fun speed() =
        HealthConnectRecordPlan.Record(
            HealthPlan.Quantity(
                "powerlog:r:s",
                HealthQuantity.cyclingSpeed,
                1_788_679_800_000_000L,
                1_788_679_801_000_000L,
                6.0,
            )
        )

    private fun session(laps: Int = 0, pauses: Int = 0, points: Int = 0): HealthConnectRecordPlan.Session {
        val start = 1_788_679_800_000_000L
        return HealthConnectRecordPlan.Session(
            HealthPlan.Workout(
                syncId = "powerlog:r:workout",
                rideId = "r",
                startUtcUs = start,
                endUtcUs = start + 3_600_000_000L,
                indoor = false,
                laps = List(laps) { UtcRange(start + it * 1_000_000L, start + it * 1_000_000L + 500_000L) },
                pauses =
                    List(pauses) {
                        UtcRange(
                            start + 2_000_000_000L + it * 1_000_000L,
                            start + 2_000_000_000L + it * 1_000_000L + 500_000L,
                        )
                    },
            ),
            List(points) { RoutePoint(start + it * 1_000_000L, 52.0, 13.0, 40.0, 5.0, 3.0, null, null) },
        )
    }

    private fun <T> answer(call: (Reply<T>) -> Unit): T {
        val answers = mutableListOf<T>()
        call(Reply({ answers += it }, { throw AssertionError("failed: $it") }))
        return answers.single()
    }

    private fun <T> failure(call: (Reply<T>) -> Unit): PortError {
        val failures = mutableListOf<PortError>()
        call(Reply({ throw AssertionError("answered: $it") }, { failures += it }))
        return failures.single()
    }

    class FixedClock(var utcUs: Long) : Clock {
        override fun monoUs(): Long = 0

        override fun utcUs(): Long = utcUs

        override fun read(): ClockReading = ClockReading(monoUs(), utcUs)
    }

    class FakeUi : AndroidUiContext {
        var activity: Activity? = null
        val started = mutableListOf<Intent>()
        private var done: ((Int?, Intent?) -> Unit)? = null

        override fun activity(): Activity? = activity

        val asked = mutableListOf<List<String>>()
        private var answered: ((Map<String, Boolean>) -> Unit)? = null

        override fun requestPermissions(permissions: List<String>, done: (Map<String, Boolean>) -> Unit): Boolean {
            if (activity == null) return false
            asked += permissions
            answered = done
            return true
        }

        override fun startForResult(intent: Intent, done: (resultCode: Int?, data: Intent?) -> Unit) {
            if (activity == null) return done(null, null)
            started += intent
            this.done = done
        }

        fun finish(resultCode: Int) = requireNotNull(done).invoke(resultCode, null)

        fun answer(results: Map<String, Boolean>) = requireNotNull(answered).invoke(results)
    }

    internal class FakeClient : HealthConnectClientSeam {
        var status = HealthConnectClient.SDK_AVAILABLE
        var background = false
        var grants: Set<String> = emptySet()
        var hold: CompletableDeferred<Unit>? = null
        var error: Throwable? = null
        var page = HealthConnectPage(emptyList(), null)
        var ids: (List<Record>) -> List<String> = { emptyList() }
        val inserted = mutableListOf<List<Record>>()
        val reads = mutableListOf<Triple<KClass<out Record>, String?, Long>>()

        override fun sdkStatus(): Int = status

        override fun backgroundReadAvailable(): Boolean = background

        override suspend fun granted(): Set<String> {
            hold?.await()
            error?.let { throw it }
            return grants
        }

        override suspend fun insert(records: List<Record>): List<String> {
            inserted += records
            return ids(records)
        }

        override suspend fun read(
            type: KClass<out Record>,
            start: Instant,
            end: Instant,
            pageToken: String?,
        ): HealthConnectPage {
            reads += Triple(type, pageToken, HealthConnectRecords.micros(start))
            return page
        }
    }
}
