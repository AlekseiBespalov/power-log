package app.powerlog.application.store

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

abstract class ArchiveContract {
    protected abstract val harness: ArchiveHarness

    protected val storesHealth: Boolean
        get() = harness.storesHealth

    protected fun contract(
        timeout: Duration = CONTRACT_TIMEOUT,
        body: suspend TestScope.(StoreUnderTest) -> Unit,
    ): TestResult = runTest(timeout = timeout) { body(opened(harness.create(this))) }
}

abstract class SqliteArchiveContract : ArchiveContract() {
    abstract override val harness: SqliteArchiveHarness

    protected fun sqlite(
        timeout: Duration = CONTRACT_TIMEOUT,
        body: suspend TestScope.(SqliteStoreUnderTest) -> Unit,
    ): TestResult = runTest(timeout = timeout) { body(opened(harness.create(this))) }
}

abstract class WebArchiveContract : ArchiveContract() {
    abstract override val harness: WebArchiveHarness

    protected fun web(body: suspend TestScope.(WebStoreUnderTest) -> Unit): TestResult = runTest {
        body(opened(harness.create(this)))
    }
}

internal val CONTRACT_TIMEOUT: Duration = 2.minutes

internal suspend fun <S : StoreUnderTest> opened(store: S): S {
    assertIs<StoreOpen.Opened>(store.host.open().ok())
    return store
}

internal fun <T> StoreResult<T>.ok(): T =
    when (this) {
        is StoreResult.Ok -> value
        is StoreResult.Failed -> fail("expected Ok, got $error")
    }

internal fun StoreResult<*>.assertFailed(kind: StorageError.Kind) {
    assertEquals(kind, assertIs<StoreResult.Failed>(this, "expected $kind").error.kind)
}

internal suspend fun StoreUnderTest.answer(
    vararg mutations: Mutation,
    closedRide: RideId? = null,
): StoreResult<Commit> = writer.commit(WriteBatch(mutations.toList(), closedRide))

internal suspend fun StoreUnderTest.commit(vararg mutations: Mutation, closedRide: RideId? = null): Commit.Committed =
    assertIs<Commit.Committed>(answer(*mutations, closedRide = closedRide).ok())

internal suspend fun StoreUnderTest.create(ride: Ride, health: RideHealth = ContractData.health): Commit.Committed =
    commit(Mutation.CreateRide(ride, health))

internal suspend fun StoreUnderTest.revision(rideId: RideId): Long? = rides.ride(rideId).ok()?.revision

internal suspend fun MeasurementRepository.allTelemetry(
    rideId: RideId,
    range: TimeRange = ContractData.ALL,
    limit: Int = 4_096,
): List<ContractData.Reading> {
    val readings = mutableListOf<ContractData.Reading>()
    var after: TelemetryKey? = null
    var pages = 0
    do {
        val page = telemetry(rideId, range, after, limit).ok()
        pages++
        assertTrue(page.rows.rows <= limit, "a telemetry page holds ${page.rows.rows} rows for a limit of $limit")
        if (pages > 1) assertTrue(page.rows.rows > 0, "next was not null at the end")
        val rows = page.rows.readings()
        readings += rows
        after = page.next
        if (after != null) assertEquals(rows.lastOrNull()?.key, after, "next is the last row's key")
    } while (after != null)
    return readings
}

internal suspend fun MeasurementRepository.allLocations(
    rideId: RideId,
    range: TimeRange = ContractData.ALL,
    limit: Int = 4_096,
): List<LocationFix> {
    val fixes = mutableListOf<LocationFix>()
    var after: LocationKey? = null
    var pages = 0
    do {
        val page = locations(rideId, range, after, limit).ok()
        pages++
        assertTrue(page.fixes.size <= limit, "a location page holds ${page.fixes.size} fixes for a limit of $limit")
        if (pages > 1) assertTrue(page.fixes.isNotEmpty(), "next was not null at the end")
        fixes += page.fixes
        after = page.next
        if (after != null)
            assertEquals(
                page.fixes.lastOrNull()?.let { LocationKey(it.elapsedUs, it.seq) },
                after,
                "next is the last fix's key",
            )
    } while (after != null)
    return fixes
}

internal suspend fun MeasurementRepository.allHealth(
    rideId: RideId,
    range: TimeRange = ContractData.ALL,
    metric: HealthMetric? = null,
    limit: Int = 4_096,
    source: HealthSourceFilter? = null,
): List<HealthSample> {
    val samples = mutableListOf<HealthSample>()
    var after: HealthKey? = null
    var pages = 0
    do {
        val page = health(rideId, range, metric, after, limit, source).ok()
        pages++
        assertTrue(page.samples.size <= limit, "a Health page holds ${page.samples.size} samples for a limit of $limit")
        if (pages > 1) assertTrue(page.samples.isNotEmpty(), "next was not null at the end")
        samples += page.samples
        after = page.next
        if (after != null)
            assertEquals(page.samples.lastOrNull()?.elapsedUs, after.elapsedUs, "next is the last sample's")
    } while (after != null)
    return samples
}

internal suspend fun StoreUnderTest.allTelemetry(
    rideId: RideId,
    range: TimeRange = ContractData.ALL,
    limit: Int = 4_096,
): List<ContractData.Reading> = measurements.allTelemetry(rideId, range, limit)

internal suspend fun StoreUnderTest.allLocations(
    rideId: RideId,
    range: TimeRange = ContractData.ALL,
    limit: Int = 4_096,
): List<LocationFix> = measurements.allLocations(rideId, range, limit)

internal suspend fun StoreUnderTest.allHealth(
    rideId: RideId,
    range: TimeRange = ContractData.ALL,
    metric: HealthMetric? = null,
    limit: Int = 4_096,
    source: HealthSourceFilter? = null,
): List<HealthSample> = measurements.allHealth(rideId, range, metric, limit, source)

internal class Notices(val commits: List<CommitNotice>, val history: List<CommitNotice>)

private var sentinels = 0

internal suspend fun TestScope.notices(store: StoreUnderTest, block: suspend () -> Unit): Notices {
    val commits = Channel<CommitNotice>(Channel.UNLIMITED)
    val history = Channel<CommitNotice>(Channel.UNLIMITED)
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val collectors =
        listOf(
            backgroundScope.launch(dispatcher) { store.writer.commits.collect { commits.send(it) } },
            backgroundScope.launch(dispatcher) { store.writer.historyCommits.collect { history.send(it) } },
        )
    block()
    val sentinel = ContractData.rideId(SENTINEL_RIDES + ++sentinels)
    store.create(ContractData.ride(sentinel, phase = RidePhase.completed, startedUtcUs = ContractData.SENTINEL_UTC_US))
    val notices = Notices(commits.until(sentinel), history.until(sentinel))
    collectors.forEach { it.cancel() }
    return notices
}

private suspend fun Channel<CommitNotice>.until(sentinel: RideId): List<CommitNotice> {
    val notices = mutableListOf<CommitNotice>()
    while (true) {
        val notice = receive()
        if (sentinel in notice.historyRides) return notices
        notices += notice
    }
}

private const val SENTINEL_RIDES = 0x0f00_0000

internal object ContractData {
    const val START_UTC_US = 1_788_912_000_000_000L
    const val SENTINEL_UTC_US = 946_684_800_000_000L
    const val MAX_SAFE = 9_007_199_254_740_991L
    val ALL = TimeRange(-MAX_SAFE, MAX_SAFE + 1)

    fun rideId(n: Int): RideId = RideId("00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}")

    fun eventId(n: Int): EventId = EventId("00000000-0000-4000-9000-${n.toString(16).padStart(12, '0')}")

    val options =
        RideOptions(
            sampleRate = SampleRate.hz8,
            indoor = false,
            useWatch = false,
            saveToHealth = true,
            recordGps = true,
            distanceSource = DistanceSource.controller,
            batteryMethod = BatteryMethod.controller,
        )

    val identity = ControllerIdentity(DecoderId("cyc"), "X6", "20260101", "5.3")

    val health = RideHealth(HealthProviderKind.appleHealth, HealthOutcome.pending, null, null)

    fun timing(startedUtcUs: Long, elapsedUs: Long, timerUs: Long = elapsedUs): RideTiming =
        RideTiming(startedUtcUs + elapsedUs, elapsedUs, timerUs)

    fun ride(
        id: RideId,
        phase: RidePhase = RidePhase.running,
        startedUtcUs: Long = START_UTC_US,
        recordedBy: Device = Device.iphone,
        interrupted: Boolean = false,
        checkpointUs: Long = 0,
        endUs: Long? = null,
        options: RideOptions = ContractData.options,
    ): Ride =
        Ride(
            id = id,
            recordedBy = recordedBy,
            example = false,
            startedUtcUs = startedUtcUs,
            options = options,
            phase = phase,
            interrupted = interrupted,
            checkpoint = timing(startedUtcUs, checkpointUs),
            end = endUs?.let { timing(startedUtcUs, it) },
        )

    fun session(rideId: RideId, sessionNo: Int = 1, identity: ControllerIdentity? = ContractData.identity) =
        ControllerSession(rideId, sessionNo, identity, ControllerCapabilities(emptySet(), controllerSpeed = false))

    data class Reading(
        val elapsedUs: Long,
        val seq: Long,
        val sessionNo: Int = 1,
        val utcUs: Long = START_UTC_US + elapsedUs,
        val activeInterval: Int? = 1,
        val run: Int = 1,
        val quanta: Map<MetricId, Long> = mapOf(MetricId.humanPowerW to 150_000L),
    ) {
        val key: TelemetryKey
            get() = TelemetryKey(elapsedUs, sessionNo, seq)
    }

    fun readings(count: Int, fromUs: Long = 0, stepUs: Long = 125_000, sessionNo: Int = 1, firstSeq: Long = 1) =
        List(count) { Reading(elapsedUs = fromUs + it * stepUs, seq = firstSeq + it, sessionNo = sessionNo) }

    fun rows(readings: List<Reading>): TelemetryRows {
        val metrics = readings.flatMap { it.quanta.keys }.toSet()
        return TelemetryRows(
            rows = readings.size,
            elapsedUs = DoubleArray(readings.size) { readings[it].elapsedUs.toDouble() },
            sessionNo = IntArray(readings.size) { readings[it].sessionNo },
            seq = DoubleArray(readings.size) { readings[it].seq.toDouble() },
            utcUs = DoubleArray(readings.size) { readings[it].utcUs.toDouble() },
            activeInterval = IntArray(readings.size) { readings[it].activeInterval ?: 0 },
            run = IntArray(readings.size) { readings[it].run },
            quanta =
                metrics.associateWith { metric ->
                    DoubleArray(readings.size) { readings[it].quanta[metric]?.toDouble() ?: Double.NaN }
                },
        )
    }

    fun append(rideId: RideId, readings: List<Reading>) = Mutation.AppendTelemetry(rideId, rows(readings))

    fun fix(
        rideId: RideId,
        seq: Long,
        elapsedUs: Long,
        speedMps: Double? = 5.5,
        mslM: Double? = 120.0,
        horizontalAccuracyM: Double? = 4.0,
    ) =
        LocationFix(
            rideId = rideId,
            seq = seq,
            elapsedUs = elapsedUs,
            utcUs = START_UTC_US + elapsedUs,
            activeInterval = 1,
            run = 1,
            barrier = false,
            latitude = 45.5,
            longitude = 9.2,
            horizontalAccuracyM = horizontalAccuracyM,
            altitudeM = mslM,
            altitudeDatum = AltitudeDatum.msl,
            verticalAccuracyM = 3.0,
            mslM = mslM,
            mslVerticalAccuracyM = 3.0,
            speedMps = speedMps,
            speedAccuracyMps = 0.5,
            courseDeg = 37.0,
            courseAccuracyDeg = 8.0,
        )

    fun fixes(rideId: RideId, count: Int, fromUs: Long = 0, stepUs: Long = 1_000_000, firstSeq: Long = 1) =
        List(count) { fix(rideId, firstSeq + it, fromUs + it * stepUs) }

    val heartRate = HealthMetric("heartRateBpm")

    fun sample(
        rideId: RideId,
        key: String,
        elapsedUs: Long,
        metric: HealthMetric? = heartRate,
        value: Double? = 120.0,
        producer: Producer = Producer.watch,
        representation: HealthRepresentation = HealthRepresentation.rawQuantity,
        dataOrigin: String = "com.apple.health",
    ) =
        HealthSample(
            rideId = rideId,
            producer = producer,
            sampleKey = key,
            dataOrigin = dataOrigin,
            parentId = null,
            member = null,
            metric = metric,
            representation = representation,
            value = value,
            sampleCount = null,
            utcUs = START_UTC_US + elapsedUs,
            elapsedUs = elapsedUs,
            startUtcUs = START_UTC_US + elapsedUs,
            endUtcUs = START_UTC_US + elapsedUs,
            activeInterval = 1,
        )

    fun event(n: Int, kind: RideEventKind, elapsedUs: Long) =
        RideEvent(eventId(n), kind, START_UTC_US + elapsedUs, elapsedUs)

    fun publish(
        rideId: RideId,
        component: HealthComponent,
        state: ComponentState = ComponentState.pending,
        attempts: Int = 0,
        cursor: String? = null,
        dueUtcUs: Long? = null,
        reason: HealthReason? = null,
    ) = HealthWork(rideId, HealthWorkScope.Publish(component), state, attempts, cursor, dueUtcUs, reason)

    fun readback(
        rideId: RideId,
        metric: HealthMetric,
        state: ComponentState = ComponentState.pending,
        cursor: String? = null,
        dueUtcUs: Long? = null,
    ) = HealthWork(rideId, HealthWorkScope.Readback(metric), state, 0, cursor, dueUtcUs, null)
}

internal fun TelemetryRows.readings(): List<ContractData.Reading> =
    List(rows) { row ->
        ContractData.Reading(
            elapsedUs = elapsedUs[row].toLong(),
            seq = seq[row].toLong(),
            sessionNo = sessionNo[row],
            utcUs = utcUs[row].toLong(),
            activeInterval = activeInterval[row].takeIf { it != 0 },
            run = run[row],
            quanta =
                quanta.entries
                    .mapNotNull { (metric, values) ->
                        values[row].takeUnless { it.isNaN() }?.let { metric to it.toLong() }
                    }
                    .toMap(),
        )
    }
