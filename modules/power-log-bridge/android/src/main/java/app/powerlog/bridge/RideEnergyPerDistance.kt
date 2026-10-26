package app.powerlog.bridge

import app.powerlog.core.analytics.*
import app.powerlog.core.inspection.InspectionPlotSlots
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

internal class RideEnergyPerDistance(
    private val store: RideStore,
    private val seriesPoints: Int = InspectionPlotSlots.maximumPoints,
) {
    private data class Key(
        val id: String,
        val battery: String,
        val distance: String,
        val smoothingSeconds: Int,
        val includeTotals: Boolean,
    )

    private class Resume(
        val checkpoint: EnergyPerDistanceCheckpoint,
        val cutUs: Long,
        val revision: Long,
        val batteryRow: Long,
    )

    private class Chart {
        var series: EnergyPerDistanceSeries? = null
        var throughUs = 0L
        var revision = 0L
        var exhausted = false
    }

    private class Source(
        val id: String,
        val distance: String,
        val battery: String,
        val revision: Long,
        val timeline: ActiveTimeline,
        val intervals: Boolean,
        val preview: Boolean,
    )

    private class Inputs(
        val distance: (Long, Long, Double, Double?, Double?) -> Unit,
        val battery: (Long, Long, Double, Double?, Double?, Boolean, String) -> Unit,
        val speed: (Long, Double?, Double?, Int?) -> Unit,
        val telemetry: (Long, Double?, Double?, Double?, Int?, String?, String) -> Unit,
    )

    private val resumes = LinkedHashMap<Key, Resume>(8, 0.75f, true)
    private val charts = LinkedHashMap<Key, Chart>(8, 0.75f, true)
    val fed = AtomicLong()
    val resumed = AtomicLong()
    val restarted = AtomicLong()
    val seriesBuilt = AtomicLong()
    val seriesResumed = AtomicLong()
    val seriesFresh = AtomicLong()

    fun forget(id: String) {
        synchronized(resumes) { resumes.keys.removeAll { it.id == id } }
        synchronized(charts) { charts.keys.removeAll { it.id == id } }
    }

    fun read(
        id: String,
        start: Double?,
        end: Double?,
        buckets: Int,
        smoothingSeconds: Int = 0,
        includeTotals: Boolean = true,
        current: Boolean = false,
        cached: Boolean = true,
    ): Payload {
        val elapsed = store.timing(id).elapsed
        val dataEnd = maxOf(10.0, elapsed)
        val whole = !includeTotals && !current && start == 0.0 && (end ?: dataEnd) >= dataEnd
        val last = if (whole) dataEnd else end ?: elapsed
        val first =
            when {
                current -> last
                whole -> 0.0
                else -> start ?: last
            }
        val timeline = RideAnalysis(store).timeline(id)
        val timer =
            timeline.intervals.sumOf {
                maxOf(0L, minOf(elapsedUs(last), it.endUs) - maxOf(elapsedUs(first), it.startUs))
            }
        val metadata = store.metadata(id)
        // Preview captures controller telemetry only and has no accumulated ride totals.
        val preview = id.startsWith("live-")
        val distance = if (preview) "controller" else metadata.str("distanceSource")
        val battery = metadata.str("batteryConsumptionSource") + "EnergyWh"
        val source = Source(id, distance, battery, store.readRevision(id), timeline, includeTotals && !preview, preview)
        val firstUs = elapsedUs(first)
        val lastUs = elapsedUs(last)
        val from = elapsedUs(maxOf(0.0, first - EnergyPerDistancePolicy.contextSeconds(smoothingSeconds)))
        val until = elapsedUs(last + 10)
        val controller = distance == "controller"
        if (whole) {
            val key = Key(id, battery, distance, smoothingSeconds, false)
            return chart(source, key, lastUs, until, buckets, timer, cached)
        }
        val key =
            Key(id, battery, distance, smoothingSeconds, includeTotals).takeIf {
                includeTotals && !preview && !current && firstUs == 0L
            }
        val resume = key?.let { resumable(it, source.revision, lastUs) }
        val query =
            when {
                resume != null ->
                    EnergyPerDistanceQuery(
                        resume.checkpoint,
                        firstUs,
                        lastUs,
                        timer,
                        controller,
                        smoothingSeconds,
                        includeTotals,
                    )
                includeTotals ->
                    EnergyPerDistanceQuery(firstUs, lastUs, timer, controller, smoothingSeconds, includeTotals)
                else ->
                    EnergyPerDistanceQuery(firstUs, lastUs, buckets, timer, controller, smoothingSeconds, includeTotals)
            }
        if (key != null) (if (resume == null) restarted else resumed).incrementAndGet()
        val cutUs =
            key?.let {
                    from +
                        Math.floorDiv(lastUs - EnergyPerDistancePolicy.checkpointMarginUs - 1 - from, CHUNK_US) *
                            CHUNK_US
                }
                ?.takeIf { it > (resume?.cutUs ?: from) }
        var checkpoint: EnergyPerDistanceCheckpoint? = null
        val chunkStart = resume?.cutUs ?: from
        feed(
            source,
            chunkStart,
            if (resume == null) Triple(from - 1, -1, -1L) else Triple(chunkStart, Int.MAX_VALUE, Long.MAX_VALUE),
            until,
            inputs(query),
        ) { chunkEnd ->
            if (chunkEnd == cutUs) checkpoint = query.checkpoint()
        }
        val next = checkpoint
        if (key != null && cutUs != null && next != null)
            keep(key, Resume(next, cutUs, source.revision, batteryRow(id, battery, source.revision)))
        return JSONObject(query.resultJson()).map()
    }

    private fun chart(
        source: Source,
        key: Key,
        lastUs: Long,
        until: Long,
        buckets: Int,
        timer: Long,
        cached: Boolean,
    ): Payload {
        val controller = key.distance == "controller"
        val slot = InspectionPlotSlots.alignedSlotUs(AnalysisRange(0, lastUs), buckets)
        fun fresh(): Payload {
            val query = EnergyPerDistanceQuery(0, lastUs, buckets, slot, timer, controller, key.smoothingSeconds, false)
            feed(source, 0, Triple(-1L, -1, -1L), until, inputs(query))
            return JSONObject(query.resultJson()).map()
        }
        if (source.preview || !cached) return fresh()
        val chart =
            synchronized(charts) {
                charts.getOrPut(key, ::Chart).also {
                    val retained = charts.keys.iterator()
                    while (charts.size > CHART_LIMIT && retained.hasNext()) {
                        if (retained.next() != key) retained.remove()
                    }
                }
            }
        val margin = EnergyPerDistancePolicy.checkpointMarginUs
        val cutUs = minOf(lastUs - margin, source.timeline.endUs)
        fun read(series: EnergyPerDistanceSeries): Payload {
            val read = series.begin(lastUs, timer, buckets, slot)
            var cut = cutUs <= 0
            var last = chart.throughUs - 1
            feed(
                source,
                chart.throughUs,
                Triple(chart.throughUs - 1, -1, -1L),
                until,
                inputs(read),
                before = { time ->
                    if (!cut && time >= cutUs) {
                        read.cut()
                        cut = true
                    }
                    if (!cut) last = time
                },
            )
            if (!cut) read.cut()
            val json = read.resultJson()
            chart.series = series.takeIf { it.valid }
            chart.exhausted = !series.valid
            if (cutUs > 0) chart.throughUs = last + 1
            chart.revision = source.revision
            return JSONObject(json).map()
        }
        synchronized(chart) {
            val saved = chart.series
            if (
                chart.exhausted ||
                    source.revision < chart.revision ||
                    saved != null && lastUs - saved.throughUs <= margin
            ) {
                seriesFresh.incrementAndGet()
                return fresh()
            }
            if (
                saved != null &&
                    chart.throughUs <= source.timeline.endUs &&
                    (source.revision == chart.revision || !changed(source, chart.revision, chart.throughUs))
            ) {
                try {
                    return read(saved).also { seriesResumed.incrementAndGet() }
                } catch (failure: Throwable) {
                    if (saved.valid) throw failure
                }
            }
            chart.series = null
            chart.throughUs = 0L
            seriesBuilt.incrementAndGet()
            return read(EnergyPerDistanceSeries(controller, key.smoothingSeconds, false, seriesPoints))
        }
    }

    private fun inputs(query: EnergyPerDistanceQuery) =
        Inputs(query::appendDistance, query::appendBattery, query::appendSpeed, query::appendTelemetry)

    private fun inputs(read: EnergyPerDistanceSeriesRead) =
        Inputs(read::appendDistance, read::appendBattery, read::appendSpeed, read::appendTelemetry)

    private fun feed(
        source: Source,
        firstChunk: Long,
        after: Triple<Long, Int, Long>,
        until: Long,
        inputs: Inputs,
        before: (Long) -> Unit = {},
        completed: (Long) -> Unit = {},
    ) {
        val id = source.id
        val revision = source.revision
        var afterTime = after.first
        var afterChannel = after.second
        var afterId = after.third
        var chunkStart = firstChunk
        var chunkEnd = if (source.preview) until else minOf(until, chunkStart + CHUNK_US)
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "Energy/distance read cancelled" }
            val count =
                store.select(
                    """
                    SELECT * FROM (
                      SELECT 0 AS channel,to_id AS row_id,start_us AS a,end_us AS b,meters_q/1000000.0 AS amount,
                        u,v,0 AS indivisible,'' AS stamp,'' AS producer,0 AS seq,NULL AS clock,NULL AS epoch,NULL AS watts,NULL AS power,NULL AS speed,NULL AS accuracy
                      FROM distance_intervals WHERE ride=? AND source=? AND revision<=? AND end_us BETWEEN ? AND ?
                      UNION ALL
                      SELECT 1,to_id,start_us,end_us,end_value-start_value,rate0,rate1,indivisible,
                        (SELECT timestamp FROM observations WHERE id=battery_intervals.to_id),'',0,NULL,NULL,NULL,NULL,NULL,NULL
                      FROM battery_intervals WHERE ride=? AND metric=? AND revision<=?
                        AND (retired_revision IS NULL OR retired_revision>?) AND end_us BETWEEN ? AND ?
                      UNION ALL
                      SELECT CASE kind WHEN 'location' THEN 2 ELSE 3 END,id,elapsed_us,elapsed_us,0,NULL,NULL,0,timestamp,producer,producer_sequence,
                        json_extract(evidence,'$.clockEpoch'),epoch,humanPowerW,motorInputPowerW,
                        CASE kind WHEN 'location' THEN CASE WHEN json_extract(evidence,'$.distanceBarrier')=1 THEN NULL ELSE speedMps END ELSE controllerSpeedMps END,speedAccuracyMps
                      FROM observations WHERE ride=? AND (kind='telemetry' OR (kind='location' AND ?='gps:phone')) AND revision<=? AND elapsed_us BETWEEN ? AND ?
                    ) WHERE (b,channel,row_id)>(?,?,?) ORDER BY b,channel,row_id LIMIT 128
                    """
                        .trimIndent(),
                    arrayOf(
                        if (source.intervals) id else "",
                        source.distance,
                        revision,
                        chunkStart,
                        chunkEnd,
                        if (source.intervals) id else "",
                        source.battery,
                        revision,
                        revision,
                        chunkStart,
                        chunkEnd,
                        id,
                        source.distance,
                        revision,
                        chunkStart,
                        chunkEnd,
                        afterTime,
                        afterChannel,
                        afterId,
                    ),
                ) { c ->
                    var count = 0
                    while (c.moveToNext()) {
                        fun optional(key: String): Double? =
                            c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getDouble(it) }
                        val time = c.long("b")
                        before(time)
                        when (c.long("channel").toInt()) {
                            0 ->
                                if (time > c.long("a"))
                                    inputs.distance(c.long("a"), time, c.double("amount"), optional("u"), optional("v"))
                            1 ->
                                inputs.battery(
                                    c.long("a"),
                                    time,
                                    c.double("amount"),
                                    optional("u"),
                                    optional("v"),
                                    c.long("indivisible") == 1L,
                                    c.text("stamp"),
                                )
                            else -> {
                                val clock = c.getString(c.getColumnIndexOrThrow("clock"))
                                val epoch = c.getString(c.getColumnIndexOrThrow("epoch"))
                                val active = source.timeline.membership(time, c.text("producer"), c.long("seq"), clock)
                                if (c.long("channel") == 2L)
                                    inputs.speed(time, optional("speed"), optional("accuracy"), active)
                                else
                                    inputs.telemetry(
                                        time,
                                        optional("watts"),
                                        optional("power"),
                                        optional("speed"),
                                        active,
                                        "$clock|$epoch",
                                        c.text("stamp"),
                                    )
                            }
                        }
                        afterTime = time
                        afterChannel = c.long("channel").toInt()
                        afterId = c.long("row_id")
                        count++
                    }
                    count
                }
            fed.addAndGet(count.toLong())
            if (count < 128) {
                completed(chunkEnd)
                if (chunkEnd == until) break
                chunkStart = chunkEnd
                chunkEnd = minOf(until, chunkEnd + CHUNK_US)
                afterTime = chunkStart
                afterChannel = Int.MAX_VALUE
                afterId = Long.MAX_VALUE
            }
        }
    }

    private fun exists(id: String, sql: String, vararg args: Any): Boolean =
        store.select(sql, arrayOf(id, *args)) {
            it.moveToFirst()
        }

    private fun changed(source: Source, after: Long, throughUs: Long): Boolean =
        exists(
            source.id,
            "SELECT 1 FROM lifecycle WHERE ride=? AND revision>? AND revision<=? LIMIT 1",
            after,
            source.revision,
        ) ||
            exists(
                source.id,
                "SELECT 1 FROM observations WHERE ride=? AND revision>? AND revision<=? AND elapsed_us<? AND (kind='telemetry' OR (kind='location' AND ?='gps:phone')) LIMIT 1",
                after,
                source.revision,
                throughUs,
                source.distance,
            )

    private fun resumable(key: Key, revision: Long, lastUs: Long): Resume? {
        val stored = synchronized(resumes) { resumes[key] } ?: return null
        if (revision < stored.revision || lastUs - stored.cutUs <= EnergyPerDistancePolicy.checkpointMarginUs)
            return null
        if (revision == stored.revision) return stored
        val changed =
            exists(
                key.id,
                "SELECT 1 FROM lifecycle WHERE ride=? AND revision>? AND revision<=? LIMIT 1",
                stored.revision,
                revision,
            ) ||
                exists(
                    key.id,
                    "SELECT 1 FROM observations WHERE ride=? AND revision>? AND revision<=? AND elapsed_us<=? AND (kind='telemetry' OR (kind='location' AND ?='gps:phone')) LIMIT 1",
                    stored.revision,
                    revision,
                    stored.cutUs,
                    key.distance,
                ) ||
                exists(
                    key.id,
                    "SELECT 1 FROM distance_intervals WHERE ride=? AND source=? AND revision>? AND revision<=? AND end_us<=? LIMIT 1",
                    key.distance,
                    stored.revision,
                    revision,
                    stored.cutUs,
                ) ||
                exists(
                    key.id,
                    "SELECT 1 FROM battery_intervals WHERE ride=? AND retired_revision>? AND retired_revision<=? LIMIT 1",
                    stored.revision,
                    revision,
                ) ||
                exists(
                    key.id,
                    "SELECT 1 FROM battery_intervals WHERE ride=? AND metric=? AND to_id>? AND revision<=? AND end_us<=? LIMIT 1",
                    key.battery,
                    stored.batteryRow,
                    revision,
                    stored.cutUs,
                )
        return stored.takeUnless { changed }
    }

    private fun batteryRow(id: String, battery: String, revision: Long): Long =
        store.select(
            "SELECT to_id FROM battery_intervals WHERE ride=? AND metric=? AND revision<=? ORDER BY to_id DESC LIMIT 1",
            arrayOf(id, battery, revision),
        ) { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    private fun keep(key: Key, resume: Resume) =
        synchronized(resumes) {
            val stored = resumes[key]
            if (
                stored == null ||
                    stored.revision < resume.revision ||
                    stored.revision == resume.revision && stored.cutUs < resume.cutUs
            )
                resumes[key] = resume
            val retained = resumes.keys.iterator()
            while (resumes.size > RESUME_LIMIT && retained.hasNext()) {
                retained.next()
                retained.remove()
            }
        }

    companion object {
        private const val CHUNK_US = 16_000_000L
        private const val RESUME_LIMIT = 16
        private const val CHART_LIMIT = 2
    }
}
