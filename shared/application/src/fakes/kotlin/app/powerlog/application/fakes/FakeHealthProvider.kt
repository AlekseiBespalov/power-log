package app.powerlog.application.fakes

import app.powerlog.application.port.AppleWorkoutSession
import app.powerlog.application.port.Clock
import app.powerlog.application.port.FinishedWorkout
import app.powerlog.application.port.HealthAccess
import app.powerlog.application.port.HealthAvailability
import app.powerlog.application.port.HealthConnectWriter
import app.powerlog.application.port.HealthGrants
import app.powerlog.application.port.HealthOriginal
import app.powerlog.application.port.HealthOriginalPage
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.HealthReader
import app.powerlog.application.port.HealthScope
import app.powerlog.application.port.LiveHeartRate
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.SessionOptions
import app.powerlog.application.port.SessionState
import app.powerlog.application.port.WorkoutSessionEvents
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.health.HealthConnectRecordPlan
import app.powerlog.domain.health.HealthPlan

class FakeHealthProvider
private constructor(override val kind: HealthProviderKind, clock: Clock, post: (() -> Unit) -> Unit) : HealthProvider {
    private val flight = Flight(singleFlight = kind == HealthProviderKind.healthConnect, post = post)

    override val access: FakeHealthAccess = FakeHealthAccess(flight)

    override val reader: FakeHealthReader =
        FakeHealthReader(flight, pageSize = if (kind == HealthProviderKind.appleHealth) 256 else 128)

    override val session: FakeAppleWorkoutSession? =
        if (kind == HealthProviderKind.appleHealth) FakeAppleWorkoutSession(clock, post) else null

    override val writer: FakeHealthConnectWriter? =
        if (kind == HealthProviderKind.healthConnect) FakeHealthConnectWriter(flight) else null

    companion object {
        fun apple(clock: Clock, post: (() -> Unit) -> Unit): FakeHealthProvider =
            FakeHealthProvider(HealthProviderKind.appleHealth, clock, post)

        fun healthConnect(clock: Clock, post: (() -> Unit) -> Unit): FakeHealthProvider =
            FakeHealthProvider(HealthProviderKind.healthConnect, clock, post)
    }

    class Flight internal constructor(private val singleFlight: Boolean, private val post: (() -> Unit) -> Unit) {
        private val calls = mutableListOf<FakeRequests<*, *>>()

        internal fun <A, T> requests(script: (A) -> Answer<T>): FakeRequests<A, T> =
            FakeRequests(post, script).also { calls += it }

        internal fun <A, T> call(requests: FakeRequests<A, T>, request: A, reply: Reply<T>) {
            if (singleFlight && calls.any { it.pending > 0 }) post { reply.failed(PortError(PortErrorKind.busy)) }
            else requests.take(request, reply)
        }
    }
}

class FakeHealthAccess internal constructor(private val flight: FakeHealthProvider.Flight) : HealthAccess {
    var availability: HealthAvailability = HealthAvailability.available

    var grants: HealthGrants =
        HealthGrants(
            write = emptyMap(),
            read = emptyMap(),
            route = PermissionState.notDetermined,
            backgroundRead = null,
            readableFromUtcUs = null,
        )

    val grantReads: FakeRequests<Unit, HealthGrants> = flight.requests { Answer.Ok(grants) }

    val requests: FakeRequests<HealthScope, Unit> = flight.requests { Answer.Ok(Unit) }

    override fun availability(): HealthAvailability = availability

    override fun grants(reply: Reply<HealthGrants>) = flight.call(grantReads, Unit, reply)

    override fun request(scope: HealthScope, reply: Reply<Unit>) = flight.call(requests, scope, reply)
}

class FakeHealthReader internal constructor(private val flight: FakeHealthProvider.Flight, val pageSize: Int) :
    HealthReader {
    data class Read(val metric: String, val startUtcUs: Long, val endUtcUs: Long, val cursor: String?)

    val samples: MutableList<HealthOriginal> = mutableListOf()

    val reads: FakeRequests<Read, HealthOriginalPage> = flight.requests { Answer.Ok(page(it)) }

    override fun read(
        metric: String,
        startUtcUs: Long,
        endUtcUs: Long,
        cursor: String?,
        reply: Reply<HealthOriginalPage>,
    ) = flight.call(reads, Read(metric, startUtcUs, endUtcUs, cursor), reply)

    private fun page(read: Read): HealthOriginalPage {
        val matching = samples.filter {
            it.metric == read.metric && it.startUtcUs < read.endUtcUs && it.endUtcUs > read.startUtcUs
        }
        val from = read.cursor?.toInt() ?: 0
        val page = matching.drop(from).take(pageSize)
        val next = (from + page.size).takeIf { it < matching.size }?.toString()
        return HealthOriginalPage(page, next)
    }
}

class FakeHealthConnectWriter internal constructor(private val flight: FakeHealthProvider.Flight) :
    HealthConnectWriter {
    private var issued = 0

    override val packageName: String = "app.powerlog.mobile"
    override val appLabel: String = "Power Log"

    val inserts: FakeRequests<List<HealthConnectRecordPlan>, List<String>> = flight.requests { records ->
        Answer.Ok(records.map { "record-${++issued}" })
    }

    override fun insert(records: List<HealthConnectRecordPlan>, reply: Reply<List<String>>) =
        flight.call(inserts, records.toList(), reply)
}

class FakeAppleWorkoutSession(private val clock: Clock, private val post: (() -> Unit) -> Unit) : AppleWorkoutSession {
    data class Begin(val workout: HealthPlan.Workout, val options: SessionOptions)

    data class Finish(val rideId: String, val atUtcUs: Long, val route: HealthPlan.Route?)

    private var events: WorkoutSessionEvents? = null
    private val log = mutableListOf<String>()
    private val saved = mutableMapOf<String, String>()
    private var issued = 0

    val commands: List<String>
        get() = log

    var workout: HealthPlan.Workout? = null
        private set

    var orphan: String? = null

    val begins: FakeRequests<Begin, Unit> = FakeRequests(post) { Answer.Ok(Unit) }

    val adds: FakeRequests<List<HealthPlan.Quantity>, Unit> = FakeRequests(post) { Answer.Ok(Unit) }

    val laps: FakeRequests<Long, Unit> = FakeRequests(post) { Answer.Ok(Unit) }

    val finishes: FakeRequests<Finish, FinishedWorkout> =
        FakeRequests(post) { finish ->
            val id = "00000000-0000-4000-8000-${(++issued).toString().padStart(12, '0')}"
            saved[finish.rideId] = id
            Answer.Ok(FinishedWorkout(workoutId = id, routeSaved = finish.route != null))
        }

    override fun attach(sessionEvents: WorkoutSessionEvents) {
        check(events == null) { "attach is called once" }
        events = sessionEvents
    }

    override fun begin(workout: HealthPlan.Workout, options: SessionOptions, reply: Reply<Unit>) {
        attached()
        if (this.workout != null) {
            post { reply.failed(PortError(PortErrorKind.busy)) }
            return
        }
        this.workout = workout
        val refusable =
            Reply<Unit>(
                onOk = reply::ok,
                onFailed = { error ->
                    if (this.workout === workout) this.workout = null
                    reply.failed(error)
                },
            )
        begins.take(Begin(workout, options), refusable)
    }

    override fun pause() {
        attached()
        log += "pause"
    }

    override fun resume() {
        attached()
        log += "resume"
    }

    override fun add(quantities: List<HealthPlan.Quantity>, reply: Reply<Unit>) {
        attached()
        adds.take(quantities.toList(), reply)
    }

    override fun addLap(atUtcUs: Long, reply: Reply<Unit>) {
        attached()
        laps.take(atUtcUs, reply)
    }

    override fun finish(atUtcUs: Long, route: HealthPlan.Route?, reply: Reply<FinishedWorkout>) {
        attached()
        val ending = checkNotNull(workout) { "finish needs a begun session" }
        workout = null
        finishes.take(Finish(ending.rideId, atUtcUs, route), reply)
    }

    override fun discard(atUtcUs: Long) {
        attached()
        log += "discard $atUtcUs"
        workout = null
    }

    override fun recoverOrphan(reply: Reply<String?>) {
        val recovered = orphan
        orphan = null
        post { reply.ok(recovered) }
    }

    override fun savedWorkout(rideId: String, reply: Reply<String?>) {
        val id = saved[rideId]
        post { reply.ok(id) }
    }

    fun state(state: SessionState, atUtcUs: Long) {
        val sink = attached()
        post { sink.stateChanged(state, atUtcUs) }
    }

    fun fail(error: PortError) {
        val sink = attached()
        post { sink.failed(error) }
    }

    fun heartRate(heartRate: LiveHeartRate) {
        val sink = attached()
        val at = clock.read()
        post { sink.heartRateReceived(heartRate, at) }
    }

    fun collect(samples: List<HealthOriginal>) {
        val sink = attached()
        val at = clock.read()
        val copy = samples.toList()
        post { sink.samplesCollected(copy, at) }
    }

    private fun attached(): WorkoutSessionEvents = checkNotNull(events) { "attach comes before the first command" }
}
