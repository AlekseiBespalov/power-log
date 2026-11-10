package app.powerlog.application.slice

import app.powerlog.application.Executors
import app.powerlog.application.api.ApiStream
import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchReason
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.connection.ConnectInput
import app.powerlog.application.connection.ConnectionView
import app.powerlog.application.diagnostics.CrashLineSource
import app.powerlog.application.fakes.FakeBleCentral
import app.powerlog.application.fakes.FakeDeviceInfo
import app.powerlog.application.fakes.FakeFilePicker
import app.powerlog.application.fakes.FakePermissionGate
import app.powerlog.application.fakes.FakeRecordingKeepAlive
import app.powerlog.application.fakes.FakeRideCard
import app.powerlog.application.fakes.FakeTabLock
import app.powerlog.application.fakes.FakeTonePlayer
import app.powerlog.application.fakes.FakeWatchLink
import app.powerlog.application.history.ExampleRideSource
import app.powerlog.application.history.HistoryPage
import app.powerlog.application.history.HistoryPageInput
import app.powerlog.application.history.HistoryRow
import app.powerlog.application.launch.PowerLogApplication
import app.powerlog.application.port.BackgroundScheduler
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.Clock
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.LocationSource
import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.Reply
import app.powerlog.application.port.RideCard
import app.powerlog.application.port.SendResult
import app.powerlog.application.port.StoragePersistence
import app.powerlog.application.port.TabLease
import app.powerlog.application.port.TabLock
import app.powerlog.application.port.TonePlayer
import app.powerlog.application.port.WatchLink
import app.powerlog.application.recording.ControlInput
import app.powerlog.application.recording.RecordingView
import app.powerlog.application.recording.TabLeases
import app.powerlog.application.settings.RideToggle
import app.powerlog.application.settings.SettingsChange
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.application.watch.FakeWatchCodec
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.controller.cyc.CycDecoder
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthOutcomeRules
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.domain.telemetry.cyc.CycProtocol
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Scenario(val platform: Platform = Platform.ios) {
    val scheduler = TestCoroutineScheduler()
    val dispatches = mutableMapOf<String, Int>()
    private val ride = counted("ride")
    val clock =
        object : Clock {
            override fun monoUs(): Long = scheduler.currentTime * 1_000

            override fun utcUs(): Long = START_UTC_US + monoUs()

            override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
        }
    private val failures = mutableListOf<Throwable>()
    private val os =
        CoroutineScope(ride + SupervisorJob() + CoroutineExceptionHandler { _, error -> failures += error })
    val post: (() -> Unit) -> Unit = { block -> os.launch { block() } }
    val fake = FakeBleCentral(platform, clock, post)
    val archive = ScenarioStores.inMemory(counted("store"), clock)
    val permissions = FakePermissionGate(post, granted)
    var statusFailure: PortError? = null
    private val gate =
        object : PermissionGate by permissions {
            override fun status(reply: Reply<PermissionStates>) {
                val failure = statusFailure ?: return permissions.status(reply)
                post { reply.failed(failure) }
            }
        }
    val keepAlive = FakeRecordingKeepAlive(post)
    val picker = FakeFilePicker(post)
    var exampleRides: ExampleRideSource? = null

    val device = FakeDeviceInfo(FakeDeviceInfo.snapshot(platform))
    var health: HealthProvider? = null
    var crashLines: CrashLineSource? = null
    var backgroundScheduler: BackgroundScheduler? = null
    var persistence: StoragePersistence? = null
    var location: LocationSource? = null
    val card = FakeRideCard(post)
    val tonePlayer = FakeTonePlayer(post)
    var tones: TonePlayer? = tonePlayer
    val watchLink = FakeWatchLink(clock, post)
    val codec = FakeWatchCodec()
    var watch: WatchLink? = null
    var rideCard: RideCard? = null
    val lock = FakeTabLock(post)
    var tabLock: TabLock? = lock.takeIf { platform == Platform.web }
    var replies = true
    var telemetryReplies = true
    var linkUps = true
    val sent = mutableListOf<ControllerRequest>()
    val sentAtUs = mutableListOf<Long>()
    var lastLink = 0L
    lateinit var events: BleEvents
        private set

    private val controller =
        object : BleCentral by fake {
            override fun attach(bleEvents: BleEvents) {
                events = bleEvents
                fake.attach(bleEvents)
            }

            override fun connect(bike: String, profile: TransportProfile, adopt: Boolean): Long {
                val link = fake.connect(bike, profile, adopt)
                lastLink = link
                if (linkUps)
                    os.launch {
                        delay(LINK_UP_MS)
                        fake.os.connected(bike)
                    }
                return link
            }

            override fun send(link: Long, frame: ControllerFrame): SendResult {
                val result = fake.send(link, frame)
                if (result != SendResult.accepted) return result
                val request =
                    if (frame.bytes.contentEquals(CycDecoder.request(ControllerRequest.identity).bytes))
                        ControllerRequest.identity
                    else ControllerRequest.telemetry
                sent += request
                sentAtUs += clock.monoUs()
                if (!replies || (request == ControllerRequest.telemetry && !telemetryReplies)) return result
                os.launch {
                    delay(REPLY_MS)
                    fake.os.notify(if (request == ControllerRequest.identity) identityFrame else telemetryFrame)
                }
                return result
            }
        }

    val application: PowerLogApplication by lazy {
        PowerLogApplication(
                executors = Executors(ride, counted("health"), counted("maintenance"), counted("reads")),
                clock = clock,
                stores = archive.stores,
                device = device,
                permissions = gate,
                picker = picker,
                ble = controller,
                location = location,
                health = health,
                keepAlive = keepAlive,
                tabLock = tabLock,
                persistence = persistence,
                rideCard = rideCard,
                tones = tones,
                watch = watch,
                watchCodec = watch?.let { codec },
                scheduler = backgroundScheduler,
                exampleRides = exampleRides,
                developerTools = false,
                crashLines = crashLines,
                unexpected = { component, error, at ->
                    LogLine(
                        LogLevel.error,
                        at.utcUs,
                        at.monoUs,
                        "unexpected",
                        listOf(
                            LogField("component", LogValue.Text(component)),
                            LogField("exception", LogValue.Text(error::class.simpleName.orEmpty())),
                        ),
                    )
                },
            )
            .also { it.attach() }
    }

    val api: PowerLogApi
        get() = application.api

    @OptIn(ExperimentalCoroutinesApi::class)
    fun test(body: suspend ScenarioScope.() -> Unit) {
        val run =
            CoroutineScope(StandardTestDispatcher(scheduler, "test")).async { ScenarioScope(scheduler, this).body() }
        val limit = scheduler.currentTime + VIRTUAL_LIMIT_MS
        while (!run.isCompleted) {
            scheduler.runCurrent()
            if (run.isCompleted) break
            check(scheduler.currentTime < limit) {
                "the scenario still runs after $VIRTUAL_LIMIT_MS ms of virtual time"
            }
            scheduler.advanceTimeBy(1)
        }
        run.getCompletionExceptionOrNull()?.let { throw it }
        failures.firstOrNull()?.let { throw it }
        val violations = LogWhitelist.violations(archive.log)
        check(violations.isEmpty()) { "log lines outside 60 §5.5's whitelist: $violations" }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun counted(name: String): CoroutineDispatcher {
        val delegate = StandardTestDispatcher(scheduler, name)
        return object : CoroutineDispatcher(), Delay by delegate as Delay {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches[name] = (dispatches[name] ?: 0) + 1
                delegate.dispatch(context, block)
            }

            override fun toString(): String = name
        }
    }

    suspend fun launch(): LaunchResult = application.host.launch(LaunchContext(LaunchReason.userInterface))

    suspend fun ScenarioScope.search() {
        val search = async { api.connection.startSearch(Empty) }
        runCurrent()
        if (platform == Platform.ios) fake.os.radio(RadioState.poweredOn)
        assertEquals(Outcome.Ok(Unit), search.await())
    }

    fun discovered(bike: String = BIKE) =
        fake.os.discovered(bike, "CYCMOTOR X6", -60, listOf(CycDecoder.transport.service), null)

    suspend fun ScenarioScope.connect(bike: String = BIKE) {
        search()
        discovered(bike)
        connection { view -> view.found.any { it.bikeId == BikeId(bike) } }
        assertEquals(Outcome.Ok(Unit), api.connection.connect(ConnectInput(BikeId(bike))))
    }

    suspend fun indoor(rate: SampleRate = SampleRate.hz8) {
        for (change in
            listOf(
                SettingsChange.Toggle(RideToggle.indoor, true),
                SettingsChange.SampleRateTo(rate),
                SettingsChange.Toggle(RideToggle.useWatch, false),
                SettingsChange.Toggle(RideToggle.saveToHealth, false),
            )) assertEquals(Outcome.Ok(Unit), api.settings.change(change))
    }

    suspend fun ScenarioScope.ready(rate: SampleRate = SampleRate.hz8) {
        assertEquals(LaunchResult.Ready, launch())
        connect()
        indoor(rate)
    }

    suspend fun ScenarioScope.riding(rate: SampleRate = SampleRate.hz8): RideId {
        ready(rate)
        assertEquals(Outcome.Ok(Unit), api.recording.start(Empty))
        return requireNotNull(recording { it.phase == RidePhase.running }.ride).rideId
    }

    suspend fun control(rideId: RideId, action: RideAction): Outcome<Unit> =
        api.recording.control(ControlInput(rideId, action))

    suspend fun page(): List<HistoryRow> =
        assertIs<Outcome.Ok<HistoryPage>>(api.history.page(HistoryPageInput(null, "page"))).value.rows

    fun telemetry(): List<Mutation.AppendTelemetry> = mutations().filterIsInstance<Mutation.AppendTelemetry>()

    suspend fun recording(until: (RecordingView) -> Boolean = { true }): RecordingView =
        api.recording.state.until("recording", until)

    suspend fun connection(until: (ConnectionView) -> Boolean = { true }): ConnectionView =
        api.connection.state.until("connection", until)

    fun mutations(): List<Mutation> = archive.committed.flatMap { it.mutations }

    fun telemetryRows(): Int = mutations().filterIsInstance<Mutation.AppendTelemetry>().sumOf { it.rows.rows }

    suspend fun otherTabLease(): TabLease {
        val leased = CompletableDeferred<TabLease?>()
        lock.acquire(TabLeases.LOCK, false, Reply({ leased.complete(it) }, { leased.completeExceptionally(error(it)) }))
        return requireNotNull(leased.await())
    }

    suspend fun seedOpenRide(
        phase: RidePhase,
        startedMonoUs: Long = 0,
        otherTab: Boolean = false,
        notify: Boolean = true,
        useWatch: Boolean = false,
    ): Ride {
        val options =
            RideOptions(
                SampleRate.hz8,
                indoor = true,
                useWatch = useWatch,
                saveToHealth = false,
                recordGps = false,
                DistanceSource.controller,
                BatteryMethod.controller,
            )
        val startedUtcUs = START_UTC_US + startedMonoUs
        val preparing =
            Ride(
                RideId("seeded-ride"),
                Device.iphone,
                example = false,
                startedUtcUs,
                options,
                RidePhase.preparing,
                interrupted = false,
                RideTiming(startedUtcUs, 0, 0),
                end = null,
            )
        val batches =
            mutableListOf(WriteBatch(listOf(Mutation.CreateRide(preparing, HealthOutcomeRules.initial(options, null)))))
        if (phase != RidePhase.preparing)
            batches +=
                WriteBatch(
                    listOf(
                        Mutation.SetPhase(preparing.id, phase),
                        Mutation.Checkpoint(preparing.id, RideTiming(startedUtcUs + 5_000_000, 5_000_000, 5_000_000)),
                    )
                )
        if (otherTab) archive.otherTab(*batches.toTypedArray(), notify = notify)
        else archive.seed(*batches.toTypedArray())
        return preparing.copy(phase = phase)
    }

    companion object {
        const val START_UTC_US = 1_767_225_600_000_000L
        const val BIKE = "D1"
        const val LINK_UP_MS = 50L
        const val REPLY_MS = 20L
        const val VIRTUAL_LIMIT_MS = 900_000L

        val granted =
            PermissionStates(PermissionState.granted, PermissionState.granted, true, true, PermissionState.granted)

        private val repository = File(requireNotNull(System.getProperty("powerlog.repositoryRoot")))

        val identityFrame: ByteArray =
            frame(fixture("identity.json").getValue("cases").jsonArray[0].jsonObject.text("payloadHex"))
        val telemetryFrame: ByteArray = frame(fixture("wire-metrics.json").text("payloadHex"))

        private fun fixture(name: String) =
            Json.parseToJsonElement(repository.resolve("tests/fixtures/contract/telemetry/cyc/$name").readText())
                .jsonObject

        private fun Map<String, kotlinx.serialization.json.JsonElement>.text(key: String) =
            getValue(key).jsonPrimitive.content

        fun frame(hex: String): ByteArray {
            val payload = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val crc = CycProtocol.crc(payload)
            return byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
        }
    }
}

class ScenarioScope(private val scheduler: TestCoroutineScheduler, scope: CoroutineScope) : CoroutineScope by scope {
    fun runCurrent() = scheduler.runCurrent()
}

fun Outcome<*>.code(): FailureCode? = (this as? Outcome.Failed)?.failure?.code

suspend fun <T> ApiStream<Empty, T>.until(key: String, predicate: (T) -> Boolean): T {
    var answer = assertIs<StreamAnswer.Value<T>>(next(Empty, null, key))
    while (!predicate(answer.value)) answer = assertIs<StreamAnswer.Value<T>>(next(Empty, answer.revision, key))
    return answer.value
}
