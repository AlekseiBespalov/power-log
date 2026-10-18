package app.powerlog.bridge

import app.powerlog.core.analytics.*
import app.powerlog.core.health.*
import app.powerlog.core.health.Association as CoreAssociation
import app.powerlog.core.health.Origin as CoreOrigin

internal class HealthReadings(private val store: RideStore) {
    private data class Prepared(
        val id: String,
        val revision: Long,
        val endUs: Long,
        val value: Pair<HeartRateOriginPreparation, EnergyOriginPreparation>,
    )

    private var prepared: Prepared? = null

    fun interval(row: Observation, window: AnalysisRange): EnergyInterval? {
        if (row.evidence["collectionMapped"] == false) return null
        val interval =
            EnergyInterval(
                Source.phone,
                origin(row),
                row.revision,
                row.elapsedUs,
                canonicalInt64(row.evidence.getValue("endUs")!!),
                row.timestamp,
                row.evidence.str("originalEndTimestamp"),
                (row.evidence["value"] as Number).toDouble(),
            )
        if (!summaryCollectionAvailable(EnergyEvidence.IntervalRecord("active", interval).collectionUs)) return null
        val duration =
            Math.subtractExact(
                timeUs(interval.originalEndTimestamp),
                timeUs(interval.originalStartTimestamp),
            )
        if (interval.endUs - interval.startUs == duration) return interval
        val a = maxOf(window.startUs, interval.startUs)
        val b = minOf(window.endUs, interval.endUs)
        if (b <= a || duration <= 0) return null
        // Recovery mapping clips collection time while retaining the original interval duration.
        val consumption = EnergyReducer("active", b - a, "phone", interval.origin.dataOrigin)
        consumption.append(EnergyEvidence.IntervalRecord("active", interval.copy(startUs = 0, endUs = duration)))
        return consumption.valueQ()?.let { q ->
            interval.copy(startUs = a, endUs = b, valueKcal = FixedPoint.divide(q, 1000000))
        }
    }

    private fun heart(row: Observation, window: AnalysisRange) =
        HeartRateEvidence(
            origin(row),
            Source.phone,
            CoreAssociation.RideWindow(window.startUs, window.endUs),
            row.timestamp,
            row.evidence.str("originalEndTimestamp"),
            row.elapsedUs,
            canonicalInt64(row.evidence.getValue("endUs")!!),
            row.queryUs,
            row.elapsedUs,
            Continuity(interruptionIndex = row.segment),
            row.evidence.str("representation"),
            row.evidence["logicalKey"] as? String,
            1,
            (row.evidence["value"] as? Number)?.toDouble(),
            row.evidence.str("unit"),
            row.revision,
        )

    private fun preparation(id: String): Pair<HeartRateOriginPreparation, EnergyOriginPreparation> {
        val window = AnalysisRange(0, elapsedUs(store.timing(id).elapsed))
        val revision = store.readRevision(id)
        prepared
            ?.takeIf { it.id == id && it.revision == revision && it.endUs == window.endUs }
            ?.let {
                return it.value
            }
        val hr = HeartRateOriginPreparation("phone", window)
        val active = EnergyOriginPreparation("phone", window)
        store.healthOriginalRows(id, measures = listOf("heartRate", "activeEnergy")) { row ->
            if (row.evidence["collectionMapped"] == false) return@healthOriginalRows
            when (row.evidence["measure"]) {
                "heartRate" -> hr.append(heart(row, window))
                "activeEnergy" -> {
                    val value = interval(row, window) ?: return@healthOriginalRows
                    active.append("active", value)
                }
            }
        }
        return Pair(hr, active).also { prepared = Prepared(id, revision, window.endUs, it) }
    }

    fun origins(id: String): Map<String, String?> =
        store.healthOrigins(id) {
            val (hr, active) = preparation(id)
            mapOf(
                "heartRate" to hr.selected()?.dataOrigin,
                "activeEnergy" to active.selected()?.dataOrigin,
            )
        }

    fun selections(id: String, selected: Map<String, String?> = origins(id)): List<HealthSelection> {
        val (hr, active) = preparation(id)
        val scores =
            mapOf(
                "heartRate" to hr.selected(),
                "activeEnergy" to active.selected(),
            )
        return selected.map { (metric, dataOrigin) ->
            val score = scores[metric]
            check(score?.dataOrigin == dataOrigin)
            HealthSelection(
                when (metric) {
                    "heartRate" -> "heartRateBpm"
                    "activeEnergy" -> "active"
                    else -> error("Unsupported Android Health selection")
                },
                Source.phone,
                dataOrigin,
                score?.coveredUs ?: 0,
                emptyList(),
                CoreAssociation.RideWindow(0, elapsedUs(store.timing(id).elapsed)),
            )
        }
    }

    fun predicate(id: String, metric: String): String {
        val measure =
            when (metric) {
                "heartRateBpm" -> "heartRate"
                "activeEnergyKcal" -> "activeEnergy"
                else -> return "AND 0"
            }
        val dataOrigin = origins(id)[measure] ?: return "AND 0"
        val escaped = dataOrigin.replace("'", "''")
        var clause =
            "AND json_extract(o.evidence,'$.measure')='$measure' AND json_extract(o.evidence,'$.origin.dataOrigin')='$escaped'"
        if (metric == "heartRateBpm") {
            val raw = rawHeartPresent(id, dataOrigin)
            if (raw) clause += " AND json_extract(o.evidence,'$.representation') IN ('rawQuantity','rawSeries')"
        }
        return clause
    }

    private fun rawHeartPresent(id: String, dataOrigin: String): Boolean {
        val revision = store.readRevision(id)
        var after = 0L
        while (true) {
            val candidates =
                "SELECT o.id,o.ride,o.event_id,o.revision FROM observations o WHERE o.ride=? AND o.kind='health' AND json_extract(o.evidence,'$.measure')='heartRate' AND json_extract(o.evidence,'$.origin.dataOrigin')=? AND json_extract(o.evidence,'$.representation') IN ('rawQuantity','rawSeries') AND o.id>? ORDER BY o.id LIMIT 128"
            val args = arrayOf<Any>(id, dataOrigin, after)
            val present =
                store.select(
                    "SELECT page.id,1 AS health_selected FROM ($candidates) page WHERE page.revision<=$revision ${store.selectedPredicate("page")} LIMIT 1",
                    args,
                ) {
                    it.moveToFirst()
                }
            if (present) return true
            after =
                store.select("SELECT max(id) FROM ($candidates)", args) { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                } ?: return false
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun summary(id: String): Payload {
        val distance = RideDistance(store)
        val summary = RideDetail(store, distance).detail(id, "auto")["summary"] as Payload
        val fields =
            listOf(
                "averageHeartRateBpm",
                "maximumHeartRateBpm",
                "heartRateCoveredSeconds",
                "activeEnergyKcal",
                "healthOrigins",
                "physiologyProvenance",
            )
        return fields.mapNotNull { key -> summary[key]?.let { key to it } }.toMap()
    }

    companion object {
        fun origin(row: Observation): CoreOrigin {
            return origin(row.evidence["origin"] as Map<*, *>)
        }

        fun origin(h: Map<*, *>): CoreOrigin {
            return CoreOrigin(
                Provider.healthConnect,
                h["dataOrigin"] as String,
                h["providerObservationId"] as String,
                h["originEventId"] as String,
                Source.valueOf(h["producer"] as String),
                canonicalInt64(h["sequence"]!!),
                h["originRevision"] as? String,
            )
        }
    }
}
