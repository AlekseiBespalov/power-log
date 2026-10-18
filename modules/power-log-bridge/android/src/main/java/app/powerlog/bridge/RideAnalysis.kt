package app.powerlog.bridge

import android.database.Cursor
import app.powerlog.core.analytics.*
import app.powerlog.core.health.Component
import app.powerlog.core.health.ComponentState
import app.powerlog.core.health.Device
import app.powerlog.core.health.HealthCodec
import app.powerlog.core.health.HealthOutcome
import app.powerlog.core.health.MeasurementSource
import app.powerlog.core.health.Provider
import app.powerlog.core.health.Source
import org.json.JSONArray
import org.json.JSONObject

internal fun Observation.displayContinuity() =
    Continuity(
        clockEpoch = evidence["clockEpoch"] as? String,
        interruptionIndex =
            (evidence[if (kind == "health") "collectionSegment" else "interruptionIndex"] as? Number)?.toInt() ?: 0,
    )

private fun healthMetric(measure: Any?) =
    when (measure) {
        "heartRate" -> "heartRateBpm"
        "activeEnergy" -> "activeEnergyKcal"
        else -> null
    }

internal val summaryMetrics =
    listOf(
        "humanPowerW",
        "cadenceRpm",
        "heartRateBpm",
        "activeEnergyKcal",
        "latitude",
        "longitude",
        "horizontalAccuracyM",
        "altitudeMeters",
        "verticalAccuracyM",
        "speedMps",
        "speedAccuracyMps",
    )

internal fun Observation.analysisItem(): AnalysisItem {
    val identity = requireNotNull(original)
    val metric = healthMetric(evidence["measure"])
    return AnalysisItem(
        elapsedUs,
        time,
        identity.eventId,
        "event:${identity.eventId}",
        identity.producer,
        identity.sequence,
        kind,
        if (kind == "telemetry") "controller" else "phone",
        timestamp,
        (values +
                ((evidence["values"] as? Map<*, *>)?.get("courseAccuracyDegrees") as? Number)
                    ?.let { mapOf("courseAccuracyDegrees" to it.toDouble()) }
                    .orEmpty())
            .mapValues { Scalar.Real64(it.value) },
        (evidence["originalElapsedSeconds"] as? Number)?.toDouble(),
        evidence["representation"] as? String,
        Continuity(evidence["clockEpoch"] as? String ?: epoch, epoch, segment),
        distanceBarrier = evidence["distanceBarrier"] == true,
        hasElapsedMapping = evidence["collectionMapped"] != false,
        origin = if (kind == "health" && evidence["origin"] != null) HealthReadings.origin(this) else null,
        declaredMetrics = if (kind == "health") listOfNotNull(metric) else null,
    )
}

internal class RideAnalysis(private val store: RideStore) {
    fun pin(id: String, selection: String): AnalysisPin {
        val metadata = store.metadata(id)
        val timing = store.timing(id)
        return AnalysisPin(
            id,
            store.readRevision(id),
            AnalysisMetadata(
                id,
                metadata.str("startedAt"),
                timing.elapsed,
                metadata.flag("indoor"),
                false,
                metadata.flag("recordGPS"),
                metadata.flag("saveToHealth"),
                metadata.num("sampleHz").toInt(),
                metadata["endedAt"] as? String ?: timing.timestamp,
                stopElapsedSeconds = timing.elapsed.takeIf { metadata["endedAt"] != null },
                sources = listOf(MeasurementSource(Source.phone, Device.androidPhone, Provider.healthConnect)),
                completeEncodedMetadata =
                    json(metadata + mapOf("timing" to listOf(timing.elapsed, timing.timer, timing.timestamp)))
                        .toString(),
                phase = metadata.str("phase"),
                healthOutcome = HealthOutcome.valueOf(metadata.str("healthKitState")),
                healthReadbackPending =
                    (metadata["componentResults"] as? List<*>).orEmpty().any {
                        @Suppress("UNCHECKED_CAST")
                        val component = HealthCodec.decodeComponentResult(json(it as Payload).toString())
                        component.component == Component.readback && component.state == ComponentState.pending
                    },
            ),
            metadata.str("distanceSource"),
        )
    }

    fun lifecycle(id: String): List<AnalysisItem> =
        store.select(
            "SELECT id,elapsed_us,producer,producer_sequence,evidence FROM lifecycle WHERE ride=? AND revision<=? ORDER BY elapsed_us,producer,producer_sequence LIMIT 10001",
            arrayOf(id, store.readRevision(id)),
        ) { c ->
            buildList {
                while (c.moveToNext()) {
                    val p = JSONObject(c.getString(4)).map()
                    add(
                        AnalysisItem(
                            c.getLong(1),
                            p.num("time"),
                            p.str("eventId"),
                            "lifecycle:${c.getLong(0)}",
                            c.getString(2),
                            c.getLong(3),
                            "lifecycle",
                            "phone",
                            p.str("timestamp"),
                            continuity = Continuity(p["clockEpoch"] as? String),
                            action = p.str("action"),
                            interrupted = p["interrupted"] == true && p["action"] == "pause",
                            controllerSequence = (p["controllerSequence"] as? String)?.toLong(),
                        )
                    )
                }
            }
        }

    fun timeline(id: String): ActiveTimeline = ActiveTimeline(elapsedUs(store.timing(id).elapsed), lifecycle(id))

    private data class Row(val id: Long, val item: AnalysisItem)

    private enum class Field(val key: String) {
        originalElapsed("originalElapsedSeconds"),
        representation("representation"),
        clockEpoch("clockEpoch"),
        distanceBarrier("distanceBarrier"),
        mapped("collectionMapped"),
        measure("measure"),
        origin("origin"),
    }

    private fun Cursor.summaryItem(): AnalysisItem {
        val evidence = JSONArray(text("analysis_evidence"))
        fun field(key: Field) = evidence.get(key.ordinal)
        val kind = text("kind")
        val identity = OriginalIdentity(text("event_id"), text("producer"), long("producer_sequence"))
        val values =
            summaryMetrics
                .mapNotNull { metric ->
                    val index = getColumnIndexOrThrow(metric)
                    if (isNull(index)) null else metric to Scalar.Real64(getDouble(index))
                }
                .toMap()
        return AnalysisItem(
            long("elapsed_us"),
            double("time"),
            identity.eventId,
            "event:${identity.eventId}",
            identity.producer,
            identity.sequence,
            kind,
            if (kind == "telemetry") "controller" else "phone",
            text("timestamp"),
            values,
            (field(Field.originalElapsed) as? Number)?.toDouble(),
            field(Field.representation) as? String,
            Continuity(
                field(Field.clockEpoch) as? String ?: text("epoch"),
                text("epoch"),
                long("segment").toInt(),
            ),
            distanceBarrier = field(Field.distanceBarrier) == true,
            hasElapsedMapping = field(Field.mapped) != false,
            origin =
                if (kind == "health" && field(Field.origin) != JSONObject.NULL)
                    HealthReadings.origin((field(Field.origin) as JSONObject).map())
                else null,
            declaredMetrics = if (kind == "health") listOfNotNull(healthMetric(field(Field.measure))) else null,
        )
    }

    fun each(id: String, body: (AnalysisItem) -> Unit) = each(id, false, body)

    fun eachSummary(id: String, body: (AnalysisItem) -> Unit) = each(id, true, body)

    private fun each(id: String, summary: Boolean, body: (AnalysisItem) -> Unit) {
        val events = lifecycle(id)
        require(events.size <= 10000) { "Too many lifecycle events" }
        var next = 0
        var after: Row? = null
        val projection =
            if (!summary) "o.*"
            else
                "o.id,o.elapsed_us,o.time,o.timestamp,o.event_id,o.producer,o.producer_sequence,o.kind,o.epoch,o.segment," +
                    "json_extract(o.evidence,${Field.entries.joinToString { "'$.${it.key}'" }}) AS analysis_evidence," +
                    "length(CAST(o.evidence AS BLOB)) AS payload_bytes," +
                    summaryMetrics.joinToString { "o.$it" }
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
            val cursor = after
            val continuation =
                if (cursor == null) "" else "AND (o.elapsed_us,o.producer,o.producer_sequence,o.id)>(?,?,?,?)"
            val args =
                listOf(id, cursor?.item?.collectionUs ?: Long.MIN_VALUE, store.readRevision(id)) +
                    (cursor?.let { listOf(it.item.collectionUs, it.item.producer, it.item.sequence, it.id) }
                        ?: emptyList())
            val rows =
                store.select(
                    "SELECT $projection FROM observations o INDEXED BY observations_elapsed WHERE o.ride=? AND o.elapsed_us>=? AND o.revision<=? ${store.selectedPredicate("o")} $continuation ORDER BY elapsed_us,producer,producer_sequence,id LIMIT 256",
                    args.toTypedArray(),
                ) { c ->
                    if (!summary) with(store) { c.observationPage() }.map { Row(it.id, it.analysisItem()) }
                    else
                        buildList {
                            var bytes = 0L
                            while (size < 256 && c.moveToNext()) {
                                if (size % 16 == 0)
                                    check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
                                val rowBytes = c.long("payload_bytes") * 2 + 1024
                                require(rowBytes <= 524288) { "Analysis original exceeds the page byte bound" }
                                if (bytes + rowBytes > 524288) break
                                add(Row(c.long("id"), c.summaryItem()))
                                bytes += rowBytes
                            }
                        }
                }
            if (rows.isEmpty()) break
            for ((index, row) in rows.withIndex()) {
                if (index % 16 == 0) check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
                while (next < events.size && events[next].collectionUs <= row.item.collectionUs) body(events[next++])
                body(row.item)
            }
            after = rows.last()
        }
        while (next < events.size) body(events[next++])
    }
}
