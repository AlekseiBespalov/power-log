package app.powerlog.application.charts

import app.powerlog.application.Derivations
import app.powerlog.application.DerivedSink
import app.powerlog.application.DerivedStates
import app.powerlog.application.FactSink
import app.powerlog.application.store.CacheRepository
import app.powerlog.domain.charts.DerivedResume
import app.powerlog.domain.charts.PlotAggregate
import app.powerlog.domain.charts.PlotReducer
import app.powerlog.domain.charts.PlotTiles
import app.powerlog.domain.core.analysis.battery.BatteryConsumptionState
import app.powerlog.domain.core.analysis.battery.ConsumptionInterval
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.battery.ConsumptionReducer
import app.powerlog.domain.core.analysis.battery.ConsumptionTotals
import app.powerlog.domain.core.analysis.distance.ControllerDistanceState
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.distance.GpsDistanceState
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange

internal class DerivedTiles(private val cache: CacheRepository) {
    class Resumed(
        val startUs: Long,
        val states: DerivedStates,
        val distance: Map<DistanceSource, DistanceTotals>,
        val consumption: Map<ConsumptionMetric, ConsumptionTotals>,
    )

    suspend fun resume(
        ride: ChartTiles.Ride,
        metrics: List<MetricId>,
        frozen: DistanceSource,
        beforeUs: Long,
    ): Resumed? {
        val derived = metrics.filter(ChartMetrics::derived).distinct()
        val last = beforeUs.floorDiv(BASE_US) - 1
        if (derived.isEmpty() || last < 0) return null
        val window = maxOf(0L, last - WINDOW + 1)..last
        val found = derived.associateWith { metric ->
            cache.tiles(ride.rideId, metric, 0, window, ride.revision).filterValues { it.resume != null }
        }
        val part = found.values.map { it.keys }.reduce { a, b -> a intersect b }.maxOrNull() ?: return null
        var controller: ControllerDistanceState? = null
        var gps: GpsDistanceState? = null
        var battery: BatteryConsumptionState? = null
        val distance = mutableMapOf<DistanceSource, DistanceTotals>()
        val consumption = mutableMapOf<ConsumptionMetric, ConsumptionTotals>()
        for ((metric, tiles) in found) when (val resume = tiles.getValue(part).resume) {
            is DerivedResume.Distance -> {
                controller = resume.controller ?: controller
                gps = resume.gps ?: gps
                distance[ChartMetrics.distanceSource(metric, frozen)] = resume.totals
            }
            is DerivedResume.Consumption -> {
                battery = resume.state
                consumption[ChartMetrics.consumptionMetric(metric)] = resume.totals
            }
            null -> Unit
        }
        return Resumed((part + 1) * BASE_US, DerivedStates(controller, gps, battery), distance, consumption)
    }

    fun capture(
        ride: ChartTiles.Ride,
        metrics: List<MetricId>,
        frozen: DistanceSource,
        resumed: Resumed?,
        startUs: Long,
    ): Capture = Capture(ride, metrics.filter(ChartMetrics::derived).distinct(), frozen, resumed, startUs)

    inner class Capture(
        private val ride: ChartTiles.Ride,
        private val metrics: List<MetricId>,
        private val frozen: DistanceSource,
        resumed: Resumed?,
        startUs: Long,
    ) {
        private val distances =
            DistanceSource.entries.associateWith {
                DistanceReducer(TimeRange(0, Long.MAX_VALUE), resumed?.distance?.get(it))
            }
        private val consumption =
            ConsumptionMetric.entries.associateWith {
                ConsumptionReducer(it, TimeRange(0, Long.MAX_VALUE), resumed?.consumption?.get(it))
            }
        private val plots = mutableMapOf<MetricId, PlotReducer>()
        private val built = metrics.associateWith { mutableMapOf<Long, PlotAggregate>() }
        private var part = startUs.floorDiv(BASE_US)
        private var lastDistance = mutableMapOf<DistanceSource, DistanceInterval>()
        private var lastBattery = mutableMapOf<ConsumptionMetric, ConsumptionInterval>()

        val sink: DerivedSink =
            object : DerivedSink {
                override fun distance(source: DistanceSource, interval: DistanceInterval) {
                    distances.getValue(source).append(interval)
                    lastDistance[source] = interval
                    for (metric in metrics) if (
                        ChartMetrics.kind(metric) == ChartKind.distance &&
                            ChartMetrics.distanceSource(metric, frozen) == source
                    )
                        plot(metric).distance(interval)
                }

                override fun battery(interval: ConsumptionInterval) {
                    consumption.getValue(interval.metric).append(interval)
                    lastBattery[interval.metric] = interval
                    for (metric in metrics) if (ChartMetrics.kind(metric) == ChartKind.consumption)
                        plot(metric).battery(interval)
                }
            }

        fun wrap(derivations: Derivations): FactSink =
            object : FactSink {
                override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
                    var start = from
                    while (start < to) {
                        while (rows.elapsedUs[start].toLong() >= boundary()) close(derivations)
                        var end = start + 1
                        while (end < to && rows.elapsedUs[end].toLong() < boundary()) end++
                        derivations.append(rows, start, end, sessions)
                        start = end
                    }
                }

                override fun append(fact: Fact) {
                    val elapsedUs =
                        when (fact) {
                            is Fact.Location -> fact.fix.elapsedUs
                            is Fact.Health -> fact.sample.elapsedUs
                            is Fact.Event -> fact.event.elapsedUs
                        }
                    while (elapsedUs >= boundary()) close(derivations)
                    derivations.append(fact)
                }
            }

        suspend fun put() {
            if (metrics.isEmpty()) return
            for ((metric, tiles) in built) {
                if (tiles.isEmpty()) continue
                val newestUs = newestUs(metric)
                val final = tiles.filterKeys { (it + 1) * BASE_US <= newestUs - 1 - ChartsPolicy.finalTileLagUs }
                if (final.isNotEmpty()) cache.putTiles(ride.rideId, metric, 0, 0, final)
                val others = tiles - final.keys
                if (!ride.recording && others.isNotEmpty())
                    cache.putTiles(ride.rideId, metric, 0, ride.revision, others)
            }
        }

        private fun boundary(): Long = (part + 1) * BASE_US

        private fun close(derivations: Derivations) {
            val states = derivations.states()
            for (metric in metrics) {
                val aggregate = plots.remove(metric)?.aggregate() ?: slotReducer(metric).aggregate()
                val resume =
                    if (ChartMetrics.kind(metric) == ChartKind.distance) {
                        val totals =
                            distances.getValue(ChartMetrics.distanceSource(metric, frozen)).result(0) as? Bounded.Done
                                ?: continue
                        DerivedResume.Distance(states.controller, states.gps, totals.value)
                    } else
                        DerivedResume.Consumption(
                            requireNotNull(states.battery),
                            consumption.getValue(ChartMetrics.consumptionMetric(metric)).result(0),
                        )
                built.getValue(metric)[part] = aggregate.copy(resume = resume)
            }
            part++
        }

        private fun plot(metric: MetricId): PlotReducer = plots.getOrPut(metric) { slotReducer(metric) }

        private fun slotReducer(metric: MetricId): PlotReducer {
            val reducer = PlotReducer(metric, TimeRange(part * BASE_US, (part + 1) * BASE_US), BASE_US, 1)
            if (ChartMetrics.kind(metric) == ChartKind.distance)
                lastDistance[ChartMetrics.distanceSource(metric, frozen)]?.let(reducer::distance)
            else lastBattery[ChartMetrics.consumptionMetric(metric)]?.let(reducer::battery)
            return reducer
        }

        private fun newestUs(metric: MetricId): Long {
            val telemetry = ride.extent.telemetry?.endUs ?: 0L
            val locations = ride.extent.locations?.endUs ?: 0L
            return when {
                ChartMetrics.kind(metric) == ChartKind.consumption -> telemetry
                ChartMetrics.distanceSource(metric, frozen) == DistanceSource.phoneGps -> locations
                else -> telemetry
            }
        }
    }

    private companion object {
        const val BASE_US = PlotTiles.baseSlotUs
        const val WINDOW = 64L
    }
}
