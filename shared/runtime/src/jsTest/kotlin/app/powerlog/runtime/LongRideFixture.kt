@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.powerlog.runtime

import app.powerlog.application.store.Commit
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.data.WebStores
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.runtime.clock.WebClock
import app.powerlog.runtime.dev.ExampleRideGenerator
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@JsExport
public object LongRideFixture {
    public fun write(archiveName: String, durationUs: Double?, done: (rideId: String?, error: String?) -> Unit) {
        GlobalScope.launch {
            try {
                done(written(archiveName, durationUs?.toLong()), null)
            } catch (error: Throwable) {
                done(null, error.toString())
            }
        }
    }

    private suspend fun written(archiveName: String, cutUs: Long?): String {
        val clock = WebClock()
        val stores = WebStores.create(archiveName, Unlogged, clock::read)
        val opened = stores.host.open()
        check(opened is StoreResult.Ok) { "The archive did not open: $opened" }
        try {
            val generator = ExampleRideGenerator({ true }, { Device.browser })
            val rideId = generator.rideIds.last()
            var index = 0
            while (true) {
                val batch = generator.batch(rideId, index++) ?: break
                val kept = if (cutUs == null) batch else cut(batch, cutUs)
                if (kept.mutations.isEmpty()) break
                val commit = stores.writer.commit(kept)
                check((commit as? StoreResult.Ok)?.value is Commit.Committed) { "Batch ${index - 1}: $commit" }
            }
            return rideId.value
        } finally {
            stores.host.close()
        }
    }

    private fun cut(batch: WriteBatch, cutUs: Long): WriteBatch {
        val events = batch.mutations.filterIsInstance<Mutation.AppendEvents>().flatMap { it.events }
        return WriteBatch(
            batch.mutations.mapNotNull { mutation ->
                when (mutation) {
                    is Mutation.CreateRide -> {
                        val endUs = mutation.ride.end?.elapsedUs
                        if (endUs != null && endUs <= cutUs) mutation
                        else {
                            val end = RideTiming(mutation.ride.startedUtcUs + cutUs, cutUs, timerUs(events, cutUs))
                            mutation.copy(ride = mutation.ride.copy(checkpoint = end, end = end))
                        }
                    }
                    is Mutation.AppendEvents -> {
                        val stop = mutation.events.firstOrNull { it.kind == RideEventKind.stop }
                        if (stop == null || stop.elapsedUs <= cutUs) mutation
                        else {
                            val startedUtcUs = stop.utcUs - stop.elapsedUs
                            val kept = mutation.events.filter { it.elapsedUs < cutUs }
                            mutation.copy(events = kept + stop.copy(utcUs = startedUtcUs + cutUs, elapsedUs = cutUs))
                        }
                    }
                    is Mutation.AppendTelemetry -> {
                        val rows = mutation.rows
                        val kept = (0 until rows.rows).count { rows.elapsedUs[it] < cutUs }
                        if (kept == 0) null else mutation.copy(rows = rows.first(kept))
                    }
                    is Mutation.AppendLocations ->
                        mutation.fixes
                            .filter { it.elapsedUs < cutUs }
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                mutation.copy(fixes = it)
                            }
                    is Mutation.AppendHealthSamples ->
                        mutation.samples
                            .filter { it.elapsedUs < cutUs }
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                mutation.copy(samples = it)
                            }
                    else -> mutation
                }
            }
        )
    }

    private fun timerUs(events: List<RideEvent>, cutUs: Long): Long {
        var timer = 0L
        var since: Long? = null
        for (event in events.sortedBy { it.elapsedUs }) {
            if (event.elapsedUs >= cutUs) break
            when (event.kind) {
                RideEventKind.start,
                RideEventKind.resume -> if (since == null) since = event.elapsedUs
                RideEventKind.pause,
                RideEventKind.stop -> {
                    timer += event.elapsedUs - (since ?: event.elapsedUs)
                    since = null
                }
                RideEventKind.lap -> Unit
            }
        }
        return timer + (since?.let { cutUs - it } ?: 0L)
    }

    private fun TelemetryRows.first(count: Int): TelemetryRows =
        TelemetryRows(
            count,
            elapsedUs.copyOf(count),
            sessionNo.copyOf(count),
            seq.copyOf(count),
            utcUs.copyOf(count),
            activeInterval.copyOf(count),
            run.copyOf(count),
            quanta.mapValues { it.value.copyOf(count) },
        )
}

internal object Unlogged : DiagnosticLogRepository {
    override suspend fun append(line: LogLine) = Unit

    override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> =
        StoreResult.Ok(LogChunk(ByteArray(0), null))
}
