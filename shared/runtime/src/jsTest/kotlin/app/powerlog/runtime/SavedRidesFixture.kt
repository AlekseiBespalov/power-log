@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.powerlog.runtime

import app.powerlog.application.store.Commit
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.data.WebStores
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.health.HealthOutcomeRules
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.runtime.clock.WebClock
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@JsExport
public object SavedRidesFixture {
    public fun write(archiveName: String, rides: String, done: (rideIds: String?, error: String?) -> Unit) {
        GlobalScope.launch {
            try {
                done(Json.encodeToString(written(archiveName, Json.decodeFromString<List<SavedRide>>(rides))), null)
            } catch (error: Throwable) {
                done(null, error.toString())
            }
        }
    }

    private suspend fun written(archiveName: String, rides: List<SavedRide>): List<String> {
        val clock = WebClock()
        val stores = WebStores.create(archiveName, Unlogged, clock::read)
        val opened = stores.host.open()
        check(opened is StoreResult.Ok) { "The archive did not open: $opened" }
        try {
            return rides.map { saved ->
                val batch = batch(saved)
                val commit = stores.writer.commit(batch)
                check((commit as? StoreResult.Ok)?.value is Commit.Committed) { "The ride was not saved: $commit" }
                (batch.mutations.first() as Mutation.CreateRide).ride.id.value
            }
        } finally {
            stores.host.close()
        }
    }

    private fun batch(saved: SavedRide): WriteBatch {
        val id = RideId("5a7ed000-0000-4000-8000-${Random.nextLong(0, 1L shl 48).toString(16).padStart(12, '0')}")
        val startUtcUs = (saved.startUtcMs * 1_000).roundToLong()
        val durationUs = (saved.durationMs * 1_000).roundToLong()
        val end = RideTiming(startUtcUs + durationUs, durationUs, durationUs)
        val options =
            RideOptions(
                SampleRate.entries.single { it.hz == saved.sampleHz },
                indoor = saved.indoor,
                useWatch = false,
                saveToHealth = false,
                recordGps = false,
                DistanceSource.controller,
                BatteryMethod.valueOf(saved.battery),
            )
        val ride =
            Ride(
                id,
                Device.browser,
                example = false,
                startUtcUs,
                options,
                RidePhase.completed,
                interrupted = false,
                checkpoint = end,
                end = end,
            )
        val events =
            (listOf(RideEventKind.start to 0L) +
                    saved.lapsMs.map { RideEventKind.lap to (it * 1_000).roundToLong() } +
                    listOf(RideEventKind.stop to durationUs))
                .mapIndexed { index, (kind, elapsedUs) ->
                    RideEvent(EventId("${id.value}:event:$index"), kind, startUtcUs + elapsedUs, elapsedUs)
                }
        val columns =
            (saved.columns.mapKeys { MetricId.valueOf(it.key) } +
                    (MetricId.humanPowerW to saved.powerW).takeIf { saved.powerW.isNotEmpty() }.let(::listOfNotNull))
                .filterValues { it.isNotEmpty() }
        val telemetry =
            saved.atMs.mapIndexed { index, atMs ->
                val elapsedUs = (atMs * 1_000).roundToLong()
                TelemetrySample(
                    id,
                    1,
                    index + 1L,
                    elapsedUs,
                    startUtcUs + elapsedUs,
                    1,
                    1,
                    columns.mapValues { (metric, values) ->
                        (values[index] * MetricCatalog.of(metric).quantum).roundToLong()
                    },
                )
            }
        return WriteBatch(
            listOfNotNull(
                Mutation.CreateRide(ride, HealthOutcomeRules.initial(options, null)),
                Mutation.OpenControllerSession(
                    ControllerSession(
                        id,
                        1,
                        ControllerIdentity(DecoderId("cyc"), "X6", "fixture", "5.3"),
                        ControllerCapabilities(
                            columns.keys.ifEmpty { setOf(MetricId.humanPowerW) },
                            controllerSpeed = MetricId.speedRaw in columns,
                        ),
                    )
                ),
                Mutation.AppendEvents(id, events),
                telemetry.takeIf { it.isNotEmpty() }?.let { Mutation.AppendTelemetry(id, TelemetryRows.of(it)) },
            )
        )
    }

    @Serializable
    private class SavedRide(
        val startUtcMs: Double,
        val durationMs: Double,
        val atMs: List<Double> = emptyList(),
        val powerW: List<Double> = emptyList(),
        val lapsMs: List<Double> = emptyList(),
        val sampleHz: Int = 2,
        val indoor: Boolean = true,
        val battery: String = "controller",
        val columns: Map<String, List<Double>> = emptyMap(),
    )
}
