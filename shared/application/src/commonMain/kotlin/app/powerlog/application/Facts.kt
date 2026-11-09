package app.powerlog.application

import app.powerlog.application.store.HealthKey
import app.powerlog.application.store.HealthSourceFilter
import app.powerlog.application.store.LocationKey
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.TelemetryKey
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.time.TimeRange

internal class StoreFailure(val failed: StoreResult.Failed) : Exception()

internal fun <T> StoreResult<T>.orThrow(): T =
    when (this) {
        is StoreResult.Ok -> value
        is StoreResult.Failed -> throw StoreFailure(this)
    }

internal interface FactSink : TelemetryConsumer {
    fun append(fact: Fact)
}

internal data class HealthStream(val metric: HealthMetric?, val source: HealthSourceFilter?)

internal class FactStreams(val telemetry: Boolean, val locations: Boolean, val health: HealthStream?)

internal class FactReader(
    private val store: MeasurementRepository,
    private val rideId: RideId,
    private val sessions: Map<Int, ControllerSession>,
) {
    suspend fun feed(range: TimeRange, streams: FactStreams, sink: FactSink) {
        if (range.endUs <= range.startUs) return
        val telemetry = if (streams.telemetry) Telemetry(range) else null
        val locations = if (streams.locations) Locations(range) else null
        val health = streams.health?.let { Health(range, it) }
        telemetry?.fill()
        locations?.fill()
        health?.fill()
        while (true) {
            val rowUs = telemetry?.headUs() ?: Long.MAX_VALUE
            val fixUs = locations?.headUs() ?: Long.MAX_VALUE
            val sampleUs = health?.headUs() ?: Long.MAX_VALUE
            when {
                rowUs == Long.MAX_VALUE && fixUs == Long.MAX_VALUE && sampleUs == Long.MAX_VALUE -> return
                rowUs <= fixUs && rowUs <= sampleUs -> requireNotNull(telemetry).feed(minOf(fixUs, sampleUs), sink)
                fixUs <= sampleUs -> sink.append(Fact.Location(requireNotNull(locations).take()))
                else -> sink.append(Fact.Health(requireNotNull(health).take()))
            }
        }
    }

    private inner class Telemetry(private val range: TimeRange) {
        private var rows: TelemetryRows? = null
        private var index = 0
        private var next: TelemetryKey? = null
        private var done = false

        suspend fun fill() {
            while (!done && (rows?.let { index >= it.rows } != false)) {
                val page = store.telemetry(rideId, range, next, PagePolicy.rows).orThrow()
                rows = page.rows
                index = 0
                next = page.next
                done = page.next == null
            }
        }

        fun headUs(): Long? = rows?.takeIf { index < it.rows }?.elapsedUs?.get(index)?.toLong()

        suspend fun feed(throughUs: Long, sink: FactSink) {
            val page = requireNotNull(rows)
            var end = index
            while (end < page.rows && page.elapsedUs[end].toLong() <= throughUs) end++
            val start = index
            inSlices(end - start) { from, to -> sink.append(page, start + from, start + to, sessions) }
            index = end
            fill()
        }
    }

    private inner class Locations(private val range: TimeRange) {
        private var fixes: List<LocationFix> = emptyList()
        private var index = 0
        private var next: LocationKey? = null
        private var done = false

        suspend fun fill() {
            while (!done && index >= fixes.size) {
                val page = store.locations(rideId, range, next, PagePolicy.rows).orThrow()
                fixes = page.fixes
                index = 0
                next = page.next
                done = page.next == null
            }
        }

        fun headUs(): Long? = fixes.getOrNull(index)?.elapsedUs

        suspend fun take(): LocationFix = fixes[index++].also { fill() }
    }

    private inner class Health(private val range: TimeRange, private val stream: HealthStream) {
        private var samples: List<HealthSample> = emptyList()
        private var index = 0
        private var next: HealthKey? = null
        private var done = false

        suspend fun fill() {
            while (!done && index >= samples.size) {
                val page = store.health(rideId, range, stream.metric, next, PagePolicy.rows, stream.source).orThrow()
                samples = page.samples
                index = 0
                next = page.next
                done = page.next == null
            }
        }

        fun headUs(): Long? = samples.getOrNull(index)?.elapsedUs

        suspend fun take(): HealthSample = samples[index++].also { fill() }
    }
}
