package app.powerlog.application.charts

import app.powerlog.application.Derivations
import app.powerlog.application.DerivedSink
import app.powerlog.application.Executors
import app.powerlog.application.FactReader
import app.powerlog.application.FactSink
import app.powerlog.application.FactStreams
import app.powerlog.application.HealthStream
import app.powerlog.application.PagePolicy
import app.powerlog.application.RideSummaries
import app.powerlog.application.StoreFailure
import app.powerlog.application.SummaryAnswer
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.api.StreamPolicy
import app.powerlog.application.api.failure
import app.powerlog.application.api.readFailure
import app.powerlog.application.orThrow
import app.powerlog.application.port.Clock
import app.powerlog.application.recording.LiveReadings
import app.powerlog.application.recording.LiveSnapshot
import app.powerlog.application.store.CommitNotice
import app.powerlog.application.store.Extent
import app.powerlog.application.store.HealthSourceFilter
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.MemoryMeasurementRepository
import app.powerlog.application.store.NearestTarget
import app.powerlog.application.store.RideRecord
import app.powerlog.application.store.RideRepository
import app.powerlog.domain.charts.EnergyPerDistancePlotReducer
import app.powerlog.domain.charts.EnergyPerDistanceSeries
import app.powerlog.domain.charts.InspectionReducer
import app.powerlog.domain.charts.InspectionTarget
import app.powerlog.domain.charts.PlotPoint
import app.powerlog.domain.charts.PlotReducer
import app.powerlog.domain.charts.PlotSlots
import app.powerlog.domain.charts.PlotTiles
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.battery.ConsumptionInterval
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.battery.ConsumptionReducer
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.energyperdistance.EnergyPerDistancePolicy
import app.powerlog.domain.core.analysis.energyperdistance.EnergyPerDistanceReducer
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.summary.RideSummary
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.TimeRange
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalAtomicApi::class)
internal class Charts(
    private val executors: Executors,
    private val clock: Clock,
    private val rides: RideRepository,
    private val archive: MeasurementRepository,
    private val memory: MemoryMeasurementRepository,
    private val commits: Flow<CommitNotice>,
    private val live: LiveReadings,
    private val summaries: RideSummaries,
    private val tiles: ChartTiles,
    private val derivedTiles: DerivedTiles,
    private val refuse: () -> Unit,
) {
    private class Gone : Exception()

    private class Capacity : Exception()

    private class Source(
        val store: MeasurementRepository,
        val id: RideId,
        val record: RideRecord?,
        val extent: Extent,
        val sessions: Map<Int, ControllerSession>,
        val events: List<RideEvent>,
        val originMonoUs: Long?,
        val range: TimeRange,
        val streamRevision: Long,
        val derived: ChartMetrics.Derived?,
        val imported: Boolean = false,
    ) {
        val revision: Long = extent.revision
        val live: Boolean = originMonoUs != null
        val reader = FactReader(store, id, sessions)
        val metrics: List<MetricId> =
            ChartMetrics.available(
                sessions.values,
                extent.locations != null,
                extent.healthMetrics.mapNotNullTo(mutableSetOf(), HealthMetrics::metricId),
                derived,
            )
    }

    private val previews = AtomicReference<Pair<RideId, Long>?>(null)
    private val energySeries = AtomicReference<List<HeldSeries>>(emptyList())
    private val origins = AtomicReference<Map<Pair<RideId, HealthMetric>, Pair<Long, String?>>>(emptyMap())

    suspend fun head(input: ChartHeadInput, after: Long?): StreamAnswer<ChartHeadView> =
        withContext(executors.reads) {
            coroutineScope {
                val changed = Channel<Unit>(Channel.CONFLATED)
                val rideId = (input.source as? ChartSource.Ride)?.rideId
                val watching = rideId?.let { id ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        commits.filter { id in it.revisions || id in it.historyRides }.collect { changed.trySend(Unit) }
                    }
                }
                try {
                    nextHead(input.source, after, changed)
                } finally {
                    watching?.cancel()
                }
            }
        }

    suspend fun plot(input: PlotInput): Outcome<PlotView> =
        query(input.source, executors.reads) { source ->
            val range = clamp(input.rangeUs ?: source.range, source)
            val buckets = PlotSlots.buckets(input.buckets, input.metrics.size)
            val slot = PlotSlots.alignedSlotUs(range, buckets)
            val plotted = plotted(source, input.metrics, range, slot, buckets, input.smoothingUs)
            Outcome.Ok(PlotView(source.revision, range, slot, input.metrics.map { series(it, plotted.getValue(it)) }))
        }

    suspend fun latest(input: LatestInput): Outcome<LatestView> =
        query(input.source, executors.reads) { source ->
            val snapshot = live.snapshot()
            val summary = lazySummary(source)
            Outcome.Ok(
                LatestView(
                    source.revision,
                    input.metrics.map { reading(source, it, snapshot, summary, input.smoothingUs) },
                )
            )
        }

    suspend fun inspect(input: InspectInput): Outcome<InspectView> =
        query(input.source, executors.reads) { source ->
            Outcome.Ok(InspectView(source.revision, input.atUs, input.metrics.map { inspected(source, it, input) }))
        }

    suspend fun statistics(input: StatisticsInput): Outcome<StatisticsView> =
        query(input.source, executors.reads) { source ->
            val range = clamp(input.rangeUs, source)
            val nextUs =
                when {
                    !source.live -> null
                    input.whPerKmCharted -> EnergyPerDistancePolicy.totalRefreshUs(range.endUs - range.startUs)
                    else -> ChartsPolicy.statisticsRefreshUs
                }
            Outcome.Ok(StatisticsView(source.revision, statistics(source, input.metrics, range), nextUs))
        }

    private suspend fun <T> query(
        chart: ChartSource,
        executor: CoroutineDispatcher,
        block: suspend (Source) -> Outcome<T>,
    ): Outcome<T> =
        withContext(executor) {
            try {
                block(source(chart))
            } catch (_: Gone) {
                Outcome.Failed(failure(FailureCode.rideDeleted))
            } catch (_: Capacity) {
                Outcome.Failed(failure(FailureCode.chartCapacity))
            } catch (failed: StoreFailure) {
                Outcome.Failed(mapped(failed))
            }
        }

    private suspend fun nextHead(
        chart: ChartSource,
        after: Long?,
        changed: Channel<Unit>,
    ): StreamAnswer<ChartHeadView> {
        while (true) {
            val view =
                try {
                    source(chart).let { view(it) to it.streamRevision }
                } catch (_: Gone) {
                    return StreamAnswer.Failed(failure(FailureCode.rideDeleted))
                } catch (failed: StoreFailure) {
                    return StreamAnswer.Failed(mapped(failed))
                }
            val revision = view.second
            if (after == null || revision > after) return StreamAnswer.Value(revision, view.first)
            if (view.first.live || chart !is ChartSource.Ride) delay(StreamPolicy.chartsHeadUs / 1_000)
            else changed.receive()
        }
    }

    private fun view(source: Source): ChartHeadView =
        ChartHeadView(
            revision = source.revision,
            rangeUs = source.range,
            live = source.live,
            originMonoUs = source.originMonoUs,
            metrics = source.metrics,
            catchingUp = tiles.catchingUp(source.id),
        )

    private suspend fun source(chart: ChartSource): Source {
        val snapshot = live.snapshot()
        return when (chart) {
            is ChartSource.Ride -> {
                val record = rides.ride(chart.rideId).orThrow() ?: throw Gone()
                val extent = archive.extent(chart.rideId).orThrow() ?: throw Gone()
                val ride = record.ride
                val origin =
                    snapshot.rideOriginMonoUs.takeIf { snapshot.rideId == ride.id && ride.phase in recordingPhases }
                val endUs =
                    if (origin != null) maxOf(TEN_SECONDS_US, clock.monoUs() - origin)
                    else maxOf(TEN_SECONDS_US, ride.end?.elapsedUs ?: 0L, newest(extent))
                Source(
                    store = archive,
                    id = ride.id,
                    record = record,
                    extent = extent,
                    sessions = archive.sessions(ride.id).orThrow().associateBy { it.sessionNo },
                    events = archive.events(ride.id).orThrow(),
                    originMonoUs = origin,
                    range = TimeRange(0, endUs),
                    streamRevision = extent.revision,
                    derived = ChartMetrics.Derived(ride.options.distanceSource, ride.options.batteryMethod),
                )
            }
            ChartSource.Preview -> {
                val previewId = snapshot.previewId ?: throw Gone()
                val extent = memory.extent(previewId).orThrow() ?: throw Gone()
                val origin = snapshot.previewOriginMonoUs
                val endUs =
                    if (origin != null) maxOf(TEN_SECONDS_US, clock.monoUs() - origin)
                    else maxOf(TEN_SECONDS_US, newest(extent))
                Source(
                    store = memory,
                    id = previewId,
                    record = null,
                    extent = extent,
                    sessions = memory.sessions(previewId).orThrow().associateBy { it.sessionNo },
                    events = emptyList(),
                    originMonoUs = origin,
                    range = TimeRange(0, endUs),
                    streamRevision = epoch(previewId) * PREVIEW_REVISIONS + extent.revision,
                    derived = ChartMetrics.Derived(DistanceSource.controller, BatteryMethod.controller),
                )
            }
            is ChartSource.Imported -> {
                val extent = memory.extent(chart.importId).orThrow() ?: throw Gone()
                val startUs = extent.telemetry?.startUs ?: 0L
                Source(
                    store = memory,
                    id = chart.importId,
                    record = null,
                    extent = extent,
                    sessions = memory.sessions(chart.importId).orThrow().associateBy { it.sessionNo },
                    events = emptyList(),
                    originMonoUs = null,
                    range = TimeRange(startUs, maxOf(startUs + 1, TEN_SECONDS_US, newest(extent))),
                    streamRevision = extent.revision,
                    derived = null,
                    imported = true,
                )
            }
        }
    }

    private fun newest(extent: Extent): Long =
        maxOf(extent.telemetry?.endUs ?: 0L, extent.locations?.endUs ?: 0L, extent.health?.endUs ?: 0L)

    private fun clamp(range: TimeRange, source: Source): TimeRange {
        val clamped = TimeRange(range.startUs, minOf(range.endUs, source.range.endUs))
        require(clamped.startUs <= clamped.endUs) { "a chart range starts before its source ends: $range" }
        return clamped
    }

    private fun requireCharted(source: Source, metric: MetricId) {
        val kind = ChartMetrics.kind(metric)
        require(
            source.derived != null ||
                kind == ChartKind.telemetry ||
                kind == ChartKind.location ||
                kind == ChartKind.health
        ) {
            "an imported ride charts its columns only: $metric"
        }
    }

    // A new preview's data revision starts again, so each preview's stream revisions sit above the previous one's.
    private fun epoch(previewId: RideId): Long {
        while (true) {
            val current = previews.load()
            if (current?.first == previewId) return current.second
            val next = previewId to (current?.second ?: 0L) + 1
            if (previews.compareAndSet(current, next)) return next.second
        }
    }

    private suspend fun plotted(
        source: Source,
        metrics: List<MetricId>,
        range: TimeRange,
        slot: Long?,
        buckets: Int,
        smoothingUs: Long,
    ): Map<MetricId, List<PlotPoint>> {
        metrics.forEach { requireCharted(source, it) }
        val reducers = metrics.associateWith { PlotReducer(it, range, slot, buckets) }
        val level = slot?.let(PlotTiles::level)
        val measuredAll = metrics.filter {
            ChartMetrics.kind(it) == ChartKind.telemetry || ChartMetrics.kind(it) == ChartKind.location
        }
        val aligned =
            slot != null &&
                range.startUs % slot == 0L &&
                (range.endUs % slot == 0L || range.endUs == source.range.endUs)
        val tiled =
            if (level != null && aligned && (source.record != null || source.imported)) measuredAll else emptyList()
        for (metric in tiled) tiledPlot(
            source,
            metric,
            range,
            requireNotNull(slot),
            requireNotNull(level),
            reducers.getValue(metric),
        )
        val measured = measuredAll - tiled.toSet()
        for (metric in measured) neighbour(
            source,
            ChartMetrics.target(metric),
            range.startUs,
            true,
            reducers.getValue(metric).sink(),
        )
        if (measured.isNotEmpty())
            scan(source, range, streams(measured), Fanout(measured.map { reducers.getValue(it).sink() }))
        for (metric in measured) neighbour(
            source,
            ChartMetrics.target(metric),
            range.endUs,
            false,
            reducers.getValue(metric).sink(),
        )
        for (metric in metrics.filter { ChartMetrics.kind(it) == ChartKind.health }) {
            val stream = healthStream(source, metric) ?: continue
            val reducer = reducers.getValue(metric)
            val target = NearestTarget.Health(setOfNotNull(stream.metric), stream.source)
            neighbour(source, target, range.startUs, true, reducer.sink())
            scan(source, range, FactStreams(false, false, stream), reducer.sink())
            neighbour(source, target, range.endUs, false, reducer.sink())
        }
        val derived = metrics.filter { ChartMetrics.derived(it) }
        if (derived.isNotEmpty()) {
            val frozen = requireNotNull(source.derived).distance
            val sinks = derived.map { DerivedPlot(it, frozen, reducers.getValue(it)) }
            derivedPass(
                source,
                derived,
                range.startUs - PlotTiles.baseSlotUs,
                range.endUs + DERIVED_LOOKAHEAD_US,
                sinks,
            )
        }
        val whole = range.startUs == 0L && range.endUs == source.range.endUs && slot != null && source.record != null
        val energyMetrics = metrics.filter { ChartMetrics.kind(it) == ChartKind.energyPerDistance }
        val distanceSource = source.derived?.distance
        val held =
            if (whole)
                energyMetrics.associateWith { metric ->
                    heldSeries(source, metric, requireNotNull(distanceSource), smoothingUs)
                }
            else emptyMap()
        val energy = energyMetrics.associateWith { metric ->
            held[metric]?.series?.begin(range.endUs, buckets, slot)
                ?: EnergyPerDistancePlotReducer(
                    metric,
                    requireNotNull(distanceSource),
                    range,
                    buckets,
                    slot,
                    smoothingUs,
                )
        }
        if (held.isNotEmpty())
            for ((metric, series) in held) {
                val start = maxOf(0L, series.series.throughUs + 1)
                scan(
                    source,
                    TimeRange(start, range.endUs),
                    FactStreams(true, true, null),
                    energy.getValue(metric).sink(),
                )
                keep(series)
            }
        else if (energy.isNotEmpty()) {
            val start = maxOf(0L, range.startUs - EnergyPerDistancePolicy.contextUs(smoothingUs))
            scan(
                source,
                TimeRange(start, range.endUs),
                FactStreams(true, true, null),
                Fanout(energy.values.map { it.sink() }),
            )
        }
        return metrics.associateWith { metric ->
            val result = energy[metric]?.result() ?: reducers.getValue(metric).result()
            when (result) {
                is Bounded.Done -> result.value
                Bounded.Capacity -> throw Capacity()
            }
        }
    }

    private suspend fun tiledPlot(
        source: Source,
        metric: MetricId,
        range: TimeRange,
        slot: Long,
        level: Int,
        reducer: PlotReducer,
    ) {
        val parts = PlotTiles.parts(range, level)
        if (parts.isEmpty()) return
        val target = ChartMetrics.target(metric)
        val record = source.record
        val ride =
            ChartTiles.Ride(
                source.id,
                source.revision,
                source.extent,
                source.live || record != null && record.ride.phase in openPhases,
                source.reader,
                memory = source.imported,
            )
        val built =
            tiles.tiles(ride, metric, level, slot, parts) { neighbour(source, target, parts.first * slot, true, it) }
        neighbour(source, target, parts.first * slot, true, reducer.sink())
        for (part in parts) reducer.merge(built.getValue(part))
        neighbour(source, target, (parts.last + 1) * slot, false, reducer.sink())
    }

    private suspend fun derivedPass(
        source: Source,
        metrics: List<MetricId>,
        beforeUs: Long,
        endUs: Long,
        sinks: List<DerivedSink>,
    ) {
        val frozen = requireNotNull(source.derived).distance
        val record = source.record
        val streams = FactStreams(true, true, null)
        if (record == null) {
            scan(source, TimeRange(0, endUs), streams, Derivations(sinks))
            return
        }
        val ride =
            ChartTiles.Ride(
                source.id,
                source.revision,
                source.extent,
                source.live || record.ride.phase in openPhases,
                source.reader,
            )
        val resumed = derivedTiles.resume(ride, metrics, frozen, beforeUs)
        val startUs = resumed?.startUs ?: 0L
        val capture = derivedTiles.capture(ride, metrics, frozen, resumed, startUs)
        val derivations = Derivations(sinks + capture.sink, resumed?.states)
        scan(source, TimeRange(startUs, endUs), streams, capture.wrap(derivations))
        capture.put()
    }

    private class HeldSeries(val key: List<Any>, val revision: Long, val series: EnergyPerDistanceSeries)

    private fun heldSeries(source: Source, metric: MetricId, distance: DistanceSource, smoothingUs: Long): HeldSeries {
        val key = listOf(source.id, metric, distance, smoothingUs)
        while (true) {
            val current = energySeries.load()
            val found = current.firstOrNull { it.key == key }
            if (found == null || energySeries.compareAndSet(current, current - found)) {
                val valid = found?.takeIf { it.revision == source.revision || source.live }
                return HeldSeries(
                    key,
                    source.revision,
                    valid?.series ?: EnergyPerDistanceSeries(metric, distance, smoothingUs),
                )
            }
        }
    }

    private fun keep(held: HeldSeries) {
        while (true) {
            val current = energySeries.load()
            val next = (listOf(held) + current.filter { it.key != held.key }).take(ChartsPolicy.energySeries)
            if (energySeries.compareAndSet(current, next)) return
        }
    }

    fun closed(importId: RideId) = tiles.drop(importId)

    private suspend fun scan(source: Source, range: TimeRange, streams: FactStreams, sink: FactSink) =
        withContext(executors.scans) { source.reader.feed(range, streams, sink) }

    private fun streams(metrics: List<MetricId>): FactStreams =
        FactStreams(
            telemetry = metrics.any { ChartMetrics.kind(it) == ChartKind.telemetry },
            locations = metrics.any { ChartMetrics.kind(it) == ChartKind.location },
            health = null,
        )

    private suspend fun neighbour(source: Source, target: NearestTarget, atUs: Long, before: Boolean, sink: FactSink) {
        val elapsedUs = source.store.nearest(source.id, target, atUs, before, Long.MAX_VALUE).orThrow() ?: return
        feedAt(source, target, elapsedUs, sink)
    }

    private suspend fun feedAt(source: Source, target: NearestTarget, elapsedUs: Long, sink: FactSink) {
        val at = TimeRange(elapsedUs, elapsedUs + 1)
        when (target) {
            is NearestTarget.Telemetry -> {
                val page = source.store.telemetry(source.id, at, null, PagePolicy.rows).orThrow()
                sink.append(page.rows, 0, page.rows.rows, source.sessions)
            }
            is NearestTarget.Location ->
                source.store.locations(source.id, at, null, PagePolicy.rows).orThrow().fixes.forEach {
                    sink.append(Fact.Location(it))
                }
            is NearestTarget.Health ->
                source.store
                    .health(source.id, at, target.metrics.singleOrNull(), null, PagePolicy.rows, target.source)
                    .orThrow()
                    .samples
                    .forEach { sink.append(Fact.Health(it)) }
        }
    }

    private suspend fun reading(
        source: Source,
        metric: MetricId,
        snapshot: LiveSnapshot,
        summary: suspend () -> RideSummary?,
        smoothingUs: Long,
    ): LatestReading {
        requireCharted(source, metric)
        val definition = MetricCatalog.of(metric)
        val none = LatestReading(metric, null, null, null, null, null)
        return when (ChartMetrics.kind(metric)) {
            ChartKind.distance,
            ChartKind.consumption ->
                if (source.live) {
                    val total = snapshot.totals[metric] ?: return none
                    LatestReading(
                        metric,
                        total.value,
                        total.elapsedUs,
                        null,
                        total.acquiredMonoUs + definition.tailUs,
                        null,
                    )
                } else {
                    val totals = summary() ?: return none
                    val value = total(totals, metric, requireNotNull(source.derived).distance) ?: return none
                    LatestReading(metric, value, totals.end.elapsedUs, null, null, null)
                }
            ChartKind.energyPerDistance ->
                if (source.live) {
                    val newest = newestEnergy(source, metric, smoothingUs)
                    LatestReading(
                        metric,
                        newest?.let { it.valueQ / definition.quantum.toDouble() },
                        newest?.elapsedUs,
                        null,
                        null,
                        snapshot.rideAverage[metric].takeIf { source.record != null },
                    )
                } else none.copy(rideAverage = summary()?.energyPerDistance?.get(metric)?.whPerKm)
            else ->
                if (source.live) {
                    val newest = snapshot.newest[metric] ?: return none
                    LatestReading(
                        metric,
                        newest.value,
                        newest.elapsedUs,
                        definition.displayHoldUs?.let { newest.acquiredMonoUs + it },
                        newest.acquiredMonoUs + definition.tailUs,
                        null,
                    )
                } else {
                    val original = newestStored(source, metric) ?: return none
                    LatestReading(
                        metric,
                        original.valueQ / definition.quantum.toDouble(),
                        original.elapsedUs,
                        null,
                        null,
                        null,
                    )
                }
        }
    }

    private suspend fun newestStored(source: Source, metric: MetricId): OriginalRef? {
        val stream =
            if (ChartMetrics.kind(metric) == ChartKind.health) healthStream(source, metric) ?: return null else null
        val target =
            stream?.let { NearestTarget.Health(setOfNotNull(it.metric), it.source) } ?: ChartMetrics.target(metric)
        val elapsedUs =
            source.store.nearest(source.id, target, source.range.endUs, true, Long.MAX_VALUE).orThrow() ?: return null
        val newest = Newest(metric)
        feedAt(source, target, elapsedUs, newest)
        return newest.last
    }

    private suspend fun newestEnergy(source: Source, metric: MetricId, smoothingUs: Long): OriginalRef? {
        val nowUs = source.range.endUs
        val range = TimeRange(maxOf(0L, nowUs - LIVE_ENERGY_WINDOW_US), nowUs + 1)
        val reducer =
            EnergyPerDistancePlotReducer(metric, requireNotNull(source.derived).distance, range, 1, null, smoothingUs)
        val start = maxOf(0L, range.startUs - EnergyPerDistancePolicy.contextUs(smoothingUs))
        scan(source, TimeRange(start, range.endUs), FactStreams(true, true, null), reducer.sink())
        val last =
            when (val result = reducer.result()) {
                is Bounded.Done -> result.value.lastOrNull()?.last
                Bounded.Capacity -> throw Capacity()
            }
        return last?.takeIf { it.elapsedUs >= range.startUs }
    }

    private fun lazySummary(source: Source): suspend () -> RideSummary? {
        var loaded = false
        var summary: RideSummary? = null
        return {
            if (!loaded) {
                loaded = true
                val record = source.record
                summary =
                    if (record == null || source.live || !summaries.summarized(record.ride)) null
                    else
                        when (val answer = summaries.of(record.ride, record.revision)) {
                            is SummaryAnswer.Ready -> answer.summary
                            SummaryAnswer.Unavailable -> null
                        }
            }
            summary
        }
    }

    private fun total(summary: RideSummary, metric: MetricId, frozen: DistanceSource): Double? =
        when (ChartMetrics.kind(metric)) {
            ChartKind.distance ->
                summary.distance[ChartMetrics.distanceSource(metric, frozen)]
                    ?.takeIf { it.coveredUs > 0 }
                    ?.let { it.distanceQ / MetricCatalog.of(metric).quantum.toDouble() }
            ChartKind.consumption ->
                summary.battery[ChartMetrics.consumptionMetric(metric)]?.takeIf { it.available }?.amount
            else -> null
        }

    private suspend fun inspected(source: Source, metric: MetricId, input: InspectInput): InspectedValue {
        requireCharted(source, metric)
        val definition = MetricCatalog.of(metric)
        val gapUs = definition.plotGapUs
        val atUs = input.atUs
        if (ChartMetrics.kind(metric) == ChartKind.energyPerDistance) {
            val range = TimeRange(maxOf(0L, atUs - gapUs), atUs + 1)
            val reducer =
                EnergyPerDistancePlotReducer(
                    metric,
                    requireNotNull(source.derived).distance,
                    range,
                    1,
                    null,
                    input.smoothingUs,
                )
            val start = maxOf(0L, range.startUs - EnergyPerDistancePolicy.contextUs(input.smoothingUs))
            scan(source, TimeRange(start, range.endUs), FactStreams(true, true, null), reducer.sink())
            val last =
                when (val result = reducer.result()) {
                    is Bounded.Done -> result.value.lastOrNull()?.last?.takeIf { it.elapsedUs >= range.startUs }
                    Bounded.Capacity -> throw Capacity()
                }
            return InspectedValue(metric, last?.let { original(metric, it) }, false)
        }
        if (ChartMetrics.derived(metric)) {
            val reducer = InspectionReducer(metric, InspectionTarget(atUs, null), null, source.live)
            val frozen = requireNotNull(source.derived).distance
            derivedPass(
                source,
                listOf(metric),
                atUs - gapUs - PlotTiles.baseSlotUs,
                atUs + gapUs + DERIVED_LOOKAHEAD_US,
                listOf(DerivedInspection(metric, frozen, reducer)),
            )
            return inspection(metric, reducer)
        }
        val stream = if (ChartMetrics.kind(metric) == ChartKind.health) healthStream(source, metric) else null
        if (ChartMetrics.kind(metric) == ChartKind.health && stream?.metric == null)
            return InspectedValue(metric, null, false)
        val target =
            stream?.let { NearestTarget.Health(setOfNotNull(it.metric), it.source) } ?: ChartMetrics.target(metric)
        val anchorUs = input.anchor?.takeIf { it.metric == metric }?.elapsedUs
        val anchor = anchorUs?.let { anchored(source, metric, target, it) }
        val reducer = InspectionReducer(metric, InspectionTarget(atUs, anchor), null, source.live)
        val before = source.store.nearest(source.id, target, atUs, true, Long.MAX_VALUE).orThrow()
        val after = source.store.nearest(source.id, target, atUs, false, Long.MAX_VALUE).orThrow()
        for (elapsedUs in listOfNotNull(before, anchorUs, after).distinct().sorted()) feedAt(
            source,
            target,
            elapsedUs,
            reducer.sink(),
        )
        return inspection(metric, reducer)
    }

    private suspend fun anchored(
        source: Source,
        metric: MetricId,
        target: NearestTarget,
        elapsedUs: Long,
    ): OriginalRef? {
        val newest = Newest(metric)
        feedAt(source, target, elapsedUs, newest)
        return newest.last
    }

    private fun inspection(metric: MetricId, reducer: InspectionReducer): InspectedValue =
        when (val result = reducer.result()) {
            is Bounded.Done ->
                InspectedValue(
                    metric,
                    result.value.selection?.let { original(metric, it) },
                    result.value.selection == null && result.value.reference != null,
                )
            Bounded.Capacity -> throw Capacity()
        }

    private suspend fun statistics(
        source: Source,
        metrics: List<MetricId>,
        range: TimeRange,
    ): List<MetricRangeStatistics> {
        metrics.forEach { requireCharted(source, it) }
        val measured = metrics.filter {
            ChartMetrics.kind(it) == ChartKind.telemetry || ChartMetrics.kind(it) == ChartKind.location
        }
        val reducers =
            (measured + metrics.filter { ChartMetrics.kind(it) == ChartKind.health }).associateWith {
                MetricReducer(it, range)
            }
        val record = source.record
        if (record == null) {
            if (measured.isNotEmpty())
                scan(source, range, streams(measured), Fanout(measured.map { reducers.getValue(it).sink() }))
        } else {
            val ride = ChartTiles.Ride(source.id, source.revision, source.extent, source.live, source.reader)
            for (metric in measured) {
                val reducer = reducers.getValue(metric)
                var atUs = range.startUs
                for (tile in tiles.covering(ride, metric, range)) {
                    if (tile.range.startUs > atUs)
                        scan(source, TimeRange(atUs, tile.range.startUs), streams(listOf(metric)), reducer.sink())
                    reducer.merge(tile.aggregate.statistics)
                    atUs = tile.range.endUs
                }
                if (atUs < range.endUs)
                    scan(source, TimeRange(atUs, range.endUs), streams(listOf(metric)), reducer.sink())
            }
        }
        for (metric in metrics.filter { ChartKind.health == ChartMetrics.kind(it) }) {
            val stream = healthStream(source, metric) ?: continue
            scan(source, range, FactStreams(false, false, stream), reducers.getValue(metric).sink())
        }
        val totals = metrics.filter { ChartMetrics.derived(it) || ChartMetrics.kind(it) == ChartKind.energyPerDistance }
        val derived =
            if (totals.isEmpty()) null
            else {
                val frozen = requireNotNull(source.derived)
                val pass = RangeTotals(range, frozen)
                derivedPass(
                    source,
                    totals,
                    range.startUs - PlotTiles.baseSlotUs,
                    range.endUs + DERIVED_LOOKAHEAD_US,
                    listOf(pass),
                )
                pass
            }
        val timerUs = timer(source, range)
        return metrics.map { metric ->
            reducers[metric]?.let { reducer ->
                when (val result = reducer.result()) {
                    is Bounded.Done ->
                        MetricRangeStatistics(
                            metric,
                            result.value.minimum?.let { original(metric, it) },
                            result.value.average,
                            result.value.maximum?.let { original(metric, it) },
                            null,
                            null,
                            false,
                        )
                    Bounded.Capacity -> throw Capacity()
                }
            } ?: requireNotNull(derived).statistics(metric, timerUs)
        }
    }

    private fun timer(source: Source, range: TimeRange): Long {
        if (source.events.isEmpty()) return range.endUs - range.startUs
        val timeline = ActiveTimeline(source.range.endUs, source.events)
        return timeline.timerAt(range.endUs) - timeline.timerAt(range.startUs)
    }

    private suspend fun healthStream(source: Source, metric: MetricId): HealthStream? {
        val record = source.record ?: return null
        val healthMetric =
            source.extent.healthMetrics.firstOrNull { HealthMetrics.metricId(it) == metric } ?: return null
        val ride = record.ride
        val base =
            if (ride.recordedBy == Device.androidPhone)
                HealthSourceFilter(null, origin(source, healthMetric) ?: return null, null)
            else HealthSourceFilter(if (ride.options.useWatch) Producer.watch else Producer.phone, null, null)
        if (metric != MetricId.heartRateBpm) return HealthStream(healthMetric, base)
        val raw = base.copy(representations = rawRepresentations)
        val hasRaw =
            source.store
                .nearest(source.id, NearestTarget.Health(setOf(healthMetric), raw), 0, false, Long.MAX_VALUE)
                .orThrow() != null
        return HealthStream(healthMetric, if (hasRaw) raw else base)
    }

    private suspend fun origin(source: Source, metric: HealthMetric): String? {
        val key = source.id to metric
        origins
            .load()[key]
            ?.takeIf { it.first == source.revision }
            ?.let {
                return it.second
            }
        val ride = requireNotNull(source.record).ride
        val windowStart = ride.startedUtcUs
        val windowEnd = ride.startedUtcUs + source.range.endUs
        val covered = mutableMapOf<String, Long>()
        val reached = mutableMapOf<String, Long>()
        val sink =
            object : FactSink {
                override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) =
                    Unit

                override fun append(fact: Fact) {
                    val sample = (fact as? Fact.Health)?.sample ?: return
                    val start =
                        maxOf(
                            windowStart,
                            sample.startUtcUs ?: sample.utcUs,
                            reached[sample.dataOrigin] ?: Long.MIN_VALUE,
                        )
                    val end = minOf(windowEnd, sample.endUtcUs ?: sample.utcUs)
                    covered[sample.dataOrigin] = (covered[sample.dataOrigin] ?: 0L) + maxOf(0L, end - start)
                    if (end > start) reached[sample.dataOrigin] = end
                }
            }
        scan(
            source,
            TimeRange(Long.MIN_VALUE / 2, Long.MAX_VALUE),
            FactStreams(false, false, HealthStream(metric, null)),
            sink,
        )
        val selected =
            app.powerlog.domain.core.health.HealthSourceSelection.select(
                    covered.map { app.powerlog.domain.core.health.OriginCoverage(it.key, it.value) }
                )
                ?.dataOrigin
        while (true) {
            val current = origins.load()
            if (origins.compareAndSet(current, current + (key to (source.revision to selected)))) return selected
        }
    }

    private fun original(metric: MetricId, ref: OriginalRef): Original =
        Original(ref.valueQ / MetricCatalog.of(metric).quantum.toDouble(), ref.utcUs, ref.elapsedUs)

    private fun series(metric: MetricId, points: List<PlotPoint>): PlotSeries {
        val quantum = MetricCatalog.of(metric).quantum.toDouble()
        return PlotSeries(
            metric = metric,
            segment = points.map { it.segment },
            firstUs = points.map { it.first.elapsedUs },
            first = points.map { it.first.valueQ / quantum },
            minUs = points.map { it.min.elapsedUs },
            min = points.map { it.min.valueQ / quantum },
            maxUs = points.map { it.max.elapsedUs },
            max = points.map { it.max.valueQ / quantum },
            lastUs = points.map { it.last.elapsedUs },
            last = points.map { it.last.valueQ / quantum },
        )
    }

    private fun mapped(failed: StoreFailure): app.powerlog.application.api.ApiFailure {
        val mapped = readFailure(failed.failed.error)
        if (mapped.code == FailureCode.storageRefused) refuse()
        return mapped
    }

    private class Fanout(private val sinks: List<FactSink>) : FactSink {
        override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) =
            sinks.forEach {
                it.append(rows, from, to, sessions)
            }

        override fun append(fact: Fact) = sinks.forEach { it.append(fact) }
    }

    private fun sink(consumer: TelemetryConsumer, facts: (Fact) -> Unit): FactSink =
        object : FactSink {
            override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) =
                consumer.append(rows, from, to, sessions)

            override fun append(fact: Fact) = facts(fact)
        }

    private fun PlotReducer.sink(): FactSink = sink(this, ::append)

    private fun MetricReducer.sink(): FactSink = sink(this, ::append)

    private fun InspectionReducer.sink(): FactSink = sink(this, ::append)

    private fun EnergyPerDistancePlotReducer.sink(): FactSink = sink(this, ::append)

    private class Newest(metric: MetricId) : FactSink {
        private val reducer = InspectionReducer(metric, InspectionTarget(Long.MAX_VALUE, null), null, false)

        val last: OriginalRef?
            get() = (reducer.result() as? Bounded.Done)?.value?.reference

        override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) =
            reducer.append(rows, from, to, sessions)

        override fun append(fact: Fact) = reducer.append(fact)
    }

    private class DerivedPlot(metric: MetricId, frozen: DistanceSource, private val reducer: PlotReducer) :
        DerivedSink {
        private val source = ChartMetrics.distanceSource(metric, frozen)

        override fun distance(source: DistanceSource, interval: DistanceInterval) {
            if (source == this.source) reducer.distance(interval)
        }

        override fun battery(interval: ConsumptionInterval) = reducer.battery(interval)
    }

    private class DerivedInspection(metric: MetricId, frozen: DistanceSource, private val reducer: InspectionReducer) :
        DerivedSink {
        private val source = ChartMetrics.distanceSource(metric, frozen)

        override fun distance(source: DistanceSource, interval: DistanceInterval) {
            if (source == this.source) reducer.distance(interval)
        }

        override fun battery(interval: ConsumptionInterval) = reducer.battery(interval)
    }

    private class RangeTotals(range: TimeRange, private val frozen: ChartMetrics.Derived) : DerivedSink {
        private val distances = DistanceSource.entries.associateWith { DistanceReducer(range, null) }
        private val consumption = ConsumptionMetric.entries.associateWith { ConsumptionReducer(it, range, null) }
        private val energy = EnergyPerDistanceReducer(null, frozen.battery, frozen.distance, range)

        override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) =
            energy.append(rows, from, to, sessions)

        override fun distance(source: DistanceSource, interval: DistanceInterval) {
            distances.getValue(source).append(interval)
            energy.distance(interval)
        }

        override fun battery(interval: ConsumptionInterval) {
            consumption.getValue(interval.metric).append(interval)
            energy.battery(interval)
        }

        fun statistics(metric: MetricId, timerUs: Long): MetricRangeStatistics {
            val quantum = MetricCatalog.of(metric).quantum.toDouble()
            return when (ChartMetrics.kind(metric)) {
                ChartKind.distance -> {
                    val totals =
                        distances.getValue(ChartMetrics.distanceSource(metric, frozen.distance)).result(timerUs).done()
                    MetricRangeStatistics(metric, null, null, null, totals.distanceQ / quantum, null, totals.partial)
                }
                ChartKind.consumption -> {
                    val totals = consumption.getValue(ChartMetrics.consumptionMetric(metric)).result(timerUs)
                    MetricRangeStatistics(
                        metric,
                        null,
                        null,
                        null,
                        totals.amount.takeIf { totals.available },
                        null,
                        totals.partial,
                    )
                }
                else -> {
                    val distance = distances.getValue(frozen.distance).result(timerUs).done()
                    val energyLane =
                        if (frozen.battery == BatteryMethod.controller) ConsumptionMetric.controllerEnergyWh
                        else ConsumptionMetric.calculatedEnergyWh
                    val partial =
                        distance.partial ||
                            metric == MetricId.batteryWhPerKm &&
                                consumption.getValue(energyLane).result(timerUs).partial
                    MetricRangeStatistics(
                        metric,
                        null,
                        null,
                        null,
                        null,
                        energy.result().done()[metric]?.whPerKm,
                        partial,
                    )
                }
            }
        }
    }

    private companion object {
        fun <T> Bounded<T>.done(): T =
            when (this) {
                is Bounded.Done -> value
                Bounded.Capacity -> throw Capacity()
            }

        const val TEN_SECONDS_US = 10_000_000L
        const val PREVIEW_REVISIONS = 1L shl 40
        const val DERIVED_LOOKAHEAD_US = 20_000_000L
        const val LIVE_ENERGY_WINDOW_US = 2_500_000L
        val recordingPhases = setOf(RidePhase.preparing, RidePhase.running, RidePhase.paused)
        val openPhases = recordingPhases + RidePhase.interrupted
        val rawRepresentations = setOf(HealthRepresentation.rawQuantity, HealthRepresentation.rawSeries)
    }
}
