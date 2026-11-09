package app.powerlog.application

import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.domain.core.analysis.battery.ConsumptionInterval
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.battery.ConsumptionReducer
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.energyperdistance.EnergyPerDistanceReducer
import app.powerlog.domain.core.analysis.summary.RideSummary
import app.powerlog.domain.core.analysis.summary.RideSummaryReducer
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.FinishBoundary
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.TimeRange
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

internal sealed interface SummaryAnswer {
    data class Ready(val summary: RideSummary) : SummaryAnswer

    data object Unavailable : SummaryAnswer
}

@OptIn(ExperimentalAtomicApi::class)
internal class RideSummaries(
    private val scope: CoroutineScope,
    private val executors: Executors,
    private val measurements: MeasurementRepository,
    private val cache: CacheRepository,
) {
    private val flights = AtomicReference<Map<Pair<RideId, Long>, Deferred<SummaryAnswer>>>(emptyMap())

    suspend fun cached(rideId: RideId, revision: Long): RideSummary? = cache.summary(rideId, revision)

    fun summarized(ride: Ride): Boolean = ride.phase in summarizedPhases

    suspend fun of(ride: Ride, revision: Long): SummaryAnswer {
        require(summarized(ride)) { "a ${ride.phase} ride has no summary" }
        cached(ride.id, revision)?.let {
            return SummaryAnswer.Ready(it)
        }
        return start(ride, revision).await()
    }

    fun warm(ride: Ride, revision: Long) {
        if (summarized(ride)) start(ride, revision)
    }

    fun start(ride: Ride, revision: Long): Deferred<SummaryAnswer> {
        val key = ride.id to revision
        while (true) {
            val current = flights.load()
            current[key]?.let {
                return it
            }
            val computation = scope.async(executors.scans, CoroutineStart.LAZY) { compute(ride, revision) }
            if (flights.compareAndSet(current, current + (key to computation))) {
                computation.invokeOnCompletion { drop(key, computation) }
                computation.start()
                return computation
            }
            computation.cancel()
        }
    }

    private fun drop(key: Pair<RideId, Long>, computation: Deferred<SummaryAnswer>) {
        while (true) {
            val current = flights.load()
            if (current[key] !== computation) return
            if (flights.compareAndSet(current, current - key)) return
        }
    }

    private suspend fun compute(ride: Ride, revision: Long): SummaryAnswer {
        cached(ride.id, revision)?.let {
            return SummaryAnswer.Ready(it)
        }
        val events = measurements.events(ride.id).orThrow()
        val endUs = endUs(ride, events)
        val timeline = ActiveTimeline(maxOf(0L, endUs), events)
        val pass = SummaryPass(ride, timeline, TimeRange(0, maxOf(0L, endUs) + 1))
        events.forEach { pass.append(Fact.Event(it)) }
        val sessions = measurements.sessions(ride.id).orThrow().associateBy { it.sessionNo }
        FactReader(measurements, ride.id, sessions)
            .feed(
                TimeRange(0, Long.MAX_VALUE),
                FactStreams(true, true, HealthStream(null, null)),
                Derivations(listOf(pass)),
            )
        return when (val result = pass.result()) {
            is Bounded.Done -> {
                cache.putSummary(ride.id, revision, result.value)
                SummaryAnswer.Ready(result.value)
            }
            Bounded.Capacity -> SummaryAnswer.Unavailable
        }
    }

    private suspend fun endUs(ride: Ride, events: List<RideEvent>): Long =
        when (ride.phase) {
            RidePhase.completed -> (ride.end ?: ride.checkpoint).elapsedUs
            RidePhase.interrupted ->
                FinishBoundary.of(ride.startedUtcUs, measurements.lastFact(ride.id).orThrow(), events).elapsedUs
            else -> ride.checkpoint.elapsedUs
        }

    private class SummaryPass(ride: Ride, private val timeline: ActiveTimeline, range: TimeRange) : DerivedSink {
        private val summary = RideSummaryReducer(ride, timeline)
        private val distances = DistanceSource.entries.associateWith { DistanceReducer(range, null) }
        private val consumption = ConsumptionMetric.entries.associateWith { ConsumptionReducer(it, range, null) }
        private val energy = EnergyPerDistanceReducer(null, ride.options.batteryMethod, ride.options.distanceSource)

        override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
            summary.append(rows, from, to, sessions)
            energy.append(rows, from, to, sessions)
        }

        override fun append(fact: Fact) {
            summary.append(fact)
            energy.append(fact)
        }

        override fun distance(source: DistanceSource, interval: DistanceInterval) {
            distances.getValue(source).append(interval)
            energy.distance(interval)
        }

        override fun battery(interval: ConsumptionInterval) {
            consumption.getValue(interval.metric).append(interval)
            energy.battery(interval)
        }

        fun result(): Bounded<RideSummary> {
            summary.distance(
                distances.mapValues {
                    (it.value.result(timeline.timerUs) as? Bounded.Done)?.value ?: return Bounded.Capacity
                }
            )
            summary.battery(consumption.mapValues { it.value.result(timeline.timerUs) })
            summary.energyPerDistance((energy.result() as? Bounded.Done)?.value ?: return Bounded.Capacity)
            return summary.result()
        }
    }

    private companion object {
        val summarizedPhases = setOf(RidePhase.completed, RidePhase.interrupted, RidePhase.failed)
    }
}
